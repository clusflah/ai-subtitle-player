package com.voxmusic.app.media

import java.util.concurrent.ConcurrentHashMap

/** 流媒体个人曲库中的单曲 / 专辑 / 歌单条目（Apple Music / Spotify 统一结构）。 */
data class CatalogItem(
    val ratingKey: String,
    val type: String, // "track" | "album" | "playlist"
    val title: String,
    val summary: String = "",
    val year: Int? = null,
    val thumb: String? = null,
    val durationMs: Long = 0L,
    val leafCount: Int = 0,
    val childCount: Int = 0,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val fileName: String? = null,
    val playUrl: String? = null,
) {
    val isFolder: Boolean get() = type != "track"
}

/**
 * 内存元数据注册表：记录每条流媒体 URI 的歌名、歌手、专辑、时长和封面地址，
 * 供播放页、队列、历史记录和桌面小组件即时读取。
 */
object TrackMetadataRegistry {
    data class Entry(
        val sourceId: String,
        val ratingKey: String,
        val durationMs: Long = 0L,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val coverUrl: String? = null,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(uri: String, entry: Entry) {
        if (uri.isNotBlank()) entries[uri] = entry
    }

    fun find(uri: String?): Entry? = uri?.let { entries[it] }
}

fun CatalogItem.toMusicMediaEntry(fallbackSubtitle: String = ""): MediaEntry? {
    val url = playUrl ?: return null
    val fn = fileName ?: "${title.replace('/', '_')}.mp3"
    val sub = listOfNotNull(grandparentTitle, parentTitle)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" - ")
        .ifBlank { fallbackSubtitle }
    TrackMetadataRegistry.register(
        url,
        TrackMetadataRegistry.Entry(
            sourceId = url.substringBefore("://", "web"),
            ratingKey = ratingKey,
            durationMs = durationMs,
            title = title.takeIf { it.isNotBlank() },
            artist = grandparentTitle?.takeIf { it.isNotBlank() },
            album = parentTitle?.takeIf { it.isNotBlank() },
            coverUrl = thumb,
        ),
    )
    return MediaEntry(
        uri = url,
        title = fn,
        durationMs = durationMs,
        folder = sub,
        coverUrl = thumb,
    )
}
