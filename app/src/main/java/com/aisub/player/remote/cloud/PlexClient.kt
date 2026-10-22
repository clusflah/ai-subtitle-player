package com.aisub.player.remote.cloud

import android.net.Uri
import com.aisub.player.remote.ItemMeta
import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Plex 媒体服务器。登录走 plex.tv 的 PIN 授权（浏览器打开 plex.tv 确认），然后自动找到账号下的服务器。
 * 服务器有多个地址（局域网、公网、中继），按顺序试，记住能连上的那个。
 * creds：token（服务器访问令牌）/ connections（地址列表）/ client_id；定位符："" 根目录、"s:<key>" 媒体库、"m:<ratingKey>" 剧集/季。
 */
class PlexClient(server: Server) : CloudClient(server) {

    private val token get() = creds.optString("token")

    /** 当前可用的服务器地址；失败时依次尝试其他地址。 */
    @Volatile
    private var baseUrl: String? = creds.optString("base").ifEmpty { null }

    private fun connections(): List<String> {
        val arr = creds.optJSONArray("connections") ?: JSONArray()
        return (listOfNotNull(baseUrl) + (0 until arr.length()).map { arr.getString(it) }).distinct()
    }

    private fun get(path: String): JSONObject {
        var last: Exception? = null
        for (base in connections()) {
            try {
                val req = Request.Builder().url(base + path)
                    .header("X-Plex-Token", token).header("Accept", "application/json")
                    .header("X-Plex-Client-Identifier", creds.optString("client_id")).build()
                val json = probeHttp.newCall(req).execute().use { resp ->
                    if (resp.code == 401) throw IOException("Plex 登录已失效，请重新登录")
                    if (!resp.isSuccessful) throw IOException("Plex 请求失败 HTTP ${resp.code}")
                    resp.json()
                }
                if (base != baseUrl) {
                    baseUrl = base
                    creds.put("base", base)
                    saveCreds()
                }
                return json.optJSONObject("MediaContainer") ?: JSONObject()
            } catch (e: IOException) {
                if (e.message?.contains("登录已失效") == true) throw e
                last = e
            }
        }
        throw IOException("连不上 Plex 服务器（${last?.message ?: "没有可用地址"}）")
    }

    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val items = ArrayList<RemoteItem>()
        when {
            path.isEmpty() -> {
                val dirs = get("/library/sections").optJSONArray("Directory") ?: JSONArray()
                for (i in 0 until dirs.length()) {
                    val d = dirs.getJSONObject(i)
                    items += RemoteItem(d.optString("title"), "s:" + d.optString("key"), isDir = true)
                }
                return@withContext items
            }
            path.startsWith("s:") -> collect(get("/library/sections/${path.removePrefix("s:")}/all?includeGuids=1"), items)
            else -> collect(get("/library/metadata/${path.removePrefix("m:")}/children?includeGuids=1"), items)
        }
        items
    }

    private fun collect(container: JSONObject, out: MutableList<RemoteItem>) {
        val meta = container.optJSONArray("Metadata") ?: JSONArray()
        for (i in 0 until meta.length()) {
            val m = meta.getJSONObject(i)
            val type = m.optString("type")
            var title = m.optString("title")
            val info = meta(m)
            if (type in FOLDER_TYPES) {
                out += RemoteItem(title, "m:" + m.optString("ratingKey"), isDir = true, meta = info)
                continue
            }
            if (type == "episode" && m.has("index")) title = "第 ${m.optInt("index")} 集 · $title"
            if (type == "movie" && m.has("year")) title = "$title (${m.optInt("year")})"
            val part = m.optJSONArray("Media")?.optJSONObject(0)?.optJSONArray("Part")?.optJSONObject(0) ?: continue
            val ext = part.optString("container").ifEmpty { part.optString("file").substringAfterLast('.', "mkv") }
            val fileName = "${title.replace('/', '_')}.$ext"
            out += RemoteItem(
                fileName, part.optString("key"), isDir = false, size = part.optLong("size"),
                playUrl = playUri(part.optString("key"), fileName), meta = info,
            )
        }
    }

    /** 服务器缩放后的图片地址（原图可能有好几 MB）。 */
    private fun photo(path: String, width: Int, height: Int): String? {
        if (path.isEmpty()) return null
        val base = baseUrl ?: return null
        return "$base/photo/:/transcode?width=$width&height=$height&minSize=1&upscale=1&url=${Uri.encode(path)}&X-Plex-Token=$token"
    }

    /** 海报、年份、评分、外部编号（Guid 里的 tmdb://、imdb://）。 */
    private fun meta(m: JSONObject): ItemMeta {
        val type = m.optString("type")
        val wide = type == "episode" && m.optString("thumb").isNotEmpty()
        val poster = if (wide) photo(m.optString("thumb"), 400, 225)
        else photo(m.optString("thumb").ifEmpty { m.optString("parentThumb").ifEmpty { m.optString("grandparentThumb") } }, 300, 450)
        val guids = m.optJSONArray("Guid")?.let { g -> (0 until g.length()).map { g.getJSONObject(it).optString("id") } }.orEmpty()
        fun guid(prefix: String) = guids.firstOrNull { it.startsWith("$prefix://") }?.substringAfter("://")
        val leaf = m.optInt("leafCount") - m.optInt("viewedLeafCount")
        return ItemMeta(
            title = m.optString("title"),
            posterUrl = poster,
            posterRatio = if (wide) 16f / 9f else 2f / 3f,
            backdropUrl = photo(m.optString("art").ifEmpty { m.optString("grandparentArt") }, 1280, 720),
            year = m.optInt("year").takeIf { it > 0 } ?: m.optInt("parentYear").takeIf { it > 0 },
            rating = (m.optDouble("audienceRating", 0.0).takeIf { it > 0 } ?: m.optDouble("rating", 0.0)).takeIf { it > 0 },
            overview = m.optString("summary"),
            genres = m.optJSONArray("Genre")?.let { g -> (0 until g.length()).map { g.getJSONObject(it).optString("tag") } }.orEmpty(),
            kind = when (type) {
                "movie" -> "movie"
                "show" -> "tv"
                "season" -> "season"
                "episode" -> "episode"
                else -> null
            },
            seriesName = m.optString("grandparentTitle").ifEmpty { m.optString("parentTitle").takeIf { type == "season" }.orEmpty() }
                .takeIf { it.isNotEmpty() },
            season = (if (type == "season") m.optInt("index") else m.optInt("parentIndex")).takeIf { it > 0 && type in setOf("season", "episode") },
            episode = m.optInt("index").takeIf { it > 0 && type == "episode" },
            tmdbId = guid("tmdb"),
            imdbId = guid("imdb"),
            badge = when {
                type == "episode" && m.has("index") -> "第 ${m.optInt("index")} 集"
                leaf > 0 && type in setOf("show", "season") -> leaf.toString()
                else -> null
            },
        )
    }

    /** 原始文件地址，令牌放在地址里。 */
    override fun resolve(fileRef: String): ResolvedLink {
        if (baseUrl == null) get("/identity")
        return ResolvedLink("$baseUrl$fileRef?X-Plex-Token=$token", validMs = 24 * 60 * 60_000L)
    }

    companion object {
        private val FOLDER_TYPES = setOf("show", "season", "artist", "album", "collection", "photo")
        private const val PRODUCT = "AiSubPlayer"

        /** 探测服务器地址时超时要短，局域网地址在外网时连不上 */
        private val probeHttp = cloudHttp.newBuilder().connectTimeout(5, TimeUnit.SECONDS).build()

        private fun plexTv(url: String, clientId: String, token: String? = null, post: Boolean = false): Request =
            Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("X-Plex-Product", PRODUCT)
                .header("X-Plex-Client-Identifier", clientId)
                .apply { token?.let { header("X-Plex-Token", it) } }
                .apply { if (post) post(okhttp3.FormBody.Builder().build()) }
                .build()

        /** 申请 PIN。QrSession.content 是浏览器授权页地址。 */
        fun startPin(): QrSession {
            val clientId = UUID.randomUUID().toString()
            val json = cloudHttp.newCall(plexTv("https://plex.tv/api/v2/pins?strong=true", clientId, post = true)).execute().use { it.json() }
            val code = json.getString("code")
            val url = "https://app.plex.tv/auth#?" + Uri.Builder()
                .appendQueryParameter("clientID", clientId)
                .appendQueryParameter("code", code)
                .appendQueryParameter("context[device][product]", PRODUCT)
                .build().encodedQuery
            return QrSession(
                imageUrl = null, content = url,
                hint = "点「打开浏览器」登录 Plex 并允许访问，完成后回到这里",
                state = mapOf("id" to json.get("id").toString(), "client_id" to clientId),
            )
        }

        /** 轮询 PIN；授权后找到账号下的服务器，返回凭据 JSON。 */
        fun pollPin(s: QrSession): Pair<QrStatus, String?> {
            val clientId = s.state.getValue("client_id")
            val json = cloudHttp.newCall(plexTv("https://plex.tv/api/v2/pins/${s.state.getValue("id")}", clientId)).execute().use { it.json() }
            val userToken = json.optString("authToken").takeIf { it.isNotEmpty() && it != "null" }
                ?: return (if (json.has("errors")) QrStatus.EXPIRED else QrStatus.WAITING) to null
            val resources = cloudHttp.newCall(
                plexTv("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", clientId, userToken),
            ).execute().use { resp -> JSONArray(resp.body?.string().orEmpty()) }
            val servers = (0 until resources.length()).map { resources.getJSONObject(it) }
                .filter { "server" in it.optString("provides") }
            val srv = servers.firstOrNull { it.optBoolean("owned") } ?: servers.firstOrNull()
                ?: throw IOException("这个 Plex 账号下没有找到服务器")
            val conns = srv.optJSONArray("connections") ?: JSONArray()
            // 局域网地址优先，其次公网，中继最后
            val ordered = (0 until conns.length()).map { conns.getJSONObject(it) }
                .sortedBy { c -> when { c.optBoolean("relay") -> 2; c.optBoolean("local") -> 0; else -> 1 } }
                .map { it.getString("uri") }
            return QrStatus.SUCCESS to JSONObject()
                .put("token", srv.optString("accessToken").ifEmpty { userToken })
                .put("client_id", clientId)
                .put("server_name", srv.optString("name"))
                .put("connections", JSONArray(ordered)).toString()
        }

        /** 手动填写服务器地址和令牌（局域网直连、没有 plex.tv 账号时）。 */
        fun manual(baseUrl: String, token: String): String = JSONObject()
            .put("token", token.trim())
            .put("client_id", UUID.randomUUID().toString())
            .put("connections", JSONArray().put(baseUrl.trim().trimEnd('/'))).toString()
    }
}
