package com.aisub.player.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 液态玻璃（Liquid Glass）视觉与物理动画引擎：
 * - 支持真实兄弟图层背景高斯模糊（仅在悬浮底栏/顶栏等兄弟节点上开启 [sampleBackdrop]，杜绝父子 RenderNode 循环引用与 Snapshot 状态写入）
 * - 支持双弹簧水滴拉伸形变（Leading / Trailing Edge 双刚度弹簧 + 速度弹性缩放）
 * - Android 13+ (API 33+)：AGSL RuntimeShader 曲面透镜边缘折射高光 + 棱镜色散 + Fresnel 双高光描边
 * - Android 12  (API 31-32)：RenderEffect 实时背景模糊 + 双角度 Fresnel 高光折射描边
 * - Android 8-11 (API 26-30)：多层半透明渐变玻璃基底 + 斜向光泽流线 + 高光边缘折射描边
 */
@Stable
class GlassBackdropState internal constructor(
    internal val contentLayer: GraphicsLayer,
) {
    // 使用普通字段而非 mutableStateOf，绝不在绘制或布局阶段触发 Compose Snapshot 写入异常
    internal var sourceRootOffset: Offset = Offset.Zero
    internal var sourceSize: Size = Size.Zero
    internal var hasRecorded: Boolean = false
}

val LocalGlassBackdrop = compositionLocalOf<GlassBackdropState?> { null }

@Composable
fun rememberGlassBackdrop(): GlassBackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdropState(layer) }
}

/**
 * 标记作为液态玻璃背景源的内容层（必须与开启 `sampleBackdrop = true` 的悬浮栏处于兄弟层级，不可嵌套）。
 */
fun Modifier.glassBackdropSource(state: GlassBackdropState?): Modifier = composed {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || state == null || !LocalLiquidGlassEnabled.current) return@composed this
    this
        .onGloballyPositioned { coords: LayoutCoordinates ->
            if (coords.isAttached) {
                state.sourceRootOffset = coords.positionInRoot()
                state.sourceSize = Size(coords.size.width.toFloat(), coords.size.height.toFloat())
            }
        }
        .drawWithContent {
            val w = size.width
            val h = size.height
            if (w > 1f && h > 1f) {
                state.contentLayer.record {
                    this@drawWithContent.drawContent()
                }
                state.hasRecorded = true
                drawLayer(state.contentLayer)
            } else {
                this@drawWithContent.drawContent()
            }
        }
}

/**
 * 提供 [LocalGlassBackdrop] 上下文的容器。
 */
@Composable
fun GlassBackdropBox(
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") captureContent: Boolean = false,
    content: @Composable BoxScope.(GlassBackdropState) -> Unit,
) {
    val enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocalLiquidGlassEnabled.current
    val backdrop = rememberGlassBackdrop()
    CompositionLocalProvider(LocalGlassBackdrop provides if (enabled) backdrop else null) {
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
            blurRadius = 28.dp,
            bevelWidth = 14.dp,
            refractionStrength = 10.dp,
            borderWidth = 0.9.dp,
            lightAlpha = 0.76f,
            darkAlpha = 0.66f,
            specularIntensity = 0.85f,
            chromaticAberration = 1.6f,
        )

        /** 悬浮胶囊、底部液态导航栏、播放器控制条、迷你播放条 */
        val Floating = GlassStyle(
            blurRadius = 30.dp,
            bevelWidth = 16.dp,
            refractionStrength = 12.dp,
            borderWidth = 1.dp,
            lightAlpha = 0.74f,
            darkAlpha = 0.64f,
            specularIntensity = 0.96f,
            chromaticAberration = 2.0f,
        )

        /** 底部抽屉、对话框、下拉菜单、侧滑抽屉 */
        val Sheet = GlassStyle(
            blurRadius = 32.dp,
            bevelWidth = 18.dp,
            refractionStrength = 11.dp,
            borderWidth = 1.dp,
            lightAlpha = 0.84f,
            darkAlpha = 0.76f,
            specularIntensity = 0.82f,
            chromaticAberration = 1.5f,
        )

        /** 列表卡片、分组卡片、封面卡 */
        val Card = GlassStyle(
            blurRadius = 18.dp,
            bevelWidth = 12.dp,
            refractionStrength = 7.dp,
            borderWidth = 0.85.dp,
            lightAlpha = 0.82f,
            darkAlpha = 0.66f,
            specularIntensity = 0.72f,
            chromaticAberration = 1.1f,
        )

        /** 角标、时长标签、小按钮、字幕胶囊、水滴指示器 */
        val Pill = GlassStyle(
            blurRadius = 16.dp,
            bevelWidth = 10.dp,
            refractionStrength = 6.dp,
            borderWidth = 0.85.dp,
            lightAlpha = 0.72f,
            darkAlpha = 0.58f,
            specularIntensity = 0.92f,
            chromaticAberration = 1.4f,
        )
    }
}

/**
 * 全局统一的液态玻璃语义色板与柔和弥散阴影 Token，消除各页面散落的硬编码半透明色差。
 */
object GlassTokens {
    /** 悬浮胶囊底栏、侧边栏、播放控制坞、迷你播放条基底色 */
    val DarkDockTint = Color(0xBF181F2C)
    val LightDockTint = Color(0xDCF7F9FC)

    /** 导航栏/控制坞内滑动水滴指示器色 */
    val DarkDropletTint = Color(0xE02E3B52)
    val LightDropletTint = Color(0xF0E6EEF8)

    /** 底部抽屉、对话框、下拉菜单、侧滑面板基底色 */
    val DarkSheetTint = Color(0xD4181F2C)
    val LightSheetTint = Color(0xDCF7F9FC)

    /** 悬浮提示框、进度预览窗、片头跳过、状态气泡（深色沉浸场景） */
    val DarkOverlayTint = Color(0xB8181F2C)

    /** 视频/全屏画面上的半透明独立圆形控制按钮（锁屏、退出全屏、播放圆钮） */
    val DarkControlTint = Color(0x78181F2C)

    /** 缩略图/海报/漫画右下角的小角标（时长、评分、页码、日期） */
    val DarkBadgeTint = Color(0x8C121824)

    /** 导航选中态与水滴高光强调色 */
    val DarkActiveBlue = Color(0xFF4DA3FF)
    val LightActiveBlue = Color(0xFF007AFF)
}

