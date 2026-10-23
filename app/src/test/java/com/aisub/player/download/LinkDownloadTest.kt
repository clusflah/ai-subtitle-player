package com.aisub.player.download

import com.aisub.player.subtitle.Cue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkDownloadTest {

    @Test
    fun extractsUrlFromSharedText() {
        assertEquals("https://youtu.be/abc123?si=x", extractUrl("Check this out https://youtu.be/abc123?si=x"))
        assertEquals(
            "https://b23.tv/AbCdEf",
            extractUrl("【标题】一段视频-哔哩哔哩】 https://b23.tv/AbCdEf"),
        )
        assertEquals("https://www.youtube.com/watch?v=1", extractUrl("看这个：https://www.youtube.com/watch?v=1，很好笑"))
        assertNull(extractUrl("没有链接"))
    }

    private fun info(language: String?, manual: List<String>, auto: List<String>) = JSONObject().apply {
        if (language != null) put("language", language)
        put("subtitles", JSONObject().apply { manual.forEach { put(it, JSONArray().put(JSONObject().put("ext", "vtt"))) } })
        put("automatic_captions", JSONObject().apply { auto.forEach { put(it, JSONArray().put(JSONObject().put("ext", "vtt"))) } })
    }

    @Test
    fun prefersManualSubtitleInOriginalLanguage() {
        val c = SubtitleChoice.pick(info("en", listOf("de", "en-US", "zh-Hans"), listOf("en-orig", "fr")), "zh-CN")
        assertEquals("en-US", c.source)
        assertFalse(c.sourceIsAuto)
        // 平台有简体中文人工字幕，直接当译文
        assertEquals("zh-Hans", c.target)
        assertEquals(listOf("en-US", "zh-Hans"), c.languages)
        assertTrue(c.manual)
        assertFalse(c.auto)
    }

    @Test
    fun fallsBackToOriginalAutoCaptions() {
        val c = SubtitleChoice.pick(info("en", emptyList(), listOf("af", "en", "en-orig", "zh-Hans")), "zh-CN")
        assertEquals("en-orig", c.source)
        assertTrue(c.sourceIsAuto)
        // 自动翻译的字幕质量差，不当译文
        assertNull(c.target)
    }

    @Test
    fun ignoresDanmakuAndHandlesUnknownLanguage() {
        val c = SubtitleChoice.pick(info(null, listOf("danmaku", "live_chat"), listOf("ja-orig", "en")), "zh-CN")
        assertEquals("ja-orig", c.source)
        assertTrue(c.sourceIsAuto)
    }

    @Test
    fun videoAlreadyInTargetLanguage() {
        val c = SubtitleChoice.pick(info("zh", listOf("zh-Hans"), emptyList()), "zh-CN")
        assertEquals("zh-Hans", c.source)
        assertNull(c.target)
        // 简体目标不匹配繁体字幕
        assertFalse(SubtitleChoice.matchesTarget("zh-Hant", "zh-CN"))
        assertTrue(SubtitleChoice.matchesTarget("zh-Hant", "zh-TW"))
    }

    @Test
    fun noSubtitlesAtAll() {
        val c = SubtitleChoice.pick(info("en", emptyList(), emptyList()), "zh-CN")
        assertNull(c.source)
        assertTrue(c.languages.isEmpty())
    }

    @Test
    fun cleansRollingAutoCaptions() {
        // yt-dlp 把 YouTube 自动字幕转成 SRT 后的典型样子
        val raw = listOf(
            Cue(1, 0, 2000, "so today we are"),
            Cue(2, 2000, 2010, "so today we are"),
            Cue(3, 2010, 4000, "so today we are going to talk about"),
            Cue(4, 4000, 4010, "going to talk about"),
            Cue(5, 4010, 6000, "going to talk about cats and dogs"),
        )
        val cleaned = PlatformSubtitles.cleanRolling(raw)
        assertEquals(listOf("so today we are", "going to talk about", "cats and dogs"), cleaned.map { it.text })
        assertEquals(listOf(1, 2, 3), cleaned.map { it.id })
    }

    @Test
    fun mergesTargetSubtitlesByTime() {
        val source = listOf(Cue(1, 1000, 3000, "Hello there."), Cue(2, 3500, 5000, "How are you?"), Cue(3, 9000, 10000, "Bye."))
        val target = listOf(Cue(1, 1100, 2900, "你好。"), Cue(2, 3400, 5100, "你好吗？"))
        val merged = PlatformSubtitles.mergeTranslation(source, target)
        assertEquals(listOf("你好。", "你好吗？", null), merged.map { it.translation })
    }
}
