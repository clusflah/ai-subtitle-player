package com.aisub.player.book

import com.aisub.player.manga.Epub
import com.aisub.player.manga.MangaArchive
import com.aisub.player.manga.Mobi
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** 书里的一块：一段文字（标题也是一段），或者一张插图（[image] 是图片在文件里的位置，交给漫画的取图） */
data class BookBlock(val text: String = "", val heading: Boolean = false, val image: String? = null)

data class BookChapter(val title: String, val blocks: List<BookBlock>)

data class Ebook(val chapters: List<BookChapter>) {
    /** 正文字数（不算空白） */
    val textLength: Int by lazy { chapters.sumOf { c -> c.blocks.sumOf { b -> b.text.count { !it.isWhitespace() } } } }
    val imageCount: Int by lazy { chapters.sumOf { c -> c.blocks.count { it.image != null } } }

    /** 几乎全是图片的（图片做的漫画书）：交给漫画阅读 */
    val looksLikeManga: Boolean get() = imageCount >= 3 && textLength < imageCount * 40
}

/** 文件是什么书 */
enum class BookKind { TEXT, EPUB, MOBI, OTHER }

object Ebooks {
    fun isTextName(name: String) = name.substringAfterLast('.', "").lowercase() == "txt"

    /** 电子书（文字书）的扩展名：TXT 只能当文字书；EPUB、MOBI 可能是漫画，打开时再看 */
    fun isBookName(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("txt", "epub", "mobi", "azw3", "azw")

    /** 看文件开头认格式（content:// 复制过来的缓存文件没有扩展名） */
    fun kindOf(file: File): BookKind {
        val head = ByteArray(68)
        val n = file.inputStream().use { it.read(head) }
        if (n >= 68 && Mobi.isMobi(head)) return BookKind.MOBI
        if (n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            return if (runCatching { ZipFile(file).use { it.getEntry("META-INF/container.xml") != null } }.getOrDefault(false)) BookKind.EPUB else BookKind.OTHER
        }
        if (MangaArchive.isPdf(file)) return BookKind.OTHER
        // 前 4KB 里没有 0 字节：当文字
        val sample = file.inputStream().use { s -> ByteArray(4096).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } }
        return if (sample.isNotEmpty() && sample.none { it == 0.toByte() } || sample.isUtf16()) BookKind.TEXT else BookKind.OTHER
    }

    private fun ByteArray.isUtf16() = size >= 2 && ((this[0] == 0xFF.toByte() && this[1] == 0xFE.toByte()) || (this[0] == 0xFE.toByte() && this[1] == 0xFF.toByte()))

    /** 读成书；不是文字书返回 null */
    fun load(file: File): Ebook? = when (kindOf(file)) {
        BookKind.TEXT -> Txt.parse(decodeText(file.readBytes()))
        BookKind.EPUB -> ZipFile(file).use { EpubText.parse(it) }
        BookKind.MOBI -> RandomAccessFile(file, "r").use { MobiText.parse(it) }
        BookKind.OTHER -> null
    }

    /** TXT 的编码：BOM、UTF-8（严格）、GB18030（包含 GBK） */
    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.isUtf16()) return String(bytes, Charsets.UTF_16)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("GB18030"))
        }
    }
}

