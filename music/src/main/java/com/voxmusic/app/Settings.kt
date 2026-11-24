package com.voxmusic.app

import android.content.Context
import com.voxmusic.app.net.ProxyType
import com.voxmusic.app.translate.AzureTranslator
import com.voxmusic.app.translate.ChatCompletionsTranslator
import com.voxmusic.app.translate.ClaudeTranslator
import com.voxmusic.app.translate.FailoverTranslator
import com.voxmusic.app.translate.PromptStyle
import com.voxmusic.app.translate.Translator
import com.voxmusic.app.util.Pinyin
import org.json.JSONObject

/**
 * 翻译与 AI 荐歌大模型服务商。
 */
enum class Provider(
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val needsApiKey: Boolean,
    val editableBaseUrl: Boolean,
) {
    LOCAL("本地模型", "http://127.0.0.1:11434/v1", "", needsApiKey = false, editableBaseUrl = true),
    INDEX_TRANSLATE(
        "Index-Translate（免费）", "https://index-translate.bilibili.com/v1", "Index-Translate-35B-A3B",
        needsApiKey = false, editableBaseUrl = false,
    ),
    CLAUDE("Claude", "", ClaudeTranslator.DEFAULT_MODEL, needsApiKey = true, editableBaseUrl = false),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", needsApiKey = true, editableBaseUrl = false),
    GEMINI(
        "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash",
        needsApiKey = true, editableBaseUrl = false,
    ),
    ALIYUN(
        "阿里云百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash",
        needsApiKey = true, editableBaseUrl = false,
    ),
    VOLC(
        "火山方舟（豆包）", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-1-6-flash-250828",
        needsApiKey = true, editableBaseUrl = false,
    ),
    AZURE_TRANSLATOR(
        "Azure 翻译", "https://api.cognitive.microsofttranslator.com", "global",
        needsApiKey = true, editableBaseUrl = false,
    ),
    OPENAI_COMPAT("自定义", "https://api.openai.com/v1", "", needsApiKey = false, editableBaseUrl = true),
    ;

    val isLocal: Boolean get() = this == LOCAL
}

data class ProviderConfig(val apiKey: String = "", val model: String = "", val baseUrl: String = "")

enum class AppDarkMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

enum class AppBackground(
    val label: String,
    val lightColorCode: String,
    val darkColorCode: String,
    val description: String,
) {
    NEUTRAL_GRAY("柔和中性灰", "#F2F3F5", "#14161A", "干净、现代，能够突出各种颜色的专辑封面"),
    WARM_BEIGE_GRAY("暖米灰", "#F5F3EF", "#181613", "更温润，有高级感，适合音乐与唱片氛围"),
    PALE_CYAN_GRAY("淡青灰", "#EEF2F0", "#131816", "略带绿色倾向，清爽舒适"),
    VERY_LIGHT_GRAY("极浅灰", "#F7F7F7", "#121212", "简洁轻盈，深色下为经典暗灰"),
    MINT_GREEN_GRADIENT("薄荷青绿渐变", "#E4F1EA → #F3F6F3 → #E8EFF3", "#111B17 → #141918 → #13191D", "清爽柔和的青绿渐变"),
    GLACIER_BLUE_PURPLE_GRADIENT("冰川蓝紫渐变", "#E8EDF8 → #F4F3F8 → #F1EBF4", "#131722 → #171620 → #1B1521", "现代轻盈的蓝紫渐变"),
    AURORA_GRAY_WHITE_GRADIENT("极光灰白渐变", "#E9F1F2 → #F5F5F6 → #F0EDF5", "#13191B → #16171A → #19161F", "左上淡青灰、右下淡紫灰，现代耐看"),
    WARM_CHAMPAGNE_GRADIENT("暖白香槟渐变", "#F4EEE7 → #FAF8F5 → #EEEFF2", "#1B1713 → #171615 → #15161A", "温润柔和的香槟色调"),
    ;

    fun colorCode(dark: Boolean): String = if (dark) darkColorCode else lightColorCode
}

/** 发现页榜单与相似推荐数据源偏好 */
enum class MusicDiscoverSource(
    val label: String,
    val shortLabel: String,
    val description: String,
) {
    AUTO("自动融合（推荐）", "自动融合", "融合热门华语与全球榜单，任一源受限时自动回退"),
    APPLE("Apple Music / iTunes", "Apple Music", "榜单、搜索与推荐优先采用 Apple Music 台湾区热播榜与 iTunes 曲库"),
    NETEASE("网易云榜单", "网易云榜单", "流行榜单与新歌趋势优先采用网易云榜单数据"),
    QQ("QQ 巅峰榜", "QQ 巅峰榜", "流行榜单与新歌趋势优先采用 QQ 巅峰榜数据"),
    ;

    companion object {
        fun fromStored(raw: String?, default: MusicDiscoverSource = AUTO): MusicDiscoverSource {
            val clean = raw?.trim()?.uppercase() ?: return default
            return entries.firstOrNull { it.name == clean } ?: default
        }
    }
}

