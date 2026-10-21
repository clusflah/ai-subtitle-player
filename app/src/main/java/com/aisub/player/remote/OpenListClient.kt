package com.aisub.player.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenList（原 AList）HTTP API 客户端。
 * 列目录用 /api/fs/list；播放地址用 /d/<路径>?sign=<签名>，
 * 由 OpenList 重定向到网盘直链，播放器不需要携带任何鉴权头。
 */
class OpenListClient(private val server: Server) : RemoteClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val base = server.normalizedBase

    @Volatile
    private var token: String? = null

    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        // 服务端每页最多 500 条，大目录需要翻页
        val all = ArrayList<JSONObject>()
        var page = 1
        while (page <= MAX_PAGES) {
            val body = JSONObject()
                .put("path", path.ifEmpty { "/" })
                .put("password", "")
                .put("page", page)
                .put("per_page", PAGE_SIZE)
                .put("refresh", false)
            val data = post("/api/fs/list", body, auth = true)
            val content = data.optJSONArray("content") ?: break
            for (i in 0 until content.length()) all.add(content.getJSONObject(i))
            val total = data.optInt("total", all.size)
            if (content.length() < PAGE_SIZE || all.size >= total) break
            page++
        }
        all.map { o ->
            val name = o.getString("name")
            val isDir = o.optBoolean("is_dir")
            val itemPath = joinPath(path.ifEmpty { "/" }, name)
            val sign = o.optString("sign")
            val playUrl = if (isDir) null
            else "$base/d" + encodePath(itemPath) + if (sign.isNotEmpty()) "?sign=$sign" else ""
            RemoteItem(name, itemPath, isDir, o.optLong("size"), playUrl)
        }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    private fun login(): String? {
        if (server.username.isEmpty()) return null // 访客访问
        val body = JSONObject().put("username", server.username).put("password", server.password)
        return post("/api/auth/login", body, auth = false).getString("token").also { token = it }
    }

    private fun post(api: String, body: JSONObject, auth: Boolean, retried: Boolean = false): JSONObject {
        val t = if (auth) token ?: login() else null
        val request = Request.Builder()
            .url(base + api)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (t != null) header("Authorization", t) }
            .build()
        val json = http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("OpenList 请求失败 HTTP ${resp.code}")
            JSONObject(resp.body?.string().orEmpty())
        }
        val code = json.optInt("code")
        if (code == 401 && auth && !retried) {
            // token 过期，重新登录一次
            token = null
            return post(api, body, auth, retried = true)
        }
        if (code != 200) throw IOException("OpenList：${json.optString("message").ifEmpty { "错误 $code" }}")
        return json.optJSONObject("data") ?: JSONObject()
    }

    companion object {
        private const val PAGE_SIZE = 500
        private const val MAX_PAGES = 20
    }
}
