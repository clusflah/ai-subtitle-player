package com.aisub.player.asr

import com.aisub.player.subtitle.Cue
import com.aisub.player.subtitle.CueSplitter
import com.aisub.player.subtitle.TimedToken
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * VAD 切出语音段 → SenseVoice 离线识别 → 生成字幕。
 * VAD 能跳过静音和纯音乐，既省算力也避免模型"幻听"。
 *
 * @param language SenseVoice 的语言代码："auto"、"zh"、"en"、"ja"、"ko"、"yue"
 */
class Transcriber(paths: ModelManager.Paths, language: String, numThreads: Int) : SpeechEngine {

    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = paths.vad.absolutePath,
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
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = paths.senseVoiceModel.absolutePath,
                    language = language,
                    useInverseTextNormalization = true,
                ),
                tokens = paths.tokens.absolutePath,
                numThreads = numThreads,
                provider = "cpu",
            ),
        ),
    )

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
        val text = result.text.trim()
        if (text.isEmpty() || text.all { it in PUNCTUATION || it.isWhitespace() }) return emptyList()

        val startMs = segment.start * 1000L / SAMPLE_RATE
        val endMs = startMs + segment.samples.size * 1000L / SAMPLE_RATE

        // 优先用逐 token 的时间戳切分（SenseVoice 的标点和数字转换在模型内完成，token 与最终文本一致）
        val tokens = result.tokens
        val stamps = result.timestamps
        val lines = if (tokens.isNotEmpty() && tokens.size == stamps.size) {
            val timed = tokens.indices
                .filterNot { tokens[it].startsWith("<|") } // 语言、情感等特殊标记
                .map { TimedToken(tokens[it].replace('▁', ' '), startMs + (stamps[it] * 1000).toLong()) }
            CueSplitter.split(timed, endMs)
        } else {
            splitLong(text, startMs, endMs - startMs)
        }
        return lines.map { (s, e, t) -> Cue(nextId++, s, e, t) }
    }

    /**
     * 没有逐字时间戳时的后备方案：在句末标点处拆开，按字数比例分配时间。
     */
    private fun splitLong(text: String, startMs: Long, durationMs: Long): List<Triple<Long, Long, String>> {
        if (durationMs <= MAX_CUE_MS) return listOf(Triple(startMs, startMs + durationMs, text))
        val sentences = SENTENCE_SPLIT.findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        if (sentences.size <= 1) return listOf(Triple(startMs, startMs + durationMs, text))

        val totalChars = sentences.sumOf { it.length }.toDouble()
        val result = ArrayList<Triple<Long, Long, String>>()
        var cursor = startMs
        for ((i, s) in sentences.withIndex()) {
            val end = if (i == sentences.lastIndex) startMs + durationMs
            else cursor + (durationMs * s.length / totalChars).toLong()
            result.add(Triple(cursor, end, s))
            cursor = end
        }
        return result
    }

    override fun close() {
        vad.release()
        recognizer.release()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val VAD_WINDOW = 512
        private const val MAX_CUE_MS = 6_000L
        private const val PUNCTUATION = "。，、！？；：,.!?;:…\"'“”‘’"
        private val SENTENCE_SPLIT = Regex("[^。！？!?；;]+[。！？!?；;]*")
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
