package com.aisub.player.download

import android.content.Context
import com.aisub.player.desktop.DesktopBinaryFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Windows 桌面版：用 yt-dlp + ffmpeg 从视频网站下载。 */
object Downloaders {
    const val EDITION = "Windows 完整版"

    fun create(context: Context): LinkDownloader = DesktopYtDlpDownloader()
}

private class DesktopYtDlpDownloader : LinkDownloader {

    private val initLock = Mutex()
    @Volatile
    private var ytdlpExe: String? = null
    @Volatile
    private var ffmpegLocation: String? = null
    @Volatile
    private var activeProcess: Process? = null
    @Volatile
    private var cancelled = false

    private suspend fun ensureReady(onStatus: ((String) -> Unit)? = null) = initLock.withLock {
        if (ytdlpExe != null && File(ytdlpExe!!).exists()) return@withLock
        withContext(Dispatchers.IO) {
            ytdlpExe = DesktopBinaryFinder.ensureYtDlp(onStatus)
            ffmpegLocation = DesktopBinaryFinder.ensureFfmpeg(onStatus)?.let { File(it).parentFile?.absolutePath }
        }
    }

    override suspend fun download(
        url: String,
        dir: File,
        quality: Int,
        targetLocale: String?,
        cookies: File?,
        onProgress: (LinkProgress) -> Unit,
    ): DownloadedLink = withContext(Dispatchers.IO) {
        cancelled = false
        onProgress(LinkProgress(LinkStage.INFO, null, "准备下载组件…"))
        ensureReady { msg -> onProgress(LinkProgress(LinkStage.INFO, null, msg)) }
        onProgress(LinkProgress(LinkStage.INFO, null))
        dir.mkdirs()

        val cookieArgs = if (cookies != null && cookies.length() > 0) {
            listOf("--cookies", cookies.absolutePath)
        } else emptyList()
        val ffmpegArgs = ffmpegLocation?.let { listOf("--ffmpeg-location", it) }.orEmpty()

        // 1. 视频信息：标题、ID、可用字幕
        val infoOut = runCommand(
            listOf(ytdlpExe!!, "-J", "--no-playlist", "--no-warnings") + cookieArgs + listOf(url)
        ) { _, _ -> }
        val info = runCatching { JSONObject(infoOut) }.getOrElse { throw IOException("无法解析视频信息") }.let { json ->
            json.optJSONArray("entries")?.optJSONObject(0) ?: json
        }
        val id = info.optString("id").ifEmpty { throw IOException("这个链接里没有找到视频") }
        val title = info.optString("title").ifEmpty { id }
        val subs = SubtitleChoice.pick(info, targetLocale)

        // 2. 下载视频（和字幕）
        val safeId = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        dir.listFiles()?.filter { it.name.startsWith("$safeId.") }?.forEach { it.delete() }

        val cmd = ArrayList<String>()
        cmd += ytdlpExe!!
        cmd += listOf(
            "--no-playlist",
            "--no-mtime",
            "--newline",
            "-o", File(dir, "$safeId.%(ext)s").absolutePath,
            "-f", "bv*+ba/b",
            "-S", listOfNotNull(quality.takeIf { it > 0 }?.let { "res:$it" }, "vcodec:h264", "acodec:m4a").joinToString(","),
            "--merge-output-format", "mp4",
        )
        cmd += ffmpegArgs
        cmd += cookieArgs
        if (subs.languages.isNotEmpty()) {
            if (subs.manual) cmd += "--write-subs"
            if (subs.auto) cmd += "--write-auto-subs"
            cmd += listOf("--sub-langs", subs.languages.joinToString(","))
            cmd += listOf("--convert-subs", "srt")
        }
        cmd += info.optString("webpage_url").ifEmpty { url }

        onProgress(LinkProgress(LinkStage.DOWNLOAD, 0f, title))
        val percentRegex = Regex("""\[download\]\s+(\d+(?:\.\d+)?)%""")
        runCommand(cmd) { line, _ ->
            when {
                line.contains("[Merger]") || line.contains("[VideoConvertor]") ->
                    onProgress(LinkProgress(LinkStage.MERGE, null, title))
                line.contains("[download]") -> {
                    val m = percentRegex.find(line)
                    val pct = m?.groupValues?.getOrNull(1)?.toFloatOrNull()
                    if (pct != null) {
                        onProgress(
                            LinkProgress(
                                LinkStage.DOWNLOAD,
                                (pct / 100f).coerceIn(0f, 1f),
                                line.substringAfter("[download]").trim().takeIf { it.contains('%') } ?: title,
                            ),
                        )
                    }
                }
            }
        }

        val files = dir.listFiles()?.filter { it.name.startsWith("$safeId.") }.orEmpty()
        val video = files.filter { it.extension.lowercase() in VIDEO_EXTS }.maxByOrNull { it.length() }
            ?: throw IOException("下载完成，但没有找到视频文件")
        fun subFile(lang: String?) = lang?.let { l -> files.firstOrNull { it.name == "$safeId.$l.srt" } }
        DownloadedLink(
            video = video,
            title = title,
            subtitle = subFile(subs.source),
            autoCaption = subs.sourceIsAuto,
            translation = subFile(subs.target),
        )
    }

