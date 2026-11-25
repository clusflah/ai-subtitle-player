package com.aisub.player.music

import android.content.Context
import com.aisub.player.media.MediaEntry
import com.aisub.player.meta.DiscoverItem
import com.aisub.player.remote.RemoteClient
import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType
import com.aisub.player.remote.cloud.EmbyClient
import com.aisub.player.remote.cloud.MediaBrowser
import com.aisub.player.remote.cloud.PlexClient
import com.aisub.player.remote.cloud.ServerItem
import com.aisub.player.remote.cloud.ServerSection
import com.aisub.player.remote.cloud.SubsonicClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MusicSourceKind {
    LOCAL,
    SERVER,
    APPLE_MUSIC,
    SPOTIFY,
    YOUTUBE_MUSIC,
    NETEASE_MUSIC,
    QQ_MUSIC,
}

enum class WebMusicService(
    val sourceKind: MusicSourceKind,
    val label: String,
    val badge: String,
    val accentHex: Long,
    val homeUrl: String,
    val uriScheme: String,
) {
    APPLE_MUSIC(
        sourceKind = MusicSourceKind.APPLE_MUSIC,
        label = "Apple Music",
        badge = "AM",
        accentHex = 0xFFFA243C,
        homeUrl = "https://music.apple.com/",
        uriScheme = "applemusic",
    ),
    SPOTIFY(
        sourceKind = MusicSourceKind.SPOTIFY,
        label = "Spotify",
        badge = "SP",
        accentHex = 0xFF1DB954,
        homeUrl = "https://open.spotify.com/",
        uriScheme = "spotify",
    ),
    YOUTUBE_MUSIC(
        sourceKind = MusicSourceKind.YOUTUBE_MUSIC,
        label = "YouTube Music",
        badge = "YT",
        accentHex = 0xFFFF0000,
        homeUrl = "https://music.youtube.com/",
        uriScheme = "ytmusic",
    ),
    NETEASE_MUSIC(
        sourceKind = MusicSourceKind.NETEASE_MUSIC,
        label = "网易云音乐",
        badge = "云",
        accentHex = 0xFFE60026,
        homeUrl = "https://music.163.com/",
        uriScheme = "neteasemusic",
    ),
    QQ_MUSIC(
        sourceKind = MusicSourceKind.QQ_MUSIC,
        label = "QQ音乐",
        badge = "QQ",
        accentHex = 0xFF12B775,
        homeUrl = "https://y.qq.com/",
        uriScheme = "qqmusic",
    ),
    ;

    companion object {
        fun fromSourceKind(kind: MusicSourceKind): WebMusicService? =
            entries.firstOrNull { it.sourceKind == kind }

        fun fromUri(uri: String?): WebMusicService? {
            val scheme = uri?.substringBefore("://", "")?.lowercase().orEmpty()
            return entries.firstOrNull { it.uriScheme == scheme }
        }
    }
}

/**
 * 智能音乐源决策器：点击榜单/歌单/搜索/AI 荐歌时自动选用已登录音源直接开播，无需弹窗选源。
 */
object SmartMusicSourceResolver {
    private val priorityOrder: List<WebMusicService> = listOf(
        WebMusicService.APPLE_MUSIC,
        WebMusicService.SPOTIFY,
        WebMusicService.NETEASE_MUSIC,
        WebMusicService.QQ_MUSIC,
        WebMusicService.YOUTUBE_MUSIC,
    )

    fun resolve(
        loggedIn: Set<WebMusicService>,
        activeService: WebMusicService?,
        spotifyRemoteReady: Boolean = false,
    ): WebMusicService? {
        if (loggedIn.isEmpty()) return null
        if (activeService != null && activeService in loggedIn) return activeService
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
        val loggedIn = WebStreamingMusic.refreshAuthState(context)
        val active = MusicSourceStore.active(context).webService
        val spotifyRemote = SpotifyAppRemoteBridge.refreshStatus(context).canAttemptRemotePlayback
        return resolve(loggedIn, active, spotifyRemote)
    }

