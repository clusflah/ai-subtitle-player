package com.aisub.player.manga

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * 漫画图片：按屏幕宽度解码（不是按最长边：条漫一张图很长，按最长边缩会糊成一条），
 * 放在单独的内存缓存里，大小按手机 / 电视盒子的可用内存定。同时最多解码 3 张，低端盒子也不会内存暴涨。
 */
object MangaImages {
    /** 解码后的宽度上限（像素）；够电视 1080P 放大两三倍看清字 */
    @Volatile
    var targetWidth = 1600

    private val maxMemory = Runtime.getRuntime().maxMemory()
    /** 内存小的盒子（≤ 256MB）用 RGB_565，省一半内存（漫画多是黑白，看不出差别） */
    private val lowMemory = maxMemory <= 256L * 1024 * 1024
    private val cache = object : LruCache<String, ImageBitmap>((maxMemory / 5).coerceIn(24L * 1024 * 1024, 160L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * (if (lowMemory) 2 else 4)
    }
    private val gate = Semaphore(3)

    fun cached(page: MangaPage): ImageBitmap? = cache.get(page.cacheKey)

    /** 失败抛异常（界面显示「加载失败，重试」） */
    suspend fun load(context: Context, page: MangaPage): ImageBitmap {
        cache.get(page.cacheKey)?.let { return it }
        return gate.withPermit {
            cache.get(page.cacheKey) ?: pdfPage(page)?.let { i ->
                MangaArchive.renderPdf(context, page.url, i, targetWidth).asImageBitmap().also { cache.put(page.cacheKey, it) }
            } ?: run {
                val bytes = MangaFetcher.bytes(context, page)
                val bmp = withContext(Dispatchers.Default) { decode(bytes) } ?: throw java.io.IOException("不是图片")
                bmp.asImageBitmap().also { cache.put(page.cacheKey, it) }
            }
        }
    }

    /** 提前把附近几页加载好（翻页时不用等） */
    suspend fun preload(context: Context, pages: List<MangaPage>) {
        for (p in pages) if (cache.get(p.cacheKey) == null) runCatching { load(context, p) }
    }

    /** 缩略图：小尺寸，单独缓存 */
    private val thumbs = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cachedThumb(page: MangaPage): ImageBitmap? = thumbs.get(page.cacheKey)

    suspend fun thumb(context: Context, page: MangaPage): ImageBitmap? {
        thumbs.get(page.cacheKey)?.let { return it }
        // 大图已经在缓存里：直接缩，不用重新下载
        val big = cache.get(page.cacheKey)
        return gate.withPermit {
            runCatching {
                val pdf = pdfPage(page)
                val bmp = if (pdf != null && big == null) {
                    MangaArchive.renderPdf(context, page.url, pdf, 240)
                } else if (big != null) {
                    val b = big.asAndroidBitmap()
                    val w = 240
                    Bitmap.createScaledBitmap(b, w, (b.height.toLong() * w / b.width).toInt().coerceIn(1, 2400), true)
                } else {
                    val bytes = MangaFetcher.bytes(context, page)
                    withContext(Dispatchers.Default) { decode(bytes, 240, 2400) }
                }
                bmp?.asImageBitmap()?.also { thumbs.put(page.cacheKey, it) }
            }.getOrNull()
        }
    }

    private fun pdfPage(page: MangaPage): Int? = page.entry?.takeIf { it.startsWith(MangaArchive.PDF) }?.removePrefix(MangaArchive.PDF)?.toIntOrNull()

    /**
     * 解码：宽度不超过 maxWidth（2 的倍数缩小，再精确缩到不超过），高度不超过 maxHeight
     * （太长的条漫图再缩一点，单张 bitmap 太大画不出来）。
     */
    fun decode(bytes: ByteArray, maxWidth: Int = targetWidth, maxHeight: Int = 12_000): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return decodeOther(bytes, maxWidth)
        // 按宽度和高度分别要缩多少，取大的
        val scale = maxOf(w.toFloat() / maxWidth, h.toFloat() / maxHeight, 1f)
        var sample = 1
        while (sample * 2 <= scale) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (lowMemory) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return decodeOther(bytes, maxWidth)
        val tw = (w / scale).toInt().coerceAtLeast(1)
        if (bmp.width <= tw * 1.15f) return bmp
        val th = (bmp.height.toLong() * tw / bmp.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, tw, th, true).also { if (it !== bmp) bmp.recycle() }
    }

