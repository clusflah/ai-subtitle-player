package com.aisub.player.media

import android.content.Context
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Server
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HistoryItem(val entry: MediaEntry, val positionMs: Long, val playedAt: Long)

data class Playlist(val id: String, val name: String, val items: List<MediaEntry>)

/** 播放历史、播放列表、网络服务器的持久化（SharedPreferences + JSON）。 */
class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    init {
        servers().forEach(AuthRegistry::register)
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

    fun clearHistory() = prefs.edit().remove("history").apply()

    // ---- 播放列表 ----

    fun playlists(): List<Playlist> = readArray("playlists").map { o ->
        val arr = o.getJSONArray("items")
        Playlist(o.getString("id"), o.getString("name"), List(arr.length()) { MediaEntry.fromJson(arr.getJSONObject(it)) })
    }

    fun savePlaylists(list: List<Playlist>) = writeArray("playlists", list.map { p ->
        JSONObject().put("id", p.id).put("name", p.name).put("items", JSONArray(p.items.map { it.toJson() }))
    })

    fun createPlaylist(name: String): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name, emptyList())
        savePlaylists(playlists() + p)
        return p
    }

    // ---- 网络服务器 ----

    fun servers(): List<Server> = readArray("servers").map(Server::fromJson)

    fun saveServer(server: Server) {
        val list = servers().filter { it.id != server.id } + server
        writeArray("servers", list.map { it.toJson() })
        AuthRegistry.register(server)
    }

    fun deleteServer(id: String) {
        servers().firstOrNull { it.id == id }?.let(AuthRegistry::unregister)
        writeArray("servers", servers().filter { it.id != id }.map { it.toJson() })
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
    }
}
