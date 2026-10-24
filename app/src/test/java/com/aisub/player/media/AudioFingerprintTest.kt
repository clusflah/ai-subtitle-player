package com.aisub.player.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class AudioFingerprintTest {
    private val rate = AudioFingerprint.SAMPLE_RATE

    /** 带一点起伏的噪声，像音乐一样各频带能量随时间变化 */
    private fun noise(seconds: Int, seed: Int): FloatArray {
        val r = Random(seed)
        var level = 0.3f
        return FloatArray(seconds * rate) { i ->
            if (i % 800 == 0) level = 0.1f + r.nextFloat() * 0.5f
            (r.nextFloat() * 2 - 1) * level
        }
    }

    private fun near(expected: Long, actual: Long, tolerance: Long = 1_000) =
        assertEquals("expected ≈$expected, got $actual", true, abs(expected - actual) <= tolerance)

    @Test
    fun findsSharedIntroAtDifferentOffsets() {
        val intro = noise(40, 1)
        // a：20 秒前情提要 + 片头 + 正片；b：5 秒 + 片头 + 正片
        val a = noise(20, 2) + intro + noise(60, 3)
        val b = noise(5, 4) + intro + noise(50, 5)
        val m = AudioFingerprint.longestCommon(AudioFingerprint.compute(a), AudioFingerprint.compute(b))
        assertNotNull(m)
        near(20_000, m!!.aStartMs)
        near(60_000, m.aEndMs)
        near(5_000, m.bStartMs)
        near(45_000, m.bEndMs)
    }

    @Test
    fun unrelatedAudioHasNoMatch() {
        val m = AudioFingerprint.longestCommon(AudioFingerprint.compute(noise(90, 7)), AudioFingerprint.compute(noise(90, 8)))
        assertNull(m)
    }

    @Test
    fun silenceDoesNotCountAsShared() {
        val silence = FloatArray(40 * rate)
        val a = noise(10, 9) + silence + noise(30, 10)
        val b = noise(10, 11) + silence + noise(30, 12)
        assertNull(AudioFingerprint.longestCommon(AudioFingerprint.compute(a), AudioFingerprint.compute(b)))
    }
}
