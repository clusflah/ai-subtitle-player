package com.aisub.player.asr.cloud

import com.aisub.player.asr.SpeechEngine
import com.aisub.player.asr.Transcriber
import com.aisub.player.subtitle.Cue
import com.aisub.player.subtitle.CueSplitter
import com.aisub.player.subtitle.TimedToken
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** 一个带时间的识别片段：词/字列表（有逐词时间戳时）或整句文本。时间为整段音频中的绝对毫秒。 */
sealed interface Recognized {
    data class Words(val tokens: List<TimedToken>, val endMs: Long) : Recognized
    data class Sentence(val text: String, val startMs: Long, val endMs: Long) : Recognized
}

/**
 * 云端识别的公共部分：把音频按几分钟一段打包成 WAV 依次上传，切点选在附近最安静的位置，
 * 避免把一个词切成两半；第一段较短，让字幕尽快出现。子类只负责"上传一段、解析结果"。
 */
abstract class ChunkedCloudEngine(
    private val firstChunkSec: Int = 60,
    private val chunkSec: Int = 240,
) : SpeechEngine {

    protected val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    private var buffer = ShortArray(SAMPLE_RATE * 60)
    private var size = 0
    private var bufferStartMs = 0L
    private var chunksSent = 0
    private var nextId = 1

    @Volatile
    private var currentCall: Call? = null

    @Volatile
    protected var cancelled = false

    /** 识别一段 WAV（16kHz 单声道 16bit）。chunkStartMs 是这段在整段音频中的起点。 */
    protected abstract fun recognize(wav: ByteArray, chunkStartMs: Long, chunkEndMs: Long): List<Recognized>

    override fun accept(samples: FloatArray, onCue: (Cue) -> Unit) {
        if (size + samples.size > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + samples.size))
        for (s in samples) buffer[size++] = (s.coerceIn(-1f, 1f) * 32767).toInt().toShort()
        val target = SAMPLE_RATE * if (chunksSent == 0) firstChunkSec else chunkSec
        if (size >= target) flush(quietestPoint(target - SAMPLE_RATE * 10, target), onCue)
    }

    override fun finish(onCue: (Cue) -> Unit) {
        if (size > SAMPLE_RATE / 2) flush(size, onCue)
    }

    override fun cancel() {
        cancelled = true
        currentCall?.cancel()
    }

    override fun close() = cancel()

    private fun flush(count: Int, onCue: (Cue) -> Unit) {
        val start = bufferStartMs
        val end = start + count * 1000L / SAMPLE_RATE
        val results = recognize(encodeWav(buffer, count), start, end)
        for (r in results) {
            val lines = when (r) {
                is Recognized.Words -> CueSplitter.split(r.tokens, r.endMs)
                is Recognized.Sentence -> CueSplitter.splitProportional(r.text, r.startMs, r.endMs)
            }
            lines.filter { it.third.isNotBlank() }.forEach { (s, e, t) -> onCue(Cue(nextId++, s, e, t)) }
        }
        System.arraycopy(buffer, count, buffer, 0, size - count)
        size -= count
        bufferStartMs = end
        chunksSent++
    }

    /** 在 [from, to) 区间内找能量最低的 100ms 窗口，返回它的中点。 */
    private fun quietestPoint(from: Int, to: Int): Int {
        val frame = SAMPLE_RATE / 10
        var best = to
        var bestEnergy = Double.MAX_VALUE
        var start = from.coerceAtLeast(0)
        while (start + frame <= to) {
            var sum = 0.0
            for (i in start until start + frame) sum += buffer[i].toDouble() * buffer[i]
            val rms = sqrt(sum / frame)
            if (rms < bestEnergy) {
                bestEnergy = rms
                best = start + frame / 2
            }
            start += frame / 2
        }
        return best
    }

    /**
     * 发请求；429 和 5xx 按 2/4/8/16/32 秒退避重试（各家文档的通用建议）。
     * 每次重试都会重新调用 build，所以请求体可以是一次性的。
     */
    protected fun <T> execute(build: () -> Request, handle: (Response) -> T): T {
        var attempt = 0
        while (true) {
            if (cancelled) throw IOException("已取消")
            val call = http.newCall(build()).also { currentCall = it }
            call.execute().use { resp ->
                if ((resp.code == 429 || resp.code >= 500) && attempt < RETRY_DELAYS_S.size) {
                    Thread.sleep(RETRY_DELAYS_S[attempt++] * 1000L)
                } else {
                    return handle(resp)
                }
            }
        }
    }

    /** 轮询型接口的等待（可被取消）。 */
    protected fun pause(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (cancelled) throw IOException("已取消")
            Thread.sleep(200)
        }
    }

    companion object {
        const val SAMPLE_RATE = Transcriber.SAMPLE_RATE
        private val RETRY_DELAYS_S = longArrayOf(2, 4, 8, 16, 32)

        /** 词列表 → 带空格的 token：西文词之间补空格，中日韩不补。 */
        fun spacedTokens(words: List<Triple<String, Long, Long>>): Pair<List<TimedToken>, Long> {
            val tokens = ArrayList<TimedToken>(words.size)
            var prev: String? = null
            for ((text, start, _) in words) {
                val needsSpace = prev != null && !CueSplitter.isCjk(prev.lastOrNull()) && !CueSplitter.isCjk(text.firstOrNull()) &&
                    text.firstOrNull()?.let { it !in CueSplitter.PUNCTUATION } != false
                tokens.add(TimedToken(if (needsSpace) " $text" else text, start))
                prev = text
            }
            return tokens to (words.lastOrNull()?.third ?: 0L)
        }
    }
}

/** 16-bit 单声道 PCM → WAV 文件字节。 */
internal fun encodeWav(samples: ShortArray, count: Int, sampleRate: Int = 16_000): ByteArray {
    val dataSize = count * 2
    val out = ByteArrayOutputStream(44 + dataSize)
    fun int32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
    fun int16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
    out.write("RIFF".toByteArray()); int32(36 + dataSize); out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); int32(16); int16(1); int16(1); int32(sampleRate); int32(sampleRate * 2); int16(2); int16(16)
    out.write("data".toByteArray()); int32(dataSize)
    val bytes = ByteArray(dataSize)
    for (i in 0 until count) {
        val v = samples[i].toInt()
        bytes[i * 2] = v.toByte()
        bytes[i * 2 + 1] = (v shr 8).toByte()
    }
    out.write(bytes)
    return out.toByteArray()
}
