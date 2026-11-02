package com.aisub.player.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramUriTest {
    @Test
    fun roundTrip() {
        // 频道和超级群的会话 ID 是负数
        val uri = Telegram.uriOf(-1001234567890, 4194304)
        assertEquals("tg://msg/-1001234567890/4194304", uri)
        assertTrue(Telegram.isTelegram(uri))
        assertEquals(-1001234567890L to 4194304L, Telegram.parse(uri))
    }

    @Test
    fun rejectsOthers() {
        assertFalse(Telegram.isTelegram("https://t.me/abc/1"))
        assertNull(Telegram.parse("tg://msg/abc/1"))
        assertNull(Telegram.parse("tg://msg/-100"))
    }
}
