package com.aisub.player.music

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 安卓端 Apple Music / Spotify 应用内登录与后台 Widevine DRM 播放引擎：
 * - 登录态：通过桌面 Chrome 环境伪装 + 多窗口 OAuth 弹窗支持，完成 Apple ID（含双重认证）与 Spotify 账号登录并持久化 Cookie/LocalStorage；
 * - 播放态：维护 1×1 隐形保活 WebView（自动授予 EME `RESOURCE_PROTECTED_MEDIA_ID` Widevine 硬件解密权限），
 *   将实时播放状态、毫秒级进度、切歌控制与个人曲库桥接至应用原生「音乐」播放界面（MusicView）。
 */
object WebStreamingMusic {
    const val DESKTOP_CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val AUTH_PREFS = "web_music_auth"
    private val mainHandler = Handler(Looper.getMainLooper())

    data class PlaybackSnapshot(
        val service: WebMusicService,
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
    private var snapshotListener: ((PlaybackSnapshot) -> Unit)? = null

    val isSupportedOnPlatform: Boolean = true

    fun setSnapshotListener(listener: ((PlaybackSnapshot) -> Unit)?) {
        snapshotListener = listener
    }

    fun refreshAuthState(context: Context): Set<WebMusicService> {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        val cm = runCatching { CookieManager.getInstance() }.getOrNull()
        val active = HashSet<WebMusicService>()

        val spotifyCookies = runCatching { cm?.getCookie("https://open.spotify.com").orEmpty() }.getOrDefault("")
        if (spotifyCookies.contains("sp_dc=") || prefs.getBoolean("logged_SPOTIFY", false) && spotifyCookies.isNotBlank()) {
            active += WebMusicService.SPOTIFY
            prefs.edit().putBoolean("logged_SPOTIFY", true).apply()
        } else if (!spotifyCookies.contains("sp_dc=")) {
            prefs.edit().putBoolean("logged_SPOTIFY", false).apply()
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

    fun markLoggedIn(context: Context, service: WebMusicService, loggedIn: Boolean) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        val wasLogged = prefs.getBoolean("logged_${service.name}", false)
        prefs.edit().putBoolean("logged_${service.name}", loggedIn).apply()
        runCatching { CookieManager.getInstance().flush() }
        if (loggedIn && !wasLogged) {
            mainHandler.post { engines.remove(service)?.destroy() }
        }
        refreshAuthState(app)
    }

    fun logout(context: Context, service: WebMusicService) {
        val app = context.applicationContext
        markLoggedIn(app, service, false)
        mainHandler.post {
            engines.remove(service)?.destroy()
            val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return@post
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

    // ---------------- 个人曲库浏览与目录搜索（供「音乐 → 乐库」调用） ----------------

    suspend fun loadLibraryTracks(context: Context, service: WebMusicService): List<ServerItem> {
        val engine = ensureEngine(context, service)
        val raw = engine.callRpc("loadTracks", JSONObject(), timeoutMs = 15_000L)
        val parsed = parseServerItemsJson(service, raw)
        if (parsed.isNotEmpty()) return parsed
        if (service == WebMusicService.APPLE_MUSIC) {
            return withContext(Dispatchers.IO) {
                MusicDiscoverRepository.fetchAppleMostPlayed("cn", 40)
                    .ifEmpty { MusicDiscoverRepository.fetchAppleMostPlayed("tw", 40) }
                    .map { discoverToServerItem(service, it) }
            }
        }
        return emptyList()
    }

    suspend fun loadLibraryAlbums(context: Context, service: WebMusicService): List<ServerItem> {
        val engine = ensureEngine(context, service)
        val raw = engine.callRpc("loadAlbums", JSONObject(), timeoutMs = 15_000L)
        return parseServerItemsJson(service, raw)
    }

    suspend fun loadLibraryPlaylists(context: Context, service: WebMusicService): List<ServerItem> {
        val engine = ensureEngine(context, service)
        val raw = engine.callRpc("loadPlaylists", JSONObject(), timeoutMs = 15_000L)
        return parseServerItemsJson(service, raw)
    }

    suspend fun loadContainerChildren(context: Context, service: WebMusicService, container: ServerItem): List<ServerItem> {
        val engine = ensureEngine(context, service)
        val args = JSONObject()
            .put("ratingKey", container.ratingKey)
            .put("type", container.type)
        val raw = engine.callRpc("loadChildren", args, timeoutMs = 15_000L)
        return parseServerItemsJson(service, raw)
    }

    suspend fun searchMusic(context: Context, service: WebMusicService, query: String): List<ServerItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val engine = ensureEngine(context, service)
        val raw = engine.callRpc("search", JSONObject().put("query", q), timeoutMs = 12_000L)
        val parsed = parseServerItemsJson(service, raw)
        if (parsed.isNotEmpty()) return parsed
        if (service == WebMusicService.APPLE_MUSIC) {
            return withContext(Dispatchers.IO) {
                MusicDiscoverRepository.fetchItunesSearch(q, 25).map { discoverToServerItem(service, it) }
            }
        }
        return emptyList()
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

    suspend fun playUri(context: Context, uriString: String, startPositionMs: Long = 0L): Result<Unit> {
        val service = WebMusicService.fromUri(uriString)
            ?: return Result.failure(IllegalArgumentException("不支持的流媒体协议: $uriString"))
        if (!isLoggedIn(context, service)) {
            return Result.failure(IllegalStateException("请先登录 ${service.label} 账号后再播放"))
        }
        val parsed = Uri.parse(uriString)
        var trackId = if (parsed.host == "track") parsed.pathSegments.firstOrNull()?.let(::decodeParam).orEmpty() else ""
        val title = parsed.getQueryParameter("title")?.let(::decodeParam).orEmpty()
        val artist = parsed.getQueryParameter("artist")?.let(::decodeParam).orEmpty()
        val webUrl = parsed.getQueryParameter("url")?.let(::decodeParam).orEmpty()

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

        // Pause the other streaming engine if it was active
        WebMusicService.entries.filter { it != service }.forEach { other ->
            engines[other]?.pauseOnly()
        }

        val engine = ensureEngine(context, service)
        val args = JSONObject()
            .put("trackId", trackId)
            .put("title", title)
            .put("artist", artist)
            .put("webUrl", webUrl)
            .put("startPositionMs", startPositionMs.coerceAtLeast(0L))
        val resp = engine.callRpc("playTrack", args, timeoutMs = 22_000L)
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
        engines[service]?.evalFireAndForget("window.__voxBridge && window.__voxBridge.${if (play) "resume" else "pause"}()")
    }

    fun seekTo(service: WebMusicService, positionMs: Long) {
        engines[service]?.evalFireAndForget("window.__voxBridge && window.__voxBridge.seek(${positionMs.coerceAtLeast(0L)})")
    }

    fun setVolume(service: WebMusicService, volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        engines[service]?.evalFireAndForget("window.__voxBridge && window.__voxBridge.setVolume($v)")
    }

    fun setSpeed(service: WebMusicService, speed: Float) {
        val s = speed.coerceIn(0.5f, 2f)
        engines[service]?.evalFireAndForget("window.__voxBridge && window.__voxBridge.setSpeed($s)")
    }

    fun stopAll() {
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

    internal fun dispatchSnapshot(service: WebMusicService, rawJson: String) {
        val o = runCatching { JSONObject(rawJson) }.getOrNull() ?: return
        val snap = PlaybackSnapshot(
            service = service,
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
        snapshotListener?.invoke(snap)
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
            withTimeoutOrNull(12_000L) { readySignal.await() }
            withContext(Dispatchers.Main.immediate) {
                webView.evaluateJavascript(ENGINE_RUNTIME_SCRIPT, null)
            }
        }

        suspend fun callRpc(method: String, args: JSONObject, timeoutMs: Long): String? {
            if (method == "playTrack") keepAwakeForAudio = true
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
            databaseEnabled = true
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
     * 3. 捕获 Spotify Web Player 请求头中的实时 Bearer Token 与 Device ID。
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

  try {
    var origFetch = window.fetch;
    if (origFetch) {
      window.fetch = function(input, init) {
        try {
          var url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
          var hdrs = (init && init.headers) || (input && input.headers);
          var auth = null;
          if (hdrs) {
            if (typeof hdrs.get === 'function') auth = hdrs.get('Authorization') || hdrs.get('authorization');
            else auth = hdrs['Authorization'] || hdrs['authorization'];
          }
          if (auth && auth.indexOf('Bearer ') === 0 && /spotify\.com/i.test(url) && auth.length > 40) {
            window.__voxSpotifyToken = auth.substring(7).trim();
          }
          if (/track-playback\/v1\/devices|connect-state\/v1\/devices\/hobs_/i.test(url)) {
            var m = url.match(/hobs_[A-Za-z0-9]+/);
            if (m) window.__voxSpotifyDeviceId = m[0];
          }
        } catch (e) {}
        return origFetch.apply(this, arguments).then(function(resp) {
          try {
            var rUrl = resp && resp.url ? resp.url : '';
            if (/track-playback\/v1\/devices/i.test(rUrl)) {
              resp.clone().json().then(function(j) {
                if (j && j.device_id) window.__voxSpotifyDeviceId = j.device_id;
              }).catch(function(){});
            } else if (/get_access_token|api\/token/i.test(rUrl)) {
              resp.clone().json().then(function(j) {
                if (j && j.accessToken) window.__voxSpotifyToken = j.accessToken;
              }).catch(function(){});
            }
          } catch (e) {}
          return resp;
        });
      };
    }
  } catch (e) {}
})();
"""

    /**
     * 运行时控制与曲库读取桥接脚本（同时适配 Apple Music `MusicKit` 与 Spotify Web Player）。
     */
    private const val ENGINE_RUNTIME_SCRIPT = """
(function() {
  if (window.__voxBridge) return;
  var isApple = location.hostname.indexOf('apple.com') >= 0;
  var isSpotify = location.hostname.indexOf('spotify.com') >= 0;

  function sleep(ms) { return new Promise(function(r) { setTimeout(r, ms); }); }

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

  async function waitForMusicKit(maxMs) {
    var deadline = Date.now() + (maxMs || 8000);
    while (Date.now() < deadline) {
      var mk = window.MusicKit && window.MusicKit.getInstance ? runSafe(function(){ return window.MusicKit.getInstance(); }) : null;
      if (mk && (mk.developerToken || mk.api)) return mk;
      await sleep(250);
    }
    return window.MusicKit && window.MusicKit.getInstance ? runSafe(function(){ return window.MusicKit.getInstance(); }) : null;
  }

  function runSafe(fn) { try { return fn(); } catch (e) { return null; } }

  function formatAppleArtwork(art, size) {
    if (!art || !art.url) return '';
    return String(art.url).replace('{w}', String(size || 500)).replace('{h}', String(size || 500)).replace('{f}', 'jpg');
  }

  async function appleApi(mk, path, queryObj) {
    if (mk && mk.api && typeof mk.api.music === 'function') {
      var res = await mk.api.music(path, queryObj || {});
      return res && res.data ? res.data : res;
    }
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
    var headers = { 'Authorization': 'Bearer ' + (mk ? mk.developerToken : '') };
    if (mk && mk.musicUserToken) headers['Music-User-Token'] = mk.musicUserToken;
    var r = await fetch('https://amp-api.music.apple.com' + path + qs, { headers: headers });
    return await r.json();
  }

  function mapAppleResource(r) {
    if (!r || !r.attributes) return null;
    var a = r.attributes;
    var t = r.type || '';
    var itemType = 'track';
    if (t.indexOf('playlists') >= 0) itemType = 'playlist';
    else if (t.indexOf('albums') >= 0) itemType = 'album';
    else if (t.indexOf('artists') >= 0) itemType = 'artist';
    var playId = (a.playParams && (a.playParams.catalogId || a.playParams.id)) || r.id;
    var desc = '';
    if (a.description) desc = a.description.standard || a.description.short || '';
    else if (a.editorialNotes) desc = a.editorialNotes.standard || a.editorialNotes.short || '';
    var yr = a.releaseDate ? parseInt(String(a.releaseDate).substring(0, 4), 10) : 0;
    return {
      id: itemType === 'track' ? String(playId) : (r.type + '|' + r.id),
      type: itemType,
      title: a.name || '',
      artist: a.artistName || a.curatorName || '',
      album: a.albumName || '',
      summary: String(desc || '').replace(/<[^>]+>/g, ''),
      cover: formatAppleArtwork(a.artwork, 500),
      durationMs: a.durationInMillis || 0,
      count: a.trackCount || 0,
      year: yr > 1900 ? yr : 0,
      webUrl: a.url || ''
    };
  }

  async function ensureSpotifyToken() {
    if (window.__voxSpotifyToken) return window.__voxSpotifyToken;
    try {
      var r = await fetch('https://open.spotify.com/get_access_token?reason=transport&productType=web_player', { credentials: 'include' });
      if (r.ok) {
        var j = await r.json();
        if (j && j.accessToken) {
          window.__voxSpotifyToken = j.accessToken;
          return j.accessToken;
        }
      }
    } catch (e) {}
    for (var i = 0; i < 12 && !window.__voxSpotifyToken; i++) await sleep(300);
    return window.__voxSpotifyToken || '';
  }

  async function spotifyApi(path) {
    var tok = await ensureSpotifyToken();
    if (!tok) return null;
    var r = await fetch('https://api.spotify.com/v1' + path, {
      headers: { 'Authorization': 'Bearer ' + tok }
    });
    if (!r.ok) return null;
    return await r.json();
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

  async function playOnApple(args) {
    var mk = await waitForMusicKit(9000);
    if (!mk) return { ok: false, error: 'Apple Music 网页组件尚未就绪，请稍后重试' };
    if (!mk.isAuthorized && !mk.musicUserToken) {
      return { ok: false, error: 'Apple Music 登录状态已失效，请在音源设置中重新登录' };
    }
    var trackId = String(args.trackId || '').trim();
    var storefront = mk.storefrontId || 'cn';
    if (!trackId && args.title) {
      var q = (args.title + ' ' + (args.artist || '')).trim();
      var sData = await appleApi(mk, '/v1/catalog/' + storefront + '/search', { term: q, types: 'songs', limit: 5 });
      var songs = (((sData || {}).results || {}).songs || {}).data || [];
      if (songs.length) trackId = songs[0].id;
    }
    if (!trackId) return { ok: false, error: '未在 Apple Music 曲库中找到该歌曲' };
    var queueArg = trackId.indexOf('i.') === 0 ? { librarySong: trackId, startPlaying: true } : { song: trackId, startPlaying: true };
    await mk.setQueue(queueArg);
    await mk.play();
    if (args.startPositionMs > 1000 && typeof mk.seekToTime === 'function') {
      mk.seekToTime(args.startPositionMs / 1000.0);
    }
    return { ok: true };
  }

  async function playOnSpotify(args) {
    var trackId = String(args.trackId || '').trim();
    var tok = await ensureSpotifyToken();
    if (!trackId && args.title && tok) {
      var q = encodeURIComponent((args.title + ' ' + (args.artist || '')).trim());
      var sr = await spotifyApi('/search?type=track&limit=5&q=' + q);
      var items = (((sr || {}).tracks || {}).items) || [];
      if (items.length) trackId = items[0].id;
    }
    if (!trackId && args.webUrl) {
      var m = String(args.webUrl).match(/track\/([A-Za-z0-9]+)/);
      if (m) trackId = m[1];
    }
    if (!trackId) return { ok: false, error: '未在 Spotify 曲库中找到该歌曲' };

    // 优先通过 Spotify Web Playback Connect API 直接在当前 Web Player 设备起播（无需整页刷新）
    if (tok) {
      var devId = window.__voxSpotifyDeviceId || '';
      if (!devId) {
        var devs = await spotifyApi('/me/player/devices');
        var list = (devs && devs.devices) || [];
        var webDev = list.find(function(d){ return /web player/i.test(d.name || ''); }) || list[0];
        if (webDev && webDev.id) devId = webDev.id;
      }
      if (devId) {
        var playRes = await fetch('https://api.spotify.com/v1/me/player/play?device_id=' + encodeURIComponent(devId), {
          method: 'PUT',
          headers: { 'Authorization': 'Bearer ' + tok, 'Content-Type': 'application/json' },
          body: JSON.stringify({ uris: ['spotify:track:' + trackId], position_ms: args.startPositionMs || 0 })
        });
        if (playRes.ok || playRes.status === 204) return { ok: true };
      }
    }

    // 回退路径：SPA 路由跳转至单曲页并自动点击主播放按钮
    var targetPath = '/track/' + trackId;
    if (location.pathname !== targetPath) {
      window.history.pushState({}, '', targetPath);
      window.dispatchEvent(new PopStateEvent('popstate'));
    }
    var deadline = Date.now() + 9000;
    while (Date.now() < deadline) {
      await sleep(350);
      var btn = document.querySelector('main button[data-testid="play-button"]') ||
                document.querySelector('button[data-testid="play-button"]');
      if (btn) {
        btn.click();
        await sleep(300);
        return { ok: true };
      }
    }
    location.href = 'https://open.spotify.com/track/' + trackId;
    return { ok: true };
  }

  function pushSnapshot(endedFlag) {
    try {
      var playing = false, buffering = false, posMs = 0, durMs = 0;
      var title = '', artist = '', album = '', coverUrl = '';
      if (isApple && window.MusicKit && window.MusicKit.getInstance) {
        var mk = runSafe(function(){ return window.MusicKit.getInstance(); });
        if (mk) {
          playing = !!mk.isPlaying;
          posMs = Math.max(0, Math.round((mk.currentPlaybackTime || 0) * 1000));
          durMs = Math.max(0, Math.round((mk.currentPlaybackDuration || 0) * 1000));
          var np = mk.nowPlayingItem;
          if (np && np.attributes) {
            title = np.attributes.name || '';
            artist = np.attributes.artistName || '';
            album = np.attributes.albumName || '';
            coverUrl = formatAppleArtwork(np.attributes.artwork, 600);
          }
        }
      }
      var m = bestMedia();
      if (m) {
        if (!playing && !m.paused && m.readyState >= 2) playing = true;
        if (m.readyState > 0 && m.readyState < 3 && !m.paused) buffering = true;
        if (posMs <= 0 && !isNaN(m.currentTime)) posMs = Math.round(m.currentTime * 1000);
        if (durMs <= 0 && !isNaN(m.duration) && isFinite(m.duration)) durMs = Math.round(m.duration * 1000);
      }
      var ms = navigator.mediaSession && navigator.mediaSession.metadata;
      if (ms) {
        if (!title) title = ms.title || '';
        if (!artist) artist = ms.artist || '';
        if (!album) album = ms.album || '';
        if (!coverUrl && ms.artwork && ms.artwork.length) {
          coverUrl = ms.artwork[ms.artwork.length - 1].src || '';
        }
      }
      if (window.VoxMusicHost && window.VoxMusicHost.onSnapshot) {
        window.VoxMusicHost.onSnapshot(JSON.stringify({
          playing: playing,
          buffering: buffering,
          posMs: posMs,
          durMs: durMs,
          title: title,
          artist: artist,
          album: album,
          coverUrl: coverUrl,
          ended: !!endedFlag
        }));
      }
    } catch (e) {}
  }

  setInterval(function() { pushSnapshot(false); }, 300);

  window.__voxBridge = {
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
          out = isApple ? await playOnApple(args) : await playOnSpotify(args);
          setTimeout(function(){ pushSnapshot(false); }, 200);
        } else if (isApple) {
          var mk = await waitForMusicKit(8000);
          var sf = (mk && mk.storefrontId) || 'cn';
          if (method === 'loadTracks') {
            var d = await appleApi(mk, '/v1/me/library/songs', { limit: 100 });
            out = { ok: true, items: ((d && d.data) || []).map(mapAppleResource).filter(Boolean) };
          } else if (method === 'loadAlbums') {
            var da = await appleApi(mk, '/v1/me/library/albums', { limit: 80 });
            out = { ok: true, items: ((da && da.data) || []).map(mapAppleResource).filter(Boolean) };
          } else if (method === 'loadPlaylists') {
            var dp = await appleApi(mk, '/v1/me/library/playlists', { limit: 80 });
            out = { ok: true, items: ((dp && dp.data) || []).map(mapAppleResource).filter(Boolean) };
          } else if (method === 'loadChildren') {
            var parts = String(args.ratingKey || '').split('|');
            var rType = parts[0] || '';
            var rId = parts[1] || parts[0] || '';
            var path = rType.indexOf('library-playlists') >= 0 ? ('/v1/me/library/playlists/' + rId + '/tracks')
                     : rType.indexOf('library-albums') >= 0 ? ('/v1/me/library/albums/' + rId + '/tracks')
                     : rType.indexOf('playlists') >= 0 ? ('/v1/catalog/' + sf + '/playlists/' + rId + '/tracks')
                     : ('/v1/catalog/' + sf + '/albums/' + rId + '/tracks');
            var dc = await appleApi(mk, path, { limit: 100 });
            out = { ok: true, items: ((dc && dc.data) || []).map(mapAppleResource).filter(Boolean) };
          } else if (method === 'search') {
            var ds = await appleApi(mk, '/v1/catalog/' + sf + '/search', { term: args.query, types: 'songs,albums,playlists', limit: 25 });
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
            var st = await spotifyApi('/me/tracks?limit=50');
            var sItems = ((st && st.items) || []).map(function(x){ return mapSpotifyTrack(x.track); }).filter(Boolean);
            out = { ok: true, items: sItems };
          } else if (method === 'loadAlbums') {
            var sa = await spotifyApi('/me/albums?limit=50');
            var aItems = ((sa && sa.items) || []).map(function(x) {
              var al = x.album;
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
            }).filter(Boolean);
            out = { ok: true, items: aItems };
          } else if (method === 'loadPlaylists') {
            var sp = await spotifyApi('/me/playlists?limit=50');
            var pItems = ((sp && sp.items) || []).map(function(pl) {
              if (!pl || !pl.id) return null;
              return {
                id: 'playlist|' + pl.id,
                type: 'playlist',
                title: pl.name || '歌单',
                summary: String(pl.description || '').replace(/<[^>]+>/g, ''),
                cover: (pl.images && pl.images[0] && pl.images[0].url) || '',
                count: (pl.tracks && pl.tracks.total) || 0
              };
            }).filter(Boolean);
            out = { ok: true, items: pItems };
          } else if (method === 'loadChildren') {
            var spParts = String(args.ratingKey || '').split('|');
            var kind = spParts[0];
            var cid = spParts[1] || spParts[0];
            if (kind === 'playlist') {
              var ptr = await spotifyApi('/playlists/' + cid + '/tracks?limit=100');
              var listP = ((ptr && ptr.items) || []).map(function(x){ return mapSpotifyTrack(x && x.track); }).filter(Boolean);
              out = { ok: true, items: listP };
            } else {
              var albInfo = await spotifyApi('/albums/' + cid);
              var listA = (((albInfo && albInfo.tracks) || {}).items || []).map(function(t){ return mapSpotifyTrack(t, albInfo); }).filter(Boolean);
              out = { ok: true, items: listA };
            }
          } else if (method === 'search') {
            var sq = encodeURIComponent(args.query || '');
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
            out = { ok: true, items: sOut.filter(Boolean) };
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
 * Apple Music / Spotify 应用内账号登录全屏弹窗：
 * - 支持多窗口 `window.open` 认证子窗口（保留 `window.opener` 以支持 Apple ID 双重认证回调）；
 * - 实时自动检测登录完成状态，保存 Cookie 后自动关闭并回调 [onLoggedIn]。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebMusicLoginDialog(
    service: WebMusicService,
    onDismiss: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf("正在加载 ${service.label} 登录页…") }
    var mainWebView by remember { mutableStateOf<WebView?>(null) }
    var popupContainer by remember { mutableStateOf<FrameLayout?>(null) }
    var hasPopup by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }

    val finishLogin = {
        if (!completed) {
            completed = true
            WebStreamingMusic.markLoggedIn(context, service, true)
            onLoggedIn()
        }
    }

    // 轮询检测 Cookie / MusicKit 授权状态，登录成功后自动完成绑定
    LaunchedEffect(service, mainWebView) {
        while (!completed) {
            delay(1_000L)
            val cm = runCatching { CookieManager.getInstance() }.getOrNull()
            if (service == WebMusicService.SPOTIFY) {
                val cookies = cm?.getCookie("https://open.spotify.com").orEmpty()
                if (cookies.contains("sp_dc=")) {
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

    BackHandler {
        val container = popupContainer
        if (container != null && container.childCount > 1) {
            val topChild = container.getChildAt(container.childCount - 1) as? WebView
            if (topChild?.canGoBack() == true) {
                topChild.goBack()
            } else {
                container.removeViewAt(container.childCount - 1)
                topChild?.destroy()
                hasPopup = container.childCount > 1
            }
        } else if (mainWebView?.canGoBack() == true) {
            mainWebView?.goBack()
        } else {
            onDismiss()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
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
                    IconButton(onClick = onDismiss) {
                        Icon(AppIcons.Close, contentDescription = "关闭")
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(service.accentHex))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(service.badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "登录 ${service.label}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (hasPopup) "请在安全认证窗口完成验证" else pageTitle,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { mainWebView?.reload() }) {
                        Icon(AppIcons.Refresh, contentDescription = "刷新")
                    }
                    Button(
                        onClick = { finishLogin() },
                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Text("我已登录", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = Color(service.accentHex),
                    )
                }

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
                                        view.evaluateJavascript(WebStreamingMusic.DOCUMENT_START_SCRIPT, null)
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        super.onPageFinished(view, url)
                                        CookieManager.getInstance().flush()
                                    }

                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        val scheme = request.url.scheme?.lowercase().orEmpty()
                                        // 阻止跳出到外部官方客户端，强制留在内置页面完成网页登录
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
                                val entryUrl = when (service) {
                                    WebMusicService.SPOTIFY -> "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
                                    WebMusicService.APPLE_MUSIC -> "https://music.apple.com/"
                                }
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
