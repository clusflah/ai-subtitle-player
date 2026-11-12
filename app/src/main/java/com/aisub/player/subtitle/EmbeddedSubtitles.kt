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
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser as Media3SubtitleParser
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 读取视频文件里自带的字幕轨（MKV / MP4 的 SRT、ASS、WebVTT、TX3G 等文字字幕），转成 [Cue]，
 * 之后可以像识别出的字幕一样翻译、朗读、导出。
 * 字幕穿插在整个文件里，需要从头到尾读一遍（不解码音视频，比语音识别快得多）。
 * 同时支持直接复用播放内核已解封装的字幕样本（网盘/远程大文件无需等待全片下载即可立刻出字幕并应对单连接限制）。
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

    private val ASS_TAGS = Regex("\\{\\\\[^}]*\\}")
    private val demuxCollectors = ConcurrentHashMap<Int, TextCollector>()

    @Volatile
    private var demuxListener: ((List<Track>) -> Unit)? = null

    @Volatile
    private var lastDemuxNotifyMs = 0L

    fun resetDemuxCache() {
        demuxCollectors.clear()
        lastDemuxNotifyMs = 0L
    }

    fun setDemuxListener(listener: ((List<Track>) -> Unit)?) {
        demuxListener = listener
    }

    fun demuxedTracks(): List<Track> =
        demuxCollectors.values.map { it.toTrack() }.filter { it.cues.isNotEmpty() || it.imageBased }

    private fun notifyDemuxUpdated(force: Boolean = false) {
        val listener = demuxListener ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastDemuxNotifyMs < 400L) return
        lastDemuxNotifyMs = now
        listener(demuxedTracks())
    }

    private fun recordDemuxedCues(format: Format, cues: CuesWithTiming) {
        val id = format.id?.toIntOrNull()
            ?: demuxCollectors.entries.firstOrNull { (_, c) ->
                c.format?.language == format.language && c.format?.label == format.label
            }?.key
            ?: (format.hashCode() and 0x7fff)
        val collector = demuxCollectors.getOrPut(id) {
            TextCollector(id).also { it.format(format) }
        }
        if (collector.format == null) collector.format(format)
        val lines = cues.cues.mapNotNull { it.text?.toString() }
            .flatMap { it.split('\n') }
            .map { it.replace(ASS_TAGS, "").trim() }
            .filter { it.isNotEmpty() }
        if (collector.addSample(cues.startTimeUs, cues.durationUs, lines)) {
            notifyDemuxUpdated(force = collector.sampleCount() <= 3)
        }
    }

    fun wrapSubtitleParserFactory(delegate: Media3SubtitleParser.Factory): Media3SubtitleParser.Factory {
        val ssaFactory = DefaultSubtitleParserFactory()
        return object : Media3SubtitleParser.Factory {
            override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

            override fun getCueReplacementBehavior(format: Format): Int = delegate.getCueReplacementBehavior(format)

            override fun create(format: Format): Media3SubtitleParser {
                val primary = delegate.create(format)
                val isAss = format.sampleMimeType == MimeTypes.TEXT_SSA || format.codecs == MimeTypes.TEXT_SSA
                val ssaFallback = if (isAss && ssaFactory.supportsFormat(format)) {
                    runCatching { ssaFactory.create(format) }.getOrNull()
                } else null
                return object : Media3SubtitleParser {
                    override fun parse(
                        data: ByteArray,
                        offset: Int,
                        length: Int,
                        outputOptions: Media3SubtitleParser.OutputOptions,
                        output: androidx.media3.common.util.Consumer<CuesWithTiming>,
                    ) {
                        var emitted = false
                        primary.parse(data, offset, length, outputOptions) { c ->
                            emitted = true
                            recordDemuxedCues(format, c)
                            output.accept(c)
                        }
                        if (!emitted && ssaFallback != null) {
                            runCatching {
                                ssaFallback.parse(data, offset, length, Media3SubtitleParser.OutputOptions.allCues()) { c ->
                                    recordDemuxedCues(format, c)
                                }
                            }
                        }
                    }

                    override fun getCueReplacementBehavior(): Int = primary.cueReplacementBehavior

                    override fun reset() {
                        primary.reset()
                        ssaFallback?.reset()
                    }
                }
            }
        }
    }

    fun wrapExtractor(extractor: Extractor): Extractor {
        if (extractor is MatroskaExtractor) {
            runCatching {
                val field = MatroskaExtractor::class.java.getDeclaredField("subtitleParserFactory")
                field.isAccessible = true
                val current = field.get(extractor) as? Media3SubtitleParser.Factory
                if (current != null) {
                    field.set(extractor, wrapSubtitleParserFactory(current))
                }
            }
        }
        return object : Extractor {
            override fun getUnderlyingImplementation(): Extractor = extractor.underlyingImplementation
            override fun sniff(input: androidx.media3.extractor.ExtractorInput): Boolean = extractor.sniff(input)
            override fun init(output: ExtractorOutput) = extractor.init(TapExtractorOutput(output))
            override fun read(input: androidx.media3.extractor.ExtractorInput, seekPosition: PositionHolder): Int =
                extractor.read(input, seekPosition)
            override fun seek(position: Long, timeUs: Long) = extractor.seek(position, timeUs)
            override fun release() = extractor.release()
        }
    }

    private class TapExtractorOutput(private val downstream: ExtractorOutput) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            val out = downstream.track(id, type)
            if (type != C.TRACK_TYPE_TEXT) return out
            val collector = demuxCollectors.getOrPut(id) { TextCollector(id) }
            return TapTrackOutput(collector, out)
        }

        override fun endTracks() = downstream.endTracks()
        override fun seekMap(seekMap: SeekMap) = downstream.seekMap(seekMap)
    }

    private class TapTrackOutput(
        private val collector: TextCollector,
        private val downstream: TrackOutput,
    ) : TrackOutput {
        override fun format(format: Format) {
            collector.format(format)
            downstream.format(format)
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            collector.readAndForward(input, length, allowEndOfInput, sampleDataPart, downstream)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val pos = data.position
            collector.sampleData(data, length, sampleDataPart)
            data.position = pos
            downstream.sampleData(data, length, sampleDataPart)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val added = collector.onSampleMetadata(timeUs, size, offset)
            if (added) notifyDemuxUpdated(force = collector.sampleCount() <= 3)
            downstream.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }
    }

    /** 合并两份字幕轨列表（按轨道 ID 或语言/标签匹配，合并各自已读到的字幕句并按时间去重排序） */
    fun mergeTracks(primary: List<Track>, secondary: List<Track>): List<Track> {
        if (secondary.isEmpty()) return primary
        if (primary.isEmpty()) return secondary
        val usedSecondary = HashSet<Int>()
        val merged = primary.map { p ->
            val match = secondary.firstOrNull { s ->
                s.id == p.id || (s.id !in usedSecondary && s.language == p.language && s.label == p.label && s.codec == p.codec)
            }
            if (match == null || match.cues.isEmpty()) {
                p
            } else {
                usedSecondary += match.id
                p.copy(cues = mergeCueLists(p.cues, match.cues))
            }
        }
        val extras = secondary.filter { it.id !in usedSecondary && primary.none { p -> p.id == it.id } }
        return merged + extras
    }

    private fun mergeCueLists(a: List<Cue>, b: List<Cue>): List<Cue> {
        if (b.isEmpty()) return a
        if (a.isEmpty()) return b
        val seen = HashSet<Long>(a.size + b.size)
        val combined = ArrayList<Cue>(a.size + b.size)
        for (c in (a + b).sortedBy { it.startMs }) {
            val key = ((c.startMs / 20L) shl 20) xor c.text.hashCode().toLong()
            if (seen.add(key)) {
                combined += c
            }
        }
        return combined.mapIndexed { idx, c -> c.copy(id = idx + 1) }
    }

    /** 读一遍文件，返回所有字幕轨（没有字幕轨时为空）。阻塞调用，isActive 返回 false 时中止。 */
    fun extract(
        uri: Uri,
        dataSourceFactory: DataSource.Factory,
        isActive: () -> Boolean,
        startMs: Long = 0L,
        onPartial: ((List<Track>) -> Unit)? = null,
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
        var seekedToStart = false
        var lastPartialCount = 0
        var lastPartialMs = 0L
        try {
            var result = Extractor.RESULT_CONTINUE
            while (result != Extractor.RESULT_END_OF_INPUT) {
                val opened = dataSource.open(DataSpec.Builder().setUri(uri).setPosition(start).build())
                if (start == 0L) length = opened
                // cloud://serverId/pickcode?n=Movie.mkv 解析后保留原始文件名后缀，方便识别 MKV/MP4 容器
                val resolvedUri = uri.getQueryParameter("n")?.takeIf { it.isNotBlank() }?.let { Uri.fromFile(File(it)) }
                    ?: dataSource.uri ?: uri
                adapter.init(
                    dataSource,
                    resolvedUri,
                    dataSource.responseHeaders,
                    start,
                    if (opened == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else start + opened,
                    output,
                )
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
                    if (output.tracksEnded && output.texts.isEmpty()) {
                        return demuxedTracks()
                    }
                    // 远程大文件从当前播放位置附近开始读字幕，无需等待前面几十分钟的视频下载完
                    val sm = output.seekMap
                    if (!seekedToStart && startMs > 15_000L && output.tracksEnded && sm != null && result == Extractor.RESULT_CONTINUE) {
                        seekedToStart = true
                        if (sm.isSeekable) {
                            val targetUs = (startMs - 5_000L).coerceAtLeast(0L) * 1000L
                            val pt = sm.getSeekPoints(targetUs).first
                            if (pt.position > pos + (2L shl 20)) {
                                adapter.seek(pt.position, pt.timeUs)
                                position.position = pt.position
                                result = Extractor.RESULT_SEEK
                                break
                            }
                        }
                    }
                    if (onPartial != null) {
                        val total = output.totalSamples()
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (total > lastPartialCount && (lastPartialCount == 0 || now - lastPartialMs >= 800L)) {
                            lastPartialCount = total
                            lastPartialMs = now
                            onPartial(mergeTracks(output.texts.values.map { it.toTrack() }, demuxedTracks()))
                        }
                    }
                }
                dataSource.close()
                // 提取器要求跳到别处读（MP4 的 moov 在文件末尾等），重新打开
                if (result == Extractor.RESULT_SEEK) start = position.position
            }
        } catch (e: UnsupportedOperationException) {
            val fallback = demuxedTracks()
            if (fallback.isNotEmpty()) return fallback
            throw IOException("不支持这种视频格式", e)
        } catch (e: IOException) {
            val partial = mergeTracks(output.texts.values.map { it.toTrack() }, demuxedTracks())
            if (partial.any { it.cues.isNotEmpty() }) return partial
            throw e
        } finally {
            runCatching { dataSource.close() }
            adapter.release()
        }
        onProgress(1f)
        return mergeTracks(output.texts.values.map { it.toTrack() }, demuxedTracks())
    }

    private class Output : ExtractorOutput {
        val texts = LinkedHashMap<Int, TextCollector>()
        var tracksEnded = false
        var seekMap: SeekMap? = null

        override fun track(id: Int, type: Int): TrackOutput =
            if (type == C.TRACK_TYPE_TEXT) texts.getOrPut(id) { TextCollector(id) } else DiscardingTrackOutput()

        override fun endTracks() {
            tracksEnded = true
        }

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }

        fun totalSamples(): Int = texts.values.sumOf { it.sampleCount() }
    }

    /** 收集一条字幕轨的样本。转码后的样本是 Media3 自己的格式，用 CueDecoder 解开。 */
    private class TextCollector(val id: Int) : TrackOutput {
        @Volatile
        var format: Format? = null
        private var pending = ByteArray(0)
        private var pendingSize = 0
        private val decoder = CueDecoder()
        private val sampleKeys = HashSet<Long>()

        /** (开始, 时长, 文字)，时长未知时为 C.TIME_UNSET */
        private val samples = ArrayList<Triple<Long, Long, List<String>>>()

        @Synchronized
        fun sampleCount(): Int = samples.size

        @Synchronized
        fun addSample(startUs: Long, durationUs: Long, lines: List<String>): Boolean {
            if (lines.isEmpty()) return false
            val key = ((startUs / 1000L) shl 20) xor lines.hashCode().toLong()
            if (!sampleKeys.add(key)) return false
            samples += Triple(startUs, durationUs, lines)
            return true
        }

        override fun format(format: Format) {
            this.format = format
        }

        @Synchronized
        private fun ensure(extra: Int) {
            if (pendingSize + extra > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingSize + extra))
        }

        @Synchronized
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

        fun readAndForward(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
            downstream: TrackOutput,
        ): Int {
            val chunk: ByteArray
            val n: Int
            synchronized(this) {
                ensure(length)
                val startOffset = pendingSize
                n = input.read(pending, startOffset, length)
                if (n == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                    throw java.io.EOFException()
                }
                pendingSize += n
                chunk = pending.copyOfRange(startOffset, startOffset + n)
            }
            var consumed = 0
            downstream.sampleData(
                DataReader { target, offset, readLen ->
                    val c = minOf(readLen, n - consumed)
                    if (c <= 0) C.RESULT_END_OF_INPUT else {
                        System.arraycopy(chunk, consumed, target, offset, c)
                        consumed += c
                        c
                    }
                },
                n,
                allowEndOfInput,
                sampleDataPart,
            )
            return n
        }

        @Synchronized
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            ensure(length)
            data.readBytes(pending, pendingSize, length)
            pendingSize += length
        }

        @Synchronized
        fun onSampleMetadata(timeUs: Long, size: Int, offset: Int): Boolean {
            var added = false
            val startIndex = pendingSize - size - offset
            if (startIndex >= 0 && format?.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) {
                runCatching {
                    val cues = decoder.decode(timeUs, pending, startIndex, size)
                    val lines = cues.cues.mapNotNull { it.text?.toString() }
                        .flatMap { it.split('\n') }
                        .map { it.replace(ASS_TAGS, "").trim() }
                        .filter { it.isNotEmpty() }
                    added = addSample(cues.startTimeUs, cues.durationUs, lines)
                }
            }
            // 用掉的数据丢弃，保留 offset 之后还没成为样本的部分
            val keep = offset
            if (keep > 0 && pendingSize >= keep) System.arraycopy(pending, pendingSize - keep, pending, 0, keep)
            pendingSize = keep.coerceAtLeast(0)
            return added
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            onSampleMetadata(timeUs, size, offset)
        }

        @Synchronized
        fun toTrack(): Track {
            val f = format
            val transcoded = f?.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES || f?.sampleMimeType == MimeTypes.TEXT_SSA
            val codec = if (f?.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) f.codecs else f?.sampleMimeType
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
