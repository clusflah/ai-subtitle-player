package com.aisub.player.asr

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.aisub.player.media.withAiff
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 备用音频解码：用一个不出声的 ExoPlayer（带 FFmpeg 扩展）把音轨解码成 PCM。
 * 用于手机系统 MediaCodec 解不了的格式（AC3、E-AC3、DTS、TrueHD 等）和 MediaExtractor 打不开的容器。
 *
 * 原理：自定义 [AudioSink] 只收集数据不播放，并把"已播放位置"报告为已写入的长度，
 * 播放器的时钟就会跟着数据走，以解码速度而不是 1 倍速推进。
 */
@UnstableApi
class ExoAudioDecoder(
    private val context: Context,
    private val uri: Uri,
    private val dataSourceFactory: DataSource.Factory,
    /** 要解码的条目；直播要带上和屏幕播放器一样的类型（地址看不出是 HLS 时） */
    private val mediaItem: MediaItem = MediaItem.fromUri(uri),
    /** 直播识别：选最低码率，只为了声音，少占流量 */
    private val lowestBitrate: Boolean = false,
    /** 解码线程上每 300 毫秒回调一次：解码用的播放器、已交出的音频长度（毫秒）。用来对齐直播时间轴 */
    private val onTick: ((Player, Long) -> Unit)? = null,
    /** 直播识别：把这一路的下载、状态和缓冲写进日志（adb logcat 看 "Voxtitle-dec"），排查声音为什么停 */
    private val diagnostics: Boolean = false,
) {
    private fun log(message: String) {
        try {
            android.util.Log.i("Voxtitle-dec", message)
        } catch (_: RuntimeException) {
        }
    }

    /** 下载信息里只留路径最后一段，不把地址里的令牌写进日志 */
    private fun shortUri(u: Uri) = u.lastPathSegment ?: u.host ?: "?"

    private fun dataType(type: Int) = when (type) {
        C.DATA_TYPE_MANIFEST -> "列表"
        C.DATA_TYPE_MEDIA -> "分片"
        C.DATA_TYPE_MEDIA_INITIALIZATION -> "初始化"
        else -> "类型$type"
    }

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "空闲"
        Player.STATE_BUFFERING -> "缓冲中"
        Player.STATE_READY -> "就绪"
        Player.STATE_ENDED -> "结束"
        else -> "$state"
    }

    /** 加给解码用的播放器，记录每次下载和状态变化 */
    private fun diagnosticsListener() = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onLoadCompleted(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
        ) = log("下载完成 ${dataType(mediaLoadData.dataType)} ${shortUri(loadEventInfo.uri)} ${loadEventInfo.bytesLoaded}B ${loadEventInfo.loadDurationMs}ms 媒体时间 ${mediaLoadData.mediaStartTimeMs}-${mediaLoadData.mediaEndTimeMs}")

        override fun onLoadError(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) = log("下载失败 ${dataType(mediaLoadData.dataType)} ${shortUri(loadEventInfo.uri)}：${error.javaClass.simpleName} ${error.message}")

        override fun onLoadCanceled(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
            mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
        ) = log("下载取消 ${dataType(mediaLoadData.dataType)} ${shortUri(loadEventInfo.uri)}")

        override fun onPlaybackStateChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, state: Int) =
            log("状态 → ${stateName(state)}")

        override fun onIsLoadingChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, isLoading: Boolean) =
            log(if (isLoading) "开始下载" else "停止下载")

        override fun onAudioInputFormatChanged(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) = log("音轨 ${format.sampleMimeType} ${format.sampleRate}Hz ${format.channelCount}声道 码率 ${format.bitrate}")

        override fun onPositionDiscontinuity(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = log("进度跳变 ${oldPosition.positionMs} → ${newPosition.positionMs}（原因 $reason）")
    }

    /** 每 3 秒一次：播放器状态、进度、已缓冲多少、离直播最新处多远 */
    private fun logStatus(player: ExoPlayer, sink: CapturingSink) {
        val window = androidx.media3.common.Timeline.Window()
        val timeline = player.currentTimeline
        val windowInfo = if (!timeline.isEmpty) {
            timeline.getWindow(player.currentMediaItemIndex, window)
            "窗口 ${window.durationMs}ms 动态=${window.isDynamic}"
        } else "没有时间线"
        log(
            "${stateName(player.playbackState)} 下载中=${player.isLoading} 进度 ${player.currentPosition} 缓冲到 ${player.bufferedPosition}" +
                "（领先 ${player.totalBufferedDuration}ms）离直播最新处 ${player.currentLiveOffset}ms $windowInfo 已交出声音 ${sink.emitted / 16}ms",
        )
    }

    fun decode(
        targetRate: Int = 16_000,
        startMs: Long = 0,
        isActive: () -> Boolean,
        onProgress: (Float) -> Unit,
        onChunk: (FloatArray) -> Unit,
    ) {
        val thread = HandlerThread("asr-exo-decoder").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        var error: Throwable? = null
        val sink = CapturingSink(targetRate, onChunk)
        lateinit var player: ExoPlayer

        handler.post {
            val renderers = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink = sink
            }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON).setEnableDecoderFallback(true)
            player = ExoPlayer.Builder(context, renderers)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, DefaultExtractorsFactory().withAiff()))
                .setLooper(thread.looper)
                .build()
            // 只要音频
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setForceLowestBitrate(lowestBitrate)
                .build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) done.countDown()
                }

                override fun onPlayerError(e: PlaybackException) {
                    error = e
                    done.countDown()
                }
            })
            if (diagnostics) player.addAnalyticsListener(diagnosticsListener())
            player.volume = 0f
            if (startMs > 0) player.setMediaItem(mediaItem, startMs) else player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
        }

        try {
            var ticks = 0
            while (!done.await(300, TimeUnit.MILLISECONDS)) {
                if (!isActive()) break
                val logNow = diagnostics && ++ticks % 10 == 0
                handler.post {
                    val d = player.duration
                    if (d > 0) onProgress((sink.writtenUs / 1000f / d).coerceIn(0f, 1f))
                    onTick?.invoke(player, sink.emitted * 1000L / targetRate)
                    if (logNow) logStatus(player, sink)
                }
            }
        } finally {
            val released = CountDownLatch(1)
            handler.post { runCatching { player.release() }; released.countDown() }
            released.await(5, TimeUnit.SECONDS)
            thread.quitSafely()
        }
        error?.let { throw IOException("无法解码音轨：${it.message}", it) }
        if (isActive()) onProgress(1f)
    }

    /** 只收集 PCM、不播放的音频输出。 */
    private class CapturingSink(private val targetRate: Int, private val onChunk: (FloatArray) -> Unit) : AudioSink {
        private var listener: AudioSink.Listener? = null
        private var sampleRate = 0
        private var channels = 1
        private var encoding = C.ENCODING_PCM_16BIT
        private var resampler: LinearResampler? = null
        /** 最后一块音频结束处的时间戳（播放器的时间轴）；播放进度就报这个 */
        private var endUs = C.TIME_UNSET
        private var framesWritten = 0L
        private var ended = false
        private var params = PlaybackParameters.DEFAULT
        private var attributes = AudioAttributes.DEFAULT
        private var skipSilence = false

        @Volatile
        var writtenUs = 0L

        /** 交出去的样本数（目标采样率），拖动、重置时也不清零 */
        @Volatile
        var emitted = 0L

        override fun setListener(listener: AudioSink.Listener) {
            this.listener = listener
        }

        override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

        override fun getFormatSupport(format: Format): Int =
            if (format.sampleMimeType == "audio/raw" &&
                (format.pcmEncoding == C.ENCODING_PCM_16BIT || format.pcmEncoding == C.ENCODING_PCM_FLOAT)
            ) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED

        /**
         * 播放进度按音频自带的时间戳报，不按收到了多少音频自己数：直播流里缺几帧、时间戳跳一下，
         * 自己数的进度就会落后于真实时间，播放器以为缓冲了很多、不再下载新分片，
         * 手上的音频交完后进度不再前进，就一直卡着（不报错，也不会结束）。
         */
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
            if (endUs == C.TIME_UNSET) AudioSink.CURRENT_POSITION_NOT_SET else endUs

        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
            sampleRate = inputFormat.sampleRate
            channels = inputFormat.channelCount.coerceAtLeast(1)
            encoding = inputFormat.pcmEncoding
            resampler = LinearResampler(sampleRate, targetRate)
        }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            val buf = buffer.duplicate().order(ByteOrder.nativeOrder())
            val mono = if (encoding == C.ENCODING_PCM_FLOAT) {
                val fb = buf.asFloatBuffer()
                FloatArray(fb.remaining() / channels) { i -> (0 until channels).sumOf { c -> fb.get(i * channels + c).toDouble() }.toFloat() / channels }
            } else {
                val sb = buf.asShortBuffer()
                FloatArray(sb.remaining() / channels) { i -> (0 until channels).sumOf { c -> sb.get(i * channels + c).toDouble() }.toFloat() / channels / 32768f }
            }
            buffer.position(buffer.limit()) // 告诉播放器这块已经全部消费
            framesWritten += mono.size
            writtenUs = framesToUs(framesWritten) // 只算音频长度，不含播放器内部的时间偏移
            endUs = presentationTimeUs + framesToUs(mono.size.toLong())
            val out = resampler?.process(mono) ?: mono
            if (out.isNotEmpty()) {
                onChunk(out)
                emitted += out.size
            }
            return true
        }

        private fun framesToUs(frames: Long) = if (sampleRate > 0) frames * 1_000_000L / sampleRate else 0L

        override fun playToEndOfStream() {
            ended = true
        }

        override fun isEnded() = ended

        /**
         * 收到第一段音频后、结束前一直说"还有数据在播"。真的扬声器总有一点数据在放，播放器靠这个从"缓冲中"进入"播放"；
         * 这里收到就算处理完，要是说没有，播放器就一直停在缓冲中、进度不动：
         * 直播窗口往前滑时它以为缓冲越攒越多，超过上限就不再下载（约半分钟后没声音），
         * 直播字幕的对照点也按不动的进度算，字幕被对到很早的位置（比画面早十几秒）。
         */
        override fun hasPendingData() = endUs != C.TIME_UNSET && !ended
        override fun play() {}
        override fun pause() {}
        override fun handleDiscontinuity() {}

        override fun flush() {
            endUs = C.TIME_UNSET
            framesWritten = 0
            ended = false
        }

        override fun reset() = flush()
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
            params = playbackParameters
        }

        override fun getPlaybackParameters() = params
        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
            skipSilence = skipSilenceEnabled
        }

        override fun getSkipSilenceEnabled() = skipSilence
        override fun setAudioAttributes(audioAttributes: AudioAttributes) {
            attributes = audioAttributes
        }

        override fun getAudioAttributes() = attributes
        override fun setAudioSessionId(audioSessionId: Int) {}
        override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}
        override fun getAudioTrackBufferSizeUs() = C.TIME_UNSET
        override fun enableTunnelingV21() {}
        override fun disableTunneling() {}
        override fun setVolume(volume: Float) {}
    }
}
