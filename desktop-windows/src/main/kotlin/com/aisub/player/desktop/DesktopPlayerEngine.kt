package com.aisub.player.desktop

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
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
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.json.JSONObject
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.media.TrackType
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
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import javax.sound.sampled.SourceDataLine
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

private const val TAG = "DesktopPlayer"

/**
 * 零分配三缓冲视频帧槽位：
 * - writeSlot: 解码回调线程独占写入，直接 installPixels 到 Skia
 * - readySlot: 最新写完待显示的帧
 * - readSlot:  Compose UI 线程独占读取绘制，无撕裂、无每帧堆分配
 */
internal class FrameSlot(val width: Int, val height: Int) {
    val bytes = ByteArray(width * height * 4)
    val imageInfo = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
    val skiaBitmap = SkiaBitmap().apply { allocPixels(imageInfo) }
    val composeBitmap: ImageBitmap = skiaBitmap.asComposeImageBitmap()

    fun uploadToSkia() {
        skiaBitmap.installPixels(imageInfo, bytes, width * 4)
        skiaBitmap.notifyPixelsChanged()
    }
}

object DesktopPlayerEngine {
    private val _currentFrameFlow = MutableStateFlow<ImageBitmap?>(null)
    val currentFrameFlow: StateFlow<ImageBitmap?> = _currentFrameFlow.asStateFlow()

    /** 仅供 Canvas 绘制阶段订阅的帧序号，避免触发外层 Composable 重组与布局 */
    val frameTick = mutableLongStateOf(0L)

    @Volatile
    var currentDrawBitmap: ImageBitmap? = null
        private set

    private val swapLock = Any()
    private var slotWidth = 0
    private var slotHeight = 0
    private var writeSlot: FrameSlot? = null
    private var readySlot: FrameSlot? = null
    private var readSlot: FrameSlot? = null
    private var hasReadyFrame = false

    @Volatile
    private var hasAnyFrame = false

    private val frameCounter = AtomicLong(0L)
    private val frameNotifyPending = AtomicBoolean(false)

    // 渲染帧率与合并统计
    private val decodedFramesWindow = AtomicInteger(0)
    private val drawnFramesWindow = AtomicInteger(0)
    private val coalescedFramesWindow = AtomicInteger(0)
    @Volatile
    private var statsWindowStartMs = System.currentTimeMillis()

    // 截图 / 书签（PixelCopy）按需懒加载缓存，正常播放期间零分配
    @Volatile
    private var manualFrameBitmap: Bitmap? = null
    @Volatile
    private var cachedSnapshotTick: Long = -1L
    @Volatile
    private var cachedSnapshotBitmap: Bitmap? = null

    val latestFrameBitmap: Bitmap?
        get() {
            manualFrameBitmap?.let { return it }
            if (!hasAnyFrame) return null
            val tick = frameCounter.get()
            if (cachedSnapshotTick == tick && cachedSnapshotBitmap != null) {
                return cachedSnapshotBitmap
            }
            val (w, h, rawBytes) = synchronized(swapLock) {
                val slot = readSlot ?: readySlot ?: return null
                Triple(slot.width, slot.height, slot.bytes.clone())
            }
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val pixels = (img.raster.dataBuffer as DataBufferInt).data
            var bi = 0
            for (pi in pixels.indices) {
                val b = rawBytes[bi].toInt() and 0xFF
                val g = rawBytes[bi + 1].toInt() and 0xFF
                val r = rawBytes[bi + 2].toInt() and 0xFF
                pixels[pi] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                bi += 4
            }
            val bmp = Bitmap(img)
            cachedSnapshotBitmap = bmp
            cachedSnapshotTick = tick
            return bmp
        }

    @Volatile
    var displayWidthPx: Int = 1920
        private set

    @Volatile
    var displayHeightPx: Int = 1080
        private set

    fun updateDisplaySize(widthPx: Int, heightPx: Int) {
        if (widthPx > 64 && heightPx > 64) {
            displayWidthPx = widthPx
            displayHeightPx = heightPx
        }
    }

    const val MIN_BRIGHTNESS = 0.20f
    const val MAX_BRIGHTNESS = 1.80f

    val masterVolumeState = androidx.compose.runtime.mutableFloatStateOf(1.0f)
    val mutedState = androidx.compose.runtime.mutableStateOf(false)
    val brightnessState = androidx.compose.runtime.mutableFloatStateOf(1.0f)

    @Volatile
    var masterVolume: Float = 1.0f
        private set

    @Volatile
    var isMuted: Boolean = false
        private set

    @Volatile
    var videoBrightness: Float = 1.0f
        private set

    val effectiveMasterVolume: Float
        get() = if (isMuted) 0f else masterVolume

    @Volatile
    private var volumePrefs: android.content.SharedPreferences? = null

    @Volatile
    private var volumeModifiedInSession: Boolean = false

    internal fun initPrefsIfNeeded(context: Context?) {
        if (context == null || volumePrefs != null) return
        val p = runCatching { context.getSharedPreferences("player", Context.MODE_PRIVATE) }.getOrNull() ?: return
        volumePrefs = p
        if (!volumeModifiedInSession) {
            val savedVol = p.getFloat("desktop.volume", 1.0f).coerceIn(0f, 1f)
            val savedMuted = p.getBoolean("desktop.muted", false)
            masterVolume = savedVol
            masterVolumeState.floatValue = savedVol
            isMuted = savedMuted
            mutedState.value = savedMuted
        }
    }

    @Volatile
    var activePlayer: DesktopExoPlayer? = null

    /** 主窗口内播放器正在显示 AI 字幕或自带字幕时置为 true，供桌面全局悬浮字幕自动感知避让 */
    @Volatile
    var inAppSubtitlesActive: Boolean = false

    val vlcAvailable: Boolean by lazy {
        val t0 = System.currentTimeMillis()
        val ok = runCatching {
            if (System.getProperty("jna.encoding").isNullOrBlank()) {
                System.setProperty("jna.encoding", "UTF-8")
            }
            val vlcDirs = listOf(
                File("C:/Program Files/VideoLAN/VLC"),
                File("C:/Program Files (x86)/VideoLAN/VLC"),
                File(DesktopBinaryFinder.binDir, "vlc"),
            )
            vlcDirs.firstOrNull { File(it, "libvlc.dll").exists() }?.let { dir ->
                val cur = System.getProperty("jna.library.path").orEmpty()
                if (!cur.contains(dir.absolutePath, ignoreCase = true)) {
                    System.setProperty(
                        "jna.library.path",
                        if (cur.isEmpty()) dir.absolutePath else "$cur${File.pathSeparator}${dir.absolutePath}",
                    )
                }
            }
            NativeDiscovery().discover()
        }.getOrDefault(false)
        Log.i(TAG, "VLC NativeDiscovery: available=$ok, 耗时=${System.currentTimeMillis() - t0}ms")
        ok
    }

    private val factoryLock = Any()

    @Volatile
    private var sharedVlcFactory: MediaPlayerFactory? = null

    /** 全局共享单个 libvlc MediaPlayerFactory，避免多实例重复加载插件与卡顿 */
    fun getSharedVlcFactory(): MediaPlayerFactory? {
        sharedVlcFactory?.let { return it }
        if (!vlcAvailable) return null
        synchronized(factoryLock) {
            sharedVlcFactory?.let { return it }
            val t0 = System.currentTimeMillis()
            return runCatching {
                MediaPlayerFactory(
                    "--avcodec-hw=any",
                    "--http-reconnect",
                    "--network-caching=1200",
                    "--file-caching=300",
                    "--prefetch-buffer-size=2048",
                    "--prefetch-read-size=65536",
                    "--no-lua",
                    "--audio-time-stretch",
                    "--no-video-title-show",
                    "--no-osd",
                    "--no-snapshot-preview",
                    "--quiet",
                ).also {
                    sharedVlcFactory = it
                    Log.i(TAG, "共享 MediaPlayerFactory 初始化完成, 耗时=${System.currentTimeMillis() - t0}ms")
                }
            }.onFailure { e ->
                Log.e(TAG, "初始化 MediaPlayerFactory 失败", e)
            }.getOrNull()
        }
    }

    /** 应用启动后在后台线程预热 VLC 与双地址服务器探测，避免首次点击播放时阻塞等待 */
    fun prewarm(context: Context? = null) {
        DesktopExoPlayer.ioPool.submit {
            runCatching { getSharedVlcFactory() }
        }
        if (context != null) {
            DesktopExoPlayer.ioPool.submit {
                runCatching {
                    com.aisub.player.media.AppStore(context).servers().forEach { s ->
                        Endpoints.register(s)
                        if (s.normalizedLan.isNotEmpty()) {
                            DesktopExoPlayer.ioPool.submit { runCatching { Endpoints.base(s) } }
                        }
                    }
                }
            }
        }
    }