    /** BitmapFactory 不认识的格式（AVIF、HEIC）：交给系统 ImageDecoder */
    private fun decodeOther(bytes: ByteArray, maxWidth: Int): Bitmap? =
        com.aisub.player.ui.ImageLoader.decode(bytes, maxWidth * 2)

    /** 根据图片魔数识别真实扩展名与 MIME，保持原图无损落盘。 */
    internal fun sniffImageFormat(bytes: ByteArray, hintName: String? = null): Pair<String, String> {
        if (bytes.size >= 12) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            val b3 = bytes[3].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return "jpg" to "image/jpeg"
            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return "png" to "image/png"
            if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) return "gif" to "image/gif"
            if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
            ) return "webp" to "image/webp"
            if (bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
                bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
            ) {
                val brand = String(bytes, 8, 4, Charsets.US_ASCII)
                if (brand == "avif" || brand == "avis") return "avif" to "image/avif"
                if (brand == "heic" || brand == "heix" || brand == "mif1") return "heic" to "image/heic"
            }
        }
        val ext = hintName?.substringBefore('?')?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (ext) {
            "png" -> "png" to "image/png"
            "webp" -> "webp" to "image/webp"
            "gif" -> "gif" to "image/gif"
            "avif" -> "avif" to "image/avif"
            else -> "jpg" to "image/jpeg"
        }
    }

    /**
     * 将漫画指定页原图无损保存到系统相册 `Pictures/译幕`（PDF 页面高分辨率渲染为 PNG；断网/失效时回退保存已解码缓存图）。
     * @return 保存后的文件名。
     */
    suspend fun savePageToGallery(
        context: Context,
        bookTitle: String,
        page: MangaPage,
        pageIndex: Int,
    ): String = withContext(Dispatchers.IO) {
        val pdfIdx = pdfPage(page)
        val (rawBytes, extAndMime) = if (pdfIdx != null) {
            val bmp = MangaArchive.renderPdf(context, page.url, pdfIdx, 2400)
            val bos = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)) {
                throw IOException("无法编码 PDF 页面图片")
            }
            bos.toByteArray() to ("png" to "image/png")
        } else {
            val fetched = runCatching { MangaFetcher.bytes(context, page) }.getOrNull()?.takeIf { it.isNotEmpty() }
            if (fetched != null) {
                fetched to sniffImageFormat(fetched, page.entry ?: page.url)
            } else {
                val cachedBmp = cached(page)?.asAndroidBitmap()
                    ?: throw IOException("无法读取该页图片数据")
                val bos = ByteArrayOutputStream()
                if (!cachedBmp.compress(Bitmap.CompressFormat.PNG, 100, bos)) {
                    throw IOException("无法编码缓存图片")
                }
                bos.toByteArray() to ("png" to "image/png")
            }
        }

        val (ext, mime) = extAndMime
        val safeTitle = bookTitle.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(48).ifEmpty { "漫画" }
        val pageLabel = (pageIndex + 1).toString().padStart(3, '0')
        val fileName = "${safeTitle}_P${pageLabel}_${System.currentTimeMillis() % 100000}.$ext"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/译幕")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法写入系统相册")
            try {
                resolver.openOutputStream(uri)?.use { out -> out.write(rawBytes) }
                    ?: throw IOException("无法打开相册输出流")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "译幕",
            ).apply { mkdirs() }
            val dst = File(dir, fileName)
            dst.outputStream().use { out -> out.write(rawBytes) }
            runCatching {
                MediaScannerConnection.scanFile(context, arrayOf(dst.absolutePath), arrayOf(mime), null)
            }
        }
        fileName
    }
}
