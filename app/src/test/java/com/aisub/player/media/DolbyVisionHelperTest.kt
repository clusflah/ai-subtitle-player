package com.aisub.player.media

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionHelperTest {

    @Test
    fun detectsProfile5FromCodecsAndDvccBox() {
        val mp4P5 = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvh1.05.06")
            .build()
        assertTrue(DolbyVisionHelper.isDolbyVision(mp4P5))
        assertEquals(5, DolbyVisionHelper.parseProfile(mp4P5))
        assertTrue(DolbyVisionHelper.isProfile5(mp4P5))
        assertFalse(DolbyVisionHelper.hasHdrBaseLayerFallback(mp4P5))

        // Raw dvcC record: major=1, minor=0, profile=5 -> byte[2] = (5 << 1) = 0x0A
        val mkvDvccP5 = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setInitializationData(listOf(byteArrayOf(1, 0, 0x0A, 0x18)))
            .build()
        assertTrue(DolbyVisionHelper.isDolbyVision(mkvDvccP5))
        assertEquals(5, DolbyVisionHelper.parseProfile(mkvDvccP5))
        assertTrue(DolbyVisionHelper.isProfile5(mkvDvccP5))
    }

    @Test
    fun distinguishesProfile7And8WithBaseLayerFromProfile5() {
        val p8 = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.08.06")
            .build()
        assertTrue(DolbyVisionHelper.isDolbyVision(p8))
        assertEquals(8, DolbyVisionHelper.parseProfile(p8))
        assertFalse(DolbyVisionHelper.isProfile5(p8))
        assertTrue(DolbyVisionHelper.hasHdrBaseLayerFallback(p8))

        val p7 = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.07.06")
            .build()
        assertEquals(7, DolbyVisionHelper.parseProfile(p7))
        assertFalse(DolbyVisionHelper.isProfile5(p7))
        assertTrue(DolbyVisionHelper.hasHdrBaseLayerFallback(p7))
    }

    @Test
    fun ignoresStandardHevcAndAvcFormats() {
        val hevc = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hev1.1.6.L120.90")
            .build()
        assertFalse(DolbyVisionHelper.isDolbyVision(hevc))
        assertNull(DolbyVisionHelper.parseProfile(hevc))
        assertFalse(DolbyVisionHelper.isProfile5(hevc))
    }

    @Test
    fun detectsDolbyVisionTitlesAccurately() {
        assertTrue(DolbyVisionHelper.looksLikeDolbyVisionTitle("Dune.Part.Two.2024.2160p.WEB-DL.DV.H265.mkv"))
        assertTrue(DolbyVisionHelper.looksLikeDolbyVisionTitle("Severance.S02E01.DoVi.P5.mp4"))
        assertTrue(DolbyVisionHelper.looksLikeDolbyVisionTitle("Sample.Dolby.Vision.2160p.mp4"))
        assertFalse(DolbyVisionHelper.looksLikeDolbyVisionTitle("Advisor.2024.1080p.WEB-DL.x264.mkv"))
    }
}
