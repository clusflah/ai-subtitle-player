package com.aisub.player.asr

import com.aisub.player.subtitle.Cue
import com.aisub.player.subtitle.CueSplitter
import com.aisub.player.subtitle.TimedToken
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * 本地识别：VAD 切出语音段 → sherpa-onnx 离线模型识别 →（需要时）补标点 → 按逐字时间戳切成字幕。
 * VAD 能跳过静音和纯音乐，既省算力也避免模型"幻听"。
 *
 * @param language 应用里的语言代码："auto"、"zh"、"en"、"ja"、"ko"、"yue"
 */
class Transcriber(
    model: LocalModel,
    models: ModelManager,
    language: String,
    numThreads: Int,
) : SpeechEngine {

    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = models.vadFile.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.35f,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW,
                maxSpeechDuration = 12f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = model.build(models.dirOf(model), language, numThreads),
        ),
    )

    private val punctuation: OfflinePunctuation? = if (model.needsPunctuation) {
        val onnx = models.punctuationDir.listFiles()?.firstOrNull { it.name.endsWith(".onnx") }
            ?: File(models.punctuationDir, "model.int8.onnx")
        OfflinePunctuation(config = OfflinePunctuationConfig(OfflinePunctuationModelConfig(ctTransformer = onnx.path, numThreads = 1)))
    } else null

    private val pending = FloatArrayBuilder()
    private var nextId = 1

    override fun accept(samples: FloatArray, onCue: (Cue) -> Unit) {
        pending.append(samples)
        while (pending.size >= VAD_WINDOW) {
            vad.acceptWaveform(pending.take(VAD_WINDOW))
            drain(onCue)
        }
    }

    override fun finish(onCue: (Cue) -> Unit) {
        vad.flush()
        drain(onCue)
    }

    private fun drain(onCue: (Cue) -> Unit) {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            recognize(segment).forEach(onCue)
        }
    }

    private fun recognize(segment: SpeechSegment): List<Cue> {
        val stream = recognizer.createStream()
        val result = try {
            stream.acceptWaveform(segment.samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream)
        } finally {
            stream.release()
        }
        var text = result.text.trim()
        if (text.isEmpty() || text.all { it in CueSplitter.PUNCTUATION || it.isWhitespace() }) return emptyList()
        punctuation?.let { text = it.addPunctuation(text) }

        val startMs = segment.start * 1000L / SAMPLE_RATE
        val endMs = startMs + segment.samples.size * 1000L / SAMPLE_RATE

        // 优先用逐 token 的时间戳切分；没有时间戳的模型（如 Qwen3）按字数比例分配
        val tokens = result.tokens
        val stamps = result.timestamps
        val lines = if (tokens.isNotEmpty() && tokens.size == stamps.size) {
            val kept = tokens.indices.filterNot { tokens[it].startsWith("<|") } // 语言、情感等特殊标记
            var texts = CueSplitter.normalizeTokens(kept.map { tokens[it] })
            if (punctuation != null) texts = CueSplitter.attachPunctuation(texts, text)
            val timed = kept.mapIndexed { i, k -> TimedToken(texts[i], startMs + (stamps[k] * 1000).toLong()) }
            CueSplitter.split(timed, endMs)
        } else {
            CueSplitter.splitProportional(text, startMs, endMs)
        }
        return lines.map { (s, e, t) -> Cue(nextId++, s, e, t) }
    }

    override fun close() {
        vad.release()
        recognizer.release()
        punctuation?.release()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val VAD_WINDOW = 512
    }
}

/** 简单的 float 缓冲区，避免频繁装箱。 */
private class FloatArrayBuilder {
    private var data = FloatArray(16_000)
    var size = 0
        private set

    fun append(src: FloatArray) {
        if (size + src.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + src.size))
        System.arraycopy(src, 0, data, size, src.size)
        size += src.size
    }

    fun take(n: Int): FloatArray {
        val out = data.copyOfRange(0, n)
        System.arraycopy(data, n, data, 0, size - n)
        size -= n
        return out
    }
}