/** TXT：每行一段；按「第 X 章」这类标题分章，认不出章节时每几千字分一节 */
object Txt {
    private val CHAPTER = Regex(
        """^\s*(?:第\s*[0-9０-９零〇一二两三四五六七八九十百千万]+\s*[章节節回卷集部篇话話].{0,40}|卷\s*[0-9零一二三四五六七八九十百千]+(?:\s.{0,40})?|chapter\s+\d+\b.{0,40}|(?:序章|序言|序|楔子|引子|前言|后记|後記|尾声|尾聲|番外[0-9一二三四五六七八九十]*)(?:\s.{0,30}|[:：].{0,30})?)$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): Ebook {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .map { it.trim().trim('　', '﻿') }.filter { it.isNotEmpty() }
        val chapters = ArrayList<BookChapter>()
        var title = ""
        var blocks = ArrayList<BookBlock>()
        fun flush() {
            if (blocks.isNotEmpty() || title.isNotEmpty()) chapters.add(BookChapter(title.ifEmpty { "开头" }, blocks))
            blocks = ArrayList()
        }
        var found = 0
        for (line in lines) {
            if (line.length <= 50 && CHAPTER.matches(line)) {
                found++
                flush()
                title = line
                blocks.add(BookBlock(line, heading = true))
            } else blocks.add(BookBlock(line))
        }
        flush()
        if (found >= 2 || chapters.size > 1) return Ebook(chapters.filter { it.blocks.isNotEmpty() })
        // 没有章节标题：每 8000 字左右分一节，方便目录跳转
        val all = chapters.flatMap { it.blocks }
        val parts = ArrayList<BookChapter>()
        var cur = ArrayList<BookBlock>()
        var len = 0
        for (b in all) {
            cur.add(b); len += b.text.length
            if (len >= 8000) { parts.add(BookChapter("第 ${parts.size + 1} 部分", cur)); cur = ArrayList(); len = 0 }
        }
        if (cur.isNotEmpty()) parts.add(BookChapter("第 ${parts.size + 1} 部分", cur))
        return Ebook(parts)
    }
}

/**
 * 网页标记 → 段落和插图：块级标签（p、div、h1~h6、li、br……）断段，h1~h3 是标题，
 * script / style / head 里的不要；img 交给 [image] 换成图片位置（找不到的不要）
 */
object Html {
    private val BLOCK = setOf(
        "p", "div", "br", "li", "ul", "ol", "tr", "table", "blockquote", "section", "article", "header", "footer",
        "h1", "h2", "h3", "h4", "h5", "h6", "dt", "dd", "hr", "pre", "body", "figure", "figcaption", "mbp:pagebreak",
    )
    private val SKIP = setOf("script", "style", "head", "title", "svg:title")

    fun blocks(html: String, image: (Map<String, String>) -> String?): List<BookBlock> {
        val out = ArrayList<BookBlock>()
        val sb = StringBuilder()
        var heading = 0
        var skip: String? = null
        fun flush() {
            val t = sb.toString().replace(Regex("\\s+"), " ").trim()
            if (t.isNotEmpty()) out.add(BookBlock(t, heading = heading > 0))
            sb.setLength(0)
        }
        var i = 0
        val n = html.length
        while (i < n) {
            val c = html[i]
            if (c != '<') {
                val next = html.indexOf('<', i).let { if (it < 0) n else it }
                if (skip == null) sb.append(entities(html.substring(i, next)))
                i = next
                continue
            }
            if (html.startsWith("<!--", i)) { i = html.indexOf("-->", i).let { if (it < 0) n else it + 3 }; continue }
            val end = html.indexOf('>', i).let { if (it < 0) n else it }
            val tag = html.substring(i + 1, end)
            i = end + 1
            if (tag.startsWith("!") || tag.startsWith("?")) continue
            val closing = tag.startsWith("/")
            val name = tag.removePrefix("/").trim().split(Regex("[\\s/]"), 2)[0].lowercase()
            if (skip != null) { if (closing && name == skip) skip = null; continue }
            if (!closing && name in SKIP && !tag.endsWith("/")) { skip = name; continue }
            if (name == "img" || name == "image" || name == "svg:image") {
                image(attrs(tag))?.let { flush(); out.add(BookBlock(image = it)) }
                continue
            }
            if (name in BLOCK) {
                flush()
                if (name.length == 2 && name[0] == 'h' && name[1] in '1'..'3') heading = if (closing) 0 else 1
            }
        }
        flush()
        return out
    }

    /** 标签里的属性（名字小写） */
    fun attrs(tag: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (r in ATTR.findAll(tag)) m[r.groupValues[1].lowercase()] = entities(r.groupValues[2].ifEmpty { r.groupValues[3] })
        return m
    }

    private val ATTR = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private val NAMED = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "hellip" to "…", "mdash" to "—",
        "ndash" to "–", "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "middot" to "·", "emsp" to " ", "ensp" to " ",
        "thinsp" to " ", "copy" to "©", "laquo" to "«", "raquo" to "»", "bull" to "•",
    )
    private val ENTITY = Regex("""&(#\d+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);""")

    fun entities(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
            else -> NAMED[e.lowercase()]
        } ?: m.value
    }
}

/** EPUB 文字书：按 spine 的顺序，每个页面一章，章名用目录（nav / NCX）里的，没有就用页面里的第一个标题 */
object EpubText {
    fun parse(zip: ZipFile): Ebook? {
        val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        val lower = names.associateBy { it.lowercase() }
        fun find(path: String) = path.takeIf { zip.getEntry(it) != null } ?: lower[path.lowercase()]
        fun text(path: String): String? = find(path)?.let(zip::getEntry)?.takeIf { it.size <= 16L * 1024 * 1024 }
            ?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }

