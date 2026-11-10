package com.aisub.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstGraphemeTest {
    @Test
    fun keepsWholeEmoji() {
        assertEquals("🏎️", firstGrapheme("🏎️ Ternus 赛车俱乐部"))
        assertEquals("🍭", firstGrapheme("🍭 lolipop AI"))
        assertEquals("M", firstGrapheme("Mikan番剧"))
        assertEquals("癫", firstGrapheme("  癫影"))
        assertEquals("?", firstGrapheme(""))
    }
}
