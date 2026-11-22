package com.aisub.player.remote.cloud

import android.net.Uri
import com.aisub.player.music.LyricLine
import com.aisub.player.music.Lyrics
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.ItemMeta
import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom

/**
 * Subsonic / OpenSubsonic 音乐库客户端：
 * 原生兼容 Navidrome、Subsonic、Airsonic、Gonic、LMS (Lightweight Music Server)、Funkwhale 等主流自建音乐库。
 * 支持专辑、歌手、单曲、歌单、封面、搜索、播放统计上报（Scrobble）以及 OpenSubsonic 结构化逐行同步歌词。
 */
class SubsonicClient(server: Server) : CloudClient(server), MediaBrowser {

    private val base: String get() = Endpoints.base(server)
    private val username: String get() = creds.optString("user").ifEmpty { server.username }
    private val password: String get() = creds.optString("pass").ifEmpty { server.password }
    private val legacyEnc: Boolean get() = creds.optBoolean("legacy_enc", false)

    private fun authParams(): Map<String, String> {
        val baseParams = LinkedHashMap<String, String>()
        baseParams["u"] = username
        if (legacyEnc) {
            baseParams["p"] = "enc:" + hex(password.toByteArray(Charsets.UTF_8))
        } else {
            val salt = randomSalt()
            baseParams["t"] = md5Hex(password + salt)
            baseParams["s"] = salt
        }
        baseParams["v"] = API_VERSION
        baseParams["c"] = CLIENT_NAME
        baseParams["f"] = "json"
        return baseParams
    }

    private fun buildEndpointUrl(endpoint: String, extra: Map<String, String> = emptyMap()): String {
        val cleanEndpoint = endpoint.removePrefix("/")
        val httpUrl = "$base/rest/$cleanEndpoint".toHttpUrl().newBuilder()
        for ((k, v) in authParams()) httpUrl.addQueryParameter(k, v)
        for ((k, v) in extra) httpUrl.addQueryParameter(k, v)
        return httpUrl.build().toString()
    }