    private fun runCommand(args: List<String>, onLine: (String, Boolean) -> Unit): String {
        val pb = ProcessBuilder(args)
        pb.environment()["PYTHONIOENCODING"] = "utf-8"
        val proc = pb.start()
        activeProcess = proc
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        try {
            val errThread = Thread {
                runCatching {
                    proc.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        synchronized(stderr) { stderr.appendLine(line) }
                        onLine(line, true)
                    }
                }
            }.apply { isDaemon = true; start() }

            proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                stdout.appendLine(line)
                onLine(line, false)
            }
            errThread.join(3000)
            val code = proc.waitFor()
            if (cancelled) throw kotlinx.coroutines.CancellationException("已取消")
            if (code != 0) {
                val errText = synchronized(stderr) { stderr.toString() }.ifBlank { stdout.toString() }
                throw IOException(friendlyError(errText))
            }
            return stdout.toString()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IOException) {
            if (cancelled) throw kotlinx.coroutines.CancellationException("已取消")
            throw e
        } catch (e: Exception) {
            if (cancelled) throw kotlinx.coroutines.CancellationException("已取消")
            throw IOException(friendlyError(e.message ?: e.toString()), e)
        } finally {
            activeProcess = null
            runCatching { proc.destroyForcibly() }
        }
    }

    override fun cancel() {
        cancelled = true
        activeProcess?.let { runCatching { it.destroyForcibly() } }
    }

    override suspend fun update(): String = withContext(Dispatchers.IO) {
        ensureReady()
        val proc = ProcessBuilder(ytdlpExe!!, "-U").redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        proc.waitFor()
        out.lines().lastOrNull { it.isNotBlank() } ?: "已检查更新"
    }

    override suspend fun version(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val exe = DesktopBinaryFinder.findYtDlp() ?: return@runCatching null
            val proc = ProcessBuilder(exe, "--version").redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
            proc.waitFor()
            out.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun friendlyError(message: String): String {
        val line = message.lines().lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
            ?: message.lines().lastOrNull { it.isNotBlank() }
            ?: "下载失败"
        return when {
            "Unsupported URL" in line -> "不支持这个网站的链接"
            "Sign in" in line || "login" in line.lowercase() || "cookies" in line ->
                "这个视频需要登录才能下载，请到「设置 → 视频下载 → 登录 cookies」导入 cookies.txt（$line）"
            "Private video" in line -> "这是私密视频"
            "Video unavailable" in line -> "视频不可用（可能已删除或有地区限制）"
            else -> line.take(300)
        }
    }

    companion object {
        private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "flv", "m4a", "mp3", "opus", "ogg")
    }
}
