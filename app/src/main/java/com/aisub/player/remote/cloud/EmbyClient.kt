package com.aisub.player.remote.cloud

import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.ItemMeta
import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Emby / Jellyfin 媒体服务器（两者接口兼容）。用服务器地址 + 用户名密码登录。
 * 浏览：媒体库 → 文件夹 / 剧集 → 季 → 集；播放用服务器原始文件（static=true，不转码）。
 * creds：token / user_id / device_id；Server.baseUrl 是服务器地址，lanUrl 是可选的内网地址。定位符是条目 ID，根目录为空。
 */
class EmbyClient(server: Server) : CloudClient(server), MediaBrowser {

    /** 内网 / 外网地址里当前能用的那个；会联网探测，只在后台线程用 */
    private val base get() = Endpoints.base(server)
    private val token get() = creds.optString("token")
    private val uid get() = creds.getString("user_id")

    /** method：GET / POST / DELETE；body 为 JSON。返回响应文本（可能为空）。 */
    private fun call(path: String, params: Map<String, String> = emptyMap(), method: String = "GET", body: JSONObject? = null): String {
        val url = (base + path).toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        // 带上设备信息，服务器的「正在播放」里能看到译幕
        val auth = authHeader(creds.optString("device_id").ifEmpty { "voxtitle" }) + """, Token="$token""""
        val req = Request.Builder().url(url)
            .header("X-Emby-Token", token).header("X-Emby-Authorization", auth).header("Authorization", auth)
            .header("Accept", "application/json")
            .apply {
                when (method) {
                    "POST" -> post((body?.toString() ?: "").toRequestBody("application/json".toMediaType()))
                    "DELETE" -> delete()
                }
            }.build()
        return cloudHttp.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw IOException("${server.type.label} 登录已失效，请重新登录")
            if (!resp.isSuccessful) throw IOException("${server.type.label} 请求失败 HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject =
        call(path, params).let { if (it.isBlank()) JSONObject() else JSONObject(it) }

    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val uid = creds.getString("user_id")
        val json = if (path.isEmpty()) {
            get("/Users/$uid/Views")
        } else {
            get(
                "/Users/$uid/Items",
                mapOf(
                    "ParentId" to path, "SortBy" to "SortName", "SortOrder" to "Ascending",
                    "Fields" to "MediaSources,ProductionYear,Overview,Genres,ProviderIds,PrimaryImageAspectRatio",
                    "EnableImageTypes" to "Primary,Backdrop,Thumb", "ImageTypeLimit" to "1",
                ),
            )
        }
        val arr = json.optJSONArray("Items")
        val items = ArrayList<RemoteItem>()
        for (i in 0 until (arr?.length() ?: 0)) {
            val it = arr!!.getJSONObject(i)
            val id = it.getString("Id")
            val type = it.optString("Type")
            var name = it.optString("Name")
            if (type == "Episode" && it.has("IndexNumber")) name = "第 ${it.optInt("IndexNumber")} 集 · $name"
            val meta = meta(it)
            if (it.optBoolean("IsFolder") || type in FOLDER_TYPES) {
                items += RemoteItem(name, id, isDir = true, meta = meta)
                continue
            }
            val source = it.optJSONArray("MediaSources")?.optJSONObject(0)
            val isAudio = it.optString("MediaType") == "Audio"
            // 名字带上扩展名，列表和下载时才能识别为视频 / 音频
            val ext = (source?.optString("Container") ?: it.optString("Container")).substringBefore(',').ifEmpty { if (isAudio) "mp3" else "mkv" }
            val fileName = "${name.replace('/', '_')}.$ext"
            if (isAudio) ServerProgress.register(
                playUri("$id|Audio", fileName),
                ServerProgress.Entry(
                    server.id, id, it.optLong("RunTimeTicks") / 10_000, 0, title = it.optString("Name"),
                    artist = it.optString("AlbumArtist").ifEmpty { null }, album = it.optString("Album").ifEmpty { null },
                ),
            )
            items += RemoteItem(
                fileName, id, isDir = false, size = source?.optLong("Size") ?: 0,
                playUrl = playUri("$id|${if (isAudio) "Audio" else "Videos"}", fileName), meta = meta,
            )
        }
        if (path.isEmpty()) items else items.sortedWith(compareBy { !it.isDir })
    }

    private fun image(id: String, type: String, tag: String, maxHeight: Int) =
        "$base/Items/$id/Images/$type?maxHeight=$maxHeight&quality=90&tag=$tag&api_key=$token"

    /** 海报、年份、评分、外部编号。单集没有自己的封面时用剧集海报。 */
    private fun meta(it: JSONObject): ItemMeta {
        val id = it.getString("Id")
        val type = it.optString("Type")
        val tags = it.optJSONObject("ImageTags")
        val primary = tags?.optString("Primary").orEmpty()
        val poster = when {
            primary.isNotEmpty() -> image(id, "Primary", primary, 450)
            it.optString("SeriesPrimaryImageTag").isNotEmpty() && it.has("SeriesId") ->
                image(it.getString("SeriesId"), "Primary", it.getString("SeriesPrimaryImageTag"), 450)
            tags?.optString("Thumb").orEmpty().isNotEmpty() -> image(id, "Thumb", tags!!.getString("Thumb"), 300)
            else -> null
        }
        val ratio = when {
            primary.isNotEmpty() && it.has("PrimaryImageAspectRatio") -> it.optDouble("PrimaryImageAspectRatio").toFloat()
            primary.isEmpty() && poster != null && it.optString("SeriesPrimaryImageTag").isEmpty() -> 16f / 9f
            type == "Episode" && primary.isNotEmpty() -> 16f / 9f
            type == "CollectionFolder" || type == "UserView" -> 16f / 9f
            else -> 2f / 3f
        }
        val backdrop = it.optJSONArray("BackdropImageTags")?.optString(0)?.takeIf { t -> t.isNotEmpty() }
            ?.let { t -> image(id, "Backdrop", t, 720) }
            ?: it.optJSONArray("ParentBackdropImageTags")?.optString(0)?.takeIf { t -> t.isNotEmpty() && it.has("ParentBackdropItemId") }
                ?.let { t -> image(it.getString("ParentBackdropItemId"), "Backdrop", t, 720) }
        val providers = it.optJSONObject("ProviderIds")
        fun provider(name: String) = providers?.keys()?.asSequence()?.firstOrNull { k -> k.equals(name, ignoreCase = true) }
            ?.let { k -> providers.optString(k) }?.takeIf { v -> v.isNotEmpty() }
        val unplayed = it.optJSONObject("UserData")?.optInt("UnplayedItemCount") ?: 0
        return ItemMeta(
            title = it.optString("Name"),
            posterUrl = poster,
            posterRatio = ratio.takeIf { r -> r > 0.2f && r < 5f } ?: (2f / 3f),
            backdropUrl = backdrop,
            year = it.optInt("ProductionYear").takeIf { y -> y > 0 },
            rating = it.optDouble("CommunityRating", 0.0).takeIf { r -> r > 0 },
            overview = it.optString("Overview"),
            genres = it.optJSONArray("Genres")?.let { g -> (0 until g.length()).map { k -> g.optString(k) } }.orEmpty(),
            kind = when (type) {
                "Movie" -> "movie"
                "Series" -> "tv"
                "Season" -> "season"
                "Episode" -> "episode"
                else -> null
            },
            seriesName = it.optString("SeriesName").takeIf { s -> s.isNotEmpty() },
            season = (if (type == "Season") it.optInt("IndexNumber") else it.optInt("ParentIndexNumber")).takeIf { n -> n > 0 },
            episode = it.optInt("IndexNumber").takeIf { n -> n > 0 && type == "Episode" },
            tmdbId = provider("Tmdb"),
            imdbId = provider("Imdb"),
            badge = when {
                type == "Episode" && it.has("IndexNumber") -> "第 ${it.optInt("IndexNumber")} 集"
                unplayed > 0 -> unplayed.toString()
                else -> null
            },
        )
    }

    // ---------------- 官方客户端风格的浏览 ----------------

    private val browseFields = mapOf(
        "Fields" to "Overview,Genres,People,Studios,MediaSources,ProductionYear,OfficialRating,CommunityRating,ChildCount,RecursiveItemCount,PrimaryImageAspectRatio,ProviderIds",
        "EnableImageTypes" to "Primary,Backdrop,Thumb", "ImageTypeLimit" to "1", "EnableUserData" to "true",
    )

    override suspend fun sections(): List<ServerSection> = withContext(Dispatchers.IO) {
        get("/Users/$uid/Views").optJSONArray("Items").objects().map {
            val type = when (it.optString("CollectionType")) {
                "movies" -> "movie"
                "tvshows" -> "show"
                "music" -> "artist"
                else -> it.optString("CollectionType").ifEmpty { "other" }
            }
            ServerSection(it.optString("Id"), it.optString("Name"), type)
        }
    }

    /** 首页：继续观看、接着看（下一集），再加每个媒体库的最近添加 */
    override suspend fun hubs(): List<ServerHub> = withContext(Dispatchers.IO) {
        val hubs = ArrayList<ServerHub>()
        fun add(id: String, title: String, items: List<ServerItem>) {
            if (items.isNotEmpty()) hubs += ServerHub(id, title, items)
        }
        runCatching {
            add("home.continue", "继续观看", get("/Users/$uid/Items/Resume", browseFields + mapOf("Limit" to "16", "MediaTypes" to "Video")).optJSONArray("Items").objects().map(::item))
        }
        runCatching {
            add("home.ondeck", "接着看", get("/Shows/NextUp", browseFields + mapOf("UserId" to uid, "Limit" to "16")).optJSONArray("Items").objects().map(::item))
        }
        for (s in sections().filter { it.type in setOf("movie", "show", "artist") }) {
            runCatching {
                // Latest 返回的是数组
                val arr = JSONArray(call("/Users/$uid/Items/Latest", browseFields + mapOf("ParentId" to s.key, "Limit" to "16")))
                add("latest.${s.key}", "最近添加 · ${s.title}", arr.objects().map(::item))
            }
        }
        hubs
    }

    override fun folderPath(section: ServerSection) = section.key

    override fun transcodeUrl(ratingKey: String): String {
        val q = listOf(
            "MediaSourceId" to ratingKey, "VideoCodec" to "h264", "AudioCodec" to "aac,mp3", "MaxWidth" to "1920",
            "MaxStreamingBitrate" to "12000000", "TranscodingMaxAudioChannels" to "2", "SegmentContainer" to "ts",
            "DeviceId" to creds.optString("device_id").ifEmpty { "voxtitle" }, "PlaySessionId" to UUID.randomUUID().toString(),
            "api_key" to token,
        ).joinToString("&") { (k, v) -> "$k=${android.net.Uri.encode(v)}" }
        return "$base/Videos/$ratingKey/master.m3u8?$q"
    }

    override suspend fun library(sectionKey: String, sort: LibrarySort, unwatchedOnly: Boolean): List<ServerItem> = withContext(Dispatchers.IO) {
        val (by, order) = when (sort) {
            LibrarySort.NAME -> "SortName" to "Ascending"
            LibrarySort.ADDED -> "DateCreated,SortName" to "Descending"
            LibrarySort.RELEASED -> "PremiereDate,ProductionYear,SortName" to "Descending"
            LibrarySort.RATING -> "CommunityRating,SortName" to "Descending"
        }
        val params = browseFields + mapOf(
            "ParentId" to sectionKey, "Recursive" to "true", "IncludeItemTypes" to "Movie,Series",
            "SortBy" to by, "SortOrder" to order,
        ) + (if (unwatchedOnly) mapOf("Filters" to "IsUnplayed") else emptyMap())
        get("/Users/$uid/Items", params).optJSONArray("Items").objects().map(::item)
    }

    override suspend fun item(ratingKey: String): ServerItem? = withContext(Dispatchers.IO) {
        get("/Users/$uid/Items/$ratingKey", browseFields).takeIf { it.has("Id") }?.let(::item)
    }

    /** 剧集的各季、季的各集、专辑的曲目；歌手的专辑按艺人查 */
    override suspend fun children(ratingKey: String): List<ServerItem> = withContext(Dispatchers.IO) {
        val parent = get("/Users/$uid/Items/$ratingKey")
        val params = if (parent.optString("Type") == "MusicArtist") {
            mapOf("ArtistIds" to ratingKey, "IncludeItemTypes" to "MusicAlbum", "Recursive" to "true", "SortBy" to "ProductionYear,SortName")
        } else {
            mapOf("ParentId" to ratingKey, "SortBy" to "ParentIndexNumber,IndexNumber,SortName")
        }
        get("/Users/$uid/Items", browseFields + params).optJSONArray("Items").objects().map(::item)
    }

    override suspend fun setWatched(ratingKey: String, watched: Boolean) = withContext(Dispatchers.IO) {
        call("/Users/$uid/PlayedItems/$ratingKey", method = if (watched) "POST" else "DELETE")
        Unit
    }

    /** 已经告诉服务器开始播放的条目（之后只报进度） */
    private val started = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 播放会话：开始、进度、停止。停止时超过九成服务器自动标为已看。时间单位是 100 纳秒（ticks）。 */
    override fun timeline(ratingKey: String, state: String, timeMs: Long, durationMs: Long) {
        val body = JSONObject().put("ItemId", ratingKey).put("MediaSourceId", ratingKey).put("PositionTicks", timeMs * 10_000)
            .put("IsPaused", state == "paused").put("CanSeek", true).put("PlayMethod", "DirectPlay")
        when {
            state == "stopped" -> {
                started.remove(ratingKey)
                call("/Sessions/Playing/Stopped", method = "POST", body = body)
            }
            started.add(ratingKey) -> call("/Sessions/Playing", method = "POST", body = body)
            else -> call("/Sessions/Playing/Progress", method = "POST", body = body.put("EventName", if (state == "paused") "pause" else "timeupdate"))
        }
    }

    private fun item(it: JSONObject): ServerItem {
        val id = it.optString("Id")
        val rawType = it.optString("Type")
        val type = when (rawType) {
            "Movie", "Video" -> "movie"
            "Series" -> "show"
            "Season" -> "season"
            "Episode" -> "episode"
            "MusicArtist" -> "artist"
            "MusicAlbum" -> "album"
            "Audio" -> "track"
            "BoxSet" -> "collection"
            else -> rawType.lowercase()
        }
        val tags = it.optJSONObject("ImageTags")
        fun tag(name: String) = tags?.optString(name).orEmpty().ifEmpty { null }
        val seriesPoster = it.optString("SeriesId").ifEmpty { null }?.let { sid ->
            it.optString("SeriesPrimaryImageTag").ifEmpty { null }?.let { t -> image(sid, "Primary", t, 450) }
        }
        val thumb = when {
            type == "episode" -> tag("Primary")?.let { t -> image(id, "Primary", t, 270) } ?: tag("Thumb")?.let { t -> image(id, "Thumb", t, 270) }
            else -> tag("Primary")?.let { t -> image(id, "Primary", t, 450) }
                ?: it.optString("AlbumId").ifEmpty { null }?.let { aid -> it.optString("AlbumPrimaryImageTag").ifEmpty { null }?.let { t -> image(aid, "Primary", t, 450) } }
                ?: seriesPoster
        }
        val art = it.optJSONArray("BackdropImageTags")?.optString(0)?.ifEmpty { null }?.let { t -> image(id, "Backdrop", t, 720) }
            ?: it.optJSONArray("ParentBackdropImageTags")?.optString(0)?.ifEmpty { null }
                ?.let { t -> it.optString("ParentBackdropItemId").ifEmpty { null }?.let { pid -> image(pid, "Backdrop", t, 720) } }
        val user = it.optJSONObject("UserData")
        val played = user?.optBoolean("Played") == true
        val unplayed = user?.optInt("UnplayedItemCount") ?: 0
        val leaves = if (type == "show" || type == "season") maxOf(it.optInt("RecursiveItemCount"), unplayed, if (played) 1 else 0) else 0
        val people = it.optJSONArray("People").objects()
        val source = it.optJSONArray("MediaSources")?.optJSONObject(0)
        val streams = source?.optJSONArray("MediaStreams").objects()
        val video = streams.firstOrNull { s -> s.optString("Type") == "Video" }
        val audio = streams.firstOrNull { s -> s.optString("Type") == "Audio" && s.optBoolean("IsDefault") } ?: streams.firstOrNull { s -> s.optString("Type") == "Audio" }
        val isAudio = it.optString("MediaType") == "Audio"
        val name = it.optString("Name")
        val year = it.optInt("ProductionYear").takeIf { y -> y > 0 }
        val playable = !it.optBoolean("IsFolder") && type in setOf("movie", "episode", "track")
        val fileName = if (!playable) null else {
            val ext = (source?.optString("Container") ?: it.optString("Container")).substringBefore(',').ifEmpty { if (isAudio) "mp3" else "mkv" }
            val baseName = when (type) {
                "episode" -> "${it.optString("SeriesName")} - S%02dE%02d - $name".format(it.optInt("ParentIndexNumber"), it.optInt("IndexNumber"))
                "movie" -> if (year != null) "$name ($year)" else name
                "track" -> listOfNotNull(it.optString("AlbumArtist").ifEmpty { null }, name).joinToString(" - ")
                else -> name
            }
            "${baseName.replace('/', '_')}.$ext"
        }
        val durationMs = it.optLong("RunTimeTicks") / 10_000
        val offsetMs = (user?.optLong("PlaybackPositionTicks") ?: 0) / 10_000
        val playUrl = fileName?.let { f -> playUri("$id|${if (isAudio) "Audio" else "Videos"}", f) }
        playUrl?.let { u ->
            ServerProgress.register(u, ServerProgress.Entry(
                server.id, id, durationMs, offsetMs, title = name,
                artist = it.optString("AlbumArtist").ifEmpty { it.optJSONArray("Artists")?.optString(0).orEmpty() }.ifEmpty { null }.takeIf { type == "track" },
                album = it.optString("Album").ifEmpty { null }.takeIf { type == "track" },
            ))
        }
        return ServerItem(
            ratingKey = id, type = type, title = name, year = year,
            summary = it.optString("Overview"),
            thumb = thumb, showThumb = seriesPoster, art = art,
            durationMs = durationMs, viewOffsetMs = offsetMs, viewCount = if (played) maxOf(1, user?.optInt("PlayCount") ?: 1) else 0,
            leafCount = leaves, viewedLeafCount = if (played) leaves else (leaves - unplayed).coerceAtLeast(0),
            childCount = it.optInt("ChildCount"),
            index = it.optInt("IndexNumber").takeIf { _ -> it.has("IndexNumber") },
            parentIndex = it.optInt("ParentIndexNumber").takeIf { _ -> it.has("ParentIndexNumber") },
            parentTitle = it.optString("SeasonName").ifEmpty { it.optString("Album") }.ifEmpty { null },
            grandparentTitle = it.optString("SeriesName").ifEmpty { it.optString("AlbumArtist") }.ifEmpty { null },
            parentRatingKey = it.optString("SeasonId").ifEmpty { it.optString("AlbumId") }.ifEmpty { it.optString("ParentId") }.ifEmpty { null },
            grandparentRatingKey = it.optString("SeriesId").ifEmpty { null },
            rating = it.optDouble("CommunityRating", 0.0).takeIf { r -> r > 0 },
            contentRating = it.optString("OfficialRating").ifEmpty { null },
            studio = it.optJSONArray("Studios")?.optJSONObject(0)?.optString("Name")?.ifEmpty { null },
            genres = it.optJSONArray("Genres")?.let { g -> (0 until g.length()).map { k -> g.optString(k) } }.orEmpty(),
            directors = people.filter { p -> p.optString("Type") == "Director" }.map { p -> p.optString("Name") },
            roles = people.filter { p -> p.optString("Type") == "Actor" }.map { p ->
                ServerRole(
                    p.optString("Name"), p.optString("Role").ifEmpty { null },
                    p.optString("PrimaryImageTag").ifEmpty { null }?.let { t -> image(p.optString("Id"), "Primary", t, 200) },
                )
            },
            videoInfo = listOfNotNull(
                video?.optInt("Height")?.takeIf { h -> h > 0 }?.let { h -> if (h >= 2000) "4K" else "${h}p" },
                video?.optString("Codec")?.ifEmpty { null }?.uppercase(),
                audio?.optString("Codec")?.ifEmpty { null }?.uppercase(),
                audio?.optInt("Channels")?.takeIf { c -> c > 0 }?.let { c -> if (c == 6) "5.1" else if (c == 8) "7.1" else "${c}ch" },
            ).joinToString(" · ").ifEmpty { null },
            fileName = fileName, playUrl = playUrl, sizeBytes = source?.optLong("Size") ?: 0,
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    /** 原始文件流（不转码），api_key 放在地址里，播放器和下载器都不需要额外请求头。 */
    override fun resolve(fileRef: String): ResolvedLink {
        val (id, kind) = fileRef.split('|').let { it[0] to (it.getOrNull(1) ?: "Videos") }
        return ResolvedLink("$base/$kind/$id/stream?static=true&api_key=$token", validMs = 24 * 60 * 60_000L)
    }

    companion object {
        private val FOLDER_TYPES = setOf("Folder", "CollectionFolder", "Series", "Season", "BoxSet", "MusicAlbum", "MusicArtist", "Playlist", "UserView")

        private fun authHeader(deviceId: String) =
            """MediaBrowser Client="Voxtitle", Device="Android", DeviceId="$deviceId", Version="1.0""""

        /** 用户名密码登录，返回凭据 JSON。填了内网地址时先试内网（在家可能连不上外网域名），失败再试外网。 */
        fun login(baseUrl: String, lanUrl: String, username: String, password: String): String {
            val urls = listOf(lanUrl, baseUrl).map { it.trim() }.filter { it.isNotEmpty() }
            var last: IOException? = null
            for (url in urls) {
                try {
                    return login(url, username, password)
                } catch (e: IOException) {
                    if (e.message == "用户名或密码错误") throw e
                    last = e
                }
            }
            throw last ?: IOException("请填写服务器地址")
        }

        private fun login(baseUrl: String, username: String, password: String): String {
            val deviceId = UUID.randomUUID().toString()
            val body = JSONObject().put("Username", username).put("Pw", password).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(baseUrl.trim().trimEnd('/') + "/Users/AuthenticateByName")
                .header("X-Emby-Authorization", authHeader(deviceId))
                .header("Authorization", authHeader(deviceId))
                .post(body).build()
            val json = cloudHttp.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 -> throw IOException("用户名或密码错误")
                    !resp.isSuccessful -> throw IOException("登录失败 HTTP ${resp.code}，请检查服务器地址")
                }
                resp.json()
            }
            return JSONObject()
                .put("token", json.getString("AccessToken"))
                .put("user_id", json.getJSONObject("User").getString("Id"))
                .put("device_id", deviceId).toString()
        }
    }
}
