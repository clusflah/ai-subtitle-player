package com.aisub.player.media

import android.content.Context
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Looper
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
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
import com.aisub.player.live.LiveHeaders
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.cloud.CloudRegistry
import com.aisub.player.subtitle.EmbeddedSubtitles
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

/**
 * 用 libVLC 实现的 Media3 [Player]：播放 ISO（DVD / 蓝光镜像）、ExoPlayer 解不了的格式以及单连接/特殊封装直播源。
 * 对外和 ExoPlayer 一样是 Player 接口，播放页的控制、手势、字幕叠加都不用改。
 * 音频通过 [VlcAudioBridge] 接管原生回调：
 * - Android 8.1+ 走 C++ AAudio 低延迟直出；
 * - Android 6.0 老电视自动回退到 Java AudioTrack，并配合 `--audio-desync=-160` 抵消环形缓冲延迟；
 * - 开启 AI 字幕时在 C++ 层同步重采样出 16kHz 单声道音频喂给识别引擎，单连接源无需第二路连接即可出字幕。
 */
@UnstableApi
class VlcPlayer(context: Context, private val proxy: MediaProxy) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val appContext = context.applicationContext
    private val audioBridge = VlcAudioBridge()
    private val kernel = startVlcKernel()
    private val libVlc: LibVLC = kernel.first
    private val mp: MediaPlayer = kernel.second

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
    /** ENDED 之后或起播 Opening 阶段拖动：进入 Playing 后再跳到目标位置 */
    private var pendingSeekMs: Long? = null
    private var hasEnteredPlaying = false
    private var seekHoldTargetMs: Long = C.TIME_UNSET
    private var seekHoldUntilMs: Long = 0L
    /** 当前媒体是否已经调用过 play() */
    private var started = false
    /** 下一次状态里带上"已渲染首帧" */
    private var firstFrame = false
    private var videoView: View? = null
    private var subtitlesView: SurfaceView? = null
    private var suppressNativeSubtitles = false
    private val layoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        if (v.width > 0 && v.height > 0) mp.vlcVout.setWindowSize(v.width, v.height)
    }

    init {
        mp.setEventListener { e -> onVlcEvent(e) }
    }

    /** 当前媒体是否绕过 MediaProxy 直接由 libVLC 拉远端流，用于轮询统计输入字节并换算网速 */
    @Volatile
    private var directRemoteStream = false
    private var lastDirectReadBytes = -1L

    private fun startVlcKernel(): Pair<LibVLC, MediaPlayer> {
        val useAAudio = VlcAudioBridge.isAAudioSupported()
        val vlcOptions = if (useAAudio) {
            // AAudio 标准媒体缓冲约 240ms，提前 140ms 送出音频抵消硬件与环形缓冲延迟，同时彻底杜绝 4K 直播 XRun 电流声
            arrayListOf(
                "--network-caching=1500",
                "--live-caching=1800",
                "--prefetch-buffer-size=16384",
                "--audio-desync=-140",
                "--no-spdif",
                "--avcodec-skiploopfilter=1",
                "--input-fast-seek",
                "--http-reconnect",
                "--audio-time-stretch",
            )
        } else {
            // Android 6.0 老电视等走 Java AudioTrack 回退路径：提前 180ms 送出音频抵消环形缓冲延迟，嘴型对齐
            arrayListOf(
                "--network-caching=1500",
                "--live-caching=1800",
                "--prefetch-buffer-size=16384",
                "--audio-desync=-180",
                "--no-spdif",
                "--avcodec-skiploopfilter=1",
                "--input-fast-seek",
                "--http-reconnect",
                "--audio-time-stretch",
            )
        }
        val vlc = LibVLC(appContext, vlcOptions)
        val player = MediaPlayer(vlc)
        audioBridge.attach(vlc, player)
        return vlc to player
    }

    /** 轮询 libVLC 直连远端流时的输入读取字节增量并计入实时网速（本地代理流已由 http TransferListener 统计）。 */
    fun pollDirectNetworkBytes() {
        if (!directRemoteStream) return
        val m = mp.media ?: return
        val total = try {
            m.stats?.readBytes?.toLong()?.and(0xFFFFFFFFL) ?: return
        } finally {
            m.release()
        }
        val prev = lastDirectReadBytes
        if (prev >= 0L && total > prev) {
            com.aisub.player.net.NetworkSpeedTracker.recordNetworkBytes(total - prev)
        }
        lastDirectReadBytes = total
    }

    /** 注册或清除实时 AI 字幕识别的 16kHz 单声道音频分流回调（无需另开网络连接）。 */
    fun setAsrAudioConsumer(
        onChunk: ((FloatArray) -> Unit)?,
        onDiscontinuity: (() -> Unit)? = null,
    ) {
        audioBridge.setAsrConsumer(onChunk, onDiscontinuity)
    }

    /** 用户手动覆盖的硬解偏好：null=智能自动，true=强制硬解，false=强制软解 */
    var userForceHwdec: Boolean? = null
        private set

    /** 当前实际生效的解码策略描述 */
    val decodeSummaryLabel: String
        get() = when (userForceHwdec) {
            true -> "硬解优先 (MediaCodec)"
            false -> "纯软解 (FFmpeg CPU)"
            null -> "智能自动 (MediaCodec 硬解)"
        }

    /** 切换 VLC 软硬解策略；若当前正在播放点播视频则保持原进度即时重载解码器。 */
    fun setHwdecPreference(forceHwdec: Boolean?) {
        if (userForceHwdec == forceHwdec) return
        userForceHwdec = forceHwdec
        if (items.isNotEmpty() && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED) {
            val isLive = items.firstOrNull()?.localConfiguration?.tag == "live"
            if (!isLive) {
                startPositionMs = mp.time.coerceAtLeast(0L)
            }
            mp.stop()
            handlePrepare()
        }
        invalidateState()
    }

    /** 当前实际生效的音频输出路径描述（AAudio 低延迟直出 / AudioTrack + -160ms 同步补偿）。 */
    val audioBackendLabel: String
        get() = audioBridge.activeBackendLabel()

    private fun updateVideoSizeFromTrack() {
        val t = mp.currentVideoTrack ?: return
        if (t.width > 0 && t.height > 0) {
            val sar = if (t.sarNum > 0 && t.sarDen > 0) t.sarNum.toFloat() / t.sarDen else 1f
            // libVLC IMedia.VideoTrack.Orientation: 4..7 为旋转 90°/270°（宽高对调）
            videoSize = if (t.orientation in 4..7) {
                VideoSize(t.height, t.width, if (sar > 0f) 1f / sar else 1f)
            } else {
                VideoSize(t.width, t.height, sar)
            }
        }
    }

    private fun onVlcEvent(e: MediaPlayer.Event) {
        when (e.type) {
            MediaPlayer.Event.Opening -> playbackState = Player.STATE_BUFFERING
            MediaPlayer.Event.Buffering -> if (playbackState != Player.STATE_IDLE) {
                playbackState = if (e.buffering >= 100f) Player.STATE_READY else Player.STATE_BUFFERING
            }
            MediaPlayer.Event.Playing -> {
                hasEnteredPlaying = true
                playbackState = Player.STATE_READY
                pendingSeekMs?.let { mp.setTime(it); pendingSeekMs = null }
                if (videoSize == VideoSize.UNKNOWN) updateVideoSizeFromTrack()
                applyTrackSelectionInternal()
                refreshTracks()
            }
            MediaPlayer.Event.Paused -> if (playbackState == Player.STATE_BUFFERING) playbackState = Player.STATE_READY
            MediaPlayer.Event.EndReached -> {
                hasEnteredPlaying = false
                playbackState = Player.STATE_ENDED
                playWhenReady = false
            }
            MediaPlayer.Event.EncounteredError -> {
                hasEnteredPlaying = false
                val isLive = items.firstOrNull()?.localConfiguration?.tag == "live"
                error = PlaybackException(
                    if (isLive) "VLC 内核无法打开这个直播源（可能源已失效、地址受限或网络中断）"
                    else "VLC 无法打开这个文件（可能是加密的蓝光、损坏的镜像或网络中断）",
                    null, PlaybackException.ERROR_CODE_UNSPECIFIED,
                )
                playbackState = Player.STATE_IDLE
            }
            MediaPlayer.Event.LengthChanged -> durationMs = e.lengthChanged.takeIf { it > 0 } ?: C.TIME_UNSET
            MediaPlayer.Event.ESAdded -> {
                if (videoSize == VideoSize.UNKNOWN) updateVideoSizeFromTrack()
                applyTrackSelectionInternal()
                refreshTracks()
            }
            MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> {
                if (videoSize == VideoSize.UNKNOWN) updateVideoSizeFromTrack()
                refreshTracks()
            }
            // 画面开始输出：通知 PlayerView 去掉黑色遮罩，并更新视频尺寸（决定画面比例）
            MediaPlayer.Event.Vout -> if (e.voutCount > 0) {
                firstFrame = true
                updateVideoSizeFromTrack()
                refreshTracks()
            }
            else -> return
        }
        invalidateState()
    }

    /** 当前标题的章节（DVD / 蓝光、MKV 等）；VLC 开始播放后才有。 */
    fun chapters(): List<Chapter> = runCatching {
        mp.getChapters(-1).orEmpty().map { Chapter(it.timeOffset, (it.timeOffset + it.duration).takeIf { _ -> it.duration > 0 }, it.name.orEmpty()) }
    }.getOrDefault(emptyList())

    /** VLC 的视频轨、音轨、内嵌字幕轨转成 Media3 的 Tracks，保证 PlayerView 正确识别视频轨并刷新画面宽高比。 */
    private fun refreshTracks() {
        val groups = ArrayList<Tracks.Group>()
        val vt = mp.currentVideoTrack
        val vw = vt?.width?.takeIf { it > 0 } ?: videoSize.width
        val vh = vt?.height?.takeIf { it > 0 } ?: videoSize.height
        if (vw > 0 && vh > 0) {
            val vSelected = C.TRACK_TYPE_VIDEO !in trackParams.disabledTrackTypes
            val vf = Format.Builder()
                .setId("v0")
                .setSampleMimeType(MimeTypes.VIDEO_UNKNOWN)
                .setWidth(vw)
                .setHeight(vh)
                .build()
            groups += Tracks.Group(TrackGroup("v0", vf), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(vSelected))
        }
        val audio = mp.audioTracks.orEmpty().filter { it.id >= 0 }
        val currentAudio = mp.audioTrack
        audio.forEach { t ->
            val lang = EmbeddedSubtitles.parseLanguageFromLabel(t.name)
            val f = Format.Builder()
                .setId("a${t.id}")
                .setLabel(t.name)
                .setLanguage(lang)
                .setSampleMimeType(MimeTypes.AUDIO_UNKNOWN)
                .build()
            groups += Tracks.Group(TrackGroup("a${t.id}", f), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(t.id == currentAudio))
        }
        val spu = mp.spuTracks.orEmpty().filter { it.id >= 0 }
        val currentSpu = mp.spuTrack
        spu.forEach { t ->
            val lang = EmbeddedSubtitles.parseLanguageFromLabel(t.name)
            val forced = t.name?.contains("forced", ignoreCase = true) == true
            val fb = Format.Builder()
                .setId("s${t.id}")
                .setLabel(t.name)
                .setLanguage(lang)
                .setSampleMimeType(MimeTypes.TEXT_UNKNOWN)
            if (forced) fb.setSelectionFlags(C.SELECTION_FLAG_FORCED)
            groups += Tracks.Group(TrackGroup("s${t.id}", fb.build()), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(t.id == currentSpu))
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
            val isLiveItem = item.localConfiguration?.tag == "live" && durationMs == C.TIME_UNSET
            MediaItemData.Builder(item.mediaId.ifEmpty { "item$i" })
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000)
                .setIsSeekable(!isLiveItem)
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
            .setCurrentMediaItemIndex(if (playlist.isEmpty()) C.INDEX_UNSET else 0)
            .setContentPositionMs(
                PositionSupplier {
                    val rawTime = mp.time.coerceAtLeast(0)
                    when {
                        playbackState == Player.STATE_ENDED && durationMs != C.TIME_UNSET -> durationMs
                        pendingSeekMs != null -> pendingSeekMs!!
                        System.currentTimeMillis() < seekHoldUntilMs &&
                            seekHoldTargetMs != C.TIME_UNSET &&
                            (rawTime <= 0L || kotlin.math.abs(rawTime - seekHoldTargetMs) > 2_000L) -> seekHoldTargetMs
                        else -> rawTime
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
        items = mediaItems.drop(startIndex.coerceAtLeast(0)).take(1).ifEmpty { mediaItems.take(1) }
        this.startPositionMs = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        durationMs = C.TIME_UNSET
        tracks = Tracks.EMPTY
        videoSize = VideoSize.UNKNOWN
        error = null
        hasEnteredPlaying = false
        pendingSeekMs = null
        seekHoldTargetMs = C.TIME_UNSET
        seekHoldUntilMs = 0L
        if (items.isEmpty()) {
            mp.stop()
            playbackState = Player.STATE_IDLE
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        if (mediaItems.isEmpty()) return Futures.immediateVoidFuture()
        if (items.isEmpty()) items = mediaItems.take(1)
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> =
        Futures.immediateVoidFuture()

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        if (items.isEmpty()) return Futures.immediateVoidFuture()
        val safeFrom = fromIndex.coerceIn(0, items.size)
        val safeTo = toIndex.coerceIn(safeFrom, items.size)
        if (safeFrom < safeTo) {
            val mutable = items.toMutableList()
            mutable.subList(safeFrom, safeTo).clear()
            items = mutable.take(1)
        }
        if (items.isEmpty()) {
            directRemoteStream = false
            lastDirectReadBytes = -1L
            started = false
            hasEnteredPlaying = false
            pendingSeekMs = null
            seekHoldTargetMs = C.TIME_UNSET
            seekHoldUntilMs = 0L
            mp.stop()
            playbackState = Player.STATE_IDLE
            durationMs = C.TIME_UNSET
            tracks = Tracks.EMPTY
            videoSize = VideoSize.UNKNOWN
            error = null
        }
        return Futures.immediateVoidFuture()
    }

    /** 切换至其它播放内核前同步解绑音频回调、Surface 与停止播放 */
    fun deactivateForKernelSwitch() {
        setAsrAudioConsumer(null, null)
        directRemoteStream = false
        lastDirectReadBytes = -1L
        playWhenReady = false
        started = false
        hasEnteredPlaying = false
        pendingSeekMs = null
        seekHoldTargetMs = C.TIME_UNSET
        seekHoldUntilMs = 0L
        videoView?.removeOnLayoutChangeListener(layoutListener)
        videoView = null
        if (mp.vlcVout.areViewsAttached()) mp.vlcVout.detachViews()
        detachSubtitlesView()
        mp.stop()
        items = emptyList()
        playbackState = Player.STATE_IDLE
        durationMs = C.TIME_UNSET
        tracks = Tracks.EMPTY
        videoSize = VideoSize.UNKNOWN
        error = null
        invalidateState()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        val item = items.firstOrNull() ?: return Futures.immediateVoidFuture()
        val uri = item.localConfiguration?.uri ?: Uri.parse(item.mediaId)
        val name = item.mediaMetadata.title?.toString() ?: uri.lastPathSegment.orEmpty()
        val isLive = item.localConfiguration?.tag == "live"
        error = null
        videoSize = VideoSize.UNKNOWN

        val raw = uri.toString()
        val scheme = uri.scheme?.lowercase().orEmpty()
        val routed = if (!CloudRegistry.isCloud(raw)) Endpoints.rewrite(raw) else raw
        val authHeaders = if (!CloudRegistry.isCloud(raw)) AuthRegistry.headersFor(routed) else emptyMap()
        val liveHeaders = if (!CloudRegistry.isCloud(raw)) LiveHeaders.headersFor(routed) else emptyMap()

        // 直播流（HLS m3u8 相对分片、FLV/TS 单连接源）及 RTSP/RTMP/UDP/RTP 等流协议直接交给 libVLC 打开，
        // 避免本机 MediaProxy 破坏 m3u8 相对分片路径或不支持非 HTTP 协议；本地文件、网盘与 WebDAV 鉴权仍走 MediaProxy。
        val openDirectly = scheme in DIRECT_STREAM_SCHEMES ||
            (isLive && (scheme == "http" || scheme == "https") && !CloudRegistry.isCloud(raw) && authHeaders.isEmpty())

        val media = if (openDirectly) {
            Media(libVlc, Uri.parse(routed)).apply {
                liveHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                    ?.takeIf { it.isNotBlank() }
                    ?.let { addOption(":http-user-agent=$it") }
                liveHeaders.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
                    ?.takeIf { it.isNotBlank() }
                    ?.let { addOption(":http-referrer=$it") }
            }
        } else {
            Media(libVlc, Uri.parse(proxy.register(uri, name))).apply {
                if (!isLive && CloudRegistry.isCloud(raw)) {
                    addOption(":network-caching=2500")
                }
            }
        }
        directRemoteStream = openDirectly
        lastDirectReadBytes = -1L

        when (userForceHwdec) {
            true -> {
                media.setHWDecoderEnabled(true, true)
                // 视频保留 MediaCodec 硬解，音频强制优先走 FFmpeg avcodec，绕过 VLC 旧版 faad 在 DVB AAC-LATM 广播流上的电流声以及 spdif 透传误触发
                media.addOption(":codec=mediacodec_ndk,mediacodec_jni,avcodec,any")
            }
            false -> {
                media.setHWDecoderEnabled(false, false)
                media.addOption(":no-hw-dec")
                media.addOption(":avcodec-hw=none")
                media.addOption(":codec=avcodec,any")
            }
            null -> {
                media.setHWDecoderEnabled(true, false)
                media.addOption(":codec=mediacodec_ndk,mediacodec_jni,avcodec,any")
            }
        }
        media.addOption(":no-spdif")
        if (isLive) {
            // 直播倍速恒为 1.0x，禁用 scaletempo 时域切片拉伸，避免网络 PCR 微调触发 WSOLA 颗粒电流声
            media.addOption(":no-audio-time-stretch")
        }
        // 光盘镜像不进菜单，直接播放正片（应用里没有光盘菜单的操作界面）
        media.addOption(":no-dvdnav-menu")
        media.addOption(":no-bluray-menu")
        if (!isLive && startPositionMs > 0) media.addOption(":start-time=${startPositionMs / 1000.0}")
        audioBridge.rebindCallbacks(mp)
        mp.media = media
        media.release()
        hasEnteredPlaying = false
        playbackState = Player.STATE_BUFFERING
        // VLC 在打开过程中暂停容易卡住，所以未要求播放时先不启动，等 setPlayWhenReady(true) 再开始
        started = playWhenReady
        if (playWhenReady) {
            audioBridge.rebindCallbacks(mp)
            mp.play()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playWhenReady && !started && items.isNotEmpty() && playbackState != Player.STATE_IDLE) {
            started = true
            audioBridge.rebindCallbacks(mp)
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
        seekHoldTargetMs = target
        seekHoldUntilMs = System.currentTimeMillis() + 500L
        if (playbackState == Player.STATE_ENDED) {
            pendingSeekMs = target
            hasEnteredPlaying = false
            playbackState = Player.STATE_BUFFERING
            mp.stop()
            audioBridge.rebindCallbacks(mp)
            mp.play()
        } else if (!hasEnteredPlaying) {
            pendingSeekMs = target
            startPositionMs = target
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
        val effective = if (C.TRACK_TYPE_AUDIO in trackParams.disabledTrackTypes) 0f else volume
        audioBridge.setVolume(effective)
        return Futures.immediateVoidFuture()
    }

    fun setEmbeddedSubtitlesSuppressed(suppressed: Boolean) {
        if (suppressNativeSubtitles == suppressed) return
        suppressNativeSubtitles = suppressed
        subtitlesView?.let { sv ->
            sv.post { sv.visibility = if (suppressed) View.INVISIBLE else View.VISIBLE }
        }
    }

    private fun detachSubtitlesView() {
        val sv = subtitlesView ?: return
        subtitlesView = null
        (sv.parent as? ViewGroup)?.removeView(sv)
    }

    private fun applyTrackSelectionInternal() {
        val audioDisabled = C.TRACK_TYPE_AUDIO in trackParams.disabledTrackTypes
        val audioOverride = trackParams.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
        val audioTracks = mp.audioTracks.orEmpty().filter { it.id >= 0 }
        val preferredAudioId = if (!audioDisabled && audioOverride == null && trackParams.preferredAudioLanguages.isNotEmpty()) {
            trackParams.preferredAudioLanguages.firstNotNullOfOrNull { pref ->
                val normPref = pref.lowercase().substringBefore('-').substringBefore('_')
                audioTracks.firstOrNull { EmbeddedSubtitles.parseLanguageFromLabel(it.name) == normPref }?.id
            }
        } else null
        if (audioBridge.isAttached) {
            // 原生音频桥接管时：静音只关闭扬声器输出，保留音频解码以便后台继续做 AI 语音识别
            audioBridge.setVolume(if (audioDisabled) 0f else volume)
            if (!audioDisabled) {
                val aid = audioOverride?.mediaTrackGroup?.id?.removePrefix("a")?.toIntOrNull() ?: preferredAudioId
                if (aid != null && mp.audioTrack != aid) mp.setAudioTrack(aid)
            }
        } else {
            when {
                audioDisabled -> mp.setAudioTrack(-1)
                audioOverride != null -> audioOverride.mediaTrackGroup.id.removePrefix("a").toIntOrNull()?.let {
                    if (mp.audioTrack != it) mp.setAudioTrack(it)
                }
                preferredAudioId != null && mp.audioTrack != preferredAudioId -> mp.setAudioTrack(preferredAudioId)
            }
        }
        val textDisabled = C.TRACK_TYPE_TEXT in trackParams.disabledTrackTypes
        val textOverride = trackParams.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
        val spuTracks = mp.spuTracks.orEmpty().filter { it.id >= 0 }
        when {
            textDisabled -> if (mp.spuTrack != -1) mp.setSpuTrack(-1)
            textOverride != null -> textOverride.mediaTrackGroup.id.removePrefix("s").toIntOrNull()?.let {
                if (mp.spuTrack != it) mp.setSpuTrack(it)
            }
            trackParams.preferredTextLanguages.isNotEmpty() -> {
                val nonForced = spuTracks.filterNot { it.name?.contains("forced", ignoreCase = true) == true }
                val matched = trackParams.preferredTextLanguages.firstNotNullOfOrNull { pref ->
                    val normPref = pref.lowercase().substringBefore('-').substringBefore('_')
                    nonForced.firstOrNull { EmbeddedSubtitles.parseLanguageFromLabel(it.name) == normPref }
                        ?: spuTracks.firstOrNull { EmbeddedSubtitles.parseLanguageFromLabel(it.name) == normPref }
                }
                if (matched != null) {
                    if (mp.spuTrack != matched.id) mp.setSpuTrack(matched.id)
                } else if (spuTracks.isNotEmpty() && mp.spuTrack >= 0) {
                    mp.setSpuTrack(-1)
                }
            }
            spuTracks.isNotEmpty() && mp.spuTrack >= 0 -> mp.setSpuTrack(-1)
        }
        mp.setVideoTrackEnabled(C.TRACK_TYPE_VIDEO !in trackParams.disabledTrackTypes)
    }

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        trackParams = trackSelectionParameters
        applyTrackSelectionInternal()
        refreshTracks()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        val vout = mp.vlcVout
        if (vout.areViewsAttached()) vout.detachViews()
        detachSubtitlesView()
        videoView?.removeOnLayoutChangeListener(layoutListener)
        when (videoOutput) {
            is SurfaceView -> {
                vout.setVideoView(videoOutput)
                val parent = videoOutput.parent as? ViewGroup
                if (parent != null) {
                    val idx = parent.indexOfChild(videoOutput).coerceAtLeast(0)
                    val sub = SurfaceView(videoOutput.context).apply {
                        setZOrderMediaOverlay(true)
                        holder.setFormat(PixelFormat.TRANSLUCENT)
                        visibility = if (suppressNativeSubtitles) View.INVISIBLE else View.VISIBLE
                    }
                    parent.addView(
                        sub,
                        idx + 1,
                        videoOutput.layoutParams ?: ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    subtitlesView = sub
                    vout.setSubtitlesView(sub)
                }
            }
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
                refreshTracks()
                invalidateState()
            }
        })
        if (view.width > 0 && view.height > 0) vout.setWindowSize(view.width, view.height)
        if (videoSize == VideoSize.UNKNOWN) updateVideoSizeFromTrack()
        refreshTracks()
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        videoView?.removeOnLayoutChangeListener(layoutListener)
        videoView = null
        if (mp.vlcVout.areViewsAttached()) mp.vlcVout.detachViews()
        detachSubtitlesView()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        directRemoteStream = false
        lastDirectReadBytes = -1L
        mp.stop()
        playbackState = Player.STATE_IDLE
        videoSize = VideoSize.UNKNOWN
        tracks = Tracks.EMPTY
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        directRemoteStream = false
        lastDirectReadBytes = -1L
        mp.setEventListener(null)
        mp.stop()
        audioBridge.detach()
        if (mp.vlcVout.areViewsAttached()) mp.vlcVout.detachViews()
        detachSubtitlesView()
        mp.release()
        libVlc.release()
        return Futures.immediateVoidFuture()
    }

    companion object {
        private val DIRECT_STREAM_SCHEMES = setOf("rtsp", "rtsps", "rtmp", "rtmps", "rtp", "udp", "mms", "mmsh")

        /** 需要用 VLC 内核的文件：光盘镜像。 */
        fun needsVlc(name: String) = name.substringAfterLast('.', "").lowercase() == "iso"
    }
}
