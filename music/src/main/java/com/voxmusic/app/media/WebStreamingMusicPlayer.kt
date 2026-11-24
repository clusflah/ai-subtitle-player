package com.voxmusic.app.media

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.voxmusic.app.music.WebMusicService
import com.voxmusic.app.music.WebStreamingMusic
import com.voxmusic.app.media.TrackMetadataRegistry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 基于 Media3 [SimpleBasePlayer] 封装的 Apple Music / Spotify 应用内流媒体播放器：
 * 对外完整实现 [Player] 接口，无缝复用现有的黑胶唱机页（MusicView）、逐行滚动歌词、播放队列抽屉、
 * 底部迷你播放条（NowPlayingBar）、睡眠定时以及系统通知栏 / 锁屏媒体控制（MediaSession）。
 */
@UnstableApi
class WebStreamingMusicPlayer(
    context: Context,
    private val onLiveMetadata: ((uri: String, title: String, artist: String, album: String, durationMs: Long, coverUrl: String?) -> Unit)? = null,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var items: List<MediaItem> = emptyList()
    private var currentIndex: Int = 0
    private var startPositionMs: Long = 0L
    private var playWhenReady: Boolean = false
    private var playbackState: Int = Player.STATE_IDLE
    private var repeatModeState: Int = Player.REPEAT_MODE_OFF
    private var shuffleEnabled: Boolean = false
    private var speed: Float = 1f
    private var volume: Float = 1f
    private var error: PlaybackException? = null

    private var currentPosMs: Long = 0L
    private var currentDurMs: Long = C.TIME_UNSET
    private var maxPosReachedMs: Long = 0L
    private var seekHoldTargetMs: Long = C.TIME_UNSET
    private var seekHoldUntilMs: Long = 0L
    private var launchJob: Job? = null
    private var activePlayGeneration: Long = 0L
    private var hasStartedCurrentTrackAudio: Boolean = false
    private var remoteActuallyPlaying: Boolean = false
    private var lastAnchoredPosMs: Long = 0L
    private var lastAnchoredElapsedMs: Long = 0L
    private var trackTransitionPending: Boolean = false
    private var lastSyncedMetaKey: String = ""
    private var consecutiveAutoAdvanceFailures: Int = 0
    private val shufflePlayedUris = HashSet<String>()

    init {
        WebStreamingMusic.setSnapshotListener { snap ->
            scope.launch { onSnapshot(snap) }
        }
    }

    private fun itemUriAt(index: Int): String =
        items.getOrNull(index)?.localConfiguration?.uri?.toString()
            ?: items.getOrNull(index)?.mediaId?.takeIf { it.isNotBlank() }.orEmpty()

    private fun currentUriString(): String? =
        itemUriAt(currentIndex).takeIf { it.isNotBlank() }

    private fun activeService(): WebMusicService? =
        WebMusicService.fromUri(currentUriString())

    private fun onSnapshot(snap: WebStreamingMusic.PlaybackSnapshot) {
        val curService = activeService() ?: return
        if (snap.service != curService) return
        if (playbackState == Player.STATE_IDLE || items.isEmpty()) return
        // 严格校验播放请求代次，丢弃切歌前残留定时器发来的上一首快照
        if (snap.playSeq != 0L && snap.playSeq != activePlayGeneration) return

        if (snap.switching) {
            if (remoteActuallyPlaying || (playWhenReady && playbackState != Player.STATE_BUFFERING)) {
                remoteActuallyPlaying = false
                if (playWhenReady) playbackState = Player.STATE_BUFFERING
                invalidateState()
            }
            return
        }

        var changed = false
        val nowWall = System.currentTimeMillis()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val nowFlowing = snap.playing && !snap.buffering && playWhenReady

        if (nowFlowing && !hasStartedCurrentTrackAudio) {
            hasStartedCurrentTrackAudio = true
            if (seekHoldTargetMs != C.TIME_UNSET) {
                seekHoldUntilMs = nowWall + 3_000L
            }
            changed = true
        }

        // 在当前歌曲尚未真正解码出声前，保持起点进度与初始时长不变，杜绝旧歌残留进度与缓冲期乱跳
        if (!hasStartedCurrentTrackAudio) {
            if (remoteActuallyPlaying) {
                remoteActuallyPlaying = false
                changed = true
            }
            if (playWhenReady && playbackState != Player.STATE_BUFFERING && playbackState != Player.STATE_ENDED) {
                playbackState = Player.STATE_BUFFERING
                changed = true
            }
            if (changed) invalidateState()
            return
        }

        if (nowWall < seekHoldUntilMs && seekHoldTargetMs != C.TIME_UNSET) {
            if (kotlin.math.abs(snap.positionMs - seekHoldTargetMs) <= 1_200L) {
                seekHoldUntilMs = 0L
                seekHoldTargetMs = C.TIME_UNSET
            }
        }

        if (snap.durationMs > 0L && snap.durationMs != currentDurMs) {
            currentDurMs = snap.durationMs
            changed = true
        }
        if (nowWall >= seekHoldUntilMs) {
            currentPosMs = snap.positionMs
            if (snap.positionMs > maxPosReachedMs) maxPosReachedMs = snap.positionMs
        }

        if (nowFlowing != remoteActuallyPlaying) {
            remoteActuallyPlaying = nowFlowing
            changed = true
        }

        val targetState = when {
            !playWhenReady -> Player.STATE_READY
            nowFlowing -> Player.STATE_READY
            snap.buffering -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        if (playbackState != targetState && playbackState != Player.STATE_ENDED) {
            playbackState = targetState
            changed = true
        }

        // 外推时钟运行期间，若底层真实进度与外推值偏差超过 400ms 则立即重锚定，防止累积超前或回跳
        if (!changed && remoteActuallyPlaying && nowWall >= seekHoldUntilMs) {
            val predicted = lastAnchoredPosMs + ((nowElapsed - lastAnchoredElapsedMs).coerceAtLeast(0L) * speed).toLong()
            if (kotlin.math.abs(currentPosMs - predicted) > 400L) {
                changed = true
            }
        }

        // 实时将流媒体播放器解析到的封面、专辑与精确时长回写至 ServerProgress 与 MusicFeatures
        val curUri = currentUriString()
        if (curUri != null && snap.title.isNotEmpty()) {
            val metaKey = "$curUri|${snap.title}|${snap.artist}|${snap.album}|${snap.coverUrl}|${snap.durationMs}"
            if (metaKey != lastSyncedMetaKey) {
                lastSyncedMetaKey = metaKey
                val prev = TrackMetadataRegistry.find(curUri)
                TrackMetadataRegistry.register(
                    curUri,
                    TrackMetadataRegistry.Entry(
                        sourceId = prev?.sourceId ?: "web:${curService.name}",
                        ratingKey = prev?.ratingKey ?: snap.title,
                        durationMs = if (snap.durationMs > 0L) snap.durationMs else (prev?.durationMs ?: 0L),
                        
                        title = prev?.title?.takeIf { it.isNotBlank() } ?: snap.title,
                        artist = prev?.artist?.takeIf { it.isNotBlank() } ?: snap.artist.ifBlank { null },
                        album = prev?.album?.takeIf { it.isNotBlank() } ?: snap.album.ifBlank { null },
                        coverUrl = prev?.coverUrl ?: snap.coverUrl,
                    ),
                )
                onLiveMetadata?.invoke(curUri, snap.title, snap.artist, snap.album, snap.durationMs, snap.coverUrl)
            }
        }

        // 检测单曲自然播完并自动切入队列下一首
        val naturalEnd = snap.ended ||
            (snap.durationMs > 15_000L && maxPosReachedMs > 5_000L && snap.positionMs >= snap.durationMs - 650L && !snap.playing)
        if (naturalEnd && !trackTransitionPending && playWhenReady) {
            trackTransitionPending = true
            advanceOnTrackEnd()
            return
        }

        if (changed) invalidateState()
    }

    private fun advanceOnTrackEnd() {
        if (items.isEmpty()) return
        if (repeatModeState == Player.REPEAT_MODE_ONE) {
            startCurrentTrack(0L)
            return
        }
        val nextIdx = when {
            shuffleEnabled && items.size > 1 -> {
                pickNextShuffleIndex(
                    itemUris = List(items.size) { idx -> itemUriAt(idx) },
                    currentIndex = currentIndex,
                    playedUris = shufflePlayedUris,
                )
            }
            currentIndex + 1 < items.size -> currentIndex + 1
            repeatModeState == Player.REPEAT_MODE_ALL -> 0
            else -> -1
        }
        if (nextIdx in items.indices) {
            currentIndex = nextIdx
            startPositionMs = 0L
            invalidateState()
            startCurrentTrack(0L, autoAdvanced = true)
        } else {
            remoteActuallyPlaying = false
            playbackState = Player.STATE_ENDED
            playWhenReady = false
            invalidateState()
        }
    }

    private fun startCurrentTrack(positionMs: Long, autoAdvanced: Boolean = false) {
        val uri = currentUriString() ?: return
        shufflePlayedUris.add(uri)
        val gen = ++activePlayGeneration
        launchJob?.cancel()
        error = null
        val safeStart = positionMs.coerceAtLeast(0L)
        currentPosMs = safeStart
        startPositionMs = safeStart
        hasStartedCurrentTrackAudio = false
        remoteActuallyPlaying = false
        maxPosReachedMs = 0L
        seekHoldTargetMs = if (safeStart > 1_000L) safeStart else C.TIME_UNSET
        seekHoldUntilMs = 0L
        trackTransitionPending = false
        lastSyncedMetaKey = ""
        val knownDur = TrackMetadataRegistry.find(uri)?.durationMs?.takeIf { it > 0L } ?: C.TIME_UNSET
        currentDurMs = knownDur
        playbackState = Player.STATE_BUFFERING
        invalidateState()

        launchJob = scope.launch {
            val result = WebStreamingMusic.playUri(appContext, uri, safeStart, gen)
            if (gen != activePlayGeneration) return@launch
            result.onSuccess {
                consecutiveAutoAdvanceFailures = 0
                error = null
                // 保持 STATE_BUFFERING 直至首帧真实音频开始推进，防止缓冲静默期进度条提前空跑
                if (!playWhenReady) {
                    playbackState = Player.STATE_READY
                    remoteActuallyPlaying = false
                }
                val svc = activeService()
                if (svc != null) {
                    if (!playWhenReady) WebStreamingMusic.setPlaying(svc, false)
                    if (speed != 1f) WebStreamingMusic.setSpeed(svc, speed)
                    if (volume != 1f) WebStreamingMusic.setVolume(svc, volume)
                }
                invalidateState()
            }.onFailure { e ->
                remoteActuallyPlaying = false
                // 榜单/歌单自动连播下一首时，若个别曲目因无版权等原因无法播放，自动跳过继续播放后续曲目（最多连续跳 3 首）
                if (autoAdvanced && playWhenReady && items.size > 1 && consecutiveAutoAdvanceFailures < 3) {
                    consecutiveAutoAdvanceFailures++
                    advanceOnTrackEnd()
                    return@onFailure
                }
                error = PlaybackException(
                    e.message ?: "流媒体播放失败",
                    e,
                    PlaybackException.ERROR_CODE_UNSPECIFIED,
                )
                playbackState = Player.STATE_IDLE
                playWhenReady = false
                invalidateState()
            }
        }
    }

    fun deactivateForKernelSwitch() {
        activePlayGeneration++
        launchJob?.cancel()
        playWhenReady = false
        hasStartedCurrentTrackAudio = false
        remoteActuallyPlaying = false
        playbackState = Player.STATE_IDLE
        items = emptyList()
        currentIndex = 0
        currentPosMs = 0L
        currentDurMs = C.TIME_UNSET
        shufflePlayedUris.clear()
        error = null
        WebStreamingMusic.stopAll()
        invalidateState()
    }

    override fun getState(): State {
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_TRACKS,
            Player.COMMAND_SET_VOLUME,
            Player.COMMAND_GET_VOLUME,
            Player.COMMAND_RELEASE,
        ).build()

        val playlist = items.mapIndexed { idx, item ->
            val u = item.localConfiguration?.uri?.toString().orEmpty()
            val itemDurMs = if (idx == currentIndex && currentDurMs != C.TIME_UNSET) {
                currentDurMs
            } else {
                TrackMetadataRegistry.find(u)?.durationMs?.takeIf { it > 0L } ?: C.TIME_UNSET
            }
            // Media3 SimpleBasePlayer.State.Builder.setPlaylist 要求每条 MediaItemData 的 uid 严格唯一；
            // 歌单中可能包含重复曲目（如导入/转换歌单），必须带上队列下标防止 IllegalArgumentException 闪退
            MediaItemData.Builder("web_${idx}_${item.mediaId.ifEmpty { u }}")
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setDurationUs(if (itemDurMs == C.TIME_UNSET) C.TIME_UNSET else itemDurMs * 1_000L)
                .setIsSeekable(true)
                .setTracks(Tracks.EMPTY)
                .build()
        }

        val effectivePos = if (System.currentTimeMillis() < seekHoldUntilMs && seekHoldTargetMs != C.TIME_UNSET) {
            seekHoldTargetMs
        } else {
            currentPosMs
        }
        val shouldExtrapolate = playWhenReady &&
            playbackState == Player.STATE_READY &&
            hasStartedCurrentTrackAudio &&
            remoteActuallyPlaying
        if (shouldExtrapolate) {
            lastAnchoredPosMs = effectivePos
            lastAnchoredElapsedMs = android.os.SystemClock.elapsedRealtime()
        }
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else playbackState)
            .setIsLoading(playbackState == Player.STATE_BUFFERING)
            .setPlayerError(error)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (playlist.isEmpty()) C.INDEX_UNSET else currentIndex.coerceIn(playlist.indices))
            .setRepeatMode(repeatModeState)
            .setShuffleModeEnabled(shuffleEnabled)
            .setContentPositionMs(
                if (shouldExtrapolate) {
                    PositionSupplier.getExtrapolating(effectivePos, speed)
                } else {
                    PositionSupplier.getConstant(effectivePos)
                },
            )
            .setPlaybackParameters(PlaybackParameters(speed))
            .setVolume(volume)
            .build()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        // 切换播放列表/新曲目前立即中断上一首后台播放与残留快照，避免 prepare() 前的 playWhenReady=true 误唤醒旧曲目
        activePlayGeneration++
        launchJob?.cancel()
        WebStreamingMusic.stopAll()
        items = mediaItems.toList()
        val retainedUris = items.indices.mapTo(HashSet(items.size)) { idx -> itemUriAt(idx) }
        shufflePlayedUris.retainAll(retainedUris)
        currentIndex = if (items.isEmpty()) 0 else startIndex.coerceIn(items.indices)
        this.startPositionMs = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        currentPosMs = this.startPositionMs
        currentDurMs = C.TIME_UNSET
        hasStartedCurrentTrackAudio = false
        remoteActuallyPlaying = false
        maxPosReachedMs = 0L
        seekHoldTargetMs = C.TIME_UNSET
        seekHoldUntilMs = 0L
        trackTransitionPending = false
        lastSyncedMetaKey = ""
        consecutiveAutoAdvanceFailures = 0
        error = null
        playbackState = Player.STATE_IDLE
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        if (mediaItems.isEmpty()) return Futures.immediateVoidFuture()
        val mutable = items.toMutableList()
        val insertAt = index.coerceIn(0, mutable.size)
        mutable.addAll(insertAt, mediaItems)
        if (items.isNotEmpty() && insertAt <= currentIndex) {
            currentIndex += mediaItems.size
        }
        items = mutable
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        if (items.isEmpty()) return Futures.immediateVoidFuture()
        val safeFrom = fromIndex.coerceIn(0, items.size)
        val safeTo = toIndex.coerceIn(safeFrom, items.size)
        if (safeFrom >= safeTo) return Futures.immediateVoidFuture()
        val mutable = items.toMutableList()
        mutable.subList(safeFrom, safeTo).clear()
        items = mutable
        if (items.isEmpty()) {
            deactivateForKernelSwitch()
        } else if (currentIndex >= items.size) {
            currentIndex = items.lastIndex
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> =
        Futures.immediateVoidFuture()

    override fun handlePrepare(): ListenableFuture<*> {
        if (items.isNotEmpty()) {
            startCurrentTrack(startPositionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (!playWhenReady) {
            remoteActuallyPlaying = false
            if (playbackState == Player.STATE_BUFFERING && items.isNotEmpty()) {
                playbackState = Player.STATE_READY
            }
        }
        val svc = activeService()
        if (playbackState == Player.STATE_ENDED && playWhenReady) {
            startCurrentTrack(0L)
        } else if (playWhenReady && (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_READY) && !hasStartedCurrentTrackAudio && items.isNotEmpty()) {
            // 若当前曲目尚未真正起播（如冷启动恢复暂停态，或缓冲期被暂停后再次点播放），按当前进度发起播放
            startCurrentTrack(currentPosMs)
        } else if (svc != null && playbackState != Player.STATE_IDLE) {
            if (!playWhenReady || hasStartedCurrentTrackAudio) {
                WebStreamingMusic.setPlaying(svc, playWhenReady)
            }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (items.isEmpty()) return Futures.immediateVoidFuture()
        val targetIdx = if (mediaItemIndex == C.INDEX_UNSET) currentIndex else mediaItemIndex.coerceIn(items.indices)
        val targetPos = positionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        if (targetIdx != currentIndex) {
            currentIndex = targetIdx
            startPositionMs = targetPos
            if (playbackState == Player.STATE_IDLE && !playWhenReady) {
                currentPosMs = targetPos
                currentDurMs = TrackMetadataRegistry.find(itemUriAt(targetIdx))?.durationMs?.takeIf { it > 0L } ?: C.TIME_UNSET
                invalidateState()
            } else {
                startCurrentTrack(targetPos)
            }
        } else {
            currentPosMs = targetPos
            startPositionMs = targetPos
            seekHoldTargetMs = targetPos
            seekHoldUntilMs = System.currentTimeMillis() + 1_600L
            if (playbackState == Player.STATE_ENDED) {
                startCurrentTrack(targetPos)
            } else if (playbackState != Player.STATE_IDLE) {
                activeService()?.let { WebStreamingMusic.seekTo(it, targetPos) }
                invalidateState()
            } else {
                invalidateState()
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeatModeState = repeatMode
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        shuffleEnabled = shuffleModeEnabled
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        activeService()?.let { WebStreamingMusic.setSpeed(it, speed) }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        this.volume = volume.coerceIn(0f, 1f)
        activeService()?.let { WebStreamingMusic.setVolume(it, this.volume) }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        activePlayGeneration++
        launchJob?.cancel()
        WebStreamingMusic.stopAll()
        hasStartedCurrentTrackAudio = false
        remoteActuallyPlaying = false
        playbackState = Player.STATE_IDLE
        playWhenReady = false
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        deactivateForKernelSwitch()
        scope.cancel()
        WebStreamingMusic.setSnapshotListener(null)
        return Futures.immediateVoidFuture()
    }

    companion object {
        /**
         * 随机切歌时优先从未播放过的曲目中挑选（使无限随机新追加的推荐曲目优先被播到）；
         * 若队列内曲目均已播过一轮，则清空本轮记录后在非当前曲目中重新随机。
         */
        internal fun pickNextShuffleIndex(
            itemUris: List<String>,
            currentIndex: Int,
            playedUris: MutableSet<String>,
        ): Int {
            if (itemUris.size <= 1) return currentIndex.coerceAtLeast(0)
            val unplayed = itemUris.indices.filter { idx ->
                idx != currentIndex && itemUris[idx] !in playedUris
            }
            val pool = if (unplayed.isNotEmpty()) {
                unplayed
            } else {
                val curUri = itemUris.getOrNull(currentIndex)
                playedUris.clear()
                if (!curUri.isNullOrEmpty()) playedUris.add(curUri)
                itemUris.indices.filter { it != currentIndex }
            }
            return pool[Random.nextInt(pool.size)]
        }
    }
}