    private fun acquireWriteSlot(width: Int, height: Int): FrameSlot {
        synchronized(swapLock) {
            if (slotWidth != width || slotHeight != height || writeSlot == null) {
                slotWidth = width
                slotHeight = height
                writeSlot = FrameSlot(width, height)
                readySlot = FrameSlot(width, height)
                readSlot = FrameSlot(width, height)
                hasReadyFrame = false
            }
            return writeSlot!!
        }
    }

    internal fun submitFrameBgraBuffer(width: Int, height: Int, buf: ByteBuffer) {
        val slot = acquireWriteSlot(width, height)
        buf.rewind()
        val count = minOf(slot.bytes.size, buf.remaining())
        buf.get(slot.bytes, 0, count)
        slot.uploadToSkia()
        publishWrittenSlot(slot)
    }

    internal fun readFrameBgraStream(
        width: Int,
        height: Int,
        ins: InputStream,
        shouldContinue: () -> Boolean,
        renderFrame: Boolean = true,
    ): Boolean {
        val slot = acquireWriteSlot(width, height)
        val target = slot.bytes
        var off = 0
        while (off < target.size && shouldContinue()) {
            val n = ins.read(target, off, target.size - off)
            if (n < 0) return false
            off += n
        }
        if (off < target.size) return false
        if (renderFrame) {
            slot.uploadToSkia()
            publishWrittenSlot(slot)
        }
        return true
    }

    private fun publishWrittenSlot(slot: FrameSlot) {
        synchronized(swapLock) {
            if (slot.width != slotWidth || slot.height != slotHeight) return
            if (hasReadyFrame) {
                coalescedFramesWindow.incrementAndGet()
            }
            writeSlot = readySlot
            readySlot = slot
            hasReadyFrame = true
            hasAnyFrame = true
            manualFrameBitmap = null
        }
        decodedFramesWindow.incrementAndGet()
        scheduleUiFrameSwap()
        logStatsIfNeeded()
    }

    private fun scheduleUiFrameSwap() {
        if (frameNotifyPending.compareAndSet(false, true)) {
            SwingUtilities.invokeLater {
                frameNotifyPending.set(false)
                var bmpToPublish: ImageBitmap? = null
                synchronized(swapLock) {
                    if (hasReadyFrame && readySlot != null && readSlot != null) {
                        val prevRead = readSlot
                        readSlot = readySlot
                        readySlot = prevRead
                        hasReadyFrame = false
                        bmpToPublish = readSlot?.composeBitmap
                    }
                }
                if (bmpToPublish != null) {
                    currentDrawBitmap = bmpToPublish
                    _currentFrameFlow.value = bmpToPublish
                    val tick = frameCounter.incrementAndGet()
                    frameTick.longValue = tick
                    drawnFramesWindow.incrementAndGet()
                }
            }
        }
    }

    private fun logStatsIfNeeded() {
        val now = System.currentTimeMillis()
        val elapsed = now - statsWindowStartMs
        if (elapsed >= 10_000L) {
            statsWindowStartMs = now
            val dec = decodedFramesWindow.getAndSet(0)
            val drw = drawnFramesWindow.getAndSet(0)
            val coal = coalescedFramesWindow.getAndSet(0)
            if (dec > 0) {
                val decFps = (dec * 1000.0 / elapsed)
                val drwFps = (drw * 1000.0 / elapsed)
                Log.d(
                    TAG,
                    "视频帧率统计: 解码=%.1ffps, 绘制=%.1ffps, 合并帧=%d, 缓冲尺寸=%dx%d".format(
                        java.util.Locale.US,
                        decFps,
                        drwFps,
                        coal,
                        slotWidth,
                        slotHeight,
                    ),
                )
            }
        }
    }

    internal fun updateFrame(bufferedImage: BufferedImage) {
        val bmp = Bitmap(bufferedImage)
        manualFrameBitmap = bmp
        hasAnyFrame = true
        val composeBmp = bufferedImage.toComposeImageBitmap()
        SwingUtilities.invokeLater {
            currentDrawBitmap = composeBmp
            _currentFrameFlow.value = composeBmp
            frameTick.longValue = frameCounter.incrementAndGet()
        }
    }

    internal fun clearFrame() {
        synchronized(swapLock) {
            hasReadyFrame = false
            hasAnyFrame = false
            manualFrameBitmap = null
            cachedSnapshotBitmap = null
            cachedSnapshotTick = -1L
        }
        SwingUtilities.invokeLater {
            currentDrawBitmap = null
            _currentFrameFlow.value = null
            frameTick.longValue = frameCounter.incrementAndGet()
        }
    }

    fun setMasterVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1f)
        volumeModifiedInSession = true
        masterVolume = clamped
        masterVolumeState.floatValue = clamped
        if (clamped > 0.001f && isMuted) {
            isMuted = false
            mutedState.value = false
        }
        volumePrefs?.edit()
            ?.putFloat("desktop.volume", clamped)
            ?.putBoolean("desktop.muted", isMuted)
            ?.apply()
        activePlayer?.updateEffectiveVolume()
    }

    fun setMuted(muted: Boolean) {
        volumeModifiedInSession = true
        isMuted = muted
        mutedState.value = muted
        if (!muted && masterVolume <= 0.005f) {
            masterVolume = 0.50f
            masterVolumeState.floatValue = 0.50f
        }
        volumePrefs?.edit()
            ?.putFloat("desktop.volume", masterVolume)
            ?.putBoolean("desktop.muted", muted)
            ?.apply()
        activePlayer?.updateEffectiveVolume()
    }

    fun toggleMute(): Boolean {
        val next = !isMuted
        setMuted(next)
        return next
    }

    fun setVideoBrightness(value: Float) {
        val clamped = value.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        videoBrightness = clamped
        brightnessState.floatValue = clamped
    }

    fun resetVideoBrightness() = setVideoBrightness(1.0f)

    fun applyEqualizer(enabled: Boolean, bandLevels: ShortArray) {
        activePlayer?.applyEqualizer(enabled, bandLevels)
    }
}

