package com.aisub.player.media

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * 杜比视界（Dolby Vision）流检测与硬件渲染能力判定辅助类：
 * 1. 准确解析容器编码标记（`dvh1` / `dvhe` / `dav1` / `dvav`）与 `dvcC` / `dvvC` 初始化盒子，区分：
 *    - Profile 5（专有 `IPTPQc2` 色彩空间，无 HDR10/HEVC 兼容底流，非杜比认证管线直接硬解会出现严重紫绿偏色）；
 *    - Profile 7 / 8（带 HDR10 / HLG 兼容底流，非杜比屏幕可回退至标准 HEVC/AV1 解码器正常还原色彩）。
 * 2. 结合当前屏幕 HDR 能力（[displaySupportsDolbyVision]）与系统解码器支持度，决定是由硬件直出、回退兼容底流，
 *    还是交由内置 `libplacebo` 动态色调映射的 [MpvPlayer] 渲染为标准 Rec.709 / sRGB。
 */
object DolbyVisionHelper {

    private val DV_CODEC_REGEX = Regex("""^(?:dvhe|dvh1|dvav|dva1|dav1)\.?0*(\d+)""", RegexOption.IGNORE_CASE)
    private val DV_PREFIX_REGEX = Regex("""^(?:dvhe|dvh1|dvav|dva1|dav1)\b""", RegexOption.IGNORE_CASE)

    /** 判断视频轨道是否为杜比视界流。 */
    fun isDolbyVision(format: Format?): Boolean {
        if (format == null) return false
        if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return true
        val codecs = format.codecs?.trim().orEmpty()
        if (codecs.isNotEmpty() && (DV_CODEC_REGEX.containsMatchIn(codecs) || DV_PREFIX_REGEX.containsMatchIn(codecs))) {
            return true
        }
        return parseDvccProfile(format.initializationData) != null
    }

    /**
     * 解析杜比视界 Profile 编号（如 5、7、8、4、9、10）；非杜比视界或无法识别时返回 null。
     */
    fun parseProfile(format: Format?): Int? {
        if (format == null) return null
        val codecs = format.codecs?.trim().orEmpty()
        if (codecs.isNotEmpty()) {
            for (part in codecs.split(',')) {
                val m = DV_CODEC_REGEX.find(part.trim())
                if (m != null) {
                    m.groupValues.getOrNull(1)?.toIntOrNull()?.let { return it }
                }
            }
        }
        parseDvccProfile(format.initializationData)?.let { return it }
        return null
    }

    /**
     * 从 ISO BMFF / Matroska `dvcC` / `dvvC` 配置记录（至少 4 字节）读取 `dv_profile`：
     * byte[0]=major, byte[1]=minor, byte[2..3] 前 7 bit 为 dv_profile (`(byte[2] >> 1) & 0x7F`)。
     */
    internal fun parseDvccProfile(initData: List<ByteArray>?): Int? {
        if (initData.isNullOrEmpty()) return null
        for (buf in initData) {
            if (buf.size in 4..64 && (buf[0].toInt() == 1) && (buf[1].toInt() in 0..2)) {
                val profile = (buf[2].toInt() and 0xFF) ushr 1
                if (profile in 1..20) return profile
            }
            // 若初始化块包含完整 atom 头（4B size + "dvcC" / "dvvC" / "dvwC"）
            for (i in 0..(buf.size - 8)) {
                if (buf[i] == 'd'.code.toByte() &&
                    buf[i + 1] == 'v'.code.toByte() &&
                    (buf[i + 2] == 'c'.code.toByte() || buf[i + 2] == 'v'.code.toByte() || buf[i + 2] == 'w'.code.toByte()) &&
                    buf[i + 3] == 'C'.code.toByte()
                ) {
                    val base = i + 4
                    if (base + 4 <= buf.size) {
                        val profile = (buf[base + 2].toInt() and 0xFF) ushr 1
                        if (profile in 1..20) return profile
                    }
                }
            }
        }
        return null
    }

    /**
     * 是否为无兼容底流的杜比视界 Profile 5（`IPTPQc2` 专有色彩空间）。
     * 若容器只标了 `video/dolby-vision` 而未标明 `.07` / `.08` / `.04`，也按需保护处理以避免紫绿画面。
     */
    fun isProfile5(format: Format?): Boolean {
        if (!isDolbyVision(format)) return false
        return when (val p = parseProfile(format)) {
            5 -> true
            // 未标明具体 profile 的裸 video/dolby-vision 极易为 MP4 封装的流媒体 Profile 5（dvh1）
            null -> format?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
            else -> false
        }
    }

    /** 是否为带 HDR10 / HLG 兼容底流的 Profile 7 / 8 / 4。 */
    fun hasHdrBaseLayerFallback(format: Format?): Boolean {
        return when (parseProfile(format)) {
            4, 7, 8, 9, 10 -> true
            else -> false
        }
    }

