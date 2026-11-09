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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
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
fun Modifier.glassBackdropSource(state: GlassBackdropState?): Modifier {
    if (state == null) return this
    return this
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
 * 为 Dialog / ModalBottomSheet / Popup 窗口开启 Android 12+ (API 31+) 系统级窗口背景模糊（FLAG_BLUR_BEHIND）。
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
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val defaultBase = if (isDark) Color(0xFF1A222D) else Color(0xFFF7F9FC)
    val resolvedTint = if (tint != Color.Unspecified) {
        tint
    } else {
        defaultBase.copy(alpha = if (isDark) style.darkAlpha else style.lightAlpha)
    }

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
            val shiftWave = sin(lightShift * 1.6f)
            val shiftCos = cos(lightShift * 1.2f)
            val sheenStart = Offset(
                x = (safeW * (0.18f + shiftWave * 0.28f)).coerceIn(0f, safeW),
                y = 0f,
            )
            val sheenEnd = Offset(
                x = (safeW * (0.82f + shiftCos * 0.24f)).coerceIn(0.1f, safeW),
                y = safeH,
            )

            if (blurredBackdropLayer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurPx > 0.5f) {
                blurredBackdropLayer.renderEffect = RenderEffect
                    .createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                    .asComposeRenderEffect()
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
                    0.35f to Color(0xFFA8D8FF).copy(alpha = midRimAlpha.coerceIn(0f, 1f)),
                    0.65f to Color(0xFFDCC4FF).copy(alpha = midRimAlpha.coerceIn(0f, 1f)),
                    1f to Color.White.copy(alpha = bottomRimAlpha.coerceIn(0f, 1f)),
                    start = sheenStart,
                    end = sheenEnd,
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
                    cornerRadii = cornerRadiiPx,
                    bevelWidth = bevelPx.coerceAtMost(min(size.width, size.height) * 0.45f),
                    intensity = style.specularIntensity,
                    isDark = isDark,
                    lightPhase = lightShift,
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
                        blurredBackdropLayer.record {
                            translate(left = dx, top = dy) {
                                drawLayer(backdrop.contentLayer)
                            }
                        }
                        drawLayer(blurredBackdropLayer)
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleX by animateFloatAsState(
        targetValue = if (onClick != null && isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 360f),
        label = "glassBoxScaleX",
    )
    val scaleY by animateFloatAsState(
        targetValue = if (onClick != null && isPressed) 0.91f else 1f,
        animationSpec = spring(dampingRatio = 0.38f, stiffness = 320f),
        label = "glassBoxScaleY",
    )
    val lightShift by animateFloatAsState(
        targetValue = if (isPressed) 1.2f else 0f,
        animationSpec = spring(dampingRatio = 0.50f, stiffness = 260f),
        label = "glassBoxLight",
    )
    val glassModifier = modifier
        .let {
            if (onClick != null) {
                it.graphicsLayer {
                    this.scaleX = scaleX
                    this.scaleY = scaleY
                }
            } else it
        }
        .liquidGlass(
            shape = shape,
            style = style,
            tint = tint,
            borderColor = borderColor,
            enableBackdrop = enableBackdrop,
            lightShift = lightShift,
        )
        .let {
            if (onClick != null) {
                it.clickable(
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
 * 顶栏右侧独立圆形液态玻璃按钮（带明显的水滴按压扁平化形变与高弹回跃动画，对应演示视频顶栏的 `+`、`⋯` 按钮）。
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleX by animateFloatAsState(
        targetValue = if (isPressed) 0.82f else 1f,
        animationSpec = spring(dampingRatio = 0.36f, stiffness = 340f),
        label = "glassBtnScaleX",
    )
    val scaleY by animateFloatAsState(
        targetValue = if (isPressed) 0.74f else 1f,
        animationSpec = spring(dampingRatio = 0.34f, stiffness = 300f),
        label = "glassBtnScaleY",
    )
    val lightShift by animateFloatAsState(
        targetValue = if (isPressed) 1.6f else 0f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 240f),
        label = "glassBtnLight",
    )
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val glassTint = if (isDark) Color(0xCC222A38) else Color(0xE6FFFFFF)

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                this.scaleX = scaleX
                this.scaleY = scaleY
            }
            .shadow(
                elevation = if (isDark) 6.dp else 4.dp,
                shape = CircleShape,
                ambientColor = Color(0x1F000000),
                spotColor = Color(0x29000000),
            )
            .liquidGlass(
                shape = CircleShape,
                style = GlassStyle.Floating,
                tint = glassTint,
                lightShift = lightShift,
            )
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
            tint = tint,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/**
 * 悬浮胶囊液态玻璃底部导航栏（仿演示视频 UI 与明显的水滴拉伸、挤压与果冻回弹动画）：
 * - 外层为全圆角胶囊悬浮条，实时模糊下方滚过的页面内容，并随水滴运动产生微弹共振；
 * - 选中项指示器为包裹「图标 + 文字」的完整液态玻璃水滴胶囊；
 * - 切换标签或手指滑动时，水滴前缘高速跃出、后缘粘滞牵引拉长，纵向明顯收缩（体积守恒），落定瞬间产生果冻回弹与光泽扫过。
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
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val selectedIndex = items.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    var touching by remember { mutableStateOf(false) }

    // 三弹簧驱动：前缘快速跃出 + 后缘粘滞拉长 + 落定果冻回弹振荡
    val fastPos by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.54f, stiffness = 580f),
        label = "dropletFast",
    )
    val slowPos by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.44f, stiffness = 105f),
        label = "dropletSlow",
    )
    val jellyPos by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.34f, stiffness = 185f),
        label = "dropletJelly",
    )
    val touchPress by animateFloatAsState(
        targetValue = if (touching) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 360f),
        label = "barTouchPress",
    )

    val rawStretch = abs(fastPos - slowPos)
    val wobble = fastPos - jellyPos
    val lightPhase = fastPos * 1.1f + rawStretch * 1.4f + wobble * 0.9f
    val barScaleX = 1f + (rawStretch * 0.028f).coerceAtMost(0.048f) + touchPress * 0.018f
    val barScaleY = 1f - (rawStretch * 0.022f).coerceAtMost(0.036f) + touchPress * 0.024f

    val pillShape = RoundedCornerShape(50)
    val barTint = if (isDark) Color(0xBF181F2C) else Color(0xD8F7F9FC)
    val dropletTint = if (isDark) Color(0xE02E3B52) else Color(0xF0E6EEF8)
    val activeColor = if (isDark) Color(0xFF4DA3FF) else Color(0xFF007AFF)
    val inactiveColor = if (isDark) Color(0xFFD5DCE6) else Color(0xFF1C1C1E)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .height(64.dp)
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
                lightShift = lightPhase * 0.6f,
            )
            .padding(horizontal = 5.dp, vertical = 5.dp),
    ) {
        // 1. 底层滑动、拉长与果冻回弹的液态玻璃水滴胶囊指示器
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val count = items.size.coerceAtLeast(1)
            val slotWidth = maxWidth / count
            val leftEdgeIndex = min(fastPos, slowPos)
            val rightEdgeIndex = max(fastPos, slowPos)
            val stretch = (rightEdgeIndex - leftEdgeIndex).coerceIn(0f, count.toFloat())
            // 放大水滴拉伸感，并在落定瞬间通过 wobble 产生横向/纵向果冻回弹
            val extraWidthFactor = (stretch * 1.48f + abs(wobble) * 0.42f + touchPress * 0.10f).coerceIn(0f, count.toFloat())
            val centerShift = (extraWidthFactor - stretch) * 0.5f
            val dropletLeft = (slotWidth * (leftEdgeIndex - centerShift)).coerceIn(0.dp, maxWidth - slotWidth * 0.6f)
            val dropletWidth = (slotWidth * (1f + extraWidthFactor)).coerceIn(slotWidth * 0.75f, maxWidth)
            val scaleY = (1f - (stretch * 0.30f).coerceAtMost(0.36f) + abs(wobble) * 0.22f + touchPress * 0.08f)
                .coerceIn(0.64f, 1.22f)

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

        // 2. 顶层各标签图标与文字（支持点击与手指滑动跟随切换）
        Row(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(items) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        touching = true
                        val count = items.size.coerceAtLeast(1)
                        val slotW = (size.width / count.toFloat()).coerceAtLeast(1f)
                        val initialSlot = (down.position.x / slotW).toInt().coerceIn(0, items.lastIndex)
                        onSelect(items[initialSlot].value)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val curSlot = (change.position.x / slotW).toInt().coerceIn(0, items.lastIndex)
                            if (curSlot != items.indexOfFirst { it.value == selected }) {
                                onSelect(items[curSlot].value)
                            }
                        }
                        touching = false
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex
                val distance = abs(fastPos - index)
                val proximity = (1f - distance.coerceIn(0f, 1f))
                val bounceScale by animateFloatAsState(
                    targetValue = if (isSelected) 1.16f else 0.94f,
                    animationSpec = spring(dampingRatio = 0.40f, stiffness = 320f),
                    label = "navItemBounce",
                )
                val liftY by animateFloatAsState(
                    targetValue = if (isSelected) -5f else 0f,
                    animationSpec = spring(dampingRatio = 0.44f, stiffness = 300f),
                    label = "navItemLift",
                )
                val itemScale = bounceScale + proximity * 0.04f
                val color by animateColorAsState(
                    targetValue = if (isSelected) activeColor else inactiveColor,
                    animationSpec = tween(durationMillis = 160),
                    label = "navItemColor",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = itemScale
                            scaleY = itemScale
                            translationY = liftY
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = color,
                        modifier = Modifier.size(23.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.label,
                        color = color,
                        fontSize = 11.sp,
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
 * 液态玻璃分段控制器（对应演示视频 00:03 的胶囊分段切换条，带明显的水滴滑动拉伸与果冻回弹动画）。
 */
@Composable
fun LiquidGlassSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val safeIndex = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    var touching by remember { mutableStateOf(false) }

    val fastPos by animateFloatAsState(
        targetValue = safeIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.54f, stiffness = 600f),
        label = "segFast",
    )
    val slowPos by animateFloatAsState(
        targetValue = safeIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.44f, stiffness = 115f),
        label = "segSlow",
    )
    val jellyPos by animateFloatAsState(
        targetValue = safeIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.34f, stiffness = 195f),
        label = "segJelly",
    )
    val touchPress by animateFloatAsState(
        targetValue = if (touching) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 360f),
        label = "segTouchPress",
    )

    val rawStretch = abs(fastPos - slowPos)
    val wobble = fastPos - jellyPos
    val lightPhase = fastPos * 1.15f + rawStretch * 1.5f + wobble

    val trackShape = RoundedCornerShape(50)
    val trackBg = if (isDark) Color(0xCC1A212E) else Color(0xFFEAECEF)
    val thumbTint = if (isDark) Color(0xEC32405A) else Color(0xFAFFFFFF)
    val activeText = if (isDark) Color(0xFF4DA3FF) else Color(0xFF007AFF)
    val inactiveText = if (isDark) Color(0xFFB0BAC9) else Color(0xFF3C3C43)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .graphicsLayer {
                scaleX = 1f + (rawStretch * 0.02f).coerceAtMost(0.035f) + touchPress * 0.015f
                scaleY = 1f - (rawStretch * 0.016f).coerceAtMost(0.028f) + touchPress * 0.02f
            }
            .liquidGlass(
                shape = trackShape,
                style = GlassStyle.Pill,
                tint = trackBg,
                lightShift = lightPhase * 0.5f,
            )
            .padding(3.dp),
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val count = options.size.coerceAtLeast(1)
            val slotWidth = maxWidth / count
            val leftEdge = min(fastPos, slowPos)
            val rightEdge = max(fastPos, slowPos)
            val stretch = (rightEdge - leftEdge).coerceIn(0f, count.toFloat())
            val extraWidth = (stretch * 1.45f + abs(wobble) * 0.40f + touchPress * 0.08f).coerceIn(0f, count.toFloat())
            val centerShift = (extraWidth - stretch) * 0.5f
            val thumbLeft = (slotWidth * (leftEdge - centerShift)).coerceIn(0.dp, maxWidth - slotWidth * 0.6f)
            val thumbWidth = (slotWidth * (1f + extraWidth)).coerceIn(slotWidth * 0.75f, maxWidth)
            val thumbScaleY = (1f - (stretch * 0.28f).coerceAtMost(0.34f) + abs(wobble) * 0.20f + touchPress * 0.06f)
                .coerceIn(0.66f, 1.20f)

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
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        touching = true
                        val count = options.size.coerceAtLeast(1)
                        val slotW = (size.width / count.toFloat()).coerceAtLeast(1f)
                        val initialSlot = (down.position.x / slotW).toInt().coerceIn(0, options.lastIndex)
                        onSelect(initialSlot)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val curSlot = (change.position.x / slotW).toInt().coerceIn(0, options.lastIndex)
                            onSelect(curSlot)
                        }
                        touching = false
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == safeIndex
                val textScale by animateFloatAsState(
                    targetValue = if (selected) 1.08f else 0.96f,
                    animationSpec = spring(dampingRatio = 0.42f, stiffness = 340f),
                    label = "segTextScale",
                )
                val textColor by animateColorAsState(
                    targetValue = if (selected) activeText else inactiveText,
                    animationSpec = tween(160),
                    label = "segTextColor",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
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
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
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

    fun createConfiguredSurfaceBrush(
        width: Float,
        height: Float,
        cornerRadii: FloatArray,
        bevelWidth: Float,
        intensity: Float,
        isDark: Boolean,
        lightPhase: Float = 0f,
    ): ShaderBrush? = runCatching {
        val shader = RuntimeShader(SURFACE_GLASS_SKSL)
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
