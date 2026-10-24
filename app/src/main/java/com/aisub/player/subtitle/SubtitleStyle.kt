package com.aisub.player.subtitle

/**
 * 画面上一行字幕的样式。译文行、原文行各一份，可以分别调。
 * @param scale 相对基础字号（18sp）的倍率，再乘以全局字号
 * @param color ARGB
 * @param background 黑色底板的不透明度，0 为不要底板（改用描边阴影）
 */
data class LineStyle(
    val scale: Float,
    val color: Long,
    val background: Float,
    val bold: Boolean,
) {
    fun encode() = "$scale,$color,$background,$bold"

    companion object {
        val TRANSLATION = LineStyle(1f, 0xFFFFFFFF, 0.6f, true)
        val ORIGINAL = LineStyle(0.78f, 0xFFE0E0E0, 0.6f, false)

        /** 可选的字幕颜色 */
        val COLORS = listOf(
            0xFFFFFFFF to "白",
            0xFFE0E0E0 to "浅灰",
            0xFFFFE066 to "黄",
            0xFFF28C00 to "橙",
            0xFF80DEEA to "青",
            0xFFA5D6A7 to "绿",
            0xFFF48FB1 to "粉",
        )

        fun decode(s: String?, default: LineStyle): LineStyle {
            val p = s?.split(',') ?: return default
            if (p.size < 4) return default
            return LineStyle(
                scale = p[0].toFloatOrNull() ?: default.scale,
                color = p[1].toLongOrNull() ?: default.color,
                background = p[2].toFloatOrNull() ?: default.background,
                bold = p[3].toBooleanStrictOrNull() ?: default.bold,
            )
        }
    }
}
