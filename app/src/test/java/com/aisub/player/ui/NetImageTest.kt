package com.aisub.player.ui

import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NetImageTest {

    private fun jpegWithExifOrientation(order: ByteOrder, orientation: Int): ByteArray {
        val tiff = ByteBuffer.allocate(26).order(order).apply {
            if (order == ByteOrder.LITTLE_ENDIAN) {
                put('I'.code.toByte()); put('I'.code.toByte())
            } else {
                put('M'.code.toByte()); put('M'.code.toByte())
            }
            putShort(42)
            putInt(8) // IFD0 offset
            putShort(1) // 1 entry
            putShort(0x0112) // Orientation
            putShort(3) // SHORT
            putInt(1) // count
            putShort(orientation.toShort())
            putShort(0) // padding
            putInt(0) // next IFD
        }.array()
        val app1PayloadSize = 2 + 6 + tiff.size
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // SOI
        out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte())) // APP1
        out.write((app1PayloadSize ushr 8) and 0xFF)
        out.write(app1PayloadSize and 0xFF)
        out.write("Exif\u0000\u0000".toByteArray(Charsets.US_ASCII))
        out.write(tiff)
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte())) // EOI
        return out.toByteArray()
    }

    @Test
    fun parsesJpegExifOrientationLittleAndBigEndian() {
        assertEquals(
            ExifInterface.ORIENTATION_ROTATE_90,
            ImageLoader.jpegExifOrientation(jpegWithExifOrientation(ByteOrder.LITTLE_ENDIAN, ExifInterface.ORIENTATION_ROTATE_90)),
        )
        assertEquals(
            ExifInterface.ORIENTATION_ROTATE_180,
            ImageLoader.jpegExifOrientation(jpegWithExifOrientation(ByteOrder.BIG_ENDIAN, ExifInterface.ORIENTATION_ROTATE_180)),
        )
        assertEquals(
            ExifInterface.ORIENTATION_ROTATE_270,
            ImageLoader.jpegExifOrientation(jpegWithExifOrientation(ByteOrder.LITTLE_ENDIAN, ExifInterface.ORIENTATION_ROTATE_270)),
        )
    }

    @Test
    fun handlesNonJpegAndTruncatedDataSafely() {
        assertEquals(ExifInterface.ORIENTATION_NORMAL, ImageLoader.jpegExifOrientation(ByteArray(0)))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, ImageLoader.jpegExifOrientation(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        val truncated = jpegWithExifOrientation(ByteOrder.BIG_ENDIAN, ExifInterface.ORIENTATION_ROTATE_90).copyOf(20)
        assertEquals(ExifInterface.ORIENTATION_NORMAL, ImageLoader.jpegExifOrientation(truncated))
    }
}
