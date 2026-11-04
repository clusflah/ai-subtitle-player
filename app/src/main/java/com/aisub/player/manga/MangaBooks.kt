package com.aisub.player.manga

import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLDecoder

/**
 * EPUB 漫画（图片书）：本身是个 ZIP，阅读顺序在 OPF 的 spine 里——每一页是一个 XHTML（里面一张图），
 * 或者直接就是图片。按这个顺序取出图片；认不出来（不是 EPUB、没有图片页）时由调用方退回按文件名排序。
 */
object Epub {
    /** zip 里的条目：按名字读出文本（没有返回 null） */
    fun interface Entries {
        fun text(name: String): String?
    }

    /**
     * 按阅读顺序排好的图片条目名（和压缩包里的写法一样）；[names] 是压缩包里所有条目名。
     * 不是 EPUB 或者找不出图片返回 null
     */
    fun imageOrder(names: Collection<String>, entries: Entries): List<String>? {
        val exact = names.toSet()
        // 链接里的大小写和压缩包里的不一样时也认
        val lower = names.associateBy { it.lowercase() }
        fun find(path: String): String? = path.takeIf { it in exact } ?: lower[path.lowercase()]

        val container = entries.text("META-INF/container.xml") ?: return null
        val opfPath = attr(ROOTFILE.find(container)?.value ?: return null, "full-path")?.let(::unescape)?.let(::find) ?: return null
        val opf = entries.text(opfPath) ?: return null
        val opfDir = dirOf(opfPath)

        // manifest：id → (地址, 类型)
        val manifest = HashMap<String, Pair<String, String>>()
        for (m in ITEM.findAll(opf)) {
            val tag = m.value
            val id = attr(tag, "id") ?: continue
            val href = attr(tag, "href") ?: continue
            manifest[id] = resolve(opfDir, href) to (attr(tag, "media-type") ?: "").lowercase()
        }
        val spine = ITEMREF.findAll(opf).mapNotNull { attr(it.value, "idref")?.let(manifest::get) }.toList()

        val out = LinkedHashSet<String>()
        for ((path, type) in spine) {
            if (type.startsWith("image/")) {
                find(path)?.let(out::add)
                continue
            }
            val page = find(path)?.let(entries::text) ?: continue
            val dir = dirOf(path)
            // <img src>、SVG 里的 <image xlink:href> / <image href>，按页面里出现的顺序
            for (m in IMAGE_TAG.findAll(page)) {
                val tag = m.value
                val ref = (if (tag.startsWith("<img", ignoreCase = true)) attr(tag, "src") else attr(tag, "xlink:href") ?: attr(tag, "href"))
                    ?: continue
                if (ref.startsWith("data:")) continue
                val img = find(resolve(dir, ref)) ?: continue
                if (com.aisub.player.media.MediaEntry.isImageName(img)) out.add(img)
            }
        }
        return out.toList().takeIf { it.isNotEmpty() }
    }

