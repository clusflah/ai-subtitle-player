package com.aisub.player.asr

import android.content.Context
import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import com.aisub.player.desktop.DesktopBinaryFinder
import com.aisub.player.live.LiveHeaders
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.cloud.CloudRegistry
import java.io.File
import java.io.IOException

/** 读取视频数据失败（不是格式解不了）：网络、直链过期这类，重连可能就好了 */
class SourceReadException(message: String, cause: Throwable) : IOException(message, cause) {
    /** 服务器返回的 HTTP 状态码（403 等）；不是 HTTP 错误时为 null */
    val httpCode: Int?
        get() = generateSequence(cause) { it.cause }
            .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
}

/**
 * Windows 桌面版备用/直播音频解码器（基于 FFmpeg）。
 */
@UnstableApi
class ExoAudioDecoder(
    private val context: Context,
    private val uri: Uri,
    private val dataSourceFactory: DataSource.Factory,
    private val mediaItem: MediaItem = MediaItem.fromUri(uri),
    private val lowestBitrate: Boolean = false,
    private val onTick: ((Player, Long) -> Unit)? = null,
    private val diagnostics: Boolean = false,
    private val onCues: ((Player, List<androidx.media3.common.text.Cue>) -> Unit)? = null,
    private val captionTrack: (Format) -> Boolean = { true },
) {
    fun decode(
        targetRate: Int = 16_000,
        startMs: Long = 0,
        isActive: () -> Boolean,
        onProgress: (Float) -> Unit,
        onChunk: (FloatArray) -> Unit,
    ) {
        if (onCues != null) {
            throw IOException("桌面端使用主播放器字幕轨")
        }
        val ffmpeg = DesktopBinaryFinder.ensureFfmpeg()
            ?: throw IOException("未找到 FFmpeg 解码组件")

        val rawUri = uri.toString()
        val inputPath: String
        val headers = LinkedHashMap<String, String>()
        if (CloudRegistry.isCloud(rawUri)) {
            val link = CloudRegistry.resolve(rawUri)
            inputPath = link.url
            headers.putAll(link.headers)
        } else if (com.aisub.player.telegram.Telegram.isTelegram(rawUri)) {
            inputPath = com.aisub.player.media.MediaProxy(dataSourceFactory).register(uri, "video.mp4")
        } else if (rawUri.startsWith("http://") || rawUri.startsWith("https://")) {
            val routed = Endpoints.rewrite(rawUri)
            inputPath = routed
            headers.putAll(AuthRegistry.headersFor(routed))
            headers.putAll(LiveHeaders.headersFor(rawUri))
        } else {
            val f = android.content.ContentResolver.resolveFile(uri) ?: uri.toJavaFile() ?: File(uri.path ?: rawUri)
            inputPath = f.absolutePath
        }

        val dummyPlayer = ExoPlayer.Builder(context).build()
        try {
            AudioDecoder.decodeWithFfmpeg(
                ffmpegExe = ffmpeg,
                inputPath = inputPath,
                headers = headers,
                targetRate = targetRate,
                startMs = startMs,
                isActive = isActive,
                onProgress = onProgress,
                onChunk = onChunk,
                onEmittedMs = { emittedMs ->
                    onTick?.invoke(dummyPlayer, emittedMs)
                },
            )
        } catch (e: AudioDecoder.NoAudioTrackException) {
            // 保持与原版一致：没有音轨时不弹 IO 错误，由上层决定
        } catch (e: IOException) {
            throw SourceReadException("读取视频失败：${e.message}", e)
        } finally {
            runCatching { dummyPlayer.release() }
        }
    }
}
