package com.aisub.player.asr

import com.aisub.player.subtitle.Cue
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Azure AI Speech 快速转写（Fast Transcription REST API）。
 *
 * 音频按几分钟一段打包成 WAV 依次上传，切点选在附近最安静的位置，避免把一个词切成两半。
 * 第一段较短，让字幕尽快出现。
 *
 * @param endpoint 例如 https://eastasia.api.cognitive.microsoft.com，
 *                 Azure 中国区为 https://chinanorth3.api.cognitive.azure.cn
 * @param locales 候选语言，例如 ["zh-CN"]；为空时由服务端自动识别（多语言模型）
 */
class AzureTranscriber(
    endpoint: String,
    private val apiKey: String,
    private val locales: List<String>,
) : SpeechEngine {

    private val url = endpoint.trimEnd('/') + "/speechtotext/transcriptions:transcribe?api-version=$API_VERSION"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    private var buffer = ShortArray(SAMPLE_RATE * 60)
    private var size = 0

    /** 当前缓冲区第一个样本在整段音频中的位置（毫秒） */
    private var bufferStartMs = 0L
    private var chunksSent = 0
    private var nextId = 1

    @Volatile
    private var currentCall: Call? = null

    @Volatile
    private var cancelled = false

    override fun accept(samples: FloatArray, onCue: (Cue) -> Unit) {
        if (size + samples.size > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + samples.size))
        for (s in samples) buffer[size++] = (s.coerceIn(-1f, 1f) * 32767).toInt().toShort()

        val target = if (chunksSent == 0) FIRST_CHUNK_SAMPLES else CHUNK_SAMPLES
        if (size >= target) {
            val cut = quietestPoint(target - SEARCH_WINDOW_SAMPLES, target)
            flush(cut, onCue)
        }
    }

    override fun finish(onCue: (Cue) -> Unit) {
        if (size > SAMPLE_RATE / 2) flush(size, onCue)
    }

    override fun cancel() {
        cancelled = true
        currentCall?.cancel()
    }

    override fun close() {
        cancel()
    }

    /** 上传缓冲区前 count 个样本并输出字幕，剩余部分留到下一段。 */
    private fun flush(count: Int, onCue: (Cue) -> Unit) {
        val wav = encodeWav(buffer, count)
        val chunkStart = bufferStartMs
        val response = postWithRetry(wav)
        parsePhrases(response, chunkStart).forEach(onCue)

        System.arraycopy(buffer, count, buffer, 0, size - count)
        size -= count
        bufferStartMs += count * 1000L / SAMPLE_RATE
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

    private fun postWithRetry(wav: ByteArray): JSONObject {
        val definition = JSONObject()
            .put("locales", JSONArray(locales))
            .put("profanityFilterMode", "None")
        var attempt = 0
        while (true) {
            if (cancelled) throw IOException("已取消")
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("definition", definition.toString())
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Ocp-Apim-Subscription-Key", apiKey)
                .post(body)
                .build()
            val call = http.newCall(request).also { currentCall = it }
            call.execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> return JSONObject(text)
                    // 限流或服务端临时错误：按官方建议指数退避重试
                    (resp.code == 429 || resp.code >= 500) && attempt < RETRY_DELAYS_S.size -> {
                        Thread.sleep(RETRY_DELAYS_S[attempt++] * 1000L)
                    }
                    resp.code == 401 -> throw IOException("Azure 鉴权失败：请检查 Key 和区域是否匹配")
                    else -> throw IOException("Azure 识别失败 HTTP ${resp.code}：${text.take(300)}")
                }
            }
        }
    }

    private fun parsePhrases(json: JSONObject, chunkStartMs: Long): List<Cue> {
        val phrases = json.optJSONArray("phrases") ?: return emptyList()
        val cues = ArrayList<Cue>(phrases.length())
        for (i in 0 until phrases.length()) {
            val p = phrases.getJSONObject(i)
            val text = p.optString("text").trim()
            if (text.isEmpty()) continue
            val start = chunkStartMs + p.optLong("offsetMilliseconds")
            val end = start + p.optLong("durationMilliseconds")
            cues.add(Cue(nextId++, start, end, text))
        }
        return cues
    }

    companion object {
        private const val API_VERSION = "2025-10-15"
        private const val SAMPLE_RATE = Transcriber.SAMPLE_RATE
        private const val FIRST_CHUNK_SAMPLES = SAMPLE_RATE * 60
        private const val CHUNK_SAMPLES = SAMPLE_RATE * 240
        private const val SEARCH_WINDOW_SAMPLES = SAMPLE_RATE * 10
        private val RETRY_DELAYS_S = longArrayOf(2, 4, 8, 16, 32)

        fun defaultEndpoint(region: String) = "https://${region.trim()}.api.cognitive.microsoft.com"

        /** 应用里的语言代码 → Azure locale。"auto" 返回空列表，由服务端识别。 */
        fun localesFor(language: String): List<String> = when (language) {
            "zh" -> listOf("zh-CN")
            "en" -> listOf("en-US")
            "ja" -> listOf("ja-JP")
            "ko" -> listOf("ko-KR")
            "yue" -> listOf("zh-HK")
            else -> emptyList()
        }
    }
}

/** 16-bit 单声道 PCM → WAV 文件字节。 */
private fun encodeWav(samples: ShortArray, count: Int, sampleRate: Int = 16_000): ByteArray {
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
