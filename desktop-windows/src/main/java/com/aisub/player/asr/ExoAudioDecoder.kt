package com.aisub.player.asr

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.aisub.player.desktop.DesktopBinaryFinder
import com.aisub.player.desktop.DesktopLiveRelay
import com.aisub.player.live.LiveHeaders
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.cloud.CloudRegistry
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 读取视频数据失败（不是格式解不了）：网络、直链过期这类，重连可能就好了 */
class SourceReadException(message: String, cause: Throwable) : IOException(message, cause) {
    /** 服务器返回的 HTTP 状态码（403 等）；不是 HTTP 错误时为 null */
    val httpCode: Int?
        get() = generateSequence(cause) { it.cause }
            .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
}

/**
 * 轻量级无线程时间线适配器：
 * 将 [DesktopLiveRelay] 直播时移缓冲区上的音频流起点与已解码时长映射为 Media3 [Player]，
 * 供 `MainViewModel.runLive` 通过 `LiveTimeline.keyOf(p)` 建立精确到毫秒的音画对照锚点。
 */
internal open class RelayTimelinePlayer(
    private val mediaItemRef: MediaItem,
    private val windowStartUtcMs: Long,
    private val latestTimelineMsProvider: () -> Long = { 0L },
    private val streamStartContentMs: Long = 0L,
) : Player {
    @Volatile
    var emittedMs: Long = 0L

    private val singleTimeline = object : Timeline() {
        override val windowCount: Int get() = 1
        override fun getWindow(windowIndex: Int, window: Window): Window {
            window.windowStartTimeMs = windowStartUtcMs
            window.positionInFirstPeriodUs = 0L
            window.durationUs = C.TIME_UNSET
            window.isSeekable = false
            window.isDynamic = true
            return window
        }
    }

    override val currentTimeline: Timeline
        get() = if (windowStartUtcMs == C.TIME_UNSET) Timeline.EMPTY else singleTimeline

    override val currentPosition: Long get() = emittedMs
    override val bufferedPosition: Long get() = emittedMs
    override val currentLiveOffset: Long
        get() = if (windowStartUtcMs == C.TIME_UNSET) {
            C.TIME_UNSET
        } else {
            (latestTimelineMsProvider() - (streamStartContentMs + emittedMs)).coerceAtLeast(0L)
        }
    override val duration: Long get() = C.TIME_UNSET
    override val isCurrentMediaItemLive: Boolean get() = true
    override val isCurrentMediaItemSeekable: Boolean get() = false

    override val mediaItemCount: Int get() = 1
    override val currentMediaItemIndex: Int get() = 0
    override val currentMediaItem: MediaItem get() = mediaItemRef
    override fun getMediaItemAt(index: Int): MediaItem = mediaItemRef
    override fun getMediaMetadata(): MediaMetadata = mediaItemRef.mediaMetadata

    override var playWhenReady: Boolean = true
    override val playbackState: Int get() = Player.STATE_READY
    override val isPlaying: Boolean get() = true
    override val playerError: PlaybackException? get() = null
    override var repeatMode: Int = Player.REPEAT_MODE_OFF
    override var shuffleModeEnabled: Boolean = false
    override var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    override var volume: Float = 0f
    override var trackSelectionParameters: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
    override val currentTracks: Tracks get() = Tracks.EMPTY
    override val videoSize: VideoSize get() = VideoSize.UNKNOWN
    override val currentManifest: Any? get() = null

    override fun addListener(listener: Player.Listener) {}
    override fun removeListener(listener: Player.Listener) {}
    override fun setMediaItem(mediaItem: MediaItem) {}
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {}
    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {}
    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {}
    override fun addMediaItem(mediaItem: MediaItem) {}
    override fun addMediaItems(mediaItems: List<MediaItem>) {}
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {}
    override fun clearMediaItems() {}
    override fun prepare() {}
    override fun play() {}
    override fun pause() {}
    override fun stop() {}
    override fun release() {}
    override fun seekTo(positionMs: Long) {}
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {}
    override fun seekToDefaultPosition() {}
    override fun seekToDefaultPosition(mediaItemIndex: Int) {}
    override fun seekToNext() {}
    override fun seekToPrevious() {}
    override fun seekToNextMediaItem() {}
    override fun seekToPreviousMediaItem() {}
    override fun hasNextMediaItem(): Boolean = false
    override fun hasPreviousMediaItem(): Boolean = false
}

