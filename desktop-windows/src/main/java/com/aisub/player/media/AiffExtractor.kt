package com.aisub.player.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.text.SubtitleParser
import kotlin.math.pow

/**
 * AIFF / AIFF-C（.aif .aiff .aifc）解封装。ExoPlayer 自带的只有 WAV，AIFF 里同样是未压缩的 PCM，
 * 只是字节序是大端：这里换成小端交给 ExoPlayer。支持 8 / 16 / 24 / 32 位整数、AIFF-C 的 sowt（小端）和 fl32（浮点）；
 * 文件里的「ID3 」块（歌名、歌手、封面）放在 SSND 前面时一起读出来。
 */
@OptIn(UnstableApi::class)
class AiffExtractor : Extractor {

    private lateinit var output: ExtractorOutput
    private lateinit var track: TrackOutput

    // 头部解析结果
    private var headerParsed = false
    private var formatSent = false
    private var channels = 0
    private var sampleRate = 0.0
    private var bytesPerSample = 0
    private var conversion = Conversion.SWAP
    private var dataStart = 0L
    private var dataEnd = 0L
    private var bytesPerFrame = 0

    // 读样本
    private var buffer = ByteArray(0)
    private var pending = 0
    private var framesOutput = 0L

    private enum class Conversion { PASSTHROUGH, SWAP, SIGNED_TO_UNSIGNED }

