package com.aisub.player.subtitle

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.BundledExtractorsAdapter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import java.io.IOException

/**
 * 读取视频文件里自带的字幕轨（MKV / MP4 的 SRT、ASS、WebVTT、TX3G 等文字字幕），转成 [Cue]，
 * 之后可以像识别出的字幕一样翻译、朗读、导出。
 * 字幕穿插在整个文件里，需要从头到尾读一遍（不解码音视频，比语音识别快得多）。
 * PGS、VobSub 这类图形字幕没有文字，只能列出来、不能提取。
 */
@UnstableApi
object EmbeddedSubtitles {

    data class Track(
        val id: Int,
        val language: String?,
        val label: String?,
        /** 原始格式，如 application/x-subrip、text/x-ssa、application/pgs */
        val codec: String?,
        /** 图形字幕：没有文字，提取不了 */
        val imageBased: Boolean,
        val cues: List<Cue>,
    ) {
        val title: String
            get() = listOfNotNull(
                label?.takeIf { it.isNotBlank() },
                language?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage },
                codec?.substringAfterLast('/')?.removePrefix("x-")?.uppercase(),
            ).distinct().joinToString(" · ").ifEmpty { "字幕轨 $id" }
    }

    /** 读一遍文件，返回所有字幕轨（没有字幕轨时为空）。阻塞调用，isActive 返回 false 时中止。 */
    fun extract(
        uri: Uri,
        dataSourceFactory: DataSource.Factory,
        isActive: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): List<Track> {
        val extractors = DefaultExtractorsFactory()
            .setTextTrackTranscodingEnabled(true)
            .setSubtitleParserFactory(DefaultSubtitleParserFactory())
        val adapter = BundledExtractorsAdapter(extractors)
        val output = Output()
        val dataSource = dataSourceFactory.createDataSource()
        val position = PositionHolder()
        var start = 0L
        var length = C.LENGTH_UNSET.toLong()
        try {
            var result = Extractor.RESULT_CONTINUE
            while (result != Extractor.RESULT_END_OF_INPUT) {
                val opened = dataSource.open(DataSpec.Builder().setUri(uri).setPosition(start).build())
                if (start == 0L) length = opened
                adapter.init(dataSource, uri, dataSource.responseHeaders, start, if (opened == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else start + opened, output)
                result = Extractor.RESULT_CONTINUE
                var lastReport = 0L
                while (result == Extractor.RESULT_CONTINUE) {
                    if (!isActive()) throw InterruptedException()
                    result = adapter.read(position)
                    val pos = adapter.currentInputPosition
                    if (length > 0 && pos - lastReport > 1 shl 20) {
                        lastReport = pos
                        onProgress((pos.toFloat() / length).coerceIn(0f, 1f))
                    }
                    // 音视频都不要，只有字幕轨时读完文件头就能确认没有字幕
                    if (output.tracksEnded && output.texts.isEmpty()) return emptyList()
                }
                dataSource.close()
                // 提取器要求跳到别处读（MP4 的 moov 在文件末尾等），重新打开
                if (result == Extractor.RESULT_SEEK) start = position.position
            }
        } catch (e: UnsupportedOperationException) {
            throw IOException("不支持这种视频格式", e)
        } finally {
            runCatching { dataSource.close() }
            adapter.release()
        }
        onProgress(1f)
        return output.texts.values.map { it.toTrack() }
    }

    private class Output : ExtractorOutput {
        val texts = LinkedHashMap<Int, TextCollector>()
        var tracksEnded = false

        override fun track(id: Int, type: Int): TrackOutput =
            if (type == C.TRACK_TYPE_TEXT) texts.getOrPut(id) { TextCollector(id) } else DiscardingTrackOutput()

        override fun endTracks() {
            tracksEnded = true
        }

        override fun seekMap(seekMap: SeekMap) {}
    }

    /** 收集一条字幕轨的样本。转码后的样本是 Media3 自己的格式，用 CueDecoder 解开。 */
    private class TextCollector(val id: Int) : TrackOutput {
        var format: Format? = null
        private var pending = ByteArray(0)
        private var pendingSize = 0
        private val decoder = CueDecoder()

        /** (开始, 时长, 文字)，时长未知时为 C.TIME_UNSET */
        val samples = ArrayList<Triple<Long, Long, List<String>>>()

        override fun format(format: Format) {
            this.format = format
        }

        private fun ensure(extra: Int) {
            if (pendingSize + extra > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + extra))
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            ensure(length)
            val n = input.read(pending, pendingSize, length)
            if (n == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw java.io.EOFException()
            }
            pendingSize += n
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            ensure(length)
            data.readBytes(pending, pendingSize, length)
            pendingSize += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val startIndex = pendingSize - size - offset
            if (startIndex >= 0 && format?.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) {
                runCatching {
                    val cues = decoder.decode(timeUs, pending, startIndex, size)
                    val lines = cues.cues.mapNotNull { it.text?.toString() }
                        .flatMap { it.split('\n') }.map { it.trim() }.filter { it.isNotEmpty() }
                    samples += Triple(cues.startTimeUs, cues.durationUs, lines)
                }
            }
            // 用掉的数据丢弃，保留 offset 之后还没成为样本的部分
            val keep = offset
            if (keep > 0) System.arraycopy(pending, pendingSize - keep, pending, 0, keep)
            pendingSize = keep
        }

        fun toTrack(): Track {
            val f = format
            val transcoded = f?.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES
            val codec = if (transcoded) f?.codecs else f?.sampleMimeType
            val sorted = samples.sortedBy { it.first }
            val cues = ArrayList<Cue>()
            for ((i, s) in sorted.withIndex()) {
                val (startUs, durationUs, lines) = s
                if (lines.isEmpty()) continue
                val startMs = startUs / 1000
                // 没有时长（TX3G 等）时显示到下一条开始，最长 6 秒
                val nextStart = sorted.getOrNull(i + 1)?.first?.div(1000)
                val endMs = when {
                    durationUs != C.TIME_UNSET && durationUs > 0 -> startMs + durationUs / 1000
                    nextStart != null -> minOf(nextStart, startMs + 6000)
                    else -> startMs + 3000
                }
                // 同一时间段被拆成多个样本（ASS 多个对话框）时合并
                val last = cues.lastOrNull()
                if (last != null && last.startMs == startMs && last.endMs == endMs) {
                    cues[cues.size - 1] = SubtitleParser.cueOf(last.id, startMs, endMs, (listOfNotNull(last.translation, last.text) + lines).distinct())
                } else {
                    cues += SubtitleParser.cueOf(cues.size + 1, startMs, endMs, lines)
                }
            }
            return Track(
                id = id,
                language = f?.language,
                label = f?.labels?.firstOrNull()?.value ?: f?.label,
                codec = codec,
                imageBased = !transcoded && f != null,
                cues = cues,
            )
        }
    }
}
