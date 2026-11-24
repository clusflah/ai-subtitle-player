package com.voxmusic.app.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HistoryItem(val entry: MediaEntry, val positionMs: Long, val playedAt: Long)

data class PlaybackStats(val musicMs: Long = 0L, val starts: Long = 0L)

data class Playlist(val id: String, val name: String, val items: List<MediaEntry>, val updatedAt: Long = 0L)

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

    private fun readArray(key: String): List<JSONObject> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
        List(arr.length()) { arr.getJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun writeArray(key: String, items: List<JSONObject>) {
        prefs.edit().putString(key, JSONArray(items).toString()).apply()
    }

    companion object {
        private const val MAX_HISTORY = 80
    }
}
