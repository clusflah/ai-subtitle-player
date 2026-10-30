package com.aisub.player

import android.content.Context
import com.aisub.player.asr.ModelManager
import com.aisub.player.subtitle.LineStyle
import com.aisub.player.subtitle.Pinyin
import com.aisub.player.tts.TtsEngineType
import com.aisub.player.tts.ttsLocaleFor
import com.aisub.player.translate.AzureTranslator
import com.aisub.player.translate.ChatCompletionsTranslator
import com.aisub.player.translate.ClaudeTranslator
import com.aisub.player.translate.OfflineTranslator
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
    // 免费的排第一（设置页按这个顺序列出）
    INDEX_TRANSLATE(
        "Index-Translate（免费）", "https://index-translate.bilibili.com/v1", "Index-Translate-35B-A3B",
        needsApiKey = false, editableBaseUrl = false,
    ),
    CLAUDE("Claude", "", ClaudeTranslator.DEFAULT_MODEL, needsApiKey = true, editableBaseUrl = false),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", needsApiKey = true, editableBaseUrl = false),
    GEMINI(
        "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash",
        needsApiKey = true, editableBaseUrl = false,
    ),
    // 国内直连、速度快的几家：阿里云百炼（通义千问）、火山方舟（豆包）、Azure 机器翻译
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
    // 手机或局域网电脑上跑的模型（Ollama、LM Studio、llama.cpp 等）：不用 Key、不花钱，字幕内容不出本地
    LOCAL("本地模型", "http://127.0.0.1:11434/v1", "", needsApiKey = false, editableBaseUrl = true),
    // 另外安装的 Offline Translator App（Bergamot 机器翻译）：完全离线，不用地址、Key 和模型
    OFFLINE_TRANSLATOR("离线翻译（Offline Translator App）", "", "", needsApiKey = false, editableBaseUrl = false),
    OPENAI_COMPAT("自定义", "https://api.openai.com/v1", "", needsApiKey = false, editableBaseUrl = true),
}

data class ProviderConfig(val apiKey: String = "", val model: String = "", val baseUrl: String = "")

/** 播放视频时切到别的应用、回桌面或锁屏怎么办 */
enum class VideoLeaveAction(val label: String) {
    PAUSE("暂停"),
    AUDIO("继续播放声音"),
    PIP("悬浮窗（画中画）"),
}