        val container = text("META-INF/container.xml") ?: return null
        val opfPath = Regex("""full-path\s*=\s*["']([^"']+)["']""").find(container)?.groupValues?.get(1)?.let(Html::entities) ?: return null
        val opf = text(opfPath) ?: return null
        val opfDir = opfPath.substringBeforeLast('/', "")

        val manifest = HashMap<String, Pair<String, String>>()
        var tocNcx: String? = null
        var tocNav: String? = null
        for (m in Regex("""<(?:\w+:)?item\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf)) {
            val a = Html.attrs(m.value)
            val id = a["id"] ?: continue
            val href = a["href"] ?: continue
            val path = Epub.resolve(opfDir, href)
            val type = a["media-type"].orEmpty().lowercase()
            manifest[id] = path to type
            if (type == "application/x-dtbncx+xml") tocNcx = path
            if (a["properties"].orEmpty().split(' ').contains("nav")) tocNav = path
        }
        val spine = Regex("""<(?:\w+:)?itemref\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf)
            .mapNotNull { Html.attrs(it.value)["idref"]?.let(manifest::get) }.toList()

        // 目录：文件 → 章名（同一个文件只取第一个）
        val titles = HashMap<String, String>()
        tocNav?.let { nav ->
            val html = text(nav) ?: return@let
            val dir = nav.substringBeforeLast('/', "")
            for (m in Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE).findAll(html)) {
                val href = Html.attrs(m.groupValues[1])["href"] ?: continue
                val t = Html.entities(m.groupValues[2].replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()
                if (t.isNotEmpty()) titles.putIfAbsent(Epub.resolve(dir, href).lowercase(), t)
            }
        }
        if (titles.isEmpty()) tocNcx?.let { ncx ->
            val xml = text(ncx) ?: return@let
            val dir = ncx.substringBeforeLast('/', "")
            for (m in Regex("""<navLabel>\s*<text>([\s\S]*?)</text>[\s\S]*?<content\s+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(xml)) {
                val t = Html.entities(m.groupValues[1]).replace(Regex("\\s+"), " ").trim()
                if (t.isNotEmpty()) titles.putIfAbsent(Epub.resolve(dir, Html.entities(m.groupValues[2])).lowercase(), t)
            }
        }

        val chapters = ArrayList<BookChapter>()
        for ((path, type) in spine) {
            val entry = find(path) ?: continue
            if (type.startsWith("image/")) {
                chapters.add(BookChapter(titles[path.lowercase()] ?: "插图", listOf(BookBlock(image = entry))))
                continue
            }
            val html = text(entry) ?: continue
            val dir = entry.substringBeforeLast('/', "")
            val blocks = Html.blocks(html) { a ->
                val src = a["src"] ?: a["xlink:href"] ?: a["href"] ?: return@blocks null
                if (src.startsWith("data:")) null else find(Epub.resolve(dir, src))
            }
            if (blocks.isEmpty()) continue
            val title = titles[entry.lowercase()] ?: blocks.firstOrNull { it.heading }?.text ?: blocks.firstOrNull { it.text.isNotEmpty() }?.text?.take(30)
                ?: "第 ${chapters.size + 1} 节"
            chapters.add(BookChapter(title, blocks))
        }
        return if (chapters.isEmpty()) null else Ebook(chapters)
    }
}

/**
 * MOBI / AZW3 文字书：PalmDOC 压缩的正文记录解开、拼起来是一整篇网页标记（KF8 的是各文件的骨架加片段，
 * 正文顺序是对的），按分页符或者 h1/h2 标题分章。图片：MOBI 的 recindex、KF8 的 kindle:embed 换成图片记录。
 * 有 DRM 的、HUFF/CDIC 压缩的（很少见）打不开。
 */
object MobiText {
    fun parse(f: RandomAccessFile): Ebook {
        val off = offsets(f)
        val r0 = off[0]
        f.seek(r0)
        val compression = f.readUnsignedShort()
        f.readUnsignedShort()
        f.readInt() // 正文长度
        val textRecords = f.readUnsignedShort()
        f.readUnsignedShort()
        val encryption = f.readUnsignedShort()
        if (encryption != 0) throw IOException("这本书有 DRM 加密，打不开（Kindle 商店买的书要先去掉 DRM）")
        if (compression == 17480) throw IOException("这本 MOBI 用的 HUFF/CDIC 压缩暂不支持，可以先转成 EPUB")
        if (compression != 1 && compression != 2) throw IOException("不认识的 MOBI 压缩方式（$compression）")
        f.seek(r0 + 16)
        val magic = ByteArray(4).also { f.readFully(it) }
        if (String(magic, Charsets.ISO_8859_1) != "MOBI") throw IOException("不是 MOBI 文件")
        val headerLen = f.readInt()
        f.seek(r0 + 0x1C)
        val encoding = f.readInt()
        f.seek(r0 + 0x6C)
        val firstImage = f.readInt()
        var extraFlags = 0
        if (headerLen >= 0xE4) {
            f.seek(r0 + 0xF2)
            extraFlags = f.readUnsignedShort()
        }
        val raw = java.io.ByteArrayOutputStream()
        for (i in 1..textRecords) {
            if (i >= off.size - 1) break
            val size = (off[i + 1] - off[i]).toInt()
            val data = ByteArray(size)
            f.seek(off[i]); f.readFully(data)
            val trail = trailingSize(data, extraFlags)
            val body = data.copyOf((size - trail).coerceAtLeast(0))
            raw.write(if (compression == 2) palmDoc(body) else body)
        }
        val charset = if (encoding == 1252) Charset.forName("windows-1252") else Charsets.UTF_8
        val html = String(raw.toByteArray(), charset)
        val images = Mobi.imageRecords(f).toSet()
        fun imageFor(a: Map<String, String>): String? {
            val rec = a["recindex"]?.trim()?.toIntOrNull()?.let { firstImage + it - 1 }
                ?: a["src"]?.let { Regex("""kindle:embed:([0-9A-Va-v]+)""").find(it)?.groupValues?.get(1)?.toIntOrNull(32) }?.let { firstImage + it - 1 }
            return rec?.takeIf { it in images }?.let { "${MangaArchive.MOBI}$it" }
        }
        // 分章：MOBI 的分页符、KF8 每个文件的 <body>
        val parts = html.split(Regex("""<mbp:pagebreak\s*/?>|(?=<body\b)""", RegexOption.IGNORE_CASE))
        val chapters = ArrayList<BookChapter>()
        for (p in parts) {
            val blocks = Html.blocks(p, ::imageFor)
            if (blocks.isEmpty()) continue
            val title = blocks.firstOrNull { it.heading }?.text ?: blocks.first().text.take(30).ifEmpty { "插图" }
            chapters.add(BookChapter(title, blocks))
        }
        if (chapters.isEmpty()) throw IOException("这本书里没有内容")
        return Ebook(chapters)
    }

    private fun offsets(f: RandomAccessFile): LongArray {
        f.seek(76)
        val n = f.readUnsignedShort()
        val out = LongArray(n + 1)
        for (i in 0 until n) { f.seek(78L + i * 8); out[i] = f.readInt().toLong() and 0xFFFFFFFFL }
        out[n] = f.length()
        return out
    }

    /** 正文记录末尾附加数据的长度（MOBI 头的 extra data flags） */
    internal fun trailingSize(data: ByteArray, flags: Int): Int {
        var num = 0
        var fl = flags ushr 1
        while (fl != 0) {
            if (fl and 1 != 0) num += backwardVarInt(data, data.size - num)
            fl = fl ushr 1
        }
        if (flags and 1 != 0 && data.size - num - 1 >= 0) num += (data[data.size - num - 1].toInt() and 3) + 1
        return num.coerceAtMost(data.size)
    }

    private fun backwardVarInt(data: ByteArray, end: Int): Int {
        var result = 0
        var bit = 0
        var p = end
        while (p > 0) {
            val v = data[p - 1].toInt() and 0xFF
            result = result or ((v and 0x7F) shl bit)
            bit += 7
            p--
            if (v and 0x80 != 0 || bit >= 28) break
        }
        return result
    }

    /** PalmDOC（LZ77 变种）解压 */
    internal fun palmDoc(src: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(src.size * 2)
        var buf = ByteArray(0)
        var len = 0
        fun put(b: Int) {
            if (len == buf.size) buf = buf.copyOf(maxOf(64, buf.size * 2))
            buf[len++] = b.toByte()
        }
        var i = 0
        while (i < src.size) {
            val c = src[i++].toInt() and 0xFF
            when {
                c == 0 || c in 0x09..0x7F -> put(c)
                c in 1..8 -> repeat(c) { if (i < src.size) put(src[i++].toInt() and 0xFF) }
                c in 0x80..0xBF -> {
                    if (i >= src.size) break
                    val c2 = src[i++].toInt() and 0xFF
                    val pair = (c shl 8) or c2
                    val dist = (pair shr 3) and 0x7FF
                    val n = (pair and 7) + 3
                    if (dist in 1..len) repeat(n) { put(buf[len - dist].toInt() and 0xFF) }
                }
                else -> { put(' '.code); put(c xor 0x80) }
            }
        }
        out.write(buf, 0, len)
        return out.toByteArray()
    }
}

/** 一句话在书里的位置：第几块（段落）、在这一段里的起止 */
data class BookSentence(val block: Int, val start: Int, val end: Int)

object Sentences {
    private const val MAX = 160

    /** 把一段分成朗读的句子：句号、问号、感叹号、分号后断开（连同后面的引号），太长的在逗号处再断 */
    fun split(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        var i = 0
        fun cut(end: Int) {
            var s = start
            while (s < end && text[s].isWhitespace()) s++
            if (s < end) {
                // 太长：在逗号、顿号处切
                var a = s
                while (end - a > MAX) {
                    val window = text.substring(a, a + MAX)
                    val k = window.indexOfLast { it in "，,、：:" }.takeIf { it > MAX / 3 } ?: (MAX - 1)
                    out.add(a until a + k + 1)
                    a += k + 1
                }
                if (a < end) out.add(a until end)
            }
            start = end
        }
        while (i < text.length) {
            val ch = text[i]
            val stop = ch in "。！？!?；;…" || (ch == '.' && (i + 1 == text.length || text[i + 1] == ' '))
            i++
            if (stop) {
                while (i < text.length && (text[i] in "。！？!?…”’」』\"')）" )) i++
                cut(i)
            }
        }
        cut(text.length)
        return out
    }

    /** 朗读的语言：中日韩字多的按中文，否则英文（一个汉字差不多是一个英文词，按 5 个字母算） */
    fun localeOf(sample: String): String {
        var cjk = 0
        var latin = 0
        for (c in sample) {
            if (c in '一'..'鿿' || c in '㐀'..'䶿') cjk++
            else if (c in 'a'..'z' || c in 'A'..'Z') latin++
        }
        return if (cjk > 0 && cjk * 5 >= latin) "zh-CN" else "en-US"
    }
}
