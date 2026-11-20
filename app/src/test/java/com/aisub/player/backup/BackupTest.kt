package com.aisub.player.backup

import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        val file = Backup.parse("\uFEFF" + text)
        assertEquals(setOf(BackupSection.SETTINGS, BackupSection.LIVE), file.sections.keys)
        assertEquals("2 个密钥 / 账号", file.summary(BackupSection.SETTINGS))
        assertEquals("1 个直播源", file.summary(BackupSection.LIVE))
    }

    @Test
    fun pbkdf2HmacSha256MatchesStandardTestVectors() {
        // Known PBKDF2-HMAC-SHA256 test vectors (password="password", salt="salt")
        val dk1 = BackupCrypto.pbkdf2HmacSha256("password".toByteArray(), "salt".toByteArray(), 1, 32)
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", dk1.toByteString().hex())
        val dk2 = BackupCrypto.pbkdf2HmacSha256("password".toByteArray(), "salt".toByteArray(), 2, 32)
        assertEquals("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43", dk2.toByteString().hex())
    }

    @Test
    fun encryptedBackupRoundTripAndFailureCases() {
        val tgSection = JSONObject()
            .put("telegram", PrefsCodec.encode(mapOf("apiId" to 123456, "apiHash" to "super_secret_hash", "pinned" to "1001,1002")))
            .put(Backup.KEY_TELEGRAM_DB, JSONObject().put("encoding", "zip-base64").put("fileCount", 2).put("rawBytes", 2048L).put("data", "UEs="))
        val plain = JSONObject()
            .put("format", Backup.FORMAT)
            .put("version", Backup.VERSION)
            .put("device", "TestPhone")
            .put("exportedAt", 1_760_000_000_000L)
            .put("sections", JSONObject().put(BackupSection.TELEGRAM.name, tgSection))
            .toString()

        val encrypted = BackupCrypto.encrypt(plain, "MyStrongPass!99", "TestPhone", 1_760_000_000_000L)
        assertFalse("密文不得包含明文 apiHash", encrypted.contains("super_secret_hash"))
        assertFalse("密文不得包含明文 sections", encrypted.contains("\"sections\""))

        // 校验外壳与预检查
        Backup.validateEnvelope(encrypted)
        val env = Backup.inspect(encrypted)
        assertTrue(env is BackupEnvelope.Encrypted)
        val encEnv = env as BackupEnvelope.Encrypted
        assertEquals("TestPhone", encEnv.device)
        assertEquals(1_760_000_000_000L, encEnv.exportedAt)

        // 缺少密码 -> BackupPasswordRequiredException
        val missingPwd = runCatching { Backup.parse(encrypted) }.exceptionOrNull()
        assertTrue(missingPwd is BackupPasswordRequiredException)

        // 错误密码 -> BackupWrongPasswordException
        val wrongPwd = runCatching { Backup.parse(encrypted, "WrongPass") }.exceptionOrNull()
        assertTrue(wrongPwd is BackupWrongPasswordException)

        // 篡改密文 -> BackupWrongPasswordException
        val tamperedObj = JSONObject(encrypted)
        val ct = tamperedObj.getString("ciphertext")
        val flipped = (if (ct[0] == 'A') 'B' else 'A') + ct.substring(1)
        tamperedObj.put("ciphertext", flipped)
        val tamperedErr = runCatching { Backup.parse(tamperedObj.toString(), "MyStrongPass!99") }.exceptionOrNull()
        assertTrue(tamperedErr is BackupWrongPasswordException)

        // 正确密码 -> 还原完整内容
        val decrypted = Backup.parse(encrypted, "MyStrongPass!99")
        assertTrue(decrypted.encrypted)
        assertEquals("TestPhone", decrypted.device)
        assertEquals(setOf(BackupSection.TELEGRAM), decrypted.sections.keys)
        val summary = decrypted.summary(BackupSection.TELEGRAM)
        assertTrue(summary.contains("API 凭证"))
        assertTrue(summary.contains("登录会话"))
        assertTrue(summary.contains("2 个置顶"))
    }

    @Test
    fun mediaLibrarySummaryHistoryMergeAndServerIdRemap() {
        val oldHist = JSONArray().put(
            JSONObject().put("entry", JSONObject().put("uri", "http://nas/a.mkv")).put("position", 1000L).put("playedAt", 100L),
        ).toString()
        val newHist = JSONArray()
            .put(JSONObject().put("entry", JSONObject().put("uri", "http://nas/a.mkv")).put("position", 5000L).put("playedAt", 200L))
            .put(JSONObject().put("entry", JSONObject().put("uri", "http://nas/b.mkv")).put("position", 3000L).put("playedAt", 150L))
            .toString()
        val mergedHist = JSONArray(Backup.mergeList("library", "history", oldHist, newHist))
        assertEquals(2, mergedHist.length())
        assertEquals(5000L, mergedHist.getJSONObject(0).getLong("position"))
        assertEquals("http://nas/b.mkv", mergedHist.getJSONObject(1).getJSONObject("entry").getString("uri"))

        // 服务器 ID 映射并修正媒体库绑定
        val tvSrv = JSONObject().put("id", "local-srv-1").put("type", "WEBDAV").put("name", "NAS").put("baseUrl", "http://nas/dav").put("username", "u")
        val phoneSrv = JSONObject().put("id", "phone-srv-9").put("type", "WEBDAV").put("name", "NAS").put("baseUrl", "http://nas/dav/").put("username", "u")
        val (_, idMap) = Backup.mergeServersWithIdMap(JSONArray().put(tvSrv).toString(), JSONArray().put(phoneSrv).toString())
        assertEquals(mapOf("phone-srv-9" to "local-srv-1"), idMap)

        val customLibs = JSONObject()
            .put(
                "libraries",
                JSONArray().put(
                    JSONObject().put("id", "lib1").put("name", "电影").put(
                        "folders",
                        JSONArray().put(JSONObject().put("serverId", "phone-srv-9").put("path", "/movies")),
                    ),
                ),
            )
            .put(
                "catalogs",
                JSONObject().put(
                    "lib1",
                    JSONArray().put(
                        JSONObject().put("id", "m1").put("title", "Demo").put(
                            "episodes",
                            JSONArray().put(JSONObject().put("serverId", "phone-srv-9").put("playUrl", "http://nas/dav/movies/a.mkv")),
                        ),
                    ),
                ),
            )
        val remapped = Backup.remapCustomLibrariesServerIds(customLibs, idMap)
        assertEquals("local-srv-1", remapped.getJSONArray("libraries").getJSONObject(0).getJSONArray("folders").getJSONObject(0).getString("serverId"))
        assertEquals("local-srv-1", remapped.getJSONObject("catalogs").getJSONArray("lib1").getJSONObject(0).getJSONArray("episodes").getJSONObject(0).getString("serverId"))

        val mlSection = JSONObject()
            .put(Backup.KEY_CUSTOM_LIBRARIES, remapped)
            .put("library", PrefsCodec.encode(mapOf("history" to newHist)))
        val bf = BackupFile("PC", 1L, mapOf(BackupSection.MEDIA_LIBRARY to mlSection))
        assertEquals("1 个媒体库（1 部影片）、2 条观看进度", bf.summary(BackupSection.MEDIA_LIBRARY))
    }

    @Test
    fun telegramDbPackUnpackRoundTripAndZipSlipBlocked() {
        val filtered = Backup.filterTelegramPrefs(mapOf("apiId" to 1, "apiHash" to "h", "pinned" to "10", "stats_10" to "5,5,0,0"))
        assertEquals(setOf("apiId", "apiHash", "pinned"), filtered.keys)

        val srcDir = Files.createTempDirectory("voxtitle-tg-src").toFile()
        val dstDir = Files.createTempDirectory("voxtitle-tg-dst").toFile()
        try {
            val binlogBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            val sqliteBytes = "SQLite format 3\u0000mock-session".toByteArray()
            File(srcDir, "td.binlog").writeBytes(binlogBytes)
            File(srcDir, "db.sqlite").writeBytes(sqliteBytes)
            File(srcDir, "td.binlog.lock").writeText("lock")
            File(srcDir, "stale.tmp").writeText("tmp")

            val packed = Backup.packTelegramDb(srcDir)
            assertNotNull(packed)
            assertEquals(2, packed!!.getInt("fileCount"))

            // 目标目录已有旧文件时应被完整替换为备份中的会话文件
            File(dstDir, "old.sqlite").writeText("old")
            Backup.unpackTelegramDb(packed, dstDir)
            assertFalse(File(dstDir, "old.sqlite").exists())
            assertFalse(File(dstDir, "td.binlog.lock").exists())
            assertArrayEquals(binlogBytes, File(dstDir, "td.binlog").readBytes())
            assertArrayEquals(sqliteBytes, File(dstDir, "db.sqlite").readBytes())

            // 构造恶意路径穿越 ZIP，必须拒绝
            val evilBaos = ByteArrayOutputStream()
            ZipOutputStream(evilBaos).use { zos ->
                zos.putNextEntry(ZipEntry("../escape.txt"))
                zos.write("bad".toByteArray())
                zos.closeEntry()
            }
            val evilObj = JSONObject().put("data", evilBaos.toByteArray().toByteString().base64())
            val err = runCatching { Backup.unpackTelegramDb(evilObj, dstDir) }.exceptionOrNull()
            assertNotNull(err)
            assertFalse(File(dstDir.parentFile, "escape.txt").exists())
        } finally {
            srcDir.deleteRecursively()
            dstDir.deleteRecursively()
        }
    }

    private fun src(id: String, name: String, url: String) = JSONObject().put("id", id).put("name", name).put("url", url)
}