    /**
     * 结合单曲来源信息解析最优播放源：
     * - 若曲目仅来自单一流媒体独占搜索结果（例如 Apple Music 常规目录没有，仅在 YouTube Music 或 Spotify 搜到），
     *   且该独占源已登录，则直接优先用该源播放，避免在其它源空搜或播错版本；
     * - 若曲目在多源/通用榜单均存在，仍严格遵循用户当前激活音源。
     */
    fun resolveForItem(
        item: DiscoverItem,
        loggedIn: Set<WebMusicService>,
        activeService: WebMusicService?,
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
        val active = MusicSourceStore.active(context).webService
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
        val hasNetEase = !item.neteaseId.isNullOrBlank() ||
            item.id.startsWith("netease:") ||
            item.platforms.any { it.name.contains("网易") || it.name.contains("NetEase", ignoreCase = true) }
        val hasQq = item.id.startsWith("qq:") ||
            item.platforms.any { it.name.contains("QQ", ignoreCase = true) }
        val hasSpotify = item.id.startsWith("spotify:") ||
            item.platforms.any { it.name.equals("Spotify", ignoreCase = true) }
        val hasYouTube = item.id.startsWith("yt:") ||
            item.id.startsWith("youtube:") ||
            item.id.startsWith("ytmusic:") ||
            item.platforms.any { it.name.contains("YouTube", ignoreCase = true) }

        val candidates = buildList(5) {
            if (hasApple) add(WebMusicService.APPLE_MUSIC)
            if (hasSpotify) add(WebMusicService.SPOTIFY)
            if (hasYouTube) add(WebMusicService.YOUTUBE_MUSIC)
            if (hasNetEase) add(WebMusicService.NETEASE_MUSIC)
            if (hasQq) add(WebMusicService.QQ_MUSIC)
        }
        return candidates.singleOrNull()
    }
}

/**
 * 音乐页当前选中的音乐源：
 * - [MusicSourceKind.LOCAL]：本机音乐库；
 * - [MusicSourceKind.SERVER]：Plex、Navidrome / Subsonic、Emby、Jellyfin 音乐库，或 WebDAV / OpenList / 网盘音乐目录；
 * - [MusicSourceKind.APPLE_MUSIC] / [MusicSourceKind.SPOTIFY]：应用内登录直连流媒体音乐源。
 */
data class MusicSourceSpec(
    val kind: MusicSourceKind = MusicSourceKind.LOCAL,
    val serverId: String? = null,
    val sectionKey: String? = null,
    val folderPath: String = "",
    val label: String = "本机音乐",
) {
    val webService: WebMusicService? get() = WebMusicService.fromSourceKind(kind)
    val isWebStreaming: Boolean get() = webService != null
    val isLocal: Boolean get() = kind == MusicSourceKind.LOCAL || (kind == MusicSourceKind.SERVER && serverId.isNullOrBlank())
}

object MusicSourceStore {
    private const val PREFS = "music_source"
    private const val KEY_KIND = "kind"
    private const val KEY_SERVER_ID = "server_id"
    private const val KEY_SECTION_KEY = "section_key"
    private const val KEY_FOLDER_PATH = "folder_path"
    private const val KEY_LABEL = "label"

