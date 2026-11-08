package com.aisub.player.ui

import android.graphics.RuntimeShader
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.max
import kotlin.math.min

/**
 * 液态玻璃（Liquid Glass）视觉引擎，按系统版本自动分级：
 * - Android 13+ (API 33+)：AGSL RuntimeShader 曲面透镜边缘折射高光 + 棱镜色散 + Fresnel 双高光描边 + 系统级窗口背景模糊
 * - Android 12  (API 31-32)：系统级窗口背景高斯模糊 + 双角度 Fresnel 高光折射描边 + 多层通透玻璃光泽层
 * - Android 8-11 (API 26-30)：多层半透明渐变玻璃基底 + 斜向光泽流线 + 高光边缘折射描边回退
 *
 * 设计要点：完全通过无状态的 [drawWithCache] 绘制，不在绘制阶段录制嵌套 GraphicsLayer 或修改 Snapshot 状态，
 * 避免父子组件间的 RenderNode 循环引用（libhwui 栈溢出闪退）与跨 Window（Dialog / Popup）图层引用崩溃。
 */
@Stable
class GlassBackdropState internal constructor()

val LocalGlassBackdrop = compositionLocalOf<GlassBackdropState?> { null }

@Composable
fun rememberGlassBackdrop(): GlassBackdropState {
    return remember { GlassBackdropState() }
}

/**
 * 标记作为液态玻璃背景的容器层（保持轻量无副作用，避免嵌套 RenderNode 循环引用）。
 */
fun Modifier.glassBackdropSource(@Suppress("UNUSED_PARAMETER") state: GlassBackdropState?): Modifier = this

/**
 * 提供 [LocalGlassBackdrop] 上下文的容器。
 */
@Composable
fun GlassBackdropBox(
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") captureContent: Boolean = false,
    content: @Composable BoxScope.(GlassBackdropState) -> Unit,
) {
    val backdrop = rememberGlassBackdrop()
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        Box(modifier = modifier) {
            content(backdrop)
        }
    }
}

/** 液态玻璃预设样式，覆盖导航栏、悬浮控制条、弹窗面板、卡片与胶囊角标。 */
data class GlassStyle(
    val blurRadius: Dp,
    val bevelWidth: Dp,
    val refractionStrength: Dp,
    val borderWidth: Dp,
    val lightAlpha: Float,
    val darkAlpha: Float,
    val specularIntensity: Float,
    val chromaticAberration: Float,
) {
    companion object {
        /** 顶栏、底栏、侧边导航栏 */
        val Bar = GlassStyle(
            blurRadius = 26.dp,
            bevelWidth = 14.dp,
            refractionStrength = 10.dp,
            borderWidth = 0.9.dp,
            lightAlpha = 0.72f,
            darkAlpha = 0.64f,
            specularIntensity = 0.85f,
            chromaticAberration = 1.6f,
        )

        /** 悬浮胶囊、播放器控制条、迷你播放条、阅读器工具栏 */
        val Floating = GlassStyle(
            blurRadius = 24.dp,
            bevelWidth = 16.dp,
            refractionStrength = 12.dp,
            borderWidth = 1.dp,
            lightAlpha = 0.68f,
            darkAlpha = 0.60f,
            specularIntensity = 0.95f,
            chromaticAberration = 2.0f,
        )

        /** 底部抽屉、对话框、下拉菜单、侧滑抽屉 */
        val Sheet = GlassStyle(
            blurRadius = 32.dp,
            bevelWidth = 18.dp,
            refractionStrength = 11.dp,
            borderWidth = 1.dp,
            lightAlpha = 0.80f,
            darkAlpha = 0.74f,
            specularIntensity = 0.80f,
            chromaticAberration = 1.5f,
        )

        /** 列表卡片、分组卡片、封面卡 */
        val Card = GlassStyle(
            blurRadius = 18.dp,
            bevelWidth = 12.dp,
            refractionStrength = 7.dp,
            borderWidth = 0.85.dp,
            lightAlpha = 0.74f,
            darkAlpha = 0.64f,
            specularIntensity = 0.72f,
            chromaticAberration = 1.1f,
        )

        /** 角标、时长标签、小按钮、字幕胶囊 */
        val Pill = GlassStyle(
            blurRadius = 16.dp,
            bevelWidth = 9.dp,
            refractionStrength = 6.dp,
            borderWidth = 0.8.dp,
            lightAlpha = 0.64f,
            darkAlpha = 0.56f,
            specularIntensity = 0.90f,
            chromaticAberration = 1.4f,
        )
    }
}

