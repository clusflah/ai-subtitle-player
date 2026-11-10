package com.aisub.player.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    @Test
    fun prefsValuesKeepTheirTypes() {
        val values = mapOf("s" to "key", "b" to true, "i" to 3, "l" to 5_000_000_000L, "f" to 1.25f, "ss" to setOf("a", "b"))
        val decoded = PrefsCodec.decode(JSONObject(PrefsCodec.encode(values).toString()))
        assertEquals(values, decoded)
    }

    @Test
    fun liveSourcesMergeByUrlKeepingLocalId() {
        val old = JSONArray().put(src("tv-1", "旧名字", "http://a/live.m3u")).put(src("tv-2", "本机的", "http://b/x.txt")).toString()
        val new = JSONArray().put(src("phone-1", "新名字", "http://a/live.m3u ")).put(src("phone-2", "手机的", "http://c/y.m3u")).toString()
        val merged = JSONArray(Backup.mergeList("live", "sources", old, new))
        assertEquals(3, merged.length())
        assertEquals("tv-1", merged.getJSONObject(0).getString("id"))
        assertEquals("新名字", merged.getJSONObject(0).getString("name"))
        assertEquals("本机的", merged.getJSONObject(1).getString("name"))
        assertEquals("phone-2", merged.getJSONObject(2).getString("id"))
    }

    @Test
    fun sameServerIsNotDuplicated() {
        val tv = JSONObject().put("id", "tv").put("type", "WEBDAV").put("name", "NAS").put("baseUrl", "http://nas/dav/").put("username", "me").put("password", "old")
        val phone = JSONObject().put("id", "phone").put("type", "WEBDAV").put("name", "NAS").put("baseUrl", "http://nas/dav").put("username", "me").put("password", "new")
        val drive = JSONObject().put("id", "d").put("type", "ALIYUN").put("name", "阿里云盘").put("baseUrl", "").put("extra", "{\"refresh\":\"x\"}")
        val merged = JSONArray(Backup.mergeList("library", "servers", JSONArray().put(tv).toString(), JSONArray().put(phone).put(drive).toString()))
        assertEquals(2, merged.length())
        assertEquals("tv", merged.getJSONObject(0).getString("id"))
        assertEquals("new", merged.getJSONObject(0).getString("password"))
        assertEquals("ALIYUN", merged.getJSONObject(1).getString("type"))
    }

    @Test
    fun playlistsWithSameNameCombineItems() {
        val old = JSONArray().put(JSONObject().put("id", "1").put("name", "收藏").put("items", JSONArray().put(JSONObject().put("uri", "a")))).toString()
        val new = JSONArray().put(JSONObject().put("id", "2").put("name", "收藏").put("items", JSONArray().put(JSONObject().put("uri", "a")).put(JSONObject().put("uri", "b")))).toString()
        val merged = JSONArray(Backup.mergeList("library", "playlists", old, new)).getJSONObject(0)
        assertEquals("1", merged.getString("id"))
        assertEquals(2, merged.getJSONArray("items").length())
    }

    @Test
    fun otherKeysAreOverwrittenNotMerged() {
        assertNull(Backup.mergeList("playback", "skip.file.x", "[]", "[]"))
        assertNull(Backup.mergeList("settings", "OPENAI.apiKey", null, "sk-1"))
    }

    @Test
    fun parseRejectsOtherFilesAndSummarizes() {
        val bad = runCatching { Backup.parse("{\"hello\":1}") }
        assertTrue(bad.isFailure)
        val settings = PrefsCodec.encode(mapOf("OPENAI.apiKey" to "sk-1", "tmdbKey" to "", "osUsername" to "me", "subtitleScale" to 1f))
        val live = PrefsCodec.encode(mapOf("sources" to JSONArray().put(src("1", "a", "http://a")).toString()))
        val text = JSONObject().put("format", Backup.FORMAT).put("version", 1).put("device", "Pixel").put(
            "sections", JSONObject().put("SETTINGS", JSONObject().put("settings", settings)).put("LIVE", JSONObject().put("live", live)),
        ).toString()
        val file = Backup.parse("﻿" + text)
        assertEquals(setOf(BackupSection.SETTINGS, BackupSection.LIVE), file.sections.keys)
        assertEquals("2 个密钥 / 账号", file.summary(BackupSection.SETTINGS))
        assertEquals("1 个直播源", file.summary(BackupSection.LIVE))
    }

    private fun src(id: String, name: String, url: String) = JSONObject().put("id", id).put("name", name).put("url", url)
}
