package com.voxmusic.app.music

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.widget.Toast
import com.voxmusic.app.net.AppProxy
import com.voxmusic.app.media.CatalogItem
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.ListItem
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Spotify 方案 3（高音质 / 无损联动模式）：
 * - 通过用户配置的 Spotify Developer `Client ID` + `SpotifyAppRemote` SDK 直接连接并遥控手机已安装的官方 Spotify 客户端发声，
 *   完整继承官方 Spotify 客户端内开启的「极高 (320 kbps Ogg)」或「无损 (Lossless 24-bit/44.1kHz FLAC)」音质档位；
 * - 同时支持标准 OAuth 2.0 Authorization Code + PKCE 授权与自动续期，原生高速读取个人红心歌曲、专辑、歌单及全曲库搜索；
 * - 当未配置 `Client ID`、未安装官方客户端、未登录或连接/授权异常时，由 [WebStreamingMusic] 自动无缝回退至内置网页 DRM 播放引擎。
 */
object SpotifyAppRemoteBridge {
    const val REDIRECT_URI = "voxmusic://spotify-callback"
    private const val PREFS = "spotify_app_remote_prefs"
    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_REMOTE_ENABLED = "remote_enabled"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at_ms"
    private const val KEY_PKCE_VERIFIER = "pkce_verifier"
    private const val KEY_PKCE_STATE = "pkce_state"

    private const val OAUTH_SCOPES =
        "app-remote-control user-library-read playlist-read-private playlist-read-collaborative user-read-recently-played user-top-read user-read-playback-state user-modify-playback-state"

    data class Status(
        val clientId: String = "",
        val remoteEnabled: Boolean = false,
        val hasOAuthToken: Boolean = false,
        val spotifyAppInstalled: Boolean = false,
        val remoteConnected: Boolean = false,
        val activeForCurrentTrack: Boolean = false,
        val lastFallbackReason: String? = null,
    ) {
        val isConfigured: Boolean get() = clientId.length >= 16
        val canAttemptRemotePlayback: Boolean get() = isConfigured && remoteEnabled && spotifyAppInstalled
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectMutex = Mutex()
    private val tokenMutex = Mutex()

    private val http by lazy {
        AppProxy.builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val remoteItemCache = java.util.concurrent.ConcurrentHashMap<String, ListItem>()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    private var activeRemote: SpotifyAppRemote? = null

    @Volatile
    private var connectedClientId: String = ""

    private var stateSubscription: Subscription<PlayerState>? = null
    private var tickerJob: Job? = null

    @Volatile
    private var latestPlayerState: PlayerState? = null

    @Volatile
    private var latestStateRealtimeMs: Long = 0L

    @Volatile
    private var targetTrackUri: String? = null

    @Volatile
    private var stalePreviousUri: String? = null

    @Volatile
    private var commandSentElapsedMs: Long = 0L

    @Volatile
    private var sawTargetTrackPlaying: Boolean = false

    @Volatile
    private var maxTargetPosMs: Long = 0L

    @Volatile
    private var endedDispatchedForTarget: Boolean = false

    @Volatile
    var onOAuthCompleted: (() -> Unit)? = null

    fun refreshStatus(context: Context): Status {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cid = prefs.getString(KEY_CLIENT_ID, null)?.trim().orEmpty()
        val hasTok = !prefs.getString(KEY_ACCESS_TOKEN, null).isNullOrBlank() ||
            !prefs.getString(KEY_REFRESH_TOKEN, null).isNullOrBlank()
        val enabled = prefs.getBoolean(KEY_REMOTE_ENABLED, cid.length >= 16)
        val installed = isSpotifyAppInstalled(app)
        val connected = activeRemote?.isConnected == true
        val cur = _status.value.copy(
            clientId = cid,
            remoteEnabled = enabled && cid.length >= 16,
            hasOAuthToken = hasTok,
            spotifyAppInstalled = installed,
            remoteConnected = connected,
        )
        _status.value = cur
        return cur
    }

    fun getClientId(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CLIENT_ID, null)?.trim().orEmpty()

    fun saveClientId(context: Context, rawClientId: String) {
        val app = context.applicationContext
        val clean = rawClientId.trim()
        val prev = getClientId(app)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CLIENT_ID, clean)
            .putBoolean(KEY_REMOTE_ENABLED, clean.length >= 16)
            .apply()
        if (prev != clean) {
            disconnect()
            if (clean.isEmpty()) clearOAuthTokens(app)
        }
        _status.value = _status.value.copy(lastFallbackReason = null)
        refreshStatus(app)
    }

    fun clearOAuthTokens(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_PKCE_VERIFIER)
            .remove(KEY_PKCE_STATE)
            .apply()
        refreshStatus(app)
    }

