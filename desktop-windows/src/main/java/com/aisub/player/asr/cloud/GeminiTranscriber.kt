package com.aisub.player.asr.cloud

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

/**
 * Google Gemini 音频理解：一次请求同时识别和翻译（[translateTo] 不为空时），
 * 省掉单独翻译的那一轮网络往返，最快出中文字幕。时间戳是模型估的（精度约零点几秒），所以每段切得短。
 * 文档：ai.google.dev/gemini-api/docs/audio
 *
 * @param language 视频里说的语言名（"自动识别"时为空）
 */
class GeminiTranscriber(
    private val apiKey: String,
    private val model: String,
    endpoint: String,
    private val language: String,
) : ChunkedCloudEngine() {

    /** 不为空：同时翻译成这种语言（设置里的目标语言名，如「简体中文」） */
    @Volatile
    var translateTo: String? = null

    private val base = endpoint.trimEnd('/')

    /** 这个模型支持 JSON 模式；试出来不支持后，后面的段直接不用 */
    @Volatile
    private var jsonMode = true

    override fun recognize(wav: ByteArray, chunkStartMs: Long, chunkEndMs: Long): List<Recognized> {
        val target = translateTo
        val prompt = buildString {
            append("把这段音频里的人声逐句转写成文字，给出每句的开始和结束时间（相对这段音频开头的秒数，保留一位小数）。")
            if (language.isNotEmpty()) append("音频里说的是$language。")
            if (target != null) append("同时把每句翻译成$target，口语化、简洁，适合当字幕。")
            append("只输出 JSON 数组：[{\"start\":0.0,\"end\":2.5,\"text\":\"原文\"")
            if (target != null) append(",\"translation\":\"译文\"")
            append("}]。没有人说话（只有音乐、音效或静音）时输出 []；听不清的不要编造；不要把背景音乐的歌词当成对白。")
        }
        val audio = JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", Base64.getEncoder().encodeToString(wav)))
        fun body(thinking: Boolean, json: Boolean): String {
            val config = JSONObject().put("temperature", 0)
            if (json) config.put("responseMimeType", "application/json")
            // Gemini 3 无法完全关闭推理，调到最低档以降低延迟
            if (thinking) config.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            return JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)).put(audio))))
                .put("generationConfig", config)
                .toString()
        }

        var thinking = true
        // 转写专用模型（如 gemini-*-transcribe）不支持 JSON 模式：去掉重来，按提示词要求的 JSON 解析
        var json = jsonMode
        val text = run {
            while (true) {
                val result = execute({
                    Request.Builder().url("$base/v1beta/models/$model:generateContent")
                        .header("x-goog-api-key", apiKey)
                        .post(body(thinking, json).toRequestBody("application/json".toMediaType()))
                        .build()
                }) { resp ->
                    val raw = resp.body?.string().orEmpty()
                    when {
                        resp.isSuccessful -> raw to 0
                        // 老模型不认 thinkingConfig：去掉重来一次
                        resp.code == 400 && thinking && raw.contains("thinking", ignoreCase = true) -> "" to 1
                        resp.code == 400 && json && raw.contains("JSON mode", ignoreCase = true) -> "" to 2
                        resp.code == 400 && raw.contains("API key", ignoreCase = true) -> throw IOException("Gemini Key 无效：请检查 API Key")
                        resp.code == 403 || resp.code == 401 -> throw IOException("Gemini 鉴权失败 HTTP ${resp.code}：${raw.take(200)}")
                        else -> throw IOException("Gemini 识别失败 HTTP ${resp.code}：${raw.take(300)}")
                    }
                }
                when (result.second) {
                    1 -> thinking = false
                    2 -> { json = false; jsonMode = false }
                    else -> return@run result.first
                }
            }
            @Suppress("UNREACHABLE_CODE")
            ""
        }
        return parse(text, chunkStartMs, chunkEndMs)
    }

    private fun parse(raw: String, chunkStartMs: Long, chunkEndMs: Long): List<Recognized> {
        val parts = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: return emptyList()
        val content = buildString { for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty()) }
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start < 0 || end <= start) {
            // 没按要求给 JSON（转写专用模型可能只给纯文本）：整段当一句，时间按比例分
            val plain = content.trim()
            return if (plain.isEmpty()) emptyList() else listOf(Recognized.Sentence(plain, chunkStartMs, chunkEndMs))
        }
        val arr = try {
            JSONArray(content.substring(start, end + 1))
        } catch (e: Exception) {
            // 纯文本里带着 [音乐] 这类方括号
            if (!jsonMode) return listOf(Recognized.Sentence(content.trim(), chunkStartMs, chunkEndMs))
            throw IOException("无法解析 Gemini 返回的结果：${content.take(200)}", e)
        }
        val out = ArrayList<Recognized>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("text").trim()
            if (text.isEmpty()) continue
            val s = (chunkStartMs + (o.optDouble("start", 0.0) * 1000).toLong()).coerceIn(chunkStartMs, chunkEndMs)
            var e = (chunkStartMs + (o.optDouble("end", 0.0) * 1000).toLong()).coerceAtMost(chunkEndMs)
            if (e <= s) e = minOf(s + 2000, chunkEndMs).coerceAtLeast(s + 500)
            val translation = o.optString("translation").trim().ifEmpty { null }
            out.add(if (translation != null) Recognized.Translated(text, translation, s, e) else Recognized.Sentence(text, s, e))
        }
        return out
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com"
    }
}
