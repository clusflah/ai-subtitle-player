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
    /** 当前分组；null 为全部 */
    val group: String? = null,
    val query: String = "",
) {
    val groups: List<String> get() = channels.map { it.group }.filter { it.isNotBlank() }.distinct()

    /** 按分组和搜索词筛过的频道 */
    val visible: List<LiveChannel>
        get() = channels.filter { c ->
            (group == null || c.group == group) && (query.isBlank() || c.name.contains(query.trim(), ignoreCase = true))
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
        _state.update { it.copy(sources = sources) }
        (sources.firstOrNull { it.id == store.lastSourceId } ?: sources.firstOrNull())?.let { select(it) }
    }

    /** 切到某个源：先显示缓存，再联网更新 */
    fun select(source: LiveSource, refresh: Boolean = true) {
        store.lastSourceId = source.id
        val cached = store.cached(source)
        _state.update { it.copy(selected = source, channels = cached.orEmpty(), group = null, error = null) }
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
                    _state.update { s -> s.copy(channels = channels, group = s.group?.takeIf { g -> channels.any { it.group == g } }) }
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
        _state.update { it.copy(sources = sources, selected = null, channels = emptyList(), error = null) }
        sources.firstOrNull()?.let { select(it, refresh = false) }
    }

    fun setGroup(group: String?) = _state.update { it.copy(group = group) }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    /** 播放列表：当前筛选下的频道，切台就在这些里面切 */
    fun playlist(): List<MediaEntry> = _state.value.visible.map { c ->
        LiveHeaders.register(c)
        MediaEntry(c.url, c.name, isLive = true, logo = c.logo, folder = c.group)
    }
}
