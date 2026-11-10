package com.aisub.player.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.cloud.CloudRegistry
import java.io.Closeable

/**
 * 从视频里取某一时刻的画面：进度条缩略图、片段预览、书签图、截图的备用方案。
 * 本地文件、WebDAV（带鉴权头）、网盘（先换直链）都能用；网络文件每张要发几次范围请求，会慢一些。
 * 不是线程安全的，调用方要保证同一时间只有一个线程在用。
 */
class FrameGrabber(private val context: Context, private val uri: String) : Closeable {
    private var retriever: MediaMetadataRetriever? = null
    private var failed = false

    private fun open(): MediaMetadataRetriever? {
        retriever?.let { return it }
        if (failed) return null
        return try {
            MediaMetadataRetriever().also { r ->
                when {
                    CloudRegistry.isCloud(uri) -> CloudRegistry.resolve(uri).let { r.setDataSource(it.url, it.headers) }
                    uri.startsWith("http://") || uri.startsWith("https://") -> {
                        val routed = Endpoints.rewrite(uri)
                        r.setDataSource(routed, AuthRegistry.headersFor(routed))
                    }
                    else -> r.setDataSource(context, Uri.parse(uri))
                }
                retriever = r
            }
        } catch (e: Exception) {
            android.util.Log.w("AiSub", "无法读取画面：$uri", e)
            failed = true
            null
        }
    }

    /**
     * @param exact true 取最接近的那一帧（慢，用于截图、书签）；false 取附近的关键帧（快，用于拖动预览）
     * @param maxWidth 缩小到这个宽度以内；0 为原尺寸
     */
    fun frameAt(positionMs: Long, exact: Boolean = false, maxWidth: Int = 320): Bitmap? {
        val r = open() ?: return null
        val us = positionMs.coerceAtLeast(0) * 1000
        val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        return try {
            if (maxWidth > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                r.getScaledFrameAtTime(us, option, maxWidth, maxWidth)
            } else {
                r.getFrameAtTime(us, option)?.let { full -> if (maxWidth > 0) scale(full, maxWidth) else full }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun durationMs(): Long =
        open()?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

    override fun close() {
        runCatching { retriever?.release() }
        retriever = null
    }

    private fun scale(b: Bitmap, maxWidth: Int): Bitmap {
        if (b.width <= maxWidth) return b
        val h = (b.height * maxWidth.toFloat() / b.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, maxWidth, h, true).also { if (it !== b) b.recycle() }
    }
}
