package com.aisub.player.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSubtitleTest {
    // 测试里简单按有没有汉字判断是不是中文
    private val isChinese: (String) -> Boolean = { t -> t.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } }

    private fun decide(cues: List<Cue> = emptyList(), tracks: List<EmbeddedTrackInfo> = emptyList()) =
        QuickSubtitles.decide(cues, tracks, "zh-CN", isChinese)

    private fun cue(id: Int, text: String, translation: String? = null) = Cue(id, id * 1000L, id * 1000L + 900, text, translation)

    @Test
    fun noSubtitlesGenerates() {
        assertEquals(QuickSubtitle.GENERATE, decide())
    }

    @Test
    fun chineseEmbeddedTrackHidesButton() {
        // 截图里的情况：8 条中文图形字幕
        val pgs = List(8) { EmbeddedTrackInfo("zh", null, imageBased = true) }
        assertNull(decide(tracks = pgs))
        assertNull(decide(tracks = listOf(EmbeddedTrackInfo("chi", null, false))))
        assertNull(decide(tracks = listOf(EmbeddedTrackInfo(null, "简体中文 - [chs]", false))))
    }

    @Test
    fun foreignTextTrackTranslates() {
        assertEquals(QuickSubtitle.TRANSLATE_EMBEDDED, decide(tracks = listOf(EmbeddedTrackInfo("en", "English", false))))
    }

    @Test
    fun foreignImageTrackGenerates() {
        // 图形字幕没有文字，只能识别
        assertEquals(QuickSubtitle.GENERATE, decide(tracks = listOf(EmbeddedTrackInfo("en", null, true))))
    }

    @Test
    fun loadedSubtitles() {
        assertEquals(QuickSubtitle.TRANSLATE_LOADED, decide(cues = listOf(cue(1, "Hello"), cue(2, "How are you"))))
        assertNull(decide(cues = listOf(cue(1, "你好"), cue(2, "最近怎么样"))))
        assertNull(decide(cues = listOf(cue(1, "Hello", "你好"))))
        // 翻译了一半（例如中途失败）可以接着翻
        assertEquals(QuickSubtitle.TRANSLATE_LOADED, decide(cues = listOf(cue(1, "Hello", "你好"), cue(2, "Bye"))))
    }

    @Test
    fun targetTrackMatching() {
        assertTrue(QuickSubtitles.isTargetTrack(EmbeddedTrackInfo("zh-Hant", null, false), "zh-CN"))
        assertTrue(QuickSubtitles.isTargetTrack(EmbeddedTrackInfo("und", "Chinese", false), "zh-CN"))
        assertFalse(QuickSubtitles.isTargetTrack(EmbeddedTrackInfo("en", "Chinese commentary", false), "zh-CN"))
        assertFalse(QuickSubtitles.isTargetTrack(EmbeddedTrackInfo("zh", null, false), null))
        assertTrue(QuickSubtitles.isTargetTrack(EmbeddedTrackInfo("eng", null, false), "en"))
    }
}
