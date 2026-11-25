package com.voxmusic.app.music

import android.content.Context
import com.voxmusic.app.meta.DiscoverItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 音乐源：Apple Music、Spotify 与 YouTube Music 三大流媒体服务。 */
enum class WebMusicService(
    val label: String,
    val badge: String,
    val accentHex: Long,
    val homeUrl: String,
    val uriScheme: String,
) {
    APPLE_MUSIC(
        label = "Apple Music",
        badge = "AM",
        accentHex = 0xFFFA243C,
        homeUrl = "https://music.apple.com/",
        uriScheme = "applemusic",
    ),
    SPOTIFY(
        label = "Spotify",
        badge = "SP",
        accentHex = 0xFF1DB954,
        homeUrl = "https://open.spotify.com/",
        uriScheme = "spotify",
    ),
    YOUTUBE_MUSIC(
        label = "YouTube Music",
        badge = "YM",
        accentHex = 0xFFFF0000,
        homeUrl = "https://music.youtube.com/",
        uriScheme = "ytmusic",
    ),
    ;

    companion object {
        fun fromName(raw: String?): WebMusicService? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }

        fun fromUri(uri: String?): WebMusicService? {
            val scheme = uri?.substringBefore("://", "")?.lowercase().orEmpty()
            return entries.firstOrNull { it.uriScheme == scheme }
        }
    }
}

/**
 * 当前选中的音乐源持久化存储（Apple Music / Spotify）。
 */
object MusicSourceStore {
    private const val PREFS = "voxmusic_source"
    private const val KEY_SERVICE = "service"

    private val _active = MutableStateFlow(WebMusicService.APPLE_MUSIC)

    @Volatile
    private var initialized = false

    fun ensureInit(context: Context): StateFlow<WebMusicService> {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    val saved = context.applicationContext
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_SERVICE, null)
                    _active.value = WebMusicService.fromName(saved) ?: WebMusicService.APPLE_MUSIC
                    initialized = true
                }
            }
        }
        return _active.asStateFlow()
    }

    fun active(context: Context): WebMusicService {
        ensureInit(context)
        return _active.value
    }

    fun select(context: Context, service: WebMusicService) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVICE, service.name)
            .apply()
        initialized = true
        _active.value = service
    }
}

/**
 * 榜单/发现/搜索点击播放时的智能音源解析器：
 * 无需弹窗让用户选源，自动选择当前可用的已登录音源直接开播。
 */
object SmartMusicSourceResolver {

    private val priorityOrder = listOf(
        WebMusicService.APPLE_MUSIC,
        WebMusicService.SPOTIFY,
        WebMusicService.YOUTUBE_MUSIC,
    )

    /**
     * 智能决策顺序：
     * 1. 若无任何已登录源，返回 null（由界面提示登录 Apple Music / Spotify / YouTube Music，登录后自动开播）；
     * 2. 若当前激活的音乐源已登录，优先使用该源；
     * 3. 若仅登录了一个源，直接选择该已登录源；
     * 4. 若多个源均已登录且未命中上述条件，优先本机 Spotify 官方客户端高音质联动，否则按 Apple Music > Spotify > YouTube Music 顺序。
     */
    fun resolve(
        loggedIn: Set<WebMusicService>,
        activeService: WebMusicService,
        spotifyRemoteReady: Boolean = false,
    ): WebMusicService? {
        if (loggedIn.isEmpty()) return null
        if (activeService in loggedIn) return activeService
        if (loggedIn.size == 1) return loggedIn.first()
        if (spotifyRemoteReady && WebMusicService.SPOTIFY in loggedIn) {
            return WebMusicService.SPOTIFY
        }
        return priorityOrder.firstOrNull { it in loggedIn } ?: loggedIn.first()
    }

    /**
     * 跨源自动兜底候选序列：
     * 当首选音源 [primary] 搜索不到曲目、无版权或播放失败时，按优先级依次尝试其余已登录音源。
     */
    fun fallbackOrder(
        primary: WebMusicService,
        loggedIn: Set<WebMusicService>,
        spotifyRemoteReady: Boolean = false,
    ): List<WebMusicService> {
        val others = loggedIn.filter { it != primary }
        if (others.size <= 1) return others
        return buildList(others.size) {
            if (spotifyRemoteReady && WebMusicService.SPOTIFY in others) {
                add(WebMusicService.SPOTIFY)
            }
            priorityOrder.forEach { svc ->
                if (svc in others && svc !in this) add(svc)
            }
            others.forEach { svc ->
                if (svc !in this) add(svc)
            }
        }
    }

    fun resolveForContext(context: Context): WebMusicService? {
        val logged = WebStreamingMusic.refreshAuthState(context)
        val active = MusicSourceStore.active(context)
        val spotifyRemote = SpotifyAppRemoteBridge.refreshStatus(context).canAttemptRemotePlayback
        return resolve(
            loggedIn = logged,
            activeService = active,
            spotifyRemoteReady = spotifyRemote,
        )
    }

    /**
     * 结合单曲来源信息解析最优播放源：
     * - 若曲目仅来自单一流媒体独占搜索结果（例如 Apple Music/常规目录没有，仅在 YouTube Music 或 Spotify 搜到），
     *   且该独占源已登录，则直接优先用该源播放，避免在其它源空搜或播错版本；
     * - 若曲目在多源/通用榜单均存在，仍严格遵循用户当前激活音源。
     */
    fun resolveForItem(
        item: DiscoverItem,
        loggedIn: Set<WebMusicService>,
        activeService: WebMusicService,
        spotifyRemoteReady: Boolean = false,
    ): WebMusicService? {
        if (loggedIn.isEmpty()) return null
        val exclusive = exclusiveServiceOf(item)
        if (exclusive != null && exclusive in loggedIn) {
            return exclusive
        }
        return resolve(
            loggedIn = loggedIn,
            activeService = activeService,
            spotifyRemoteReady = spotifyRemoteReady,
        )
    }

    fun resolveForItemInContext(context: Context, item: DiscoverItem): WebMusicService? {
        val logged = WebStreamingMusic.refreshAuthState(context)
        val active = MusicSourceStore.active(context)
        val spotifyRemote = SpotifyAppRemoteBridge.refreshStatus(context).canAttemptRemotePlayback
        return resolveForItem(
            item = item,
            loggedIn = logged,
            activeService = active,
            spotifyRemoteReady = spotifyRemote,
        )
    }

    internal fun exclusiveServiceOf(item: DiscoverItem): WebMusicService? {
        val hasApple = !item.appleUrl.isNullOrBlank() ||
            item.id.startsWith("apple:") ||
            item.id.startsWith("itunes:") ||
            item.platforms.any { it.name.contains("Apple", ignoreCase = true) }
        val hasCnCatalog = !item.neteaseId.isNullOrBlank() ||
            item.id.startsWith("netease:") ||
            item.id.startsWith("qq:") ||
            item.platforms.any { it.name.contains("网易") || it.name.contains("QQ", ignoreCase = true) }
        if (hasApple || hasCnCatalog) return null

        val hasSpotify = item.id.startsWith("spotify:") ||
            item.platforms.any { it.name.equals("Spotify", ignoreCase = true) }
        val hasYouTube = item.id.startsWith("yt:") ||
            item.id.startsWith("youtube:") ||
            item.id.startsWith("ytmusic:") ||
            item.platforms.any { it.name.contains("YouTube", ignoreCase = true) }

        return when {
            hasYouTube && !hasSpotify -> WebMusicService.YOUTUBE_MUSIC
            hasSpotify && !hasYouTube -> WebMusicService.SPOTIFY
            else -> null
        }
    }
}
