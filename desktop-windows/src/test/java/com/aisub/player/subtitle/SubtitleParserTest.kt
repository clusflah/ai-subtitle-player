package com.aisub.player.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
