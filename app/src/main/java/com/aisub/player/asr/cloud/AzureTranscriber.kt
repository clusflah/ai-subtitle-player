package com.aisub.player.asr.cloud

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Azure AI Speech 快速转写（Fast Transcription REST API）。
 *
 * @param endpoint 例如 https://eastasia.api.cognitive.microsoft.com，
 *                 Azure 中国区为 https://chinanorth3.api.cognitive.azure.cn
 * @param locales 候选语言，例如 ["zh-CN"]；为空时由服务端自动识别（多语言模型）
 */
class AzureTranscriber(endpoint: String, private val apiKey: String, private val locales: List<String>) : ChunkedCloudEngine() {

    private val url = endpoint.trimEnd('/') + "/speechtotext/transcriptions:transcribe?api-version=2025-10-15"

    override fun recognize(wav: ByteArray, chunkStartMs: Long, chunkEndMs: Long): List<Recognized> {
        val definition = JSONObject().put("locales", JSONArray(locales)).put("profanityFilterMode", "None")
        val json = execute({
            Request.Builder().url(url)
                .header("Ocp-Apim-Subscription-Key", apiKey)
                .post(
                    MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("audio", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
                        .addFormDataPart("definition", definition.toString())
                        .build()
                )
                .build()
        }) { resp ->
            val text = resp.body?.string().orEmpty()
            when {
                resp.isSuccessful -> JSONObject(text)
                resp.code == 401 -> throw IOException("Azure 鉴权失败：请检查 Key 和区域是否匹配")
                else -> throw IOException("Azure 识别失败 HTTP ${resp.code}：${text.take(300)}")
            }
        }
        val phrases = json.optJSONArray("phrases") ?: return emptyList()
        return List(phrases.length()) { i ->
            val p = phrases.getJSONObject(i)
            val start = chunkStartMs + p.optLong("offsetMilliseconds")
            val end = start + p.optLong("durationMilliseconds")
            val words = p.optJSONArray("words")
            if (words != null && words.length() > 1) {
                val list = List(words.length()) { w ->
                    val o = words.getJSONObject(w)
                    val ws = chunkStartMs + o.optLong("offsetMilliseconds")
                    Triple(o.optString("text"), ws, ws + o.optLong("durationMilliseconds"))
                }
                Recognized.Words(spacedTokens(list).first, end)
            } else {
                Recognized.Sentence(p.optString("text"), start, end)
            }
        }
    }

    companion object {
        fun defaultEndpoint(region: String) = "https://${region.trim()}.api.cognitive.microsoft.com"

        /** 应用里的语言代码 → Azure locale。"auto" 返回空列表，由服务端识别。 */
        fun localesFor(language: String): List<String> = listOfNotNull(com.aisub.player.asr.AsrLanguages.azureLocale(language))
    }
}
