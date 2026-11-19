package com.aisub.player.sync

import android.content.Context
import android.content.SharedPreferences
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.aisub.player.backup.PrefsCodec
import com.aisub.player.media.CustomMediaLibraryStore
import com.aisub.player.net.AppProxy
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.Server
import com.aisub.player.remote.cloud.CloudRegistry
import com.aisub.player.ui.PhoneFormServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 多端同步配置（支持 WebDAV 云同步 + 局域网双向直连同步，覆盖 Android 手机 / 电视 / Windows 桌面端） */
data class SyncConfig(
    val autoSync: Boolean = false,
    val webdavUrl: String = "",
    val webdavUser: String = "",
    val webdavPass: String = "",
    val syncHistoryAndProgress: Boolean = true,
    val syncMediaLibraries: Boolean = true,
    val syncServersAndLive: Boolean = true,
    val syncSettingsAndKeys: Boolean = false,
    val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    val lastSyncAt: Long = 0L,
    val lastSyncSummary: String = "",
) {
    val hasWebDavConfigured: Boolean
        get() = webdavUrl.trim().let { it.startsWith("http://") || it.startsWith("https://") }
}

data class SyncRuntimeState(
    val syncing: Boolean = false,
    val statusMessage: String? = null,
    val error: String? = null,
    val lastSyncAt: Long = 0L,
)

data class LanSyncPeer(val name: String, val address: String)

/**
 * 跨平台多端双向合并同步引擎（Android 手机 / Android TV / Windows 桌面）：
 * 1. 按时间戳智能双向合并（非破坏性合并，无需重启应用立即热刷新内存状态）：
 *    - 观看历史与播放进度（网络/网盘/媒体库流按 `playedAt` 取最新播放位置，支持跨端接着看）；
 *    - 首页自定义媒体库及刮削海报墙元数据（一端刮削，全端共享海报墙）；
 *    - 网盘、NAS、Emby / Jellyfin / Plex 媒体服务器与直播源、收藏夹、播放列表、漫画/电子书阅读记录；
 *    - 可选同步应用设置与大模型 API 密钥；
 * 2. 支持两类传输通道：
 *    - **WebDAV 云端自动/手动同步**（支持坚果云、群晖 NAS、OpenList/AList `/dav`、Nextcloud 等，自动在退出播放或启动时后台同步）；
 *    - **局域网零配置双向对等同步**（NSD `_voxtitle-sync._tcp.` 广播发现或手动输入 `IP:端口`，一次握手双向合并两台设备的最新进度与媒体库）。
 */
object MultiDeviceSync {
    private const val PREFS = "multi_sync"
    private const val FORMAT = "voxtitle-multisync"
    private const val VERSION = 1
    private const val REMOTE_DIR = "VoxtitleSync"
    private const val REMOTE_FILE = "sync_state.json"
    private const val LAN_SERVICE_TYPE = "_voxtitle-sync._tcp."
    private const val MAX_SYNC_BYTES = 24 * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private var autoPushJob: Job? = null

    private val http = AppProxy.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(SyncRuntimeState())
    val state: StateFlow<SyncRuntimeState> = _state.asStateFlow()

    /** 当同步合并落盘后通知 UI 层刷新 ViewModel（由 MainActivity 注册） */
    @Volatile
    var onSyncedReload: (() -> Unit)? = null

    fun loadConfig(context: Context): SyncConfig {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaultName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Voxtitle Device" }
        return SyncConfig(
            autoSync = p.getBoolean("autoSync", false),
            webdavUrl = p.getString("webdavUrl", "")!!,
            webdavUser = p.getString("webdavUser", "")!!,
            webdavPass = p.getString("webdavPass", "")!!,
            syncHistoryAndProgress = p.getBoolean("syncHistoryAndProgress", true),
            syncMediaLibraries = p.getBoolean("syncMediaLibraries", true),
            syncServersAndLive = p.getBoolean("syncServersAndLive", true),
            syncSettingsAndKeys = p.getBoolean("syncSettingsAndKeys", false),
            deviceName = p.getString("deviceName", defaultName)!!.ifBlank { defaultName },
            lastSyncAt = p.getLong("lastSyncAt", 0L),
            lastSyncSummary = p.getString("lastSyncSummary", "")!!,
        ).also { cfg ->
            if (_state.value.lastSyncAt == 0L && cfg.lastSyncAt > 0L) {
                _state.update { it.copy(lastSyncAt = cfg.lastSyncAt, statusMessage = cfg.lastSyncSummary.ifBlank { null }) }
            }
        }
    }