/**
 * 与 [liquidGlass] 配套的统一柔和弥散阴影修饰符，根据 [GlassStyle] 自动匹配高度与环境光透明度。
 */
fun Modifier.glassShadow(
    shape: Shape,
    style: GlassStyle = GlassStyle.Floating,
    elevation: Dp = Dp.Unspecified,
): Modifier = composed {
    if (!LocalLiquidGlassEnabled.current) {
        val classicElevation = when {
            elevation != Dp.Unspecified -> elevation
            style == GlassStyle.Sheet -> 6.dp
            style == GlassStyle.Floating -> 4.dp
            style == GlassStyle.Bar -> 2.dp
            else -> 0.dp
        }
        return@composed if (classicElevation <= 0.dp) this else this.shadow(classicElevation, shape)
    }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val resolvedElevation = if (elevation != Dp.Unspecified) {
        elevation
    } else {
        when (style) {
            GlassStyle.Sheet -> if (isDark) 20.dp else 16.dp
            GlassStyle.Floating -> if (isDark) 16.dp else 12.dp
            GlassStyle.Bar -> if (isDark) 10.dp else 8.dp
            GlassStyle.Card -> if (isDark) 4.dp else 3.dp
            else -> if (isDark) 4.dp else 3.dp
        }
    }
    if (resolvedElevation <= 0.dp) {
        this
    } else {
        this.shadow(
            elevation = resolvedElevation,
            shape = shape,
            ambientColor = if (isDark) Color(0x28000000) else Color(0x16000000),
            spotColor = if (isDark) Color(0x38000000) else Color(0x22000000),
        )
    }
}

/**
 * 为 Dialog / ModalBottomSheet / Popup 窗口开启 Android 12+ (API 31+) 系统级窗口背景模糊（FLAG_BLUR_BEHIND）。
 */
