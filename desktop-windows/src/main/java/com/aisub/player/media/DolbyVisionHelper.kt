package com.aisub.player.media

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * Windows 桌面端杜比视界（Dolby Vision）流检测辅助类：
 * 识别 `dvh1` / `dvhe` / `dav1` / `dvav`、`dvcC` / `dvvC` 初始化块与文件名特征，
 * 以便绕过不支持 `IPTPQc2` 的 LibVLC 3.x（避免紫绿偏色），交由 `libplacebo` 色调映射管线渲染。
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
        return Regex("""(?i)(?<![a-z0-9])(dv|dovi|dolby[\s._-]*vision)(?![a-z0-9])""").containsMatchIn(title)
    }

    fun describeProfile(profile: Int?): String = when (profile) {
        5 -> "Dolby Vision Profile 5 (IPTPQc2)"
        7 -> "Dolby Vision Profile 7"
        8 -> "Dolby Vision Profile 8"
        null -> "Dolby Vision"
        else -> "Dolby Vision Profile $profile"
    }
}