    private val ROOTFILE = Regex("""<(?:\w+:)?rootfile\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ITEM = Regex("""<(?:\w+:)?item\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ITEMREF = Regex("""<(?:\w+:)?itemref\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val IMAGE_TAG = Regex("""<(?:img|(?:\w+:)?image)\b[^>]*>""", RegexOption.IGNORE_CASE)

    /** 标签里一个属性的值（单双引号都认），已经还原 &amp; 之类 */
    private fun attr(tag: String, name: String): String? =
        Regex("""(?:^|\s)${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(tag)
            ?.let { m -> unescape(m.groupValues[1].ifEmpty { m.groupValues[2] }) }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private fun dirOf(path: String) = path.substringBeforeLast('/', "")

    /** 相对地址 → 压缩包里的路径（去掉 #、?，%20 这类还原，处理 ../） */
    internal fun resolve(dir: String, href: String): String {
        val clean = href.substringBefore('#').substringBefore('?')
        val decoded = runCatching { URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8") }.getOrDefault(clean)
        val parts = ArrayList<String>()
        if (!decoded.startsWith("/") && dir.isNotEmpty()) parts.addAll(dir.split('/'))
        for (p in decoded.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }
}

/**
 * MOBI / AZW3（Kindle 格式，PalmDB 结构）：图片原样存在一条条记录里，从 MOBI 头里的「第一张图片」那条开始按顺序排，
 * 就是漫画的页序（KindleGen、KCC 做的漫画都这样）。只取图片记录，跳过缩略图。
 */
object Mobi {
    /** 文件开头（至少 68 字节）是不是 MOBI / AZW3 */
    fun isMobi(head: ByteArray): Boolean =
        head.size >= 68 && String(head, 60, 8, Charsets.ISO_8859_1).let { it == "BOOKMOBI" }

    fun isMobi(file: java.io.File): Boolean = runCatching {
        file.inputStream().use { val b = ByteArray(68); it.read(b) == 68 && isMobi(b) }
    }.getOrDefault(false)

    /** 每条记录在文件里的起止位置 */
    private fun offsets(f: RandomAccessFile): LongArray {
        f.seek(76)
        val n = f.readUnsignedShort()
        if (n <= 0) throw IOException("MOBI 文件里没有内容")
        val out = LongArray(n + 1)
        for (i in 0 until n) {
            f.seek(78L + i * 8)
            out[i] = f.readInt().toLong() and 0xFFFFFFFFL
        }
        out[n] = f.length()
        return out
    }

    /** 图片记录的序号，按书里的顺序 */
    fun imageRecords(f: RandomAccessFile): List<Int> {
        val off = offsets(f)
        val n = off.size - 1
        val r0 = off[0]
        f.seek(r0 + 16)
        val magic = ByteArray(4).also { f.readFully(it) }
        if (String(magic, Charsets.ISO_8859_1) != "MOBI") throw IOException("不是 MOBI 文件")
        val headerLen = f.readInt().toLong() and 0xFFFFFFFFL
        f.seek(r0 + 0x6C)
        val first = f.readInt().let { if (it <= 0 || it >= n) 1 else it }
        // EXTH 202：缩略图是第几张（从第一张图片算起）
        var thumb = -1
        f.seek(r0 + 0x80)
        val exthFlags = f.readInt()
        if (exthFlags and 0x40 != 0) {
            val exth = r0 + 16 + headerLen
            f.seek(exth)
            val tag = ByteArray(4).also { f.readFully(it) }
            if (String(tag, Charsets.ISO_8859_1) == "EXTH") {
                f.readInt()
                val count = f.readInt()
                var pos = exth + 12
                repeat(count.coerceIn(0, 10_000)) {
                    f.seek(pos)
                    val type = f.readInt()
                    val len = f.readInt()
                    if (len < 8) return@repeat
                    if (type == 202 && len >= 12) thumb = first + f.readInt()
                    pos += len
                }
            }
        }
        val out = ArrayList<Int>()
        val head = ByteArray(12)
        for (i in first until n) {
            if (i == thumb) continue
            val size = off[i + 1] - off[i]
            if (size < 12) continue
            f.seek(off[i])
            f.readFully(head)
            if (isImage(head)) out.add(i)
        }
        return out
    }

    fun record(f: RandomAccessFile, index: Int): ByteArray {
        val off = offsets(f)
        if (index !in 0 until off.size - 1) throw IOException("MOBI 里没有第 $index 条记录")
        val size = off[index + 1] - off[index]
        if (size !in 1..(40L * 1024 * 1024)) throw IOException("MOBI 记录大小不对")
        f.seek(off[index])
        return ByteArray(size.toInt()).also { f.readFully(it) }
    }

    private fun isImage(h: ByteArray): Boolean =
        (h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() && h[2] == 0xFF.toByte()) ||
            (h[0] == 0x89.toByte() && h[1] == 'P'.code.toByte() && h[2] == 'N'.code.toByte() && h[3] == 'G'.code.toByte()) ||
            String(h, 0, 4, Charsets.ISO_8859_1) == "GIF8" ||
            (String(h, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(h, 8, 4, Charsets.ISO_8859_1) == "WEBP")
}
