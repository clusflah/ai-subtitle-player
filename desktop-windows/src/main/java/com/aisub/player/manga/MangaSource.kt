package com.aisub.player.manga

import android.content.Context
import android.net.Uri
import com.aisub.player.media.RemoteFiles
import com.aisub.player.remote.cloud.cloudHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * 漫画的一页：网页图片（带上网页地址当 Referer、网页的 Cookie，过防盗链）、
 * 本地 / 网盘图片（content://、file://、cloud://、WebDAV 地址），或压缩包里的一个文件（zip 条目名）。
 */
data class MangaPage(
    val url: String,
    /** 网页图片：图片所在网页（iframe 里的就是 iframe 的地址），当 Referer 用 */
    val referer: String? = null,
    /** 压缩包里的条目名；非空时 url 是压缩包 */
    val entry: String? = null,
) {
    val cacheKey: String get() = if (entry != null) "$url!$entry" else url
}

/** 一话漫画（或一个文件夹、一个压缩包） */
data class MangaBook(
    /** 记进度用：网页是这一话的地址，本地是文件地址 */
    val key: String,
    val title: String,
    val pages: List<MangaPage>,
    /** 记阅读设置用（同一个网站的漫画用同一套设置）；本地漫画是 "local" */
    val site: String,
    /** 上一话、下一话、目录的网页地址（网页漫画才有） */
    val prev: String? = null,
    val next: String? = null,
    val toc: String? = null,
    /** 网页漫画：这一话所在网页（打开「最近阅读」时回到这里） */
    val webUrl: String? = null,
    /** 记进「最近阅读」（看图时临时切到漫画模式的不记，只记进度） */
    val recent: Boolean = true,
)

/** 读取漫画图片的字节 */
object MangaFetcher {
    /** 网页图片要和网页用一样的 User-Agent（有的网站按 UA 发 Cookie、做防盗链） */
    @Volatile
    var userAgent: String? = null

    suspend fun bytes(context: Context, page: MangaPage): ByteArray = withContext(Dispatchers.IO) {
        when {
            page.entry?.startsWith(MangaArchive.PDF) == true -> throw IOException("PDF 页要画出来，不能直接读")
            page.entry != null -> MangaArchive.read(context, page.url, page.entry)
            // 网页里解密出来的图：请网页交出来
            com.aisub.player.web.WebPageImages.isPage(page.url) -> com.aisub.player.web.WebPageImages.bytes(page.url)
            page.referer != null && (page.url.startsWith("http://") || page.url.startsWith("https://")) -> web(page)
            else -> RemoteFiles.read(context, page.url, MAX_BYTES)
        }
    }

    private fun web(page: MangaPage): ByteArray {
        val req = Request.Builder().url(page.url).apply {
            header("Referer", page.referer!!)
            header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            userAgent?.let { header("User-Agent", it) }
            runCatching { android.webkit.CookieManager.getInstance().getCookie(page.url) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
        }.build()
        return cloudHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("没有内容")
            val bytes = body.bytes()
            if (bytes.size > MAX_BYTES) throw IOException("图片太大")
            bytes
        }
    }

    private const val MAX_BYTES = 40 * 1024 * 1024
}

/**
 * 漫画压缩包（CBZ / ZIP）。要随机读取每一页，content:// 和网盘里的先复制到缓存目录，
 * 只留最近打开的几个，免得占满空间。
 */
object MangaArchive {
    private val lock = Mutex()
    private val open = LinkedHashMap<String, ZipFile>()

    fun isArchiveName(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("cbz", "zip", "pdf", "epub", "mobi", "azw3", "azw")

    /** 系统给的类型是漫画压缩包、PDF、EPUB 或 MOBI */
    fun isArchiveType(mime: String?) = mime?.lowercase() in TYPES

    /** 漫画文件的类型（文件选择器、「用译幕打开」用） */
    val TYPES = listOf(
        "application/zip", "application/x-zip-compressed", "application/x-cbz", "application/vnd.comicbook+zip", "application/pdf",
        "application/epub+zip", "application/x-mobipocket-ebook", "application/vnd.amazon.ebook", "application/vnd.amazon.mobi8-ebook",
    )

    /** PDF 的页：条目名是这个前缀加页码 */
    const val PDF = "#pdf:"

    /** MOBI / AZW3 的页：条目名是这个前缀加记录序号 */
    const val MOBI = "#mobi:"

    fun isPdf(file: File): Boolean = runCatching {
        file.inputStream().use { val b = ByteArray(5); it.read(b) == 5 && String(b, Charsets.US_ASCII) == "%PDF-" }
    }.getOrDefault(false)

    private val pdfLock = Mutex()
    private var pdfOpen: Pair<String, org.apache.pdfbox.pdmodel.PDDocument>? = null

    private fun pdfFor(url: String, file: File): org.apache.pdfbox.pdmodel.PDDocument {
        pdfOpen?.takeIf { it.first == url }?.let { return it.second }
        pdfOpen?.second?.let { runCatching { it.close() } }
        if (!file.exists()) throw IOException("文件不见了，请重新打开")
        val doc = org.apache.pdfbox.pdmodel.PDDocument.load(file)
        pdfOpen = url to doc
        return doc
    }

    /** 把 PDF 的一页画成图片，宽度 width 像素（白底） */
    suspend fun renderPdf(context: Context, url: String, index: Int, width: Int): android.graphics.Bitmap = withContext(Dispatchers.IO) {
        pdfLock.withLock {
            val doc = pdfFor(url, localFile(context, url))
            val page = doc.getPage(index)
            val box = page.mediaBox
            val pageWidthPt = box?.width?.coerceAtLeast(1f) ?: 600f
            val w = width.coerceAtLeast(1)
            val scale = (w.toFloat() / pageWidthPt).coerceIn(0.5f, 6f)
            val renderer = org.apache.pdfbox.rendering.PDFRenderer(doc)
            val bufferedImage = renderer.renderImage(index, scale, org.apache.pdfbox.rendering.ImageType.RGB)
            android.graphics.Bitmap.fromBufferedImage(bufferedImage)
        }
    }

    /** 缓存里有没有这个压缩包（没有的话打开前要先下载 / 复制） */
    fun localFile(context: Context, url: String): File {
        if (url.startsWith("file://")) return Uri.parse(url).toJavaFile() ?: File(Uri.parse(url).path!!)
        if (url.startsWith("/") || (url.length >= 2 && url[1] == ':')) return File(url)
        val dir = File(context.cacheDir, "manga").apply { mkdirs() }
        return File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length + ".zip")
    }