/**
 * 为 Dialog / ModalBottomSheet 窗口开启 Android 12+ (API 31+) 系统级窗口背景模糊（FLAG_BLUR_BEHIND）。
 */
@Composable
fun WindowBlurEffect(
    blurBehindRadius: Int = 52,
    @Suppress("UNUSED_PARAMETER") backgroundBlurRadius: Int = 68,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    DisposableEffect(view, blurBehindRadius) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            runCatching {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply {
                    this.blurBehindRadius = blurBehindRadius
                }
            }
        }
        onDispose { }
    }
}

/**
 * 核心液态玻璃修饰符：
 * 1. 绘制多层半透明玻璃染色层与斜向光泽流线；
 * 2. 绘制 AGSL 3D 曲面玻璃边缘高光折射与棱镜色散（API 33+）；
 * 3. 绘制双角度 Fresnel 高光渐变描边（API 26+ 全版本支持）。
 */
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(16.dp),
    style: GlassStyle = GlassStyle.Card,
    tint: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") enableBackdrop: Boolean = true,
): Modifier = composed {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val defaultBase = if (isDark) Color(0xFF1A222D) else Color(0xFFF7FAFF)
    val resolvedTint = if (tint != Color.Unspecified) {
        tint
    } else {
        defaultBase.copy(alpha = if (isDark) style.darkAlpha else style.lightAlpha)
    }

    val density = LocalDensity.current
    val bevelPx = with(density) { style.bevelWidth.toPx() }
    val borderPx = with(density) { style.borderWidth.toPx() }

    this
        .clip(shape)
        .drawWithCache {
            val safeW = size.width.coerceAtLeast(1f)
            val safeH = size.height.coerceAtLeast(1f)
            val endOffset = Offset(safeW, safeH)

            val outline = shape.createOutline(size, layoutDirection, this)
            val cornerRadiusPx = extractCornerRadius(outline, size)
            val clipPath = Path().apply {
                when (outline) {
                    is Outline.Generic -> addPath(outline.path)
                    is Outline.Rounded -> addRoundRect(outline.roundRect)
                    is Outline.Rectangle -> addRect(outline.rect)
                }
            }

            // 玻璃基底渐变（左上略亮，中间通透，右下微反光）
            val baseAlpha = resolvedTint.alpha.coerceIn(0.08f, 0.94f)
            val baseColor = resolvedTint.copy(alpha = 1f)
            val highlightTint = if (isDark) {
                Color.White.copy(alpha = (baseAlpha * 0.24f).coerceAtMost(0.30f))
            } else {
                Color.White.copy(alpha = (baseAlpha * 0.58f).coerceAtMost(0.75f))
            }
            val fillBrush = Brush.linearGradient(
                0f to baseColor.copy(alpha = (baseAlpha * 1.05f).coerceAtMost(0.95f)),
                0.45f to baseColor.copy(alpha = (baseAlpha * 0.88f).coerceAtLeast(0.06f)),
                1f to baseColor.copy(alpha = (baseAlpha * 0.96f).coerceAtMost(0.92f)),
                start = Offset.Zero,
                end = endOffset,
            )
            val sheenBrush = Brush.linearGradient(
                0f to highlightTint,
                0.38f to Color.Transparent,
                0.72f to Color.Transparent,
                1f to highlightTint.copy(alpha = highlightTint.alpha * 0.45f),
                start = Offset.Zero,
                end = endOffset,
            )

            // 边缘高光描边（Fresnel 双高光：左上亮白，中部冷色棱镜微光，右下柔和反光）
            val spec = style.specularIntensity
            val topRimAlpha = (if (isDark) 0.44f else 0.80f) * spec
            val midRimAlpha = (if (isDark) 0.12f else 0.26f) * spec
            val bottomRimAlpha = (if (isDark) 0.28f else 0.54f) * spec
            val rimBrush = if (borderColor != Color.Unspecified) {
                Brush.linearGradient(
                    listOf(
                        borderColor,
                        borderColor.copy(alpha = borderColor.alpha * 0.45f),
                        borderColor.copy(alpha = borderColor.alpha * 0.8f),
                    ),
                    start = Offset.Zero,
                    end = endOffset,
                )
            } else {
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = topRimAlpha.coerceIn(0f, 1f)),
                    0.35f to Color(0xFFB8DCFF).copy(alpha = midRimAlpha.coerceIn(0f, 1f)),
                    0.65f to Color(0xFFD6C8FF).copy(alpha = midRimAlpha.coerceIn(0f, 1f)),
                    1f to Color.White.copy(alpha = bottomRimAlpha.coerceIn(0f, 1f)),
                    start = Offset.Zero,
                    end = endOffset,
                )
            }

            // Android 13+ AGSL 表面液态玻璃曲面高光与色散画刷
            val agslSurfaceBrush = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                size.width > 2f &&
                size.height > 2f
            ) {
                AgslShaders.createConfiguredSurfaceBrush(
                    width = size.width,
                    height = size.height,
                    cornerRadius = cornerRadiusPx,
                    bevelWidth = bevelPx.coerceAtMost(min(size.width, size.height) * 0.45f),
                    intensity = style.specularIntensity,
                    isDark = isDark,
                )
            } else {
                null
            }

            onDrawWithContent {
                if (size.width <= 0f || size.height <= 0f) {
                    this@onDrawWithContent.drawContent()
                    return@onDrawWithContent
                }
                clipPath(clipPath) {
                    // 1. 液态玻璃半透明染色层 + 斜向光泽层
                    drawRect(brush = fillBrush)
                    drawRect(brush = sheenBrush)

                    // 2. Android 13+ AGSL 曲面透镜高光与色散层
                    if (agslSurfaceBrush != null) {
                        drawRect(brush = agslSurfaceBrush)
                    }

                    // 3. 组件自身内容
                    this@onDrawWithContent.drawContent()

                    // 4. 边缘 Fresnel 折射高光描边
                    if (borderPx > 0f && size.width > borderPx && size.height > borderPx) {
                        when (outline) {
                            is Outline.Rounded -> {
                                val rr = outline.roundRect
                                if (rr.width > borderPx && rr.height > borderPx) {
                                    drawRoundRect(
                                        brush = rimBrush,
                                        topLeft = Offset(rr.left + borderPx * 0.5f, rr.top + borderPx * 0.5f),
                                        size = Size(
                                            (rr.width - borderPx).coerceAtLeast(1f),
                                            (rr.height - borderPx).coerceAtLeast(1f),
                                        ),
                                        cornerRadius = CornerRadius(
                                            max(0f, rr.topLeftCornerRadius.x - borderPx * 0.5f),
                                            max(0f, rr.topLeftCornerRadius.y - borderPx * 0.5f),
                                        ),
                                        style = Stroke(width = borderPx),
                                    )
                                }
                            }
                            is Outline.Rectangle -> {
                                drawRect(
                                    brush = rimBrush,
                                    topLeft = Offset(borderPx * 0.5f, borderPx * 0.5f),
                                    size = Size(
                                        (size.width - borderPx).coerceAtLeast(1f),
                                        (size.height - borderPx).coerceAtLeast(1f),
                                    ),
                                    style = Stroke(width = borderPx),
                                )
                            }
                            is Outline.Generic -> {
                                drawPath(
                                    path = outline.path,
                                    brush = rimBrush,
                                    style = Stroke(width = borderPx),
                                )
                            }
                        }
                    }
                }
            }
        }
}

