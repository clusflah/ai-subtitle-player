package com.aisub.player.subtitle

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 用 ffmpeg 生成的小样本（各 6 秒，带 SRT / mov_text 字幕轨）验证内嵌字幕提取。 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmbeddedSubtitlesTest {

    private fun extract(name: String): List<EmbeddedSubtitles.Track> {
        val file = File(javaClass.classLoader!!.getResource("embedded/$name")!!.toURI())
        return EmbeddedSubtitles.extract(Uri.fromFile(file), FileDataSource.Factory(), isActive = { true }) {}
    }

    @Test
    fun mkvWithTwoSrtTracks() {
        val tracks = extract("test.mkv")
        assertEquals(2, tracks.size)
        val en = tracks[0]
        // Media3 会把语言代码规范成两个字母
        assertEquals("en", en.language)
        assertEquals(listOf("And then after a long silence,", "Mike just says the word crickets."), en.cues.map { it.text })
        assertEquals(1000L, en.cues[0].startMs)
        assertEquals(2500L, en.cues[0].endMs)
        assertEquals(4800L, en.cues[1].endMs)
        // 中英双语字幕轨拆成原文和译文
        val bilingual = tracks[1].cues.single()
        assertEquals("And then after a long silence,", bilingual.text)
        assertEquals("然后过了好一阵沉默，", bilingual.translation)
        assertTrue(tracks.none { it.imageBased })
    }

    @Test
    fun mp4WithMovText() {
        val tracks = extract("test.mp4")
        assertEquals(1, tracks.size)
        assertEquals(listOf("And then after a long silence,", "Mike just says the word crickets."), tracks[0].cues.map { it.text })
        // MP4 的时间会被音频编码器的前置填充推后几十毫秒
        assertTrue(tracks[0].cues[1].startMs in 3000L..3100L)
    }

    @Test
    fun noSubtitleTrack() {
        assertTrue(extract("nosub.mkv").isEmpty())
    }
}
