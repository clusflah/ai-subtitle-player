package com.aisub.player.remote.cloud

import java.util.concurrent.ConcurrentHashMap

/**
 * 媒体服务器（Plex、Emby / Jellyfin）按官方客户端的方式浏览：首页各行、媒体库、详情、子条目、已看标记、播放进度。
 * 条目类型统一用 Plex 的叫法（movie / show / season / episode / artist / album / track）。
 */
interface MediaBrowser {
    suspend fun sections(): List<ServerSection>
    suspend fun hubs(): List<ServerHub>
    suspend fun library(sectionKey: String, sort: LibrarySort, unwatchedOnly: Boolean): List<ServerItem>
    suspend fun item(ratingKey: String): ServerItem?
    /** 剧集的各季、季的各集、歌手的专辑、专辑的曲目 */
    suspend fun children(ratingKey: String): List<ServerItem>
    suspend fun setWatched(ratingKey: String, watched: Boolean)

    /** 报告播放进度（阻塞调用）。state：playing / paused / stopped */
    fun timeline(ratingKey: String, state: String, timeMs: Long, durationMs: Long)

    /** 媒体库按文件夹浏览时的定位符 */
    fun folderPath(section: ServerSection): String
}

enum class LibrarySort(val label: String) { NAME("名称"), ADDED("最近添加"), RELEASED("上映日期"), RATING("评分") }

/** 媒体库。type：movie / show 用海报墙浏览，其他（音乐、照片、混合）按文件夹浏览。 */
data class ServerSection(val key: String, val title: String, val type: String)

/** 首页的一行：继续观看、最近添加等。 */
data class ServerHub(val id: String, val title: String, val items: List<ServerItem>)

data class ServerRole(val name: String, val role: String?, val thumb: String?)

/**
 * 媒体服务器的一个条目：电影、剧集、季、单集、歌手、专辑等，给官方客户端风格的浏览页用。
 * 图片地址已经换成服务器缩放后的地址；能播放的条目带 playUrl 和带扩展名的 fileName。
 */
data class ServerItem(
    val ratingKey: String,
    /** movie / show / season / episode / artist / album / track / collection … */
    val type: String,
    val title: String,
    val year: Int? = null,
    val summary: String = "",
    /** 竖版海报；单集是 16:9 截图 */
    val thumb: String? = null,
    /** 剧集海报（单集、季用来显示所属剧集） */
    val showThumb: String? = null,
    val art: String? = null,
    val durationMs: Long = 0,
    val viewOffsetMs: Long = 0,
    val viewCount: Int = 0,
    val leafCount: Int = 0,
    val viewedLeafCount: Int = 0,
    val childCount: Int = 0,
    /** 第几季 / 第几集 */
    val index: Int? = null,
    val parentIndex: Int? = null,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val parentRatingKey: String? = null,
    val grandparentRatingKey: String? = null,
    val rating: Double? = null,
    val contentRating: String? = null,
    val studio: String? = null,
    val genres: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val roles: List<ServerRole> = emptyList(),
    val videoInfo: String? = null,
    val fileName: String? = null,
    val playUrl: String? = null,
    val sizeBytes: Long = 0,
) {
    val isFolder get() = type in setOf("show", "season", "artist", "album", "collection")

    /** 看完了：剧集、季看所有集；其他看播放次数 */
    val watched get() = if (type == "show" || type == "season") leafCount > 0 && viewedLeafCount >= leafCount else viewCount > 0

    /** 没看完的进度 0..1；没看过或看完了为 null */
    val progress: Float? get() = if (durationMs > 0 && viewOffsetMs > 0) (viewOffsetMs.toFloat() / durationMs).coerceIn(0f, 1f) else null

    /** 还没看的集数（剧集、季） */
    val unwatchedLeaves get() = (leafCount - viewedLeafCount).coerceAtLeast(0)

    /** 「S1 · E3」这样的集号 */
    val episodeLabel: String? get() = if (type == "episode" && index != null) "S${parentIndex ?: 1} · E$index" else null
}

/**
 * 正在播放的地址对应哪个 Plex 条目，播放器据此把进度报给服务器（继续观看、已看标记跟官方客户端同步）。
 * 浏览时登记；viewOffset 是服务器上记的进度，用来「继续播放」。
 */
object ServerProgress {
    data class Entry(val serverId: String, val ratingKey: String, val durationMs: Long, val viewOffsetMs: Long)

    private val byUri = ConcurrentHashMap<String, Entry>()

    fun register(uri: String, e: Entry) {
        byUri[uri] = e
    }

    fun find(uri: String): Entry? = byUri[uri]

    /** 本地播放时更新记下的进度，再次打开时从这里继续 */
    fun updateOffset(uri: String, offsetMs: Long) {
        byUri[uri]?.let { byUri[uri] = it.copy(viewOffsetMs = offsetMs) }
    }
}
