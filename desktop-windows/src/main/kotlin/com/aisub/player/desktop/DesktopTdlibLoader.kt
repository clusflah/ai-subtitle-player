package com.aisub.player.desktop

import android.content.Context
import com.aisub.player.net.AppProxy
import com.aisub.player.net.ProxyType
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Windows 桌面端 TDLib (`tdjni.dll` 及依赖库 OpenSSL / zlib / VC++ 运行库) 加载与按需安装管理器：
 * 1. 优先从安装包内置资源 `/tdlib/native/win-x64/` 解压并按拓扑顺序加载，开箱即用；
 * 2. 兼容本地自定义导入目录、`%LOCALAPPDATA%/Voxtitle/bin/tdlib` 与程序运行目录；
 * 3. 若开发构建未内置 `tdjni.dll`，支持一键在线下载组件包或导入本地编译的 `tdjni.dll` / `.zip`；
 * 4. 自动检测 Windows 系统代理（Clash / v2rayN 等 `HKCU\...\Internet Settings`）以供 TDLib 连接 Telegram。
 */
object DesktopTdlibLoader {
    const val TDLIB_TAG = "tdlib-1.8.69-4c6377aa73"
    const val WIN_ASSET_NAME = "tdjni-windows-x64.zip"
    const val DEFAULT_REPO = "clusflah/ai-subtitle-player"

    private val KNOWN_RESOURCES = listOf(
        "vcruntime140.dll",
        "vcruntime140_1.dll",
        "msvcp140.dll",
        "msvcp140_1.dll",
        "msvcp140_2.dll",
        "concrt140.dll",
        "zlib1.dll",
        "libcrypto-3-x64.dll",
        "libssl-3-x64.dll",
        "libcrypto-1_1-x64.dll",
        "libssl-1_1-x64.dll",
        "legacy.dll",
        "tdjni.dll",
    )

    @Volatile
    private var loaded = false

    @Volatile
    private var lastLoadError: String? = null

    fun isLoaded(): Boolean = loaded

    fun lastError(): String? = lastLoadError

    private interface Win32DllDir : Library {
        fun SetDllDirectoryW(lpPathName: WString?): Boolean

        companion object {
            val INSTANCE: Win32DllDir? = runCatching {
                Native.load("kernel32", Win32DllDir::class.java)
            }.getOrNull()
        }
    }

    private fun bundledExtractDir(): File =
        File(Context.appDataRoot(), "bin/$TDLIB_TAG").apply { mkdirs() }

    private fun customImportDir(): File =
        File(Context.appDataRoot(), "bin/tdlib-custom").apply { mkdirs() }

    @Synchronized
    fun ensureLoaded(): Result<Unit> {
        if (loaded) return Result.success(Unit)

        // 1. 尝试从内置 classpath 资源 (/tdlib/native/win-x64/) 提取
        extractBundledResources()?.let { dir ->
            val res = loadFromDirectory(dir)
            if (res.isSuccess) return res
        }

        // 2. 尝试从用户手动导入目录、已下载缓存目录、应用运行目录查找
        val candidateDirs = buildList {
            add(customImportDir())
            add(bundledExtractDir())
            add(File(DesktopBinaryFinder.binDir, "tdlib"))
            add(DesktopBinaryFinder.binDir)
            val appPath = System.getProperty("jpackage.app-path")
            if (!appPath.isNullOrBlank()) {
                File(appPath).parentFile?.let {
                    add(it)
                    add(File(it, "app"))
                }
            }
            add(File(System.getProperty("user.dir", ".")))
        }.distinctBy { it.absolutePath }

        for (dir in candidateDirs) {
            val tdjni = File(dir, "tdjni.dll")
            if (tdjni.isFile && tdjni.length() > 50_000L) {
                val res = loadFromDirectory(dir)
                if (res.isSuccess) return res
            }
        }

        // 3. 最后尝试系统 PATH 中的 tdjni.dll
        val sysTry = runCatching {
            System.loadLibrary("tdjni")
            loaded = true
        }
        if (sysTry.isSuccess) return Result.success(Unit)

        val err = lastLoadError ?: sysTry.exceptionOrNull()?.message ?: "未找到 tdjni.dll"
        lastLoadError = err
        return Result.failure(UnsatisfiedLinkError(err))
    }

    private fun extractBundledResources(): File? {
        val cl = DesktopTdlibLoader::class.java.classLoader ?: return null
        val prefix = "tdlib/native/win-x64"
        if (cl.getResource("$prefix/tdjni.dll") == null) return null

        val outDir = bundledExtractDir()
        val manifestNames = runCatching {
            cl.getResourceAsStream("$prefix/manifest.txt")
                ?.bufferedReader(Charsets.UTF_8)
                ?.readLines()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && !it.startsWith("#") && it.endsWith(".dll", ignoreCase = true) }
                .orEmpty()
        }.getOrDefault(emptyList())

