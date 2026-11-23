package com.aisub.player.music

import android.content.Context
import androidx.compose.runtime.Composable
import com.aisub.player.media.MediaEntry
import com.aisub.player.meta.DiscoverItem
import com.aisub.player.remote.cloud.ServerItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLEncoder

/**
 * 桌面端兼容桩实现（Windows 桌面端开源 JCEF 默认不含商业 Widevine DRM，安卓端提供完整 DRM 网页音频直连实现）。
 */
object WebStreamingMusic {
    private val _loggedIn = MutableStateFlow<Set<WebMusicService>>(emptySet())
    val loggedInServices: StateFlow<Set<WebMusicService>> = _loggedIn.asStateFlow()

    val isSupportedOnPlatform: Boolean = false

    fun refreshAuthState(context: Context): Set<WebMusicService> = emptySet()

    fun isLoggedIn(context: Context, service: WebMusicService): Boolean = false

    fun logout(context: Context, service: WebMusicService) {}

    fun serviceStatusSubtitle(context: Context, service: WebMusicService, logged: Boolean): String =
        if (logged) "已登录 · 应用内直接播放" else "点击登录账号 · 应用内直接播放"

    fun hasHighQualityConfigOption(service: WebMusicService): Boolean = false

    fun highQualityConfigBadgeText(context: Context, service: WebMusicService): String = ""

    fun isStreamingUri(uri: String?): Boolean = WebMusicService.fromUri(uri) != null

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
        val encoded = URLEncoder.encode(cleanId.ifEmpty { title }, "UTF-8")
        return MediaEntry(
            uri = "${service.uriScheme}://track/$encoded",
            title = "${title.ifBlank { service.label }.replace('/', '_')}.mp3",
            folder = artist.ifBlank { service.label },
            durationMs = durationMs,
            isVideo = false,
        )
    }

    fun buildEntryFromDiscoverItem(service: WebMusicService, item: DiscoverItem): MediaEntry =
        buildTrackEntry(
            service = service,
            trackId = null,
            title = item.title,
            artist = item.artist,
            album = item.album,
            durationMs = item.durationMs,
            coverUrl = item.posterUrl ?: item.backdropUrl,
        )

    suspend fun loadLibraryTracks(context: Context, service: WebMusicService): List<ServerItem> = emptyList()
    suspend fun loadLibraryAlbums(context: Context, service: WebMusicService): List<ServerItem> = emptyList()
    suspend fun loadLibraryPlaylists(context: Context, service: WebMusicService): List<ServerItem> = emptyList()
    suspend fun loadContainerChildren(context: Context, service: WebMusicService, container: ServerItem): List<ServerItem> = emptyList()
    suspend fun searchMusic(context: Context, service: WebMusicService, query: String): List<ServerItem> = emptyList()
}

@Composable
fun WebMusicLoginDialog(
    service: WebMusicService,
    onDismiss: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    onDismiss()
}