data class AppSettings(
    // 默认用免费、不用 Key 的翻译，装好就能用
    val provider: Provider = Provider.INDEX_TRANSLATE,
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
    /** 识别、翻译、编辑后在本地视频旁边写一个同名 .srt，给别的播放器用 */
    val autoSaveSrt: Boolean = false,
    /** 播放音乐时，没有内嵌歌词、封面就在线查找 */
    val musicOnlineLookup: Boolean = true,
    /** 播放音乐时切到后台、锁屏继续播放 */
    val musicBackground: Boolean = true,
    /** 播放视频时离开应用 */
    val videoLeaveAction: VideoLeaveAction = VideoLeaveAction.PAUSE,
    val showOriginal: Boolean = true,
    val showTranslation: Boolean = true,
    /** 字幕字号倍率（视频上的字幕） */
    val subtitleScale: Float = 1f,
    /** 字幕离视频底部的距离（dp） */
    val subtitleOffset: Int = 40,
    /** 译文行、原文行各自的字号、颜色、底板、粗细 */
    val translationStyle: LineStyle = LineStyle.TRANSLATION,
    val originalStyle: LineStyle = LineStyle.ORIGINAL,
    /** 双语时原文在上面（默认译文在上） */
    val originalOnTop: Boolean = false,
    /** 上下两行之间的距离（dp） */
    val subtitleLineGap: Int = 2,
    /** 上面那行单独放到画面顶部时离顶部的距离（dp）；-1 为紧挨着下面那行 */
    val topLineFromTop: Int = -1,
    /** 字幕列表自动滚动到当前句 */
    val followPlayback: Boolean = true,
    /** 播放到片头、片尾或自定义跳过区间时自动跳过；关掉时只显示"跳过"按钮 */
    val autoSkip: Boolean = true,
    /** 没有片头片尾记录时，和同一部剧的另一集比对声音自动找出来 */
    val autoDetectIntro: Boolean = true,
    /** 首选音轨、视频自带字幕的语言（ISO 639 代码，逗号分隔，如 "ja,en"）；空为跟随视频默认 */
    val preferredAudioLanguages: String = "",
    val preferredSubtitleLanguages: String = "",
    /** 视频已有 AI / 外挂字幕时，不自动打开视频自带字幕 */
    val preferLoadedSubtitles: Boolean = true,
    /** 在画面上显示 AI 字幕（识别、导入或从视频提取的字幕）；关掉后仍可翻译、朗读 */
    val showAiSubtitles: Boolean = true,
    /** 下载版：链接下载的最高分辨率，0 为不限 */
    val dlQuality: Int = 1080,
    /** 下载版：平台没有字幕时自动 AI 识别 */
    val dlAutoAsr: Boolean = true,
    /** 中文译文的每个字上方标注拼音 */
    val showPinyin: Boolean = false,
    /** 播放时跟随字幕时间朗读译文 */
    val ttsEnabled: Boolean = false,
    val ttsEngine: TtsEngineType = TtsEngineType.AZURE,
    /** Azure 朗读的 Key、区域、接口地址；Key 留空时沿用语音识别里的 Azure 配置 */
    val ttsAzureKey: String = "",
    val ttsAzureRegion: String = "",
    val ttsAzureEndpoint: String = "",
    /** 各引擎的默认语音（旁白、未识别角色的台词用它）；空为该语言的第一个语音 */
    val ttsVoices: Map<TtsEngineType, String> = emptyMap(),
    /** 朗读时原声的音量 */
    val ttsDuckVolume: Float = 0.3f,
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
    /** 主数据源：MetaSource 的名字（TMDB / DOUBAN / BANGUMI / TVDB），其余来源补全缺的字段 */
    val metaPrimary: String = "TMDB",
    /** 动画查 Bangumi 评分和分集标题 */
    val bangumiEnabled: Boolean = true,
    /** TheTVDB v4 的 API Key 和订阅 PIN */
    val tvdbKey: String = "",
    val tvdbPin: String = "",
    /** Fanart.tv 个人 API Key：透明标题图、高清背景图 */
    val fanartKey: String = "",
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

    /** 目标语言是中文时才标拼音（日文汉字的读音不同） */
    val targetIsChinese: Boolean
        get() = targetLanguage.trim().let { t ->
            listOf("中文", "汉语", "漢語", "华语", "華語", "简体", "繁體", "繁体", "普通话", "国语", "國語").any { it in t } ||
                t.contains("chinese", ignoreCase = true) || t.lowercase().startsWith("zh")
        }

    /** 当前是否给译文标拼音：开关打开、系统支持、目标语言是中文 */
    val pinyinActive: Boolean get() = showPinyin && Pinyin.available && targetIsChinese

    /** 朗读用的 Azure 配置：朗读里没填 Key 时用语音识别的 Azure Key 和区域。 */
    fun ttsAzure(): Triple<String, String, String> {
        val key = ttsAzureKey.trim()
        return if (key.isNotEmpty()) Triple(key, ttsAzureRegion.trim().ifEmpty { "eastasia" }, ttsAzureEndpoint.trim())
        else Triple(asrValue(AsrEngine.AZURE, "key"), ttsAzureRegion.trim().ifEmpty { asrValue(AsrEngine.AZURE, "region") }, ttsAzureEndpoint.trim())
    }

    val ttsLocale: String? get() = ttsLocaleFor(targetLanguage)

    fun withConfig(p: Provider, c: ProviderConfig) = copy(providerConfigs = providerConfigs + (p to c))

    /** 当前服务商缺少必填项时返回提示，否则返回 null。 */
    fun translatorProblem(): String? {
        val c = configOf(provider)
        if (provider == Provider.OFFLINE_TRANSLATOR) {
            return if (OfflineTranslator.isInstalled(VoxtitleApp.instance)) null
            else "没有安装 Offline Translator（离线翻译 App）"
        }
        return when {
            provider.needsApiKey && c.apiKey.isEmpty() -> "请先在设置里填写 ${provider.label} 的 API Key"
            c.model.isEmpty() && provider == Provider.LOCAL -> "请先在设置里选择本地模型（点模型一栏右边的箭头列出已装的模型）"
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
            // 国内这两家的接口都兼容 OpenAI；通义千问 3 默认开启思考会很慢，翻译不需要
            Provider.ALIYUN -> ChatCompletionsTranslator(
                c.baseUrl, c.apiKey, c.model,
                extraBody = mapOf("enable_thinking" to false),
            )
            Provider.VOLC -> ChatCompletionsTranslator(
                c.baseUrl, c.apiKey, c.model,
                extraBody = mapOf("thinking" to JSONObject().put("type", "disabled")),
            )
            // 模型一栏填的是资源区域
            Provider.AZURE_TRANSLATOR -> AzureTranslator(c.baseUrl, c.apiKey, c.model)
            // 本地小模型：温度调低更稳；Qwen3 等混合推理模型关掉思考（llama.cpp、vLLM 认这个参数，其他服务会忽略）
            Provider.LOCAL -> ChatCompletionsTranslator(
                c.baseUrl, c.apiKey, c.model,
                temperature = 0.1,
                extraBody = mapOf("chat_template_kwargs" to JSONObject().put("enable_thinking", false)),
            )
            Provider.OFFLINE_TRANSLATOR -> OfflineTranslator(VoxtitleApp.instance, asrLanguage)
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
            autoSaveSrt = prefs.getBoolean("autoSaveSrt", d.autoSaveSrt),
            musicOnlineLookup = prefs.getBoolean("musicOnlineLookup", d.musicOnlineLookup),
            musicBackground = prefs.getBoolean("musicBackground", d.musicBackground),
            videoLeaveAction = runCatching { VideoLeaveAction.valueOf(prefs.getString("videoLeaveAction", d.videoLeaveAction.name)!!) }
                .getOrDefault(d.videoLeaveAction),
            showOriginal = prefs.getBoolean("showOriginal", d.showOriginal),
            showTranslation = prefs.getBoolean("showTranslation", d.showTranslation),
            subtitleScale = prefs.getFloat("subtitleScale", d.subtitleScale),
            subtitleOffset = prefs.getInt("subtitleOffset", d.subtitleOffset),
            translationStyle = LineStyle.decode(prefs.getString("translationStyle", null), d.translationStyle),
            originalStyle = LineStyle.decode(prefs.getString("originalStyle", null), d.originalStyle),
            originalOnTop = prefs.getBoolean("originalOnTop", d.originalOnTop),
            subtitleLineGap = prefs.getInt("subtitleLineGap", d.subtitleLineGap),
            topLineFromTop = prefs.getInt("topLineFromTop", d.topLineFromTop),
            followPlayback = prefs.getBoolean("followPlayback", d.followPlayback),
            autoSkip = prefs.getBoolean("autoSkip", d.autoSkip),
            autoDetectIntro = prefs.getBoolean("autoDetectIntro", d.autoDetectIntro),
            preferredAudioLanguages = prefs.getString("preferredAudioLanguages", d.preferredAudioLanguages)!!,
            preferredSubtitleLanguages = prefs.getString("preferredSubtitleLanguages", d.preferredSubtitleLanguages)!!,
            preferLoadedSubtitles = prefs.getBoolean("preferLoadedSubtitles", d.preferLoadedSubtitles),
            showPinyin = prefs.getBoolean("showPinyin", d.showPinyin),
            showAiSubtitles = prefs.getBoolean("showAiSubtitles", d.showAiSubtitles),
            dlQuality = prefs.getInt("dlQuality", d.dlQuality),
            dlAutoAsr = prefs.getBoolean("dlAutoAsr", d.dlAutoAsr),
            ttsEnabled = prefs.getBoolean("ttsEnabled", d.ttsEnabled),
            ttsEngine = runCatching { TtsEngineType.valueOf(prefs.getString("ttsEngine", d.ttsEngine.name)!!) }.getOrDefault(d.ttsEngine),
            ttsAzureKey = prefs.getString("ttsAzureKey", "")!!,
            ttsAzureRegion = prefs.getString("ttsAzureRegion", "")!!,
            ttsAzureEndpoint = prefs.getString("ttsAzureEndpoint", "")!!,
            ttsVoices = TtsEngineType.entries.mapNotNull { e -> prefs.getString("ttsVoice.${e.name}", null)?.takeIf { it.isNotEmpty() }?.let { e to it } }.toMap(),
            ttsDuckVolume = prefs.getFloat("ttsDuckVolume", d.ttsDuckVolume),
            osApiKey = prefs.getString("osApiKey", "")!!,
            osUsername = prefs.getString("osUsername", "")!!,
            osPassword = prefs.getString("osPassword", "")!!,
            assrtToken = prefs.getString("assrtToken", "")!!,
            subtitleLanguages = prefs.getString("subtitleLanguages", d.subtitleLanguages)!!,
            tmdbKey = prefs.getString("tmdbKey", "")!!,
            tmdbBaseUrl = prefs.getString("tmdbBaseUrl", "")!!,
            tmdbImageBase = prefs.getString("tmdbImageBase", "")!!,
            doubanEnabled = prefs.getBoolean("doubanEnabled", d.doubanEnabled),
            metaPrimary = prefs.getString("metaPrimary", d.metaPrimary)!!,
            bangumiEnabled = prefs.getBoolean("bangumiEnabled", d.bangumiEnabled),
            tvdbKey = prefs.getString("tvdbKey", "")!!,
            tvdbPin = prefs.getString("tvdbPin", "")!!,
            fanartKey = prefs.getString("fanartKey", "")!!,
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
            .putBoolean("autoSaveSrt", s.autoSaveSrt)
            .putBoolean("musicOnlineLookup", s.musicOnlineLookup)
            .putBoolean("musicBackground", s.musicBackground)
            .putString("videoLeaveAction", s.videoLeaveAction.name)
            .putBoolean("showOriginal", s.showOriginal)
            .putBoolean("showTranslation", s.showTranslation)
            .putFloat("subtitleScale", s.subtitleScale)
            .putInt("subtitleOffset", s.subtitleOffset)
            .putString("translationStyle", s.translationStyle.encode())
            .putString("originalStyle", s.originalStyle.encode())
            .putBoolean("originalOnTop", s.originalOnTop)
            .putInt("subtitleLineGap", s.subtitleLineGap)
            .putInt("topLineFromTop", s.topLineFromTop)
            .putBoolean("followPlayback", s.followPlayback)
            .putBoolean("autoSkip", s.autoSkip)
            .putBoolean("autoDetectIntro", s.autoDetectIntro)
            .putString("preferredAudioLanguages", s.preferredAudioLanguages)
            .putString("preferredSubtitleLanguages", s.preferredSubtitleLanguages)
            .putBoolean("preferLoadedSubtitles", s.preferLoadedSubtitles)
            .putBoolean("showPinyin", s.showPinyin)
            .putBoolean("showAiSubtitles", s.showAiSubtitles)
            .putInt("dlQuality", s.dlQuality)
            .putBoolean("dlAutoAsr", s.dlAutoAsr)
            .putBoolean("ttsEnabled", s.ttsEnabled)
            .putString("ttsEngine", s.ttsEngine.name)
            .putString("ttsAzureKey", s.ttsAzureKey)
            .putString("ttsAzureRegion", s.ttsAzureRegion)
            .putString("ttsAzureEndpoint", s.ttsAzureEndpoint)
            .putFloat("ttsDuckVolume", s.ttsDuckVolume)
            .putString("osApiKey", s.osApiKey)
            .putString("osUsername", s.osUsername)
            .putString("osPassword", s.osPassword)
            .putString("assrtToken", s.assrtToken)
            .putString("subtitleLanguages", s.subtitleLanguages)
            .putString("tmdbKey", s.tmdbKey)
            .putString("tmdbBaseUrl", s.tmdbBaseUrl)
            .putString("tmdbImageBase", s.tmdbImageBase)
            .putBoolean("doubanEnabled", s.doubanEnabled)
            .putString("metaPrimary", s.metaPrimary)
            .putBoolean("bangumiEnabled", s.bangumiEnabled)
            .putString("tvdbKey", s.tvdbKey)
            .putString("tvdbPin", s.tvdbPin)
            .putString("fanartKey", s.fanartKey)
        for (e in TtsEngineType.entries) editor.putString("ttsVoice.${e.name}", s.ttsVoices[e].orEmpty())
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
