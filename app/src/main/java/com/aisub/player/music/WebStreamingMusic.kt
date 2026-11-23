package com.aisub.player.music

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aisub.player.media.MediaEntry
import com.aisub.player.meta.DiscoverItem
import com.aisub.player.meta.MusicDiscoverRepository
import com.aisub.player.remote.cloud.ServerItem
import com.aisub.player.remote.cloud.ServerProgress
import com.aisub.player.ui.AppIcons
import com.aisub.player.ui.Orange
import com.aisub.player.ui.WebMusicServiceIcon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 安卓端 Apple Music / Spotify 应用内登录与多引擎播放调度器：
 * - Spotify 优先采用方案 3（`Client ID` + OAuth PKCE + `SpotifyAppRemote` 本机官方客户端高音质/无损联动发声）；
 *   当未配置 `Client ID`、未安装官方客户端、未登录或连接异常时，自动无缝回退至内置隐形 Widevine DRM 网页播放引擎；
 * - Apple Music 采用 `media-user-token` 直连 `amp-api.music.apple.com` + 内置 Widevine DRM 播放引擎；
 * - 实时播放进度、封面、切歌与个人曲库统一桥接至应用原生「音乐」播放界面（MusicView）。
 */
object WebStreamingMusic {
    const val DESKTOP_CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val AUTH_PREFS = "web_music_auth"
    private val mainHandler = Handler(Looper.getMainLooper())

    data class PlaybackSnapshot(
        val service: WebMusicService,
        val playSeq: Long = 0L,
        val switching: Boolean = false,
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val coverUrl: String? = null,
        val ended: Boolean = false,
        val error: String? = null,
    )

    private val _loggedIn = MutableStateFlow<Set<WebMusicService>>(emptySet())
    val loggedInServices: StateFlow<Set<WebMusicService>> = _loggedIn.asStateFlow()

    private val engines = ConcurrentHashMap<WebMusicService, ServiceWebEngine>()
    private val pendingRpc = ConcurrentHashMap<String, CompletableDeferred<String>>()

    @Volatile
    internal var activePlaySeq: Long = 0L

    @Volatile
    private var snapshotListener: ((PlaybackSnapshot) -> Unit)? = null

    val isSupportedOnPlatform: Boolean = true

    fun setSnapshotListener(listener: ((PlaybackSnapshot) -> Unit)?) {
        snapshotListener = listener
    }

    fun isWebSpotifyLoggedIn(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        val cm = runCatching { CookieManager.getInstance() }.getOrNull()
        val spotifyOpenCookies = runCatching { cm?.getCookie("https://open.spotify.com").orEmpty() }.getOrDefault("")
        val spotifyAcctCookies = runCatching { cm?.getCookie("https://accounts.spotify.com").orEmpty() }.getOrDefault("")
        val allSpotifyCookies = "$spotifyOpenCookies; $spotifyAcctCookies"
        return allSpotifyCookies.contains("sp_dc=") ||
            (prefs.getBoolean("logged_SPOTIFY", false) && spotifyOpenCookies.isNotBlank())
    }

    fun refreshAuthState(context: Context): Set<WebMusicService> {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        val cm = runCatching { CookieManager.getInstance() }.getOrNull()
        val active = HashSet<WebMusicService>()

        val webSpotify = isWebSpotifyLoggedIn(app)
        val remoteSpotify = SpotifyAppRemoteBridge.isReadyAsLoggedIn(app)
        if (webSpotify) {
            prefs.edit().putBoolean("logged_SPOTIFY", true).apply()
        } else {
            prefs.edit().putBoolean("logged_SPOTIFY", false).apply()
        }
        if (webSpotify || remoteSpotify) {
            active += WebMusicService.SPOTIFY
        }

        val appleCookies = runCatching { cm?.getCookie("https://music.apple.com").orEmpty() }.getOrDefault("")
        if (appleCookies.contains("media-user-token=") ||
            appleCookies.contains("music-user-token=") ||
            prefs.getBoolean("logged_APPLE_MUSIC", false)
        ) {
            active += WebMusicService.APPLE_MUSIC
        }

        _loggedIn.value = active
        return active
    }

    fun isLoggedIn(context: Context, service: WebMusicService): Boolean =
        service in refreshAuthState(context)

    fun serviceStatusSubtitle(context: Context, service: WebMusicService, logged: Boolean): String {
        if (service != WebMusicService.SPOTIFY) {
            return if (logged) "已登录 · 应用内直接播放与同步歌词" else "点击登录账号 · 应用内直接播放"
        }
        val st = SpotifyAppRemoteBridge.refreshStatus(context)
        val webLogged = isWebSpotifyLoggedIn(context)
        return when {
            st.canAttemptRemotePlayback && webLogged -> "已登录 · 官方客户端联动（异常时自动网页兜底）"
            st.canAttemptRemotePlayback -> "已启用官方客户端联动 · 本机 Spotify 高音质发声"
            st.hasOAuthToken && !st.spotifyAppInstalled && webLogged -> "已登录 · 未装官方App，已自动回退网页播放"
            logged -> "已登录 · 网页播放（可配置 Client ID 启用高音质）"
            else -> "支持官方客户端联动 (高音质) 与网页直连"
        }
    }

    fun hasHighQualityConfigOption(service: WebMusicService): Boolean =
        service == WebMusicService.SPOTIFY

    fun highQualityConfigBadgeText(context: Context, service: WebMusicService): String {
        if (service != WebMusicService.SPOTIFY) return ""
        val st = SpotifyAppRemoteBridge.refreshStatus(context)
        return when {
            st.canAttemptRemotePlayback -> "官方客户端联动已启用 · 点击管理 Client ID / 兜底登录"
            st.isConfigured && !st.spotifyAppInstalled -> "已保存 Client ID · 未装官方App，当前自动使用网页播放"
            else -> "配置 Client ID 启用官方客户端联动（极高 / 无损音质）"
        }
    }

