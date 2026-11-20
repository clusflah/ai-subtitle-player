package com.aisub.player.backup

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.aisub.player.media.CustomMediaLibraryStore
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.Server
import com.aisub.player.remote.cloud.CloudRegistry
import com.aisub.player.telegram.Telegram
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 备份里可以分别选择导出、导入的几类数据。 */
enum class BackupSection(val label: String, val hint: String) {
    SETTINGS("设置和密钥", "语音识别、翻译、朗读、字幕样式、均衡器、多端同步配置及各服务 API Key"),
    MEDIA_LIBRARY("媒体库与进度", "首页自定义媒体库及海报墙刮削目录、观看历史与进度、收藏文件夹、电子书与漫画记录"),
    TELEGRAM("Telegram 登录与分组", "Telegram API 凭证、已登录会话（免重新收验证码）、置顶频道与分组；含敏感登录态，建议加密"),
    LIVE("直播源和收藏", "直播源订阅、收藏的频道、各频道调过的字幕延迟"),
    SERVERS("服务器和网盘", "WebDAV、OpenList、Emby / Plex 和各网盘的登录"),
    OTHER("其他记录", "片头片尾标记、音轨/字幕偏好、播放列表、影片信息修正、网页书签与历史"),
}

/** 备份文件已加密但尚未提供密码 */
class BackupPasswordRequiredException(message: String = "此备份文件已加密，请输入密码解密") : IllegalArgumentException(message)

/** 提供的解密密码错误或密文已损坏 */
class BackupWrongPasswordException(message: String = "密码错误或备份文件已损坏") : IllegalArgumentException(message)

/** 预检查结果：不解密就能先识别备份是否合法以及是否需要密码 */
sealed interface BackupEnvelope {
    data class Plain(val file: BackupFile) : BackupEnvelope
    data class Encrypted(val device: String, val exportedAt: Long, val rawText: String) : BackupEnvelope
}

/** 读进来的备份：来源设备、导出时间、各类数据（还没写入）。 */
class BackupFile(
    val device: String,
    val exportedAt: Long,
    val sections: Map<BackupSection, JSONObject>,
    val encrypted: Boolean = false,
) {
    /** 预览：每类数据有哪些内容，给用户确认 */
    fun summary(section: BackupSection): String {
        val data = sections[section] ?: return ""
        fun prefs(name: String) = data.optJSONObject(name) ?: JSONObject()
        fun count(name: String, key: String) = PrefsCodec.arrayOf(prefs(name), key)?.length() ?: 0
        return when (section) {
            BackupSection.SETTINGS -> {
                val keys = PrefsCodec.decode(prefs("settings")).count { (k, v) -> v is String && v.isNotBlank() && Backup.isSecretKey(k) }
                val hasSync = (PrefsCodec.decode(prefs("multi_sync"))["webdavUrl"] as? String)?.isNotBlank() == true
                val parts = ArrayList<String>()
                parts += if (keys > 0) "$keys 个密钥 / 账号" else "不含密钥"
                if (hasSync) parts += "含多端同步配置"
                parts.joinToString("、")
            }
            BackupSection.MEDIA_LIBRARY -> {
                val custom = data.optJSONObject(Backup.KEY_CUSTOM_LIBRARIES)
                val libs = custom?.optJSONArray("libraries")?.length() ?: 0
                var scrapedItems = 0
                val catalogs = custom?.optJSONObject("catalogs")
                if (catalogs != null) {
                    for (k in catalogs.keys()) {
                        scrapedItems += catalogs.optJSONArray(k)?.length() ?: 0
                    }
                }
                val history = count("library", "history")
                val favFolders = count("library", "favoriteFolders")
                val books = count("books", "recent")
                val manga = count("manga", "recent")
                val libDesc = when {
                    libs > 0 && scrapedItems > 0 -> "$libs 个媒体库（$scrapedItems 部影片）"
                    libs > 0 -> "$libs 个媒体库"
                    else -> ""
                }
                listOf(
                    libDesc,
                    if (history > 0) "$history 条观看进度" else "",
                    if (favFolders > 0) "$favFolders 个收藏目录" else "",
                    if (books > 0) "$books 本电子书记录" else "",
                    if (manga > 0) "$manga 本漫画记录" else "",
                ).filter { it.isNotEmpty() }.joinToString("、").ifEmpty { "空" }
            }
            BackupSection.TELEGRAM -> {
                val tg = PrefsCodec.decode(prefs("telegram"))
                val hasCreds = (tg["apiId"] as? Int ?: 0) != 0 && !(tg["apiHash"] as? String).isNullOrBlank()
                val dbObj = data.optJSONObject(Backup.KEY_TELEGRAM_DB)
                val hasSession = !dbObj?.optString("data").isNullOrBlank()
                val rawBytes = dbObj?.optLong("rawBytes", 0L) ?: 0L
                val pinned = (tg["pinned"] as? String).orEmpty().split(',').count { it.isNotBlank() }
                val groups = PrefsCodec.arrayOf(prefs("telegram"), "groups")?.length() ?: 0
                val parts = ArrayList<String>()
                if (hasCreds) parts += "API 凭证"
                if (hasSession) {
                    val sizeLabel = Backup.formatBytes(rawBytes)
                    parts += if (sizeLabel.isNotEmpty()) "登录会话（$sizeLabel）" else "登录会话"
                }
                if (pinned > 0) parts += "$pinned 个置顶"
                if (groups > 0) parts += "$groups 个分组"
                parts.joinToString("、").ifEmpty { "未配置" }
            }
            BackupSection.LIVE -> {
                val delays = PrefsCodec.decode(prefs("playback")).keys.count { it.startsWith("delay.live.") }
                listOf("${count("live", "sources")} 个直播源", "${count("live", "favorites")} 个收藏频道", "$delays 个频道的字幕延迟")
                    .filterNot { it.startsWith("0 ") }.joinToString("、").ifEmpty { "空" }
            }
            BackupSection.SERVERS -> {
                val arr = PrefsCodec.arrayOf(prefs("library"), "servers") ?: JSONArray()
                val names = List(arr.length()) { arr.getJSONObject(it).optString("name") }
                if (names.isEmpty()) "空" else "${names.size} 个：" + names.joinToString("、")
            }
            BackupSection.OTHER -> {
                val skips = PrefsCodec.decode(prefs("playback")).keys.count { it.startsWith("skip.file.") || it.startsWith("skip.series.") }
                val bookmarks = count("web", "bookmarks")
                listOf(
                    "$skips 处片头片尾标记",
                    "${count("library", "playlists")} 个播放列表",
                    "$bookmarks 条网页书签",
                    "${count("web", "history")} 条网页历史",
                ).filterNot { it.startsWith("0 ") }.joinToString("、").ifEmpty { "少量记录" }
            }
        }
    }
}

