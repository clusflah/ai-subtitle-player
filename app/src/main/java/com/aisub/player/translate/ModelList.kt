package com.aisub.player.translate

import com.aisub.player.Provider
import com.aisub.player.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 从服务商接口获取可用的模型列表，供设置页下拉选择。
 * Claude 走 Anthropic 的 /v1/models，其余走 OpenAI 兼容的 /models。
 */
object ModelList {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(provider: Provider, c: ProviderConfig): List<String> = withContext(Dispatchers.IO) {
        if (provider.needsApiKey && c.apiKey.isEmpty()) throw IOException("请先填写 API Key")
        val request = if (provider == Provider.CLAUDE) {
            Request.Builder().url("https://api.anthropic.com/v1/models?limit=1000")
                .header("x-api-key", c.apiKey)
                .header("anthropic-version", "2023-06-01")
                .build()
        } else {
            if (c.baseUrl.isEmpty()) throw IOException("请先填写 Base URL")
            Request.Builder().url(c.baseUrl.trimEnd('/') + "/models")
                .apply {
                    if (c.apiKey.isNotEmpty()) header("Authorization", "Bearer ${c.apiKey}")
                    // Index-Translate 公共接口要求带 User-Agent，与翻译请求一致
                    if (provider == Provider.INDEX_TRANSLATE) header("User-Agent", "Index-Translate-Client/1.0")
                }
                .build()
        }
        val text = try {
            http.newCall(request).execute()
        } catch (e: java.net.ConnectException) {
            throw IOException("连不上 ${c.baseUrl}，确认模型服务已经运行、地址和端口正确", e)
        }.use { resp ->
            val body = resp.body?.string().orEmpty()
            when {
                resp.code == 401 || resp.code == 403 -> throw IOException("API Key 无效（HTTP ${resp.code}）")
                resp.code == 404 -> throw IOException("这个接口不提供模型列表，请手动填写")
                !resp.isSuccessful -> throw IOException("HTTP ${resp.code}：${body.take(200)}")
            }
            body
        }
        val ids = parse(text)
        // Claude 按发布时间从新到旧返回，保持原顺序；其他按名称排
        if (provider == Provider.CLAUDE) ids else ids.sortedBy { it.lowercase() }
    }

    /**
     * 语音识别用的模型列表。Gemini 用原生接口，只留能 generateContent、听得懂音频的 gemini 模型；
     * OpenAI 兼容接口只留转写模型（whisper、transcribe）。
     */
    suspend fun fetchAsr(engine: com.aisub.player.AsrEngine, apiKey: String, endpoint: String, live: Boolean = false): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) throw IOException("请先填写 API Key")
        val gemini = engine == com.aisub.player.AsrEngine.GEMINI
        val request = if (gemini) {
            Request.Builder().url(endpoint.trimEnd('/') + "/v1beta/models?pageSize=1000").header("x-goog-api-key", apiKey).build()
        } else {
            Request.Builder().url(endpoint.trimEnd('/') + "/models").header("Authorization", "Bearer $apiKey").build()
        }
        val text = http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            when {
                resp.code == 400 && gemini && "API_KEY" in body -> throw IOException("API Key 无效")
                resp.code == 401 || resp.code == 403 -> throw IOException("API Key 无效（HTTP ${resp.code}）")
                resp.code == 404 -> throw IOException("这个接口不提供模型列表，请手动填写")
                !resp.isSuccessful -> throw IOException("HTTP ${resp.code}：${body.take(200)}")
            }
            body
        }
        if (gemini) {
            val arr = runCatching { JSONObject(text).optJSONArray("models") }.getOrNull() ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { o ->
                    val methods = o.optJSONArray("supportedGenerationMethods")
                    // 流式识别用 Live 接口（bidiGenerateContent），分段识别用 generateContent
                    val method = if (live) "bidiGenerateContent" else "generateContent"
                    methods != null && (0 until methods.length()).any { methods.optString(it) == method }
                }
                .map { it.optString("name").removePrefix("models/") }
                // 分段识别只留对话模型：画图、朗读、向量、实时语音这些用不了；流式只留 live 模型
                .filter { n ->
                    n.startsWith("gemini") && if (live) "translate" !in n
                    else listOf("image", "tts", "embedding", "live", "native-audio").none { it in n }
                }
                .distinct().sortedDescending()
        } else {
            parse(text).filter { "whisper" in it || "transcribe" in it }.sorted()
        }
    }

    /** OpenAI 格式是 {"data":[{"id":…}]}；Ollama 原生接口是 {"models":[{"name":…}]}。 */
    private fun parse(text: String): List<String> {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("接口返回的不是模型列表：${text.take(200)}")
        }
        val arr = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i)
            (o?.optString("id")?.ifEmpty { null } ?: o?.optString("name")?.ifEmpty { null } ?: arr.optString(i).ifEmpty { null })
                // Gemini 的兼容接口返回 models/gemini-xxx，调用时用不带前缀的名字
                ?.removePrefix("models/")
        }.distinct()
    }
}
