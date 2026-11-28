package com.voxmusic.app.music

import android.webkit.CookieManager
import com.voxmusic.app.media.CatalogItem
import com.voxmusic.app.net.AppProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * YouTube Music 原生直连曲库、搜索与高音质音频流解析客户端（InnerTube WEB_REMIX + ANDROID_MUSIC / IOS_MUSIC）：
 * - 复用内置登录弹窗落盘的 Google Cookie（自动走全局代理 [AppProxy]），无需隐形 WebView 即可秒级拉取个人歌单、点赞歌曲、专辑与全目录搜索；
 * - 播放时直接向官方接口请求最高可用音质直链（最高 256k Opus / 256k AAC）交给原生 Media3 ExoPlayer 秒播；
 *   若受限无直链，由 [WebStreamingMusic] 自动回退内置网页播放。
 */
object YouTubeMusicApi {

    data class ResolvedAudioStream(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val qualityLabel: String = "",
        val durationMs: Long = 0L,
    )

    private const val BROWSER_UA = WebStreamingMusic.DESKTOP_CHROME_UA
    private const val YT_ANDROID_VR_UA = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    private const val YT_ANDROID_MUSIC_UA = "com.google.android.apps.youtube.music/8.05.51 (Linux; U; Android 14) gzip"
    private const val YT_IOS_MUSIC_UA = "com.google.ios.youtubemusic/8.05 (iPhone16,2; U; CPU iOS 18_3 like Mac OS X)"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // 若当前网络/会话下 InnerTube 直连音频流被全部拒绝，短时熔断 5 分钟，避免每切一首歌都白等直连探测才回退 WebView SPA
    private const val DIRECT_CIRCUIT_COOLDOWN_MS = 5 * 60_000L
    @Volatile
    private var directStreamBlockedUntilMs: Long = 0L

    fun resetDirectStreamCircuit() {
        directStreamBlockedUntilMs = 0L
    }

