package com.aisub.player.asr.cloud

import com.aisub.player.asr.AsrLanguages
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val JSON = "application/json".toMediaType()
private val WAV = "audio/wav".toMediaType()

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else List(length()) { getJSONObject(it) }

private fun errorText(code: Int, body: String) = "HTTP $code：${body.take(300)}"

/**
 * 火山引擎 · 豆包语音「录音文件极速版识别」：一次请求直接返回结果，音频 base64 上传。
 * 文档：docs.volcengine.com（DoubaoVoice / LargemodelrecordingfileLiterecognitionAPI）。
 * 新版控制台只需 API Key；旧版控制台需要 APP ID + Access Token。
 */
class VolcEngineTranscriber(private val appKey: String, private val accessKey: String) : ChunkedCloudEngine() {
    override fun recognize(wav: ByteArray, chunkStartMs: Long, chunkEndMs: Long): List<Recognized> {
        val body = JSONObject()
            .put("user", JSONObject().put("uid", appKey.take(32)))
            .put("audio", JSONObject().put("data", Base64.getEncoder().encodeToString(wav)).put("format", "wav"))
            .put("request", JSONObject().put("model_name", "bigmodel").put("enable_itn", true).put("enable_punc", true))
        val json = execute({
            Request.Builder().url("https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash")
                .apply {
                    if (accessKey.isBlank()) header("X-Api-Key", appKey)
                    else header("X-Api-App-Key", appKey).header("X-Api-Access-Key", accessKey)
                }
                .header("X-Api-Resource-Id", "volc.bigasr.auc_turbo")
                .header("X-Api-Request-Id", UUID.randomUUID().toString())
                .header("X-Api-Sequence", "-1")
                .post(body.toString().toRequestBody(JSON))
                .build()
        }) { resp ->
            val status = resp.header("X-Api-Status-Code").orEmpty()
            val text = resp.body?.string().orEmpty()
            when (status) {
                "20000000" -> JSONObject(text)
                "20000003" -> null // 静音
                else -> throw IOException("火山引擎识别失败（$status ${resp.header("X-Api-Message").orEmpty()}）${text.take(200)}")
            }
        } ?: return emptyList()
        return json.optJSONObject("result")?.optJSONArray("utterances").objects().map { u ->
            val start = chunkStartMs + u.optLong("start_time")
            val end = chunkStartMs + u.optLong("end_time")
            val words = u.optJSONArray("words").objects()
            if (words.size > 1) {
                // 逐字结果不含标点，用整句文本把标点挂回去
                val texts = com.aisub.player.subtitle.CueSplitter.attachPunctuation(words.map { it.optString("text") }, u.optString("text"))
                val list = words.mapIndexed { i, w -> Triple(texts[i], chunkStartMs + w.optLong("start_time"), chunkStartMs + w.optLong("end_time")) }
                Recognized.Words(spacedTokens(list).first, end)
            } else Recognized.Sentence(u.optString("text"), start, end)
        }
    }
}

/**
 * 阿里云百炼 · Fun-ASR 录音文件识别（同步 Flash 版）：单次不超过 5 分钟 / 10MB，返回逐字时间戳。
 * 文档：help.aliyun.com/zh/model-studio/fun-asr-flash-recorded-speech-recognition-http-api
 */