    val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "m4a", "aac", "wav", "ogg", "opus",
        "ape", "alac", "wma", "dsf", "dff", "aiff", "aif", "mid", "mka",
    )

    private val _active = MutableStateFlow(MusicSourceSpec())

    @Volatile
    private var initialized = false

    fun ensureInit(context: Context): StateFlow<MusicSourceSpec> {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    _active.value = load(context.applicationContext)
                    initialized = true
                }
            }
        }
        return _active.asStateFlow()
    }

    fun active(context: Context): MusicSourceSpec {
        ensureInit(context)
        return _active.value
    }

    fun selectLocal(context: Context) {
        save(context, MusicSourceSpec(kind = MusicSourceKind.LOCAL, label = "本机音乐"))
    }

    fun selectWebService(context: Context, service: WebMusicService) {
        save(context, MusicSourceSpec(kind = service.sourceKind, label = service.label))
    }

    fun selectServer(
        context: Context,
        server: Server,
        sectionKey: String? = null,
        folderPath: String = "",
        customLabel: String? = null,
    ) {
        val cleanFolder = folderPath.trim()
        val display = customLabel?.takeIf { it.isNotBlank() } ?: buildString {
            append(server.name.ifBlank { server.type.label })
            if (cleanFolder.isNotEmpty() && cleanFolder != "/" && cleanFolder != server.rootPath) {
                append(" · ")
                append(cleanFolder.trim('/').substringAfterLast('/'))
            }
        }
        save(
            context,
            MusicSourceSpec(
                kind = MusicSourceKind.SERVER,
                serverId = server.id,
                sectionKey = sectionKey?.takeIf { it.isNotBlank() },
                folderPath = cleanFolder,
                label = display,
            ),
        )
    }

    fun resolveEffective(spec: MusicSourceSpec, servers: List<Server>): Pair<MusicSourceSpec, Server?> {
        val web = spec.webService
        if (web != null) return MusicSourceSpec(kind = web.sourceKind, label = web.label) to null
        if (spec.isLocal) return MusicSourceSpec(kind = MusicSourceKind.LOCAL, label = "本机音乐") to null
        val srv = servers.firstOrNull { it.id == spec.serverId }
            ?: return MusicSourceSpec(kind = MusicSourceKind.LOCAL, label = "本机音乐") to null
        return spec to srv
    }

    private fun load(app: Context): MusicSourceSpec {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kind = runCatching { MusicSourceKind.valueOf(p.getString(KEY_KIND, null) ?: "LOCAL") }
            .getOrDefault(MusicSourceKind.LOCAL)
        val web = WebMusicService.fromSourceKind(kind)
        if (web != null) {
            return MusicSourceSpec(kind = web.sourceKind, label = web.label)
        }
        val serverId = p.getString(KEY_SERVER_ID, null)?.takeIf { it.isNotBlank() }
        if (kind == MusicSourceKind.LOCAL || serverId == null) {
            return MusicSourceSpec(kind = MusicSourceKind.LOCAL, label = "本机音乐")
        }
        return MusicSourceSpec(
            kind = MusicSourceKind.SERVER,
            serverId = serverId,
            sectionKey = p.getString(KEY_SECTION_KEY, null)?.takeIf { it.isNotBlank() },
            folderPath = p.getString(KEY_FOLDER_PATH, "") ?: "",
            label = p.getString(KEY_LABEL, null)?.takeIf { it.isNotBlank() } ?: "远程音乐库",
        )
    }

    private fun save(context: Context, spec: MusicSourceSpec) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_KIND, spec.kind.name)
            .putString(KEY_SERVER_ID, spec.serverId)
            .putString(KEY_SECTION_KEY, spec.sectionKey)
            .putString(KEY_FOLDER_PATH, spec.folderPath)
            .putString(KEY_LABEL, spec.label)
            .apply()
        initialized = true
        _active.value = spec
    }

    fun isAudioFileName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    /** 是否属于具备结构化专辑/歌手/歌单 API 的专用音乐库服务端（Plex、Navidrome/Subsonic、Emby、Jellyfin） */
    fun isStructuredMusicServer(type: ServerType): Boolean =
        type == ServerType.PLEX || type == ServerType.SUBSONIC || type == ServerType.EMBY || type == ServerType.JELLYFIN

    suspend fun discoverMusicSections(client: RemoteClient): List<ServerSection> = when (client) {
        is SubsonicClient -> client.sections()
        is MediaBrowser -> client.sections().filter { it.type == "artist" || it.type == "music" }
        else -> emptyList()
    }

    private suspend fun resolvePlexMusicSection(client: PlexClient, preferredKey: String?): Pair<String?, Set<String>> {
        val secs = discoverMusicSections(client)
        val valid = secs.map { it.key }.toSet()
        val chosen = preferredKey?.takeIf { it in valid } ?: secs.firstOrNull()?.key
        return chosen to valid
    }

    suspend fun loadServerTracks(client: RemoteClient, sectionKey: String?): List<ServerItem> = when (client) {
        is SubsonicClient -> client.musicTracks(sectionKey)
        is PlexClient -> {
            val sec = resolvePlexMusicSection(client, sectionKey).first ?: return emptyList()
            client.musicTracks(sec)
        }
        is EmbyClient -> {
            val sec = sectionKey ?: discoverMusicSections(client).firstOrNull()?.key
            client.musicTracks(sec)
        }
        else -> emptyList()
    }

    suspend fun loadServerAlbums(client: RemoteClient, sectionKey: String?): List<ServerItem> = when (client) {
        is SubsonicClient -> client.musicAlbums(musicFolderId = sectionKey)
        is PlexClient -> {
            val sec = resolvePlexMusicSection(client, sectionKey).first ?: return emptyList()
            client.musicAlbums(sec)
        }
        is EmbyClient -> {
            val sec = sectionKey ?: discoverMusicSections(client).firstOrNull()?.key
            client.musicAlbums(sec)
        }
        else -> emptyList()
    }

    suspend fun loadServerArtists(client: RemoteClient, sectionKey: String?): List<ServerItem> = when (client) {
        is SubsonicClient -> client.musicArtists(musicFolderId = sectionKey)
        is PlexClient -> {
            val sec = resolvePlexMusicSection(client, sectionKey).first ?: return emptyList()
            client.musicArtists(sec)
        }
        is EmbyClient -> {
            val sec = sectionKey ?: discoverMusicSections(client).firstOrNull()?.key
            client.musicArtists(sec)
        }
        else -> emptyList()
    }

    suspend fun loadServerPlaylists(client: RemoteClient, sectionKey: String? = null): List<ServerItem> = when (client) {
        is SubsonicClient -> client.musicPlaylists()
        is PlexClient -> {
            val (sec, valid) = resolvePlexMusicSection(client, sectionKey)
            client.musicPlaylists(sectionKey = sec, validSectionKeys = valid)
        }
        is EmbyClient -> client.musicPlaylists()
        else -> emptyList()
    }

    suspend fun loadContainerChildren(client: RemoteClient, container: ServerItem): List<ServerItem> = when {
        container.type == "playlist" && client is PlexClient -> client.playlistTracks(container.ratingKey)
        container.type == "playlist" && client is SubsonicClient -> client.playlistTracks(container.ratingKey)
        client is MediaBrowser -> client.children(container.ratingKey)
        else -> emptyList()
    }

    suspend fun loadContainerDetail(client: RemoteClient, container: ServerItem): ServerItem? = when {
        container.type == "playlist" && client is PlexClient -> client.playlistDetail(container.ratingKey)
        client is MediaBrowser -> client.item(container.ratingKey)
        else -> null
    }

    suspend fun searchServerMusic(client: RemoteClient, query: String): List<ServerItem> = when (client) {
        is SubsonicClient -> client.searchMusic(query)
        is PlexClient -> client.searchMusic(query)
        is EmbyClient -> client.searchMusic(query)
        else -> emptyList()
    }

    suspend fun listStorageMusicFolder(
        client: RemoteClient,
        path: String,
        folderDisplayName: String,
    ): Pair<List<RemoteItem>, List<MediaEntry>> {
        val raw = client.list(path)
        val dirs = raw.filter { it.isDir }.sortedBy { it.name.lowercase() }
        val tracks = raw.mapNotNull { item ->
            if (item.isDir || item.playUrl == null || !isAudioFileName(item.name)) null
            else MediaEntry(
                uri = item.playUrl,
                title = item.name,
                folder = folderDisplayName,
                sizeBytes = item.size,
                isVideo = false,
            )
        }
        return dirs to tracks
    }
}

fun ServerItem.toMusicMediaEntry(serverId: String? = null, fallbackSubtitle: String = ""): MediaEntry? {
    val url = playUrl ?: return null
    val fn = fileName ?: "${title.replace('/', '_')}.mp3"
    val sub = listOfNotNull(grandparentTitle, parentTitle)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" - ")
        .ifBlank { fallbackSubtitle }
    if (!serverId.isNullOrBlank()) {
        com.aisub.player.remote.cloud.ServerProgress.register(
            url,
            com.aisub.player.remote.cloud.ServerProgress.Entry(
                serverId = serverId,
                ratingKey = ratingKey,
                durationMs = durationMs,
                viewOffsetMs = viewOffsetMs,
                title = title.takeIf { it.isNotBlank() },
                artist = grandparentTitle?.takeIf { it.isNotBlank() },
                album = parentTitle?.takeIf { it.isNotBlank() },
                coverUrl = thumb ?: showThumb ?: art,
            ),
        )
    }
    return MediaEntry(
        uri = url,
        title = fn,
        folder = sub,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        isVideo = false,
    )
}
