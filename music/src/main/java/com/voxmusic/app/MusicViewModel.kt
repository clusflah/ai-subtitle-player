package com.voxmusic.app

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.voxmusic.app.media.HistoryItem
import com.voxmusic.app.media.LIKED_PLAYLIST
import com.voxmusic.app.media.MediaEntry
import com.voxmusic.app.media.MusicAppStore
import com.voxmusic.app.media.PlaybackStats
import com.voxmusic.app.media.Playlist
import com.voxmusic.app.media.TrackMetadataRegistry
import com.voxmusic.app.media.WebStreamingMusicPlayer
import com.voxmusic.app.meta.DiscoverItem
import com.voxmusic.app.meta.MusicDiscoverRepository
import com.voxmusic.app.meta.MusicRecommendationEngine
import com.voxmusic.app.music.MusicFeatures
import com.voxmusic.app.music.MusicSourceStore
import com.voxmusic.app.music.SmartMusicSourceResolver
import com.voxmusic.app.music.WebMusicService
import com.voxmusic.app.music.WebStreamingMusic
import com.voxmusic.app.net.AppProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class MusicUiState(
    val current: MediaEntry? = null,
    val settings: AppSettings = AppSettings(),
    val sleepAt: Long? = null,
    val loopA: Long? = null,
    val loopB: Long? = null,
    val playbackError: String? = null,
    val infiniteShuffle: Boolean = false,
    val playModeVersion: Int = 0,
    val queueVersion: Int = 0,
    val history: List<HistoryItem> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val stats: PlaybackStats = PlaybackStats(),
)

