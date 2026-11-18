package com.aisub.player.media

import android.app.ActivityManager
import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowManager
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
import com.aisub.player.R
import com.aisub.player.live.LiveHeaders
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.cloud.CloudRegistry
import com.aisub.player.subtitle.EmbeddedSubtitles
import com.aisub.player.telegram.Telegram
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/**
 * 基于 `libmpv` + `libplacebo` 实现的 Media3 [Player]：
 * 1. **彻底解决杜比视界（Dolby Vision Profile 5 / 7 / 8）紫绿偏色与 HDR 发灰问题**：
 *    - 采用 `vo=gpu-next`（仅支持 GLES 2.0 的旧芯片自动回退 `gpu`）与 `libplacebo` 动态色调映射管线；
 *    - 普通视频与 HDR10 / Profile 7 / 8 默认使用 `hwdec=mediacodec-copy` 硬解加速并映射至 Rec.709 / sRGB；
 *    - 针对无兼容底流的 **Dolby Vision Profile 5（`IPTPQc2`）**，自动切换至 FFmpeg 原生多线程解码（`hwdec=no`），
 *      确保 `hevcdec` 完整解析 `UNSPEC62` 杜比 RPU 动态元数据与 `IPTPQc2` 色彩空间矩阵，交由 `libplacebo` 精准重塑色彩，
 *      避免系统 `MediaCodec` 吞掉 RPU 元数据导致紫绿画面；
 * 2. **全格式解码与特效字幕**：支持 Hi10P、4:2:2、AV1、VC-1、RMVB、FLV、AVI、MKV、ASS 特效字幕及 PGS/VobSub 图形字幕；
 * 3. **完整融入译幕播放生态**：对外遵循标准 Media3 [Player] 接口，控制栏、手势、倍速、选轨、章节、逐帧、截图与 AI 字幕无缝工作。
 */