data class AppSettings(
    val provider: Provider = Provider.INDEX_TRANSLATE,
    val providerConfigs: Map<Provider, ProviderConfig> = emptyMap(),
    val targetLanguage: String = "简体中文",
    val musicOnlineLookup: Boolean = true,
    val musicBackground: Boolean = true,
    val showPinyin: Boolean = false,
    val proxyType: ProxyType = ProxyType.OFF,
    val proxyHost: String = "",
    val proxyPort: Int = 7890,
    val proxyBypassCn: Boolean = true,
    val musicDiscoverSource: MusicDiscoverSource = MusicDiscoverSource.AUTO,
    val appBackground: AppBackground = AppBackground.AURORA_GRAY_WHITE_GRADIENT,
    val appDarkMode: AppDarkMode = AppDarkMode.SYSTEM,
    val liquidGlass: Boolean = false,
    val aiRecommendProvider: Provider? = null,
) {
    fun configOf(p: Provider): ProviderConfig {
        val c = providerConfigs[p] ?: ProviderConfig()
        return ProviderConfig(
            apiKey = c.apiKey.trim(),
            model = c.model.trim().ifEmpty { p.defaultModel },
            baseUrl = if (p.editableBaseUrl) c.baseUrl.trim().ifEmpty { p.defaultBaseUrl } else p.defaultBaseUrl,
        )
    }

    val targetIsChinese: Boolean
        get() = targetLanguage.trim().let { t ->
            listOf("中文", "汉语", "漢語", "华语", "華語", "简体", "繁體", "繁体", "普通话", "国语", "國語").any { it in t } ||
                t.contains("chinese", ignoreCase = true) || t.lowercase().startsWith("zh")
        }

    val pinyinActive: Boolean get() = showPinyin && Pinyin.available && targetIsChinese

    val targetLocale: String?
        get() {
            val t = targetLanguage.trim().lowercase()
            return when {
                "繁" in targetLanguage || "hant" in t || "tw" in t || "hk" in t -> "zh-TW"
                targetIsChinese -> "zh-CN"
                "英" in targetLanguage || "english" in t || t.startsWith("en") -> "en"
                "日" in targetLanguage || "japan" in t || t.startsWith("ja") -> "ja"
                "韩" in targetLanguage || "韓" in targetLanguage || "korean" in t || t.startsWith("ko") -> "ko"
                else -> null
            }
        }

    fun withConfig(p: Provider, c: ProviderConfig) = copy(providerConfigs = providerConfigs + (p to c))

    fun translatorProblemFor(p: Provider): String? {
        val c = configOf(p)
        return when {
            p.needsApiKey && c.apiKey.isEmpty() -> "请先在设置里填写 ${p.label} 的 API Key"
            c.model.isEmpty() && p == Provider.LOCAL -> "请先在设置里选择本地模型"
            c.model.isEmpty() -> "请先在设置里填写 ${p.label} 的模型名"
            c.baseUrl.isEmpty() && p != Provider.CLAUDE -> "请先在设置里填写 Base URL"
            else -> null
        }
    }

    fun translatorProblem(): String? = translatorProblemFor(provider)

    fun createSingleTranslator(p: Provider = provider): Translator? {
        if (translatorProblemFor(p) != null) return null
        val c = configOf(p)
        return when (p) {
            Provider.CLAUDE -> ClaudeTranslator(c.apiKey, c.model)
            Provider.DEEPSEEK -> ChatCompletionsTranslator(
                c.baseUrl, c.apiKey, c.model,
                extraBody = mapOf("response_format" to JSONObject().put("type", "json_object")),
            )
            Provider.GEMINI -> ChatCompletionsTranslator(
                c.baseUrl, c.apiKey, c.model,
                extraBody = mapOf("reasoning_effort" to "low"),
            )
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
            Provider.ALIYUN -> ChatCompletionsTranslator(
                c.baseUrl, c.apiKey, c.model,
                extraBody = mapOf("enable_thinking" to false),
            )
            Provider.VOLC -> ChatCompletionsTranslator(
                c.baseUrl, c.apiKey, c.model,
                extraBody = mapOf("thinking" to JSONObject().put("type", "disabled")),
            )
            Provider.AZURE_TRANSLATOR -> AzureTranslator(c.baseUrl, c.apiKey, c.model)
            Provider.LOCAL -> ChatCompletionsTranslator(
                c.baseUrl, c.apiKey, c.model,
                temperature = 0.1,
                extraBody = mapOf("chat_template_kwargs" to JSONObject().put("enable_thinking", false)),
            )
            Provider.OPENAI_COMPAT -> ChatCompletionsTranslator(c.baseUrl, c.apiKey, c.model)
        }
    }

    fun candidateTranslators(): List<Pair<Provider, Translator>> {
        val fallbackOrder = listOf(
            Provider.INDEX_TRANSLATE,
            Provider.LOCAL,
            Provider.VOLC,
            Provider.ALIYUN,
            Provider.DEEPSEEK,
            Provider.GEMINI,
            Provider.CLAUDE,
            Provider.AZURE_TRANSLATOR,
            Provider.OPENAI_COMPAT,
        )
        val out = ArrayList<Pair<Provider, Translator>>()
        val added = HashSet<Provider>()
        fun tryAdd(p: Provider, isPrimary: Boolean) {
            if (!added.add(p)) return
            if (!isPrimary && p == Provider.OPENAI_COMPAT) {
                val raw = providerConfigs[p]
                val configured = raw != null && (raw.apiKey.isNotBlank() || raw.baseUrl.isNotBlank()) && raw.model.isNotBlank()
                if (!configured) return
            }
            createSingleTranslator(p)?.let { out += p to it }
        }
        tryAdd(provider, isPrimary = true)
        for (p in fallbackOrder) tryAdd(p, isPrimary = false)
        return out
    }

    fun createTranslator(onFallback: ((from: Provider, to: Provider, error: Throwable) -> Unit)? = null): Translator? {
        val candidates = candidateTranslators()
        return when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> {
                val single = candidates[0]
                if (single.first != provider) {
                    onFallback?.invoke(provider, single.first, IllegalStateException(translatorProblem() ?: "${provider.label} 未配置"))
                }
                single.second
            }
            else -> FailoverTranslator(candidates, onFallback)
        }
    }
}

