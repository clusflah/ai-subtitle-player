package com.aisub.player.remote

import android.util.Base64
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ServerType(val label: String) {
    WEBDAV("WebDAV"),
    OPENLIST("OpenList"),
}

/** 一个网络媒体来源：WebDAV 服务器，或 OpenList（原 AList，可挂载 115/百度/阿里/PikPak 等网盘）。 */
data class Server(
    val id: String = UUID.randomUUID().toString(),
    val type: ServerType,
    val name: String,
    /** WebDAV：完整地址，如 https://nas.local:5006/dav；OpenList：站点地址，如 http://192.168.1.2:5244 */
    val baseUrl: String,
    val username: String = "",
    val password: String = "",
    /** 进入时打开的目录，例如 OpenList 里网盘的挂载路径 /115 */
    val rootPath: String = "/",
) {
    val normalizedBase get() = baseUrl.trim().trimEnd('/')

    fun basicAuthHeader(): String? {
        if (username.isEmpty()) return null
        val raw = "$username:$password".toByteArray()
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type.name).put("name", name).put("baseUrl", baseUrl)
        .put("username", username).put("password", password).put("rootPath", rootPath)

    companion object {
        fun fromJson(o: JSONObject) = Server(
            id = o.getString("id"),
            type = runCatching { ServerType.valueOf(o.getString("type")) }.getOrDefault(ServerType.WEBDAV),
            name = o.optString("name"),
            baseUrl = o.optString("baseUrl"),
            username = o.optString("username"),
            password = o.optString("password"),
            rootPath = o.optString("rootPath", "/"),
        )
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
    /** 服务器内的路径，例如 /电影/xxx.mkv */
    val path: String,
    val isDir: Boolean,
    val size: Long = 0,
    /** 文件的播放地址；目录为 null */
    val playUrl: String? = null,
)

interface RemoteClient {
    suspend fun list(path: String): List<RemoteItem>
}

fun clientFor(server: Server): RemoteClient = when (server.type) {
    ServerType.WEBDAV -> WebDavClient(server)
    ServerType.OPENLIST -> OpenListClient(server)
}

/** 路径逐段做 URL 编码，保留分隔符。 */
internal fun encodePath(path: String): String =
    path.split('/').joinToString("/") { android.net.Uri.encode(it) }

internal fun joinPath(dir: String, name: String): String =
    (if (dir.endsWith('/')) dir else "$dir/") + name
