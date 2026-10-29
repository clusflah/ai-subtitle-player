package com.aisub.player.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Azure AI Translator（传统机器翻译）：一次请求翻译一批字幕，速度比大模型快、价格低，
 * 适合追求出字幕快的场合；译文偏直译，不读上下文，也不支持识别角色。
 * 文档：learn.microsoft.com/azure/ai-services/translator/text-translation/reference/v3/translate
 *
 * @param region 资源所在区域，如 eastasia；全局资源填 global
 */
class AzureTranslator(
    endpoint: String,
    private val apiKey: String,
    private val region: String,
) : Translator {

    private val base = endpoint.trimEnd('/')

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(lines: List<String>, context: List<String>, targetLanguage: String): List<String?> =
        withContext(Dispatchers.IO) {
            val body = JSONArray(lines.map { JSONObject().put("Text", it) }).toString()
            var attempt = 0
            while (true) {
                val request = Request.Builder()
                    .url("$base/translate?api-version=3.0&to=${languageCode(targetLanguage)}")
                    .header("Ocp-Apim-Subscription-Key", apiKey)
                    .apply { if (region.isNotBlank()) header("Ocp-Apim-Subscription-Region", region.trim()) }
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                val (code, text) = http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
                when {
                    code == 200 -> return@withContext parse(text, lines.size)
                    (code == 429 || code >= 500) && attempt < 3 -> delay(1000L shl attempt++)
                    code == 401 || code == 403 ->
                        throw TranslationException("Azure 翻译鉴权失败（HTTP $code）：请检查 Key 和区域（模型一栏）是否匹配")
                    else -> throw TranslationException("Azure 翻译失败 HTTP $code：${text.take(200)}")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            emptyList()
        }

    override suspend fun chat(system: String, user: String): String =
        throw TranslationException("Azure 翻译只能翻译，不支持识别角色，请换成大模型服务")

    private fun parse(text: String, expected: Int): List<String?> {
        val arr = try {
            JSONArray(text)
        } catch (e: Exception) {
            throw TranslationException("无法解析翻译结果：${text.take(200)}", e)
        }
        return List(expected) { i ->
            arr.optJSONObject(i)?.optJSONArray("translations")?.optJSONObject(0)?.optString("text")?.trim()?.ifEmpty { null }
        }
    }

    companion object {
        /** 设置里的目标语言名 → Azure 语言代码 */
        fun languageCode(targetLanguage: String): String = when (targetLanguage) {
            "繁體中文" -> "zh-Hant"
            "English" -> "en"
            "日本語" -> "ja"
            "한국어" -> "ko"
            else -> "zh-Hans"
        }
    }
}
