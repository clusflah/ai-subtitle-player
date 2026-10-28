package com.aisub.player

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisub.player.media.AppStore
import com.aisub.player.media.FavoriteFolder
import com.aisub.player.media.HistoryItem
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.MediaLibrary
import com.aisub.player.media.Playlist
import com.aisub.player.media.SortOrder
import com.aisub.player.media.ViewPrefs
import com.aisub.player.subtitle.SubtitleStore
import com.aisub.player.subtitle.searchCached
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val favoriteFolders: List<FavoriteFolder> = emptyList(),
    val servers: List<Server> = emptyList(),
    val videoSort: SortOrder = SortOrder.RECENT,
    val audioSort: SortOrder = SortOrder.RECENT,
    val videoGrid: Boolean = true,
    /** 字幕内容匹配搜索词的视频地址 */
    val subtitleMatches: Set<String> = emptySet(),
)

/** 首页的数据：本地媒体库、历史、播放列表、网络服务器。 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AppStore(app)
    private val clients = HashMap<String, RemoteClient>()
    private val viewPrefs = ViewPrefs(app)
    private val subtitles = SubtitleStore(app)
    private var searchJob: Job? = null

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // ---- 首页界面状态：进播放页再回来时还是原来的标签和滚动位置 ----

    /** 当前标签（HomeScreen 里 Tab 的序号） */
    var homeTab by androidx.compose.runtime.mutableIntStateOf(0)

    /** 「本机」标签显示视频还是音乐 */
    var localShowsVideo by androidx.compose.runtime.mutableStateOf(true)

    private val listStates = HashMap<String, androidx.compose.foundation.lazy.LazyListState>()
    private val gridStates = HashMap<String, androidx.compose.foundation.lazy.grid.LazyGridState>()

    fun listState(key: String) = listStates.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() }
    fun gridState(key: String) = gridStates.getOrPut(key) { androidx.compose.foundation.lazy.grid.LazyGridState() }

    /** 首页各媒体服务器的「继续观看」，上次读到的先显示，避免每次回首页都闪一下 */
    val serverContinue = androidx.compose.runtime.mutableStateMapOf<String, List<com.aisub.player.remote.cloud.ServerItem>>()

    init {
        reloadStored()
        _state.update {
            it.copy(videoSort = viewPrefs.sort("video"), audioSort = viewPrefs.sort("audio"), videoGrid = viewPrefs.grid("video"))
        }
    }

    fun setSort(page: String, order: SortOrder) {
        viewPrefs.setSort(page, order)
        _state.update { if (page == "video") it.copy(videoSort = order) else it.copy(audioSort = order) }
    }

    fun setVideoGrid(grid: Boolean) {
        viewPrefs.setGrid("video", grid)
        _state.update { it.copy(videoGrid = grid) }
    }

    /** 在已生成的字幕里搜索（读缓存文件，放后台）。 */
    fun searchSubtitles(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _state.update { it.copy(subtitleMatches = emptySet()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            val uris = (_state.value.videos + _state.value.audios).map { it.uri }
            val matches = withContext(Dispatchers.IO) { subtitles.searchCached(uris, query) }
            _state.update { it.copy(subtitleMatches = matches) }
        }
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
        _state.update {
            it.copy(history = store.history(), playlists = store.playlists(), favoriteFolders = store.favoriteFolders(), servers = store.servers())
        }
    }

    fun clearHistory(music: Boolean) {
        store.clearHistory(music)
        reloadStored()
    }

    // ---- 收藏的文件夹 ----

    fun isFavoriteFolder(serverId: String?, path: String) = _state.value.favoriteFolders.any { it.serverId == serverId && it.path == path }

    /** 收藏或取消收藏；返回之后是否处于收藏状态 */
    fun toggleFavoriteFolder(serverId: String?, path: String, title: String): Boolean {
        val list = store.favoriteFolders()
        val exists = list.any { it.serverId == serverId && it.path == path }
        store.saveFavoriteFolders(
            if (exists) list.filter { !(it.serverId == serverId && it.path == path) }
            else listOf(FavoriteFolder(serverId, path, title, System.currentTimeMillis())) + list,
        )
        reloadStored()
        return !exists
    }

    // ---- 播放列表 ----

    fun createPlaylist(name: String, first: MediaEntry? = null) {
        val p = store.createPlaylist(name.ifBlank { "新建播放列表" })
        if (first != null) addToPlaylist(p.id, first) else reloadStored()
    }

    fun addToPlaylist(id: String, entry: MediaEntry) = editPlaylist(id) { p ->
        if (p.items.any { it.uri == entry.uri }) p else p.copy(items = p.items + entry)
    }

    /** 新建播放列表并加入多个条目。 */
    fun createPlaylistWith(name: String, entries: List<MediaEntry>) {
        val p = store.createPlaylist(name.ifBlank { "新建播放列表" })
        editPlaylist(p.id) { it.copy(items = entries.distinctBy { e -> e.uri }) }
    }

    /** 用最近播放的内容新建一个播放列表。 */
    fun playlistFromHistory() {
        val p = store.createPlaylist("最近播放")
        editPlaylist(p.id) { it.copy(items = store.history().map { h -> h.entry }) }
    }

    /** 调整列表内顺序：delta = -1 上移，+1 下移。 */
    fun moveInPlaylist(id: String, uri: String, delta: Int) = editPlaylist(id) { p ->
        val i = p.items.indexOfFirst { it.uri == uri }
        val j = i + delta
        if (i < 0 || j !in p.items.indices) p
        else p.copy(items = p.items.toMutableList().also { l -> l.add(j, l.removeAt(i)) })
    }

    fun removeFromPlaylist(id: String, uri: String) = editPlaylist(id) { p -> p.copy(items = p.items.filter { it.uri != uri }) }

    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name) }

    /** 修改一个列表并刷新它的更新时间。 */
    private fun editPlaylist(id: String, edit: (Playlist) -> Playlist) = editPlaylists { list ->
        list.map { if (it.id == id) edit(it).copy(updatedAt = System.currentTimeMillis()) else it }
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
