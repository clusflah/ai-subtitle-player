package com.aisub.player.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast
import androidx.core.content.FileProvider
import com.aisub.player.net.AppProxy
import okhttp3.Request
import org.json.JSONArray
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** 只给更新安装包用的 FileProvider（桌面端保留同名类以保持源码对齐） */
class UpdateFileProvider : FileProvider()

/**
 * Windows 桌面端应用内更新：
 * 1. 统一走 [AppProxy]（OkHttpClient），自动遵循应用内 HTTP/SOCKS5 代理并正确处理 GitHub Releases 跨域重定向；
 * 2. 提供原生 Swing 实时下载进度窗口（支持断点续传重试、下载速度显示与随时取消）；
 * 3. 严格校验安装包完整字节长度与二进制魔数（EXE `MZ` / MSI `D0CF11E0` / ZIP `PK`），杜绝半截文件或报错网页被当成安装包导致 Windows 提示“无法打开程序”；
 * 4. 自动清理 NTFS `Zone.Identifier` 网络锁定标记，并通过独立脱离父进程句柄的更新脚本拉起 `msiexec` / UAC `Start-Process`（或解压便携版），同时优雅退出当前进程释放 `.exe` / `.dll` 文件锁。
 */
class AppUpdater(
    private val repo: String,
    private val acceptTag: (String) -> Boolean = { true },
    private val pickAsset: (List<String>) -> String?,
) {
    class Release(
        val tag: String,
        val notes: String,
        val url: String,
        val fileName: String,
        val size: Long,
        val page: String,
    )

    private val main = Handler(Looper.getMainLooper())

    private val apiHttp = AppProxy.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val downloadHttp = AppProxy.builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** 当前桌面端版本号（versionName） */
    fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    /** 查询 GitHub Releases 最新正式版 */
    fun fetchLatest(): Release? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases?per_page=20")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "$repo-windows-updater")
            .build()
        val text = apiHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GitHub 返回 HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        val list = JSONArray(text)
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            if (!acceptTag(tag)) continue
            val assets = r.optJSONArray("assets") ?: continue
            val names = (0 until assets.length()).map { assets.getJSONObject(it).optString("name") }
            val pick = pickAsset(names) ?: continue
            val a = assets.getJSONObject(names.indexOf(pick))
            return Release(
                tag = tag,
                notes = r.optString("body"),
                url = a.optString("browser_download_url"),
                fileName = pick,
                size = a.optLong("size"),
                page = r.optString("html_url"),
            )
        }
        return null
    }

    /** 打开 App 时调用：一天最多自动检查一次 */
    fun autoCheck(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0) < 20 * 60 * 60 * 1000L) return
        prefs.edit().putLong("last_check", now).apply()
        Thread({
            val rel = runCatching { fetchLatest() }.getOrNull() ?: return@Thread
            if (!isNewer(rel.tag, currentVersion(activity)) || rel.tag == prefs.getString("skipped", null)) return@Thread
            main.post { if (!activity.isFinishing && !activity.isDestroyed) showRelease(activity, rel, auto = true) }
        }, "Voxtitle-AutoUpdateCheck").apply { isDaemon = true; start() }
    }

    /** 设置或「我的」里的手动检查更新 */
    fun checkNow(activity: Activity) {
        Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
        Thread({
            val result = runCatching { fetchLatest() }
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                val rel = result.getOrElse {
                    Toast.makeText(
                        activity,
                        "检查更新失败：${it.message ?: it.javaClass.simpleName}（可在设置中配置网络代理）",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@post
                }
                if (rel == null || !isNewer(rel.tag, currentVersion(activity))) {
                    Toast.makeText(activity, "已是最新版本（${currentVersion(activity)}）", Toast.LENGTH_SHORT).show()
                } else {
                    showRelease(activity, rel, auto = false)
                }
            }
        }, "Voxtitle-ManualUpdateCheck").apply { isDaemon = true; start() }
    }

    private fun showRelease(activity: Activity, rel: Release, auto: Boolean) {
        val notes = notesSince(rel.notes, currentVersion(activity)).let { if (it.length > 4000) it.take(4000) + "…" else it }
        val size = if (rel.size > 0) " · 安装包 %.1f MB".format(rel.size / 1048576.0) else ""
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 ${rel.tag}")
            .setMessage("当前版本 ${currentVersion(activity)}$size\n\n$notes")
            .setPositiveButton("下载并更新") { _, _ -> download(activity, rel) }
            .setNegativeButton("以后再说", null)
            .apply {
                if (auto) {
                    setNeutralButton("跳过此版本") { _, _ ->
                        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("skipped", rel.tag).apply()
                    }
                } else {
                    setNeutralButton("网页下载") { _, _ -> openPage(activity, rel.page) }
                }
            }
            .show()
    }

    private fun resolveUpdateDir(activity: Activity): File {
        val downloads = runCatching {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Voxtitle/Updates")
                .apply { mkdirs() }
                .takeIf { it.isDirectory && it.canWrite() }
        }.getOrNull()
        if (downloads != null) return downloads
        val temp = runCatching {
            File(System.getProperty("java.io.tmpdir", "."), "VoxtitleUpdates")
                .apply { mkdirs() }
                .takeIf { it.isDirectory && it.canWrite() }
        }.getOrNull()
        return temp ?: File(activity.cacheDir, DIR).apply { mkdirs() }
    }

    private class DesktopProgressUi(fileName: String, indeterminate: Boolean, onCancel: () -> Unit) {
        val label = JLabel("正在连接下载服务器：$fileName")
        val bar = JProgressBar(0, 100).apply {
            isIndeterminate = indeterminate
            isStringPainted = !indeterminate
            preferredSize = Dimension(380, 22)
        }
        private val cancelBtn = JButton("取消下载").apply {
            addActionListener { onCancel() }
        }
        val dialog: JDialog = JDialog(null as java.awt.Frame?, "下载更新", false).apply {
            defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
            val content = JPanel(BorderLayout(0, 10)).apply {
                border = BorderFactory.createEmptyBorder(16, 20, 12, 20)
                add(label, BorderLayout.NORTH)
                add(bar, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { add(cancelBtn) }, BorderLayout.SOUTH)
            }
            contentPane = content
            pack()
            setLocationRelativeTo(null)
            isAlwaysOnTop = true
        }

        fun show() = SwingUtilities.invokeLater { runCatching { dialog.isVisible = true } }
        fun update(pct: Int, text: String) = SwingUtilities.invokeLater {
            runCatching {
                if (pct in 0..100) {
                    bar.isIndeterminate = false
                    bar.isStringPainted = true
                    bar.value = pct
                }
                label.text = text
            }
        }
        fun dismiss() = SwingUtilities.invokeLater { runCatching { dialog.dispose() } }
    }

    private fun download(activity: Activity, rel: Release) {
        val cancelled = AtomicBoolean(false)
        val progressUi = runCatching {
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                DesktopProgressUi(rel.fileName, rel.size <= 0) { cancelled.set(true) }.also { it.show() }
            } else null
        }.getOrNull()

        Toast.makeText(activity, "开始下载更新 ${rel.fileName}…", Toast.LENGTH_SHORT).show()

        Thread({
            val dir = resolveUpdateDir(activity)
            val safeFileName = rel.fileName.replace(Regex("""[\\/:*?"<>|]"""), "_")
            val targetFile = File(dir, safeFileName)
            val partFile = File(dir, "$safeFileName.part")

            // 如果之前已经完整下载并通过校验，直接复用，免去重复下载
            if (targetFile.isFile && isValidInstallerFile(targetFile, rel.size)) {
                progressUi?.dismiss()
                main.post {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        install(activity, targetFile, rel)
                    }
                }
                return@Thread
            }

            val result = runCatching {
                var attempt = 0
                val maxAttempts = 3
                var expectedTotal = rel.size

                // 旧的残余 .part 若超过预期大小则清理重下
                if (partFile.exists() && expectedTotal > 0 && partFile.length() > expectedTotal) {
                    partFile.delete()
                }

                while (true) {
                    if (cancelled.get()) throw InterruptedException()
                    val existingBytes = if (partFile.exists()) partFile.length() else 0L
                    if (expectedTotal > 0 && existingBytes == expectedTotal) break

                    val reqBuilder = Request.Builder()
                        .url(rel.url)
                        .header("User-Agent", "$repo-windows-updater")
                        .header("Accept", "application/octet-stream, */*")
                    if (existingBytes > 0L) {
                        reqBuilder.header("Range", "bytes=$existingBytes-")
                    }

                    val ioErr = runCatching {
                        downloadHttp.newCall(reqBuilder.build()).execute().use { resp ->
                            if (resp.code == 416) {
                                partFile.delete()
                                throw IOException("断点范围失效，正在重新下载")
                            }
                            if (!resp.isSuccessful) {
                                throw IOException("下载服务器返回 HTTP ${resp.code}")
                            }
                            val appendMode = resp.code == 206 && existingBytes > 0L
                            val body = resp.body ?: throw IOException("下载响应为空")
                            val contentLen = body.contentLength()
                            if (expectedTotal <= 0L && contentLen > 0L) {
                                expectedTotal = if (appendMode) existingBytes + contentLen else contentLen
                            }
                            var done = if (appendMode) existingBytes else 0L
                            var lastUiMs = 0L
                            var speedWindowBytes = 0L
                            var speedWindowStart = System.currentTimeMillis()
                            var speedText = ""

                            body.byteStream().use { ins ->
                                FileOutputStream(partFile, appendMode).use { fos ->
                                    val buf = ByteArray(128 * 1024)
                                    while (true) {
                                        if (cancelled.get()) throw InterruptedException()
                                        val n = ins.read(buf)
                                        if (n < 0) break
                                        fos.write(buf, 0, n)
                                        done += n
                                        speedWindowBytes += n
                                        val now = System.currentTimeMillis()
                                        val elapsed = now - speedWindowStart
                                        if (elapsed >= 600L) {
                                            val bps = speedWindowBytes * 1000.0 / elapsed
                                            speedText = " · %.1f MB/s".format(bps / 1048576.0)
                                            speedWindowBytes = 0L
                                            speedWindowStart = now
                                        }
                                        if (now - lastUiMs >= 120L || (expectedTotal > 0 && done == expectedTotal)) {
                                            lastUiMs = now
                                            val pct = if (expectedTotal > 0) ((done * 100L) / expectedTotal).toInt().coerceIn(0, 100) else -1
                                            val mbDone = done / 1048576.0
                                            val mbTotal = if (expectedTotal > 0) "/ %.1f MB".format(expectedTotal / 1048576.0) else ""
                                            progressUi?.update(
                                                pct,
                                                "正在下载 ${rel.fileName}（%.1f MB%s%s）".format(mbDone, mbTotal, speedText),
                                            )
                                        }
                                    }
                                    fos.flush()
                                    runCatching { fos.fd.sync() }
                                }
                            }
                        }
                    }.exceptionOrNull()

                    if (ioErr is InterruptedException || cancelled.get()) throw InterruptedException()

                    // 检查是否已完整下载
                    if (ioErr == null && (expectedTotal <= 0L || partFile.length() == expectedTotal)) {
                        break
                    }

                    attempt++
                    if (attempt >= maxAttempts) {
                        throw IOException(
                            ioErr?.message ?: "下载中断，已接收 ${partFile.length() / 1024} KB / 预期 ${expectedTotal / 1024} KB",
                            ioErr,
                        )
                    }
                    progressUi?.update(-1, "网络波动，正在自动续传重试 ($attempt/$maxAttempts)…")
                    Thread.sleep(1200L * attempt)
                }

                // 严格校验文件完整性与二进制头魔数，防止残缺文件导致“无法打开程序”
                validateDownloadedInstaller(partFile, expectedTotal)

                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }
                unblockWindowsZoneIdentifier(targetFile)
                targetFile
            }

            progressUi?.dismiss()

            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                result.onSuccess { file ->
                    install(activity, file, rel)
                }.onFailure { e ->
                    if (e is InterruptedException) {
                        Toast.makeText(activity, "已取消下载更新", Toast.LENGTH_SHORT).show()
                        return@onFailure
                    }
                    AlertDialog.Builder(activity)
                        .setTitle("下载更新未完成")
                        .setMessage(
                            (e.message ?: e.javaClass.simpleName) +
                                "\n\n可点击「重试续传」接着刚才的进度继续下载，或前往网页手动下载。",
                        )
                        .setPositiveButton("重试续传") { _, _ -> download(activity, rel) }
                        .setNeutralButton("去网页下载") { _, _ -> openPage(activity, rel.page) }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }
        }, "Voxtitle-UpdateDownloader").apply { isDaemon = true; start() }
    }

    private fun install(activity: Activity, file: File, rel: Release) {
        val ext = file.extension.lowercase()
        if (ext !in setOf("exe", "msi", "zip")) {
            openExplorerSelect(file)
            return
        }

        SwingUtilities.invokeLater {
            val isPortableZip = ext == "zip"
            val options = arrayOf(
                if (isPortableZip) "立即替换并重启更新" else "立即退出并安装更新",
                "打开安装包所在文件夹",
                "稍后手动安装",
            )
            val msg = buildString {
                append("新版本 ${rel.tag} 安装包已下载并校验完整：\n")
                append(file.absolutePath)
                append("\n\n")
                if (isPortableZip) {
                    append("点击「立即替换并重启更新」将自动关闭译幕、覆盖更新文件并重新启动。")
                } else {
                    append("Windows 安装程序更新时需要释放当前正在运行的译幕主程序与组件文件。\n")
                    append("点击「立即退出并安装更新」将自动关闭当前窗口并启动安装向导。")
                }
            }
            val choice = JOptionPane.showOptionDialog(
                null,
                msg,
                "更新准备就绪 (${rel.tag})",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                options,
                options[0],
            )
            when (choice) {
                0 -> launchWindowsInstallerAndExit(activity, file)
                1 -> openExplorerSelect(file)
                else -> Toast.makeText(activity, "安装包已保存在：${file.parentFile?.absolutePath}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchWindowsInstallerAndExit(activity: Activity, installerFile: File) {
        val ext = installerFile.extension.lowercase()
        val currentPid = Process.myPid()
        val appDir = detectRunningAppDir()
        val currentExe = appDir?.let { File(it, "Voxtitle.exe").takeIf(File::isFile) }
        val tmpDir = File(System.getProperty("java.io.tmpdir", ".")).apply { mkdirs() }

        val launched = runCatching {
            val psScript = buildWindowsUpdatePsScript(
                ext = ext,
                installerPath = installerFile.absolutePath,
                currentPid = currentPid,
                appDirPath = appDir?.absolutePath,
                relaunchExePath = currentExe?.absolutePath,
            )
            val encoded = java.util.Base64.getEncoder().encodeToString(psScript.toByteArray(Charsets.UTF_16LE))
            ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-WindowStyle",
                "Hidden",
                "-EncodedCommand",
                encoded,
            )
                // 工作目录设为 %TEMP%，避免子进程锁定当前应用安装目录
                .directory(tmpDir)
                .start()
            true
        }.getOrElse { err ->
            // 兜底：若精简版系统缺失 PowerShell，退回 msiexec / cmd start 或资源管理器定位
            val fallbackOk = runCatching {
                when (ext) {
                    "msi" -> ProcessBuilder("msiexec.exe", "/i", installerFile.absolutePath)
                        .directory(tmpDir)
                        .start()
                    "exe" -> ProcessBuilder("cmd.exe", "/c", "start", "", installerFile.absolutePath)
                        .directory(tmpDir)
                        .start()
                    else -> ProcessBuilder("explorer.exe", "/select,${installerFile.absolutePath}")
                        .directory(tmpDir)
                        .start()
                }
                true
            }.getOrDefault(false)

            if (!fallbackOk) {
                openExplorerSelect(installerFile)
                Toast.makeText(
                    activity,
                    "自动拉起安装向导受阻（${err.message}），已为您打开安装包所在文件夹，请双击安装",
                    Toast.LENGTH_LONG,
                ).show()
            }
            fallbackOk
        }

        if (launched) {
            Toast.makeText(activity, "正在启动更新程序并关闭当前窗口…", Toast.LENGTH_SHORT).show()
            Thread({
                runCatching { activity.dispatchOnStop() }
                Thread.sleep(350L)
                exitProcess(0)
            }, "Voxtitle-UpdateExit").start()
        }
    }

    private fun openExplorerSelect(file: File) {
        runCatching {
            ProcessBuilder("explorer.exe", "/select,${file.absolutePath}").start()
        }.onFailure {
            runCatching {
                val parent = file.parentFile ?: return@runCatching
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(parent)
                }
            }
        }
    }

    private fun openPage(activity: Activity, url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    companion object {
        private const val PREFS = "app_update"
        private const val DIR = "updates"

        /**
         * 生成 UTF-16LE `-EncodedCommand` 专用 PowerShell 更新脚本：
         * 彻底规避中文用户名/空格/括号路径在 `cmd.exe` 代码页下的乱码报错，等待主进程退出释放文件锁后执行安装。
         */
        internal fun buildWindowsUpdatePsScript(
            ext: String,
            installerPath: String,
            currentPid: Int,
            appDirPath: String?,
            relaunchExePath: String?,
        ): String {
            fun psQuote(s: String): String = "'" + s.replace("'", "''") + "'"
            return buildString {
                appendLine("\$ErrorActionPreference = 'Stop'")
                appendLine("\$installer = ${psQuote(installerPath)}")
                if (currentPid > 0) {
                    appendLine("try { Wait-Process -Id $currentPid -Timeout 30 -ErrorAction SilentlyContinue } catch {}")
                    appendLine("Start-Sleep -Milliseconds 400")
                } else {
                    appendLine("Start-Sleep -Seconds 2")
                }
                appendLine("try { Unblock-File -LiteralPath \$installer -ErrorAction SilentlyContinue } catch {}")
                when (ext.lowercase()) {
                    "msi" -> {
                        appendLine("Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', ('\"' + \$installer + '\"'))")
                    }
                    "exe" -> {
                        appendLine("try {")
                        appendLine("  Start-Process -FilePath \$installer")
                        appendLine("} catch {")
                        appendLine("  Start-Process -FilePath 'explorer.exe' -ArgumentList ('/select,\"' + \$installer + '\"')")
                        appendLine("}")
                    }
                    "zip" -> {
                        if (!appDirPath.isNullOrBlank()) {
                            appendLine("\$targetDir = ${psQuote(appDirPath)}")
                            appendLine("\$stageDir = Join-Path ([System.IO.Path]::GetTempPath()) ('voxtitle_stage_' + [Guid]::NewGuid().ToString('N'))")
                            appendLine("try {")
                            appendLine("  Expand-Archive -LiteralPath \$installer -DestinationPath \$stageDir -Force")
                            appendLine("  \$entries = @(Get-ChildItem -LiteralPath \$stageDir -Force)")
                            appendLine("  \$copySrc = if (\$entries.Count -eq 1 -and \$entries[0].PSIsContainer) { \$entries[0].FullName } else { \$stageDir }")
                            appendLine("  Copy-Item -Path (Join-Path \$copySrc '*') -Destination \$targetDir -Recurse -Force")
                            if (!relaunchExePath.isNullOrBlank()) {
                                appendLine("  \$exe = ${psQuote(relaunchExePath)}")
                                appendLine("  if (Test-Path -LiteralPath \$exe) { Start-Process -FilePath \$exe }")
                            }
                            appendLine("} catch {")
                            appendLine("  Start-Process -FilePath 'explorer.exe' -ArgumentList ('/select,\"' + \$installer + '\"')")
                            appendLine("} finally {")
                            appendLine("  Remove-Item -LiteralPath \$stageDir -Recurse -Force -ErrorAction SilentlyContinue")
                            appendLine("}")
                        } else {
                            appendLine("Start-Process -FilePath 'explorer.exe' -ArgumentList ('/select,\"' + \$installer + '\"')")
                        }
                    }
                    else -> {
                        appendLine("Start-Process -FilePath 'explorer.exe' -ArgumentList ('/select,\"' + \$installer + '\"')")
                    }
                }
            }
        }

        /** 检测当前运行目录是否为便携版或安装版目录（包含 Voxtitle.exe 与 app 子目录） */
        internal fun detectRunningAppDir(): File? {
            val jpackageAppPath = System.getProperty("jpackage.app-path")
            if (!jpackageAppPath.isNullOrBlank()) {
                val exe = File(jpackageAppPath)
                if (exe.isFile) return exe.parentFile
            }
            val userDir = File(System.getProperty("user.dir", "."))
            if (File(userDir, "Voxtitle.exe").isFile) return userDir
            val parent = userDir.parentFile
            if (parent != null && File(parent, "Voxtitle.exe").isFile) return parent
            return null
        }

        /** 清理 Windows 从互联网下载文件附加的 NTFS Zone.Identifier 标记，减少系统拦截导致的打不开 */
        internal fun unblockWindowsZoneIdentifier(file: File) {
            runCatching {
                val ads = File(file.absolutePath + ":Zone.Identifier")
                if (ads.exists()) ads.delete()
            }
        }

        /** 校验已存在的安装包是否完整有效 */
        internal fun isValidInstallerFile(file: File, expectedSize: Long): Boolean =
            runCatching { validateDownloadedInstaller(file, expectedSize); true }.getOrDefault(false)

        /** 严格检查下载的安装包大小与二进制文件头魔数（PE `MZ` / MSI OLE `D0CF11E0` / ZIP `PK\x03\x04`） */
        internal fun validateDownloadedInstaller(file: File, expectedSize: Long) {
            if (!file.isFile) throw IllegalStateException("未找到下载的安装包文件")
            val actual = file.length()
            if (expectedSize > 0L && actual != expectedSize) {
                throw IllegalStateException(
                    "安装包未下载完整（已下载 %.1f MB / 应为 %.1f MB）".format(actual / 1048576.0, expectedSize / 1048576.0),
                )
            }
            if (actual < 512 * 1024L) {
                throw IllegalStateException("下载的文件体积异常（仅 ${actual / 1024} KB），可能被网关拦截")
            }
            val header = ByteArray(8)
            val n = FileInputStream(file).use { it.read(header) }
            if (n < 4) throw IllegalStateException("安装包无法读取")

            val ext = file.extension.lowercase()
            val validMagic = when (ext) {
                "exe" -> header[0] == 0x4D.toByte() && header[1] == 0x5A.toByte() // "MZ"
                "msi" -> n >= 8 &&
                    header[0] == 0xD0.toByte() && header[1] == 0xCF.toByte() &&
                    header[2] == 0x11.toByte() && header[3] == 0xE0.toByte() // OLE2 Compound Document
                "zip" -> header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() && header[3] == 0x04.toByte() // "PK\x03\x04"
                else -> true
            }
            if (!validMagic) {
                file.delete()
                throw IllegalStateException("下载的安装包格式已损坏或被网络劫持（非有效的 ${ext.uppercase()} 程序）")
            }
        }

        /** 版本号里的数字依次比较：v1.10.0 > 1.9.2 */
        fun isNewer(remote: String, current: String): Boolean {
            val a = Regex("\\d+").findAll(remote).map { it.value.toLong() }.toList()
            val b = Regex("\\d+").findAll(current).map { it.value.toLong() }.toList()
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        val abis: List<String> get() = Build.SUPPORTED_ABIS.toList()

        fun latestNotes(notes: String): String = notesSince(notes, null)

        fun notesSince(notes: String, current: String?): String {
            val sections = ArrayList<Pair<String, List<String>>>()
            var version: String? = null
            var body = ArrayList<String>()
            fun flush() {
                version?.let { sections += it to body }
                version = null
                body = ArrayList()
            }
            for (line in notes.lines()) {
                if (line.startsWith("## ")) {
                    flush()
                    version = VERSION_HEADING.find(line)?.groupValues?.get(1)
                } else if (version != null) body += line
            }
            flush()
            val picked = (if (current == null) emptyList() else sections.filter { isNewer(it.first, current) }).ifEmpty { sections.take(1) }
            val text = when {
                picked.isEmpty() -> notes
                picked.size == 1 -> picked[0].second.joinToString("\n")
                else -> picked.joinToString("\n\n") { (v, lines) -> "【$v】\n" + lines.joinToString("\n").trim() }
            }
            return text
                .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
                .replace("**", "").replace("__", "").replace("`", "")
                .replace(Regex("""(?m)^\s*[-*]\s+"""), "• ")
                .replace(Regex("""(?m)^#+\s*"""), "")
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
        }

        private val VERSION_HEADING = Regex("""^##\s*(?:本版更新\s*[（(]\s*)?v?(\d+\.\d+\.\d+)""")
    }
}

/**
 * Windows 端更新资源选择策略：
 * - 若当前为免安装便携目录（且目录可写、不在 Program Files），或 Release 中仅有便携包时支持 `.msi` / `-Setup.exe` / `-portable.zip`；
 * - 优先挑选原生 MSI / Setup.exe 安装包（覆盖升级快捷方式与注册表版本号），缺失时回退至 portable.zip 自动热替换。
 */
val appUpdater = AppUpdater(
    repo = "clusflah/ai-subtitle-player",
    pickAsset = { names ->
        names.firstOrNull { it.startsWith("Voxtitle-") && it.endsWith("-windows-x64-Setup.exe") }
            ?: names.firstOrNull { it.startsWith("Voxtitle-") && it.endsWith("-windows-x64.msi") }
            ?: names.firstOrNull { it.startsWith("Voxtitle-") && it.endsWith("-Setup.exe") }
            ?: names.firstOrNull { it.startsWith("Voxtitle-") && it.endsWith(".msi") }
            ?: names.firstOrNull { it.startsWith("Voxtitle-") && it.endsWith("-windows-x64-portable.zip") }
            ?: names.firstOrNull { it.startsWith("Voxtitle-") && it.endsWith(".exe") }
    },
)