    /** 当前连接的显示屏幕是否原生支持杜比视界（HDR_TYPE_DOLBY_VISION）。Android 6.0 (API 23) 恒为 false。 */
    fun displaySupportsDolbyVision(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return displaySupportsHdrTypeApi24(context, Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
    }

    /** 当前屏幕是否原生支持任意 HDR（Dolby Vision / HDR10 / HLG / HDR10+）。 */
    fun displaySupportsAnyHdr(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
            @Suppress("DEPRECATION")
            display?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
        }.getOrDefault(false)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.N)
    private fun displaySupportsHdrTypeApi24(context: Context, hdrType: Int): Boolean {
        return runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
            @Suppress("DEPRECATION")
            val types = display?.hdrCapabilities?.supportedHdrTypes ?: return false
            types.any { it == hdrType }
        }.getOrDefault(false)
    }

    /** 设备是否具备硬解杜比视界（含 Profile 5 `DolbyVisionProfileDvheStn` = 0x20）的硬件解码器。 */
    fun hasHardwareDolbyVisionDecoder(requireProfile5: Boolean = true): Boolean {
        return runCatching {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            codecs.any { info ->
                if (info.isEncoder) return@any false
                val name = info.name.lowercase()
                if (name.startsWith("omx.google.") || name.startsWith("c2.android.") || "sw" in name) return@any false
                val supported = info.supportedTypes.any { it.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true) }
                if (!supported) return@any false
                if (!requireProfile5) return@any true
                val caps = runCatching { info.getCapabilitiesForType(MimeTypes.VIDEO_DOLBY_VISION) }.getOrNull() ?: return@any false
                caps.profileLevels?.any { pl ->
                    pl.profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn || pl.profile == 0x20
                } == true
            }
        }.getOrDefault(false)
    }

    /**
     * 判断当前杜比视界视频在默认 ExoPlayer 下是否会出现紫绿偏色或无法正确色调映射，
     * 从而需要自动分流至 [MpvPlayer]（`vo=gpu-next` + `libplacebo`）进行动态色彩映射：
     * - Profile 5 且当前屏幕不支持杜比视界（或无认证硬解）时必转 MPV，彻底杜绝 `IPTPQc2` 紫绿画面；
     * - 老旧 SDR 设备（如 Android 6.0 电视，无任何 HDR 屏幕能力）遇到杜比视界流时也优先交由 MPV 映射为鲜亮的 BT.709 SDR。
     */
    /** 当前设备 GPU 是否原生支持 OpenGL ES 3.0+（MPV `gpu-next` / `libplacebo` 的最低硬件要求）。 */
    fun supportsOpenGlEs3(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return (am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= 0x00030000
    }

    /** 当前是否为 Android TV 电视/机顶盒设备。 */
    fun isTelevisionDevice(context: Context): Boolean {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    @Volatile
    private var cachedCanSoftDecode4kP5: Boolean? = null

    /**
     * 评估当前移动设备 CPU 是否具备流畅软解高码率 4K 杜比视界 Profile 5 的算力：
     * - 所有电视/机顶盒（普遍采用四核 A53/A55 低功耗核心）恒返回 false，确保电视播 4K P5 走芯片硬解不卡顿；
     * - 64 位 8 核且大核主频 >= 2.35GHz、总内存 >= 5.5GB 的旗舰/次旗舰手机/平板返回 true（享受 MPV 16-bit libplacebo）；
     * - 中低端或老款 64 位手机返回 false（1080p P5 仍走 MPV，4K P5 自动走 Exo 零拷贝硬解 + 着色器防掉帧发热）。
     */
    fun canSmoothlySoftDecode4kProfile5(context: Context): Boolean {
        cachedCanSoftDecode4kP5?.let { return it }
        val result = runCatching {
            if (isTelevisionDevice(context) || isLegacyDolbyDevice(context)) return@runCatching false
            val cores = Runtime.getRuntime().availableProcessors()
            if (cores < 8) return@runCatching false
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val totalRam = memInfo.totalMem
            if (totalRam in 1L until 5_500_000_000L) return@runCatching false

            var maxFreqKhz = 0L
            for (i in 0 until minOf(cores, 12)) {
                val f = java.io.File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                if (f.isFile && f.canRead()) {
                    val v = runCatching { f.readText().trim().toLongOrNull() ?: 0L }.getOrDefault(0L)
                    if (v > maxFreqKhz) maxFreqKhz = v
                }
            }
            if (maxFreqKhz in 100_000L until 2_350_000L) {
                false
            } else {
                // 若系统限制读取 cpufreq，则以 8 核 + >= 7.2GB 内存作为现代高性能机型判据
                maxFreqKhz >= 2_350_000L || totalRam >= 7_200_000_000L
            }
        }.getOrDefault(false)
        cachedCanSoftDecode4kP5 = result
        return result
    }

    /**
     * 严格判定是否为传统老旧 SDR/32 位/GLES 2.0 设备：
     * - 凡具备原生杜比视界显示与硬解的设备恒为 false；
     * - 仅命中：GLES 2.0 老 GPU（如 Mali-450）、32 位架构（如小米电视 4A）、低内存设备，或无任何 HDR 能力且系统低于 Android 12 的设备。
     */
    fun isLegacyDolbyDevice(context: Context): Boolean {
        if (displaySupportsDolbyVision(context) && hasHardwareDolbyVisionDecoder(requireProfile5 = true)) {
            return false
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val is32Bit = Build.SUPPORTED_64_BIT_ABIS.isEmpty()
        val isLowRam = am?.isLowRamDevice == true
        val isOldSdr = !displaySupportsAnyHdr(context) && Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        return !supportsOpenGlEs3(context) || is32Bit || isLowRam || isOldSdr
    }

    /**
     * 统一判定杜比视界 Profile 5 是否应留在 ExoPlayer 走「芯片纯硬解 + 零拷贝 GLES 2.0 杜比着色器」：
     * 1. 原生杜比视界屏幕 + 硬解认证设备：false（走系统原生杜比硬件直出，不挂着色器）；
     * 2. 用户手动指定默认内核（Exo）或手动选了「硬解」模式：true（安卓上唯有 Exo 杜比硬解管线能同时硬解且不偏紫）；
     * 3. 用户手动选了「软解」模式（且支持 GLES 3.0+）：false（交由 MPV 多核软解 + libplacebo）；
     * 4. 所有不支持原生杜比视界的 Android TV / 机顶盒（含 64 位 HDR10 电视）：true（避免电视弱 CPU 软解 4K P5 卡顿；同时 P7/P8 仍直出 HDR10）；
     * 5. 老设备（[isLegacyDolbyDevice]）：true；
     * 6. 中低端 64 位手机遇到 4K / 高帧率 Profile 5：true（1080p P5 仍交 MPV，4K P5 留 Exo 硬解防掉帧）。
     */
    fun shouldUseExoHardwareShaderForProfile5(
        context: Context,
        format: Format?,
        decodeMode: com.aisub.player.DecodeMode = com.aisub.player.DecodeMode.AUTO,
        manualExoLocked: Boolean = false,
    ): Boolean {
        if (displaySupportsDolbyVision(context) && hasHardwareDolbyVisionDecoder(requireProfile5 = true)) {
            return false
        }
        if (manualExoLocked) return true
        if (!supportsOpenGlEs3(context)) return true
        if (decodeMode == com.aisub.player.DecodeMode.HW) return true
        if (decodeMode == com.aisub.player.DecodeMode.SW) return false
        if (isTelevisionDevice(context) || isLegacyDolbyDevice(context)) return true

        val w = format?.width ?: 0
        val h = format?.height ?: 0
        val fps = format?.frameRate ?: 0f
        val isHeavy4kOrHfr = w >= 3000 || h >= 1800 || (w >= 1900 && fps >= 55f)
        if (isHeavy4kOrHfr && !canSmoothlySoftDecode4kProfile5(context)) {
            return true
        }
        return false
    }

    fun requiresMpvForColorAccuracy(
        context: Context,
        format: Format?,
        decodeMode: com.aisub.player.DecodeMode = com.aisub.player.DecodeMode.AUTO,
    ): Boolean {
        if (!isDolbyVision(format)) return false
        val dvDisplay = displaySupportsDolbyVision(context)
        if (isProfile5(format)) {
            if (shouldUseExoHardwareShaderForProfile5(context, format, decodeMode, manualExoLocked = false)) {
                return false
            }
            return !dvDisplay || !hasHardwareDolbyVisionDecoder(requireProfile5 = true)
        }
        return false
    }

    /** 根据文件名初步猜测是否为杜比视界片源（仅用于提示文案展示，不锁死软解）。 */
    fun looksLikeDolbyVisionTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        return Regex("""(?i)(?:(?<![a-z0-9])(?:dv|dovi|dolby[\s._-]*vision)(?![a-z0-9])|杜比视界)""").containsMatchIn(title)
    }

    /** 根据文件名判断是否明确标注为无兼容底流的杜比视界 Profile 5（避免将 Profile 7/8 误判锁死软解）。 */
    fun looksLikeProfile5Title(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        return Regex("""(?i)(?:(?:dvhe|dvh1|dvav|dva1|dav1)[\s._-]*0*5\b|\bdvp5\b|\b(?:dolby[\s._-]*vision|dovi|dv)[\s._-]*(?:profile[\s._-]*)?5\b)""")
            .containsMatchIn(title)
    }

    /** 可读的杜比视界规格说明（供播放提示与视频信息面板展示）。 */
    fun describeFormat(format: Format?): String? {
        if (!isDolbyVision(format)) return null
        val p = parseProfile(format)
        return when (p) {
            5 -> "Dolby Vision Profile 5 (IPTPQc2)"
            7 -> "Dolby Vision Profile 7 (UHD Blu-ray)"
            8 -> "Dolby Vision Profile 8 (HDR10/HLG 兼容层)"
            null -> "Dolby Vision"
            else -> "Dolby Vision Profile $p"
        }
    }
}
