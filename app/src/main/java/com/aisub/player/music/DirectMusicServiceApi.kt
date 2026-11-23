package com.aisub.player.music

import android.webkit.CookieManager
import com.aisub.player.meta.DiscoverItem
import com.aisub.player.meta.MusicDiscoverRepository
import com.aisub.player.net.AppProxy
import com.aisub.player.remote.cloud.ServerItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * YouTube Music、网易云音乐、QQ音乐 原生直连曲库、搜索与高音质音频流解析客户端：
 * - 复用内置登录弹窗落盘的 Cookie（自动走全局代理 [AppProxy]），无需隐形 WebView 即可秒级拉取个人歌单、红心歌曲、专辑与全目录搜索；
 * - 播放时直接向官方接口请求最高可用音质直链（YouTube Music 256k AAC/Opus、网易云音乐 无损 FLAC / 320k、QQ音乐 无损 FLAC / 320k）
 *   交给原生 Media3 ExoPlayer 极速秒播；若遇到加密/PO-Token 限制导致暂无直链，由 [WebStreamingMusic] 自动无缝回退网页播放。
 */
object DirectMusicServiceApi {

    data class ResolvedAudioStream(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val qualityLabel: String = "",
        val durationMs: Long = 0L,
    )

    private const val BROWSER_UA = WebStreamingMusic.DESKTOP_CHROME_UA
    private const val YT_ANDROID_MUSIC_UA = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14) gzip"
    private const val YT_IOS_MUSIC_UA = "com.google.ios.youtubemusic/7.27 (iPhone16,2; U; CPU iOS 18_1 like Mac OS X)"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val http by lazy {
        AppProxy.builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(16, TimeUnit.SECONDS)
            .build()
    }

    private val neteaseUidCache = ConcurrentHashMap<String, Long>()

    // ---------------- Cookie 读取工具 ----------------

    internal fun readCookiesFor(service: WebMusicService): String {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return ""
        val urls = when (service) {
            WebMusicService.YOUTUBE_MUSIC -> listOf("https://music.youtube.com", "https://youtube.com", "https://www.youtube.com")
            WebMusicService.NETEASE_MUSIC -> listOf("https://music.163.com", "https://interface3.music.163.com")
            WebMusicService.QQ_MUSIC -> listOf("https://y.qq.com", "https://c.y.qq.com", "https://u.y.qq.com")
            else -> listOf(service.homeUrl)
        }
        val merged = LinkedHashMap<String, String>()
        for (u in urls) {
            val raw = runCatching { cm.getCookie(u).orEmpty() }.getOrDefault("")
            if (raw.isBlank()) continue
            for (part in raw.split(';')) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val k = part.substring(0, idx).trim()
                val v = part.substring(idx + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty() && !merged.containsKey(k)) {
                    merged[k] = v
                }
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    internal fun buildSapisidHash(cookies: String, origin: String = "https://music.youtube.com", nowSec: Long = System.currentTimeMillis() / 1000L): String? {
        val sapisid = WebStreamingMusic.extractCookieValue(cookies, "SAPISID")
            ?: WebStreamingMusic.extractCookieValue(cookies, "__Secure-3PAPISID")
            ?: WebStreamingMusic.extractCookieValue(cookies, "__Secure-1PAPISID")
            ?: return null
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$nowSec $sapisid $origin".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${nowSec}_$digest"
    }

    internal fun extractQqUinAndKey(cookies: String): Pair<String, String> {
        val rawUin = WebStreamingMusic.extractCookieValue(cookies, "uin")
            ?: WebStreamingMusic.extractCookieValue(cookies, "p_uin")
            ?: WebStreamingMusic.extractCookieValue(cookies, "wxuin")
            ?: ""
        val cleanUin = rawUin.trim().removePrefix("o").removePrefix("O").trimStart('0').ifEmpty { "0" }
        val key = WebStreamingMusic.extractCookieValue(cookies, "qqmusic_key")
            ?: WebStreamingMusic.extractCookieValue(cookies, "qm_keyst")
            ?: ""
        return cleanUin to key
    }

    private fun httpText(req: Request): String? = runCatching {
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            resp.body?.string()
        }
    }.getOrNull()

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (i in 0 until length()) {
                optJSONObject(i)?.let(::add)
            }
        }
    }

    private fun toTrackServerItem(
        service: WebMusicService,
        trackId: String,
        title: String,
        artist: String = "",
        album: String = "",
        durationMs: Long = 0L,
        coverUrl: String? = null,
        webUrl: String? = null,
        year: Int? = null,
    ): ServerItem {
        val entry = WebStreamingMusic.buildTrackEntry(
            service = service,
            trackId = trackId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            coverUrl = coverUrl,
            webUrl = webUrl,
        )
        return ServerItem(
            ratingKey = trackId,
            type = "track",
            title = title,
            year = year,
            thumb = coverUrl,
            durationMs = durationMs,
            parentTitle = album.ifBlank { null },
            grandparentTitle = artist.ifBlank { null },
            fileName = entry.title,
            playUrl = entry.uri,
        )
    }

    private fun discoverToServerItem(service: WebMusicService, item: DiscoverItem): ServerItem {
        val tid = when (service) {
            WebMusicService.NETEASE_MUSIC -> item.neteaseId
                ?: item.id.takeIf { it.startsWith("netease:") }?.substringAfter(':')
            WebMusicService.QQ_MUSIC -> item.id.takeIf { it.startsWith("qq:") }?.substringAfter(':')
                ?: item.platforms.firstOrNull { it.name.contains("QQ", ignoreCase = true) }?.url
                    ?.substringAfter("songDetail/")?.substringBefore('?')?.substringBefore('/')
            else -> null
        }
        val webUrl = when (service) {
            WebMusicService.NETEASE_MUSIC -> tid?.let { "https://music.163.com/#/song?id=$it" }
            WebMusicService.QQ_MUSIC -> tid?.let { "https://y.qq.com/n/ryqq/songDetail/$it" }
            WebMusicService.YOUTUBE_MUSIC -> tid?.let { "https://music.youtube.com/watch?v=$it" }
            else -> null
        }
        return toTrackServerItem(
            service = service,
            trackId = tid.orEmpty(),
            title = item.title,
            artist = item.artist,
            album = item.album,
            durationMs = item.durationMs,
            coverUrl = item.posterUrl ?: item.backdropUrl,
            webUrl = webUrl,
            year = item.year,
        )
    }

    // =========================================================================
    // 1. 统一对外入口（供 WebStreamingMusic 调度）
    // =========================================================================

    suspend fun loadTracks(service: WebMusicService): List<ServerItem> = withContext(Dispatchers.IO) {
        val cookies = readCookiesFor(service)
        when (service) {
            WebMusicService.YOUTUBE_MUSIC -> loadYouTubeTracks(cookies)
            WebMusicService.NETEASE_MUSIC -> loadNetEaseTracks(cookies)
            WebMusicService.QQ_MUSIC -> loadQqTracks(cookies)
            else -> emptyList()
        }
    }

    suspend fun loadAlbums(service: WebMusicService): List<ServerItem> = withContext(Dispatchers.IO) {
        val cookies = readCookiesFor(service)
        when (service) {
            WebMusicService.YOUTUBE_MUSIC -> loadYouTubeAlbums(cookies)
            WebMusicService.NETEASE_MUSIC -> loadNetEaseAlbums(cookies)
            WebMusicService.QQ_MUSIC -> loadQqAlbums(cookies)
            else -> emptyList()
        }
    }

    suspend fun loadPlaylists(service: WebMusicService): List<ServerItem> = withContext(Dispatchers.IO) {
        val cookies = readCookiesFor(service)
        when (service) {
            WebMusicService.YOUTUBE_MUSIC -> loadYouTubePlaylists(cookies)
            WebMusicService.NETEASE_MUSIC -> loadNetEasePlaylists(cookies)
            WebMusicService.QQ_MUSIC -> loadQqPlaylists(cookies)
            else -> emptyList()
        }
    }

    suspend fun loadContainerChildren(service: WebMusicService, container: ServerItem): List<ServerItem> = withContext(Dispatchers.IO) {
        val cookies = readCookiesFor(service)
        when (service) {
            WebMusicService.YOUTUBE_MUSIC -> loadYouTubeChildren(cookies, container.ratingKey)
            WebMusicService.NETEASE_MUSIC -> loadNetEaseChildren(cookies, container.ratingKey)
            WebMusicService.QQ_MUSIC -> loadQqChildren(cookies, container.ratingKey)
            else -> emptyList()
        }
    }

    suspend fun search(service: WebMusicService, query: String): List<ServerItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val cookies = readCookiesFor(service)
        when (service) {
            WebMusicService.YOUTUBE_MUSIC -> searchYouTube(cookies, q)
            WebMusicService.NETEASE_MUSIC -> searchNetEase(cookies, q)
            WebMusicService.QQ_MUSIC -> searchQq(cookies, q)
            else -> emptyList()
        }
    }

    suspend fun resolveAudioStream(
        service: WebMusicService,
        trackId: String,
        title: String,
        artist: String,
    ): ResolvedAudioStream? = withContext(Dispatchers.IO) {
        val cookies = readCookiesFor(service)
        when (service) {
            WebMusicService.YOUTUBE_MUSIC -> resolveYouTubeStream(cookies, trackId, title, artist)
            WebMusicService.NETEASE_MUSIC -> resolveNetEaseStream(cookies, trackId, title, artist)
            WebMusicService.QQ_MUSIC -> resolveQqStream(cookies, trackId, title, artist)
            else -> null
        }
    }

    // =========================================================================
    // 2. YouTube Music (InnerTube WEB_REMIX + ANDROID_MUSIC / IOS_MUSIC)
    // =========================================================================

    private fun ytInnerTubeRequest(endpoint: String, body: JSONObject, cookies: String): JSONObject? {
        val reqBuilder = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .header("User-Agent", BROWSER_UA)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("X-Origin", "https://music.youtube.com")
            .header("X-Goog-AuthUser", "0")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", "1.20251110.01.00")
            .post(body.toString().toRequestBody(JSON_MEDIA))
        if (cookies.isNotBlank()) {
            reqBuilder.header("Cookie", cookies)
            buildSapisidHash(cookies)?.let { reqBuilder.header("Authorization", it) }
        }
        val text = httpText(reqBuilder.build()) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun ytWebRemixContext(): JSONObject = JSONObject().put(
        "client",
        JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", "1.20251110.01.00")
            .put("hl", "zh-CN")
            .put("gl", "US"),
    )

    private fun loadYouTubeTracks(cookies: String): List<ServerItem> {
        val liked = ytInnerTubeRequest(
            "browse",
            JSONObject().put("context", ytWebRemixContext()).put("browseId", "FEmusic_liked_videos"),
            cookies,
        )?.let { parseYouTubeItems(it, wantType = "track") }.orEmpty()
        if (liked.isNotEmpty()) return liked

        val history = ytInnerTubeRequest(
            "browse",
            JSONObject().put("context", ytWebRemixContext()).put("browseId", "FEmusic_history"),
            cookies,
        )?.let { parseYouTubeItems(it, wantType = "track") }.orEmpty()
        if (history.isNotEmpty()) return history

        val home = ytInnerTubeRequest(
            "browse",
            JSONObject().put("context", ytWebRemixContext()).put("browseId", "FEmusic_home"),
            cookies,
        )?.let { parseYouTubeItems(it, wantType = "track") }.orEmpty()
        if (home.isNotEmpty()) return home

        return searchYouTube(cookies, "热门华语流行音乐").filter { it.type == "track" }
    }

    private fun loadYouTubeAlbums(cookies: String): List<ServerItem> {
        val liked = ytInnerTubeRequest(
            "browse",
            JSONObject().put("context", ytWebRemixContext()).put("browseId", "FEmusic_liked_albums"),
            cookies,
        )?.let { parseYouTubeItems(it, wantType = "album") }.orEmpty()
        if (liked.isNotEmpty()) return liked

        return ytInnerTubeRequest(
            "browse",
            JSONObject().put("context", ytWebRemixContext()).put("browseId", "FEmusic_explore"),
            cookies,
        )?.let { parseYouTubeItems(it, wantType = "album") }.orEmpty()
    }

    private fun loadYouTubePlaylists(cookies: String): List<ServerItem> {
        val liked = ytInnerTubeRequest(
            "browse",
            JSONObject().put("context", ytWebRemixContext()).put("browseId", "FEmusic_liked_playlists"),
            cookies,
        )?.let { parseYouTubeItems(it, wantType = "playlist") }.orEmpty()
        if (liked.isNotEmpty()) return liked

        return ytInnerTubeRequest(
            "browse",
            JSONObject().put("context", ytWebRemixContext()).put("browseId", "FEmusic_explore"),
            cookies,
        )?.let { parseYouTubeItems(it, wantType = "playlist") }.orEmpty()
    }

    private fun loadYouTubeChildren(cookies: String, ratingKey: String): List<ServerItem> {
        val rawId = ratingKey.substringAfter('|', ratingKey).trim()
        if (rawId.isEmpty()) return emptyList()
        val browseId = when {
            rawId.startsWith("VL") || rawId.startsWith("MPRE") || rawId.startsWith("FEmusic_") -> rawId
            rawId.startsWith("PL") || rawId.startsWith("OLAK") || rawId.startsWith("RD") || rawId == "LM" -> "VL$rawId"
            else -> rawId
        }
        val json = ytInnerTubeRequest(
            "browse",
            JSONObject().put("context", ytWebRemixContext()).put("browseId", browseId),
            cookies,
        ) ?: return emptyList()
        return parseYouTubeItems(json, wantType = "track")
    }

    private fun searchYouTube(cookies: String, query: String): List<ServerItem> {
        val json = ytInnerTubeRequest(
            "search",
            JSONObject().put("context", ytWebRemixContext()).put("query", query),
            cookies,
        ) ?: return emptyList()
        val tracks = parseYouTubeItems(json, wantType = "track").take(25)
        val albums = parseYouTubeItems(json, wantType = "album").take(10)
        val playlists = parseYouTubeItems(json, wantType = "playlist").take(10)
        return tracks + albums + playlists
    }

    internal fun parseYouTubeItems(root: JSONObject, wantType: String? = null): List<ServerItem> {
        val out = ArrayList<ServerItem>()
        val seen = HashSet<String>()

        fun extractText(obj: JSONObject?): String {
            if (obj == null) return ""
            val runs = obj.optJSONArray("runs")
            if (runs != null) {
                return buildString {
                    for (i in 0 until runs.length()) {
                        append(runs.optJSONObject(i)?.optString("text").orEmpty())
                    }
                }.trim()
            }
            return obj.optString("simpleText").trim()
        }

        fun extractThumb(obj: JSONObject?): String? {
            val arr = obj?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
                ?: obj?.optJSONArray("thumbnails")
                ?: return null
            if (arr.length() == 0) return null
            val raw = arr.optJSONObject(arr.length() - 1)?.optString("url")?.trim().orEmpty()
            return raw.takeIf { it.startsWith("http") }
                ?.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
        }

        fun parseDurationText(s: String): Long {
            val parts = s.trim().split(':').mapNotNull { it.toLongOrNull() }
            return when (parts.size) {
                2 -> (parts[0] * 60L + parts[1]) * 1000L
                3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L
                else -> 0L
            }
        }

        fun handleResponsiveItem(item: JSONObject) {
            val cols = item.optJSONArray("flexColumns") ?: return
            val colTexts = ArrayList<String>()
            for (i in 0 until cols.length()) {
                val txt = extractText(cols.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
                if (txt.isNotEmpty()) colTexts += txt
            }
            val title = colTexts.firstOrNull().orEmpty()
            if (title.isEmpty()) return

            var videoId = item.optJSONObject("playlistItemData")?.optString("videoId")?.trim().orEmpty()
            if (videoId.isEmpty()) {
                videoId = item.optJSONObject("overlay")
                    ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("musicPlayButtonRenderer")
                    ?.optJSONObject("playNavigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId")?.trim().orEmpty()
            }
            if (videoId.isEmpty()) {
                val firstRuns = cols.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")
                videoId = firstRuns?.optJSONObject(0)
                    ?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId")?.trim().orEmpty()
            }

            val navBrowse = item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            val browseId = navBrowse?.optString("browseId")?.trim().orEmpty()
            val pageType = navBrowse?.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")
                ?.optString("pageType")?.trim().orEmpty()

            val thumb = extractThumb(item.optJSONObject("thumbnail"))
            val subTokens = colTexts.drop(1).flatMap { it.split(" • ", " · ") }.map { it.trim() }.filter { it.isNotEmpty() }
            var durationMs = 0L
            val fixedCols = item.optJSONArray("fixedColumns")
            if (fixedCols != null) {
                for (i in 0 until fixedCols.length()) {
                    val ft = extractText(fixedCols.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text"))
                    val parsed = parseDurationText(ft)
                    if (parsed > 0L) durationMs = parsed
                }
            }
            if (durationMs == 0L) {
                durationMs = subTokens.firstNotNullOfOrNull { parseDurationText(it).takeIf { d -> d > 0L } } ?: 0L
            }

            val metaTokens = subTokens.filterNot {
                parseDurationText(it) > 0L ||
                    it.equals("歌曲", ignoreCase = true) ||
                    it.equals("Song", ignoreCase = true) ||
                    it.equals("视频", ignoreCase = true) ||
                    it.equals("Video", ignoreCase = true) ||
                    it.equals("专辑", ignoreCase = true) ||
                    it.equals("Album", ignoreCase = true) ||
                    it.equals("单曲", ignoreCase = true) ||
                    it.equals("Single", ignoreCase = true) ||
                    it.equals("EP", ignoreCase = true) ||
                    it.equals("播放列表", ignoreCase = true) ||
                    it.equals("Playlist", ignoreCase = true) ||
                    it.contains("次播放") || it.contains("plays", ignoreCase = true)
            }
            val artist = metaTokens.firstOrNull().orEmpty()
            val album = metaTokens.getOrNull(1).orEmpty()

            when {
                videoId.isNotEmpty() -> {
                    if (wantType != null && wantType != "track") return
                    if (!seen.add("t:$videoId")) return
                    out += toTrackServerItem(
                        service = WebMusicService.YOUTUBE_MUSIC,
                        trackId = videoId,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs,
                        coverUrl = thumb,
                        webUrl = "https://music.youtube.com/watch?v=$videoId",
                    )
                }
                browseId.isNotEmpty() && (pageType.contains("ALBUM") || browseId.startsWith("MPRE")) -> {
                    if (wantType != null && wantType != "album") return
                    if (!seen.add("a:$browseId")) return
                    out += ServerItem(
                        ratingKey = "album|$browseId",
                        type = "album",
                        title = title,
                        thumb = thumb,
                        parentTitle = artist.ifBlank { null },
                        grandparentTitle = artist.ifBlank { null },
                    )
                }
                browseId.isNotEmpty() && (pageType.contains("PLAYLIST") || browseId.startsWith("VL") || browseId.startsWith("FEmusic_liked")) -> {
                    if (wantType != null && wantType != "playlist") return
                    if (!seen.add("p:$browseId")) return
                    out += ServerItem(
                        ratingKey = "playlist|$browseId",
                        type = "playlist",
                        title = title,
                        thumb = thumb,
                        summary = metaTokens.joinToString(" · "),
                    )
                }
            }
        }

        fun handleTwoRowItem(item: JSONObject) {
            val title = extractText(item.optJSONObject("title"))
            if (title.isEmpty()) return
            val subtitle = extractText(item.optJSONObject("subtitle"))
            val thumb = extractThumb(item.optJSONObject("thumbnailRenderer"))
            val nav = item.optJSONObject("navigationEndpoint")
            val videoId = nav?.optJSONObject("watchEndpoint")?.optString("videoId")?.trim().orEmpty()
            val browse = nav?.optJSONObject("browseEndpoint")
            val browseId = browse?.optString("browseId")?.trim().orEmpty()
            val pageType = browse?.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")
                ?.optString("pageType")?.trim().orEmpty()

            val subClean = subtitle.split(" • ", " · ").map { it.trim() }.filterNot {
                it.isEmpty() || it.equals("专辑", true) || it.equals("Album", true) ||
                    it.equals("单曲", true) || it.equals("Single", true) || it.equals("EP", true) ||
                    it.equals("播放列表", true) || it.equals("Playlist", true)
            }
            val artist = subClean.firstOrNull().orEmpty()

            when {
                videoId.isNotEmpty() -> {
                    if (wantType != null && wantType != "track") return
                    if (!seen.add("t:$videoId")) return
                    out += toTrackServerItem(
                        service = WebMusicService.YOUTUBE_MUSIC,
                        trackId = videoId,
                        title = title,
                        artist = artist,
                        coverUrl = thumb,
                        webUrl = "https://music.youtube.com/watch?v=$videoId",
                    )
                }
                browseId.isNotEmpty() && (pageType.contains("ALBUM") || browseId.startsWith("MPRE")) -> {
                    if (wantType != null && wantType != "album") return
                    if (!seen.add("a:$browseId")) return
                    out += ServerItem(
                        ratingKey = "album|$browseId",
                        type = "album",
                        title = title,
                        thumb = thumb,
                        parentTitle = artist.ifBlank { null },
                        grandparentTitle = artist.ifBlank { null },
                    )
                }
                browseId.isNotEmpty() && (pageType.contains("PLAYLIST") || browseId.startsWith("VL") || browseId.startsWith("FEmusic_liked")) -> {
                    if (wantType != null && wantType != "playlist") return
                    if (!seen.add("p:$browseId")) return
                    out += ServerItem(
                        ratingKey = "playlist|$browseId",
                        type = "playlist",
                        title = title,
                        thumb = thumb,
                        summary = subClean.joinToString(" · "),
                    )
                }
            }
        }

        fun walk(node: Any?, depth: Int) {
            if (node == null || depth > 22 || out.size >= 120) return
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("musicResponsiveListItemRenderer")?.let(::handleResponsiveItem)
                    node.optJSONObject("musicTwoRowItemRenderer")?.let(::handleTwoRowItem)
                    val keys = node.keys()
                    while (keys.hasNext() && out.size < 120) {
                        val k = keys.next()
                        if (k == "musicResponsiveListItemRenderer" || k == "musicTwoRowItemRenderer" || k == "trackingParams") continue
                        walk(node.opt(k), depth + 1)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        if (out.size >= 120) break
                        walk(node.opt(i), depth + 1)
                    }
                }
            }
        }
        walk(root, 0)
        return out
    }

    private fun resolveYouTubeStream(
        cookies: String,
        trackId: String,
        title: String,
        artist: String,
    ): ResolvedAudioStream? {
        var videoId = trackId.trim()
        if (videoId.isEmpty() && title.isNotBlank()) {
            val q = listOfNotNull(title, artist.takeIf { it.isNotBlank() }).joinToString(" ")
            videoId = searchYouTube(cookies, q).firstOrNull { it.type == "track" }?.ratingKey.orEmpty()
        }
        if (videoId.isEmpty()) return null

        val clientConfigs = listOf(
            Triple(
                "ANDROID_MUSIC",
                YT_ANDROID_MUSIC_UA,
                JSONObject()
                    .put("clientName", "ANDROID_MUSIC")
                    .put("clientVersion", "7.27.52")
                    .put("androidSdkVersion", 34)
                    .put("osName", "Android")
                    .put("osVersion", "14")
                    .put("hl", "zh-CN")
                    .put("gl", "US"),
            ),
            Triple(
                "IOS_MUSIC",
                YT_IOS_MUSIC_UA,
                JSONObject()
                    .put("clientName", "IOS_MUSIC")
                    .put("clientVersion", "7.27")
                    .put("deviceModel", "iPhone16,2")
                    .put("osName", "iPhone")
                    .put("osVersion", "18.1.0.22B83")
                    .put("hl", "zh-CN")
                    .put("gl", "US"),
            ),
            Triple(
                "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                BROWSER_UA,
                JSONObject()
                    .put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                    .put("clientVersion", "2.0")
                    .put("hl", "zh-CN")
                    .put("gl", "US"),
            ),
        )

        for ((clientName, ua, clientJson) in clientConfigs) {
            val payload = JSONObject()
                .put("context", JSONObject().put("client", clientJson))
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
            val reqBuilder = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", ua)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("X-Origin", "https://music.youtube.com")
                .header("X-Goog-AuthUser", "0")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
            if (cookies.isNotBlank() && clientName != "TVHTML5_SIMPLY_EMBEDDED_PLAYER") {
                reqBuilder.header("Cookie", cookies)
                buildSapisidHash(cookies)?.let { reqBuilder.header("Authorization", it) }
            }
            val text = httpText(reqBuilder.build()) ?: continue
            val root = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val parsed = parseBestYouTubeAudioFormat(root, ua)
            if (parsed != null) return parsed
        }
        return null
    }

    internal fun parseBestYouTubeAudioFormat(root: JSONObject, ua: String = YT_ANDROID_MUSIC_UA): ResolvedAudioStream? {
        val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        if (status.isNotEmpty() && status != "OK") return null
        val streaming = root.optJSONObject("streamingData") ?: return null
        val adaptive = streaming.optJSONArray("adaptiveFormats").objects()
        val durationSec = root.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull() ?: 0L

        val candidates = adaptive.filter { fmt ->
            val mime = fmt.optString("mimeType").lowercase()
            val url = fmt.optString("url").trim()
            mime.startsWith("audio/") && url.startsWith("http")
        }
        if (candidates.isEmpty()) return null

        // 音质优先级：256k Opus (774) > 256k AAC Premium (141) > 160k Opus (251) > 128k AAC (140) > 其余最高码率
        val best = candidates.maxByOrNull { fmt ->
            val itag = fmt.optInt("itag", 0)
            val br = fmt.optInt("averageBitrate", 0).takeIf { it > 0 } ?: fmt.optInt("bitrate", 0)
            val tierBonus = when (itag) {
                774 -> 5_000_000
                141 -> 4_500_000
                251 -> 3_000_000
                140 -> 2_500_000
                250 -> 1_500_000
                else -> 0
            }
            tierBonus + br
        } ?: return null

        val url = best.optString("url").trim()
        val itag = best.optInt("itag", 0)
        val brKbps = (best.optInt("averageBitrate", 0).takeIf { it > 0 } ?: best.optInt("bitrate", 0)) / 1000
        val label = when (itag) {
            774 -> "256k Opus"
            141 -> "256k AAC"
            251 -> "${brKbps.coerceAtLeast(128)}k Opus"
            140 -> "128k AAC"
            else -> if (brKbps > 0) "${brKbps}k" else "YouTube Audio"
        }
        val durMs = best.optString("approxDurationMs").toLongOrNull()
            ?: (durationSec * 1000L).takeIf { it > 0L }
            ?: 0L
        return ResolvedAudioStream(
            url = url,
            headers = mapOf(
                "User-Agent" to ua,
                "Origin" to "https://music.youtube.com",
                "Referer" to "https://music.youtube.com/",
            ),
            qualityLabel = label,
            durationMs = durMs,
        )
    }

    // =========================================================================
    // 3. 网易云音乐 (music.163.com / interface3.music.163.com)
    // =========================================================================

    private fun neteaseCookieHeader(cookies: String): String =
        if (cookies.isBlank()) "os=pc; appver=8.10.0" else "$cookies; os=pc; appver=8.10.0"

    private fun fetchNetEaseUid(cookies: String): Long? {
        val musicU = WebStreamingMusic.extractCookieValue(cookies, "MUSIC_U") ?: return null
        neteaseUidCache[musicU]?.let { return it }
        val req = Request.Builder()
            .url("https://music.163.com/api/nuser/account/get")
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", neteaseCookieHeader(cookies))
            .post(FormBody.Builder().build())
            .build()
        val text = httpText(req) ?: return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val uid = root.optJSONObject("account")?.optLong("id", 0L)?.takeIf { it > 0L }
            ?: root.optJSONObject("profile")?.optLong("userId", 0L)?.takeIf { it > 0L }
            ?: return null
        neteaseUidCache[musicU] = uid
        return uid
    }

    private fun parseNetEaseTrackObj(s: JSONObject): ServerItem? {
        val id = s.optLong("id", 0L)
        val title = s.optString("name").trim()
        if (id <= 0L || title.isEmpty()) return null
        val artistsArr = s.optJSONArray("ar") ?: s.optJSONArray("artists")
        val artist = artistsArr.objects().mapNotNull { it.optString("name").trim().takeIf(String::isNotEmpty) }.joinToString(" / ")
        val al = s.optJSONObject("al") ?: s.optJSONObject("album")
        val album = al?.optString("name")?.trim().orEmpty()
        val cover = al?.optString("picUrl")?.trim()
            ?.takeIf { it.startsWith("http") }
            ?.replace("http://", "https://")
            ?.let { if ("?param=" in it) it else "$it?param=500y500" }
        val dur = s.optLong("dt", 0L).takeIf { it > 0L } ?: s.optLong("duration", 0L)
        return toTrackServerItem(
            service = WebMusicService.NETEASE_MUSIC,
            trackId = id.toString(),
            title = title,
            artist = artist,
            album = album,
            durationMs = dur.coerceAtLeast(0L),
            coverUrl = cover,
            webUrl = "https://music.163.com/#/song?id=$id",
        )
    }

    private fun fetchNetEasePlaylistTracks(cookies: String, playlistId: String, limit: Int = 100): List<ServerItem> {
        val body = FormBody.Builder()
            .add("id", playlistId)
            .add("n", limit.coerceIn(10, 100).toString())
            .add("s", "0")
            .build()
        val req = Request.Builder()
            .url("https://music.163.com/api/v6/playlist/detail")
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", neteaseCookieHeader(cookies))
            .post(body)
            .build()
        val text = httpText(req) ?: return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val pl = root.optJSONObject("playlist") ?: return emptyList()
        val direct = pl.optJSONArray("tracks").objects().mapNotNull(::parseNetEaseTrackObj)
        val ids = pl.optJSONArray("trackIds").objects()
            .mapNotNull { it.optLong("id", 0L).takeIf { v -> v > 0L }?.toString() }
            .take(limit)
        if (direct.size >= limit || ids.size <= direct.size) return direct.take(limit)

        val have = direct.map { it.ratingKey }.toSet()
        val missing = ids.filterNot { it in have }.take(limit - direct.size)
        if (missing.isEmpty()) return direct.take(limit)

        val cArr = JSONArray().apply { missing.forEach { id -> id.toLongOrNull()?.let { put(JSONObject().put("id", it)) } } }
        val detailReq = Request.Builder()
            .url("https://music.163.com/api/v3/song/detail")
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", neteaseCookieHeader(cookies))
            .post(FormBody.Builder().add("c", cArr.toString()).build())
            .build()
        val detailText = httpText(detailReq)
        val extra = detailText?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONArray("songs").objects().mapNotNull(::parseNetEaseTrackObj)
        val byId = (direct + extra).associateBy { it.ratingKey }
        return ids.mapNotNull { byId[it] }.ifEmpty { direct }.take(limit)
    }

    private fun loadNetEasePlaylists(cookies: String): List<ServerItem> {
        val uid = fetchNetEaseUid(cookies)
        if (uid != null) {
            val req = Request.Builder()
                .url("https://music.163.com/api/user/playlist")
                .header("User-Agent", BROWSER_UA)
                .header("Referer", "https://music.163.com/")
                .header("Cookie", neteaseCookieHeader(cookies))
                .post(
                    FormBody.Builder()
                        .add("uid", uid.toString())
                        .add("limit", "100")
                        .add("offset", "0")
                        .build(),
                )
                .build()
            val text = httpText(req)
            val arr = text?.let { runCatching { JSONObject(it) }.getOrNull() }?.optJSONArray("playlist").objects()
            val parsed = arr.mapNotNull { pl ->
                val id = pl.optLong("id", 0L)
                val name = pl.optString("name").trim()
                if (id <= 0L || name.isEmpty()) return@mapNotNull null
                val cover = pl.optString("coverImgUrl").trim().takeIf { it.startsWith("http") }?.replace("http://", "https://")
                val count = pl.optInt("trackCount", 0).coerceAtLeast(0)
                val desc = pl.optString("description").trim().takeIf { it != "null" }.orEmpty()
                ServerItem(
                    ratingKey = "playlist|$id",
                    type = "playlist",
                    title = name,
                    summary = desc,
                    thumb = cover,
                    leafCount = count,
                    childCount = count,
                )
            }
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun loadNetEaseTracks(cookies: String): List<ServerItem> {
        val playlists = loadNetEasePlaylists(cookies)
        val favId = playlists.firstOrNull()?.ratingKey?.substringAfter('|')
        if (!favId.isNullOrBlank()) {
            val liked = fetchNetEasePlaylistTracks(cookies, favId, 100)
            if (liked.isNotEmpty()) return liked
        }
        val recReq = Request.Builder()
            .url("https://music.163.com/api/v1/discovery/recommend/songs")
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", neteaseCookieHeader(cookies))
            .post(FormBody.Builder().build())
            .build()
        val recText = httpText(recReq)
        val recSongs = recText?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.let { it.optJSONObject("data")?.optJSONArray("dailySongs") ?: it.optJSONArray("recommend") }
            .objects().mapNotNull(::parseNetEaseTrackObj)
        if (recSongs.isNotEmpty()) return recSongs

        return MusicDiscoverRepository.fetchNeteaseNewExpress(40).map { discoverToServerItem(WebMusicService.NETEASE_MUSIC, it) }
    }

    private fun loadNetEaseAlbums(cookies: String): List<ServerItem> {
        val subReq = Request.Builder()
            .url("https://music.163.com/api/album/sublist")
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", neteaseCookieHeader(cookies))
            .post(FormBody.Builder().add("limit", "100").add("offset", "0").build())
            .build()
        val subText = httpText(subReq)
        val subList = subText?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONArray("data").objects().mapNotNull(::parseNetEaseAlbumObj)
        if (subList.isNotEmpty()) return subList

        val newReq = Request.Builder()
            .url("https://music.163.com/api/album/new?limit=30&offset=0")
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", neteaseCookieHeader(cookies))
            .build()
        return httpText(newReq)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONArray("albums").objects().mapNotNull(::parseNetEaseAlbumObj)
    }

    private fun parseNetEaseAlbumObj(al: JSONObject): ServerItem? {
        val id = al.optLong("id", 0L)
        val name = al.optString("name").trim()
        if (id <= 0L || name.isEmpty()) return null
        val artists = al.optJSONArray("artists").objects()
            .mapNotNull { it.optString("name").trim().takeIf(String::isNotEmpty) }
            .joinToString(" / ")
            .ifBlank { al.optJSONObject("artist")?.optString("name")?.trim().orEmpty() }
        val cover = al.optString("picUrl").trim().takeIf { it.startsWith("http") }?.replace("http://", "https://")
        val size = al.optInt("size", 0).coerceAtLeast(0)
        return ServerItem(
            ratingKey = "album|$id",
            type = "album",
            title = name,
            thumb = cover,
            leafCount = size,
            childCount = size,
            parentTitle = artists.ifBlank { null },
            grandparentTitle = artists.ifBlank { null },
        )
    }

    private fun loadNetEaseChildren(cookies: String, ratingKey: String): List<ServerItem> {
        val kind = ratingKey.substringBefore('|', "")
        val id = ratingKey.substringAfter('|', ratingKey).trim()
        if (id.isEmpty()) return emptyList()
        return if (kind == "album") {
            val req = Request.Builder()
                .url("https://music.163.com/api/v1/album/$id")
                .header("User-Agent", BROWSER_UA)
                .header("Referer", "https://music.163.com/")
                .header("Cookie", neteaseCookieHeader(cookies))
                .build()
            val text = httpText(req) ?: return emptyList()
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            root.optJSONArray("songs").objects().mapNotNull(::parseNetEaseTrackObj)
        } else {
            fetchNetEasePlaylistTracks(cookies, id, 100)
        }
    }

    private fun searchNetEase(cookies: String, query: String): List<ServerItem> {
        val out = ArrayList<ServerItem>()
        fun cloudSearch(type: Int, limit: Int): JSONObject? {
            val url = "https://music.163.com/api/cloudsearch/pc".toHttpUrl().newBuilder()
                .addQueryParameter("s", query)
                .addQueryParameter("type", type.toString())
                .addQueryParameter("limit", limit.toString())
                .build()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Referer", "https://music.163.com/")
                .header("Cookie", neteaseCookieHeader(cookies))
                .build()
            val text = httpText(req) ?: return null
            return runCatching { JSONObject(text) }.getOrNull()?.optJSONObject("result")
        }
        cloudSearch(1, 25)?.optJSONArray("songs").objects().mapNotNull(::parseNetEaseTrackObj).let(out::addAll)
        cloudSearch(10, 8)?.optJSONArray("albums").objects().mapNotNull(::parseNetEaseAlbumObj).let(out::addAll)
        cloudSearch(1000, 8)?.optJSONArray("playlists").objects().mapNotNull { pl ->
            val id = pl.optLong("id", 0L)
            val name = pl.optString("name").trim()
            if (id <= 0L || name.isEmpty()) return@mapNotNull null
            val cover = pl.optString("coverImgUrl").trim().takeIf { it.startsWith("http") }?.replace("http://", "https://")
            val count = pl.optInt("trackCount", 0).coerceAtLeast(0)
            ServerItem(
                ratingKey = "playlist|$id",
                type = "playlist",
                title = name,
                thumb = cover,
                leafCount = count,
                childCount = count,
            )
        }.let(out::addAll)
        return out
    }

    private fun resolveNetEaseStream(
        cookies: String,
        trackId: String,
        title: String,
        artist: String,
    ): ResolvedAudioStream? {
        var songId = trackId.trim()
        if (songId.isEmpty() && title.isNotBlank()) {
            val q = listOfNotNull(title, artist.takeIf { it.isNotBlank() }).joinToString(" ")
            songId = MusicDiscoverRepository.fetchNeteaseSearch(q, 5).firstOrNull()?.neteaseId.orEmpty()
        }
        if (songId.isEmpty()) return null

        val levels = listOf(
            "lossless" to "flac",
            "exhigh" to "mp3",
            "standard" to "mp3",
        )
        for ((level, enc) in levels) {
            val req = Request.Builder()
                .url("https://interface3.music.163.com/api/song/enhance/player/url/v1")
                .header("User-Agent", BROWSER_UA)
                .header("Referer", "https://music.163.com/")
                .header("Cookie", neteaseCookieHeader(cookies))
                .post(
                    FormBody.Builder()
                        .add("ids", "[$songId]")
                        .add("level", level)
                        .add("encodeType", enc)
                        .build(),
                )
                .build()
            val text = httpText(req) ?: continue
            val root = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val stream = parseNetEasePlayerUrlResponse(root)
            if (stream != null) return stream
        }
        return null
    }

    internal fun parseNetEasePlayerUrlResponse(root: JSONObject): ResolvedAudioStream? {
        val item = root.optJSONArray("data")?.optJSONObject(0) ?: return null
        val url = item.optString("url").trim().takeIf { it.startsWith("http") } ?: return null
        val level = item.optString("level").trim()
        val br = item.optInt("br", 0)
        val durMs = item.optLong("time", 0L).coerceAtLeast(0L)
        val label = when {
            level.equals("lossless", true) || level.equals("hires", true) -> "无损 FLAC"
            br >= 300_000 -> "极高 320k"
            br > 0 -> "${br / 1000}k"
            else -> "网易云直连"
        }
        return ResolvedAudioStream(
            url = url.replace("http://", "https://"),
            headers = mapOf(
                "User-Agent" to BROWSER_UA,
                "Referer" to "https://music.163.com/",
            ),
            qualityLabel = label,
            durationMs = durMs,
        )
    }

    // =========================================================================
    // 4. QQ音乐 (u.y.qq.com / c.y.qq.com)
    // =========================================================================

    private fun qqMusicuRequest(payload: JSONObject, cookies: String): JSONObject? {
        val reqBuilder = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://y.qq.com/")
            .header("Origin", "https://y.qq.com")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
        if (cookies.isNotBlank()) reqBuilder.header("Cookie", cookies)
        val text = httpText(reqBuilder.build()) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun qqComm(cookies: String): JSONObject {
        val (uin, key) = extractQqUinAndKey(cookies)
        val isWx = cookies.contains("wxuin=") && !cookies.contains("p_uin=")
        return JSONObject()
            .put("uin", uin.toLongOrNull() ?: 0L)
            .put("authst", key)
            .put("tmeLoginType", if (isWx) 1 else 2)
            .put("format", "json")
            .put("ct", 24)
            .put("cv", 0)
    }

    private fun parseQqTrackObj(s: JSONObject): ServerItem? {
        val item = MusicDiscoverRepository.parseQqSongJson(s) ?: return null
        return discoverToServerItem(WebMusicService.QQ_MUSIC, item)
    }

    private fun fetchQqProfileHomepage(cookies: String): JSONObject? {
        val (uin, _) = extractQqUinAndKey(cookies)
        if (uin == "0") return null
        val url = "https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg".toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("inCharset", "utf8")
            .addQueryParameter("outCharset", "utf-8")
            .addQueryParameter("cid", "205360838")
            .addQueryParameter("userid", uin)
            .addQueryParameter("reqfrom", "1")
            .addQueryParameter("reqtype", "0")
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://y.qq.com/")
            .header("Cookie", cookies)
            .build()
        val text = httpText(req) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()?.optJSONObject("data")
    }

    private fun fetchQqPlaylistTracks(cookies: String, disstid: String): List<ServerItem> {
        val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg".toHttpUrl().newBuilder()
            .addQueryParameter("type", "1")
            .addQueryParameter("json", "1")
            .addQueryParameter("utf8", "1")
            .addQueryParameter("onlysong", "0")
            .addQueryParameter("disstid", disstid)
            .addQueryParameter("format", "json")
            .build()
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://y.qq.com/")
        if (cookies.isNotBlank()) reqBuilder.header("Cookie", cookies)
        val text = httpText(reqBuilder.build()) ?: return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val cd = root.optJSONArray("cdlist")?.optJSONObject(0) ?: return emptyList()
        return cd.optJSONArray("songlist").objects().mapNotNull(::parseQqTrackObj).take(100)
    }

    private fun loadQqPlaylists(cookies: String): List<ServerItem> {
        val out = ArrayList<ServerItem>()
        val seen = HashSet<String>()
        val home = fetchQqProfileHomepage(cookies)
        if (home != null) {
            home.optJSONArray("mymusic").objects().forEach { mm ->
                val id = mm.optString("id").trim()
                val title = mm.optString("title").trim().ifEmpty { "我喜欢" }
                if (id.isNotEmpty() && id != "0" && seen.add(id)) {
                    out += ServerItem(
                        ratingKey = "playlist|$id",
                        type = "playlist",
                        title = title,
                        thumb = mm.optString("laypic").trim().takeIf { it.startsWith("http") },
                        leafCount = mm.optString("num0").toIntOrNull() ?: 0,
                        childCount = mm.optString("num0").toIntOrNull() ?: 0,
                    )
                }
            }
            home.optJSONObject("mydiss")?.optJSONArray("list").objects().forEach { d ->
                val id = d.optString("dissid").trim()
                val title = d.optString("title").trim()
                if (id.isNotEmpty() && title.isNotEmpty() && seen.add(id)) {
                    val count = d.optString("subtitle").filter(Char::isDigit).toIntOrNull() ?: 0
                    out += ServerItem(
                        ratingKey = "playlist|$id",
                        type = "playlist",
                        title = title,
                        thumb = d.optString("picurl").trim().takeIf { it.startsWith("http") },
                        leafCount = count,
                        childCount = count,
                    )
                }
            }
        }
        val (uin, _) = extractQqUinAndKey(cookies)
        if (uin != "0") {
            val favUrl = "https://c.y.qq.com/fav/fcgi-bin/fcg_get_profile_order_asset.fcg".toHttpUrl().newBuilder()
                .addQueryParameter("format", "json")
                .addQueryParameter("cid", "205360956")
                .addQueryParameter("userid", uin)
                .addQueryParameter("reqtype", "3")
                .addQueryParameter("sin", "0")
                .addQueryParameter("ein", "50")
                .build()
            val favText = httpText(
                Request.Builder()
                    .url(favUrl)
                    .header("User-Agent", BROWSER_UA)
                    .header("Referer", "https://y.qq.com/")
                    .header("Cookie", cookies)
                    .build(),
            )
            favText?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optJSONObject("data")?.optJSONArray("cdlist").objects().forEach { cd ->
                    val id = cd.optString("dissid").trim()
                    val title = cd.optString("dissname").trim()
                    if (id.isNotEmpty() && title.isNotEmpty() && seen.add(id)) {
                        val cnt = cd.optInt("songnum", 0).coerceAtLeast(0)
                        out += ServerItem(
                            ratingKey = "playlist|$id",
                            type = "playlist",
                            title = title,
                            thumb = cd.optString("logo").trim().takeIf { it.startsWith("http") },
                            leafCount = cnt,
                            childCount = cnt,
                        )
                    }
                }
        }
        return out
    }

    private fun loadQqTracks(cookies: String): List<ServerItem> {
        val playlists = loadQqPlaylists(cookies)
        val favId = playlists.firstOrNull()?.ratingKey?.substringAfter('|')
        if (!favId.isNullOrBlank()) {
            val liked = fetchQqPlaylistTracks(cookies, favId)
            if (liked.isNotEmpty()) return liked
        }
        return MusicDiscoverRepository.fetchQqToplist(26, 40).map { discoverToServerItem(WebMusicService.QQ_MUSIC, it) }
    }

    private fun loadQqAlbums(cookies: String): List<ServerItem> {
        val (uin, _) = extractQqUinAndKey(cookies)
        if (uin != "0") {
            val favUrl = "https://c.y.qq.com/fav/fcgi-bin/fcg_get_profile_order_asset.fcg".toHttpUrl().newBuilder()
                .addQueryParameter("format", "json")
                .addQueryParameter("cid", "205360956")
                .addQueryParameter("userid", uin)
                .addQueryParameter("reqtype", "1")
                .addQueryParameter("sin", "0")
                .addQueryParameter("ein", "50")
                .build()
            val favText = httpText(
                Request.Builder()
                    .url(favUrl)
                    .header("User-Agent", BROWSER_UA)
                    .header("Referer", "https://y.qq.com/")
                    .header("Cookie", cookies)
                    .build(),
            )
            val favAlbums = favText?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optJSONObject("data")?.optJSONArray("albumlist").objects().mapNotNull { al ->
                    val mid = al.optString("albummid").trim()
                    val name = al.optString("albumname").trim()
                    if (mid.isEmpty() || name.isEmpty()) return@mapNotNull null
                    val singer = al.optString("singername").trim()
                    ServerItem(
                        ratingKey = "album|$mid",
                        type = "album",
                        title = name,
                        thumb = "https://y.gtimg.cn/music/photo_new/T002R500x500M000$mid.jpg",
                        parentTitle = singer.ifBlank { null },
                        grandparentTitle = singer.ifBlank { null },
                    )
                }
            if (!favAlbums.isNullOrEmpty()) return favAlbums
        }
        return emptyList()
    }

    private fun loadQqChildren(cookies: String, ratingKey: String): List<ServerItem> {
        val kind = ratingKey.substringBefore('|', "")
        val id = ratingKey.substringAfter('|', ratingKey).trim()
        if (id.isEmpty()) return emptyList()
        return if (kind == "album") {
            val payload = JSONObject()
                .put(
                    "req_0",
                    JSONObject()
                        .put("module", "music.musichallAlbum.AlbumSongList")
                        .put("method", "GetAlbumSongList")
                        .put(
                            "param",
                            JSONObject()
                                .put("albumMid", id)
                                .put("begin", 0)
                                .put("num", 100)
                                .put("order", 2),
                        ),
                )
                .put("comm", qqComm(cookies))
            val root = qqMusicuRequest(payload, cookies) ?: return emptyList()
            val list = root.optJSONObject("req_0")?.optJSONObject("data")?.optJSONArray("songList").objects()
            list.mapNotNull { entry -> parseQqTrackObj(entry.optJSONObject("songInfo") ?: entry) }
        } else {
            fetchQqPlaylistTracks(cookies, id)
        }
    }

    private fun searchQq(cookies: String, query: String): List<ServerItem> {
        val payload = JSONObject()
            .put(
                "songs",
                JSONObject()
                    .put("module", "music.search.SearchCgiService")
                    .put("method", "DoSearchForQQMusicDesktop")
                    .put("param", JSONObject().put("num_per_page", 25).put("page_num", 1).put("query", query).put("search_type", 0)),
            )
            .put(
                "albums",
                JSONObject()
                    .put("module", "music.search.SearchCgiService")
                    .put("method", "DoSearchForQQMusicDesktop")
                    .put("param", JSONObject().put("num_per_page", 8).put("page_num", 1).put("query", query).put("search_type", 2)),
            )
            .put(
                "playlists",
                JSONObject()
                    .put("module", "music.search.SearchCgiService")
                    .put("method", "DoSearchForQQMusicDesktop")
                    .put("param", JSONObject().put("num_per_page", 8).put("page_num", 1).put("query", query).put("search_type", 3)),
            )
            .put("comm", qqComm(cookies))
        val root = qqMusicuRequest(payload, cookies) ?: return emptyList()
        val out = ArrayList<ServerItem>()

        root.optJSONObject("songs")?.optJSONObject("data")?.optJSONObject("body")
            ?.optJSONObject("song")?.optJSONArray("list").objects()
            .mapNotNull(::parseQqTrackObj).let(out::addAll)

        root.optJSONObject("albums")?.optJSONObject("data")?.optJSONObject("body")
            ?.optJSONObject("album")?.optJSONArray("list").objects()
            .mapNotNull { al ->
                val mid = al.optString("albumMID").ifBlank { al.optString("albummid") }.trim()
                val name = al.optString("albumName").ifBlank { al.optString("albumname") }.trim()
                if (mid.isEmpty() || name.isEmpty()) return@mapNotNull null
                val singer = al.optString("singerName").ifBlank { al.optString("singername") }.trim()
                ServerItem(
                    ratingKey = "album|$mid",
                    type = "album",
                    title = name,
                    thumb = "https://y.gtimg.cn/music/photo_new/T002R500x500M000$mid.jpg",
                    parentTitle = singer.ifBlank { null },
                    grandparentTitle = singer.ifBlank { null },
                )
            }.let(out::addAll)

        root.optJSONObject("playlists")?.optJSONObject("data")?.optJSONObject("body")
            ?.optJSONObject("songlist")?.optJSONArray("list").objects()
            .mapNotNull { pl ->
                val id = pl.optString("dissid").trim()
                val name = pl.optString("dissname").trim()
                if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
                val cnt = pl.optInt("song_count", 0).coerceAtLeast(0)
                ServerItem(
                    ratingKey = "playlist|$id",
                    type = "playlist",
                    title = name,
                    thumb = pl.optString("imgurl").trim().takeIf { it.startsWith("http") },
                    leafCount = cnt,
                    childCount = cnt,
                )
            }.let(out::addAll)
        return out
    }

    private fun resolveQqStream(
        cookies: String,
        trackId: String,
        title: String,
        artist: String,
    ): ResolvedAudioStream? {
        var songMid = trackId.trim()
        if (songMid.isEmpty() && title.isNotBlank()) {
            val q = listOfNotNull(title, artist.takeIf { it.isNotBlank() }).joinToString(" ")
            val hit = MusicDiscoverRepository.fetchQqSearch(q, 5).firstOrNull()
            songMid = hit?.id?.removePrefix("qq:").orEmpty()
        }
        if (songMid.isEmpty()) return null

        val (uin, _) = extractQqUinAndKey(cookies)
        // 第 1 次探针：获取准确的 mediaMid（及普通音质默认 purl）
        val probePayload = JSONObject()
            .put(
                "req_0",
                JSONObject()
                    .put("module", "vkey.GetVkeyServer")
                    .put("method", "CgiGetVkey")
                    .put(
                        "param",
                        JSONObject()
                            .put("guid", "10000")
                            .put("songmid", JSONArray().put(songMid))
                            .put("songtype", JSONArray().put(0))
                            .put("uin", uin)
                            .put("loginflag", 1)
                            .put("platform", "20"),
                    ),
            )
            .put("comm", qqComm(cookies))
        val probeRoot = qqMusicuRequest(probePayload, cookies) ?: return null
        val probeData = probeRoot.optJSONObject("req_0")?.optJSONObject("data")
        val mediaMid = probeData?.optJSONArray("midurlinfo")?.optJSONObject(0)
            ?.optString("mid")?.trim()?.takeIf { it.isNotEmpty() } ?: songMid

        // 第 2 次批量按音质从高到低请求：FLAC 无损 > 320k MP3 > 128k MP3 > 96k M4A
        val filenames = JSONArray()
            .put("F000$mediaMid.flac")
            .put("M800$mediaMid.mp3")
            .put("M500$mediaMid.mp3")
            .put("C400$mediaMid.m4a")
        val mids = JSONArray().put(songMid).put(songMid).put(songMid).put(songMid)
        val types = JSONArray().put(0).put(0).put(0).put(0)
        val hqPayload = JSONObject()
            .put(
                "req_0",
                JSONObject()
                    .put("module", "vkey.GetVkeyServer")
                    .put("method", "CgiGetVkey")
                    .put(
                        "param",
                        JSONObject()
                            .put("guid", "10000")
                            .put("filename", filenames)
                            .put("songmid", mids)
                            .put("songtype", types)
                            .put("uin", uin)
                            .put("loginflag", 1)
                            .put("platform", "20"),
                    ),
            )
            .put("comm", qqComm(cookies))
        val hqRoot = qqMusicuRequest(hqPayload, cookies)
        return hqRoot?.let(::parseQqVkeyResponse) ?: parseQqVkeyResponse(probeRoot)
    }

    internal fun parseQqVkeyResponse(root: JSONObject): ResolvedAudioStream? {
        val data = root.optJSONObject("req_0")?.optJSONObject("data") ?: return null
        val sip = data.optJSONArray("sip")
            ?.let { arr -> (0 until arr.length()).firstNotNullOfOrNull { arr.optString(it).trim().takeIf { u -> u.startsWith("http") } } }
            ?: "https://ws.stream.qqmusic.qq.com/"
        val infos = data.optJSONArray("midurlinfo").objects()
        for (info in infos) {
            val purl = info.optString("purl").trim()
            if (purl.isEmpty()) continue
            val fn = info.optString("filename").trim()
            val label = when {
                fn.startsWith("F000", ignoreCase = true) || fn.endsWith(".flac", ignoreCase = true) -> "无损 FLAC"
                fn.startsWith("M800", ignoreCase = true) -> "HQ 320k"
                fn.startsWith("M500", ignoreCase = true) -> "标准 128k"
                else -> "QQ音乐直连"
            }
            val fullUrl = if (purl.startsWith("http")) purl else (sip.trimEnd('/') + "/" + purl.trimStart('/'))
            return ResolvedAudioStream(
                url = fullUrl,
                headers = mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Referer" to "https://y.qq.com/",
                ),
                qualityLabel = label,
            )
        }
        return null
    }
}
