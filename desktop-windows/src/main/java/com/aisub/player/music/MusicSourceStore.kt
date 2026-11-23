package com.aisub.player.music

import android.content.Context
import com.aisub.player.media.MediaEntry
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
}

/**
 * 音乐页当前选中的音乐源：
 * - [MusicSourceKind.LOCAL]：本机音乐库；
 * - [MusicSourceKind.SERVER]：Plex、Navidrome / Subsonic、Emby、Jellyfin 音乐库，或 WebDAV / OpenList / 网盘音乐目录。
 */
data class MusicSourceSpec(
    val kind: MusicSourceKind = MusicSourceKind.LOCAL,
    val serverId: String? = null,
    val sectionKey: String? = null,
    val folderPath: String = "",
    val label: String = "本机音乐",
) {
    val isLocal: Boolean get() = kind == MusicSourceKind.LOCAL || serverId.isNullOrBlank()
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
        if (spec.isLocal) return MusicSourceSpec(kind = MusicSourceKind.LOCAL, label = "本机音乐") to null
        val srv = servers.firstOrNull { it.id == spec.serverId }
            ?: return MusicSourceSpec(kind = MusicSourceKind.LOCAL, label = "本机音乐") to null
        return spec to srv
    }

    private fun load(app: Context): MusicSourceSpec {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kind = runCatching { MusicSourceKind.valueOf(p.getString(KEY_KIND, null) ?: "LOCAL") }
            .getOrDefault(MusicSourceKind.LOCAL)
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
