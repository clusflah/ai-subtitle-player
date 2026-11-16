package com.aisub.player.live

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.aisub.player.download.MovieSaver
import com.aisub.player.net.AppProxy
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.subtitle.Cue
import com.aisub.player.subtitle.SrtMode
import com.aisub.player.subtitle.writeSrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class LiveRecordState(
    val recording: Boolean = false,
    val saving: Boolean = false,
    val channelTitle: String = "",
    val streamUrl: String = "",
    val startedAtMs: Long = 0L,
    val elapsedMs: Long = 0L,
    val bytesWritten: Long = 0L,
    val lastSavedMessage: String? = null,
    val error: String? = null,
)

/**
 * Android 直播录屏 / 直播流无损录制器（兼容 Android 6.0 API 23 至 Android 15，支持 32 位老电视）：
 * 1. 支持 HLS (`.m3u8`) 多码率主列表自动优选最高画质、实时轮询分片、`#EXT-X-MAP` 初始化段及 `#EXT-X-KEY` AES-128 解密拼接；
 * 2. 支持 HTTP-TS / HTTP-FLV / MPEG-TS 直连流持续拉取落盘；
 * 3. 支持非 HTTP 协议（如 RTSP / RTMP）在 MPV 内核下调用 `stream-record` 原生直录；
 * 4. 停止录制后自动写入系统相册/视频库 `Movies/Voxtitle`（立即可在「本机 → 视频」看到），若录制期间开启了实时 AI 字幕则同步导出同名 `.srt` 字幕。
 */