@UnstableApi
class MpvPlayer(
    context: Context,
    private val proxy: MediaProxy,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var items: List<MediaItem> = emptyList()
    private var startPositionMs = 0L
    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var durationMs = C.TIME_UNSET
    private var positionMs = 0L
    private var videoSize = VideoSize.UNKNOWN
    private var error: PlaybackException? = null
    private var speed = 1f
    private var volume = 1f
    private var repeatModeState = Player.REPEAT_MODE_OFF
    private var trackParams = TrackSelectionParameters.DEFAULT
    private var tracks = Tracks.EMPTY
    private var chaptersList: List<Chapter> = emptyList()
    private var firstFrame = false
    private var pendingLoadUrl: String? = null
    private var pendingLoadStartSec: Double = 0.0
    private var pendingHeaders: Map<String, String> = emptyMap()
    private var surfaceAttached = false
    private var released = false
    private var activeVideoTrackId: Int = 1
    private var suppressNativeSubtitles = false
    private var activeSubTrackId: Int = 0
    private var activeSubLang: String? = null
    private var activeSubLabel: String? = null
    private var activeSubCodec: String? = null
    private var subStartSec: Double = Double.NaN
    private var subEndSec: Double = Double.NaN
    private var lastRecordedSubKey: Pair<Long, String>? = null
    private var directRemoteStream = false
    private var lastDirectPollMs = 0L

    /** 上层已确认当前条目为杜比视界 Profile 5 时置为 true，起播即用 FFmpeg 原生 RPU 解码管线 */
    @Volatile
    var dolbyProfile5Hint: Boolean = false

    @Volatile
    var detectedDolbyProfile: Int? = null
        private set

    @Volatile
    var activeColorMatrix: String? = null
        private set

    @Volatile
    var activeVo: String? = null
        private set

    @Volatile
    var activeHwdec: String? = null
        private set

    @Volatile
    var activeVideoCodec: String? = null
        private set

    @Volatile
    var activeAudioCodec: String? = null
        private set

    /** 用户手动覆盖的硬解偏好：null=自动（常规硬解、杜比 P5 自动软解保 RPU），true=强制硬解，false=强制软解 */
    var userForceHwdec: Boolean? = null
        private set

    private var attachedSurfaceView: SurfaceView? = null
    private var attachedHolder: SurfaceHolder? = null
    private var attachedTextureView: TextureView? = null
    private var textureSurface: Surface? = null

    private val layoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        if (v.width > 0 && v.height > 0 && surfaceAttached) {
            bridge.setPropertyString("android-surface-size", "${v.width}x${v.height}")
        }
    }

    private val surfaceHolderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            val s = holder.surface ?: return
            if (!s.isValid) return
            bridge.attachSurface(s)
            surfaceAttached = true
            attachedSurfaceView?.let { v ->
                if (v.width > 0 && v.height > 0) {
                    bridge.setPropertyString("android-surface-size", "${v.width}x${v.height}")
                }
            }
            restoreVideoTrackAfterSurface()
            flushPendingLoadIfReady()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (width > 0 && height > 0 && surfaceAttached) {
                bridge.setPropertyString("android-surface-size", "${width}x${height}")
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceAttached = false
            bridge.detachSurface()
        }
    }

    private val textureSurfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            if (surfaceAttached) {
                surfaceAttached = false
                bridge.detachSurface()
            }
            textureSurface?.release()
            val s = Surface(st)
            textureSurface = s
            bridge.attachSurface(s)
            surfaceAttached = true
            if (width > 0 && height > 0) {
                bridge.setPropertyString("android-surface-size", "${width}x${height}")
            }
            restoreVideoTrackAfterSurface()
            flushPendingLoadIfReady()
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
            if (width > 0 && height > 0 && surfaceAttached) {
                bridge.setPropertyString("android-surface-size", "${width}x${height}")
            }
        }

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            surfaceAttached = false
            bridge.detachSurface()
            textureSurface?.release()
            textureSurface = null
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    private val bridgeListener: MpvNativeBridge.Listener = object : MpvNativeBridge.Listener {
        override fun onEvent(eventId: Int, reason: Int, error: Int) {
            mainHandler.post { handleMpvEvent(eventId, reason, error) }
        }

        override fun onPropertyLong(name: String, value: Long) {}

        override fun onPropertyDouble(name: String, value: Double) {
            mainHandler.post {
                if (items.isEmpty()) return@post
                when (name) {
                    "time-pos" -> if (!value.isNaN() && value >= 0.0) {
                        positionMs = (value * 1000.0).toLong()
                    }
                    "duration" -> if (!value.isNaN() && value > 0.0) {
                        val newDur = (value * 1000.0).toLong()
                        if (newDur != durationMs) {
                            durationMs = newDur
                            invalidateState()
                        }
                    }
                    "sub-start" -> subStartSec = value
                    "sub-end" -> subEndSec = value
                }
            }
        }

        override fun onPropertyBoolean(name: String, value: Boolean) {
            mainHandler.post {
                if (items.isEmpty()) return@post
                when (name) {
                    "paused-for-cache" -> if (playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED) {
                        val next = if (value) Player.STATE_BUFFERING else Player.STATE_READY
                        if (next != playbackState) {
                            playbackState = next
                            invalidateState()
                        }
                    }
                    "eof-reached" -> if (value && repeatModeState != Player.REPEAT_MODE_ONE) {
                        playbackState = Player.STATE_ENDED
                        playWhenReady = false
                        invalidateState()
                    }
                }
            }
        }

        override fun onPropertyString(name: String, value: String?) {
            mainHandler.post {
                when (name) {
                    "video-params/colormatrix" -> {
                        activeColorMatrix = value
                        if (value.equals("dolbyvision", ignoreCase = true) && !DolbyVisionHelper.displaySupportsDolbyVision(appContext)) {
                            bridge.setPropertyString("target-prim", "bt.709")
                            bridge.setPropertyString("target-trc", "srgb")
                            bridge.setPropertyString("tone-mapping", "bt.2390")
                        }
                    }
                    "hwdec-current" -> activeHwdec = value
                    "current-vo" -> activeVo = value
                    "sub-text" -> {
                        if (value.isNullOrBlank()) {
                            subStartSec = Double.NaN
                            subEndSec = Double.NaN
                            return@post
                        }
                        val lines = value.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
                        if (lines.isEmpty()) return@post
                        val liveStart = if (!subStartSec.isNaN() && subStartSec >= 0.0) {
                            subStartSec
                        } else {
                            bridge.getPropertyDouble("sub-start", Double.NaN)
                        }
                        val liveEnd = if (!subEndSec.isNaN() && subEndSec > liveStart) {
                            subEndSec
                        } else {
                            bridge.getPropertyDouble("sub-end", Double.NaN)
                        }
                        val startSec = if (!liveStart.isNaN() && liveStart >= 0.0) {
                            liveStart
                        } else {
                            positionMs.coerceAtLeast(0L) / 1000.0
                        }
                        val endSec = if (!liveEnd.isNaN() && liveEnd > startSec) liveEnd else (startSec + 3.5)
                        val startMs = (startSec * 1000.0).toLong().coerceAtLeast(0L)
                        val endMs = (endSec * 1000.0).toLong().coerceAtLeast(startMs + 200L)
                        val key = (startMs / 50L) to lines.joinToString("\n")
                        if (lastRecordedSubKey != key) {
                            lastRecordedSubKey = key
                            EmbeddedSubtitles.recordExternalCue(
                                trackId = activeSubTrackId.takeIf { it > 0 } ?: 1,
                                language = activeSubLang,
                                label = activeSubLabel,
                                codec = activeSubCodec,
                                startMs = startMs,
                                endMs = endMs,
                                lines = lines,
                            )
                        }
                    }
                }
            }
        }
    }

    private val bridge: MpvNativeBridge = MpvNativeBridge(bridgeListener)

    init {
        initMpvKernel()
    }

    private fun initMpvKernel() {
        if (!bridge.create()) {
            error = PlaybackException(
                "当前设备未能加载 MPV 原生引擎（libmpv.so）",
                null,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            )
            return
        }

        val lowRam = isLowRamOr32BitDevice()
        val dvDisplay = DolbyVisionHelper.displaySupportsDolbyVision(appContext)

        val configDir = ensureMpvSubFontDir()
        if (configDir != null) {
            bridge.setOptionString("config", "yes")
            bridge.setOptionString("config-dir", configDir.absolutePath)
        } else {
            bridge.setOptionString("config", "no")
        }
        bridge.setOptionString("idle", "yes")
        bridge.setOptionString("keep-open", "no")
        // Surface 尚未绑定前保持 vo=null 与 force-window=no，待 Surface 有效时由 attachSurface 激活 gpu-next/gpu
        bridge.setOptionString("force-window", "no")
        bridge.setOptionString("vo", "null")
        bridge.setOptionString("input-default-bindings", "no")
        bridge.setOptionString("input-vo-keyboard", "no")
        bridge.setOptionString("osc", "no")
        bridge.setOptionString("osd-level", "0")

        bridge.setOptionString("gpu-context", "android")
        bridge.setOptionString("gpu-api", "opengl")

        // 精简移动端与电视端 GPU 着色器负载：采用单趟双线性采样并关闭高开销蓝噪点抖动与非线性缩放，4K 杜比映射更流畅省电
        bridge.setOptionString("scale", "bilinear")
        bridge.setOptionString("cscale", "bilinear")
        bridge.setOptionString("dscale", "bilinear")
        bridge.setOptionString("dither", "no")
        bridge.setOptionString("correct-downscaling", "no")
        bridge.setOptionString("linear-downscaling", "no")
        bridge.setOptionString("sigmoid-upscaling", "no")

        // 默认 mediacodec-copy（硬解帧拷回内存供着色器色调映射，不支持的编码或手动指定软解时走 FFmpeg 多核软解）
        val initHwdec = if (userForceHwdec == false) "no" else "mediacodec-copy"
        bridge.setOptionString("hwdec", initHwdec)
        bridge.setOptionString("hwdec-codecs", "all")
        bridge.setOptionString("vd-lavc-threads", "0")
        if (lowRam || initHwdec == "no") {
            bridge.setOptionString("vd-lavc-fast", "yes")
        }
        if (lowRam) {
            bridge.setOptionString("vd-lavc-skiploopfilter", "nonref")
        }

        // 杜比视界与 HDR 色彩管线设置：非杜比视界屏幕映射为标准 Rec.709 + sRGB，彻底消除紫绿偏色与 HDR 灰白画面
        if (!dvDisplay) {
            bridge.setOptionString("target-colorspace-hint", "no")
            bridge.setOptionString("target-prim", "bt.709")
            bridge.setOptionString("target-trc", "srgb")
            bridge.setOptionString("tone-mapping", "bt.2390")
            bridge.setOptionString("hdr-compute-peak", "no")
        } else {
            bridge.setOptionString("target-colorspace-hint", "yes")
            bridge.setOptionString("tone-mapping", "bt.2390")
        }

        runCatching {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            val fps = wm?.defaultDisplay?.refreshRate
            if (fps != null && fps > 20f) {
                bridge.setOptionString("display-fps-override", fps.toString())
            }
        }

        // 音频输出：audiotrack 优先，Android 6.0 老设备回退 opensles；下混立体声解决 5.1/7.1 杜比环绕声对白缺失与音量不可调问题
        bridge.setOptionString("ao", "audiotrack,opensles")
        bridge.setOptionString("audio-channels", "stereo")
        bridge.setOptionString("audio-pitch-correction", "yes")
        bridge.setOptionString("volume-max", "150")

        // 内嵌字幕渲染（ASS/SSA 原样特效渲染，同时挂系统回退字体以支持无内嵌字体的 SRT/ASS）
        bridge.setOptionString("sub-auto", "no")
        bridge.setOptionString("sub-ass", "yes")
        bridge.setOptionString("embeddedfonts", "yes")
        bridge.setOptionString("sub-font-provider", "none")
        bridge.setOptionString("sub-fonts-dir", "/system/fonts")

        // 网络与缓存（32 位老电视控制内存峰值）
        bridge.setOptionString("demuxer-max-bytes", if (lowRam) "24MiB" else "64MiB")
        bridge.setOptionString("demuxer-max-back-bytes", if (lowRam) "6MiB" else "16MiB")
        bridge.setOptionString("network-timeout", "20")
        bridge.setOptionString("tls-verify", "no")
        ensureCaBundleFile()?.let { ca ->
            bridge.setOptionString("tls-ca-file", ca.absolutePath)
        }

        if (!bridge.initialize()) {
            error = PlaybackException(
                "初始化 MPV 内核失败",
                null,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            )
        }
    }

    private fun ensureMpvSubFontDir(): File? = runCatching {
        val dir = File(appContext.filesDir, "mpv_config")
        if (!dir.exists()) dir.mkdirs()
        val subfont = File(dir, "subfont.ttf")
        if (!subfont.exists() || subfont.length() == 0L) {
            val candidates = listOf(
                "/system/fonts/NotoSansCJK-Regular.ttc",
                "/system/fonts/NotoSansSC-Regular.otf",
                "/system/fonts/NotoSerifCJK-Regular.ttc",
                "/system/fonts/DroidSansFallback.ttf",
                "/system/fonts/NotoSans-Regular.ttf",
                "/system/fonts/Roboto-Regular.ttf",
            )
            val src = candidates.map(::File).firstOrNull { it.isFile && it.canRead() && it.length() > 0L }
                ?: File("/system/fonts").listFiles()
                    ?.firstOrNull { f ->
                        f.isFile && f.canRead() && f.length() > 0L &&
                            f.extension.lowercase() in setOf("ttf", "otf", "ttc") &&
                            (f.name.contains("CJK", true) || f.name.contains("SC", true) || f.name.contains("Sans", true))
                    }
            if (src != null) {
                src.inputStream().use { input ->
                    subfont.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        dir.takeIf { subfont.exists() && subfont.length() > 0L }
    }.getOrNull()

    fun setEmbeddedSubtitlesSuppressed(suppressed: Boolean) {
        if (suppressNativeSubtitles == suppressed) return
        suppressNativeSubtitles = suppressed
        mainHandler.post {
            if (!released) {
                applyTrackSelectionInternal()
            }
        }
    }

    private fun isLowRamOr32BitDevice(): Boolean {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val is32Bit = Build.SUPPORTED_64_BIT_ABIS.isEmpty()
        return is32Bit || (am?.isLowRamDevice == true)
    }

    private fun ensureCaBundleFile(): File? = runCatching {
        val out = File(appContext.filesDir, "mpv_cacert.pem")
        if (!out.exists() || out.length() == 0L) {
            appContext.resources.openRawResource(R.raw.compat_ca_roots).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        out
    }.getOrNull()

    /** 切换 MPV 解码策略：null=智能自动（常规硬解、杜比 P5 软解保留 RPU），true=硬解优先，false=纯软解 */
    fun setHwdecPreference(forceHwdec: Boolean?) {
        if (userForceHwdec == forceHwdec) return
        userForceHwdec = forceHwdec
        applyEffectiveHwdec(
            isDolbyP5 = dolbyProfile5Hint || detectedDolbyProfile == 5,
            reloadVideoIfChanged = true,
        )
        invalidateState()
    }

    private fun applyEffectiveHwdec(isDolbyP5: Boolean, reloadVideoIfChanged: Boolean = false) {
        if (!bridge.isCreated) return
        val target = when (userForceHwdec) {
            true -> "mediacodec-copy"
            false -> "no"
            // 智能模式：Android MediaCodec 解码器会丢弃 HEVC UNSPEC62 杜比 RPU 动态元数据与 IPTPQc2 标记，
            // 因此仅在确认 Profile 5 时切为 FFmpeg 原生解码（hevcdec），Profile 7/8 与常规视频均保持硬解
            null -> if (isDolbyP5) "no" else "mediacodec-copy"
        }
        val wantSw = target == "no"
        bridge.setPropertyString("vd-lavc-fast", if (wantSw || isLowRamOr32BitDevice()) "yes" else "no")
        bridge.setPropertyString("hwdec", target)
        if (reloadVideoIfChanged &&
            playbackState != Player.STATE_IDLE &&
            items.isNotEmpty() &&
            surfaceAttached &&
            C.TRACK_TYPE_VIDEO !in trackParams.disabledTrackTypes
        ) {
            val curSw = activeHwdec.isNullOrBlank() || activeHwdec == "no"
            if (curSw != wantSw) {
                val targetVid = if (activeVideoTrackId > 0) activeVideoTrackId.toString() else "auto"
                bridge.setPropertyString("vid", "no")
                bridge.setPropertyString("vid", targetVid)
            }
        }
    }

    /** 供「视频信息」面板展示当前色彩与解码链路详情。 */
    val pipelineSummary: String
        get() {
            val vo = activeVo?.takeIf { it.isNotBlank() && it != "null" } ?: "gpu-next"
            val hw = activeHwdec?.takeIf { it.isNotBlank() && it != "no" }?.let { "硬解 ($it)" } ?: "FFmpeg 软解"
            val dvProf = detectedDolbyProfile
            val isDv = dvProf != null || dolbyProfile5Hint || activeColorMatrix.equals("dolbyvision", ignoreCase = true)
            val color = when {
                isDv && DolbyVisionHelper.displaySupportsDolbyVision(appContext) ->
                    "杜比视界${dvProf?.let { " P$it" }.orEmpty()} 直出"
                isDv ->
                    "杜比视界${dvProf?.let { " P$it" }.orEmpty()} → Rec.709 (libplacebo bt.2390 映射)"
                !activeColorMatrix.isNullOrBlank() -> activeColorMatrix!!
                else -> "标准色彩"
            }
            return "$vo · $hw · $color"
        }

    fun chapters(): List<Chapter> = chaptersList

    /** 直连直播流缓冲时采样 mpv 底层输入速率并计入 NetworkSpeedTracker。 */
    fun pollDirectNetworkBytes() {
        if (!directRemoteStream || !bridge.isCreated) return
        val now = System.currentTimeMillis()
        val prev = lastDirectPollMs
        lastDirectPollMs = now
        if (prev <= 0L || now <= prev) return
        val rate = bridge.getPropertyLong("cache-speed", 0L)
            .takeIf { it > 0L }
            ?: bridge.getPropertyLong("demuxer-cache-state/raw-input-rate", 0L)
        if (rate > 0L) {
            val deltaBytes = (rate * (now - prev) / 1000L).coerceAtLeast(0L)
            if (deltaBytes > 0L) {
                com.aisub.player.net.NetworkSpeedTracker.recordNetworkBytes(deltaBytes, now)
            }
        }
    }

    fun stepFrame(forward: Boolean): Long {
        playWhenReady = false
        bridge.setPropertyBoolean("pause", true)
        bridge.command(if (forward) "frame-step" else "frame-back-step")
        invalidateState()
        return positionMs
    }

    /**
     * 切换至其它播放内核（如 ExoPlayer / VLC）前调用：
     * 1. 先同步解绑 Surface 并将 mpv `vo` 切为 `null`，确保 EGL 渲染线程与 `ANativeWindow` 完整释放；
     * 2. 再同步执行 `stop` 关闭解复用连接与音频输出，清空当前播放项，彻底消除跨内核切换闪退与单连接抢流。
     */
    fun deactivateForKernelSwitch() {
        pendingLoadUrl = null
        directRemoteStream = false
        lastDirectPollMs = 0L
        playWhenReady = false
        detachCurrentViews()
        if (bridge.isCreated) {
            bridge.commandSync("stop")
        }
        items = emptyList()
        playbackState = Player.STATE_IDLE
        durationMs = C.TIME_UNSET
        positionMs = 0L
        videoSize = VideoSize.UNKNOWN
        error = null
        tracks = Tracks.EMPTY
        chaptersList = emptyList()
        activeSubTrackId = 0
        activeSubLang = null
        activeSubLabel = null
        activeSubCodec = null
        subStartSec = Double.NaN
        subEndSec = Double.NaN
        lastRecordedSubKey = null
        detectedDolbyProfile = null
        activeColorMatrix = null
        invalidateState()
    }

    private fun restoreVideoTrackAfterSurface() {
        if (C.TRACK_TYPE_VIDEO !in trackParams.disabledTrackTypes && items.isNotEmpty()) {
            val targetVid = if (activeVideoTrackId > 0) activeVideoTrackId.toString() else "auto"
            bridge.setPropertyString("vid", targetVid)
        }
    }

    private fun flushPendingLoadIfReady() {
        val url = pendingLoadUrl ?: return
        pendingLoadUrl = null
        executeLoadFile(url, pendingLoadStartSec, pendingHeaders)
    }

    private fun executeLoadFile(url: String, startSec: Double, headers: Map<String, String>) {
        applyEffectiveHwdec(isDolbyP5 = dolbyProfile5Hint)

        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
        bridge.setPropertyString("user-agent", ua?.takeIf { it.isNotBlank() } ?: "Mozilla/5.0")
        bridge.setPropertyString("referrer", referer.orEmpty())
        val extraHeaders = headers.entries
            .filter { !it.key.equals("User-Agent", ignoreCase = true) && !it.key.equals("Referer", ignoreCase = true) }
            .joinToString(",") { "${it.key}: ${it.value.replace(",", "%2C")}" }
        bridge.setPropertyString("http-header-fields", extraHeaders)

        bridge.setPropertyString("start", if (startSec > 0.05) "+%.3f".format(java.util.Locale.US, startSec) else "none")
        bridge.setPropertyBoolean("pause", !playWhenReady)
        bridge.setPropertyDouble("speed", speed.toDouble())
        applyVolumeInternal()

        bridge.command("loadfile", url, "replace")
    }

    private fun handleMpvEvent(eventId: Int, reason: Int, errCode: Int) {
        if (items.isEmpty() && eventId != MpvNativeBridge.EVENT_SHUTDOWN) {
            return
        }
        when (eventId) {
            MpvNativeBridge.EVENT_START_FILE -> {
                if (items.isNotEmpty()) {
                    playbackState = Player.STATE_BUFFERING
                    invalidateState()
                }
            }
            MpvNativeBridge.EVENT_FILE_LOADED -> {
                // 重置 start 属性，避免后续单曲重播仍跳到上次续播点
                bridge.setPropertyString("start", "none")
                activeVideoCodec = bridge.getPropertyString("video-codec")
                activeAudioCodec = bridge.getPropertyString("audio-codec")
                activeVo = bridge.getPropertyString("current-vo")
                activeHwdec = bridge.getPropertyString("hwdec-current")
                activeColorMatrix = bridge.getPropertyString("video-params/colormatrix")
                val dur = bridge.getPropertyDouble("duration", -1.0)
                if (dur > 0.0) durationMs = (dur * 1000.0).toLong()
                refreshTracksAndChapters()
                // 根据容器真实解封装结果精确匹配软硬解：仅真 Profile 5 启用 FFmpeg 软解保留 RPU，
                // 若实际为带兼容层的 Profile 7 / 8 或普通 HDR10，则立即解除软解锁死恢复 mediacodec-copy 硬解
                val isConfirmedP5 = detectedDolbyProfile == 5 ||
                    (detectedDolbyProfile == null && activeColorMatrix.equals("dolbyvision", ignoreCase = true))
                if (isConfirmedP5) {
                    dolbyProfile5Hint = true
                    applyEffectiveHwdec(isDolbyP5 = true, reloadVideoIfChanged = true)
                } else if (dolbyProfile5Hint) {
                    dolbyProfile5Hint = false
                    applyEffectiveHwdec(isDolbyP5 = false, reloadVideoIfChanged = true)
                } else {
                    applyEffectiveHwdec(isDolbyP5 = false, reloadVideoIfChanged = false)
                }
                applyTrackSelectionInternal()
                refreshTracksAndChapters()
                bridge.setPropertyBoolean("pause", !playWhenReady)
                playbackState = Player.STATE_READY
                invalidateState()
            }
            MpvNativeBridge.EVENT_VIDEO_RECONFIG,
            MpvNativeBridge.EVENT_PLAYBACK_RESTART -> {
                updateVideoDimensions()
                activeHwdec = bridge.getPropertyString("hwdec-current")
                activeVo = bridge.getPropertyString("current-vo")
                activeColorMatrix = bridge.getPropertyString("video-params/colormatrix")
                if (playbackState == Player.STATE_BUFFERING) {
                    playbackState = Player.STATE_READY
                }
                firstFrame = true
                invalidateState()
            }
            MpvNativeBridge.EVENT_END_FILE -> {
                when (reason) {
                    MpvNativeBridge.END_REASON_EOF -> {
                        if (repeatModeState == Player.REPEAT_MODE_ONE && items.isNotEmpty()) {
                            this.startPositionMs = 0L
                            this.positionMs = 0L
                            handlePrepare()
                        } else {
                            playbackState = Player.STATE_ENDED
                            playWhenReady = false
                            invalidateState()
                        }
                    }
                    MpvNativeBridge.END_REASON_ERROR -> {
                        val isLive = items.firstOrNull()?.localConfiguration?.tag == "live"
                        error = PlaybackException(
                            if (isLive) "MPV 内核无法打开该直播源（可能地址失效、受限或网络中断，错误码 $errCode）"
                            else "MPV 内核无法打开这个媒体文件（错误码 $errCode）",
                            null,
                            PlaybackException.ERROR_CODE_UNSPECIFIED,
                        )
                        playbackState = Player.STATE_IDLE
                        invalidateState()
                    }
                }
            }
        }
    }

    private fun updateVideoDimensions() {
        val w = bridge.getPropertyLong("video-params/w", 0L).toInt()
        val h = bridge.getPropertyLong("video-params/h", 0L).toInt()
        if (w <= 0 || h <= 0) return
        val par = bridge.getPropertyDouble("video-params/par", 1.0).let {
            if (it.isNaN() || it <= 0.0) 1f else it.toFloat()
        }
        val rotate = bridge.getPropertyLong("video-params/rotate", 0L).toInt()
        videoSize = if (rotate == 90 || rotate == 270) {
            VideoSize(h, w, if (par > 0f) 1f / par else 1f)
        } else {
            VideoSize(w, h, par)
        }
    }

    private fun refreshTracksAndChapters() {
        val groups = ArrayList<Tracks.Group>()
        var foundDvProfile: Int? = null
        val count = bridge.getPropertyLong("track-list/count", 0L).toInt().coerceIn(0, 128)
        for (i in 0 until count) {
            val type = bridge.getPropertyString("track-list/$i/type") ?: continue
            val id = bridge.getPropertyLong("track-list/$i/id", -1L).toInt()
            if (id < 0) continue
            val lang = bridge.getPropertyString("track-list/$i/lang")
            val title = bridge.getPropertyString("track-list/$i/title")
            val codec = bridge.getPropertyString("track-list/$i/codec").orEmpty().lowercase()
            val selected = bridge.getPropertyString("track-list/$i/selected") == "yes"

            when (type) {
                "video" -> {
                    if (selected) activeVideoTrackId = id
                    val w = bridge.getPropertyLong("track-list/$i/demux-w", 0L).toInt()
                    val h = bridge.getPropertyLong("track-list/$i/demux-h", 0L).toInt()
                    val fps = bridge.getPropertyDouble("track-list/$i/demux-fps", 0.0)
                    val dvProf = bridge.getPropertyLong("track-list/$i/dolby-vision-profile", 0L).toInt().takeIf { it > 0 }
                    if (dvProf != null && (selected || foundDvProfile == null)) {
                        foundDvProfile = dvProf
                    }
                    val isDv = dvProf != null || activeColorMatrix.equals("dolbyvision", ignoreCase = true) ||
                        "dovi" in codec || "dvhe" in codec || "dvh1" in codec
                    val mime = when {
                        isDv -> MimeTypes.VIDEO_DOLBY_VISION
                        "hevc" in codec || "h265" in codec -> MimeTypes.VIDEO_H265
                        "h264" in codec || "avc" in codec -> MimeTypes.VIDEO_H264
                        "av1" in codec -> MimeTypes.VIDEO_AV1
                        "vp9" in codec -> MimeTypes.VIDEO_VP9
                        else -> MimeTypes.VIDEO_UNKNOWN
                    }
                    val codecStr = if (dvProf != null) "dvhe.%02d".format(java.util.Locale.US, dvProf) else codec.ifEmpty { null }
                    val fb = Format.Builder()
                        .setId("v$id")
                        .setLabel(title)
                        .setLanguage(lang)
                        .setSampleMimeType(mime)
                        .setCodecs(codecStr)
                    if (w > 0 && h > 0) fb.setWidth(w).setHeight(h)
                    if (!fps.isNaN() && fps > 0.0) fb.setFrameRate(fps.toFloat())
                    groups += Tracks.Group(TrackGroup("v$id", fb.build()), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
                }
                "audio" -> {
                    val ch = bridge.getPropertyLong("track-list/$i/demux-channel-count", 0L).toInt()
                    val sr = bridge.getPropertyLong("track-list/$i/demux-samplerate", 0L).toInt()
                    val mime = when {
                        "eac3" in codec -> MimeTypes.AUDIO_E_AC3
                        "ac3" in codec -> MimeTypes.AUDIO_AC3
                        "truehd" in codec -> MimeTypes.AUDIO_TRUEHD
                        "dts" in codec -> MimeTypes.AUDIO_DTS
                        "flac" in codec -> MimeTypes.AUDIO_FLAC
                        "aac" in codec -> MimeTypes.AUDIO_AAC
                        "opus" in codec -> MimeTypes.AUDIO_OPUS
                        else -> MimeTypes.AUDIO_UNKNOWN
                    }
                    val fb = Format.Builder()
                        .setId("a$id")
                        .setLabel(title)
                        .setLanguage(lang)
                        .setSampleMimeType(mime)
                        .setCodecs(codec.ifEmpty { null })
                    if (ch > 0) fb.setChannelCount(ch)
                    if (sr > 0) fb.setSampleRate(sr)
                    groups += Tracks.Group(TrackGroup("a$id", fb.build()), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
                }
                "sub" -> {
                    val codecTag = when {
                        "ass" in codec || "ssa" in codec -> "text/x-ssa"
                        "subrip" in codec || "srt" in codec -> "application/x-subrip"
                        "webvtt" in codec || "vtt" in codec -> "text/vtt"
                        "pgs" in codec || "hdmv" in codec -> "application/pgs"
                        "dvd_subtitle" in codec || "vobsub" in codec -> "application/vobsub"
                        "dvb_subtitle" in codec -> "application/dvbsubs"
                        else -> MimeTypes.TEXT_UNKNOWN
                    }
                    val normalizedLang = lang?.takeIf { it.isNotBlank() && it != "und" }
                        ?: EmbeddedSubtitles.parseLanguageFromLabel(title)
                    val forced = bridge.getPropertyString("track-list/$i/forced") == "yes" ||
                        (title?.contains("forced", ignoreCase = true) == true)
                    if (selected) {
                        activeSubTrackId = id
                        activeSubLang = normalizedLang
                        activeSubLabel = title
                        activeSubCodec = codecTag
                    }
                    val fb = Format.Builder()
                        .setId("s$id")
                        .setLabel(title)
                        .setLanguage(normalizedLang)
                        .setSampleMimeType(codecTag)
                        .setCodecs(codecTag)
                    if (forced) fb.setSelectionFlags(C.SELECTION_FLAG_FORCED)
                    groups += Tracks.Group(TrackGroup("s$id", fb.build()), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
                }
            }
        }
        detectedDolbyProfile = foundDvProfile
        tracks = Tracks(groups)

        val chapCount = bridge.getPropertyLong("chapter-list/count", 0L).toInt().coerceIn(0, 256)
        if (chapCount > 0) {
            val raw = ArrayList<Pair<Long, String>>(chapCount)
            for (c in 0 until chapCount) {
                val tSec = bridge.getPropertyDouble("chapter-list/$c/time", -1.0)
                if (tSec.isNaN() || tSec < 0.0) continue
                val cTitle = bridge.getPropertyString("chapter-list/$c/title").orEmpty()
                raw += (tSec * 1000.0).toLong() to cTitle
            }
            chaptersList = raw.mapIndexed { idx, (startMs, cTitle) ->
                val endMs = raw.getOrNull(idx + 1)?.first ?: durationMs.takeIf { it > startMs }
                Chapter(startMs, endMs, cTitle)
            }
        } else {
            chaptersList = emptyList()
        }
    }

    private fun applyVolumeInternal() {
        val muted = C.TRACK_TYPE_AUDIO in trackParams.disabledTrackTypes
        val pct = if (muted) 0.0 else (volume.coerceIn(0f, 1.5f) * 100.0)
        bridge.setPropertyDouble("volume", pct)
    }

    private fun applyTrackSelectionInternal() {
        applyVolumeInternal()
        val audioDisabled = C.TRACK_TYPE_AUDIO in trackParams.disabledTrackTypes
        val audioOverride = trackParams.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
        if (!audioDisabled && audioOverride != null) {
            audioOverride.mediaTrackGroup.id.removePrefix("a").toIntOrNull()?.let { id ->
                bridge.setPropertyString("aid", id.toString())
            }
        }

        val textDisabled = C.TRACK_TYPE_TEXT in trackParams.disabledTrackTypes
        val textOverride = trackParams.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_TEXT }
        val showNative = !suppressNativeSubtitles
        when {
            textDisabled -> {
                bridge.setPropertyBoolean("sub-visibility", false)
                bridge.setPropertyString("sid", "no")
            }
            textOverride != null -> {
                textOverride.mediaTrackGroup.id.removePrefix("s").toIntOrNull()?.let { id ->
                    bridge.setPropertyString("sid", id.toString())
                    bridge.setPropertyBoolean("sub-visibility", showNative)
                }
            }
            else -> {
                val prefLangs = trackParams.preferredTextLanguages
                if (prefLangs.isNotEmpty()) {
                    bridge.setPropertyString("slang", prefLangs.joinToString(","))
                    bridge.setPropertyString("sid", "auto")
                    bridge.setPropertyBoolean("sub-visibility", showNative)
                } else {
                    bridge.setPropertyBoolean("sub-visibility", false)
                    bridge.setPropertyString("sid", "no")
                }
            }
        }

        val videoDisabled = C.TRACK_TYPE_VIDEO in trackParams.disabledTrackTypes
        if (videoDisabled) {
            bridge.setPropertyString("vid", "no")
        } else if (surfaceAttached) {
            val targetVid = if (activeVideoTrackId > 0) activeVideoTrackId.toString() else "auto"
            bridge.setPropertyString("vid", targetVid)
        }
    }

    override fun getState(): State {
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_VIDEO_SURFACE, Player.COMMAND_GET_TRACKS,
            Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS, Player.COMMAND_SET_VOLUME, Player.COMMAND_GET_VOLUME,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_RELEASE,
        ).build()

        val playlist = items.mapIndexed { i, item ->
            val isLiveItem = item.localConfiguration?.tag == "live" && durationMs == C.TIME_UNSET
            MediaItemData.Builder(item.mediaId.ifEmpty { "mpv_item_$i" })
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000L)
                .setIsSeekable(!isLiveItem)
                .setIsDynamic(isLiveItem)
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
                    if (playbackState == Player.STATE_ENDED && durationMs != C.TIME_UNSET) durationMs
                    else positionMs.coerceAtLeast(0L)
                },
            )
            .setPlaybackParameters(PlaybackParameters(speed))
            .setTrackSelectionParameters(trackParams)
            .setRepeatMode(repeatModeState)
            .setVolume(volume)
            .setVideoSize(videoSize)
            .build()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        items = mediaItems.drop(startIndex.coerceAtLeast(0)).take(1).ifEmpty { mediaItems.take(1) }
        this.startPositionMs = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        this.positionMs = this.startPositionMs
        durationMs = C.TIME_UNSET
        tracks = Tracks.EMPTY
        chaptersList = emptyList()
        activeSubTrackId = 0
        activeSubLang = null
        activeSubLabel = null
        activeSubCodec = null
        subStartSec = Double.NaN
        subEndSec = Double.NaN
        lastRecordedSubKey = null
        detectedDolbyProfile = null
        activeColorMatrix = null
        error = null
        if (items.isEmpty() && bridge.isCreated) {
            pendingLoadUrl = null
            playbackState = Player.STATE_IDLE
            bridge.commandSync("stop")
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        if (mediaItems.isEmpty()) return Futures.immediateVoidFuture()
        if (items.isEmpty()) {
            items = mediaItems.take(1)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        return Futures.immediateVoidFuture()
    }

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
            pendingLoadUrl = null
            directRemoteStream = false
            lastDirectPollMs = 0L
            playbackState = Player.STATE_IDLE
            durationMs = C.TIME_UNSET
            positionMs = 0L
            videoSize = VideoSize.UNKNOWN
            error = null
            tracks = Tracks.EMPTY
            chaptersList = emptyList()
            detectedDolbyProfile = null
            activeColorMatrix = null
            if (bridge.isCreated) {
                bridge.commandSync("stop")
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        val item = items.firstOrNull() ?: return Futures.immediateVoidFuture()
        if (!bridge.isCreated) {
            initMpvKernel()
            if (!bridge.isCreated) return Futures.immediateVoidFuture()
        }

        val uri = item.localConfiguration?.uri ?: Uri.parse(item.mediaId)
        val name = item.mediaMetadata.title?.toString() ?: uri.lastPathSegment.orEmpty()
        val isLive = item.localConfiguration?.tag == "live"
        error = null

        val raw = uri.toString()
        val scheme = uri.scheme?.lowercase().orEmpty()
        val isCloud = CloudRegistry.isCloud(raw)
        val isTg = Telegram.isTelegram(raw)
        val routed = if (!isCloud && !isTg) Endpoints.rewrite(raw) else raw
        val authHeaders = if (!isCloud && !isTg) AuthRegistry.headersFor(routed) else emptyMap()
        val liveHeaders = if (!isCloud && !isTg) LiveHeaders.headersFor(routed) else emptyMap()

        val targetUrl: String
        val headersForMpv: Map<String, String>

        lastDirectPollMs = 0L
        when {
            scheme == "file" && !uri.path.isNullOrEmpty() -> {
                directRemoteStream = false
                targetUrl = uri.path!!
                headersForMpv = emptyMap()
            }
            scheme in DIRECT_STREAM_SCHEMES ||
                (isLive && (scheme == "http" || scheme == "https") && !isCloud && !isTg && authHeaders.isEmpty()) -> {
                directRemoteStream = true
                targetUrl = routed
                headersForMpv = liveHeaders
            }
            else -> {
                directRemoteStream = false
                targetUrl = proxy.register(uri, name)
                headersForMpv = emptyMap()
            }
        }

        val startSec = if (!isLive && startPositionMs > 0L) startPositionMs / 1000.0 else 0.0
        playbackState = Player.STATE_BUFFERING
        if (surfaceAttached) {
            pendingLoadUrl = null
            executeLoadFile(targetUrl, startSec, headersForMpv)
        } else {
            pendingLoadUrl = targetUrl
            pendingLoadStartSec = startSec
            pendingHeaders = headersForMpv
            mainHandler.postDelayed({
                if (pendingLoadUrl == targetUrl && items.isNotEmpty()) {
                    flushPendingLoadIfReady()
                }
            }, 120L)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED) {
            bridge.setPropertyBoolean("pause", !playWhenReady)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = positionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        this.positionMs = target
        if (playbackState == Player.STATE_ENDED) {
            this.startPositionMs = target
            playbackState = Player.STATE_BUFFERING
            handlePrepare()
        } else {
            bridge.command("seek", "%.3f".format(java.util.Locale.US, target / 1000.0), "absolute")
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        bridge.setPropertyDouble("speed", speed.toDouble())
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeatModeState = repeatMode
        bridge.setPropertyString("loop-file", if (repeatMode == Player.REPEAT_MODE_ONE) "inf" else "no")
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        this.volume = volume
        applyVolumeInternal()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetTrackSelectionParameters(
        trackSelectionParameters: TrackSelectionParameters,
    ): ListenableFuture<*> {
        trackParams = trackSelectionParameters
        applyTrackSelectionInternal()
        refreshTracksAndChapters()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        detachCurrentViews()
        when (videoOutput) {
            is SurfaceView -> {
                attachedSurfaceView = videoOutput
                videoOutput.addOnLayoutChangeListener(layoutListener)
                val holder = videoOutput.holder
                attachedHolder = holder
                holder.addCallback(surfaceHolderCallback)
                val curSurface = holder.surface
                if (curSurface != null && curSurface.isValid) {
                    bridge.attachSurface(curSurface)
                    surfaceAttached = true
                    if (videoOutput.width > 0 && videoOutput.height > 0) {
                        bridge.setPropertyString("android-surface-size", "${videoOutput.width}x${videoOutput.height}")
                    }
                    restoreVideoTrackAfterSurface()
                    flushPendingLoadIfReady()
                }
            }
            is SurfaceHolder -> {
                attachedHolder = videoOutput
                videoOutput.addCallback(surfaceHolderCallback)
                val curSurface = videoOutput.surface
                if (curSurface != null && curSurface.isValid) {
                    bridge.attachSurface(curSurface)
                    surfaceAttached = true
                    restoreVideoTrackAfterSurface()
                    flushPendingLoadIfReady()
                }
            }
            is TextureView -> {
                attachedTextureView = videoOutput
                videoOutput.addOnLayoutChangeListener(layoutListener)
                videoOutput.surfaceTextureListener = textureSurfaceListener
                val st = videoOutput.surfaceTexture
                if (st != null) {
                    val s = Surface(st)
                    textureSurface = s
                    bridge.attachSurface(s)
                    surfaceAttached = true
                    if (videoOutput.width > 0 && videoOutput.height > 0) {
                        bridge.setPropertyString("android-surface-size", "${videoOutput.width}x${videoOutput.height}")
                    }
                    restoreVideoTrackAfterSurface()
                    flushPendingLoadIfReady()
                }
            }
            is Surface -> if (videoOutput.isValid) {
                bridge.attachSurface(videoOutput)
                surfaceAttached = true
                restoreVideoTrackAfterSurface()
                flushPendingLoadIfReady()
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        detachCurrentViews()
        return Futures.immediateVoidFuture()
    }

    private fun detachCurrentViews() {
        attachedSurfaceView?.removeOnLayoutChangeListener(layoutListener)
        attachedSurfaceView = null
        attachedHolder?.removeCallback(surfaceHolderCallback)
        attachedHolder = null

        attachedTextureView?.let { tv ->
            tv.removeOnLayoutChangeListener(layoutListener)
            if (tv.surfaceTextureListener === textureSurfaceListener) {
                tv.surfaceTextureListener = null
            }
        }
        attachedTextureView = null

        if (surfaceAttached) {
            surfaceAttached = false
            bridge.detachSurface()
        }
        textureSurface?.release()
        textureSurface = null
    }

    override fun handleStop(): ListenableFuture<*> {
        pendingLoadUrl = null
        directRemoteStream = false
        lastDirectPollMs = 0L
        if (bridge.isCreated) {
            bridge.commandSync("stop")
        }
        playbackState = Player.STATE_IDLE
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        pendingLoadUrl = null
        directRemoteStream = false
        lastDirectPollMs = 0L
        items = emptyList()
        detachCurrentViews()
        bridge.destroy()
        playbackState = Player.STATE_IDLE
        return Futures.immediateVoidFuture()
    }

    /** 供直播录屏在 RTSP / RTMP / UDP 等协议下调用 libmpv 原生 stream-record 无损落盘 */
    fun setStreamRecordPath(path: String): Boolean {
        if (!MpvNativeBridge.isAvailable()) return false
        return runCatching {
            bridge.setPropertyString("stream-record", path)
            true
        }.getOrDefault(false)
    }

    companion object {
        private val DIRECT_STREAM_SCHEMES = setOf("rtsp", "rtsps", "rtmp", "rtmps", "rtp", "udp", "mms", "mmsh")

        fun isAvailable(): Boolean = MpvNativeBridge.isAvailable()
    }
}
