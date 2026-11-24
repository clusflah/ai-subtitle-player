package com.voxmusic.app.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HistoryItem(val entry: MediaEntry, val positionMs: Long, val playedAt: Long)

data class PlaybackStats(val musicMs: Long = 0L, val starts: Long = 0L)

data class Playlist(val id: String, val name: String, val items: List<MediaEntry>, val updatedAt: Long = 0L)

/** 上次播放队列、当前曲目下标与播放进度快照（用于应用关闭重开后恢复）。 */
data class SavedPlaybackSession(
    val queue: List<MediaEntry>,
    val index: Int,
    val positionMs: Long,
    val savedAt: Long = System.currentTimeMillis(),
)

/** 播放页点「♡」、小组件点「♡」的歌放在这个歌单里 */
const val LIKED_PLAYLIST = "我喜欢的音乐"

/** 音乐播放历史、自建歌单与红心收藏的持久化（SharedPreferences + JSON）。 */
class MusicAppStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("voxmusic_library", Context.MODE_PRIVATE)

    // ---- 播放历史 ----

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

    fun removeHistory(uri: String) = writeArray(
        "history",
        history().filter { it.entry.uri != uri }.map {
            JSONObject().put("entry", it.entry.toJson()).put("position", it.positionMs).put("playedAt", it.playedAt)
        },
    )

    fun clearHistory() = writeArray("history", emptyList())

    // ---- 歌单 & 红心喜欢 ----

    fun playlists(): List<Playlist> = readArray("playlists").map { o ->
        val arr = o.getJSONArray("items")
        Playlist(
            o.getString("id"),
            o.getString("name"),
            List(arr.length()) { MediaEntry.fromJson(arr.getJSONObject(it)) },
            o.optLong("updatedAt"),
        )
    }

    fun savePlaylists(list: List<Playlist>) = writeArray("playlists", list.map { p ->
        JSONObject()
            .put("id", p.id)
            .put("name", p.name)
            .put("items", JSONArray(p.items.map { it.toJson() }))
            .put("updatedAt", p.updatedAt)
    })

    fun createPlaylist(name: String): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name, emptyList(), System.currentTimeMillis())
        savePlaylists(playlists() + p)
        return p
    }

    fun deletePlaylist(id: String) {
        savePlaylists(playlists().filter { it.id != id })
    }

    fun isLiked(uri: String): Boolean =
        playlists().firstOrNull { it.name == LIKED_PLAYLIST }?.items?.any { it.uri == uri } == true

    fun toggleLiked(entry: MediaEntry): Boolean {
        val liked = playlists().firstOrNull { it.name == LIKED_PLAYLIST } ?: createPlaylist(LIKED_PLAYLIST)
        val now = liked.items.none { it.uri == entry.uri }
        val items = if (now) listOf(entry) + liked.items else liked.items.filter { it.uri != entry.uri }
        val updated = liked.copy(items = items, updatedAt = System.currentTimeMillis())
        savePlaylists(playlists().map { if (it.id == liked.id) updated else it })
        return now
    }

    // ---- 听歌统计 ----

    @Synchronized
    fun playbackStats(): PlaybackStats = runCatching {
        val o = JSONObject(prefs.getString("playbackStats", "{}")!!)
        PlaybackStats(o.optLong("music"), o.optLong("starts"))
    }.getOrDefault(PlaybackStats())

    @Synchronized
    fun recordPlayback(elapsedMs: Long, started: Boolean = false) {
        if (elapsedMs <= 0L && !started) return
        val stats = playbackStats()
        val updated = stats.copy(
            musicMs = stats.musicMs + elapsedMs.coerceAtLeast(0L),
            starts = stats.starts + if (started) 1 else 0,
        )
        prefs.edit().putString(
            "playbackStats",
            JSONObject().put("music", updated.musicMs).put("starts", updated.starts).toString(),
        ).apply()
    }

    // ---- 播放队列与进度恢复 ----

    /** 持久化完整播放队列及当前下标/进度；队列为空时自动清除已保存会话。 */
    @Synchronized
    fun savePlaybackSession(
        queue: List<MediaEntry>,
        index: Int,
        positionMs: Long,
        sync: Boolean = false,
    ) {
        if (queue.isEmpty()) {
            clearPlaybackSession(sync)
            return
        }
        val (trimmed, safeIdx) = trimQueueAroundIndex(queue, index, MAX_SAVED_QUEUE)
        val safePos = positionMs.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        val sessionJson = encodePlaybackSessionJson(
            SavedPlaybackSession(trimmed, safeIdx, safePos, now),
        )
        val cur = trimmed[safeIdx]
        val progressJson = encodePlaybackProgressJson(
            uri = cur.uri,
            index = safeIdx,
            positionMs = safePos,
            durationMs = cur.durationMs,
            savedAt = now,
        )
        val editor = prefs.edit()
            .putString(KEY_PLAYBACK_SESSION, sessionJson)
            .putString(KEY_PLAYBACK_PROGRESS, progressJson)
        if (sync) editor.commit() else editor.apply()
    }

    /** 轻量更新当前曲目下标与进度（用于播放中定时保存及拖动进度条，避免反复序列化整个队列）。 */
    @Synchronized
    fun savePlaybackProgress(
        entry: MediaEntry,
        index: Int,
        positionMs: Long,
        durationMs: Long = entry.durationMs,
        sync: Boolean = false,
    ) {
        val progressJson = encodePlaybackProgressJson(
            uri = entry.uri,
            index = index.coerceAtLeast(0),
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            savedAt = System.currentTimeMillis(),
        )
        val editor = prefs.edit().putString(KEY_PLAYBACK_PROGRESS, progressJson)
        if (sync) editor.commit() else editor.apply()
    }

    @Synchronized
    fun loadPlaybackSession(): SavedPlaybackSession? {
        val rawSession = prefs.getString(KEY_PLAYBACK_SESSION, null) ?: return null
        val rawProgress = prefs.getString(KEY_PLAYBACK_PROGRESS, null)
        return decodePlaybackSessionJson(rawSession, rawProgress)
    }

    @Synchronized
    fun clearPlaybackSession(sync: Boolean = false) {
        val editor = prefs.edit()
            .remove(KEY_PLAYBACK_SESSION)
            .remove(KEY_PLAYBACK_PROGRESS)
        if (sync) editor.commit() else editor.apply()
    }

    private fun readArray(key: String): List<JSONObject> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
        List(arr.length()) { arr.getJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun writeArray(key: String, items: List<JSONObject>) {
        prefs.edit().putString(key, JSONArray(items).toString()).apply()
    }

    companion object {
        private const val MAX_HISTORY = 80
        private const val MAX_SAVED_QUEUE = 200
        private const val KEY_PLAYBACK_SESSION = "playback_session"
        private const val KEY_PLAYBACK_PROGRESS = "playback_progress"

        internal fun trimQueueAroundIndex(
            queue: List<MediaEntry>,
            index: Int,
            maxItems: Int = MAX_SAVED_QUEUE,
        ): Pair<List<MediaEntry>, Int> {
            if (queue.isEmpty()) return emptyList<MediaEntry>() to 0
            val safeIdx = index.coerceIn(queue.indices)
            if (queue.size <= maxItems) return queue.toList() to safeIdx
            val start = (safeIdx - maxItems / 2).coerceIn(0, queue.size - maxItems)
            return queue.subList(start, start + maxItems).toList() to (safeIdx - start)
        }

        internal fun sanitizeResumePosition(positionMs: Long, durationMs: Long): Long {
            val safePos = positionMs.coerceAtLeast(0L)
            if (durationMs > 10_000L && safePos >= durationMs - 2_000L) return 0L
            return safePos
        }

        internal fun encodePlaybackSessionJson(session: SavedPlaybackSession): String {
            val itemsArr = JSONArray()
            for (entry in session.queue) {
                val obj = entry.toJson()
                val meta = TrackMetadataRegistry.find(entry.uri)
                if (meta != null) {
                    val m = JSONObject()
                        .put("sourceId", meta.sourceId)
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

        internal fun encodePlaybackProgressJson(
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

        internal fun decodePlaybackSessionJson(
            rawSession: String,
            rawProgress: String? = null,
        ): SavedPlaybackSession? = runCatching {
            val root = JSONObject(rawSession)
            val arr = root.optJSONArray("queue") ?: return null
            if (arr.length() == 0) return null
            val queue = ArrayList<MediaEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val entry = runCatching { MediaEntry.fromJson(o) }.getOrNull() ?: continue
                if (entry.uri.isBlank()) continue
                queue += entry
                val metaObj = o.optJSONObject("meta")
                if (metaObj != null) {
                    TrackMetadataRegistry.register(
                        entry.uri,
                        TrackMetadataRegistry.Entry(
                            sourceId = metaObj.optString("sourceId").ifBlank { entry.uri.substringBefore("://", "web") },
                            ratingKey = metaObj.optString("ratingKey"),
                            durationMs = metaObj.optLong("durationMs").takeIf { it > 0L } ?: entry.durationMs,
                            title = metaObj.optString("title").takeIf { it.isNotBlank() } ?: entry.displayTitle,
                            artist = metaObj.optString("artist").takeIf { it.isNotBlank() },
                            album = metaObj.optString("album").takeIf { it.isNotBlank() },
                            coverUrl = metaObj.optString("coverUrl").takeIf { it.isNotBlank() } ?: entry.coverUrl,
                        ),
                    )
                } else if (TrackMetadataRegistry.find(entry.uri) == null) {
                    val parts = entry.folder.split(" - ", limit = 2).map { it.trim() }
                    TrackMetadataRegistry.register(
                        entry.uri,
                        TrackMetadataRegistry.Entry(
                            sourceId = entry.uri.substringBefore("://", "web"),
                            ratingKey = entry.uri,
                            durationMs = entry.durationMs,
                            title = entry.displayTitle,
                            artist = parts.getOrNull(0)?.takeIf { it.isNotBlank() },
                            album = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
                            coverUrl = entry.coverUrl,
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
                                val updatedEntry = queue[matchedIdx].copy(durationMs = pDur)
                                queue[matchedIdx] = updatedEntry
                                val prevMeta = TrackMetadataRegistry.find(updatedEntry.uri)
                                if (prevMeta != null && prevMeta.durationMs <= 0L) {
                                    TrackMetadataRegistry.register(updatedEntry.uri, prevMeta.copy(durationMs = pDur))
                                }
                            }
                        }
                    }
                }
            }

            val target = queue[index]
            val finalPos = sanitizeResumePosition(positionMs, target.durationMs)
            SavedPlaybackSession(
                queue = queue,
                index = index,
                positionMs = finalPos,
                savedAt = savedAt,
            )
        }.getOrNull()
    }
}
