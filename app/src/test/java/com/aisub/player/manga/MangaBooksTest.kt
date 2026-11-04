package com.aisub.player.manga

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class MangaBooksTest {
    private fun epub(files: Map<String, String>, extra: List<String> = emptyList()) =
        Epub.imageOrder(files.keys + extra) { files[it] }

    @Test
    fun epubFollowsSpineNotFileNames() {
        val files = mapOf(
            "META-INF/container.xml" to """<?xml version="1.0"?><container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OEBPS/content.opf" to """
                <package><manifest>
                  <item id="p2" href="Text/page%202.xhtml" media-type="application/xhtml+xml"/>
                  <item media-type="application/xhtml+xml" href='Text/p1.xhtml' id='p1'/>
                  <item id="cover" href="Images/cover.jpg" media-type="image/jpeg"/>
                  <item id="i1" href="Images/b.jpg" media-type="image/jpeg"/>
                  <item id="i2" href="Images/a.png" media-type="image/png"/>
                </manifest>
                <spine page-progression-direction="rtl">
                  <itemref idref="cover"/><itemref idref="p1"/><itemref idref="p2" linear="yes"/>
                </spine></package>
            """.trimIndent(),
            "OEBPS/Text/p1.xhtml" to """<html><body><div><img src="../Images/b.jpg" alt=""/></div></body></html>""",
            // SVG 包着的图（KCC 做的固定版式）
            "OEBPS/Text/page 2.xhtml" to """<svg xmlns:xlink="http://www.w3.org/1999/xlink"><image width="800" height="1200" xlink:href="../Images/a.png"/></svg>""",
        )
        // 文件名排序会是 a、b、cover；按 spine 是 cover、b、a
        assertEquals(
            listOf("OEBPS/Images/cover.jpg", "OEBPS/Images/b.jpg", "OEBPS/Images/a.png"),
            epub(files, listOf("OEBPS/Images/cover.jpg", "OEBPS/Images/b.jpg", "OEBPS/Images/a.png")),
        )
    }

    @Test
    fun epubCaseMismatchAndNotEpub() {
        val files = mapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
            "content.opf" to """<package><manifest><item id="x" href="page.html" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="x"/></spine></package>""",
            "page.html" to """<img src="IMG/P01.JPG">""",
        )
        assertEquals(listOf("img/p01.jpg"), epub(files, listOf("img/p01.jpg")))
        // 普通 ZIP、文字书（没有图片）：null，交给按文件名排序 / 报错
        assertNull(epub(mapOf("01.jpg" to "")))
        assertNull(epub(files - "page.html" + ("page.html" to "<p>只有文字</p>")))
    }

    @Test
    fun resolvePaths() {
        assertEquals("OEBPS/Images/a b.jpg", Epub.resolve("OEBPS/Text", "../Images/a%20b.jpg#x"))
        assertEquals("img/1+1.png", Epub.resolve("", "./img/1+1.png"))
    }

    @Test
    fun mobiImagesInOrderWithoutThumbnail() {
        val jpeg1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5, 6, 7, 8)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 9, 9, 9, 9, 9, 9, 9, 9)
        val jpeg2 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte(), 2, 2, 2, 2, 2, 2, 2, 2, 2)
        val thumb = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 3, 3, 3, 3, 3, 3, 3, 3)
        val text = "<html>some text record</html>".toByteArray()
        val flis = "FLIS\u0000\u0000\u0000\u0008\u0000A\u0000\u0000".toByteArray(Charsets.ISO_8859_1)

        // 记录 0：PalmDOC 头 16 字节 + MOBI 头（长 0xE8）+ EXTH（202 缩略图 = 第一张图片之后第 3 张）
        val headerLen = 0xE8
        val r0 = ByteBuffer.allocate(16 + headerLen + 12 + 12)
        r0.position(16); r0.put("MOBI".toByteArray()); r0.putInt(headerLen)
        r0.putInt(0x6C, 2)           // 第一张图片是记录 2
        r0.putInt(0x80, 0x40)        // 有 EXTH
        r0.position(16 + headerLen)
        r0.put("EXTH".toByteArray()); r0.putInt(24); r0.putInt(1)
        r0.putInt(202); r0.putInt(12); r0.putInt(3)
        val records = listOf(r0.array(), text, jpeg1, png, jpeg2, thumb, flis)

        val file = File.createTempFile("test", ".mobi")
        try {
            val head = ByteBuffer.allocate(78 + records.size * 8)
            head.position(60); head.put("BOOKMOBI".toByteArray())
            head.putShort(76, records.size.toShort())
            var off = 78 + records.size * 8 + 2
            records.forEachIndexed { i, r -> head.putInt(78 + i * 8, off); off += r.size }
            file.outputStream().use { o ->
                o.write(head.array()); o.write(byteArrayOf(0, 0))
                records.forEach(o::write)
            }

            assertTrue(Mobi.isMobi(file))
            RandomAccessFile(file, "r").use { f ->
                // 记录 5 是缩略图（2 + 3），FLIS 不是图片
                assertEquals(listOf(2, 3, 4), Mobi.imageRecords(f))
                assertArrayEquals(png, Mobi.record(f, 3))
                assertArrayEquals(jpeg2, Mobi.record(f, 4))
            }
        } finally {
            file.delete()
        }
        val notMobi = File.createTempFile("test", ".zip")
        try {
            notMobi.writeBytes(ByteArray(100))
            assertFalse(Mobi.isMobi(notMobi))
        } finally {
            notMobi.delete()
        }
    }
}
