package com.aisub.player.remote

import com.aisub.player.media.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
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

    private val http = com.aisub.player.net.AppProxy.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 内网 / 外网地址里当前能用的那个；会联网探测，只在 IO 线程里用 */
    private val base get() = Endpoints.base(server)

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

    /** 按关键词检索 OpenList 中的视频或剧集目录；若服务端未开启全局索引则回退扫描根目录。 */
    suspend fun searchVideos(query: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val rootDir = server.rootPath.ifBlank { "/" }
        val searched = runCatching {
            val body = JSONObject()
                .put("parent", rootDir)
                .put("keywords", q)
                .put("scope", 0)
                .put("page", 1)
                .put("per_page", 20)
                .put("password", "")
            val data = post("/api/fs/search", body, auth = true)
            val content = data.optJSONArray("content") ?: return@runCatching emptyList()
            val out = ArrayList<RemoteItem>()
            for (i in 0 until content.length()) {
                val o = content.getJSONObject(i)
                val name = o.optString("name").ifBlank { continue }
                val isDir = o.optBoolean("is_dir")
                if (!isDir && !MediaEntry.isVideoName(name)) continue
                val parent = o.optString("parent").ifBlank { rootDir }
                val itemPath = joinPath(parent, name)
                val playUrl = if (isDir) null else resolveFilePlayUrl(itemPath, o.optString("sign"))
                out += RemoteItem(name, itemPath, isDir, o.optLong("size"), playUrl)
                if (out.size >= 10) break
            }
            out
        }.getOrDefault(emptyList())
        if (searched.isNotEmpty()) return@withContext searched
        runCatching {
            list(rootDir).filter {
                it.name.contains(q, ignoreCase = true) && (it.isDir || MediaEntry.isVideoName(it.name))
            }.take(8)
        }.getOrDefault(emptyList())
    }

    private fun resolveFilePlayUrl(itemPath: String, existingSign: String): String {
        val sign = existingSign.ifBlank {
            runCatching {
                post("/api/fs/get", JSONObject().put("path", itemPath).put("password", ""), auth = true)
                    .optString("sign")
            }.getOrDefault("")
        }
        return "$base/d" + encodePath(itemPath) + if (sign.isNotEmpty()) "?sign=$sign" else ""
    }

    override suspend fun rename(parent: String, item: RemoteItem, newName: String) = withContext(Dispatchers.IO) {
        post("/api/fs/rename", JSONObject().put("path", item.path).put("name", checkName(newName)), auth = true)
        Unit
    }

    override suspend fun delete(parent: String, items: List<RemoteItem>) = withContext(Dispatchers.IO) {
        val names = org.json.JSONArray(items.map { it.name })
        post("/api/fs/remove", JSONObject().put("dir", parent.ifEmpty { "/" }).put("names", names), auth = true)
        Unit
    }

    override suspend fun mkdir(parent: String, name: String) = withContext(Dispatchers.IO) {
        post("/api/fs/mkdir", JSONObject().put("path", joinPath(parent.ifEmpty { "/" }, checkName(name))), auth = true)
        Unit
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
