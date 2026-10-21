package com.aisub.player.remote.cloud

import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * PikPak（非公开接口，模拟网页版客户端）。登录方式：账号 + 密码。
 * 每次请求需要"验证码 token"，由设备 ID + 时间戳经一串 MD5 算出签名后向服务器申请。
 * 参考 OpenList drivers/pikpak。
 * 凭据：creds.username / password / device_id / refresh_token / access_token / user_id / captcha_token
 */
class PikPakClient(server: Server) : CloudClient(server) {

    class VerificationRequired(val url: String) : IOException("PikPak 需要人机验证")

    private val deviceId: String
        get() = creds.optString("device_id").ifEmpty {
            md5Hex(creds.optString("username") + creds.optString("password")).also { creds.put("device_id", it) }
        }

    private fun baseRequest(url: String) = Request.Builder().url(url)
        .header("User-Agent", UA)
        .header("X-Device-ID", deviceId)
        .apply {
            creds.optString("captcha_token").takeIf { it.isNotEmpty() }?.let { header("X-Captcha-Token", it) }
            creds.optString("access_token").takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") }
        }

    private fun captchaSign(timestamp: String): String {
        var s = CLIENT_ID + CLIENT_VERSION + PACKAGE_NAME + deviceId + timestamp
        for (alg in ALGORITHMS) s = md5Hex(s + alg)
        return "1.$s"
    }

    /** 申请验证码 token。action 形如 "GET:/drive/v1/files"。 */
    @Synchronized
    private fun refreshCaptcha(action: String, meta: JSONObject) {
        val ts = System.currentTimeMillis().toString()
        meta.put("client_version", CLIENT_VERSION).put("package_name", PACKAGE_NAME)
            .put("timestamp", ts).put("captcha_sign", captchaSign(ts))
        val body = JSONObject()
            .put("action", action)
            .put("captcha_token", creds.optString("captcha_token"))
            .put("client_id", CLIENT_ID)
            .put("device_id", deviceId)
            .put("meta", meta)
            .put("redirect_uri", "xlaccsdk01://xbase.cloud/callback?state=harbor")
        val req = baseRequest("$USER_API/v1/shield/captcha/init?client_id=$CLIENT_ID")
            .removeHeader("Authorization")
            .post(body.toString().toRequestBody(JSON)).build()
        val json = cloudHttp.newCall(req).execute().use { it.json() }
        checkError(json)
        val verifyUrl = json.optString("url")
        if (verifyUrl.isNotEmpty()) throw VerificationRequired(verifyUrl)
        creds.put("captcha_token", json.getString("captcha_token"))
    }

    @Synchronized
    fun login() {
        val username = creds.optString("username")
        creds.remove("access_token")
        val meta = JSONObject()
        when {
            Regex("""\w+([-+.]\w+)*@\w+([-.]\w+)*\.\w+([-.]\w+)*""").matches(username) -> meta.put("email", username)
            username.length in 11..18 -> meta.put("phone_number", username)
            else -> meta.put("username", username)
        }
        refreshCaptcha("POST:/v1/auth/signin", meta)
        val body = JSONObject()
            .put("captcha_token", creds.optString("captcha_token"))
            .put("client_id", CLIENT_ID).put("client_secret", CLIENT_SECRET)
            .put("username", username).put("password", creds.optString("password"))
        val req = baseRequest("$USER_API/v1/auth/signin?client_id=$CLIENT_ID")
            .post(body.toString().toRequestBody(JSON)).build()
        val json = cloudHttp.newCall(req).execute().use { it.json() }
        checkError(json)
        saveTokens(json)
        refreshCaptcha("GET:/drive/v1/files", JSONObject().put("user_id", creds.optString("user_id")))
        saveCreds()
    }

    @Synchronized
    private fun refreshToken() {
        val body = JSONObject()
            .put("client_id", CLIENT_ID).put("client_secret", CLIENT_SECRET)
            .put("grant_type", "refresh_token").put("refresh_token", creds.optString("refresh_token"))
        val req = Request.Builder().url("$USER_API/v1/auth/token?client_id=$CLIENT_ID")
            .post(body.toString().toRequestBody(JSON)).build()
        val json = cloudHttp.newCall(req).execute().use { it.json() }
        if (json.optInt("error_code") == 4126) {
            login() // refresh_token 失效，用账号密码重新登录
            return
        }
        checkError(json)
        saveTokens(json)
        saveCreds()
    }

    private fun saveTokens(json: JSONObject) {
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        if (access.isEmpty() || refresh.isEmpty()) throw IOException("PikPak 登录失败：服务器没有返回 token")
        creds.put("access_token", access).put("refresh_token", refresh).put("user_id", json.optString("sub"))
    }

