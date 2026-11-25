package com.aisub.player.media

import android.content.Context
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.Server
import com.aisub.player.remote.cloud.CloudRegistry
import com.aisub.player.remote.cloud.ServerProgress
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HistoryItem(val entry: MediaEntry, val positionMs: Long, val playedAt: Long)

/** 上次音乐播放队列、当前曲目下标与播放进度快照（用于应用关闭重开后恢复）。 */
data class SavedMusicSession(
    val queue: List<MediaEntry>,
    val index: Int,
    val positionMs: Long,
    val savedAt: Long = System.currentTimeMillis(),
)

data class PlaybackStats(val videoMs: Long = 0, val musicMs: Long = 0, val liveMs: Long = 0, val starts: Long = 0) {
    val totalMs get() = videoMs + musicMs + liveMs
}

data class Playlist(val id: String, val name: String, val items: List<MediaEntry>, val updatedAt: Long = 0)

/** 收藏的文件夹：serverId 为 null 是本机文件夹（path 是相对存储根目录的路径），否则是网络服务器上的目录 */
data class FavoriteFolder(val serverId: String?, val path: String, val title: String, val addedAt: Long) {
    val key: String get() = "${serverId ?: "local"}|$path"
}

/** 播放页点「♡」、小组件点「♡」的歌放在这个播放列表里 */
const val LIKED_PLAYLIST = "我喜欢的音乐"

/** 「稍后观看」也是一个播放列表，在「我的」里单独显示 */
const val WATCH_LATER_PLAYLIST = "稍后观看"
const val FAVORITE_VIDEOS_PLAYLIST = "收藏的视频"