    fun markLoggedIn(context: Context, service: WebMusicService, loggedIn: Boolean) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        if (service == WebMusicService.SPOTIFY) {
            // 仅在确实持有网页 Cookie 时标记网页登录态，纯官方客户端联动登录由 SpotifyAppRemoteBridge 独立维护
            if (!loggedIn || isWebSpotifyLoggedIn(app)) {
                prefs.edit().putBoolean("logged_SPOTIFY", loggedIn).apply()
            }
        } else {
            prefs.edit().putBoolean("logged_${service.name}", loggedIn).apply()
        }
        runCatching { CookieManager.getInstance().flush() }
        if (loggedIn) {
            // 重建后台引擎以确保立即携带最新登录 Cookie 初始化令牌与曲库
            mainHandler.post { engines.remove(service)?.destroy() }
        }
        refreshAuthState(app)
    }

    fun logout(context: Context, service: WebMusicService) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("logged_${service.name}", false).apply()
        if (service == WebMusicService.SPOTIFY) {
            SpotifyAppRemoteBridge.logoutAll(app)
        }
        mainHandler.post {
            engines.remove(service)?.destroy()
            val cm = runCatching { CookieManager.getInstance() }.getOrNull()
            if (cm != null) {
                val domains = when (service) {
                    WebMusicService.APPLE_MUSIC -> listOf(
                        "https://music.apple.com",
                        "https://authorize.music.apple.com",
                        "https://idmsa.apple.com",
                        "https://appleid.apple.com",
                    )
                    WebMusicService.SPOTIFY -> listOf(
                        "https://open.spotify.com",
                        "https://accounts.spotify.com",
                        "https://spotify.com",
                    )
                }
                for (d in domains) {
                    val raw = cm.getCookie(d).orEmpty()
                    raw.split(';').forEach { pair ->
                        val name = pair.substringBefore('=').trim()
                        if (name.isNotEmpty()) {
                            cm.setCookie(d, "$name=; Max-Age=0; Path=/")
                            val host = Uri.parse(d).host.orEmpty()
                            val baseDomain = host.substringAfter('.', host)
                            cm.setCookie(d, "$name=; Max-Age=0; Domain=.$baseDomain; Path=/")
                        }
                    }
                }
                cm.flush()
            }
            refreshAuthState(app)
        }
    }

    fun isStreamingUri(uri: String?): Boolean = WebMusicService.fromUri(uri) != null

    /** 构建供 MainViewModel.play 直接播放的流媒体 MediaEntry，并注册歌名/歌手/专辑/封面至 ServerProgress。 */
    fun buildTrackEntry(
        service: WebMusicService,
        trackId: String?,
        title: String,
        artist: String = "",
        album: String = "",
        durationMs: Long = 0L,
        coverUrl: String? = null,
        webUrl: String? = null,
    ): MediaEntry {
        val cleanId = trackId?.trim().orEmpty()
        val uri = buildString {
            append(service.uriScheme).append("://")
            if (cleanId.isNotEmpty()) {
                append("track/").append(encodeParam(cleanId))
            } else {
                append("search")
            }
            val params = ArrayList<String>()
            if (title.isNotBlank()) params += "title=${encodeParam(title)}"
            if (artist.isNotBlank()) params += "artist=${encodeParam(artist)}"
            if (album.isNotBlank()) params += "album=${encodeParam(album)}"
            if (!webUrl.isNullOrBlank()) params += "url=${encodeParam(webUrl)}"
            if (params.isNotEmpty()) {
                append('?').append(params.joinToString("&"))
            }
        }
        val subtitle = listOfNotNull(artist.takeIf { it.isNotBlank() }, album.takeIf { it.isNotBlank() })
            .joinToString(" - ")
            .ifBlank { service.label }
        ServerProgress.register(
            uri,
            ServerProgress.Entry(
                serverId = "web:${service.name}",
                ratingKey = cleanId.ifEmpty { "$title|$artist" },
                durationMs = durationMs,
                viewOffsetMs = 0L,
                title = title.ifBlank { service.label },
                artist = artist.takeIf { it.isNotBlank() },
                album = album.takeIf { it.isNotBlank() },
                coverUrl = coverUrl,
            ),
        )
        return MediaEntry(
            uri = uri,
            title = "${title.ifBlank { "未知歌曲" }.replace('/', '_')}.mp3",
            folder = subtitle,
            durationMs = durationMs,
            isVideo = false,
        )
    }

    /** 将发现页歌曲转换为指定流媒体服务的应用内直播 MediaEntry。 */
    fun buildEntryFromDiscoverItem(service: WebMusicService, item: DiscoverItem): MediaEntry {
        val directId: String? = when (service) {
            WebMusicService.APPLE_MUSIC -> extractAppleSongId(item)
            WebMusicService.SPOTIFY -> extractSpotifyTrackId(item)
        }
        val directUrl: String? = when (service) {
            WebMusicService.APPLE_MUSIC -> item.appleUrl
            WebMusicService.SPOTIFY -> item.platforms.firstOrNull { it.name.equals("Spotify", ignoreCase = true) }?.url
        }
        return buildTrackEntry(
            service = service,
            trackId = directId,
            title = item.title,
            artist = item.artist,
            album = item.album,
            durationMs = item.durationMs,
            coverUrl = item.posterUrl ?: item.backdropUrl,
            webUrl = directUrl,
        )
    }

    internal fun extractAppleSongId(item: DiscoverItem): String? {
        if (item.id.startsWith("apple:") || item.id.startsWith("itunes:")) {
            val raw = item.id.substringAfter(':').trim()
            if (raw.all(Char::isDigit) && raw.isNotEmpty()) return raw
        }
        val url = item.appleUrl?.trim().orEmpty()
        if (url.isNotEmpty()) {
            val parsed = runCatching { Uri.parse(url) }.getOrNull()
            val iParam = parsed?.getQueryParameter("i")?.trim()
            if (!iParam.isNullOrEmpty() && iParam.all(Char::isDigit)) return iParam
            val segs = parsed?.pathSegments.orEmpty()
            if (segs.contains("song")) {
                val last = segs.lastOrNull()?.trim().orEmpty()
                if (last.all(Char::isDigit) && last.isNotEmpty()) return last
            }
        }
        return null
    }

    internal fun extractSpotifyTrackId(item: DiscoverItem): String? {
        if (item.id.startsWith("spotify:")) {
            val raw = item.id.substringAfterLast(':').trim()
            if (raw.isNotEmpty()) return raw
        }
        val url = item.platforms.firstOrNull { it.name.equals("Spotify", ignoreCase = true) }?.url.orEmpty()
        if (url.contains("open.spotify.com/track/")) {
            return url.substringAfter("open.spotify.com/track/").substringBefore('?').substringBefore('/').trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun encodeParam(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun decodeParam(s: String?): String =
        if (s.isNullOrEmpty()) "" else runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    internal fun extractCookieValue(rawCookies: String, cookieName: String): String? {
        if (rawCookies.isBlank()) return null
        for (part in rawCookies.split(';')) {
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val k = trimmed.substring(0, eq).trim()
            if (k.equals(cookieName, ignoreCase = true)) {
                val rawVal = trimmed.substring(eq + 1).trim().trim('"')
                if (rawVal.isEmpty()) continue
                return if ('%' in rawVal) {
                    runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
                } else {
                    rawVal
                }
            }
        }
        return null
    }

    // ---------------- 个人曲库浏览与目录搜索（供「音乐 → 乐库」调用） ----------------

    suspend fun loadLibraryTracks(context: Context, service: WebMusicService): List<ServerItem> {
        if (service == WebMusicService.SPOTIFY) {
            val oauthTracks = runCatching { SpotifyAppRemoteBridge.loadTracksViaOAuth(context) }.getOrDefault(emptyList())
            if (oauthTracks.isNotEmpty()) return oauthTracks
            if (isWebSpotifyLoggedIn(context)) {
                val engine = ensureEngine(context, service)
                val raw = engine.callRpc("loadTracks", JSONObject(), timeoutMs = 22_000L)
                val parsed = parseServerItemsJson(service, raw)
                if (parsed.isNotEmpty()) return parsed
            }
            if (SpotifyAppRemoteBridge.refreshStatus(context).canAttemptRemotePlayback) {
                val recTracks = runCatching {
                    SpotifyAppRemoteBridge.loadRecommendedViaRemote(context).filter { it.type == "track" }
                }.getOrDefault(emptyList())
                if (recTracks.isNotEmpty()) return recTracks
            }
        } else {
            val engine = ensureEngine(context, service)
            val raw = engine.callRpc("loadTracks", JSONObject(), timeoutMs = 22_000L)
            val parsed = parseServerItemsJson(service, raw)
            if (parsed.isNotEmpty()) return parsed
        }
        // 新账号或暂未收藏单曲时，自动兜底加载精选热门单曲列表（点击直接通过当前流媒体服务播放）
        return withContext(Dispatchers.IO) {
            val primaryStore = if (service == WebMusicService.SPOTIFY) "tw" else "cn"
            MusicDiscoverRepository.fetchAppleMostPlayed(primaryStore, 40)
                .ifEmpty { MusicDiscoverRepository.fetchAppleMostPlayed("us", 40) }
                .map { discoverToServerItem(service, it) }
        }
    }

    suspend fun loadLibraryAlbums(context: Context, service: WebMusicService): List<ServerItem> {
        if (service == WebMusicService.SPOTIFY) {
            val oauthAlbums = runCatching { SpotifyAppRemoteBridge.loadAlbumsViaOAuth(context) }.getOrDefault(emptyList())
            if (oauthAlbums.isNotEmpty()) return oauthAlbums
            if (isWebSpotifyLoggedIn(context)) {
                val engine = ensureEngine(context, service)
                val raw = engine.callRpc("loadAlbums", JSONObject(), timeoutMs = 20_000L)
                val parsed = parseServerItemsJson(service, raw)
                if (parsed.isNotEmpty()) return parsed
            }
            if (SpotifyAppRemoteBridge.refreshStatus(context).canAttemptRemotePlayback) {
                val recAlbums = runCatching {
                    SpotifyAppRemoteBridge.loadRecommendedViaRemote(context).filter { it.type == "album" }
                }.getOrDefault(emptyList())
                if (recAlbums.isNotEmpty()) return recAlbums
            }
            return emptyList()
        }
        val engine = ensureEngine(context, service)
        val raw = engine.callRpc("loadAlbums", JSONObject(), timeoutMs = 20_000L)
        return parseServerItemsJson(service, raw)
    }

    suspend fun loadLibraryPlaylists(context: Context, service: WebMusicService): List<ServerItem> {
        if (service == WebMusicService.SPOTIFY) {
            val oauthPlaylists = runCatching { SpotifyAppRemoteBridge.loadPlaylistsViaOAuth(context) }.getOrDefault(emptyList())
            if (oauthPlaylists.isNotEmpty()) return oauthPlaylists
            if (isWebSpotifyLoggedIn(context)) {
                val engine = ensureEngine(context, service)
                val raw = engine.callRpc("loadPlaylists", JSONObject(), timeoutMs = 20_000L)
                val parsed = parseServerItemsJson(service, raw)
                if (parsed.isNotEmpty()) return parsed
            }
            if (SpotifyAppRemoteBridge.refreshStatus(context).canAttemptRemotePlayback) {
                val recPlaylists = runCatching {
                    SpotifyAppRemoteBridge.loadRecommendedViaRemote(context).filter { it.type == "playlist" }
                }.getOrDefault(emptyList())
                if (recPlaylists.isNotEmpty()) return recPlaylists
            }
            return emptyList()
        }
        val engine = ensureEngine(context, service)
        val raw = engine.callRpc("loadPlaylists", JSONObject(), timeoutMs = 20_000L)
        return parseServerItemsJson(service, raw)
    }

    suspend fun loadContainerChildren(context: Context, service: WebMusicService, container: ServerItem): List<ServerItem> {
        if (service == WebMusicService.SPOTIFY) {
            val oauthChildren = runCatching { SpotifyAppRemoteBridge.loadChildrenViaOAuth(context, container) }.getOrDefault(emptyList())
            if (oauthChildren.isNotEmpty()) return oauthChildren
        }
        val engine = ensureEngine(context, service)
        val args = JSONObject()
            .put("ratingKey", container.ratingKey)
            .put("type", container.type)
        val raw = engine.callRpc("loadChildren", args, timeoutMs = 20_000L)
        return parseServerItemsJson(service, raw)
    }

    suspend fun searchMusic(context: Context, service: WebMusicService, query: String): List<ServerItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        if (service == WebMusicService.SPOTIFY) {
            val oauthHits = runCatching { SpotifyAppRemoteBridge.searchViaOAuth(context, q) }.getOrDefault(emptyList())
            if (oauthHits.isNotEmpty()) return oauthHits
        }
        val engine = ensureEngine(context, service)
        val raw = engine.callRpc("search", JSONObject().put("query", q), timeoutMs = 16_000L)
        val parsed = parseServerItemsJson(service, raw)
        if (parsed.isNotEmpty()) return parsed
        return withContext(Dispatchers.IO) {
            MusicDiscoverRepository.fetchItunesSearch(q, 25).map { discoverToServerItem(service, it) }
        }
    }

    private fun discoverToServerItem(service: WebMusicService, item: DiscoverItem): ServerItem {
        val tid = if (service == WebMusicService.APPLE_MUSIC) extractAppleSongId(item) else extractSpotifyTrackId(item)
        val entry = buildEntryFromDiscoverItem(service, item)
        return ServerItem(
            ratingKey = tid ?: item.id,
            type = "track",
            title = item.title,
            year = item.year,
            thumb = item.posterUrl ?: item.backdropUrl,
            durationMs = item.durationMs,
            parentTitle = item.album.ifBlank { null },
            grandparentTitle = item.artist.ifBlank { null },
            fileName = entry.title,
            playUrl = entry.uri,
        )
    }

    private fun parseServerItemsJson(service: WebMusicService, raw: String?): List<ServerItem> {
        if (raw.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<ServerItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            val type = o.optString("type").trim().ifEmpty { "track" }
            val title = o.optString("title").trim()
            if (id.isEmpty() || title.isEmpty()) continue
            val artist = o.optString("artist").trim()
            val album = o.optString("album").trim()
            val summary = o.optString("summary").trim()
            val cover = o.optString("cover").trim().takeIf { it.startsWith("http") }
            val durationMs = o.optLong("durationMs", 0L).coerceAtLeast(0L)
            val leafCount = o.optInt("count", 0).coerceAtLeast(0)
            val year = o.optInt("year", 0).takeIf { it in 1900..2100 }
            val webUrl = o.optString("webUrl").trim().takeIf { it.startsWith("http") }

            if (type == "track") {
                val entry = buildTrackEntry(
                    service = service,
                    trackId = id,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    coverUrl = cover,
                    webUrl = webUrl,
                )
                out += ServerItem(
                    ratingKey = id,
                    type = "track",
                    title = title,
                    year = year,
                    summary = summary,
                    thumb = cover,
                    durationMs = durationMs,
                    parentTitle = album.ifBlank { null },
                    grandparentTitle = artist.ifBlank { null },
                    fileName = entry.title,
                    playUrl = entry.uri,
                )
            } else {
                out += ServerItem(
                    ratingKey = id,
                    type = type,
                    title = title,
                    year = year,
                    summary = summary,
                    thumb = cover,
                    durationMs = durationMs,
                    leafCount = leafCount,
                    childCount = leafCount,
                    parentTitle = artist.ifBlank { null },
                    grandparentTitle = artist.ifBlank { null },
                )
            }
        }
        return out
    }

    // ---------------- 播放控制入口（供 WebStreamingMusicPlayer 调用） ----------------

    suspend fun playUri(
        context: Context,
        uriString: String,
        startPositionMs: Long = 0L,
        playSeq: Long = 0L,
    ): Result<Unit> {
        val service = WebMusicService.fromUri(uriString)
            ?: return Result.failure(IllegalArgumentException("不支持的流媒体协议: $uriString"))
        if (!isLoggedIn(context, service)) {
            return Result.failure(IllegalStateException("请先登录 ${service.label} 账号后再播放"))
        }
        val effectiveSeq = if (playSeq > 0L) playSeq else (activePlaySeq + 1L)
        activePlaySeq = effectiveSeq

        val parsed = Uri.parse(uriString)
        var trackId = if (parsed.host == "track") parsed.pathSegments.firstOrNull()?.let(::decodeParam).orEmpty() else ""
        val title = parsed.getQueryParameter("title")?.let(::decodeParam).orEmpty()
        val artist = parsed.getQueryParameter("artist")?.let(::decodeParam).orEmpty()
        val webUrl = parsed.getQueryParameter("url")?.let(::decodeParam).orEmpty()

        // 在发起任何网络查询前立即通知后台网页引擎进入切歌冻结态并暂停旧音轨，彻底阻断旧进度上报
        engines[service]?.evalFireAndForget(
            "window.__voxBridge && window.__voxBridge.beginSwitch($effectiveSeq, ${JSONObject.quote(trackId)}, ${startPositionMs.coerceAtLeast(0L)}, ${JSONObject.quote(title)})",
        )

        if (service == WebMusicService.SPOTIFY && trackId.isEmpty() && webUrl.contains("open.spotify.com/track/")) {
            trackId = webUrl.substringAfter("open.spotify.com/track/").substringBefore('?').substringBefore('/').trim()
        }

        // Apple Music 若尚未携带数字单曲 ID，先通过 iTunes 免鉴权搜索快速补齐精确 trackId
        if (service == WebMusicService.APPLE_MUSIC && trackId.isEmpty() && title.isNotBlank()) {
            trackId = withContext(Dispatchers.IO) {
                val q = listOfNotNull(title, artist.takeIf { it.isNotBlank() }).joinToString(" ")
                val hits = runCatching { MusicDiscoverRepository.fetchItunesSearch(q, 5) }.getOrDefault(emptyList())
                val wantT = MusicDiscoverRepository.normMusicText(title)
                val matched = hits.firstOrNull { MusicDiscoverRepository.normMusicText(it.title) == wantT } ?: hits.firstOrNull()
                matched?.let(::extractAppleSongId).orEmpty()
            }
        }

        // 暂停另一流媒体音源的后台引擎
        WebMusicService.entries.filter { it != service }.forEach { other ->
            engines[other]?.pauseOnly()
        }
        if (service != WebMusicService.SPOTIFY) {
            SpotifyAppRemoteBridge.pauseIfActive()
        }

        // Spotify 优先通道（方案 3）：已配置 Client ID 且安装了官方 Spotify 客户端时，联动本机高音质/无损发声
        if (service == WebMusicService.SPOTIFY) {
            val remoteSt = SpotifyAppRemoteBridge.refreshStatus(context)
            if (remoteSt.canAttemptRemotePlayback) {
                if (trackId.isEmpty() && title.isNotBlank()) {
                    val q = listOfNotNull(title, artist.takeIf { it.isNotBlank() }).joinToString(" ")
                    trackId = runCatching {
                        SpotifyAppRemoteBridge.searchViaOAuth(context, q).firstOrNull { it.type == "track" }?.ratingKey.orEmpty()
                    }.getOrDefault("")
                    if (trackId.isEmpty()) {
                        trackId = runCatching {
                            val eng = ensureEngine(context, WebMusicService.SPOTIFY)
                            val rawSearch = eng.callRpc("search", JSONObject().put("query", q), timeoutMs = 10_000L)
                            parseServerItemsJson(WebMusicService.SPOTIFY, rawSearch)
                                .firstOrNull { it.type == "track" }?.ratingKey.orEmpty()
                        }.getOrDefault("")
                    }
                }
                engines[WebMusicService.SPOTIFY]?.pauseOnly()
                val remoteRes = SpotifyAppRemoteBridge.tryPlayTrack(
                    context = context,
                    trackId = trackId,
                    title = title,
                    artist = artist,
                    startPositionMs = startPositionMs,
                )
                if (remoteRes.isSuccess) {
                    return Result.success(Unit)
                }
                // 方案 3 连接或播放不可用时，自动无缝回退至下方现有网页 DRM 播放方案
            } else {
                SpotifyAppRemoteBridge.pauseIfActive()
            }

            // 回退前确认是否具备网页版 Spotify 会话 Cookie
            if (!isWebSpotifyLoggedIn(context)) {
                val fallbackWhy = SpotifyAppRemoteBridge.status.value.lastFallbackReason
                val msg = when {
                    remoteSt.isConfigured && !remoteSt.spotifyAppInstalled ->
                        "未检测到本机安装的官方 Spotify 客户端，且尚未登录网页兜底会话；请在 Spotify 登录页补充网页登录"
                    !fallbackWhy.isNullOrBlank() ->
                        "官方客户端联动暂不可用（$fallbackWhy），且尚未登录网页兜底会话；请检查本机 Spotify 或在登录页补充网页登录"
                    else -> "请先登录 Spotify 账号后再播放"
                }
                return Result.failure(IllegalStateException(msg))
            }
        }

        val engine = ensureEngine(context, service)
        val args = JSONObject()
            .put("playSeq", effectiveSeq)
            .put("trackId", trackId)
            .put("title", title)
            .put("artist", artist)
            .put("webUrl", webUrl)
            .put("startPositionMs", startPositionMs.coerceAtLeast(0L))
        val resp = engine.callRpc("playTrack", args, timeoutMs = 24_000L)
            ?: return Result.failure(IllegalStateException("${service.label} 播放器响应超时，请检查网络或重新登录"))
        val obj = runCatching { JSONObject(resp) }.getOrNull()
        val ok = obj?.optBoolean("ok", false) == true
        return if (ok) {
            Result.success(Unit)
        } else {
            val err = obj?.optString("error").orEmpty().ifBlank { "无法播放该曲目，请确认账号已开通 ${service.label} 会员" }
            Result.failure(IllegalStateException(err))
        }
    }

    fun setPlaying(service: WebMusicService, play: Boolean) {
        if (service == WebMusicService.SPOTIFY && SpotifyAppRemoteBridge.setPlaying(play)) return
        engines[service]?.evalFireAndForget("window.__voxBridge && window.__voxBridge.${if (play) "resume" else "pause"}()")
    }

    fun seekTo(service: WebMusicService, positionMs: Long) {
        if (service == WebMusicService.SPOTIFY && SpotifyAppRemoteBridge.seekTo(positionMs)) return
        engines[service]?.evalFireAndForget("window.__voxBridge && window.__voxBridge.seek(${positionMs.coerceAtLeast(0L)})")
    }

    fun setVolume(service: WebMusicService, volume: Float) {
        if (service == WebMusicService.SPOTIFY && SpotifyAppRemoteBridge.setVolume(volume)) return
        val v = volume.coerceIn(0f, 1f)
        engines[service]?.evalFireAndForget("window.__voxBridge && window.__voxBridge.setVolume($v)")
    }

    fun setSpeed(service: WebMusicService, speed: Float) {
        val s = speed.coerceIn(0.5f, 2f)
        engines[service]?.evalFireAndForget("window.__voxBridge && window.__voxBridge.setSpeed($s)")
    }

    fun stopAll() {
        activePlaySeq++
        SpotifyAppRemoteBridge.pauseIfActive()
        engines.values.forEach { it.pauseOnly() }
    }

    private suspend fun ensureEngine(context: Context, service: WebMusicService): ServiceWebEngine {
        engines[service]?.let { existing ->
            existing.awaitReady()
            return existing
        }
        val created = withContext(Dispatchers.Main.immediate) {
            engines.getOrPut(service) {
                ServiceWebEngine(context.applicationContext, service)
            }
        }
        created.awaitReady()
        return created
    }

    internal fun completeRpc(reqId: String, payload: String) {
        pendingRpc.remove(reqId)?.complete(payload)
    }

    internal fun dispatchSnapshotObj(snap: PlaybackSnapshot) {
        val wantSeq = activePlaySeq
        if (wantSeq > 0L && snap.playSeq != wantSeq) return
        snapshotListener?.invoke(snap)
    }

    internal fun dispatchSnapshot(service: WebMusicService, rawJson: String) {
        if (service == WebMusicService.SPOTIFY && SpotifyAppRemoteBridge.status.value.activeForCurrentTrack) {
            return
        }
        val o = runCatching { JSONObject(rawJson) }.getOrNull() ?: return
        val jsSeq = o.optLong("playSeq", 0L)
        val wantSeq = activePlaySeq
        // 丢弃尚未收到最新切歌代次的网页旧定时器快照
        if (wantSeq <= 0L || jsSeq != wantSeq) return
        val snap = PlaybackSnapshot(
            service = service,
            playSeq = jsSeq,
            switching = o.optBoolean("switching", false),
            playing = o.optBoolean("playing", false),
            buffering = o.optBoolean("buffering", false),
            positionMs = o.optLong("posMs", 0L).coerceAtLeast(0L),
            durationMs = o.optLong("durMs", 0L).coerceAtLeast(0L),
            title = o.optString("title").trim(),
            artist = o.optString("artist").trim(),
            album = o.optString("album").trim(),
            coverUrl = o.optString("coverUrl").trim().takeIf { it.startsWith("http") },
            ended = o.optBoolean("ended", false),
            error = o.optString("error").trim().takeIf { it.isNotEmpty() },
        )
        dispatchSnapshotObj(snap)
    }

    // ---------------- 隐形保活 DRM WebView 宿主 ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private class ServiceWebEngine(
        private val appContext: Context,
        val service: WebMusicService,
    ) {
        private val readySignal = CompletableDeferred<Unit>()

        @Volatile
        var keepAwakeForAudio: Boolean = false

        val webView: WebView = object : WebView(appContext) {
            // 锁屏或切到后台时保持 Chromium 音频与定时器不被挂起
            override fun onWindowVisibilityChanged(visibility: Int) {
                if (keepAwakeForAudio) {
                    super.onWindowVisibilityChanged(View.VISIBLE)
                } else {
                    super.onWindowVisibilityChanged(visibility)
                }
            }
        }.apply {
            configureDrmWebView(this, allowPopups = false)
            addJavascriptInterface(JsHostBridge(appContext, service), "VoxMusicHost")
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, DOCUMENT_START_SCRIPT, setOf("*"))
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view.evaluateJavascript(DOCUMENT_START_SCRIPT, null)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    view.evaluateJavascript(DOCUMENT_START_SCRIPT, null)
                    view.evaluateJavascript(ENGINE_RUNTIME_SCRIPT, null)
                    if (!readySignal.isCompleted) {
                        mainHandler.postDelayed({
                            if (!readySignal.isCompleted) readySignal.complete(Unit)
                        }, 900L)
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    val allowed = request.resources.filter {
                        it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                    }.toTypedArray()
                    if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()
                }
            }
            loadUrl(service.homeUrl)
        }

        suspend fun awaitReady() {
            withTimeoutOrNull(14_000L) { readySignal.await() }
            withContext(Dispatchers.Main.immediate) {
                webView.evaluateJavascript(ENGINE_RUNTIME_SCRIPT, null)
            }
        }

        suspend fun callRpc(method: String, args: JSONObject, timeoutMs: Long): String? {
            if (method == "playTrack") keepAwakeForAudio = true
            if (service == WebMusicService.APPLE_MUSIC) {
                val rawCookies = runCatching { CookieManager.getInstance().getCookie("https://music.apple.com").orEmpty() }.getOrDefault("")
                val cookieMut = extractCookieValue(rawCookies, "media-user-token")
                    ?: extractCookieValue(rawCookies, "music-user-token")
                if (!cookieMut.isNullOrBlank()) {
                    args.put("_cookieMut", cookieMut)
                }
            }
            val reqId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<String>()
            pendingRpc[reqId] = deferred
            val quotedReq = JSONObject.quote(reqId)
            val quotedMethod = JSONObject.quote(method)
            val argsJson = args.toString()
            withContext(Dispatchers.Main.immediate) {
                webView.onResume()
                webView.resumeTimers()
                webView.evaluateJavascript(
                    "window.__voxBridge && window.__voxBridge.invoke($quotedReq, $quotedMethod, $argsJson);",
                    null,
                )
            }
            val res = withTimeoutOrNull(timeoutMs) { deferred.await() }
            pendingRpc.remove(reqId)
            return res
        }

        fun evalFireAndForget(js: String) {
            mainHandler.post {
                if (js.contains(".pause()")) keepAwakeForAudio = false
                if (js.contains(".resume()")) keepAwakeForAudio = true
                webView.evaluateJavascript(js, null)
            }
        }

        fun pauseOnly() {
            keepAwakeForAudio = false
            evalFireAndForget("window.__voxBridge && window.__voxBridge.pause()")
        }

        fun destroy() {
            keepAwakeForAudio = false
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }
    }

    private class JsHostBridge(
        private val appContext: Context,
        private val service: WebMusicService,
    ) {
        @JavascriptInterface
        fun onRpcResult(reqId: String, jsonPayload: String) {
            completeRpc(reqId, jsonPayload)
        }

        @JavascriptInterface
        fun onSnapshot(jsonPayload: String) {
            dispatchSnapshot(service, jsonPayload)
        }

        @JavascriptInterface
        fun onAuthDetected(loggedIn: Boolean) {
            if (loggedIn) {
                markLoggedIn(appContext, service, true)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    internal fun configureDrmWebView(webView: WebView, allowPopups: Boolean) {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = allowPopups
            setSupportMultipleWindows(allowPopups)
            userAgentString = DESKTOP_CHROME_UA
        }
        runCatching {
            if (WebViewFeature.isFeatureSupported("REQUESTED_WITH_HEADER_ALLOW_LIST")) {
                val cls = Class.forName("androidx.webkit.WebSettingsCompat")
                val m = cls.getMethod("setRequestedWithHeaderOriginAllowList", WebSettings::class.java, Set::class.java)
                m.invoke(null, webView.settings, emptySet<String>())
            }
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
    }

    /**
     * 页面最早时机注入：
     * 1. 伪装桌面 Windows Chrome 的 `navigator.userAgentData` 与 `navigator.platform`，防止 Spotify/Apple Music 识别为手机端而禁用网页播放器；
     * 2. 拦截离屏创建的 `<audio>`/`<video>` EME 元素与 `navigator.mediaSession`，拿到毫秒级进度和播放控制权；
     * 3. 同时拦截 `fetch`（兼容 `URL` / `Request` 对象）与 `XMLHttpRequest`，捕获 Spotify 新版 TOTP `/api/token`、
     *    `client-token`、`spotify-app-version`、Device ID 以及 `pathfinder/v2/query` 持久化查询哈希与响应缓存。
     */
    internal const val DOCUMENT_START_SCRIPT = """
(function() {
  if (window.__voxDocHooked) return;
  window.__voxDocHooked = true;
  try {
    Object.defineProperty(navigator, 'platform', { get: function() { return 'Win32'; }, configurable: true });
    Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; }, configurable: true });
    Object.defineProperty(navigator, 'userAgentData', {
      get: function() {
        return {
          brands: [
            { brand: 'Google Chrome', version: '131' },
            { brand: 'Chromium', version: '131' },
            { brand: 'Not_A Brand', version: '24' }
          ],
          mobile: false,
          platform: 'Windows',
          getHighEntropyValues: function() {
            return Promise.resolve({
              architecture: 'x86',
              bitness: '64',
              brands: this.brands,
              mobile: false,
              model: '',
              platform: 'Windows',
              platformVersion: '15.0.0',
              uaFullVersion: '131.0.0.0'
            });
          }
        };
      },
      configurable: true
    });
  } catch (e) {}

  window.__voxMediaPool = window.__voxMediaPool || [];
  window.__voxActionHandlers = window.__voxActionHandlers || {};
  window.__voxSpotifyHashes = window.__voxSpotifyHashes || {};
  window.__voxPathfinderCache = window.__voxPathfinderCache || {};

  function trackMediaEl(el) {
    if (!el || el.__voxTracked) return;
    el.__voxTracked = true;
    window.__voxMediaPool.push(el);
    el.addEventListener('play', function() { window.__voxActiveMedia = el; });
    el.addEventListener('playing', function() { window.__voxActiveMedia = el; });
    el.addEventListener('timeupdate', function() {
      if (!el.paused || !window.__voxActiveMedia) window.__voxActiveMedia = el;
    });
  }

  try {
    var origCreate = document.createElement.bind(document);
    document.createElement = function(tag) {
      var el = origCreate.apply(document, arguments);
      var t = String(tag || '').toLowerCase();
      if (t === 'audio' || t === 'video') trackMediaEl(el);
      return el;
    };
    var origPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function() {
      trackMediaEl(this);
      window.__voxActiveMedia = this;
      return origPlay.apply(this, arguments);
    };
  } catch (e) {}

  try {
    if (navigator.mediaSession && navigator.mediaSession.setActionHandler) {
      var origSetHandler = navigator.mediaSession.setActionHandler.bind(navigator.mediaSession);
      navigator.mediaSession.setActionHandler = function(action, handler) {
        if (handler) window.__voxActionHandlers[action] = handler;
        return origSetHandler(action, handler);
      };
    }
  } catch (e) {}

  function inspectHeaderPair(k, v) {
    if (!k || !v) return;
    var kl = String(k).toLowerCase();
    var vs = String(v).trim();
    var onApple = location.hostname.indexOf('apple.com') >= 0;
    if (kl === 'authorization' && vs.indexOf('Bearer ') === 0 && vs.length > 40) {
      var tok = vs.substring(7).trim();
      if (onApple) window.__voxAppleDevToken = tok;
      else window.__voxSpotifyToken = tok;
    } else if ((kl === 'media-user-token' || kl === 'music-user-token') && vs.length > 20) {
      window.__voxAppleUserToken = vs;
    } else if (kl === 'x-apple-client-version' && vs.length > 2) {
      window.__voxAppleClientVer = vs;
    } else if (kl === 'client-token' && vs.length > 20) {
      window.__voxSpotifyClientToken = vs;
    } else if (kl === 'spotify-app-version' && vs.length > 2) {
      window.__voxSpotifyAppVer = vs;
    }
  }

  function scanHeadersObj(hdrs) {
    if (!hdrs) return;
    try {
      if (typeof hdrs.forEach === 'function') {
        hdrs.forEach(function(val, key) { inspectHeaderPair(key, val); });
      } else if (Array.isArray(hdrs)) {
        for (var i = 0; i < hdrs.length; i++) {
          if (hdrs[i] && hdrs[i].length >= 2) inspectHeaderPair(hdrs[i][0], hdrs[i][1]);
        }
      } else if (typeof hdrs === 'object') {
        var keys = Object.keys(hdrs);
        for (var j = 0; j < keys.length; j++) inspectHeaderPair(keys[j], hdrs[keys[j]]);
      }
    } catch (e) {}
  }

  function inspectSpotifyUrlAndBody(url, bodyStr) {
    if (!url) return;
    try {
      var spHost = url.match(/^(https:\/\/[a-z0-9-]*spclient\.spotify\.com)/i);
      if (spHost) window.__voxSpotifySpClientOrigin = spHost[1];
      if (/track-playback\/v1\/devices|connect-state\/v1\/devices\/|player\/command\/from\//i.test(url)) {
        var m = url.match(/hobs_[A-Za-z0-9_-]+/);
        if (m) window.__voxSpotifyDeviceId = m[0];
        else {
          var mDev = url.match(/connect-state\/v1\/devices\/([A-Za-z0-9_-]{12,})/);
          if (mDev) window.__voxSpotifyDeviceId = mDev[1];
        }
      }
      if (/\/pathfinder\/v/i.test(url) && typeof bodyStr === 'string' && bodyStr.charAt(0) === '{') {
        var jb = JSON.parse(bodyStr);
        var op = jb && jb.operationName;
        var hash = jb && jb.extensions && jb.extensions.persistedQuery && jb.extensions.persistedQuery.sha256Hash;
        if (op && hash) window.__voxSpotifyHashes[op] = hash;
      }
    } catch (e) {}
  }

  function inspectSpotifyResponseJson(rUrl, j) {
    if (!j || typeof j !== 'object') return;
    try {
      if (/\/api\/token|get_access_token/i.test(rUrl)) {
        if (j.accessToken) {
          window.__voxSpotifyToken = j.accessToken;
          window.__voxSpotifyAnon = !!j.isAnonymous;
          if (j.accessTokenExpirationTimestampMs) {
            window.__voxSpotifyTokenExp = Number(j.accessTokenExpirationTimestampMs) || 0;
          }
        }
      } else if (/clienttoken\.spotify\.com\/v1\/clienttoken/i.test(rUrl)) {
        var gt = j.granted_token && j.granted_token.token;
        if (gt) window.__voxSpotifyClientToken = gt;
      } else if (/track-playback\/v1\/devices/i.test(rUrl)) {
        if (j.device_id) window.__voxSpotifyDeviceId = j.device_id;
      } else if (/\/pathfinder\/v/i.test(rUrl) && j.data) {
        if (j.data.me && j.data.me.libraryV3) window.__voxPathfinderCache.libraryV3 = j;
        if (j.data.home) window.__voxPathfinderCache.home = j;
        if (j.data.me && j.data.me.library && j.data.me.library.tracks) window.__voxPathfinderCache.fetchLibraryTracks = j;
      }
    } catch (e) {}
  }

  try {
    var origFetch = window.fetch;
    if (origFetch) {
      window.fetch = function(input, init) {
        var url = '';
        try {
          if (typeof input === 'string') url = input;
          else if (input && typeof input.href === 'string') url = input.href;
          else if (input && typeof input.url === 'string') url = input.url;
          else if (input) url = String(input);
          if (input && input.headers) scanHeadersObj(input.headers);
          if (init && init.headers) scanHeadersObj(init.headers);
          inspectSpotifyUrlAndBody(url, init && init.body);
        } catch (e) {}
        return origFetch.apply(this, arguments).then(function(resp) {
          try {
            var rUrl = (resp && resp.url) || url || '';
            if (/\/api\/token|get_access_token|clienttoken\.spotify\.com|track-playback\/v1\/devices|\/pathfinder\/v/i.test(rUrl)) {
              resp.clone().json().then(function(j) {
                inspectSpotifyResponseJson(rUrl, j);
              }).catch(function(){});
            }
          } catch (e) {}
          return resp;
        });
      };
    }
  } catch (e) {}

  try {
    var origXhrOpen = XMLHttpRequest.prototype.open;
    var origXhrSetHeader = XMLHttpRequest.prototype.setRequestHeader;
    var origXhrSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url) {
      this.__voxUrl = typeof url === 'string' ? url : (url && url.href ? url.href : String(url || ''));
      return origXhrOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
      inspectHeaderPair(name, value);
      return origXhrSetHeader.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function(body) {
      var u = this.__voxUrl || '';
      inspectSpotifyUrlAndBody(u, body);
      if (/\/api\/token|get_access_token|clienttoken\.spotify\.com|track-playback\/v1\/devices|\/pathfinder\/v/i.test(u)) {
        this.addEventListener('load', function() {
          try {
            if (typeof this.responseText === 'string' && this.responseText.charAt(0) === '{') {
              inspectSpotifyResponseJson(u, JSON.parse(this.responseText));
            }
          } catch (e) {}
        });
      }
      return origXhrSend.apply(this, arguments);
    };
  } catch (e) {}
})();
"""

    /**
     * 运行时控制与曲库读取桥接脚本（同时适配 Apple Music `amp-api` + `MusicKit v3` 与 Spotify `pathfinder/v2/query` + Web API）。
     */
    private const val ENGINE_RUNTIME_SCRIPT = """
(function() {
  if (window.__voxBridge) return;
  var isApple = location.hostname.indexOf('apple.com') >= 0;
  var isSpotify = location.hostname.indexOf('spotify.com') >= 0;

  var APPLE_WEBPLAY_FALLBACK_JWT = 'eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IldlYlBsYXlLaWQifQ.eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxNzkzOTAwNzA4LCJleHAiOjE3OTk5NDg3MDgsInJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdfQ.7L-PYJHjO8PpFdhKS-OSkf_snyFvg5cq-JDERUs_N_UcUX9Vk0kf1WLatgDeEvhB_Arg1b5Tl-99G1v6hUwH0A';

  function sleep(ms) { return new Promise(function(r) { setTimeout(r, ms); }); }
  function runSafe(fn) { try { return fn(); } catch (e) { return null; } }

  function bestMedia() {
    if (window.__voxActiveMedia && !isNaN(window.__voxActiveMedia.duration)) return window.__voxActiveMedia;
    var dom = document.querySelectorAll('audio,video');
    for (var i = 0; i < dom.length; i++) {
      if (!dom[i].paused || dom[i].currentTime > 0 || dom[i].duration > 0) return dom[i];
    }
    var pool = window.__voxMediaPool || [];
    for (var j = pool.length - 1; j >= 0; j--) {
      if (!pool[j].paused || pool[j].duration > 0) return pool[j];
    }
    return pool[pool.length - 1] || dom[0] || null;
  }

  function readDocCookie(name) {
    try {
      var prefix = name + '=';
      var parts = (document.cookie || '').split(';');
      for (var i = 0; i < parts.length; i++) {
        var c = parts[i].trim();
        if (c.indexOf(prefix) === 0) {
          var raw = c.substring(prefix.length).trim().replace(/^"|"$/g, '');
          if (!raw) continue;
          return raw.indexOf('%') >= 0 ? decodeURIComponent(raw) : raw;
        }
      }
    } catch (e) {}
    return '';
  }

  function getAppleDevToken(mk) {
    if (window.__voxAppleDevToken) return window.__voxAppleDevToken;
    if (mk && mk.developerToken) return mk.developerToken;
    return APPLE_WEBPLAY_FALLBACK_JWT;
  }

  function getAppleUserToken(mk, args) {
    if (window.__voxAppleUserToken) return window.__voxAppleUserToken;
    var fromCookie = readDocCookie('media-user-token') || readDocCookie('music-user-token');
    if (fromCookie) {
      window.__voxAppleUserToken = fromCookie;
      return fromCookie;
    }
    if (args && args._cookieMut) {
      window.__voxAppleUserToken = String(args._cookieMut);
      return window.__voxAppleUserToken;
    }
    if (mk && mk.musicUserToken) return mk.musicUserToken;
    try {
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i) || '';
        var v = localStorage.getItem(k) || '';
        if ((/media-user-token|music-user-token/i.test(k) || (k.indexOf('music.') === 0 && /\.u$/.test(k))) && v.length > 25) {
          return v.replace(/^"|"$/g, '');
        }
      }
    } catch (e) {}
    return '';
  }

  async function ensureAppleMusicKit(sf, args) {
    var existing = window.MusicKit && window.MusicKit.getInstance ? runSafe(function(){ return window.MusicKit.getInstance(); }) : null;
    if (existing && (existing.developerToken || existing.api)) return existing;

    // music.apple.com 默认按需懒加载 MusicKit；播放时若尚未加载则主动从同源路径挂载
    if (!window.MusicKit) {
      await new Promise(function(resolve) {
        var s = document.createElement('script');
        s.src = '/includes/js-cdn/musickit/v3/musickit.js';
        s.async = true;
        s.onload = function() { resolve(true); };
        s.onerror = function() { resolve(false); };
        (document.head || document.documentElement).appendChild(s);
        setTimeout(function() { resolve(false); }, 6500);
      });
    }
    var deadline = Date.now() + 4500;
    while (Date.now() < deadline && !window.MusicKit) {
      await sleep(200);
    }
    if (window.MusicKit) {
      var inst = window.MusicKit.getInstance ? runSafe(function(){ return window.MusicKit.getInstance(); }) : null;
      if (!inst && typeof window.MusicKit.configure === 'function') {
        try {
          var cfgRes = await window.MusicKit.configure({
            developerToken: getAppleDevToken(null),
            app: { name: 'Apple Music', build: '2646.4.0' },
            bitrate: 256,
            persist: 'cookie',
            storefrontId: sf || 'cn'
          });
          inst = cfgRes || (window.MusicKit.getInstance ? runSafe(function(){ return window.MusicKit.getInstance(); }) : null);
        } catch (e) {
          inst = window.MusicKit.getInstance ? runSafe(function(){ return window.MusicKit.getInstance(); }) : null;
        }
      }
      var mut = getAppleUserToken(inst, args);
      if (inst && mut && !inst.musicUserToken) {
        try { inst.musicUserToken = mut; } catch (e) {}
      }
      return inst;
    }
    return null;
  }

  function formatAppleArtwork(art, size) {
    if (!art || !art.url) return '';
    return String(art.url)
      .replace('{w}', String(size || 500))
      .replace('{h}', String(size || 500))
      .replace('{c}', 'bb')
      .replace('{f}', 'jpg');
  }

  /**
   * 直接请求 `https://amp-api.music.apple.com`：
   * 注意 Apple CORS `Access-Control-Allow-Headers` 仅允许小写 `authorization, media-user-token, x-apple-client-version`。
   */
  async function appleApi(mk, path, queryObj, args) {
    var devTok = getAppleDevToken(mk);
    var mut = getAppleUserToken(mk, args);
    var qs = '';
    if (queryObj) {
      var parts = [];
      Object.keys(queryObj).forEach(function(k) {
        if (queryObj[k] !== undefined && queryObj[k] !== null) {
          parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(queryObj[k])));
        }
      });
      if (parts.length) qs = '?' + parts.join('&');
    }
    var headers = { 'authorization': 'Bearer ' + devTok };
    if (mut) headers['media-user-token'] = mut;
    if (window.__voxAppleClientVer) headers['x-apple-client-version'] = window.__voxAppleClientVer;
    try {
      var r = await fetch('https://amp-api.music.apple.com' + path + qs, { headers: headers });
      if (!r.ok) return null;
      return await r.json();
    } catch (e) {
      return null;
    }
  }

  async function detectAppleStorefront(mk, args) {
    if (window.__voxAppleStorefront) return window.__voxAppleStorefront;
    if (mk && mk.storefrontId) {
      window.__voxAppleStorefront = mk.storefrontId;
      return mk.storefrontId;
    }
    var seg = (location.pathname || '').split('/').filter(Boolean)[0] || '';
    if (/^[a-z]{2}$/i.test(seg) && seg.toLowerCase() !== 'library') {
      window.__voxAppleStorefront = seg.toLowerCase();
      return window.__voxAppleStorefront;
    }
    var sfData = await appleApi(mk, '/v1/me/storefront', { platform: 'web' }, args);
    var sfId = sfData && sfData.data && sfData.data[0] && sfData.data[0].id;
    if (sfId) {
      window.__voxAppleStorefront = String(sfId).toLowerCase();
      return window.__voxAppleStorefront;
    }
    var itua = readDocCookie('itua');
    if (itua && /^[a-z]{2,3}$/i.test(itua)) {
      window.__voxAppleStorefront = itua.toLowerCase();
      return window.__voxAppleStorefront;
    }
    return 'cn';
  }

  function mapAppleResource(r) {
    if (!r || !r.attributes) return null;
    var a = r.attributes;
    var catRel = r.relationships && r.relationships.catalog && r.relationships.catalog.data && r.relationships.catalog.data[0];
    var ca = (catRel && catRel.attributes) || {};
    var t = r.type || '';
    var itemType = 'track';
    if (t.indexOf('playlist') >= 0) itemType = 'playlist';
    else if (t.indexOf('album') >= 0) itemType = 'album';
    else if (t.indexOf('artist') >= 0) itemType = 'artist';
    var playId = (a.playParams && (a.playParams.catalogId || a.playParams.id)) || (catRel && catRel.id) || r.id;
    var descObj = a.description || a.editorialNotes || ca.description || ca.editorialNotes || null;
    var desc = descObj ? (descObj.standard || descObj.short || '') : '';
    var rawDate = a.releaseDate || ca.releaseDate || '';
    var yr = rawDate ? parseInt(String(rawDate).substring(0, 4), 10) : 0;
    var art = a.artwork || ca.artwork || null;
    var title = a.name || ca.name || '';
    if (!title) return null;
    return {
      id: itemType === 'track' ? String(playId) : (t + '|' + r.id),
      type: itemType,
      title: title,
      artist: a.artistName || a.curatorName || ca.artistName || ca.curatorName || '',
      album: a.albumName || ca.albumName || '',
      summary: String(desc || '').replace(/<[^>]+>/g, ''),
      cover: formatAppleArtwork(art, 500),
      durationMs: a.durationInMillis || ca.durationInMillis || 0,
      count: a.trackCount || ca.trackCount || 0,
      year: yr > 1900 ? yr : 0,
      webUrl: a.url || ca.url || ''
    };
  }

  function dedupeAppleItems(list) {
    var seen = {};
    var out = [];
    for (var i = 0; i < list.length; i++) {
      var it = list[i];
      if (!it || !it.id || seen[it.id]) continue;
      seen[it.id] = true;
      out.push(it);
    }
    return out;
  }

  function extractAppleRecommendations(recJson, wantType) {
    var list = [];
    var groups = (recJson && recJson.data) || [];
    for (var i = 0; i < groups.length; i++) {
      var contents = groups[i] && groups[i].relationships && groups[i].relationships.contents && groups[i].relationships.contents.data;
      if (!Array.isArray(contents)) continue;
      for (var j = 0; j < contents.length; j++) {
        var mapped = mapAppleResource(contents[j]);
        if (mapped && mapped.type === wantType) list.push(mapped);
      }
    }
    return dedupeAppleItems(list);
  }

  // ---------------- Spotify 新版 TOTP 会话令牌与 Pathfinder v2 GraphQL 引擎 ----------------

  var SPOTIFY_HASHES = {
    fetchLibraryTracks: '087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240',
    libraryV3: '390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455',
    fetchPlaylist: '21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf',
    getAlbum: '0b6ce3511e1b02e4a40fa95440f992acbe85080eb308af9194322d708fc1914f',
    searchSuggestions: 'f244254b94c0e824d458ac216e0f8406a73f2f69a9ff52831a2f78fa774ff6ce',
    assistedCurationSearch: 'f78953bf9207d73493c27284103f5aeb6e728876d5793851bf79bc706127ff70',
    home: '5fb7da7a03ee2776c0670856609cb85a189a3cecc36cc160b91cde79063b9cf1'
  };

  async function ensureSpotifyToken(maxWaitMs) {
    var exp = Number(window.__voxSpotifyTokenExp || 0);
    if (window.__voxSpotifyToken && (!exp || Date.now() < exp - 30000)) {
      return window.__voxSpotifyToken;
    }
    var limit = maxWaitMs || 5500;
    var deadline = Date.now() + limit;
    var triedActiveFetch = false;
    while (Date.now() < deadline) {
      exp = Number(window.__voxSpotifyTokenExp || 0);
      if (window.__voxSpotifyToken && (!exp || Date.now() < exp - 30000)) {
        return window.__voxSpotifyToken;
      }
      if (!triedActiveFetch && (deadline - Date.now()) < (limit - 1200)) {
        triedActiveFetch = true;
        try {
          var tr = await fetch('https://open.spotify.com/get_access_token?reason=transport&productType=web_player', { credentials: 'include' });
          if (tr && tr.ok) {
            var tj = await tr.json();
            if (tj && tj.accessToken) {
              window.__voxSpotifyToken = tj.accessToken;
              window.__voxSpotifyAnon = !!tj.isAnonymous;
              if (tj.accessTokenExpirationTimestampMs) window.__voxSpotifyTokenExp = Number(tj.accessTokenExpirationTimestampMs) || 0;
              return tj.accessToken;
            }
          }
        } catch (e) {}
      }
      await sleep(180);
    }
    return window.__voxSpotifyToken || '';
  }

  function spotifyAuthHeaders(tok) {
    var h = {
      'Authorization': 'Bearer ' + tok,
      'app-platform': 'WebPlayer',
      'spotify-app-version': window.__voxSpotifyAppVer || '896000000'
    };
    if (window.__voxSpotifyClientToken) h['client-token'] = window.__voxSpotifyClientToken;
    return h;
  }

  async function spotifyApi(path) {
    var tok = await ensureSpotifyToken(10000);
    if (!tok) return null;
    try {
      var r = await fetch('https://api.spotify.com/v1' + path, {
        headers: spotifyAuthHeaders(tok)
      });
      if (!r.ok) return null;
      return await r.json();
    } catch (e) {
      return null;
    }
  }

  async function spotifyPathfinder(opName, variables) {
    var tok = await ensureSpotifyToken(11000);
    if (!tok) return null;
    var hash = (window.__voxSpotifyHashes && window.__voxSpotifyHashes[opName]) || SPOTIFY_HASHES[opName];
    if (!hash) return null;
    try {
      var hdrs = spotifyAuthHeaders(tok);
      hdrs['Content-Type'] = 'application/json;charset=UTF-8';
      var r = await fetch('https://api-partner.spotify.com/pathfinder/v2/query', {
        method: 'POST',
        headers: hdrs,
        body: JSON.stringify({
          variables: variables || {},
          operationName: opName,
          extensions: { persistedQuery: { version: 1, sha256Hash: hash } }
        })
      });
      if (!r.ok) return null;
      return await r.json();
    } catch (e) {
      return null;
    }
  }

  function bestSpotifyCover(sources) {
    if (!Array.isArray(sources) || !sources.length) return '';
    var best = sources[0];
    for (var i = 1; i < sources.length; i++) {
      var w = sources[i].width || sources[i].maxWidth || 0;
      var bw = best.width || best.maxWidth || 0;
      if (w > bw && w <= 640) best = sources[i];
    }
    return (best && best.url) || '';
  }

  function spotifyUriId(uri) {
    if (!uri || typeof uri !== 'string') return '';
    var parts = uri.split(':');
    return parts[parts.length - 1] || '';
  }

  function mapSpotifyTrack(t, albumFallback) {
    if (!t || !t.id || !t.name) return null;
    var artists = (t.artists || []).map(function(x){ return x.name; }).filter(Boolean).join(' / ');
    var alb = t.album || albumFallback || {};
    var imgs = alb.images || [];
    var cover = imgs.length ? imgs[0].url : '';
    var yr = alb.release_date ? parseInt(String(alb.release_date).substring(0, 4), 10) : 0;
    return {
      id: t.id,
      type: 'track',
      title: t.name,
      artist: artists,
      album: alb.name || '',
      cover: cover,
      durationMs: t.duration_ms || 0,
      year: yr > 1900 ? yr : 0,
      webUrl: 'https://open.spotify.com/track/' + t.id
    };
  }

  function mapPathfinderTrackNode(node, albumFallback) {
    if (!node || typeof node !== 'object') return null;
    var t = node;
    if (t.itemV2 && t.itemV2.data) t = t.itemV2.data;
    else if (t.track && t.track.data) t = t.track.data;
    else if (t.track && t.track.uri) t = t.track;
    else if (t.item && t.item.data) t = t.item.data;
    else if (t.data && (t.data.__typename === 'Track' || t.data.uri)) t = t.data;

    var uri = t.uri || t._uri || (node.track && node.track._uri) || '';
    if (uri.indexOf('spotify:track:') !== 0 && t.__typename !== 'Track') return null;
    var id = spotifyUriId(uri) || t.id || '';
    var name = t.name || '';
    if (!id || !name) return null;

    var artistNames = [];
    var aItems = (t.artists && t.artists.items) || (Array.isArray(t.artists) ? t.artists : []);
    for (var i = 0; i < aItems.length; i++) {
      var an = (aItems[i] && aItems[i].profile && aItems[i].profile.name) || (aItems[i] && aItems[i].name) || '';
      if (an) artistNames.push(an);
    }

    var alb = t.albumOfTrack || albumFallback || {};
    var albName = alb.name || '';
    var cover = '';
    if (alb.coverArt && alb.coverArt.sources) cover = bestSpotifyCover(alb.coverArt.sources);
    else if (alb.images && alb.images.length) cover = alb.images[0].url || '';

    var dur = (t.duration && t.duration.totalMilliseconds) ||
              (t.trackDuration && t.trackDuration.totalMilliseconds) ||
              t.duration_ms || 0;
    var yr = (alb.date && alb.date.year) || 0;
    return {
      id: id,
      type: 'track',
      title: name,
      artist: artistNames.join(' / '),
      album: albName,
      cover: cover,
      durationMs: dur,
      year: yr > 1900 ? yr : 0,
      webUrl: 'https://open.spotify.com/track/' + id
    };
  }

  function mapPathfinderContainerNode(rawWrapper) {
    if (!rawWrapper || typeof rawWrapper !== 'object') return null;
    var d = rawWrapper;
    if (d.item && d.item.data) d = d.item.data;
    else if (d.data) d = d.data;

    var uri = d.uri || d._uri || (rawWrapper.item && rawWrapper.item._uri) || '';
    var tn = d.__typename || '';
    if (tn === 'LibraryPseudoPlaylist' || uri === 'spotify:collection:tracks') {
      var imgP = (d.image && d.image.sources && bestSpotifyCover(d.image.sources)) || '';
      return {
        id: 'playlist|liked',
        type: 'playlist',
        title: d.name || '我喜欢的音乐',
        summary: 'Spotify 已点赞收藏的歌曲',
        cover: imgP,
        count: d.count || 0
      };
    }
    if (tn === 'Playlist' || uri.indexOf('spotify:playlist:') === 0) {
      var pid = spotifyUriId(uri);
      if (!pid || !d.name) return null;
      var pSources = (d.images && d.images.items && d.images.items[0] && d.images.items[0].sources) ||
                     (d.coverArt && d.coverArt.sources) || [];
      return {
        id: 'playlist|' + pid,
        type: 'playlist',
        title: d.name,
        artist: (d.ownerV2 && d.ownerV2.data && d.ownerV2.data.name) || '',
        summary: String(d.description || '').replace(/<[^>]+>/g, ''),
        cover: bestSpotifyCover(pSources),
        count: (d.attributes && d.attributes.length) || 0
      };
    }
    if (tn === 'Album' || uri.indexOf('spotify:album:') === 0) {
      var aid = spotifyUriId(uri);
      if (!aid || !d.name) return null;
      var aArtists = ((d.artists && d.artists.items) || []).map(function(a) {
        return (a && a.profile && a.profile.name) || (a && a.name) || '';
      }).filter(Boolean).join(' / ');
      var aSources = (d.coverArt && d.coverArt.sources) || [];
      var yr = (d.date && d.date.year) || 0;
      return {
        id: 'album|' + aid,
        type: 'album',
        title: d.name,
        artist: aArtists,
        cover: bestSpotifyCover(aSources),
        year: yr > 1900 ? yr : 0,
        count: 0
      };
    }
    return null;
  }

  function collectDeepEntities(root, wantType, limit) {
    var results = [];
    var seen = {};
    function walk(obj, depth) {
      if (!obj || typeof obj !== 'object' || depth > 14 || results.length >= limit) return;
      if (wantType === 'track') {
        var tr = mapPathfinderTrackNode(obj);
        if (tr && !seen[tr.id]) {
          seen[tr.id] = true;
          results.push(tr);
          return;
        }
      } else {
        var ct = mapPathfinderContainerNode(obj);
        if (ct && ct.type === wantType && !seen[ct.id]) {
          seen[ct.id] = true;
          results.push(ct);
          return;
        }
      }
      if (Array.isArray(obj)) {
        for (var i = 0; i < obj.length && results.length < limit; i++) walk(obj[i], depth + 1);
      } else {
        var keys = Object.keys(obj);
        for (var j = 0; j < keys.length && results.length < limit; j++) {
          var k = keys[j];
          if (k === 'extensions') continue;
          walk(obj[k], depth + 1);
        }
      }
    }
    walk(root, 0);
    return results;
  }

  async function getSpotifyLibraryV3(filterName) {
    if (!filterName && window.__voxPathfinderCache && window.__voxPathfinderCache.libraryV3) {
      return window.__voxPathfinderCache.libraryV3;
    }
    var vars = {
      filters: filterName ? [filterName] : [],
      order: null,
      textFilter: null,
      features: ['LIKED_SONGS', 'YOUR_EPISODES_V2', 'PRERELEASES', 'EVENTS'],
      limit: 50,
      offset: 0,
      flatten: false,
      expandedFolders: [],
      folderUri: null,
      includeFoldersWhenFlattening: true
    };
    var res = await spotifyPathfinder('libraryV3', vars);
    if (res && res.data && res.data.me && res.data.me.libraryV3) return res;
    return (window.__voxPathfinderCache && window.__voxPathfinderCache.libraryV3) || null;
  }

  async function loadSpotifyPlaylistTracks(playlistId) {
    if (playlistId === 'liked') {
      var fl = await spotifyPathfinder('fetchLibraryTracks', { offset: 0, limit: 80 });
      var flItems = fl && fl.data && fl.data.me && fl.data.me.library && fl.data.me.library.tracks && fl.data.me.library.tracks.items;
      if (Array.isArray(flItems) && flItems.length) {
        return flItems.map(function(x) { return mapPathfinderTrackNode(x); }).filter(Boolean);
      }
      var st = await spotifyApi('/me/tracks?limit=50');
      return (((st && st.items) || []).map(function(x) { return mapSpotifyTrack(x && x.track); })).filter(Boolean);
    }
    var pf = await spotifyPathfinder('fetchPlaylist', {
      uri: 'spotify:playlist:' + playlistId,
      offset: 0,
      limit: 100,
      enableWatchFeedEntrypoint: false,
      includeEpisodeContentRatingsV2: false
    });
    var pItems = pf && pf.data && pf.data.playlistV2 && pf.data.playlistV2.content && pf.data.playlistV2.content.items;
    if (Array.isArray(pItems) && pItems.length) {
      var mapped = pItems.map(function(x) { return mapPathfinderTrackNode(x); }).filter(Boolean);
      if (mapped.length) return mapped;
    }
    var ptr = await spotifyApi('/playlists/' + playlistId + '/tracks?limit=100');
    return (((ptr && ptr.items) || []).map(function(x) { return mapSpotifyTrack(x && x.track); })).filter(Boolean);
  }

  async function loadSpotifyAlbumTracks(albumId) {
    var pf = await spotifyPathfinder('getAlbum', {
      uri: 'spotify:album:' + albumId,
      locale: '',
      offset: 0,
      limit: 50
    });
    var au = pf && pf.data && pf.data.albumUnion;
    if (au && au.tracksV2 && Array.isArray(au.tracksV2.items)) {
      var mapped = au.tracksV2.items.map(function(x) { return mapPathfinderTrackNode(x, au); }).filter(Boolean);
      if (mapped.length) return mapped;
    }
    var albInfo = await spotifyApi('/albums/' + albumId);
    return ((((albInfo && albInfo.tracks) || {}).items || []).map(function(t) { return mapSpotifyTrack(t, albInfo); })).filter(Boolean);
  }

  async function loadSpotifyTracksSmart() {
    // 1. Pathfinder v2: 已点赞收藏歌曲 (Liked Songs)
    var fl = (window.__voxPathfinderCache && window.__voxPathfinderCache.fetchLibraryTracks) ||
             await spotifyPathfinder('fetchLibraryTracks', { offset: 0, limit: 60 });
    var flItems = fl && fl.data && fl.data.me && fl.data.me.library && fl.data.me.library.tracks && fl.data.me.library.tracks.items;
    if (Array.isArray(flItems) && flItems.length) {
      var list1 = flItems.map(function(x) { return mapPathfinderTrackNode(x); }).filter(Boolean);
      if (list1.length) return list1;
    }

    // 2. Web API: /me/tracks 或最近播放
    var st = await spotifyApi('/me/tracks?limit=50');
    if (st && Array.isArray(st.items) && st.items.length) {
      var list2 = st.items.map(function(x) { return mapSpotifyTrack(x && x.track); }).filter(Boolean);
      if (list2.length) return list2;
    }
    var recent = await spotifyApi('/me/player/recently-played?limit=40');
    if (recent && Array.isArray(recent.items) && recent.items.length) {
      var seenR = {};
      var listR = [];
      for (var r = 0; r < recent.items.length; r++) {
        var tr = mapSpotifyTrack(recent.items[r] && recent.items[r].track);
        if (tr && !seenR[tr.id]) { seenR[tr.id] = true; listR.push(tr); }
      }
      if (listR.length) return listR;
    }

    // 3. 若账号未单独点赞歌曲，自动展开用户首个歌单中的歌曲
    var lib = await getSpotifyLibraryV3('Playlists');
    var playlists = collectDeepEntities(lib, 'playlist', 10);
    for (var p = 0; p < playlists.length; p++) {
      var pid = String(playlists[p].id || '').split('|')[1] || '';
      if (pid && pid !== 'liked') {
        var pTracks = await loadSpotifyPlaylistTracks(pid);
        if (pTracks.length) return pTracks;
      }
    }

    // 4. 从首页个性化推荐缓存或首个推荐歌单中提取可播放曲目
    var homeData = (window.__voxPathfinderCache && window.__voxPathfinderCache.home) ||
                   await spotifyPathfinder('home', { timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai', sp_t: '', country: '' });
    if (homeData) {
      var hTracks = collectDeepEntities(homeData, 'track', 40);
      if (hTracks.length) return hTracks;
      var hPlaylists = collectDeepEntities(homeData, 'playlist', 5);
      for (var hp = 0; hp < hPlaylists.length; hp++) {
        var hpid = String(hPlaylists[hp].id || '').split('|')[1] || '';
        if (hpid) {
          var hpTracks = await loadSpotifyPlaylistTracks(hpid);
          if (hpTracks.length) return hpTracks;
        }
      }
    }
    return [];
  }

  async function searchSpotifySmart(query) {
    var pf = await spotifyPathfinder('searchSuggestions', {
      query: query,
      limit: 30,
      numberOfTopResults: 30,
      offset: 0,
      includeAuthors: false,
      includeAlbumPreReleases: true,
      includeEpisodeContentRatingsV2: false
    });
    if (pf && pf.data && pf.data.searchV2) {
      var sv = pf.data.searchV2;
      var tracks = collectDeepEntities(sv.tracksV2 || sv.topResultsV2 || sv, 'track', 25);
      var albums = collectDeepEntities(sv.albumsV2 || sv.topResultsV2 || sv, 'album', 10);
      var playlists = collectDeepEntities(sv.playlists || sv.topResultsV2 || sv, 'playlist', 10);
      var combined = tracks.concat(albums).concat(playlists);
      if (combined.length) return combined;
    }
    var ac = await spotifyPathfinder('assistedCurationSearch', { searchTerm: query, limit: 25, offset: 0 });
    if (ac && ac.data) {
      var acTracks = collectDeepEntities(ac.data, 'track', 25);
      if (acTracks.length) return acTracks;
    }
    var sq = encodeURIComponent(query || '');
    var ss = await spotifyApi('/search?type=track,album,playlist&limit=20&q=' + sq);
    var sOut = [];
    if (ss && ss.tracks && ss.tracks.items) {
      sOut = sOut.concat(ss.tracks.items.map(function(t){ return mapSpotifyTrack(t); }));
    }
    if (ss && ss.albums && ss.albums.items) {
      sOut = sOut.concat(ss.albums.items.map(function(al){
        return {
          id: 'album|' + al.id,
          type: 'album',
          title: al.name || '',
          artist: (al.artists || []).map(function(a){ return a.name; }).join(' / '),
          cover: (al.images && al.images[0] && al.images[0].url) || '',
          count: al.total_tracks || 0
        };
      }));
    }
    if (ss && ss.playlists && ss.playlists.items) {
      sOut = sOut.concat(ss.playlists.items.map(function(pl){
        if (!pl || !pl.id) return null;
        return {
          id: 'playlist|' + pl.id,
          type: 'playlist',
          title: pl.name || '',
          summary: String(pl.description || '').replace(/<[^>]+>/g, ''),
          cover: (pl.images && pl.images[0] && pl.images[0].url) || '',
          count: (pl.tracks && pl.tracks.total) || 0
        };
      }));
    }
    return sOut.filter(Boolean);
  }

  async function playOnApple(args) {
    var sf = await detectAppleStorefront(null, args);
    var mk = await ensureAppleMusicKit(sf, args);
    var mut = getAppleUserToken(mk, args);
    if (!mut && (!mk || !mk.isAuthorized)) {
      return { ok: false, error: 'Apple Music 登录状态已失效，请在音源设置中重新登录' };
    }
    var trackId = String(args.trackId || '').trim();
    if (!trackId && args.title) {
      var q = (args.title + ' ' + (args.artist || '')).trim();
      var sData = await appleApi(mk, '/v1/catalog/' + sf + '/search', { term: q, types: 'songs', limit: 5 }, args);
      var songs = (((sData || {}).results || {}).songs || {}).data || [];
      if (songs.length) trackId = songs[0].id;
    }
    if (!trackId) return { ok: false, error: '未在 Apple Music 曲库中找到该歌曲' };

    if (mk && typeof mk.setQueue === 'function') {
      var queueArg = trackId.indexOf('i.') === 0 ? { librarySong: trackId, startPlaying: true } : { song: trackId, startPlaying: true };
      await mk.setQueue(queueArg);
      await mk.play();
      if (args.startPositionMs > 1000 && typeof mk.seekToTime === 'function') {
        mk.seekToTime(args.startPositionMs / 1000.0);
      }
      return { ok: true };
    }

    // 回退路径：通过 Apple Music SPA 跳转单曲页触发官方页面播放器加载并起播
    var targetPath = '/' + sf + '/song/' + trackId;
    window.history.pushState({}, '', targetPath);
    window.dispatchEvent(new PopStateEvent('popstate'));
    var deadline = Date.now() + 8000;
    while (Date.now() < deadline) {
      await sleep(350);
      var btn = document.querySelector('button[data-testid="click-action"]') ||
                document.querySelector('.headings__subtitles button') ||
                document.querySelector('main button[aria-label*="播放"], main button[aria-label*="Play"]');
      if (btn) {
        btn.click();
        await sleep(300);
        return { ok: true };
      }
    }
    return { ok: false, error: 'Apple Music 播放器尚未就绪，请重试' };
  }

  function normTrackTitle(s) {
    return String(s || '').toLowerCase().replace(/[\s\-_()（）\[\]【】·.,，。!?！？'"]/g, '');
  }

  function isPlayCancelled(reqSeq) {
    return !!(reqSeq && reqSeq > 0 && reqSeq !== voxPlaySeq);
  }

  async function waitForSpotifyWebDevice(tok, reqSeq, maxWaitMs) {
    var deadline = Date.now() + (maxWaitMs || 2200);
    var queriedApi = false;
    while (Date.now() < deadline) {
      if (isPlayCancelled(reqSeq)) return '';
      if (window.__voxSpotifyDeviceId) return window.__voxSpotifyDeviceId;
      if (!queriedApi && tok && (deadline - Date.now()) < ((maxWaitMs || 2200) - 700)) {
        queriedApi = true;
        var devs = await spotifyApi('/me/player/devices');
        if (isPlayCancelled(reqSeq)) return '';
        var list = (devs && devs.devices) || [];
        // 严格只匹配本机隐形 WebView 的 Web Player，绝不误选同一账号下的其他手机或电脑设备
        var webDev = list.find(function(d){ return d && d.id && /web player/i.test(d.name || ''); });
        if (webDev && webDev.id) {
          window.__voxSpotifyDeviceId = webDev.id;
          return webDev.id;
        }
      }
      await sleep(150);
    }
    return window.__voxSpotifyDeviceId || '';
  }

  async function trySpotifySpClientPlay(tok, devId, trackId, startPosMs, reqSeq) {
    if (!tok || !devId || !window.__voxSpotifySpClientOrigin) return false;
    if (isPlayCancelled(reqSeq)) return false;
    try {
      var spUrl = window.__voxSpotifySpClientOrigin +
        '/connect-state/v1/player/command/from/' + encodeURIComponent(devId) +
        '/to/' + encodeURIComponent(devId);
      var hdrs = spotifyAuthHeaders(tok);
      hdrs['Content-Type'] = 'application/json';
      var cmd = {
        context: {
          uri: 'spotify:track:' + trackId,
          url: 'context://spotify:track:' + trackId,
          metadata: {}
        },
        play_origin: {
          feature_identifier: 'track',
          feature_version: 'web-player'
        },
        options: {
          license: 'on-demand',
          skip_to: { track_uri: 'spotify:track:' + trackId },
          player_options_override: {
            shuffling_context: false,
            repeating_context: false,
            repeating_track: false
          }
        },
        endpoint: 'play'
      };
      if (startPosMs > 1000) cmd.options.seek_to = Math.round(startPosMs);
      var res = await fetch(spUrl, {
        method: 'POST',
        headers: hdrs,
        body: JSON.stringify({ command: cmd })
      });
      if (isPlayCancelled(reqSeq)) return false;
      return !!(res && (res.ok || res.status === 204));
    } catch (e) {
      return false;
    }
  }

  async function trySpotifyWebApiPlay(tok, devId, trackId, startPosMs, reqSeq) {
    if (!tok || !devId) return false;
    if (isPlayCancelled(reqSeq)) return false;
    try {
      var hdrs = spotifyAuthHeaders(tok);
      hdrs['Content-Type'] = 'application/json';
      var playUrl = 'https://api.spotify.com/v1/me/player/play?device_id=' + encodeURIComponent(devId);
      var playBody = JSON.stringify({ uris: ['spotify:track:' + trackId], position_ms: startPosMs || 0 });
      var playRes = await fetch(playUrl, { method: 'PUT', headers: hdrs, body: playBody });
      if (isPlayCancelled(reqSeq)) return false;
      if (playRes.status === 404) {
        await fetch('https://api.spotify.com/v1/me/player', {
          method: 'PUT',
          headers: hdrs,
          body: JSON.stringify({ device_ids: [devId], play: false })
        }).catch(function(){});
        await sleep(220);
        if (isPlayCancelled(reqSeq)) return false;
        playRes = await fetch(playUrl, { method: 'PUT', headers: hdrs, body: playBody });
      }
      if (isPlayCancelled(reqSeq)) return false;
      return !!(playRes && (playRes.ok || playRes.status === 204));
    } catch (e) {
      return false;
    }
  }

  async function clickVerifiedSpotifyTrackPlayBtn(expectedTrackId, expectedTitle, reqSeq, timeoutMs) {
    var deadline = Date.now() + (timeoutMs || 5000);
    var wantNorm = normTrackTitle(expectedTitle);
    while (Date.now() < deadline) {
      if (isPlayCancelled(reqSeq)) return false;
      if (location.pathname.indexOf('/track/' + expectedTrackId) === 0) {
        var h1 = document.querySelector('main h1');
        var h1Norm = normTrackTitle(h1 && h1.textContent);
        var titleOk = !voxStalePrevTitle || !h1Norm || h1Norm !== voxStalePrevTitle || (wantNorm && h1Norm.indexOf(wantNorm) >= 0);
        // 严格只定位目标单曲页顶部的 action-bar-row 主播放键，严禁误点首页/推荐卡片或上一首歌残留的按钮
        var bar = document.querySelector('[data-testid="action-bar-row"]') ||
                  document.querySelector('main section [data-testid="action-bar-row"]');
        var btn = bar && bar.querySelector('button[data-testid="play-button"]');
        if (btn && titleOk) {
          btn.click();
          await sleep(220);
          return true;
        }
      }
      await sleep(200);
    }
    return false;
  }

  async function playOnSpotify(args) {
    var reqSeq = Number(args && args.playSeq) || voxPlaySeq;
    var trackId = String((args && args.trackId) || '').trim();
    var startPosMs = Math.max(0, Number(args && args.startPositionMs) || 0);
    var tok = await ensureSpotifyToken(4500);
    if (isPlayCancelled(reqSeq)) return { ok: false, cancelled: true };

    if (!trackId && args && args.webUrl) {
      var m = String(args.webUrl).match(/track\/([A-Za-z0-9]+)/);
      if (m) trackId = m[1];
    }
    if (!trackId && args && args.title) {
      var q = (args.title + ' ' + (args.artist || '')).trim();
      var hits = await searchSpotifySmart(q);
      if (isPlayCancelled(reqSeq)) return { ok: false, cancelled: true };
      var wantT = normTrackTitle(args.title);
      var exact = hits.find(function(h) { return h && h.type === 'track' && h.id && normTrackTitle(h.title) === wantT; });
      var firstTrack = exact || hits.find(function(h) { return h && h.type === 'track' && h.id; });
      if (firstTrack) trackId = firstTrack.id;
    }
    if (!trackId) return { ok: false, error: '未在 Spotify 曲库中找到该歌曲' };
    voxTargetTrackId = trackId;

    // 1. 极速通道 A：直接走 Spotify Web Player 原生 spclient Connect 指令（~250ms 起播且严格锁定指定 trackId）
    // 2. 极速通道 B：回退官方 Web API PUT /v1/me/player/play（自动 404 激活当前 Web Player 设备）
    if (tok) {
      var devId = await waitForSpotifyWebDevice(tok, reqSeq, 2200);
      if (isPlayCancelled(reqSeq)) return { ok: false, cancelled: true };
      if (devId) {
        if (await trySpotifySpClientPlay(tok, devId, trackId, startPosMs, reqSeq)) {
          return { ok: true };
        }
        if (isPlayCancelled(reqSeq)) return { ok: false, cancelled: true };
        if (await trySpotifyWebApiPlay(tok, devId, trackId, startPosMs, reqSeq)) {
          return { ok: true };
        }
        if (isPlayCancelled(reqSeq)) return { ok: false, cancelled: true };
      }
    }

    // 3. 页面直达兜底：严禁使用 React Router 无效的 pushState + 盲点旧 play-button，改用精确单曲页跳转并在新页核验主播放键
    var targetPath = '/track/' + trackId;
    if (location.pathname.indexOf(targetPath) === 0) {
      var clicked = await clickVerifiedSpotifyTrackPlayBtn(trackId, args && args.title, reqSeq, 4200);
      if (isPlayCancelled(reqSeq)) return { ok: false, cancelled: true };
      if (clicked) return { ok: true };
    }

    try {
      sessionStorage.setItem('__voxPendingSpotifyPlay', JSON.stringify({
        trackId: trackId,
        playSeq: reqSeq || voxPlaySeq,
        title: String((args && args.title) || ''),
        startPosMs: startPosMs,
        ts: Date.now()
      }));
    } catch (e) {}
    setTimeout(function() {
      if (!isPlayCancelled(reqSeq)) {
        location.assign('https://open.spotify.com/track/' + trackId);
      }
    }, 20);
    return { ok: true };
  }

  var voxPlaySeq = 0;
  var voxSwitching = false;
  var voxSwitchStartedAt = 0;
  var voxTargetTrackId = '';
  var voxTargetTitle = '';
  var voxStalePrevId = '';
  var voxStalePrevTitle = '';
  var voxStartPosMs = 0;
  var voxSawTargetProgress = false;

  function currentAppleNowPlayingId() {
    if (!isApple || !window.MusicKit || !window.MusicKit.getInstance) return '';
    var mk = runSafe(function(){ return window.MusicKit.getInstance(); });
    var np = mk && mk.nowPlayingItem;
    if (!np) return '';
    return String(
      (np.attributes && np.attributes.playParams && (np.attributes.playParams.catalogId || np.attributes.playParams.id)) ||
      np.songId || np.id || ''
    ).trim();
  }

  function emitHostSnapshot(payload) {
    if (window.VoxMusicHost && window.VoxMusicHost.onSnapshot) {
      window.VoxMusicHost.onSnapshot(JSON.stringify(payload));
    }
  }

  function markTrackSwitching(seq, trackId, startPosMs, targetTitle) {
    var incomingSeq = Number(seq) || 0;
    var isNewSeq = incomingSeq > 0 && incomingSeq !== voxPlaySeq;
    if (incomingSeq > 0) voxPlaySeq = incomingSeq;
    var nextTarget = String(trackId || '').trim();
    var nextTitleNorm = normTrackTitle(targetTitle);
    if (isNewSeq) {
      var prevId = currentAppleNowPlayingId();
      voxStalePrevId = (prevId && prevId !== nextTarget) ? prevId : '';
      var msPrev = normTrackTitle(navigator.mediaSession && navigator.mediaSession.metadata && navigator.mediaSession.metadata.title);
      voxStalePrevTitle = (msPrev && (!nextTitleNorm || msPrev !== nextTitleNorm)) ? msPrev : '';
    } else {
      if (nextTarget && voxStalePrevId === nextTarget) voxStalePrevId = '';
      if (nextTitleNorm && voxStalePrevTitle === nextTitleNorm) voxStalePrevTitle = '';
    }
    if (nextTarget) voxTargetTrackId = nextTarget;
    if (nextTitleNorm) voxTargetTitle = nextTitleNorm;
    voxSwitching = true;
    voxSwitchStartedAt = Date.now();
    voxStartPosMs = Math.max(0, Number(startPosMs) || 0);
    voxSawTargetProgress = false;
    window.__voxActiveMedia = null;
    var dom = document.querySelectorAll('audio,video');
    for (var i = 0; i < dom.length; i++) {
      try {
        dom[i].__voxStaleSrc = dom[i].currentSrc || dom[i].src || '__old';
        dom[i].__voxStaleTime = dom[i].currentTime || 0;
        if (!dom[i].paused) dom[i].pause();
      } catch (e) {}
    }
    var pool = window.__voxMediaPool || [];
    for (var j = 0; j < pool.length; j++) {
      try {
        pool[j].__voxStaleSrc = pool[j].currentSrc || pool[j].src || '__old';
        pool[j].__voxStaleTime = pool[j].currentTime || 0;
        if (!pool[j].paused) pool[j].pause();
      } catch (e) {}
    }
  }

  function pushSnapshot(endedFlag) {
    try {
      if (voxPlaySeq <= 0) return;
      if (voxSwitching) {
        emitHostSnapshot({
          playSeq: voxPlaySeq,
          switching: true,
          playing: false,
          buffering: true,
          posMs: voxStartPosMs,
          durMs: 0,
          title: '',
          artist: '',
          album: '',
          coverUrl: '',
          ended: false
        });
        return;
      }

      var playing = false, buffering = false, posMs = 0, durMs = 0;
      var title = '', artist = '', album = '', coverUrl = '';
      var ended = !!endedFlag;
      var usedMusicKit = false;

      if (isApple && window.MusicKit && window.MusicKit.getInstance) {
        var mk = runSafe(function(){ return window.MusicKit.getInstance(); });
        if (mk && (mk.nowPlayingItem || typeof mk.playbackState === 'number')) {
          usedMusicKit = true;
          var np = mk.nowPlayingItem;
          var npId = currentAppleNowPlayingId();
          // 切歌初期若 MusicKit 仍停留在切歌前的旧曲目 ID，持续冻结在起点缓冲态（同时兼容跨区自动重链的新曲目 ID）
          if (!voxSawTargetProgress && voxStalePrevId && npId === voxStalePrevId && (Date.now() - voxSwitchStartedAt) < 8500) {
            emitHostSnapshot({
              playSeq: voxPlaySeq,
              switching: true,
              playing: false,
              buffering: true,
              posMs: voxStartPosMs,
              durMs: 0,
              ended: false
            });
            return;
          }

          // MusicKit v3 PlaybackStates: 1=LOADING, 2=PLAYING, 3=PAUSED, 5=ENDED, 6=SEEKING, 8=WAITING, 9=STALLED, 10=COMPLETED
          var mkState = (typeof mk.playbackState === 'number') ? mk.playbackState : -1;
          var mkBuffering = (mkState === 1 || mkState === 6 || mkState === 8 || mkState === 9);
          var mkPlaying = (mkState === 2) || (mkState < 0 && !!mk.isPlaying && !mkBuffering);
          var rawPos = Math.max(0, Math.round((mk.currentPlaybackTime || 0) * 1000));
          var rawDur = Math.max(0, Math.round((mk.currentPlaybackDuration || 0) * 1000));

          if (!voxSawTargetProgress) {
            if (mkPlaying && !mkBuffering && (voxStartPosMs > 0 || rawPos < 2500 || (Date.now() - voxSwitchStartedAt) > 5000)) {
              voxSawTargetProgress = true;
            } else {
              emitHostSnapshot({
                playSeq: voxPlaySeq,
                switching: false,
                playing: false,
                buffering: true,
                posMs: voxStartPosMs,
                durMs: 0,
                ended: false
              });
              return;
            }
          } else if (mkState === 5 || mkState === 10) {
            ended = true;
          }

          playing = mkPlaying && !ended;
          buffering = mkBuffering;
          // 严禁在 posMs===0 时回退读取页面残留的旧 <audio> 节点，彻底消除起播瞬间跳到上一首秒数
          posMs = rawPos;
          durMs = rawDur;
          if (np && np.attributes) {
            title = np.attributes.name || '';
            artist = np.attributes.artistName || '';
            album = np.attributes.albumName || '';
            coverUrl = formatAppleArtwork(np.attributes.artwork, 600);
          }
        }
      }

      if (!usedMusicKit) {
        var msNowTitle = normTrackTitle(navigator.mediaSession && navigator.mediaSession.metadata && navigator.mediaSession.metadata.title);
        // Spotify 切歌初期若 MediaSession 仍指向切歌前的旧歌名，继续冻结在缓冲态，避免旧歌进度串扰到新歌
        if (!voxSawTargetProgress && voxStalePrevTitle && msNowTitle && msNowTitle === voxStalePrevTitle && (Date.now() - voxSwitchStartedAt) < 7000) {
          emitHostSnapshot({
            playSeq: voxPlaySeq,
            switching: true,
            playing: false,
            buffering: true,
            posMs: voxStartPosMs,
            durMs: 0,
            ended: false
          });
          return;
        }
        var m = bestMedia();
        if (m) {
          if (!m.__voxEndedHooked) {
            m.__voxEndedHooked = true;
            m.addEventListener('ended', function() {
              if (voxSawTargetProgress) pushSnapshot(true);
            });
          }
          var mReady = m.readyState >= 3 && !m.seeking;
          var mPlaying = !m.paused && !m.ended && mReady;
          var mBuffering = (!m.paused && !mReady) || !!m.seeking;
          var mPos = !isNaN(m.currentTime) ? Math.max(0, Math.round(m.currentTime * 1000)) : 0;
          var mDur = (!isNaN(m.duration) && isFinite(m.duration)) ? Math.max(0, Math.round(m.duration * 1000)) : 0;
          var curSrc = m.currentSrc || m.src || '';
          var sameStale = !!(m.__voxStaleSrc && curSrc === m.__voxStaleSrc &&
                             Math.abs((m.currentTime || 0) - (m.__voxStaleTime || 0)) < 0.08);
          if (!voxSawTargetProgress) {
            if (mPlaying && !sameStale && (voxStartPosMs > 0 || mPos < 2500 || (Date.now() - voxSwitchStartedAt) > 5000)) {
              voxSawTargetProgress = true;
            } else {
              emitHostSnapshot({
                playSeq: voxPlaySeq,
                switching: false,
                playing: false,
                buffering: true,
                posMs: voxStartPosMs,
                durMs: 0,
                ended: false
              });
              return;
            }
          } else if (m.ended) {
            ended = true;
          }
          playing = mPlaying && !ended;
          buffering = mBuffering;
          posMs = mPos;
          durMs = mDur;
        } else if (!voxSawTargetProgress) {
          emitHostSnapshot({
            playSeq: voxPlaySeq,
            switching: false,
            playing: false,
            buffering: true,
            posMs: voxStartPosMs,
            durMs: 0,
            ended: false
          });
          return;
        }
      }

      var ms = navigator.mediaSession && navigator.mediaSession.metadata;
      if (ms && voxSawTargetProgress) {
        if (!title) title = ms.title || '';
        if (!artist) artist = ms.artist || '';
        if (!album) album = ms.album || '';
        if (!coverUrl && ms.artwork && ms.artwork.length) {
          coverUrl = ms.artwork[ms.artwork.length - 1].src || '';
        }
      }
      emitHostSnapshot({
        playSeq: voxPlaySeq,
        switching: false,
        playing: playing,
        buffering: buffering,
        posMs: posMs,
        durMs: durMs,
        title: title,
        artist: artist,
        album: album,
        coverUrl: coverUrl,
        ended: ended
      });
    } catch (e) {}
  }

  setInterval(function() { pushSnapshot(false); }, 250);

  // 若上一步是通过整页跳转直达 /track/<id>，新页面加载完毕后自动接续执行精确点播
  if (isSpotify) {
    (async function resumePendingSpotifyNavPlay() {
      try {
        var raw = sessionStorage.getItem('__voxPendingSpotifyPlay');
        if (!raw) return;
        sessionStorage.removeItem('__voxPendingSpotifyPlay');
        var p = JSON.parse(raw);
        if (!p || !p.trackId || (Date.now() - (p.ts || 0)) > 20000) return;
        if (location.pathname.indexOf('/track/' + p.trackId) !== 0) return;
        if (p.playSeq && p.playSeq > voxPlaySeq) {
          markTrackSwitching(p.playSeq, p.trackId, p.startPosMs || 0, p.title || '');
        }
        voxSwitching = false;
        voxSwitchStartedAt = Date.now();
        var reqSeq = p.playSeq || voxPlaySeq;
        var tok = await ensureSpotifyToken(3000);
        if (isPlayCancelled(reqSeq)) return;
        if (tok) {
          var devId = await waitForSpotifyWebDevice(tok, reqSeq, 1800);
          if (isPlayCancelled(reqSeq)) return;
          if (devId) {
            if (await trySpotifySpClientPlay(tok, devId, p.trackId, p.startPosMs || 0, reqSeq)) return;
            if (await trySpotifyWebApiPlay(tok, devId, p.trackId, p.startPosMs || 0, reqSeq)) return;
          }
        }
        await clickVerifiedSpotifyTrackPlayBtn(p.trackId, p.title || '', reqSeq, 6000);
      } catch (e) {}
    })();
  }

  window.__voxBridge = {
    beginSwitch: function(seq, trackId, startPosMs, title) {
      markTrackSwitching(seq, trackId, startPosMs, title);
      pushSnapshot(false);
    },
    resume: function() {
      if (isApple && window.MusicKit) {
        var mk = runSafe(function(){ return window.MusicKit.getInstance(); });
        if (mk) { mk.play(); return; }
      }
      if (window.__voxActionHandlers && window.__voxActionHandlers.play) {
        window.__voxActionHandlers.play();
        return;
      }
      var m = bestMedia();
      if (m) m.play();
    },
    pause: function() {
      if (isApple && window.MusicKit) {
        var mk = runSafe(function(){ return window.MusicKit.getInstance(); });
        if (mk) { mk.pause(); return; }
      }
      if (window.__voxActionHandlers && window.__voxActionHandlers.pause) {
        window.__voxActionHandlers.pause();
        return;
      }
      var m = bestMedia();
      if (m) m.pause();
    },
    seek: function(ms) {
      var sec = Math.max(0, (ms || 0) / 1000.0);
      if (isApple && window.MusicKit) {
        var mk = runSafe(function(){ return window.MusicKit.getInstance(); });
        if (mk && typeof mk.seekToTime === 'function') { mk.seekToTime(sec); return; }
      }
      if (window.__voxActionHandlers && window.__voxActionHandlers.seekto) {
        try { window.__voxActionHandlers.seekto({ seekTime: sec }); } catch(e) {}
      }
      var m = bestMedia();
      if (m) m.currentTime = sec;
    },
    setVolume: function(v) {
      var m = bestMedia();
      if (m) m.volume = Math.max(0, Math.min(1, v));
    },
    setSpeed: function(s) {
      var m = bestMedia();
      if (m) m.playbackRate = s;
    },
    invoke: async function(reqId, method, args) {
      var out = { ok: false, items: [] };
      try {
        if (method === 'playTrack') {
          var reqSeq = Number(args && args.playSeq) || (voxPlaySeq + 1);
          markTrackSwitching(reqSeq, args && args.trackId, args && args.startPositionMs, args && args.title);
          pushSnapshot(false);
          try {
            out = isApple ? await playOnApple(args) : await playOnSpotify(args);
          } finally {
            if (!isPlayCancelled(reqSeq)) {
              voxSwitching = false;
              voxSwitchStartedAt = Date.now();
            }
          }
          setTimeout(function(){ pushSnapshot(false); }, 150);
        } else if (isApple) {
          var mk = window.MusicKit && window.MusicKit.getInstance ? runSafe(function(){ return window.MusicKit.getInstance(); }) : null;
          var sf = await detectAppleStorefront(mk, args);
          if (method === 'loadTracks') {
            var d = await appleApi(mk, '/v1/me/library/songs', { platform: 'web', 'include[library-songs]': 'catalog', limit: 100 }, args);
            var tList = ((d && d.data) || []).map(mapAppleResource).filter(Boolean);
            if (!tList.length) {
              var rp = await appleApi(mk, '/v1/me/recent/played/tracks', { platform: 'web', limit: 40 }, args);
              tList = ((rp && rp.data) || []).map(mapAppleResource).filter(Boolean);
            }
            if (!tList.length) {
              var cs = await appleApi(mk, '/v1/catalog/' + sf + '/charts', { types: 'songs', limit: 40 }, args);
              var sChart = cs && cs.results && cs.results.songs && cs.results.songs[0] && cs.results.songs[0].data;
              tList = (sChart || []).map(mapAppleResource).filter(Boolean);
            }
            out = { ok: true, items: dedupeAppleItems(tList) };
          } else if (method === 'loadAlbums') {
            var da = await appleApi(mk, '/v1/me/library/albums', { platform: 'web', include: 'catalog', limit: 100 }, args);
            var aList = ((da && da.data) || []).map(mapAppleResource).filter(Boolean);
            if (!aList.length) {
              var hr = await appleApi(mk, '/v1/me/history/heavy-rotation', { platform: 'web', limit: 25 }, args);
              aList = ((hr && hr.data) || []).map(mapAppleResource).filter(function(x) { return x && x.type === 'album'; });
            }
            if (!aList.length) {
              var recA = await appleApi(mk, '/v1/me/recommendations', { platform: 'web', limit: 15 }, args);
              aList = extractAppleRecommendations(recA, 'album');
            }
            if (!aList.length) {
              var caChart = await appleApi(mk, '/v1/catalog/' + sf + '/charts', { types: 'albums', limit: 30 }, args);
              var cAlbums = caChart && caChart.results && caChart.results.albums && caChart.results.albums[0] && caChart.results.albums[0].data;
              aList = (cAlbums || []).map(mapAppleResource).filter(Boolean);
            }
            out = { ok: true, items: dedupeAppleItems(aList) };
          } else if (method === 'loadPlaylists') {
            var dp = await appleApi(mk, '/v1/me/library/playlists', { platform: 'web', include: 'catalog', limit: 100 }, args);
            var df = await appleApi(mk, '/v1/me/library/playlist-folders/p.playlistsroot/children', { platform: 'web', include: 'catalog', limit: 100 }, args);
            var dm = await appleApi(mk, '/v1/me/library/playlists', { platform: 'web', 'filter[featured]': 'made-for-you', include: 'catalog', limit: 25 }, args);
            var pList = []
              .concat(((dp && dp.data) || []).map(mapAppleResource))
              .concat(((df && df.data) || []).map(mapAppleResource))
              .concat(((dm && dm.data) || []).map(mapAppleResource))
              .filter(Boolean);
            pList = dedupeAppleItems(pList);
            if (!pList.length) {
              var recP = await appleApi(mk, '/v1/me/recommendations', { platform: 'web', limit: 15 }, args);
              pList = extractAppleRecommendations(recP, 'playlist');
            }
            if (!pList.length) {
              var cpChart = await appleApi(mk, '/v1/catalog/' + sf + '/charts', { types: 'playlists', limit: 30 }, args);
              var cPlaylists = cpChart && cpChart.results && cpChart.results.playlists && cpChart.results.playlists[0] && cpChart.results.playlists[0].data;
              pList = (cPlaylists || []).map(mapAppleResource).filter(Boolean);
            }
            out = { ok: true, items: dedupeAppleItems(pList) };
          } else if (method === 'loadChildren') {
            var parts = String(args.ratingKey || '').split('|');
            var rType = parts[0] || '';
            var rId = parts[1] || parts[0] || '';
            var cItems = [];
            if (rType.indexOf('library-playlist-folders') >= 0) {
              var fch = await appleApi(mk, '/v1/me/library/playlist-folders/' + rId + '/children', { platform: 'web', include: 'catalog', limit: 100 }, args);
              cItems = ((fch && fch.data) || []).map(mapAppleResource).filter(Boolean);
            } else if (rType.indexOf('library-playlists') >= 0) {
              var lpt = await appleApi(mk, '/v1/me/library/playlists/' + rId + '/tracks', { platform: 'web', 'include[library-songs]': 'catalog', limit: 100 }, args);
              cItems = ((lpt && lpt.data) || []).map(mapAppleResource).filter(Boolean);
            } else if (rType.indexOf('library-albums') >= 0) {
              var lat = await appleApi(mk, '/v1/me/library/albums/' + rId + '/tracks', { platform: 'web', 'include[library-songs]': 'catalog', limit: 100 }, args);
              cItems = ((lat && lat.data) || []).map(mapAppleResource).filter(Boolean);
            } else if (rType.indexOf('playlist') >= 0) {
              var cpt = await appleApi(mk, '/v1/catalog/' + sf + '/playlists/' + rId + '/tracks', { limit: 100 }, args);
              cItems = ((cpt && cpt.data) || []).map(mapAppleResource).filter(Boolean);
              if (!cItems.length) {
                var cpFull = await appleApi(mk, '/v1/catalog/' + sf + '/playlists/' + rId, { include: 'tracks' }, args);
                var relT = cpFull && cpFull.data && cpFull.data[0] && cpFull.data[0].relationships && cpFull.data[0].relationships.tracks && cpFull.data[0].relationships.tracks.data;
                cItems = (relT || []).map(mapAppleResource).filter(Boolean);
              }
            } else {
              var cat = await appleApi(mk, '/v1/catalog/' + sf + '/albums/' + rId + '/tracks', { limit: 100 }, args);
              cItems = ((cat && cat.data) || []).map(mapAppleResource).filter(Boolean);
              if (!cItems.length) {
                var caFull = await appleApi(mk, '/v1/catalog/' + sf + '/albums/' + rId, { include: 'tracks' }, args);
                var relAT = caFull && caFull.data && caFull.data[0] && caFull.data[0].relationships && caFull.data[0].relationships.tracks && caFull.data[0].relationships.tracks.data;
                cItems = (relAT || []).map(mapAppleResource).filter(Boolean);
              }
            }
            out = { ok: true, items: cItems };
          } else if (method === 'search') {
            var ds = await appleApi(mk, '/v1/catalog/' + sf + '/search', { term: args.query, types: 'songs,albums,playlists', limit: 25 }, args);
            var res = (ds && ds.results) || {};
            var merged = []
              .concat(((res.songs && res.songs.data) || []).map(mapAppleResource))
              .concat(((res.albums && res.albums.data) || []).map(mapAppleResource))
              .concat(((res.playlists && res.playlists.data) || []).map(mapAppleResource))
              .filter(Boolean);
            out = { ok: true, items: merged };
          }
        } else if (isSpotify) {
          if (method === 'loadTracks') {
            var sTracks = await loadSpotifyTracksSmart();
            out = { ok: true, items: sTracks };
          } else if (method === 'loadAlbums') {
            var libA = await getSpotifyLibraryV3('Albums');
            var aItems = collectDeepEntities(libA, 'album', 60);
            if (!aItems.length) {
              var sa = await spotifyApi('/me/albums?limit=50');
              aItems = (((sa && sa.items) || []).map(function(x) {
                var al = x && x.album;
                if (!al) return null;
                var artists = (al.artists || []).map(function(a){ return a.name; }).join(' / ');
                return {
                  id: 'album|' + al.id,
                  type: 'album',
                  title: al.name || '',
                  artist: artists,
                  cover: (al.images && al.images[0] && al.images[0].url) || '',
                  count: al.total_tracks || 0,
                  year: al.release_date ? parseInt(String(al.release_date).substring(0, 4), 10) : 0
                };
              })).filter(Boolean);
            }
            if (!aItems.length && window.__voxPathfinderCache && window.__voxPathfinderCache.home) {
              aItems = collectDeepEntities(window.__voxPathfinderCache.home, 'album', 30);
            }
            out = { ok: true, items: aItems };
          } else if (method === 'loadPlaylists') {
            var libP = await getSpotifyLibraryV3('Playlists');
            var pItems = collectDeepEntities(libP, 'playlist', 60);
            if (!pItems.length) {
              var sp = await spotifyApi('/me/playlists?limit=50');
              pItems = (((sp && sp.items) || []).map(function(pl) {
                if (!pl || !pl.id) return null;
                return {
                  id: 'playlist|' + pl.id,
                  type: 'playlist',
                  title: pl.name || '歌单',
                  summary: String(pl.description || '').replace(/<[^>]+>/g, ''),
                  cover: (pl.images && pl.images[0] && pl.images[0].url) || '',
                  count: (pl.tracks && pl.tracks.total) || 0
                };
              })).filter(Boolean);
            }
            if (!pItems.length && window.__voxPathfinderCache && window.__voxPathfinderCache.home) {
              pItems = collectDeepEntities(window.__voxPathfinderCache.home, 'playlist', 30);
            }
            out = { ok: true, items: pItems };
          } else if (method === 'loadChildren') {
            var spParts = String(args.ratingKey || '').split('|');
            var kind = spParts[0];
            var cid = spParts[1] || spParts[0];
            if (kind === 'playlist') {
              out = { ok: true, items: await loadSpotifyPlaylistTracks(cid) };
            } else {
              out = { ok: true, items: await loadSpotifyAlbumTracks(cid) };
            }
          } else if (method === 'search') {
            out = { ok: true, items: await searchSpotifySmart(args.query || '') };
          }
        }
      } catch (err) {
        out = { ok: false, error: String(err && err.message ? err.message : err) };
      }
      if (window.VoxMusicHost && window.VoxMusicHost.onRpcResult) {
        window.VoxMusicHost.onRpcResult(reqId, JSON.stringify(out));
      }
    }
  };
})();
"""
}

/**
 * Apple Music / Spotify 应用内账号登录与高音质配置全屏弹窗：
 * - Spotify 同时提供「方案 3 高音质联动（Client ID + PKCE + 官方客户端发声）」与「网页直连（免配置 / 自动兜底）」双通道；
 * - 支持多窗口 `window.open` 认证子窗口（保留 `window.opener` 以支持 Apple ID 双重认证回调）；
 * - 实时自动检测登录与 PKCE 授权回调状态，完成后自动同步会话并回调 [onLoggedIn]。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebMusicLoginDialog(
    service: WebMusicService,
    onDismiss: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val remoteStatus by SpotifyAppRemoteBridge.status.collectAsState()
    val initialRemoteStatus = remember(service) {
        if (service == WebMusicService.SPOTIFY) SpotifyAppRemoteBridge.refreshStatus(context) else SpotifyAppRemoteBridge.Status()
    }
    val wasWebLoggedOnOpen = remember(service) {
        service == WebMusicService.SPOTIFY && WebStreamingMusic.isWebSpotifyLoggedIn(context)
    }

    // Spotify：0 = 方案3高音质联动 (Client ID)，1 = 网页直连 (免配置 / 自动兜底)
    var spotifyTab by remember(service) {
        mutableIntStateOf(
            if (service == WebMusicService.SPOTIFY && (initialRemoteStatus.isConfigured || initialRemoteStatus.spotifyAppInstalled)) 0 else 1,
        )
    }
    var pkceWebFlowActive by remember { mutableStateOf(false) }
    var pendingWebUrl by remember { mutableStateOf<String?>(null) }
    var clientIdInput by remember(service) { mutableStateOf(initialRemoteStatus.clientId) }
    var busyConnecting by remember { mutableStateOf(false) }
    var configFeedback by remember { mutableStateOf<String?>(null) }
    var configFeedbackIsError by remember { mutableStateOf(false) }
    val appPackageName = remember { context.packageName }
    val appSha1 = remember {
        if (service == WebMusicService.SPOTIFY) SpotifyAppRemoteBridge.getAppSigningSha1(context) else ""
    }

    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf("正在加载 ${service.label} 登录页…") }
    var mainWebView by remember { mutableStateOf<WebView?>(null) }
    var popupContainer by remember { mutableStateOf<FrameLayout?>(null) }
    var hasPopup by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }

    val finishLogin = {
        if (!completed) {
            completed = true
            runCatching { CookieManager.getInstance().flush() }
            WebStreamingMusic.markLoggedIn(context, service, true)
            onLoggedIn()
        }
    }

    val handlePkceCallbackUri: (Uri) -> Unit = { cbUri ->
        uiScope.launch {
            busyConnecting = true
            configFeedback = "正在验证 PKCE 授权令牌…"
            configFeedbackIsError = false
            val res = SpotifyAppRemoteBridge.completeOAuthCallbackSuspend(context, cbUri)
            res.onSuccess {
                if (SpotifyAppRemoteBridge.isSpotifyAppInstalled(context)) {
                    SpotifyAppRemoteBridge.testConnectInteractive(context, clientIdInput)
                }
                busyConnecting = false
                pkceWebFlowActive = false
                Toast.makeText(context, "Spotify 高音质联动已授权启用", Toast.LENGTH_SHORT).show()
                finishLogin()
            }.onFailure { err ->
                busyConnecting = false
                pkceWebFlowActive = false
                spotifyTab = 0
                configFeedback = err.message ?: "PKCE 授权失败"
                configFeedbackIsError = true
            }
        }
    }

    val copyToClipboard: (String, String) -> Unit = { label, value ->
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, "已复制 $label", Toast.LENGTH_SHORT).show()
    }

    // 当切换到 WebView 或触发 PKCE 授权链接时，加载目标地址
    LaunchedEffect(pendingWebUrl, mainWebView) {
        val url = pendingWebUrl
        val wv = mainWebView
        if (!url.isNullOrBlank() && wv != null) {
            pendingWebUrl = null
            wv.loadUrl(url)
        }
    }

    // 轮询检测网页 Cookie / MusicKit 授权状态，登录成功后完成会话落盘并回调
    LaunchedEffect(service, mainWebView, spotifyTab, pkceWebFlowActive) {
        if (service == WebMusicService.SPOTIFY && (spotifyTab == 0 || pkceWebFlowActive)) {
            return@LaunchedEffect
        }
        var redirectedSpotifyHome = false
        while (!completed) {
            delay(900L)
            val cm = runCatching { CookieManager.getInstance() }.getOrNull()
            if (service == WebMusicService.SPOTIFY) {
                if (wasWebLoggedOnOpen) continue
                val openCookies = cm?.getCookie("https://open.spotify.com").orEmpty()
                val acctCookies = cm?.getCookie("https://accounts.spotify.com").orEmpty()
                val hasSpDc = openCookies.contains("sp_dc=") || acctCookies.contains("sp_dc=")
                if (hasSpDc) {
                    val wv = mainWebView
                    val curUrl = wv?.url.orEmpty()
                    if (wv != null && !curUrl.contains("open.spotify.com") && !redirectedSpotifyHome) {
                        redirectedSpotifyHome = true
                        wv.loadUrl("https://open.spotify.com/")
                        delay(1_400L)
                    } else {
                        delay(600L)
                    }
                    runCatching { cm?.flush() }
                    finishLogin()
                    break
                }
            } else {
                val cookies = cm?.getCookie("https://music.apple.com").orEmpty()
                if (cookies.contains("media-user-token=") || cookies.contains("music-user-token=")) {
                    finishLogin()
                    break
                }
                val wv = mainWebView
                if (wv != null) {
                    val jsAuthorized = suspendCancellableCoroutine<Boolean> { cont ->
                        wv.evaluateJavascript(
                            """
                            (function() {
                              try {
                                var mk = window.MusicKit && window.MusicKit.getInstance ? window.MusicKit.getInstance() : null;
                                if (mk && (mk.isAuthorized || (mk.musicUserToken && mk.musicUserToken.length > 20))) return true;
                                for (var i = 0; i < localStorage.length; i++) {
                                  var k = localStorage.key(i) || '';
                                  var v = localStorage.getItem(k) || '';
                                  if ((/user.?token/i.test(k) && v.length > 25) || (k.indexOf('music.') === 0 && k.indexOf('userToken') >= 0 && v.length > 25)) return true;
                                }
                                return false;
                              } catch (e) { return false; }
                            })()
                            """.trimIndent(),
                        ) { res ->
                            if (cont.isActive) cont.resume(res?.trim() == "true")
                        }
                    }
                    if (jsAuthorized) {
                        finishLogin()
                        break
                    }
                }
            }
        }
    }

    val showingWebView = service != WebMusicService.SPOTIFY || spotifyTab == 1 || pkceWebFlowActive

    BackHandler {
        if (pkceWebFlowActive) {
            pkceWebFlowActive = false
            spotifyTab = 0
            return@BackHandler
        }
        val container = popupContainer
        if (showingWebView && container != null && container.childCount > 1) {
            val topChild = container.getChildAt(container.childCount - 1) as? WebView
            if (topChild?.canGoBack() == true) {
                topChild.goBack()
            } else {
                container.removeViewAt(container.childCount - 1)
                topChild?.destroy()
                hasPopup = container.childCount > 1
            }
        } else if (showingWebView && mainWebView?.canGoBack() == true) {
            mainWebView?.goBack()
        } else {
            onDismiss()
        }
    }

    DisposableEffect(service) {
        if (service == WebMusicService.SPOTIFY) {
            SpotifyAppRemoteBridge.onOAuthCompleted = { finishLogin() }
        }
        onDispose {
            if (service == WebMusicService.SPOTIFY) {
                SpotifyAppRemoteBridge.onOAuthCompleted = null
            }
            runCatching { CookieManager.getInstance().flush() }
            popupContainer?.let { root ->
                for (i in root.childCount - 1 downTo 0) {
                    (root.getChildAt(i) as? WebView)?.destroy()
                }
                root.removeAllViews()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (pkceWebFlowActive) {
                                pkceWebFlowActive = false
                                spotifyTab = 0
                            } else {
                                onDismiss()
                            }
                        },
                    ) {
                        Icon(AppIcons.Close, contentDescription = "关闭")
                    }
                    WebMusicServiceIcon(service = service, size = 24.dp)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (service == WebMusicService.SPOTIFY) "Spotify 登录与高音质设置" else "登录 ${service.label}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = when {
                                pkceWebFlowActive -> "请在下方点击同意授权以启用官方客户端高音质联动"
                                !showingWebView -> "优先通过本机官方 Spotify 发声，不可用时自动回退网页播放"
                                hasPopup -> "请在安全认证窗口完成验证"
                                else -> pageTitle
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (showingWebView) {
                        IconButton(onClick = { mainWebView?.reload() }) {
                            Icon(AppIcons.Refresh, contentDescription = "刷新")
                        }
                        if (!pkceWebFlowActive) {
                            Button(
                                onClick = { finishLogin() },
                                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text("我已登录", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                if (service == WebMusicService.SPOTIFY && !pkceWebFlowActive) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val spotifyGreen = Color(0xFF1ED760)
                        listOf(
                            "客户端联动 (高音质)",
                            "网页直连 (免配置)",
                        ).forEachIndexed { idx, label ->
                            val selected = spotifyTab == idx
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) spotifyGreen.copy(alpha = 0.22f) else Color.Transparent)
                                    .clickable {
                                        spotifyTab = idx
                                        if (idx == 1 && mainWebView?.url.isNullOrBlank()) {
                                            pendingWebUrl = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
                                        }
                                    }
                                    .padding(vertical = 8.dp, horizontal = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) spotifyGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                if (showingWebView && progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = Color(service.accentHex),
                    )
                }

                if (!showingWebView) {
                    val webFallbackReady = remember(spotifyTab, completed) {
                        WebStreamingMusic.isWebSpotifyLoggedIn(context)
                    }
                    val spotifyGreen = Color(0xFF1ED760)
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 1. 当前状态与自动回退说明
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .padding(13.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "官方客户端联动（极高 / 无损音质）",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "通过你自己的 Spotify Client ID 遥控手机已安装的官方 Spotify 客户端在后台解码发声，完整享受 320 kbps 极高音质或 24-bit/44.1kHz FLAC 无损音质，同时保留本应用黑胶唱机与双语滚动歌词。当官方客户端未安装、未登录或不可用时，会自动回退至右侧网页直连播放。",
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(2.dp))
                            SpotifyStatusLine(
                                label = "本机官方客户端",
                                value = if (remoteStatus.spotifyAppInstalled) "已安装 (com.spotify.music)" else "未检测到安装（将自动回退网页播放）",
                                ok = remoteStatus.spotifyAppInstalled,
                            )
                            SpotifyStatusLine(
                                label = "PKCE 曲库授权",
                                value = if (remoteStatus.hasOAuthToken) "已授权（原生 API 秒级读取曲库）" else "待授权",
                                ok = remoteStatus.hasOAuthToken,
                            )
                            SpotifyStatusLine(
                                label = "网页自动兜底",
                                value = if (webFallbackReady) "已就绪（客户端联动不可用时自动接管）" else "未登录（可切至右侧「网页直连」登录备用）",
                                ok = webFallbackReady,
                            )
                            remoteStatus.lastFallbackReason?.takeIf { it.isNotBlank() }?.let { reason ->
                                Text(
                                    text = "上次自动回退原因：$reason",
                                    fontSize = 11.sp,
                                    color = Orange,
                                )
                            }
                        }

                        // 2. Client ID 输入框
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Spotify Client ID",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp),
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        val pasted = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                                        if (pasted.isNotEmpty()) {
                                            clientIdInput = pasted
                                            configFeedback = null
                                        } else {
                                            Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                ) {
                                    Text("粘贴", fontSize = 12.sp, color = Orange)
                                }
                                if (clientIdInput.isNotEmpty()) {
                                    TextButton(
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(28.dp),
                                        onClick = {
                                            clientIdInput = ""
                                            SpotifyAppRemoteBridge.saveClientId(context, "")
                                            configFeedback = "已清空 Client ID，当前使用网页播放方案"
                                            configFeedbackIsError = false
                                        },
                                    ) {
                                        Text("清空", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = clientIdInput,
                                onValueChange = {
                                    clientIdInput = it.trim()
                                    configFeedback = null
                                },
                                placeholder = { Text("粘贴 32 位 Spotify Client ID", fontSize = 13.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )
                        }

                        if (!configFeedback.isNullOrBlank()) {
                            Text(
                                text = configFeedback.orEmpty(),
                                fontSize = 12.sp,
                                color = if (configFeedbackIsError) MaterialTheme.colorScheme.error else spotifyGreen,
                            )
                        }

                        // 3. 操作按钮
                        Button(
                            enabled = !busyConnecting,
                            onClick = {
                                val cid = clientIdInput.trim()
                                if (cid.length < 16) {
                                    configFeedback = "请先填入有效的 Spotify Client ID"
                                    configFeedbackIsError = true
                                    return@Button
                                }
                                SpotifyAppRemoteBridge.saveClientId(context, cid)
                                val authUrl = SpotifyAppRemoteBridge.buildPkceAuthorizeUrl(context, cid)
                                if (authUrl != null) {
                                    configFeedback = null
                                    pkceWebFlowActive = true
                                    pendingWebUrl = authUrl
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen, contentColor = Color.Black),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                        ) {
                            Text(
                                text = if (busyConnecting) "正在连接授权…" else "保存并授权高音质模式（推荐）",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        if (remoteStatus.spotifyAppInstalled) {
                            OutlinedButton(
                                enabled = !busyConnecting,
                                onClick = {
                                    val cid = clientIdInput.trim()
                                    if (cid.length < 16) {
                                        configFeedback = "请先填入有效的 Spotify Client ID"
                                        configFeedbackIsError = true
                                        return@OutlinedButton
                                    }
                                    uiScope.launch {
                                        busyConnecting = true
                                        configFeedback = "正在唤醒并连接本机官方 Spotify 客户端…"
                                        configFeedbackIsError = false
                                        val res = SpotifyAppRemoteBridge.testConnectInteractive(context, cid)
                                        busyConnecting = false
                                        res.onSuccess {
                                            Toast.makeText(context, "已成功连接本机 Spotify 客户端", Toast.LENGTH_SHORT).show()
                                            finishLogin()
                                        }.onFailure {
                                            val why = SpotifyAppRemoteBridge.status.value.lastFallbackReason ?: it.message ?: "连接失败"
                                            configFeedback = "连接未通过：$why（请核对下方包名与 SHA-1 指纹，并确保手机 Spotify 已登录账号）"
                                            configFeedbackIsError = true
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                            ) {
                                Text("仅保存并测试直连本机 Spotify 客户端", fontSize = 13.sp)
                            }
                        }

                        // 4. Spotify Developer 后台必填参数一键复制卡片
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                                .padding(13.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Spotify 开发者后台必填参数",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "打开开发者后台 ↗",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Orange,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.spotify.com/dashboard"))
                                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                                )
                                            }
                                        }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                            Text(
                                text = "在 developer.spotify.com/dashboard 创建应用 → 进入 Settings：填入下方 Redirect URI，并在 Android Packages 中填入包名与 SHA-1 指纹后点击 Save。",
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SpotifyCopyParamRow(
                                title = "Redirect URI",
                                value = SpotifyAppRemoteBridge.REDIRECT_URI,
                                onCopy = { copyToClipboard("Redirect URI", SpotifyAppRemoteBridge.REDIRECT_URI) },
                            )
                            SpotifyCopyParamRow(
                                title = "Package Name (Android 包名)",
                                value = appPackageName,
                                onCopy = { copyToClipboard("Android 包名", appPackageName) },
                            )
                            SpotifyCopyParamRow(
                                title = "SHA-1 签名指纹",
                                value = appSha1.ifBlank { "未读取到签名指纹" },
                                onCopy = {
                                    if (appSha1.isNotBlank()) copyToClipboard("SHA-1 指纹", appSha1)
                                },
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                val container = FrameLayout(ctx)
                                val wv = WebView(ctx).apply {
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                    WebStreamingMusic.configureDrmWebView(this, allowPopups = true)
                                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                        WebViewCompat.addDocumentStartJavaScript(this, WebStreamingMusic.DOCUMENT_START_SCRIPT, setOf("*"))
                                    }
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            if (url != null && SpotifyAppRemoteBridge.isCallbackUri(Uri.parse(url))) {
                                                handlePkceCallbackUri(Uri.parse(url))
                                                return
                                            }
                                            view.evaluateJavascript(WebStreamingMusic.DOCUMENT_START_SCRIPT, null)
                                        }

                                        override fun onPageFinished(view: WebView, url: String?) {
                                            super.onPageFinished(view, url)
                                            CookieManager.getInstance().flush()
                                        }

                                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                            if (SpotifyAppRemoteBridge.isCallbackUri(request.url)) {
                                                handlePkceCallbackUri(request.url)
                                                return true
                                            }
                                            val scheme = request.url.scheme?.lowercase().orEmpty()
                                            // 阻止跳出到外部官方客户端，强制留在内置页面完成网页登录或 PKCE 回调
                                            return scheme != "http" && scheme != "https"
                                        }
                                    }
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                                            progress = newProgress
                                        }

                                        override fun onReceivedTitle(view: WebView, title: String?) {
                                            if (!title.isNullOrBlank()) pageTitle = title
                                        }

                                        override fun onPermissionRequest(request: PermissionRequest) {
                                            val allowed = request.resources.filter {
                                                it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                                            }.toTypedArray()
                                            if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()
                                        }

                                        override fun onCreateWindow(
                                            view: WebView,
                                            isDialog: Boolean,
                                            isUserGesture: Boolean,
                                            resultMsg: Message,
                                        ): Boolean {
                                            val child = WebView(ctx).apply {
                                                layoutParams = FrameLayout.LayoutParams(
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                )
                                                WebStreamingMusic.configureDrmWebView(this, allowPopups = true)
                                                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                                    WebViewCompat.addDocumentStartJavaScript(this, WebStreamingMusic.DOCUMENT_START_SCRIPT, setOf("*"))
                                                }
                                                webViewClient = object : WebViewClient() {
                                                    override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                                                        if (SpotifyAppRemoteBridge.isCallbackUri(req.url)) {
                                                            handlePkceCallbackUri(req.url)
                                                            return true
                                                        }
                                                        val s = req.url.scheme?.lowercase().orEmpty()
                                                        return s != "http" && s != "https"
                                                    }

                                                    override fun onPageFinished(v: WebView, url: String?) {
                                                        CookieManager.getInstance().flush()
                                                    }
                                                }
                                                webChromeClient = object : WebChromeClient() {
                                                    override fun onCloseWindow(window: WebView) {
                                                        container.removeView(window)
                                                        window.destroy()
                                                        hasPopup = container.childCount > 1
                                                        CookieManager.getInstance().flush()
                                                    }
                                                }
                                            }
                                            container.addView(child)
                                            hasPopup = true
                                            val transport = resultMsg.obj as? WebView.WebViewTransport
                                            transport?.webView = child
                                            resultMsg.sendToTarget()
                                            return true
                                        }
                                    }
                                    val entryUrl = pendingWebUrl ?: when (service) {
                                        WebMusicService.SPOTIFY -> "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
                                        WebMusicService.APPLE_MUSIC -> "https://music.apple.com/"
                                    }
                                    pendingWebUrl = null
                                    loadUrl(entryUrl)
                                }
                                container.addView(wv)
                                mainWebView = wv
                                popupContainer = container
                                container
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifyStatusLine(
    label: String,
    value: String,
    ok: Boolean,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(if (ok) Color(0xFF1ED760) else Orange),
        )
        Text(
            text = "$label：",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontSize = 11.sp,
            color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SpotifyCopyParamRow(
    title: String,
    value: String,
    onCopy: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClick = onCopy)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "复制",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Orange,
        )
    }
}
