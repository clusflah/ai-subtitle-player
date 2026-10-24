package com.aisub.player.media

import com.aisub.player.subtitle.LineStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStoreTest {

    @Test
    fun episodesOfOneShowShareKey() {
        val a = PlaybackStore.seriesKey(MediaEntry("u1", "Some.Show.S01E03.1080p.mkv"))
        val b = PlaybackStore.seriesKey(MediaEntry("u2", "Some Show S01E04 WEB-DL.mkv"))
        assertEquals(a, b)
        assertNull(PlaybackStore.seriesKey(MediaEntry("u3", "The.Matrix.1999.1080p.mkv")))
    }

    @Test
    fun outroMeasuredFromEndResolvesPerEpisode() {
        // 片尾记为离结尾 90～0 秒
        val r = SkipRange(90_000, 0, SkipKind.OUTRO, SkipSource.USER, fromEnd = true, series = true)
        val resolved = r.resolve(1_440_000)!!
        assertEquals(1_350_000, resolved.startMs)
        assertEquals(1_440_000, resolved.endMs)
        assertNull(r.resolve(0))
    }

    @Test
    fun lineStyleRoundTrip() {
        val s = LineStyle(1.25f, 0xFFFFE066, 0f, false)
        assertEquals(s, LineStyle.decode(s.encode(), LineStyle.TRANSLATION))
        assertEquals(LineStyle.ORIGINAL, LineStyle.decode("garbage", LineStyle.ORIGINAL))
        assertEquals(LineStyle.ORIGINAL, LineStyle.decode(null, LineStyle.ORIGINAL))
    }
}
