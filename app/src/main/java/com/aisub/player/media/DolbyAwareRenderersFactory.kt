package com.aisub.player.media

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * 感知杜比视界色彩能力的自定义 Media3 [DefaultRenderersFactory]：
 * 1. 当设备屏幕不具备原生杜比视界显示能力（或缺少 Profile 5 认证硬解）时，若遇到无兼容底流的
 *    **Dolby Vision Profile 5（`IPTPQc2`）**，主动拦截默认 `MediaCodec` 视频解码器选择（同时阻止 Media3
 *    将其静默回退给普通 `video/hevc` 解码器直接上屏造成紫绿偏色），触发上层 [com.aisub.player.MainViewModel]
 *    的快速无损分流至 [MpvPlayer]（`libplacebo` 动态色调映射）；
 * 2. 当遇到带兼容底流的 **Profile 7 / 8** 且屏幕不支持杜比视界时，过滤掉半残的 `video/dolby-vision` 解码器，
 *    引导 Media3 干净回退到 `video/hevc` / `video/av01` 解码 HDR10/HLG 底流，防止色彩异常。
 */
@UnstableApi
class DolbyAwareRenderersFactory(
    private val appContext: Context,
    private val decodeModeProvider: () -> com.aisub.player.DecodeMode = { com.aisub.player.DecodeMode.AUTO },
    private val allowBlockedProfile5DirectRender: () -> Boolean = { false },
) : DefaultRenderersFactory(appContext) {

    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true)
        setMediaCodecSelector(createDolbyAwareSelector())
    }

    private fun createDolbyAwareSelector(): MediaCodecSelector {
        return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val defaultList = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            )
            if (!mimeType.startsWith("video/", ignoreCase = true)) {
                return@MediaCodecSelector defaultList
            }

            val filteredForDolby = if (mimeType.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true)) {
                val dvDisplay = DolbyVisionHelper.displaySupportsDolbyVision(appContext)
                val hwDv = DolbyVisionHelper.hasHardwareDolbyVisionDecoder(requireProfile5 = false)
                // 屏幕与硬解均原生支持杜比视界或用户手动锁死默认内核时放行，否则拦截 Profile 5 转交 MPV，Profile 7/8 回退 HEVC/AV1 底流
                if ((dvDisplay && hwDv) || allowBlockedProfile5DirectRender()) defaultList else emptyList()
            } else {
                defaultList
            }
            if (filteredForDolby.isEmpty()) {
                return@MediaCodecSelector emptyList<MediaCodecInfo>()
            }

            when (decodeModeProvider()) {
                com.aisub.player.DecodeMode.SW -> filteredForDolby.filter { isSoftwareCodec(it) }
                com.aisub.player.DecodeMode.HW -> {
                    val hw = filteredForDolby.filterNot { isSoftwareCodec(it) }
                    hw.ifEmpty { filteredForDolby }
                }
                com.aisub.player.DecodeMode.AUTO -> filteredForDolby
            }
        }
    }

    companion object {
        internal fun isSoftwareCodec(info: MediaCodecInfo): Boolean {
            val name = info.name.lowercase()
            return name.startsWith("omx.google.") ||
                name.startsWith("c2.android.") ||
                name.startsWith("c2.goldfish.") ||
                name.contains(".sw.") ||
                name.contains("sw.decoder") ||
                name.endsWith(".sw") ||
                "ffmpeg" in name
        }
    }
}
