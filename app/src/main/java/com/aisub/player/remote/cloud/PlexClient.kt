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
class PlexClient(server: Server) : CloudClient(server), MediaBrowser {

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
                    .header("X-Plex-Client-Identifier", creds.optString("client_id"))
                    // 服务器的「正在播放」和设备列表里显示这些
                    .header("X-Plex-Product", PRODUCT)
                    .header("X-Plex-Platform", "Android")
                    .header("X-Plex-Device-Name", android.os.Build.MODEL)
                    .header("X-Plex-Language", "zh")
                    .build()
                val started = System.currentTimeMillis()
                val json = probeHttp.newCall(req).execute().use { resp ->
                    android.util.Log.d("Voxtitle", "Plex ${path.substringBefore('?')} → ${resp.code}，${System.currentTimeMillis() - started}ms")
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
            val url = playUri(part.optString("key"), fileName)
            ServerProgress.register(url, ServerProgress.Entry(
                server.id, m.optString("ratingKey"), m.optLong("duration"), m.optLong("viewOffset"),
                title = m.optString("title"), artist = m.optString("originalTitle").ifEmpty { m.optString("grandparentTitle") }.ifEmpty { null }.takeIf { type == "track" },
                album = m.optString("parentTitle").ifEmpty { null }.takeIf { type == "track" },
            ))
            out += RemoteItem(
                fileName, part.optString("key"), isDir = false, size = part.optLong("size"),
                playUrl = url, meta = info,
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

    // ---------------- 官方客户端风格的浏览 ----------------

    val serverName: String get() = creds.optString("server_name").ifEmpty { server.name }

    override suspend fun sections(): List<ServerSection> = withContext(Dispatchers.IO) {
        get("/library/sections").optJSONArray("Directory").objects()
            .map { ServerSection(it.optString("key"), it.optString("title"), it.optString("type")) }
    }

    /** 首页的各行：继续观看、最近添加……空的行不要 */
    override suspend fun hubs(): List<ServerHub> = withContext(Dispatchers.IO) {
        get("/hubs?count=16&excludeFields=summary").optJSONArray("Hub").objects().mapNotNull { h ->
            val items = h.optJSONArray("Metadata").objects().map(::item)
            if (items.isEmpty()) return@mapNotNull null
            val id = h.optString("hubIdentifier")
            ServerHub(id, HUB_TITLES[id] ?: h.optString("title"), items)
        }
    }

    override fun folderPath(section: ServerSection) = "s:" + section.key

    /**
     * directStream=0：视频强制转码成 H.264（4K HEVC、杜比视界在老手机上解不了）。
     * 先问服务器的 decision，转不了时把原因说清楚（例如杜比视界 Profile 5 Plex 不支持转码），否则 start 只返回 400。
     */
    override fun transcodeUrl(ratingKey: String): String {
        if (baseUrl == null) get("/identity")
        val session = UUID.randomUUID().toString()
        val q = listOf(
            "path" to "/library/metadata/$ratingKey", "mediaIndex" to "0", "partIndex" to "0", "protocol" to "hls",
            "fastSeek" to "1", "directPlay" to "0", "directStream" to "0", "directStreamAudio" to "1",
            "videoQuality" to "100", "videoResolution" to "1920x1080", "maxVideoBitrate" to "12000", "subtitles" to "none",
            "session" to session, "X-Plex-Session-Identifier" to session,
            "X-Plex-Client-Identifier" to creds.optString("client_id"),
            "X-Plex-Product" to PRODUCT, "X-Plex-Platform" to "Android", "X-Plex-Token" to token,
        ).joinToString("&") { (k, v) -> "$k=${Uri.encode(v)}" }
        val decision = probeHttp.newCall(
            Request.Builder().url("$baseUrl/video/:/transcode/universal/decision?$q").header("Accept", "application/json").build(),
        ).execute().use { it.json() }.optJSONObject("MediaContainer") ?: JSONObject()
        val code = decision.optInt("transcodeDecisionCode")
        if (code != 0 && code !in 1000..1999) {
            val text = decision.optString("transcodeDecisionText")
            throw IOException(
                when {
                    text.contains("DoVi", ignoreCase = true) -> "这个文件是杜比视界 Profile 5，Plex 服务器也转不了"
                    text.contains("transcoder", ignoreCase = true) -> "Plex 服务器没开启转码，或者性能不够"
                    else -> "Plex 服务器转不了这个文件（$text）"
                },
            )
        }
        return "$baseUrl/video/:/transcode/universal/start.m3u8?$q"
    }

    /** 媒体库里的全部条目 */
    override suspend fun library(sectionKey: String, sort: LibrarySort, unwatchedOnly: Boolean): List<ServerItem> = withContext(Dispatchers.IO) {
        val s = when (sort) {
            LibrarySort.NAME -> "titleSort"
            LibrarySort.ADDED -> "addedAt:desc"
            LibrarySort.RELEASED -> "originallyAvailableAt:desc"
            LibrarySort.RATING -> "audienceRating:desc"
        }
        val q = "sort=$s" + if (unwatchedOnly) "&unwatched=1" else ""
        get("/library/sections/$sectionKey/all?$q").optJSONArray("Metadata").objects().map(::item)
    }

    /** 条目详情（简介、演职员、类型等都在这里） */
    override suspend fun item(ratingKey: String): ServerItem? = withContext(Dispatchers.IO) {
        get("/library/metadata/$ratingKey").optJSONArray("Metadata").objects().firstOrNull()?.let(::item)
    }

    /** 子条目：剧集的各季、季的各集、专辑的曲目 */
    override suspend fun children(ratingKey: String): List<ServerItem> = withContext(Dispatchers.IO) {
        get("/library/metadata/$ratingKey/children").optJSONArray("Metadata").objects().map(::item)
    }

    /** 标为已看 / 未看（剧集、季会连带所有集） */
    override suspend fun setWatched(ratingKey: String, watched: Boolean) = withContext(Dispatchers.IO) {
        get("/:/${if (watched) "scrobble" else "unscrobble"}?identifier=com.plexapp.plugins.library&key=$ratingKey")
        Unit
    }

    /**
     * 报告播放进度，服务器据此更新「继续观看」，播放超过九成且停止时自动标为已看。
     * state：playing / paused / stopped。
     */
    override fun timeline(ratingKey: String, state: String, timeMs: Long, durationMs: Long) {
        get("/:/timeline?ratingKey=$ratingKey&key=${Uri.encode("/library/metadata/$ratingKey")}&state=$state&time=$timeMs&duration=$durationMs")
    }

    private fun item(m: JSONObject): ServerItem {
        val type = m.optString("type")
        val rk = m.optString("ratingKey")
        fun tags(name: String) = m.optJSONArray(name).objects().map { it.optString("tag") }.filter { it.isNotEmpty() }
        val media = m.optJSONArray("Media")?.optJSONObject(0)
        val part = media?.optJSONArray("Part")?.optJSONObject(0)
        val showTitle = m.optString("grandparentTitle").ifEmpty { null }
        val title = m.optString("title")
        val fileName = part?.let {
            val ext = it.optString("container").ifEmpty { it.optString("file").substringAfterLast('.', "mkv") }
            val base = when (type) {
                "episode" -> "${showTitle ?: ""} - S%02dE%02d - $title".format(m.optInt("parentIndex"), m.optInt("index"))
                "movie" -> if (m.has("year")) "$title (${m.optInt("year")})" else title
                "track" -> listOfNotNull(showTitle, title).joinToString(" - ")
                else -> title
            }
            "${base.replace('/', '_')}.$ext"
        }
        val playUrl = part?.let { p -> playUri(p.optString("key"), fileName!!) }
        val duration = m.optLong("duration")
        val offset = m.optLong("viewOffset")
        playUrl?.let {
            ServerProgress.register(it, ServerProgress.Entry(
                server.id, rk, duration, offset, title = title,
                artist = (m.optString("originalTitle").ifEmpty { showTitle.orEmpty() }).ifEmpty { null }.takeIf { type == "track" },
                album = m.optString("parentTitle").ifEmpty { null }.takeIf { type == "track" },
            ))
        }
        val wide = type == "episode" || type == "clip"
        return ServerItem(
            ratingKey = rk, type = type, title = title,
            year = m.optInt("year").takeIf { it > 0 } ?: m.optInt("parentYear").takeIf { it > 0 },
            summary = m.optString("summary"),
            thumb = if (wide) photo(m.optString("thumb"), 480, 270) else photo(m.optString("thumb").ifEmpty { m.optString("parentThumb") }, 300, 450),
            showThumb = photo(m.optString("grandparentThumb").ifEmpty { m.optString("parentThumb") }, 300, 450),
            art = photo(m.optString("art").ifEmpty { m.optString("grandparentArt") }, 1280, 720),
            durationMs = duration, viewOffsetMs = offset, viewCount = m.optInt("viewCount"),
            leafCount = m.optInt("leafCount"), viewedLeafCount = m.optInt("viewedLeafCount"), childCount = m.optInt("childCount"),
            index = m.optInt("index").takeIf { m.has("index") }, parentIndex = m.optInt("parentIndex").takeIf { m.has("parentIndex") },
            parentTitle = m.optString("parentTitle").ifEmpty { null }, grandparentTitle = showTitle,
            parentRatingKey = m.optString("parentRatingKey").ifEmpty { null },
            grandparentRatingKey = m.optString("grandparentRatingKey").ifEmpty { null },
            rating = (m.optDouble("audienceRating", 0.0).takeIf { it > 0 } ?: m.optDouble("rating", 0.0)).takeIf { it > 0 },
            contentRating = m.optString("contentRating").ifEmpty { null },
            studio = m.optString("studio").ifEmpty { null },
            genres = tags("Genre"), directors = tags("Director"),
            roles = m.optJSONArray("Role").objects().map {
                ServerRole(it.optString("tag"), it.optString("role").ifEmpty { null }, it.optString("thumb").ifEmpty { null })
            },
            videoInfo = media?.let { md ->
                listOfNotNull(
                    md.optString("videoResolution").ifEmpty { null }?.let { r -> if (r.all(Char::isDigit)) "${r}p" else r.uppercase() },
                    md.optString("videoCodec").ifEmpty { null }?.uppercase(),
                    md.optString("audioCodec").ifEmpty { null }?.uppercase(),
                    md.optInt("audioChannels").takeIf { it > 0 }?.let { c -> if (c == 6) "5.1" else if (c == 8) "7.1" else "${c}ch" },
                ).joinToString(" · ").ifEmpty { null }
            },
            fileName = fileName, playUrl = playUrl, sizeBytes = part?.optLong("size") ?: 0,
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    /** 原始文件地址，令牌放在地址里。 */
    override fun resolve(fileRef: String): ResolvedLink {
        if (baseUrl == null) get("/identity")
        return ResolvedLink("$baseUrl$fileRef?X-Plex-Token=$token", validMs = 24 * 60 * 60_000L)
    }

    companion object {
        private val FOLDER_TYPES = setOf("show", "season", "artist", "album", "collection", "photo")

        /** 首页各行的中文名（服务器给的名字按服务器语言，多半是英文） */
        private val HUB_TITLES = mapOf(
            "home.continue" to "继续观看",
            "home.ondeck" to "接着看",
            "home.movies.recent" to "最近添加的电影",
            "home.television.recent" to "最近添加的剧集",
            "home.music.recent" to "最近添加的音乐",
            "home.videos.recent" to "最近添加的视频",
            "home.photos.recent" to "最近添加的照片",
            "home.playlists" to "播放列表",
        )
        private const val PRODUCT = "Voxtitle"

        /** 探测服务器地址时超时要短，局域网地址在外网时连不上 */
        private val probeHttp = cloudHttp.newBuilder().connectTimeout(5, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

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
