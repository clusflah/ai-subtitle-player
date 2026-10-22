package com.aisub.player

import android.content.Context
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

data class ProviderConfig(val apiKey: String = "", val model: String = "", val baseUrl: String = "")

data class AppSettings(
    val provider: Provider = Provider.CLAUDE,
    val providerConfigs: Map<Provider, ProviderConfig> = emptyMap(),
    val targetLanguage: String = "简体中文",
    val asrEngine: AsrEngine = AsrEngine.LOCAL,
    val asrLanguage: String = "auto",
    /** 本地识别模型 ID，见 ModelCatalog */
    val localModel: String = "sense-voice",
    val modelBaseUrl: String = ModelManager.DEFAULT_BASE_URL,
    /** 各云端识别引擎的配置（Key、区域等），键见 AsrEngine.fields */
    val asrConfigs: Map<AsrEngine, Map<String, String>> = emptyMap(),
    val autoTranslate: Boolean = true,
    val showOriginal: Boolean = true,
    val showTranslation: Boolean = true,
    /** 字幕字号倍率（视频上的字幕） */
    val subtitleScale: Float = 1f,
    /** 字幕离视频底部的距离（dp） */
    val subtitleOffset: Int = 40,
    /** 字幕列表自动滚动到当前句 */
    val followPlayback: Boolean = true,
    /** 在线字幕：OpenSubtitles.com 的 API Key 与账号（账号可不填，不登录每天限 5 次下载） */
    val osApiKey: String = "",
    val osUsername: String = "",
    val osPassword: String = "",
    /** 在线字幕：射手网（assrt.net）的 API Token */
    val assrtToken: String = "",
    /** 在线字幕搜索语言，OpenSubtitles 语言代码，逗号分隔 */
    val subtitleLanguages: String = "zh-cn,en",
    /** 影视信息：TMDB 的 API Key（v3 Key 或 v4 Read Access Token），不填只显示豆瓣评分 */
    val tmdbKey: String = "",
    /** TMDB 接口和图片地址，国内可换成反代；空为官方地址 */
    val tmdbBaseUrl: String = "",
    val tmdbImageBase: String = "",
    /** 详情页查询豆瓣评分 */
    val doubanEnabled: Boolean = true,
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
            localModel = prefs.getString("localModel", d.localModel)!!,
            modelBaseUrl = prefs.getString("modelBaseUrl", d.modelBaseUrl)!!,
            asrConfigs = loadAsrConfigs(),
            autoTranslate = prefs.getBoolean("autoTranslate", d.autoTranslate),
            showOriginal = prefs.getBoolean("showOriginal", d.showOriginal),
            showTranslation = prefs.getBoolean("showTranslation", d.showTranslation),
            subtitleScale = prefs.getFloat("subtitleScale", d.subtitleScale),
            subtitleOffset = prefs.getInt("subtitleOffset", d.subtitleOffset),
            followPlayback = prefs.getBoolean("followPlayback", d.followPlayback),
            osApiKey = prefs.getString("osApiKey", "")!!,
            osUsername = prefs.getString("osUsername", "")!!,
            osPassword = prefs.getString("osPassword", "")!!,
            assrtToken = prefs.getString("assrtToken", "")!!,
            subtitleLanguages = prefs.getString("subtitleLanguages", d.subtitleLanguages)!!,
            tmdbKey = prefs.getString("tmdbKey", "")!!,
            tmdbBaseUrl = prefs.getString("tmdbBaseUrl", "")!!,
            tmdbImageBase = prefs.getString("tmdbImageBase", "")!!,
            doubanEnabled = prefs.getBoolean("doubanEnabled", d.doubanEnabled),
        )
    }

    fun save(s: AppSettings) {
        val editor = prefs.edit()
            .putString("provider", s.provider.name)
            .putString("targetLanguage", s.targetLanguage)
            .putString("asrEngine", s.asrEngine.name)
            .putString("asrLanguage", s.asrLanguage)
            .putString("localModel", s.localModel)
            .putString("modelBaseUrl", s.modelBaseUrl)
            .putBoolean("autoTranslate", s.autoTranslate)
            .putBoolean("showOriginal", s.showOriginal)
            .putBoolean("showTranslation", s.showTranslation)
            .putFloat("subtitleScale", s.subtitleScale)
            .putInt("subtitleOffset", s.subtitleOffset)
            .putBoolean("followPlayback", s.followPlayback)
            .putString("osApiKey", s.osApiKey)
            .putString("osUsername", s.osUsername)
            .putString("osPassword", s.osPassword)
            .putString("assrtToken", s.assrtToken)
            .putString("subtitleLanguages", s.subtitleLanguages)
            .putString("tmdbKey", s.tmdbKey)
            .putString("tmdbBaseUrl", s.tmdbBaseUrl)
            .putString("tmdbImageBase", s.tmdbImageBase)
            .putBoolean("doubanEnabled", s.doubanEnabled)
        for ((p, c) in s.providerConfigs) {
            editor.putString("${p.name}.apiKey", c.apiKey)
                .putString("${p.name}.model", c.model)
                .putString("${p.name}.baseUrl", c.baseUrl)
        }
        for ((engine, values) in s.asrConfigs) {
            for ((key, value) in values) editor.putString("asr.${engine.name}.$key", value)
        }
        editor.apply()
    }

    private fun loadAsrConfigs(): Map<AsrEngine, Map<String, String>> {
        val configs = AsrEngine.entries.associateWith { engine ->
            engine.fields.mapNotNull { f -> prefs.getString("asr.${engine.name}.${f.key}", null)?.let { f.key to it } }.toMap()
        }.toMutableMap()
        // 0.2～0.4 版的 Azure 设置存在旧键里
        if (configs[AsrEngine.AZURE].isNullOrEmpty() && prefs.contains("azureKey")) {
            configs[AsrEngine.AZURE] = mapOf(
                "key" to prefs.getString("azureKey", "")!!,
                "region" to prefs.getString("azureRegion", "eastasia")!!,
                "endpoint" to prefs.getString("azureEndpoint", "")!!,
            )
        }
        return configs
    }
}
