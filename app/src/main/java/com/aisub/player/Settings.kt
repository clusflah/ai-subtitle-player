package com.aisub.player

import android.content.Context
import com.aisub.player.asr.AzureTranscriber
import com.aisub.player.asr.ModelManager
import com.aisub.player.translate.ChatCompletionsTranslator
import com.aisub.player.translate.ClaudeTranslator
import com.aisub.player.translate.PromptStyle
import com.aisub.player.translate.Translator
import org.json.JSONObject

/**
 * 翻译服务商。除 Claude 走官方 SDK 外，其余都是 OpenAI Chat Completions 兼容接口，
 * 区别只在默认地址、模型、提示词风格和少量额外参数。
 */
enum class Provider(
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val needsApiKey: Boolean,
    /** 是否允许用户修改 Base URL（自定义服务商必须填，其余预置） */
    val editableBaseUrl: Boolean,
) {
    CLAUDE("Claude", "", ClaudeTranslator.DEFAULT_MODEL, needsApiKey = true, editableBaseUrl = false),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", needsApiKey = true, editableBaseUrl = false),
    GEMINI(
        "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash",
        needsApiKey = true, editableBaseUrl = false,
    ),
    INDEX_TRANSLATE(
        "Index-Translate", "https://index-translate.bilibili.com/v1", "Index-Translate-35B-A3B",
        needsApiKey = false, editableBaseUrl = false,
    ),
    OPENAI_COMPAT("自定义", "https://api.openai.com/v1", "", needsApiKey = false, editableBaseUrl = true),
}

enum class AsrEngine(val label: String) {
    LOCAL("本地 SenseVoice"),
    AZURE("Azure 语音"),
}

data class ProviderConfig(val apiKey: String = "", val model: String = "", val baseUrl: String = "")

