package com.aisub.player.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWatchdogTest {

    @Test
    fun noAudio() {
        val w = LiveWatchdog(0, null, streaming = false, onDevice = false)
        assertNull(w.hint(10_000))
        assertTrue(w.hint(21_000)!!.startsWith("21 秒还没收到直播的声音"))
    }

    @Test
    fun audioButNoSentence() {
        val w = LiveWatchdog(0, "韩语", streaming = true, onDevice = false)
        for (t in 1_000L..50_000L step 1_000) w.audio(t)
        val hint = w.hint(50_000)!!
        assertTrue(hint.startsWith("已经听了 49 秒，还没识别出一句话"))
        assertTrue("现在是韩语" in hint)
        assertTrue("流式识别要先在控制台开通" in hint)
        w.cue(50_000)
        assertNull(w.hint(50_500))
    }

    @Test
    fun audioDroppedAndQuiet() {
        val w = LiveWatchdog(0, null, streaming = false, onDevice = true)
        w.audio(1_000)
        w.cue(5_000)
        assertEquals("直播的声音断了，正在重连…", w.hint(17_000))
        for (t in 17_000L..100_000L step 1_000) w.audio(t)
        assertEquals("最近 95 秒没有识别出字幕，可能这段没人说话", w.hint(100_000))
    }
}
