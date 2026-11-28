package com.aisub.player.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 全局唯一的动态主强调色（默认柔和月光暖白/流光色，随当前播放封面动态流转）。
 */
val LocalPlayerAccentColor = compositionLocalOf { Color(0xFFD8E4FF) }

/**
 * 从当前专辑封面提取的双态极光色板：
 * - 深色模式：[aurora1] / [aurora2] / [aurora3] 明度收敛在 15%–22% 区间，[accent] 保持 64%–78% 明度清晰发光；
 * - 浅色模式：[aurora1] / [aurora2] / [aurora3] 明度收敛在 82%–90% 区间呈柔雾冰川光斑，[accent] 收敛至 36%–48% 明度确保高对比度可读。
 */
data class AmbientPalette(
    val aurora1: Color,
    val aurora2: Color,
    val aurora3: Color,
    val accent: Color,
)

object AmbientPalettes {
    val DefaultDark = AmbientPalette(
        aurora1 = Color(0xFF1E293B),
        aurora2 = Color(0xFF282138),
        aurora3 = Color(0xFF1A2E2E),
        accent = Color(0xFFA8C5FF),
    )

    val DefaultLight = AmbientPalette(
        aurora1 = Color(0xFFD8E6F8),
        aurora2 = Color(0xFFE7DFF5),
        aurora3 = Color(0xFFD6ECEB),
        accent = Color(0xFF2F64C8),
    )

    val Default: AmbientPalette get() = DefaultDark

    fun defaultFor(dark: Boolean): AmbientPalette = if (dark) DefaultDark else DefaultLight

