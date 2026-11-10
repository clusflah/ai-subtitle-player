package com.aisub.player.desktop

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object DesktopBinaryFinder {
    val binDir: File by lazy {
        val localAppData = System.getenv("LOCALAPPDATA")
        val base = if (!localAppData.isNullOrBlank()) {
            File(localAppData, "Voxtitle/bin")
        } else {
            File(System.getProperty("user.home", "."), ".voxtitle/bin")
        }
        if (!base.exists()) base.mkdirs()
        base
    }

    @Volatile
    private var cachedFfmpeg: String? = null

    @Volatile
    private var cachedFfprobe: String? = null

    @Volatile
    private var cachedYtDlp: String? = null

    fun findFfmpeg(): String? {
        cachedFfmpeg?.let { if (File(it).exists()) return it }
        findExecutable("ffmpeg.exe")?.let {
            cachedFfmpeg = it
            return it
        }
        return null
    }

    fun findFfprobe(): String? {
        cachedFfprobe?.let { if (File(it).exists()) return it }
        findExecutable("ffprobe.exe")?.let {
            cachedFfprobe = it
            return it
        }
        return null
    }

    fun findYtDlp(): String? {
        cachedYtDlp?.let { if (File(it).exists()) return it }
        findExecutable("yt-dlp.exe")?.let {
            cachedYtDlp = it
            return it
        }
        return null
    }

    fun ensureYtDlp(onProgress: ((String) -> Unit)? = null): String {
        findYtDlp()?.let { return it }
        val target = File(binDir, "yt-dlp.exe")
        onProgress?.invoke("正在下载 Windows 组件 yt-dlp.exe…")
        val url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
        downloadFile(url, target)
        cachedYtDlp = target.absolutePath
        return target.absolutePath
    }

    fun ensureFfmpeg(onProgress: ((String) -> Unit)? = null): String? {
        findFfmpeg()?.let { return it }
        val target = File(binDir, "ffmpeg.exe")
        if (target.exists()) {
            cachedFfmpeg = target.absolutePath
            return target.absolutePath
        }
        runCatching {
            onProgress?.invoke("正在下载 Windows 音视频解码组件 ffmpeg…")
            val zipUrl = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip"
            val tmpZip = File(binDir, "ffmpeg-win64.zip")
            downloadFile(zipUrl, tmpZip)
            ZipInputStream(tmpZip.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && (name == "ffmpeg.exe" || name == "ffprobe.exe")) {
                        val out = File(binDir, name)
                        out.outputStream().use { zis.copyTo(it) }
                    }
                }
            }
            tmpZip.delete()
        }
        return findFfmpeg()
    }

    private fun findExecutable(exeName: String): String? {
        // 1. 应用自带的 bin 目录
        val inBin = File(binDir, exeName)
        if (inBin.isFile && inBin.length() > 0) return inBin.absolutePath

        // 2. 环境变量 PATH
        val pathEnv = System.getenv("PATH").orEmpty()
        for (dir in pathEnv.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            val candidate = File(dir.trim('"'), exeName)
            if (candidate.isFile) return candidate.absolutePath
        }

        // 3. 常见 Windows 包管理器安装目录（Winget / Scoop / Chocolatey）
        val home = System.getProperty("user.home").orEmpty()
        val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
        val commonDirs = listOf(
            File(home, "scoop/shims"),
            File(localAppData, "Microsoft/WinGet/Links"),
            File("C:/ProgramData/chocolatey/bin"),
            File("C:/ffmpeg/bin"),
        )
        for (d in commonDirs) {
            val f = File(d, exeName)
            if (f.isFile) return f.absolutePath
        }

        // 4. WinGet Packages 子目录
        val wingetPkgs = File(localAppData, "Microsoft/WinGet/Packages")
        if (wingetPkgs.isDirectory) {
            wingetPkgs.walkTopDown().maxDepth(4).firstOrNull { it.isFile && it.name.equals(exeName, ignoreCase = true) }?.let {
                return it.absolutePath
            }
        }
        return null
    }

    private fun downloadFile(url: String, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${dest.name}.part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Voxtitle-Windows")
        try {
            if (conn.responseCode !in 200..299) {
                throw java.io.IOException("下载失败 HTTP ${conn.responseCode}: $url")
            }
            conn.inputStream.use { ins ->
                tmp.outputStream().use { out ->
                    ins.copyTo(out)
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            conn.disconnect()
        }
    }
}
