package com.aisub.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aisub.player.media.RemoteFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

/**
 * 简单的图片加载：海报、网盘里的图片、TMDB 图片。
 * 按显示尺寸降采样后放进内存缓存；同时最多 4 个下载，避免网盘接口被刷。
 */
object ImageLoader {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val failed = HashSet<String>()
    private val gate = Semaphore(4)

    fun cached(url: String, maxSize: Int): ImageBitmap? = cache.get("$maxSize|$url")

    suspend fun load(context: Context, url: String, maxSize: Int): ImageBitmap? {
        val key = "$maxSize|$url"
        cache.get(key)?.let { return it }
        if (synchronized(failed) { key in failed }) return null
        return gate.withPermit {
            cache.get(key) ?: withContext(Dispatchers.IO) {
                runCatching {
                    decode(RemoteFiles.read(context, url, MAX_BYTES), maxSize)
                }.getOrNull()?.asImageBitmap()
            }?.also { cache.put(key, it) } ?: run {
                synchronized(failed) { failed += key }
                null
            }
        }
    }

    /** 降采样解码，最长边不超过 maxSize；Android 9+ 用 ImageDecoder，支持 HEIC 并自动按 EXIF 旋转。 */
    fun decode(bytes: ByteArray, maxSize: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > maxSize) decoder.setTargetSampleSize(sampleSize(longest, maxSize))
                }
            }.getOrNull()
            if (decoded != null) return decoded
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), maxSize) }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val rotation = runCatching {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
    }

    private fun sampleSize(longest: Int, maxSize: Int): Int {
        var s = 1
        while (longest / (s * 2) >= maxSize) s *= 2
        return s
    }

    private const val MAX_BYTES = 40 * 1024 * 1024
}

/**
 * 网络图片。url 可以是 http(s)、网盘 cloud://、content://；加载失败或没有地址时显示 fallback 图标。
 * maxSize 是解码后最长边的像素数，列表里的小图用小一点的值省内存。
 */
@Composable
fun NetImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxSize: Int = 600,
    fallback: ImageVector? = AppIcons.Movie,
) {
    val context = LocalContext.current
    val image by produceState(url?.let { ImageLoader.cached(it, maxSize) }, url, maxSize) {
        if (url != null && value == null) value = ImageLoader.load(context, url, maxSize)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        } else if (fallback != null) {
            Icon(fallback, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(32.dp))
        }
    }
}