class AliyunFunAsrTranscriber(
    private val apiKey: String,
    endpoint: String,
    private val model: String,
    private val language: String,
) : ChunkedCloudEngine(firstChunkSec = 60, chunkSec = 240) {
    private val url = endpoint.trimEnd('/') + "/api/v1/services/aigc/multimodal-generation/generation"

    override fun recognize(wav: ByteArray, chunkStartMs: Long, chunkEndMs: Long): List<Recognized> {
        val audio = JSONObject().put("type", "input_audio")
            .put("input_audio", JSONObject().put("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav)))
        val params = JSONObject().put("format", "wav").put("sample_rate", 16000)
        if (language != "auto") params.put("language_hints", JSONArray().put(language))
        val body = JSONObject().put("model", model)
            .put("input", JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(audio)))))
            .put("parameters", params)
        val json = execute({
            Request.Builder().url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("X-DashScope-SSE", "disable")
                .post(body.toString().toRequestBody(JSON))
                .build()
        }) { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("阿里云识别失败 ${errorText(resp.code, text)}")
            JSONObject(text)
        }
        val output = json.optJSONObject("output") ?: return emptyList()
        // 文档示例中 sentence 是单个对象；兼容数组形式
        val sentences = output.optJSONArray("sentence")?.objects()
            ?: output.optJSONArray("sentences")?.objects()
            ?: listOfNotNull(output.optJSONObject("sentence"))
        if (sentences.isEmpty()) {
            val text = output.optString("text")
            return if (text.isBlank()) emptyList() else listOf(Recognized.Sentence(text, chunkStartMs, chunkEndMs))
        }
        return sentences.map { s ->
            val start = chunkStartMs + s.optLong("begin_time")
            val end = chunkStartMs + s.optLong("end_time")
            val words = s.optJSONArray("words").objects()
            if (words.size > 1) {
                val list = words.map { w ->
                    Triple(w.optString("text") + w.optString("punctuation"), chunkStartMs + w.optLong("begin_time"), chunkStartMs + w.optLong("end_time"))
                }
                // 西文词自带前导空格（如 " World"），中文没有
                Recognized.Words(list.map { com.aisub.player.subtitle.TimedToken(it.first, it.second) }, end)
            } else Recognized.Sentence(s.optString("text"), start, end)
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com"
        const val DEFAULT_MODEL = "fun-asr-flash-2026-06-15"
    }
}

/**
 * 讯飞开放平台 · 录音文件转写标准版（lfasr v2）：上传后轮询结果。
 * 文档：www.xfyun.cn/doc/asr/ifasr_new/API.html
 */
class XfyunTranscriber(private val appId: String, private val secretKey: String, private val language: String) : ChunkedCloudEngine() {