/**
 * 数据导出、导入：换手机、或在手机上设置好后一键搬到电视 / Windows 桌面端。
 *
 * - 备份是一个 JSON 文件（v2 支持可选 AES-256-GCM + PBKDF2-HMAC-SHA256 密码加密 + GZIP 压缩），
 *   按 [BackupSection] 分类保存设置、媒体库与刮削目录、Telegram 登录会话、直播源、服务器与网盘等数据；
 * - 导入时设置整体覆盖；媒体库、直播源、收藏、服务器、观看进度、播放列表等和本机已有的合并，不会删掉本机已有的；
 * - Telegram 登录会话恢复后会替换本机 Telegram 登录态，重启应用即可免验证码直接使用。
 */
object Backup {
    const val FORMAT = "voxtitle-backup"
    const val VERSION = 2
    internal const val KEY_CUSTOM_LIBRARIES = "__customLibraries"
    internal const val KEY_TELEGRAM_DB = "__telegramDb"
    private const val MAX_TG_DB_RAW_BYTES = 48L * 1024L * 1024L
    private const val MAX_HISTORY_ITEMS = 80
    private val LISTS = setOf(
        "live/sources",
        "live/favorites",
        "library/servers",
        "library/playlists",
        "library/history",
        "library/favoriteFolders",
        "web/history",
        "web/bookmarks",
    )
    private val SYNC_EXCLUDED_KEYS = setOf("deviceId", "deviceName", "lastSyncAt", "lastSyncSummary")

