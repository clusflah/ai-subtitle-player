package com.aisub.player.media

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * 杜比视界（Dolby Vision）与高动态范围（HDR10 / HLG / BT.2020）流检测辅助类：
 * 识别 `dvh1` / `dvhe` / `dav1` / `dvav`、`dvcC` / `dvvC` 初始化块与文件名特征，
 * 以便桌面端绕过不支持 HDR 色调映射与 `IPTPQc2` 的 LibVLC 3.x `vmem` 回调管线（避免紫绿或灰白偏色），
 * 交由 GPU `libplacebo` 动态色调映射管线精准还原色彩。
 */
object DolbyVisionHelper {

    private val DV_CODEC_REGEX = Regex("""^(?:dvhe|dvh1|dvav|dva1|dav1)\.?0*(\d+)""", RegexOption.IGNORE_CASE)
    private val DV_PREFIX_REGEX = Regex("""^(?:dvhe|dvh1|dvav|dva1|dav1)\b""", RegexOption.IGNORE_CASE)

    fun isDolbyVision(format: Format?): Boolean {
        if (format == null) return false
        if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return true
        val codecs = format.codecs?.trim().orEmpty()
        return codecs.isNotEmpty() && (DV_CODEC_REGEX.containsMatchIn(codecs) || DV_PREFIX_REGEX.containsMatchIn(codecs))
    }

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
        return null
    }

    fun isProfile5(format: Format?): Boolean {
        if (!isDolbyVision(format)) return false
        return when (parseProfile(format)) {
            5, null -> true
            else -> false
        }
    }

    fun looksLikeDolbyVisionTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        return Regex("""(?i)(?:(?<![a-z0-9])(?:dv|dovi|dolby[\s._-]*vision)(?![a-z0-9])|杜比视界)""").containsMatchIn(title)
    }

    fun looksLikeHdrOrDolbyTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        if (looksLikeDolbyVisionTitle(title)) return true
        return Regex("""(?i)(?:(?<![a-z0-9])(?:hdr10\+?|hdr|hlg|pq|bt\.?2020|rec\.?2020)(?![a-z0-9])|高动态)""").containsMatchIn(title)
    }

    fun looksLikeProfile5Title(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        return Regex("""(?i)(?:(?:dvhe|dvh1|dvav|dva1|dav1)[\s._-]*0*5\b|\bdvp5\b|\b(?:dolby[\s._-]*vision|dovi|dv)[\s._-]*(?:profile[\s._-]*)?5\b)""")
            .containsMatchIn(title)
    }

    fun describeProfile(profile: Int?): String = when (profile) {
        5 -> "Dolby Vision Profile 5 (IPTPQc2)"
        7 -> "Dolby Vision Profile 7"
        8 -> "Dolby Vision Profile 8"
        null -> "Dolby Vision"
        else -> "Dolby Vision Profile $profile"
    }
}