/**
 * VoxMusic 核心 ViewModel：
 * 专注管理 Apple Music / Spotify 流媒体播放队列、歌词与封面、红心/歌单/历史、睡眠定时、A-B 循环、
 * 无限随机自动补曲、系统媒体会话通知栏及桌面小组件同步。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MusicViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val store = MusicAppStore(app)
    private val playbackPrefs = app.getSharedPreferences("voxmusic_playback", Context.MODE_PRIVATE)

    val player: WebStreamingMusicPlayer = WebStreamingMusicPlayer(app) { uri, title, artist, album, durationMs, coverUrl ->
        music.onStreamingMetadataUpdated(uri, title, artist, album, durationMs, coverUrl)
    }

    private val _playerFlow = MutableStateFlow<Player>(player)
    val playerFlow: StateFlow<Player> = _playerFlow.asStateFlow()

    private val _state = MutableStateFlow(
        MusicUiState(
            settings = settingsRepo.load(),
            infiniteShuffle = playbackPrefs.getBoolean("infinite_shuffle", false),
            history = store.history(),
            playlists = store.playlists(),
            stats = store.playbackStats(),
        ),
    )
    val state: StateFlow<MusicUiState> = _state.asStateFlow()

    val music = MusicFeatures(
        app = app,
        scope = viewModelScope,
        player = { player },
        settings = { _state.value.settings },
    )

    private var queue: List<MediaEntry> = emptyList()
    private var sleepJob: Job? = null
    private var infiniteRefillJob: Job? = null
    private var playSessionStartMs: Long = 0L

    private var widgetHandover = WidgetPlayback.takeOver()

    private val session: MediaSession = MediaSession.Builder(app, player)
        .setId(UUID.randomUUID().toString())
        .setSessionActivity(
            PendingIntent.getActivity(
                app,
                0,
                Intent(app, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_PLAYER)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()
        .also { PlaybackService.session = it }

    val queueSize: Int get() = queue.size
    val currentQueue: List<MediaEntry> get() = queue
    val queueEntries: List<MediaEntry> get() = queue
    val playingMusic: Boolean get() = _state.value.current != null

    fun applyMusicPlayMode(infiniteShuffle: Boolean, shuffleEnabled: Boolean, repeatMode: Int) =
        savePlayMode(repeatMode = repeatMode, shuffleEnabled = shuffleEnabled, infiniteShuffle = infiniteShuffle)

    fun skipInQueue(next: Boolean, restartFirst: Boolean = false) {
        if (next) skipNext()
        else {
            if (restartFirst && player.currentPosition > 4_000L) player.seekTo(0L)
            else skipPrevious()
        }
    }

    fun removeQueueIndex(index: Int) = removeFromQueue(index)

    fun setAbLoopStart(startMs: Long) = setAbLoop(startMs, null)
    fun setAbLoopRange(startMs: Long, endMs: Long) = setAbLoop(startMs, endMs)
    fun clearAbLoop() = setAbLoop(null, null)

    init {
        restoreSavedPlayMode()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && _state.value.sleepAt == -1L) {
                    player.pause()
                    setSleepTimer(null)
                }
                flushPlaybackTime()
                val idx = player.currentMediaItemIndex
                val next = queue.getOrNull(idx) ?: return
                onTrackActive(next, recordStart = true)
                maybeRefillInfiniteShuffleQueue()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    if (playSessionStartMs == 0L) playSessionStartMs = System.currentTimeMillis()
                } else {
                    flushPlaybackTime()
                    savePosition()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(playbackError = error.message ?: "流媒体播放失败，请检查网络或重新登录") }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _state.update { it.copy(playbackError = null) }
                }
            }
        })

        // 同步歌曲元数据与封面至 MediaSession、通知栏锁屏及桌面小组件
        viewModelScope.launch {
            music.state.distinctUntilChanged { a, b -> a.tags == b.tags && a.cover === b.cover }.collect { ms ->
                val sp = SessionPlayer(player, musicMetadata(ms))
                session.player = sp
                NowPlayingWidget.attach(getApplication(), sp)
            }
        }
        viewModelScope.launch {
            music.state.map { it.lyrics }.distinctUntilChanged().collect { l ->
                NowPlayingWidget.setLyrics(getApplication(), l)
            }
        }
        NowPlayingWidget.attach(app, player)
    }

    private fun restoreSavedPlayMode() {
        val repeat = playbackPrefs.getInt("repeat_mode", Player.REPEAT_MODE_ALL)
        val shuffle = playbackPrefs.getBoolean("shuffle_mode", false)
        player.repeatMode = repeat
        player.shuffleModeEnabled = shuffle
    }

    fun savePlayMode(repeatMode: Int, shuffleEnabled: Boolean, infiniteShuffle: Boolean) {
        player.repeatMode = repeatMode
        player.shuffleModeEnabled = shuffleEnabled
        playbackPrefs.edit()
            .putInt("repeat_mode", repeatMode)
            .putBoolean("shuffle_mode", shuffleEnabled)
            .putBoolean("infinite_shuffle", infiniteShuffle)
            .apply()
        _state.update {
            it.copy(
                infiniteShuffle = infiniteShuffle,
                playModeVersion = it.playModeVersion + 1,
            )
        }
        if (infiniteShuffle) maybeRefillInfiniteShuffleQueue()
    }

    fun resumeWidgetPlayback() {
        val h = widgetHandover ?: return
        widgetHandover = null
        play(h.queue, h.index)
        if (!h.playing) player.pause()
    }

    fun play(entries: List<MediaEntry>, index: Int = 0) {
        if (entries.isEmpty()) return
        val safeIdx = index.coerceIn(entries.indices)
        flushPlaybackTime()
        queue = entries.toList()
        val target = queue[safeIdx]
        val items = queue.map(::itemOf)
        _state.update {
            it.copy(
                playbackError = null,
                loopA = null,
                loopB = null,
                queueVersion = it.queueVersion + 1,
            )
        }
        startPlaybackService()
        player.setMediaItems(items, safeIdx, 0L)
        player.prepare()
        player.play()
        onTrackActive(target, recordStart = true)
        maybeRefillInfiniteShuffleQueue()
    }

    /**
     * 智能播放发现/榜单歌曲或列表：
     * 自动选用已登录的 Apple Music 或 Spotify 源直接开播；若均未登录则返回 false 由界面引导登录。
     */
    fun smartPlayDiscover(
        item: DiscoverItem,
        queueItems: List<DiscoverItem> = emptyList(),
        preferredIndex: Int = 0,
    ): WebMusicService? {
        val app = getApplication<Application>()
        val service = SmartMusicSourceResolver.resolveForContext(app) ?: return null
        MusicSourceStore.select(app, service)
        val (entries, startIdx) = WebStreamingMusic.buildQueueFromDiscoverItems(
            service = service,
            currentItem = item,
            queueItems = queueItems,
            preferredIndex = preferredIndex,
        )
        play(entries, startIdx)
        return service
    }

    /** 使用明确指定的服务（如刚完成登录后自动续播）播放发现/榜单曲目 */
    fun playDiscoverWithService(
        service: WebMusicService,
        item: DiscoverItem,
        queueItems: List<DiscoverItem> = emptyList(),
        preferredIndex: Int = 0,
    ) {
        MusicSourceStore.select(getApplication(), service)
        val (entries, startIdx) = WebStreamingMusic.buildQueueFromDiscoverItems(
            service = service,
            currentItem = item,
            queueItems = queueItems,
            preferredIndex = preferredIndex,
        )
        play(entries, startIdx)
    }

    fun playQueueIndex(index: Int) {
        if (index !in queue.indices) return
        player.seekTo(index, 0L)
        player.play()
        onTrackActive(queue[index], recordStart = true)
    }

    fun removeFromQueue(index: Int) {
        if (index !in queue.indices) return
        val mutable = queue.toMutableList()
        mutable.removeAt(index)
        queue = mutable
        player.removeMediaItem(index)
        _state.update { it.copy(queueVersion = it.queueVersion + 1) }
        if (queue.isEmpty()) {
            _state.update { it.copy(current = null) }
        } else {
            val newIdx = player.currentMediaItemIndex.coerceIn(queue.indices)
            onTrackActive(queue[newIdx], recordStart = false)
        }
    }

    fun togglePlay() {
        if (player.playWhenReady) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            player.play()
        }
    }

    fun skipNext() {
        if (queue.isEmpty()) return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else if (queue.size > 1) {
            playQueueIndex((player.currentMediaItemIndex + 1) % queue.size)
        }
        maybeRefillInfiniteShuffleQueue()
    }

    fun skipPrevious() {
        if (queue.isEmpty()) return
        if (player.currentPosition > 4_000L) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else if (queue.size > 1) {
            val prev = (player.currentMediaItemIndex - 1 + queue.size) % queue.size
            playQueueIndex(prev)
        }
    }

    private fun onTrackActive(entry: MediaEntry, recordStart: Boolean) {
        _state.update { it.copy(current = entry, loopA = null, loopB = null) }
        music.onEntryChanged(entry)
        store.recordHistory(entry, 0L)
        if (recordStart) store.recordPlayback(0L, started = true)
        refreshLibraryStore()
    }

    private fun flushPlaybackTime() {
        val start = playSessionStartMs
        if (start > 0L) {
            val elapsed = (System.currentTimeMillis() - start).coerceIn(0L, 4 * 3600_000L)
            playSessionStartMs = 0L
            if (elapsed > 1_000L) {
                store.recordPlayback(elapsed, started = false)
                _state.update { it.copy(stats = store.playbackStats()) }
            }
        }
    }

    fun savePosition() {
        val cur = _state.value.current ?: return
        val dur = player.duration.takeIf { it > 0L } ?: cur.durationMs
        store.recordHistory(cur.copy(durationMs = dur), player.currentPosition.coerceAtLeast(0L))
        refreshLibraryStore()
    }

    private fun startPlaybackService() {
        val app = getApplication<Application>()
        runCatching { app.startService(Intent(app, PlaybackService::class.java)) }
    }

    fun keepPlayingInBackground(): Boolean =
        _state.value.current != null && player.playWhenReady && _state.value.settings.musicBackground

    // ---------------- 喜欢、自建歌单与历史 ----------------

    fun isLiked(uri: String): Boolean = store.isLiked(uri)

    fun toggleLiked(): Boolean {
        val cur = _state.value.current ?: return false
        val now = store.toggleLiked(cur)
        refreshLibraryStore()
        NowPlayingWidget.refresh(getApplication())
        return now
    }

    fun addCurrentToPlaylist(playlistId: String?, newName: String? = null) {
        val cur = _state.value.current ?: return
        val list = store.playlists().toMutableList()
        val target = if (playlistId != null) {
            list.firstOrNull { it.id == playlistId }
        } else {
            val name = newName?.trim().orEmpty().ifEmpty { "新建歌单" }
            store.createPlaylist(name)
        } ?: return
        val latest = store.playlists()
        val updated = latest.map { p ->
            if (p.id != target.id) p
            else if (p.items.any { it.uri == cur.uri }) p
            else p.copy(items = listOf(cur) + p.items, updatedAt = System.currentTimeMillis())
        }
        store.savePlaylists(updated)
        refreshLibraryStore()
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        store.createPlaylist(name.trim())
        refreshLibraryStore()
    }

    fun deletePlaylist(id: String) {
        store.deletePlaylist(id)
        refreshLibraryStore()
    }

    fun removeFromPlaylist(playlistId: String, uri: String) {
        val updated = store.playlists().map { p ->
            if (p.id == playlistId) p.copy(items = p.items.filterNot { it.uri == uri }, updatedAt = System.currentTimeMillis())
            else p
        }
        store.savePlaylists(updated)
        refreshLibraryStore()
    }

    fun removeHistory(uri: String) {
        store.removeHistory(uri)
        refreshLibraryStore()
    }

    fun clearHistory() {
        store.clearHistory()
        refreshLibraryStore()
    }

    fun refreshLibraryStore() {
        _state.update {
            it.copy(
                history = store.history(),
                playlists = store.playlists(),
                stats = store.playbackStats(),
            )
        }
    }

    // ---------------- 睡眠定时 & A-B 循环 ----------------

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        when {
            minutes == null -> _state.update { it.copy(sleepAt = null) }
            minutes == 0 -> _state.update { it.copy(sleepAt = -1L) }
            else -> {
                val target = System.currentTimeMillis() + minutes * 60_000L
                _state.update { it.copy(sleepAt = target) }
                sleepJob = viewModelScope.launch {
                    delay(minutes * 60_000L)
                    player.pause()
                    _state.update { it.copy(sleepAt = null) }
                }
            }
        }
    }

    fun setAbLoop(a: Long?, b: Long?) {
        _state.update { it.copy(loopA = a, loopB = b) }
    }

    fun checkAbLoop(positionMs: Long) {
        val a = _state.value.loopA ?: return
        val b = _state.value.loopB ?: return
        if (b > a && positionMs >= b) {
            player.seekTo(a)
        }
    }

    // ---------------- 无限随机自动续曲 ----------------

    private fun maybeRefillInfiniteShuffleQueue() {
        if (!_state.value.infiniteShuffle) return
        if (infiniteRefillJob?.isActive == true) return
        val remaining = queue.size - 1 - player.currentMediaItemIndex
        if (remaining > 3) return
        val cur = _state.value.current ?: return
        val service = WebMusicService.fromUri(cur.uri) ?: return
        val meta = TrackMetadataRegistry.find(cur.uri)
        val tags = music.state.value.tags
        val seedItem = DiscoverItem(
            id = "seed:${cur.uri.hashCode()}",
            kind = "music",
            title = tags?.title ?: meta?.title ?: cur.displayTitle,
            artist = tags?.artist ?: meta?.artist.orEmpty(),
            album = tags?.album ?: meta?.album.orEmpty(),
        )
        infiniteRefillJob = viewModelScope.launch {
            val candidates = withContext(Dispatchers.IO) {
                runCatching {
                    val sim = MusicRecommendationEngine.fetchSimilarSongs(
                        item = seedItem,
                        sourcePref = _state.value.settings.musicDiscoverSource,
                    )
                    if (sim.size >= 5) sim
                    else sim + MusicDiscoverRepository.fetchAppleMostPlayed("tw", 15)
                }.getOrDefault(emptyList())
            }
            val existingKeys = queue.map { it.displayTitle.lowercase() }.toHashSet()
            val added = candidates
                .filter { existingKeys.add(it.title.lowercase()) }
                .take(8)
                .map { WebStreamingMusic.buildEntryFromDiscoverItem(service, it) }
            if (added.isNotEmpty()) {
                queue = queue + added
                player.addMediaItems(added.map(::itemOf))
                _state.update { it.copy(queueVersion = it.queueVersion + 1) }
            }
        }
    }

    // ---------------- 设置 ----------------

    fun updateSettings(updated: AppSettings) {
        val prev = _state.value.settings
        settingsRepo.save(updated)
        _state.update { it.copy(settings = updated) }
        if (prev.proxyType != updated.proxyType ||
            prev.proxyHost != updated.proxyHost ||
            prev.proxyPort != updated.proxyPort ||
            prev.proxyBypassCn != updated.proxyBypassCn
        ) {
            AppProxy.apply(getApplication(), updated.proxyType, updated.proxyHost, updated.proxyPort, updated.proxyBypassCn)
        }
    }

    private fun itemOf(e: MediaEntry) = MediaItem.Builder()
        .setUri(e.uri)
        .setMediaId(e.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(e.displayTitle).build())
        .build()

    override fun onCleared() {
        flushPlaybackTime()
        savePosition()
        sleepJob?.cancel()
        infiniteRefillJob?.cancel()
        if (PlaybackService.session === session) PlaybackService.session = null
        session.release()
        NowPlayingWidget.detach(getApplication())
        player.release()
        super.onCleared()
    }

    companion object {
        const val LIKED_PLAYLIST = com.voxmusic.app.media.LIKED_PLAYLIST
    }
}
