package androidx.media3.extractor

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.text.SubtitleParser

class PositionHolder {
    @JvmField
    var position: Long = 0L
}

data class SeekPoint(val timeUs: Long, val position: Long) {
    companion object {
        val START = SeekPoint(0L, 0L)
    }
}

interface SeekMap {
    fun isSeekable(): Boolean
    fun getDurationUs(): Long
    fun getSeekPoints(timeUs: Long): SeekPoints

    class SeekPoints(val first: SeekPoint, val second: SeekPoint = first)

    open class Unseekable(private val durationUs: Long, startPosition: Long = 0L) : SeekMap {
        private val startSeekPoints = SeekPoints(if (startPosition == 0L) SeekPoint.START else SeekPoint(0L, startPosition))
        override fun isSeekable(): Boolean = false
        override fun getDurationUs(): Long = durationUs
        override fun getSeekPoints(timeUs: Long): SeekPoints = startSeekPoints
    }
}

interface ExtractorInput : DataReader {
    val position: Long
    val length: Long
    val peekPosition: Long

    fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean
    fun readFully(target: ByteArray, offset: Int, length: Int)
    fun skip(length: Int): Int
    fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean
    fun skipFully(length: Int)
    fun peek(target: ByteArray, offset: Int, length: Int): Int
    fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean
    fun peekFully(target: ByteArray, offset: Int, length: Int)
    fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean
    fun advancePeekPosition(length: Int)
    fun resetPeekPosition()
}

interface TrackOutput {
    class CryptoData(
        val cryptoMode: Int,
        val encryptionKey: ByteArray,
        val encryptedBlocks: Int,
        val clearBlocks: Int,
    )

    fun format(format: Format)
    fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
        sampleData(input, length, allowEndOfInput, 0)
    fun sampleData(data: ParsableByteArray, length: Int) =
        sampleData(data, length, 0)
    fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int
    fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int)
    fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: CryptoData?)
}

class DiscardingTrackOutput : TrackOutput {
    override fun format(format: Format) {}
    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        val buf = ByteArray(minOf(length, 4096))
        val n = input.read(buf, 0, buf.size)
        return if (n == C.RESULT_END_OF_INPUT && allowEndOfInput) C.RESULT_END_OF_INPUT else n
    }
    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        data.position = (data.position + length).coerceAtMost(data.limit)
    }
    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {}
}

interface ExtractorOutput {
    fun track(id: Int, type: Int): TrackOutput
    fun endTracks()
    fun seekMap(seekMap: SeekMap)
}

interface Extractor {
    fun sniff(input: ExtractorInput): Boolean
    fun init(output: ExtractorOutput)
    fun read(input: ExtractorInput, seekPosition: PositionHolder): Int
    fun seek(position: Long, timeUs: Long)
    fun release()

    companion object {
        const val RESULT_CONTINUE = 0
        const val RESULT_SEEK = 1
        const val RESULT_END_OF_INPUT = -1
    }
}

interface ExtractorsFactory {
    fun createExtractors(): Array<Extractor>
    fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> = createExtractors()
    fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory = this
    fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory = this
    fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory = this
}

class DefaultExtractorsFactory : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = emptyArray()
    fun setTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): DefaultExtractorsFactory = this
    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): DefaultExtractorsFactory = this
}