    private fun request(url: String, retries: Int = 2): JSONObject {
        if (creds.optString("access_token").isEmpty()) {
            if (creds.optString("refresh_token").isNotEmpty()) refreshToken() else login()
        }
        val json = cloudHttp.newCall(baseRequest(url).build()).execute().use { it.json() }
        val code = json.optInt("error_code")
        if (code != 0 && retries > 0) {
            when (code) {
                4122, 4121, 16 -> { refreshToken(); return request(url, retries - 1) }
                9 -> {
                    val path = url.toHttpUrl().encodedPath
                    refreshCaptcha("GET:$path", JSONObject().put("user_id", creds.optString("user_id")))
                    saveCreds()
                    return request(url, retries - 1)
                }
            }
        }
        checkError(json)
        return json
    }

    private fun checkError(json: JSONObject) {
        if (json.optInt("error_code") == 0) return
        val msg = json.optString("error_description").ifEmpty { json.optString("error") }
        throw IOException("PikPak：$msg（${json.optInt("error_code")}）")
    }

    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val items = ArrayList<RemoteItem>()
        var pageToken = ""
        do {
            val url = "$DRIVE_API/drive/v1/files".toHttpUrl().newBuilder()
                .addQueryParameter("parent_id", path)
                .addQueryParameter("thumbnail_size", "SIZE_LARGE")
                .addQueryParameter("with_audit", "true")
                .addQueryParameter("limit", "100")
                .addQueryParameter("filters", """{"phase":{"eq":"PHASE_TYPE_COMPLETE"},"trashed":{"eq":false}}""")
                .addQueryParameter("page_token", pageToken)
                .build()
            val json = request(url.toString())
            val files = json.optJSONArray("files")
            for (i in 0 until (files?.length() ?: 0)) {
                val f = files!!.getJSONObject(i)
                val id = f.getString("id")
                val name = f.optString("name")
                if (f.optString("kind") == "drive#folder") items.add(RemoteItem(name, id, isDir = true))
                else items.add(RemoteItem(name, id, isDir = false, size = f.optString("size").toLongOrNull() ?: 0, playUrl = playUri(id, name)))
            }
            pageToken = json.optString("next_page_token")
        } while (pageToken.isNotEmpty())
        items.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    override fun resolve(fileRef: String): ResolvedLink {
        val json = request("$DRIVE_API/drive/v1/files/$fileRef?_magic=2021&usage=FETCH&thumbnail_size=SIZE_LARGE")
        val url = json.optString("web_content_link").ifEmpty {
            json.optJSONArray("medias")?.optJSONObject(0)?.optJSONObject("link")?.optString("url").orEmpty()
        }
        if (url.isEmpty()) throw IOException("PikPak：没有拿到下载地址")
        return ResolvedLink(url, validMs = 30 * 60_000L)
    }

    companion object {
        private const val USER_API = "https://user.mypikpak.net"
        private const val DRIVE_API = "https://api-drive.mypikpak.net"
        private val JSON = "application/json".toMediaType()

        // 网页版客户端参数
        private const val CLIENT_ID = "YUMx5nI8ZU8Ap8pm"
        private const val CLIENT_SECRET = "dbw2OtmVEeuUvIptb1Coyg"
        private const val CLIENT_VERSION = "2.0.0"
        private const val PACKAGE_NAME = "mypikpak.com"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36"
        private val ALGORITHMS = listOf(
            "C9qPpZLN8ucRTaTiUMWYS9cQvWOE", "+r6CQVxjzJV6LCV", "F", "pFJRC",
            "9WXYIDGrwTCz2OiVlgZa90qpECPD6olt", "/750aCr4lm/Sly/c", "RB+DT/gZCrbV", "",
            "CyLsf7hdkIRxRm215hl", "7xHvLi2tOYP0Y92b", "ZGTXXxu8E/MIWaEDB+Sm/", "1UI3",
            "E7fP5Pfijd+7K+t6Tg/NhuLq0eEUVChpJSkrKxpO", "ihtqpG6FMt65+Xk+tWUH2", "NhXXU9rg4XXdzo7u5o",
        )

        /**
         * 用账号密码登录，返回凭据 JSON；需要人机验证时抛出 [VerificationRequired]。
         * 完成验证后，验证页最终跳转的地址里带有 captcha_token，填进来再登录即可。
         */
        fun loginWithPassword(username: String, password: String, captchaToken: String = ""): String {
            val token = Regex("captcha_token=([^&]+)").find(captchaToken)?.groupValues?.get(1) ?: captchaToken.trim()
            val temp = Server(type = com.aisub.player.remote.ServerType.PIKPAK, name = "", baseUrl = "",
                extra = JSONObject().put("username", username.trim()).put("password", password)
                    .put("captcha_token", token).toString())
            val client = PikPakClient(temp)
            var result = ""
            client.onCredentialsChanged = { result = it.extra }
            client.login()
            return result
        }
    }
}