@Composable
fun WindowBlurEffect(
    blurBehindRadius: Int = 52,
    @Suppress("UNUSED_PARAMETER") backgroundBlurRadius: Int = 68,
) {
    if (!LocalLiquidGlassEnabled.current) return
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

private class SelfPositionHolder {
    var offsetInRoot: Offset = Offset.Zero
}

/**
 * 核心液态玻璃修饰符：
 * @param sampleBackdrop 是否采样 [LocalGlassBackdrop] 的实时背景模糊。默认 false（防止子卡片递归引用父容器图层）；
 *                       仅在悬浮底栏等与内容层处于兄弟关系的浮层上设为 true。
 * @param lightShift     液态高光与棱镜折射相位偏移（随水滴移动、按压或弹簧动画变化，驱动表面光泽扫过与边缘折射旋转）。
 */
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(16.dp),
    style: GlassStyle = GlassStyle.Card,
    tint: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    @Suppress("UNUSED_PARAMETER") enableBackdrop: Boolean = true,
    sampleBackdrop: Boolean = false,
    lightShift: Float = 0f,
): Modifier = composed {
    if (!LocalLiquidGlassEnabled.current) {
        val classicFill = when (tint) {
            Color.Unspecified -> when (style) {
                GlassStyle.Card -> MaterialTheme.colorScheme.surfaceContainerLowest
                GlassStyle.Bar, GlassStyle.Sheet -> MaterialTheme.colorScheme.surfaceContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            }
            GlassTokens.DarkBadgeTint -> Color(0x99000000)
            GlassTokens.DarkControlTint -> Color(0x66000000)
            GlassTokens.DarkOverlayTint -> Color(0xCC000000)
            GlassTokens.DarkSheetTint -> Color(0xFF22252A)
            GlassTokens.DarkDockTint -> Color(0xE01B1E24)
            GlassTokens.DarkDropletTint -> Color(0xFF333842)
            GlassTokens.LightDockTint, GlassTokens.LightSheetTint -> MaterialTheme.colorScheme.surfaceContainerHigh
            GlassTokens.LightDropletTint -> MaterialTheme.colorScheme.secondaryContainer
            else -> if (tint.alpha >= 0.65f) tint.copy(alpha = 1f) else tint
        }
        return@composed this
            .clip(shape)
            .background(classicFill, shape)
            .let { mod ->
                if (borderColor != Color.Unspecified && style.borderWidth > 0.dp) {
                    mod.border(style.borderWidth, borderColor, shape)
                } else {
                    mod
                }
            }
    }
    val palette = currentVisualPalette()
    val defaultBase = palette.glassBase
    val resolvedTint = if (tint != Color.Unspecified) {
        tint
    } else {
        defaultBase.copy(alpha = if (palette.isDark) style.darkAlpha else style.lightAlpha)
    }
    val isDark = palette.isDark || resolvedTint.copy(alpha = 1f).luminance() < 0.35f
    val rimPrimary = if (isDark && !palette.isDark) Color(0xFFA8D8FF) else palette.rimMidPrimary
    val rimSecondary = if (isDark && !palette.isDark) Color(0xFFDCC4FF) else palette.rimMidSecondary

    val density = LocalDensity.current
    val bevelPx = with(density) { style.bevelWidth.toPx() }
    val borderPx = with(density) { style.borderWidth.toPx() }
    val blurPx = with(density) { style.blurRadius.toPx() }

    val backdrop = if (sampleBackdrop) LocalGlassBackdrop.current else null
    val posHolder = remember { SelfPositionHolder() }
    val blurredBackdropLayer = if (sampleBackdrop && backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        rememberGraphicsLayer()
    } else {
        null
    }
    val blurRenderEffect = remember(blurPx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurPx > 0.5f) {
            RenderEffect
                .createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        } else {
            null
        }
    }
    val runtimeShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AgslShaders.newSurfaceShader()
        } else {
            null
        }
    }

    this
        .let { mod ->
            if (blurredBackdropLayer != null) {
                mod.onGloballyPositioned { coords ->
                    if (coords.isAttached) {
                        posHolder.offsetInRoot = coords.positionInRoot()
                    }
                }
            } else {
                mod
            }
        }
        .clip(shape)
        .drawWithCache {
            val safeW = size.width.coerceAtLeast(1f)
            val safeH = size.height.coerceAtLeast(1f)
            val endOffset = Offset(safeW, safeH)
            val safeLightShift = if (lightShift.isFinite()) lightShift else 0f
            val shiftWave = sin(safeLightShift * 1.6f)
            val shiftCos = cos(safeLightShift * 1.2f)
            val sheenStart = Offset(
                x = (safeW * (0.18f + shiftWave * 0.28f)).coerceIn(0f, safeW),
                y = 0f,
            )
            val sheenEnd = Offset(
                x = (safeW * (0.82f + shiftCos * 0.24f)).coerceIn(0.1f, safeW),
                y = safeH,
            )

            if (blurredBackdropLayer != null && blurRenderEffect != null) {
                blurredBackdropLayer.renderEffect = blurRenderEffect
            }

            val outline = shape.createOutline(size, layoutDirection, this)
            val cornerRadiiPx = extractCornerRadii(outline, size)
            val clipPath = Path().apply {
                when (outline) {
                    is Outline.Generic -> addPath(outline.path)
                    is Outline.Rounded -> addRoundRect(outline.roundRect)
                    is Outline.Rectangle -> addRect(outline.rect)
                }
            }
            val hasTopRound = max(cornerRadiiPx[0], cornerRadiiPx[1]) >= 0.5f
            val hasBottomRound = max(cornerRadiiPx[2], cornerRadiiPx[3]) >= 0.5f
            val topFlush = !hasTopRound && hasBottomRound
            val bottomFlush = hasTopRound && !hasBottomRound

            val strokePath = if (borderPx > 0f && size.width > borderPx && size.height > borderPx) {
                val inset = borderPx * 0.5f
                Path().apply {
                    when (outline) {
                        is Outline.Generic -> addPath(outline.path)
                        is Outline.Rounded -> {
                            val rr = outline.roundRect
                            if (rr.width > borderPx && rr.height > borderPx) {
                                fun shrink(c: CornerRadius) = CornerRadius(
                                    max(0f, c.x - inset),
                                    max(0f, c.y - inset),
                                )
                                addRoundRect(
                                    RoundRect(
                                        left = rr.left + inset,
                                        top = if (topFlush) rr.top - borderPx * 2f else rr.top + inset,
                                        right = rr.right - inset,
                                        bottom = if (bottomFlush) rr.bottom + borderPx * 2f else rr.bottom - inset,
                                        topLeftCornerRadius = shrink(rr.topLeftCornerRadius),
                                        topRightCornerRadius = shrink(rr.topRightCornerRadius),
                                        bottomRightCornerRadius = shrink(rr.bottomRightCornerRadius),
                                        bottomLeftCornerRadius = shrink(rr.bottomLeftCornerRadius),
                                    ),
                                )
                            }
                        }
                        is Outline.Rectangle -> {
                            val r = outline.rect
                            if (r.width > borderPx && r.height > borderPx) {
                                addRect(
                                    Rect(
                                        left = r.left + inset,
                                        top = r.top + inset,
                                        right = r.right - inset,
                                        bottom = r.bottom - inset,
                                    ),
                                )
                            }
                        }
                    }
                }
            } else {
                null
            }

            // 玻璃基底渐变（左上略亮，中间通透，右下微反光）
            val baseAlpha = resolvedTint.alpha.coerceIn(0.08f, 0.94f)
            val baseColor = resolvedTint.copy(alpha = 1f)
            val highlightTint = if (isDark) {
                Color.White.copy(alpha = (baseAlpha * 0.30f).coerceAtMost(0.42f))
            } else {
                Color.White.copy(alpha = (baseAlpha * 0.72f).coerceAtMost(0.88f))
            }
            val fillBrush = Brush.linearGradient(
                0f to baseColor.copy(alpha = (baseAlpha * 1.04f).coerceAtMost(0.95f)),
                0.45f to baseColor.copy(alpha = (baseAlpha * 0.84f).coerceAtLeast(0.06f)),
                1f to baseColor.copy(alpha = (baseAlpha * 0.95f).coerceAtMost(0.92f)),
                start = Offset.Zero,
                end = endOffset,
            )
            val sheenCenter = (0.34f + shiftWave * 0.16f).coerceIn(0.16f, 0.56f)
            val sheenBrush = Brush.linearGradient(
                0f to highlightTint,
                sheenCenter to highlightTint.copy(alpha = highlightTint.alpha * 0.32f),
                (sheenCenter + 0.28f).coerceAtMost(0.92f) to Color.Transparent,
                1f to highlightTint.copy(alpha = highlightTint.alpha * 0.56f),
                start = sheenStart,
                end = sheenEnd,
            )

            // 边缘高光描边（Fresnel 双高光：左上亮白，中部冷色棱镜微光，右下柔和反光）
            val spec = style.specularIntensity
            val topRimAlpha = (if (isDark) 0.54f else 0.92f) * spec
            val midRimAlpha = (if (isDark) 0.20f else 0.38f) * spec
            val bottomRimAlpha = (if (isDark) 0.36f else 0.64f) * spec
            val rimBrush = if (borderColor != Color.Unspecified) {
                Brush.linearGradient(
                    listOf(
                        borderColor,
                        borderColor.copy(alpha = borderColor.alpha * 0.45f),
                        borderColor.copy(alpha = borderColor.alpha * 0.85f),
                    ),
                    start = sheenStart,
                    end = sheenEnd,
                )
            } else {
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = topRimAlpha.coerceIn(0f, 1f)),
                    0.35f to rimPrimary.copy(alpha = midRimAlpha.coerceIn(0f, 1f)),
                    0.65f to rimSecondary.copy(alpha = midRimAlpha.coerceIn(0f, 1f)),
                    1f to Color.White.copy(alpha = bottomRimAlpha.coerceIn(0f, 1f)),
                    start = sheenStart,
                    end = sheenEnd,
                )
            }

            // Android 13+ AGSL 表面液态玻璃曲面高光与色散画刷（复用已编译的 RuntimeShader，避免动画帧重复创建）
            val agslSurfaceBrush = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                runtimeShader != null &&
                size.width > 2f &&
                size.height > 2f
            ) {
                AgslShaders.configureSurfaceBrush(
                    shader = runtimeShader,
                    width = size.width,
                    height = size.height,
                    cornerRadii = cornerRadiiPx,
                    bevelWidth = bevelPx.coerceAtMost(min(size.width, size.height) * 0.45f),
                    intensity = style.specularIntensity,
                    isDark = isDark,
                    lightPhase = safeLightShift,
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
                    // 0. 实时背景模糊层（仅针对开启 sampleBackdrop 的兄弟浮层）
                    if (blurredBackdropLayer != null && backdrop != null && backdrop.hasRecorded) {
                        val dx = backdrop.sourceRootOffset.x - posHolder.offsetInRoot.x
                        val dy = backdrop.sourceRootOffset.y - posHolder.offsetInRoot.y
                        runCatching {
                            blurredBackdropLayer.record {
                                translate(left = dx, top = dy) {
                                    drawLayer(backdrop.contentLayer)
                                }
                            }
                            drawLayer(blurredBackdropLayer)
                        }
                    }

                    // 1. 液态玻璃半透明染色层 + 斜向光泽层
                    drawRect(brush = fillBrush)
                    drawRect(brush = sheenBrush)

                    // 2. Android 13+ AGSL 曲面透镜高光与色散层
                    if (agslSurfaceBrush != null) {
                        drawRect(brush = agslSurfaceBrush)
                    }

                    // 3. 组件自身内容
                    this@onDrawWithContent.drawContent()

                    // 4. 边缘 Fresnel 折射高光描边（完整遵循四角独立圆角）
                    if (strokePath != null) {
                        drawPath(
                            path = strokePath,
                            brush = rimBrush,
                            style = Stroke(width = borderPx),
                        )
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
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!LocalLiquidGlassEnabled.current) {
        var focusedNode by remember { mutableStateOf(false) }
        val tv = LocalTv.current
        val focused = tv && onClick != null && focusedNode
        val effectiveTint = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.38f) else tint
        val effectiveBorder = if (focused) MaterialTheme.colorScheme.primary else borderColor
        val classicMod = modifier
            .let { if (onClick != null) it.onFocusChanged { focusedNode = it.isFocused } else it }
            .liquidGlass(
                shape = shape,
                style = style,
                tint = effectiveTint,
                borderColor = effectiveBorder,
                enableBackdrop = false,
            )
            .let {
                if (onClick != null) {
                    it.tvFocusRing(shape = shape).clickable(onClick = onClick)
                } else it
            }
        Box(
            modifier = classicMod,
            contentAlignment = contentAlignment,
            content = content,
        )
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    var focusedNode by remember { mutableStateOf(false) }
    val tv = LocalTv.current
    val focused = tv && onClick != null && (isFocused || focusedNode)

    val scaleX by animateFloatAsState(
        targetValue = when {
            onClick != null && isPressed -> 0.97f
            focused -> 1.02f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.54f, stiffness = 380f),
        label = "glassBoxScaleX",
    )
    val scaleY by animateFloatAsState(
        targetValue = when {
            onClick != null && isPressed -> 0.95f
            focused -> 1.02f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.50f, stiffness = 340f),
        label = "glassBoxScaleY",
    )
    val lightShift by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.8f
            focused -> 0.4f
            else -> 0f
        },
        animationSpec = spring(dampingRatio = 0.56f, stiffness = 280f),
        label = "glassBoxLight",
    )
    val effectiveTint = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.38f) else tint
    val effectiveBorder = if (focused) MaterialTheme.colorScheme.primary else borderColor
    val glassModifier = modifier
        .let {
            if (onClick != null) {
                it.onFocusChanged { focusedNode = it.isFocused }
                    .graphicsLayer {
                        this.scaleX = scaleX
                        this.scaleY = scaleY
                    }
            } else it
        }
        .liquidGlass(
            shape = shape,
            style = style,
            tint = effectiveTint,
            borderColor = effectiveBorder,
            enableBackdrop = enableBackdrop,
            lightShift = lightShift,
        )
        .let {
            if (onClick != null) {
                it.tvFocusRing(shape = shape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
            } else it
        }
    Box(
        modifier = glassModifier,
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * 顶栏右侧独立圆形液态玻璃按钮（带柔和的水滴按压形变与回弹动画，TV 端选中时切换为醒目品牌橙色高亮）。
 */
@Composable
fun LiquidGlassIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (!LocalLiquidGlassEnabled.current) {
        IconButton(
            onClick = onClick,
            modifier = modifier.size(size).tvFocusRing(shape = CircleShape),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
            )
        }
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    var focusedNode by remember { mutableStateOf(false) }
    val tv = LocalTv.current
    val focused = tv && (isFocused || focusedNode)

    val scaleX by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.90f
            focused -> 1.08f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.50f, stiffness = 360f),
        label = "glassBtnScaleX",
    )
    val scaleY by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.86f
            focused -> 1.08f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.48f, stiffness = 320f),
        label = "glassBtnScaleY",
    )
    val lightShift by animateFloatAsState(
        targetValue = when {
            isPressed -> 1.0f
            focused -> 0.6f
            else -> 0f
        },
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 260f),
        label = "glassBtnLight",
    )
    val palette = currentVisualPalette()
    val isDark = palette.isDark
    val baseGlassTint = palette.iconButtonTint
    val glassTint by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.90f) else baseGlassTint,
        animationSpec = tween(durationMillis = 150),
        label = "glassBtnTint",
    )
    val iconTint by animateColorAsState(
        targetValue = if (focused) Color.White else tint,
        animationSpec = tween(durationMillis = 150),
        label = "glassBtnIconTint",
    )

    Box(
        modifier = modifier
            .size(size)
            .onFocusChanged { focusedNode = it.isFocused }
            .graphicsLayer {
                this.scaleX = scaleX
                this.scaleY = scaleY
            }
            .shadow(
                elevation = if (focused) 10.dp else if (isDark) 6.dp else 4.dp,
                shape = CircleShape,
                ambientColor = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else Color(0x1F000000),
                spotColor = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else Color(0x29000000),
            )
            .liquidGlass(
                shape = CircleShape,
                style = GlassStyle.Floating,
                tint = glassTint,
                borderColor = if (focused) Color.White else Color.Unspecified,
                lightShift = lightShift,
            )
            .tvFocusRing(shape = CircleShape, color = Color.White)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/**
 * 悬浮胶囊液态玻璃底部导航栏（仿演示视频 UI 与克制柔和的水滴拉伸、挤压与果冻回弹动画）：
 * - 外层为全圆角胶囊悬浮条，实时模糊下方滚过的页面内容，并随水滴运动产生微弹共振；
 * - 选中项指示器为包裹「图标 + 文字」的完整液态玻璃水滴胶囊；
 * - 切换标签或手指滑动时，水滴前缘跃出、后缘牵引拉长，纵向适度收缩，落定瞬间产生柔和回弹与光泽扫过。
 */
data class LiquidNavTabItem<T>(
    val value: T,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun <T> LiquidGlassBottomBar(
    items: List<LiquidNavTabItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!LocalLiquidGlassEnabled.current) {
        NavigationBar(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            tonalElevation = 0.dp,
        ) {
            items.forEach { item ->
                NavigationBarItem(
                    selected = item.value == selected,
                    onClick = { onSelect(item.value) },
                    icon = { Icon(item.icon, contentDescription = item.label) },
                    label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }
        return
    }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val selectedIndex = items.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    var touching by remember { mutableStateOf(false) }

    // 三弹簧驱动（略微调弱幅度与振荡，使手感更紧致自然）：前缘跃出 + 后缘牵引 + 落定微弹
    val fastPos by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 560f),
        label = "dropletFast",
    )
    val slowPos by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 135f),
        label = "dropletSlow",
    )
    val jellyPos by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.46f, stiffness = 210f),
        label = "dropletJelly",
    )
    val touchPress by animateFloatAsState(
        targetValue = if (touching) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.54f, stiffness = 380f),
        label = "barTouchPress",
    )

    val rawStretch = abs(fastPos - slowPos)
    val wobble = fastPos - jellyPos
    val lightPhase = fastPos * 0.9f + rawStretch * 1.0f + wobble * 0.65f
    val barScaleX = 1f + (rawStretch * 0.018f).coerceAtMost(0.030f) + touchPress * 0.010f
    val barScaleY = 1f - (rawStretch * 0.014f).coerceAtMost(0.022f) + touchPress * 0.014f

    val pillShape = RoundedCornerShape(50)
    val palette = currentVisualPalette()
    val barTint = palette.dockTint
    val dropletTint = palette.dropletTint
    val activeColor = palette.navActiveColor
    val inactiveColor = palette.navInactiveColor

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(68.dp)
            .graphicsLayer {
                scaleX = barScaleX
                scaleY = barScaleY
            }
            .shadow(
                elevation = if (isDark) 18.dp else 14.dp,
                shape = pillShape,
                ambientColor = Color(0x28000000),
                spotColor = Color(0x36000000),
            )
            .liquidGlass(
                shape = pillShape,
                style = GlassStyle.Floating,
                tint = barTint,
                sampleBackdrop = true,
                lightShift = lightPhase * 0.5f,
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        // 1. 底层滑动、拉长与柔和回弹的液态玻璃水滴胶囊指示器（始终完整包裹图标和文字）
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val count = items.size.coerceAtLeast(1)
            val safeMaxW = maxWidth.coerceAtLeast(1.dp)
            val slotWidth = (safeMaxW / count).coerceIn(1.dp, safeMaxW)
            val leftEdgeIndex = min(fastPos, slowPos)
            val rightEdgeIndex = max(fastPos, slowPos)
            val stretch = (rightEdgeIndex - leftEdgeIndex).coerceIn(0f, count.toFloat())
            // 适度水滴拉伸感，并在落定瞬间通过 wobble 产生柔和回弹
            val extraWidthFactor = (stretch * 1.05f + abs(wobble) * 0.24f + touchPress * 0.05f).coerceIn(0f, count.toFloat())
            val centerShift = (extraWidthFactor - stretch) * 0.5f
            val dropletWidth = (slotWidth * (1f + extraWidthFactor)).coerceIn(slotWidth, safeMaxW)
            val maxLeft = (safeMaxW - dropletWidth).coerceAtLeast(0.dp)
            val dropletLeft = (slotWidth * (leftEdgeIndex - centerShift)).coerceIn(0.dp, maxLeft)
            val scaleY = (1f - (stretch * 0.12f).coerceAtMost(0.14f) + abs(wobble) * 0.06f + touchPress * 0.025f)
                .coerceIn(0.86f, 1.04f)

            Box(
                Modifier
                    .padding(start = dropletLeft)
                    .size(width = dropletWidth, height = maxHeight)
                    .graphicsLayer {
                        this.scaleY = scaleY
                    }
                    .shadow(
                        elevation = if (isDark) 8.dp else 6.dp,
                        shape = pillShape,
                        ambientColor = activeColor.copy(alpha = 0.22f),
                        spotColor = activeColor.copy(alpha = 0.32f),
                    )
                    .liquidGlass(
                        shape = pillShape,
                        style = GlassStyle.Floating,
                        tint = dropletTint,
                        borderColor = if (isDark) Color(0x88FFFFFF) else Color(0xEEFFFFFF),
                        lightShift = lightPhase,
                    ),
            )
        }

        // 2. 顶层各标签图标与文字（紧凑居中排布，确保完全处于液态玻璃水滴内部）
        Row(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(items) {
                    if (items.isEmpty()) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        touching = true
                        try {
                            val count = items.size.coerceAtLeast(1)
                            val maxIdx = (items.size - 1).coerceAtLeast(0)
                            val slotW = (size.width / count.toFloat()).coerceAtLeast(1f)
                            var lastSlot = (down.position.x / slotW).toInt().coerceIn(0, maxIdx)
                            currentOnSelect(items[lastSlot].value)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val curSlot = (change.position.x / slotW).toInt().coerceIn(0, maxIdx)
                                if (curSlot != lastSlot && items[curSlot].value != currentSelected) {
                                    lastSlot = curSlot
                                    currentOnSelect(items[curSlot].value)
                                }
                            }
                        } finally {
                            touching = false
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex
                val bounceScale by animateFloatAsState(
                    targetValue = if (isSelected) 1.03f else 0.98f,
                    animationSpec = spring(dampingRatio = 0.54f, stiffness = 360f),
                    label = "navItemBounce",
                )
                val color by animateColorAsState(
                    targetValue = if (isSelected) activeColor else inactiveColor,
                    animationSpec = tween(durationMillis = 160),
                    label = "navItemColor",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 6.dp)
                        .graphicsLayer {
                            scaleX = bounceScale
                            scaleY = bounceScale
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = color,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.label,
                        color = color,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 宽屏 / 平板横屏 / 电视 / Windows 桌面端专用：悬浮胶囊液态玻璃纵向侧边导航栏。
 * 与 [LiquidGlassBottomBar] 保持完全一致的材质色温、Fresnel 棱镜折射描边与三弹簧纵向水滴拉伸回弹物理，
 * 并同时支持触摸/鼠标滑动切换与 TV 遥控器上下方向键焦点高亮。
 */
@Composable
fun <T> LiquidGlassNavigationRail(
    items: List<LiquidNavTabItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!LocalLiquidGlassEnabled.current) {
        NavigationRail(
            modifier = modifier,
            containerColor = Color.Transparent,
        ) {
            items.forEach { item ->
                NavigationRailItem(
                    selected = item.value == selected,
                    onClick = { onSelect(item.value) },
                    icon = { Icon(item.icon, contentDescription = item.label) },
                    label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }
        return
    }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val tv = LocalTv.current
    val selectedIndex = items.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    var touching by remember { mutableStateOf(false) }
    var focusedIndex by remember { mutableStateOf(-1) }

    val activeTarget = if (tv && focusedIndex in items.indices) focusedIndex else selectedIndex
    val fastPos by animateFloatAsState(
        targetValue = activeTarget.toFloat(),
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 560f),
        label = "railDropletFast",
    )
    val slowPos by animateFloatAsState(
        targetValue = activeTarget.toFloat(),
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 135f),
        label = "railDropletSlow",
    )
    val jellyPos by animateFloatAsState(
        targetValue = activeTarget.toFloat(),
        animationSpec = spring(dampingRatio = 0.46f, stiffness = 210f),
        label = "railDropletJelly",
    )
    val touchPress by animateFloatAsState(
        targetValue = if (touching) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.54f, stiffness = 380f),
        label = "railTouchPress",
    )

    val rawStretch = abs(fastPos - slowPos)
    val wobble = fastPos - jellyPos
    val lightPhase = fastPos * 0.9f + rawStretch * 1.0f + wobble * 0.65f
    val railScaleY = 1f + (rawStretch * 0.016f).coerceAtMost(0.028f) + touchPress * 0.010f
    val railScaleX = 1f - (rawStretch * 0.012f).coerceAtMost(0.020f) + touchPress * 0.012f

    val pillShape = RoundedCornerShape(38.dp)
    val slotShape = RoundedCornerShape(32.dp)
    val palette = currentVisualPalette()
    val railTint = palette.dockTint
    val tvFocused = tv && focusedIndex in items.indices
    val dropletTint = if (tvFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.88f) else palette.dropletTint
    val activeColor = palette.navActiveColor
    val inactiveColor = palette.navInactiveColor
    val slotHeight = 66.dp
    val count = items.size.coerceAtLeast(1)

    Box(
        modifier = modifier
            .width(76.dp)
            .height(slotHeight * count + 12.dp)
            .graphicsLayer {
                scaleX = railScaleX
                scaleY = railScaleY
            }
            .glassShadow(shape = pillShape, style = GlassStyle.Floating)
            .liquidGlass(
                shape = pillShape,
                style = GlassStyle.Floating,
                tint = railTint,
                lightShift = lightPhase * 0.5f,
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val safeMaxH = maxHeight.coerceAtLeast(1.dp)
            val itemH = (safeMaxH / count).coerceIn(1.dp, safeMaxH)
            val topEdgeIndex = min(fastPos, slowPos)
            val bottomEdgeIndex = max(fastPos, slowPos)
            val stretch = (bottomEdgeIndex - topEdgeIndex).coerceIn(0f, count.toFloat())
            val extraHeightFactor = (stretch * 1.04f + abs(wobble) * 0.22f + touchPress * 0.05f).coerceIn(0f, count.toFloat())
            val centerShift = (extraHeightFactor - stretch) * 0.5f
            val dropletHeight = (itemH * (1f + extraHeightFactor)).coerceIn(itemH, safeMaxH)
            val maxTop = (safeMaxH - dropletHeight).coerceAtLeast(0.dp)
            val dropletTop = (itemH * (topEdgeIndex - centerShift)).coerceIn(0.dp, maxTop)
            val dropletScaleX = (1f - (stretch * 0.10f).coerceAtMost(0.13f) + abs(wobble) * 0.05f + touchPress * 0.025f)
                .coerceIn(0.87f, 1.04f)

            Box(
                Modifier
                    .padding(top = dropletTop)
                    .size(width = maxWidth, height = dropletHeight)
                    .graphicsLayer {
                        this.scaleX = dropletScaleX
                    }
                    .shadow(
                        elevation = if (isDark) 8.dp else 6.dp,
                        shape = slotShape,
                        ambientColor = activeColor.copy(alpha = 0.22f),
                        spotColor = activeColor.copy(alpha = 0.32f),
                    )
                    .liquidGlass(
                        shape = slotShape,
                        style = GlassStyle.Floating,
                        tint = dropletTint,
                        borderColor = if (tvFocused) Color.White else if (isDark) Color(0x88FFFFFF) else Color(0xEEFFFFFF),
                        lightShift = lightPhase,
                    ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .let { mod ->
                    if (!tv) {
                        mod.pointerInput(items) {
                            if (items.isEmpty()) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                touching = true
                                try {
                                    val maxIdx = (items.size - 1).coerceAtLeast(0)
                                    val slotH = (size.height / count.toFloat()).coerceAtLeast(1f)
                                    var lastSlot = (down.position.y / slotH).toInt().coerceIn(0, maxIdx)
                                    currentOnSelect(items[lastSlot].value)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        val curSlot = (change.position.y / slotH).toInt().coerceIn(0, maxIdx)
                                        if (curSlot != lastSlot && items[curSlot].value != currentSelected) {
                                            lastSlot = curSlot
                                            currentOnSelect(items[curSlot].value)
                                        }
                                    }
                                } finally {
                                    touching = false
                                }
                            }
                        }
                    } else mod
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex
                val interactionSource = remember { MutableInteractionSource() }
                val isFocused by interactionSource.collectIsFocusedAsState()
                var nodeFocused by remember { mutableStateOf(false) }
                val itemFocused = tv && (isFocused || nodeFocused)

                val bounceScale by animateFloatAsState(
                    targetValue = when {
                        itemFocused -> 1.07f
                        isSelected -> 1.03f
                        else -> 0.98f
                    },
                    animationSpec = spring(dampingRatio = 0.54f, stiffness = 360f),
                    label = "railItemBounce",
                )
                val color by animateColorAsState(
                    targetValue = when {
                        itemFocused -> Color.White
                        isSelected -> activeColor
                        else -> inactiveColor
                    },
                    animationSpec = tween(durationMillis = 160),
                    label = "railItemColor",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onFocusChanged {
                            nodeFocused = it.isFocused
                            if (it.isFocused) focusedIndex = index
                            else if (focusedIndex == index) focusedIndex = -1
                        }
                        .let {
                            if (tv) {
                                it.clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    onClick = { currentOnSelect(item.value) },
                                )
                            } else it
                        }
                        .graphicsLayer {
                            scaleX = bounceScale
                            scaleY = bounceScale
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = color,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = item.label,
                        color = color,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = if (isSelected || itemFocused) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 液态玻璃分段控制器（带柔和的水滴滑动拉伸与回弹动画，支持 TV 遥控器焦点高亮变色与确认键切换）。
 */
@Composable
fun LiquidGlassSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!LocalLiquidGlassEnabled.current) {
        val safeIdx = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
        Surface(
            modifier = modifier.fillMaxWidth().height(40.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                Modifier.fillMaxSize().padding(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEachIndexed { index, label ->
                    val isSelected = index == safeIdx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent)
                            .tvFocusRing(shape = RoundedCornerShape(9.dp), fillBackground = true)
                            .clickable { onSelect(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        return
    }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val tv = LocalTv.current
    val safeIndex = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    val currentSafeIndex by rememberUpdatedState(safeIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)
    var touching by remember { mutableStateOf(false) }

    val fastPos by animateFloatAsState(
        targetValue = safeIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 580f),
        label = "segFast",
    )
    val slowPos by animateFloatAsState(
        targetValue = safeIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 140f),
        label = "segSlow",
    )
    val jellyPos by animateFloatAsState(
        targetValue = safeIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.46f, stiffness = 220f),
        label = "segJelly",
    )
    val touchPress by animateFloatAsState(
        targetValue = if (touching) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.54f, stiffness = 380f),
        label = "segTouchPress",
    )

    val rawStretch = abs(fastPos - slowPos)
    val wobble = fastPos - jellyPos
    val lightPhase = fastPos * 0.95f + rawStretch * 1.05f + wobble * 0.7f

    val trackShape = RoundedCornerShape(50)
    val palette = currentVisualPalette()
    val trackBg = palette.segmentedTrackTint
    val thumbTint = palette.segmentedThumbTint
    val activeText = palette.navActiveColor
    val inactiveText = palette.segmentedInactiveText

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .graphicsLayer {
                scaleX = 1f + (rawStretch * 0.014f).coerceAtMost(0.024f) + touchPress * 0.010f
                scaleY = 1f - (rawStretch * 0.011f).coerceAtMost(0.018f) + touchPress * 0.012f
            }
            .liquidGlass(
                shape = trackShape,
                style = GlassStyle.Pill,
                tint = trackBg,
                lightShift = lightPhase * 0.45f,
            )
            .padding(3.dp),
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val count = options.size.coerceAtLeast(1)
            val safeMaxW = maxWidth.coerceAtLeast(1.dp)
            val slotWidth = (safeMaxW / count).coerceIn(1.dp, safeMaxW)
            val leftEdge = min(fastPos, slowPos)
            val rightEdge = max(fastPos, slowPos)
            val stretch = (rightEdge - leftEdge).coerceIn(0f, count.toFloat())
            val extraWidth = (stretch * 1.02f + abs(wobble) * 0.22f + touchPress * 0.04f).coerceIn(0f, count.toFloat())
            val centerShift = (extraWidth - stretch) * 0.5f
            val thumbWidth = (slotWidth * (1f + extraWidth)).coerceIn(slotWidth, safeMaxW)
            val maxLeft = (safeMaxW - thumbWidth).coerceAtLeast(0.dp)
            val thumbLeft = (slotWidth * (leftEdge - centerShift)).coerceIn(0.dp, maxLeft)
            val thumbScaleY = (1f - (stretch * 0.12f).coerceAtMost(0.14f) + abs(wobble) * 0.06f + touchPress * 0.025f)
                .coerceIn(0.86f, 1.04f)

            Box(
                Modifier
                    .padding(start = thumbLeft)
                    .size(width = thumbWidth, height = maxHeight)
                    .graphicsLayer {
                        scaleY = thumbScaleY
                    }
                    .shadow(4.dp, trackShape, ambientColor = activeText.copy(alpha = 0.20f), spotColor = activeText.copy(alpha = 0.28f))
                    .liquidGlass(
                        shape = trackShape,
                        style = GlassStyle.Floating,
                        tint = thumbTint,
                        borderColor = if (isDark) Color(0x88FFFFFF) else Color(0xEEFFFFFF),
                        lightShift = lightPhase,
                    ),
            )
        }

        Row(
            Modifier
                .fillMaxSize()
                .pointerInput(options) {
                    if (options.isEmpty()) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        touching = true
                        try {
                            val count = options.size.coerceAtLeast(1)
                            val maxIdx = (options.size - 1).coerceAtLeast(0)
                            val slotW = (size.width / count.toFloat()).coerceAtLeast(1f)
                            var lastSlot = (down.position.x / slotW).toInt().coerceIn(0, maxIdx)
                            currentOnSelect(lastSlot)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val curSlot = (change.position.x / slotW).toInt().coerceIn(0, maxIdx)
                                if (curSlot != lastSlot && curSlot != currentSafeIndex) {
                                    lastSlot = curSlot
                                    currentOnSelect(curSlot)
                                }
                            }
                        } finally {
                            touching = false
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == safeIndex
                val itemInteraction = remember { MutableInteractionSource() }
                val isFocused by itemInteraction.collectIsFocusedAsState()
                var focusedNode by remember { mutableStateOf(false) }
                val itemFocused = tv && (isFocused || focusedNode)

                val textScale by animateFloatAsState(
                    targetValue = if (selected || itemFocused) 1.04f else 0.98f,
                    animationSpec = spring(dampingRatio = 0.54f, stiffness = 360f),
                    label = "segTextScale",
                )
                val textColor by animateColorAsState(
                    targetValue = when {
                        itemFocused -> Color.White
                        selected -> activeText
                        else -> inactiveText
                    },
                    animationSpec = tween(160),
                    label = "segTextColor",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onFocusChanged { focusedNode = it.isFocused }
                        .tvFocusRing(shape = trackShape, fillBackground = true)
                        .let {
                            if (tv) {
                                it.clickable(
                                    interactionSource = itemInteraction,
                                    indication = null,
                                    onClick = { currentOnSelect(index) },
                                )
                            } else it
                        }
                        .graphicsLayer {
                            scaleX = textScale
                            scaleY = textScale
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = textColor,
                        fontSize = 14.sp,
                        fontWeight = if (selected || itemFocused) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 提取四个角的圆角半径（顺序：topLeft, topRight, bottomRight, bottomLeft）。
 */
private fun extractCornerRadii(outline: Outline, size: Size): FloatArray {
    val maxR = min(size.width, size.height) * 0.5f
    if (maxR <= 0f) return floatArrayOf(0f, 0f, 0f, 0f)
    return when (outline) {
        is Outline.Rounded -> {
            val rr: RoundRect = outline.roundRect
            floatArrayOf(
                rr.topLeftCornerRadius.x.coerceIn(0f, maxR),
                rr.topRightCornerRadius.x.coerceIn(0f, maxR),
                rr.bottomRightCornerRadius.x.coerceIn(0f, maxR),
                rr.bottomLeftCornerRadius.x.coerceIn(0f, maxR),
            )
        }
        is Outline.Rectangle -> floatArrayOf(0f, 0f, 0f, 0f)
        is Outline.Generic -> {
            val r = min(size.width, size.height) * 0.25f
            floatArrayOf(r, r, r, r)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object AgslShaders {
    // AGSL 表面液态玻璃曲面高光与边缘色散着色器（支持四角独立圆角、贴边顶栏/底栏与动态光线相位旋转）
    private const val SURFACE_GLASS_SKSL = """
        uniform float2 size;
        uniform float4 cornerRadii;
        uniform float bevelWidth;
        uniform float intensity;
        uniform float isDark;
        uniform float lightPhase;

        float pickRadius(float2 p, float4 r) {
            return (p.x < 0.0)
                ? ((p.y < 0.0) ? r.x : r.w)
                : ((p.y < 0.0) ? r.y : r.z);
        }

        half4 main(float2 fragCoord) {
            float bw = max(bevelWidth, 2.0);
            float hasTopRound = step(0.5, max(cornerRadii.x, cornerRadii.y));
            float hasBottomRound = step(0.5, max(cornerRadii.z, cornerRadii.w));
            float topFlush = (1.0 - hasTopRound) * hasBottomRound;
            float bottomFlush = hasTopRound * (1.0 - hasBottomRound);

            float boxMinY = -topFlush * bw * 2.0;
            float boxMaxY = size.y + bottomFlush * bw * 2.0;
            float2 center = float2(size.x * 0.5, (boxMinY + boxMaxY) * 0.5);
            float2 halfSize = max(float2(size.x * 0.5, (boxMaxY - boxMinY) * 0.5), float2(1.0, 1.0));
            float2 p = fragCoord - center;

            float maxR = min(halfSize.x, halfSize.y);
            float4 r4 = clamp(cornerRadii, float4(0.0), float4(maxR));
            float r = pickRadius(p, r4);

            float2 q = abs(p) - halfSize + float2(r);
            float d = min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;

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
            float2 n2d = grad * edge * 0.88;
            float nz = sqrt(max(0.04, 1.0 - dot(n2d, n2d)));
            float3 normal = normalize(float3(n2d.x, n2d.y, nz));

            // 随水滴运动偏转主光源与副反光角度，形成流动折射高光
            float shiftSin = sin(lightPhase * 1.4) * 0.32;
            float shiftCos = cos(lightPhase * 1.1) * 0.24;
            float3 lightTopLeft = normalize(float3(-0.489 + shiftSin, -0.584 + shiftCos, 0.648));
            float3 lightBottomRight = normalize(float3(0.493 - shiftSin, 0.578 - shiftCos, 0.650));

            float d1 = max(0.0, dot(normal, lightTopLeft));
            float d2 = max(0.0, dot(normal, lightBottomRight));
            float d1_2 = d1 * d1;
            float d2_2 = d2 * d2;
            float specPrimary = d1_2 * d1_2 * d1;
            float specSecondary = d2_2 * d2_2 * d2_2;
            float oneMinusNz = clamp(1.0 - nz, 0.0, 1.0);
            float fresnel = oneMinusNz * oneMinusNz;

            // 边缘微小棱镜色散（随 lightPhase 流动的冷暖微光）
            float3 prismColor = mix(
                float3(0.70, 0.88, 1.0),
                float3(1.0, 0.84, 0.98),
                clamp(grad.x * 0.5 + 0.5 + shiftSin * 0.4, 0.0, 1.0)
            );

            // 顶边内侧柔光流线（贴顶栏不额外叠加顶边光带）
            float topGlow = (1.0 - topFlush) * smoothstep(max(size.y * 0.46, 1.0), 0.0, fragCoord.y) * 0.09;

            float rimStrength = (specPrimary * 0.42 + specSecondary * 0.24 + fresnel * 0.34) * edge;
            float darkScale = mix(1.0, 0.74, isDark);
            float alpha = clamp((rimStrength + topGlow) * intensity * darkScale, 0.0, 0.64);

            float3 finalRgb = mix(prismColor, float3(1.0, 1.0, 1.0), 0.62) * alpha;
            return half4(half3(finalRgb), half(alpha));
        }
    """

    fun newSurfaceShader(): RuntimeShader? = runCatching {
        RuntimeShader(SURFACE_GLASS_SKSL)
    }.getOrNull()

    fun configureSurfaceBrush(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        cornerRadii: FloatArray,
        bevelWidth: Float,
        intensity: Float,
        isDark: Boolean,
        lightPhase: Float = 0f,
    ): ShaderBrush? = runCatching {
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform(
            "cornerRadii",
            cornerRadii.getOrElse(0) { 0f },
            cornerRadii.getOrElse(1) { 0f },
            cornerRadii.getOrElse(2) { 0f },
            cornerRadii.getOrElse(3) { 0f },
        )
        shader.setFloatUniform("bevelWidth", bevelWidth)
        shader.setFloatUniform("intensity", intensity)
        shader.setFloatUniform("isDark", if (isDark) 1f else 0f)
        shader.setFloatUniform("lightPhase", lightPhase)
        ShaderBrush(shader)
    }.getOrNull()
}
