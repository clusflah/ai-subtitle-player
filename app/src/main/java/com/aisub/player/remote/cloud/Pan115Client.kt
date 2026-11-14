package com.aisub.player.remote.cloud

import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * 115 网盘（网页接口 + Cookie）。登录方式：用 115 App 扫码。
 * 参考 SheltonZhu/115driver 与 OpenList drivers/115。
 * 凭据：creds.cookie = "UID=..;CID=..;SEID=..;KID=.."
 */
class Pan115Client(server: Server) : CloudClient(server) {

    private val cookie get() = creds.optString("cookie")

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", UA)
        .header("Cookie", cookie)

    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val cid = path.ifEmpty { "0" }
        val items = ArrayList<RemoteItem>()
        var offset = 0
        while (true) {
            val url = "https://webapi.115.com/files".toHttpUrl().newBuilder()
                .addQueryParameter("aid", "1").addQueryParameter("cid", cid)
                .addQueryParameter("o", "file_name").addQueryParameter("asc", "1")
                .addQueryParameter("offset", offset.toString()).addQueryParameter("show_dir", "1")
                .addQueryParameter("limit", PAGE.toString()).addQueryParameter("snap", "0")
                .addQueryParameter("natsort", "1").addQueryParameter("record_open_time", "1")
                .addQueryParameter("format", "json").addQueryParameter("fc_mix", "0")
                .build()
            val json = cloudHttp.newCall(request(url.toString()).build()).execute().use { it.json() }
            check(json)
            val data = json.optJSONArray("data") ?: break
            for (i in 0 until data.length()) {
                val f = data.getJSONObject(i)
                val name = f.optString("n")
                val fid = f.optString("fid")
                if (fid.isEmpty()) {
                    // 没有 fid 的是目录，目录 ID 在 cid
                    items.add(RemoteItem(name, f.optString("cid"), isDir = true))
                } else {
                    val pickCode = f.optString("pc")
                    items.add(RemoteItem(name, fid, isDir = false, size = f.optLong("s"), playUrl = playUri(pickCode, name)))
                }
            }
            offset += PAGE
            if (offset >= json.optInt("count") || data.length() == 0) break
        }
        items.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    override fun resolve(fileRef: String): ResolvedLink {
        val key = M115.generateKey()
        val data = M115.encode(JSONObject().put("pickcode", fileRef).toString().toByteArray(), key)
        val url = "https://proapi.115.com/app/chrome/downurl?t=${System.currentTimeMillis() / 1000}"
        val req = request(url).post(FormBody.Builder().add("data", data).build()).build()
        val (json, setCookies) = cloudHttp.newCall(req).execute().use { it.json() to it.headers("Set-Cookie") }
        check(json)
        val decoded = JSONObject(String(M115.decode(json.getString("data"), key)))
        val info = decoded.keys().asSequence().map { decoded.getJSONObject(it) }.firstOrNull()
            ?: throw IOException("115：没有拿到下载地址")
        val link = info.optJSONObject("url")?.optString("url").orEmpty()
        if (link.isEmpty()) throw IOException("115：文件暂时无法下载")
        // 下载链接与获取它时的 UA、Cookie 绑定，播放时要原样带上
        val cookies = (listOf(cookie) + setCookies.map { it.substringBefore(';') }).filter { it.isNotBlank() }
        return ResolvedLink(link, mapOf("User-Agent" to UA, "Cookie" to cookies.joinToString("; ")), validMs = 30 * 60_000L)
    }

    private fun postForm(url: String, form: FormBody): JSONObject {
        val json = cloudHttp.newCall(request(url).post(form).build()).execute().use { it.json() }
        check(json)
        return json
    }

    // 目录的定位符是 cid，文件是 fid，接口里两者通用
    override suspend fun rename(parent: String, item: RemoteItem, newName: String) = withContext(Dispatchers.IO) {
        postForm(
            "https://webapi.115.com/files/batch_rename",
            FormBody.Builder().add("files_new_name[${item.path}]", com.aisub.player.remote.checkName(newName)).build(),
        )
        Unit
    }