    fun logoutAll(context: Context) {
        val app = context.applicationContext
        disconnect()
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_REMOTE_ENABLED, false)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_PKCE_VERIFIER)
            .remove(KEY_PKCE_STATE)
            .apply()
        refreshStatus(app)
    }

    fun isSpotifyAppInstalled(context: Context): Boolean =
        runCatching { SpotifyAppRemote.isSpotifyInstalled(context.applicationContext) }.getOrDefault(false)

    /** 是否已具备让 Spotify 视为已登录的条件（已获 OAuth 令牌，或已启用 Client ID 且本机装有官方 Spotify 客户端）。 */
    fun isReadyAsLoggedIn(context: Context): Boolean {
        val s = refreshStatus(context)
        return s.hasOAuthToken || s.canAttemptRemotePlayback
    }

    fun noteFallback(reason: String?) {
        _status.value = _status.value.copy(activeForCurrentTrack = false, lastFallbackReason = reason)
    }

    // ---------------- 应用包名与签名证书 SHA-1 指纹读取（供用户一键复制填入 Spotify 后台） ----------------

    @Suppress("DEPRECATION")
    fun getAppSigningSha1(context: Context): String = runCatching {
        val pm = context.packageManager
        val pkg = context.packageName
        val certBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo
            val sigs = if (signingInfo?.hasMultipleSigners() == true) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo?.signingCertificateHistory
            }
            sigs?.firstOrNull()?.toByteArray()
        } else {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            info.signatures?.firstOrNull()?.toByteArray()
        }
        if (certBytes != null) formatSha1Bytes(MessageDigest.getInstance("SHA-1").digest(certBytes)) else ""
    }.getOrDefault("")

    internal fun formatSha1Bytes(digest: ByteArray): String =
        digest.joinToString(":") { b -> "%02X".format(b.toInt() and 0xFF) }

    internal fun spotifyImageUriToHttps(rawImageUri: String?): String? {
        val s = rawImageUri?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        if (s.startsWith("spotify:image:")) {
            val hex = s.removePrefix("spotify:image:").trim()
            if (hex.isNotEmpty()) return "https://i.scdn.co/image/$hex"
        }
        return null
    }

    // ---------------- OAuth 2.0 Authorization Code + PKCE ----------------

    fun buildPkceAuthorizeUrl(context: Context, clientIdOverride: String? = null): String? {
        val app = context.applicationContext
        val cid = (clientIdOverride?.trim()?.takeIf { it.isNotEmpty() } ?: getClientId(app))
        if (cid.length < 16) return null
        if (!clientIdOverride.isNullOrBlank()) saveClientId(app, cid)

        val verifier = generateRandomUrlSafe(64)
        val challenge = sha256Base64Url(verifier)
        val state = generateRandomUrlSafe(24)

        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PKCE_VERIFIER, verifier)
            .putString(KEY_PKCE_STATE, state)
            .apply()

        val enc = { v: String -> URLEncoder.encode(v, "UTF-8") }
        return "https://accounts.spotify.com/authorize" +
            "?client_id=${enc(cid)}" +
            "&response_type=code" +
            "&redirect_uri=${enc(REDIRECT_URI)}" +
            "&code_challenge_method=S256" +
            "&code_challenge=${enc(challenge)}" +
            "&state=${enc(state)}" +
            "&scope=${enc(OAUTH_SCOPES)}"
    }

    internal fun generateRandomUrlSafe(byteLen: Int): String {
        val buf = ByteArray(byteLen)
        SecureRandom().nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .take(96)
    }

    internal fun sha256Base64Url(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun isCallbackUri(uri: Uri?): Boolean =
        uri != null && uri.scheme.equals("voxmusic", ignoreCase = true) && uri.host.equals("spotify-callback", ignoreCase = true)

    fun handleOAuthCallback(context: Context, uri: Uri) {
        val app = context.applicationContext
        scope.launch {
            val res = completeOAuthCallbackSuspend(app, uri)
            res.onSuccess {
                WebStreamingMusic.markLoggedIn(app, WebMusicService.SPOTIFY, true)
                Toast.makeText(app, "Spotify 高音质授权成功", Toast.LENGTH_SHORT).show()
                onOAuthCompleted?.invoke()
            }.onFailure { e ->
                Toast.makeText(app, "Spotify 授权失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    suspend fun completeOAuthCallbackSuspend(context: Context, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val err = uri.getQueryParameter("error")
        if (!err.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("Spotify 拒绝授权: $err"))
        }
        val code = uri.getQueryParameter("code")?.trim().orEmpty()
        if (code.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("回调缺少授权码 code"))
        }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cid = prefs.getString(KEY_CLIENT_ID, null)?.trim().orEmpty()
        val verifier = prefs.getString(KEY_PKCE_VERIFIER, null)?.trim().orEmpty()
        if (cid.isEmpty() || verifier.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("缺少 Client ID 或 PKCE 校验器，请重试授权"))
        }

        val body = FormBody.Builder()
            .add("client_id", cid)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", verifier)
            .build()
        val req = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .post(body)
            .build()

        runCatching {
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(txt).optString("error_description") }.getOrDefault("")
                    throw IllegalStateException("换取令牌失败 (${resp.code}) ${msg.ifBlank { txt.take(120) }}")
                }
                val json = JSONObject(txt)
                val access = json.optString("access_token").trim()
                val refresh = json.optString("refresh_token").trim()
                val expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
                if (access.isEmpty()) throw IllegalStateException("返回令牌为空")
                prefs.edit()
                    .putBoolean(KEY_REMOTE_ENABLED, true)
                    .putString(KEY_ACCESS_TOKEN, access)
                    .apply {
                        if (refresh.isNotEmpty()) putString(KEY_REFRESH_TOKEN, refresh)
                    }
                    .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + (expiresIn - 60L) * 1_000L)
                    .remove(KEY_PKCE_VERIFIER)
                    .remove(KEY_PKCE_STATE)
                    .apply()
                refreshStatus(app)
                Unit
            }
        }
    }

    suspend fun getValidAccessToken(context: Context): String? = tokenMutex.withLock {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cid = prefs.getString(KEY_CLIENT_ID, null)?.trim().orEmpty()
        if (cid.isEmpty()) return null

        val cached = prefs.getString(KEY_ACCESS_TOKEN, null)?.trim().orEmpty()
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (cached.isNotEmpty() && System.currentTimeMillis() < expiresAt) {
            return cached
        }

        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)?.trim().orEmpty()
        if (refresh.isEmpty()) return null

        return withContext(Dispatchers.IO) {
            runCatching {
                val body = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refresh)
                    .add("client_id", cid)
                    .build()
                val req = Request.Builder()
                    .url("https://accounts.spotify.com/api/token")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    val txt = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@use null
                    val json = JSONObject(txt)
                    val newAccess = json.optString("access_token").trim()
                    if (newAccess.isEmpty()) return@use null
                    val newRefresh = json.optString("refresh_token").trim()
                    val expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
                    prefs.edit()
                        .putString(KEY_ACCESS_TOKEN, newAccess)
                        .apply {
                            if (newRefresh.isNotEmpty()) putString(KEY_REFRESH_TOKEN, newRefresh)
                        }
                        .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + (expiresIn - 60L) * 1_000L)
                        .apply()
                    refreshStatus(app)
                    newAccess
                }
            }.getOrNull()
        }
    }

    // ---------------- 原生 Web API 曲库读取（当已持有 PKCE Token 时秒级直连） ----------------

    private suspend fun apiGetJson(context: Context, path: String): JSONObject? {
        val tok = getValidAccessToken(context) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://api.spotify.com/v1$path")
                    .header("Authorization", "Bearer $tok")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val txt = resp.body?.string().orEmpty()
                    if (txt.isBlank()) null else JSONObject(txt)
                }
            }.getOrNull()
        }
    }

    private fun parseWebApiTrack(t: JSONObject?, albumFallback: JSONObject? = null): CatalogItem? {
        if (t == null) return null
        val id = t.optString("id").trim()
        val title = t.optString("name").trim()
        if (id.isEmpty() || title.isEmpty()) return null
        val artistsArr = t.optJSONArray("artists")
        val artist = buildList {
            if (artistsArr != null) {
                for (i in 0 until artistsArr.length()) {
                    val n = artistsArr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                    if (n.isNotEmpty()) add(n)
                }
            }
        }.joinToString(" / ")
        val alb = t.optJSONObject("album") ?: albumFallback
        val albumName = alb?.optString("name")?.trim().orEmpty()
        val cover = alb?.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.trim()?.takeIf { it.startsWith("http") }
        val durationMs = t.optLong("duration_ms", 0L).coerceAtLeast(0L)
        val year = alb?.optString("release_date")?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
        val entry = WebStreamingMusic.buildTrackEntry(
            service = WebMusicService.SPOTIFY,
            trackId = id,
            title = title,
            artist = artist,
            album = albumName,
            durationMs = durationMs,
            coverUrl = cover,
            webUrl = "https://open.spotify.com/track/$id",
        )
        return CatalogItem(
            ratingKey = id,
            type = "track",
            title = title,
            year = year,
            thumb = cover,
            durationMs = durationMs,
            parentTitle = albumName.ifBlank { null },
            grandparentTitle = artist.ifBlank { null },
            fileName = entry.title,
            playUrl = entry.uri,
        )
    }

    suspend fun loadTracksViaOAuth(context: Context): List<CatalogItem> {
        getValidAccessToken(context) ?: return emptyList()
        val saved = apiGetJson(context, "/me/tracks?limit=50")?.optJSONArray("items")
        if (saved != null && saved.length() > 0) {
            val out = ArrayList<CatalogItem>(saved.length())
            for (i in 0 until saved.length()) {
                parseWebApiTrack(saved.optJSONObject(i)?.optJSONObject("track"))?.let(out::add)
            }
            if (out.isNotEmpty()) return out
        }
        val recent = apiGetJson(context, "/me/player/recently-played?limit=40")?.optJSONArray("items")
        if (recent != null && recent.length() > 0) {
            val seen = HashSet<String>()
            val out = ArrayList<CatalogItem>()
            for (i in 0 until recent.length()) {
                val item = parseWebApiTrack(recent.optJSONObject(i)?.optJSONObject("track")) ?: continue
                if (seen.add(item.ratingKey)) out += item
            }
            if (out.isNotEmpty()) return out
        }
        val top = apiGetJson(context, "/me/top/tracks?limit=40")?.optJSONArray("items")
        if (top != null && top.length() > 0) {
            val out = ArrayList<CatalogItem>(top.length())
            for (i in 0 until top.length()) {
                parseWebApiTrack(top.optJSONObject(i))?.let(out::add)
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    suspend fun loadAlbumsViaOAuth(context: Context): List<CatalogItem> {
        val arr = apiGetJson(context, "/me/albums?limit=50")?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<CatalogItem>(arr.length())
        for (i in 0 until arr.length()) {
            val al = arr.optJSONObject(i)?.optJSONObject("album") ?: continue
            val id = al.optString("id").trim()
            val title = al.optString("name").trim()
            if (id.isEmpty() || title.isEmpty()) continue
            val artistsArr = al.optJSONArray("artists")
            val artist = buildList {
                if (artistsArr != null) {
                    for (j in 0 until artistsArr.length()) {
                        val n = artistsArr.optJSONObject(j)?.optString("name")?.trim().orEmpty()
                        if (n.isNotEmpty()) add(n)
                    }
                }
            }.joinToString(" / ")
            val cover = al.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.trim()?.takeIf { it.startsWith("http") }
            val year = al.optString("release_date").take(4).toIntOrNull()?.takeIf { it in 1900..2100 }
            val count = al.optInt("total_tracks", 0).coerceAtLeast(0)
            out += CatalogItem(
                ratingKey = "album|$id",
                type = "album",
                title = title,
                year = year,
                thumb = cover,
                leafCount = count,
                childCount = count,
                parentTitle = artist.ifBlank { null },
                grandparentTitle = artist.ifBlank { null },
            )
        }
        return out
    }

    suspend fun loadPlaylistsViaOAuth(context: Context): List<CatalogItem> {
        val arr = apiGetJson(context, "/me/playlists?limit=50")?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<CatalogItem>(arr.length())
        for (i in 0 until arr.length()) {
            val pl = arr.optJSONObject(i) ?: continue
            val id = pl.optString("id").trim()
            val title = pl.optString("name").trim()
            if (id.isEmpty() || title.isEmpty()) continue
            val desc = pl.optString("description").replace(Regex("<[^>]+>"), "").trim()
            val cover = pl.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.trim()?.takeIf { it.startsWith("http") }
            val count = pl.optJSONObject("tracks")?.optInt("total", 0)?.coerceAtLeast(0) ?: 0
            val owner = pl.optJSONObject("owner")?.optString("display_name")?.trim().orEmpty()
            out += CatalogItem(
                ratingKey = "playlist|$id",
                type = "playlist",
                title = title,
                summary = desc,
                thumb = cover,
                leafCount = count,
                childCount = count,
                parentTitle = owner.ifBlank { null },
            )
        }
        return out
    }

    suspend fun loadChildrenViaOAuth(context: Context, container: CatalogItem): List<CatalogItem> {
        getValidAccessToken(context) ?: return emptyList()
        val parts = container.ratingKey.split('|')
        val kind = parts.firstOrNull().orEmpty()
        val cid = parts.getOrNull(1) ?: parts.firstOrNull().orEmpty()
        if (cid.isEmpty()) return emptyList()
        return if (kind == "playlist") {
            if (cid == "liked") return loadTracksViaOAuth(context)
            val arr = apiGetJson(context, "/playlists/$cid/tracks?limit=100")?.optJSONArray("items")
                ?: apiGetJson(context, "/playlists/$cid/items?limit=100")?.optJSONArray("items")
                ?: return emptyList()
            val out = ArrayList<CatalogItem>(arr.length())
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                parseWebApiTrack(entry.optJSONObject("track") ?: entry.optJSONObject("item"))?.let(out::add)
            }
            out
        } else {
            val alb = apiGetJson(context, "/albums/$cid") ?: return emptyList()
            val arr = alb.optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()
            val out = ArrayList<CatalogItem>(arr.length())
            for (i in 0 until arr.length()) {
                parseWebApiTrack(arr.optJSONObject(i), albumFallback = alb)?.let(out::add)
            }
            out
        }
    }

    suspend fun searchViaOAuth(context: Context, query: String): List<CatalogItem> {
        getValidAccessToken(context) ?: return emptyList()
        val enc = URLEncoder.encode(query.trim(), "UTF-8")
        val root = apiGetJson(context, "/search?type=track,album,playlist&limit=20&q=$enc") ?: return emptyList()
        val out = ArrayList<CatalogItem>()

        val tracks = root.optJSONObject("tracks")?.optJSONArray("items")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                parseWebApiTrack(tracks.optJSONObject(i))?.let(out::add)
            }
        }
        val albums = root.optJSONObject("albums")?.optJSONArray("items")
        if (albums != null) {
            for (i in 0 until albums.length()) {
                val al = albums.optJSONObject(i) ?: continue
                val id = al.optString("id").trim()
                val title = al.optString("name").trim()
                if (id.isEmpty() || title.isEmpty()) continue
                val artistsArr = al.optJSONArray("artists")
                val artist = buildList {
                    if (artistsArr != null) {
                        for (j in 0 until artistsArr.length()) {
                            val n = artistsArr.optJSONObject(j)?.optString("name")?.trim().orEmpty()
                            if (n.isNotEmpty()) add(n)
                        }
                    }
                }.joinToString(" / ")
                val cover = al.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.trim()?.takeIf { it.startsWith("http") }
                val count = al.optInt("total_tracks", 0).coerceAtLeast(0)
                val year = al.optString("release_date").take(4).toIntOrNull()?.takeIf { it in 1900..2100 }
                out += CatalogItem(
                    ratingKey = "album|$id",
                    type = "album",
                    title = title,
                    year = year,
                    thumb = cover,
                    leafCount = count,
                    childCount = count,
                    parentTitle = artist.ifBlank { null },
                    grandparentTitle = artist.ifBlank { null },
                )
            }
        }
        val playlists = root.optJSONObject("playlists")?.optJSONArray("items")
        if (playlists != null) {
            for (i in 0 until playlists.length()) {
                val pl = playlists.optJSONObject(i) ?: continue
                val id = pl.optString("id").trim()
                val title = pl.optString("name").trim()
                if (id.isEmpty() || title.isEmpty()) continue
                val desc = pl.optString("description").replace(Regex("<[^>]+>"), "").trim()
                val cover = pl.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.trim()?.takeIf { it.startsWith("http") }
                val count = pl.optJSONObject("tracks")?.optInt("total", 0)?.coerceAtLeast(0) ?: 0
                out += CatalogItem(
                    ratingKey = "playlist|$id",
                    type = "playlist",
                    title = title,
                    summary = desc,
                    thumb = cover,
                    leafCount = count,
                    childCount = count,
                )
            }
        }
        return out
    }

    private fun cacheRemoteListItem(li: ListItem, mapped: CatalogItem? = null) {
        if (!li.uri.isNullOrBlank()) remoteItemCache[li.uri] = li
        if (!li.id.isNullOrBlank()) remoteItemCache[li.id] = li
        if (mapped != null && mapped.ratingKey.isNotBlank()) {
            remoteItemCache[mapped.ratingKey] = li
        }
    }

    /** 通过本机官方 Spotify 客户端 ContentApi（本地 Binder IPC，无需代理）读取推荐/曲库歌单与专辑。 */
    suspend fun loadRecommendedViaRemote(context: Context): List<CatalogItem> {
        val remote = connectRemote(context, showAuthView = false, timeoutMs = 4_500L).getOrNull() ?: return emptyList()
        val listItems = awaitCallResult(remote.contentApi.getRecommendedContentItems("default"), 5_000L).getOrNull()
            ?: return emptyList()
        val items = listItems.items ?: return emptyList()
        val out = ArrayList<CatalogItem>()
        val seen = HashSet<String>()
        var expandedShelves = 0
        for (li in items) {
            if (li == null || li.uri.isNullOrBlank()) continue
            val mapped = mapRemoteListItem(li)
            cacheRemoteListItem(li, mapped)
            if (mapped != null && seen.add("${mapped.type}|${mapped.ratingKey}")) {
                out += mapped
            }
            val isContainerLeaf = li.uri.startsWith("spotify:track:") ||
                li.uri.startsWith("spotify:playlist:") ||
                li.uri.contains(":playlist:") ||
                li.uri.startsWith("spotify:album:")
            if (li.hasChildren && !isContainerLeaf && expandedShelves < 6) {
                expandedShelves++
                val children = awaitCallResult(remote.contentApi.getChildrenOfItem(li, 30, 0), 3_500L)
                    .getOrNull()?.items.orEmpty()
                for (child in children) {
                    if (child == null || child.uri.isNullOrBlank()) continue
                    val cm = mapRemoteListItem(child)
                    cacheRemoteListItem(child, cm)
                    if (cm != null && seen.add("${cm.type}|${cm.ratingKey}")) {
                        out += cm
                    }
                }
            }
        }
        return out
    }

    /**
     * 通过本机官方 Spotify 客户端 ContentApi.getChildrenOfItem 读取歌单/专辑内曲目：
     * 直接走本地 Binder IPC 复用官方客户端已打通的网络与本地缓存，不开代理也能秒开歌单。
     */
    suspend fun loadChildrenViaRemote(context: Context, container: CatalogItem): List<CatalogItem> {
        val st = refreshStatus(context)
        if (!st.canAttemptRemotePlayback) return emptyList()
        val parts = container.ratingKey.split('|')
        val kind = parts.firstOrNull().orEmpty()
        val cid = parts.getOrNull(1) ?: parts.firstOrNull().orEmpty()
        if (cid.isEmpty()) return emptyList()

        val remote = connectRemote(context, showAuthView = false, timeoutMs = 4_500L).getOrNull() ?: return emptyList()
        val candidateUris = when {
            kind == "playlist" && cid == "liked" -> listOf("spotify:collection:tracks")
            kind == "playlist" -> listOf("spotify:playlist:$cid")
            kind == "album" -> listOf("spotify:album:$cid")
            else -> listOf("spotify:playlist:$cid", "spotify:album:$cid")
        }

        val out = ArrayList<CatalogItem>()
        val seen = HashSet<String>()
        for (targetUri in candidateUris) {
            val baseItem = remoteItemCache[container.ratingKey]
                ?: remoteItemCache[targetUri]
                ?: ListItem(
                    targetUri,
                    targetUri,
                    null,
                    container.title,
                    container.summary.ifBlank { container.parentTitle.orEmpty() },
                    true,
                    true,
                )
            var offset = 0
            val pageSize = 50
            while (offset < 200) {
                val page = awaitCallResult(remote.contentApi.getChildrenOfItem(baseItem, pageSize, offset), 4_500L)
                    .getOrNull() ?: break
                val batch = page.items.orEmpty()
                if (batch.isEmpty()) break
                for (li in batch) {
                    if (li == null || li.uri.isNullOrBlank()) continue
                    val mapped = mapRemoteListItem(li)
                    cacheRemoteListItem(li, mapped)
                    if (mapped != null && seen.add("${mapped.type}|${mapped.ratingKey}")) {
                        out += mapped
                    }
                }
                offset += batch.size
                if (batch.size < pageSize || (page.total > 0 && offset >= page.total)) break
            }
            if (out.isNotEmpty()) break
        }
        android.util.Log.d("VoxMusic", "Spotify loadChildrenViaRemote key=${container.ratingKey} count=${out.size}")
        return out
    }

    private fun mapRemoteListItem(li: ListItem?): CatalogItem? {
        if (li == null || li.uri.isNullOrBlank() || li.title.isNullOrBlank()) return null
        val uri = li.uri
        val cover = spotifyImageUriToHttps(li.imageUri?.raw)
        return when {
            uri.startsWith("spotify:track:") -> {
                val id = uri.substringAfterLast(':')
                val entry = WebStreamingMusic.buildTrackEntry(
                    service = WebMusicService.SPOTIFY,
                    trackId = id,
                    title = li.title,
                    artist = li.subtitle.orEmpty(),
                    coverUrl = cover,
                    webUrl = "https://open.spotify.com/track/$id",
                )
                CatalogItem(
                    ratingKey = id,
                    type = "track",
                    title = li.title,
                    thumb = cover,
                    grandparentTitle = li.subtitle?.takeIf { it.isNotBlank() },
                    fileName = entry.title,
                    playUrl = entry.uri,
                )
            }
            uri.startsWith("spotify:playlist:") || uri.contains(":playlist:") -> {
                val id = uri.substringAfterLast(':')
                CatalogItem(
                    ratingKey = "playlist|$id",
                    type = "playlist",
                    title = li.title,
                    summary = li.subtitle.orEmpty(),
                    thumb = cover,
                )
            }
            uri.startsWith("spotify:album:") -> {
                val id = uri.substringAfterLast(':')
                CatalogItem(
                    ratingKey = "album|$id",
                    type = "album",
                    title = li.title,
                    parentTitle = li.subtitle?.takeIf { it.isNotBlank() },
                    thumb = cover,
                )
            }
            else -> null
        }
    }

    // ---------------- SpotifyAppRemote 本机高音质遥控播放 ----------------

    /**
     * 适配 Android 14+ (API 34+) 后台启动限制（BAL）：
     * 官方 `spotify-app-remote-release-0.8.0.aar` 内部调用 `getApplicationContext().bindService(..., 65)` 时
     * 未携带 `Context.BIND_ALLOW_ACTIVITY_STARTS`，导致系统拦截 Spotify 协议服务拉起首次授权页并一直超时。
     */
    private class SpotifyBalContextWrapper(private val appBase: Context) : ContextWrapper(appBase) {
        override fun getApplicationContext(): Context = this

        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            val effectiveFlags = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                (service.`package`?.startsWith("com.spotify.") == true ||
                    service.action?.contains("spotify", ignoreCase = true) == true)
            ) {
                flags or Context.BIND_ALLOW_ACTIVITY_STARTS
            } else {
                flags
            }
            return appBase.bindService(service, conn, effectiveFlags)
        }

        override fun unbindService(conn: ServiceConnection) {
            appBase.unbindService(conn)
        }
    }

    suspend fun testConnectInteractive(context: Context, clientIdOverride: String? = null): Result<Unit> {
        if (!clientIdOverride.isNullOrBlank()) {
            saveClientId(context, clientIdOverride)
        }
        return connectRemote(context, showAuthView = true, timeoutMs = 25_000L).map { Unit }
    }

    private suspend fun connectRemote(
        context: Context,
        showAuthView: Boolean,
        timeoutMs: Long = 8_500L,
    ): Result<SpotifyAppRemote> = connectMutex.withLock {
        val app = context.applicationContext
        val cid = getClientId(app)
        if (cid.length < 16) {
            return Result.failure(IllegalStateException("尚未配置 Spotify Client ID"))
        }
        if (!isSpotifyAppInstalled(app)) {
            return Result.failure(IllegalStateException("未检测到本机安装的官方 Spotify 客户端"))
        }

        val existing = activeRemote
        if (existing != null && existing.isConnected && connectedClientId == cid) {
            return Result.success(existing)
        }
        disconnectInternal()

        val bindContext = SpotifyBalContextWrapper(app)
        val outcome = withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Result<SpotifyAppRemote>> { cont ->
                    val params = ConnectionParams.Builder(cid)
                        .setRedirectUri(REDIRECT_URI)
                        .showAuthView(showAuthView)
                        .build()
                    SpotifyAppRemote.connect(
                        bindContext,
                        params,
                        object : Connector.ConnectionListener {
                            override fun onConnected(remote: SpotifyAppRemote) {
                                if (cont.isActive) {
                                    cont.resume(Result.success(remote))
                                } else {
                                    runCatching { SpotifyAppRemote.disconnect(remote) }
                                }
                            }

                            override fun onFailure(throwable: Throwable) {
                                if (cont.isActive) cont.resume(Result.failure(throwable))
                            }
                        },
                    )
                }
            }
        } ?: Result.failure(IllegalStateException("连接本机 Spotify 客户端超时"))

        outcome.onSuccess { remote ->
            activeRemote = remote
            connectedClientId = cid
            subscribePlayerState(remote)
            _status.value = _status.value.copy(
                spotifyAppInstalled = true,
                remoteConnected = true,
                lastFallbackReason = null,
            )
        }.onFailure { err ->
            val readable = humanizeRemoteError(err)
            _status.value = _status.value.copy(
                remoteConnected = false,
                activeForCurrentTrack = false,
                lastFallbackReason = readable,
            )
        }
        return outcome
    }

    private fun humanizeRemoteError(err: Throwable): String {
        val name = err.javaClass.simpleName
        return when {
            name.contains("CouldNotFindSpotifyApp", ignoreCase = true) -> "未安装官方 Spotify 客户端"
            name.contains("NotLoggedInException", ignoreCase = true) -> "本机 Spotify 客户端尚未登录账号"
            name.contains("UserNotAuthorizedException", ignoreCase = true) ||
                name.contains("AuthenticationFailedException", ignoreCase = true) ->
                "Client ID / 包名 / SHA-1 指纹尚未在 Spotify 后台匹配授权"
            name.contains("OfflineModeException", ignoreCase = true) -> "本机 Spotify 客户端正处于离线模式"
            else -> err.message ?: name
        }
    }

    @Volatile
    private var startTargetPosMs: Long = 0L

    @Volatile
    private var targetPlaySeq: Long = 0L

    suspend fun tryPlayTrack(
        context: Context,
        trackId: String,
        title: String,
        artist: String,
        startPositionMs: Long,
        playSeq: Long = 0L,
    ): Result<Unit> {
        val statusNow = refreshStatus(context)
        if (!statusNow.canAttemptRemotePlayback) {
            return Result.failure(IllegalStateException("官方客户端联动当前未启用或未安装本机 Spotify"))
        }

        var resolvedId = trackId.trim()
        if (resolvedId.isEmpty() && title.isNotBlank()) {
            val q = listOfNotNull(title, artist.takeIf { it.isNotBlank() }).joinToString(" ")
            resolvedId = searchViaOAuth(context, q).firstOrNull { it.type == "track" }?.ratingKey.orEmpty()
        }
        if (resolvedId.isEmpty()) {
            return Result.failure(IllegalStateException("缺少 Spotify 曲目 ID"))
        }

        val remote = connectRemote(context, showAuthView = true, timeoutMs = 4_500L).getOrElse { err ->
            noteFallback(humanizeRemoteError(err))
            return Result.failure(err)
        }

        val spotifyUri = "spotify:track:$resolvedId"
        val prevPlayingUri = latestPlayerState?.track?.uri
        stalePreviousUri = if (!prevPlayingUri.isNullOrBlank() && prevPlayingUri != spotifyUri) prevPlayingUri else null
        commandSentElapsedMs = SystemClock.elapsedRealtime()
        targetTrackUri = spotifyUri
        targetPlaySeq = playSeq.takeIf { it > 0L } ?: WebStreamingMusic.activePlaySeq
        startTargetPosMs = startPositionMs.coerceAtLeast(0L)
        sawTargetTrackPlaying = false
        maxTargetPosMs = 0L
        endedDispatchedForTarget = false

        return withContext(Dispatchers.Main.immediate) {
            // 先等待把 Connect 输出切回本机官方客户端（避免沿用旧的 Web Player (Chrome) 设备），再下发播放
            awaitCallResult(remote.connectApi.connectSwitchToLocalDevice(), 1_500L)
            val playRes = awaitCallResult(remote.playerApi.play(spotifyUri), 4_500L)
            playRes.onSuccess {
                if (startPositionMs > 1_000L) {
                    runCatching { remote.playerApi.seekTo(startPositionMs) }
                }
                _status.value = _status.value.copy(
                    remoteConnected = true,
                    activeForCurrentTrack = true,
                    lastFallbackReason = null,
                )
                ensureTickerRunning()
            }.onFailure { err ->
                noteFallback(humanizeRemoteError(err))
            }
            playRes.map { Unit }
        }
    }

    fun setPlaying(play: Boolean): Boolean {
        val remote = activeRemote?.takeIf { it.isConnected && _status.value.activeForCurrentTrack } ?: return false
        scope.launch {
            runCatching {
                if (play) remote.playerApi.resume() else remote.playerApi.pause()
            }
        }
        return true
    }

    fun seekTo(positionMs: Long): Boolean {
        val remote = activeRemote?.takeIf { it.isConnected && _status.value.activeForCurrentTrack } ?: return false
        scope.launch {
            runCatching { remote.playerApi.seekTo(positionMs.coerceAtLeast(0L)) }
        }
        return true
    }

    fun setVolume(volume: Float): Boolean {
        val remote = activeRemote?.takeIf { it.isConnected && _status.value.activeForCurrentTrack } ?: return false
        scope.launch {
            runCatching { remote.connectApi.connectSetVolume(volume.coerceIn(0f, 1f)) }
        }
        return true
    }

    fun pauseIfActive() {
        val remote = activeRemote
        if (remote != null && remote.isConnected && _status.value.activeForCurrentTrack) {
            scope.launch { runCatching { remote.playerApi.pause() } }
        }
        tickerJob?.cancel()
        tickerJob = null
        _status.value = _status.value.copy(activeForCurrentTrack = false)
        targetTrackUri = null
        targetPlaySeq = 0L
        stalePreviousUri = null
    }

    fun disconnect() {
        scope.launch { disconnectInternal() }
    }

    private fun disconnectInternal() {
        tickerJob?.cancel()
        tickerJob = null
        runCatching { stateSubscription?.cancel() }
        stateSubscription = null
        activeRemote?.let { r ->
            runCatching { SpotifyAppRemote.disconnect(r) }
        }
        activeRemote = null
        latestPlayerState = null
        targetTrackUri = null
        stalePreviousUri = null
        _status.value = _status.value.copy(remoteConnected = false, activeForCurrentTrack = false)
    }

    private fun subscribePlayerState(remote: SpotifyAppRemote) {
        runCatching { stateSubscription?.cancel() }
        stateSubscription = remote.playerApi.subscribeToPlayerState().apply {
            setEventCallback { state ->
                latestPlayerState = state
                latestStateRealtimeMs = SystemClock.elapsedRealtime()
                emitSnapshotFromState(state)
            }
            setErrorCallback {
                val stillConnected = remote.isConnected
                _status.value = _status.value.copy(remoteConnected = stillConnected)
                if (!stillConnected && _status.value.activeForCurrentTrack) {
                    WebStreamingMusic.dispatchSnapshotObj(
                        WebStreamingMusic.PlaybackSnapshot(
                            service = WebMusicService.SPOTIFY,
                            playing = false,
                        ),
                    )
                }
            }
        }
    }

    private fun ensureTickerRunning() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive && activeRemote?.isConnected == true && _status.value.activeForCurrentTrack) {
                latestPlayerState?.let { emitSnapshotFromState(it) }
                delay(280L)
            }
            if (_status.value.activeForCurrentTrack && activeRemote?.isConnected != true) {
                _status.value = _status.value.copy(remoteConnected = false)
            }
        }
    }

    private fun emitSnapshotFromState(state: PlayerState) {
        if (!_status.value.activeForCurrentTrack) return
        val tr = state.track
        val curUri = tr?.uri.orEmpty()
        var wantUri = targetTrackUri
        val dur = (tr?.duration ?: 0L).coerceAtLeast(0L)
        val sinceCmdMs = (SystemClock.elapsedRealtime() - commandSentElapsedMs).coerceAtLeast(0L)

        val snapSeq = targetPlaySeq.takeIf { it > 0L } ?: WebStreamingMusic.activePlaySeq

        // 切歌初期过滤上一首残留状态，同时兼容 Spotify 跨区曲目自动重链（Track Relinking）
        if (!sawTargetTrackPlaying && curUri.isNotEmpty()) {
            val stale = stalePreviousUri
            if (stale != null && curUri == stale) {
                return
            }
            if (wantUri != null && curUri != wantUri && curUri.startsWith("spotify:track:")) {
                targetTrackUri = curUri
                wantUri = curUri
            }
        }

        val elapsed = (SystemClock.elapsedRealtime() - latestStateRealtimeMs).coerceAtLeast(0L)
        val rawPos = if (!state.isPaused && state.playbackSpeed > 0f) {
            state.playbackPosition + (elapsed * state.playbackSpeed).toLong()
        } else {
            state.playbackPosition
        }
        val clampedPos = if (dur > 0L) rawPos.coerceIn(0L, dur) else rawPos.coerceAtLeast(0L)

        // 同曲单曲循环重播初期，忽略尚未归零的旧末尾进度
        if (!sawTargetTrackPlaying && sinceCmdMs < 900L && dur > 15_000L && clampedPos > dur - 3_500L) {
            return
        }

        var naturalEnded = false
        if (wantUri != null) {
            if (curUri == wantUri) {
                if (!state.isPaused && (startTargetPosMs > 0L || clampedPos < 2_500L || sinceCmdMs > 1_200L)) {
                    sawTargetTrackPlaying = true
                }
                if (sawTargetTrackPlaying && clampedPos > maxTargetPosMs) maxTargetPosMs = clampedPos
                if (sawTargetTrackPlaying &&
                    dur > 15_000L &&
                    maxTargetPosMs >= dur - 2_800L &&
                    state.isPaused &&
                    state.playbackPosition < 1_500L &&
                    !endedDispatchedForTarget
                ) {
                    endedDispatchedForTarget = true
                    naturalEnded = true
                }
            } else if (curUri.isNotEmpty() && sawTargetTrackPlaying && maxTargetPosMs > 5_000L && !endedDispatchedForTarget) {
                // 官方客户端自动连播到了其他曲目：立即通知本应用队列切入下一首
                endedDispatchedForTarget = true
                naturalEnded = true
            }
        }

        if (wantUri != null && !sawTargetTrackPlaying && !naturalEnded && sinceCmdMs < 6_000L) {
            WebStreamingMusic.dispatchSnapshotObj(
                WebStreamingMusic.PlaybackSnapshot(
                    service = WebMusicService.SPOTIFY,
                    playSeq = snapSeq,
                    switching = false,
                    playing = false,
                    buffering = true,
                    positionMs = startTargetPosMs,
                    durationMs = if (curUri == wantUri) dur else 0L,
                ),
            )
            return
        }

        val artistNames = tr?.artists
            ?.mapNotNull { it?.name?.trim()?.takeIf(String::isNotEmpty) }
            ?.joinToString(" / ")
            ?.ifBlank { tr.artist?.name.orEmpty() }
            ?: tr?.artist?.name.orEmpty()

        val coverUrl = spotifyImageUriToHttps(tr?.imageUri?.raw)
        val snap = WebStreamingMusic.PlaybackSnapshot(
            service = WebMusicService.SPOTIFY,
            playSeq = snapSeq,
            playing = !state.isPaused && !naturalEnded,
            buffering = false,
            positionMs = if (naturalEnded && dur > 0L) dur else clampedPos,
            durationMs = dur,
            title = if (wantUri == null || curUri == wantUri) tr?.name.orEmpty() else "",
            artist = if (wantUri == null || curUri == wantUri) artistNames else "",
            album = if (wantUri == null || curUri == wantUri) tr?.album?.name.orEmpty() else "",
            coverUrl = if (wantUri == null || curUri == wantUri) coverUrl else null,
            ended = naturalEnded,
        )
        WebStreamingMusic.dispatchSnapshotObj(snap)
    }

    private suspend fun <T> awaitCallResult(call: CallResult<T>, timeoutMs: Long): Result<T> {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Result<T>> { cont ->
                call.setResultCallback { data ->
                    if (cont.isActive) cont.resume(Result.success(data))
                }
                call.setErrorCallback { err ->
                    if (cont.isActive) cont.resume(Result.failure(err))
                }
            }
        } ?: Result.failure(IllegalStateException("Spotify App Remote 指令响应超时"))
    }
}
