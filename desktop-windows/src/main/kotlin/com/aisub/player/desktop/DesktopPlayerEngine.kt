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
import com.aisub.player.media.Chapter
import com.aisub.player.media.MediaProxy
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.cloud.CloudRegistry
import com.aisub.player.subtitle.Cue
import com.aisub.player.subtitle.EmbeddedSubtitles
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
import uk.co.caprica.vlcj.media.MediaStatistics
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
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.net.ServerSocket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import javax.sound.sampled.SourceDataLine
import com.sun.jna.Library
import com.sun.jna.Native
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val TAG = "DesktopPlayer"

private interface DesktopWinMm : Library {
    fun timeBeginPeriod(uPeriod: Int): Int
    fun timeEndPeriod(uPeriod: Int): Int

    companion object {
        val INSTANCE: DesktopWinMm? = runCatching {
            if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) {
                Native.load("winmm", DesktopWinMm::class.java)
            } else {
                null
            }
        }.getOrNull()
    }
}

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

    /** 桌面端 MPV/FFmpeg 自带文本字幕当前显示文本，由 PlayerView 叠加绘制 */
    val currentEmbeddedSubtitleText = androidx.compose.runtime.mutableStateOf("")

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

    // 实时下载网速统计：100ms 分桶环形窗口（最近 1.5s 平均）
    private const val SPEED_BUCKET_MS = 100L
    private const val SPEED_WINDOW_BUCKETS = 15
    private const val SPEED_WINDOW_MS = 1_500L
    private const val SPEED_STALE_MS = 2_000L
    private val speedLock = Any()
    private val speedBuckets = LongArray(SPEED_WINDOW_BUCKETS)
    private var speedHeadBucketIdx: Long = -1L
    private var speedFirstSampleMs: Long = 0L
    private var speedLastSampleMs: Long = 0L

    fun recordNetworkBytes(bytes: Long, nowMs: Long = System.currentTimeMillis()) {
        if (bytes <= 0L) return
        val bucket = nowMs / SPEED_BUCKET_MS
        synchronized(speedLock) {
            if (speedHeadBucketIdx < 0L || nowMs - speedLastSampleMs > SPEED_STALE_MS) {
                speedBuckets.fill(0L)
                speedHeadBucketIdx = bucket
                speedFirstSampleMs = nowMs
            } else if (bucket > speedHeadBucketIdx) {
                val deltaBuckets = bucket - speedHeadBucketIdx
                if (deltaBuckets >= SPEED_WINDOW_BUCKETS.toLong()) {
                    speedBuckets.fill(0L)
                    speedFirstSampleMs = nowMs
                } else {
                    for (i in 1..deltaBuckets.toInt()) {
                        val bIdx = ((speedHeadBucketIdx + i) % SPEED_WINDOW_BUCKETS).toInt()
                        speedBuckets[bIdx] = 0L
                    }
                    val oldestValid = nowMs - SPEED_WINDOW_MS
                    if (speedFirstSampleMs < oldestValid) {
                        speedFirstSampleMs = oldestValid
                    }
                }
                speedHeadBucketIdx = bucket
            }
            val slot = (bucket % SPEED_WINDOW_BUCKETS).toInt().let { if (it < 0) it + SPEED_WINDOW_BUCKETS else it }
            speedBuckets[slot] += bytes
            speedLastSampleMs = nowMs
        }
    }

    fun resetNetworkSpeed() {
        synchronized(speedLock) {
            speedBuckets.fill(0L)
            speedHeadBucketIdx = -1L
            speedFirstSampleMs = 0L
            speedLastSampleMs = 0L
        }
    }

    fun currentDownloadSpeedBytesPerSec(nowMs: Long = System.currentTimeMillis()): Long {
        synchronized(speedLock) {
            if (speedHeadBucketIdx < 0L || nowMs - speedLastSampleMs > SPEED_STALE_MS) {
                return 0L
            }
            val currentBucket = nowMs / SPEED_BUCKET_MS
            val scanStart = maxOf(
                currentBucket - SPEED_WINDOW_BUCKETS + 1L,
                speedHeadBucketIdx - SPEED_WINDOW_BUCKETS + 1L,
            )
            val scanEnd = minOf(currentBucket, speedHeadBucketIdx)
            var totalBytes = 0L
            for (b in scanStart..scanEnd) {
                val slot = (b % SPEED_WINDOW_BUCKETS).toInt().let { if (it < 0) it + SPEED_WINDOW_BUCKETS else it }
                totalBytes += speedBuckets[slot]
            }
            if (totalBytes <= 0L) return 0L
            val elapsedMs = (nowMs - speedFirstSampleMs).coerceIn(250L, SPEED_WINDOW_MS)
            return totalBytes * 1_000L / elapsedMs
        }
    }

    fun formatNetworkSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 KB/s"
        val kbPerSec = bytesPerSec / 1024.0
        if (kbPerSec < 100.0) {
            return "%.1f KB/s".format(java.util.Locale.US, kbPerSec)
        }
        if (bytesPerSec < 1024L * 1024L) {
            return "${bytesPerSec / 1024L} KB/s"
        }
        val mbPerSec = bytesPerSec / (1024.0 * 1024.0)
        if (mbPerSec < 100.0) {
            return "%.1f MB/s".format(java.util.Locale.US, mbPerSec)
        }
        return "%.0f MB/s".format(java.util.Locale.US, mbPerSec)
    }

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
                    "--avcodec-hw=none",
                    "--avcodec-threads=0",
                    "--network-caching=2500",
                    "--file-caching=300",
                    "--prefetch-buffer-size=65536",
                    "--prefetch-read-size=1048576",
                    "--no-lua",
                    "--audio-time-stretch",
                    "--no-video-title-show",
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

    private val highResTimerUsers = AtomicInteger(0)

    internal fun acquireWindowsHighResTimer() {
        if (highResTimerUsers.getAndIncrement() == 0) {
            runCatching { DesktopWinMm.INSTANCE?.timeBeginPeriod(1) }
        }
    }

    internal fun releaseWindowsHighResTimer() {
        val rem = highResTimerUsers.decrementAndGet()
        if (rem <= 0) {
            if (rem < 0) highResTimerUsers.set(0)
            runCatching { DesktopWinMm.INSTANCE?.timeEndPeriod(1) }
        }
    }

    @Volatile
    internal var vulkanPlaceboSupported: Boolean? = null

    private val vulkanProbeStarted = AtomicBoolean(false)

    internal fun probeVulkanPlaceboAsync() {
        if (vulkanPlaceboSupported != null || !vulkanProbeStarted.compareAndSet(false, true)) return
        DesktopExoPlayer.ioPool.submit {
            val ffmpeg = DesktopBinaryFinder.findFfmpeg() ?: return@submit
            val ok = runCatching {
                val proc = ProcessBuilder(
                    ffmpeg, "-v", "error",
                    "-init_hw_device", "vulkan=vk",
                    "-filter_hw_device", "vk",
                    "-f", "lavfi", "-i", "color=c=black:s=64x64:d=0.04",
                    "-frames:v", "1",
                    "-vf", "hwupload,libplacebo=w=64:h=64:format=bgra,hwdownload,format=bgra",
                    "-pix_fmt", "bgra",
                    "-f", "null", "-",
                ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                if (!proc.waitFor(4, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    false
                } else {
                    proc.exitValue() == 0
                }
            }.getOrDefault(false)
            vulkanPlaceboSupported = ok
            Log.i(TAG, "Vulkan libplacebo 预探测结果: supported=$ok")
        }
    }

    /** 应用启动后在后台线程预热 VLC、Vulkan libplacebo 管线与双地址服务器探测，避免首次点击播放时阻塞等待 */
    fun prewarm(context: Context? = null) {
        DesktopExoPlayer.ioPool.submit {
            runCatching { getSharedVlcFactory() }
        }
        probeVulkanPlaceboAsync()
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
        publishWrittenSlot(slot)
    }

    internal fun submitFrameBgraBytes(width: Int, height: Int, src: ByteArray) {
        val slot = acquireWriteSlot(width, height)
        val count = minOf(slot.bytes.size, src.size)
        System.arraycopy(src, 0, slot.bytes, 0, count)
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
            val n = ins.read(target, off, minOf(65_536, target.size - off))
            if (n < 0) return false
            off += n
        }
        if (off < target.size) return false
        if (renderFrame) {
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
                var slotToPublish: FrameSlot? = null
                synchronized(swapLock) {
                    if (hasReadyFrame && readySlot != null && readSlot != null) {
                        val prevRead = readSlot
                        readSlot = readySlot
                        readySlot = prevRead
                        hasReadyFrame = false
                        slotToPublish = readSlot
                    }
                }
                if (slotToPublish != null) {
                    slotToPublish!!.uploadToSkia()
                    val bmpToPublish = slotToPublish!!.composeBitmap
                    currentDrawBitmap = bmpToPublish
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
            currentEmbeddedSubtitleText.value = ""
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

enum class DesktopBackendMode {
    /** 默认智能模式：常规流走 VLCJ 低延迟渲染，检测到杜比视界流自动切 libplacebo 色彩映射管线消除紫绿偏色 */
    AUTO,
    /** MPV / libplacebo 模式：走 FFmpeg + Vulkan libplacebo（无 Vulkan 时自动回退 zscale 色调映射）渲染管线 */
    MPV_LIBPLACEBO,
    /** 纯 VLC 模式：强制优先使用 LibVLC（如蓝光/DVD ISO 光盘镜像） */
    VLC_ONLY,
}

class DesktopExoPlayer(
    private val context: Context,
    private val dataSourceFactory: DataSource.Factory?,
    var backendMode: DesktopBackendMode = DesktopBackendMode.AUTO,
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

    private val playbackStateLock = Any()

    @field:Volatile
    override var playbackState: Int = Player.STATE_IDLE
        private set(value) {
            synchronized(playbackStateLock) {
                if (field == value) return
                field = value
            }
            val playingNow = isPlaying
            notifyMain {
                listeners.forEach {
                    it.onPlaybackStateChanged(value)
                    it.onIsPlayingChanged(playingNow)
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

    private fun currentFfmpegAudioClockMs(): Long {
        val base = positionMsInternal
        if (!playWhenReady || !ffmpegAudioClockReady) return base.coerceAtLeast(0L)
        val spd = playbackParameters.speed.coerceIn(0.25f, 4.0f)
        val elapsedMs = ((System.nanoTime() - positionAnchorNano) / 1_000_000L).coerceIn(0L, 45L)
        val latencyComp = (FFMPEG_SPEAKER_LATENCY_MS * spd).roundToLong()
        return (base + (elapsedMs * spd).toLong() - latencyComp).coerceAtLeast(0L)
    }

    private fun rawExtrapolatedPlayheadMs(compensateSpeakerLatency: Boolean): Long {
        if (ffmpegRunning.get() && ffmpegAudioClockReady) {
            return currentFfmpegAudioClockMs()
        }
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
            if (isPlaying && (vlcActivelyPlaying || (ffmpegRunning.get() && ffmpegAudioClockReady))) {
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
            val prevSpeed = field.speed
            field = value
            val speed = value.speed.coerceIn(0.25f, 4.0f)
            if (ffmpegRunning.get()) {
                if (kotlin.math.abs(speed - prevSpeed) > 0.01f &&
                    playbackState != Player.STATE_IDLE &&
                    playbackState != Player.STATE_ENDED &&
                    ffmpegSpeedScheduled.compareAndSet(false, true)
                ) {
                    watchdogPool.schedule({
                        ffmpegSpeedScheduled.set(false)
                        if (ffmpegRunning.get() && playbackState != Player.STATE_IDLE) {
                            val resumePos = if (isCurrentMediaItemLive) 0L else currentPosition.coerceAtLeast(0L)
                            val nextGen = mediaGeneration.incrementAndGet()
                            currentMediaItem?.let { startCurrentItemAsync(nextGen, it, resumePos) }
                        }
                    }, 80L, TimeUnit.MILLISECONDS)
                }
                return
            }
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

    @Volatile
    private var currentChapters: List<Chapter> = emptyList()

    fun chapters(): List<Chapter> = currentChapters

    /** 非杜比认证管线下自动拦截杜比视界流改走 libplacebo 色调映射 */
    @Volatile
    var autoDolbyProtection: Boolean = true

    /** MPV/FFmpeg 硬解策略：null = 智能自动（Windows 默认启用 D3D11VA 硬解），true = 硬解优先，false = 纯软解 */
    @Volatile
    var hwdecPreference: Boolean? = null

    fun updateHwdecPreference(forceHwdec: Boolean?) {
        if (hwdecPreference == forceHwdec) return
        hwdecPreference = forceHwdec
        if ((ffmpegRunning.get() || vlcHasActiveMedia) && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED) {
            val resumePos = if (isCurrentMediaItemLive) 0L else currentPosition.coerceAtLeast(0L)
            val nextGen = mediaGeneration.incrementAndGet()
            playbackState = Player.STATE_BUFFERING
            currentMediaItem?.let { startCurrentItemAsync(nextGen, it, resumePos) }
        }
    }

    @Volatile
    var detectedDolbyProfile: Int? = null
        private set

    @Volatile
    var activePipelineDescription: String = ""
        private set

    @Volatile
    private var vlcBufferingSinceMs: Long = 0L

    @Volatile
    private var lastVlcCachePercent: Float = 100f

    private val vlcStats = MediaStatistics()

    @Volatile
    private var lastVlcInputBytesRead: Long = -1L

    @Volatile
    private var vlcStatsFuture: ScheduledFuture<*>? = null

    private val vlcStatsPollPending = AtomicBoolean(false)

    // 后端 2: 纯 Java WAV Clip (用于听书等短音频)
    @Volatile
    private var wavClip: Clip? = null

    // 后端 3: FFmpeg + D3D11VA 硬解 + Vulkan libplacebo 高速渲染引擎
    private val ffmpegRunning = AtomicBoolean(false)
    private val ffmpegSeekScheduled = AtomicBoolean(false)
    private val ffmpegSpeedScheduled = AtomicBoolean(false)
    @Volatile
    private var ffmpegAudioClockReady: Boolean = false
    @Volatile
    private var ffmpegAudioProc: Process? = null
    @Volatile
    private var ffmpegVideoProc: Process? = null
    @Volatile
    private var ffmpegAudioLine: SourceDataLine? = null
    @Volatile
    private var ffmpegAudioServer: ServerSocket? = null
    @Volatile
    private var ffmpegVideoServer: ServerSocket? = null
    private val ffmpegSubServers = CopyOnWriteArraySet<ServerSocket>()
    @Volatile
    private var selectedFfmpegAudioStreamSpec: String = "0:a:0?"
    @Volatile
    private var selectedFfmpegSubStreamIdx: Int = -1
    @Volatile
    private var selectedFfmpegBitmapSubIdx: Int = -1
    @Volatile
    private var lastProbedMeta: ProbedStreamMeta? = null
    private val ffmpegSubCueMap = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.CopyOnWriteArrayList<com.aisub.player.subtitle.Cue>>()
    @Volatile
    private var lastRenderedFfmpegSubText: String = ""
    @Volatile
    private var suppressEmbeddedRender: Boolean = false

    fun setEmbeddedSubtitlesSuppressed(suppressed: Boolean) {
        if (suppressEmbeddedRender == suppressed) return
        suppressEmbeddedRender = suppressed
        if (ffmpegRunning.get()) {
            updateFfmpegRenderedSubtitle(currentPosition)
        } else {
            vlcExecutor.execute {
                val mp = vlcMp ?: return@execute
                if (suppressed) {
                    runCatching { mp.subpictures().setTrack(-1) }
                } else {
                    applyTrackSelectionOnVlc(trackSelectionParameters, mp)
                }
            }
        }
    }

    private fun formatVlcCompatTrackName(label: String?, language: String?, forced: Boolean, fallback: String): String {
        val base = buildString {
            val clean = label?.trim().orEmpty()
            if (clean.isNotEmpty()) append(clean) else append(fallback)
            if (forced && !contains("forced", ignoreCase = true)) {
                append(" (Forced)")
            }
        }
        val lang = language?.trim()?.takeIf { it.isNotEmpty() && it != "und" }
        return if (lang != null && !base.contains("[$lang]", ignoreCase = true)) {
            "$base - [$lang]"
        } else {
            base
        }
    }

    internal fun vlcAudioTrackDescriptions(): List<Pair<Int, String>> {
        if (ffmpegRunning.get()) {
            return lastProbedMeta?.audioStreams.orEmpty().mapIndexed { i, a ->
                a.streamIdx to formatVlcCompatTrackName(a.label ?: a.format.label, a.language, forced = false, fallback = "Track ${i + 1}")
            }
        }
        val mp = vlcMp ?: return emptyList()
        return runCatching {
            mp.audio().trackDescriptions().orEmpty()
                .filter { it.id() >= 0 }
                .map { it.id() to it.description().orEmpty() }
        }.getOrDefault(emptyList())
    }

    internal fun vlcAudioTrackId(): Int {
        if (ffmpegRunning.get()) {
            return selectedFfmpegAudioStreamSpec.removePrefix("0:").removeSuffix("?").toIntOrNull() ?: -1
        }
        return runCatching { vlcMp?.audio()?.track() ?: -1 }.getOrDefault(-1)
    }

    internal fun setVlcAudioTrackId(id: Int): Boolean {
        if (id < 0) {
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            return true
        }
        val grp = currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.mediaTrackGroup.id == "a$id" }
        if (grp != null) {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(grp.mediaTrackGroup, 0))
                .build()
            return true
        }
        return runCatching { vlcMp?.audio()?.let { it.setTrack(id); true } == true }.getOrDefault(false)
    }

    internal fun vlcSpuTrackDescriptions(): List<Pair<Int, String>> {
        if (ffmpegRunning.get()) {
            return lastProbedMeta?.subtitleStreams.orEmpty().map { s ->
                s.streamIdx to formatVlcCompatTrackName(s.label, s.language, forced = s.forced, fallback = "Track ${s.subOrdinal + 1}")
            }
        }
        val mp = vlcMp ?: return emptyList()
        return runCatching {
            mp.subpictures().trackDescriptions().orEmpty()
                .filter { it.id() >= 0 }
                .map { it.id() to it.description().orEmpty() }
        }.getOrDefault(emptyList())
    }

    internal fun vlcSpuTrackId(): Int {
        if (ffmpegRunning.get()) {
            return selectedFfmpegSubStreamIdx
        }
        return runCatching { vlcMp?.subpictures()?.track() ?: -1 }.getOrDefault(-1)
    }

    internal fun setVlcSpuTrackId(id: Int): Boolean {
        if (id < 0) {
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            return true
        }
        val grp = currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.mediaTrackGroup.id == "s$id" }
        if (grp != null) {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(grp.mediaTrackGroup, 0))
                .build()
            return true
        }
        return runCatching { vlcMp?.subpictures()?.let { it.setTrack(id); true } == true }.getOrDefault(false)
    }

    internal fun setVlcVideoTrackEnabled(enabled: Boolean) {
        trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled).build()
    }
    private val ringBufferPoolLock = Any()
    private var pooledRingFrameBytes: Int = 0
    private var pooledRingBuffers: Array<ByteArray>? = null

    private fun acquireRingBuffers(ringCap: Int, frameBytes: Int): Array<ByteArray> = synchronized(ringBufferPoolLock) {
        val existing = pooledRingBuffers
        if (existing != null && existing.size == ringCap && pooledRingFrameBytes == frameBytes) {
            return existing
        }
        return Array(ringCap) { ByteArray(frameBytes) }.also {
            pooledRingFrameBytes = frameBytes
            pooledRingBuffers = it
        }
    }

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
        proxy?.clear()
        DesktopPlayerEngine.resetNetworkSpeed()
        stopInternalAsync()
        playlist.clear()
        playlist.addAll(mediaItems)
        currentMediaItemIndex = startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        positionMsInternal = if (startPositionMs != C.TIME_UNSET && startPositionMs > 0) startPositionMs else 0L
        positionAnchorNano = System.nanoTime()
        lastReportedMonotonicMs = -1L
        duration = C.TIME_UNSET
        currentTracks = Tracks.EMPTY
        currentChapters = emptyList()
        lastProbedMeta = null
        selectedFfmpegAudioStreamSpec = "0:a:0?"
        selectedFfmpegSubStreamIdx = -1
        selectedFfmpegBitmapSubIdx = -1
        ffmpegSubCueMap.clear()
        clearRenderedEmbeddedSubtitle()
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
        proxy?.clear()
        DesktopPlayerEngine.resetNetworkSpeed()
        stopInternalAsync()
        playlist.clear()
        currentChapters = emptyList()
        lastProbedMeta = null
        selectedFfmpegSubStreamIdx = -1
        selectedFfmpegBitmapSubIdx = -1
        ffmpegSubCueMap.clear()
        clearRenderedEmbeddedSubtitle()
        playbackState = Player.STATE_IDLE
    }

    override fun prepare() {
        val item = currentMediaItem ?: return
        val gen = mediaGeneration.incrementAndGet()
        lastReportedMonotonicMs = -1L
        playerError = null
        DesktopPlayerEngine.resetNetworkSpeed()
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
        vlcStatsFuture?.cancel(false)
        vlcStatsFuture = null
        currentChapters = emptyList()
        listeners.clear()
        if (DesktopPlayerEngine.activePlayer === this) {
            DesktopPlayerEngine.activePlayer = null
        }
        vlcExecutor.execute {
            stopInternalOnExecutor()
            proxy?.close()
            proxy = null
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
            pendingSeekTargetMs = targetPos
            if (ffmpegSeekScheduled.compareAndSet(false, true)) {
                watchdogPool.schedule({
                    ffmpegSeekScheduled.set(false)
                    val finalTarget = pendingSeekTargetMs
                    if (finalTarget != C.TIME_UNSET && playbackState != Player.STATE_IDLE) {
                        val nextGen = mediaGeneration.incrementAndGet()
                        currentMediaItem?.let { startCurrentItemAsync(nextGen, it, finalTarget) }
                    }
                }, 60L, TimeUnit.MILLISECONDS)
            }
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
                    val urlNow = lastPlayableUrl
                    val isNetwork = urlNow != null && (urlNow.startsWith("http://", ignoreCase = true) || urlNow.startsWith("https://", ignoreCase = true))
                    if (isNetwork && playWhenReady && (renderedFirstFrame.get() || isCurrentItemAudioOnly)) {
                        vlcActivelyPlaying = false
                        lastVlcCachePercent = 0f
                        vlcBufferingSinceMs = System.currentTimeMillis()
                        scheduleBufferingIndicatorCheck(gen, vlcMp)
                    }
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

    private fun resolveSelectedFfmpegSubStreamIdx(
        params: TrackSelectionParameters,
        subs: List<ProbedSubtitleStream>,
    ): Int {
        if (subs.isEmpty() || C.TRACK_TYPE_TEXT in params.disabledTrackTypes) return -1
        val textOverride = params.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
        if (textOverride != null) {
            val idx = textOverride.mediaTrackGroup.id.removePrefix("s").toIntOrNull()
            if (idx != null && subs.any { it.streamIdx == idx }) return idx
        }
        val prefLangs = params.preferredTextLanguages
        for (rawPref in prefLangs) {
            val pref = EmbeddedSubtitles.normalizeLang(rawPref) ?: rawPref.lowercase()
            subs.firstOrNull { !it.forced && (it.language == pref || it.language?.startsWith(pref) == true) }?.let { return it.streamIdx }
            subs.firstOrNull { it.language == pref || it.language?.startsWith(pref) == true }?.let { return it.streamIdx }
        }
        return -1
    }

    private fun resolveSelectedFfmpegAudioStreamSpec(
        params: TrackSelectionParameters,
        meta: ProbedStreamMeta?,
    ): String {
        if (C.TRACK_TYPE_AUDIO in params.disabledTrackTypes) return ""
        val audioOverride = params.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
        if (audioOverride != null) {
            val streamIdx = audioOverride.mediaTrackGroup.id.removePrefix("a").toIntOrNull()
            if (streamIdx != null) return "0:$streamIdx?"
        }
        val audios = meta?.audioStreams.orEmpty()
        if (audios.isNotEmpty()) {
            for (rawPref in params.preferredAudioLanguages) {
                val pref = EmbeddedSubtitles.normalizeLang(rawPref) ?: rawPref.lowercase()
                audios.firstOrNull { it.language == pref || it.language?.startsWith(pref) == true }?.let {
                    return "0:${it.streamIdx}?"
                }
            }
            return "0:${audios.first().streamIdx}?"
        }
        return "0:a:0?"
    }

    private fun rebuildFfmpegTracksAndNotify(meta: ProbedStreamMeta) {
        val groups = ArrayList<Tracks.Group>()
        meta.videoFormat?.let { vf ->
            val vId = vf.id ?: "v0"
            groups.add(Tracks.Group(TrackGroup(vId, vf), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)))
        }
        val selectedAudioIdx = selectedFfmpegAudioStreamSpec.removePrefix("0:").removeSuffix("?").toIntOrNull()
        meta.audioStreams.forEachIndexed { i, a ->
            val isSel = selectedFfmpegAudioStreamSpec.isNotEmpty() &&
                (selectedAudioIdx == a.streamIdx || (selectedAudioIdx == null && i == 0))
            groups.add(Tracks.Group(TrackGroup("a${a.streamIdx}", a.format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(isSel)))
        }
        val curSubIdx = selectedFfmpegSubStreamIdx
        meta.subtitleStreams.forEach { sub ->
            val isSel = sub.streamIdx == curSubIdx
            groups.add(Tracks.Group(TrackGroup("s${sub.streamIdx}", sub.format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(isSel)))
        }
        currentTracks = Tracks(groups)
        notifyMain { listeners.forEach { it.onTracksChanged(currentTracks) } }
    }

    private fun clearRenderedEmbeddedSubtitle() {
        lastRenderedFfmpegSubText = ""
        DesktopPlayerEngine.currentEmbeddedSubtitleText.value = ""
        notifyMain { listeners.forEach { it.onCues(androidx.media3.common.text.CueGroup()) } }
    }

    private fun updateFfmpegRenderedSubtitle(posMs: Long) {
        if (DesktopPlayerEngine.activePlayer !== this) return
        val subIdx = selectedFfmpegSubStreamIdx
        val activeCues = if (subIdx < 0 || suppressEmbeddedRender || C.TRACK_TYPE_TEXT in trackSelectionParameters.disabledTrackTypes) {
            emptyList()
        } else {
            val subMeta = lastProbedMeta?.subtitleStreams?.firstOrNull { it.streamIdx == subIdx }
            if (subMeta == null || subMeta.imageBased) {
                emptyList()
            } else {
                ffmpegSubCueMap[subIdx]?.filter { posMs in it.startMs..it.endMs }.orEmpty()
            }
        }
        val displayLines = activeCues.flatMap { c -> listOfNotNull(c.translation, c.text).filter { it.isNotBlank() } }
        val textNow = displayLines.joinToString("\n")
        if (textNow == lastRenderedFfmpegSubText) return
        lastRenderedFfmpegSubText = textNow
        if (DesktopPlayerEngine.activePlayer === this@DesktopExoPlayer) {
            DesktopPlayerEngine.currentEmbeddedSubtitleText.value = textNow
        }
        val mediaCues = if (textNow.isEmpty()) emptyList() else listOf(
            androidx.media3.common.text.Cue.Builder().setText(textNow).build(),
        )
        notifyMain {
            listeners.forEach { it.onCues(androidx.media3.common.text.CueGroup(mediaCues)) }
        }
    }

    private fun applyTrackSelectionOnVlc(params: TrackSelectionParameters, mp: MediaPlayer) {
        val audioOverride = params.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
        val audioDescs = runCatching { mp.audio().trackDescriptions().orEmpty().filter { it.id() >= 0 } }.getOrDefault(emptyList())
        if (C.TRACK_TYPE_AUDIO in params.disabledTrackTypes) {
            runCatching { mp.audio().setTrack(-1) }
        } else if (audioOverride != null) {
            val id = audioOverride.mediaTrackGroup.id.removePrefix("a").toIntOrNull()
            if (id != null) runCatching { mp.audio().setTrack(id) }
        } else if (params.preferredAudioLanguages.isNotEmpty() && audioDescs.isNotEmpty()) {
            for (rawPref in params.preferredAudioLanguages) {
                val pref = EmbeddedSubtitles.normalizeLang(rawPref) ?: rawPref.lowercase()
                val hit = audioDescs.firstOrNull { EmbeddedSubtitles.parseLanguageFromLabel(it.description()) == pref }
                if (hit != null) {
                    runCatching { mp.audio().setTrack(hit.id()) }
                    break
                }
            }
        }
        val textOverride = params.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
        val spuDescs = runCatching { mp.subpictures().trackDescriptions().orEmpty().filter { it.id() >= 0 } }.getOrDefault(emptyList())
        when {
            suppressEmbeddedRender || C.TRACK_TYPE_TEXT in params.disabledTrackTypes -> {
                runCatching { mp.subpictures().setTrack(-1) }
            }
            textOverride != null -> {
                val id = textOverride.mediaTrackGroup.id.removePrefix("s").toIntOrNull()
                if (id != null) runCatching { mp.subpictures().setTrack(id) }
            }
            params.preferredTextLanguages.isNotEmpty() && spuDescs.isNotEmpty() -> {
                var matched: Int? = null
                for (rawPref in params.preferredTextLanguages) {
                    val pref = EmbeddedSubtitles.normalizeLang(rawPref) ?: rawPref.lowercase()
                    val hit = spuDescs.firstOrNull { d ->
                        val desc = d.description().orEmpty()
                        !"forced".equals(desc.trim(), ignoreCase = true) && !desc.contains("forced", ignoreCase = true) &&
                            EmbeddedSubtitles.parseLanguageFromLabel(desc) == pref
                    } ?: spuDescs.firstOrNull { EmbeddedSubtitles.parseLanguageFromLabel(it.description()) == pref }
                    if (hit != null) {
                        matched = hit.id()
                        break
                    }
                }
                if (matched != null) runCatching { mp.subpictures().setTrack(matched) }
            }
        }
    }

    private fun applyTrackSelection(params: TrackSelectionParameters) {
        val gen = mediaGeneration.get()
        if (ffmpegRunning.get()) {
            val meta = lastProbedMeta
            val nextAudioSpec = resolveSelectedFfmpegAudioStreamSpec(params, meta)
            val nextSubIdx = resolveSelectedFfmpegSubStreamIdx(params, meta?.subtitleStreams.orEmpty())
            val nextBitmapSubIdx = meta?.subtitleStreams?.firstOrNull { it.streamIdx == nextSubIdx && it.imageBased }?.streamIdx ?: -1
            val needRestartForAudio = nextAudioSpec != selectedFfmpegAudioStreamSpec
            val needRestartForBitmap = nextBitmapSubIdx != selectedFfmpegBitmapSubIdx
            selectedFfmpegAudioStreamSpec = nextAudioSpec
            selectedFfmpegSubStreamIdx = nextSubIdx
            selectedFfmpegBitmapSubIdx = nextBitmapSubIdx
            if (meta != null) {
                rebuildFfmpegTracksAndNotify(meta)
            }
            updateFfmpegRenderedSubtitle(currentPosition)
            if (needRestartForAudio || needRestartForBitmap) {
                val resumePos = currentPosition
                val nextGen = mediaGeneration.incrementAndGet()
                currentMediaItem?.let { startCurrentItemAsync(nextGen, it, resumePos) }
            }
            return
        }
        vlcExecutor.execute {
            if (gen != mediaGeneration.get()) return@execute
            val mp = vlcMp ?: return@execute
            applyTrackSelectionOnVlc(params, mp)
            refreshVlcTracksOnExecutor(mp)
        }
    }

    private fun hasCustomAuthHeaders(headers: Map<String, String>): Boolean =
        headers.keys.any { !it.equals("User-Agent", ignoreCase = true) && !it.equals("Referer", ignoreCase = true) }

    private fun redactUrlForLog(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val maskedQuery = raw.replace(Regex("(?i)([?&](?:X-Plex-Token|token|password|passwd|auth|authorization|api_key|apikey|sig|signature)=)[^&\\s]+"), "$1***")
        if ("://" !in maskedQuery) {
            return File(maskedQuery).name.ifBlank { "<local-file>" }
        }
        if (maskedQuery.startsWith("http://127.0.0.1:", ignoreCase = true) ||
            maskedQuery.startsWith("http://localhost:", ignoreCase = true)
        ) {
            return maskedQuery
        }
        return runCatching {
            val u = URI(maskedQuery)
            val host = u.host
            if (host.isNullOrBlank()) {
                maskedQuery
            } else {
                val tail = u.rawPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "..."
                "${u.scheme ?: "https"}://<redacted-host>/.../$tail"
            }
        }.getOrDefault("<redacted-url>")
    }

    @Volatile
    private var lastPresentedVideoPtsMs: Long = 0L

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
        lastPresentedVideoPtsMs = startPosMs.coerceAtLeast(0L)
        renderedFirstFrame.set(false)
        vlcPlayedCurrentItem = false
        vlcActivelyPlaying = false
        vlcBufferingSinceMs = 0L
        lastVlcCachePercent = 0f
        lastVlcInputBytesRead = -1L
        ffmpegAudioClockReady = false
        currentChapters = emptyList()

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
                Log.d(TAG, "解析播放地址耗时=${resolveCost}ms -> ${redactUrlForLog(playableUrl)}")
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

            // 2. 若指定了 MPV / libplacebo 内核，或开启了防偏色且探测到杜比视界（IPTPQc2 / RPU）或 HDR10 / HLG / BT.2020 广色域流，
            //    主动绕过不支持 vmem 回调色调映射与色域转换的 LibVLC 3.x（否则必出紫绿或灰白偏色），直连 GPU libplacebo 色彩映射管线
            val forceMpvPipeline = backendMode == DesktopBackendMode.MPV_LIBPLACEBO
            val colorRequirement = if (!forceMpvPipeline && backendMode == DesktopBackendMode.AUTO && autoDolbyProtection && !isCurrentItemAudioOnly) {
                probeColorPipelineRequirementQuick(playableUrl, headers, item.mediaMetadata.title?.toString())
            } else {
                null
            }
            val probeDvProfile = colorRequirement?.dvProfile
            val requiresColorMap = colorRequirement != null && (probeDvProfile != null || colorRequirement.isHdrOrWideGamut)
            if (probeDvProfile != null) {
                detectedDolbyProfile = probeDvProfile
                Log.i(TAG, "检测到杜比视界 Profile $probeDvProfile，绕过 VLC vmem 改用 GPU libplacebo 色调映射管线: ${redactUrlForLog(playableUrl)}")
            } else if (requiresColorMap) {
                Log.i(TAG, "检测到 HDR10 / HLG / BT.2020 广色域流，绕过 VLC vmem 改用 GPU libplacebo 色调映射管线: ${redactUrlForLog(playableUrl)}")
            }

            val forceFfmpegForWindowsHwdec = IS_WINDOWS_HOST && hwdecPreference == true && backendMode == DesktopBackendMode.AUTO
            if (!forceMpvPipeline && !requiresColorMap && !forceFfmpegForWindowsHwdec &&
                DesktopPlayerEngine.vlcAvailable && startWithVlcjOnExecutor(gen, playableUrl, headers, startPosMs)
            ) {
                activePipelineDescription = when (hwdecPreference) {
                    true -> "LibVLC 硬件加速管线"
                    false -> "LibVLC 纯软解管线"
                    null -> "LibVLC 智能混合管线"
                }
                return@execute
            }

            // 3. 使用内置 MPV/libplacebo + FFmpeg GPU 色彩映射渲染引擎
            startWithFfmpeg(
                gen = gen,
                url = playableUrl,
                headers = headers,
                startPosMs = startPosMs,
                preferLibplacebo = forceMpvPipeline || requiresColorMap,
            )
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
            when (hwdecPreference) {
                true -> options.add(if (IS_WINDOWS_HOST) ":avcodec-hw=none" else ":avcodec-hw=any")
                false -> options.add(":avcodec-hw=none")
                null -> {}
            }
            val lowerUrl = url.lowercase()
            if (lowerUrl.startsWith("http://127.0.0.1:") || lowerUrl.startsWith("http://localhost:")) {
                options.add(":http-proxy=")
                options.add(if (lowerUrl.contains("/live/")) ":network-caching=300" else ":network-caching=2500")
            } else if (lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")) {
                options.add(":network-caching=2500")
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
            Log.e(TAG, "VLCJ 启动异常: ${redactUrlForLog(url)}", e)
        }.getOrDefault(false)
    }

    private fun isDirectRemoteNetworkUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        val isNet = lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("rtsp://") || lower.startsWith("rtmp://") ||
            lower.startsWith("rtmps://") || lower.startsWith("mms://")
        if (!isNet) return false
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host != "127.0.0.1" && host != "localhost" && host != "::1" && host != "[::1]"
    }

    private fun startVlcNetworkStatsSampler(gen: Int, url: String) {
        vlcStatsFuture?.cancel(false)
        vlcStatsFuture = null
        lastVlcInputBytesRead = -1L
        if (!isDirectRemoteNetworkUrl(url)) return
        vlcStatsPollPending.set(false)
        vlcStatsFuture = watchdogPool.scheduleWithFixedDelay(
            {
                if (gen != mediaGeneration.get() || !vlcHasActiveMedia || DesktopPlayerEngine.activePlayer !== this@DesktopExoPlayer) {
                    vlcStatsFuture?.cancel(false)
                    return@scheduleWithFixedDelay
                }
                if (vlcStatsPollPending.compareAndSet(false, true)) {
                    vlcExecutor.execute {
                        try {
                            if (gen != mediaGeneration.get() || !vlcHasActiveMedia) return@execute
                            val mp = vlcMp ?: return@execute
                            if (mp.media().info().statistics(vlcStats)) {
                                val readNow = vlcStats.inputBytesRead().toUInt().toLong()
                                val prev = lastVlcInputBytesRead
                                if (prev in 0L until readNow) {
                                    DesktopPlayerEngine.recordNetworkBytes(readNow - prev)
                                }
                                lastVlcInputBytesRead = readNow
                            }
                        } catch (_: Throwable) {
                        } finally {
                            vlcStatsPollPending.set(false)
                        }
                    }
                }
            },
            250L,
            250L,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun scheduleBufferingIndicatorCheck(gen: Int, mp: MediaPlayer?) {
        watchdogPool.schedule(
            {
                if (gen == mediaGeneration.get() && vlcMp === mp && playWhenReady &&
                    lastVlcCachePercent < 100f && vlcBufferingSinceMs > 0L && playbackState == Player.STATE_READY
                ) {
                    playbackState = Player.STATE_BUFFERING
                }
            },
            250L,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun startVlcMediaOnExecutor(gen: Int, url: String, optArr: Array<String>): Boolean {
        val mp = vlcMp ?: return false
        val t0 = System.currentTimeMillis()
        vlcHasActiveMedia = true
        lastVlcCachePercent = 0f
        vlcBufferingSinceMs = System.currentTimeMillis()
        val ok = runCatching { mp.media().play(url, *optArr) }.getOrDefault(false)
        Log.i(TAG, "VLC media().play 调用返回: ok=$ok, 耗时=${System.currentTimeMillis() - t0}ms, url=${redactUrlForLog(url)}")
        if (!ok) {
            vlcHasActiveMedia = false
            val fallbackUrl = lastPlayableUrl
            if (fallbackUrl != null && gen == mediaGeneration.get()) {
                startWithFfmpeg(gen, fallbackUrl, lastHeaders, positionMsInternal)
            }
            return false
        }
        startVlcNetworkStatsSampler(gen, url)
        // 25 秒打开超时守护：若仍在有效下载缓冲则宽限 20 秒，否则自动切换 FFmpeg 兜底
        watchdogPool.schedule({
            if (gen == mediaGeneration.get() && playWhenReady && !vlcPlayedCurrentItem && !renderedFirstFrame.get() && !ffmpegRunning.get()) {
                val activeDownload = lastVlcCachePercent > 0f || DesktopPlayerEngine.currentDownloadSpeedBytesPerSec() > 0L
                if (activeDownload) {
                    val speedStr = DesktopPlayerEngine.formatNetworkSpeed(DesktopPlayerEngine.currentDownloadSpeedBytesPerSec())
                    Log.i(TAG, "VLC 仍在下载缓冲 (${lastVlcCachePercent.toInt()}%, $speedStr)，继续等待最多 20s: ${redactUrlForLog(url)}")
                    watchdogPool.schedule({
                        if (gen == mediaGeneration.get() && playWhenReady && !vlcPlayedCurrentItem && !renderedFirstFrame.get() && !ffmpegRunning.get()) {
                            Log.w(TAG, "VLC 打开媒体超过 45s 仍未起播，自动切换 FFmpeg 兜底: ${redactUrlForLog(url)}")
                            vlcExecutor.execute {
                                if (gen == mediaGeneration.get() && !vlcPlayedCurrentItem && !renderedFirstFrame.get() && !ffmpegRunning.get()) {
                                    stopVlcWithTimeoutOnExecutor()
                                    startWithFfmpeg(gen, url, lastHeaders, positionMsInternal)
                                }
                            }
                        }
                    }, 20, TimeUnit.SECONDS)
                } else {
                    Log.w(TAG, "VLC 打开媒体超过 25s 无响应，自动切换 FFmpeg 兜底: ${redactUrlForLog(url)}")
                    vlcExecutor.execute {
                        if (gen == mediaGeneration.get() && !vlcPlayedCurrentItem && !renderedFirstFrame.get() && !ffmpegRunning.get()) {
                            stopVlcWithTimeoutOnExecutor()
                            startWithFfmpeg(gen, url, lastHeaders, positionMsInternal)
                        }
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
                lastVlcCachePercent = newCache
                if (newCache >= 100f) {
                    vlcBufferingSinceMs = 0L
                    if (vlcPlayedCurrentItem && (isCurrentItemAudioOnly || renderedFirstFrame.get())) {
                        vlcActivelyPlaying = playWhenReady
                        positionAnchorNano = System.nanoTime()
                        if (playbackState == Player.STATE_BUFFERING) {
                            playbackState = Player.STATE_READY
                        }
                    }
                } else {
                    vlcActivelyPlaying = false
                    if (!renderedFirstFrame.get() && !isCurrentItemAudioOnly) {
                        if (playbackState != Player.STATE_IDLE) {
                            playbackState = Player.STATE_BUFFERING
                        }
                    } else if (playWhenReady && playbackState == Player.STATE_READY && vlcBufferingSinceMs == 0L) {
                        vlcBufferingSinceMs = System.currentTimeMillis()
                        scheduleBufferingIndicatorCheck(mediaGeneration.get(), mp)
                    }
                }
            }

            override fun playing(mediaPlayer: MediaPlayer) {
                if (vlcMp !== mp) return
                vlcPlayedCurrentItem = true
                vlcActivelyPlaying = true
                vlcBufferingSinceMs = 0L
                lastVlcCachePercent = 100f
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
                        applyTrackSelectionOnVlc(trackSelectionParameters, mp)
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
                    vlcBufferingSinceMs = 0L
                    lastVlcCachePercent = 100f
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
                        applyTrackSelectionOnVlc(trackSelectionParameters, mp)
                        refreshVlcTracksOnExecutor(mp)
                    }
                }
            }

            override fun finished(mediaPlayer: MediaPlayer) {
                if (vlcMp !== mp) return
                vlcActivelyPlaying = false
                vlcHasActiveMedia = false
                vlcBufferingSinceMs = 0L
                vlcStatsFuture?.cancel(false)
                vlcStatsFuture = null
                onItemEnded()
            }

            override fun error(mediaPlayer: MediaPlayer) {
                if (vlcMp !== mp) return
                vlcActivelyPlaying = false
                vlcHasActiveMedia = false
                vlcBufferingSinceMs = 0L
                vlcStatsFuture?.cancel(false)
                vlcStatsFuture = null
                val gen = mediaGeneration.get()
                val fallbackUrl = lastPlayableUrl
                Log.w(TAG, "VLC 播放报错: played=$vlcPlayedCurrentItem, url=${redactUrlForLog(fallbackUrl)}")
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
                val desc = d.description().orEmpty()
                val lang = EmbeddedSubtitles.parseLanguageFromLabel(desc)
                val fb = Format.Builder().setId("a${d.id()}").setLabel(desc).setSampleMimeType(MimeTypes.AUDIO_UNKNOWN)
                if (lang != null) fb.setLanguage(lang)
                groups.add(Tracks.Group(TrackGroup("a${d.id()}", fb.build()), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(d.id() == currentAudio)))
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
                val lang = EmbeddedSubtitles.parseLanguageFromLabel(desc)
                val selFlag = if ("forced" in desc.lowercase()) C.SELECTION_FLAG_FORCED else 0
                val fb = Format.Builder()
                    .setId("s${d.id()}")
                    .setLabel(desc)
                    .setSampleMimeType(mime)
                    .setSelectionFlags(selFlag)
                if (lang != null) fb.setLanguage(lang)
                groups.add(Tracks.Group(TrackGroup("s${d.id()}", fb.build()), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(d.id() == currentSpu)))
            }
            val rawChaps = runCatching { mp.chapters().descriptions().orEmpty() }.getOrDefault(emptyList())
            if (rawChaps.isNotEmpty()) {
                currentChapters = rawChaps.mapIndexed { idx, ch ->
                    val start = ch.offset().coerceAtLeast(0L)
                    val dur = ch.duration()
                    val end = if (dur > 0L) start + dur else rawChaps.getOrNull(idx + 1)?.offset()?.takeIf { it > start }
                    Chapter(start, end, ch.name().orEmpty().ifBlank { "章节 ${idx + 1}" })
                }
            }
            currentTracks = Tracks(groups)
            notifyMain { listeners.forEach { it.onTracksChanged(currentTracks) } }
        }
    }

    private fun parseFfmpegFrameRate(rateStr: String?): Double? {
        if (rateStr.isNullOrBlank()) return null
        val parts = rateStr.split('/')
        if (parts.size != 2) return null
        val num = parts[0].toDoubleOrNull() ?: return null
        val den = parts[1].toDoubleOrNull() ?: return null
        if (den <= 0.0) return null
        val v = num / den
        return if (v in 1.0..240.0) v else null
    }

    /**
     * 快速探测媒体流是否为杜比视界（Dolby Vision）：
     * 本地文件直接调 ffprobe 毫秒级读取流头；远程流仅在文件名含 DV / DoVi / Dolby Vision 或 MP4/MKV 容器时轻量探测，不拖慢常规起播。
     */
    internal data class ProbedAudioStream(
        val streamIdx: Int,
        val language: String?,
        val label: String?,
        val format: Format,
    )

    internal data class ProbedSubtitleStream(
        val streamIdx: Int,
        val subOrdinal: Int,
        val language: String?,
        val label: String?,
        val codecMime: String?,
        val imageBased: Boolean,
        val forced: Boolean,
        val format: Format,
    )

    private data class ProbedStreamMeta(
        val srcWidth: Int,
        val srcHeight: Int,
        val hasVideo: Boolean,
        val hasAudio: Boolean,
        val fps: Double,
        val durationMs: Long,
        val isHdrOrDv: Boolean,
        val dvProfile: Int?,
        val videoFormat: Format?,
        val audioFormat: Format?,
        val audioStreams: List<ProbedAudioStream>,
        val subtitleStreams: List<ProbedSubtitleStream>,
        val trackGroups: List<Tracks.Group>,
    )

    private fun probeMediaStreamsCached(
        url: String,
        headers: Map<String, String>,
        timeoutSec: Long = 8L,
    ): ProbedStreamMeta? {
        val cacheKey = currentMediaItem?.localConfiguration?.uri?.toString()?.ifBlank { url } ?: url
        probeMetaCache[cacheKey]?.let { return it }
        probeMetaCache[url]?.let { return it }

        val ffprobe = DesktopBinaryFinder.findFfprobe() ?: return null
        return runCatching {
            val cmd = mutableListOf(
                ffprobe, "-v", "error",
                "-probesize", "1500000",
                "-analyzeduration", "1500000",
                "-show_entries", "format=duration:stream=index,codec_type,codec_name,codec_tag_string,color_space,color_transfer,color_primaries,width,height,avg_frame_rate,r_frame_rate:stream_tags=language,title:stream_disposition=forced:stream_side_data",
                "-of", "json",
            )
            if (url.startsWith("http://127.0.0.1:", ignoreCase = true) || url.startsWith("http://localhost:", ignoreCase = true)) {
                cmd.addAll(listOf("-http_proxy", ""))
            }
            if (headers.isNotEmpty() && url.startsWith("http")) {
                val hdr = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
                cmd.addAll(listOf("-headers", hdr))
            }
            cmd.add(url)
            val proc = ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            var jsonText = ""
            val readerThread = Thread(
                { runCatching { jsonText = proc.inputStream.bufferedReader().readText() } },
                "Voxtitle-FfprobeRead",
            ).apply { isDaemon = true; start() }
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
            }
            readerThread.join(400)
            if (jsonText.isBlank()) return null

            val dvProf = parseDvProfileFromFfprobeJson(jsonText)
            val root = JSONObject(jsonText)
            val durSec = root.optJSONObject("format")?.optString("duration")?.toDoubleOrNull()
            val durMs = if (durSec != null && durSec > 0.0) (durSec * 1000.0).toLong() else C.TIME_UNSET
            val streams = root.optJSONArray("streams")

            var srcW = 1280
            var srcH = 720
            var foundVideo = false
            var foundAudio = false
            var fps = 24.0
            var isHdr = dvProf != null
            var vFmt: Format? = null
            var aFmt: Format? = null
            val audioStreams = ArrayList<ProbedAudioStream>()
            val subtitleStreams = ArrayList<ProbedSubtitleStream>()
            val groups = ArrayList<Tracks.Group>()
            var subOrdinal = 0

            if (streams != null) {
                for (i in 0 until streams.length()) {
                    val s = streams.getJSONObject(i)
                    val stype = s.optString("codec_type")
                    val idx = s.optInt("index", i)
                    val tags = s.optJSONObject("tags")
                    val rawLang = tags?.optString("language")?.takeIf { it.isNotBlank() }
                    val lang = EmbeddedSubtitles.normalizeLang(rawLang) ?: rawLang
                    val title = tags?.optString("title")?.takeIf { it.isNotBlank() }
                    if (stype == "video" && !foundVideo) {
                        val w = s.optInt("width", 0)
                        val h = s.optInt("height", 0)
                        val trc = s.optString("color_transfer").lowercase()
                        val prim = s.optString("color_primaries").lowercase()
                        val cspace = s.optString("color_space").lowercase()
                        if (trc == "smpte2084" || trc == "arib-std-b67" || trc == "smpte428" ||
                            prim == "bt2020" || cspace.startsWith("bt2020") || "ipt" in cspace || "dolby" in cspace
                        ) {
                            isHdr = true
                        }
                        val sideList = s.optJSONArray("side_data_list")
                        if (sideList != null) {
                            for (si in 0 until sideList.length()) {
                                val sdType = sideList.getJSONObject(si).optString("side_data_type").lowercase()
                                if ("mastering display" in sdType || "content light" in sdType || "hdr" in sdType || "dovi" in sdType) {
                                    isHdr = true
                                }
                            }
                        }
                        if (w > 0 && h > 0) {
                            srcW = w
                            srcH = h
                            foundVideo = true
                            fps = parseFfmpegFrameRate(s.optString("avg_frame_rate"))
                                ?: parseFfmpegFrameRate(s.optString("r_frame_rate"))
                                ?: fps
                            val mime = if (dvProf != null) MimeTypes.VIDEO_DOLBY_VISION else "video/${s.optString("codec_name", "avc")}"
                            val codecsStr = if (dvProf != null) "dvhe.%02d".format(java.util.Locale.US, dvProf) else null
                            vFmt = Format.Builder()
                                .setId("v$idx")
                                .setSampleMimeType(mime)
                                .setCodecs(codecsStr)
                                .setWidth(w)
                                .setHeight(h)
                                .setFrameRate(fps.toFloat())
                                .build()
                            groups.add(Tracks.Group(TrackGroup("v$idx", vFmt!!), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)))
                        }
                    } else if (stype == "audio") {
                        val codecName = s.optString("codec_name", "")
                        val aMime = when {
                            "eac3" in codecName -> MimeTypes.AUDIO_E_AC3
                            "ac3" in codecName -> MimeTypes.AUDIO_AC3
                            "truehd" in codecName -> MimeTypes.AUDIO_TRUEHD
                            "dts" in codecName -> MimeTypes.AUDIO_DTS
                            "flac" in codecName -> MimeTypes.AUDIO_FLAC
                            "aac" in codecName -> MimeTypes.AUDIO_AAC
                            "opus" in codecName -> MimeTypes.AUDIO_OPUS
                            else -> MimeTypes.AUDIO_UNKNOWN
                        }
                        val af = Format.Builder()
                            .setId("a$idx")
                            .setLanguage(lang)
                            .setLabel(title ?: lang?.let { java.util.Locale.forLanguageTag(it).displayLanguage } ?: "音轨 ${audioStreams.size + 1}")
                            .setSampleMimeType(aMime)
                            .setCodecs(codecName.ifBlank { null })
                            .build()
                        if (!foundAudio) aFmt = af
                        audioStreams += ProbedAudioStream(idx, lang, title, af)
                        groups.add(Tracks.Group(TrackGroup("a$idx", af), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(!foundAudio)))
                        foundAudio = true
                    } else if (stype == "subtitle") {
                        val codecName = s.optString("codec_name", "")
                        val (mime, imageBased) = EmbeddedSubtitles.codecToMime(codecName)
                        val forced = s.optJSONObject("disposition")?.optInt("forced", 0) == 1
                        val safeMime = mime ?: MimeTypes.TEXT_UNKNOWN
                        val sf = Format.Builder()
                            .setId("s$idx")
                            .setLanguage(lang)
                            .setLabel(title)
                            .setSampleMimeType(safeMime)
                            .setCodecs(safeMime)
                            .setSelectionFlags(if (forced) C.SELECTION_FLAG_FORCED else 0)
                            .build()
                        subtitleStreams += ProbedSubtitleStream(
                            streamIdx = idx,
                            subOrdinal = subOrdinal++,
                            language = lang,
                            label = title,
                            codecMime = safeMime,
                            imageBased = imageBased,
                            forced = forced,
                            format = sf,
                        )
                        groups.add(Tracks.Group(TrackGroup("s$idx", sf), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false)))
                    }
                }
            }
            if (!foundVideo && !foundAudio) return null
            ProbedStreamMeta(
                srcWidth = srcW,
                srcHeight = srcH,
                hasVideo = foundVideo,
                hasAudio = foundAudio,
                fps = fps,
                durationMs = durMs,
                isHdrOrDv = isHdr,
                dvProfile = dvProf,
                videoFormat = vFmt,
                audioFormat = aFmt,
                audioStreams = audioStreams,
                subtitleStreams = subtitleStreams,
                trackGroups = groups,
            ).also { meta ->
                if (probeMetaCache.size > 64) probeMetaCache.clear()
                probeMetaCache[cacheKey] = meta
                probeMetaCache[url] = meta
            }
        }.getOrNull()
    }

    internal data class ColorPipelineProbe(
        val dvProfile: Int?,
        val isHdrOrWideGamut: Boolean,
        val parsedTrackHeader: Boolean = false,
    )

    private fun probeColorPipelineRequirementQuick(
        url: String,
        headers: Map<String, String>,
        titleHint: String?,
    ): ColorPipelineProbe? {
        if (isCurrentMediaItemLive) return null
        val lowerUrl = url.lowercase()
        if (lowerUrl.contains(".m3u8") || lowerUrl.contains("/live/")) return null

        val rawUriStr = currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
        val cacheKey = rawUriStr.ifBlank { url }
        colorSniffCache[cacheKey]?.let { return it }
        probeMetaCache[cacheKey]?.let { meta ->
            return ColorPipelineProbe(meta.dvProfile, meta.isHdrOrDv, true).also { colorSniffCache[cacheKey] = it }
        }

        val titleLooksDv = com.aisub.player.media.DolbyVisionHelper.looksLikeDolbyVisionTitle(titleHint ?: url)
        val titleLooksHdr = com.aisub.player.media.DolbyVisionHelper.looksLikeHdrOrDolbyTitle(titleHint ?: url)
        val isHttp = lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")
        val isCloudStrict = CloudRegistry.isCloud(rawUriStr)

        // 1. 进程内零冲突 256KB 容器头 + MP4 精确 mdat->moov 跳转二进制嗅探（同时识别杜比视界与 HDR10/HLG/BT.2020）：
        val sniffed = if (!isHttp) {
            sniffLocalFileColorRequirement(url)
        } else {
            sniffHttpColorRequirementBounded(url, headers)
        }
        if (sniffed != null && (sniffed.dvProfile != null || sniffed.isHdrOrWideGamut)) {
            dvSniffCache[cacheKey] = sniffed.dvProfile ?: 0
            colorSniffCache[cacheKey] = sniffed
            return sniffed
        }

        // 2. 本地文件、标题含 HDR/DV 提示、或非严格单连接网盘且容器头未解析出明确 SDR 轨道元数据时（如 TS/M2TS 封装），用 ffprobe 快速确认
        val needFfprobe = !isHttp || titleLooksHdr || (!isCloudStrict && sniffed?.parsedTrackHeader != true)
        if (needFfprobe) {
            if (DesktopBinaryFinder.findFfprobe() == null && titleLooksHdr) {
                DesktopBinaryFinder.ensureFfmpeg()
            }
            val probed = probeMediaStreamsCached(url, headers, timeoutSec = if (isHttp) 4L else 3L)
            if (probed != null) {
                val res = ColorPipelineProbe(
                    dvProfile = probed.dvProfile ?: if (titleLooksDv) 5 else null,
                    isHdrOrWideGamut = probed.isHdrOrDv || titleLooksHdr,
                    parsedTrackHeader = true,
                )
                dvSniffCache[cacheKey] = res.dvProfile ?: 0
                colorSniffCache[cacheKey] = res
                return res
            } else if (titleLooksHdr) {
                val res = ColorPipelineProbe(dvProfile = if (titleLooksDv) 5 else null, isHdrOrWideGamut = true)
                colorSniffCache[cacheKey] = res
                return res
            }
        }

        val sdr = ColorPipelineProbe(null, false, sniffed?.parsedTrackHeader == true)
        dvSniffCache[cacheKey] = 0
        colorSniffCache[cacheKey] = sdr
        return sdr
    }

    private fun sniffLocalFileColorRequirement(path: String): ColorPipelineProbe? = runCatching {
        val f = File(path)
        if (!f.isFile || f.length() < 32L) return null
        val chunkSize = 262_144
        val buf = ByteArray(chunkSize)
        RandomAccessFile(f, "r").use { raf ->
            val fileLen = f.length()
            val headRead = raf.read(buf, 0, minOf(chunkSize.toLong(), fileLen).toInt())
            if (headRead <= 16) return null
            DesktopExoPlayer.scanDolbyVisionProfileFromBytes(buf, headRead)?.let {
                return ColorPipelineProbe(it, true, true)
            }
            if (DesktopExoPlayer.scanHdrOrWideGamutFromBytes(buf, headRead)) {
                return ColorPipelineProbe(null, true, true)
            }
            val isMkv = (buf[0].toInt() and 0xFF) == 0x1A && (buf[1].toInt() and 0xFF) == 0x45 &&
                (buf[2].toInt() and 0xFF) == 0xDF && (buf[3].toInt() and 0xFF) == 0xA3
            if (isMkv) {
                val conclusiveSdr = DesktopExoPlayer.containsMkvTracksElement(buf, headRead) &&
                    !DesktopExoPlayer.has10BitMkvWithoutTransferTag(buf, headRead)
                return ColorPipelineProbe(null, false, conclusiveSdr)
            }
            val isMp4 = buf[4] == 'f'.code.toByte() && buf[5] == 't'.code.toByte() &&
                buf[6] == 'y'.code.toByte() && buf[7] == 'p'.code.toByte()
            if (!isMp4) return ColorPipelineProbe(null, false, false)

            val hasStsdInHead = DesktopExoPlayer.containsAsciiFourCc(buf, headRead, 's', 't', 's', 'd')
            if (hasStsdInHead) {
                val conclusiveSdr = !DesktopExoPlayer.has10BitCodecWithoutColrBox(buf, headRead)
                return ColorPipelineProbe(null, false, conclusiveSdr)
            }

            // 非 faststart MP4（如 77.76GB 120fps 大文件，moov 长达数 MB~十几 MB 位于 mdat 之后）：
            // 解析顶层 ftyp + mdat 64位长度直接定位到 moov 起始处读取 stsd，避免只读末尾 192KB 落在 co64 偏移表里漏检
            val moovPos = DesktopExoPlayer.findMp4MoovOffset(buf, headRead, fileLen)
            if (moovPos != null && moovPos in 16L until fileLen) {
                raf.seek(moovPos)
                val moovRead = raf.read(buf, 0, minOf(chunkSize.toLong(), fileLen - moovPos).toInt())
                if (moovRead > 16) {
                    DesktopExoPlayer.scanDolbyVisionProfileFromBytes(buf, moovRead)?.let { return ColorPipelineProbe(it, true, true) }
                    if (DesktopExoPlayer.scanHdrOrWideGamutFromBytes(buf, moovRead)) return ColorPipelineProbe(null, true, true)
                    // 若第 1 条 trak 为音轨且体积超过 256KB，继续跳到第 2 条 trak（视频轨）起始处读取 stsd
                    val nextTrak = DesktopExoPlayer.findSecondMp4TrakOffsetIfNeeded(buf, moovRead, moovPos, fileLen)
                    if (nextTrak != null) {
                        raf.seek(nextTrak)
                        val trakRead = raf.read(buf, 0, minOf(chunkSize.toLong(), fileLen - nextTrak).toInt())
                        if (trakRead > 16) {
                            DesktopExoPlayer.scanDolbyVisionProfileFromBytes(buf, trakRead)?.let { return ColorPipelineProbe(it, true, true) }
                            if (DesktopExoPlayer.scanHdrOrWideGamutFromBytes(buf, trakRead)) return ColorPipelineProbe(null, true, true)
                        }
                    }
                    if (DesktopExoPlayer.containsAsciiFourCc(buf, moovRead, 's', 't', 's', 'd')) {
                        val conclusiveSdr = !DesktopExoPlayer.has10BitCodecWithoutColrBox(buf, moovRead)
                        return ColorPipelineProbe(null, false, conclusiveSdr)
                    }
                }
            }
            if (fileLen > chunkSize * 2L) {
                raf.seek(fileLen - chunkSize)
                val tailRead = raf.read(buf, 0, chunkSize)
                if (tailRead > 16) {
                    DesktopExoPlayer.scanDolbyVisionProfileFromBytes(buf, tailRead)?.let { return ColorPipelineProbe(it, true, true) }
                    if (DesktopExoPlayer.scanHdrOrWideGamutFromBytes(buf, tailRead)) return ColorPipelineProbe(null, true, true)
                }
            }
            ColorPipelineProbe(null, false, false)
        }
    }.getOrNull()

    private fun sniffHttpColorRequirementBounded(url: String, headers: Map<String, String>): ColorPipelineProbe? = runCatching {
        val chunkSize = 262_144
        val headResult = fetchBoundedHttpRange(url, headers, 0L, chunkSize.toLong() - 1L, timeoutMs = 1800) ?: return null
        val (headBuf, headLen, totalSize) = headResult
        if (headLen <= 16) return null
        DesktopExoPlayer.scanDolbyVisionProfileFromBytes(headBuf, headLen)?.let {
            return ColorPipelineProbe(it, true, true)
        }
        if (DesktopExoPlayer.scanHdrOrWideGamutFromBytes(headBuf, headLen)) {
            return ColorPipelineProbe(null, true, true)
        }
        val isMkv = (headBuf[0].toInt() and 0xFF) == 0x1A && (headBuf[1].toInt() and 0xFF) == 0x45 &&
            (headBuf[2].toInt() and 0xFF) == 0xDF && (headBuf[3].toInt() and 0xFF) == 0xA3
        if (isMkv) {
            val conclusiveSdr = DesktopExoPlayer.containsMkvTracksElement(headBuf, headLen) &&
                !DesktopExoPlayer.has10BitMkvWithoutTransferTag(headBuf, headLen)
            return ColorPipelineProbe(null, false, conclusiveSdr)
        }
        val isMp4 = headBuf[4] == 'f'.code.toByte() && headBuf[5] == 't'.code.toByte() &&
            headBuf[6] == 'y'.code.toByte() && headBuf[7] == 'p'.code.toByte()
        if (!isMp4) return ColorPipelineProbe(null, false, false)
        if (DesktopExoPlayer.containsAsciiFourCc(headBuf, headLen, 's', 't', 's', 'd')) {
            val conclusiveSdr = !DesktopExoPlayer.has10BitCodecWithoutColrBox(headBuf, headLen)
            return ColorPipelineProbe(null, false, conclusiveSdr)
        }

        if (totalSize > chunkSize) {
            val moovPos = DesktopExoPlayer.findMp4MoovOffset(headBuf, headLen, totalSize)
            if (moovPos != null && moovPos in 16L until totalSize) {
                val moovEnd = minOf(totalSize - 1L, moovPos + chunkSize - 1L)
                val moovRes = fetchBoundedHttpRange(url, headers, moovPos, moovEnd, timeoutMs = 1500)
                if (moovRes != null && moovRes.second > 16) {
                    val (mBuf, mLen, _) = moovRes
                    DesktopExoPlayer.scanDolbyVisionProfileFromBytes(mBuf, mLen)?.let { return ColorPipelineProbe(it, true, true) }
                    if (DesktopExoPlayer.scanHdrOrWideGamutFromBytes(mBuf, mLen)) return ColorPipelineProbe(null, true, true)
                    val nextTrak = DesktopExoPlayer.findSecondMp4TrakOffsetIfNeeded(mBuf, mLen, moovPos, totalSize)
                    if (nextTrak != null) {
                        val tEnd = minOf(totalSize - 1L, nextTrak + chunkSize - 1L)
                        val trakRes = fetchBoundedHttpRange(url, headers, nextTrak, tEnd, timeoutMs = 1500)
                        if (trakRes != null && trakRes.second > 16) {
                            DesktopExoPlayer.scanDolbyVisionProfileFromBytes(trakRes.first, trakRes.second)?.let { return ColorPipelineProbe(it, true, true) }
                            if (DesktopExoPlayer.scanHdrOrWideGamutFromBytes(trakRes.first, trakRes.second)) return ColorPipelineProbe(null, true, true)
                        }
                    }
                    if (DesktopExoPlayer.containsAsciiFourCc(mBuf, mLen, 's', 't', 's', 'd')) {
                        val conclusiveSdr = !DesktopExoPlayer.has10BitCodecWithoutColrBox(mBuf, mLen)
                        return ColorPipelineProbe(null, false, conclusiveSdr)
                    }
                }
            } else if (totalSize > chunkSize * 2L) {
                val tailStart = totalSize - chunkSize
                val tailResult = fetchBoundedHttpRange(url, headers, tailStart, totalSize - 1L, timeoutMs = 1500)
                if (tailResult != null && tailResult.second > 16) {
                    DesktopExoPlayer.scanDolbyVisionProfileFromBytes(tailResult.first, tailResult.second)?.let { return ColorPipelineProbe(it, true, true) }
                    if (DesktopExoPlayer.scanHdrOrWideGamutFromBytes(tailResult.first, tailResult.second)) return ColorPipelineProbe(null, true, true)
                }
            }
        }
        ColorPipelineProbe(null, false, false)
    }.getOrNull()

    private fun fetchBoundedHttpRange(
        url: String,
        headers: Map<String, String>,
        startByte: Long,
        endByteInclusive: Long,
        timeoutMs: Int,
    ): Triple<ByteArray, Int, Long>? {
        val isLoopback = url.startsWith("http://127.0.0.1:", ignoreCase = true) ||
            url.startsWith("http://localhost:", ignoreCase = true)
        val conn = (if (isLoopback) URL(url).openConnection(Proxy.NO_PROXY) else URL(url).openConnection()) as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Range", "bytes=$startByte-$endByteInclusive")
            conn.setRequestProperty("Connection", "close")
            for ((k, v) in headers) {
                conn.setRequestProperty(k, v)
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            val contentRange = conn.getHeaderField("Content-Range").orEmpty()
            val totalSize = contentRange.substringAfter('/', "").trim().toLongOrNull() ?: -1L
            val expectedMax = (endByteInclusive - startByte + 1L).coerceIn(1L, 262_144L).toInt()
            val buf = ByteArray(expectedMax)
            var readTotal = 0
            conn.inputStream.use { input ->
                while (readTotal < expectedMax) {
                    val n = input.read(buf, readTotal, expectedMax - readTotal)
                    if (n < 0) break
                    readTotal += n
                    if (!isLoopback && n > 0) {
                        DesktopPlayerEngine.recordNetworkBytes(n.toLong())
                    }
                }
            }
            Triple(buf, readTotal, totalSize)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun parseDvProfileFromFfprobeJson(jsonText: String): Int? {
        if (jsonText.isBlank()) return null
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val streams = root.optJSONArray("streams") ?: return null
        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            val sideList = s.optJSONArray("side_data_list")
            if (sideList != null) {
                for (j in 0 until sideList.length()) {
                    val sd = sideList.getJSONObject(j)
                    if (sd.has("dv_profile")) {
                        val p = sd.optInt("dv_profile", -1)
                        if (p > 0) return p
                    }
                }
            }
            val tag = s.optString("codec_tag_string").lowercase()
            if (tag in setOf("dvh1", "dvhe", "dav1", "dvav", "dva1")) return 5
            val cs = s.optString("color_space").lowercase()
            if ("ipt" in cs || "dolby" in cs) return 5
        }
        return null
    }

    private fun buildFfmpegInputArgs(
        ffmpeg: String,
        url: String,
        headers: Map<String, String>,
        startPosMs: Long,
        useVulkanDevice: Boolean = false,
        useD3d11Hwdec: Boolean = false,
    ): MutableList<String> {
        val cmd = mutableListOf(ffmpeg, "-v", "error")
        if (useD3d11Hwdec) {
            cmd.addAll(listOf("-init_hw_device", "d3d11va=d3d"))
        }
        if (useVulkanDevice) {
            cmd.addAll(listOf("-init_hw_device", "vulkan=vk", "-filter_hw_device", "vk"))
        }
        if (url.startsWith("http://127.0.0.1:", ignoreCase = true) || url.startsWith("http://localhost:", ignoreCase = true)) {
            cmd.addAll(listOf("-http_proxy", ""))
        }
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
        if (useD3d11Hwdec) {
            cmd.addAll(listOf("-hwaccel", "d3d11va", "-hwaccel_device", "d3d"))
        }
        cmd.addAll(listOf("-i", url))
        return cmd
    }

    internal fun buildColorMappedVfFilter(
        width: Int,
        height: Int,
        mode: Int,
        sourceFps: Double = 24.0,
    ): String {
        val targetFps = computeTargetRenderFps(sourceFps)
        // 对 120fps / 119.88fps 高帧率片源在上传 GPU 之前先执行整数倍抽帧（如 119.88 -> 59.94），直接减半 PCIe 上传、着色与读回带宽
        val fpsPrefix = if (sourceFps > 60.5) "fps=%.6f,".format(java.util.Locale.US, targetFps) else ""
        return when (mode) {
            // Mode 1: 高速单趟 Vulkan GPU libplacebo 色彩与动态色调映射：
            // 显式通过 hwupload 上传解码帧（含杜比视界 RPU 与 HDR10 元数据）至 Vulkan 并在 GPU 缩放映射后 hwdownload,format=bgra 读回，
            // 修复裸调 libplacebo 因缺少 AV_PIX_FMT_VULKAN 输入/输出转换导致滤镜协商失败退回纯 CPU 浮点 zscale 卡顿的问题。
            1 -> "${fpsPrefix}hwupload,libplacebo=w=$width:h=$height:upscaler=bilinear:downscaler=bilinear:peak_detect=false:sigmoid=false:apply_filmgrain=false:dithering=ordered_fixed:tonemapping=bt.2390:colorspace=bt709:color_primaries=bt709:color_trc=iec61966-2-1:apply_dolbyvision=true:format=bgra,hwdownload,format=bgra"
            // Mode 2: 极少数无 Vulkan 驱动机器上的轻量 CPU zscale HDR 色调映射兜底
            2 -> "${fpsPrefix}zscale=w=$width:h=$height:t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=bt2390:desat=0,zscale=t=bt709:m=bt709:r=tv,format=bgra"
            // Mode 0: 标准 SDR 快速缩放管线
            else -> "${fpsPrefix}scale=$width:$height:flags=fast_bilinear"
        }
    }

    @JvmOverloads
    private fun startWithFfmpeg(
        gen: Int,
        url: String,
        headers: Map<String, String>,
        startPosMs: Long,
        preferLibplacebo: Boolean = false,
    ) {
        if (gen != mediaGeneration.get()) return
        ffmpegRunning.set(true)

        // 仅对严格单连接非媒体服务器网盘（如 115 / 百度 / 阿里云盘）挂载 MediaProxy，Plex/Emby/Jellyfin 直接直连以跑满局域网与磁盘 Remux 带宽
        var effectiveUrl = url
        var effectiveHeaders = headers
        val dsFactory = dataSourceFactory
        if (!isCurrentMediaItemLive && isDirectRemoteNetworkUrl(url) && dsFactory != null) {
            val item = currentMediaItem
            val itemUri = item?.localConfiguration?.uri
            val itemUriStr = itemUri?.toString().orEmpty()
            val strictCloudNetdisk = if (CloudRegistry.isCloud(itemUriStr)) {
                val srv = itemUri?.host?.let(CloudRegistry.serverLookup)
                srv != null && !srv.type.isMediaServer
            } else {
                false
            }
            if (strictCloudNetdisk && itemUri != null) {
                val itemName = item.mediaMetadata.title?.toString() ?: itemUri.lastPathSegment ?: "video.mp4"
                runCatching {
                    val p = proxy ?: MediaProxy(dsFactory).also { proxy = it }
                    effectiveUrl = p.register(itemUri, itemName)
                    effectiveHeaders = emptyMap()
                }
            }
        }

        Log.i(TAG, "启动 FFmpeg/libplacebo 播放引擎: url=${redactUrlForLog(effectiveUrl)}, startPosMs=$startPosMs, preferLibplacebo=$preferLibplacebo")
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

            val probed = probeMediaStreamsCached(effectiveUrl, effectiveHeaders, timeoutSec = 8L)
            if (gen != mediaGeneration.get() || !ffmpegRunning.get()) return@submit

            val srcWidth = probed?.srcWidth ?: 1280
            val srcHeight = probed?.srcHeight ?: 720
            val hasVideo = probed?.hasVideo ?: true
            val hasAudio = probed?.hasAudio ?: true
            val fps = probed?.fps ?: 24.0
            val dvProfile = probed?.dvProfile ?: detectedDolbyProfile
            if (dvProfile != null) detectedDolbyProfile = dvProfile
            val isHdrOrDv = preferLibplacebo || (probed?.isHdrOrDv == true) || dvProfile != null

            if (probed != null) {
                lastProbedMeta = probed
                if (probed.durationMs != C.TIME_UNSET && probed.durationMs > 0) {
                    duration = probed.durationMs
                }
                probed.videoFormat?.let { videoFormat = it }
                probed.audioFormat?.let { audioFormat = it }
                selectedFfmpegAudioStreamSpec = resolveSelectedFfmpegAudioStreamSpec(trackSelectionParameters, probed)
                selectedFfmpegSubStreamIdx = resolveSelectedFfmpegSubStreamIdx(trackSelectionParameters, probed.subtitleStreams)
                selectedFfmpegBitmapSubIdx = probed.subtitleStreams
                    .firstOrNull { it.streamIdx == selectedFfmpegSubStreamIdx && it.imageBased }?.streamIdx ?: -1
                rebuildFfmpegTracksAndNotify(probed)
            } else {
                lastProbedMeta = null
                selectedFfmpegSubStreamIdx = -1
                selectedFfmpegBitmapSubIdx = -1
            }

            val vkKnownUnsupported = DesktopPlayerEngine.vulkanPlaceboSupported == false
            val usePlacebo = (preferLibplacebo || isHdrOrDv) && !vkKnownUnsupported
            val initialFilterMode = when {
                usePlacebo -> 1
                isHdrOrDv && dvProfile != 5 -> 2
                else -> 0
            }

            val maxW = if (initialFilterMode == 2) 1280 else DesktopPlayerEngine.displayWidthPx.coerceIn(1280, 1920)
            val maxH = if (initialFilterMode == 2) 720 else DesktopPlayerEngine.displayHeightPx.coerceIn(720, 1080)
            val scale = minOf(1.0, maxW.toDouble() / srcWidth.toDouble(), maxH.toDouble() / srcHeight.toDouble())
            // 16 字节行对齐以加速 Vulkan GPU 读回与 Skia 纹理拷贝
            val width = ((((srcWidth * scale).roundToInt() + 8) / 16) * 16).coerceAtLeast(16)
            val height = (((srcHeight * scale).roundToInt() / 2) * 2).coerceAtLeast(2)

            val wantD3d11Hwdec = IS_WINDOWS_HOST && (hwdecPreference != false)
            val hwLabel = if (wantD3d11Hwdec) "D3D11VA 硬解 + " else "FFmpeg 软解 + "
            activePipelineDescription = when {
                initialFilterMode == 1 && dvProfile != null -> "${hwLabel}libplacebo Vulkan · 杜比视界 P$dvProfile → BT.709"
                initialFilterMode == 1 -> "${hwLabel}libplacebo Vulkan · HDR 动态色调映射"
                initialFilterMode == 2 -> "FFmpeg zscale CPU HDR 色调映射"
                wantD3d11Hwdec -> "D3D11VA 硬件加速渲染管线"
                else -> "FFmpeg 软解渲染管线"
            }

            val subtitleStreams = probed?.subtitleStreams.orEmpty()
            if (hasVideo) {
                val sz = VideoSize(srcWidth, srcHeight)
                videoSize = sz
                notifyMain {
                    listeners.forEach { it.onVideoSizeChanged(sz) }
                }
                if (hasAudio && selectedFfmpegAudioStreamSpec.isNotEmpty()) {
                    startFfmpegMuxedPlayback(
                        gen = gen,
                        ffmpeg = ffmpeg,
                        url = effectiveUrl,
                        headers = effectiveHeaders,
                        startPosMs = startPosMs,
                        width = width,
                        height = height,
                        fps = fps,
                        filterMode = initialFilterMode,
                        useD3d11Hwdec = wantD3d11Hwdec,
                        isHdrFallbackCandidate = isHdrOrDv && dvProfile != 5,
                        subtitleStreams = subtitleStreams,
                    )
                } else {
                    startFfmpegVideoThread(
                        gen = gen,
                        ffmpeg = ffmpeg,
                        url = effectiveUrl,
                        headers = effectiveHeaders,
                        startPosMs = startPosMs,
                        width = width,
                        height = height,
                        fps = fps,
                        filterMode = initialFilterMode,
                        useD3d11Hwdec = wantD3d11Hwdec,
                        subtitleStreams = subtitleStreams,
                    )
                }
            } else {
                startFfmpegAudioThread(gen, ffmpeg, effectiveUrl, effectiveHeaders, startPosMs, markReadyWithoutVideo = true, endWhenDone = true)
            }
        }
    }

    private fun buildFfmpegAudioFilter(speed: Float): String {
        val filters = ArrayList<String>(4)
        filters.add("aresample=async=1:first_pts=0")
        val safeSpeed = speed.coerceIn(0.25f, 4.0f)
        if (kotlin.math.abs(safeSpeed - 1.0f) > 0.01f) {
            var rem = safeSpeed.toDouble()
            while (rem > 2.0) {
                filters.add("atempo=2.0")
                rem /= 2.0
            }
            while (rem < 0.5) {
                filters.add("atempo=0.5")
                rem /= 0.5
            }
            if (kotlin.math.abs(rem - 1.0) > 0.005) {
                filters.add("atempo=%.4f".format(java.util.Locale.US, rem))
            }
        }
        return filters.joinToString(",")
    }

    private fun openFfmpegTextSubtitleBindings(
        textSubs: List<ProbedSubtitleStream>,
    ): List<Pair<ProbedSubtitleStream, ServerSocket>> {
        if (textSubs.isEmpty()) return emptyList()
        val list = ArrayList<Pair<ProbedSubtitleStream, ServerSocket>>(textSubs.size)
        for (sub in textSubs.take(6)) {
            runCatching {
                val srv = ServerSocket().apply {
                    receiveBufferSize = 65_536
                    bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
                }
                ffmpegSubServers.add(srv)
                list += sub to srv
            }
        }
        return list
    }

    private fun appendFfmpegVideoFilterAndOutputArgs(
        cmd: MutableList<String>,
        vfStr: String,
        width: Int,
        height: Int,
        fps: Double,
        fpsStr: String,
        bitmapSubIdx: Int,
    ) {
        if (bitmapSubIdx >= 0) {
            cmd.addAll(
                listOf(
                    "-filter_complex",
                    "[0:v:0]$vfStr[vbase];[0:$bitmapSubIdx]scale=$width:$height[sub];[vbase][sub]overlay=eof_action=pass:shortest=0,format=bgra[vout]",
                    "-map", "[vout]",
                ),
            )
        } else {
            cmd.addAll(listOf("-map", "0:v:0", "-vf", vfStr))
        }
        if (fps <= 60.5) {
            cmd.addAll(listOf("-r", fpsStr))
        }
        cmd.addAll(listOf("-pix_fmt", "bgra", "-f", "rawvideo", "-"))
    }

    private fun launchFfmpegTextSubtitleReaders(
        gen: Int,
        proc: Process,
        bindings: List<Pair<ProbedSubtitleStream, ServerSocket>>,
        startPosMs: Long,
    ) {
        for ((sub, srv) in bindings) {
            ioPool.submit {
                try {
                    val sock = acceptLoopbackWhileProcAlive(srv, proc, gen, 20_000L).apply {
                        tcpNoDelay = true
                    }
                    runCatching { srv.close() }
                    ffmpegSubServers.remove(srv)
                    val cueList = ffmpegSubCueMap.getOrPut(sub.streamIdx) { CopyOnWriteArrayList() }
                    sock.use { s ->
                        BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).use { reader ->
                            val block = ArrayList<String>(4)
                            fun flushBlock() {
                                if (block.isEmpty()) return
                                val parsed = EmbeddedSubtitles.parseSrtBlock(block)
                                block.clear()
                                if (parsed != null) {
                                    val absStart = startPosMs + parsed.startMs
                                    val dur = (parsed.endMs - parsed.startMs).let { if (it > 0L) it else 3500L }
                                    val absEnd = absStart + dur
                                    val absCue = parsed.copy(startMs = absStart, endMs = absEnd)
                                    val dup = cueList.any { existing ->
                                        kotlin.math.abs(existing.startMs - absStart) <= 25L &&
                                            existing.text == absCue.text &&
                                            existing.translation == absCue.translation
                                    }
                                    if (!dup) {
                                        cueList.add(absCue)
                                    }
                                    EmbeddedSubtitles.recordExternalCue(
                                        trackId = sub.streamIdx,
                                        language = sub.language,
                                        label = sub.label,
                                        codec = sub.codecMime,
                                        startMs = absStart,
                                        endMs = absEnd,
                                        lines = listOfNotNull(parsed.text, parsed.translation),
                                    )
                                    val curSel = selectedFfmpegSubStreamIdx
                                    if (sub.streamIdx != curSel &&
                                        curSel >= 0 &&
                                        !sub.forced &&
                                        trackSelectionParameters.overrides.values.none { it.type == C.TRACK_TYPE_TEXT } &&
                                        ffmpegSubCueMap[curSel].isNullOrEmpty()
                                    ) {
                                        val curMeta = lastProbedMeta?.subtitleStreams?.firstOrNull { it.streamIdx == curSel }
                                        if (curMeta != null && curMeta.language == sub.language) {
                                            selectedFfmpegSubStreamIdx = sub.streamIdx
                                            lastProbedMeta?.let { rebuildFfmpegTracksAndNotify(it) }
                                        }
                                    }
                                    if (sub.streamIdx == selectedFfmpegSubStreamIdx) {
                                        updateFfmpegRenderedSubtitle(currentPosition)
                                    }
                                }
                            }
                            try {
                                while (ffmpegRunning.get() && gen == mediaGeneration.get()) {
                                    val line = reader.readLine() ?: break
                                    if (line.isBlank()) {
                                        flushBlock()
                                    } else {
                                        block += line
                                    }
                                }
                            } catch (_: Throwable) {
                            } finally {
                                flushBlock()
                            }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    runCatching { srv.close() }
                    ffmpegSubServers.remove(srv)
                }
            }
        }
    }

    private fun captureProcessStderrTail(proc: Process): AtomicReference<String> {
        val tail = AtomicReference("")
        Thread({
            runCatching {
                val sb = StringBuilder()
                proc.errorStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() ||
                            "Failed to send close message" in trimmed ||
                            "Discarding stream metadata" in trimmed ||
                            "Last message repeated" in trimmed
                        ) {
                            continue
                        }
                        if (sb.isNotEmpty()) sb.append(" | ")
                        sb.append(trimmed)
                        if (sb.length > 600) {
                            sb.delete(0, sb.length - 400)
                        }
                        tail.set(sb.toString().takeLast(400))
                    }
                }
            }
        }, "Voxtitle-FfmpegStderr").apply {
            isDaemon = true
            start()
        }
        return tail
    }

    private fun acceptLoopbackWhileProcAlive(
        server: ServerSocket,
        proc: Process?,
        gen: Int,
        timeoutMs: Long = 20_000L,
    ): java.net.Socket {
        server.soTimeout = 200
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!ffmpegRunning.get() || gen != mediaGeneration.get()) {
                throw java.io.IOException("Playback cancelled")
            }
            try {
                return server.accept()
            } catch (_: java.net.SocketTimeoutException) {
                if (proc != null && !proc.isAlive) {
                    // Check once with 5ms timeout in case a loopback connection arrived right before process exit
                    server.soTimeout = 5
                    try {
                        return server.accept()
                    } catch (_: java.net.SocketTimeoutException) {
                        throw java.io.IOException("FFmpeg exited early (code=${proc.exitValue()})")
                    }
                }
            }
        }
        throw java.net.SocketTimeoutException("Accept timed out after ${timeoutMs}ms")
    }

    /**
     * 单进程高速匿名管道 (pipe:1) 视频 + 本机回环 PCM 音频 + 多轨字幕同步解复用引擎：
     * - 视频帧通过 OS 匿名管道直传 (70+ fps)，彻底绕开 Windows WFP/Defender 对回环高码率原始像素流的限速 (~16.9fps 卡顿)；
     * - 音频通过 `-af aresample=async=1:first_pts=0` + 首帧门控对齐 (`renderedFirstFrame`) 与倍速 `atempo` 确保音画毫秒级同步；
     * - 文本字幕同步解复用至轻量化本机套接字，支持 MPV 模式直接显示、0ms 无重启切轨与实时翻译。
     */
    private fun startFfmpegMuxedPlayback(
        gen: Int,
        ffmpeg: String,
        url: String,
        headers: Map<String, String>,
        startPosMs: Long,
        width: Int,
        height: Int,
        fps: Double,
        filterMode: Int = 0,
        useD3d11Hwdec: Boolean = false,
        isHdrFallbackCandidate: Boolean = false,
        subtitleStreams: List<ProbedSubtitleStream> = emptyList(),
    ) {
        ioPool.submit {
            if (gen != mediaGeneration.get() || !ffmpegRunning.get()) return@submit
            val safeFps = computeTargetRenderFps(fps)
            val fpsStr = "%.6f".format(java.util.Locale.US, safeFps)

            val audioServer = runCatching {
                ServerSocket().apply {
                    receiveBufferSize = 1024 * 1024
                    bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
                }
            }.getOrElse { e ->
                Log.e(TAG, "FFmpeg 本地音频套接字创建失败，改用纯视频模式", e)
                startFfmpegVideoThread(gen, ffmpeg, url, headers, startPosMs, width, height, fps, filterMode, useD3d11Hwdec, subtitleStreams)
                return@submit
            }
            ffmpegVideoServer = null
            ffmpegAudioServer = audioServer

            val subBindings = openFfmpegTextSubtitleBindings(subtitleStreams.filter { !it.imageBased })
            val bitmapSubIdx = selectedFfmpegBitmapSubIdx
            val curSpeed = playbackParameters.speed.coerceIn(0.25f, 4.0f)
            val afStr = buildFfmpegAudioFilter(curSpeed)
            val hasLiveAudio = AtomicBoolean(true)
            val audioDoneLatch = CountDownLatch(1)
            val vfStr = buildColorMappedVfFilter(width, height, filterMode, fps)
            val audioMapSpec = selectedFfmpegAudioStreamSpec.ifEmpty { "0:a:0?" }
            val cmd = buildFfmpegInputArgs(
                ffmpeg = ffmpeg,
                url = url,
                headers = headers,
                startPosMs = startPosMs,
                useVulkanDevice = filterMode == 1,
                useD3d11Hwdec = useD3d11Hwdec,
            ).apply {
                addAll(listOf("-threads", "0", "-filter_threads", "0"))
                appendFfmpegVideoFilterAndOutputArgs(this, vfStr, width, height, fps, fpsStr, bitmapSubIdx)
                addAll(
                    listOf(
                        "-map", audioMapSpec, "-vn",
                        "-af", afStr,
                        "-ac", "2", "-ar", "48000",
                        "-f", "s16le", "tcp://127.0.0.1:${audioServer.localPort}?send_buffer_size=1048576&tcp_nodelay=1",
                    ),
                )
                for ((sub, srv) in subBindings) {
                    addAll(
                        listOf(
                            "-map", "0:${sub.streamIdx}?",
                            "-c:s", "srt",
                            "-flush_packets", "1",
                            "-f", "srt",
                            "tcp://127.0.0.1:${srv.localPort}?tcp_nodelay=1",
                        ),
                    )
                }
            }

            var framesRead = 0L
            val stderrTailRef = AtomicReference("")
            var procTail: AtomicReference<String>? = null
            try {
                val proc = ProcessBuilder(cmd).start()
                procTail = captureProcessStderrTail(proc)
                ffmpegVideoProc = proc
                ffmpegAudioProc = null

                ioPool.submit {
                    var preOpenedLine: SourceDataLine? = null
                    try {
                        val pcmFormat = AudioFormat(48000f, 16, 2, true, false)
                        preOpenedLine = runCatching {
                            AudioSystem.getSourceDataLine(pcmFormat)?.apply {
                                open(pcmFormat, 48000 * 4 * 2 / 5)
                            }
                        }.getOrNull()
                        val sock = acceptLoopbackWhileProcAlive(audioServer, proc, gen, 20_000L).apply {
                            receiveBufferSize = 1024 * 1024
                            tcpNoDelay = true
                        }
                        runCatching { audioServer.close() }
                        if (ffmpegAudioServer === audioServer) ffmpegAudioServer = null
                        sock.use { s ->
                            BufferedInputStream(s.getInputStream(), 65_536).use { audioIn ->
                                val handedLine = preOpenedLine
                                preOpenedLine = null
                                pumpPcmToAudioLine(
                                    gen = gen,
                                    ins = audioIn,
                                    startPosMs = startPosMs,
                                    markReadyWithoutVideo = false,
                                    waitForFirstVideoFrame = true,
                                    playbackSpeed = curSpeed,
                                    preOpenedLine = handedLine,
                                )
                            }
                        }
                    } catch (_: Exception) {
                        hasLiveAudio.set(false)
                        ffmpegAudioClockReady = false
                    } finally {
                        runCatching { preOpenedLine?.close() }
                        runCatching { audioServer.close() }
                        if (ffmpegAudioServer === audioServer) ffmpegAudioServer = null
                        audioDoneLatch.countDown()
                    }
                }

                launchFfmpegTextSubtitleReaders(gen, proc, subBindings, startPosMs)

                framesRead = readAndPresentFfmpegVideoRing(
                    gen = gen,
                    ins = proc.inputStream,
                    width = width,
                    height = height,
                    startPosMs = startPosMs,
                    safeFps = safeFps,
                    useAudioClock = { hasLiveAudio.get() && ffmpegAudioClockReady },
                    expectAudio = { hasLiveAudio.get() },
                )
                proc.destroy()
                audioDoneLatch.await(2, TimeUnit.SECONDS)
                stderrTailRef.set(procTail?.get().orEmpty())
            } catch (e: Throwable) {
                runCatching { Thread.sleep(30L) }
                val errDetail = procTail?.get().orEmpty().also { stderrTailRef.set(it) }
                Log.w(TAG, "FFmpeg 管线运行退出 (hwdec=$useD3d11Hwdec, mode=$filterMode, frames=$framesRead): ${e.message}" + if (errDetail.isNotBlank()) " [$errDetail]" else "")
            } finally {
                runCatching { ffmpegVideoProc?.destroy() }
                runCatching { audioServer.close() }
                subBindings.forEach { (_, srv) ->
                    runCatching { srv.close() }
                    ffmpegSubServers.remove(srv)
                }
                if (ffmpegAudioServer === audioServer) ffmpegAudioServer = null
            }

            if (framesRead == 0L && ffmpegRunning.get() && gen == mediaGeneration.get()) {
                // 1) 若 D3D11VA 硬解初始化失败（如老显卡不支持 HEVC Main10 硬解），优先降级为多线程软解 + Vulkan libplacebo（保持杜比视界正常色彩）
                if (useD3d11Hwdec) {
                    Log.w(TAG, "当前显卡不支持 D3D11VA 硬解该流，自动回退至多线程解码 + libplacebo 色彩管线")
                    activePipelineDescription = activePipelineDescription.replace("D3D11VA 硬解 + ", "FFmpeg 多核软解 + ")
                    // 纯 CPU 解码 4K 时将读回分辨率限制在 1600x900 以进一步减轻 CPU 与总线负担
                    val softMaxW = width.coerceAtMost(1600).let { ((it + 8) / 16) * 16 }.coerceAtLeast(16)
                    val softMaxH = if (softMaxW < width) (((height.toDouble() * softMaxW / width).roundToInt() / 2) * 2).coerceAtLeast(2) else height
                    startFfmpegMuxedPlayback(
                        gen = gen,
                        ffmpeg = ffmpeg,
                        url = url,
                        headers = headers,
                        startPosMs = startPosMs,
                        width = softMaxW,
                        height = softMaxH,
                        fps = fps,
                        filterMode = filterMode,
                        useD3d11Hwdec = false,
                        isHdrFallbackCandidate = isHdrFallbackCandidate,
                        subtitleStreams = subtitleStreams,
                    )
                    return@submit
                }
                // 2) 若当前电脑缺少 Vulkan ICD 导致 filterMode=1 未输出任何帧，记录缓存并自动降级至轻量 720p CPU zscale / 标准滤镜续播
                if (filterMode == 1) {
                    DesktopPlayerEngine.vulkanPlaceboSupported = false
                    val nextMode = if (isHdrFallbackCandidate) 2 else 0
                    val fbW = width.coerceAtMost(1280).let { ((it + 8) / 16) * 16 }.coerceAtLeast(16)
                    val fbH = if (fbW < width) (((height.toDouble() * fbW / width).roundToInt() / 2) * 2).coerceAtLeast(2) else height
                    Log.w(TAG, "当前显卡未启用 Vulkan libplacebo，自动切换至 CPU 色调映射管线 (mode=$nextMode, ${fbW}x${fbH})")
                    activePipelineDescription = if (nextMode == 2) "FFmpeg zscale CPU HDR 色调映射" else "FFmpeg 软解渲染管线"
                    startFfmpegMuxedPlayback(gen, ffmpeg, url, headers, startPosMs, fbW, fbH, fps, filterMode = nextMode, useD3d11Hwdec = false, isHdrFallbackCandidate = false, subtitleStreams = subtitleStreams)
                    return@submit
                }
                if (filterMode == 2) {
                    Log.w(TAG, "CPU HDR 色调映射管线未输出帧，回退至标准缩放管线")
                    activePipelineDescription = "FFmpeg 软解渲染管线"
                    startFfmpegMuxedPlayback(gen, ffmpeg, url, headers, startPosMs, width, height, fps, filterMode = 0, useD3d11Hwdec = false, isHdrFallbackCandidate = false, subtitleStreams = subtitleStreams)
                    return@submit
                }
                ffmpegRunning.set(false)
                val detail = stderrTailRef.get().takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                onError(
                    PlaybackException(
                        "MPV/FFmpeg 渲染管线无法打开该媒体流$detail",
                        null,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    ),
                )
                return@submit
            }
            if (ffmpegRunning.get() && gen == mediaGeneration.get() && framesRead > 0L) {
                onItemEnded()
            }
        }
    }

    /**
     * 零分配 12 帧异步环形缓冲器：
     * - 独立读取线程全速排空 FFmpeg 视频匿名管道输出，绝不因 PTS 睡眠阻塞 FFmpeg 内部的音视频复用器；
     * - 独立呈现线程对齐平滑插值音频时钟精准上屏，并实时同步驱动内嵌文本字幕上屏。
     */
    private fun readAndPresentFfmpegVideoRing(
        gen: Int,
        ins: InputStream,
        width: Int,
        height: Int,
        startPosMs: Long,
        safeFps: Double,
        useAudioClock: () -> Boolean,
        expectAudio: () -> Boolean = { false },
    ): Long {
        if (filterModeHintForVulkanMark(activePipelineDescription)) {
            DesktopPlayerEngine.vulkanPlaceboSupported = true
        }
        val frameBytes = width * height * 4
        val ringCap = 12
        val ringBuffers = acquireRingBuffers(ringCap, frameBytes)
        val ringPts = LongArray(ringCap)
        val ringLock = java.util.concurrent.locks.ReentrantLock()
        val notFull = ringLock.newCondition()
        val notEmpty = ringLock.newCondition()
        var writeIdx = 0
        var readIdx = 0
        var queued = 0
        var producerDone = false
        val totalFramesRead = AtomicLong(0L)

        val readerThread = Thread({
            var seq = 0L
            try {
                while (ffmpegRunning.get() && gen == mediaGeneration.get()) {
                    val slotIdx: Int
                    ringLock.lock()
                    try {
                        while (queued >= ringCap && ffmpegRunning.get() && gen == mediaGeneration.get()) {
                            notFull.await(25L, TimeUnit.MILLISECONDS)
                        }
                        if (!ffmpegRunning.get() || gen != mediaGeneration.get()) break
                        slotIdx = writeIdx
                    } finally {
                        ringLock.unlock()
                    }
                    val target = ringBuffers[slotIdx]
                    var off = 0
                    while (off < frameBytes && ffmpegRunning.get() && gen == mediaGeneration.get()) {
                        val n = ins.read(target, off, minOf(65_536, frameBytes - off))
                        if (n < 0) break
                        off += n
                    }
                    if (off < frameBytes) break
                    val ptsMs = startPosMs + (seq * 1000.0 / safeFps).roundToLong()
                    seq++
                    totalFramesRead.incrementAndGet()
                    ringLock.lock()
                    try {
                        ringPts[slotIdx] = ptsMs
                        writeIdx = (writeIdx + 1) % ringCap
                        queued++
                        notEmpty.signal()
                    } finally {
                        ringLock.unlock()
                    }
                }
            } catch (_: Throwable) {
            } finally {
                ringLock.lock()
                try {
                    producerDone = true
                    notEmpty.signalAll()
                } finally {
                    ringLock.unlock()
                }
            }
        }, "Voxtitle-FfmpegVideoRead").apply {
            isDaemon = true
            priority = (Thread.NORM_PRIORITY + 1).coerceAtMost(Thread.MAX_PRIORITY)
            start()
        }

        var wallClockAnchorMs = System.currentTimeMillis()
        var firstFramePresented = false
        var consecutiveDropped = 0
        DesktopPlayerEngine.acquireWindowsHighResTimer()
        try {
            while (ffmpegRunning.get() && gen == mediaGeneration.get()) {
                if (!playWhenReady) {
                    Thread.sleep(20L)
                    wallClockAnchorMs += 20L
                    continue
                }
                val slotIdx: Int
                val framePtsMs: Long
                val remainingAfterPop: Int
                ringLock.lock()
                try {
                    while (queued == 0 && !producerDone && ffmpegRunning.get() && gen == mediaGeneration.get()) {
                        notEmpty.await(20L, TimeUnit.MILLISECONDS)
                    }
                    if (queued == 0) break
                    slotIdx = readIdx
                    framePtsMs = ringPts[slotIdx]
                    remainingAfterPop = queued - 1
                } finally {
                    ringLock.unlock()
                }

                val audioActive = useAudioClock()
                val refClockMs = if (audioActive) {
                    currentFfmpegAudioClockMs()
                } else if (firstFramePresented) {
                    startPosMs + ((System.currentTimeMillis() - wallClockAnchorMs) * playbackParameters.speed).toLong()
                } else {
                    startPosMs
                }

                val lagMs = refClockMs - framePtsMs
                val dropFrame = audioActive && firstFramePresented && remainingAfterPop >= 2 && lagMs > 90L && consecutiveDropped < 2
                if (!dropFrame) {
                    val aheadMs = framePtsMs - refClockMs
                    if (firstFramePresented && aheadMs > 3L) {
                        val maxWaitMs = aheadMs.coerceAtMost(65L)
                        val waitDeadline = System.currentTimeMillis() + maxWaitMs
                        var waitLeft = maxWaitMs
                        while (waitLeft > 2L && playWhenReady && ffmpegRunning.get() && gen == mediaGeneration.get()) {
                            val nowMs = System.currentTimeMillis()
                            if (nowMs >= waitDeadline) break
                            val step = minOf(waitLeft - 1L, waitDeadline - nowMs, 12L).coerceAtLeast(1L)
                            Thread.sleep(step)
                            val nowRef = if (useAudioClock()) {
                                currentFfmpegAudioClockMs()
                            } else {
                                startPosMs + ((System.currentTimeMillis() - wallClockAnchorMs) * playbackParameters.speed).toLong()
                            }
                            waitLeft = minOf(framePtsMs - nowRef, waitDeadline - System.currentTimeMillis())
                        }
                    }
                    if (DesktopPlayerEngine.activePlayer === this@DesktopExoPlayer) {
                        DesktopPlayerEngine.submitFrameBgraBytes(width, height, ringBuffers[slotIdx])
                        updateFfmpegRenderedSubtitle(framePtsMs)
                    }
                    lastPresentedVideoPtsMs = framePtsMs
                    consecutiveDropped = 0
                    if (!firstFramePresented) {
                        firstFramePresented = true
                        if (renderedFirstFrame.compareAndSet(false, true)) {
                            playbackState = Player.STATE_READY
                            val cost = if (prepareStartRealtimeMs > 0L) System.currentTimeMillis() - prepareStartRealtimeMs else 0L
                            Log.i(TAG, "FFmpeg 首帧已渲染 (${width}x${height}), 耗时=${cost}ms")
                            notifyMain { listeners.forEach { it.onRenderedFirstFrame() } }
                        }
                        val f0WaitStart = System.currentTimeMillis()
                        while (expectAudio() && !useAudioClock() && playWhenReady &&
                            ffmpegRunning.get() && gen == mediaGeneration.get() &&
                            System.currentTimeMillis() - f0WaitStart < 250L
                        ) {
                            Thread.sleep(4L)
                        }
                        wallClockAnchorMs = System.currentTimeMillis()
                    } else if (renderedFirstFrame.compareAndSet(false, true)) {
                        playbackState = Player.STATE_READY
                        val cost = if (prepareStartRealtimeMs > 0L) System.currentTimeMillis() - prepareStartRealtimeMs else 0L
                        Log.i(TAG, "FFmpeg 首帧已渲染 (${width}x${height}), 耗时=${cost}ms")
                        notifyMain { listeners.forEach { it.onRenderedFirstFrame() } }
                    }
                    if (!audioActive) {
                        positionMsInternal = framePtsMs
                        positionAnchorNano = System.nanoTime()
                    }
                } else {
                    if (DesktopPlayerEngine.activePlayer === this@DesktopExoPlayer) {
                        updateFfmpegRenderedSubtitle(framePtsMs)
                    }
                    consecutiveDropped++
                }

                ringLock.lock()
                try {
                    readIdx = (readIdx + 1) % ringCap
                    queued--
                    notFull.signal()
                } finally {
                    ringLock.unlock()
                }
            }
        } finally {
            DesktopPlayerEngine.releaseWindowsHighResTimer()
            ringLock.lock()
            try {
                notFull.signalAll()
            } finally {
                ringLock.unlock()
            }
            runCatching { readerThread.join(300L) }
        }
        return totalFramesRead.get()
    }

    private fun filterModeHintForVulkanMark(desc: String): Boolean = "libplacebo Vulkan" in desc

    private fun pumpPcmToAudioLine(
        gen: Int,
        ins: InputStream,
        startPosMs: Long,
        markReadyWithoutVideo: Boolean,
        waitForFirstVideoFrame: Boolean = false,
        playbackSpeed: Float = 1.0f,
        preOpenedLine: SourceDataLine? = null,
    ): Long {
        val format = AudioFormat(48000f, 16, 2, true, false)
        val line = preOpenedLine ?: runCatching {
            AudioSystem.getSourceDataLine(format)?.apply {
                open(format, 48000 * 4 * 2 / 5)
            }
        }.getOrNull()
        var lineStarted = !waitForFirstVideoFrame
        if (lineStarted) {
            line?.start()
        }
        ffmpegAudioLine = line

        val buf = ByteArray(960 * 4) // 20ms
        var carry = 0
        var bytesPlayed = 0L
        var audioGateReleased = !waitForFirstVideoFrame
        var prebufferedChunks = 0
        var audioBasePosMs = startPosMs
        var skipRemainingBytes = 0L
        var lateAudioChecked = false
        val speedFactor = playbackSpeed.coerceIn(0.25f, 4.0f).toDouble()
        try {
            while (ffmpegRunning.get() && gen == mediaGeneration.get()) {
                if (!playWhenReady) {
                    if (lineStarted) {
                        runCatching { line?.stop() }
                        lineStarted = false
                    }
                    Thread.sleep(20)
                    continue
                }
                if (!audioGateReleased && prebufferedChunks >= 2) {
                    var waitedMs = 0L
                    while (ffmpegRunning.get() && gen == mediaGeneration.get() && playWhenReady &&
                        !renderedFirstFrame.get() && waitedMs < 12_000L
                    ) {
                        Thread.sleep(5L)
                        waitedMs += 5L
                    }
                    audioGateReleased = true
                    if (!lineStarted && playWhenReady) {
                        positionMsInternal = audioBasePosMs
                        positionAnchorNano = System.nanoTime()
                        runCatching { line?.start() }
                        lineStarted = true
                        ffmpegAudioClockReady = true
                    }
                }
                if (audioGateReleased && !lineStarted) {
                    positionAnchorNano = System.nanoTime()
                    runCatching { line?.start() }
                    lineStarted = true
                }
                val n = ins.read(buf, carry, buf.size - carry)
                if (n < 0) break
                if (n == 0) continue
                val totalAvail = carry + n
                val aligned = totalAvail - (totalAvail % 4)
                val nextCarry = totalAvail - aligned
                if (aligned > 0) {
                    if (!lateAudioChecked) {
                        lateAudioChecked = true
                        if (waitForFirstVideoFrame && renderedFirstFrame.get()) {
                            val videoLeadMs = lastPresentedVideoPtsMs - startPosMs
                            if (videoLeadMs > 60L) {
                                audioBasePosMs = lastPresentedVideoPtsMs
                                skipRemainingBytes = (((videoLeadMs.toDouble() / speedFactor) * 48.0).roundToLong().coerceAtLeast(0L)) * 4L
                            }
                        }
                    }
                    var writeOffset = 0
                    var writeLen = aligned
                    if (skipRemainingBytes > 0L && writeLen > 0) {
                        val drop = minOf(skipRemainingBytes, writeLen.toLong()).toInt().let { it - (it % 4) }
                        if (drop > 0) {
                            skipRemainingBytes -= drop
                            writeOffset += drop
                            writeLen -= drop
                        }
                    }
                    if (writeLen > 0) {
                        val effVol = (volume * DesktopPlayerEngine.effectiveMasterVolume).coerceIn(0f, 1f)
                        if (effVol < 0.99f) {
                            var i = writeOffset
                            val end = writeOffset + writeLen
                            while (i + 1 < end) {
                                val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
                                val scaled = (sample * effVol).toInt().coerceIn(-32768, 32767).toShort()
                                buf[i] = (scaled.toInt() and 0xFF).toByte()
                                buf[i + 1] = ((scaled.toInt() shr 8) and 0xFF).toByte()
                                i += 2
                            }
                        }
                        if (!audioGateReleased) {
                            prebufferedChunks++
                            if (line != null) {
                                line.write(buf, writeOffset, writeLen)
                            }
                            if (renderedFirstFrame.get()) {
                                audioGateReleased = true
                                positionMsInternal = audioBasePosMs
                                positionAnchorNano = System.nanoTime()
                                if (!lineStarted) {
                                    runCatching { line?.start() }
                                    lineStarted = true
                                }
                                ffmpegAudioClockReady = true
                            }
                        } else if (line != null) {
                            line.write(buf, writeOffset, writeLen)
                        } else {
                            val pcmMs = ((writeLen / 4L) * 1000L / 48_000L).coerceIn(1L, 50L)
                            Thread.sleep(pcmMs)
                        }
                        bytesPlayed += writeLen
                        if (audioGateReleased) {
                            val playedFrames = line?.longFramePosition ?: (bytesPlayed / 4L)
                            val sampleRateLong = format.sampleRate.toDouble().coerceAtLeast(8_000.0)
                            val actualPlayedMs = ((playedFrames.coerceAtLeast(0L) * 1000.0 / sampleRateLong) * speedFactor).roundToLong()
                            positionMsInternal = audioBasePosMs + actualPlayedMs
                            positionAnchorNano = System.nanoTime()
                            ffmpegAudioClockReady = true
                            if (markReadyWithoutVideo && playbackState == Player.STATE_BUFFERING) {
                                playbackState = Player.STATE_READY
                            }
                        }
                    }
                }
                if (nextCarry > 0) {
                    System.arraycopy(buf, aligned, buf, 0, nextCarry)
                }
                carry = nextCarry
            }
            if (!audioGateReleased && bytesPlayed > 0L) {
                runCatching { line?.start() }
            }
            if (ffmpegRunning.get() && gen == mediaGeneration.get()) {
                line?.drain()
            } else {
                runCatching { line?.flush() }
            }
        } finally {
            runCatching { line?.close() }
            if (ffmpegAudioLine === line) ffmpegAudioLine = null
        }
        return bytesPlayed
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
        filterMode: Int = 0,
        useD3d11Hwdec: Boolean = false,
        subtitleStreams: List<ProbedSubtitleStream> = emptyList(),
    ) {
        ioPool.submit {
            var framesRead = 0L
            val stderrTailRef = AtomicReference("")
            var procTail: AtomicReference<String>? = null
            val subBindings = openFfmpegTextSubtitleBindings(subtitleStreams.filter { !it.imageBased })
            val bitmapSubIdx = selectedFfmpegBitmapSubIdx
            try {
                val safeFps = computeTargetRenderFps(fps)
                val fpsStr = "%.6f".format(java.util.Locale.US, safeFps)
                val vfStr = buildColorMappedVfFilter(width, height, filterMode, fps)
                val cmd = buildFfmpegInputArgs(
                    ffmpeg = ffmpeg,
                    url = url,
                    headers = headers,
                    startPosMs = startPosMs,
                    useVulkanDevice = filterMode == 1,
                    useD3d11Hwdec = useD3d11Hwdec,
                ).apply {
                    addAll(listOf("-threads", "0", "-filter_threads", "0"))
                    appendFfmpegVideoFilterAndOutputArgs(this, vfStr, width, height, fps, fpsStr, bitmapSubIdx)
                    for ((sub, srv) in subBindings) {
                        addAll(
                            listOf(
                                "-map", "0:${sub.streamIdx}?",
                                "-c:s", "srt",
                                "-flush_packets", "1",
                                "-f", "srt",
                                "tcp://127.0.0.1:${srv.localPort}?tcp_nodelay=1",
                            ),
                        )
                    }
                }
                val proc = ProcessBuilder(cmd).start()
                procTail = captureProcessStderrTail(proc)
                ffmpegVideoProc = proc
                launchFfmpegTextSubtitleReaders(gen, proc, subBindings, startPosMs)
                framesRead = readAndPresentFfmpegVideoRing(
                    gen = gen,
                    ins = proc.inputStream,
                    width = width,
                    height = height,
                    startPosMs = startPosMs,
                    safeFps = safeFps,
                    useAudioClock = { false },
                )
                proc.destroy()
                stderrTailRef.set(procTail?.get().orEmpty())
            } catch (e: Throwable) {
                runCatching { Thread.sleep(30L) }
                val detail = procTail?.get().orEmpty().also { stderrTailRef.set(it) }
                Log.e(TAG, "FFmpeg 视频解码线程异常: ${redactUrlForLog(url)}" + if (detail.isNotBlank()) " [$detail]" else "", e)
            } finally {
                subBindings.forEach { (_, srv) ->
                    runCatching { srv.close() }
                    ffmpegSubServers.remove(srv)
                }
            }
            if (framesRead == 0L && ffmpegRunning.get() && gen == mediaGeneration.get()) {
                if (useD3d11Hwdec) {
                    startFfmpegVideoThread(gen, ffmpeg, url, headers, startPosMs, width, height, fps, filterMode = filterMode, useD3d11Hwdec = false, subtitleStreams = subtitleStreams)
                    return@submit
                }
                if (filterMode == 1) {
                    startFfmpegVideoThread(gen, ffmpeg, url, headers, startPosMs, width, height, fps, filterMode = 0, useD3d11Hwdec = false, subtitleStreams = subtitleStreams)
                    return@submit
                }
                ffmpegRunning.set(false)
                val detail = stderrTailRef.get().takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                onError(
                    PlaybackException(
                        "MPV/FFmpeg 视频渲染管线无法打开该媒体流$detail",
                        null,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    ),
                )
                return@submit
            }
            if (ffmpegRunning.get() && gen == mediaGeneration.get() && framesRead > 0L) {
                onItemEnded()
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
                val curSpeed = playbackParameters.speed.coerceIn(0.25f, 4.0f)
                val afStr = buildFfmpegAudioFilter(curSpeed)
                val cmd = buildFfmpegInputArgs(ffmpeg, url, headers, startPosMs).apply {
                    addAll(
                        listOf(
                            "-vn", "-sn",
                            "-af", afStr,
                            "-ac", "2",
                            "-ar", "48000",
                            "-f", "s16le", "-",
                        ),
                    )
                }
                val proc = ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                ffmpegAudioProc = proc
                val bytesPlayed = pumpPcmToAudioLine(
                    gen = gen,
                    ins = proc.inputStream,
                    startPosMs = startPosMs,
                    markReadyWithoutVideo = markReadyWithoutVideo,
                    waitForFirstVideoFrame = false,
                    playbackSpeed = curSpeed,
                )
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
        vlcBufferingSinceMs = 0L
        vlcStatsFuture?.cancel(false)
        vlcStatsFuture = null
        ffmpegRunning.set(false)
        runCatching { ffmpegVideoServer?.close() }
        ffmpegVideoServer = null
        runCatching { ffmpegAudioServer?.close() }
        ffmpegAudioServer = null
        ffmpegSubServers.forEach { runCatching { it.close() } }
        ffmpegSubServers.clear()
        clearRenderedEmbeddedSubtitle()
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
        /** Windows WASAPI 共享模式扬声器输出缓冲典型延迟补偿（毫秒），使 VLC / FFmpeg 播放时钟贴合人耳听到的采样时刻 */
        private const val VLC_SPEAKER_LATENCY_MS = 85L
        private const val FFMPEG_SPEAKER_LATENCY_MS = 25L
        private val IS_WINDOWS_HOST: Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("win")
        private val probeMetaCache = java.util.concurrent.ConcurrentHashMap<String, ProbedStreamMeta>()

        /** 缓存轻量字节头嗅探结果（>0 表示杜比视界 Profile，0 表示已确认为普通非杜比流，避免重复网络读取） */
        private val dvSniffCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
        private val colorSniffCache = java.util.concurrent.ConcurrentHashMap<String, ColorPipelineProbe>()

        /**
         * 计算目标渲染帧率：
         * <=60.5fps 保持原始精确帧率（如 23.976 / 24 / 29.97 / 59.94 / 60）；
         * >60.5fps（如《比利·林恩的中场战事》119.88fps / 120fps）按整数分频降为 59.94fps / 60fps，消除非整数倍抽帧抖动并减半 GPU 带宽。
         */
        internal fun computeTargetRenderFps(srcFps: Double): Double {
            if (!srcFps.isFinite() || srcFps <= 0.0) return 24.0
            if (srcFps < 12.0) return 12.0
            if (srcFps <= 60.5) return srcFps
            val divisor = ceil(srcFps / 60.5).toInt().coerceAtLeast(2)
            return (srcFps / divisor.toDouble()).coerceIn(24.0, 60.5)
        }

        /**
         * 从 MP4 / MKV 容器头部（或 MP4 尾部 moov）原始字节中零依赖快速识别杜比视界 Profile：
         * - 支持 MP4/MOV 的 `dvcC` / `dvvC` / `dvwC` 配置盒、`ftyp` 品牌 `dby1` 及 `stsd` 样本项 `dvh1` / `dvhe` / `dav1`；
         * - 支持 MKV Matroska 的 `BlockAddIDType` (`dvcC` / `dvvC`) + `BlockAddIDExtraData` (`0x41 0xED`) 24 字节配置记录；
         * - 严格校验 `dv_version_major == 1`、`dv_version_minor in 0..2` 与 `rpu_present_flag / bl_present_flag`，杜绝普通视频误判。
         */
        internal fun scanDolbyVisionProfileFromBytes(buf: ByteArray, length: Int): Int? {
            val n = minOf(length, buf.size)
            if (n < 16) return null
            var sawDvBrandOrEntry = false

            // 1. 检查起始处 MP4 ftyp 兼容品牌是否包含 "dby1"
            if (n >= 16 &&
                buf[4] == 'f'.code.toByte() &&
                buf[5] == 't'.code.toByte() &&
                buf[6] == 'y'.code.toByte() &&
                buf[7] == 'p'.code.toByte()
            ) {
                val ftypSize = readBeInt(buf, 0)
                val ftypEnd = if (ftypSize in 16..512) minOf(ftypSize, n) else minOf(64, n)
                var pos = 8
                while (pos + 4 <= ftypEnd) {
                    if (buf[pos] == 'd'.code.toByte() &&
                        buf[pos + 1] == 'b'.code.toByte() &&
                        buf[pos + 2] == 'y'.code.toByte() &&
                        buf[pos + 3] == '1'.code.toByte()
                    ) {
                        sawDvBrandOrEntry = true
                        break
                    }
                    pos += 4
                }
            }

            // 2. 扫描 dvcC / dvvC / dvwC 配置记录（兼容 MP4 Box 与 MKV BlockAdditionMapping）及 MP4 dvh1/dvhe SampleEntry
            val limit = n - 8
            var i = 0
            while (i <= limit) {
                val b0 = buf[i]
                if (b0 == 'd'.code.toByte()) {
                    val b1 = buf[i + 1]
                    val b2 = buf[i + 2]
                    val b3 = buf[i + 3]
                    // 'dvcC' / 'dvvC' / 'dvwC'
                    if (b1 == 'v'.code.toByte() &&
                        (b2 == 'c'.code.toByte() || b2 == 'v'.code.toByte() || b2 == 'w'.code.toByte()) &&
                        b3 == 'C'.code.toByte()
                    ) {
                        // 2a) MP4 Box: [size(4)]['dvcC'(4)][major(1)][minor(1)][profile_level(2)][flags(1)]
                        parseDoviRecordAt(buf, n, i + 4)?.let { return it }

                        // 2b) MKV BlockAdditionMapping: 0x41 0xED (BlockAddIDExtraData) 可能位于 'dvcC' 之后或之前（如 mkvmerge 不同版本）
                        val mkvStart = (i - 48).coerceAtLeast(0)
                        val mkvEnd = minOf(i + 32, n - 8)
                        var k = mkvStart
                        while (k <= mkvEnd) {
                            if ((buf[k].toInt() and 0xFF) == 0x41 && (buf[k + 1].toInt() and 0xFF) == 0xED) {
                                val vintByte = buf[k + 2].toInt() and 0xFF
                                val vintLen = when {
                                    (vintByte and 0x80) != 0 -> 1
                                    (vintByte and 0x40) != 0 -> 2
                                    else -> 0
                                }
                                if (vintLen > 0) {
                                    parseDoviRecordAt(buf, n, k + 2 + vintLen)?.let { return it }
                                }
                            }
                            k++
                        }
                    }

                    // MP4 VisualSampleEntry: [size(4)]['dvh1'|'dvhe'|'dav1'|'dva1'(4)][reserved 6 bytes == 0]
                    val isDvSampleEntry = (b1 == 'v'.code.toByte() && b2 == 'h'.code.toByte() && (b3 == '1'.code.toByte() || b3 == 'e'.code.toByte())) ||
                        (b1 == 'a'.code.toByte() && b2 == 'v'.code.toByte() && b3 == '1'.code.toByte()) ||
                        (b1 == 'v'.code.toByte() && b2 == 'a'.code.toByte() && b3 == '1'.code.toByte())
                    if (isDvSampleEntry && i >= 4 && i + 10 <= n) {
                        val entrySize = readBeInt(buf, i - 4)
                        val zeroReserved = buf[i + 4].toInt() == 0 && buf[i + 5].toInt() == 0 &&
                            buf[i + 6].toInt() == 0 && buf[i + 7].toInt() == 0 &&
                            buf[i + 8].toInt() == 0 && buf[i + 9].toInt() == 0
                        if (entrySize in 86..65536 && zeroReserved) {
                            sawDvBrandOrEntry = true
                        }
                    }
                }
                i++
            }
            return if (sawDvBrandOrEntry) 5 else null
        }

        private fun parseDoviRecordAt(buf: ByteArray, limit: Int, recOffset: Int): Int? {
            if (recOffset < 0 || recOffset + 5 > limit) return null
            val major = buf[recOffset].toInt() and 0xFF
            val minor = buf[recOffset + 1].toInt() and 0xFF
            if (major != 1 || minor !in 0..2) return null
            val b2 = buf[recOffset + 2].toInt() and 0xFF
            val b3 = buf[recOffset + 3].toInt() and 0xFF
            val b4 = buf[recOffset + 4].toInt() and 0xFF
            val profile = (b2 ushr 1) and 0x7F
            val level = ((b2 and 0x01) shl 5) or (b3 ushr 3)
            val rpuPresent = (b3 and 0x04) != 0
            val blPresent = (b3 and 0x01) != 0
            val reservedLowZero = (b4 and 0x0F) == 0
            return if (profile in 1..20 && level in 1..20 && rpuPresent && blPresent && reservedLowZero) profile else null
        }

        private fun readBeInt(buf: ByteArray, offset: Int): Int {
            if (offset < 0 || offset + 4 > buf.size) return 0
            return ((buf[offset].toInt() and 0xFF) shl 24) or
                ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                (buf[offset + 3].toInt() and 0xFF)
        }

        /**
         * 从 MP4/MOV `stsd`（`colr` nclx/nclc、`mdcv`、`clli`）或 MKV Matroska `Colour`（`0x55 0xB0`）原始字节中
         * 零依赖识别 HDR10 (SMPTE ST 2084 PQ)、HLG (ARIB STD-B67)、BT.2020 广色域或 ICtCp 流。
         */
        internal fun scanHdrOrWideGamutFromBytes(buf: ByteArray, length: Int): Boolean {
            val n = minOf(length, buf.size)
            if (n < 16) return false
            val isMkv = (buf[0].toInt() and 0xFF) == 0x1A && (buf[1].toInt() and 0xFF) == 0x45 &&
                (buf[2].toInt() and 0xFF) == 0xDF && (buf[3].toInt() and 0xFF) == 0xA3

            var i = 0
            val end = n - 12
            while (i <= end) {
                val b0 = buf[i].toInt() and 0xFF
                // 1) MP4 'colr' Box: [size(4)]['colr'(4)]['nclx'|'nclc'(4)][primaries(2)][transfer(2)][matrix(2)]
                if (b0 == 'c'.code &&
                    buf[i + 1] == 'o'.code.toByte() &&
                    buf[i + 2] == 'l'.code.toByte() &&
                    buf[i + 3] == 'r'.code.toByte() &&
                    i >= 4 && i + 14 <= n
                ) {
                    val boxSize = readBeInt(buf, i - 4)
                    val isNclx = buf[i + 4] == 'n'.code.toByte() && buf[i + 5] == 'c'.code.toByte() &&
                        buf[i + 6] == 'l'.code.toByte() && (buf[i + 7] == 'x'.code.toByte() || buf[i + 7] == 'c'.code.toByte())
                    if (boxSize in 18..64 && isNclx) {
                        val primaries = ((buf[i + 8].toInt() and 0xFF) shl 8) or (buf[i + 9].toInt() and 0xFF)
                        val transfer = ((buf[i + 10].toInt() and 0xFF) shl 8) or (buf[i + 11].toInt() and 0xFF)
                        val matrix = ((buf[i + 12].toInt() and 0xFF) shl 8) or (buf[i + 13].toInt() and 0xFF)
                        if (transfer == 16 || transfer == 18 || transfer == 17 || primaries == 9 || matrix in intArrayOf(9, 10, 14)) {
                            return true
                        }
                    }
                }
                // 2) MP4 SMPTE ST 2086 'mdcv' (32B) 或 CEA-861.3 'clli' (12B) HDR 元数据盒
                if (i >= 4 && (
                        (b0 == 'm'.code && buf[i + 1] == 'd'.code.toByte() && buf[i + 2] == 'c'.code.toByte() && buf[i + 3] == 'v'.code.toByte() && readBeInt(buf, i - 4) == 32) ||
                            (b0 == 'c'.code && buf[i + 1] == 'l'.code.toByte() && buf[i + 2] == 'l'.code.toByte() && buf[i + 3] == 'i'.code.toByte() && readBeInt(buf, i - 4) == 12)
                        )
                ) {
                    return true
                }
                // 2b) MP4/MKV HEVC 'hvcC' 配置记录内嵌 PREFIX_SEI (nal_unit_type=39: payload 137=MasteringDisplay, 144=MaxCLL, 147=HLG)
                if (b0 == 'h'.code &&
                    buf[i + 1] == 'v'.code.toByte() &&
                    buf[i + 2] == 'c'.code.toByte() &&
                    buf[i + 3] == 'C'.code.toByte() &&
                    i + 27 <= n && (buf[i + 4].toInt() and 0xFF) == 1
                ) {
                    if (scanHevcConfigForHdrSei(buf, i + 4, n)) return true
                }
                // 2c) MKV CodecPrivate (0x63 0xA2) 内嵌 HEVCDecoderConfigurationRecord 的 HDR PREFIX_SEI
                if (isMkv && b0 == 0x63 && (buf[i + 1].toInt() and 0xFF) == 0xA2 && i + 28 <= n) {
                    val vByte = buf[i + 2].toInt() and 0xFF
                    val vLen = when {
                        (vByte and 0x80) != 0 -> 1
                        (vByte and 0x40) != 0 -> 2
                        (vByte and 0x20) != 0 -> 3
                        (vByte and 0x10) != 0 -> 4
                        else -> 0
                    }
                    val cpStart = i + 2 + vLen
                    if (vLen > 0 && cpStart + 23 <= n &&
                        (buf[cpStart].toInt() and 0xFF) == 1 &&
                        (buf[cpStart + 16].toInt() and 0xFC) == 0xFC &&
                        (buf[cpStart + 17].toInt() and 0xFC) == 0xFC
                    ) {
                        if (scanHevcConfigForHdrSei(buf, cpStart, n)) return true
                    }
                }
                // 3) Matroska EBML Colour Master Element: 0x55 0xB0
                if (isMkv && b0 == 0x55 && (buf[i + 1].toInt() and 0xFF) == 0xB0) {
                    val vintByte = buf[i + 2].toInt() and 0xFF
                    val (vintLen, elemLen) = when {
                        (vintByte and 0x80) != 0 -> 1 to (vintByte and 0x7F)
                        (vintByte and 0x40) != 0 && i + 3 < n -> 2 to (((vintByte and 0x3F) shl 8) or (buf[i + 3].toInt() and 0xFF))
                        else -> 0 to 0
                    }
                    if (vintLen > 0 && elemLen in 3..256) {
                        val cStart = i + 2 + vintLen
                        val cEnd = minOf(cStart + elemLen, n - 4)
                        var k = cStart
                        while (k <= cEnd) {
                            if ((buf[k].toInt() and 0xFF) == 0x55) {
                                val subId = buf[k + 1].toInt() and 0xFF
                                val subLen = buf[k + 2].toInt() and 0xFF
                                val subVal = buf[k + 3].toInt() and 0xFF
                                // 0x55BA TransferCharacteristics (16=PQ, 18=HLG), 0x55BB Primaries (9=BT.2020), 0x55B1 Matrix (9,10=BT.2020, 14=ICtCp)
                                if (subLen == 0x81) {
                                    if ((subId == 0xBA && (subVal == 16 || subVal == 18)) ||
                                        (subId == 0xBB && subVal == 9) ||
                                        (subId == 0xB1 && subVal in intArrayOf(9, 10, 14))
                                    ) {
                                        return true
                                    }
                                }
                                // 0x55D0 MasteringMetadata
                                if (subId == 0xD0 && (subLen and 0x80) != 0 && (subLen and 0x7F) >= 10) {
                                    return true
                                }
                            }
                            k++
                        }
                    }
                }
                i++
            }
            return false
        }

        private fun scanHevcConfigForHdrSei(buf: ByteArray, hvccStart: Int, limit: Int): Boolean {
            if (hvccStart + 23 > limit) return false
            val numArrays = buf[hvccStart + 22].toInt() and 0xFF
            var p = hvccStart + 23
            var arrIdx = 0
            while (arrIdx < numArrays && p + 3 <= limit) {
                val nalType = buf[p].toInt() and 0x3F
                val numNalus = ((buf[p + 1].toInt() and 0xFF) shl 8) or (buf[p + 2].toInt() and 0xFF)
                p += 3
                var nIdx = 0
                while (nIdx < numNalus && p + 2 <= limit) {
                    val nalLen = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
                    p += 2
                    if (nalLen <= 0 || p + nalLen > limit) return false
                    // HEVC PREFIX_SEI_NUT = 39: [2B NAL Header][payloadType...]
                    if (nalType == 39 && nalLen >= 4) {
                        val payloadType = buf[p + 2].toInt() and 0xFF
                        if (payloadType == 137 || payloadType == 144 || payloadType == 147) {
                            return true
                        }
                    }
                    p += nalLen
                    nIdx++
                }
                arrIdx++
            }
            return false
        }

        /** 若 MP4 stsd 含 10-bit HEVC/AV1 配置但缺少外层 colr 色彩盒（VUI 色域藏在压缩 SPS 内），标记为需进一步用 ffprobe 确认 */
        internal fun has10BitCodecWithoutColrBox(buf: ByteArray, length: Int): Boolean {
            if (containsAsciiFourCc(buf, length, 'c', 'o', 'l', 'r')) return false
            val end = minOf(length, buf.size) - 24
            var i = 0
            while (i <= end) {
                if (buf[i] == 'h'.code.toByte() && buf[i + 1] == 'v'.code.toByte() &&
                    buf[i + 2] == 'c'.code.toByte() && buf[i + 3] == 'C'.code.toByte() &&
                    (buf[i + 4].toInt() and 0xFF) == 1
                ) {
                    val lumaMinus8 = buf[i + 4 + 17].toInt() and 0x03
                    if (lumaMinus8 >= 2) return true
                }
                if (buf[i] == 'a'.code.toByte() && buf[i + 1] == 'v'.code.toByte() &&
                    buf[i + 2] == '1'.code.toByte() && buf[i + 3] == 'C'.code.toByte() &&
                    (buf[i + 5].toInt() and 0x40) != 0
                ) {
                    return true
                }
                i++
            }
            return false
        }

        /** MKV 含 10-bit HEVC CodecPrivate 但外层未写入 0x55BA TransferCharacteristics 时标记为需用 ffprobe 读取 SPS VUI */
        internal fun has10BitMkvWithoutTransferTag(buf: ByteArray, length: Int): Boolean {
            val n = minOf(length, buf.size)
            var hasExplicitTransfer = false
            var has10BitHevc = false
            var i = 0
            while (i <= n - 6) {
                val b0 = buf[i].toInt() and 0xFF
                if (b0 == 0x55 && (buf[i + 1].toInt() and 0xFF) == 0xBA && (buf[i + 2].toInt() and 0xFF) == 0x81) {
                    hasExplicitTransfer = true
                }
                if (b0 == 0x63 && (buf[i + 1].toInt() and 0xFF) == 0xA2 && i + 26 <= n) {
                    val vByte = buf[i + 2].toInt() and 0xFF
                    val vLen = when {
                        (vByte and 0x80) != 0 -> 1
                        (vByte and 0x40) != 0 -> 2
                        (vByte and 0x20) != 0 -> 3
                        (vByte and 0x10) != 0 -> 4
                        else -> 0
                    }
                    val cp = i + 2 + vLen
                    if (vLen > 0 && cp + 19 <= n &&
                        (buf[cp].toInt() and 0xFF) == 1 &&
                        (buf[cp + 16].toInt() and 0xFC) == 0xFC &&
                        (buf[cp + 17].toInt() and 0xFC) == 0xFC &&
                        (buf[cp + 17].toInt() and 0x03) >= 2
                    ) {
                        has10BitHevc = true
                    }
                }
                i++
            }
            return has10BitHevc && !hasExplicitTransfer
        }

        internal fun containsMkvTracksElement(buf: ByteArray, length: Int): Boolean {
            val end = minOf(length, buf.size) - 4
            var i = 0
            while (i <= end) {
                if ((buf[i].toInt() and 0xFF) == 0x16 &&
                    (buf[i + 1].toInt() and 0xFF) == 0x54 &&
                    (buf[i + 2].toInt() and 0xFF) == 0xAE &&
                    (buf[i + 3].toInt() and 0xFF) == 0x6B
                ) return true
                i++
            }
            return false
        }

        /**
         * 解析非 faststart MP4 头部顶层 Box（支持 >4GB 64 位 largesize == 1 的超大 mdat），
         * 直接计算出位于 mdat 之后的 moov 精确字节偏移量。
         */
        internal fun findMp4MoovOffset(headBuf: ByteArray, headLen: Int, totalFileSize: Long): Long? {
            val n = minOf(headLen, headBuf.size)
            var pos = 0L
            var guard = 0
            while (pos + 8L <= n.toLong() && guard++ < 16) {
                val p = pos.toInt()
                val size32 = readBeInt(headBuf, p).toUInt().toLong()
                val t0 = headBuf[p + 4].toInt().toChar()
                val t1 = headBuf[p + 5].toInt().toChar()
                val t2 = headBuf[p + 6].toInt().toChar()
                val t3 = headBuf[p + 7].toInt().toChar()
                val boxSize = when {
                    size32 == 1L -> {
                        if (p + 16 > n) return null
                        readBeLong(headBuf, p + 8)
                    }
                    size32 == 0L -> return null
                    else -> size32
                }
                if (boxSize < 8L) return null
                if (t0 == 'm' && t1 == 'o' && t2 == 'o' && t3 == 'v') {
                    return pos
                }
                if (t0 == 'm' && t1 == 'd' && t2 == 'a' && t3 == 't') {
                    val afterMdat = pos + boxSize
                    return if (afterMdat in 16L until totalFileSize) afterMdat else null
                }
                pos += boxSize
            }
            return null
        }

        /** 若 moov 内的第一条 trak 体积大于当前读取块且不含视频 stsd（例如 2 小时音轨 stbl 在前、视频轨在后），返回第二条 trak 的文件偏移 */
        internal fun findSecondMp4TrakOffsetIfNeeded(
            moovBuf: ByteArray,
            moovLen: Int,
            moovFileOffset: Long,
            totalFileSize: Long,
        ): Long? {
            val n = minOf(moovLen, moovBuf.size)
            if (n < 32) return null
            if (moovBuf[4] != 'm'.code.toByte() || moovBuf[5] != 'o'.code.toByte() ||
                moovBuf[6] != 'o'.code.toByte() || moovBuf[7] != 'v'.code.toByte()
            ) return null
            val moovHeaderLen = if (readBeInt(moovBuf, 0) == 1) 16 else 8
            var rel = moovHeaderLen.toLong()
            var guard = 0
            while (rel + 8L <= n.toLong() && guard++ < 12) {
                val p = rel.toInt()
                val sz32 = readBeInt(moovBuf, p).toUInt().toLong()
                val boxSize = if (sz32 == 1L) {
                    if (p + 16 > n) return null
                    readBeLong(moovBuf, p + 8)
                } else sz32
                if (boxSize < 8L) return null
                val isTrak = moovBuf[p + 4] == 't'.code.toByte() && moovBuf[p + 5] == 'r'.code.toByte() &&
                    moovBuf[p + 6] == 'a'.code.toByte() && moovBuf[p + 7] == 'k'.code.toByte()
                if (isTrak) {
                    val nextRel = rel + boxSize
                    if (nextRel > n.toLong() && moovFileOffset + nextRel < totalFileSize - 64L) {
                        return moovFileOffset + nextRel
                    }
                    return null
                }
                rel += boxSize
            }
            return null
        }

        private fun readBeLong(buf: ByteArray, offset: Int): Long {
            if (offset < 0 || offset + 8 > buf.size) return 0L
            val hi = readBeInt(buf, offset).toUInt().toLong()
            val lo = readBeInt(buf, offset + 4).toUInt().toLong()
            return (hi shl 32) or lo
        }

        internal fun containsAsciiFourCc(buf: ByteArray, length: Int, c0: Char, c1: Char, c2: Char, c3: Char): Boolean {
            val b0 = c0.code.toByte(); val b1 = c1.code.toByte(); val b2 = c2.code.toByte(); val b3 = c3.code.toByte()
            val end = minOf(length, buf.size) - 4
            var i = 0
            while (i <= end) {
                if (buf[i] == b0 && buf[i + 1] == b1 && buf[i + 2] == b2 && buf[i + 3] == b3) return true
                i++
            }
            return false
        }

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