class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("voxmusic_settings", Context.MODE_PRIVATE)

    private fun provider(default: Provider): Provider {
        prefs.getString("provider", null)?.let { name -> runCatching { Provider.valueOf(name) }.getOrNull()?.let { return it } }
        prefs.edit().putString("provider", default.name).apply()
        return default
    }

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
            provider = provider(d.provider),
            providerConfigs = configs,
            targetLanguage = prefs.getString("targetLanguage", d.targetLanguage)!!,
            musicOnlineLookup = prefs.getBoolean("musicOnlineLookup", d.musicOnlineLookup),
            musicBackground = prefs.getBoolean("musicBackground", d.musicBackground),
            showPinyin = prefs.getBoolean("showPinyin", d.showPinyin),
            proxyType = runCatching { ProxyType.valueOf(prefs.getString("proxyType", d.proxyType.name)!!) }.getOrDefault(d.proxyType),
            proxyHost = prefs.getString("proxyHost", d.proxyHost)!!,
            proxyPort = prefs.getInt("proxyPort", d.proxyPort),
            proxyBypassCn = prefs.getBoolean("proxyBypassCn", d.proxyBypassCn),
            musicDiscoverSource = MusicDiscoverSource.fromStored(prefs.getString("musicDiscoverSource", null), d.musicDiscoverSource),
            appBackground = prefs.getString("appBackground", null)?.let { raw ->
                runCatching { AppBackground.valueOf(raw) }.getOrDefault(d.appBackground)
            } ?: d.appBackground,
            appDarkMode = prefs.getString("appDarkMode", null)?.let { raw ->
                runCatching { AppDarkMode.valueOf(raw) }.getOrDefault(d.appDarkMode)
            } ?: d.appDarkMode,
            liquidGlass = prefs.getBoolean("liquidGlass", d.liquidGlass),
            aiRecommendProvider = prefs.getString("aiRecommendProvider", null)?.let { raw ->
                runCatching { Provider.valueOf(raw) }.getOrNull()
            },
        )
    }

    fun save(s: AppSettings) {
        val editor = prefs.edit()
            .putString("provider", s.provider.name)
            .putString("targetLanguage", s.targetLanguage)
            .putBoolean("musicOnlineLookup", s.musicOnlineLookup)
            .putBoolean("musicBackground", s.musicBackground)
            .putBoolean("showPinyin", s.showPinyin)
            .putString("proxyType", s.proxyType.name)
            .putString("proxyHost", s.proxyHost)
            .putInt("proxyPort", s.proxyPort)
            .putBoolean("proxyBypassCn", s.proxyBypassCn)
            .putString("musicDiscoverSource", s.musicDiscoverSource.name)
            .putString("appBackground", s.appBackground.name)
            .putString("appDarkMode", s.appDarkMode.name)
            .putBoolean("liquidGlass", s.liquidGlass)
            .putString("aiRecommendProvider", s.aiRecommendProvider?.name.orEmpty())
        for ((p, c) in s.providerConfigs) {
            editor.putString("${p.name}.apiKey", c.apiKey)
                .putString("${p.name}.model", c.model)
                .putString("${p.name}.baseUrl", c.baseUrl)
        }
        editor.apply()
    }
}
