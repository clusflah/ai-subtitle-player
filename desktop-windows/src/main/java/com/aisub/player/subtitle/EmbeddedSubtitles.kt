package com.aisub.player.subtitle

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.aisub.player.desktop.DesktopBinaryFinder
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.cloud.CloudRegistry
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * 读取视频文件里自带的字幕轨（MKV / MP4 的 SRT、ASS、WebVTT、TX3G 等文字字幕），转成 [Cue]，
 * 之后可以像识别出的字幕一样翻译、朗读、导出。
 * Windows 桌面版通过 FFprobe + FFmpeg 单次扫描提取所有内嵌字幕轨。
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
    )

    private val ISO3_TO_ISO2: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (lang in Locale.getISOLanguages()) {
            val loc = Locale(lang)
            runCatching { map[loc.isO3Language.lowercase()] = lang }
        }
        map["chi"] = "zh"
        map["zho"] = "zh"
        map["fre"] = "fr"
        map["ger"] = "de"
        map["jpn"] = "ja"
        map["kor"] = "ko"
        map["eng"] = "en"
        map["rus"] = "ru"
        map["spa"] = "es"
        map
    }

    private fun normalizeLang(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val lower = raw.trim().lowercase()
        if (lower == "und") return "und"
        ISO3_TO_ISO2[lower]?.let { return it }
        val tag = Locale.forLanguageTag(lower).language
        return if (tag.isNotBlank()) (ISO3_TO_ISO2[tag] ?: tag) else lower
    }

    private fun codecToMime(codecName: String?): Pair<String?, Boolean> = when (codecName?.lowercase()) {
        "subrip", "srt", "text" -> "application/x-subrip" to false
        "ass", "ssa" -> "text/x-ssa" to false
        "webvtt", "vtt" -> "text/vtt" to false
        "mov_text", "tx3g" -> "application/x-mp4-tx3g" to false
        "ttml" -> "application/ttml+xml" to false
        "hdmv_pgs_subtitle", "pgs" -> "application/pgs" to true
        "dvd_subtitle", "vobsub" -> "application/vobsub" to true
        "dvb_subtitle" -> "application/dvbsubs" to true
        null, "" -> null to false
        else -> "application/$codecName" to true
    }

    fun resetDemuxCache() {}

    fun setDemuxListener(listener: ((List<Track>) -> Unit)?) {}

    fun demuxedTracks(): List<Track> = emptyList()

    fun mergeTracks(primary: List<Track>, secondary: List<Track>): List<Track> {
        if (secondary.isEmpty()) return primary
        if (primary.isEmpty()) return secondary
        return primary
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
        val headerArgs = if (headers.isNotEmpty() && isHttp) {
            listOf("-headers", headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n")
        } else emptyList()

        val metas = if (ffprobe != null) {
            probeStreamsWithFfprobe(ffprobe, headerArgs, inputPath)
        } else {
            probeStreamsWithFfmpeg(ffmpeg, headerArgs, inputPath)
        }

        if (metas.isEmpty()) {
            onProgress(1f)
            return emptyList()
        }

        onProgress(0.2f)
        if (!isActive()) throw InterruptedException()

        val textMetas = metas.filter { !it.imageBased }
        val tmpFiles = HashMap<Int, File>()
        fun snapshotTracks(): List<Track> = metas.map { m ->
            val cues = if (m.imageBased) {
                emptyList()
            } else {
                val f = tmpFiles[m.subIndex]
                if (f != null && f.exists() && f.length() > 0) {
                    runCatching { parseSrtWithCueOf(f.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
                } else emptyList()
            }
            Track(
                id = m.streamIndex,
                language = m.language,
                label = m.label,
                codec = m.codecMime,
                imageBased = m.imageBased,
                cues = cues,
            )
        }

        try {
            if (textMetas.isNotEmpty()) {
                val cmd = ArrayList<String>()
                cmd += ffmpeg
                cmd += listOf("-nostdin", "-hide_banner", "-y")
                if (isHttp) {
                    cmd += listOf("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "2")
                }
                cmd += headerArgs
                if (isHttp && startMs > 15_000L) {
                    val seekSec = "%.3f".format(Locale.US, (startMs - 5_000L).coerceAtLeast(0L) / 1000.0)
                    cmd += listOf("-ss", seekSec, "-copyts")
                }
                cmd += listOf("-i", inputPath)
                for (m in textMetas) {
                    val tmp = File.createTempFile("voxtitle_sub_${m.subIndex}_", ".srt")
                    tmpFiles[m.subIndex] = tmp
                    cmd += listOf("-map", "0:s:${m.subIndex}", "-c:s", "srt", "-flush_packets", "1", tmp.absolutePath)
                }
                val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                // 持续排空 FFmpeg 输出管道，避免写满 4KB 系统缓冲区导致子进程死锁
                Thread {
                    runCatching { proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { } }
                }.apply { isDaemon = true; start() }

                var lastPartialMs = System.currentTimeMillis()
                var lastTotalCues = 0
                while (proc.isAlive) {
                    if (!isActive()) {
                        proc.destroyForcibly()
                        throw InterruptedException()
                    }
                    Thread.sleep(50)
                    val now = System.currentTimeMillis()
                    if (onPartial != null && now - lastPartialMs >= 800L) {
                        lastPartialMs = now
                        val current = snapshotTracks()
                        val total = current.sumOf { it.cues.size }
                        if (total > lastTotalCues) {
                            lastTotalCues = total
                            onPartial(current)
                        }
                    }
                }
            }
            onProgress(1f)
            return snapshotTracks()
        } finally {
            tmpFiles.values.forEach { runCatching { it.delete() } }
        }
    }

    private fun probeStreamsWithFfprobe(
        ffprobe: String,
        headerArgs: List<String>,
        inputPath: String,
    ): List<StreamMeta> {
        val cmd = ArrayList<String>()
        cmd += ffprobe
        cmd += listOf("-v", "quiet", "-print_format", "json", "-show_streams", "-select_streams", "s")
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
            val (mime, imageBased) = codecToMime(codecName)
            list += StreamMeta(
                streamIndex = index,
                subIndex = i,
                language = lang,
                label = title,
                codecMime = mime,
                imageBased = imageBased,
            )
        }
        return list
    }

    private fun probeStreamsWithFfmpeg(
        ffmpeg: String,
        headerArgs: List<String>,
        inputPath: String,
    ): List<StreamMeta> {
        val cmd = ArrayList<String>()
        cmd += ffmpeg
        cmd += listOf("-nostdin", "-hide_banner")
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

    private fun parseSrtWithCueOf(content: String): List<Cue> {
        val lines = content.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            val m = SRT_TIME.find(lines[i])
            if (m == null) {
                i++
                continue
            }
            val g = m.groupValues
            val start = toMs(g[1], g[2], g[3], g[4])
            val end = toMs(g[5], g[6], g[7], g[8])
            i++
            val body = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (SRT_TIME.containsMatchIn(lines[i])) break
                body.add(lines[i].replace(SRT_TAGS, "").trim())
                i++
            }
            val textLines = body.filter { it.isNotEmpty() }
            if (textLines.isEmpty()) continue
            val last = cues.lastOrNull()
            if (last != null && last.startMs == start && last.endMs == end) {
                cues[cues.size - 1] = SubtitleParser.cueOf(
                    last.id,
                    start,
                    end,
                    (listOfNotNull(last.translation, last.text) + textLines).distinct(),
                )
            } else {
                cues += SubtitleParser.cueOf(cues.size + 1, start, end, textLines)
            }
        }
        return cues.sortedBy { it.startMs }.mapIndexed { idx, c -> c.copy(id = idx + 1) }
    }
}