        val namesToExtract = (manifestNames + KNOWN_RESOURCES).distinct()
        for (name in namesToExtract) {
            val stream = cl.getResourceAsStream("$prefix/$name") ?: continue
            val target = File(outDir, name)
            if (target.isFile && target.length() > 4096L) {
                stream.close()
                continue
            }
            val tmp = File(outDir, "$name.tmp")
            stream.use { ins ->
                tmp.outputStream().use { outs -> ins.copyTo(outs) }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        return outDir.takeIf { File(it, "tdjni.dll").isFile }
    }

    @Synchronized
    private fun loadFromDirectory(dir: File): Result<Unit> {
        if (loaded) return Result.success(Unit)
        val tdjni = File(dir, "tdjni.dll")
        if (!tdjni.isFile) return Result.failure(IOException("目录中不存在 tdjni.dll: ${dir.absolutePath}"))

        return runCatching {
            // 将该目录加入 Win32 DLL 搜索路径，确保 tdjni.dll 的隐式依赖全部解析成功
            runCatching { Win32DllDir.INSTANCE?.SetDllDirectoryW(WString(dir.absolutePath)) }

            val allDlls = dir.listFiles { f ->
                f.isFile && f.name.endsWith(".dll", ignoreCase = true) && !f.name.equals("tdjni.dll", ignoreCase = true)
            }?.toList().orEmpty()

            fun priorityOf(name: String): Int {
                val low = name.lowercase()
                return when {
                    low.startsWith("vcruntime") -> 10
                    low.startsWith("msvcp") || low.startsWith("concrt") -> 20
                    low.startsWith("zlib") -> 30
                    low.startsWith("libcrypto") -> 40
                    low.startsWith("libssl") -> 50
                    else -> 60
                }
            }

            for (dep in allDlls.sortedBy { priorityOf(it.name) }) {
                runCatching { System.load(dep.absolutePath) }
            }
            System.load(tdjni.absolutePath)
            loaded = true
            lastLoadError = null
        }.onFailure { e ->
            lastLoadError = e.message ?: e.javaClass.simpleName
        }
    }

    /**
     * 在线下载预编译的 `tdjni-windows-x64.zip` 并立即加载（自动走应用内/系统代理）。
     */
    fun downloadAndInstall(
        repo: String = DEFAULT_REPO,
        onProgress: ((String) -> Unit)? = null,
    ): Result<Unit> = runCatching {
        if (loaded) return@runCatching
        val http = AppProxy.builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val candidateUrls = ArrayList<String>()
        candidateUrls += "https://github.com/$repo/releases/download/$TDLIB_TAG/$WIN_ASSET_NAME"

        // 若当前 tag 下尚未上传，顺带查询仓库 releases 中是否有 tdjni-windows-x64.zip
        runCatching {
            val apiReq = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases?per_page=15")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "$repo-tdlib-fetcher")
                .build()
            http.newCall(apiReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    for (i in 0 until arr.length()) {
                        val assets = arr.getJSONObject(i).optJSONArray("assets") ?: continue
                        for (j in 0 until assets.length()) {
                            val a = assets.getJSONObject(j)
                            if (a.optString("name").equals(WIN_ASSET_NAME, ignoreCase = true)) {
                                val u = a.optString("browser_download_url")
                                if (u.isNotBlank() && u !in candidateUrls) candidateUrls += u
                            }
                        }
                    }
                }
            }
        }

        val outDir = bundledExtractDir()
        val tmpZip = File(outDir, "$WIN_ASSET_NAME.part")
        var downloaded = false
        var lastHttpErr: String? = null

        for (url in candidateUrls) {
            onProgress?.invoke("正在下载桌面端 TDLib 组件…")
            val ok = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "$repo-tdlib-fetcher")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("空响应")
                    val total = body.contentLength()
                    var done = 0L
                    body.byteStream().use { ins ->
                        tmpZip.outputStream().use { fos ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                fos.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100L / total).toInt().coerceIn(0, 100)
                                    onProgress?.invoke("正在下载 TDLib 组件 ($pct%)…")
                                }
                            }
                        }
                    }
                }
                true
            }.onFailure { lastHttpErr = it.message }.getOrDefault(false)

            if (ok && tmpZip.isFile && tmpZip.length() > 50_000L) {
                downloaded = true
                break
            }
        }

        if (!downloaded) {
            tmpZip.delete()
            throw IOException(
                "未能在 GitHub Releases 中下载到 $WIN_ASSET_NAME（${lastHttpErr ?: "404"}）。" +
                    "请通过 GitHub Actions 运行一次 Build TDLib / Build & Release 自动构建打包，或点击「导入本地文件」选择本地 tdjni.dll。",
            )
        }

        onProgress?.invoke("正在解压并加载 TDLib 组件…")
        extractDllZipToDir(tmpZip, outDir)
        tmpZip.delete()
        ensureLoaded().getOrThrow()
    }

    /**
     * 手动导入本地 `tdjni.dll`（自动复制同目录依赖 DLL）或包含 `tdjni.dll` 的 `.zip` 压缩包。
     */
    fun importFromLocalFile(selected: File): Result<Unit> = runCatching {
        if (!selected.isFile) throw IOException("所选文件不存在")
        val destDir = customImportDir()
        if (selected.extension.equals("zip", ignoreCase = true)) {
            extractDllZipToDir(selected, destDir)
        } else if (selected.name.equals("tdjni.dll", ignoreCase = true) || selected.extension.equals("dll", ignoreCase = true)) {
            val srcDir = selected.parentFile
            if (srcDir != null && srcDir.isDirectory) {
                srcDir.listFiles { f -> f.isFile && f.name.endsWith(".dll", ignoreCase = true) }?.forEach { dll ->
                    val dst = File(destDir, dll.name)
                    dll.copyTo(dst, overwrite = true)
                    runCatching { File(dst.absolutePath + ":Zone.Identifier").takeIf(File::exists)?.delete() }
                }
            } else {
                selected.copyTo(File(destDir, "tdjni.dll"), overwrite = true)
            }
        } else {
            throw IOException("请选择 tdjni.dll 或包含 tdjni.dll 的 .zip 压缩包")
        }
        if (!File(destDir, "tdjni.dll").isFile) {
            throw IOException("未在所选文件中找到 tdjni.dll")
        }
        loadFromDirectory(destDir).getOrThrow()
    }

    private fun extractDllZipToDir(zipFile: File, destDir: File) {
        destDir.mkdirs()
        ZipFile(zipFile).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val baseName = e.name.replace('\\', '/').substringAfterLast('/')
                if (!baseName.endsWith(".dll", ignoreCase = true)) continue
                val out = File(destDir, baseName)
                zf.getInputStream(e).use { ins ->
                    out.outputStream().use { outs -> ins.copyTo(outs) }
                }
                runCatching { File(out.absolutePath + ":Zone.Identifier").takeIf(File::exists)?.delete() }
            }
        }
    }

    /**
     * 读取 Windows 系统级代理配置（如 Clash / v2rayN 开启的「系统代理」127.0.0.1:7890），
     * 当应用内代理设为「不使用」时自动同步给 TDLib，避免桌面端 Telegram 卡在连接中。
     */
    fun detectWindowsSystemProxy(): Triple<ProxyType, String, Int>? {
        return runCatching {
            val regPath = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
            val enabled = Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, regPath, "ProxyEnable")
            if (enabled == 0) return@runCatching null
            val raw = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, regPath, "ProxyServer").trim()
            if (raw.isEmpty()) return@runCatching null

            // 支持两种格式：
            // 1) "127.0.0.1:7890" 或 "http://127.0.0.1:7890" 或 "socks5://127.0.0.1:1080"
            // 2) "socks=127.0.0.1:7890;http=127.0.0.1:7890;https=127.0.0.1:7890"
            var chosenType = ProxyType.HTTP
            var hostPort = raw
            if (raw.contains('=')) {
                val map = raw.split(';')
                    .mapNotNull { part ->
                        val idx = part.indexOf('=')
                        if (idx <= 0) null else part.substring(0, idx).trim().lowercase() to part.substring(idx + 1).trim()
                    }.toMap()
                when {
                    !map["socks"].isNullOrBlank() -> {
                        chosenType = ProxyType.SOCKS
                        hostPort = map.getValue("socks")
                    }
                    !map["https"].isNullOrBlank() -> {
                        chosenType = ProxyType.HTTP
                        hostPort = map.getValue("https")
                    }
                    !map["http"].isNullOrBlank() -> {
                        chosenType = ProxyType.HTTP
                        hostPort = map.getValue("http")
                    }
                    else -> return@runCatching null
                }
            } else {
                val low = hostPort.lowercase()
                if (low.startsWith("socks5://") || low.startsWith("socks://")) {
                    chosenType = ProxyType.SOCKS
                    hostPort = hostPort.substringAfter("://")
                } else if (low.startsWith("http://") || low.startsWith("https://")) {
                    chosenType = ProxyType.HTTP
                    hostPort = hostPort.substringAfter("://")
                }
            }
            hostPort = hostPort.substringBefore('/')
            val colon = hostPort.lastIndexOf(':')
            if (colon <= 0) return@runCatching null
            val host = hostPort.substring(0, colon).trim()
            val port = hostPort.substring(colon + 1).trim().toIntOrNull() ?: return@runCatching null
            if (host.isEmpty() || port !in 1..65535) return@runCatching null
            Triple(chosenType, host, port)
        }.getOrNull()
    }
}