    private fun signed(url: String): okhttp3.HttpUrl.Builder {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val md5 = MessageDigest.getInstance("MD5").digest((appId + ts).toByteArray()).joinToString("") { "%02x".format(it) }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secretKey.toByteArray(), "HmacSHA1")) }
        val signa = Base64.getEncoder().encodeToString(mac.doFinal(md5.toByteArray()))
        return url.toHttpUrl().newBuilder().addQueryParameter("appId", appId).addQueryParameter("ts", ts).addQueryParameter("signa", signa)
    }

    private fun check(json: JSONObject) {
        if (json.optString("code") != "000000") throw IOException("讯飞转写失败：${json.optString("descInfo")}（${json.optString("code")}）")
    }

    override fun recognize(wav: ByteArray, chunkStartMs: Long, chunkEndMs: Long): List<Recognized> {
        // 西南官话（四川、重庆、云南、贵州）是方言，需要在讯飞控制台开通
        val lang = when (language) {
            AsrLanguages.SICHUAN -> "cn_xinanese"
            else -> when (AsrLanguages.primary(language)) { "en" -> "en"; "ja" -> "ja"; "ko" -> "ko"; "yue" -> "cn_cantonese"; else -> "cn" }
        }
        val upload = execute({
            Request.Builder().url(
                signed("https://raasr.xfyun.cn/v2/api/upload")
                    .addQueryParameter("fileName", "chunk_$chunkStartMs.wav")
                    .addQueryParameter("fileSize", wav.size.toString())
                    .addQueryParameter("duration", ((chunkEndMs - chunkStartMs) / 1000).coerceAtLeast(1).toString())
                    .addQueryParameter("language", lang)
                    .build()
            ).post(wav.toRequestBody("application/octet-stream".toMediaType())).build()
        }) { JSONObject(it.body?.string().orEmpty()) }
        check(upload)
        val orderId = upload.getJSONObject("content").getString("orderId")

        var result: JSONObject
        while (true) {
            pause(3000)
            result = execute({
                Request.Builder().url(
                    signed("https://raasr.xfyun.cn/v2/api/getResult")
                        .addQueryParameter("orderId", orderId).addQueryParameter("resultType", "transfer").build()
                ).build()
            }) { JSONObject(it.body?.string().orEmpty()) }
            check(result)
            when (result.getJSONObject("content").getJSONObject("orderInfo").optInt("status")) {
                4 -> break
                -1 -> {
                    val fail = result.getJSONObject("content").getJSONObject("orderInfo").optInt("failType")
                    if (fail == 6) return emptyList() // 静音
                    throw IOException("讯飞转写失败（failType=$fail）")
                }
            }
        }
        val orderResult = result.getJSONObject("content").optString("orderResult")
        if (orderResult.isBlank()) return emptyList()
        return JSONObject(orderResult).optJSONArray("lattice").objects().mapNotNull { item ->
            val st = JSONObject(item.getString("json_1best")).getJSONObject("st")
            val bg = st.optString("bg").toLongOrNull() ?: 0
            val ed = st.optString("ed").toLongOrNull() ?: bg
            val words = ArrayList<Triple<String, Long, Long>>()
            for (rt in st.optJSONArray("rt").objects()) for (ws in rt.optJSONArray("ws").objects()) {
                val cw = ws.optJSONArray("cw").objects().firstOrNull() ?: continue
                val w = cw.optString("w")
                when (cw.optString("wp")) {
                    "p" -> if (words.isNotEmpty()) words[words.lastIndex] = words.last().copy(first = words.last().first + w)
                    "g" -> {}
                    // wb/we 以 10ms 为一帧，相对本句开头
                    else -> words.add(Triple(w, chunkStartMs + bg + ws.optLong("wb") * 10, chunkStartMs + bg + ws.optLong("we") * 10))
                }
            }
            if (words.isEmpty()) null else Recognized.Words(spacedTokens(words).first, chunkStartMs + ed)
        }
    }
}

/**
 * ElevenLabs Speech to Text（Scribe）：返回逐词时间戳（秒）。
 * 文档：elevenlabs.io/docs/api-reference/speech-to-text/convert
 */
class ElevenLabsTranscriber(private val apiKey: String, private val model: String, private val language: String) : ChunkedCloudEngine() {
    override fun recognize(wav: ByteArray, chunkStartMs: Long, chunkEndMs: Long): List<Recognized> {
        val json = execute({
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model_id", model)
                .addFormDataPart("timestamps_granularity", "word")
                .addFormDataPart("tag_audio_events", "false")
                .addFormDataPart("file", "audio.wav", wav.toRequestBody(WAV))
            if (language != "auto") form.addFormDataPart("language_code", if (language == "yue") "yue" else language)
            Request.Builder().url("https://api.elevenlabs.io/v1/speech-to-text")
                .header("xi-api-key", apiKey).post(form.build()).build()
        }) { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("ElevenLabs 识别失败 ${errorText(resp.code, text)}")
            JSONObject(text)
        }
        // words 里交替出现 word 和 spacing；空格并到下一个词前面
        val tokens = ArrayList<com.aisub.player.subtitle.TimedToken>()
        var pendingSpace = ""
        var lastEnd = chunkStartMs
        for (w in json.optJSONArray("words").objects()) {
            when (w.optString("type")) {
                "spacing" -> pendingSpace = " "
                "word" -> {
                    val start = chunkStartMs + (w.optDouble("start", 0.0) * 1000).toLong()
                    lastEnd = chunkStartMs + (w.optDouble("end", 0.0) * 1000).toLong()
                    tokens.add(com.aisub.player.subtitle.TimedToken(pendingSpace + w.optString("text"), start))
                    pendingSpace = ""
                }
            }
        }
        return if (tokens.isEmpty()) emptyList() else listOf(Recognized.Words(tokens, lastEnd))
    }

