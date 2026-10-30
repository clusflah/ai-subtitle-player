package com.aisub.player.media

import android.content.Context
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.Server
import com.aisub.player.remote.cloud.CloudRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HistoryItem(val entry: MediaEntry, val positionMs: Long, val playedAt: Long)

data class Playlist(val id: String, val name: String, val items: List<MediaEntry>, val updatedAt: Long = 0)

/** 收藏的文件夹：serverId 为 null 是本机文件夹（path 是相对存储根目录的路径），否则是网络服务器上的目录 */
data class FavoriteFolder(val serverId: String?, val path: String, val title: String, val addedAt: Long) {
    val key: String get() = "${serverId ?: "local"}|$path"
}

/** 播放页点「♡」、小组件点「♡」的歌放在这个播放列表里 */
const val LIKED_PLAYLIST = "我喜欢的音乐"

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

    // ---- 收藏的文件夹 ----

    fun favoriteFolders(): List<FavoriteFolder> = readArray("favoriteFolders").map { o ->
        FavoriteFolder(o.optString("server").ifEmpty { null }, o.getString("path"), o.optString("title"), o.optLong("addedAt"))
    }

    fun saveFavoriteFolders(list: List<FavoriteFolder>) = writeArray("favoriteFolders", list.map { f ->
        JSONObject().put("server", f.serverId.orEmpty()).put("path", f.path).put("title", f.title).put("addedAt", f.addedAt)
    })

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