    fun saveConfig(context: Context, cfg: SyncConfig) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit()
            .putBoolean("autoSync", cfg.autoSync)
            .putString("webdavUrl", cfg.webdavUrl.trim())
            .putString("webdavUser", cfg.webdavUser.trim())
            .putString("webdavPass", cfg.webdavPass)
            .putBoolean("syncHistoryAndProgress", cfg.syncHistoryAndProgress)
            .putBoolean("syncMediaLibraries", cfg.syncMediaLibraries)
            .putBoolean("syncServersAndLive", cfg.syncServersAndLive)
            .putBoolean("syncSettingsAndKeys", cfg.syncSettingsAndKeys)
            .putString("deviceName", cfg.deviceName.trim())
            .putLong("lastSyncAt", cfg.lastSyncAt)
            .putString("lastSyncSummary", cfg.lastSyncSummary)
            .apply()
    }

    private fun deviceId(context: Context): String {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("deviceId", it).apply()
        }
    }

    /** 应用启动时若开启了云端自动同步，后台拉取合并最新进度与媒体库 */
    fun triggerStartupSyncIfEnabled(context: Context) {
        val app = context.applicationContext
        CustomMediaLibraryStore.onDataChanged = { ctx -> scheduleDebouncedAutoSync(ctx) }
        val cfg = loadConfig(app)
        if (!cfg.autoSync || !cfg.hasWebDavConfigured) return
        scope.launch {
            runCatching { syncWithWebDav(app) }
        }
    }

    /** 播放位置保存、收藏变更或媒体库刮削完成时触发防抖后台自动同步 */
    fun scheduleDebouncedAutoSync(context: Context) {
        val app = context.applicationContext
        val cfg = loadConfig(app)
        if (!cfg.autoSync || !cfg.hasWebDavConfigured) return
        autoPushJob?.cancel()
        autoPushJob = scope.launch {
            delay(3_500L)
            runCatching { syncWithWebDav(app, silent = true) }
        }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, statusMessage = null) }
    }

    /** 列出 WebDAV 路径下的一级子目录名称（供根目录补全共享文件夹提示使用） */
    suspend fun listWebDavSubdirs(url: String, user: String, pass: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val cleanBase = url.trim().trimEnd('/')
        if (!cleanBase.startsWith("http://") && !cleanBase.startsWith("https://")) {
            return@withContext Result.failure(IOException("请填写以 http:// 或 https:// 开头的 WebDAV 地址"))
        }
        val auth = basicAuth(user.trim(), pass)
        runCatching {
            val req = Request.Builder()
                .url("$cleanBase/")
                .method("PROPFIND", PROPFIND_MIN.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Depth", "1")
                .apply { if (auth != null) header("Authorization", auth) }
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403 || (resp.code !in 200..299 && resp.code != 207)) {
                    throw IOException(explainWebDavError(cleanBase, resp.code, "读取目录"))
                }
                val xml = resp.body?.string().orEmpty()
                parseWebDavChildDirs(xml, cleanBase)
            }
        }
    }

    /** 测试 WebDAV 同步连接及目录可写性（依次验证：PROPFIND 连通性 -> 创建/确认同步目录 -> PUT 写入测试文件 -> DELETE 清理） */
    suspend fun testWebDavConnection(url: String, user: String, pass: String): String = withContext(Dispatchers.IO) {
        val cleanBase = url.trim().trimEnd('/')
        if (!cleanBase.startsWith("http://") && !cleanBase.startsWith("https://")) {
            return@withContext "请填写以 http:// 或 https:// 开头的 WebDAV 地址"
        }
        val auth = basicAuth(user.trim(), pass)
        runCatching {
            // 1. PROPFIND Depth:1 验证账号连通性，同时解析一级子目录以便在报错时给出精确路径建议
            val propReq = Request.Builder()
                .url("$cleanBase/")
                .method("PROPFIND", PROPFIND_MIN.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Depth", "1")
                .apply { if (auth != null) header("Authorization", auth) }
                .build()
            val childDirs = http.newCall(propReq).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403 || (resp.code !in 200..299 && resp.code != 207)) {
                    throw IOException(explainWebDavError(cleanBase, resp.code, "连接服务器"))
                }
                parseWebDavChildDirs(resp.body?.string().orEmpty(), cleanBase)
            }
            // 2. 确认同步目录存在或真实创建成功
            ensureRemoteDir(cleanBase, auth, childDirs)
            // 3. 写入并清理小文件，真实验证 PUT/DELETE 权限（避免根目录只读出现假阳性）
            val checkUrl = "$cleanBase/$REMOTE_DIR/.voxtitle_write_check"
            val putReq = Request.Builder()
                .url(checkUrl)
                .put("ok".toByteArray(Charsets.UTF_8).toRequestBody("text/plain; charset=utf-8".toMediaType()))
                .apply { if (auth != null) header("Authorization", auth) }
                .build()
            http.newCall(putReq).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 201 && resp.code != 204) {
                    throw IOException(explainWebDavError(cleanBase, resp.code, "写入测试文件", childDirs))
                }
            }
            val delReq = Request.Builder()
                .url(checkUrl)
                .delete()
                .apply { if (auth != null) header("Authorization", auth) }
                .build()
            runCatching { http.newCall(delReq).execute().close() }
            "连接成功，已验证可读写（同步路径：$cleanBase/$REMOTE_DIR/）"
        }.getOrElse { e ->
            "连接失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 执行一次完整的 WebDAV 双向合并同步（拉取远端 -> 时间戳合并 -> 本地热生效 -> 回写远端） */
    suspend fun syncWithWebDav(context: Context, silent: Boolean = false): String = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val cfg = loadConfig(app)
            if (!cfg.hasWebDavConfigured) throw IOException("请先配置 WebDAV 同步地址")
            if (!silent) {
                _state.update { it.copy(syncing = true, error = null, statusMessage = "正在与云端同步…") }
            }
            try {
                val base = cfg.webdavUrl.trim().trimEnd('/')
                val auth = basicAuth(cfg.webdavUser.trim(), cfg.webdavPass)
                ensureRemoteDir(base, auth)
                val fileUrl = "$base/$REMOTE_DIR/$REMOTE_FILE"

                // 1. 拉取远端已有状态（首次同步 404 视为远端空白）
                val getReq = Request.Builder()
                    .url(fileUrl)
                    .get()
                    .apply { if (auth != null) header("Authorization", auth) }
                    .build()
                val remoteJson: JSONObject? = http.newCall(getReq).execute().use { resp ->
                    when {
                        resp.code == 404 -> null
                        resp.code == 401 -> throw IOException(explainWebDavError(base, 401, "拉取同步文件"))
                        !resp.isSuccessful -> throw IOException(explainWebDavError(base, resp.code, "拉取云端同步文件"))
                        else -> resp.body?.string()?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
                    }
                }

                // 2. 与本机合并并热刷新本地存储
                var mergedSummary = "首次上传本机数据"
                if (remoteJson != null) {
                    mergedSummary = mergeSnapshotIntoLocal(app, remoteJson, cfg)
                }

                // 3. 重新导出合并后的全量状态上传至 WebDAV
                val mergedPayload = buildLocalSnapshot(app, cfg).toString()
                val putReq = Request.Builder()
                    .url(fileUrl)
                    .put(mergedPayload.toByteArray(Charsets.UTF_8).toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .apply { if (auth != null) header("Authorization", auth) }
                    .build()
                http.newCall(putReq).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 201 && resp.code != 204) {
                        val dirs = if (resp.code == 403 || resp.code == 405) fetchChildDirsQuietly(base, auth) else emptyList()
                        throw IOException(explainWebDavError(base, resp.code, "上传同步文件", dirs))
                    }
                }

                val now = System.currentTimeMillis()
                val summary = "WebDAV 同步完成（$mergedSummary）"
                saveConfig(app, cfg.copy(lastSyncAt = now, lastSyncSummary = summary))
                _state.update { it.copy(syncing = false, statusMessage = summary, error = null, lastSyncAt = now) }
                notifyReloadOnMain()
                summary
            } catch (e: Exception) {
                val errMsg = "同步失败：${e.message ?: e.javaClass.simpleName}"
                if (!silent) {
                    _state.update { it.copy(syncing = false, error = errMsg) }
                } else {
                    _state.update { it.copy(syncing = false) }
                }
                throw e
            }
        }
    }

    private fun dirExists(dirUrl: String, auth: String?): Boolean {
        val propReq = Request.Builder()
            .url(dirUrl)
            .method("PROPFIND", PROPFIND_MIN.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "0")
            .apply { if (auth != null) header("Authorization", auth) }
            .build()
        return runCatching {
            http.newCall(propReq).execute().use { resp ->
                if (resp.code == 401) throw IOException(explainWebDavError(dirUrl, 401, "检查同步目录"))
                resp.code in 200..299 || resp.code == 207
            }
        }.getOrElse { e ->
            if (e is IOException && e.message?.contains("401") == true) throw e
            false
        }
    }

    private fun fetchChildDirsQuietly(base: String, auth: String?): List<String> = runCatching {
        val req = Request.Builder()
            .url("$base/")
            .method("PROPFIND", PROPFIND_MIN.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .apply { if (auth != null) header("Authorization", auth) }
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code in 200..299 || resp.code == 207) {
                parseWebDavChildDirs(resp.body?.string().orEmpty(), base)
            } else {
                emptyList()
            }
        }
    }.getOrDefault(emptyList())

    private fun ensureRemoteDir(base: String, auth: String?, suggestedDirs: List<String> = emptyList()) {
        val dirUrl = "$base/$REMOTE_DIR/"
        if (dirExists(dirUrl, auth)) return
        val mkcolReq = Request.Builder()
            .url(dirUrl)
            .method("MKCOL", null)
            .apply { if (auth != null) header("Authorization", auth) }
            .build()
        http.newCall(mkcolReq).execute().use { resp ->
            if (resp.code in 200..299) return
            if (resp.code == 405 && dirExists(dirUrl, auth)) return
            val dirs = if (suggestedDirs.isEmpty() && (resp.code == 403 || resp.code == 405)) {
                fetchChildDirsQuietly(base, auth)
            } else {
                suggestedDirs
            }
            throw IOException(explainWebDavError(base, resp.code, "创建同步目录", dirs))
        }
    }

    internal fun explainWebDavError(
        baseUrl: String,
        code: Int,
        stage: String,
        suggestedDirs: List<String> = emptyList(),
    ): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val isRootPath = runCatching {
            java.net.URI(cleanBase).path.orEmpty().trim('/').isEmpty()
        }.getOrDefault(false)
        val exampleDir = suggestedDirs.firstOrNull { it.equals("home", ignoreCase = true) }
            ?: suggestedDirs.firstOrNull { it.equals("homes", ignoreCase = true) }
            ?: suggestedDirs.firstOrNull()
            ?: "home"
        return when (code) {
            401 -> "用户名或密码/应用密码错误 (HTTP 401)。若使用坚果云/Nextcloud，请确认填写的是「应用专用密码」而非登录密码"
            403 -> if (isRootPath) {
                "当前填写的是根目录且无写入权限 (HTTP 403)。请在地址末尾加上有读写权限的共享文件夹，例如：$cleanBase/$exampleDir"
            } else {
                "当前目录无写入权限 (HTTP 403)，请确认该 WebDAV 账号对目标文件夹拥有「读写」权限"
            }
            405 -> if (isRootPath) {
                "当前填写的是 WebDAV 根目录，群晖/飞牛/威联通等 NAS 根目录仅用于列出共享文件夹，不允许直接创建同步目录或上传文件 (HTTP 405)。请在地址末尾加上有读写权限的共享文件夹，例如：$cleanBase/$exampleDir（若是 OpenList/AList 请加上 /dav/挂载目录）"
            } else {
                "服务器拒绝在当前路径写入同步文件 (HTTP 405)。请检查：1) OpenList/AList 地址是否带 /dav 前缀且精确到可写挂载目录；2) NAS 共享文件夹是否为只读；3) 反向代理（Nginx/Lucky）是否放行了 MKCOL、PUT、DELETE 方法"
            }
            409 -> "上级目录不存在 (HTTP 409)，请先在网盘或 NAS 中创建对应文件夹，或检查路径拼写"
            else -> "${stage}失败 (HTTP $code)"
        }
    }

    internal fun parseWebDavChildDirs(xml: String, baseUrl: String): List<String> {
        if (xml.isBlank()) return emptyList()
        val baseRawPath = runCatching { java.net.URI(baseUrl.trim().trimEnd('/') + "/").rawPath }.getOrNull().orEmpty()
        val baseDecoded = decodeWebDavPath(baseRawPath).trimEnd('/')
        val out = LinkedHashSet<String>()
        for (match in WEBDAV_RESPONSE_REGEX.findAll(xml)) {
            val block = match.groupValues[1]
            if (!WEBDAV_COLLECTION_REGEX.containsMatchIn(block)) continue
            val rawHref = WEBDAV_HREF_REGEX.find(block)?.groupValues?.getOrNull(1)?.trim()
                ?.replace("&amp;", "&")
                .orEmpty()
            if (rawHref.isEmpty()) continue
            val hrefPath = if (rawHref.startsWith("http://", ignoreCase = true) || rawHref.startsWith("https://", ignoreCase = true)) {
                runCatching { java.net.URI(rawHref).rawPath.orEmpty() }.getOrDefault("")
            } else {
                rawHref.substringBefore('?')
            }
            val decoded = decodeWebDavPath(hrefPath).trimEnd('/')
            if (decoded == baseDecoded) continue
            val rel = when {
                baseDecoded.isEmpty() -> decoded.trimStart('/')
                decoded.startsWith("$baseDecoded/") -> decoded.removePrefix("$baseDecoded/")
                else -> continue
            }
            if (rel.isEmpty() || '/' in rel) continue
            if (rel == "." || rel == ".." || rel == "@eaDir" || rel == "#recycle" || rel == REMOTE_DIR) continue
            out += rel
        }
        return out.sortedBy { it.lowercase() }
    }

    private fun decodeWebDavPath(raw: String): String =
        runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)

    private val WEBDAV_RESPONSE_REGEX = Regex(
        "<(?:[A-Za-z0-9_]+:)?response\\b[^>]*>(.*?)</(?:[A-Za-z0-9_]+:)?response>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val WEBDAV_HREF_REGEX = Regex(
        "<(?:[A-Za-z0-9_]+:)?href\\b[^>]*>(.*?)</(?:[A-Za-z0-9_]+:)?href>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val WEBDAV_COLLECTION_REGEX = Regex("<(?:[A-Za-z0-9_]+:)?collection\\b", RegexOption.IGNORE_CASE)

    private fun basicAuth(user: String, pass: String): String? {
        if (user.isEmpty() && pass.isEmpty()) return null
        return okhttp3.Credentials.basic(user, pass, Charsets.UTF_8)
    }

    /** 构造本机当前同步快照 JSON */
    fun buildLocalSnapshot(context: Context, cfg: SyncConfig = loadConfig(context)): JSONObject {
        val app = context.applicationContext
        fun prefsMap(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE).all

        val payload = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("deviceId", deviceId(app))
            .put("deviceName", cfg.deviceName)
            .put("syncedAt", System.currentTimeMillis())

        // 1. 观看历史、播放进度、收藏夹、播放列表、片头片尾与阅读进度
        if (cfg.syncHistoryAndProgress) {
            val libPrefs = prefsMap("library")
            val remoteHistory = filterJsonArray(libPrefs["history"] as? String) { item ->
                val uri = item.optJSONObject("entry")?.optString("uri").orEmpty()
                !isDeviceLocalOnlyUri(uri)
            }
            val remotePlaylists = (libPrefs["playlists"] as? String)?.let { raw ->
                val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
                JSONArray(List(arr.length()) { arr.getJSONObject(it) }.map { p ->
                    val filteredItems = filterJsonArray(p.optJSONArray("items")?.toString()) {
                        !isDeviceLocalOnlyUri(it.optString("uri"))
                    }
                    JSONObject(p.toString()).put("items", filteredItems ?: JSONArray())
                })
            }
            val remoteFavFolders = filterJsonArray(libPrefs["favoriteFolders"] as? String) {
                it.optString("server").isNotBlank()
            }

            payload.put(
                "progress",
                JSONObject()
                    .put("history", remoteHistory ?: JSONArray())
                    .put("playlists", remotePlaylists ?: JSONArray())
                    .put("favoriteFolders", remoteFavFolders ?: JSONArray())
                    .put("playbackSkips", PrefsCodec.encode(prefsMap("playback").filterKeys {
                        it.startsWith("skip.file.") || it.startsWith("skip.series.") || it.startsWith("delay.live.")
                    }))
                    .put("webBookmarks", filterJsonArray(prefsMap("web")["bookmarks"] as? String) { true } ?: JSONArray()),
            )
        }

        // 2. 自定义媒体库与刮削海报墙目录
        if (cfg.syncMediaLibraries) {
            payload.put("customLibraries", CustomMediaLibraryStore.exportForSync(app))
        }

        // 3. 网络服务器（网盘 / WebDAV / Emby / Plex）与电视直播源
        if (cfg.syncServersAndLive) {
            val libPrefs = prefsMap("library")
            val livePrefs = prefsMap("live")
            payload.put(
                "sources",
                JSONObject()
                    .put("servers", parseJsonArray(libPrefs["servers"] as? String))
                    .put("liveSources", filterJsonArray(livePrefs["sources"] as? String) {
                        !isDeviceLocalOnlyUri(it.optString("url"))
                    } ?: JSONArray())
                    .put("liveFavorites", parseJsonArray(livePrefs["favorites"] as? String)),
            )
        }

        // 4. 可选：全局设置与大模型 API Key、搜索源、影片手动匹配覆盖
        if (cfg.syncSettingsAndKeys) {
            payload.put(
                "settingsBundle",
                JSONObject()
                    .put("updatedAt", System.currentTimeMillis())
                    .put("settings", PrefsCodec.encode(prefsMap("settings")))
                    .put("discover_search", PrefsCodec.encode(prefsMap("discover_search")))
                    .put("meta_overrides", PrefsCodec.encode(prefsMap("meta_overrides"))),
            )
        }

        return payload
    }

    /** 将远端/对端同步快照按时间戳非破坏性合并进本机存储，并返回简短统计说明 */
    fun mergeSnapshotIntoLocal(
        context: Context,
        remote: JSONObject,
        cfg: SyncConfig = loadConfig(context),
    ): String {
        if (remote.optString("format") != FORMAT) {
            throw IllegalArgumentException("无效的多端同步数据格式")
        }
        val app = context.applicationContext
        val peerDevice = remote.optString("deviceName").ifBlank { "其他设备" }
        val stats = ArrayList<String>()

        // 1. 合并观看进度、播放列表、收藏目录
        if (cfg.syncHistoryAndProgress) {
            remote.optJSONObject("progress")?.let { prog ->
                val libPrefs = app.getSharedPreferences("library", Context.MODE_PRIVATE)
                val libEdit = libPrefs.edit()

                prog.optJSONArray("history")?.let { inHist ->
                    val mergedHist = mergeHistoryByTimestamp(
                        parseJsonArray(libPrefs.getString("history", "[]")),
                        inHist,
                    )
                    libEdit.putString("history", mergedHist.toString())
                    if (inHist.length() > 0) stats += "${inHist.length()}条进度"
                }

                prog.optJSONArray("playlists")?.let { inPls ->
                    val mergedPls = mergePlaylists(
                        parseJsonArray(libPrefs.getString("playlists", "[]")),
                        inPls,
                    )
                    libEdit.putString("playlists", mergedPls.toString())
                }

                prog.optJSONArray("favoriteFolders")?.let { inFavs ->
                    val mergedFavs = mergeByKey(
                        parseJsonArray(libPrefs.getString("favoriteFolders", "[]")),
                        inFavs,
                    ) { "${it.optString("server")}|${it.optString("path")}" }
                    libEdit.putString("favoriteFolders", mergedFavs.toString())
                }
                libEdit.commit()

                prog.optJSONObject("playbackSkips")?.let { skipsObj ->
                    val pbPrefs = app.getSharedPreferences("playback", Context.MODE_PRIVATE)
                    val pbEdit = pbPrefs.edit()
                    PrefsCodec.decode(skipsObj).forEach { (k, v) -> PrefsCodec.put(pbEdit, k, v) }
                    pbEdit.commit()
                }

                prog.optJSONArray("webBookmarks")?.let { inBm ->
                    val webPrefs = app.getSharedPreferences("web", Context.MODE_PRIVATE)
                    val mergedBm = mergeByKey(
                        parseJsonArray(webPrefs.getString("bookmarks", "[]")),
                        inBm,
                    ) { it.optString("u") }
                    webPrefs.edit().putString("bookmarks", mergedBm.toString()).commit()
                }
            }
        }

        // 2. 合并自定义媒体库与刮削海报墙
        if (cfg.syncMediaLibraries) {
            remote.optJSONObject("customLibraries")?.let { customLibsObj ->
                CustomMediaLibraryStore.mergeFromSync(app, customLibsObj)
                val libCount = customLibsObj.optJSONArray("libraries")?.length() ?: 0
                if (libCount > 0) stats += "${libCount}个媒体库"
            }
        }

        // 3. 合并网络存储/媒体服务器与直播源
        if (cfg.syncServersAndLive) {
            remote.optJSONObject("sources")?.let { srcObj ->
                srcObj.optJSONArray("servers")?.let { inSrvs ->
                    val libPrefs = app.getSharedPreferences("library", Context.MODE_PRIVATE)
                    val oldSrvs = parseJsonArray(libPrefs.getString("servers", "[]"))
                    val mergedSrvs = mergeServers(oldSrvs, inSrvs)
                    libPrefs.edit().putString("servers", mergedSrvs.toString()).commit()
                    // 立即重新注册鉴权与双地址探测
                    for (i in 0 until mergedSrvs.length()) {
                        runCatching {
                            val srv = Server.fromJson(mergedSrvs.getJSONObject(i))
                            AuthRegistry.register(srv)
                            Endpoints.register(srv)
                            CloudRegistry.forget(srv.id)
                        }
                    }
                    if (inSrvs.length() > 0) stats += "${inSrvs.length()}个服务源"
                }

                val livePrefs = app.getSharedPreferences("live", Context.MODE_PRIVATE)
                val liveEdit = livePrefs.edit()
                srcObj.optJSONArray("liveSources")?.let { inLive ->
                    val mergedLive = mergeByKey(
                        parseJsonArray(livePrefs.getString("sources", "[]")),
                        inLive,
                        keepExistingId = true,
                    ) { it.optString("url").trim() }
                    liveEdit.putString("sources", mergedLive.toString())
                }
                srcObj.optJSONArray("liveFavorites")?.let { inFav ->
                    val mergedFav = mergeByKey(
                        parseJsonArray(livePrefs.getString("favorites", "[]")),
                        inFav,
                    ) { it.optString("url").trim() }
                    liveEdit.putString("favorites", mergedFav.toString())
                }
                liveEdit.commit()
            }
        }

        // 4. 合并设置与密钥（可选）
        if (cfg.syncSettingsAndKeys) {
            remote.optJSONObject("settingsBundle")?.let { bundle ->
                for (prefName in listOf("settings", "discover_search", "meta_overrides")) {
                    val encoded = bundle.optJSONObject(prefName) ?: continue
                    val p = app.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    val ed = p.edit()
                    PrefsCodec.decode(encoded).forEach { (k, v) -> PrefsCodec.put(ed, k, v) }
                    ed.commit()
                }
                stats += "设置与密钥"
            }
        }

        return if (stats.isEmpty()) "来自 $peerDevice" else "来自 $peerDevice · ${stats.joinToString("、")}"
    }

    private fun mergeHistoryByTimestamp(local: JSONArray, incoming: JSONArray): JSONArray {
        val map = LinkedHashMap<String, JSONObject>()
        fun putIfNewer(obj: JSONObject) {
            val uri = obj.optJSONObject("entry")?.optString("uri").orEmpty()
            if (uri.isEmpty()) return
            val existing = map[uri]
            if (existing == null || obj.optLong("playedAt", 0L) >= existing.optLong("playedAt", 0L)) {
                map[uri] = obj
            }
        }
        for (i in 0 until local.length()) putIfNewer(local.getJSONObject(i))
        for (i in 0 until incoming.length()) putIfNewer(incoming.getJSONObject(i))
        val sorted = map.values.sortedByDescending { it.optLong("playedAt", 0L) }.take(80)
        return JSONArray(sorted)
    }

    private fun mergePlaylists(local: JSONArray, incoming: JSONArray): JSONArray {
        val byName = LinkedHashMap<String, JSONObject>()
        for (i in 0 until local.length()) {
            val p = JSONObject(local.getJSONObject(i).toString())
            byName[p.optString("name")] = p
        }
        for (i in 0 until incoming.length()) {
            val inc = incoming.getJSONObject(i)
            val name = inc.optString("name")
            val cur = byName[name]
            if (cur == null) {
                byName[name] = inc
            } else {
                val mergedItems = mergeByKey(
                    cur.optJSONArray("items") ?: JSONArray(),
                    inc.optJSONArray("items") ?: JSONArray(),
                ) { it.optString("uri") }
                cur.put("items", mergedItems)
                cur.put("updatedAt", maxOf(cur.optLong("updatedAt"), inc.optLong("updatedAt")))
            }
        }
        return JSONArray(byName.values.toList())
    }

    private fun mergeServers(local: JSONArray, incoming: JSONArray): JSONArray {
        fun serverIdentity(o: JSONObject): String =
            o.optString("type") + "|" + o.optString("baseUrl").trim().trimEnd('/') + "|" +
                o.optString("username") + "|" + (if (o.optString("baseUrl").isBlank()) o.optString("name") else "")

        return mergeByKey(local, incoming, keepExistingId = true, keyOf = ::serverIdentity)
    }

    private fun mergeByKey(
        local: JSONArray,
        incoming: JSONArray,
        keepExistingId: Boolean = false,
        keyOf: (JSONObject) -> String,
    ): JSONArray {
        val list = MutableList(local.length()) { JSONObject(local.getJSONObject(it).toString()) }
        for (i in 0 until incoming.length()) {
            val item = JSONObject(incoming.getJSONObject(i).toString())
            val k = keyOf(item)
            val idx = list.indexOfFirst {
                keyOf(it) == k || (it.has("id") && it.optString("id").isNotEmpty() && it.optString("id") == item.optString("id"))
            }
            if (idx < 0) {
                list += item
            } else {
                if (keepExistingId && list[idx].optString("id").isNotEmpty()) {
                    item.put("id", list[idx].optString("id"))
                }
                list[idx] = item
            }
        }
        return JSONArray(list)
    }

    private fun parseJsonArray(raw: String?): JSONArray =
        if (raw.isNullOrBlank()) JSONArray() else runCatching { JSONArray(raw) }.getOrDefault(JSONArray())

    private fun filterJsonArray(raw: String?, predicate: (JSONObject) -> Boolean): JSONArray? {
        if (raw.isNullOrBlank()) return null
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        return JSONArray(List(arr.length()) { arr.getJSONObject(it) }.filter(predicate))
    }

    private fun isDeviceLocalOnlyUri(uri: String): Boolean {
        val u = uri.trim()
        return u.startsWith("content://", ignoreCase = true) ||
            u.startsWith("file://", ignoreCase = true) ||
            u.startsWith("/") ||
            (u.length >= 2 && u[1] == ':')
    }

    internal fun notifyReloadOnMain() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { onSyncedReload?.invoke() }
        }
    }

    private const val PROPFIND_MIN =
        """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""

    // ==================== 局域网双向对等同步（一次握手双向交换合并） ====================

    /** 局域网双向同步服务端：开启后本机广播 NSD，对端发起一次 POST 即完成双方数据双向合并 */
    class LanSyncHost(private val context: Context, private val onPeerSynced: (String) -> Unit) {
        private val nsd = runCatching { context.getSystemService(NsdManager::class.java) }.getOrNull()
        private val serverSocket = ServerSocket(0)
        val lanIp: String? = PhoneFormServer.lanAddress()
        val address: String? = lanIp?.let { "$it:${serverSocket.localPort}" }
        val serviceName: String = "译幕同步 · ${Build.MODEL}"
        private var registered = false

        @Volatile
        private var closed = false

        private val regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }

        init {
            if (lanIp != null && nsd != null) {
                runCatching {
                    val info = NsdServiceInfo().apply {
                        serviceName = this@LanSyncHost.serviceName
                        serviceType = LAN_SERVICE_TYPE
                        port = serverSocket.localPort
                    }
                    nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener)
                    registered = true
                }
            }
            Thread({
                while (!closed) {
                    val sock = try {
                        serverSocket.accept()
                    } catch (_: SocketException) {
                        break
                    } catch (_: Exception) {
                        continue
                    }
                    Thread({ sock.use { handleClient(it) } }, "Voxtitle-LanSyncClient").apply {
                        isDaemon = true
                        start()
                    }
                }
            }, "Voxtitle-LanSyncAccept").apply { isDaemon = true; start() }
        }

        private fun handleClient(c: Socket) {
            runCatching {
                c.soTimeout = 30_000
                val ins = c.getInputStream().buffered()
                val reqLine = readHttpLine(ins) ?: return
                val headers = generateSequence { readHttpLine(ins) }.takeWhile { it.isNotEmpty() }.toList()
                val method = reqLine.substringBefore(' ')
                val path = reqLine.substringAfter(' ').substringBefore(' ')
                if (method != "POST" || !path.startsWith("/sync")) {
                    writeHttp(c, 404, """{"error":"not found"}""")
                    return
                }
                val len = headers.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                if (len <= 0 || len > MAX_SYNC_BYTES) {
                    writeHttp(c, 413, """{"error":"payload size invalid"}""")
                    return
                }
                val buf = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = ins.read(buf, off, len - off)
                    if (n < 0) break
                    off += n
                }
                val incomingJson = JSONObject(String(buf, 0, off, Charsets.UTF_8))
                val cfg = loadConfig(context)
                val summary = mergeSnapshotIntoLocal(context, incomingJson, cfg)
                val now = System.currentTimeMillis()
                saveConfig(context, cfg.copy(lastSyncAt = now, lastSyncSummary = "局域网同步（$summary）"))
                notifyReloadOnMain()
                onPeerSynced("已完成双向同步：$summary")

                val replySnapshot = buildLocalSnapshot(context, cfg).toString()
                writeHttp(c, 200, replySnapshot)
            }.onFailure { e ->
                runCatching {
                    writeHttp(c, 400, JSONObject().put("error", e.message ?: "sync error").toString())
                }
            }
        }

        fun close() {
            closed = true
            if (registered) runCatching { nsd?.unregisterService(regListener) }
            registered = false
            runCatching { serverSocket.close() }
        }

        private fun readHttpLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                if (sb.length < 8192) sb.append(b.toChar())
            }
        }

        private fun writeHttp(c: Socket, code: Int, jsonBody: String) {
            val bytes = jsonBody.toByteArray(Charsets.UTF_8)
            c.getOutputStream().apply {
                write("HTTP/1.1 $code OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
        }
    }

    /** 局域网设备发现器 */
    class LanPeerFinder(context: Context) {
        private val nsd = runCatching { context.getSystemService(NsdManager::class.java) }.getOrNull()
        private val _peers = MutableStateFlow<List<LanSyncPeer>>(emptyList())
        val peers: StateFlow<List<LanSyncPeer>> = _peers.asStateFlow()
        private var running = false

        private val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        if (':' in host) return
                        val peer = LanSyncPeer(info.serviceName, "$host:${info.port}")
                        _peers.update { list -> list.filterNot { it.address == peer.address } + peer }
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _peers.update { list -> list.filterNot { it.name == serviceInfo.serviceName } }
            }
        }

        fun start() {
            if (running || nsd == null) return
            running = true
            runCatching { nsd?.discoverServices(LAN_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { running = false }
        }

        fun stop() {
            if (!running) return
            running = false
            runCatching { nsd?.stopServiceDiscovery(listener) }
        }
    }

    /** 向局域网另一台设备发起一键双向同步 */
    suspend fun syncWithLanPeer(context: Context, peerAddress: String): String = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val hostPort = peerAddress.trim().removePrefix("http://").substringBefore('/')
            if (hostPort.isBlank() || ':' !in hostPort) {
                throw IOException("请输入格式为 IP:端口 的同步地址，例如 192.168.1.8:51234")
            }
            val cfg = loadConfig(app)
            val localPayload = buildLocalSnapshot(app, cfg).toString()
            val req = Request.Builder()
                .url("http://$hostPort/sync")
                .post(localPayload.toByteArray(Charsets.UTF_8).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val respText = http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val err = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                    throw IOException(err.ifBlank { "对端返回 HTTP ${resp.code}" })
                }
                body
            }
            val mergedRemote = JSONObject(respText)
            val summary = mergeSnapshotIntoLocal(app, mergedRemote, cfg)
            val now = System.currentTimeMillis()
            val finalMsg = "局域网双向同步成功（$summary）"
            saveConfig(app, cfg.copy(lastSyncAt = now, lastSyncSummary = finalMsg))
            _state.update { it.copy(syncing = false, statusMessage = finalMsg, error = null, lastSyncAt = now) }
            notifyReloadOnMain()
            finalMsg
        }
    }
}
