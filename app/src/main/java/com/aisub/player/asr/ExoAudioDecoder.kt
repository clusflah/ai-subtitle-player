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
) {
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
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setLooper(thread.looper)
                .build()
            // 只要音频
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
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
            player.volume = 0f
            player.setMediaItem(MediaItem.fromUri(uri), startMs)
            player.prepare()
            player.play()
        }

        try {
            while (!done.await(300, TimeUnit.MILLISECONDS)) {
                if (!isActive()) break
                handler.post {
                    val d = player.duration
                    if (d > 0) onProgress((sink.writtenUs / 1000f / d).coerceIn(0f, 1f))
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
        private var startUs = C.TIME_UNSET
        private var framesWritten = 0L
        private var ended = false
        private var params = PlaybackParameters.DEFAULT
        private var attributes = AudioAttributes.DEFAULT
        private var skipSilence = false

        @Volatile
        var writtenUs = 0L

        override fun setListener(listener: AudioSink.Listener) {
            this.listener = listener
        }

        override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

        override fun getFormatSupport(format: Format): Int =
            if (format.sampleMimeType == "audio/raw" &&
                (format.pcmEncoding == C.ENCODING_PCM_16BIT || format.pcmEncoding == C.ENCODING_PCM_FLOAT)
            ) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED

        override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
            if (startUs == C.TIME_UNSET) AudioSink.CURRENT_POSITION_NOT_SET else startUs + framesToUs(framesWritten)

        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
            sampleRate = inputFormat.sampleRate
            channels = inputFormat.channelCount.coerceAtLeast(1)
            encoding = inputFormat.pcmEncoding
            resampler = LinearResampler(sampleRate, targetRate)
        }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            if (startUs == C.TIME_UNSET) startUs = presentationTimeUs
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
            val out = resampler?.process(mono) ?: mono
            if (out.isNotEmpty()) onChunk(out)
            return true
        }

        private fun framesToUs(frames: Long) = if (sampleRate > 0) frames * 1_000_000L / sampleRate else 0L

        override fun playToEndOfStream() {
            ended = true
        }

        override fun isEnded() = ended
        override fun hasPendingData() = false
        override fun play() {}
        override fun pause() {}
        override fun handleDiscontinuity() {}

        override fun flush() {
            startUs = C.TIME_UNSET
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