    /** 准备好压缩包（需要时复制到缓存，progress 是 0..1，不知道大小是 -1），返回漫画的所有页（按文件名自然排序） */
    /** 把文件准备到本地（content:// 复制、网盘下载到缓存，progress 是 0..1，不知道大小是 -1） */
    suspend fun prepare(context: Context, url: String, progress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val file = localFile(context, url)
        if (!file.exists()) {
            trimCache(file.parentFile)
            RemoteFiles.download(context, url, file) { done, total -> progress(if (total > 0) done.toFloat() / total else -1f) }
        }
        file.setLastModified(System.currentTimeMillis())
        file
    }

    suspend fun pages(context: Context, url: String, progress: (Float) -> Unit = {}): List<MangaPage> = withContext(Dispatchers.IO) {
        val file = prepare(context, url, progress)
        if (isPdf(file)) {
            val count = pdfLock.withLock { pdfFor(url, file).numberOfPages }
            return@withContext (0 until count).map { MangaPage(url, entry = "$PDF$it") }
        }
        if (Mobi.isMobi(file)) {
            val records = java.io.RandomAccessFile(file, "r").use { Mobi.imageRecords(it) }
            if (records.isEmpty()) throw IOException("这个 MOBI 文件里没有图片（文字书不能当漫画看）")
            return@withContext records.map { MangaPage(url, entry = "$MOBI$it") }
        }
        val zip = zipFor(url, file)
        val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        // EPUB：按书里的阅读顺序
        val epub = runCatching {
            Epub.imageOrder(names) { name ->
                zip.getEntry(name)?.takeIf { it.size in 0..(8L * 1024 * 1024) }?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            }
        }.getOrNull()
        if (epub != null) return@withContext epub.map { MangaPage(url, entry = it) }
        val images = names
            .filter { com.aisub.player.media.MediaEntry.isImageName(it) && !it.substringAfterLast('/').startsWith(".") }
            .sortedWith(NaturalOrder)
        if (images.isEmpty() && names.any { it == "META-INF/container.xml" }) throw IOException("这个 EPUB 里没有图片（文字书不能当漫画看）")
        images.map { MangaPage(url, entry = it) }
    }

    suspend fun read(context: Context, url: String, entry: String): ByteArray {
        if (entry.startsWith(MOBI)) {
            val index = entry.removePrefix(MOBI).toIntOrNull() ?: throw IOException("MOBI 页不对")
            return withContext(Dispatchers.IO) {
                java.io.RandomAccessFile(localFile(context, url), "r").use { Mobi.record(it, index) }
            }
        }
        val zip = zipFor(url, localFile(context, url))
        // ZipFile 可以多线程读，但同一个条目的流要自己读完；加锁省得低端机同时解压太多
        return lock.withLock {
            val e = zip.getEntry(entry) ?: throw IOException("压缩包里没有 $entry")
            zip.getInputStream(e).use { it.readBytes() }
        }
    }

    private suspend fun zipFor(url: String, file: File): ZipFile = lock.withLock {
        open[url] ?: run {
            if (!file.exists()) throw IOException("压缩包不见了，请重新打开")
            // 中文系统上打的包文件名常是 GBK：按 UTF-8 读文件名出错就换 GBK
            val zip = runCatching { ZipFile(file, Charsets.UTF_8).also { z -> z.entries().asSequence().forEach { it.name } } }
                .getOrElse { ZipFile(file, charset("GBK")) }
            open[url] = zip
            // 最多同时开着 3 个
            while (open.size > 3) open.remove(open.keys.first())?.let { runCatching { it.close() } }
            zip
        }
    }

    /** 缓存目录里只留最近用过的 3 个压缩包 */
    private fun trimCache(dir: File?) {
        val files = dir?.listFiles { f -> f.name.endsWith(".zip") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(2).forEach { it.delete() }
    }
}

/** 文件名自然排序：第 2 页排在第 10 页前面 */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")
    override fun compare(a: String, b: String): Int {
        val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val n = p.trimStart('0').length.compareTo(q.trimStart('0').length)
                if (n != 0) n else p.trimStart('0').compareTo(q.trimStart('0'))
            } else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}
