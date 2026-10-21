package com.aisub.player.remote.cloud

import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * 百度网盘（官方开放平台 xpan 接口）。两种授权方式：
 *  1. 自己在 pan.baidu.com/union 申请的 AppKey/SecretKey，App 内设备码扫码授权；
 *  2. 粘贴 refresh_token，由在线续期接口刷新（默认 OpenList 提供的 api.oplist.org）。
 * 参考 OpenList drivers/baidu_netdisk。
 * 凭据：creds.client_id / client_secret / refresh_token / access_token / renew_api
 */
class BaiduClient(server: Server) : CloudClient(server) {

    @Synchronized
    private fun accessToken(forceRefresh: Boolean = false): String {
        val cached = creds.optString("access_token")
        if (cached.isNotEmpty() && !forceRefresh) return cached
        val clientId = creds.optString("client_id")
        val tokens = if (clientId.isNotEmpty()) {
            val url = "https://openapi.baidu.com/oauth/2.0/token".toHttpUrl().newBuilder()
                .addQueryParameter("grant_type", "refresh_token")
                .addQueryParameter("refresh_token", creds.getString("refresh_token"))
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("client_secret", creds.optString("client_secret"))
                .build()
            cloudHttp.newCall(Request.Builder().url(url).build()).execute().use { it.json() }
        } else {
            val url = creds.optString("renew_api").ifEmpty { DEFAULT_RENEW_API }.toHttpUrl().newBuilder()
                .addQueryParameter("refresh_ui", creds.getString("refresh_token"))
                .addQueryParameter("server_use", "true")
                .addQueryParameter("driver_txt", "baiduyun_go")
                .build()
            cloudHttp.newCall(Request.Builder().url(url).build()).execute().use { it.json() }
        }
        val access = tokens.optString("access_token")
        if (access.isEmpty()) {
            val err = tokens.optString("error_description").ifEmpty { tokens.optString("text") }.ifEmpty { tokens.toString().take(200) }
            throw IOException("百度网盘授权已失效，请重新登录（$err）")
        }
        creds.put("access_token", access)
        tokens.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { creds.put("refresh_token", it) }
        saveCreds()
        return access
    }

    private fun get(path: String, params: Map<String, String>, retried: Boolean = false): JSONObject {
        val b = "https://pan.baidu.com/rest/2.0$path".toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        b.addQueryParameter("access_token", accessToken())
        val json = cloudHttp.newCall(Request.Builder().url(b.build()).header("User-Agent", UA).build()).execute().use { it.json() }
        val errno = json.optInt("errno")
        if (errno != 0) {
            if (!retried && (errno == 111 || errno == -6)) {
                accessToken(forceRefresh = true)
                return get(path, params, retried = true)
            }
            throw IOException("百度网盘：错误码 $errno")
        }
        return json
    }

    private fun post(path: String, params: Map<String, String>, form: Map<String, String>, retried: Boolean = false): JSONObject {
        val b = "https://pan.baidu.com/rest/2.0$path".toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        b.addQueryParameter("access_token", accessToken())
        val body = okhttp3.FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val json = cloudHttp.newCall(Request.Builder().url(b.build()).header("User-Agent", UA).post(body).build())
            .execute().use { it.json() }
        val errno = json.optInt("errno")
        if (errno != 0) {
            if (!retried && (errno == 111 || errno == -6)) {
                accessToken(forceRefresh = true)
                return post(path, params, form, retried = true)
            }
            throw IOException(
                when (errno) {
                    -8, -30 -> "百度网盘：已存在同名文件"
                    -7 -> "百度网盘：文件名不合法"
                    else -> "百度网盘：错误码 $errno"
                },
            )
        }
        return json
    }

    // 百度网盘的定位符就是完整路径
    override suspend fun rename(parent: String, item: RemoteItem, newName: String) = withContext(Dispatchers.IO) {
        val list = org.json.JSONArray().put(JSONObject().put("path", item.path).put("newname", com.aisub.player.remote.checkName(newName)))
        post("/xpan/file", mapOf("method" to "filemanager", "opera" to "rename"), mapOf("async" to "0", "filelist" to list.toString(), "ondup" to "fail"))
        Unit
    }

    /** 移到回收站（10 天内可在百度网盘里恢复）。 */
    override suspend fun delete(parent: String, items: List<RemoteItem>) = withContext(Dispatchers.IO) {
        val list = org.json.JSONArray(items.map { it.path })
        post("/xpan/file", mapOf("method" to "filemanager", "opera" to "delete"), mapOf("async" to "0", "filelist" to list.toString()))
        Unit
    }

