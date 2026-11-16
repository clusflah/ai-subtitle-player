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
            if (!mimeType.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true)) {
                return@MediaCodecSelector defaultList
            }

            val dvDisplay = DolbyVisionHelper.displaySupportsDolbyVision(appContext)
            val hwDv = DolbyVisionHelper.hasHardwareDolbyVisionDecoder(requireProfile5 = false)

            // 屏幕与硬件解码器均原生支持杜比视界时，保留系统杜比解码管线
            if (dvDisplay && hwDv) {
                return@MediaCodecSelector defaultList
            }

            // 用户若强制手动锁死默认内核则放行，否则在非杜比屏幕上拒绝未认证的 dolby-vision 解码器，
            // 使 Profile 5 立即进入 MainViewModel 的 MPV 自动分流（不闪出一帧紫绿画面），
            // 而 Profile 7/8 由 Media3 MediaCodecUtil 自动回退至基础 HEVC/AV1 解码器。
            if (allowBlockedProfile5DirectRender()) {
                return@MediaCodecSelector defaultList
            }
            emptyList<MediaCodecInfo>()
        }
    }
}