data class AppSettings(
    val provider: Provider = Provider.CLAUDE,
    val providerConfigs: Map<Provider, ProviderConfig> = emptyMap(),
    val targetLanguage: String = "简体中文",
    val asrEngine: AsrEngine = AsrEngine.LOCAL,
    val asrLanguage: String = "auto",
    val modelBaseUrl: String = ModelManager.DEFAULT_BASE_URL,
    val azureKey: String = "",
    val azureRegion: String = "eastasia",
    /** 留空时按区域拼默认地址；Azure 中国区或自定义域名时填写 */
    val azureEndpoint: String = "",
    val autoTranslate: Boolean = true,
    val showOriginal: Boolean = true,
    val showTranslation: Boolean = true,
) {
    /** 某个服务商的配置，未填的字段用默认值补齐。 */
    fun configOf(p: Provider): ProviderConfig {
        val c = providerConfigs[p] ?: ProviderConfig()
        return ProviderConfig(
            apiKey = c.apiKey.trim(),
            model = c.model.trim().ifEmpty { p.defaultModel },
            baseUrl = if (p.editableBaseUrl) c.baseUrl.trim().ifEmpty { p.defaultBaseUrl } else p.defaultBaseUrl,
        )
    }

    fun withConfig(p: Provider, c: ProviderConfig) = copy(providerConfigs = providerConfigs + (p to c))

    fun azureEndpointOrDefault(): String =
        azureEndpoint.trim().ifEmpty { AzureTranscriber.defaultEndpoint(azureRegion) }

    /** 当前识别引擎缺少必填项时返回提示，否则返回 null。 */
    fun asrProblem(): String? = when {
        asrEngine == AsrEngine.AZURE && azureKey.isBlank() -> "请先在设置里填写 Azure 语音的 Key"
        asrEngine == AsrEngine.AZURE && azureRegion.isBlank() && azureEndpoint.isBlank() -> "请先在设置里填写 Azure 区域"
        else -> null
    }

    /** 当前服务商缺少必填项时返回提示，否则返回 null。 */
    fun translatorProblem(): String? {
        val c = configOf(provider)
        return when {
            provider.needsApiKey && c.apiKey.isEmpty() -> "请先在设置里填写 ${provider.label} 的 API Key"
            c.model.isEmpty() -> "请先在设置里填写 ${provider.label} 的模型名"
            c.baseUrl.isEmpty() && provider != Provider.CLAUDE -> "请先在设置里填写 Base URL"
            else -> null
        }
    }

    fun createTranslator(): Translator? {
        if (translatorProblem() != null) return null
        val c = configOf(provider)
        return when (provider) {
            Provider.CLAUDE -> ClaudeTranslator(c.apiKey, c.model)
            // DeepSeek 支持 JSON 模式，能保证输出可解析
            Provider.DEEPSEEK -> ChatCompletionsTranslator(
                c.baseUrl, c.apiKey, c.model,
                extraBody = mapOf("response_format" to JSONObject().put("type", "json_object")),
            )
            // Gemini 3 无法关闭推理，调到最低档以降低延迟
            Provider.GEMINI -> ChatCompletionsTranslator(
                c.baseUrl, c.apiKey, c.model,
                extraBody = mapOf("reasoning_effort" to "low"),
            )
            // 专用翻译模型：用官方的结构化翻译提示词，关闭推理，贪心解码；公共接口要求带 User-Agent
            Provider.INDEX_TRANSLATE -> ChatCompletionsTranslator(
                c.baseUrl, c.apiKey, c.model,
                style = PromptStyle.INDEX_TRANSLATE,
                temperature = 0.0,
                extraHeaders = mapOf("User-Agent" to "Index-Translate-Client/1.0"),
                extraBody = mapOf(
                    "max_tokens" to 4096,
                    "chat_template_kwargs" to JSONObject().put("enable_thinking", false),
                ),
            )
            Provider.OPENAI_COMPAT -> ChatCompletionsTranslator(c.baseUrl, c.apiKey, c.model)
        }
    }
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        val configs = Provider.entries.associateWith { p ->
            ProviderConfig(
                apiKey = prefs.getString("${p.name}.apiKey", "")!!,
                model = prefs.getString("${p.name}.model", "")!!,
                baseUrl = prefs.getString("${p.name}.baseUrl", "")!!,
            )
        }
        return AppSettings(
            provider = runCatching { Provider.valueOf(prefs.getString("provider", d.provider.name)!!) }.getOrDefault(d.provider),
            providerConfigs = configs,
            targetLanguage = prefs.getString("targetLanguage", d.targetLanguage)!!,
            asrEngine = runCatching { AsrEngine.valueOf(prefs.getString("asrEngine", d.asrEngine.name)!!) }.getOrDefault(d.asrEngine),
            asrLanguage = prefs.getString("asrLanguage", d.asrLanguage)!!,
            modelBaseUrl = prefs.getString("modelBaseUrl", d.modelBaseUrl)!!,
            azureKey = prefs.getString("azureKey", d.azureKey)!!,
            azureRegion = prefs.getString("azureRegion", d.azureRegion)!!,
            azureEndpoint = prefs.getString("azureEndpoint", d.azureEndpoint)!!,
            autoTranslate = prefs.getBoolean("autoTranslate", d.autoTranslate),
            showOriginal = prefs.getBoolean("showOriginal", d.showOriginal),
            showTranslation = prefs.getBoolean("showTranslation", d.showTranslation),
        )
    }

    fun save(s: AppSettings) {
        val editor = prefs.edit()
            .putString("provider", s.provider.name)
            .putString("targetLanguage", s.targetLanguage)
            .putString("asrEngine", s.asrEngine.name)
            .putString("asrLanguage", s.asrLanguage)
            .putString("modelBaseUrl", s.modelBaseUrl)
            .putString("azureKey", s.azureKey)
            .putString("azureRegion", s.azureRegion)
            .putString("azureEndpoint", s.azureEndpoint)
            .putBoolean("autoTranslate", s.autoTranslate)
            .putBoolean("showOriginal", s.showOriginal)
            .putBoolean("showTranslation", s.showTranslation)
        for ((p, c) in s.providerConfigs) {
            editor.putString("${p.name}.apiKey", c.apiKey)
                .putString("${p.name}.model", c.model)
                .putString("${p.name}.baseUrl", c.baseUrl)
        }
        editor.apply()
    }
}