    override suspend fun delete(parent: String, items: List<RemoteItem>) = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("pid", parent.ifEmpty { "0" }).add("ignore_warn", "1")
        items.forEachIndexed { i, item -> form.add("fid[$i]", item.path) }
        postForm("https://webapi.115.com/rb/delete", form.build())
        Unit
    }

    override suspend fun mkdir(parent: String, name: String) = withContext(Dispatchers.IO) {
        postForm(
            "https://webapi.115.com/files/add",
            FormBody.Builder().add("pid", parent.ifEmpty { "0" }).add("cname", com.aisub.player.remote.checkName(name)).build(),
        )
        Unit
    }

    /** 提交磁力 / ed2k / HTTP 链接到 115 离线下载任务，返回任务名或状态说明；失败抛出 IOException。 */
    suspend fun addOfflineUrl(url: String, saveCid: String? = null): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cleanUrl = url.trim()
            if (cleanUrl.isEmpty()) throw IOException("链接不能为空")
            val spaceJson = runCatching {
                cloudHttp.newCall(request("https://115.com/?ct=offline&ac=space").build()).execute().use { it.json() }
            }.getOrNull()
            val sign = spaceJson?.optString("sign").orEmpty()
            val time = spaceJson?.opt("time")?.toString()?.takeIf { it != "null" && it.isNotBlank() }.orEmpty()
            var uid = Regex("""UID=([^_;]+)""").find(cookie)?.groupValues?.getOrNull(1).orEmpty()
            if (uid.isEmpty()) {
                uid = runCatching {
                    cloudHttp.newCall(request("https://my.115.com/?ct=ajax&ac=nav").build()).execute().use { it.json() }
                        .optJSONObject("data")?.opt("user_id")?.toString().orEmpty()
                }.getOrDefault("")
            }
            val form = okhttp3.FormBody.Builder().apply {
                add("url", cleanUrl)
                if (uid.isNotEmpty()) add("uid", uid)
                if (sign.isNotEmpty()) add("sign", sign)
                if (time.isNotEmpty()) add("time", time)
                if (!saveCid.isNullOrBlank()) add("wp_path_id", saveCid)
            }.build()
            val req = okhttp3.Request.Builder()
                .url("https://115.com/web/lixian/?ct=lixian&ac=add_task_url")
                .header("Cookie", cookie)
                .header("User-Agent", UA)
                .header("Origin", "https://115.com")
                .header("Referer", "https://115.com/")
                .post(form)
                .build()
            val json = cloudHttp.newCall(req).execute().use { it.json() }
            if (!json.optBoolean("state", true)) {
                val msg = json.optString("error_msg")
                    .ifEmpty { json.optString("error") }
                    .ifEmpty { json.optString("msg") }
                    .ifEmpty { "errcode ${json.optInt("errcode", json.optInt("errno"))}" }
                if (json.optInt("errno") == 99 || json.optInt("errcode") == 99 || msg.contains("登录")) {
                    throw IOException("115 登录已失效，请重新扫码")
                }
                throw IOException("115 离线添加失败：$msg")
            }
            val taskName = json.optString("name").trim()
            if (taskName.isNotEmpty()) "已添加到 115：$taskName" else "已添加到 115 离线下载"
        }

    private fun check(json: JSONObject) {
        if (json.optBoolean("state", true)) return
        val msg = json.optString("error").ifEmpty { json.optString("msg") }.ifEmpty { "errno ${json.optInt("errno")}" }
        if (json.optInt("errno") == 99 || msg.contains("登录")) throw IOException("115 登录已失效，请重新扫码")
        throw IOException("115：$msg")
    }

    companion object {
        private const val PAGE = 1000
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36 115Browser/27.0.7.5"

        /** 申请扫码会话：二维码图片 + 轮询需要的 uid/time/sign。 */
        fun startQr(): QrSession {
            val json = cloudHttp.newCall(Request.Builder().url("https://qrcodeapi.115.com/api/1.0/web/1.0/token").build())
                .execute().use { it.json() }
            val d = json.getJSONObject("data")
            val uid = d.getString("uid")
            return QrSession(
                imageUrl = "https://qrcodeapi.115.com/api/1.0/mac/1.0/qrcode?uid=$uid",
                content = d.optString("qrcode"),
                hint = "打开 115 App，扫一扫登录",
                state = mapOf("uid" to uid, "time" to d.optLong("time").toString(), "sign" to d.getString("sign")),
            )
        }

        fun pollQr(s: QrSession): QrStatus {
            val url = "https://qrcodeapi.115.com/get/status/".toHttpUrl().newBuilder()
                .addQueryParameter("uid", s.state["uid"]).addQueryParameter("time", s.state["time"])
                .addQueryParameter("sign", s.state["sign"]).addQueryParameter("_", (System.currentTimeMillis() / 1000).toString())
                .build()
            val json = cloudHttp.newCall(Request.Builder().url(url).build()).execute().use { it.json() }
            return when (json.optJSONObject("data")?.optInt("status") ?: 0) {
                1 -> QrStatus.SCANNED
                2 -> QrStatus.SUCCESS
                -1 -> QrStatus.EXPIRED
                -2 -> QrStatus.CANCELED
                else -> QrStatus.WAITING
            }
        }

        /**
         * 扫码确认后换取 Cookie。用"支付宝小程序"端登录，避免把手机 App 或网页端的登录挤下线。
         * 返回凭据 JSON。
         */
        fun finishQr(s: QrSession): String {
            val app = "alipaymini"
            val req = Request.Builder()
                .url("https://passportapi.115.com/app/1.0/$app/1.0/login/qrcode")
                .post(FormBody.Builder().add("account", s.state["uid"]!!).add("app", app).build())
                .build()
            val json = cloudHttp.newCall(req).execute().use { it.json() }
            val c = json.optJSONObject("data")?.optJSONObject("cookie")
                ?: throw IOException("115 登录失败：${json.optString("message").ifEmpty { json.toString().take(200) }}")
            val cookie = listOf("UID", "CID", "SEID", "KID").joinToString(";") { "$it=${c.optString(it)}" }
            return JSONObject().put("cookie", cookie).toString()
        }
    }
}