    companion object {
        const val DEFAULT_MODEL = "scribe_v2"
    }
}

/**
 * Deepgram 预录音频识别：原始音频作为请求体上传，返回逐词时间戳（秒）。
 * 中文等语言用 nova-2，其余默认 nova-3。
 */
class DeepgramTranscriber(private val apiKey: String, private val model: String, private val language: String) : ChunkedCloudEngine() {
    override fun recognize(wav: ByteArray, chunkStartMs: Long, chunkEndMs: Long): List<Recognized> {
        val m = model.ifBlank { if (language in setOf("zh", "yue", AsrLanguages.SICHUAN)) "nova-2" else "nova-3" }
        val url = "https://api.deepgram.com/v1/listen".toHttpUrl().newBuilder()
            .addQueryParameter("model", m)
            .addQueryParameter("smart_format", "true")
            .addQueryParameter("punctuate", "true")
            .apply {
                when (language) {
                    "auto" -> addQueryParameter("detect_language", "true")
                    "zh", AsrLanguages.SICHUAN -> addQueryParameter("language", "zh-CN")
                    "yue" -> addQueryParameter("language", "zh-HK")
                    else -> addQueryParameter("language", language)
                }
            }.build()
        val json = execute({
            Request.Builder().url(url).header("Authorization", "Token $apiKey").post(wav.toRequestBody(WAV)).build()
        }) { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Deepgram 识别失败 ${errorText(resp.code, text)}")
            JSONObject(text)
        }
        val words = json.optJSONObject("results")?.optJSONArray("channels")?.optJSONObject(0)
            ?.optJSONArray("alternatives")?.optJSONObject(0)?.optJSONArray("words").objects()
        if (words.isEmpty()) return emptyList()
        val list = words.map { w ->
            Triple(
                w.optString("punctuated_word").ifEmpty { w.optString("word") },
                chunkStartMs + (w.optDouble("start") * 1000).toLong(),
                chunkStartMs + (w.optDouble("end") * 1000).toLong(),
            )
        }
        val (tokens, end) = spacedTokens(list)
        return listOf(Recognized.Words(tokens, end))
    }
}

/**
 * OpenAI 转写接口（也兼容 Groq 等同格式的服务）。
 * whisper-1 支持 verbose_json 的分段时间戳；gpt-4o 系列只返回纯文本，按字数比例分配时间。
 */
class OpenAiTranscriber(
    private val apiKey: String,
    baseUrl: String,
    private val model: String,
    private val language: String,
) : ChunkedCloudEngine() {
    private val url = baseUrl.trimEnd('/') + "/audio/transcriptions"
    private val verbose = !model.startsWith("gpt-4o")

    override fun recognize(wav: ByteArray, chunkStartMs: Long, chunkEndMs: Long): List<Recognized> {
        val json = execute({
            val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", if (verbose) "verbose_json" else "json")
                .addFormDataPart("file", "audio.wav", wav.toRequestBody(WAV))
            if (verbose) form.addFormDataPart("timestamp_granularities[]", "segment")
            if (language != "auto") form.addFormDataPart("language", if (language == "yue") "zh" else language)
            Request.Builder().url(url).header("Authorization", "Bearer $apiKey").post(form.build()).build()
        }) { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("OpenAI 转写失败 ${errorText(resp.code, text)}")
            JSONObject(text)
        }
        val segments = json.optJSONArray("segments").objects()
        if (segments.isEmpty()) {
            val text = json.optString("text").trim()
            return if (text.isEmpty()) emptyList() else listOf(Recognized.Sentence(text, chunkStartMs, chunkEndMs))
        }
        // 分段文本带标点，长段再按字数比例细分
        return segments.map { s ->
            Recognized.Sentence(
                s.optString("text").trim(),
                chunkStartMs + (s.optDouble("start") * 1000).toLong(),
                chunkStartMs + (s.optDouble("end") * 1000).toLong(),
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "whisper-1"
    }
}
