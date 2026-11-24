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
        enrichQueueEntryMetadata(uri, durationMs, coverUrl)
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
    @Volatile
    private var cleared: Boolean = false
    private val taskRemovedCallback: () -> Unit = {
        flushPlaybackTime()
        savePosition(sync = true)
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (cleared) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && _state.value.sleepAt == -1L) {
                player.pause()
                setSleepTimer(null)
            }
            if (player.mediaItemCount == 0) return
            val idx = player.currentMediaItemIndex
            val next = queue.getOrNull(idx) ?: return
            val isIdlePaused = player.playbackState == Player.STATE_IDLE && !player.playWhenReady
            if (isIdlePaused && next.uri == _state.value.current?.uri) {
                return
            }
            flushPlaybackTime()
            onTrackActive(next, recordStart = !isIdlePaused, positionMs = player.currentPosition.coerceAtLeast(0L))
            maybeRefillInfiniteShuffleQueue()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (cleared) return
            if (isPlaying) {
                if (playSessionStartMs == 0L) playSessionStartMs = System.currentTimeMillis()
            } else {
                flushPlaybackTime()
                savePosition()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (cleared) return
            _state.update { it.copy(playbackError = error.message ?: "流媒体播放失败，请检查网络或重新登录") }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (cleared) return
            if (playbackState == Player.STATE_READY) {
                _state.update { it.copy(playbackError = null) }
            }
        }
    }

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
            if (restartFirst && player.currentPosition > 4_000L) seekTo(0L)
            else skipPrevious()
        }
    }

    fun removeQueueIndex(index: Int) = removeFromQueue(index)

    fun setAbLoopStart(startMs: Long) = setAbLoop(startMs, null)
    fun setAbLoopRange(startMs: Long, endMs: Long) = setAbLoop(startMs, endMs)
    fun clearAbLoop() = setAbLoop(null, null)

    init {
        restoreSavedPlayMode()
        PlaybackService.onBeforeTaskRemoved = taskRemovedCallback
        player.addListener(playerListener)

        if (widgetHandover == null) {
            restoreSavedPlaybackSession()
        }

        // 播放期间每 5 秒轻量持久化当前进度，防止后台进程被杀或直接划掉丢失进度
        viewModelScope.launch {
            while (true) {
                delay(5_000L)
                if (queue.isNotEmpty() && _state.value.current != null && player.isPlaying) {
                    saveProgressOnly()
                }
            }
        }

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

    private fun restoreSavedPlaybackSession() {
        val saved = store.loadPlaybackSession() ?: return
        if (saved.queue.isEmpty()) return
        val safeIdx = saved.index.coerceIn(saved.queue.indices)
        val target = saved.queue[safeIdx]
        val safePos = MusicAppStore.sanitizeResumePosition(saved.positionMs, target.durationMs)
        queue = saved.queue
        WebMusicService.fromUri(target.uri)?.let { svc ->
            MusicSourceStore.select(getApplication(), svc)
        }
        _state.update {
            it.copy(
                current = target,
                playbackError = null,
                loopA = null,
                loopB = null,
                queueVersion = it.queueVersion + 1,
            )
        }
        // 恢复上次播放列表与精确进度，保持暂停态（点播放后直接从上次进度继续）
        player.setMediaItems(queue.map(::itemOf), safeIdx, safePos)
        music.onEntryChanged(target)
    }

    private fun enrichQueueEntryMetadata(uri: String, durationMs: Long, coverUrl: String?) {
        if (durationMs <= 0L && coverUrl.isNullOrBlank()) return
        val idx = queue.indexOfFirst { it.uri == uri }
        if (idx < 0) return
        val old = queue[idx]
        val newDur = if (durationMs > 0L) durationMs else old.durationMs
        val newCover = old.coverUrl ?: coverUrl?.takeIf { it.isNotBlank() }
        if (newDur == old.durationMs && newCover == old.coverUrl) return
        val updated = old.copy(durationMs = newDur, coverUrl = newCover)
        queue = queue.toMutableList().apply { this[idx] = updated }
        if (_state.value.current?.uri == uri) {
            _state.update { it.copy(current = updated) }
        }
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
        play(h.queue, h.index, h.positionMs)
        if (!h.playing) player.pause()
    }

    fun play(entries: List<MediaEntry>, index: Int = 0, startPositionMs: Long = 0L) {
        if (entries.isEmpty()) return
        val safeIdx = index.coerceIn(entries.indices)
        val safePos = startPositionMs.coerceAtLeast(0L)
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
        player.setMediaItems(items, safeIdx, safePos)
        player.prepare()
        player.play()
        onTrackActive(target, recordStart = true, positionMs = safePos)
        store.savePlaybackSession(queue, safeIdx, safePos)
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
        startPlaybackService()
        player.seekTo(index, 0L)
        player.play()
        onTrackActive(queue[index], recordStart = true, positionMs = 0L)
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
            store.clearPlaybackSession()
        } else {
            val newIdx = player.currentMediaItemIndex.coerceIn(queue.indices)
            val pos = player.currentPosition.coerceAtLeast(0L)
            onTrackActive(queue[newIdx], recordStart = false, positionMs = pos)
            store.savePlaybackSession(queue, newIdx, pos)
        }
    }

    fun togglePlay() {
        if (player.playWhenReady) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            startPlaybackService()
            if (player.playbackState == Player.STATE_IDLE && queue.isNotEmpty()) {
                player.prepare()
            }
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        player.seekTo(target)
        val cur = _state.value.current ?: return
        val idx = resolveCurrentQueueIndex(cur)
        val dur = player.duration.takeIf { it > 0L } ?: cur.durationMs
        store.savePlaybackProgress(cur, idx, target, durationMs = dur)
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
            seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else if (queue.size > 1) {
            val prev = (player.currentMediaItemIndex - 1 + queue.size) % queue.size
            playQueueIndex(prev)
        }
    }

    private fun resolveCurrentQueueIndex(cur: MediaEntry): Int = resolveQueueIndex(
        queue = queue,
        currentUri = cur.uri,
        playerMediaItemCount = player.mediaItemCount,
        playerIndex = player.currentMediaItemIndex,
    )

    private fun onTrackActive(entry: MediaEntry, recordStart: Boolean, positionMs: Long = 0L) {
        _state.update { it.copy(current = entry, loopA = null, loopB = null) }
        music.onEntryChanged(entry)
        val safePos = positionMs.coerceAtLeast(0L)
        val idx = resolveCurrentQueueIndex(entry)
        store.savePlaybackProgress(entry, idx, safePos, durationMs = entry.durationMs)
        store.recordHistory(entry, safePos)
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

    private fun saveProgressOnly(sync: Boolean = false) {
        if (cleared) return
        val cur = _state.value.current ?: return
        if (queue.isEmpty() || player.mediaItemCount == 0) return
        val idx = resolveCurrentQueueIndex(cur)
        val dur = player.duration.takeIf { it > 0L } ?: cur.durationMs
        val pos = player.currentPosition.coerceAtLeast(0L)
        store.savePlaybackProgress(cur, idx, pos, durationMs = dur, sync = sync)
    }

    fun savePosition(sync: Boolean = false) {
        if (cleared) return
        val cur = _state.value.current ?: return
        if (player.mediaItemCount == 0) return
        val dur = player.duration.takeIf { it > 0L } ?: cur.durationMs
        val pos = player.currentPosition.coerceAtLeast(0L)
        val updatedCur = if (dur != cur.durationMs) cur.copy(durationMs = dur) else cur
        if (queue.isNotEmpty()) {
            val idx = resolveCurrentQueueIndex(cur)
            if (idx in queue.indices && queue[idx].durationMs != updatedCur.durationMs) {
                queue = queue.toMutableList().apply { this[idx] = updatedCur }
            }
            store.savePlaybackSession(queue, idx, pos, sync = sync)
        }
        store.recordHistory(updatedCur, pos)
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
                val idx = player.currentMediaItemIndex.coerceIn(queue.indices)
                store.savePlaybackSession(queue, idx, player.currentPosition.coerceAtLeast(0L))
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
        savePosition(sync = true)
        cleared = true
        player.removeListener(playerListener)
        if (PlaybackService.onBeforeTaskRemoved === taskRemovedCallback) {
            PlaybackService.onBeforeTaskRemoved = null
        }
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

        /**
         * 解析当前播放曲目在队列中的真实下标：
         * 避免播放器清空或释放时 SimpleBasePlayer.currentMediaItemIndex 回退为 0 而误覆盖当前曲目下标。
         */
        internal fun resolveQueueIndex(
            queue: List<MediaEntry>,
            currentUri: String,
            playerMediaItemCount: Int,
            playerIndex: Int,
        ): Int {
            if (queue.isEmpty()) return 0
            if (playerMediaItemCount > 0 && playerIndex in queue.indices && queue[playerIndex].uri == currentUri) {
                return playerIndex
            }
            return queue.indexOfFirst { it.uri == currentUri }.takeIf { it >= 0 }
                ?: playerIndex.coerceIn(queue.indices)
        }
    }
}
