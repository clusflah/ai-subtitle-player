package com.aisub.player.book

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EbookTest {
    @Test
    fun txtChaptersAndEncoding() {
        val text = "书名\r\n\r\n第一章 初见\r\n　　他走了进来。\r\n序幕拉开了，他走进来\r\n第二章开始\n内容二\n番外\n番外内容"
        val gbk = text.toByteArray(charset("GBK"))
        val decoded = Ebooks.decodeText(gbk)
        assertEquals(text, decoded)
        val book = Txt.parse(decoded)
        assertEquals(listOf("开头", "第一章 初见", "第二章开始", "番外"), book.chapters.map { it.title })
        // 「序幕拉开了……」是正文不是章节；行首全角空格去掉
        assertEquals(listOf("第一章 初见", "他走了进来。", "序幕拉开了，他走进来"), book.chapters[1].blocks.map { it.text })
        assertTrue(book.chapters[1].blocks[0].heading)
        // UTF-8 带 BOM
        assertEquals("abc", Ebooks.decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())))
    }

    @Test
    fun txtWithoutChaptersIsSplitIntoParts() {
        val book = Txt.parse((1..30).joinToString("\n") { "第${it}行：" + "字".repeat(500) })
        assertTrue(book.chapters.size >= 2)
        assertEquals("第 1 部分", book.chapters[0].title)
        assertEquals(30, book.chapters.sumOf { it.blocks.size })
    }

    @Test
    fun htmlBlocks() {
        val html = """<html><head><title>T</title><style>p{}</style></head><body>
            <h1>第一章</h1><p>甲&nbsp;乙 &amp; 丙&#x4E01;</p><div>a<br/>b</div><p><img src="x.jpg"/></p>
            <script>var s = "<p>不要</p>";</script><p>  末尾  </p></body></html>"""
        val blocks = Html.blocks(html) { a -> a["src"]?.let { "img/$it" } }
        assertEquals(
            listOf(BookBlock("第一章", heading = true), BookBlock("甲 乙 & 丙丁"), BookBlock("a"), BookBlock("b"), BookBlock(image = "img/x.jpg"), BookBlock("末尾")),
            blocks,
        )
    }

    private fun zip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
    }

    @Test
    fun epubText() {
        val f = File.createTempFile("book", ".epub")
        try {
            zip(
                f,
                mapOf(
                    "mimetype" to "application/epub+zip",
                    "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
                    "OEBPS/content.opf" to """<package><manifest>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="c2" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
                        <item id="c1" href="text/c1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="pic" href="images/p.jpg" media-type="image/jpeg"/>
                        </manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""",
                    "OEBPS/nav.xhtml" to """<nav epub:type="toc"><ol><li><a href="text/c1.xhtml#top">第一章 <b>开始</b></a></li><li><a href="text/c2.xhtml">第二章</a></li></ol></nav>""",
                    "OEBPS/text/c1.xhtml" to """<body><h2>一</h2><p>第一段。</p><p><img src="../images/p.jpg"/></p></body>""",
                    "OEBPS/text/c2.xhtml" to """<body><p>第二段。</p></body>""",
                    "OEBPS/images/p.jpg" to "jpg",
                ),
            )
            assertEquals(BookKind.EPUB, Ebooks.kindOf(f))
            val book = Ebooks.load(f)!!
            assertEquals(listOf("第一章 开始", "第二章"), book.chapters.map { it.title })
            assertEquals(listOf(BookBlock("一", heading = true), BookBlock("第一段。"), BookBlock(image = "OEBPS/images/p.jpg")), book.chapters[0].blocks)
            assertFalse(book.looksLikeManga)
        } finally {
            f.delete()
        }
    }

    @Test
    fun mangaLikeBook() {
        val pics = Ebook(listOf(BookChapter("1", (1..10).map { BookBlock(image = "p$it.jpg") } + BookBlock("封面"))))
        assertTrue(pics.looksLikeManga)
    }

    @Test
    fun palmDocAndTrailingEntries() {
        // "ab" 原样，往回 2 个复制 4 个，空格 + 'a'，再原样 2 个字节
        val src = byteArrayOf(0x61, 0x62, 0x80.toByte(), 0x11, 0xE1.toByte(), 0x02, 0x7A, 0x7A)
        assertEquals("ababab azz", String(MobiText.palmDoc(src), Charsets.ISO_8859_1))
        // 正文 + 多字节附加 2 字节 + 一条 3 字节的附加数据
        val data = "HELLO".toByteArray() + byteArrayOf(0xAA.toByte(), 0x01, 0x10, 0x20, 0x83.toByte())
        assertEquals(5, MobiText.trailingSize(data, 3))
        assertEquals(0, MobiText.trailingSize(data, 0))
    }

    @Test
    fun mobiTextBook() {
        val html = "<html><body><h2>第一章</h2><p>甲乙。</p><p><img recindex=\"00001\"/></p><mbp:pagebreak/><h2>第二章</h2><p>丙丁。</p></body></html>"
        val text = html.toByteArray(Charsets.UTF_8)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5, 6, 7, 8)
        val headerLen = 0xE8
        val r0 = ByteBuffer.allocate(16 + headerLen)
        r0.putShort(0, 1)                 // 不压缩
        r0.putInt(4, text.size)
        r0.putShort(8, 1)                 // 1 条正文记录
        r0.putShort(10, 4096)
        r0.position(16); r0.put("MOBI".toByteArray()); r0.putInt(headerLen)
        r0.putInt(0x1C, 65001)            // UTF-8
        r0.putInt(0x6C, 2)                // 第一张图片是记录 2
        val records = listOf(r0.array(), text, jpeg)
        val f = File.createTempFile("book", ".mobi")
        try {
            val head = ByteBuffer.allocate(78 + records.size * 8)
            head.position(60); head.put("BOOKMOBI".toByteArray())
            head.putShort(76, records.size.toShort())
            var off = 78 + records.size * 8
            records.forEachIndexed { i, r -> head.putInt(78 + i * 8, off); off += r.size }
            f.outputStream().use { o -> o.write(head.array()); records.forEach(o::write) }
            assertEquals(BookKind.MOBI, Ebooks.kindOf(f))
            val book = Ebooks.load(f)!!
            assertEquals(listOf("第一章", "第二章"), book.chapters.map { it.title })
            assertEquals(BookBlock(image = "#mobi:2"), book.chapters[0].blocks[2])
            assertEquals("丙丁。", book.chapters[1].blocks[1].text)
            RandomAccessFile(f, "r").use { assertArrayEquals(jpeg, com.aisub.player.manga.Mobi.record(it, 2)) }
        } finally {
            f.delete()
        }
    }

    @Test
    fun sentences() {
        val zh = "你好。今天天气不错！“真的吗？”他问"
        assertEquals(listOf("你好。", "今天天气不错！", "“真的吗？”", "他问"), Sentences.split(zh).map { zh.substring(it) })
        val en = "Hello world. It is 3.5 dollars."
        assertEquals(listOf("Hello world.", "It is 3.5 dollars."), Sentences.split(en).map { en.substring(it) })
        val long = "很长的一句话，".repeat(40)
        assertTrue(Sentences.split(long).all { it.last - it.first + 1 <= 160 })
        assertEquals(long.length, Sentences.split(long).sumOf { it.last - it.first + 1 })
        assertEquals("zh-CN", Sentences.localeOf("这是中文 with a few words"))
        assertEquals("en-US", Sentences.localeOf("This is English text"))
    }

    @Test
    fun kinds() {
        val txt = File.createTempFile("book", ".txt")
        val bin = File.createTempFile("book", ".bin")
        try {
            txt.writeText("一些文字")
            bin.writeBytes(byteArrayOf(1, 0, 2, 0, 3))
            assertEquals(BookKind.TEXT, Ebooks.kindOf(txt))
            assertEquals(BookKind.OTHER, Ebooks.kindOf(bin))
        } finally {
            txt.delete(); bin.delete()
        }
    }
}