    private fun getSubsonic(endpoint: String, extra: Map<String, String> = emptyMap()): JSONObject {
        val url = buildEndpointUrl(endpoint, extra)
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        return cloudHttp.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                throw IOException("音乐库登录失效或无权限（HTTP ${resp.code}）")
            }
            if (!resp.isSuccessful) {
                throw IOException("音乐库请求失败（HTTP ${resp.code}）")
            }
            parseSubsonicResponse(resp.body?.string().orEmpty())
        }
    }

    private fun coverUrl(coverArtId: String?, size: Int = 500): String? {
        val id = coverArtId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return buildEndpointUrl("getCoverArt.view", mapOf("id" to id, "size" to size.toString()))
    }

    override fun resolve(fileRef: String): ResolvedLink {
        val url = buildEndpointUrl("stream.view", mapOf("id" to fileRef, "raw" to "true"))
        return ResolvedLink(url, validMs = 6 * 60 * 60_000L)
    }

    // ---------------- 文件目录式浏览（兼容「浏览」页打开） ----------------

    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        when {
            path.isEmpty() -> listOf(
                RemoteItem("最近添加专辑", "albums:newest", isDir = true),
                RemoteItem("常听专辑", "albums:frequent", isDir = true),
                RemoteItem("全部歌手", "artists", isDir = true),
                RemoteItem("音乐歌单", "playlists", isDir = true),
                RemoteItem("随机单曲", "random", isDir = true),
            )
            path == "artists" -> musicArtists().map { a ->
                RemoteItem(a.title, "artist:${a.ratingKey.removePrefix("ar:")}", isDir = true, meta = ItemMeta(title = a.title, posterUrl = a.thumb, posterRatio = 1f))
            }
            path.startsWith("artist:") -> children("ar:${path.removePrefix("artist:")}").map { al ->
                RemoteItem(al.title, "album:${al.ratingKey.removePrefix("al:")}", isDir = true, meta = ItemMeta(title = al.title, posterUrl = al.thumb, year = al.year, posterRatio = 1f))
            }
            path.startsWith("albums:") -> musicAlbums(type = path.removePrefix("albums:")).map { al ->
                RemoteItem(al.title, "album:${al.ratingKey.removePrefix("al:")}", isDir = true, meta = ItemMeta(title = al.title, posterUrl = al.thumb, year = al.year, posterRatio = 1f))
            }
            path.startsWith("album:") -> children("al:${path.removePrefix("album:")}").mapNotNull(::songToRemoteItem)
            path == "playlists" -> musicPlaylists().map { pl ->
                RemoteItem(pl.title, "playlist:${pl.ratingKey.removePrefix("pl:")}", isDir = true, meta = ItemMeta(title = pl.title, posterUrl = pl.thumb, posterRatio = 1f))
            }
            path.startsWith("playlist:") -> playlistTracks(path.removePrefix("playlist:")).mapNotNull(::songToRemoteItem)
            path == "random" -> randomTracks(100).mapNotNull(::songToRemoteItem)
            else -> emptyList()
        }
    }

    private fun songToRemoteItem(it: ServerItem): RemoteItem? {
        val url = it.playUrl ?: return null
        val fn = it.fileName ?: "${it.title}.mp3"
        return RemoteItem(
            name = fn,
            path = it.ratingKey,
            isDir = false,
            size = it.sizeBytes,
            playUrl = url,
            meta = ItemMeta(title = it.title, posterUrl = it.thumb, year = it.year, posterRatio = 1f),
        )
    }

    // ---------------- MediaBrowser 接口与专用音乐库检索 ----------------

    override suspend fun sections(): List<ServerSection> = withContext(Dispatchers.IO) {
        val folders = runCatching {
            getSubsonic("getMusicFolders.view")
                .optJSONObject("musicFolders")
                ?.optJSONArray("musicFolder")
                .objects()
        }.getOrDefault(emptyList())
        if (folders.size > 1) {
            folders.map { f ->
                ServerSection(f.opt("id")?.toString().orEmpty(), f.optString("name").ifBlank { "音乐库" }, "artist")
            }
        } else {
            listOf(ServerSection("all", server.name.ifBlank { "音乐库" }, "artist"))
        }
    }

    override suspend fun hubs(): List<ServerHub> = withContext(Dispatchers.IO) {
        val hubs = ArrayList<ServerHub>()
        runCatching {
            val newest = musicAlbums("newest", 16)
            if (newest.isNotEmpty()) hubs += ServerHub("music.newest", "最近添加的专辑", newest)
        }
        runCatching {
            val frequent = musicAlbums("frequent", 16)
            if (frequent.isNotEmpty()) hubs += ServerHub("music.frequent", "常听专辑", frequent)
        }
        runCatching {
            val recent = musicAlbums("recent", 16)
            if (recent.isNotEmpty()) hubs += ServerHub("music.recent", "最近播放的专辑", recent)
        }
        hubs
    }

    override suspend fun library(sectionKey: String, sort: LibrarySort, unwatchedOnly: Boolean): List<ServerItem> {
        val albumType = when (sort) {
            LibrarySort.NAME -> "alphabeticalByName"
            LibrarySort.ADDED -> "newest"
            LibrarySort.RELEASED -> "byYear"
            LibrarySort.RATING -> "highest"
        }
        return musicAlbums(type = albumType, size = 200, musicFolderId = sectionKey.takeIf { it != "all" && it.isNotBlank() })
    }

    override suspend fun item(ratingKey: String): ServerItem? = withContext(Dispatchers.IO) {
        when {
            ratingKey.startsWith("al:") -> {
                val a = getSubsonic("getAlbum.view", mapOf("id" to ratingKey.removePrefix("al:"))).optJSONObject("album")
                a?.let(::parseAlbum)
            }
            ratingKey.startsWith("ar:") -> {
                val ar = getSubsonic("getArtist.view", mapOf("id" to ratingKey.removePrefix("ar:"))).optJSONObject("artist")
                ar?.let(::parseArtist)
            }
            else -> {
                val s = getSubsonic("getSong.view", mapOf("id" to ratingKey.removePrefix("tr:"))).optJSONObject("song")
                s?.let(::parseSong)
            }
        }
    }

    override suspend fun children(ratingKey: String): List<ServerItem> = withContext(Dispatchers.IO) {
        when {
            ratingKey.startsWith("ar:") -> {
                val id = ratingKey.removePrefix("ar:")
                getSubsonic("getArtist.view", mapOf("id" to id))
                    .optJSONObject("artist")
                    ?.optJSONArray("album")
                    .objects()
                    .map(::parseAlbum)
            }
            ratingKey.startsWith("pl:") -> playlistTracks(ratingKey.removePrefix("pl:"))
            else -> {
                val id = ratingKey.removePrefix("al:")
                getSubsonic("getAlbum.view", mapOf("id" to id))
                    .optJSONObject("album")
                    ?.optJSONArray("song")
                    .objects()
                    .map(::parseSong)
            }
        }
    }

    override suspend fun setWatched(ratingKey: String, watched: Boolean) {}

    override fun timeline(ratingKey: String, state: String, timeMs: Long, durationMs: Long) {
        val songId = ratingKey.removePrefix("tr:")
        if (songId.isEmpty() || songId.startsWith("al:") || songId.startsWith("ar:")) return
        if (state == "playing" || state == "stopped") {
            runCatching {
                getSubsonic(
                    "scrobble.view",
                    mapOf("id" to songId, "submission" to (state == "stopped").toString()),
                )
            }
        }
    }

    override fun folderPath(section: ServerSection): String = ""

    override fun transcodeUrl(ratingKey: String): String =
        buildEndpointUrl("stream.view", mapOf("id" to ratingKey.removePrefix("tr:")))

    // ---------------- 音乐播放页专用查询 ----------------

    suspend fun musicTracks(sectionKey: String? = null, limit: Int = 200): List<ServerItem> = withContext(Dispatchers.IO) {
        val folderParam = sectionKey?.takeIf { it != "all" && it.isNotBlank() }
            ?.let { mapOf("musicFolderId" to it) }.orEmpty()
        // OpenSubsonic (Navidrome / Gonic / LMS) 支持 search3 空关键词返回曲库单曲
        val fromSearch = runCatching {
            getSubsonic(
                "search3.view",
                folderParam + mapOf("query" to "", "songCount" to limit.toString(), "albumCount" to "0", "artistCount" to "0"),
            ).optJSONObject("searchResult3")?.optJSONArray("song").objects().map(::parseSong)
        }.getOrDefault(emptyList())
        if (fromSearch.isNotEmpty()) return@withContext fromSearch

        // 传统 Subsonic 回退：拉取随机曲目或最新专辑内曲目
        val random = runCatching { randomTracks(limit, sectionKey) }.getOrDefault(emptyList())
        if (random.isNotEmpty()) return@withContext random

        val albums = musicAlbums("newest", 12, sectionKey)
        val out = ArrayList<ServerItem>()
        for (al in albums) {
            out += runCatching { children(al.ratingKey) }.getOrDefault(emptyList())
            if (out.size >= limit) break
        }
        out.take(limit)
    }

    suspend fun randomTracks(size: Int = 100, sectionKey: String? = null): List<ServerItem> = withContext(Dispatchers.IO) {
        val extra = buildMap {
            put("size", size.toString())
            if (!sectionKey.isNullOrBlank() && sectionKey != "all") put("musicFolderId", sectionKey)
        }
        getSubsonic("getRandomSongs.view", extra)
            .optJSONObject("randomSongs")
            ?.optJSONArray("song")
            .objects()
            .map(::parseSong)
    }

    suspend fun musicAlbums(type: String = "newest", size: Int = 120, musicFolderId: String? = null): List<ServerItem> =
        withContext(Dispatchers.IO) {
            val extra = buildMap {
                put("type", type)
                put("size", size.toString())
                if (!musicFolderId.isNullOrBlank() && musicFolderId != "all") put("musicFolderId", musicFolderId)
            }
            getSubsonic("getAlbumList2.view", extra)
                .optJSONObject("albumList2")
                ?.optJSONArray("album")
                .objects()
                .map(::parseAlbum)
        }

    suspend fun musicArtists(musicFolderId: String? = null): List<ServerItem> = withContext(Dispatchers.IO) {
        val extra = if (!musicFolderId.isNullOrBlank() && musicFolderId != "all") {
            mapOf("musicFolderId" to musicFolderId)
        } else emptyMap()
        val indexes = getSubsonic("getArtists.view", extra)
            .optJSONObject("artists")
            ?.optJSONArray("index")
            .objects()
        indexes.flatMap { idx -> idx.optJSONArray("artist").objects().map(::parseArtist) }
    }

    suspend fun musicPlaylists(): List<ServerItem> = withContext(Dispatchers.IO) {
        getSubsonic("getPlaylists.view")
            .optJSONObject("playlists")
            ?.optJSONArray("playlist")
            .objects()
            .map(::parsePlaylist)
    }

    suspend fun playlistTracks(playlistId: String): List<ServerItem> = withContext(Dispatchers.IO) {
        getSubsonic("getPlaylist.view", mapOf("id" to playlistId.removePrefix("pl:")))
            .optJSONObject("playlist")
            ?.optJSONArray("entry")
            .objects()
            .map(::parseSong)
    }

    suspend fun searchMusic(query: String): List<ServerItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val sr = getSubsonic(
            "search3.view",
            mapOf("query" to q, "songCount" to "60", "albumCount" to "24", "artistCount" to "16"),
        ).optJSONObject("searchResult3") ?: return@withContext emptyList()
        buildList {
            addAll(sr.optJSONArray("song").objects().map(::parseSong))
            addAll(sr.optJSONArray("album").objects().map(::parseAlbum))
            addAll(sr.optJSONArray("artist").objects().map(::parseArtist))
        }
    }

    /** 读取 OpenSubsonic 结构化逐行歌词或传统 Subsonic 歌词（后台线程调用） */
    fun fetchLyricsBlocking(songId: String, artist: String?, title: String?): Lyrics? {
        val cleanId = songId.removePrefix("tr:")
        if (cleanId.isNotEmpty()) {
            val structured = runCatching {
                getSubsonic("getLyricsBySongId.view", mapOf("id" to cleanId))
                    .optJSONObject("lyricsList")
                    ?.optJSONArray("structuredLyrics")
                    .objects()
            }.getOrDefault(emptyList())
            val best = structured.firstOrNull { it.optBoolean("synced") } ?: structured.firstOrNull()
            if (best != null) {
                val synced = best.optBoolean("synced")
                val offsetMs = best.optLong("offset", 0L)
                val lines = best.optJSONArray("line").objects().map { ln ->
                    val start = if (synced && ln.has("start")) (ln.optLong("start") + offsetMs).coerceAtLeast(0L) else null
                    LyricLine(start, ln.optString("value"))
                }
                if (lines.isNotEmpty()) return Lyrics(lines, synced = synced && lines.any { it.timeMs != null }, source = "Subsonic")
            }
        }
        if (!title.isNullOrBlank()) {
            val params = buildMap {
                put("title", title)
                if (!artist.isNullOrBlank()) put("artist", artist)
            }
            val raw = runCatching {
                getSubsonic("getLyrics.view", params).optJSONObject("lyrics")?.optString("value")
            }.getOrNull()
            if (!raw.isNullOrBlank()) {
                Lyrics.parse(raw, "Subsonic")?.let { return it }
            }
        }
        return null
    }

    internal fun parseSong(s: JSONObject): ServerItem {
        val id = s.opt("id")?.toString().orEmpty()
        val title = s.optString("title").ifBlank { s.optString("path").substringAfterLast('/').substringBeforeLast('.') }
        val artist = s.optString("artist").ifEmpty { null }
        val album = s.optString("album").ifEmpty { null }
        val suffix = s.optString("suffix").ifEmpty { "mp3" }
        val durationMs = s.optLong("duration") * 1_000L
        val cover = coverUrl(s.opt("coverArt")?.toString()?.ifEmpty { id }, 600)
        val baseFile = listOfNotNull(artist, title).joinToString(" - ").ifEmpty { title }
        val fileName = "${baseFile.replace('/', '_')}.$suffix"
        val playUrl = playUri(id, fileName)
        ServerProgress.register(
            playUrl,
            ServerProgress.Entry(
                serverId = server.id,
                ratingKey = "tr:$id",
                durationMs = durationMs,
                viewOffsetMs = 0L,
                title = title,
                artist = artist,
                album = album,
                coverUrl = cover,
            ),
        )
        return ServerItem(
            ratingKey = "tr:$id",
            type = "track",
            title = title,
            year = s.optInt("year").takeIf { it > 0 },
            thumb = cover,
            durationMs = durationMs,
            index = s.optInt("track").takeIf { it > 0 },
            parentIndex = s.optInt("discNumber").takeIf { it > 0 },
            parentTitle = album,
            grandparentTitle = artist,
            parentRatingKey = s.opt("albumId")?.toString()?.takeIf { it.isNotEmpty() }?.let { "al:$it" },
            grandparentRatingKey = s.opt("artistId")?.toString()?.takeIf { it.isNotEmpty() }?.let { "ar:$it" },
            fileName = fileName,
            playUrl = playUrl,
            sizeBytes = s.optLong("size"),
        )
    }

    private fun parseAlbum(a: JSONObject): ServerItem {
        val id = a.opt("id")?.toString().orEmpty()
        val name = a.optString("name").ifEmpty { a.optString("title").ifEmpty { "未知专辑" } }
        val artist = a.optString("artist").ifEmpty { null }
        return ServerItem(
            ratingKey = "al:$id",
            type = "album",
            title = name,
            year = a.optInt("year").takeIf { it > 0 },
            thumb = coverUrl(a.opt("coverArt")?.toString()?.ifEmpty { id }, 450),
            durationMs = a.optLong("duration") * 1_000L,
            leafCount = a.optInt("songCount"),
            childCount = a.optInt("songCount"),
            parentTitle = artist,
            grandparentTitle = artist,
        )
    }

    private fun parseArtist(ar: JSONObject): ServerItem {
        val id = ar.opt("id")?.toString().orEmpty()
        val name = ar.optString("name").ifEmpty { "未知歌手" }
        val thumb = ar.optString("artistImageUrl").takeIf { it.startsWith("http") }
            ?: coverUrl(ar.opt("coverArt")?.toString(), 450)
        return ServerItem(
            ratingKey = "ar:$id",
            type = "artist",
            title = name,
            thumb = thumb,
            childCount = ar.optInt("albumCount"),
        )
    }

    private fun parsePlaylist(pl: JSONObject): ServerItem {
        val id = pl.opt("id")?.toString().orEmpty()
        val name = pl.optString("name").ifEmpty { "歌单" }
        return ServerItem(
            ratingKey = "pl:$id",
            type = "playlist",
            title = name,
            summary = pl.optString("comment"),
            thumb = coverUrl(pl.opt("coverArt")?.toString(), 450),
            durationMs = pl.optLong("duration") * 1_000L,
            leafCount = pl.optInt("songCount"),
            childCount = pl.optInt("songCount"),
        )
    }

    companion object {
        private const val API_VERSION = "1.16.1"
        private const val CLIENT_NAME = "Voxtitle"
        private val SALT_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray()
        private val rng = SecureRandom()

        private fun randomSalt(len: Int = 8): String =
            CharArray(len) { SALT_CHARS[rng.nextInt(SALT_CHARS.size)] }.concatToString()

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        internal fun parseSubsonicResponse(rawBody: String): JSONObject {
            val root = try {
                JSONObject(rawBody)
            } catch (_: Exception) {
                throw IOException("服务器返回的不是有效的 Subsonic JSON 响应，请检查地址是否正确")
            }
            val sub = root.optJSONObject("subsonic-response")
                ?: throw IOException("未识别到 Subsonic / Navidrome 接口响应，请确认服务器地址（无需加 /rest）")
            if (sub.optString("status") != "ok") {
                val err = sub.optJSONObject("error")
                val code = err?.optInt("code") ?: -1
                val msg = err?.optString("message").orEmpty()
                throw SubsonicApiException(code, msg.ifBlank { "Subsonic 错误 $code" })
            }
            return sub
        }

        class SubsonicApiException(val code: Int, message: String) : IOException(message)

        fun login(baseUrl: String, lanUrl: String, username: String, password: String): String {
            val urls = listOf(lanUrl, baseUrl).map { it.trim().trimEnd('/').removeSuffix("/rest") }.filter { it.isNotEmpty() }
            if (urls.isEmpty()) throw IOException("请填写服务器地址")
            var last: IOException? = null
            for (url in urls) {
                try {
                    return loginSingle(url, username.trim(), password)
                } catch (e: SubsonicApiException) {
                    if (e.code == 40) throw IOException("用户名或密码错误")
                    last = e
                } catch (e: IOException) {
                    last = e
                }
            }
            throw last ?: IOException("连不上音乐库服务器")
        }

        private fun loginSingle(cleanBase: String, user: String, pass: String): String {
            val salt = randomSalt()
            val tokenUrl = "$cleanBase/rest/ping.view".toHttpUrl().newBuilder()
                .addQueryParameter("u", user)
                .addQueryParameter("t", md5Hex(pass + salt))
                .addQueryParameter("s", salt)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("f", "json")
                .build()
            try {
                cloudHttp.newCall(Request.Builder().url(tokenUrl).header("Accept", "application/json").build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("连接失败 HTTP ${resp.code}，请检查服务器地址")
                    parseSubsonicResponse(resp.body?.string().orEmpty())
                }
                return JSONObject()
                    .put("user", user)
                    .put("pass", pass)
                    .put("legacy_enc", false)
                    .toString()
            } catch (e: SubsonicApiException) {
                if (e.code != 41) throw e
                // code 41: LDAP 用户等不支持 token 校验的旧版服务端，回退为 enc: 编码口令
                val encUrl = "$cleanBase/rest/ping.view".toHttpUrl().newBuilder()
                    .addQueryParameter("u", user)
                    .addQueryParameter("p", "enc:" + hex(pass.toByteArray(Charsets.UTF_8)))
                    .addQueryParameter("v", API_VERSION)
                    .addQueryParameter("c", CLIENT_NAME)
                    .addQueryParameter("f", "json")
                    .build()
                cloudHttp.newCall(Request.Builder().url(encUrl).header("Accept", "application/json").build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("连接失败 HTTP ${resp.code}")
                    parseSubsonicResponse(resp.body?.string().orEmpty())
                }
                return JSONObject()
                    .put("user", user)
                    .put("pass", pass)
                    .put("legacy_enc", true)
                    .toString()
            }
        }
    }
}
