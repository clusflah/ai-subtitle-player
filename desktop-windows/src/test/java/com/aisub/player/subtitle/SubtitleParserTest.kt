package com.aisub.player.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleParserTest {
    @Test
    fun parsesSrt() {
        val srt = "﻿1\r\n00:00:01,000 --> 00:00:03,400\r\n<i>Hello</i> there\r\n\r\n2\r\n00:00:03,800 --> 00:00:06,500\r\nSecond\r\nline\r\n"
        val cues = SubtitleParser.parse(srt)
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(3400L, cues[0].endMs)
        assertEquals("Hello there", cues[0].text)
        assertEquals("Second line", cues[1].text)
        assertNull(cues[1].translation)
    }

    @Test
    fun parsesVttAndBilingual() {
        val vtt = "WEBVTT\n\n00:01.500 --> 00:04.000\n你好\nHello\n\n01:02:03.004 --> 01:02:05.000\nOnly one\n"
        val cues = SubtitleParser.parse(vtt)
        assertEquals(1500L, cues[0].startMs)
        assertEquals("Hello", cues[0].text)
        assertEquals("你好", cues[0].translation)
        assertEquals(3_723_004L, cues[1].startMs)
    }

    @Test
    fun roundTripsExportedBilingualSrt() {
        val out = java.io.ByteArrayOutputStream()
        writeSrt(listOf(Cue(1, 500, 2000, "Good morning", "早上好")), SrtMode.BILINGUAL, out)
        val back = SubtitleParser.parse(out.toString("UTF-8"))
        assertEquals("Good morning", back[0].text)
        assertEquals("早上好", back[0].translation)
        assertEquals(500L, back[0].startMs)
    }

    @Test
    fun cueAtPreservesLongDialogueAcrossOverlappingShortSignAndBanner() {
        val cues = listOf(
            Cue(1, 0L, 40_000L, "字幕组压制说明"),
            Cue(2, 10_000L, 18_000L, "我们在火车上接头", translation = null),
            Cue(3, 11_000L, 12_500L, "[京城站]"),
        )
        // 11.5s 时对白与屏幕字同时在场（且 40s 超长横幅自动让位）
        val duringSign = cues.cueAt(11_500L)
        assertNotNull(duringSign)
        assertTrue(duringSign!!.text.contains("我们在火车上接头"))
        assertTrue(duringSign.text.contains("[京城站]"))

        // 12.5s 短屏幕字结束后，10s~18s 的长对白绝对不能消失！
        val afterSign = cues.cueAt(15_000L)
        assertNotNull("Spoken dialogue must remain visible after overlapping short sign cue ends", afterSign)
        assertEquals("我们在火车上接头", afterSign!!.text)
    }

    @Test
    fun parsesAssDualDialogueWithSlightTimestampOffsetAndInlineDrawing() {
        val ass = """
            [Events] ; bilingual tracks
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:05.10,0:00:08.20,Chn,,0,0,0,,{\p1}m 0 0 l 8 0 8 8 0 8{\p0}去见 Captain Miller 吧
            Dialogue: 0,0:00:05.18,0:00:08.25,Eng,,0,0,0,,Go see Captain Miller
        """.trimIndent()
        val cues = SubtitleParser.parse(ass)
        assertEquals(1, cues.size)
        assertEquals("Go see Captain Miller", cues[0].text)
        assertEquals("去见 Captain Miller 吧", cues[0].translation)
    }

    @Test
    fun parsesSamiSmiKoreanAndChinese() {
        val smi = """
            <SAMI>
            <BODY>
            <SYNC Start=2000><P Class=ZHCC>密探接头暗号<br>암호 확인</P>
            <SYNC Start=4500><P Class=ZHCC>&nbsp;</P>
            </BODY>
            </SAMI>
        """.trimIndent()
        val cues = SubtitleParser.parse(smi)
        assertEquals(1, cues.size)
        assertEquals(2000L, cues[0].startMs)
        assertEquals(4500L, cues[0].endMs)
        assertEquals("암호 확인", cues[0].text)
        assertEquals("密探接头暗号", cues[0].translation)
    }
}
