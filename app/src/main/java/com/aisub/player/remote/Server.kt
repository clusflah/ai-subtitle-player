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
    /** 内网地址（可不填）：在家能连上时优先走它，连不上走 [baseUrl]，见 [Endpoints] */
    val lanUrl: String = "",
) {
    val normalizedBase get() = baseUrl.trim().trimEnd('/')
    val normalizedLan get() = lanUrl.trim().trimEnd('/')

    fun basicAuthHeader(): String? {
        if (username.isEmpty()) return null
        val raw = "$username:$password".toByteArray()
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type.name).put("name", name).put("baseUrl", baseUrl)
        .put("username", username).put("password", password).put("rootPath", rootPath).put("extra", extra)
        .put("lanUrl", lanUrl)

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
                lanUrl = o.optString("lanUrl"),
            )
        }
    }
}

/**
 * 播放器和音频解码器访问 WebDAV 文件时需要带鉴权头。
 * 这里按地址前缀（外网、内网地址各一个）记录每台服务器的请求头，只对匹配的地址添加，不会泄露给其他主机。
 * OpenList 的播放链接自带签名，不需要登记。
 */
object AuthRegistry {
    private val headers = ConcurrentHashMap<String, Map<String, String>>()
    private val prefixesById = ConcurrentHashMap<String, List<String>>()

    fun register(server: Server) {
        unregister(server)
        if (server.type != ServerType.WEBDAV) return
        val auth = server.basicAuthHeader() ?: return
        val prefixes = listOf(server.normalizedBase, server.normalizedLan).filter { it.isNotEmpty() }.map { "$it/" }
        prefixes.forEach { headers[it] = mapOf("Authorization" to auth) }
        prefixesById[server.id] = prefixes
    }

    fun unregister(server: Server) {
        prefixesById.remove(server.id)?.forEach(headers::remove)
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
    /** 媒体服务器（Emby / Plex）给出的海报、年份、评分等；普通网盘为 null */
    val meta: ItemMeta? = null,
)

/** 媒体服务器条目的元数据，用于海报墙和详情页。 */
data class ItemMeta(
    /** 服务器上的原始片名（不带扩展名、集数前缀） */
    val title: String? = null,
    val posterUrl: String? = null,
    /** 海报宽高比：电影海报 2:3，单集截图、媒体库封面约 16:9 */
    val posterRatio: Float = 2f / 3f,
    val backdropUrl: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val overview: String = "",
    val genres: List<String> = emptyList(),
    /** "movie" / "tv" / "season" / "episode" / null */
    val kind: String? = null,
    val seriesName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val tmdbId: String? = null,
    val imdbId: String? = null,
    /** 角标文字，例如剧集的集数、未看集数 */
    val badge: String? = null,
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