/** 播放历史、播放列表、网络服务器的持久化（SharedPreferences + JSON）。 */
class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    init {
        servers().forEach { AuthRegistry.register(it); Endpoints.register(it) }
    }

    // ---- 历史 ----

    fun history(): List<HistoryItem> = readArray("history").map {
        HistoryItem(MediaEntry.fromJson(it.getJSONObject("entry")), it.optLong("position"), it.optLong("playedAt"))
    }

    fun recordHistory(entry: MediaEntry, positionMs: Long) {
        val items = history().filter { it.entry.uri != entry.uri }.toMutableList()
        items.add(0, HistoryItem(entry, positionMs, System.currentTimeMillis()))
        writeArray("history", items.take(MAX_HISTORY).map {
            JSONObject().put("entry", it.entry.toJson()).put("position", it.positionMs).put("playedAt", it.playedAt)
        })
    }

    fun positionOf(uri: String): Long = history().firstOrNull { it.entry.uri == uri }?.positionMs ?: 0L

    /** 删掉一条播放记录 */
    fun removeHistory(uri: String) = writeArray(
        "history",
        history().filter { it.entry.uri != uri }.map {
            JSONObject().put("entry", it.entry.toJson()).put("position", it.positionMs).put("playedAt", it.playedAt)
        },
    )

    /** 只清空音乐（music = true）或视频的播放记录 */
    fun clearHistory(music: Boolean) = writeArray(
        "history",
        history().filter { it.entry.isMusic != music }.map {
            JSONObject().put("entry", it.entry.toJson()).put("position", it.positionMs).put("playedAt", it.playedAt)
        },
    )

    // ---- 播放列表 ----

    fun playlists(): List<Playlist> = readArray("playlists").map { o ->
        val arr = o.getJSONArray("items")
        Playlist(o.getString("id"), o.getString("name"), List(arr.length()) { MediaEntry.fromJson(arr.getJSONObject(it)) }, o.optLong("updatedAt"))
    }

    fun savePlaylists(list: List<Playlist>) = writeArray("playlists", list.map { p ->
        JSONObject().put("id", p.id).put("name", p.name).put("items", JSONArray(p.items.map { it.toJson() })).put("updatedAt", p.updatedAt)
    })

    fun createPlaylist(name: String): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name, emptyList(), System.currentTimeMillis())
        savePlaylists(playlists() + p)
        return p
    }

    /** 「我喜欢的音乐」里有没有这首（播放页的 ♡、桌面小组件的 ♡ 共用） */
    fun isLiked(uri: String): Boolean = playlists().firstOrNull { it.name == LIKED_PLAYLIST }?.items?.any { it.uri == uri } == true

    /** 喜欢 / 取消喜欢，返回现在是否喜欢 */
    fun toggleLiked(entry: MediaEntry): Boolean {
        val liked = playlists().firstOrNull { it.name == LIKED_PLAYLIST } ?: createPlaylist(LIKED_PLAYLIST)
        val now = liked.items.none { it.uri == entry.uri }
        val items = if (now) listOf(entry) + liked.items else liked.items.filter { it.uri != entry.uri }
        val updated = liked.copy(items = items, updatedAt = System.currentTimeMillis())
        savePlaylists(playlists().map { if (it.id == liked.id) updated else it })
        return now
    }

    /** 加入 / 移出「稍后观看」，返回现在是否在里面 */
    fun toggleWatchLater(entry: MediaEntry): Boolean {
        val list = playlists().firstOrNull { it.name == WATCH_LATER_PLAYLIST } ?: createPlaylist(WATCH_LATER_PLAYLIST)
        val now = list.items.none { it.uri == entry.uri }
        val items = if (now) list.items + entry else list.items.filter { it.uri != entry.uri }
        val updated = list.copy(items = items, updatedAt = System.currentTimeMillis())
        savePlaylists(playlists().map { if (it.id == list.id) updated else it })
        return now
    }

    fun isFavoriteVideo(uri: String) = playlists().firstOrNull { it.name == FAVORITE_VIDEOS_PLAYLIST }?.items?.any { it.uri == uri } == true

    fun toggleFavoriteVideo(entry: MediaEntry): Boolean {
        val list = playlists().firstOrNull { it.name == FAVORITE_VIDEOS_PLAYLIST } ?: createPlaylist(FAVORITE_VIDEOS_PLAYLIST)
        val now = list.items.none { it.uri == entry.uri }
        val items = if (now) listOf(entry) + list.items else list.items.filter { it.uri != entry.uri }
        val updated = list.copy(items = items, updatedAt = System.currentTimeMillis())
        savePlaylists(playlists().map { if (it.id == list.id) updated else it })
        return now
    }

    // ---- 网络服务上次打开的时间：id → 毫秒，负数表示那次连接失败 ----

    fun serverVisits(): Map<String, Long> = runCatching {
        val o = JSONObject(prefs.getString("serverVisits", "{}")!!)
        o.keys().asSequence().associateWith { o.getLong(it) }
    }.getOrDefault(emptyMap())

    fun recordServerVisit(id: String, ok: Boolean) {
        val o = runCatching { JSONObject(prefs.getString("serverVisits", "{}")!!) }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis()
        o.put(id, if (ok) now else -now)
        prefs.edit().putString("serverVisits", o.toString()).apply()
    }

    @Synchronized
    fun playbackStats(): PlaybackStats = runCatching {
        val o = JSONObject(prefs.getString("playbackStats", "{}")!!)
        PlaybackStats(o.optLong("video"), o.optLong("music"), o.optLong("live"), o.optLong("starts"))
    }.getOrDefault(PlaybackStats())

    @Synchronized
    fun recordPlayback(entry: MediaEntry, elapsedMs: Long, started: Boolean = false) {
        if (elapsedMs <= 0 && !started) return
        val stats = playbackStats()
        val updated = when {
            entry.isLive -> stats.copy(liveMs = stats.liveMs + elapsedMs, starts = stats.starts + if (started) 1 else 0)
            entry.isMusic -> stats.copy(musicMs = stats.musicMs + elapsedMs, starts = stats.starts + if (started) 1 else 0)
            else -> stats.copy(videoMs = stats.videoMs + elapsedMs, starts = stats.starts + if (started) 1 else 0)
        }
        prefs.edit().putString("playbackStats", JSONObject().put("video", updated.videoMs).put("music", updated.musicMs)
            .put("live", updated.liveMs).put("starts", updated.starts).toString()).apply()
    }

    // ---- 收藏的文件夹 ----

    fun favoriteFolders(): List<FavoriteFolder> = readArray("favoriteFolders").map { o ->
        FavoriteFolder(o.optString("server").ifEmpty { null }, o.getString("path"), o.optString("title"), o.optLong("addedAt"))
    }

    fun saveFavoriteFolders(list: List<FavoriteFolder>) = writeArray("favoriteFolders", list.map { f ->
        JSONObject().put("server", f.serverId.orEmpty()).put("path", f.path).put("title", f.title).put("addedAt", f.addedAt)
    })

    // ---- 隐藏的文件夹（和收藏同样的结构；addedAt 是隐藏的时间） ----

    fun hiddenFolders(): List<FavoriteFolder> = readArray("hiddenFolders").map { o ->
        FavoriteFolder(o.optString("server").ifEmpty { null }, o.getString("path"), o.optString("title"), o.optLong("addedAt"))
    }

    fun saveHiddenFolders(list: List<FavoriteFolder>) = writeArray("hiddenFolders", list.map { f ->
        JSONObject().put("server", f.serverId.orEmpty()).put("path", f.path).put("title", f.title).put("addedAt", f.addedAt)
    })

    /** 查看、取消隐藏要输入的密码（存的是摘要）；空 = 没设 */
    var hiddenPinHash: String
        get() = prefs.getString("hiddenPin", "")!!
        set(v) { prefs.edit().putString("hiddenPin", v).apply() }

    // ---- 网络服务器 ----

    fun servers(): List<Server> = readArray("servers").map(Server::fromJson)

    /** 用户添加或编辑服务器：配置变了，丢弃旧的网盘客户端。 */
    fun saveServer(server: Server) {
        writeServer(server)
        CloudRegistry.forget(server.id)
    }

    /** 网盘客户端刷新 token 后回写，保留客户端。 */
    fun updateServerCredentials(server: Server) = writeServer(server)

    @Synchronized
    private fun writeServer(server: Server) {
        val list = servers().map { if (it.id == server.id) server else it }
            .let { if (it.any { s -> s.id == server.id }) it else it + server }
        writeArray("servers", list.map { it.toJson() })
        AuthRegistry.register(server)
        Endpoints.register(server)
    }

    fun deleteServer(id: String) {
        servers().firstOrNull { it.id == id }?.let { AuthRegistry.unregister(it); Endpoints.unregister(it) }
        CloudRegistry.forget(id)
        writeArray("servers", servers().filter { it.id != id }.map { it.toJson() })
        saveFavoriteFolders(favoriteFolders().filter { it.serverId != id })
        saveHiddenFolders(hiddenFolders().filter { it.serverId != id })
    }

    // ---- 音乐播放列表与进度恢复 ----

    @Synchronized
    fun saveMusicSession(
        queue: List<MediaEntry>,
        index: Int,
        positionMs: Long,
        sync: Boolean = false,
    ) {
        val musicQueue = queue.filter { it.isMusic }
        if (musicQueue.isEmpty()) {
            clearMusicSession(sync)
            return
        }
        val origCurUri = queue.getOrNull(index)?.uri
        val mappedIdx = origCurUri?.let { u -> musicQueue.indexOfFirst { it.uri == u }.takeIf { it >= 0 } }
            ?: index.coerceIn(musicQueue.indices)
        val (trimmed, safeIdx) = trimMusicQueueAroundIndex(musicQueue, mappedIdx, MAX_SAVED_MUSIC_QUEUE)
        val safePos = positionMs.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        val sessionJson = encodeMusicSessionJson(SavedMusicSession(trimmed, safeIdx, safePos, now))
        val cur = trimmed[safeIdx]
        val progressJson = encodeMusicProgressJson(cur.uri, safeIdx, safePos, cur.durationMs, now)
        val editor = prefs.edit()
            .putString(KEY_MUSIC_SESSION, sessionJson)
            .putString(KEY_MUSIC_PROGRESS, progressJson)
        if (sync) editor.commit() else editor.apply()
    }

    @Synchronized
    fun saveMusicProgress(
        entry: MediaEntry,
        index: Int,
        positionMs: Long,
        durationMs: Long = entry.durationMs,
        sync: Boolean = false,
    ) {
        if (!entry.isMusic) return
        val progressJson = encodeMusicProgressJson(
            uri = entry.uri,
            index = index.coerceAtLeast(0),
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            savedAt = System.currentTimeMillis(),
        )
        val editor = prefs.edit().putString(KEY_MUSIC_PROGRESS, progressJson)
        if (sync) editor.commit() else editor.apply()
    }

    @Synchronized
    fun loadMusicSession(): SavedMusicSession? {
        val rawSession = prefs.getString(KEY_MUSIC_SESSION, null) ?: return null
        val rawProgress = prefs.getString(KEY_MUSIC_PROGRESS, null)
        return decodeMusicSessionJson(rawSession, rawProgress)
    }

    @Synchronized
    fun clearMusicSession(sync: Boolean = false) {
        val editor = prefs.edit()
            .remove(KEY_MUSIC_SESSION)
            .remove(KEY_MUSIC_PROGRESS)
        if (sync) editor.commit() else editor.apply()
    }

    private fun readArray(key: String): List<JSONObject> = try {
        val arr = JSONArray(prefs.getString(key, "[]"))
        List(arr.length()) { arr.getJSONObject(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeArray(key: String, items: List<JSONObject>) {
        prefs.edit().putString(key, JSONArray(items).toString()).apply()
    }

    companion object {
        private const val MAX_HISTORY = 50
        private const val MAX_SAVED_MUSIC_QUEUE = 200
        private const val KEY_MUSIC_SESSION = "music_playback_session"
        private const val KEY_MUSIC_PROGRESS = "music_playback_progress"

        internal fun trimMusicQueueAroundIndex(
            queue: List<MediaEntry>,
            index: Int,
            maxItems: Int = MAX_SAVED_MUSIC_QUEUE,
        ): Pair<List<MediaEntry>, Int> {
            if (queue.isEmpty()) return emptyList<MediaEntry>() to 0
            val safeIdx = index.coerceIn(queue.indices)
            if (queue.size <= maxItems) return queue.toList() to safeIdx
            val start = (safeIdx - maxItems / 2).coerceIn(0, queue.size - maxItems)
            return queue.subList(start, start + maxItems).toList() to (safeIdx - start)
        }

        internal fun sanitizeMusicResumePosition(positionMs: Long, durationMs: Long): Long {
            val safePos = positionMs.coerceAtLeast(0L)
            if (durationMs > 10_000L && safePos >= durationMs - 2_000L) return 0L
            return safePos
        }

        internal fun encodeMusicSessionJson(session: SavedMusicSession): String {
            val itemsArr = JSONArray()
            for (entry in session.queue) {
                val obj = entry.toJson()
                val meta = ServerProgress.find(entry.uri)
                if (meta != null) {
                    val m = JSONObject()
                        .put("serverId", meta.serverId)
                        .put("ratingKey", meta.ratingKey)
                        .put("durationMs", meta.durationMs)
                    if (!meta.title.isNullOrBlank()) m.put("title", meta.title)
                    if (!meta.artist.isNullOrBlank()) m.put("artist", meta.artist)
                    if (!meta.album.isNullOrBlank()) m.put("album", meta.album)
                    if (!meta.coverUrl.isNullOrBlank()) m.put("coverUrl", meta.coverUrl)
                    obj.put("meta", m)
                }
                itemsArr.put(obj)
            }
            return JSONObject()
                .put("queue", itemsArr)
                .put("index", session.index)
                .put("positionMs", session.positionMs.coerceAtLeast(0L))
                .put("savedAt", session.savedAt)
                .toString()
        }

        internal fun encodeMusicProgressJson(
            uri: String,
            index: Int,
            positionMs: Long,
            durationMs: Long,
            savedAt: Long = System.currentTimeMillis(),
        ): String = JSONObject()
            .put("uri", uri)
            .put("index", index)
            .put("positionMs", positionMs.coerceAtLeast(0L))
            .put("durationMs", durationMs.coerceAtLeast(0L))
            .put("savedAt", savedAt)
            .toString()

        internal fun decodeMusicSessionJson(
            rawSession: String,
            rawProgress: String? = null,
        ): SavedMusicSession? = runCatching {
            val root = JSONObject(rawSession)
            val arr = root.optJSONArray("queue") ?: return null
            if (arr.length() == 0) return null
            val queue = ArrayList<MediaEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val entry = runCatching { MediaEntry.fromJson(o) }.getOrNull() ?: continue
                if (entry.uri.isBlank() || !entry.isMusic) continue
                queue += entry
                val metaObj = o.optJSONObject("meta")
                if (metaObj != null) {
                    ServerProgress.register(
                        entry.uri,
                        ServerProgress.Entry(
                            serverId = metaObj.optString("serverId").ifBlank { entry.uri.substringBefore("://", "web") },
                            ratingKey = metaObj.optString("ratingKey"),
                            durationMs = metaObj.optLong("durationMs").takeIf { it > 0L } ?: entry.durationMs,
                            viewOffsetMs = 0L,
                            title = metaObj.optString("title").takeIf { it.isNotBlank() } ?: entry.displayTitle,
                            artist = metaObj.optString("artist").takeIf { it.isNotBlank() },
                            album = metaObj.optString("album").takeIf { it.isNotBlank() },
                            coverUrl = metaObj.optString("coverUrl").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
            if (queue.isEmpty()) return null

            var index = root.optInt("index", 0).coerceIn(queue.indices)
            var positionMs = root.optLong("positionMs", 0L).coerceAtLeast(0L)
            var savedAt = root.optLong("savedAt", 0L)

            if (!rawProgress.isNullOrBlank()) {
                runCatching {
                    val p = JSONObject(rawProgress)
                    val pSavedAt = p.optLong("savedAt", 0L)
                    if (pSavedAt >= savedAt) {
                        val pUri = p.optString("uri")
                        val pIdx = p.optInt("index", index)
                        val matchedIdx = when {
                            pIdx in queue.indices && (pUri.isBlank() || queue[pIdx].uri == pUri) -> pIdx
                            pUri.isNotBlank() -> queue.indexOfFirst { it.uri == pUri }.takeIf { it >= 0 }
                            else -> null
                        }
                        if (matchedIdx != null) {
                            index = matchedIdx
                            positionMs = p.optLong("positionMs", positionMs).coerceAtLeast(0L)
                            savedAt = pSavedAt
                            val pDur = p.optLong("durationMs", 0L)
                            if (pDur > 0L && queue[matchedIdx].durationMs <= 0L) {
                                queue[matchedIdx] = queue[matchedIdx].copy(durationMs = pDur)
                            }
                        }
                    }
                }
            }

            val target = queue[index]
            val finalPos = sanitizeMusicResumePosition(positionMs, target.durationMs)
            SavedMusicSession(queue = queue, index = index, positionMs = finalPos, savedAt = savedAt)
        }.getOrNull()
    }
}

/** 浏览偏好：排序方式、网格/列表，按页面保存。 */
enum class SortOrder(val label: String) { RECENT("最近添加"), NAME("名称"), DURATION("时长"), SIZE("大小") }

class ViewPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("view", Context.MODE_PRIVATE)

    fun sort(page: String): SortOrder =
        runCatching { SortOrder.valueOf(prefs.getString("$page.sort", "")!!) }.getOrDefault(SortOrder.RECENT)

    fun setSort(page: String, order: SortOrder) = prefs.edit().putString("$page.sort", order.name).apply()

    fun grid(page: String): Boolean = prefs.getBoolean("$page.grid", true)

    fun setGrid(page: String, grid: Boolean) = prefs.edit().putBoolean("$page.grid", grid).apply()
}