/**
 * 通用液态玻璃容器，可直接替代普通半透明 Box / Surface。
 */
@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    style: GlassStyle = GlassStyle.Card,
    tint: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    enableBackdrop: Boolean = true,
    onClick: (() -> Unit)? = null,
    contentAlignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val glassModifier = modifier
        .liquidGlass(
            shape = shape,
            style = style,
            tint = tint,
            borderColor = borderColor,
            enableBackdrop = enableBackdrop,
        )
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Box(
        modifier = glassModifier,
        contentAlignment = contentAlignment,
        content = content,
    )
}

private fun extractCornerRadius(outline: Outline, size: Size): Float {
    val maxR = min(size.width, size.height) * 0.5f
    if (maxR <= 0f) return 0f
    return when (outline) {
        is Outline.Rounded -> {
            val rr: RoundRect = outline.roundRect
            max(rr.topLeftCornerRadius.x, rr.bottomRightCornerRadius.x).coerceIn(0f, maxR)
        }
        is Outline.Rectangle -> 0f
        is Outline.Generic -> min(size.width, size.height) * 0.25f
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object AgslShaders {
    // AGSL 表面液态玻璃曲面高光与边缘色散着色器（全数值安全，无 pow(0, y) 或除以 0）
    private const val SURFACE_GLASS_SKSL = """
        uniform float2 size;
        uniform float cornerRadius;
        uniform float bevelWidth;
        uniform float intensity;
        uniform float isDark;

        float sdRoundBox(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + float2(r);
            return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
        }

        half4 main(float2 fragCoord) {
            float2 halfSize = max(size * 0.5, float2(1.0, 1.0));
            float2 p = fragCoord - halfSize;
            float r = clamp(cornerRadius, 0.0, min(halfSize.x, halfSize.y));
            float d = sdRoundBox(p, halfSize, r);

            float bw = max(bevelWidth, 2.0);
            // 边缘斜面因子：在 [-bw, 0] 区间内从 0 平滑升至 1
            float edge = smoothstep(-bw, 0.0, d);

            // 计算曲面法线方向（避免中心点 0/0 NaN）
            float2 inner = max(abs(p) - (halfSize - float2(r)), 0.0);
            float innerLen = length(inner);
            float2 safeP = p / halfSize;
            float safePLen = max(length(safeP), 0.0001);
            float2 signP = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
            float2 grad = (innerLen > 0.001)
                ? (inner / innerLen) * signP
                : (safeP / safePLen);

            // 模拟水滴形玻璃边缘的 3D 倾角
            float2 n2d = grad * edge * 0.85;
            float nz = sqrt(max(0.04, 1.0 - dot(n2d, n2d)));
            float3 normal = normalize(float3(n2d.x, n2d.y, nz));

            // 主光源（左上）与副反光（右下），已预先归一化
            float3 lightTopLeft = float3(-0.489, -0.584, 0.648);
            float3 lightBottomRight = float3(0.493, 0.578, 0.650);

            float d1 = max(0.0, dot(normal, lightTopLeft));
            float d2 = max(0.0, dot(normal, lightBottomRight));
            float d1_2 = d1 * d1;
            float d2_2 = d2 * d2;
            float specPrimary = d1_2 * d1_2 * d1;
            float specSecondary = d2_2 * d2_2 * d2_2;
            float oneMinusNz = clamp(1.0 - nz, 0.0, 1.0);
            float fresnel = oneMinusNz * oneMinusNz;

            // 边缘微小棱镜色散（冷暖微光）
            float3 prismColor = mix(
                float3(0.76, 0.90, 1.0),
                float3(1.0, 0.88, 0.98),
                clamp(grad.x * 0.5 + 0.5, 0.0, 1.0)
            );

            // 顶边内侧柔光流线
            float topGlow = smoothstep(max(size.y * 0.42, 1.0), 0.0, fragCoord.y) * 0.07;

            float rimStrength = (specPrimary * 0.36 + specSecondary * 0.20 + fresnel * 0.30) * edge;
            float darkScale = mix(1.0, 0.70, isDark);
            float alpha = clamp((rimStrength + topGlow) * intensity * darkScale, 0.0, 0.56);

            float3 finalRgb = mix(prismColor, float3(1.0, 1.0, 1.0), 0.65) * alpha;
            return half4(half3(finalRgb), half(alpha));
        }
    """

    fun createConfiguredSurfaceBrush(
        width: Float,
        height: Float,
        cornerRadius: Float,
        bevelWidth: Float,
        intensity: Float,
        isDark: Boolean,
    ): ShaderBrush? = runCatching {
        val shader = RuntimeShader(SURFACE_GLASS_SKSL)
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("cornerRadius", cornerRadius)
        shader.setFloatUniform("bevelWidth", bevelWidth)
        shader.setFloatUniform("intensity", intensity)
        shader.setFloatUniform("isDark", if (isDark) 1f else 0f)
        ShaderBrush(shader)
    }.getOrNull()
}
