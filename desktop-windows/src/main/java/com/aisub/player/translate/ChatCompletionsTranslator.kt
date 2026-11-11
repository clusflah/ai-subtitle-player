package com.aisub.player.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    private val http = com.aisub.player.net.AppProxy.builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var liveCallTimeoutSec: Int = 0

    /** 实时字幕模式：使用较短单次超时并支持协程取消时立即中断阻塞 Socket */
    fun useLiveTimeout(seconds: Int = 18): ChatCompletionsTranslator {
        liveCallTimeoutSec = seconds
        return this
    }

    override suspend fun translate(
        lines: List<String>,
        context: List<String>,
        targetLanguage: String,
    ): List<String?> {
        val job = currentCoroutineContext()[Job]
        return withContext(Dispatchers.IO) {
            val messages = when (style) {
                PromptStyle.CHAT -> JSONArray()
                    .put(JSONObject().put("role", "system").put("content", TranslationPrompt.system(targetLanguage)))
                    .put(JSONObject().put("role", "user").put("content", TranslationPrompt.user(lines, context)))
                PromptStyle.INDEX_TRANSLATE -> JSONArray()
                    .put(JSONObject().put("role", "user").put("content", TranslationPrompt.indexTranslate(lines, targetLanguage)))
            }
            val content = complete(messages, job)
            when (style) {
                PromptStyle.CHAT -> TranslationPrompt.parse(content, lines.size)
                PromptStyle.INDEX_TRANSLATE -> TranslationPrompt.parseKeyed(content, lines.size)
            }
        }
    }

    override suspend fun chat(system: String, user: String): String {
        val job = currentCoroutineContext()[Job]
        return withContext(Dispatchers.IO) {
            if (style == PromptStyle.INDEX_TRANSLATE) throw TranslationException("Index-Translate 是专用翻译模型，不能识别角色，请换用通用大模型")
            complete(
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
                job,
            )
        }
    }

    /** 发出一次 Chat Completions 请求，返回模型回复的文字。阻塞调用，但协程取消时会立即 cancel 底层 Call。 */
    private fun complete(messages: JSONArray, job: Job?): String {
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

        val activeCall = AtomicReference<Call?>(null)
        val handle = job?.invokeOnCompletion {
            activeCall.get()?.cancel()
        }
        try {
            // 网关超时、限流、服务暂时不可用时等一会儿重试（免费的 Index-Translate 忙时常回 504）
            var attempt = 0
            val maxRetries = if (liveCallTimeoutSec > 0) 1 else RETRY_DELAYS_MS.size
            var body: String
            while (true) {
                job?.ensureActive()
                val call = http.newCall(request).also {
                    if (liveCallTimeoutSec > 0) {
                        it.timeout().timeout(liveCallTimeoutSec.toLong(), TimeUnit.SECONDS)
                    }
                    activeCall.set(it)
                }
                val (code, text) = try {
                    call.execute().use { resp -> resp.code to resp.body?.string().orEmpty() }
                } catch (e: java.net.ConnectException) {
                    if (job?.isCancelled == true) throw CancellationException("翻译已取消", e)
                    throw TranslationException("连不上 $endpoint：${e.message}。本地模型要先在手机或电脑上运行起来，并检查地址和端口", e)
                } catch (e: IOException) {
                    if (job?.isCancelled == true) throw CancellationException("翻译已取消", e)
                    throw TranslationException("网络错误：${e.message}", e)
                } finally {
                    activeCall.compareAndSet(call, null)
                }
                body = text
                if (code in 200..299) break
                if (code !in RETRY_CODES || attempt >= maxRetries) {
                    throw TranslationException("翻译请求失败 HTTP $code：${errorSummary(code, text)}")
                }
                Thread.sleep(RETRY_DELAYS_MS[attempt++])
            }

            return try {
                JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                    // 推理模型（本地的 Qwen3、DeepSeek-R1 等）会把思考过程写在 <think> 里，去掉再解析
                    .replace(THINK_BLOCK, "").trim()
            } catch (e: Exception) {
                throw TranslationException("无法解析返回内容：${body.take(300)}", e)
            }
        } finally {
            handle?.dispose()
        }
    }

    private companion object {
        val RETRY_CODES = setOf(429, 502, 503, 504)
        val THINK_BLOCK = Regex("(?s)<think>.*?</think>")
        val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000)

        /** 网关返回的是整页 HTML，只取标题；JSON 等文字原样截一段 */
        fun errorSummary(code: Int, text: String): String {
            if ('<' !in text.take(20)) return text.take(300)
            val title = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
            val hint = when (code) {
                504 -> "，翻译服务响应超时，可以稍后重试或换一个翻译服务"
                502, 503 -> "，翻译服务暂时不可用，可以稍后重试或换一个翻译服务"
                else -> ""
            }
            return (title ?: "服务器返回了网页") + hint
        }
    }
}
