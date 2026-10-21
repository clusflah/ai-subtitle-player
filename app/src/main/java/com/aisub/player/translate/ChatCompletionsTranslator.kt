package com.aisub.player.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class PromptStyle {
    /** 通用 LLM：system 提示词 + 带 id 的 JSON 数组，可附上下文 */
    CHAT,

    /** Index-Translate 等专用翻译模型：单条 user 消息，官方的结构化数据翻译模板 */
    INDEX_TRANSLATE,
}

/**
 * OpenAI Chat Completions 兼容接口的翻译实现：
 * DeepSeek、Gemini、Index-Translate、通义千问、Kimi、Ollama 等都走这里。
 */
class ChatCompletionsTranslator(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val style: PromptStyle = PromptStyle.CHAT,
    private val temperature: Double = 0.3,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val extraBody: Map<String, Any> = emptyMap(),
) : Translator {

    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(
        lines: List<String>,
        context: List<String>,
        targetLanguage: String,
    ): List<String?> = withContext(Dispatchers.IO) {
        val messages = when (style) {
            PromptStyle.CHAT -> JSONArray()
                .put(JSONObject().put("role", "system").put("content", TranslationPrompt.system(targetLanguage)))
                .put(JSONObject().put("role", "user").put("content", TranslationPrompt.user(lines, context)))
            PromptStyle.INDEX_TRANSLATE -> JSONArray()
                .put(JSONObject().put("role", "user").put("content", TranslationPrompt.indexTranslate(lines, targetLanguage)))
        }
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("messages", messages)
        extraBody.forEach { (k, v) -> payload.put(k, v) }

        val request = Request.Builder()
            .url(endpoint)
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
                extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val body = try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw TranslationException("翻译请求失败 HTTP ${resp.code}：${text.take(300)}")
                text
            }
        } catch (e: IOException) {
            throw TranslationException("网络错误：${e.message}", e)
        }

        val content = try {
            JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } catch (e: Exception) {
            throw TranslationException("无法解析返回内容：${body.take(300)}", e)
        }
        when (style) {
            PromptStyle.CHAT -> TranslationPrompt.parse(content, lines.size)
            PromptStyle.INDEX_TRANSLATE -> TranslationPrompt.parseKeyed(content, lines.size)
        }
    }
}
