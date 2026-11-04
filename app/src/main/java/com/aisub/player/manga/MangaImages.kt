package com.aisub.player.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

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
            cache.get(page.cacheKey) ?: run {
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
                val bmp = if (big != null) {
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
}
