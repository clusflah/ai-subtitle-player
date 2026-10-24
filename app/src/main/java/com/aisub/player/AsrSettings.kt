package com.aisub.player

import com.aisub.player.asr.AsrLanguages
import com.aisub.player.asr.SpeechEngine
import com.aisub.player.asr.cloud.AliyunFunAsrTranscriber
import com.aisub.player.asr.cloud.AzureTranscriber
import com.aisub.player.asr.cloud.DeepgramTranscriber
import com.aisub.player.asr.cloud.ElevenLabsTranscriber
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
    LOCAL("本地模型", "在手机上离线识别，免费；可选多种模型"),
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
        AsrEngine.XFYUN -> XfyunTranscriber(v("appId"), v("secretKey"), lang)
        AsrEngine.ELEVENLABS -> ElevenLabsTranscriber(v("apiKey"), v("model"), lang)
        AsrEngine.DEEPGRAM -> DeepgramTranscriber(v("apiKey"), v("model"), asrLanguage)
        AsrEngine.OPENAI -> OpenAiTranscriber(v("apiKey"), v("baseUrl"), v("model"), lang)
    }
}

/**
 * 当前引擎明显不支持所选语言时给出提示（仍会尝试识别，通常按自动检测处理，准确率会很低）。
 */
fun AppSettings.asrLanguageWarning(): String? {
    val lang = AsrLanguages.primary(asrLanguage)
    if (lang == "auto") return null
    val name = AsrLanguages.label(asrLanguage)
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
