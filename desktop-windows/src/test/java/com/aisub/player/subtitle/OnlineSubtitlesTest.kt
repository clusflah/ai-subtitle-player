package com.aisub.player.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OnlineSubtitlesTest {
    private val ass = """
        [Script Info]
        Title: test

        [V4+ Styles]
        Format: Name, Fontname, Fontsize
        Style: Default,Arial,20

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\fad(200,200)}你好，世界\N{\fs14}Hello, world
        Dialogue: 0,0:00:04.00,0:00:05.00,Default,,0,0,0,,{\p1}m 0 0 l 100 0
        Dialogue: 0,0:01:02.03,0:01:04.00,Default,,0,0,0,,Only one line
    """.trimIndent()

    @Test
    fun parsesAssWithBilingualLinesAndSkipsDrawings() {
        val cues = SubtitleParser.parse(ass)
        assertEquals(2, cues.size)
        assertEquals(1500L, cues[0].startMs)
        assertEquals(3000L, cues[0].endMs)
        assertEquals("Hello, world", cues[0].text)
        assertEquals("你好，世界", cues[0].translation)
        assertEquals(62_030L, cues[1].startMs)
        assertEquals("Only one line", cues[1].text)
    }

    @Test
    fun picksSimplifiedChineseFromZip() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, body) in listOf(
                "readme.txt" to "nothing",
                "Movie.cht.srt" to "1\n00:00:01,000 --> 00:00:02,000\n繁體\n",
                "Movie.chs.ass" to ass,
            )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        val text = OnlineSubtitles.decodeSubtitleBytes(out.toByteArray(), "sub.zip")
        assertTrue(text.contains("[Events]"))
    }

    @Test
    fun decodesUtf16AndGbk() {
        val s = "1\n00:00:01,000 --> 00:00:02,000\n中文字幕\n"
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)
        assertEquals(s, decodeText(utf16))
        assertEquals(s, decodeText(s.toByteArray(charset("GBK"))))
        assertEquals(s, decodeText(s.toByteArray()))
    }
}