object LiveRecorder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = AppProxy.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _state = MutableStateFlow(LiveRecordState())
    val state: StateFlow<LiveRecordState> = _state.asStateFlow()

    private var recordJob: Job? = null
    private var tickerJob: Job? = null
    private val stopRequested = AtomicBoolean(false)
    private val writtenCounter = AtomicLong(0L)
    private var activeTempFile: File? = null
    private var mpvStopHook: (() -> Unit)? = null

    fun clearMessage() {
        _state.update { it.copy(lastSavedMessage = null, error = null) }
    }

    /**
     * 开始录制当前直播流。
     * @param mpvStreamRecordStarter 若当前处于 MPV 内核且流协议非 HTTP（如 rtsp/rtmp），可传入原生 `stream-record` 钩子。
     */
    @Synchronized
    fun start(
        context: Context,
        streamUrl: String,
        channelTitle: String,
        mpvStreamRecordStarter: ((File) -> (() -> Unit)?)? = null,
    ) {
        if (_state.value.recording || _state.value.saving) return
        val app = context.applicationContext
        val dir = File(app.cacheDir, "live_recordings").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeTitle = MovieSaver.safeName(channelTitle.ifBlank { "直播" }).take(48)
        val ext = guessOutputExtension(streamUrl)
        val tempFile = File(dir, "直播录屏_${safeTitle}_$stamp.$ext")

        stopRequested.set(false)
        writtenCounter.set(0L)
        activeTempFile = tempFile
        mpvStopHook = null
        val startMs = System.currentTimeMillis()

        _state.value = LiveRecordState(
            recording = true,
            saving = false,
            channelTitle = channelTitle.ifBlank { "直播频道" },
            streamUrl = streamUrl,
            startedAtMs = startMs,
            elapsedMs = 0L,
            bytesWritten = 0L,
        )

        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && _state.value.recording) {
                val fileLen = activeTempFile?.takeIf { it.exists() }?.length() ?: 0L
                val bytes = maxOf(writtenCounter.get(), fileLen)
                _state.update {
                    it.copy(
                        elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L),
                        bytesWritten = bytes,
                    )
                }
                delay(500L)
            }
        }

        recordJob?.cancel()
        recordJob = scope.launch {
            try {
                val scheme = runCatching { Uri.parse(streamUrl).scheme?.lowercase().orEmpty() }.getOrDefault("")
                if (scheme != "http" && scheme != "https") {
                    val hook = mpvStreamRecordStarter?.invoke(tempFile)
                        ?: throw IOException("该直播协议 ($scheme) 请切换至 MPV 内核后再开启录屏")
                    mpvStopHook = hook
                    while (isActive && !stopRequested.get()) {
                        delay(300L)
                    }
                } else {
                    recordHttpOrHls(streamUrl, tempFile)
                }
            } catch (ce: CancellationException) {
                // 正常停止录制
            } catch (e: Exception) {
                if (!stopRequested.get()) {
                    _state.update {
                        it.copy(recording = false, error = "直播录屏中断：${e.message ?: e.javaClass.simpleName}")
                    }
                }
            } finally {
                runCatching { mpvStopHook?.invoke() }
                mpvStopHook = null
            }
        }
    }

    /**
     * 停止录制并将已录制的音视频（及可选实时 AI 字幕）保存到系统 Movies/Voxtitle 目录。
     */
    fun stop(
        context: Context,
        cuesSnapshot: List<Cue> = emptyList(),
        onSaved: ((Uri?) -> Unit)? = null,
    ) {
        if (!_state.value.recording) return
        val app = context.applicationContext
        val snapshot = _state.value
        val tempFile = activeTempFile
        stopRequested.set(true)
        tickerJob?.cancel()
        _state.update { it.copy(recording = false, saving = true) }

        scope.launch {
            runCatching { mpvStopHook?.invoke() }
            mpvStopHook = null
            runCatching { recordJob?.cancel() }
            delay(250L)

            if (tempFile == null || !tempFile.exists() || tempFile.length() < 4096L) {
                tempFile?.delete()
                _state.update {
                    it.copy(
                        recording = false,
                        saving = false,
                        error = "录制时间过短或未接收到有效直播流数据",
                    )
                }
                onSaved?.invoke(null)
                return@launch
            }

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(snapshot.startedAtMs))
            val baseTitle = "直播录屏_${MovieSaver.safeName(snapshot.channelTitle).take(48)}_$stamp"
            runCatching {
                val savedUri = saveRecordedVideo(app, tempFile, baseTitle)
                if (cuesSnapshot.isNotEmpty()) {
                    runCatching { saveSidecarLiveSrt(app, baseTitle, cuesSnapshot) }
                }
                savedUri
            }.onSuccess { uri ->
                _state.update {
                    it.copy(
                        recording = false,
                        saving = false,
                        lastSavedMessage = "直播录屏已保存至 Movies/${MovieSaver.FOLDER}/$baseTitle",
                        error = null,
                    )
                }
                onSaved?.invoke(uri)
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        recording = false,
                        saving = false,
                        error = "保存录屏失败：${e.message ?: e.javaClass.simpleName}",
                    )
                }
                onSaved?.invoke(null)
            }
        }
    }

    private fun saveRecordedVideo(context: Context, tempFile: File, baseTitle: String): Uri {
        val ext = tempFile.extension.lowercase().ifEmpty { "ts" }
        val fileName = "$baseTitle.$ext"
        val mime = when (ext) {
            "mp4" -> "video/mp4"
            "flv" -> "video/x-flv"
            "mkv" -> "video/x-matroska"
            else -> "video/mp2t"
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/${MovieSaver.FOLDER}")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法创建系统媒体库文件")
            try {
                resolver.openOutputStream(uri)!!.use { out ->
                    tempFile.inputStream().use { ins -> ins.copyTo(out, 256 * 1024) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            tempFile.delete()
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                MovieSaver.FOLDER,
            ).apply { mkdirs() }
            val dst = File(dir, fileName)
            tempFile.inputStream().use { ins ->
                FileOutputStream(dst).use { out -> ins.copyTo(out, 256 * 1024) }
            }
            tempFile.delete()
            runCatching {
                MediaScannerConnection.scanFile(context, arrayOf(dst.absolutePath), arrayOf(mime), null)
            }
            Uri.fromFile(dst)
        }
    }

    private fun saveSidecarLiveSrt(context: Context, baseTitle: String, cues: List<Cue>) {
        if (cues.isEmpty()) return
        val baseOffset = cues.first().startMs
        val shifted = cues.mapIndexed { idx, c ->
            c.copy(
                id = idx + 1,
                startMs = (c.startMs - baseOffset).coerceAtLeast(0L),
                endMs = (c.endMs - baseOffset).coerceAtLeast(200L),
            )
        }
        val srtName = "$baseTitle.srt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, srtName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/${MovieSaver.FOLDER}")
            }
            val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { out ->
                writeSrt(shifted, SrtMode.BILINGUAL, out)
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                MovieSaver.FOLDER,
            ).apply { mkdirs() }
            FileOutputStream(File(dir, srtName)).use { out ->
                writeSrt(shifted, SrtMode.BILINGUAL, out)
            }
        }
    }

    private fun requestHeadersFor(url: String): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        merged["User-Agent"] = "Mozilla/5.0 (Linux; Android 13) Voxtitle/2.9"
        merged.putAll(AuthRegistry.headersFor(url))
        merged.putAll(LiveHeaders.headersFor(url))
        return merged
    }

    private fun buildRequest(url: String): Request {
        val b = Request.Builder().url(url)
        requestHeadersFor(url).forEach { (k, v) -> b.header(k, v) }
        return b.build()
    }

    private suspend fun recordHttpOrHls(initialUrl: String, outFile: File) {
        val probeReq = buildRequest(initialUrl)
        http.newCall(probeReq).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("直播源返回 HTTP ${resp.code}")
            val finalUrl = resp.request.url.toString()
            val ctype = resp.header("Content-Type")?.lowercase().orEmpty()
            val looksM3u8 = finalUrl.substringBefore('?').lowercase().endsWith(".m3u8") ||
                ctype.contains("mpegurl") || ctype.contains("vnd.apple.mpegurl")

            if (looksM3u8) {
                val text = resp.body?.string().orEmpty()
                if (text.trimStart().startsWith("#EXTM3U")) {
                    recordHlsLoop(finalUrl, text, outFile)
                    return
                }
            }

            // 连续字节流（HTTP-TS / HTTP-FLV 等）直接从当前响应体开始落盘
            val body = resp.body ?: throw IOException("直播流无数据")
            body.byteStream().use { ins ->
                FileOutputStream(outFile, false).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (!stopRequested.get()) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        // 可能首包其实是未带扩展名的 M3U8 文本
                        if (writtenCounter.get() == 0L && n < 16384) {
                            val head = String(buf, 0, minOf(n, 64), Charsets.UTF_8).trimStart()
                            if (head.startsWith("#EXTM3U")) {
                                val rest = ins.readBytes()
                                val fullPlaylist = String(buf, 0, n, Charsets.UTF_8) + String(rest, Charsets.UTF_8)
                                fos.close()
                                recordHlsLoop(finalUrl, fullPlaylist, outFile)
                                return
                            }
                        }
                        fos.write(buf, 0, n)
                        writtenCounter.addAndGet(n.toLong())
                    }
                    fos.flush()
                }
            }
        }
    }

    private suspend fun recordHlsLoop(entryUrl: String, firstText: String, outFile: File) {
        var playlistUrl = entryUrl
        var initialManifest: String? = firstText

        // 若是 Master Playlist，挑选最高带宽子流
        val variant = pickBestVariantUrl(playlistUrl, firstText)
        if (variant != null) {
            playlistUrl = variant
            initialManifest = null
        }

        val seenSegments = LinkedHashSet<String>()
        var initMapWritten = false
        val keyCache = HashMap<String, ByteArray>()

        FileOutputStream(outFile, false).use { fos ->
            while (!stopRequested.get()) {
                val manifest = initialManifest ?: fetchText(playlistUrl)
                initialManifest = null

                // 如果重定向到了新的二级 Master Playlist
                val subVariant = pickBestVariantUrl(playlistUrl, manifest)
                if (subVariant != null && subVariant != playlistUrl) {
                    playlistUrl = subVariant
                    continue
                }

                val parsed = parseMediaPlaylist(playlistUrl, manifest)
                if (!initMapWritten && parsed.initMapUrl != null) {
                    val initBytes = fetchBytes(parsed.initMapUrl)
                    fos.write(initBytes)
                    writtenCounter.addAndGet(initBytes.size.toLong())
                    initMapWritten = true
                }

                var newSegCount = 0
                val segmentsToFetch = if (seenSegments.isEmpty() && parsed.segments.size > 3) {
                    // 首次进入直播只从最后 2 个分片开始录，其余历史分片标记为已跳过
                    parsed.segments.dropLast(2).forEach { seenSegments.add(it.url) }
                    parsed.segments.takeLast(2)
                } else {
                    parsed.segments
                }

                for (seg in segmentsToFetch) {
                    if (stopRequested.get()) break
                    if (!seenSegments.add(seg.url)) continue
                    // 控制去重集合大小防内存增长
                    while (seenSegments.size > 600) {
                        val it = seenSegments.iterator()
                        if (it.hasNext()) {
                            it.next()
                            it.remove()
                        }
                    }

                    val rawBytes = fetchBytes(seg.url)
                    val decoded = if (seg.keyMethod == "AES-128" && !seg.keyUri.isNullOrBlank()) {
                        val keyBytes = keyCache.getOrPut(seg.keyUri) { fetchBytes(seg.keyUri) }
                        decryptAes128Segment(rawBytes, keyBytes, seg.keyIvHex, seg.sequence)
                    } else {
                        rawBytes
                    }
                    fos.write(decoded)
                    fos.flush()
                    writtenCounter.addAndGet(decoded.size.toLong())
                    newSegCount++
                }

                if (parsed.endList) break

                val waitMs = if (newSegCount > 0) {
                    (parsed.targetDurationMs / 2L).coerceIn(800L, 4_000L)
                } else {
                    1_000L
                }
                delay(waitMs)
            }
        }
    }

    private data class HlsSegment(
        val url: String,
        val sequence: Long,
        val keyMethod: String? = null,
        val keyUri: String? = null,
        val keyIvHex: String? = null,
    )

    private data class ParsedHlsMedia(
        val targetDurationMs: Long,
        val initMapUrl: String?,
        val segments: List<HlsSegment>,
        val endList: Boolean,
    )

    private fun pickBestVariantUrl(baseUrl: String, m3u8: String): String? {
        if (!m3u8.contains("#EXT-X-STREAM-INF")) return null
        val lines = m3u8.lines().map { it.trim() }
        var bestBw = -1L
        var bestUrl: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size && bw >= bestBw) {
                    bestBw = bw
                    bestUrl = resolveUrl(baseUrl, lines[j])
                }
                i = j
            }
            i++
        }
        return bestUrl
    }

    private fun parseMediaPlaylist(baseUrl: String, m3u8: String): ParsedHlsMedia {
        var targetSec = 4.0
        var mediaSeq = 0L
        var initMap: String? = null
        var keyMethod: String? = null
        var keyUri: String? = null
        var keyIv: String? = null
        var endList = false
        val segs = ArrayList<HlsSegment>()

        for (raw in m3u8.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") -> {
                    targetSec = line.substringAfter(':').trim().toDoubleOrNull() ?: targetSec
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSeq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)?.let {
                        initMap = resolveUrl(baseUrl, it)
                    }
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = line.substringAfter(':')
                    keyMethod = Regex("""METHOD=([^,\s]+)""").find(attrs)?.groupValues?.get(1)?.trim('"')
                    keyUri = Regex("""URI="([^"]+)"""").find(attrs)?.groupValues?.get(1)?.let { resolveUrl(baseUrl, it) }
                    keyIv = Regex("""IV=0[xX]([0-9a-fA-F]+)""").find(attrs)?.groupValues?.get(1)
                    if (keyMethod == "NONE") {
                        keyMethod = null; keyUri = null; keyIv = null
                    }
                }
                line == "#EXT-X-ENDLIST" -> endList = true
                !line.startsWith("#") -> {
                    segs += HlsSegment(
                        url = resolveUrl(baseUrl, line),
                        sequence = mediaSeq++,
                        keyMethod = keyMethod,
                        keyUri = keyUri,
                        keyIvHex = keyIv,
                    )
                }
            }
        }
        return ParsedHlsMedia(
            targetDurationMs = (targetSec * 1000.0).toLong().coerceAtLeast(1_000L),
            initMapUrl = initMap,
            segments = segs,
            endList = endList,
        )
    }

    private fun decryptAes128Segment(data: ByteArray, key: ByteArray, ivHex: String?, seq: Long): ByteArray {
        if (key.size != 16) return data
        val iv = ByteArray(16)
        if (!ivHex.isNullOrBlank()) {
            val padded = ivHex.padStart(32, '0').takeLast(32)
            for (i in 0 until 16) {
                iv[i] = padded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } else {
            var s = seq
            for (i in 15 downTo 8) {
                iv[i] = (s and 0xFFL).toByte()
                s = s ushr 8
            }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun fetchText(url: String): String =
        http.newCall(buildRequest(url)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("请求失败 HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }

    private fun fetchBytes(url: String): ByteArray =
        http.newCall(buildRequest(url)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("分片请求失败 HTTP ${resp.code}")
            resp.body?.bytes() ?: ByteArray(0)
        }

    private fun resolveUrl(baseUrl: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return runCatching { URI(baseUrl).resolve(ref).toString() }.getOrElse {
            val prefix = baseUrl.substringBefore('?').substringBeforeLast('/')
            if (ref.startsWith("/")) {
                val u = Uri.parse(baseUrl)
                "${u.scheme}://${u.encodedAuthority}$ref"
            } else {
                "$prefix/$ref"
            }
        }
    }

    private fun guessOutputExtension(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".flv") -> "flv"
            path.endsWith(".mp4") -> "mp4"
            else -> "ts"
        }
    }
}