/**
 * Windows 桌面版备用/直播音频解码器：
 * - 直播流优先接入 [DesktopLiveRelay] 内存环形缓冲区，复用播放器同一路连接并生成真实 [com.aisub.player.live.LiveTimeline] 对照点；
 * - 非中继流回退至 FFmpeg 直连解码。
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

        if (startMs == 0L && DesktopLiveRelay.shouldRelay(uri, mediaItem)) {
            val relaySession = DesktopLiveRelay.acquire(inputPath, headers)
            if (relaySession != null) {
                var relayUsed = false
                try {
                    val asrStream = relaySession.openAsrStream(leadBackMs = 1_000L, waitMs = 8_000L)
                    if (asrStream != null) {
                        relayUsed = true
                        decodeFromRelayStream(
                            ffmpegExe = ffmpeg,
                            session = relaySession,
                            asrStream = asrStream,
                            targetRate = targetRate,
                            isActive = isActive,
                            onChunk = onChunk,
                        )
                        return
                    }
                } catch (e: AudioDecoder.NoAudioTrackException) {
                    return
                } catch (e: IOException) {
                    if (relayUsed) {
                        throw SourceReadException("读取直播音频流失败：${e.message}", e)
                    }
                } finally {
                    DesktopLiveRelay.release(relaySession)
                }
            }
        }

        val fallbackPlayer = RelayTimelinePlayer(mediaItemRef = mediaItem, windowStartUtcMs = C.TIME_UNSET)
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
                    fallbackPlayer.emittedMs = emittedMs
                    onTick?.invoke(fallbackPlayer, emittedMs)
                },
            )
        } catch (_: AudioDecoder.NoAudioTrackException) {
            // 保持与原版一致：没有音轨时不弹 IO 错误，由上层决定
        } catch (e: IOException) {
            throw SourceReadException("读取视频失败：${e.message}", e)
        }
    }

    private fun decodeFromRelayStream(
        ffmpegExe: String,
        session: DesktopLiveRelay.RelaySession,
        asrStream: com.aisub.player.desktop.RelayRingBuffer.RelayInputStream,
        targetRate: Int,
        isActive: () -> Boolean,
        onChunk: (FloatArray) -> Unit,
    ) {
        val streamStartMs = asrStream.startContentMs
        val relayPlayer = RelayTimelinePlayer(
            mediaItemRef = mediaItem,
            windowStartUtcMs = DesktopLiveRelay.EPOCH_MS + streamStartMs,
            latestTimelineMsProvider = { session.latestTimelineMs },
            streamStartContentMs = streamStartMs,
        )
        Log.i("Voxtitle-sync", "直播识别解码接入时移中继[${session.id}]: 起点=${streamStartMs}ms, 最新=${session.latestTimelineMs}ms")

        val cmd = listOf(
            ffmpegExe,
            "-hide_banner",
            "-loglevel", "error",
            "-f", "mpegts",
            "-i", "pipe:0",
            "-vn",
            "-ac", "1",
            "-ar", targetRate.toString(),
            "-af", "aresample=async=1000",
            "-f", "f32le",
            "-acodec", "pcm_f32le",
            "pipe:1",
        )
        val proc = ProcessBuilder(cmd).start()
        val stderrLines = StringBuilder()

        val feedThread = Thread({
            runCatching {
                asrStream.use { src ->
                    proc.outputStream.use { dst ->
                        val copyBuf = ByteArray(16 * 1024)
                        while (isActive() && !session.isStopped) {
                            val n = src.read(copyBuf)
                            if (n < 0) break
                            if (n > 0) {
                                dst.write(copyBuf, 0, n)
                                dst.flush()
                            }
                        }
                    }
                }
            }
        }, "Voxtitle-RelayAsrFeed-${session.id}").apply { isDaemon = true; start() }

        val errThread = Thread({
            runCatching {
                proc.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    synchronized(stderrLines) {
                        if (stderrLines.length < 3000) stderrLines.appendLine(line)
                    }
                }
            }
        }, "Voxtitle-RelayAsrErr-${session.id}").apply { isDaemon = true; start() }

        var totalSamples = 0L
        var lastTickMs = System.currentTimeMillis()
        try {
            val buf = ByteArray(16384)
            var leftover = 0
            val ins = proc.inputStream
            while (isActive()) {
                val n = ins.read(buf, leftover, buf.size - leftover)
                if (n < 0) break
                val totalBytes = leftover + n
                val floatCount = totalBytes / 4
                if (floatCount > 0) {
                    val validBytes = floatCount * 4
                    val fb = ByteBuffer.wrap(buf, 0, validBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val floats = FloatArray(floatCount)
                    fb.get(floats)
                    onChunk(floats)
                    totalSamples += floatCount
                    val emittedMs = totalSamples * 1000L / targetRate
                    relayPlayer.emittedMs = emittedMs
                    val now = System.currentTimeMillis()
                    if (now - lastTickMs >= 240L) {
                        lastTickMs = now
                        onTick?.invoke(relayPlayer, emittedMs)
                    }
                    leftover = totalBytes - validBytes
                    if (leftover > 0) {
                        System.arraycopy(buf, validBytes, buf, 0, leftover)
                    }
                } else {
                    leftover = totalBytes
                }
            }
        } finally {
            runCatching { asrStream.close() }
            runCatching { proc.destroyForcibly() }
            feedThread.join(500)
            errThread.join(500)
        }

        if (!isActive()) return
        if (totalSamples == 0L) {
            val errText = synchronized(stderrLines) { stderrLines.toString() }
            if (errText.contains("does not contain any stream") || errText.contains("matches no streams")) {
                throw AudioDecoder.NoAudioTrackException()
            }
            throw IOException(errText.lines().lastOrNull { it.isNotBlank() } ?: "直播音频解码未产出数据")
        }
    }
}
