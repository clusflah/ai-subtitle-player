package com.aisub.player.asr

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.cloud.CloudRegistry
import java.nio.ByteOrder

/**
 * 用系统自带的 MediaExtractor + MediaCodec 把视频的音轨解码成 16kHz 单声道 float PCM，
 * 不依赖 FFmpeg。解码是流式的：每解出一块就回调一次 onChunk。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioDecoder(
    private val context: Context,
    private val uri: Uri,
    /** 系统解码器不支持时，备用的 ExoPlayer + FFmpeg 解码通道用的数据源 */
    private val fallbackDataSource: androidx.media3.datasource.DataSource.Factory? = null,
) {

    class NoAudioTrackException : Exception("视频里没有可识别的音轨")

    /** 系统 MediaCodec 处理不了（格式不支持、容器打不开）：改走备用通道。 */
    private class UnsupportedBySystem(message: String) : Exception(message)

    /**
     * @param onChunk 每块重采样后的音频
     * @param onProgress 0..1 的解码进度
     * @param isActive 返回 false 时提前停止（用于取消）
     */
    fun decode(
        targetRate: Int = 16_000,
        isActive: () -> Boolean,
        onProgress: (Float) -> Unit,
        onChunk: (FloatArray) -> Unit,
    ) {
        try {
            decodeWithMediaCodec(targetRate, isActive, onProgress, onChunk)
        } catch (e: Exception) {
            // 系统解析器有时会直接丢掉自己不支持的音轨（例如 MKV 里的 AC3），表现为"没有音轨"，
            // 所以这两种情况都再用 ExoPlayer + FFmpeg 试一次
            if (e !is UnsupportedBySystem && e !is NoAudioTrackException) throw e
            val factory = fallbackDataSource ?: throw e
            var gotAudio = false
            ExoAudioDecoder(context, uri, factory).decode(targetRate, isActive, onProgress) { chunk ->
                gotAudio = true
                onChunk(chunk)
            }
            if (!gotAudio && isActive()) throw NoAudioTrackException()
        }
    }

    private fun decodeWithMediaCodec(
        targetRate: Int,
        isActive: () -> Boolean,
        onProgress: (Float) -> Unit,
        onChunk: (FloatArray) -> Unit,
    ) {
        val extractor = MediaExtractor()
        try {
            if (CloudRegistry.isCloud(uri.toString())) {
                // 网盘：先换成直链，带上必需的 UA/Cookie
                val link = CloudRegistry.resolve(uri.toString())
                extractor.setDataSource(context, Uri.parse(link.url), link.headers.ifEmpty { null })
            } else {
                // 远程文件（WebDAV）需要带鉴权头
                extractor.setDataSource(context, uri, AuthRegistry.headersFor(uri.toString()).ifEmpty { null })
            }
        } catch (e: java.io.IOException) {
            extractor.release()
            throw UnsupportedBySystem("系统无法打开这个文件：${e.message}")
        }
        var codec: MediaCodec? = null
        try {
            val audioTracks = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            if (audioTracks.isEmpty()) throw NoAudioTrackException()
            // 优先选系统真正有解码器的音轨（多音轨视频里常有 AC3/DTS 和 AAC 并存）
            val codecs = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            val trackIndex = audioTracks.firstOrNull { codecs.findDecoderForFormat(extractor.getTrackFormat(it)) != null }
                ?: throw UnsupportedBySystem("音轨格式 ${extractor.getTrackFormat(audioTracks[0]).getString(MediaFormat.KEY_MIME)} 系统不支持")
            extractor.selectTrack(trackIndex)
            val inFormat = extractor.getTrackFormat(trackIndex)
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!

            codec = try {
                MediaCodec.createDecoderByType(mime).also {
                    it.configure(inFormat, null, null, 0)
                    it.start()
                }
            } catch (e: Exception) {
                throw UnsupportedBySystem("系统解码器无法处理 $mime")
            }

            var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = LinearResampler(sampleRate, targetRate)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (isActive()) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        resampler = LinearResampler(sampleRate, targetRate)
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIndex)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            val mono = toMono(buf, channels, encoding)
                            val out = resampler.process(mono)
                            if (out.isNotEmpty()) onChunk(out)
                            if (durationUs > 0) {
                                onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            onProgress(1f)
                            break
                        }
                    }
                }
            }
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }

    private fun toMono(buf: java.nio.ByteBuffer, channels: Int, encoding: Int): FloatArray {
        return if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = buf.asFloatBuffer()
            val frames = fb.remaining() / channels
            FloatArray(frames) { i ->
                var sum = 0f
                for (c in 0 until channels) sum += fb.get(i * channels + c)
                sum / channels
            }
        } else {
            val sb = buf.asShortBuffer()
            val frames = sb.remaining() / channels
            FloatArray(frames) { i ->
                var sum = 0f
                for (c in 0 until channels) sum += sb.get(i * channels + c)
                sum / channels / 32768f
            }
        }
    }
}

/** 流式线性插值重采样。对语音识别来说精度足够。 */
class LinearResampler(inRate: Int, outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private val passthrough = inRate == outRate

    // 下一个输出样本在输入中的位置；-1 表示上一块的最后一个样本
    private var pos = 0.0
    private var prev = 0f

    fun process(input: FloatArray): FloatArray {
        if (passthrough || input.isEmpty()) return input
        val n = input.size
        val out = ArrayList<Float>((n / step).toInt() + 2)
        while (pos < n - 1) {
            val i = kotlin.math.floor(pos).toInt()
            val frac = (pos - i).toFloat()
            val a = if (i < 0) prev else input[i]
            val b = input[i + 1]
            out.add(a + (b - a) * frac)
            pos += step
        }
        pos -= n
        prev = input[n - 1]
        return out.toFloatArray()
    }
}