    fun export(context: Context, sections: Set<BackupSection>, password: String? = null): String {
        val app = context.applicationContext
        fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE).all
        val out = JSONObject()
        for (section in sections) {
            val data = JSONObject()
            when (section) {
                BackupSection.SETTINGS -> {
                    data.put("settings", PrefsCodec.encode(prefs("settings")))
                    val eq = prefs("equalizer")
                    if (eq.isNotEmpty()) data.put("equalizer", PrefsCodec.encode(eq))
                    val view = prefs("view")
                    if (view.isNotEmpty()) data.put("view", PrefsCodec.encode(view))
                    val sync = prefs("multi_sync").filterKeys { it !in SYNC_EXCLUDED_KEYS }
                    if (sync.isNotEmpty()) data.put("multi_sync", PrefsCodec.encode(sync))
                }
                BackupSection.MEDIA_LIBRARY -> {
                    data.put(KEY_CUSTOM_LIBRARIES, CustomMediaLibraryStore.exportForSync(app))
                    val lib = prefs("library")
                    val libSlice = LinkedHashMap<String, Any?>()
                    filterArray(lib["history"]) { item ->
                        val uri = item.optJSONObject("entry")?.optString("uri").orEmpty()
                        uri.isNotBlank() && !isLocalUri(uri)
                    }?.let { libSlice["history"] = it }
                    filterArray(lib["favoriteFolders"]) { it.optString("server").isNotBlank() }?.let {
                        libSlice["favoriteFolders"] = it
                    }
                    (lib["playbackStats"] as? String)?.takeIf { it.isNotBlank() }?.let { libSlice["playbackStats"] = it }
                    (lib["hiddenPin"] as? String)?.takeIf { it.isNotBlank() }?.let { libSlice["hiddenPin"] = it }
                    if (libSlice.isNotEmpty()) data.put("library", PrefsCodec.encode(libSlice))

                    val books = filterReadingPrefs(prefs("books"), locationField = "location")
                    if (books.isNotEmpty()) data.put("books", PrefsCodec.encode(books))
                    val manga = filterReadingPrefs(prefs("manga"), locationField = "uri")
                    if (manga.isNotEmpty()) data.put("manga", PrefsCodec.encode(manga))
                }
                BackupSection.TELEGRAM -> {
                    runCatching { Telegram.closeForBackup() }
                    val tgPrefs = filterTelegramPrefs(prefs("telegram"))
                    if (tgPrefs.isNotEmpty()) data.put("telegram", PrefsCodec.encode(tgPrefs))
                    packTelegramDb(File(app.filesDir, "telegram/db"))?.let { data.put(KEY_TELEGRAM_DB, it) }
                }
                BackupSection.LIVE -> {
                    val live = prefs("live")
                    data.put("live", PrefsCodec.encode(mapOf(
                        "sources" to filterArray(live["sources"]) { !isLocalUri(it.optString("url")) },
                        "favorites" to live["favorites"],
                    )))
                    data.put("playback", PrefsCodec.encode(prefs("playback").filterKeys { it.startsWith("delay.live.") }))
                }
                BackupSection.SERVERS -> data.put("library", PrefsCodec.encode(mapOf("servers" to prefs("library")["servers"])))
                BackupSection.OTHER -> {
                    data.put("playback", PrefsCodec.encode(prefs("playback").filterKeys {
                        it.startsWith("skip.file.") || it.startsWith("skip.series.") || it.startsWith("tracks.")
                    }))
                    val playlists = (prefs("library")["playlists"] as? String)?.let { json ->
                        val arr = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
                        // 本机文件换了设备打不开，只带网络地址
                        JSONArray(List(arr.length()) { arr.getJSONObject(it) }.map { p ->
                            p.put("items", filterArray(p.optJSONArray("items")?.toString()) { !isLocalUri(it.optString("uri")) }?.let(::JSONArray) ?: JSONArray())
                        }).toString()
                    }
                    data.put("library", PrefsCodec.encode(mapOf("playlists" to playlists)))
                    data.put("meta_overrides", PrefsCodec.encode(prefs("meta_overrides")))
                    val metaDisabled = prefs("meta_disabled")
                    if (metaDisabled.isNotEmpty()) data.put("meta_disabled", PrefsCodec.encode(metaDisabled))
                    data.put("web", PrefsCodec.encode(prefs("web").filterKeys { it != "last" }))
                    data.put("discover_search", PrefsCodec.encode(prefs("discover_search")))
                }
            }
            out.put(section.name, data)
        }
        val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val exportedAt = System.currentTimeMillis()
        val plainObj = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("device", device)
            .put("exportedAt", exportedAt)
            .put("sections", out)
        val cleanPwd = password?.takeIf { it.isNotEmpty() }
        return if (cleanPwd != null) {
            BackupCrypto.encrypt(plainObj.toString(), cleanPwd, device, exportedAt)
        } else {
            plainObj.toString(2)
        }
    }

    /** 校验备份文件外壳（明文或加密均放行），供局域网接收端校验使用 */
    fun validateEnvelope(text: String) {
        inspect(text)
    }

    /** 预检查备份文本：返回明文解析结果，或带设备/导出时间的加密外壳信息 */
    fun inspect(text: String): BackupEnvelope {
        val cleaned = text.trim().removePrefix("\uFEFF")
        val o = runCatching { JSONObject(cleaned) }.getOrNull()
        if (o == null || o.optString("format") != FORMAT) throw IllegalArgumentException("不是译幕的备份文件")
        if (o.optInt("version") > VERSION) throw IllegalArgumentException("备份来自更新版本的译幕，请先更新")
        return if (o.optBoolean("encrypted", false)) {
            if (o.optString("ciphertext").isBlank() || o.optString("salt").isBlank() || o.optString("iv").isBlank()) {
                throw IllegalArgumentException("加密备份文件不完整或已损坏")
            }
            BackupEnvelope.Encrypted(
                device = o.optString("device"),
                exportedAt = o.optLong("exportedAt"),
                rawText = cleaned,
            )
        } else {
            BackupEnvelope.Plain(parsePlainObject(o, encrypted = false))
        }
    }

    /** 读备份文件；加密备份未传密码时抛 [BackupPasswordRequiredException]，密码错时抛 [BackupWrongPasswordException] */
    fun parse(text: String, password: String? = null): BackupFile {
        val cleaned = text.trim().removePrefix("\uFEFF")
        val o = runCatching { JSONObject(cleaned) }.getOrNull()
        if (o == null || o.optString("format") != FORMAT) throw IllegalArgumentException("不是译幕的备份文件")
        if (o.optInt("version") > VERSION) throw IllegalArgumentException("备份来自更新版本的译幕，请先更新")
        if (o.optBoolean("encrypted", false)) {
            if (password.isNullOrEmpty()) throw BackupPasswordRequiredException()
            val innerText = BackupCrypto.decrypt(o, password)
            val inner = runCatching { JSONObject(innerText) }.getOrNull()
                ?: throw BackupWrongPasswordException()
            if (inner.optString("format") != FORMAT) throw BackupWrongPasswordException()
            if (inner.optInt("version") > VERSION) throw IllegalArgumentException("备份来自更新版本的译幕，请先更新")
            return parsePlainObject(inner, encrypted = true)
        }
        return parsePlainObject(o, encrypted = false)
    }

    private fun parsePlainObject(o: JSONObject, encrypted: Boolean): BackupFile {
        val sections = o.optJSONObject("sections") ?: JSONObject()
        return BackupFile(
            device = o.optString("device"),
            exportedAt = o.optLong("exportedAt"),
            sections = BackupSection.entries.mapNotNull { s -> sections.optJSONObject(s.name)?.let { s to it } }.toMap(),
            encrypted = encrypted,
        )
    }

    /** 写入选中的几类数据。写完要重启应用，各页面才会读到新的内容。 */
    fun import(context: Context, file: BackupFile, sections: Set<BackupSection>) {
        val app = context.applicationContext
        fun edit(name: String, block: SharedPreferences.(SharedPreferences.Editor) -> Unit) {
            val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            prefs.block(editor)
            editor.commit()
        }
        // 先导入 SERVERS，记录可能被保留的本机 serverId 映射，以便紧接着导入 MEDIA_LIBRARY 时自动修正绑定关系
        val ordered = sections.sortedBy { if (it == BackupSection.SERVERS) 0 else 1 }
        val serverIdRemap = LinkedHashMap<String, String>()

        for (section in ordered) {
            val data = file.sections[section] ?: continue
            if (section == BackupSection.TELEGRAM) {
                runCatching { Telegram.closeForBackup() }
            }
            for (name in data.keys()) {
                when (name) {
                    KEY_CUSTOM_LIBRARIES -> {
                        val rawBundle = data.optJSONObject(name) ?: continue
                        val remapped = remapCustomLibrariesServerIds(rawBundle, serverIdRemap)
                        CustomMediaLibraryStore.mergeFromSync(app, remapped)
                    }
                    KEY_TELEGRAM_DB -> {
                        val dbObj = data.optJSONObject(name) ?: continue
                        unpackTelegramDb(dbObj, File(app.filesDir, "telegram/db"))
                    }
                    else -> {
                        if (name.startsWith("__")) continue
                        val rawObj = data.optJSONObject(name) ?: continue
                        val values = PrefsCodec.decode(rawObj)
                        when {
                            // 设置整体换成备份里的（没出现的项回到默认）
                            section == BackupSection.SETTINGS && name == "settings" -> edit(name) { e ->
                                e.clear()
                                values.forEach { (k, v) -> PrefsCodec.put(e, k, v) }
                            }
                            section == BackupSection.TELEGRAM && name == "telegram" -> edit(name) { e ->
                                values.forEach { (k, v) -> PrefsCodec.put(e, k, v) }
                            }
                            else -> edit(name) { e ->
                                for ((k, v) in values) {
                                    if (section == BackupSection.SETTINGS && name == "multi_sync" && k in SYNC_EXCLUDED_KEYS) continue
                                    val incomingStr = if (name == "library" && k == "favoriteFolders" && v is String && serverIdRemap.isNotEmpty()) {
                                        remapFavoriteFoldersServerIds(v, serverIdRemap)
                                    } else {
                                        v as? String
                                    }
                                    if (name == "library" && k == "servers" && incomingStr != null) {
                                        val existingStr = getString(k, null)
                                        val (mergedServers, idMap) = mergeServersWithIdMap(existingStr, incomingStr)
                                        serverIdRemap.putAll(idMap)
                                        PrefsCodec.put(e, k, mergedServers)
                                    } else {
                                        val merged = mergeList(name, k, getString(k, null), incomingStr)
                                        PrefsCodec.put(e, k, merged ?: v)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (section == BackupSection.SERVERS) {
                refreshRegisteredServers(app)
            }
        }
    }

    private fun refreshRegisteredServers(context: Context) {
        val raw = context.getSharedPreferences("library", Context.MODE_PRIVATE).getString("servers", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            runCatching {
                val srv = Server.fromJson(arr.getJSONObject(i))
                AuthRegistry.register(srv)
                Endpoints.register(srv)
                CloudRegistry.forget(srv.id)
            }
        }
    }

    /** 需要合并的列表：返回合并后的 JSON；不是列表的项返回 null（直接覆盖） */
    internal fun mergeList(prefs: String, key: String, existing: String?, incoming: String?): String? {
        if (incoming == null || "$prefs/$key" !in LISTS) return null
        val old = runCatching { JSONArray(existing ?: "[]") }.getOrDefault(JSONArray())
        val new = runCatching { JSONArray(incoming) }.getOrNull() ?: return null
        return when ("$prefs/$key") {
            // 同一个地址的直播源只留一份，保留本机的 id（频道缓存按 id 存）
            "live/sources" -> mergeBy(old, new, keepId = true) { it.optString("url").trim() }
            "live/favorites" -> mergeBy(old, new) { it.optString("url") }
            // 同一台服务器（类型 + 地址 + 账号）只留一份，保留本机的 id（收藏的文件夹按 id 记）
            "library/servers" -> mergeBy(old, new, keepId = true) { serverDedupKey(it) }
            // 观看进度按 URI 去重，取 playedAt 较新的那条
            "library/history" -> mergeHistoryByTimestamp(old, new)
            // 收藏文件夹按 服务器|路径 去重
            "library/favoriteFolders" -> mergeBy(old, new) { "${it.optString("server")}|${it.optString("path")}" }
            // 同名播放列表合并里面的曲目
            "library/playlists" -> mergeBy(old, new, keepId = true, combine = { a, b ->
                b.put("items", mergeArrays(a.optJSONArray("items") ?: JSONArray(), b.optJSONArray("items") ?: JSONArray()) { it.optString("uri") })
            }) { it.optString("name") }
            "web/history" -> mergeArrays(new, old) { it.optString("u") }.let { arr -> JSONArray(List(minOf(arr.length(), 30)) { arr.get(it) }) }
            "web/bookmarks" -> mergeBy(old, new) { it.optString("u") }
            else -> return null
        }.toString()
    }

    private fun serverDedupKey(it: JSONObject): String =
        it.optString("type") + "|" + it.optString("baseUrl").trim().trimEnd('/') + "|" + it.optString("username") + "|" +
            if (it.optString("baseUrl").isBlank()) it.optString("name") else ""

    internal fun mergeServersWithIdMap(existing: String?, incoming: String): Pair<String, Map<String, String>> {
        val old = runCatching { JSONArray(existing ?: "[]") }.getOrDefault(JSONArray())
        val new = runCatching { JSONArray(incoming) }.getOrNull() ?: return (existing ?: "[]") to emptyMap()
        val result = List(old.length()) { JSONObject(old.getJSONObject(it).toString()) }.toMutableList()
        val remap = LinkedHashMap<String, String>()
        for (i in 0 until new.length()) {
            val item = JSONObject(new.getJSONObject(i).toString())
            val incomingId = item.optString("id")
            val at = result.indexOfFirst { serverDedupKey(it) == serverDedupKey(item) || (it.has("id") && it.optString("id") == incomingId) }
            if (at < 0) {
                result += item
            } else {
                val keptId = result[at].optString("id")
                if (keptId.isNotEmpty()) {
                    if (incomingId.isNotEmpty() && incomingId != keptId) {
                        remap[incomingId] = keptId
                    }
                    item.put("id", keptId)
                }
                result[at] = item
            }
        }
        return JSONArray(result).toString() to remap
    }

    internal fun remapCustomLibrariesServerIds(bundle: JSONObject, idMap: Map<String, String>): JSONObject {
        if (idMap.isEmpty()) return bundle
        val copy = JSONObject(bundle.toString())
        val libs = copy.optJSONArray("libraries")
        if (libs != null) {
            for (i in 0 until libs.length()) {
                val folders = libs.optJSONObject(i)?.optJSONArray("folders") ?: continue
                for (j in 0 until folders.length()) {
                    val f = folders.optJSONObject(j) ?: continue
                    val mapped = idMap[f.optString("serverId")] ?: continue
                    f.put("serverId", mapped)
                }
            }
        }
        val catalogs = copy.optJSONObject("catalogs")
        if (catalogs != null) {
            for (libId in catalogs.keys()) {
                val items = catalogs.optJSONArray(libId) ?: continue
                for (i in 0 until items.length()) {
                    val eps = items.optJSONObject(i)?.optJSONArray("episodes") ?: continue
                    for (j in 0 until eps.length()) {
                        val ep = eps.optJSONObject(j) ?: continue
                        val mapped = idMap[ep.optString("serverId")] ?: continue
                        ep.put("serverId", mapped)
                    }
                }
            }
        }
        return copy
    }

    private fun remapFavoriteFoldersServerIds(json: String, idMap: Map<String, String>): String {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return json
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val mapped = idMap[obj.optString("server")] ?: continue
            obj.put("server", mapped)
        }
        return arr.toString()
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
        return JSONArray(map.values.sortedByDescending { it.optLong("playedAt", 0L) }.take(MAX_HISTORY_ITEMS))
    }

    internal fun filterTelegramPrefs(all: Map<String, Any?>): Map<String, Any?> =
        all.filterKeys { !it.startsWith("stats_") }

    private fun filterReadingPrefs(all: Map<String, Any?>, locationField: String): Map<String, Any?> {
        if (all.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in all) {
            if (k == "recent" && v is String) {
                val filtered = filterArray(v) { item ->
                    val loc = item.optString(locationField).ifBlank { item.optString("key") }
                    loc.isBlank() || !isLocalUri(loc)
                }
                if (filtered != null) out[k] = filtered
            } else {
                out[k] = v
            }
        }
        return out
    }

    internal fun packTelegramDb(dbDir: File): JSONObject? {
        if (!dbDir.isDirectory) return null
        val files = dbDir.walkTopDown()
            .filter { it.isFile }
            .filter { f ->
                val name = f.name.lowercase()
                !name.endsWith(".lock") && !name.endsWith(".tmp")
            }
            .sortedBy { it.relativeTo(dbDir).path }
            .toList()
        if (files.isEmpty()) return null

        val baos = ByteArrayOutputStream()
        var rawBytes = 0L
        var fileCount = 0
        ZipOutputStream(baos).use { zos ->
            val buf = ByteArray(16 * 1024)
            for (f in files) {
                val len = f.length()
                if (len < 0L || rawBytes + len > MAX_TG_DB_RAW_BYTES) continue
                val rel = f.relativeTo(dbDir).path.replace('\\', '/')
                if (rel.isBlank() || rel.startsWith("/") || ".." in rel.split('/')) continue
                zos.putNextEntry(ZipEntry(rel))
                f.inputStream().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        zos.write(buf, 0, n)
                    }
                }
                zos.closeEntry()
                rawBytes += len
                fileCount++
            }
        }
        if (fileCount == 0) return null
        val zipBytes = baos.toByteArray()
        return JSONObject()
            .put("encoding", "zip-base64")
            .put("fileCount", fileCount)
            .put("rawBytes", rawBytes)
            .put("data", zipBytes.toByteString().base64())
    }

    internal fun unpackTelegramDb(dbObj: JSONObject, dbDir: File) {
        val data = dbObj.optString("data")
        if (data.isBlank()) return
        val zipBytes = data.decodeBase64()?.toByteArray()
            ?: throw IllegalArgumentException("Telegram 会话数据损坏")

        // 先校验整个 ZIP 合法且无路径穿越，再清空原目录落盘，避免半途失败损坏已有会话
        val entries = ArrayList<Pair<String, ByteArray>>()
        var totalRaw = 0L
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val rel = entry.name.replace('\\', '/').trimStart('/')
                if (rel.isBlank() || ".." in rel.split('/')) {
                    throw IllegalArgumentException("Telegram 会话备份包含非法路径")
                }
                val out = ByteArrayOutputStream()
                while (true) {
                    val n = zis.read(buf)
                    if (n <= 0) break
                    totalRaw += n
                    if (totalRaw > MAX_TG_DB_RAW_BYTES) {
                        throw IllegalArgumentException("Telegram 会话数据超过安全大小上限")
                    }
                    out.write(buf, 0, n)
                }
                entries += rel to out.toByteArray()
                zis.closeEntry()
            }
        }
        if (entries.isEmpty()) return

        if (dbDir.exists()) {
            dbDir.listFiles()?.forEach { it.deleteRecursively() }
        } else {
            dbDir.mkdirs()
        }
        val baseCanonical = dbDir.canonicalFile
        for ((rel, bytes) in entries) {
            val target = File(dbDir, rel).canonicalFile
            if (target.path != baseCanonical.path && !target.path.startsWith(baseCanonical.path + File.separator)) {
                throw IllegalArgumentException("Telegram 会话备份包含非法路径")
            }
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
    }

    internal fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024L -> "${bytes} B"
        bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** 按 key 合并：备份里的替换本机同 key 的那条（位置不变），本机没有的加在后面 */
    private fun mergeBy(
        old: JSONArray,
        new: JSONArray,
        keepId: Boolean = false,
        combine: (JSONObject, JSONObject) -> JSONObject = { _, b -> b },
        key: (JSONObject) -> String,
    ): JSONArray {
        val result = List(old.length()) { old.getJSONObject(it) }.toMutableList()
        for (i in 0 until new.length()) {
            val item = new.getJSONObject(i)
            val at = result.indexOfFirst { key(it) == key(item) || (it.has("id") && it.optString("id") == item.optString("id")) }
            if (at < 0) result += item
            else {
                if (keepId && result[at].has("id")) item.put("id", result[at].getString("id"))
                result[at] = combine(result[at], item)
            }
        }
        return JSONArray(result)
    }

    /** first 在前，second 里 first 没有的接在后面 */
    private fun mergeArrays(first: JSONArray, second: JSONArray, key: (JSONObject) -> String): JSONArray {
        val items = List(first.length()) { first.getJSONObject(it) } + List(second.length()) { second.getJSONObject(it) }
        return JSONArray(items.distinctBy(key))
    }

    private fun filterArray(json: Any?, keep: (JSONObject) -> Boolean): String? {
        val arr = runCatching { JSONArray(json as? String ?: return null) }.getOrNull() ?: return null
        return JSONArray(List(arr.length()) { arr.getJSONObject(it) }.filter(keep)).toString()
    }

    private fun isLocalUri(uri: String) =
        uri.startsWith("content://") || uri.startsWith("file://") || uri.startsWith("/") ||
            (uri.length >= 3 && uri[1] == ':' && (uri[2] == '\\' || uri[2] == '/'))

    /** 设置里的密钥、账号类项（预览里数一数） */
    internal fun isSecretKey(key: String) =
        Regex("(?i)(apikey|key|token|secret|password|username)$").containsMatchIn(key.substringAfterLast('.'))
}

/**
 * 备份文件加密器：GZIP 压缩 + PBKDF2-HMAC-SHA256 派生 256 位密钥 + AES-256-GCM 认证加密。
 * PBKDF2 直接基于标准 HmacSHA256 实现，完全兼容 Android 6.0 (API 23) 及以上系统与 Windows JVM。
 */
internal object BackupCrypto {
    private const val CIPHER = "AES-256-GCM"
    private const val KDF = "PBKDF2-HMAC-SHA256"
    private const val DEFAULT_ITERATIONS = 120_000
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 1_000_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128

    fun encrypt(plainJson: String, password: String, device: String, exportedAt: Long): String {
        val rng = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(rng::nextBytes)
        val iv = ByteArray(IV_BYTES).also(rng::nextBytes)
        val compressed = gzip(plainJson.toByteArray(Charsets.UTF_8))
        val key = pbkdf2HmacSha256(password.toByteArray(Charsets.UTF_8), salt, DEFAULT_ITERATIONS, KEY_BYTES)
        val ciphertext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aadOf(KDF, DEFAULT_ITERATIONS))
            cipher.doFinal(compressed)
        } finally {
            key.fill(0)
        }
        return JSONObject()
            .put("format", Backup.FORMAT)
            .put("version", Backup.VERSION)
            .put("encrypted", true)
            .put("cipher", CIPHER)
            .put("kdf", KDF)
            .put("iterations", DEFAULT_ITERATIONS)
            .put("compression", "gzip")
            .put("device", device)
            .put("exportedAt", exportedAt)
            .put("salt", salt.toByteString().base64())
            .put("iv", iv.toByteString().base64())
            .put("ciphertext", ciphertext.toByteString().base64())
            .toString(2)
    }

    fun decrypt(envelope: JSONObject, password: String): String {
        val cipherName = envelope.optString("cipher")
        val kdf = envelope.optString("kdf")
        val iterations = envelope.optInt("iterations", DEFAULT_ITERATIONS)
        if (cipherName != CIPHER || kdf != KDF || iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw IllegalArgumentException("不支持的备份加密格式，请更新译幕后重试")
        }
        val salt = envelope.optString("salt").decodeBase64()?.toByteArray()
            ?: throw BackupWrongPasswordException()
        val iv = envelope.optString("iv").decodeBase64()?.toByteArray()
            ?: throw BackupWrongPasswordException()
        val ciphertext = envelope.optString("ciphertext").decodeBase64()?.toByteArray()
            ?: throw BackupWrongPasswordException()
        if (salt.size < 8 || iv.size != IV_BYTES || ciphertext.size < 16) {
            throw BackupWrongPasswordException()
        }
        val key = pbkdf2HmacSha256(password.toByteArray(Charsets.UTF_8), salt, iterations, KEY_BYTES)
        val plainBytes = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aadOf(kdf, iterations))
            cipher.doFinal(ciphertext)
        } catch (_: GeneralSecurityException) {
            throw BackupWrongPasswordException()
        } finally {
            key.fill(0)
        }
        val uncompressed = if (envelope.optString("compression") == "gzip") {
            runCatching { gunzip(plainBytes) }.getOrElse { throw BackupWrongPasswordException() }
        } else {
            plainBytes
        }
        return uncompressed.toString(Charsets.UTF_8)
    }

    private fun aadOf(kdf: String, iterations: Int): ByteArray =
        "voxtitle-backup-enc:v1:$kdf:$iterations".toByteArray(Charsets.UTF_8)

    /** RFC 2898 PBKDF2-HMAC-SHA256，原生兼容 Android API 23+ 与标准 JVM */
    internal fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (keyLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        val blockIdx = ByteArray(4)
        for (i in 1..blocks) {
            blockIdx[0] = (i ushr 24).toByte()
            blockIdx[1] = (i ushr 16).toByte()
            blockIdx[2] = (i ushr 8).toByte()
            blockIdx[3] = i.toByte()
            mac.update(salt)
            mac.update(blockIdx)
            var u = mac.doFinal()
            val t = u.copyOf()
            for (iter in 2..iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) {
                    t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        return out.copyOf(keyLen)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(data) }
        return baos.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
}

/** SharedPreferences 的值和 JSON 互转，带上类型（Int、Long、Float 在 JSON 里分不出来） */
internal object PrefsCodec {
    fun encode(values: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in values) {
            val tagged = when (v) {
                is String -> JSONArray().put("s").put(v)
                is Boolean -> JSONArray().put("b").put(v)
                is Int -> JSONArray().put("i").put(v)
                is Long -> JSONArray().put("l").put(v)
                is Float -> JSONArray().put("f").put(v.toDouble())
                is Set<*> -> JSONArray().put("ss").put(JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, tagged)
        }
        return o
    }

    fun decode(o: JSONObject): Map<String, Any> = o.keys().asSequence().mapNotNull { k ->
        val arr = o.optJSONArray(k) ?: return@mapNotNull null
        val v: Any = when (arr.optString(0)) {
            "s" -> arr.optString(1)
            "b" -> arr.optBoolean(1)
            "i" -> arr.optInt(1)
            "l" -> arr.optLong(1)
            "f" -> arr.optDouble(1).toFloat()
            "ss" -> arr.optJSONArray(1)?.let { s -> List(s.length()) { s.getString(it) }.toSet() } ?: return@mapNotNull null
            else -> return@mapNotNull null
        }
        k to v
    }.toMap()

    /** 编码后的 prefs 里某个存成 JSON 数组的字符串项 */
    fun arrayOf(o: JSONObject, key: String): JSONArray? = (decode(o)[key] as? String)?.let { runCatching { JSONArray(it) }.getOrNull() }

    @Suppress("UNCHECKED_CAST")
    fun put(e: SharedPreferences.Editor, key: String, v: Any) {
        when (v) {
            is String -> e.putString(key, v)
            is Boolean -> e.putBoolean(key, v)
            is Int -> e.putInt(key, v)
            is Long -> e.putLong(key, v)
            is Float -> e.putFloat(key, v)
            is Set<*> -> e.putStringSet(key, v as Set<String>)
        }
    }
}