    override suspend fun mkdir(parent: String, name: String) = withContext(Dispatchers.IO) {
        val path = parent.ifEmpty { "/" }.trimEnd('/') + "/" + com.aisub.player.remote.checkName(name)
        post("/xpan/file", mapOf("method" to "create"), mapOf("path" to path, "isdir" to "1", "rtype" to "0"))
        Unit
    }

    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val dir = path.ifEmpty { "/" }
        val items = ArrayList<RemoteItem>()
        var start = 0
        while (true) {
            val json = get(
                "/xpan/file",
                mapOf("method" to "list", "dir" to dir, "web" to "web", "order" to "name", "start" to "$start", "limit" to "$PAGE"),
            )
            val list = json.optJSONArray("list")
            val n = list?.length() ?: 0
            for (i in 0 until n) {
                val f = list!!.getJSONObject(i)
                val name = f.optString("server_filename")
                if (f.optInt("isdir") == 1) items.add(RemoteItem(name, f.optString("path"), isDir = true))
                else {
                    val fsId = f.optLong("fs_id").toString()
                    items.add(RemoteItem(name, f.optString("path"), isDir = false, size = f.optLong("size"), playUrl = playUri(fsId, name)))
                }
            }
            if (n < PAGE) break
            start += PAGE
        }
        items.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    override fun resolve(fileRef: String): ResolvedLink {
        val json = get("/xpan/multimedia", mapOf("method" to "filemetas", "fsids" to "[$fileRef]", "dlink" to "1"))
        val dlink = json.optJSONArray("list")?.optJSONObject(0)?.optString("dlink").orEmpty()
        if (dlink.isEmpty()) throw IOException("百度网盘：没有拿到下载地址")
        // dlink 需要带 access_token 和 UA=pan.baidu.com 访问，302 到真实地址
        val req = Request.Builder().url("$dlink&access_token=${accessToken()}").head().header("User-Agent", UA).build()
        val location = cloudHttpNoRedirect.newCall(req).execute().use { it.header("Location") }
        return ResolvedLink(location ?: "$dlink&access_token=${accessToken()}", mapOf("User-Agent" to UA), validMs = 30 * 60_000L)
    }

    companion object {
        private const val PAGE = 1000
        const val UA = "pan.baidu.com"
        const val DEFAULT_RENEW_API = "https://api.oplist.org/baiduyun/renewapi"

        /** 设备码授权：需要自己的 AppKey。 */
        fun startDeviceCode(clientId: String): QrSession {
            val url = "https://openapi.baidu.com/oauth/2.0/device/code".toHttpUrl().newBuilder()
                .addQueryParameter("response_type", "device_code")
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("scope", "basic,netdisk")
                .build()
            val json = cloudHttp.newCall(Request.Builder().url(url).build()).execute().use { it.json() }
            val deviceCode = json.optString("device_code")
            if (deviceCode.isEmpty()) {
                throw IOException("百度授权失败：${json.optString("error_description").ifEmpty { json.toString().take(200) }}")
            }
            return QrSession(
                imageUrl = json.optString("qrcode_url"),
                content = null,
                hint = "用百度网盘 App 扫码，或在 ${json.optString("verification_url")} 输入 ${json.optString("user_code")}",
                state = mapOf("device_code" to deviceCode, "client_id" to clientId),
            )
        }

        /** 轮询设备码授权结果，成功时返回凭据 JSON。 */
        fun pollDeviceCode(s: QrSession, clientSecret: String): Pair<QrStatus, String?> {
            val url = "https://openapi.baidu.com/oauth/2.0/token".toHttpUrl().newBuilder()
                .addQueryParameter("grant_type", "device_token")
                .addQueryParameter("code", s.state["device_code"])
                .addQueryParameter("client_id", s.state["client_id"])
                .addQueryParameter("client_secret", clientSecret)
                .build()
            val json = cloudHttp.newCall(Request.Builder().url(url).build()).execute().use { it.json() }
            val refresh = json.optString("refresh_token")
            if (refresh.isNotEmpty()) {
                return QrStatus.SUCCESS to JSONObject()
                    .put("client_id", s.state["client_id"]).put("client_secret", clientSecret)
                    .put("refresh_token", refresh).put("access_token", json.optString("access_token"))
                    .toString()
            }
            return when (json.optString("error")) {
                "expired_token" -> QrStatus.EXPIRED to null
                "authorization_declined" -> QrStatus.CANCELED to null
                else -> QrStatus.WAITING to null
            }
        }

        /** 直接使用 refresh_token（由在线接口续期），返回凭据 JSON。 */
        fun credsFromRefreshToken(refreshToken: String, renewApi: String): String =
            JSONObject().put("refresh_token", refreshToken.trim()).put("renew_api", renewApi.trim()).toString()
    }
}
