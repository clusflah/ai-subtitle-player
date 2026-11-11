package com.aisub.player.asr

import android.content.Context
import android.net.Uri
import com.aisub.player.desktop.DesktopBinaryFinder
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.cloud.CloudRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat as JavaAudioFormat
import javax.sound.sampled.AudioSystem

/**
 * Windows 桌面版音频解码器：
 * 使用 FFmpeg（或 Java Sound API 备用）把本地/远程视频或音频文件流式解码为 16kHz 单声道 float PCM。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioDecoder(
    private val context: Context,
    private val uri: Uri,
    private val fallbackDataSource: androidx.media3.datasource.DataSource.Factory? = null,
) {

    class NoAudioTrackException : Exception("视频里没有可识别的音轨")

    fun decode(
        targetRate: Int = 16_000,
        startMs: Long = 0,
        isActive: () -> Boolean,
        onProgress: (Float) -> Unit,
        onChunk: (FloatArray) -> Unit,
    ) {
        val rawUri = uri.toString()
        val inputPath: String
        val headers: Map<String, String>
        if (CloudRegistry.isCloud(rawUri)) {
            val link = CloudRegistry.resolve(rawUri)
            inputPath = link.url
            headers = link.headers
        } else if (com.aisub.player.telegram.Telegram.isTelegram(rawUri) && fallbackDataSource != null) {
            inputPath = com.aisub.player.media.MediaProxy(fallbackDataSource).register(uri, "video.mp4")
            headers = emptyMap()
        } else if (rawUri.startsWith("http://") || rawUri.startsWith("https://")) {
            val routed = Endpoints.rewrite(rawUri)
            inputPath = routed
            headers = AuthRegistry.headersFor(routed)
        } else {
            val f = android.content.ContentResolver.resolveFile(uri) ?: uri.toJavaFile() ?: File(uri.path ?: rawUri)
            inputPath = f.absolutePath
            headers = emptyMap()
        }

        val ffmpeg = DesktopBinaryFinder.ensureFfmpeg()
        if (ffmpeg != null) {
            val initialOffsetMs = if (startMs == 0L) probeAudioStartOffsetMs(inputPath, headers) else 0L
            decodeWithFfmpeg(
                ffmpegExe = ffmpeg,
                inputPath = inputPath,
                headers = headers,
                targetRate = targetRate,
                startMs = startMs,
                isActive = isActive,
                onProgress = onProgress,
                onChunk = onChunk,
                initialAudioOffsetMs = initialOffsetMs,
            )
            return
        }

        // 备用：纯 Java Sound API 解码本地 WAV / AIFF / AU
        val localFile = File(inputPath)
        if (localFile.isFile) {
            decodeWithJavaSound(localFile, targetRate, startMs, isActive, onProgress, onChunk)
            return
        }

        throw java.io.IOException("未找到 FFmpeg 解码组件，请检查网络连接或安装 FFmpeg")
    }

    companion object {
        /**
         * 探测音轨相对容器/视频起点的偏移（毫秒）：
         * 许多 TS 录播、MKV 或带片头静音的 MP4 中，首帧音频 PTS 晚于视频起点（或带 AAC priming 负偏移）。
         * 仅在合理范围 [-2000ms, +15000ms] 内补偿，防止损坏的 TS 时间戳跳变导致误插长静音。
         */
        internal fun probeAudioStartOffsetMs(inputPath: String, headers: Map<String, String>): Long {
            val ffprobe = DesktopBinaryFinder.findFfprobe() ?: return 0L
            return runCatching {
                val cmd = ArrayList<String>()
                cmd += listOf(
                    ffprobe, "-v", "error",
                    "-probesize", "2M",
                    "-analyzeduration", "2000000",
                    "-show_entries", "format=start_time:stream=codec_type,start_time",
                    "-of", "json",
                )
                if (headers.isNotEmpty() && (inputPath.startsWith("http://") || inputPath.startsWith("https://"))) {
                    val headerStr = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
                    cmd += listOf("-headers", headerStr)
                }
                cmd += inputPath
                val proc = ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                val text = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
                if (!proc.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    return 0L
                }
                val root = org.json.JSONObject(text)
                val formatStart = root.optJSONObject("format")?.optString("start_time")?.toDoubleOrNull()
                val streams = root.optJSONArray("streams") ?: return 0L
                var videoStart: Double? = null
                var audioStart: Double? = null
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    val type = s.optString("codec_type")
                    val st = s.optString("start_time").toDoubleOrNull() ?: continue
                    if (type == "video" && videoStart == null) videoStart = st
                    if (type == "audio" && audioStart == null) audioStart = st
                }
                val aStart = audioStart ?: return 0L
                val refStart = videoStart ?: formatStart ?: return 0L
                val diffMs = kotlin.math.round((aStart - refStart) * 1000.0).toLong()
                if (diffMs in -2_000L..15_000L && kotlin.math.abs(diffMs) >= 20L) {
                    android.util.Log.i("Voxtitle-sync", "检测到音画起点偏移=${diffMs}ms (audio=${aStart}s, ref=${refStart}s)")
                    diffMs
                } else {
                    0L
                }
            }.getOrDefault(0L)
        }

        internal fun decodeWithFfmpeg(
            ffmpegExe: String,
            inputPath: String,
            headers: Map<String, String>,
            targetRate: Int,
            startMs: Long,
            isActive: () -> Boolean,
            onProgress: (Float) -> Unit,
            onChunk: (FloatArray) -> Unit,
            onEmittedMs: ((Long) -> Unit)? = null,
            initialAudioOffsetMs: Long = 0L,
        ) {
            val cmd = ArrayList<String>()
            cmd += ffmpegExe
            cmd += listOf("-nostdin", "-hide_banner")
            if (startMs > 0) {
                cmd += listOf("-ss", String.format(java.util.Locale.US, "%.3f", startMs / 1000.0))
            }
            if (headers.isNotEmpty() && (inputPath.startsWith("http://") || inputPath.startsWith("https://"))) {
                val headerStr = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
                cmd += listOf("-headers", headerStr)
            }
            cmd += listOf(
                "-i", inputPath,
                "-vn",
                "-ac", "1",
                "-ar", targetRate.toString(),
                "-af", "aresample=async=1000",
                "-f", "f32le",
                "-acodec", "pcm_f32le",
                "pipe:1",
            )

            val proc = ProcessBuilder(cmd).start()
            var durationSec = 0.0
            val stderrLines = StringBuilder()
            val errThread = Thread {
                runCatching {
                    val durRegex = Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""")
                    proc.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        synchronized(stderrLines) {
                            if (stderrLines.length < 4000) stderrLines.appendLine(line)
                        }
                        if (durationSec <= 0.0) {
                            val m = durRegex.find(line)
                            if (m != null) {
                                val h = m.groupValues[1].toDoubleOrNull() ?: 0.0
                                val min = m.groupValues[2].toDoubleOrNull() ?: 0.0
                                val s = m.groupValues[3].toDoubleOrNull() ?: 0.0
                                val total = h * 3600.0 + min * 60.0 + s - (startMs / 1000.0)
                                if (total > 0) durationSec = total
                            }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            var totalSamples = 0L
            var dropLeadingSamples = if (initialAudioOffsetMs < 0L) (-initialAudioOffsetMs * targetRate / 1000L) else 0L
            var prependedSilence = initialAudioOffsetMs <= 0L
            var lastTickMs = System.currentTimeMillis()
            try {
                val buf = ByteArray(16384)
                var leftover = 0
                val ins = proc.inputStream
                while (isActive()) {
                    val n = ins.read(buf, leftover, buf.size - leftover)
                    if (n < 0) break
                    val totalBytes = leftover + n
                    val floatCount = totalBytes / 4
                    if (floatCount > 0) {
                        val validBytes = floatCount * 4
                        val fb = ByteBuffer.wrap(buf, 0, validBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        var floats = FloatArray(floatCount)
                        fb.get(floats)
                        if (!prependedSilence) {
                            prependedSilence = true
                            var padRemaining = (initialAudioOffsetMs * targetRate / 1000L).toInt().coerceAtLeast(0)
                            val chunkCap = targetRate // 1s chunks
                            while (padRemaining > 0 && isActive()) {
                                val step = minOf(padRemaining, chunkCap)
                                onChunk(FloatArray(step))
                                totalSamples += step
                                padRemaining -= step
                            }
                        }
                        if (dropLeadingSamples > 0L) {
                            if (floats.size <= dropLeadingSamples) {
                                dropLeadingSamples -= floats.size
                                floats = FloatArray(0)
                            } else {
                                val skip = dropLeadingSamples.toInt()
                                dropLeadingSamples = 0L
                                floats = floats.copyOfRange(skip, floats.size)
                            }
                        }
                        if (floats.isNotEmpty()) {
                            onChunk(floats)
                            totalSamples += floats.size
                        }
                        val emittedMs = totalSamples * 1000L / targetRate
                        val now = System.currentTimeMillis()
                        if (now - lastTickMs >= 250L) {
                            lastTickMs = now
                            onEmittedMs?.invoke(emittedMs)
                            if (durationSec > 0.0) {
                                val p = ((totalSamples.toDouble() / targetRate) / durationSec).toFloat().coerceIn(0f, 1f)
                                onProgress(p)
                            }
                        }
                        leftover = totalBytes - validBytes
                        if (leftover > 0) {
                            System.arraycopy(buf, validBytes, buf, 0, leftover)
                        }
                    } else {
                        leftover = totalBytes
                    }
                }
            } finally {
                if (!isActive()) {
                    runCatching { proc.destroyForcibly() }
                }
            }

            val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)
            errThread.join(1000)
            if (!isActive()) return

            if (totalSamples == 0L) {
                val errText = synchronized(stderrLines) { stderrLines.toString() }
                if (errText.contains("Output file #0 does not contain any stream") ||
                    errText.contains("Stream map '' matches no streams") ||
                    errText.contains("does not contain any stream")
                ) {
                    throw NoAudioTrackException()
                }
                if (exitCode != 0) {
                    throw java.io.IOException("无法解码音轨：${errText.lines().lastOrNull { it.isNotBlank() } ?: "FFmpeg 退出码 $exitCode"}")
                }
                throw NoAudioTrackException()
            }
            onProgress(1f)
        }
    }

    private fun decodeWithJavaSound(
        file: File,
        targetRate: Int,
        startMs: Long,
        isActive: () -> Boolean,
        onProgress: (Float) -> Unit,
        onChunk: (FloatArray) -> Unit,
    ) {
        val rawIn = AudioSystem.getAudioInputStream(file)
        val baseFormat = rawIn.format
        val pcmFormat = JavaAudioFormat(
            JavaAudioFormat.Encoding.PCM_SIGNED,
            baseFormat.sampleRate,
            16,
            baseFormat.channels.coerceAtLeast(1),
            baseFormat.channels.coerceAtLeast(1) * 2,
            baseFormat.sampleRate,
            false,
        )
        val pcmIn = AudioSystem.getAudioInputStream(pcmFormat, rawIn)
        pcmIn.use { stream ->
            val channels = pcmFormat.channels.coerceAtLeast(1)
            val sampleRate = pcmFormat.sampleRate.toInt().coerceAtLeast(8000)
            val resampler = LinearResampler(sampleRate, targetRate)
            val frameBytes = channels * 2
            if (startMs > 0) {
                val skipBytes = (startMs * sampleRate / 1000L) * frameBytes
                stream.skip(skipBytes)
            }
            val buf = ByteArray(frameBytes * 2048)
            var gotAudio = false
            while (isActive()) {
                val n = stream.read(buf)
                if (n < 0) break
                val frames = n / frameBytes
                if (frames <= 0) continue
                val sb = ByteBuffer.wrap(buf, 0, frames * frameBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val mono = FloatArray(frames) { i ->
                    var sum = 0f
                    for (c in 0 until channels) sum += sb.get(i * channels + c)
                    sum / channels / 32768f
                }
                val out = resampler.process(mono)
                if (out.isNotEmpty()) {
                    gotAudio = true
                    onChunk(out)
                }
            }
            if (!gotAudio && isActive()) throw NoAudioTrackException()
            if (isActive()) onProgress(1f)
        }
    }
}

/** 流式线性插值重采样。对语音识别来说精度足够。 */
class LinearResampler(inRate: Int, outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private val passthrough = inRate == outRate

    private var pos = 0.0
    private var prev = 0f

    fun process(input: FloatArray): FloatArray {
        if (passthrough || input.isEmpty()) return input
        val n = input.size
        val out = ArrayList<Float>((n / step).toInt() + 2)
        while (pos < n - 1) {
            val i = kotlin.math.floor(pos).toInt()
            val frac = (pos - i).toFloat()
            val a = if (i < 0) prev else input[i]
            val b = input[i + 1]
            out.add(a + (b - a) * frac)
            pos += step
        }
        pos -= n
        prev = input[n - 1]
        return out.toFloatArray()
    }
}
