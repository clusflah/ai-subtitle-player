package com.aisub.player

import com.aisub.player.asr.AsrLanguages
import com.aisub.player.asr.SpeechEngine
import com.aisub.player.asr.cloud.AliyunFunAsrTranscriber
import com.aisub.player.asr.cloud.AliyunStreamingTranscriber
import com.aisub.player.asr.cloud.AzureStreamingTranscriber
import com.aisub.player.asr.cloud.VolcStreamingTranscriber
import com.aisub.player.asr.cloud.AzureTranscriber
import com.aisub.player.asr.cloud.DeepgramTranscriber
import com.aisub.player.asr.cloud.ElevenLabsTranscriber
import com.aisub.player.asr.cloud.GeminiTranscriber
import com.aisub.player.asr.cloud.OpenAiTranscriber
import com.aisub.player.asr.cloud.VolcEngineTranscriber
import com.aisub.player.asr.cloud.XfyunTranscriber

/** 识别引擎的一个配置项。 */
data class AsrField(
    val key: String,
    val label: String,
    val secret: Boolean = false,
    val required: Boolean = true,
    val default: String = "",
    val placeholder: String = "",
)

enum class AsrEngine(val label: String, val hint: String, val fields: List<AsrField> = emptyList()) {
    LOCAL("手机上识别（免费）", "在手机上离线识别，不花钱、不联网；第一次要下载模型，可选多种"),
    AZURE(
        "Azure", "微软 Azure 语音快速转写，多语种",
        listOf(
            AsrField("key", "Speech 资源 Key", secret = true),
            AsrField("region", "区域（Region）", default = "eastasia", placeholder = "例如 eastasia、southeastasia"),
            AsrField("endpoint", "自定义 Endpoint（可不填）", required = false, placeholder = "中国区：https://chinanorth3.api.cognitive.azure.cn"),
        ),
    ),
    VOLC(
        "火山豆包", "字节跳动豆包语音·录音文件极速版，中文影视口语效果好",
        listOf(
            AsrField("appKey", "API Key（旧版控制台填 APP ID）", secret = true),
            AsrField("accessKey", "Access Token（仅旧版控制台需要）", secret = true, required = false),
        ),
    ),
    ALIYUN(
        "阿里 Fun-ASR", "阿里云百炼 Fun-ASR，中文、方言、多语种",
        listOf(
            AsrField("apiKey", "百炼 API Key", secret = true),
            AsrField("model", "模型", default = AliyunFunAsrTranscriber.DEFAULT_MODEL),
            AsrField("endpoint", "接口地址", default = AliyunFunAsrTranscriber.DEFAULT_ENDPOINT, placeholder = "国际站：https://dashscope-intl.aliyuncs.com"),
        ),
    ),
    XFYUN(
        "讯飞转写", "讯飞录音文件转写，中文老牌、稳定（需要排队，较慢）",
        listOf(
            AsrField("appId", "APPID"),
            AsrField("secretKey", "SecretKey", secret = true),
        ),
    ),
    GEMINI(
        "Gemini", "Google Gemini：一次请求同时识别和翻译成中文，出字幕最快，多语种强（需要能访问 Google）",
        listOf(
            AsrField("apiKey", "Gemini API Key", secret = true),
            AsrField("model", "模型", default = GeminiTranscriber.DEFAULT_MODEL),
            AsrField("endpoint", "接口地址", default = GeminiTranscriber.DEFAULT_ENDPOINT, required = false),
        ),
    ),
    ELEVENLABS(
        "ElevenLabs", "Scribe 模型，多语种准确率第一梯队",
        listOf(
            AsrField("apiKey", "API Key", secret = true),
            AsrField("model", "模型", default = ElevenLabsTranscriber.DEFAULT_MODEL),
        ),
    ),
    DEEPGRAM(
        "Deepgram", "Nova 模型，英语又快又便宜",
        listOf(
            AsrField("apiKey", "API Key", secret = true),
            AsrField("model", "模型（留空自动：中文 nova-2，其余 nova-3）", required = false),
        ),
    ),
    OPENAI(
        "OpenAI", "Whisper 转写；也可填 Groq 等兼容接口",
        listOf(
            AsrField("apiKey", "API Key", secret = true),
            AsrField("model", "模型", default = OpenAiTranscriber.DEFAULT_MODEL, placeholder = "whisper-1 有时间戳；gpt-4o 系列没有"),
            AsrField("baseUrl", "Base URL", default = OpenAiTranscriber.DEFAULT_BASE_URL),
        ),
    ),
}

/** 读取某引擎的配置项，未填用默认值。 */
fun AppSettings.asrValue(engine: AsrEngine, key: String): String {
    val field = engine.fields.firstOrNull { it.key == key }
    return asrConfigs[engine]?.get(key)?.trim().orEmpty().ifEmpty { field?.default.orEmpty() }
}

fun AppSettings.withAsrValue(engine: AsrEngine, key: String, value: String): AppSettings {
    val current = asrConfigs[engine].orEmpty()
    return copy(asrConfigs = asrConfigs + (engine to current + (key to value)))
}

/** 当前识别引擎缺少必填项时返回提示，否则返回 null。 */
fun AppSettings.asrProblem(): String? {
    val missing = asrEngine.fields.firstOrNull { it.required && asrValue(asrEngine, it.key).isEmpty() } ?: return null
    return "请先在设置里填写 ${asrEngine.label} 的「${missing.label}」"
}

