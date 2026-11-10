package androidx.media3.extractor.text

import androidx.media3.common.C
import androidx.media3.common.Format

class TextCue(val text: CharSequence?)

class CuesWithTiming(
    val cues: List<TextCue>,
    val startTimeUs: Long,
    val durationUs: Long = C.TIME_UNSET,
)

class CueDecoder {
    fun decode(startTimeUs: Long, byteArray: ByteArray, offset: Int, length: Int): CuesWithTiming {
        val text = String(byteArray, offset, length, Charsets.UTF_8)
        return CuesWithTiming(listOf(TextCue(text)), startTimeUs, 2_000_000L)
    }
}

interface SubtitleParser {
    interface Factory {
        fun supportsFormat(format: Format): Boolean = false
        fun getCueReplacementBehavior(format: Format): Int = 1
        fun create(format: Format): SubtitleParser = object : SubtitleParser {}
    }
}

class DefaultSubtitleParserFactory : SubtitleParser.Factory
