package com.aisub.player.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {

    @Test
    fun parsesTimedLines() {
        val l = Lyrics.parse(
            """
            [ti:晴天]
            [ar:周杰伦]
            [00:12.34]故事的小黄花
            [00:15.5]从出生那年就飘着
            [01:02:300]第三行
            """.trimIndent(),
        )!!
        assertTrue(l.synced)
        assertEquals(listOf(12_340L, 15_500L, 62_300L), l.lines.map { it.timeMs })
        assertEquals("故事的小黄花", l.lines[0].text)
    }

    @Test
    fun repeatedTimesAndOrder() {
        val l = Lyrics.parse("[00:30.00][00:10.00]副歌\n[00:20.00]主歌")!!
        assertEquals(listOf("副歌", "主歌", "副歌"), l.lines.map { it.text })
        assertEquals(listOf(10_000L, 20_000L, 30_000L), l.lines.map { it.timeMs })
    }

    @Test
    fun offsetAndWordTimes() {
        // offset 为正：歌词提前
        val l = Lyrics.parse("[offset:500]\n[00:10.00]<00:10.00>逐<00:10.50>字")!!
        assertEquals(9_500L, l.lines[0].timeMs)
        assertEquals("逐字", l.lines[0].text)
    }

    @Test
    fun plainText() {
        val l = Lyrics.parse("第一行\n第二行\n")!!
        assertFalse(l.synced)
        assertEquals(2, l.lines.size)
        assertEquals(-1, l.indexAt(5000))
        assertNull(Lyrics.parse("  \n[ar:someone]\n"))
    }

    @Test
    fun indexAtFollowsPosition() {
        val l = Lyrics.parse("[00:10.00]a\n[00:20.00]b\n[00:30.00]c")!!
        assertEquals(-1, l.indexAt(5_000))
        assertEquals(0, l.indexAt(10_000))
        assertEquals(1, l.indexAt(29_999))
        assertEquals(2, l.indexAt(99_000))
    }

    @Test
    fun roundTripsThroughLrc() {
        val l = Lyrics.parse("[01:05.40]hello\n[00:01.00]start")!!
        assertEquals(l.lines, Lyrics.parse(l.toLrc())!!.lines)
    }

    @Test
    fun scoring() {
        val tags = TrackTags("晴天", "周杰伦", durationMs = 269_000)
        val exact = SongCandidate("x", "1", "晴天", "周杰伦", "叶惠美", 269_500)
        val live = SongCandidate("x", "2", "晴天 (Live)", "周杰伦", "", 300_000)
        val other = SongCandidate("x", "3", "雨天", "孙燕姿", "", 269_000)
        assertTrue(MusicLookup.score(exact, tags) > MusicLookup.score(live, tags))
        assertEquals(0, MusicLookup.score(other, tags))
    }

    @Test
    fun badTagValues() {
        assertNull(TrackTags.valid("??"))
        assertNull(TrackTags.valid("？？ "))
        assertNull(TrackTags.valid("未知艺术家"))
        assertNull(TrackTags.valid("Track 03"))
        assertEquals("光亮", TrackTags.valid(" 光亮 "))
        // GBK 被当成 Latin-1 读出来的乱码转回中文
        val garbled = String("光亮".toByteArray(java.nio.charset.Charset.forName("GBK")), Charsets.ISO_8859_1)
        assertEquals("光亮", TrackTags.valid(garbled))
        // 真正的西文不动
        assertEquals("Café", TrackTags.valid("Café"))
    }

    @Test
    fun fileNames() {
        assertEquals("光亮" to "周深", TrackTags.parseFileName("周深 - 光亮.mp3"))
        assertEquals("光亮" to "周深", TrackTags.parseFileName("03. 周深 - 光亮 [FLAC].flac"))
        assertEquals("晴天" to null, TrackTags.parseFileName("01 晴天.mp3"))
        // 坏标签退回文件名
        val t = TrackTags.fromMetadata("周深 - 光亮.mp3", 0, emptyList())
        assertEquals("光亮", t.title)
        assertEquals("周深", t.artist)
    }

    @Test
    fun usltFrame() {
        // 编码 3（UTF-8）+ 语言 chi + 空描述 + 正文
        val data = byteArrayOf(3) + "chi".toByteArray() + byteArrayOf(0) + "[00:01.00]你好".toByteArray()
        assertEquals("[00:01.00]你好", TrackTags.parseUslt(data))
    }
}