    internal fun shouldSkipDirectStreamProbe(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs < directStreamBlockedUntilMs

    internal fun recordDirectStreamProbeOutcome(succeeded: Boolean, nowMs: Long = System.currentTimeMillis()) {
        directStreamBlockedUntilMs = if (succeeded) 0L else nowMs + DIRECT_CIRCUIT_COOLDOWN_MS
    }

    private val http by lazy {
        AppProxy.builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(16, TimeUnit.SECONDS)
            .build()
    }

    internal fun readCookies(): String {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return ""
        val urls = listOf("https://music.youtube.com", "https://youtube.com", "https://www.youtube.com")
        val merged = LinkedHashMap<String, String>()
        for (u in urls) {
            val raw = runCatching { cm.getCookie(u).orEmpty() }.getOrDefault("")
            if (raw.isBlank()) continue
            for (part in raw.split(';')) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val k = part.substring(0, idx).trim()
                val v = part.substring(idx + 1).trim()
                val prev = merged[k]
                val prevTrivial = prev == null || prev == "0" || prev == "o0" || prev == "o0000000000"
                if (k.isNotEmpty() && v.isNotEmpty() && prevTrivial) {
                    merged[k] = v
                }
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    internal fun buildSapisidHash(
        cookies: String,
        origin: String = "https://music.youtube.com",
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): String? {
        val sapisid = WebStreamingMusic.extractCookieValue(cookies, "SAPISID")
            ?: WebStreamingMusic.extractCookieValue(cookies, "__Secure-3PAPISID")
            ?: WebStreamingMusic.extractCookieValue(cookies, "__Secure-1PAPISID")
            ?: return null
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$nowSec $sapisid $origin".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${nowSec}_$digest"
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

    private fun toTrackCatalogItem(
        trackId: String,
        title: String,
        artist: String = "",
        album: String = "",
        durationMs: Long = 0L,
        coverUrl: String? = null,
        webUrl: String? = null,
        year: Int? = null,
    ): CatalogItem {
        val entry = WebStreamingMusic.buildTrackEntry(
            service = WebMusicService.YOUTUBE_MUSIC,
            trackId = trackId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            coverUrl = coverUrl,
            webUrl = webUrl,
        )
        return CatalogItem(
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

    suspend fun loadTracks(): List<CatalogItem> = withContext(Dispatchers.IO) {
        loadYouTubeTracks(readCookies())
    }

    suspend fun loadAlbums(): List<CatalogItem> = withContext(Dispatchers.IO) {
        loadYouTubeAlbums(readCookies())
    }

    suspend fun loadPlaylists(): List<CatalogItem> = withContext(Dispatchers.IO) {
        loadYouTubePlaylists(readCookies())
    }

    suspend fun loadContainerChildren(container: CatalogItem): List<CatalogItem> = withContext(Dispatchers.IO) {
        loadYouTubeChildren(readCookies(), container.ratingKey)
    }

    suspend fun search(query: String): List<CatalogItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        searchYouTube(readCookies(), q)
    }

    suspend fun resolveAudioStream(
        trackId: String,
        title: String,
        artist: String,
    ): ResolvedAudioStream? = withContext(Dispatchers.IO) {
        if (shouldSkipDirectStreamProbe()) return@withContext null
        val resolved = resolveYouTubeStreamParallel(readCookies(), trackId, title, artist)
        recordDirectStreamProbeOutcome(resolved != null)
        resolved
    }

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

    private fun loadYouTubeTracks(cookies: String): List<CatalogItem> {
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

    private fun loadYouTubeAlbums(cookies: String): List<CatalogItem> {
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

    private fun loadYouTubePlaylists(cookies: String): List<CatalogItem> {
        val raw = ytInnerTubeRequest(
            "browse",
            JSONObject().put("context", ytWebRemixContext()).put("browseId", "FEmusic_liked_playlists"),
            cookies,
        )?.let { parseYouTubeItems(it, wantType = "playlist") }.orEmpty().ifEmpty {
            ytInnerTubeRequest(
                "browse",
                JSONObject().put("context", ytWebRemixContext()).put("browseId", "FEmusic_explore"),
                cookies,
            )?.let { parseYouTubeItems(it, wantType = "playlist") }.orEmpty()
        }
        val likedIdx = raw.indexOfFirst { p ->
            p.ratingKey.contains("FEmusic_liked_videos", ignoreCase = true) ||
                p.ratingKey.endsWith("|VLLM", ignoreCase = true) ||
                p.ratingKey.endsWith("|LM", ignoreCase = true) ||
                p.title.contains("赞过") ||
                p.title.contains("Liked", ignoreCase = true)
        }
        return if (likedIdx >= 0) {
            listOf(raw[likedIdx]) + raw.filterIndexed { i, _ -> i != likedIdx }
        } else {
            val likedEntry = CatalogItem(
                ratingKey = "playlist|FEmusic_liked_videos",
                type = "playlist",
                title = "已点赞的歌曲",
                summary = "YouTube Music 点赞收藏",
            )
            listOf(likedEntry) + raw
        }
    }

    private fun loadYouTubeChildren(cookies: String, ratingKey: String): List<CatalogItem> {
        val rawId = ratingKey.substringAfter('|', ratingKey).trim()
        if (rawId.isEmpty()) return emptyList()
        if (rawId == "liked") return loadYouTubeTracks(cookies)
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

    private fun searchYouTube(cookies: String, query: String): List<CatalogItem> {
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

    internal fun parseYouTubeItems(root: JSONObject, wantType: String? = null): List<CatalogItem> {
        val out = ArrayList<CatalogItem>()
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
                    out += toTrackCatalogItem(
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
                    out += CatalogItem(
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
                    out += CatalogItem(
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
                    out += toTrackCatalogItem(
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
                    out += CatalogItem(
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
                    out += CatalogItem(
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

    private suspend fun resolveYouTubeStreamParallel(
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
                "ANDROID_VR",
                YT_ANDROID_VR_UA,
                JSONObject()
                    .put("clientName", "ANDROID_VR")
                    .put("clientVersion", "1.61.48")
                    .put("deviceMake", "Oculus")
                    .put("deviceModel", "Quest 3")
                    .put("androidSdkVersion", 32)
                    .put("osName", "Android")
                    .put("osVersion", "12L")
                    .put("hl", "zh-CN")
                    .put("gl", "US"),
            ),
            Triple(
                "ANDROID_MUSIC",
                YT_ANDROID_MUSIC_UA,
                JSONObject()
                    .put("clientName", "ANDROID_MUSIC")
                    .put("clientVersion", "8.05.51")
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
                    .put("clientVersion", "8.05")
                    .put("deviceModel", "iPhone16,2")
                    .put("osName", "iPhone")
                    .put("osVersion", "18.3.0.22D63")
                    .put("hl", "zh-CN")
                    .put("gl", "US"),
            ),
        )

        // 并行竞速探测多个 InnerTube 客户端：首个拿到有效直链即刻返回并取消其余探测，总超时收紧至 2.6s
        return withTimeoutOrNull(2_600L) {
            coroutineScope {
                val winner = CompletableDeferred<ResolvedAudioStream?>()
                val remaining = AtomicInteger(clientConfigs.size)
                val jobs = clientConfigs.map { (clientName, ua, clientJson) ->
                    launch(Dispatchers.IO) {
                        val found = runCatching {
                            val payload = JSONObject()
                                .put("context", JSONObject().put("client", clientJson))
                                .put("videoId", videoId)
                                .put("contentCheckOk", true)
                                .put("racyCheckOk", true)
                            val host = if (clientName == "ANDROID_VR") "https://www.youtube.com" else "https://music.youtube.com"
                            val reqBuilder = Request.Builder()
                                .url("$host/youtubei/v1/player?prettyPrint=false")
                                .header("User-Agent", ua)
                                .header("Origin", host)
                                .header("Referer", "$host/")
                                .header("X-Origin", host)
                                .header("X-Goog-AuthUser", "0")
                                .post(payload.toString().toRequestBody(JSON_MEDIA))
                            if (cookies.isNotBlank() && clientName != "ANDROID_VR") {
                                reqBuilder.header("Cookie", cookies)
                                buildSapisidHash(cookies)?.let { reqBuilder.header("Authorization", it) }
                            }
                            val text = httpText(reqBuilder.build()) ?: return@runCatching null
                            val root = runCatching { JSONObject(text) }.getOrNull() ?: return@runCatching null
                            parseBestYouTubeAudioFormat(root, ua)
                        }.getOrNull()
                        if (found != null) {
                            winner.complete(found)
                        } else if (remaining.decrementAndGet() == 0) {
                            winner.complete(null)
                        }
                    }
                }
                try {
                    winner.await()
                } finally {
                    jobs.forEach { it.cancel() }
                }
            }
        }
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
}
