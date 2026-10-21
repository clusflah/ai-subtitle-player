package com.aisub.player.remote.cloud


import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 阿里云盘（开放平台接口 openapi.alipan.com）。
 * 登录走"阿里云盘 TV 版"的扫码授权：用阿里云盘 App 扫码，不需要开发者密钥。
 * 参考 OpenList drivers/aliyundrive_open 与 OpenList-APIPages alicloud_tv。
 * 凭据：creds.refresh_token / access_token / drive_id
 */
class AliyunClient(server: Server) : CloudClient(server) {

    @Synchronized
    private fun accessToken(forceRefresh: Boolean = false): String {
        val cached = creds.optString("access_token")
        if (cached.isNotEmpty() && !forceRefresh) return cached
        val tokens = JSONObject(TvAuth().exchange("refresh_token", creds.getString("refresh_token")))
        creds.put("access_token", tokens.getString("access_token"))
        creds.put("refresh_token", tokens.optString("refresh_token").ifEmpty { creds.getString("refresh_token") })
        saveCreds()
        return creds.getString("access_token")
    }

    private fun api(path: String, body: JSONObject, retried: Boolean = false): JSONObject {
        val req = Request.Builder().url(API + path)
            .header("Authorization", "Bearer " + accessToken())
            .post(body.toString().toRequestBody(JSON))
            .build()
        val json = cloudHttp.newCall(req).execute().use { it.json() }
        val code = json.optString("code")
        if (code.isNotEmpty()) {
            if (!retried && code in setOf("AccessTokenInvalid", "AccessTokenExpired", "I400JD")) {
                accessToken(forceRefresh = true)
                return api(path, body, retried = true)
            }
            throw IOException("阿里云盘：${json.optString("message").ifEmpty { code }}")
        }
        return json
    }

    @Synchronized
    private fun driveId(): String {
        creds.optString("drive_id").takeIf { it.isNotEmpty() }?.let { return it }
        val info = api("/adrive/v1.0/user/getDriveInfo", JSONObject())
        // 优先"资源库"，没有则用备份盘
        val id = info.optString("resource_drive_id").ifEmpty { info.optString("default_drive_id") }
        creds.put("drive_id", id)
        saveCreds()
        return id
    }

    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val parent = path.ifEmpty { "root" }
        val items = ArrayList<RemoteItem>()
        var marker = ""
        do {
            val json = api(
                "/adrive/v1.0/openFile/list",
                JSONObject().put("drive_id", driveId()).put("parent_file_id", parent)
                    .put("limit", 200).put("marker", marker)
                    .put("order_by", "name").put("order_direction", "ASC"),
            )
            val arr = json.optJSONArray("items")
            for (i in 0 until (arr?.length() ?: 0)) {
                val f = arr!!.getJSONObject(i)
                val id = f.getString("file_id")
                val name = f.optString("name")
                if (f.optString("type") == "folder") items.add(RemoteItem(name, id, isDir = true))
                else items.add(RemoteItem(name, id, isDir = false, size = f.optLong("size"), playUrl = playUri(id, name)))
            }
            marker = json.optString("next_marker")
        } while (marker.isNotEmpty())
        items.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    override fun resolve(fileRef: String): ResolvedLink {
        val json = api(
            "/adrive/v1.0/openFile/getDownloadUrl",
            JSONObject().put("drive_id", driveId()).put("file_id", fileRef).put("expire_sec", 14400),
        )
        val url = json.optString("url").ifEmpty { throw IOException("阿里云盘：没有拿到下载地址") }
        return ResolvedLink(url, mapOf("Referer" to "https://www.alipan.com/"), validMs = 60 * 60_000L)
    }

    /**
     * 阿里云盘 TV 版的授权接口（api.extscreen.com）：请求和响应都用 AES-CBC 加密，
     * 密钥由设备参数和时间戳派生。
     */
    class TvAuth {
        private val uniqueId = UUID.randomUUID().toString().replace("-", "")
        private val wifiMac = (100000000000L + (Math.random() * 900000000000L).toLong()).toString()
        private val model = "SM-S908E"
        private val brand = "samsung"
        private val akv = "2.6.1143"
        private val apv = "1.4.0.2"
        private val timestamp: String = fetchTimestamp()

        private fun fetchTimestamp(): String = runCatching {
            cloudHttp.newCall(Request.Builder().url("https://api.extscreen.com/timestamp").build()).execute()
                .use { it.json().getJSONObject("data").get("timestamp").toString() }
        }.getOrElse { (System.currentTimeMillis() / 1000).toString() }

