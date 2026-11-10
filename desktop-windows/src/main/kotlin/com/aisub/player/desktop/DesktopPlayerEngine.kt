package com.aisub.player.desktop

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.aisub.player.live.LiveHeaders
import com.aisub.player.media.MediaProxy
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.cloud.CloudRegistry
import com.aisub.player.telegram.Telegram
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.LinuxVideoSurfaceAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.WindowsVideoSurfaceAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import javax.sound.sampled.SourceDataLine
import kotlin.math.roundToInt

object DesktopPlayerEngine {
    private val _currentFrameFlow = MutableStateFlow<ImageBitmap?>(null)
    val currentFrameFlow: StateFlow<ImageBitmap?> = _currentFrameFlow.asStateFlow()

    @Volatile
    var latestFrameBitmap: Bitmap? = null

    @Volatile
    var masterVolume: Float = 1.0f
        private set

    @Volatile
    var activePlayer: DesktopExoPlayer? = null

    val vlcAvailable: Boolean by lazy {
        runCatching {
            // 常见 Windows VLC 64 位安装路径加入到 JNA 搜索路径
            val vlcDirs = listOf(
                File("C:/Program Files/VideoLAN/VLC"),
                File("C:/Program Files (x86)/VideoLAN/VLC"),
                File(DesktopBinaryFinder.binDir, "vlc"),
            )
            vlcDirs.firstOrNull { File(it, "libvlc.dll").exists() }?.let { dir ->
                val cur = System.getProperty("jna.library.path").orEmpty()
                System.setProperty(
                    "jna.library.path",
                    if (cur.isEmpty()) dir.absolutePath else "$cur${File.pathSeparator}${dir.absolutePath}",
                )
            }
            NativeDiscovery().discover()
        }.getOrDefault(false)
    }

    internal fun updateFrame(bufferedImage: BufferedImage) {
        val bmp = Bitmap(bufferedImage)
        latestFrameBitmap = bmp
        _currentFrameFlow.value = bufferedImage.toComposeImageBitmap()
    }

    internal fun clearFrame() {
        latestFrameBitmap = null
        _currentFrameFlow.value = null
    }

    fun setMasterVolume(vol: Float) {
        masterVolume = vol.coerceIn(0f, 1f)
        activePlayer?.updateEffectiveVolume()
    }

    fun applyEqualizer(enabled: Boolean, bandLevels: ShortArray) {
        activePlayer?.applyEqualizer(enabled, bandLevels)
    }
}

