package com.aisub.player.live

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisub.player.media.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LiveState(
    val sources: List<LiveSource> = emptyList(),
    val selected: LiveSource? = null,
    val channels: List<LiveChannel> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** 当前分组；null 为全部，[FAVORITES] 为收藏 */
    val group: String? = null,
    val query: String = "",
    val favorites: List<LiveChannel> = emptyList(),
) {
    val groups: List<String> get() = channels.map { it.group }.filter { it.isNotBlank() }.distinct()

    fun isFavorite(c: LiveChannel) = favorites.any { it.url == c.url }

    /** 按分组和搜索词筛过的频道 */
    val visible: List<LiveChannel>
        get() = (if (group == FAVORITES) favorites else channels).filter { c ->
            (group == null || group == FAVORITES || c.group == group) && (query.isBlank() || c.name.contains(query.trim(), ignoreCase = true))
        }

    companion object {
        /** 「收藏」这个特殊分组 */
        const val FAVORITES = "\u0000favorites"
    }
}

/** 直播页：直播源列表、当前源的频道、分组和搜索。 */
class LiveViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LiveSources(app)
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        val sources = store.list()
        val favorites = store.favorites()
        _state.update { it.copy(sources = sources, favorites = favorites, group = if (favorites.isNotEmpty()) LiveState.FAVORITES else null) }
        (sources.firstOrNull { it.id == store.lastSourceId } ?: sources.firstOrNull())?.let { select(it) }
    }

    /** 切到某个源：先显示缓存，再联网更新 */
    fun select(source: LiveSource, refresh: Boolean = true) {
        store.lastSourceId = source.id
        val cached = store.cached(source)
        _state.update { it.copy(selected = source, channels = cached.orEmpty(), group = it.group?.takeIf { g -> g == LiveState.FAVORITES }, error = null) }
        if (refresh || cached == null) refresh()
    }

    fun refresh() {
        val source = _state.value.selected ?: return
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val channels = withContext(Dispatchers.IO) { store.fetch(source) }
                if (_state.value.selected?.id == source.id) {
                    _state.update { s -> s.copy(channels = channels, group = s.group?.takeIf { g -> g == LiveState.FAVORITES || channels.any { it.group == g } }) }
                }
            } catch (e: Exception) {
                val hasCache = _state.value.channels.isNotEmpty()
                _state.update { it.copy(error = "更新频道失败：${e.message}" + if (hasCache) "（显示的是上次的频道）" else "") }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun add(name: String, url: String) {
        val source = store.add(name.ifBlank { url.substringAfterLast('/').substringBefore('?').ifBlank { "直播源" } }, url.trim())
        _state.update { it.copy(sources = store.list()) }
        select(source)
    }

    fun rename(source: LiveSource, name: String) {
        store.rename(source.id, name)
        val sources = store.list()
        _state.update { s -> s.copy(sources = sources, selected = sources.firstOrNull { it.id == s.selected?.id }) }
    }

    fun remove(source: LiveSource) {
        store.remove(source.id)
        val sources = store.list()
        _state.update { it.copy(sources = sources, selected = null, channels = emptyList(), error = null, group = it.group?.takeIf { g -> g == LiveState.FAVORITES }) }
        sources.firstOrNull()?.let { select(it, refresh = false) }
    }

    fun setGroup(group: String?) = _state.update { it.copy(group = group) }

    /** 收藏 / 取消收藏一个频道 */
    fun toggleFavorite(c: LiveChannel) {
        val list = _state.value.favorites
        val updated = if (list.any { it.url == c.url }) list.filter { it.url != c.url } else list + c
        store.setFavorites(updated)
        _state.update { s ->
            s.copy(favorites = updated, group = if (s.group == LiveState.FAVORITES && updated.isEmpty()) null else s.group)
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    /** 播放列表：当前筛选下的频道，切台就在这些里面切 */
    fun playlist(): List<MediaEntry> = _state.value.visible.map { c ->
        LiveHeaders.register(c)
        MediaEntry(c.url, c.name, isLive = true, logo = c.logo, folder = c.group)
    }
}
