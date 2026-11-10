package com.aisub.player.translate

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 用 Claude 翻译字幕（官方 anthropic-java SDK）。 */
class ClaudeTranslator(apiKey: String, private val model: String) : Translator {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

    override suspend fun translate(
        lines: List<String>,
        context: List<String>,
        targetLanguage: String,
    ): List<String?> = withContext(Dispatchers.IO) {
        TranslationPrompt.parse(complete(TranslationPrompt.system(targetLanguage), TranslationPrompt.user(lines, context)), lines.size)
    }

    override suspend fun chat(system: String, user: String): String = withContext(Dispatchers.IO) { complete(system, user) }

    /** 发出一次请求，返回模型回复的文字。阻塞调用。 */
    private fun complete(system: String, user: String): String {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            .addUserMessage(user)

        if (supportsEffort(model)) {
            // 字幕翻译不需要深度推理，低 effort 更快更省
            builder.outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
        }
        if (supportsServerFallback(model)) {
            // 安全分类器误拒时，由服务端自动换模型重试
            builder.addBeta("server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }

        val response = try {
            client.beta().messages().create(builder.build())
        } catch (e: AnthropicException) {
            throw TranslationException("Claude 请求失败：${e.message}", e)
        }

        if (response.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
            throw TranslationException("Claude 拒绝了这次请求")
        }
        return response.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
    }

    private fun supportsEffort(model: String) =
        EFFORT_PREFIXES.any { model.startsWith(it) }

    private fun supportsServerFallback(model: String) =
        model in FALLBACK_MODELS

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        private val EFFORT_PREFIXES = listOf(
            "claude-fable-5", "claude-opus-5", "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6",
            "claude-sonnet-5", "claude-sonnet-4-6", "claude-haiku-5",
        )
        private val FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
    }
}