        /** 参数按键名排序后拼接（不含 t）。 */
        private fun paramString(): String = sortedMapOf(
            "akv" to akv, "apv" to apv, "b" to brand, "d" to uniqueId, "m" to model,
            "mac" to "", "n" to model, "wifiMac" to wifiMac,
        ).values.joinToString("")

        private fun key(t: String): String {
            val unique = LinkedHashSet<Char>()
            paramString().forEach { unique.add(it) }
            val modifier = (if (t.length > 7) t.substring(7) else "0").toLongOrNull() ?: 0L
            val modVal = (modifier % 127).toInt()
            val transformed = StringBuilder()
            for (c in unique) {
                var code = kotlin.math.abs(c.code - modVal - 1)
                if (code < 33) code += 33
                transformed.append(code.toChar())
            }
            return md5Hex(transformed.toString())
        }

        private fun encrypt(plain: JSONObject): JSONObject {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            val iv = (1..16).map { chars.random() }.joinToString("")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(timestamp).toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
            val text = plain.toString().replace(Regex("\\s"), "")
            val ct = java.util.Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray()))
            return JSONObject().put("iv", iv).put("ciphertext", ct)
        }

        private fun decrypt(ciphertext: String, ivHex: String, t: String): String {
            val iv = ByteArray(ivHex.length / 2) { ivHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(t).toByteArray(), "AES"), IvParameterSpec(iv))
            return String(cipher.doFinal(java.util.Base64.getMimeDecoder().decode(ciphertext)))
        }

        private fun call(apiPath: String, body: JSONObject): String {
            val sign = sha256Hex("POST-/api$apiPath-$timestamp-$uniqueId-${key(timestamp)}")
            val req = Request.Builder().url("https://api.extscreen.com/aliyundrive$apiPath")
                .header("User-Agent", "Mozilla/5.0 (Linux; U; Android 15; zh-cn; SM-S908E Build/UKQ1.231108.001) AppleWebKit/533.1 (KHTML, like Gecko) Mobile Safari/533.1")
                .header("akv", akv).header("apv", apv).header("b", brand).header("d", uniqueId)
                .header("m", model).header("n", model).header("t", timestamp).header("wifiMac", wifiMac)
                .header("sign", sign)
                .post(encrypt(body).toString().toRequestBody(JSON))
                .build()
            val json = cloudHttp.newCall(req).execute().use { it.json() }
            if (json.has("code") && json.optInt("code") != 200) throw IOException("阿里云盘授权失败：${json.optString("message")}")
            val data = json.getJSONObject("data")
            return decrypt(data.getString("ciphertext"), data.getString("iv"), json.opt("t")?.toString() ?: timestamp)
        }

        /** 用授权码或 refresh_token 换 token，返回 JSON 字符串（含 access_token、refresh_token）。 */
        fun exchange(field: String, value: String): String = call("/v4/token", JSONObject().put(if (field == "code") "code" else "refresh_token", value))

        fun qrcode(): JSONObject = JSONObject(
            call("/v2/qrcode", JSONObject().put("scopes", "user:base,file:all:read,file:all:write").put("width", 500).put("height", 500))
        )
    }

    companion object {
        private const val API = "https://openapi.alipan.com"
        private val JSON = "application/json".toMediaType()

        fun startQr(): QrSession {
            val data = TvAuth().qrcode()
            return QrSession(
                imageUrl = data.optString("qrCodeUrl"),
                content = null,
                hint = "打开阿里云盘 App，扫一扫授权",
                state = mapOf("sid" to data.getString("sid")),
            )
        }

        fun pollQr(s: QrSession): Pair<QrStatus, String?> {
            val json = cloudHttp.newCall(
                Request.Builder().url("$API/oauth/qrcode/${s.state["sid"]}/status").build()
            ).execute().use { it.json() }
            return when (json.optString("status")) {
                "LoginSuccess" -> QrStatus.SUCCESS to json.optString("authCode")
                "ScanSuccess" -> QrStatus.SCANNED to null
                "QRCodeExpired" -> QrStatus.EXPIRED to null
                else -> QrStatus.WAITING to null
            }
        }

        /** 用扫码得到的授权码换 token，返回凭据 JSON。 */
        fun finishQr(authCode: String): String {
            val tokens = JSONObject(TvAuth().exchange("code", authCode))
            return JSONObject()
                .put("access_token", tokens.getString("access_token"))
                .put("refresh_token", tokens.getString("refresh_token"))
                .toString()
        }
    }
}