    override fun sniff(input: ExtractorInput): Boolean {
        val head = ByteArray(12)
        if (!input.peekFully(head, 0, 12, true)) return false
        return fourCc(head, 0) == "FORM" && fourCc(head, 8).let { it == "AIFF" || it == "AIFC" }
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        track = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headerParsed) {
            readHeader(input)
            // 头部读完正好停在 PCM 数据开头
        }
        return readSamples(input)
    }

    override fun seek(position: Long, timeUs: Long) {
        pending = 0
        if (position == 0L) {
            headerParsed = false
            framesOutput = 0
        } else {
            framesOutput = if (bytesPerFrame > 0) (position - dataStart) / bytesPerFrame else 0
        }
    }

    override fun release() {}

    // ---------------- 头部 ----------------

    private fun readHeader(input: ExtractorInput) {
        val form = ByteArray(12)
        input.readFully(form, 0, 12)
        val aifc = fourCc(form, 8) == "AIFC"
        var compression = "NONE"
        var sampleBits = 0
        var metadata: Metadata? = null
        var haveComm = false
        val chunkHead = ByteArray(8)
        while (true) {
            if (!input.readFully(chunkHead, 0, 8, true)) throw ParserException.createForMalformedContainer("AIFF：没有找到音频数据（SSND）", null)
            val id = fourCc(chunkHead, 0)
            val size = int32(chunkHead, 4).toLong() and 0xffffffffL
            val padded = size + (size and 1)
            when (id) {
                "COMM" -> {
                    val c = ByteArray(size.toInt())
                    input.readFully(c, 0, c.size)
                    if (padded > size) input.skipFully(1)
                    channels = int16(c, 0)
                    sampleBits = int16(c, 6)
                    sampleRate = extended80(c, 8)
                    if (aifc && c.size >= 22) compression = fourCc(c, 18)
                    haveComm = true
                }
                "ID3 ", "id3 " -> {
                    val c = ByteArray(size.toInt())
                    input.readFully(c, 0, c.size)
                    if (padded > size) input.skipFully(1)
                    metadata = runCatching { Id3Decoder().decode(c, c.size) }.getOrNull()
                }
                "SSND" -> {
                    if (!haveComm) throw ParserException.createForMalformedContainer("AIFF：COMM 块应在 SSND 前面", null)
                    val s = ByteArray(8)
                    input.readFully(s, 0, 8)
                    val offset = int32(s, 0).toLong() and 0xffffffffL
                    input.skipFully(offset.toInt())
                    dataStart = input.position
                    dataEnd = dataStart + size - 8 - offset
                    break
                }
                else -> input.skipFully(padded.toInt())
            }
        }

        bytesPerSample = (sampleBits + 7) / 8
        val encoding = when (compression) {
            "NONE", "twos" -> {
                conversion = if (bytesPerSample == 1) Conversion.SIGNED_TO_UNSIGNED else Conversion.SWAP
                pcmEncoding(bytesPerSample)
            }
            "sowt" -> {
                conversion = Conversion.PASSTHROUGH
                pcmEncoding(bytesPerSample)
            }
            "fl32", "FL32" -> {
                conversion = Conversion.SWAP
                bytesPerSample = 4
                C.ENCODING_PCM_FLOAT
            }
            else -> C.ENCODING_INVALID
        }
        if (encoding == C.ENCODING_INVALID || channels <= 0 || sampleRate <= 0) {
            throw ParserException.createForUnsupportedContainerFeature("AIFF：不支持的格式（$compression，$sampleBits 位）")
        }
        bytesPerFrame = channels * bytesPerSample
        val rate = sampleRate.toInt()
        // 每次送大约 0.1 秒
        buffer = ByteArray(maxOf(1, rate / 10) * bytesPerFrame)
        headerParsed = true
        // 拖回开头时会重读头部，格式和 SeekMap 只报一次
        if (formatSent) return
        formatSent = true
        track.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setContainerMimeType(MimeTypes.AUDIO_RAW)
                .setChannelCount(channels)
                .setSampleRate(rate)
                .setPcmEncoding(encoding)
                .setAverageBitrate(rate * bytesPerFrame * 8)
                .setPeakBitrate(rate * bytesPerFrame * 8)
                .setMaxInputSize(buffer.size)
                .setMetadata(metadata)
                .build(),
        )
        output.seekMap(AiffSeekMap(dataStart, dataEnd, bytesPerFrame, rate))
    }

    private fun pcmEncoding(bytes: Int) = when (bytes) {
        1 -> C.ENCODING_PCM_8BIT
        2 -> C.ENCODING_PCM_16BIT
        3 -> C.ENCODING_PCM_24BIT
        4 -> C.ENCODING_PCM_32BIT
        else -> C.ENCODING_INVALID
    }

    // ---------------- 样本 ----------------

    private fun readSamples(input: ExtractorInput): Int {
        val remaining = dataEnd - input.position
        var ended = remaining <= 0
        if (!ended) {
            val want = minOf((buffer.size - pending).toLong(), remaining).toInt()
            val n = input.read(buffer, pending, want)
            if (n == C.RESULT_END_OF_INPUT) ended = true else pending += n
        }
        val whole = pending / bytesPerFrame * bytesPerFrame
        // 攒够一块再送；文件结尾时把剩下的整帧都送出去
        if (whole > 0 && (pending == buffer.size || ended || input.position >= dataEnd)) {
            convert(buffer, whole)
            track.sampleData(androidx.media3.common.util.ParsableByteArray(buffer.copyOf(whole)), whole)
            val timeUs = framesOutput * C.MICROS_PER_SECOND / sampleRate.toLong()
            track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, whole, 0, null)
            framesOutput += whole / bytesPerFrame
            System.arraycopy(buffer, whole, buffer, 0, pending - whole)
            pending -= whole
        }
        return if (ended || input.position >= dataEnd && pending < bytesPerFrame) Extractor.RESULT_END_OF_INPUT else Extractor.RESULT_CONTINUE
    }

    private fun convert(b: ByteArray, length: Int) {
        when (conversion) {
            Conversion.PASSTHROUGH -> Unit
            Conversion.SIGNED_TO_UNSIGNED -> for (i in 0 until length) b[i] = (b[i].toInt() xor 0x80).toByte()
            Conversion.SWAP -> {
                val w = bytesPerSample
                var i = 0
                while (i + w <= length) {
                    var lo = i
                    var hi = i + w - 1
                    while (lo < hi) {
                        val t = b[lo]; b[lo] = b[hi]; b[hi] = t
                        lo++; hi--
                    }
                    i += w
                }
            }
        }
    }

    /** PCM 是定长帧，按时间直接算出文件位置。 */
    private class AiffSeekMap(
        private val dataStart: Long,
        private val dataEnd: Long,
        private val bytesPerFrame: Int,
        private val sampleRate: Int,
    ) : SeekMap {
        private val totalFrames = (dataEnd - dataStart) / bytesPerFrame

        override fun isSeekable() = true

        override fun getDurationUs() = totalFrames * C.MICROS_PER_SECOND / sampleRate

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val frame = (timeUs * sampleRate / C.MICROS_PER_SECOND).coerceIn(0, maxOf(0, totalFrames - 1))
            return SeekMap.SeekPoints(SeekPoint(frame * C.MICROS_PER_SECOND / sampleRate, dataStart + frame * bytesPerFrame))
        }
    }

    companion object {
        fun isAiffName(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("aif", "aiff", "aifc")

        private fun fourCc(b: ByteArray, at: Int) = String(b, at, 4, Charsets.ISO_8859_1)

        private fun int16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff shl 8) or (b[at + 1].toInt() and 0xff)

        private fun int32(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xff shl 24) or (b[at + 1].toInt() and 0xff shl 16) or (b[at + 2].toInt() and 0xff shl 8) or (b[at + 3].toInt() and 0xff)

        /** COMM 里的采样率是 80 位扩展精度浮点数（IEEE 754 extended）。 */
        private fun extended80(b: ByteArray, at: Int): Double {
            val exponent = ((b[at].toInt() and 0x7f) shl 8) or (b[at + 1].toInt() and 0xff)
            var mantissa = 0.0
            for (i in 0 until 8) mantissa = mantissa * 256 + (b[at + 2 + i].toInt() and 0xff)
            if (exponent == 0 && mantissa == 0.0) return 0.0
            val v = mantissa * 2.0.pow(exponent - 16383 - 63)
            return if (b[at].toInt() and 0x80 != 0) -v else v
        }
    }
}

/** 在已有的解封装列表前面加上 AIFF；其余设置原样转给原来的 factory。 */
@OptIn(UnstableApi::class)
fun ExtractorsFactory.withAiff(): ExtractorsFactory {
    val base = this
    return object : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> = arrayOf<Extractor>(AiffExtractor()) + base.createExtractors()

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            arrayOf<Extractor>(AiffExtractor()) + base.createExtractors(uri, responseHeaders)

        override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory =
            base.experimentalSetTextTrackTranscodingEnabled(enabled).withAiff()

        override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory =
            base.setSubtitleParserFactory(factory).withAiff()

        override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int): ExtractorsFactory =
            base.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs).withAiff()
    }
}
