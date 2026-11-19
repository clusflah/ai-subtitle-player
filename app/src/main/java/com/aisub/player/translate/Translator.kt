package com.aisub.player.translate

import com.aisub.player.Provider
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** 一批字幕的翻译接口。返回值与 lines 一一对应，某条没翻出来时为 null。 */
interface Translator {
    suspend fun translate(lines: List<String>, context: List<String>, targetLanguage: String): List<String?>

    /** 通用对话：给一段系统提示词和用户消息，返回模型的文字回复。用于识别角色等非翻译任务。 */
    suspend fun chat(system: String, user: String): String
}

class TranslationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 带自动故障回退的组合翻译器：
 * 按优先级依次尝试 [candidates]；当主模型报错或返回全空结果时无缝切到下一个可用服务，
 * 并在会话内记住最近可用的备选索引以减少后续批次超时等待（协程取消异常直接穿透，不被吞掉）。
 */
class FailoverTranslator(
    private val candidates: List<Pair<Provider, Translator>>,
    private val onFallback: ((from: Provider, to: Provider, error: Throwable) -> Unit)? = null,
) : Translator {

    init {
        require(candidates.isNotEmpty()) { "FailoverTranslator 至少需要一个候选翻译器" }
    }

    @Volatile
    private var preferredIndex = 0

    @Volatile
    private var lastNotifiedTarget: Provider? = null

    private fun orderedIndices(): List<Int> {
        val cur = preferredIndex.coerceIn(0, candidates.lastIndex)
        return buildList(candidates.size) {
            add(cur)
            for (i in candidates.indices) {
                if (i != cur) add(i)
            }
        }
    }

    override suspend fun translate(lines: List<String>, context: List<String>, targetLanguage: String): List<String?> {
        if (lines.isEmpty()) return emptyList()
        val hasContent = lines.any { it.isNotBlank() }
        val primaryProvider = candidates[0].first
        var firstError: Throwable? = null
        var lastError: Throwable? = null
        for (idx in orderedIndices()) {
            val (provider, delegate) = candidates[idx]
            try {
                val res = delegate.translate(lines, context, targetLanguage)
                if (hasContent && res.all { it.isNullOrBlank() }) {
                    throw TranslationException("${provider.label} 未返回有效译文")
                }
                if (idx != preferredIndex) {
                    preferredIndex = idx
                }
                if (idx != 0 && provider != primaryProvider && lastNotifiedTarget != provider) {
                    lastNotifiedTarget = provider
                    onFallback?.invoke(primaryProvider, provider, firstError ?: TranslationException("${primaryProvider.label} 不可用"))
                }
                return res
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (firstError == null) firstError = t
                lastError = t
            }
        }
        throw (firstError ?: lastError ?: TranslationException("所有翻译服务均不可用"))
    }

    override suspend fun chat(system: String, user: String): String {
        val primaryProvider = candidates[0].first
        var firstError: Throwable? = null
        var lastError: Throwable? = null
        for (idx in orderedIndices()) {
            val (provider, delegate) = candidates[idx]
            try {
                val reply = delegate.chat(system, user)
                if (idx != preferredIndex) {
                    preferredIndex = idx
                }
                if (idx != 0 && provider != primaryProvider && lastNotifiedTarget != provider) {
                    lastNotifiedTarget = provider
                    onFallback?.invoke(primaryProvider, provider, firstError ?: TranslationException("${primaryProvider.label} 不可用"))
                }
                return reply
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (firstError == null) firstError = t
                lastError = t
            }
        }
        throw (firstError ?: lastError ?: TranslationException("所有对话服务均不可用"))
    }
}

/** 各家 LLM 共用的提示词和结果解析。 */
internal object TranslationPrompt {

    fun system(targetLanguage: String) = """
        你是专业的影视字幕译者。把用户给出的字幕逐条翻译成$targetLanguage。
        要求：
        - 口语化、简洁，适合在屏幕上快速阅读；
        - 结合上下文理解语气和指代，人名、术语前后保持一致；
        - 字幕来自语音识别，可能有错别字或断句不准，按最合理的意思翻译；
        - 条目数量和 id 必须与输入完全一致，不合并、不拆分、不遗漏；
        - 只输出 JSON，格式：{"translations":[{"id":1,"text":"译文"}]}，不要输出任何其他内容。
    """.trimIndent()

    fun user(lines: List<String>, context: List<String>): String {
        val input = JSONObject()
        if (context.isNotEmpty()) input.put("previous_lines_for_context_only", JSONArray(context))
        input.put("lines", JSONArray(lines.mapIndexed { i, t -> JSONObject().put("id", i + 1).put("text", t) }))
        return input.toString()
    }

    /**
     * Index-Translate 官方的结构化数据翻译模板（见 bilibili/Index-Translate 的 docs/prompts.md）。
     * 字幕以 {"1": "...", "2": "..."} 的形式传入，模型按原 key 返回译文。
     */
    fun indexTranslate(lines: List<String>, targetLanguage: String): String {
        val data = JSONObject()
        lines.forEachIndexed { i, t -> data.put((i + 1).toString(), t) }
        return "将下方 JSON 数据翻译为$targetLanguage：仅翻译面向用户的文本字段，严禁改动结构、键名与占位符：\n\n" +
            data.toString() +
            "\n\n请以相同的 JSON 格式输出翻译结果，key 保持不变，value 为对应译文。只输出 JSON，不要有任何额外说明。"
    }

    fun parse(raw: String, expected: Int): List<String?> {
        val arr = try {
            extractJson(raw).getJSONArray("translations")
        } catch (e: TranslationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException("无法解析翻译结果：${raw.take(200)}", e)
        }
        val result = arrayOfNulls<String>(expected)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", -1)
            val text = o.optString("text").trim()
            if (id in 1..expected && text.isNotEmpty()) result[id - 1] = text
        }
        return result.toList()
    }

    /** 解析 {"1": "译文", "2": "译文"} 格式。 */
    fun parseKeyed(raw: String, expected: Int): List<String?> {
        val obj = extractJson(raw)
        return List(expected) { i -> obj.optString((i + 1).toString()).trim().ifEmpty { null } }
    }

    fun extractJson(raw: String): JSONObject {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw TranslationException("模型没有返回 JSON：${raw.take(200)}")
        return try {
            JSONObject(raw.substring(start, end + 1))
        } catch (e: Exception) {
            throw TranslationException("无法解析翻译结果：${raw.take(200)}", e)
        }
    }
}
