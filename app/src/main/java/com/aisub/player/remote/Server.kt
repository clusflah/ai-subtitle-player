package com.aisub.player.remote

import android.util.Base64
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ServerType(val label: String, val isCloud: Boolean = false, val rootRef: String = "/") {
    WEBDAV("WebDAV"),
    OPENLIST("OpenList"),
    PAN115("115 网盘", isCloud = true, rootRef = "0"),
    ALIYUN("阿里云盘", isCloud = true, rootRef = "root"),
    BAIDU("百度网盘", isCloud = true, rootRef = "/"),
    PIKPAK("PikPak", isCloud = true, rootRef = ""),
    EMBY("Emby / Jellyfin", isCloud = true, rootRef = ""),
    PLEX("Plex", isCloud = true, rootRef = ""),
    ONEDRIVE("OneDrive", isCloud = true, rootRef = ""),
    DROPBOX("Dropbox", isCloud = true, rootRef = ""),
    GDRIVE("Google Drive", isCloud = true, rootRef = "root"),
}

/**
 * 一个网络媒体来源：WebDAV、OpenList，或直连的网盘账号。
 * 网盘的登录凭据和 token 存在 [extra]（JSON）里。
 */
data class Server(
    val id: String = UUID.randomUUID().toString(),
    val type: ServerType,
    val name: String,
    /** WebDAV：完整地址，如 https://nas.local:5006/dav；OpenList：站点地址，如 http://192.168.1.2:5244 */
    val baseUrl: String,
    val username: String = "",
    val password: String = "",
    /** 进入时打开的目录：WebDAV/OpenList 是路径，网盘是根目录 ID */
    val rootPath: String = type.rootRef,
    val extra: String = "",
) {
    val normalizedBase get() = baseUrl.trim().trimEnd('/')

    fun basicAuthHeader(): String? {
        if (username.isEmpty()) return null
        val raw = "$username:$password".toByteArray()
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type.name).put("name", name).put("baseUrl", baseUrl)
        .put("username", username).put("password", password).put("rootPath", rootPath).put("extra", extra)

    companion object {
        fun fromJson(o: JSONObject): Server {
            val type = runCatching { ServerType.valueOf(o.getString("type")) }.getOrDefault(ServerType.WEBDAV)
            return Server(
                id = o.getString("id"),
                type = type,
                name = o.optString("name"),
                baseUrl = o.optString("baseUrl"),
                username = o.optString("username"),
                password = o.optString("password"),
                rootPath = o.optString("rootPath", type.rootRef),
                extra = o.optString("extra"),
            )
        }
    }
}

/**
 * 播放器和音频解码器访问 WebDAV 文件时需要带鉴权头。
 * 这里按地址前缀记录每台服务器的请求头，只对匹配的地址添加，不会泄露给其他主机。
 * OpenList 的播放链接自带签名，不需要登记。
 */
object AuthRegistry {
    private val headers = ConcurrentHashMap<String, Map<String, String>>()
    private val prefixById = ConcurrentHashMap<String, String>()

    fun register(server: Server) {
        unregister(server)
        if (server.type != ServerType.WEBDAV) return
        val auth = server.basicAuthHeader() ?: return
        val prefix = server.normalizedBase + "/"
        headers[prefix] = mapOf("Authorization" to auth)
        prefixById[server.id] = prefix
    }

    fun unregister(server: Server) {
        prefixById.remove(server.id)?.let(headers::remove)
    }

    fun headersFor(url: String): Map<String, String> =
        headers.entries.filter { url.startsWith(it.key) }.maxByOrNull { it.key.length }?.value ?: emptyMap()
}

data class RemoteItem(
    val name: String,
    /** 目录的定位符：WebDAV/OpenList 是路径（/电影/xxx），网盘是目录 ID；用于进入下一级 */
    val path: String,
    val isDir: Boolean,
    val size: Long = 0,
    /** 文件的播放地址；目录为 null */
    val playUrl: String? = null,
)

interface RemoteClient {
    suspend fun list(path: String): List<RemoteItem>

    /** 重命名。parent 是所在目录的定位符（与 list 的参数相同）。 */
    suspend fun rename(parent: String, item: RemoteItem, newName: String): Unit =
        throw UnsupportedOperationException("这个来源不支持重命名")

    /** 删除（网盘一般是移到回收站）。 */
    suspend fun delete(parent: String, items: List<RemoteItem>): Unit =
        throw UnsupportedOperationException("这个来源不支持删除")

    /** 在 parent 下新建文件夹。 */
    suspend fun mkdir(parent: String, name: String): Unit =
        throw UnsupportedOperationException("这个来源不支持新建文件夹")
}

/** 检查新文件名：不能为空、不能含路径分隔符。 */
internal fun checkName(name: String): String {
    val n = name.trim()
    require(n.isNotEmpty() && '/' !in n && '\\' !in n) { "名称不合法" }
    return n
}

fun clientFor(server: Server): RemoteClient = when (server.type) {
    ServerType.WEBDAV -> WebDavClient(server)
    ServerType.OPENLIST -> OpenListClient(server)
    else -> com.aisub.player.remote.cloud.CloudRegistry.client(server)
        ?: throw IllegalArgumentException("不支持的类型 ${server.type}")
}

/** 路径逐段做 URL 编码，保留分隔符。 */
internal fun encodePath(path: String): String =
    path.split('/').joinToString("/") { android.net.Uri.encode(it) }

internal fun joinPath(dir: String, name: String): String =
    (if (dir.endsWith('/')) dir else "$dir/") + name
