package com.aisub.player.asr

import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/**
 * 可选的本地识别模型（均来自 k2-fsa/sherpa-onnx 的 asr-models 发布页）。
 * 文件名与配置写法取自 sherpa-onnx 官方 Kotlin 示例 OfflineRecognizer.kt。
 */
data class LocalModel(
    val id: String,
    val label: String,
    val description: String,
    val sizeMb: Int,
    val archive: String,
    /** 识别结果没有标点，需要再过一遍标点模型 */
    val needsPunctuation: Boolean = false,
    /** 支持的语言（语言代码）；null 表示多语种、基本都支持 */
    val languages: Set<String>? = null,
    /** dir：解压后的模型目录；language：语言代码（"auto" 或 zh、en、ru 等，已去掉地区） */
    val build: (dir: File, language: String, threads: Int) -> OfflineModelConfig,
)

object ModelCatalog {

    val PUNCTUATION_ARCHIVE = "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2"

    /** Parakeet TDT v3 支持的英语和 25 种欧洲语言 */
    val EUROPEAN = setOf(
        "en", "bg", "hr", "cs", "da", "nl", "et", "fi", "fr", "de", "el", "hu", "it", "lv", "lt", "mt",
        "pl", "pt", "ro", "sk", "sl", "es", "sv", "ru", "uk",
    )

    val all: List<LocalModel> = listOf(
        LocalModel(
            id = "sense-voice", label = "SenseVoice",
            description = "中/英/日/韩/粤，速度快，自带标点（推荐）", sizeMb = 160,
            archive = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2",
            languages = setOf("zh", "en", "ja", "ko", "yue"),
        ) { dir, lang, threads ->
            OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = dir.firstOnnx().path,
                    language = lang.takeIf { it in setOf("zh", "en", "ja", "ko", "yue") } ?: "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = File(dir, "tokens.txt").path, numThreads = threads,
            )
        },
        LocalModel(
            id = "paraformer-zh", label = "Paraformer 中文",
            description = "阿里 FunASR，普通话准确率高（配标点模型）", sizeMb = 217 + 61,
            archive = "sherpa-onnx-paraformer-zh-int8-2025-10-07.tar.bz2", needsPunctuation = true,
            languages = setOf("zh"),
        ) { dir, _, threads ->
            OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(model = File(dir, "model.int8.onnx").path),
                tokens = File(dir, "tokens.txt").path, modelType = "paraformer", numThreads = threads,
            )
        },
        LocalModel(
            id = "fire-red-asr2", label = "FireRedASR2 中英",
            description = "小红书开源，中文和方言效果强，模型较大", sizeMb = 496 + 61,
            archive = "sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25.tar.bz2", needsPunctuation = true,
            languages = setOf("zh", "en", "yue"),
        ) { dir, _, threads ->
            OfflineModelConfig(
                fireRedAsrCtc = OfflineFireRedAsrCtcModelConfig(model = File(dir, "model.int8.onnx").path),
                tokens = File(dir, "tokens.txt").path, numThreads = threads,
            )
        },
        LocalModel(
            id = "qwen3-asr", label = "Qwen3-ASR 0.6B",
            description = "通义千问语音识别，多语种效果好；体积大，适合高端机", sizeMb = 837,
            archive = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
        ) { dir, _, threads ->
            OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = File(dir, "conv_frontend.onnx").path,
                    encoder = File(dir, "encoder.int8.onnx").path,
                    decoder = File(dir, "decoder.int8.onnx").path,
                    tokenizer = File(dir, "tokenizer").path,
                ),
                tokens = "", numThreads = threads,
            )
        },
        LocalModel(
            id = "whisper-base", label = "Whisper base",
            description = "OpenAI，99 种语言，覆盖面最广", sizeMb = 197,
            archive = "sherpa-onnx-whisper-base.tar.bz2",
        ) { dir, lang, threads ->
            OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(dir, "base-encoder.int8.onnx").path,
                    decoder = File(dir, "base-decoder.int8.onnx").path,
                    // 空字符串表示自动检测语言；base 模型没有单独的粤语
                    language = when (lang) { "auto" -> ""; "yue" -> "zh"; else -> lang },
                    enableTokenTimestamps = true,
                ),
                tokens = File(dir, "base-tokens.txt").path, modelType = "whisper", numThreads = threads,
            )
        },
        LocalModel(
            id = "parakeet-v3", label = "Parakeet TDT 0.6B v3",
            description = "NVIDIA，英语及 25 种欧洲语言，准确率高", sizeMb = 464,
            archive = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
            languages = EUROPEAN,
        ) { dir, _, threads ->
            OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").path,
                    decoder = File(dir, "decoder.int8.onnx").path,
                    joiner = File(dir, "joiner.int8.onnx").path,
                ),
                tokens = File(dir, "tokens.txt").path, modelType = "nemo_transducer", numThreads = threads,
            )
        },
        LocalModel(
            id = "moonshine-base-en", label = "Moonshine base",
            description = "仅英语，体积小、速度快，适合低端机", sizeMb = 239,
            archive = "sherpa-onnx-moonshine-base-en-int8.tar.bz2",
            languages = setOf("en"),
        ) { dir, _, threads ->
            OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = File(dir, "preprocess.onnx").path,
                    encoder = File(dir, "encode.int8.onnx").path,
                    uncachedDecoder = File(dir, "uncached_decode.int8.onnx").path,
                    cachedDecoder = File(dir, "cached_decode.int8.onnx").path,
                ),
                tokens = File(dir, "tokens.txt").path, numThreads = threads,
            )
        },
        LocalModel(
            id = "dolphin-base", label = "Dolphin base",
            description = "东亚、东南亚多种语言及中文方言", sizeMb = 76 + 61,
            archive = "sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02.tar.bz2", needsPunctuation = true,
            languages = setOf("zh", "yue", "ja", "ko", "th", "vi", "id", "ms", "my", "km", "lo", "tl", "fil", "hi", "bn", "ur", "ta", "ar", "fa", "ru", "uz", "kk", "ug", "mn"),
        ) { dir, _, threads ->
            OfflineModelConfig(
                dolphin = OfflineDolphinModelConfig(model = File(dir, "model.int8.onnx").path),
                tokens = File(dir, "tokens.txt").path, numThreads = threads,
            )
        },
    )

    fun byId(id: String): LocalModel = all.firstOrNull { it.id == id } ?: all.first()
}

/** 目录里第一个 .onnx 文件（SenseVoice 不同版本的文件名不一样）。 */
private fun File.firstOnnx(): File =
    listFiles()?.filter { it.name.endsWith(".onnx") }?.minByOrNull { it.name } ?: File(this, "model.int8.onnx")