class DesktopExoPlayer(
    private val context: Context,
    private val dataSourceFactory: DataSource.Factory?,
) : ExoPlayer {
    init {
        DesktopPlayerEngine.initPrefsIfNeeded(context)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Player.Listener>()
    private val playlist = ArrayList<MediaItem>()
    private var proxy: MediaProxy? = null

    /** 串行控制队列：所有 VLC / FFmpeg 启动、停止、切轨、跳转都在此后台线程执行，绝不阻塞 UI 线程 */
    private val vlcExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Voxtitle-PlayerCtrl").apply { isDaemon = true }
    }
    private val mediaGeneration = AtomicInteger(0)
    private val renderedFirstFrame = AtomicBoolean(false)
    private val seekTaskScheduled = AtomicBoolean(false)

    @Volatile
    private var pendingSeekTargetMs: Long = C.TIME_UNSET

    @Volatile
    private var prepareStartRealtimeMs: Long = 0L

    override var currentMediaItemIndex: Int = 0
        private set

    override val mediaItemCount: Int get() = playlist.size
    override val currentMediaItem: MediaItem? get() = playlist.getOrNull(currentMediaItemIndex)
    override fun getMediaItemAt(index: Int): MediaItem = playlist[index]

    override var pauseAtEndOfMediaItems: Boolean = false

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

    @Volatile
    private var positionAnchorNano: Long = System.nanoTime()

    @Volatile
    private var vlcActivelyPlaying: Boolean = false

    @Volatile
    private var vlcAudioDelayMs: Long = 0L

    @Volatile
    private var lastReportedMonotonicMs: Long = -1L

    // 直播单连接时移中继状态（Stage 2）
    @Volatile
    internal var liveRelaySession: DesktopLiveRelay.RelaySession? = null
        private set

    @Volatile
    private var liveRelayConnId: Long = 0L

    @Volatile
    private var liveContentBaseMs: Long = 0L

    @Volatile
    private var liveFirstVlcTimeCalibrated: Boolean = false

    @Volatile
    private var firstFrameRenderedNano: Long = 0L

    @Volatile
    private var liveDesiredContentMs: Long = -1L

    @Volatile
    private var liveWindowStartContentMs: Long = 0L

    private fun computeLiveWindowStartMs(session: DesktopLiveRelay.RelaySession): Long =
        (session.oldestTimelineMs - 1_500L).coerceAtLeast(0L)

    private fun rawExtrapolatedPlayheadMs(compensateSpeakerLatency: Boolean): Long {
        val base = positionMsInternal
        if (isPlaying && vlcActivelyPlaying && !ffmpegRunning.get()) {
            val elapsedMs = ((System.nanoTime() - positionAnchorNano) / 1_000_000L).coerceIn(0L, 1_200L)
            val latencyComp = if (compensateSpeakerLatency) (VLC_SPEAKER_LATENCY_MS + vlcAudioDelayMs) else 0L
            return (base + (elapsedMs * playbackParameters.speed).toLong() - latencyComp).coerceAtLeast(0L)
        }
        return base.coerceAtLeast(0L)
    }

    private fun currentLiveScreenContentMs(session: DesktopLiveRelay.RelaySession): Long {
        val actualConnStart = session.actualStartMsForConnection(liveRelayConnId)
        val anchor = actualConnStart ?: liveContentBaseMs
        if (ffmpegRunning.get()) {
            return (anchor + positionMsInternal.coerceAtLeast(0L)).coerceIn(session.oldestTimelineMs, session.latestTimelineMs)
        }
        if (!renderedFirstFrame.get() && !isCurrentItemAudioOnly) {
            return anchor.coerceIn(session.oldestTimelineMs, session.latestTimelineMs)
        }
        if (!liveFirstVlcTimeCalibrated) {
            val elapsed = if (firstFrameRenderedNano > 0L) {
                ((System.nanoTime() - firstFrameRenderedNano) / 1_000_000L).coerceIn(0L, 1_500L)
            } else {
                0L
            }
            return (anchor + elapsed).coerceIn(session.oldestTimelineMs, session.latestTimelineMs)
        }
        val contentMs = liveContentBaseMs + rawExtrapolatedPlayheadMs(compensateSpeakerLatency = true)
        return contentMs.coerceIn(session.oldestTimelineMs, session.latestTimelineMs)
    }

    override val currentPosition: Long
        get() {
            wavClip?.let { c ->
                if (c.isOpen) positionMsInternal = c.microsecondPosition / 1000L
                return positionMsInternal
            }
            liveRelaySession?.takeIf { !it.isStopped }?.let { session ->
                val wStart = computeLiveWindowStartMs(session)
                liveWindowStartContentMs = wStart
                val contentMs = currentLiveScreenContentMs(session)
                return (contentMs - wStart).coerceAtLeast(0L)
            }
            val raw = rawExtrapolatedPlayheadMs(compensateSpeakerLatency = !isCurrentMediaItemLive)
            val bounded = if (duration != C.TIME_UNSET && duration > 0) raw.coerceAtMost(duration) else raw
            if (isPlaying && vlcActivelyPlaying && !ffmpegRunning.get()) {
                val prev = lastReportedMonotonicMs
                if (prev >= 0L && bounded < prev && (prev - bounded) < 220L) {
                    return prev
                }
                lastReportedMonotonicMs = bounded
            }
            return bounded
        }

    override val bufferedPosition: Long
        get() {
            liveRelaySession?.takeIf { !it.isStopped }?.let { session ->
                val wStart = computeLiveWindowStartMs(session)
                liveWindowStartContentMs = wStart
                return (session.latestTimelineMs - wStart).coerceAtLeast(currentPosition)
            }
            return (currentPosition + 5000L).let { if (duration != C.TIME_UNSET) it.coerceAtMost(duration) else it }
        }

    override val currentLiveOffset: Long
        get() {
            liveRelaySession?.takeIf { !it.isStopped }?.let { session ->
                return (session.latestTimelineMs - currentLiveScreenContentMs(session)).coerceAtLeast(0L)
            }
            return if (isCurrentMediaItemLive) C.TIME_UNSET else 0L
        }

    override var isCurrentMediaItemLive: Boolean = false
        private set
    override val isCurrentMediaItemSeekable: Boolean
        get() = (liveRelaySession?.let { !it.isStopped } == true) || (!isCurrentMediaItemLive && duration != C.TIME_UNSET && duration > 0)

    override var repeatMode: Int = Player.REPEAT_MODE_OFF
    override var shuffleModeEnabled: Boolean = false

    override var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
        set(value) {
            field = value
            val speed = value.speed
            vlcExecutor.execute {
                runCatching { vlcMp?.controls()?.setRate(speed) }
            }
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
                val session = if (windowIndex == currentMediaItemIndex) liveRelaySession?.takeIf { !it.isStopped } else null
                if (session != null) {
                    val wStart = computeLiveWindowStartMs(session)
                    liveWindowStartContentMs = wStart
                    window.windowStartTimeMs = DesktopLiveRelay.EPOCH_MS + wStart
                    window.positionInFirstPeriodUs = wStart * 1000L
                    window.durationUs = (session.latestTimelineMs - wStart).coerceAtLeast(1_000L) * 1000L
                    window.isSeekable = true
                    window.isDynamic = true
                } else {
                    window.windowStartTimeMs = C.TIME_UNSET
                    window.positionInFirstPeriodUs = 0L
                    window.durationUs = if (duration == C.TIME_UNSET) C.TIME_UNSET else duration * 1000L
                    window.isSeekable = isCurrentMediaItemSeekable
                    window.isDynamic = isCurrentMediaItemLive
                }
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

    // 后端 1: VLCJ（共享全局 Factory，当前 Player 只持有一个 EmbeddedMediaPlayer）
    @Volatile
    private var vlcMp: EmbeddedMediaPlayer? = null
    @Volatile
    private var vlcHasActiveMedia: Boolean = false
    @Volatile
    private var pendingVlcStart: Pair<String, Array<String>>? = null
    @Volatile
    private var lastPlayableUrl: String? = null
    @Volatile
    private var lastHeaders: Map<String, String> = emptyMap()
    @Volatile
    private var vlcPlayedCurrentItem: Boolean = false
    @Volatile
    private var activeRenderGen: Int = 0
    @Volatile
    private var surfaceWidth: Int = 0
    @Volatile
    private var surfaceHeight: Int = 0
    @Volatile
    private var isCurrentItemAudioOnly: Boolean = false

    // 后端 2: 纯 Java WAV Clip (用于听书等短音频)
    @Volatile
    private var wavClip: Clip? = null

    // 后端 3: FFmpeg 软解流 (无 VLC 时的全格式兜底)
    private val ffmpegRunning = AtomicBoolean(false)
    @Volatile
    private var ffmpegAudioClockReady: Boolean = false
    @Volatile
    private var ffmpegAudioProc: Process? = null
    @Volatile
    private var ffmpegVideoProc: Process? = null
    @Volatile
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

    private fun releaseLiveRelay() {
        val prev = liveRelaySession
        liveRelaySession = null
        liveRelayConnId = 0L
        liveFirstVlcTimeCalibrated = false
        liveDesiredContentMs = -1L
        if (prev != null) {
            DesktopLiveRelay.release(prev)
        }
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        mediaGeneration.incrementAndGet()
        releaseLiveRelay()
        stopInternalAsync()
        playlist.clear()
        playlist.addAll(mediaItems)
        currentMediaItemIndex = startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        positionMsInternal = if (startPositionMs != C.TIME_UNSET && startPositionMs > 0) startPositionMs else 0L
        positionAnchorNano = System.nanoTime()
        lastReportedMonotonicMs = -1L
        duration = C.TIME_UNSET
        currentTracks = Tracks.EMPTY
        playerError = null
        notifyMain {
            listeners.forEach { it.onMediaItemTransition(currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
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
        mediaGeneration.incrementAndGet()
        releaseLiveRelay()
        stopInternalAsync()
        playlist.clear()
        playbackState = Player.STATE_IDLE
    }

    override fun prepare() {
        val item = currentMediaItem ?: return
        val gen = mediaGeneration.incrementAndGet()
        lastReportedMonotonicMs = -1L
        playerError = null
        playbackState = Player.STATE_BUFFERING
        DesktopPlayerEngine.activePlayer = this
        startCurrentItemAsync(gen, item, positionMsInternal)
    }

    override fun play() {
        playWhenReady = true
    }

    override fun pause() {
        playWhenReady = false
    }

    override fun stop() {
        mediaGeneration.incrementAndGet()
        releaseLiveRelay()
        stopInternalAsync()
        playbackState = Player.STATE_IDLE
    }

    override fun release() {
        mediaGeneration.incrementAndGet()
        releaseLiveRelay()
        listeners.clear()
        if (DesktopPlayerEngine.activePlayer === this) {
            DesktopPlayerEngine.activePlayer = null
        }
        vlcExecutor.execute {
            stopInternalOnExecutor()
            val mp = vlcMp
            vlcMp = null
            if (mp != null) {
                disposalPool.submit { runCatching { mp.release() } }
            }
        }
        vlcExecutor.shutdown()
    }

    override fun seekTo(positionMs: Long) = seekTo(currentMediaItemIndex, positionMs)

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val itemChanged = mediaItemIndex != currentMediaItemIndex && mediaItemIndex in playlist.indices
        if (itemChanged) {
            val targetPos = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
            val oldPos = Player.PositionInfo(currentMediaItemIndex, currentPosition)
            releaseLiveRelay()
            currentMediaItemIndex = mediaItemIndex
            positionMsInternal = targetPos
            positionAnchorNano = System.nanoTime()
            lastReportedMonotonicMs = -1L
            val newPos = Player.PositionInfo(currentMediaItemIndex, targetPos)
            notifyMain {
                listeners.forEach {
                    it.onPositionDiscontinuity(oldPos, newPos, Player.DISCONTINUITY_REASON_SEEK)
                    it.onMediaItemTransition(currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                }
            }
            if (playbackState != Player.STATE_IDLE) {
                val gen = mediaGeneration.incrementAndGet()
                playbackState = Player.STATE_BUFFERING
                currentMediaItem?.let { startCurrentItemAsync(gen, it, targetPos) }
            }
            return
        }

        // 直播时移中继跳转：支持 delayLive 往回跳秒与 restoreLivePlayback(C.TIME_UNSET) 回到直播前沿，无需断开上游拉流
        val activeRelay = liveRelaySession?.takeIf { !it.isStopped }
        if (activeRelay != null) {
            val wStart = computeLiveWindowStartMs(activeRelay)
            liveWindowStartContentMs = wStart
            val desiredContent = if (positionMs == C.TIME_UNSET) {
                -1L
            } else {
                (wStart + positionMs.coerceAtLeast(0L))
                    .coerceIn(activeRelay.oldestTimelineMs, activeRelay.latestTimelineMs)
            }
            liveDesiredContentMs = desiredContent
            liveContentBaseMs = activeRelay.previewPlaybackStartMs(desiredContent)
            liveFirstVlcTimeCalibrated = false
            firstFrameRenderedNano = 0L
            positionMsInternal = 0L
            positionAnchorNano = System.nanoTime()
            lastReportedMonotonicMs = -1L
            val nextGen = mediaGeneration.incrementAndGet()
            vlcExecutor.execute {
                if (nextGen != mediaGeneration.get()) return@execute
                reconnectLiveRelayPlaybackOnExecutor(nextGen, activeRelay, desiredContent)
            }
            return
        }

        val targetPos = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
        positionMsInternal = targetPos
        positionAnchorNano = System.nanoTime()
        lastReportedMonotonicMs = -1L
        val gen = mediaGeneration.get()

        wavClip?.let { c ->
            runCatching { c.microsecondPosition = targetPos * 1000L }
            return
        }
        if (ffmpegRunning.get()) {
            val nextGen = mediaGeneration.incrementAndGet()
            currentMediaItem?.let { startCurrentItemAsync(nextGen, it, targetPos) }
            return
        }
        // 合并连续拖动进度条时的多次跳转请求，不在 UI 线程调 JNI
        pendingSeekTargetMs = targetPos
        if (seekTaskScheduled.compareAndSet(false, true)) {
            vlcExecutor.execute {
                seekTaskScheduled.set(false)
                if (gen != mediaGeneration.get()) return@execute
                val seekTarget = pendingSeekTargetMs
                if (seekTarget != C.TIME_UNSET) {
                    runCatching { vlcMp?.controls()?.setTime(seekTarget) }
                }
            }
        }
    }

    override fun seekToDefaultPosition() = seekTo(currentMediaItemIndex, C.TIME_UNSET)
    override fun seekToDefaultPosition(mediaItemIndex: Int) = seekTo(mediaItemIndex, C.TIME_UNSET)

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

    private fun applyVlcVolumeOnExecutor(mp: MediaPlayer?) {
        if (mp == null) return
        val eff = (volume * DesktopPlayerEngine.effectiveMasterVolume).coerceIn(0f, 1f)
        val volInt = (eff * 100).roundToInt()
        runCatching {
            mp.audio()?.setMute(volInt == 0)
            mp.audio()?.setVolume(volInt)
        }
    }

    internal fun updateEffectiveVolume() {
        vlcExecutor.execute {
            applyVlcVolumeOnExecutor(vlcMp)
        }
    }

    internal fun applyEqualizer(enabled: Boolean, bandLevels: ShortArray) {
        // VLCJ / FFmpeg 音量与均衡器参数已保存于 AudioEqualizer
    }

    private fun applyPlayWhenReady(ready: Boolean) {
        val gen = mediaGeneration.get()
        if (!ready) {
            vlcActivelyPlaying = false
        } else {
            positionAnchorNano = System.nanoTime()
        }
        vlcExecutor.execute {
            if (gen != mediaGeneration.get()) return@execute
            val pending = pendingVlcStart
            if (ready && pending != null) {
                pendingVlcStart = null
                startVlcMediaOnExecutor(gen, pending.first, pending.second)
                return@execute
            }
            vlcMp?.let { mp ->
                if (vlcHasActiveMedia) {
                    runCatching {
                        if (ready) {
                            if (vlcPlayedCurrentItem && !vlcActivelyPlaying) {
                                mp.controls().play()
                                vlcActivelyPlaying = true
                                positionAnchorNano = System.nanoTime()
                            }
                        } else {
                            mp.controls().setPause(true)
                            vlcActivelyPlaying = false
                        }
                    }
                }
            }
            wavClip?.let { c ->
                runCatching {
                    if (ready) c.start() else c.stop()
                }
            }
        }
    }

    private fun applyTrackSelection(params: TrackSelectionParameters) {
        val gen = mediaGeneration.get()
        vlcExecutor.execute {
            if (gen != mediaGeneration.get()) return@execute
            val mp = vlcMp ?: return@execute
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
            refreshVlcTracksOnExecutor(mp)
        }
    }

    private fun hasCustomAuthHeaders(headers: Map<String, String>): Boolean =
        headers.keys.any { !it.equals("User-Agent", ignoreCase = true) && !it.equals("Referer", ignoreCase = true) }

    private fun resolvePlayableUrlAndHeaders(item: MediaItem): Pair<String, Map<String, String>> {
        val uri = item.localConfiguration?.uri ?: Uri.parse(item.mediaId)
        val rawStr = uri.toString()
        val name = item.mediaMetadata.title?.toString() ?: uri.lastPathSegment ?: "video.mp4"

        // 本地文件直接返回绝对路径
        val localFile = ContentResolver.resolveFile(uri)
        if (localFile != null && localFile.exists()) {
            return localFile.absolutePath to emptyMap()
        }

        // Telegram 流必须走本机 MediaProxy 边下边播
        if (Telegram.isTelegram(rawStr)) {
            val dsFactory = dataSourceFactory
            if (dsFactory != null) {
                val p = proxy ?: MediaProxy(dsFactory).also { proxy = it }
                return p.register(uri, name) to emptyMap()
            }
        }

        // Cloud（Plex / Emby / 网盘）：若直链不含 Cookie/Authorization 等自定义鉴权头（如 Plex URL Token），直接交给 VLC 直连以加速起播与跳转
        if (CloudRegistry.isCloud(rawStr)) {
            val link = runCatching { CloudRegistry.resolve(rawStr) }.getOrNull()
            if (link != null && !hasCustomAuthHeaders(link.headers)) {
                return link.url to link.headers
            }
            val dsFactory = dataSourceFactory
            if (dsFactory != null) {
                val p = proxy ?: MediaProxy(dsFactory).also { proxy = it }
                return p.register(uri, name) to emptyMap()
            }
            if (link != null) return link.url to link.headers
        }

        val routed = Endpoints.rewrite(rawStr)
        val headers = AuthRegistry.headersFor(routed) + LiveHeaders.headersFor(routed)
        if (hasCustomAuthHeaders(headers) && dataSourceFactory != null) {
            val p = proxy ?: MediaProxy(dataSourceFactory).also { proxy = it }
            return p.register(uri, name) to emptyMap()
        }
        return routed to headers
    }

    private fun startCurrentItemAsync(gen: Int, item: MediaItem, startPosMs: Long) {
        prepareStartRealtimeMs = System.currentTimeMillis()
        renderedFirstFrame.set(false)
        vlcPlayedCurrentItem = false
        vlcActivelyPlaying = false
        ffmpegAudioClockReady = false

        val uri = item.localConfiguration?.uri ?: Uri.parse(item.mediaId)
        val localFile = ContentResolver.resolveFile(uri)
        val ext = (localFile?.extension ?: uri.lastPathSegment?.substringAfterLast('.', "")).orEmpty().lowercase()
        isCurrentItemAudioOnly = ext in AUDIO_EXTENSIONS
        if (isCurrentItemAudioOnly) {
            DesktopPlayerEngine.clearFrame()
        }

        vlcExecutor.execute {
            if (gen != mediaGeneration.get()) return@execute
            stopInternalOnExecutor()
            if (gen != mediaGeneration.get()) return@execute

            // 1. 本地 WAV 短语音（如听书 BookTts）：直接用 Java Sound Clip 播放，零延迟
            if (localFile != null && localFile.exists() && localFile.extension.equals("wav", ignoreCase = true)) {
                if (startWithJavaWavClipOnExecutor(gen, localFile, startPosMs)) return@execute
            }

            val tResolve = System.currentTimeMillis()
            val (playableUrl, headers) = runCatching { resolvePlayableUrlAndHeaders(item) }.getOrElse { e ->
                Log.e(TAG, "解析播放地址失败: $uri", e)
                if (gen == mediaGeneration.get()) {
                    onError(PlaybackException(e.message, e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                }
                return@execute
            }
            if (gen != mediaGeneration.get()) return@execute

            val resolveCost = System.currentTimeMillis() - tResolve
            if (resolveCost > 20) {
                Log.d(TAG, "解析播放地址耗时=${resolveCost}ms -> $playableUrl")
            }

            lastPlayableUrl = playableUrl
            lastHeaders = headers
            vlcPlayedCurrentItem = false
            vlcAudioDelayMs = 0L
            lastReportedMonotonicMs = -1L

            val shouldRelay = DesktopLiveRelay.shouldRelay(uri, item)
            isCurrentMediaItemLive = shouldRelay ||
                playableUrl.contains(".m3u8", ignoreCase = true) ||
                item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8

            if (shouldRelay) {
                val prevRelay = liveRelaySession
                val session = if (prevRelay != null && !prevRelay.isStopped && prevRelay.playableUrl == playableUrl) {
                    prevRelay
                } else {
                    releaseLiveRelay()
                    DesktopLiveRelay.acquire(playableUrl, headers)
                }
                if (session != null) {
                    if (session.awaitFirstChunk(15_000L) && gen == mediaGeneration.get()) {
                        val targetOffset = item.liveConfiguration.targetOffsetMs
                        val desired = when {
                            liveDesiredContentMs >= 0L -> liveDesiredContentMs
                            targetOffset != C.TIME_UNSET && targetOffset > 0L ->
                                (session.latestTimelineMs - targetOffset).coerceAtLeast(session.oldestTimelineMs)
                            else -> -1L
                        }
                        liveRelaySession = session
                        liveWindowStartContentMs = computeLiveWindowStartMs(session)
                        liveContentBaseMs = session.previewPlaybackStartMs(desired)
                        liveFirstVlcTimeCalibrated = false
                        firstFrameRenderedNano = 0L
                        positionMsInternal = 0L
                        positionAnchorNano = System.nanoTime()
                        val (relayUrl, connId) = session.buildPlaybackUrl(desired)
                        liveRelayConnId = connId
                        lastPlayableUrl = relayUrl
                        lastHeaders = emptyMap()
                        if (DesktopPlayerEngine.vlcAvailable && startWithVlcjOnExecutor(gen, relayUrl, emptyMap(), 0L)) {
                            return@execute
                        }
                        startWithFfmpeg(gen, relayUrl, emptyMap(), 0L)
                        return@execute
                    } else {
                        DesktopLiveRelay.release(session)
                        if (liveRelaySession === session) liveRelaySession = null
                        if (gen != mediaGeneration.get()) return@execute
                    }
                }
            } else {
                releaseLiveRelay()
            }

            // 2. 优先使用 VLCJ (共享全局 Factory)
            if (DesktopPlayerEngine.vlcAvailable && startWithVlcjOnExecutor(gen, playableUrl, headers, startPosMs)) {
                return@execute
            }

            // 3. 使用内置 FFmpeg + Java Sound 引擎
            startWithFfmpeg(gen, playableUrl, headers, startPosMs)
        }
    }

    internal fun ensureAttachedToLiveRelay(session: DesktopLiveRelay.RelaySession) {
        if (session.isStopped) return
        val cur = liveRelaySession
        if (cur === session && !cur.isStopped) return
        vlcExecutor.execute {
            if (session.isStopped || !isCurrentMediaItemLive) return@execute
            val active = liveRelaySession
            if (active === session && !active.isStopped) return@execute
            session.retain()
            releaseLiveRelay()
            liveRelaySession = session
            liveWindowStartContentMs = computeLiveWindowStartMs(session)
            liveContentBaseMs = session.previewPlaybackStartMs(-1L)
            liveFirstVlcTimeCalibrated = false
            firstFrameRenderedNano = 0L
            positionMsInternal = 0L
            positionAnchorNano = System.nanoTime()
            val nextGen = mediaGeneration.incrementAndGet()
            Log.i("Voxtitle-sync", "主播放器自动绑定至活动直播时移中继[${session.id}]")
            reconnectLiveRelayPlaybackOnExecutor(nextGen, session, -1L)
        }
    }

    private fun reconnectLiveRelayPlaybackOnExecutor(
        gen: Int,
        session: DesktopLiveRelay.RelaySession,
        desiredContentMs: Long,
    ) {
        if (session.isStopped || gen != mediaGeneration.get()) return
        prepareStartRealtimeMs = System.currentTimeMillis()
        renderedFirstFrame.set(false)
        firstFrameRenderedNano = 0L
        vlcPlayedCurrentItem = false
        vlcActivelyPlaying = false
        ffmpegAudioClockReady = false
        stopInternalOnExecutor()
        if (gen != mediaGeneration.get() || session.isStopped) return
        val (relayUrl, connId) = session.buildPlaybackUrl(desiredContentMs)
        liveRelayConnId = connId
        lastPlayableUrl = relayUrl
        lastHeaders = emptyMap()
        Log.i("Voxtitle-sync", "直播时移切换本地播放流[${session.id}]: desired=${desiredContentMs}ms, url=$relayUrl")
        if (DesktopPlayerEngine.vlcAvailable && startWithVlcjOnExecutor(gen, relayUrl, emptyMap(), 0L)) {
            return
        }
        startWithFfmpeg(gen, relayUrl, emptyMap(), 0L)
    }

    private fun startWithJavaWavClipOnExecutor(gen: Int, file: File, startPosMs: Long): Boolean {
        return runCatching {
            AudioSystem.getAudioInputStream(file).use { ais ->
                val clip = AudioSystem.getClip()
                clip.open(ais)
                if (gen != mediaGeneration.get()) {
                    clip.close()
                    return false
                }
                duration = (clip.microsecondLength / 1000L).coerceAtLeast(1L)
                if (startPosMs > 0) {
                    clip.microsecondPosition = (startPosMs * 1000L).coerceAtMost(clip.microsecondLength)
                }
                clip.addLineListener { ev ->
                    if (ev.type == LineEvent.Type.STOP && clip.isOpen && clip.microsecondPosition >= clip.microsecondLength - 20_000L) {
                        if (gen == mediaGeneration.get()) onItemEnded()
                    }
                }
                wavClip = clip
                playbackState = Player.STATE_READY
                if (playWhenReady) clip.start()
                true
            }
        }.getOrDefault(false)
    }

    private fun startWithVlcjOnExecutor(gen: Int, url: String, headers: Map<String, String>, startPosMs: Long): Boolean {
        return runCatching {
            val factory = DesktopPlayerEngine.getSharedVlcFactory() ?: return false
            if (gen != mediaGeneration.get()) return false

            val mp = vlcMp ?: factory.mediaPlayers().newEmbeddedMediaPlayer().also { created ->
                setupVlcSurface(created)
                setupVlcEvents(created)
                vlcMp = created
            }

            val options = mutableListOf(":no-dvdnav-menu", ":no-bluray-menu")
            if (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) {
                options.add(":http-proxy=")
                if (url.contains("/live/")) {
                    options.add(":network-caching=300")
                }
            }
            if (startPosMs > 0) {
                options.add(":start-time=${startPosMs / 1000.0}")
            }
            headers["User-Agent"]?.let { options.add(":http-user-agent=$it") }
            headers["Referer"]?.let { options.add(":http-referrer=$it") }

            applyVlcVolumeOnExecutor(mp)
            runCatching { mp.controls().setRate(playbackParameters.speed) }

            renderedFirstFrame.set(false)
            activeRenderGen = gen

            val optArr = options.toTypedArray()
            if (playWhenReady) {
                pendingVlcStart = null
                startVlcMediaOnExecutor(gen, url, optArr)
            } else {
                pendingVlcStart = url to optArr
                // 未调用 play() 前保持 BUFFERING，等待 applyPlayWhenReady(true) 真正启动媒体
                playbackState = Player.STATE_BUFFERING
                true
            }
        }.onFailure { e ->
            Log.e(TAG, "VLCJ 启动异常: $url", e)
        }.getOrDefault(false)
    }

    private fun startVlcMediaOnExecutor(gen: Int, url: String, optArr: Array<String>): Boolean {
        val mp = vlcMp ?: return false
        val t0 = System.currentTimeMillis()
        vlcHasActiveMedia = true
        val ok = runCatching { mp.media().play(url, *optArr) }.getOrDefault(false)
        Log.i(TAG, "VLC media().play 调用返回: ok=$ok, 耗时=${System.currentTimeMillis() - t0}ms, url=$url")
        if (!ok) {
            vlcHasActiveMedia = false
            val fallbackUrl = lastPlayableUrl
            if (fallbackUrl != null && gen == mediaGeneration.get()) {
                startWithFfmpeg(gen, fallbackUrl, lastHeaders, positionMsInternal)
            }
            return false
        }
        // 25 秒打开超时守护：若 VLC 既未进入播放也未渲染首帧且未报错，自动切换 FFmpeg 兜底
        watchdogPool.schedule({
            if (gen == mediaGeneration.get() && playWhenReady && !vlcPlayedCurrentItem && !renderedFirstFrame.get() && !ffmpegRunning.get()) {
                Log.w(TAG, "VLC 打开媒体超过 25s 无响应，自动切换 FFmpeg 兜底: $url")
                vlcExecutor.execute {
                    if (gen == mediaGeneration.get() && !vlcPlayedCurrentItem && !renderedFirstFrame.get() && !ffmpegRunning.get()) {
                        stopVlcWithTimeoutOnExecutor()
                        startWithFfmpeg(gen, url, lastHeaders, positionMsInternal)
                    }
                }
            }
        }, 25, TimeUnit.SECONDS)
        return true
    }

    private fun setupVlcSurface(mp: EmbeddedMediaPlayer) {
        val bufferFormatCallback = object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                if (vlcMp !== mp) return RV32BufferFormat(16, 16)
                val rawW = sourceWidth.coerceAtLeast(2)
                val rawH = sourceHeight.coerceAtLeast(2)
                // 修正 H.264 / HEVC 宏块对齐带来的 1088/1090 -> 1080、2176 -> 2160 等填充高度
                val srcW = rawW
                val srcH = when {
                    rawW == 1920 && rawH in 1080..1096 -> 1080
                    rawW == 3840 && rawH in 2160..2184 -> 2160
                    rawW == 1280 && rawH in 720..736 -> 720
                    rawW == 2560 && rawH in 1440..1456 -> 1440
                    else -> rawH
                }
                // 按显示窗口尺寸与 1920x1080 封顶计算回调缓冲尺寸，宽度 16 像素对齐以加速 SIMD 与纹理上传
                val maxW = DesktopPlayerEngine.displayWidthPx.coerceIn(1280, 1920)
                val maxH = DesktopPlayerEngine.displayHeightPx.coerceIn(720, 1080)
                val scale = minOf(1.0, maxW.toDouble() / srcW.toDouble(), maxH.toDouble() / srcH.toDouble())
                val dstW = ((((srcW * scale).roundToInt() + 8) / 16) * 16).coerceAtLeast(16)
                val dstH = (((srcH * scale).roundToInt() / 2) * 2).coerceAtLeast(2)

                surfaceWidth = dstW
                surfaceHeight = dstH
                val sz = VideoSize(srcW, srcH)
                videoSize = sz
                Log.i(TAG, "VLC 视频尺寸: 原始=${rawW}x${rawH} (可视=${srcW}x${srcH}), 渲染目标=${dstW}x${dstH}")
                val gen = activeRenderGen
                notifyMain {
                    if (vlcMp === mp && gen == mediaGeneration.get()) {
                        listeners.forEach { it.onVideoSizeChanged(sz) }
                    }
                }
                return RV32BufferFormat(dstW, dstH)
            }

            override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}
        }

        val renderCallback = RenderCallback { _, nativeBuffers, _ ->
            val gen = activeRenderGen
            if (vlcMp !== mp || gen != mediaGeneration.get()) return@RenderCallback
            val w = surfaceWidth
            val h = surfaceHeight
            if (w <= 0 || h <= 0) return@RenderCallback
            val buf = nativeBuffers.firstOrNull() ?: return@RenderCallback
            // 只要当前播放器是活动播放器就输出画面（修复静音或朗读压低音量 volume == 0f 时画面卡死的问题）
            if (DesktopPlayerEngine.activePlayer === this@DesktopExoPlayer) {
                DesktopPlayerEngine.submitFrameBgraBuffer(w, h, buf)
            }
            if (renderedFirstFrame.compareAndSet(false, true)) {
                firstFrameRenderedNano = System.nanoTime()
                positionAnchorNano = firstFrameRenderedNano
                val firstFrameCost = System.currentTimeMillis() - prepareStartRealtimeMs
                Log.i(TAG, "VLC 首帧已渲染 (${w}x${h}), 总耗时=${firstFrameCost}ms")
                vlcPlayedCurrentItem = true
                if (playbackState == Player.STATE_BUFFERING) {
                    playbackState = Player.STATE_READY
                }
                notifyMain {
                    if (vlcMp === mp && gen == mediaGeneration.get()) {
                        listeners.forEach { it.onRenderedFirstFrame() }
                    }
                }
            }
        }

        val adapter = if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
            WindowsVideoSurfaceAdapter()
        } else {
            LinuxVideoSurfaceAdapter()
        }
        val surface = CallbackVideoSurface(bufferFormatCallback, renderCallback, true, adapter)
        mp.videoSurface().set(surface)
        runCatching { mp.videoSurface().attachVideoSurface() }
    }

    private fun setupVlcEvents(mp: EmbeddedMediaPlayer) {
        mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun opening(mediaPlayer: MediaPlayer) {
                if (vlcMp !== mp) return
                if (playbackState != Player.STATE_IDLE && !renderedFirstFrame.get()) {
                    playbackState = Player.STATE_BUFFERING
                }
            }

            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                if (vlcMp !== mp) return
                if (newCache >= 100f && vlcPlayedCurrentItem && (isCurrentItemAudioOnly || renderedFirstFrame.get())) {
                    if (playbackState == Player.STATE_BUFFERING) {
                        playbackState = Player.STATE_READY
                    }
                }
            }

            override fun playing(mediaPlayer: MediaPlayer) {
                if (vlcMp !== mp) return
                vlcPlayedCurrentItem = true
                vlcActivelyPlaying = true
                positionAnchorNano = System.nanoTime()
                if (isCurrentItemAudioOnly || renderedFirstFrame.get()) {
                    playbackState = Player.STATE_READY
                }
                val cost = System.currentTimeMillis() - prepareStartRealtimeMs
                Log.i(TAG, "VLC playing 事件到达, 耗时=${cost}ms")
                val gen = mediaGeneration.get()
                vlcExecutor.execute {
                    if (gen == mediaGeneration.get() && vlcMp === mp) {
                        applyVlcVolumeOnExecutor(mp)
                        vlcAudioDelayMs = runCatching { (mp.audio().delay() / 1_000L).coerceIn(-2_000L, 2_000L) }.getOrDefault(0L)
                        refreshVlcTracksOnExecutor(mp)
                    }
                }
            }

            override fun paused(mediaPlayer: MediaPlayer) {
                if (vlcMp !== mp) return
                vlcActivelyPlaying = false
                if (playbackState == Player.STATE_BUFFERING && (isCurrentItemAudioOnly || renderedFirstFrame.get())) {
                    playbackState = Player.STATE_READY
                }
            }

            override fun stopped(mediaPlayer: MediaPlayer) {
                if (vlcMp !== mp) return
                vlcActivelyPlaying = false
            }

            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                if (vlcMp !== mp) return
                if (newTime >= 0) {
                    val prevTime = positionMsInternal
                    positionMsInternal = newTime
                    positionAnchorNano = System.nanoTime()
                    vlcPlayedCurrentItem = true
                    vlcActivelyPlaying = playWhenReady
                    liveRelaySession?.takeIf { !it.isStopped }?.let { session ->
                        val actualStart = session.actualStartMsForConnection(liveRelayConnId)
                        if (actualStart != null && (renderedFirstFrame.get() || isCurrentItemAudioOnly)) {
                            if (!liveFirstVlcTimeCalibrated && newTime > 0L) {
                                val elapsedFirstFrameMs = if (firstFrameRenderedNano > 0L) {
                                    ((System.nanoTime() - firstFrameRenderedNano) / 1_000_000L).coerceIn(0L, 2_000L)
                                } else {
                                    0L
                                }
                                liveContentBaseMs = actualStart + elapsedFirstFrameMs - newTime
                                liveFirstVlcTimeCalibrated = true
                                Log.i("Voxtitle-sync", "直播 VLC 时钟已校准[${session.id}]: actualStart=${actualStart}ms, firstVlcTime=${newTime}ms, elapsedFirstFrame=${elapsedFirstFrameMs}ms, base=${liveContentBaseMs}ms")
                            } else if (liveFirstVlcTimeCalibrated && kotlin.math.abs(newTime - prevTime) > 5_000L) {
                                val elapsedFirstFrameMs = if (firstFrameRenderedNano > 0L) {
                                    ((System.nanoTime() - firstFrameRenderedNano) / 1_000_000L).coerceAtLeast(0L)
                                } else {
                                    0L
                                }
                                liveContentBaseMs = actualStart + elapsedFirstFrameMs - newTime
                            }
                        }
                    }
                    if (playbackState == Player.STATE_BUFFERING && (isCurrentItemAudioOnly || renderedFirstFrame.get() || runCatching { mp.video().trackCount() <= 0 }.getOrDefault(false))) {
                        playbackState = Player.STATE_READY
                    }
                }
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                if (vlcMp !== mp) return
                if (!isCurrentMediaItemLive && newLength > 0) duration = newLength
            }

            override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: TrackType?, id: Int) {
                if (vlcMp !== mp) return
                val gen = mediaGeneration.get()
                vlcExecutor.execute {
                    if (gen == mediaGeneration.get() && vlcMp === mp) {
                        refreshVlcTracksOnExecutor(mp)
                    }
                }
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                if (vlcMp !== mp) return
                vlcActivelyPlaying = false
                vlcHasActiveMedia = false
                onItemEnded()
            }

            override fun error(mediaPlayer: MediaPlayer) {
                if (vlcMp !== mp) return
                vlcActivelyPlaying = false
                vlcHasActiveMedia = false
                val gen = mediaGeneration.get()
                val fallbackUrl = lastPlayableUrl
                Log.w(TAG, "VLC 播放报错: played=$vlcPlayedCurrentItem, url=$fallbackUrl")
                if (!vlcPlayedCurrentItem && fallbackUrl != null && !ffmpegRunning.get()) {
                    vlcExecutor.execute {
                        if (gen == mediaGeneration.get() && !ffmpegRunning.get()) {
                            startWithFfmpeg(gen, fallbackUrl, lastHeaders, positionMsInternal)
                        }
                    }
                    return
                }
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

    private fun refreshVlcTracksOnExecutor(mp: MediaPlayer) {
        runCatching {
            val groups = ArrayList<Tracks.Group>()
            val vsz = videoSize
            if (vsz.width > 0 && vsz.height > 0) {
                val vf = Format.Builder()
                    .setId("v0")
                    .setSampleMimeType("video/avc")
                    .setWidth(vsz.width)
                    .setHeight(vsz.height)
                    .setFrameRate(25f)
                    .build()
                videoFormat = vf
                groups.add(Tracks.Group(TrackGroup("v0", vf), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)))
            }
            val currentAudio = mp.audio().track()
            for (d in mp.audio().trackDescriptions().orEmpty()) {
                if (d.id() < 0) continue
                val f = Format.Builder().setId("a${d.id()}").setLabel(d.description()).setSampleMimeType(MimeTypes.AUDIO_UNKNOWN).build()
                groups.add(Tracks.Group(TrackGroup("a${d.id()}", f), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(d.id() == currentAudio)))
            }
            val currentSpu = mp.subpictures().track()
            for (d in mp.subpictures().trackDescriptions().orEmpty()) {
                if (d.id() < 0) continue
                val desc = d.description().orEmpty()
                // 直播流或 VLC 虚拟的 Closed captions / Teletext / DVB 图形字幕无法提取文本，标记为 APPLICATION_PGS 以便「一键字幕」按钮直接走 AI 实时语音识别
                val isNonExtractableSpu = isCurrentMediaItemLive ||
                    desc.contains("closed caption", ignoreCase = true) ||
                    desc.contains("teletext", ignoreCase = true) ||
                    desc.contains("dvb", ignoreCase = true) ||
                    desc.contains("pgs", ignoreCase = true) ||
                    desc.contains("vobsub", ignoreCase = true)
                val mime = if (isNonExtractableSpu) MimeTypes.APPLICATION_PGS else MimeTypes.TEXT_UNKNOWN
                val f = Format.Builder().setId("s${d.id()}").setLabel(desc).setSampleMimeType(mime).build()
                groups.add(Tracks.Group(TrackGroup("s${d.id()}", f), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(d.id() == currentSpu)))
            }
            currentTracks = Tracks(groups)
            notifyMain { listeners.forEach { it.onTracksChanged(currentTracks) } }
        }
    }

    private fun startWithFfmpeg(gen: Int, url: String, headers: Map<String, String>, startPosMs: Long) {
        if (gen != mediaGeneration.get()) return
        ffmpegRunning.set(true)
        Log.i(TAG, "启动 FFmpeg 播放引擎: url=$url, startPosMs=$startPosMs")
        ioPool.submit {
            if (gen != mediaGeneration.get() || !ffmpegRunning.get()) return@submit
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
            var srcWidth = 1280
            var srcHeight = 720
            var width = 1280
            var height = 720
            var hasVideo = true
            var hasAudio = true
            var fps = 25.0
            val probedGroups = ArrayList<Tracks.Group>()
            DesktopBinaryFinder.findFfprobe()?.let { ffprobe ->
                runCatching {
                    val cmd = mutableListOf(
                        ffprobe, "-v", "error",
                        "-probesize", "2M",
                        "-analyzeduration", "2000000",
                        "-show_entries", "format=duration:stream=index,codec_type,codec_name,width,height,r_frame_rate,tags",
                        "-of", "json",
                    )
                    if (headers.isNotEmpty() && url.startsWith("http")) {
                        val hdr = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
                        cmd.addAll(listOf("-headers", hdr))
                    }
                    cmd.add(url)
                    val proc = ProcessBuilder(cmd)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                    val jsonText = proc.inputStream.bufferedReader().readText()
                    proc.waitFor(10, TimeUnit.SECONDS)
                    val root = JSONObject(jsonText)
                    val durSec = root.optJSONObject("format")?.optString("duration")?.toDoubleOrNull()
                    if (durSec != null && durSec > 0) {
                        duration = (durSec * 1000.0).toLong()
                    }
                    val streams = root.optJSONArray("streams")
                    var foundVideo = false
                    var foundAudio = false
                    if (streams != null) {
                        for (i in 0 until streams.length()) {
                            val s = streams.getJSONObject(i)
                            val stype = s.optString("codec_type")
                            val idx = s.optInt("index", i)
                            val tags = s.optJSONObject("tags")
                            val lang = tags?.optString("language")?.takeIf { it.isNotBlank() }
                            val title = tags?.optString("title")?.takeIf { it.isNotBlank() }
                            if (stype == "video" && !foundVideo) {
                                val w = s.optInt("width", 0)
                                val h = s.optInt("height", 0)
                                if (w > 0 && h > 0) {
                                    srcWidth = w
                                    srcHeight = h
                                    // FFmpeg 管道软解限制最高 1280x720，大幅降低匿名管道吞吐并保证无 VLC 环境稳定满帧
                                    val maxW = DesktopPlayerEngine.displayWidthPx.coerceIn(854, 1280)
                                    val maxH = DesktopPlayerEngine.displayHeightPx.coerceIn(480, 720)
                                    val scale = minOf(1.0, maxW.toDouble() / w.toDouble(), maxH.toDouble() / h.toDouble())
                                    width = (((w * scale).roundToInt() / 2) * 2).coerceAtLeast(2)
                                    height = (((h * scale).roundToInt() / 2) * 2).coerceAtLeast(2)
                                    foundVideo = true
                                    val rateStr = s.optString("r_frame_rate", "25/1")
                                    val parts = rateStr.split('/')
                                    if (parts.size == 2) {
                                        val num = parts[0].toDoubleOrNull() ?: 25.0
                                        val den = parts[1].toDoubleOrNull() ?: 1.0
                                        if (den > 0 && num / den in 1.0..120.0) fps = num / den
                                    }
                                    val vf = Format.Builder()
                                        .setId("v$idx")
                                        .setSampleMimeType("video/${s.optString("codec_name", "avc")}")
                                        .setWidth(w)
                                        .setHeight(h)
                                        .setFrameRate(fps.toFloat())
                                        .build()
                                    videoFormat = vf
                                    probedGroups.add(Tracks.Group(TrackGroup("v$idx", vf), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)))
                                }
                            } else if (stype == "audio") {
                                val af = Format.Builder()
                                    .setId("a$idx")
                                    .setLanguage(lang)
                                    .setLabel(title ?: lang ?: "音轨 $idx")
                                    .setSampleMimeType(MimeTypes.AUDIO_UNKNOWN)
                                    .build()
                                if (!foundAudio) audioFormat = af
                                probedGroups.add(Tracks.Group(TrackGroup("a$idx", af), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(!foundAudio)))
                                foundAudio = true
                            } else if (stype == "subtitle") {
                                val sf = Format.Builder()
                                    .setId("s$idx")
                                    .setLanguage(lang)
                                    .setLabel(title ?: lang ?: "字幕 $idx")
                                    .setSampleMimeType(MimeTypes.TEXT_UNKNOWN)
                                    .build()
                                probedGroups.add(Tracks.Group(TrackGroup("s$idx", sf), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)))
                            }
                        }
                    }
                    hasVideo = foundVideo
                    hasAudio = foundAudio
                }
            }

            if (gen != mediaGeneration.get() || !ffmpegRunning.get()) return@submit

            if (probedGroups.isNotEmpty()) {
                currentTracks = Tracks(probedGroups)
                notifyMain { listeners.forEach { it.onTracksChanged(currentTracks) } }
            }

            if (hasVideo) {
                val sz = VideoSize(srcWidth, srcHeight)
                videoSize = sz
                notifyMain {
                    listeners.forEach { it.onVideoSizeChanged(sz) }
                }
                startFfmpegVideoThread(gen, ffmpeg, url, headers, startPosMs, width, height, fps, syncWithAudio = hasAudio)
            }

            if (hasAudio || !hasVideo) {
                startFfmpegAudioThread(gen, ffmpeg, url, headers, startPosMs, markReadyWithoutVideo = !hasVideo, endWhenDone = true)
            }
        }
    }

    private fun startFfmpegVideoThread(
        gen: Int,
        ffmpeg: String,
        url: String,
        headers: Map<String, String>,
        startPosMs: Long,
        width: Int,
        height: Int,
        fps: Double,
        syncWithAudio: Boolean = true,
    ) {
        ioPool.submit {
            runCatching {
                val cmd = mutableListOf(ffmpeg, "-v", "error")
                if (url.startsWith("http")) {
                    cmd.addAll(listOf("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "2"))
                }
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
                        "-threads", "0",
                        "-an", "-sn",
                        "-vf", "scale=$width:$height:flags=fast_bilinear",
                        "-pix_fmt", "bgra",
                        "-f", "rawvideo", "-",
                    ),
                )
                val proc = ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                ffmpegVideoProc = proc
                val ins = proc.inputStream
                var framesRead = 0L
                var consecutiveSkipped = 0
                val safeFps = fps.coerceIn(1.0, 120.0)
                var wallClockAnchorMs = System.currentTimeMillis()
                var firstFrameDecoded = false
                while (ffmpegRunning.get() && gen == mediaGeneration.get()) {
                    if (!playWhenReady) {
                        Thread.sleep(30)
                        wallClockAnchorMs += 30L
                        continue
                    }
                    val useAudioClock = syncWithAudio && ffmpegAudioClockReady
                    val framePtsMs = startPosMs + (framesRead * 1000.0 / safeFps).toLong()
                    val refClockMs = if (useAudioClock) {
                        positionMsInternal
                    } else if (firstFrameDecoded) {
                        startPosMs + (System.currentTimeMillis() - wallClockAnchorMs)
                    } else {
                        startPosMs
                    }
                    // 即使解码略慢于音频时钟，也至多隔帧跳过上传（绝不连续丢弃所有帧导致画面卡死）
                    val isLate = useAudioClock && framesRead > 0 && (refClockMs - framePtsMs) > 120L && consecutiveSkipped < 1
                    val shouldRender = !isLate && DesktopPlayerEngine.activePlayer === this@DesktopExoPlayer
                    val ok = DesktopPlayerEngine.readFrameBgraStream(
                        width = width,
                        height = height,
                        ins = ins,
                        shouldContinue = { ffmpegRunning.get() && gen == mediaGeneration.get() },
                        renderFrame = shouldRender,
                    )
                    if (!ok) break
                    consecutiveSkipped = if (isLate) consecutiveSkipped + 1 else 0
                    if (!firstFrameDecoded) {
                        firstFrameDecoded = true
                        wallClockAnchorMs = System.currentTimeMillis()
                    }
                    framesRead++
                    if (renderedFirstFrame.compareAndSet(false, true)) {
                        playbackState = Player.STATE_READY
                        val cost = if (prepareStartRealtimeMs > 0L) System.currentTimeMillis() - prepareStartRealtimeMs else 0L
                        Log.i(TAG, "FFmpeg 首帧已渲染 (${width}x${height}), 耗时=${cost}ms")
                        notifyMain { listeners.forEach { it.onRenderedFirstFrame() } }
                    }
                    if (!useAudioClock) {
                        positionMsInternal = framePtsMs
                        positionAnchorNano = System.nanoTime()
                    }
                    // 若视频帧超前于参考时钟，按差值休眠对齐
                    val updatedUseAudio = syncWithAudio && ffmpegAudioClockReady
                    val updatedRef = if (updatedUseAudio) positionMsInternal else startPosMs + (System.currentTimeMillis() - wallClockAnchorMs)
                    val aheadMs = framePtsMs - updatedRef
                    if (aheadMs > 5L) {
                        Thread.sleep(aheadMs.coerceAtMost(80L))
                    }
                }
                proc.destroy()
                if (!syncWithAudio && ffmpegRunning.get() && gen == mediaGeneration.get() && framesRead > 0) {
                    onItemEnded()
                }
            }.onFailure { e ->
                Log.e(TAG, "FFmpeg 视频解码线程异常: $url", e)
            }
        }
    }

    private fun startFfmpegAudioThread(
        gen: Int,
        ffmpeg: String,
        url: String,
        headers: Map<String, String>,
        startPosMs: Long,
        markReadyWithoutVideo: Boolean = false,
        endWhenDone: Boolean = true,
    ) {
        ioPool.submit {
            try {
                val cmd = mutableListOf(ffmpeg, "-v", "error")
                if (url.startsWith("http")) {
                    cmd.addAll(listOf("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "2"))
                }
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
                val proc = ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                ffmpegAudioProc = proc
                val format = AudioFormat(48000f, 16, 2, true, false)
                val line = runCatching { AudioSystem.getSourceDataLine(format) }.getOrNull()
                line?.open(format, 48000 * 4 / 5)
                line?.start()
                ffmpegAudioLine = line

                val buf = ByteArray(4800 * 4) // 100ms
                val ins = proc.inputStream
                var bytesPlayed = 0L
                while (ffmpegRunning.get() && gen == mediaGeneration.get()) {
                    if (!playWhenReady) {
                        Thread.sleep(30)
                        continue
                    }
                    val n = ins.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    val effVol = (volume * DesktopPlayerEngine.effectiveMasterVolume).coerceIn(0f, 1f)
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
                    // 扣除 SourceDataLine 环形缓冲中尚未真正播出的采样，确保音画同步参考时钟与扬声器实际播出位置一致
                    val playedFrames = line?.longFramePosition ?: (bytesPlayed / 4L)
                    val sampleRateLong = format.sampleRate.toLong().coerceAtLeast(8_000L)
                    val actualPlayedMs = (playedFrames.coerceAtLeast(0L) * 1000L) / sampleRateLong
                    positionMsInternal = startPosMs + actualPlayedMs
                    positionAnchorNano = System.nanoTime()
                    ffmpegAudioClockReady = true
                    if (markReadyWithoutVideo && playbackState == Player.STATE_BUFFERING) {
                        playbackState = Player.STATE_READY
                    }
                }
                line?.drain()
                line?.close()
                proc.destroy()
                if (endWhenDone && ffmpegRunning.get() && gen == mediaGeneration.get() && bytesPlayed > 0L) {
                    onItemEnded()
                }
            } catch (e: Exception) {
                if (ffmpegRunning.get() && gen == mediaGeneration.get()) {
                    onError(PlaybackException(e.message, e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                }
            }
        }
    }

    private fun onItemEnded() {
        notifyMain {
            if (pauseAtEndOfMediaItems) {
                playWhenReady = false
                playbackState = Player.STATE_ENDED
                return@notifyMain
            }
            when {
                repeatMode == Player.REPEAT_MODE_ONE -> {
                    seekTo(currentMediaItemIndex, 0L)
                }
                hasNextMediaItem() -> {
                    val oldPos = Player.PositionInfo(currentMediaItemIndex, currentPosition)
                    currentMediaItemIndex += 1
                    positionMsInternal = 0L
                    positionAnchorNano = System.nanoTime()
                    val newPos = Player.PositionInfo(currentMediaItemIndex, 0L)
                    listeners.forEach {
                        it.onPositionDiscontinuity(oldPos, newPos, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
                        it.onMediaItemTransition(currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                    }
                    val gen = mediaGeneration.incrementAndGet()
                    playbackState = Player.STATE_BUFFERING
                    currentMediaItem?.let { startCurrentItemAsync(gen, it, 0L) }
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

    private fun stopInternalAsync() {
        pendingVlcStart = null
        vlcActivelyPlaying = false
        ffmpegRunning.set(false)
        vlcExecutor.execute {
            stopInternalOnExecutor()
        }
    }

    private fun stopInternalOnExecutor() {
        pendingVlcStart = null
        vlcActivelyPlaying = false
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

        stopVlcWithTimeoutOnExecutor()
    }

    private fun stopVlcWithTimeoutOnExecutor() {
        val mp = vlcMp ?: return
        if (!vlcHasActiveMedia) return
        vlcHasActiveMedia = false
        vlcActivelyPlaying = false
        val t0 = System.currentTimeMillis()
        val future = disposalPool.submit {
            runCatching { mp.controls().stop() }
        }
        try {
            future.get(400, TimeUnit.MILLISECONDS)
            val cost = System.currentTimeMillis() - t0
            if (cost > 40) {
                Log.d(TAG, "VLC stop 耗时=${cost}ms")
            }
        } catch (_: TimeoutException) {
            Log.w(TAG, "VLC stop 超过 400ms（网络流可能阻塞），分离旧 MediaPlayer 后台释放")
            if (vlcMp === mp) {
                vlcMp = null
            }
            disposalPool.submit {
                runCatching { future.get(30, TimeUnit.SECONDS) }
                runCatching { mp.release() }
            }
        } catch (_: Throwable) {
        }
    }

    private fun notifyMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    companion object {
        /** Windows WASAPI 共享模式扬声器输出缓冲典型延迟补偿（毫秒），使 VLC 播放时钟贴合人耳听到的采样时刻 */
        private const val VLC_SPEAKER_LATENCY_MS = 85L

        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "ape", "alac", "aiff")

        internal val ioPool = Executors.newCachedThreadPool { r ->
            Thread(r, "Voxtitle-DesktopPlayer").apply { isDaemon = true }
        }

        private val disposalPool = Executors.newCachedThreadPool { r ->
            Thread(r, "Voxtitle-VlcDisposer").apply { isDaemon = true }
        }

        private val watchdogPool = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "Voxtitle-PlayerWatchdog").apply { isDaemon = true }
        }
    }
}
