package com.aisub.player.media

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

/**
 * 用 libVLC 实现的 Media3 [Player]：播放 ISO（DVD / 蓝光镜像）和 ExoPlayer 解不了的格式。
 * 对外和 ExoPlayer 一样是 Player 接口，播放页的控制、手势、字幕叠加都不用改。
 * 媒体通过 [MediaProxy] 转成本机地址交给 VLC，网盘和 WebDAV 的鉴权沿用应用自己的实现。
 * 一次只播一个文件（不做播放队列）。
 */
@UnstableApi
class VlcPlayer(context: Context, private val proxy: MediaProxy) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val libVlc = LibVLC(context.applicationContext, arrayListOf("--http-reconnect", "--network-caching=2000", "--audio-time-stretch"))
    private val mp = MediaPlayer(libVlc)

    private var items: List<MediaItem> = emptyList()
    private var startPositionMs = 0L
    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var durationMs = C.TIME_UNSET
    private var videoSize = VideoSize.UNKNOWN
    private var error: PlaybackException? = null
    private var speed = 1f
    private var volume = 1f
    private var trackParams = TrackSelectionParameters.DEFAULT
    private var tracks = Tracks.EMPTY
    /** ENDED 之后拖动：先重新开始播放，进入 Playing 后再跳到目标位置 */
    private var pendingSeekMs: Long? = null
    /** 当前媒体是否已经调用过 play() */
    private var started = false
    /** 下一次状态里带上"已渲染首帧" */
    private var firstFrame = false
    private var videoView: View? = null
    private val layoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        if (v.width > 0 && v.height > 0) mp.vlcVout.setWindowSize(v.width, v.height)
    }

    init {
        mp.setEventListener { e -> onVlcEvent(e) }
    }

    private fun onVlcEvent(e: MediaPlayer.Event) {
        when (e.type) {
            MediaPlayer.Event.Opening -> playbackState = Player.STATE_BUFFERING
            MediaPlayer.Event.Buffering -> if (playbackState != Player.STATE_IDLE) {
                playbackState = if (e.buffering >= 100f) Player.STATE_READY else Player.STATE_BUFFERING
            }
            MediaPlayer.Event.Playing -> {
                playbackState = Player.STATE_READY
                pendingSeekMs?.let { mp.setTime(it); pendingSeekMs = null }
                refreshTracks()
            }
            MediaPlayer.Event.Paused -> if (playbackState == Player.STATE_BUFFERING) playbackState = Player.STATE_READY
            MediaPlayer.Event.EndReached -> {
                playbackState = Player.STATE_ENDED
                playWhenReady = false
            }
            MediaPlayer.Event.EncounteredError -> {
                error = PlaybackException(
                    "VLC 无法打开这个文件（可能是加密的蓝光、损坏的镜像或网络中断）",
                    null, PlaybackException.ERROR_CODE_UNSPECIFIED,
                )
                playbackState = Player.STATE_IDLE
            }
            MediaPlayer.Event.LengthChanged -> durationMs = e.lengthChanged.takeIf { it > 0 } ?: C.TIME_UNSET
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> refreshTracks()
            // 画面开始输出：通知 PlayerView 去掉黑色遮罩，并更新视频尺寸（决定画面比例）
            MediaPlayer.Event.Vout -> if (e.voutCount > 0) {
                firstFrame = true
                mp.currentVideoTrack?.let { t ->
                    if (t.width > 0 && t.height > 0) {
                        val sar = if (t.sarNum > 0 && t.sarDen > 0) t.sarNum.toFloat() / t.sarDen else 1f
                        videoSize = if (t.orientation == 5 || t.orientation == 6) VideoSize(t.height, t.width, sar) else VideoSize(t.width, t.height, sar)
                    }
                }
            }
            else -> return
        }
        invalidateState()
    }

    /** VLC 的音轨、内嵌字幕轨转成 Media3 的 Tracks，播放页的音轨列表直接可用。 */
    private fun refreshTracks() {
        val groups = ArrayList<Tracks.Group>()
        val audio = mp.audioTracks.orEmpty().filter { it.id >= 0 }
        val currentAudio = mp.audioTrack
        audio.forEach { t ->
            val f = Format.Builder().setId("a${t.id}").setLabel(t.name).setSampleMimeType(MimeTypes.AUDIO_UNKNOWN).build()
            groups += Tracks.Group(TrackGroup("a${t.id}", f), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(t.id == currentAudio))
        }
        val spu = mp.spuTracks.orEmpty().filter { it.id >= 0 }
        val currentSpu = mp.spuTrack
        spu.forEach { t ->
            val f = Format.Builder().setId("s${t.id}").setLabel(t.name).setSampleMimeType(MimeTypes.TEXT_UNKNOWN).build()
            groups += Tracks.Group(TrackGroup("s${t.id}", f), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(t.id == currentSpu))
        }
        tracks = Tracks(groups)
    }

    override fun getState(): State {
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_VIDEO_SURFACE, Player.COMMAND_GET_TRACKS,
            Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS, Player.COMMAND_SET_VOLUME, Player.COMMAND_GET_VOLUME,
            Player.COMMAND_RELEASE,
        ).build()
        val playlist = items.mapIndexed { i, item ->
            MediaItemData.Builder(item.mediaId.ifEmpty { "item$i" })
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000)
                .setIsSeekable(true)
                .setTracks(tracks)
                .build()
        }
        val newlyRendered = firstFrame.also { firstFrame = false }
        return State.Builder()
            .setNewlyRenderedFirstFrame(newlyRendered)
            .setAvailableCommands(commands)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (items.isEmpty()) Player.STATE_IDLE else playbackState)
            .setPlayerError(error)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(
                PositionSupplier {
                    when {
                        playbackState == Player.STATE_ENDED && durationMs != C.TIME_UNSET -> durationMs
                        pendingSeekMs != null -> pendingSeekMs!!
                        else -> mp.time.coerceAtLeast(0)
                    }
                },
            )
            .setPlaybackParameters(PlaybackParameters(speed))
            .setTrackSelectionParameters(trackParams)
            .setVolume(volume)
            .setVideoSize(videoSize)
            .build()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        items = mediaItems.take(1)
        this.startPositionMs = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        durationMs = C.TIME_UNSET
        tracks = Tracks.EMPTY
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        val item = items.firstOrNull() ?: return Futures.immediateVoidFuture()
        val uri = item.localConfiguration?.uri ?: Uri.parse(item.mediaId)
        val name = item.mediaMetadata.title?.toString() ?: uri.lastPathSegment.orEmpty()
        error = null
        val media = Media(libVlc, Uri.parse(proxy.register(uri, name)))
        media.setHWDecoderEnabled(true, false)
        // 光盘镜像不进菜单，直接播放正片（应用里没有光盘菜单的操作界面）
        media.addOption(":no-dvdnav-menu")
        media.addOption(":no-bluray-menu")
        if (startPositionMs > 0) media.addOption(":start-time=${startPositionMs / 1000.0}")
        mp.media = media
        media.release()
        playbackState = Player.STATE_BUFFERING
        // VLC 在打开过程中暂停容易卡住，所以未要求播放时先不启动，等 setPlayWhenReady(true) 再开始
        started = playWhenReady
        if (playWhenReady) mp.play()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playWhenReady && !started && items.isNotEmpty() && playbackState != Player.STATE_IDLE) {
            started = true
            mp.play()
            return Futures.immediateVoidFuture()
        }
        if (playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED) {
            if (playWhenReady) mp.play() else mp.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = positionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        if (playbackState == Player.STATE_ENDED) {
            pendingSeekMs = target
            playbackState = Player.STATE_BUFFERING
            mp.stop()
            mp.play()
        } else {
            mp.setTime(target)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        mp.rate = speed
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        this.volume = volume
        mp.volume = (volume * 100).toInt()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        trackParams = trackSelectionParameters
        val audioOverride = trackSelectionParameters.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
        when {
            C.TRACK_TYPE_AUDIO in trackSelectionParameters.disabledTrackTypes -> mp.setAudioTrack(-1)
            audioOverride != null -> audioOverride.mediaTrackGroup.id.removePrefix("a").toIntOrNull()?.let { mp.setAudioTrack(it) }
        }
        val textOverride = trackSelectionParameters.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
        when {
            C.TRACK_TYPE_TEXT in trackSelectionParameters.disabledTrackTypes -> mp.setSpuTrack(-1)
            textOverride != null -> textOverride.mediaTrackGroup.id.removePrefix("s").toIntOrNull()?.let { mp.setSpuTrack(it) }
        }
        mp.setVideoTrackEnabled(C.TRACK_TYPE_VIDEO !in trackSelectionParameters.disabledTrackTypes)
        refreshTracks()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        val vout = mp.vlcVout
        if (vout.areViewsAttached()) vout.detachViews()
        videoView?.removeOnLayoutChangeListener(layoutListener)
        when (videoOutput) {
            is SurfaceView -> vout.setVideoView(videoOutput)
            is TextureView -> vout.setVideoView(videoOutput)
            else -> return Futures.immediateVoidFuture()
        }
        val view = videoOutput as View
        videoView = view
        view.addOnLayoutChangeListener(layoutListener)
        vout.attachViews(IVLCVout.OnNewVideoLayoutListener { _, _, _, visibleWidth, visibleHeight, sarNum, sarDen ->
            if (visibleWidth > 0 && visibleHeight > 0) {
                val ratio = if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen else 1f
                videoSize = VideoSize(visibleWidth, visibleHeight, ratio)
                invalidateState()
            }
        })
        if (view.width > 0 && view.height > 0) vout.setWindowSize(view.width, view.height)
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        videoView?.removeOnLayoutChangeListener(layoutListener)
        videoView = null
        if (mp.vlcVout.areViewsAttached()) mp.vlcVout.detachViews()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        mp.stop()
        playbackState = Player.STATE_IDLE
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        mp.setEventListener(null)
        mp.stop()
        if (mp.vlcVout.areViewsAttached()) mp.vlcVout.detachViews()
        mp.release()
        libVlc.release()
        return Futures.immediateVoidFuture()
    }

    companion object {
        /** 需要用 VLC 内核的文件：光盘镜像。 */
        fun needsVlc(name: String) = name.substringAfterLast('.', "").lowercase() == "iso"
    }
}