class DesktopExoPlayer(
    private val context: Context,
    private val dataSourceFactory: DataSource.Factory?,
) : ExoPlayer {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Player.Listener>()
    private val playlist = ArrayList<MediaItem>()
    private var proxy: MediaProxy? = null

    override var currentMediaItemIndex: Int = 0
        private set

    override val mediaItemCount: Int get() = playlist.size
    override val currentMediaItem: MediaItem? get() = playlist.getOrNull(currentMediaItemIndex)
    override fun getMediaItemAt(index: Int): MediaItem = playlist[index]

    override var playWhenReady: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyPlayWhenReady(value)
            notifyMain {
                listeners.forEach {
                    it.onPlayWhenReadyChanged(value, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    it.onIsPlayingChanged(isPlaying)
                }
            }
        }

    override var playbackState: Int = Player.STATE_IDLE
        private set(value) {
            if (field == value) return
            field = value
            notifyMain {
                listeners.forEach {
                    it.onPlaybackStateChanged(value)
                    it.onIsPlayingChanged(isPlaying)
                }
            }
        }

    override val isPlaying: Boolean
        get() = playWhenReady && playbackState == Player.STATE_READY

    override var playerError: PlaybackException? = null
        private set

    override var duration: Long = C.TIME_UNSET
        private set

    @Volatile
    private var positionMsInternal: Long = 0L

    override val currentPosition: Long
        get() {
            vlcMp?.let { mp ->
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                    val t = runCatching { mp.status().time() }.getOrDefault(-1L)
                    if (t >= 0) positionMsInternal = t
                }
            }
            wavClip?.let { c ->
                if (c.isOpen) positionMsInternal = c.microsecondPosition / 1000L
            }
            return positionMsInternal
        }

    override val bufferedPosition: Long
        get() = (currentPosition + 5000L).let { if (duration != C.TIME_UNSET) it.coerceAtMost(duration) else it }

    override val currentLiveOffset: Long = 2000L
    override var isCurrentMediaItemLive: Boolean = false
        private set
    override val isCurrentMediaItemSeekable: Boolean
        get() = !isCurrentMediaItemLive && duration != C.TIME_UNSET && duration > 0

    override var repeatMode: Int = Player.REPEAT_MODE_OFF
    override var shuffleModeEnabled: Boolean = false

    override var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
        set(value) {
            field = value
            runCatching { vlcMp?.controls()?.setRate(value.speed) }
        }

    override var volume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateEffectiveVolume()
        }

    override var trackSelectionParameters: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
        set(value) {
            field = value
            applyTrackSelection(value)
        }

    override var currentTracks: Tracks = Tracks.EMPTY
        private set

    override var videoSize: VideoSize = VideoSize.UNKNOWN
        private set

    override val currentTimeline: Timeline
        get() = if (playlist.isEmpty()) Timeline.EMPTY else object : Timeline() {
            override val windowCount: Int get() = playlist.size
            override fun getWindow(windowIndex: Int, window: Window): Window {
                window.durationUs = if (duration == C.TIME_UNSET) C.TIME_UNSET else duration * 1000L
                window.isSeekable = isCurrentMediaItemSeekable
                window.isDynamic = isCurrentMediaItemLive
                return window
            }
        }

    override val currentManifest: Any? = null
    override val audioSessionId: Int = 1
    override var videoFormat: Format? = null
        private set
    override var audioFormat: Format? = null
        private set
    override val isLoading: Boolean get() = playbackState == Player.STATE_BUFFERING
    override val totalBufferedDuration: Long get() = 5000L

    // 后端 1: VLCJ
    private var vlcFactory: MediaPlayerFactory? = null
    private var vlcMp: EmbeddedMediaPlayer? = null

    // 后端 2: 纯 Java WAV Clip (用于听书等短音频)
    private var wavClip: Clip? = null

    // 后端 3: FFmpeg 软解流 (无 VLC 时的全格式兜底)
    private val ffmpegRunning = AtomicBoolean(false)
    private var ffmpegAudioProc: Process? = null
    private var ffmpegVideoProc: Process? = null
    private var ffmpegAudioLine: SourceDataLine? = null

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
    }

    override fun setSeekParameters(seekParameters: SeekParameters?) {}
    override fun addAnalyticsListener(listener: AnalyticsListener) {}

    override fun getMediaMetadata(): MediaMetadata =
        currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY

    override fun setMediaItem(mediaItem: MediaItem) = setMediaItems(listOf(mediaItem), 0, C.TIME_UNSET)

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) =
        setMediaItems(listOf(mediaItem), 0, startPositionMs)

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) =
        setMediaItems(mediaItems, 0, C.TIME_UNSET)

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        stopInternal()
        playlist.clear()
        playlist.addAll(mediaItems)
        currentMediaItemIndex = startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        positionMsInternal = if (startPositionMs != C.TIME_UNSET && startPositionMs > 0) startPositionMs else 0L
        duration = C.TIME_UNSET
        currentTracks = Tracks.EMPTY
        playerError = null
        notifyMain {
            listeners.forEach { it.onMediaItemTransition(currentMediaItem, 0) }
        }
    }

    override fun addMediaItem(mediaItem: MediaItem) {
        playlist.add(mediaItem)
    }

    override fun addMediaItems(mediaItems: List<MediaItem>) {
        playlist.addAll(mediaItems)
    }

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        if (index in playlist.indices) playlist[index] = mediaItem
    }

    override fun clearMediaItems() {
        stopInternal()
        playlist.clear()
        playbackState = Player.STATE_IDLE
    }

    override fun prepare() {
        val item = currentMediaItem ?: return
        playerError = null
        playbackState = Player.STATE_BUFFERING
        if (volume > 0f) DesktopPlayerEngine.activePlayer = this
        startCurrentItem(item, positionMsInternal)
    }

    override fun play() {
        playWhenReady = true
    }

    override fun pause() {
        playWhenReady = false
    }

    override fun stop() {
        stopInternal()
        playbackState = Player.STATE_IDLE
    }

    override fun release() {
        stopInternal()
        runCatching { vlcMp?.release() }
        runCatching { vlcFactory?.release() }
        vlcMp = null
        vlcFactory = null
        listeners.clear()
        if (DesktopPlayerEngine.activePlayer === this) {
            DesktopPlayerEngine.activePlayer = null
        }
    }

    override fun seekTo(positionMs: Long) = seekTo(currentMediaItemIndex, positionMs)

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val targetPos = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
        val itemChanged = mediaItemIndex != currentMediaItemIndex && mediaItemIndex in playlist.indices
        if (itemChanged) {
            val oldPos = Player.PositionInfo(currentMediaItemIndex, currentPosition)
            currentMediaItemIndex = mediaItemIndex
            positionMsInternal = targetPos
            val newPos = Player.PositionInfo(currentMediaItemIndex, targetPos)
            notifyMain {
                listeners.forEach {
                    it.onPositionDiscontinuity(oldPos, newPos, Player.DISCONTINUITY_REASON_SEEK)
                    it.onMediaItemTransition(currentMediaItem, 0)
                }
            }
            if (playbackState != Player.STATE_IDLE) {
                currentMediaItem?.let { startCurrentItem(it, targetPos) }
            }
            return
        }

        positionMsInternal = targetPos
        vlcMp?.let { mp ->
            runCatching { mp.controls().setTime(targetPos) }
            return
        }
        wavClip?.let { c ->
            runCatching { c.microsecondPosition = targetPos * 1000L }
            return
        }
        if (ffmpegRunning.get()) {
            currentMediaItem?.let { startCurrentItem(it, targetPos) }
        }
    }

    override fun seekToDefaultPosition() = seekTo(currentMediaItemIndex, 0L)
    override fun seekToDefaultPosition(mediaItemIndex: Int) = seekTo(mediaItemIndex, 0L)

    override fun seekToNext() {
        if (hasNextMediaItem()) seekTo(currentMediaItemIndex + 1, 0L)
    }

    override fun seekToPrevious() {
        if (currentPosition > 3000L || !hasPreviousMediaItem()) {
            seekTo(0L)
        } else {
            seekTo(currentMediaItemIndex - 1, 0L)
        }
    }

    override fun seekToNextMediaItem() = seekToNext()
    override fun seekToPreviousMediaItem() {
        if (hasPreviousMediaItem()) seekTo(currentMediaItemIndex - 1, 0L)
    }

    override fun hasNextMediaItem(): Boolean = currentMediaItemIndex + 1 < playlist.size
    override fun hasPreviousMediaItem(): Boolean = currentMediaItemIndex > 0

    internal fun updateEffectiveVolume() {
        val eff = (volume * DesktopPlayerEngine.masterVolume).coerceIn(0f, 1f)
        runCatching { vlcMp?.audio()?.setVolume((eff * 100).roundToInt()) }
    }

    internal fun applyEqualizer(enabled: Boolean, bandLevels: ShortArray) {
        // VLCJ / FFmpeg 音量与均衡器参数已保存于 AudioEqualizer
    }

    private fun applyPlayWhenReady(ready: Boolean) {
        vlcMp?.let { mp ->
            runCatching {
                if (ready) mp.controls().play() else mp.controls().setPause(true)
            }
        }
        wavClip?.let { c ->
            runCatching {
                if (ready) c.start() else c.stop()
            }
        }
    }

    private fun applyTrackSelection(params: TrackSelectionParameters) {
        val mp = vlcMp ?: return
        val audioOverride = params.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
        if (C.TRACK_TYPE_AUDIO in params.disabledTrackTypes) {
            runCatching { mp.audio().setTrack(-1) }
        } else if (audioOverride != null) {
            val id = audioOverride.mediaTrackGroup.id.removePrefix("a").toIntOrNull()
            if (id != null) runCatching { mp.audio().setTrack(id) }
        }
        val textOverride = params.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
        if (C.TRACK_TYPE_TEXT in params.disabledTrackTypes) {
            runCatching { mp.subpictures().setTrack(-1) }
        } else if (textOverride != null) {
            val id = textOverride.mediaTrackGroup.id.removePrefix("s").toIntOrNull()
            if (id != null) runCatching { mp.subpictures().setTrack(id) }
        }
    }

    private fun resolvePlayableUrlAndHeaders(item: MediaItem): Pair<String, Map<String, String>> {
        val uri = item.localConfiguration?.uri ?: Uri.parse(item.mediaId)
        val rawStr = uri.toString()
        val name = item.mediaMetadata.title?.toString() ?: uri.lastPathSegment ?: "video.mp4"

        // 本地文件直接返回绝对路径
        val localFile = ContentResolver.resolveFile(uri)
        if (localFile != null && localFile.exists()) {
            return localFile.absolutePath to emptyMap()
        }

        // Telegram 或 Cloud 或带鉴权的 WebDAV：走本机 MediaProxy
        if (Telegram.isTelegram(rawStr) || CloudRegistry.isCloud(rawStr)) {
            val dsFactory = dataSourceFactory
            if (dsFactory != null) {
                val p = proxy ?: MediaProxy(dsFactory).also { proxy = it }
                return p.register(uri, name) to emptyMap()
            }
        }

        val routed = Endpoints.rewrite(rawStr)
        val headers = AuthRegistry.headersFor(routed) + LiveHeaders.headersFor(routed)
        if (headers.containsKey("Authorization") && dataSourceFactory != null) {
            val p = proxy ?: MediaProxy(dataSourceFactory).also { proxy = it }
            return p.register(uri, name) to emptyMap()
        }
        return routed to headers
    }

    private fun startCurrentItem(item: MediaItem, startPosMs: Long) {
        stopInternal()
        val uri = item.localConfiguration?.uri ?: Uri.parse(item.mediaId)
        val localFile = ContentResolver.resolveFile(uri)

        // 1. 本地 WAV 短语音（如听书 BookTts）：直接用 Java Sound Clip 播放，零延迟
        if (localFile != null && localFile.exists() && localFile.extension.equals("wav", ignoreCase = true)) {
            if (startWithJavaWavClip(localFile, startPosMs)) return
        }

        val (playableUrl, headers) = runCatching { resolvePlayableUrlAndHeaders(item) }.getOrElse { e ->
            onError(PlaybackException(e.message, e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
            return
        }
        isCurrentMediaItemLive = playableUrl.contains(".m3u8", ignoreCase = true) ||
            item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8

        // 2. 优先使用 VLCJ (若系统装有 VLC)
        if (DesktopPlayerEngine.vlcAvailable && startWithVlcj(playableUrl, headers, startPosMs)) {
            return
        }

        // 3. 使用内置 FFmpeg + Java Sound 引擎
        startWithFfmpeg(playableUrl, headers, startPosMs)
    }

    private fun startWithJavaWavClip(file: File, startPosMs: Long): Boolean {
        return runCatching {
            AudioSystem.getAudioInputStream(file).use { ais ->
                val clip = AudioSystem.getClip()
                clip.open(ais)
                duration = (clip.microsecondLength / 1000L).coerceAtLeast(1L)
                if (startPosMs > 0) {
                    clip.microsecondPosition = (startPosMs * 1000L).coerceAtMost(clip.microsecondLength)
                }
                clip.addLineListener { ev ->
                    if (ev.type == LineEvent.Type.STOP && clip.isOpen && clip.microsecondPosition >= clip.microsecondLength - 20_000L) {
                        onItemEnded()
                    }
                }
                wavClip = clip
                playbackState = Player.STATE_READY
                if (playWhenReady) clip.start()
                true
            }
        }.getOrDefault(false)
    }

    private fun startWithVlcj(url: String, headers: Map<String, String>, startPosMs: Long): Boolean {
        return runCatching {
            val factory = vlcFactory ?: MediaPlayerFactory(
                "--http-reconnect",
                "--network-caching=2000",
                "--audio-time-stretch",
                "--no-video-title-show",
            ).also { vlcFactory = it }

            val mp = vlcMp ?: factory.mediaPlayers().newEmbeddedMediaPlayer().also { created ->
                setupVlcSurface(factory, created)
                setupVlcEvents(created)
                vlcMp = created
            }

            val options = mutableListOf(":no-dvdnav-menu", ":no-bluray-menu")
            if (startPosMs > 0) {
                options.add(":start-time=${startPosMs / 1000.0}")
            }
            headers["User-Agent"]?.let { options.add(":http-user-agent=$it") }
            headers["Referer"]?.let { options.add(":http-referrer=$it") }

            updateEffectiveVolume()
            mp.controls().setRate(playbackParameters.speed)
            val ok = mp.media().startPaused(url, *options.toTypedArray())
            if (!ok) return false
            if (playWhenReady) {
                mp.controls().play()
            } else {
                playbackState = Player.STATE_READY
            }
            true
        }.getOrDefault(false)
    }

    private fun setupVlcSurface(factory: MediaPlayerFactory, mp: EmbeddedMediaPlayer) {
        var videoWidth = 0
        var videoHeight = 0
        var targetImage: BufferedImage? = null

        val bufferFormatCallback = object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                videoWidth = sourceWidth.coerceAtLeast(1)
                videoHeight = sourceHeight.coerceAtLeast(1)
                targetImage = BufferedImage(videoWidth, videoHeight, BufferedImage.TYPE_INT_RGB)
                val sz = VideoSize(videoWidth, videoHeight)
                videoSize = sz
                notifyMain {
                    listeners.forEach {
                        it.onVideoSizeChanged(sz)
                        it.onRenderedFirstFrame()
                    }
                }
                return RV32BufferFormat(videoWidth, videoHeight)
            }

            override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}
        }

        val renderCallback = RenderCallback { _, nativeBuffers, _ ->
            val img = targetImage ?: return@RenderCallback
            val buf = nativeBuffers.firstOrNull() ?: return@RenderCallback
            if (volume > 0f) {
                val ints = (img.raster.dataBuffer as DataBufferInt).data
                buf.rewind()
                buf.asIntBuffer().get(ints, 0, minOf(ints.size, buf.remaining() / 4))
                DesktopPlayerEngine.updateFrame(img)
            }
        }

        val adapter = if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
            WindowsVideoSurfaceAdapter()
        } else {
            LinuxVideoSurfaceAdapter()
        }
        val surface = CallbackVideoSurface(bufferFormatCallback, renderCallback, true, adapter)
        mp.videoSurface().set(surface)
    }

    private fun setupVlcEvents(mp: EmbeddedMediaPlayer) {
        mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) {
                playbackState = Player.STATE_READY
                refreshVlcTracks(mediaPlayer)
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                if (playbackState == Player.STATE_BUFFERING) playbackState = Player.STATE_READY
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                if (newLength > 0) duration = newLength
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                onItemEnded()
            }

            override fun error(mediaPlayer: MediaPlayer) {
                onError(
                    PlaybackException(
                        "无法播放该媒体流",
                        null,
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    ),
                )
            }
        })
    }

    private fun refreshVlcTracks(mp: MediaPlayer) {
        runCatching {
            val groups = ArrayList<Tracks.Group>()
            val currentAudio = mp.audio().track()
            for (d in mp.audio().trackDescriptions().orEmpty()) {
                if (d.id() < 0) continue
                val f = Format.Builder().setId("a${d.id()}").setLabel(d.description()).setSampleMimeType(MimeTypes.AUDIO_UNKNOWN).build()
                groups.add(Tracks.Group(TrackGroup("a${d.id()}", f), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(d.id() == currentAudio)))
            }
            val currentSpu = mp.subpictures().track()
            for (d in mp.subpictures().trackDescriptions().orEmpty()) {
                if (d.id() < 0) continue
                val f = Format.Builder().setId("s${d.id()}").setLabel(d.description()).setSampleMimeType(MimeTypes.TEXT_UNKNOWN).build()
                groups.add(Tracks.Group(TrackGroup("s${d.id()}", f), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(d.id() == currentSpu)))
            }
            currentTracks = Tracks(groups)
            notifyMain { listeners.forEach { it.onTracksChanged(currentTracks) } }
        }
    }

    private fun startWithFfmpeg(url: String, headers: Map<String, String>, startPosMs: Long) {
        ffmpegRunning.set(true)
        ioPool.submit {
            val ffmpeg = DesktopBinaryFinder.ensureFfmpeg()
            if (ffmpeg == null) {
                onError(
                    PlaybackException(
                        "未检测到 VLC (64位) 或 FFmpeg 组件，请安装 VLC 播放器或检查网络连接以自动下载 FFmpeg",
                        null,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    ),
                )
                return@submit
            }

            // 探测时长与视频分辨率
            var width = 1280
            var height = 720
            var hasVideo = true
            var fps = 25.0
            DesktopBinaryFinder.findFfprobe()?.let { ffprobe ->
                runCatching {
                    val cmd = mutableListOf(
                        ffprobe, "-v", "error",
                        "-show_entries", "format=duration:stream=index,codec_type,codec_name,width,height,r_frame_rate",
                        "-of", "json",
                    )
                    if (headers.isNotEmpty() && url.startsWith("http")) {
                        val hdr = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
                        cmd.addAll(listOf("-headers", hdr))
                    }
                    cmd.add(url)
                    val proc = ProcessBuilder(cmd).start()
                    val jsonText = proc.inputStream.bufferedReader().readText()
                    proc.waitFor()
                    val root = JSONObject(jsonText)
                    val durSec = root.optJSONObject("format")?.optString("duration")?.toDoubleOrNull()
                    if (durSec != null && durSec > 0) {
                        duration = (durSec * 1000.0).toLong()
                    }
                    val streams = root.optJSONArray("streams")
                    var foundVideo = false
                    if (streams != null) {
                        for (i in 0 until streams.length()) {
                            val s = streams.getJSONObject(i)
                            if (s.optString("codec_type") == "video" && !foundVideo) {
                                val w = s.optInt("width", 0)
                                val h = s.optInt("height", 0)
                                if (w > 0 && h > 0) {
                                    width = if (w > 1280) 1280 else (w / 2 * 2).coerceAtLeast(2)
                                    height = ((h.toDouble() * width / w).toInt() / 2 * 2).coerceAtLeast(2)
                                    foundVideo = true
                                    val rateStr = s.optString("r_frame_rate", "25/1")
                                    val parts = rateStr.split('/')
                                    if (parts.size == 2) {
                                        val num = parts[0].toDoubleOrNull() ?: 25.0
                                        val den = parts[1].toDoubleOrNull() ?: 1.0
                                        if (den > 0 && num / den in 1.0..120.0) fps = num / den
                                    }
                                }
                            }
                        }
                    }
                    hasVideo = foundVideo
                }
            }

            if (!ffmpegRunning.get()) return@submit

            if (hasVideo) {
                val sz = VideoSize(width, height)
                videoSize = sz
                notifyMain {
                    listeners.forEach {
                        it.onVideoSizeChanged(sz)
                        it.onRenderedFirstFrame()
                    }
                }
                startFfmpegVideoThread(ffmpeg, url, headers, startPosMs, width, height, fps)
            }

            startFfmpegAudioThread(ffmpeg, url, headers, startPosMs)
        }
    }

    private fun startFfmpegVideoThread(
        ffmpeg: String,
        url: String,
        headers: Map<String, String>,
        startPosMs: Long,
        width: Int,
        height: Int,
        fps: Double,
    ) {
        ioPool.submit {
            runCatching {
                val cmd = mutableListOf(ffmpeg, "-v", "error")
                if (startPosMs > 0) {
                    cmd.addAll(listOf("-ss", "%.3f".format(java.util.Locale.US, startPosMs / 1000.0)))
                }
                if (headers.isNotEmpty() && url.startsWith("http")) {
                    val hdr = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
                    cmd.addAll(listOf("-headers", hdr))
                }
                cmd.addAll(
                    listOf(
                        "-re",
                        "-i", url,
                        "-an", "-sn",
                        "-vf", "scale=$width:$height",
                        "-pix_fmt", "bgr24",
                        "-f", "rawvideo", "-",
                    ),
                )
                val proc = ProcessBuilder(cmd).start()
                ffmpegVideoProc = proc
                val frameBytes = ByteArray(width * height * 3)
                val ins = proc.inputStream
                while (ffmpegRunning.get()) {
                    if (!playWhenReady) {
                        Thread.sleep(40)
                        continue
                    }
                    var off = 0
                    while (off < frameBytes.size && ffmpegRunning.get()) {
                        val n = ins.read(frameBytes, off, frameBytes.size - off)
                        if (n < 0) break
                        off += n
                    }
                    if (off < frameBytes.size) break
                    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                    val pixels = (img.raster.dataBuffer as DataBufferInt).data
                    var bi = 0
                    for (pi in pixels.indices) {
                        val b = frameBytes[bi].toInt() and 0xFF
                        val g = frameBytes[bi + 1].toInt() and 0xFF
                        val r = frameBytes[bi + 2].toInt() and 0xFF
                        pixels[pi] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        bi += 3
                    }
                    DesktopPlayerEngine.updateFrame(img)
                }
                proc.destroy()
            }
        }
    }

    private fun startFfmpegAudioThread(
        ffmpeg: String,
        url: String,
        headers: Map<String, String>,
        startPosMs: Long,
    ) {
        ioPool.submit {
            try {
                val cmd = mutableListOf(ffmpeg, "-v", "error")
                if (startPosMs > 0) {
                    cmd.addAll(listOf("-ss", "%.3f".format(java.util.Locale.US, startPosMs / 1000.0)))
                }
                if (headers.isNotEmpty() && url.startsWith("http")) {
                    val hdr = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
                    cmd.addAll(listOf("-headers", hdr))
                }
                cmd.addAll(
                    listOf(
                        "-i", url,
                        "-vn", "-sn",
                        "-ac", "2",
                        "-ar", "48000",
                        "-f", "s16le", "-",
                    ),
                )
                val proc = ProcessBuilder(cmd).start()
                ffmpegAudioProc = proc
                val format = AudioFormat(48000f, 16, 2, true, false)
                val line = runCatching { AudioSystem.getSourceDataLine(format) }.getOrNull()
                line?.open(format, 48000 * 4 / 5)
                line?.start()
                ffmpegAudioLine = line

                playbackState = Player.STATE_READY
                val buf = ByteArray(4800 * 4) // 100ms
                val ins = proc.inputStream
                var bytesPlayed = 0L
                while (ffmpegRunning.get()) {
                    if (!playWhenReady) {
                        Thread.sleep(30)
                        continue
                    }
                    val n = ins.read(buf, 0, buf.size)
                    if (n < 0) break
                    val effVol = (volume * DesktopPlayerEngine.masterVolume).coerceIn(0f, 1f)
                    if (effVol < 0.99f) {
                        var i = 0
                        while (i + 1 < n) {
                            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
                            val scaled = (sample * effVol).toInt().coerceIn(-32768, 32767).toShort()
                            buf[i] = (scaled.toInt() and 0xFF).toByte()
                            buf[i + 1] = ((scaled.toInt() shr 8) and 0xFF).toByte()
                            i += 2
                        }
                    }
                    line?.write(buf, 0, n - (n % 4))
                    bytesPlayed += n
                    positionMsInternal = startPosMs + (bytesPlayed * 1000L) / (48000L * 4L)
                }
                line?.drain()
                line?.close()
                proc.destroy()
                if (ffmpegRunning.get()) {
                    onItemEnded()
                }
            } catch (e: Exception) {
                if (ffmpegRunning.get()) {
                    onError(PlaybackException(e.message, e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                }
            }
        }
    }

    private fun onItemEnded() {
        notifyMain {
            when {
                repeatMode == Player.REPEAT_MODE_ONE -> {
                    seekTo(currentMediaItemIndex, 0L)
                }
                hasNextMediaItem() -> {
                    seekTo(currentMediaItemIndex + 1, 0L)
                }
                repeatMode == Player.REPEAT_MODE_ALL && playlist.isNotEmpty() -> {
                    seekTo(0, 0L)
                }
                else -> {
                    playbackState = Player.STATE_ENDED
                }
            }
        }
    }

    private fun onError(err: PlaybackException) {
        playerError = err
        playbackState = Player.STATE_IDLE
        notifyMain {
            listeners.forEach { it.onPlayerError(err) }
        }
    }

    private fun stopInternal() {
        ffmpegRunning.set(false)
        runCatching { ffmpegAudioProc?.destroyForcibly() }
        runCatching { ffmpegVideoProc?.destroyForcibly() }
        runCatching { ffmpegAudioLine?.close() }
        ffmpegAudioProc = null
        ffmpegVideoProc = null
        ffmpegAudioLine = null

        runCatching { wavClip?.stop() }
        runCatching { wavClip?.close() }
        wavClip = null

        runCatching { vlcMp?.controls()?.stop() }
    }

    private fun notifyMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    companion object {
        private val ioPool = Executors.newCachedThreadPool { r ->
            Thread(r, "Voxtitle-DesktopPlayer").apply { isDaemon = true }
        }
    }
}
