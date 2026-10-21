package com.aisub.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisub.player.media.AppStore
import com.aisub.player.media.HistoryItem
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.MediaLibrary
import com.aisub.player.media.Playlist
import com.aisub.player.remote.RemoteClient
import com.aisub.player.remote.Server
import com.aisub.player.remote.clientFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryState(
    val hasPermission: Boolean = false,
    val loading: Boolean = false,
    val videos: List<MediaEntry> = emptyList(),
    val audios: List<MediaEntry> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val servers: List<Server> = emptyList(),
)

/** 首页的数据：本地媒体库、历史、播放列表、网络服务器。 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AppStore(app)
    private val clients = HashMap<String, RemoteClient>()

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    init {
        reloadStored()
    }

    fun onPermission(granted: Boolean) {
        _state.update { it.copy(hasPermission = granted) }
        if (granted) refreshMedia()
    }

    fun refreshMedia() {
        if (!_state.value.hasPermission) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val (videos, audios) = withContext(Dispatchers.IO) {
                runCatching { MediaLibrary.videos(getApplication()) to MediaLibrary.audios(getApplication()) }
                    .getOrDefault(emptyList<MediaEntry>() to emptyList())
            }
            _state.update { it.copy(videos = videos, audios = audios, loading = false) }
        }
    }

    /** 历史、播放列表、服务器存在本地，回到首页时重新读一次。 */
    fun reloadStored() {
        _state.update { it.copy(history = store.history(), playlists = store.playlists(), servers = store.servers()) }
    }

    fun clearHistory() {
        store.clearHistory()
        reloadStored()
    }

    // ---- 播放列表 ----

    fun createPlaylist(name: String, first: MediaEntry? = null) {
        val p = store.createPlaylist(name.ifBlank { "新建播放列表" })
        if (first != null) addToPlaylist(p.id, first) else reloadStored()
    }

    fun addToPlaylist(id: String, entry: MediaEntry) = editPlaylists { list ->
        list.map { if (it.id == id && it.items.none { e -> e.uri == entry.uri }) it.copy(items = it.items + entry) else it }
    }

    fun removeFromPlaylist(id: String, uri: String) = editPlaylists { list ->
        list.map { if (it.id == id) it.copy(items = it.items.filter { e -> e.uri != uri }) else it }
    }

    fun renamePlaylist(id: String, name: String) = editPlaylists { list ->
        list.map { if (it.id == id) it.copy(name = name) else it }
    }

    fun deletePlaylist(id: String) = editPlaylists { list -> list.filter { it.id != id } }

    private fun editPlaylists(edit: (List<Playlist>) -> List<Playlist>) {
        store.savePlaylists(edit(store.playlists()))
        reloadStored()
    }

    // ---- 网络服务器 ----

    fun saveServer(server: Server) {
        store.saveServer(server)
        clients.remove(server.id)
        reloadStored()
    }

    fun deleteServer(id: String) {
        store.deleteServer(id)
        clients.remove(id)
        reloadStored()
    }

    fun server(id: String): Server? = _state.value.servers.firstOrNull { it.id == id }

    /** 同一服务器复用客户端，OpenList 的登录 token 不用每次重新获取。 */
    fun client(server: Server): RemoteClient =
        if (server.type.isCloud) clientFor(server) // 网盘客户端由 CloudRegistry 统一管理
        else clients.getOrPut(server.id) { clientFor(server) }
}