/** 创建云端识别引擎（本地引擎由 ViewModel 创建，需要模型文件）。 */
fun AppSettings.createCloudEngine(): SpeechEngine {
    val v = { key: String -> asrValue(asrEngine, key) }
    // 大多数接口只认语言代码；Azure 要 locale，Deepgram 认得带地区的写法（pt-BR、es-419）
    val lang = AsrLanguages.primary(asrLanguage)
    return when (asrEngine) {
        AsrEngine.LOCAL -> error("本地引擎不在这里创建")
        AsrEngine.AZURE -> AzureTranscriber(
            v("endpoint").ifEmpty { AzureTranscriber.defaultEndpoint(v("region")) }, v("key"), AzureTranscriber.localesFor(asrLanguage),
        )
        AsrEngine.VOLC -> VolcEngineTranscriber(v("appKey"), v("accessKey"))
        AsrEngine.ALIYUN -> AliyunFunAsrTranscriber(v("apiKey"), v("endpoint"), v("model"), lang)
        AsrEngine.XFYUN -> XfyunTranscriber(v("appId"), v("secretKey"), asrLanguage)
        AsrEngine.GEMINI -> GeminiTranscriber(
            v("apiKey"), v("model"), v("endpoint"),
            if (lang == "auto") "" else AsrLanguages.label(asrLanguage),
        )
        AsrEngine.ELEVENLABS -> ElevenLabsTranscriber(v("apiKey"), v("model"), lang)
        AsrEngine.DEEPGRAM -> DeepgramTranscriber(v("apiKey"), v("model"), asrLanguage)
        AsrEngine.OPENAI -> OpenAiTranscriber(v("apiKey"), v("baseUrl"), v("model"), lang)
    }
}

/** 支持流式识别的引擎 */
val AsrEngine.supportsStreaming: Boolean get() = this == AsrEngine.AZURE || this == AsrEngine.VOLC || this == AsrEngine.ALIYUN

/** 当前设置能用流式识别（Azure 流式必须选定视频语言，选「自动」时照用分段识别） */
val AppSettings.streamingAvailable: Boolean
    get() = asrEngine.supportsStreaming && (asrEngine != AsrEngine.AZURE || AsrLanguages.azureLocale(asrLanguage) != null)

/**
 * 流式识别引擎；当前引擎不支持流式、或者条件不够（Azure 流式要选定视频语言）时返回 null，照用分段识别。
 */
fun AppSettings.createStreamingEngine(): SpeechEngine? {
    val v = { key: String -> asrValue(asrEngine, key) }
    return when (asrEngine) {
        AsrEngine.AZURE -> {
            val locale = AsrLanguages.azureLocale(asrLanguage) ?: return null
            // 中国区的自定义地址形如 https://chinanorth3.api.cognitive.azure.cn，区域取第一段
            val endpoint = v("endpoint")
            val china = ".azure.cn" in endpoint
            val region = if (china) endpoint.substringAfter("://").substringBefore('.') else v("region").trim()
            AzureStreamingTranscriber(v("key"), region, china, locale)
        }
        AsrEngine.VOLC -> VolcStreamingTranscriber(v("appKey"), v("accessKey"))
        AsrEngine.ALIYUN -> AliyunStreamingTranscriber(v("apiKey"), v("endpoint"), AsrLanguages.primary(asrLanguage))
        else -> null
    }
}

/**
 * 当前引擎明显不支持所选语言时给出提示（仍会尝试识别，通常按自动检测处理，准确率会很低）。
 */
fun AppSettings.asrLanguageWarning(): String? {
    val lang = AsrLanguages.primary(asrLanguage)
    if (lang == "auto") return null
    val name = AsrLanguages.label(asrLanguage)
    if (asrLanguage == AsrLanguages.SICHUAN) return when (asrEngine) {
        // Azure 有 zh-CN-sichuan；豆包大模型自动识别方言
        AsrEngine.AZURE, AsrEngine.VOLC -> null
        AsrEngine.XFYUN -> "讯飞需要在控制台开通「西南官话」方言，否则会报错"
        AsrEngine.LOCAL -> {
            val model = com.aisub.player.asr.ModelCatalog.byId(localModel)
            if (model.id in setOf("qwen3-asr", "dolphin-base", "fire-red-asr2")) null
            else "「${model.label}」不认识四川话，会按普通话识别；建议换 Qwen3-ASR、Dolphin 或 FireRedASR2，或用 Azure、讯飞、豆包"
        }
        else -> "${asrEngine.label}没有四川话选项，会按普通话识别；建议换 Azure、讯飞、豆包，或本地 Qwen3-ASR、Dolphin"
    }
    return when (asrEngine) {
        AsrEngine.LOCAL -> {
            val model = com.aisub.player.asr.ModelCatalog.byId(localModel)
            val supported = model.languages ?: return null
            if (lang in supported) null
            else "「${model.label}」不支持$name，识别效果会很差；建议换 Whisper、Qwen3-ASR" +
                (if (lang in com.aisub.player.asr.ModelCatalog.EUROPEAN) "、Parakeet" else "") + "，或用云端服务"
        }
        AsrEngine.XFYUN -> if (lang in setOf("zh", "yue", "en", "ja", "ko")) null else "讯飞录音转写只支持中英日韩粤，$name 会按中文识别；建议换其他引擎"
        else -> null
    }
}