    internal fun tuneHsl(
        argb: Int,
        minLightness: Float,
        maxLightness: Float,
        minSaturation: Float,
        maxSaturation: Float,
    ): Color {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f

        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val l = (maxC + minC) / 2f
        val s = if (delta == 0f) 0f else delta / (1f - abs(2f * l - 1f))
        val h = when {
            delta == 0f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        val targetL = l.coerceIn(minLightness, maxLightness)
        val targetS = if (s < 0.05f) s else s.coerceIn(minSaturation, maxSaturation)
        return hslToColor(h, targetS, targetL)
    }

    internal fun hslToColor(h: Float, s: Float, l: Float): Color {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(
            red = (r1 + m).coerceIn(0f, 1f),
            green = (g1 + m).coerceIn(0f, 1f),
            blue = (b1 + m).coerceIn(0f, 1f),
            alpha = 1f,
        )
    }

    internal fun extractFromPixels(
        pixels: IntArray,
        dark: Boolean = true,
        fallback: AmbientPalette = defaultFor(dark),
    ): AmbientPalette {
        if (pixels.isEmpty()) return fallback
        val bucketWeight = FloatArray(12)
        val bucketR = FloatArray(12)
        val bucketG = FloatArray(12)
        val bucketB = FloatArray(12)
        var fallbackR = 0f
        var fallbackG = 0f
        var fallbackB = 0f
        var totalCount = 0

        for (p in pixels) {
            val a = (p ushr 24) and 0xFF
            if (a < 128) continue
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            fallbackR += r
            fallbackG += g
            fallbackB += b
            totalCount++

            val rf = r / 255f
            val gf = g / 255f
            val bf = b / 255f
            val maxC = max(rf, max(gf, bf))
            val minC = min(rf, min(gf, bf))
            val delta = maxC - minC
            val l = (maxC + minC) / 2f
            val s = if (delta == 0f) 0f else delta / (1f - abs(2f * l - 1f))
            if (l < 0.08f || l > 0.94f) continue
            val h = when {
                delta == 0f -> 0f
                maxC == rf -> 60f * (((gf - bf) / delta) % 6f)
                maxC == gf -> 60f * (((bf - rf) / delta) + 2f)
                else -> 60f * (((rf - gf) / delta) + 4f)
            }.let { if (it < 0f) it + 360f else it }
            val idx = ((h / 30f).toInt()).coerceIn(0, 11)
            val w = 0.25f + s * s * 2.2f
            bucketWeight[idx] += w
            bucketR[idx] += r * w
            bucketG[idx] += g * w
            bucketB[idx] += b * w
        }

        if (totalCount == 0) return fallback

        val ranked = (0 until 12)
            .filter { bucketWeight[it] > 0f }
            .sortedByDescending { bucketWeight[it] }

        val picked = mutableListOf<Int>()
        for (idx in ranked) {
            if (picked.size >= 3) break
            if (picked.none { dist -> val d = abs(dist - idx); min(d, 12 - d) <= 1 }) {
                picked += idx
            }
        }
        for (idx in ranked) {
            if (picked.size >= 3) break
            if (idx !in picked) picked += idx
        }

        fun bucketArgb(i: Int): Int {
            val w = bucketWeight[i].coerceAtLeast(1e-4f)
            val r = (bucketR[i] / w).toInt().coerceIn(0, 255)
            val g = (bucketG[i] / w).toInt().coerceIn(0, 255)
            val b = (bucketB[i] / w).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val avgArgb = (0xFF shl 24) or
            ((fallbackR / totalCount).toInt().coerceIn(0, 255) shl 16) or
            ((fallbackG / totalCount).toInt().coerceIn(0, 255) shl 8) or
            (fallbackB / totalCount).toInt().coerceIn(0, 255)

        val c1Raw = picked.getOrNull(0)?.let(::bucketArgb) ?: avgArgb
        val c2Raw = picked.getOrNull(1)?.let(::bucketArgb) ?: shiftHue(c1Raw, 42f)
        val c3Raw = picked.getOrNull(2)?.let(::bucketArgb) ?: shiftHue(c1Raw, -38f)

        return if (dark) {
            AmbientPalette(
                aurora1 = tuneHsl(c1Raw, minLightness = 0.16f, maxLightness = 0.22f, minSaturation = 0.30f, maxSaturation = 0.68f),
                aurora2 = tuneHsl(c2Raw, minLightness = 0.15f, maxLightness = 0.21f, minSaturation = 0.28f, maxSaturation = 0.64f),
                aurora3 = tuneHsl(c3Raw, minLightness = 0.15f, maxLightness = 0.20f, minSaturation = 0.26f, maxSaturation = 0.58f),
                accent = tuneHsl(c1Raw, minLightness = 0.64f, maxLightness = 0.78f, minSaturation = 0.38f, maxSaturation = 0.72f),
            )
        } else {
            AmbientPalette(
                aurora1 = tuneHsl(c1Raw, minLightness = 0.82f, maxLightness = 0.88f, minSaturation = 0.32f, maxSaturation = 0.62f),
                aurora2 = tuneHsl(c2Raw, minLightness = 0.84f, maxLightness = 0.90f, minSaturation = 0.28f, maxSaturation = 0.58f),
                aurora3 = tuneHsl(c3Raw, minLightness = 0.83f, maxLightness = 0.89f, minSaturation = 0.26f, maxSaturation = 0.54f),
                accent = tuneHsl(c1Raw, minLightness = 0.36f, maxLightness = 0.48f, minSaturation = 0.45f, maxSaturation = 0.78f),
            )
        }
    }

    private fun shiftHue(argb: Int, deg: Float): Int {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val l = (maxC + minC) / 2f
        val s = if (delta == 0f) 0.35f else delta / (1f - abs(2f * l - 1f))
        val h = when {
            delta == 0f -> 210f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { ((it + deg) % 360f + 360f) % 360f }
        return hslToColor(h, s.coerceIn(0.25f, 0.7f), l.coerceIn(0.2f, 0.5f)).toArgb()
    }

    fun extract(
        bmp: Bitmap?,
        dark: Boolean = true,
        fallback: AmbientPalette = defaultFor(dark),
    ): AmbientPalette {
        if (bmp == null || bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return fallback
        return runCatching {
            val safeBmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bmp.config == Bitmap.Config.HARDWARE) {
                bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return fallback
            } else {
                bmp
            }
            val scaled = Bitmap.createScaledBitmap(safeBmp, 20, 20, true)
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            if (scaled !== safeBmp) scaled.recycle()
            if (safeBmp !== bmp) safeBmp.recycle()
            extractFromPixels(pixels, dark = dark, fallback = fallback)
        }.getOrDefault(fallback)
    }
}

/**
 * 音乐主界面统一的深/浅双态前景与容器语义色板，确保主播放器音乐页完整适配浅色与深色模式。
 */
data class PlayerSurfaceTokens(
    val isDark: Boolean,
    val baseBg: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val pillBg: Color,
    val pillSelectedBg: Color,
    val focusRing: Color,
    val playBtnBg: Color,
    val playBtnActiveBg: Color,
    val borderTop: Color,
    val borderMid: Color,
    val borderBottom: Color,
    val seekTrackBg: Color,
    val seekTrackDragBg: Color,
    val miniBarBg: Color,
    val miniBarShadowAmbient: Color,
    val miniBarShadowSpot: Color,
    val lyricGlowColor: Color,
)

private val DarkPlayerSurfaceTokens = PlayerSurfaceTokens(
    isDark = true,
    baseBg = Color(0xFF0A0C10),
    inkPrimary = Color.White,
    inkSecondary = Color(0x9EFFFFFF),
    inkMuted = Color(0x6EFFFFFF),
    pillBg = Color(0x14FFFFFF),
    pillSelectedBg = Color(0x21FFFFFF),
    focusRing = Color.White,
    playBtnBg = Color(0x14FFFFFF),
    playBtnActiveBg = Color(0x1CFFFFFF),
    borderTop = Color(0x5EFFFFFF),
    borderMid = Color(0x16FFFFFF),
    borderBottom = Color(0x0AFFFFFF),
    seekTrackBg = Color(0x24FFFFFF),
    seekTrackDragBg = Color(0x38FFFFFF),
    miniBarBg = Color(0xCC11151E),
    miniBarShadowAmbient = Color(0x66000000),
    miniBarShadowSpot = Color(0x88000000),
    lyricGlowColor = Color(0x6BFFFFFF),
)

private val LightPlayerSurfaceTokens = PlayerSurfaceTokens(
    isDark = false,
    baseBg = Color(0xFFF3F5FA),
    inkPrimary = Color(0xFF14171F),
    inkSecondary = Color(0xFF525866),
    inkMuted = Color(0xFF848C9C),
    pillBg = Color(0x1214171F),
    pillSelectedBg = Color(0xC8FFFFFF),
    focusRing = Color(0xFF14171F),
    playBtnBg = Color(0x9EFFFFFF),
    playBtnActiveBg = Color(0xCCFFFFFF),
    borderTop = Color(0xEBFFFFFF),
    borderMid = Color(0x2214171F),
    borderBottom = Color(0x1A14171F),
    seekTrackBg = Color(0x1C14171F),
    seekTrackDragBg = Color(0x2E14171F),
    miniBarBg = Color(0xE6FFFFFF),
    miniBarShadowAmbient = Color(0x240F172A),
    miniBarShadowSpot = Color(0x330F172A),
    lyricGlowColor = Color(0x2814171F),
)

fun playerSurfaceTokens(isDark: Boolean): PlayerSurfaceTokens =
    if (isDark) DarkPlayerSurfaceTokens else LightPlayerSurfaceTokens

/**
 * 顶部与底部滚动渐隐遮罩（Edge Fade Mask）。
 */
fun Modifier.verticalEdgeFade(
    topFade: Dp = 28.dp,
    bottomFade: Dp = 0.dp,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height
        if (h <= 1f) return@drawWithContent
        val topPx = topFade.toPx().coerceIn(0f, h * 0.45f)
        val bottomPx = bottomFade.toPx().coerceIn(0f, h * 0.45f)
        if (topPx <= 0f && bottomPx <= 0f) return@drawWithContent
        val topStop = (topPx / h).coerceIn(0f, 0.45f)
        val bottomStop = (1f - bottomPx / h).coerceIn(0.55f, 1f)
        val stops = buildList {
            if (topStop > 0f) {
                add(0f to Color.Transparent)
                add(topStop to Color.Black)
            } else {
                add(0f to Color.Black)
            }
            if (bottomStop < 1f) {
                add(bottomStop to Color.Black)
                add(1f to Color.Transparent)
            } else {
                add(1f to Color.Black)
            }
        }.toTypedArray()
        drawRect(
            brush = Brush.verticalGradient(*stops),
            blendMode = BlendMode.DstIn,
        )
    }
