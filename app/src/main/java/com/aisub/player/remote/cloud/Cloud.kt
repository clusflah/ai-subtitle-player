package com.aisub.player.remote.cloud

import android.net.Uri
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.RemoteClient
import com.aisub.player.remote.Server
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 网盘文件的真实下载地址，以及播放时必须携带的请求头（UA、Cookie 等）。 */
data class ResolvedLink(val url: String, val headers: Map<String, String> = emptyMap(), val validMs: Long = 10 * 60_000L)

/**
 * 网盘客户端。网盘的直链是临时的，所以列表里给出的是稳定的 cloud:// 地址，
 * 真正播放时再通过 [resolve] 换成直链（阻塞调用，只能在后台线程用）。
 */
abstract class CloudClient(protected var server: Server) : RemoteClient {

    /** 登录凭据和 token（JSON），刷新后通过 [onCredentialsChanged] 持久化。 */
    protected val creds: JSONObject = runCatching { JSONObject(server.extra) }.getOrDefault(JSONObject())

    var onCredentialsChanged: ((Server) -> Unit)? = null

    protected fun saveCreds() {
        server = server.copy(extra = creds.toString())
        onCredentialsChanged?.invoke(server)
    }

    /** fileRef 由子类在 [playUri] 里决定（文件 ID、提取码等）。 */
    abstract fun resolve(fileRef: String): ResolvedLink

    protected fun playUri(fileRef: String, name: String): String =
        "$SCHEME://${server.id}/${Uri.encode(fileRef)}?n=${Uri.encode(name)}"

    companion object {
        const val SCHEME = "cloud"
    }
}

/** 全局的网盘客户端表和直链缓存，供播放器、音频解码器、浏览页共用。 */
object CloudRegistry {
    private val clients = ConcurrentHashMap<String, CloudClient>()
    private val linkCache = ConcurrentHashMap<String, Pair<ResolvedLink, Long>>()

    /** 由应用启动时设置：按 ID 取服务器配置、保存刷新后的凭据。 */
    var serverLookup: (String) -> Server? = { null }
    var serverSaver: (Server) -> Unit = {}

    fun client(server: Server): CloudClient? = clients[server.id] ?: create(server)?.also {
        it.onCredentialsChanged = { s -> serverSaver(s) }
        clients[server.id] = it
    }

    fun forget(serverId: String) {
        clients.remove(serverId)
        linkCache.keys.removeAll { it.startsWith("${CloudClient.SCHEME}://$serverId/") }
    }

    fun isCloud(uri: String) = uri.startsWith("${CloudClient.SCHEME}://")

    /** cloud://<serverId>/<fileRef> → 直链。阻塞调用。 */
    fun resolve(uri: String): ResolvedLink {
        // 媒体服务器的直链里带着服务器地址，网络切换后换成当前能连上的内网 / 外网地址
        return resolveCached(uri).let { it.copy(url = Endpoints.rewrite(it.url)) }
    }

    private fun resolveCached(uri: String): ResolvedLink {
        linkCache[uri]?.let { (link, expiry) -> if (System.currentTimeMillis() < expiry) return link }
        val parsed = Uri.parse(uri)
        val serverId = parsed.host ?: throw IOException("无效的网盘地址")
        val fileRef = Uri.decode(parsed.encodedPath.orEmpty().removePrefix("/"))
        val server = serverLookup(serverId) ?: throw IOException("网盘账号已被删除")
        val client = client(server) ?: throw IOException("不支持的网盘类型")
        val link = client.resolve(fileRef)
        linkCache[uri] = link to System.currentTimeMillis() + link.validMs
        return link
    }

    private fun create(server: Server): CloudClient? = when (server.type) {
        com.aisub.player.remote.ServerType.PAN115 -> Pan115Client(server)
        com.aisub.player.remote.ServerType.ALIYUN -> AliyunClient(server)
        com.aisub.player.remote.ServerType.BAIDU -> BaiduClient(server)
        com.aisub.player.remote.ServerType.PIKPAK -> PikPakClient(server)
        com.aisub.player.remote.ServerType.EMBY -> EmbyClient(server)
        com.aisub.player.remote.ServerType.PLEX -> PlexClient(server)
        com.aisub.player.remote.ServerType.ONEDRIVE -> OneDriveClient(server)
        com.aisub.player.remote.ServerType.DROPBOX -> DropboxClient(server)
        com.aisub.player.remote.ServerType.GDRIVE -> GoogleDriveClient(server)
        else -> null
    }
}

internal val cloudHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

/** 不跟随重定向，用于取 302 的 Location。 */
internal val cloudHttpNoRedirect: OkHttpClient = cloudHttp.newBuilder().followRedirects(false).followSslRedirects(false).build()

internal fun Response.json(): JSONObject {
    val text = body?.string().orEmpty()
    // 部分写操作成功时不返回正文
    if (text.isBlank() && isSuccessful) return JSONObject()
    return try {
        JSONObject(text)
    } catch (e: Exception) {
        throw IOException("接口返回异常（HTTP $code）：${text.take(200)}")
    }
}

internal fun md5Hex(s: String): String = hex(MessageDigest.getInstance("MD5").digest(s.toByteArray()))
internal fun sha1Hex(s: String): String = hex(MessageDigest.getInstance("SHA-1").digest(s.toByteArray()))
internal fun sha256Hex(s: String): String = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

/** 扫码登录的二维码与状态。 */
data class QrSession(val imageUrl: String?, val content: String?, val hint: String, val state: Map<String, String>)

enum class QrStatus { WAITING, SCANNED, SUCCESS, EXPIRED, CANCELED }
