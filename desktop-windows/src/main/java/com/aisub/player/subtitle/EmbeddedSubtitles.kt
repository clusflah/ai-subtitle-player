package com.aisub.player.subtitle

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.aisub.player.desktop.DesktopBinaryFinder
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.cloud.CloudRegistry
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 读取视频文件里自带的字幕轨（MKV / MP4 的 SRT、ASS、WebVTT、TX3G 等文字字幕），转成 [Cue]，
 * 之后可以像识别出的字幕一样翻译、朗读、导出。
 * Windows 桌面版支持：
 * 1. 直接复用桌面播放内核（MPV/FFmpeg）实时解封装出的字幕句（零额外连接、秒出可翻译）；
 * 2. FFprobe + FFmpeg 并发管道高速提取内嵌字幕轨（远程大文件基于视频 Cues 关键帧精准定位并流式增量吐字）。
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
                language?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage },
                codec?.substringAfterLast('/')?.removePrefix("x-")?.uppercase(),
            ).distinct().joinToString(" · ").ifEmpty { "字幕轨 $id" }
    }

    private data class StreamMeta(
        val streamIndex: Int,
        val subIndex: Int,
        val language: String?,
        val label: String?,
        val codecMime: String?,
        val imageBased: Boolean,
        val forced: Boolean = false,
    )

    private class ExternalAccumulator(
        val id: Int,
        @Volatile var language: String?,
        @Volatile var label: String?,
        @Volatile var codec: String?,
        @Volatile var imageBased: Boolean,
    ) {
        private val seenKeys = HashSet<Long>()
        private val cueList = ArrayList<Cue>()

        @Synchronized
        fun add(startMs: Long, endMs: Long, lines: List<String>): Boolean {
            if (lines.isEmpty() || startMs < 0L) return false
            val safeEnd = if (endMs > startMs) endMs else startMs + 3500L
            val normalized = lines.flatMap { it.split('\n') }.map { it.trim() }.filter { it.isNotEmpty() }
            if (normalized.isEmpty()) return false
            val key = ((startMs / 25L) shl 20) xor normalized.hashCode().toLong()
            if (!seenKeys.add(key)) return false
            val last = cueList.lastOrNull()
            if (last != null && last.startMs == startMs && last.endMs == safeEnd) {
                cueList[cueList.size - 1] = SubtitleParser.cueOf(
                    last.id,
                    startMs,
                    safeEnd,
                    (listOfNotNull(last.translation, last.text) + normalized).distinct(),
                )
            } else {
                cueList += SubtitleParser.cueOf(cueList.size + 1, startMs, safeEnd, normalized)
            }
            return true
        }

        @Synchronized
        fun toTrack(): Track {
            val sorted = cueList.sortedBy { it.startMs }.mapIndexed { idx, c -> c.copy(id = idx + 1) }
            return Track(
                id = id,
                language = language,
                label = label,
                codec = codec,
                imageBased = imageBased,
                cues = sorted,
            )
        }
    }

    private val demuxAccumulators = ConcurrentHashMap<Int, ExternalAccumulator>()

    @Volatile
    private var demuxListener: ((List<Track>) -> Unit)? = null

    @Volatile
    private var lastDemuxNotifyMs: Long = 0L

    private val ISO3_TO_ISO2: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (lang in Locale.getISOLanguages()) {
            val loc = Locale(lang)
            runCatching { map[loc.isO3Language.lowercase()] = lang }
        }
        map["chi"] = "zh"
        map["zho"] = "zh"
        map["chs"] = "zh"
        map["cht"] = "zh"
        map["fre"] = "fr"
        map["fra"] = "fr"
        map["ger"] = "de"
        map["deu"] = "de"
        map["jpn"] = "ja"
        map["kor"] = "ko"
        map["eng"] = "en"
        map["rus"] = "ru"
        map["spa"] = "es"
        map["por"] = "pt"
        map["ita"] = "it"
        map["dut"] = "nl"
        map["nld"] = "nl"
        map["ara"] = "ar"
        map["hin"] = "hi"
        map["tha"] = "th"
        map["vie"] = "vi"
        map["ind"] = "id"
        map["may"] = "ms"
        map["msa"] = "ms"
        map["pol"] = "pl"
        map["tur"] = "tr"
        map["ukr"] = "uk"
        map
    }

    internal fun normalizeLang(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val lower = raw.trim().lowercase()
        if (lower == "und") return "und"
        ISO3_TO_ISO2[lower]?.let { return it }
        val tag = Locale.forLanguageTag(lower).language
        return if (tag.isNotBlank()) (ISO3_TO_ISO2[tag] ?: tag) else lower
    }

    /** 从 VLC 轨道描述（如 "Canadian - [英语]" / "Track 1 - [French]"）尽可能解析出 ISO-639-1 语言代码 */
    internal fun parseLanguageFromLabel(desc: String?): String? {
        if (desc.isNullOrBlank()) return null
        val d = desc.lowercase()
        return when {
            "english" in d || "英语" in d || "英文" in d || "[en]" in d -> "en"
            "chinese" in d || "mandarin" in d || "cantonese" in d || "中文" in d || "汉语" in d || "国语" in d || "粤语" in d || "简" in d || "繁" in d || "[zh]" in d -> "zh"
            "japanese" in d || "日本語" in d || "日语" in d || "日文" in d || "[ja]" in d -> "ja"
            "korean" in d || "한국어" in d || "韩语" in d || "韩文" in d || "[ko]" in d -> "ko"
            "french" in d || "français" in d || "法语" in d || "法文" in d || "[fr]" in d -> "fr"
            "german" in d || "deutsch" in d || "德语" in d || "德文" in d || "[de]" in d -> "de"
            "spanish" in d || "español" in d || "西班牙" in d || "[es]" in d -> "es"
            "portuguese" in d || "português" in d || "葡萄牙" in d || "[pt]" in d -> "pt"
            "russian" in d || "русский" in d || "俄语" in d || "[ru]" in d -> "ru"
            "italian" in d || "italiano" in d || "意大利" in d || "[it]" in d -> "it"
            "arabic" in d || "阿拉伯" in d || "[ar]" in d -> "ar"
            "thai" in d || "泰语" in d || "[th]" in d -> "th"
            "vietnamese" in d || "越南" in d || "[vi]" in d -> "vi"
            else -> null
        }
    }

    internal fun codecToMime(codecName: String?): Pair<String?, Boolean> = when (codecName?.lowercase()) {
        "subrip", "srt", "text", "utf8", "subviewer", "microdvd" -> "application/x-subrip" to false
        "ass", "ssa" -> "text/x-ssa" to false
        "webvtt", "vtt" -> "text/vtt" to false
        "mov_text", "tx3g" -> "application/x-mp4-tx3g" to false
        "ttml", "dfxp" -> "application/ttml+xml" to false
        "hdmv_pgs_subtitle", "pgs" -> "application/pgs" to true
        "dvd_subtitle", "vobsub" -> "application/vobsub" to true
        "dvb_subtitle", "dvbsub" -> "application/dvbsubs" to true
        null, "" -> null to false
        else -> "application/$codecName" to true
    }

    fun resetDemuxCache() {
        demuxAccumulators.clear()
        lastDemuxNotifyMs = 0L
    }

    fun setDemuxListener(listener: ((List<Track>) -> Unit)?) {
        demuxListener = listener
    }

    fun demuxedTracks(): List<Track> =
        demuxAccumulators.values.map { it.toTrack() }.filter { it.cues.isNotEmpty() || it.imageBased }

    internal fun recordExternalCue(
        trackId: Int,
        language: String?,
        label: String?,
        codec: String?,
        startMs: Long,
        endMs: Long,
        lines: List<String>,
        notify: Boolean = true,
    ) {
        if (lines.isEmpty() || startMs < 0L) return
        val acc = demuxAccumulators.getOrPut(trackId) {
            ExternalAccumulator(trackId, language, label, codec, imageBased = false)
        }
        if (acc.language == null && language != null) acc.language = language
        if (acc.label == null && label != null) acc.label = label
        if (acc.codec == null && codec != null) acc.codec = codec
        if (acc.add(startMs, endMs, lines) && notify) {
            val l = demuxListener ?: return
            lastDemuxNotifyMs = System.currentTimeMillis()
            l(demuxedTracks())
        }
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

    fun extract(
        uri: Uri,
        dataSourceFactory: DataSource.Factory,
        isActive: () -> Boolean,
        startMs: Long = 0L,
        onPartial: ((List<Track>) -> Unit)? = null,
        onProgress: (Float) -> Unit,
    ): List<Track> {
        if (!isActive()) throw InterruptedException()
        val ffmpeg = DesktopBinaryFinder.ensureFfmpeg()
            ?: throw IOException("未找到 FFmpeg 组件")
        val ffprobe = DesktopBinaryFinder.findFfprobe()
            ?: File(File(ffmpeg).parentFile, "ffprobe.exe").takeIf { it.isFile }?.absolutePath

        val rawUri = uri.toString()
        val inputPath: String
        val headers: Map<String, String>
        if (CloudRegistry.isCloud(rawUri)) {
            val link = CloudRegistry.resolve(rawUri)
            inputPath = link.url
            headers = link.headers
        } else if (rawUri.startsWith("http://") || rawUri.startsWith("https://")) {
            val routed = Endpoints.rewrite(rawUri)
            inputPath = routed
            headers = AuthRegistry.headersFor(routed)
        } else {
            val f = uri.toJavaFile() ?: File(uri.path ?: rawUri)
            inputPath = f.absolutePath
            headers = emptyMap()
        }

        val isHttp = inputPath.startsWith("http://") || inputPath.startsWith("https://")
        val isLocalhost = inputPath.startsWith("http://127.0.0.1:") || inputPath.startsWith("http://localhost:")
        val headerArgs = if (headers.isNotEmpty() && isHttp) {
            listOf("-headers", headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n")
        } else emptyList()

        val metas = if (ffprobe != null) {
            probeStreamsWithFfprobe(ffprobe, headerArgs, inputPath, isLocalhost)
        } else {
            probeStreamsWithFfmpeg(ffmpeg, headerArgs, inputPath, isLocalhost)
        }

        if (metas.isEmpty()) {
            onProgress(1f)
            return if (CloudRegistry.isCloud(rawUri)) demuxedTracks() else emptyList()
        }

        onProgress(0.2f)
        if (!isActive()) throw InterruptedException()

        val textMetas = metas.filter { !it.imageBased }
        val cueLists = ConcurrentHashMap<Int, ArrayList<Cue>>()
        textMetas.forEach { cueLists[it.subIndex] = ArrayList() }

        fun snapshotTracks(): List<Track> = metas.map { m ->
            val rawCues = if (m.imageBased) emptyList() else cueLists[m.subIndex]?.let { list ->
                synchronized(list) { list.sortedBy { it.startMs }.mapIndexed { idx, c -> c.copy(id = idx + 1) } }
            }.orEmpty()
            Track(
                id = m.streamIndex,
                language = m.language,
                label = m.label,
                codec = m.codecMime,
                imageBased = m.imageBased,
                cues = rawCues,
            )
        }

        if (textMetas.isEmpty()) {
            onProgress(1f)
            return mergeTracks(snapshotTracks(), demuxedTracks())
        }

        if (!isHttp) {
            val tempDir = java.nio.file.Files.createTempDirectory("voxtitle-emb-sub").toFile()
            try {
                val outFiles = HashMap<Int, File>(textMetas.size)
                val cmd = ArrayList<String>()
                cmd += ffmpeg
                cmd += listOf("-nostdin", "-hide_banner", "-v", "error", "-y", "-copyts", "-i", inputPath)
                for (m in textMetas) {
                    val f = File(tempDir, "sub_${m.subIndex}.srt")
                    outFiles[m.subIndex] = f
                    cmd += listOf("-map", "0:s:${m.subIndex}", "-c:s", "srt", "-f", "srt", f.absolutePath)
                }
                val proc = ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                while (proc.isAlive) {
                    if (!isActive()) {
                        proc.destroyForcibly()
                        runCatching { proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
                        throw InterruptedException()
                    }
                    Thread.sleep(25)
                }
                val localTracks = metas.map { m ->
                    val parsedCues = if (m.imageBased) {
                        emptyList()
                    } else {
                        val f = outFiles[m.subIndex]
                        if (f != null && f.isFile && f.length() > 0L) {
                            runCatching { SubtitleParser.parse(f.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
                        } else {
                            emptyList()
                        }
                    }
                    Track(
                        id = m.streamIndex,
                        language = m.language,
                        label = m.label,
                        codec = m.codecMime,
                        imageBased = m.imageBased,
                        cues = parsedCues,
                    )
                }
                if (onPartial != null && localTracks.any { it.cues.isNotEmpty() }) {
                    onPartial(localTracks)
                }
                onProgress(1f)
                return mergeTracks(localTracks, demuxedTracks())
            } finally {
                runCatching { tempDir.deleteRecursively() }
            }
        }

        // 远程大文件提取使用回环 TCP 即时接收 SRT 行，完全绕开文件 32KB 缓冲延迟；
        // 并对远程流按 [seekMs, seekMs + 5 分钟] 分窗提取，利用 -map 0:v:0? 触发 MKV Cues 索引，秒出首句且不挤占播放带宽。
        val servers = ArrayList<ServerSocket>(textMetas.size)
        val readerThreads = ArrayList<Thread>(textMetas.size)
        val seekBaseMs = if (isHttp && startMs > 15_000L) (startMs - 5_000L).coerceAtLeast(0L) else 0L
        val remoteWindowDurationUs = REMOTE_WINDOW_MS * 1000L

        try {
            val cmd = ArrayList<String>()
            cmd += ffmpeg
            cmd += listOf("-nostdin", "-hide_banner", "-v", "warning")
            if (isLocalhost) {
                cmd += listOf("-http_proxy", "")
            }
            if (isHttp) {
                cmd += listOf(
                    "-reconnect", "1",
                    "-reconnect_streamed", "1",
                    "-reconnect_delay_max", "2",
                    "-probesize", "1500000",
                    "-analyzeduration", "1500000",
                )
            }
            cmd += headerArgs
            cmd += "-copyts"
            if (seekBaseMs > 0L) {
                val seekSec = "%.3f".format(Locale.US, seekBaseMs / 1000.0)
                cmd += listOf("-ss", seekSec)
            }
            cmd += listOf("-i", inputPath)

            if (isHttp) {
                // 保持一条轻量的 video stream copy 到 null，防止 libavformat/matroskadec 因 video AVDISCARD_ALL 丢失 Cues 索引，并用 progress 汇报当前解复用时间
                cmd += listOf("-map", "0:v:0?", "-c:v", "copy", "-f", "null", "-", "-progress", "pipe:1")
            }

            for (m in textMetas) {
                val srv = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                servers += srv
                val targetList = cueLists[m.subIndex]!!
                val seen = HashSet<Long>()
                val t = Thread({
                    runCatching {
                        srv.soTimeout = 15_000
                        srv.accept().use { sock ->
                            BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8)).use { reader ->
                                val blockLines = ArrayList<String>(4)
                                fun flushBlock() {
                                    if (blockLines.isEmpty()) return
                                    val parsed = parseSrtBlock(blockLines)
                                    blockLines.clear()
                                    if (parsed != null) {
                                        val key = ((parsed.startMs / 25L) shl 20) xor parsed.text.hashCode().toLong()
                                        synchronized(targetList) {
                                            if (seen.add(key)) {
                                                val last = targetList.lastOrNull()
                                                if (last != null && last.startMs == parsed.startMs && last.endMs == parsed.endMs) {
                                                    val combined = (listOfNotNull(last.translation, last.text) +
                                                        listOfNotNull(parsed.translation, parsed.text)).distinct()
                                                    targetList[targetList.size - 1] = SubtitleParser.cueOf(last.id, last.startMs, last.endMs, combined)
                                                } else {
                                                    targetList += parsed
                                                }
                                            }
                                        }
                                    }
                                }
                                try {
                                    while (true) {
                                        val line = reader.readLine() ?: break
                                        if (line.isBlank()) {
                                            flushBlock()
                                        } else {
                                            blockLines += line
                                        }
                                    }
                                } catch (_: Throwable) {
                                } finally {
                                    flushBlock()
                                }
                            }
                        }
                    }
                }, "Voxtitle-SubExtract-${m.subIndex}").apply {
                    isDaemon = true
                    start()
                }
                readerThreads += t
                cmd += listOf(
                    "-map", "0:s:${m.subIndex}",
                    "-c:s", "srt",
                    "-flush_packets", "1",
                    "-f", "srt",
                    "tcp://127.0.0.1:${srv.localPort}?tcp_nodelay=1",
                )
            }

            val proc = ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()

            val firstOutTimeUs = java.util.concurrent.atomic.AtomicLong(-1L)
            val elapsedOutTimeUs = java.util.concurrent.atomic.AtomicLong(0L)
            val windowReached = java.util.concurrent.atomic.AtomicBoolean(false)
            val progressThread = Thread({
                runCatching {
                    proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        if (line.startsWith("out_time_us=")) {
                            val us = line.removePrefix("out_time_us=").trim().toLongOrNull()
                            if (us != null && us >= 0L) {
                                firstOutTimeUs.compareAndSet(-1L, us)
                                val baseUs = firstOutTimeUs.get().coerceAtLeast(0L)
                                val elapsedUs = (us - baseUs).coerceAtLeast(0L)
                                elapsedOutTimeUs.set(elapsedUs)
                                if (isHttp && elapsedUs >= remoteWindowDurationUs) {
                                    windowReached.set(true)
                                }
                            }
                        } else if (line == "progress=end") {
                            windowReached.set(true)
                        }
                    }
                }
            }, "Voxtitle-SubProgress").apply {
                isDaemon = true
                start()
            }

            var lastPartialMs = System.currentTimeMillis()
            var lastTotalCues = 0
            while (proc.isAlive) {
                if (!isActive()) {
                    proc.destroyForcibly()
                    runCatching { proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) }
                    throw InterruptedException()
                }
                if (windowReached.get()) {
                    Thread.sleep(150)
                    proc.destroyForcibly()
                    runCatching { proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) }
                    break
                }
                Thread.sleep(50)
                if (isHttp) {
                    val spanUs = remoteWindowDurationUs.toFloat()
                    val relUs = elapsedOutTimeUs.get().toFloat()
                    onProgress((0.2f + 0.78f * (relUs / spanUs)).coerceIn(0.2f, 0.98f))
                }
                val now = System.currentTimeMillis()
                val partialIntervalMs = if (lastTotalCues == 0) 150L else 650L
                if (onPartial != null && now - lastPartialMs >= partialIntervalMs) {
                    lastPartialMs = now
                    val current = snapshotTracks()
                    val total = current.sumOf { it.cues.size }
                    if (total > lastTotalCues) {
                        lastTotalCues = total
                        onPartial(current)
                    }
                }
            }
            progressThread.join(300)
            readerThreads.forEach { runCatching { it.join(600) } }
            onProgress(1f)
            return mergeTracks(snapshotTracks(), demuxedTracks())
        } finally {
            servers.forEach { runCatching { it.close() } }
        }
    }

    private fun probeStreamsWithFfprobe(
        ffprobe: String,
        headerArgs: List<String>,
        inputPath: String,
        isLocalhost: Boolean,
    ): List<StreamMeta> {
        val cmd = ArrayList<String>()
        cmd += ffprobe
        cmd += listOf("-v", "quiet", "-print_format", "json", "-show_entries", "stream=index,codec_name:stream_tags=language,title:stream_disposition=forced", "-select_streams", "s")
        if (isLocalhost) cmd += listOf("-http_proxy", "")
        cmd += headerArgs
        cmd += inputPath
        val proc = ProcessBuilder(cmd).start()
        val jsonText = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
        proc.waitFor()
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("streams") ?: return emptyList()
        val list = ArrayList<StreamMeta>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val index = s.optInt("index", i)
            val codecName = s.optString("codec_name", "")
            val tags = s.optJSONObject("tags")
            val lang = normalizeLang(tags?.optString("language")?.takeIf { it.isNotBlank() })
            val title = tags?.optString("title")?.takeIf { it.isNotBlank() }
            val forced = s.optJSONObject("disposition")?.optInt("forced", 0) == 1
            val (mime, imageBased) = codecToMime(codecName)
            list += StreamMeta(
                streamIndex = index,
                subIndex = i,
                language = lang,
                label = title,
                codecMime = mime,
                imageBased = imageBased,
                forced = forced,
            )
        }
        return list
    }

    private fun probeStreamsWithFfmpeg(
        ffmpeg: String,
        headerArgs: List<String>,
        inputPath: String,
        isLocalhost: Boolean,
    ): List<StreamMeta> {
        val cmd = ArrayList<String>()
        cmd += ffmpeg
        cmd += listOf("-nostdin", "-hide_banner")
        if (isLocalhost) cmd += listOf("-http_proxy", "")
        cmd += headerArgs
        cmd += listOf("-i", inputPath)
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
        proc.waitFor()
        val regex = Regex("""Stream #0:(\d+)(?:\(([a-zA-Z0-9_-]+)\))?: Subtitle:\s*([a-zA-Z0-9_]+)""")
        val list = ArrayList<StreamMeta>()
        var subIdx = 0
        for (m in regex.findAll(out)) {
            val streamIdx = m.groupValues[1].toIntOrNull() ?: subIdx
            val lang = normalizeLang(m.groupValues[2].takeIf { it.isNotBlank() })
            val codecName = m.groupValues[3]
            val (mime, imageBased) = codecToMime(codecName)
            list += StreamMeta(
                streamIndex = streamIdx,
                subIndex = subIdx++,
                language = lang,
                label = null,
                codecMime = mime,
                imageBased = imageBased,
            )
        }
        return list
    }

    private val SRT_TIME = Regex("""(\d{1,2}:)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(\d{1,2}:)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})""")
    private val SRT_TAGS = Regex("<[^>]+>|\\{\\\\[^}]*\\}")

    private fun toMs(h: String, m: String, s: String, frac: String): Long {
        val hours = h.removeSuffix(":").toLongOrNull() ?: 0
        val millis = frac.padEnd(3, '0').take(3).toLong()
        return ((hours * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + millis
    }

    internal fun parseSrtBlock(lines: List<String>): Cue? {
        var timeIdx = -1
        var match: MatchResult? = null
        for (i in lines.indices) {
            val m = SRT_TIME.find(lines[i])
            if (m != null) {
                timeIdx = i
                match = m
                break
            }
        }
        if (timeIdx < 0 || match == null) return null
        val g = match.groupValues
        val start = toMs(g[1], g[2], g[3], g[4])
        val end = toMs(g[5], g[6], g[7], g[8])
        val textLines = ArrayList<String>(lines.size - timeIdx)
        for (j in (timeIdx + 1) until lines.size) {
            val cleaned = lines[j].replace(SRT_TAGS, "").trim()
            if (cleaned.isNotEmpty()) textLines += cleaned
        }
        if (textLines.isEmpty()) return null
        return SubtitleParser.cueOf(1, start, end, textLines)
    }

    private const val REMOTE_WINDOW_MS = 5 * 60_000L
}
