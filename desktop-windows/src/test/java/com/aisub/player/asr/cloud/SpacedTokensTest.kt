package com.aisub.player.asr.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class SpacedTokensTest {
    @Test
    fun latinWordsAreSpacedCjkIsNot() {
        val (tokens, end) = ChunkedCloudEngine.spacedTokens(
            listOf(Triple("Hello", 0L, 100L), Triple("world.", 100L, 200L), Triple("你", 200L, 300L), Triple("好", 300L, 400L))
        )
        assertEquals(listOf("Hello", " world.", "你", "好"), tokens.map { it.text })
        assertEquals(400L, end)
    }
}
