package com.aisub.player.live

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.aisub.player.desktop.DesktopBinaryFinder
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
 * Windows 桌面端直播录屏 / 无损直播流录制器：
 * 1. 优先使用桌面端 FFmpeg（[DesktopBinaryFinder.ensureFfmpeg]）将任意协议直播流（HLS/FLV/RTMP/RTSP/TS）零重编码直录为 `.ts` 并在停止时瞬时封装为标准 `.mp4`；
 * 2. 若系统未检出 FFmpeg，自动无感切换至纯 Kotlin HLS/HTTP-TS 直播录制引擎；
 * 3. 录制成品直接保存在用户 `Videos/Voxtitle` 文件夹，并在开启了实时 AI 字幕时同步生成对齐时间轴的同名 `.srt` 字幕。
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
    private var activeFfmpegProcess: Process? = null

    fun clearMessage() {
        _state.update { it.copy(lastSavedMessage = null, error = null) }
    }

    @Synchronized
    fun start(
        context: Context,
        streamUrl: String,
        channelTitle: String,
        mpvStreamRecordStarter: ((File) -> (() -> Unit)?)? = null,
    ) {
        if (_state.value.recording || _state.value.saving) return
        val outDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            MovieSaver.FOLDER,
        ).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeTitle = MovieSaver.safeName(channelTitle.ifBlank { "直播" }).take(48)
        val tempFile = File(outDir, ".recording_${safeTitle}_$stamp.ts")

        stopRequested.set(false)
        writtenCounter.set(0L)
        activeTempFile = tempFile
        activeFfmpegProcess = null
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
                val ffmpeg = DesktopBinaryFinder.ensureFfmpeg()
                if (ffmpeg != null) {
                    recordWithFfmpeg(ffmpeg, streamUrl, tempFile)
                } else {
                    recordHttpOrHls(streamUrl, tempFile)
                }
            } catch (ce: CancellationException) {
                // 正常停止
            } catch (e: Exception) {
                if (!stopRequested.get()) {
                    _state.update {
                        it.copy(recording = false, error = "直播录屏中断：${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    fun stop(
        context: Context,
        cuesSnapshot: List<Cue> = emptyList(),
        onSaved: ((Uri?) -> Unit)? = null,
    ) {
        if (!_state.value.recording) return
        val snapshot = _state.value
        val tempFile = activeTempFile
        stopRequested.set(true)
        tickerJob?.cancel()
        _state.update { it.copy(recording = false, saving = true) }

        scope.launch {
            activeFfmpegProcess?.let { proc ->
                runCatching {
                    proc.outputStream.write("q\n".toByteArray())
                    proc.outputStream.flush()
                }
                if (!runCatching { proc.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)) {
                    runCatching { proc.destroyForcibly() }
                }
            }
            activeFfmpegProcess = null
            runCatching { recordJob?.cancel() }
            delay(200L)

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

            val outDir = tempFile.parentFile ?: File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                MovieSaver.FOLDER,
            ).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(snapshot.startedAtMs))
            val baseTitle = "直播录屏_${MovieSaver.safeName(snapshot.channelTitle).take(48)}_$stamp"

            val finalVideoFile = remuxToMp4IfPossible(tempFile, outDir, baseTitle)
            if (cuesSnapshot.isNotEmpty()) {
                runCatching {
                    val baseOffset = cuesSnapshot.first().startMs
                    val shifted = cuesSnapshot.mapIndexed { idx, c ->
                        c.copy(
                            id = idx + 1,
                            startMs = (c.startMs - baseOffset).coerceAtLeast(0L),
                            endMs = (c.endMs - baseOffset).coerceAtLeast(200L),
                        )
                    }
                    FileOutputStream(File(outDir, "$baseTitle.srt")).use { out ->
                        writeSrt(shifted, SrtMode.BILINGUAL, out)
                    }
                }
            }

            val uri = Uri.fromFile(finalVideoFile)
            _state.update {
                it.copy(
                    recording = false,
                    saving = false,
                    lastSavedMessage = "直播录屏已保存：${finalVideoFile.absolutePath}",
                    error = null,
                )
            }
            onSaved?.invoke(uri)
        }
    }

    private fun recordWithFfmpeg(ffmpegPath: String, streamUrl: String, outTs: File) {
        val headers = requestHeadersFor(streamUrl)
        val headerLines = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
        val args = ArrayList<String>().apply {
            add(ffmpegPath)
            add("-y")
            add("-loglevel"); add("error")
            if (streamUrl.startsWith("http://", ignoreCase = true) || streamUrl.startsWith("https://", ignoreCase = true)) {
                add("-headers"); add(headerLines)
                add("-reconnect"); add("1")
                add("-reconnect_streamed"); add("1")
                add("-reconnect_delay_max"); add("5")
            }
            add("-i"); add(streamUrl)
            add("-c"); add("copy")
            add("-f"); add("mpegts")
            add(outTs.absolutePath)
        }
        val proc = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()
        activeFfmpegProcess = proc
        proc.inputStream.bufferedReader().use { reader ->
            val errTail = StringBuilder()
            while (!stopRequested.get()) {
                if (!proc.isAlive) break
                if (reader.ready()) {
                    val line = reader.readLine()
                    if (line != null && errTail.length < 600) errTail.appendLine(line)
                } else {
                    Thread.sleep(200L)
                }
            }
            if (!stopRequested.get() && outTs.length() < 4096L) {
                throw IOException(errTail.toString().trim().ifEmpty { "FFmpeg 拉流退出 (${proc.exitValue()})" })
            }
        }
    }

    private fun remuxToMp4IfPossible(tempTs: File, outDir: File, baseTitle: String): File {
        val ffmpeg = DesktopBinaryFinder.ensureFfmpeg()
        if (ffmpeg != null) {
            val mp4File = File(outDir, "$baseTitle.mp4")
            val ok = runCatching {
                val p = ProcessBuilder(
                    ffmpeg, "-y", "-loglevel", "error",
                    "-i", tempTs.absolutePath,
                    "-c", "copy",
                    "-bsf:a", "aac_adtstoasc",
                    "-movflags", "+faststart",
                    mp4File.absolutePath,
                ).start()
                val finished = p.waitFor(30, TimeUnit.SECONDS)
                finished && p.exitValue() == 0 && mp4File.isFile && mp4File.length() > 4096L
            }.getOrDefault(false)
            if (ok) {
                tempTs.delete()
                return mp4File
            }
            mp4File.delete()
        }
        val tsFile = File(outDir, "$baseTitle.ts")
        if (!tempTs.renameTo(tsFile)) {
            tempTs.copyTo(tsFile, overwrite = true)
            tempTs.delete()
        }
        return tsFile
    }

    private fun requestHeadersFor(url: String): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        merged["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Voxtitle/2.9"
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
        http.newCall(buildRequest(initialUrl)).execute().use { resp ->
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
            val body = resp.body ?: throw IOException("直播流无数据")
            body.byteStream().use { ins ->
                FileOutputStream(outFile, false).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (!stopRequested.get()) {
                        val n = ins.read(buf)
                        if (n < 0) break
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
        val seenSegments = LinkedHashSet<String>()
        val keyCache = HashMap<String, ByteArray>()

        FileOutputStream(outFile, false).use { fos ->
            while (!stopRequested.get()) {
                val manifest = initialManifest ?: http.newCall(buildRequest(playlistUrl)).execute().use {
                    if (!it.isSuccessful) throw IOException("M3U8 请求失败 HTTP ${it.code}")
                    it.body?.string().orEmpty()
                }
                initialManifest = null
                if (manifest.contains("#EXT-X-STREAM-INF")) {
                    val lines = manifest.lines().map { it.trim() }
                    val sub = lines.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    if (sub != null) {
                        playlistUrl = resolveUrl(playlistUrl, sub)
                        continue
                    }
                }
                var seq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(manifest)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                var keyUri: String? = null
                var keyIv: String? = null
                val segs = ArrayList<Triple<String, Long, Pair<String?, String?>>>()
                for (raw in manifest.lines()) {
                    val line = raw.trim()
                    if (line.startsWith("#EXT-X-KEY:")) {
                        val method = Regex("""METHOD=([^,\s]+)""").find(line)?.groupValues?.get(1)
                        keyUri = if (method == "AES-128") Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)?.let { resolveUrl(playlistUrl, it) } else null
                        keyIv = Regex("""IV=0[xX]([0-9a-fA-F]+)""").find(line)?.groupValues?.get(1)
                    } else if (line.isNotEmpty() && !line.startsWith("#")) {
                        segs += Triple(resolveUrl(playlistUrl, line), seq++, keyUri to keyIv)
                    }
                }
                val activeSegs = if (seenSegments.isEmpty() && segs.size > 3) {
                    segs.dropLast(2).forEach { seenSegments.add(it.first) }
                    segs.takeLast(2)
                } else segs

                for ((u, s, kp) in activeSegs) {
                    if (stopRequested.get()) break
                    if (!seenSegments.add(u)) continue
                    val raw = http.newCall(buildRequest(u)).execute().use { it.body?.bytes() ?: ByteArray(0) }
                    val ku = kp.first
                    val bytes = if (ku != null) {
                        val kb = keyCache.getOrPut(ku) {
                            http.newCall(buildRequest(ku)).execute().use { it.body?.bytes() ?: ByteArray(0) }
                        }
                        decryptAes128(raw, kb, kp.second, s)
                    } else raw
                    fos.write(bytes)
                    fos.flush()
                    writtenCounter.addAndGet(bytes.size.toLong())
                }
                delay(1500L)
            }
        }
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, ivHex: String?, seq: Long): ByteArray {
        if (key.size != 16) return data
        val iv = ByteArray(16)
        if (!ivHex.isNullOrBlank()) {
            val padded = ivHex.padStart(32, '0').takeLast(32)
            for (i in 0 until 16) iv[i] = padded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        } else {
            var s = seq
            for (i in 15 downTo 8) { iv[i] = (s and 0xFFL).toByte(); s = s ushr 8 }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun resolveUrl(baseUrl: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return runCatching { URI(baseUrl).resolve(ref).toString() }.getOrElse {
            val prefix = baseUrl.substringBefore('?').substringBeforeLast('/')
            "$prefix/$ref"
        }
    }
}
