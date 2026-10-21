package com.aisub.player.asr

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.aisub.player.remote.AuthRegistry
import java.nio.ByteOrder

/**
 * 用系统自带的 MediaExtractor + MediaCodec 把视频的音轨解码成 16kHz 单声道 float PCM，
 * 不依赖 FFmpeg。解码是流式的：每解出一块就回调一次 onChunk。
 */
class AudioDecoder(private val context: Context, private val uri: Uri) {

    class NoAudioTrackException : Exception("视频里没有可识别的音轨")

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
        val extractor = MediaExtractor()
        // 远程文件（WebDAV）需要带鉴权头
        extractor.setDataSource(context, uri, AuthRegistry.headersFor(uri.toString()).ifEmpty { null })
        var codec: MediaCodec? = null
        try {
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw NoAudioTrackException()
            extractor.selectTrack(trackIndex)
            val inFormat = extractor.getTrackFormat(trackIndex)
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inFormat, null, null, 0)
            codec.start()

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
