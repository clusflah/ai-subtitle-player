package com.aisub.player.download

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** 下载版：用 yt-dlp 从视频网站下载。 */
object Downloaders {
    const val EDITION = "下载版"

    fun create(context: Context): LinkDownloader = YtDlpDownloader(context.applicationContext)
}

/**
 * yt-dlp（youtubedl-android 打包的 Python 版）。
 * 先用 -J 读出视频信息，挑好字幕语言，再下载视频和字幕；需要合并音视频时用内置 FFmpeg。
 */
private class YtDlpDownloader(private val context: Context) : LinkDownloader {

    private val initLock = Mutex()
    @Volatile
    private var ready = false
    @Volatile
    private var processId: String? = null

    private suspend fun ensureReady() = initLock.withLock {
        if (ready) return@withLock
        withContext(Dispatchers.IO) {
            // 首次启动会把 Python 和 yt-dlp 解压到应用目录，需要几秒
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
        }
        ready = true
    }

    override suspend fun download(
        url: String,
        dir: File,
        quality: Int,
        targetLocale: String?,
        cookies: File?,
        onProgress: (LinkProgress) -> Unit,
    ): DownloadedLink = withContext(Dispatchers.IO) {
        // 登录后才能看的视频（会员、部分 B 站 / 抖音视频）需要 cookies
        fun YoutubeDLRequest.withCookies() = apply { cookies?.takeIf { it.length() > 0 }?.let { addOption("--cookies", it.absolutePath) } }
        onProgress(LinkProgress(LinkStage.INFO, null, "首次使用需要准备下载组件…"))
        ensureReady()
        onProgress(LinkProgress(LinkStage.INFO, null))
        dir.mkdirs()

        // 1. 视频信息：标题、ID、可用字幕
        val info = run(
            YoutubeDLRequest(url).addOption("-J").addOption("--no-playlist").addOption("--no-warnings").withCookies(),
        ) { _, _, _ -> }.out.let { out ->
            val json = runCatching { JSONObject(out) }.getOrElse { throw IOException("无法解析视频信息") }
            // 链接是播放列表时取第一个视频
            json.optJSONArray("entries")?.optJSONObject(0) ?: json
        }
        val id = info.optString("id").ifEmpty { throw IOException("这个链接里没有找到视频") }
        val title = info.optString("title").ifEmpty { id }
        val subs = SubtitleChoice.pick(info, targetLocale)

        // 2. 下载视频（和字幕）。文件名只用 ID，避免标题里的特殊字符
        val safeId = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        dir.listFiles()?.filter { it.name.startsWith("$safeId.") }?.forEach { it.delete() }
        val request = YoutubeDLRequest(info.optString("webpage_url").ifEmpty { url })
            .addOption("--no-playlist")
            .addOption("--no-mtime")
            .addOption("--newline")
            .addOption("-o", File(dir, "$safeId.%(ext)s").absolutePath)
            .addOption("-f", "bv*+ba/b")
            // 优先 H.264 + AAC，所有手机都能硬解；限制分辨率时取不超过它的最高画质
            .addOption("-S", listOfNotNull(quality.takeIf { it > 0 }?.let { "res:$it" }, "vcodec:h264", "acodec:m4a").joinToString(","))
            .addOption("--merge-output-format", "mp4")
            .withCookies()
        if (subs.languages.isNotEmpty()) {
            if (subs.manual) request.addOption("--write-subs")
            if (subs.auto) request.addOption("--write-auto-subs")
            request.addOption("--sub-langs", subs.languages.joinToString(","))
            request.addOption("--convert-subs", "srt")
        }
        onProgress(LinkProgress(LinkStage.DOWNLOAD, 0f, title))
        run(request) { percent, eta, line ->
            when {
                line.contains("[Merger]") || line.contains("[VideoConvertor]") -> onProgress(LinkProgress(LinkStage.MERGE, null, title))
                percent >= 0 -> onProgress(
                    LinkProgress(
                        LinkStage.DOWNLOAD, percent / 100f,
                        // 进度行本身带 ETA；其他行（分片清单、合并前的提示）沿用上次的百分比和 ETA，
                        // 不能拿来当说明，否则会被当成标题、一直停在「剩余约 1 秒」
                        line.substringAfter("[download]").trim().takeIf { it.contains('%') } ?: title,
                    ),
                )
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

    private fun run(request: YoutubeDLRequest, callback: (Float, Long, String) -> Unit) = try {
        val pid = UUID.randomUUID().toString()
        processId = pid
        YoutubeDL.getInstance().execute(request, pid) { p, eta, line -> callback(p, eta, line) }
    } catch (e: YoutubeDL.CanceledException) {
        throw kotlinx.coroutines.CancellationException("已取消")
    } catch (e: Exception) {
        throw IOException(friendlyError(e.message ?: e.toString()), e)
    } finally {
        processId = null
    }

    override fun cancel() {
        processId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
    }

    override suspend fun update(): String = withContext(Dispatchers.IO) {
        ensureReady()
        val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
        val version = YoutubeDL.getInstance().versionName(context) ?: YoutubeDL.getInstance().version(context)
        if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "已经是最新版（$version）" else "已更新到 $version"
    }

    override suspend fun version(): String? = withContext(Dispatchers.IO) {
        runCatching {
            ensureReady()
            YoutubeDL.getInstance().versionName(context) ?: YoutubeDL.getInstance().version(context)
        }.getOrNull()
    }

    /** yt-dlp 的报错很长，挑出关键的一行。 */
    private fun friendlyError(message: String): String {
        val line = message.lines().lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim() ?: message.lines().last { it.isNotBlank() }
        return when {
            "Unsupported URL" in line -> "不支持这个网站的链接"
            "Sign in" in line || "login" in line.lowercase() || "cookies" in line ->
                "这个视频需要登录才能下载，请到「设置 → 视频下载 → 登录 cookies」登录网站或导入 cookies.txt（$line）"
            "Private video" in line -> "这是私密视频"
            "Video unavailable" in line -> "视频不可用（可能已删除或有地区限制）"
            else -> line.take(300)
        }
    }

    companion object {
        private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "flv", "m4a", "mp3", "opus", "ogg")
    }
}
