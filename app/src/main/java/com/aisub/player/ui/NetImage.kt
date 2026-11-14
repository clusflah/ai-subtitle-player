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
    /** 加载失败的地址 → 失败时间。一段时间内不重复请求，过后（比如网络恢复了）再试 */
    private val failed = HashMap<String, Long>()
    private val gate = Semaphore(4)

    fun cached(url: String, maxSize: Int): ImageBitmap? = cache.get("$maxSize|$url")

    suspend fun load(context: Context, url: String, maxSize: Int): ImageBitmap? {
        val key = "$maxSize|$url"
        cache.get(key)?.let { return it }
        val failedAt = synchronized(failed) { failed[key] }
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_MS) return null
        return gate.withPermit {
            cache.get(key) ?: withContext(Dispatchers.IO) {
                runCatching {
                    decode(RemoteFiles.read(context, url, MAX_BYTES), maxSize)
                }.getOrNull()?.asImageBitmap()
            }?.also { cache.put(key, it) } ?: run {
                synchronized(failed) { failed[key] = System.currentTimeMillis() }
                null
            }
        }
    }

    /** 降采样解码，最长边不超过 maxSize；Android 9+ 用 ImageDecoder，支持 HEIC 并自动按 EXIF 旋转。 */
    fun decode(bytes: ByteArray, maxSize: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeApi28(bytes, maxSize)?.let { return it }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), maxSize) }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val orientation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            exifOrientationApi24(bytes)
        } else {
            jpegExifOrientation(bytes)
        }
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun decodeApi28(bytes: ByteArray, maxSize: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxSize) decoder.setTargetSampleSize(sampleSize(longest, maxSize))
        }
    }.getOrNull()

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.N)
    private fun exifOrientationApi24(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** 兼容 API 23：直接从 JPEG APP1 EXIF 头解析 Orientation (0x0112)，避免落盘临时文件。 */
    internal fun jpegExifOrientation(bytes: ByteArray): Int {
        if (bytes.size < 16 || (bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) {
            return ExifInterface.ORIENTATION_NORMAL
        }
        var pos = 2
        while (pos + 1 < bytes.size) {
            if ((bytes[pos].toInt() and 0xFF) != 0xFF) break
            while (pos < bytes.size && (bytes[pos].toInt() and 0xFF) == 0xFF) pos++
            if (pos >= bytes.size) break
            val marker = bytes[pos++].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) break
            if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD8) continue
            if (pos + 2 > bytes.size) break
            val segLen = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            if (segLen < 2 || pos + segLen > bytes.size) break
            if (marker == 0xE1 && segLen >= 16 &&
                bytes[pos + 2] == 0x45.toByte() && bytes[pos + 3] == 0x78.toByte() &&
                bytes[pos + 4] == 0x69.toByte() && bytes[pos + 5] == 0x66.toByte() &&
                bytes[pos + 6] == 0.toByte() && bytes[pos + 7] == 0.toByte()
            ) {
                val tiff = pos + 8
                val tiffEnd = pos + segLen
                if (tiff + 8 <= tiffEnd) {
                    val le = bytes[tiff] == 0x49.toByte() && bytes[tiff + 1] == 0x49.toByte()
                    val be = bytes[tiff] == 0x4D.toByte() && bytes[tiff + 1] == 0x4D.toByte()
                    if (le || be) {
                        fun u16(off: Int): Int {
                            val b0 = bytes[off].toInt() and 0xFF
                            val b1 = bytes[off + 1].toInt() and 0xFF
                            return if (le) b0 or (b1 shl 8) else (b0 shl 8) or b1
                        }
                        fun u32(off: Int): Long {
                            val b0 = (bytes[off].toInt() and 0xFF).toLong()
                            val b1 = (bytes[off + 1].toInt() and 0xFF).toLong()
                            val b2 = (bytes[off + 2].toInt() and 0xFF).toLong()
                            val b3 = (bytes[off + 3].toInt() and 0xFF).toLong()
                            return if (le) b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                            else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
                        }
                        if (u16(tiff + 2) == 42) {
                            val ifdOff = u32(tiff + 4)
                            if (ifdOff in 8L..(tiffEnd - tiff - 2L)) {
                                val ifd = tiff + ifdOff.toInt()
                                val count = u16(ifd)
                                for (i in 0 until count) {
                                    val entry = ifd + 2 + i * 12
                                    if (entry + 12 > tiffEnd) break
                                    if (u16(entry) == 0x0112 && u16(entry + 2) == 3 && u32(entry + 4) >= 1L) {
                                        return u16(entry + 8)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            pos += segLen
        }
        return ExifInterface.ORIENTATION_NORMAL
    }

    private fun sampleSize(longest: Int, maxSize: Int): Int {
        var s = 1
        while (longest / (s * 2) >= maxSize) s *= 2
        return s
    }

    private const val MAX_BYTES = 40 * 1024 * 1024
    private const val RETRY_MS = 2 * 60_000L
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
    /** 加载前和失败时显示灰底；透明图片（标题图）叠在别的图上时关掉 */
    background: Boolean = true,
    /** 地址为空或加载失败时调用（海报墙用来改查在线海报） */
    onError: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val image by produceState(url?.let { ImageLoader.cached(it, maxSize) }, url, maxSize) {
        if (url != null && value == null) value = ImageLoader.load(context, url, maxSize)
        if (value == null) onError?.invoke()
    }
    Box(if (background) modifier.background(MaterialTheme.colorScheme.surfaceVariant) else modifier, contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        } else if (fallback != null) {
            Icon(fallback, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(32.dp))
        }
    }
}
