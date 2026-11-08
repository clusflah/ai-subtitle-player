package com.aisub.player.ui

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.max
import kotlin.math.min

/**
 * 液态玻璃（Liquid Glass）视觉引擎，按系统版本自动分级：
 * - Android 13+ (API 33+)：AGSL RuntimeShader 边缘透镜折射 + 色散 + Fresnel 高光 + 实时背景模糊
 * - Android 12  (API 31-32)：RenderEffect 实时高斯模糊 + 双角度高光折射描边 + 玻璃光泽层
 * - Android 8-11 (API 26-30)：多层半透明渐变玻璃基底 + 高光边缘折射描边回退
 */
@Stable
class GlassBackdropState internal constructor(
    internal val layer: GraphicsLayer,
) {
    internal var originInRoot by mutableStateOf(Offset.Zero)
    internal var hasContent by mutableStateOf(false)
    internal var isRecording by mutableStateOf(false)
    internal var activeGlassDepth: Int = 0
    internal var drawTick by mutableFloatStateOf(0f)
}

val LocalGlassBackdrop = compositionLocalOf<GlassBackdropState?> { null }

@Composable
fun rememberGlassBackdrop(): GlassBackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdropState(layer) }
}

/**
 * 标记作为液态玻璃背景采样的内容层。
 * 放在同一个 [LocalGlassBackdrop] 作用域下的顶栏、底栏、悬浮条会实时折射并模糊此层内容。
 */
fun Modifier.glassBackdropSource(state: GlassBackdropState?): Modifier {
    if (state == null) return this
    return this
        .onGloballyPositioned { coords ->
            if (coords.isAttached) {
                state.originInRoot = coords.positionInRoot()
            }
        }
        .drawWithContent {
            state.isRecording = true
            try {
                state.layer.record {
                    this@drawWithContent.drawContent()
                }
                state.hasContent = true
                state.drawTick += 1f
            } finally {
                state.isRecording = false
            }
            drawLayer(state.layer)
        }
}

/**
 * 提供 [LocalGlassBackdrop] 上下文的容器，便于页面内部组件自动采样背景层。
 */
@Composable
fun GlassBackdropBox(
    modifier: Modifier = Modifier,
    captureContent: Boolean = false,
    content: @Composable BoxScope.(GlassBackdropState) -> Unit,
) {
    val backdrop = rememberGlassBackdrop()
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        Box(
            modifier = if (captureContent) modifier.glassBackdropSource(backdrop) else modifier,
        ) {
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
            lightAlpha = 0.68f,
            darkAlpha = 0.60f,
            specularIntensity = 0.85f,
            chromaticAberration = 1.6f,
        )

        /** 悬浮胶囊、播放器控制条、迷你播放条、阅读器工具栏 */
        val Floating = GlassStyle(
            blurRadius = 24.dp,
            bevelWidth = 16.dp,
            refractionStrength = 12.dp,
            borderWidth = 1.dp,
            lightAlpha = 0.64f,
            darkAlpha = 0.56f,
            specularIntensity = 0.95f,
            chromaticAberration = 2.0f,
        )

        /** 底部抽屉、对话框、下拉菜单、侧滑抽屉 */
        val Sheet = GlassStyle(
            blurRadius = 32.dp,
            bevelWidth = 18.dp,
            refractionStrength = 11.dp,
            borderWidth = 1.dp,
            lightAlpha = 0.78f,
            darkAlpha = 0.72f,
            specularIntensity = 0.80f,
            chromaticAberration = 1.5f,
        )

        /** 列表卡片、分组卡片、封面卡 */
        val Card = GlassStyle(
            blurRadius = 18.dp,
            bevelWidth = 12.dp,
            refractionStrength = 7.dp,
            borderWidth = 0.85.dp,
            lightAlpha = 0.72f,
            darkAlpha = 0.62f,
            specularIntensity = 0.70f,
            chromaticAberration = 1.1f,
        )

        /** 角标、时长标签、小按钮、字幕胶囊 */
        val Pill = GlassStyle(
            blurRadius = 16.dp,
            bevelWidth = 9.dp,
            refractionStrength = 6.dp,
            borderWidth = 0.8.dp,
            lightAlpha = 0.62f,
            darkAlpha = 0.54f,
            specularIntensity = 0.90f,
            chromaticAberration = 1.4f,
        )
    }
}

/**
 * 为 Dialog / ModalBottomSheet / Popup 窗口开启 Android 12+ (API 31+) 系统级窗口背景模糊。
 */
@Composable
fun WindowBlurEffect(
    blurBehindRadius: Int = 52,
    backgroundBlurRadius: Int = 68,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    DisposableEffect(view, blurBehindRadius, backgroundBlurRadius) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            runCatching {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply {
                    this.blurBehindRadius = blurBehindRadius
                }
                window.setBackgroundBlurRadius(backgroundBlurRadius)
            }
        }
        onDispose { }
    }
}

/**
 * 核心液态玻璃修饰符：
 * 1. 若处于 [LocalGlassBackdrop] 作用域且不在背景录制层内部，则对下层画面做实时透镜折射（API 33+）与高斯模糊（API 31+）；
 * 2. 绘制多层半透明玻璃染色层；
 * 3. 绘制 AGSL 曲面玻璃高光折射与边缘色散（API 33+），或在旧系统上回退为双角度 Fresnel 高光渐变描边。
 */
fun Modifier.liquidGlass(
    shape: Shape = RoundedCornerShape(16.dp),
    style: GlassStyle = GlassStyle.Card,
    tint: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    enableBackdrop: Boolean = true,
): Modifier = composed {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val defaultBase = if (isDark) Color(0xFF1A222D) else Color(0xFFF7FAFF)
    val resolvedTint = if (tint != Color.Unspecified) {
        tint
    } else {
        defaultBase.copy(alpha = if (isDark) style.darkAlpha else style.lightAlpha)
    }
    val backdropFallbackBg = MaterialTheme.colorScheme.background
    val backdrop = if (enableBackdrop) LocalGlassBackdrop.current else null
    val sampleLayer = if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        rememberGraphicsLayer()
    } else {
        null
    }

    var selfPosInRoot by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val blurPx = with(density) { style.blurRadius.toPx() }
    val bevelPx = with(density) { style.bevelWidth.toPx() }
    val refractionPx = with(density) { style.refractionStrength.toPx() }
    val borderPx = with(density) { style.borderWidth.toPx() }

    val surfaceShaderHolder = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AgslShaders.createSurfaceShader()
        } else {
            null
        }
    }
    val backdropShaderHolder = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AgslShaders.createBackdropRefractionShader()
        } else {
            null
        }
    }

    this
        .onGloballyPositioned { coords ->
            if (coords.isAttached) {
                selfPosInRoot = coords.positionInRoot()
            }
        }
        .clip(shape)
        .drawWithCache {
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
                Color.White.copy(alpha = (baseAlpha * 0.22f).coerceAtMost(0.28f))
            } else {
                Color.White.copy(alpha = (baseAlpha * 0.55f).coerceAtMost(0.72f))
            }
            val fillBrush = Brush.linearGradient(
                0f to baseColor.copy(alpha = (baseAlpha * 1.04f).coerceAtMost(0.95f)),
                0.45f to baseColor.copy(alpha = (baseAlpha * 0.88f).coerceAtLeast(0.06f)),
                1f to baseColor.copy(alpha = (baseAlpha * 0.96f).coerceAtMost(0.92f)),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
            val sheenBrush = Brush.linearGradient(
                0f to highlightTint,
                0.38f to Color.Transparent,
                0.72f to Color.Transparent,
                1f to highlightTint.copy(alpha = highlightTint.alpha * 0.45f),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )

            // 边缘高光描边（Fresnel 双高光：左上亮白，中部柔和，右下冷色反光）
            val spec = style.specularIntensity
            val topRimAlpha = (if (isDark) 0.42f else 0.78f) * spec
            val midRimAlpha = (if (isDark) 0.10f else 0.24f) * spec
            val bottomRimAlpha = (if (isDark) 0.26f else 0.52f) * spec
            val rimBrush = if (borderColor != Color.Unspecified) {
                Brush.linearGradient(
                    listOf(
                        borderColor,
                        borderColor.copy(alpha = borderColor.alpha * 0.45f),
                        borderColor.copy(alpha = borderColor.alpha * 0.8f),
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
            } else {
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = topRimAlpha.coerceIn(0f, 1f)),
                    0.35f to Color(0xFFB8DCFF).copy(alpha = midRimAlpha.coerceIn(0f, 1f)),
                    0.65f to Color(0xFFD6C8FF).copy(alpha = midRimAlpha.coerceIn(0f, 1f)),
                    1f to Color.White.copy(alpha = bottomRimAlpha.coerceIn(0f, 1f)),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
            }

            // Android 13+ AGSL 表面液态玻璃光泽画刷
            val agslSurfaceBrush = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                surfaceShaderHolder != null &&
                size.width > 1f &&
                size.height > 1f
            ) {
                AgslShaders.configureSurfaceShader(
                    shader = surfaceShaderHolder,
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

            // 配置采样层的 RenderEffect（API 33+ 折射+模糊，API 31-32 模糊）
            if (sampleLayer != null && size.width > 1f && size.height > 1f && blurPx > 0.5f) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backdropShaderHolder != null) {
                    AgslShaders.configureBackdropEffect(
                        layer = sampleLayer,
                        shader = backdropShaderHolder,
                        width = size.width,
                        height = size.height,
                        cornerRadius = cornerRadiusPx,
                        bevelWidth = bevelPx.coerceAtMost(min(size.width, size.height) * 0.45f),
                        refraction = refractionPx,
                        aberration = style.chromaticAberration,
                        blurRadius = blurPx,
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Api31Blur.applyBlur(sampleLayer, blurPx)
                }
            }

            onDrawWithContent {
                clipPath(clipPath) {
                    val canSampleBackdrop = backdrop != null &&
                        sampleLayer != null &&
                        backdrop.hasContent &&
                        !backdrop.isRecording &&
                        backdrop.activeGlassDepth == 0 &&
                        size.width > 1f &&
                        size.height > 1f

                    // 1. 实时背景采样 + 透镜折射 + 高斯模糊（Android 12+ / 13+）
                    if (canSampleBackdrop && backdrop != null && sampleLayer != null) {
                        // 读取 drawTick 确保背景更新时触发重绘
                        val tick = backdrop.drawTick
                        if (tick >= 0f) {
                            val relX = selfPosInRoot.x - backdrop.originInRoot.x
                            val relY = selfPosInRoot.y - backdrop.originInRoot.y
                            sampleLayer.record {
                                drawRect(backdropFallbackBg)
                                translate(-relX, -relY) {
                                    drawLayer(backdrop.layer)
                                }
                            }
                            drawLayer(sampleLayer)
                        }
                    }

                    // 2. 液态玻璃半透明染色层 + 斜向光泽层
                    drawRect(brush = fillBrush)
                    drawRect(brush = sheenBrush)

                    // 3. Android 13+ AGSL 曲面透镜高光与色散层
                    if (agslSurfaceBrush != null) {
                        drawRect(brush = agslSurfaceBrush)
                    }

                    // 4. 组件自身内容（标记已处于玻璃层内部，避免内嵌子玻璃重复穿透采样背景层）
                    if (backdrop != null) backdrop.activeGlassDepth += 1
                    try {
                        this@onDrawWithContent.drawContent()
                    } finally {
                        if (backdrop != null) backdrop.activeGlassDepth -= 1
                    }

                    // 5. 边缘 Fresnel 折射高光描边
                    if (borderPx > 0f) {
                        when (outline) {
                            is Outline.Rounded -> {
                                val rr = outline.roundRect
                                drawRoundRect(
                                    brush = rimBrush,
                                    topLeft = Offset(rr.left + borderPx * 0.5f, rr.top + borderPx * 0.5f),
                                    size = Size(
                                        (rr.width - borderPx).coerceAtLeast(0f),
                                        (rr.height - borderPx).coerceAtLeast(0f),
                                    ),
                                    cornerRadius = CornerRadius(
                                        max(0f, rr.topLeftCornerRadius.x - borderPx * 0.5f),
                                        max(0f, rr.topLeftCornerRadius.y - borderPx * 0.5f),
                                    ),
                                    style = Stroke(width = borderPx),
                                )
                            }
                            is Outline.Rectangle -> {
                                drawRect(
                                    brush = rimBrush,
                                    topLeft = Offset(borderPx * 0.5f, borderPx * 0.5f),
                                    size = Size(
                                        (size.width - borderPx).coerceAtLeast(0f),
                                        (size.height - borderPx).coerceAtLeast(0f),
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
    return when (outline) {
        is Outline.Rounded -> {
            val rr: RoundRect = outline.roundRect
            max(rr.topLeftCornerRadius.x, rr.bottomRightCornerRadius.x).coerceIn(0f, maxR)
        }
        is Outline.Rectangle -> 0f
        is Outline.Generic -> min(size.width, size.height) * 0.25f
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private object Api31Blur {
    fun applyBlur(layer: GraphicsLayer, blurRadius: Float) {
        val r = blurRadius.coerceAtLeast(1f)
        layer.renderEffect = AndroidRenderEffect.createBlurEffect(
            r,
            r,
            Shader.TileMode.CLAMP,
        ).asComposeRenderEffect()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object AgslShaders {
    // AGSL 表面液态玻璃曲面高光与边缘色散着色器
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
            float2 halfSize = size * 0.5;
            float2 p = fragCoord - halfSize;
            float r = min(cornerRadius, min(halfSize.x, halfSize.y));
            float d = sdRoundBox(p, halfSize, r);

            float bw = max(bevelWidth, 2.0);
            // 边缘斜面因子：在 [-bw, 0] 区间内从 0 平滑升至 1
            float edge = smoothstep(-bw, 0.0, d);

            // 计算曲面法线方向
            float2 inner = max(abs(p) - (halfSize - float2(r)), 0.0);
            float2 grad = (length(inner) > 0.001)
                ? normalize(inner) * sign(p)
                : normalize(p / max(halfSize, float2(1.0)));

            // 模拟水滴形玻璃边缘的 3D 倾角
            float2 n2d = grad * edge * 0.85;
            float nz = sqrt(max(0.04, 1.0 - dot(n2d, n2d)));
            float3 normal = normalize(float3(n2d.x, n2d.y, nz));

            // 主光源（左上）与副反光（右下）
            float3 lightTopLeft = normalize(float3(-0.62, -0.74, 0.52));
            float3 lightBottomRight = normalize(float3(0.58, 0.68, 0.45));

            float specPrimary = pow(max(0.0, dot(normal, lightTopLeft)), 5.0);
            float specSecondary = pow(max(0.0, dot(normal, lightBottomRight)), 6.0);
            float fresnel = pow(1.0 - nz, 2.4);

            // 边缘微小棱镜色散（冷暖微光）
            float3 prismColor = mix(
                float3(0.76, 0.90, 1.0),
                float3(1.0, 0.88, 0.98),
                clamp(grad.x * 0.5 + 0.5, 0.0, 1.0)
            );

            // 顶边内侧柔光流线
            float topGlow = smoothstep(size.y * 0.42, 0.0, fragCoord.y) * 0.07;

            float rimStrength = (specPrimary * 0.34 + specSecondary * 0.18 + fresnel * 0.28) * edge;
            float darkScale = mix(1.0, 0.68, isDark);
            float alpha = clamp((rimStrength + topGlow) * intensity * darkScale, 0.0, 0.55);

            float3 finalRgb = mix(prismColor, float3(1.0), 0.65) * alpha;
            return half4(half3(finalRgb), half(alpha));
        }
    """

    // AGSL 背景实时透镜折射 + 边缘色散着色器
    private const val BACKDROP_REFRACTION_SKSL = """
        uniform shader content;
        uniform float2 size;
        uniform float cornerRadius;
        uniform float bevelWidth;
        uniform float refraction;
        uniform float aberration;

        float sdRoundBox(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + float2(r);
            return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
        }

        half4 main(float2 fragCoord) {
            float2 halfSize = size * 0.5;
            float2 p = fragCoord - halfSize;
            float r = min(cornerRadius, min(halfSize.x, halfSize.y));
            float d = sdRoundBox(p, halfSize, r);

            float bw = max(bevelWidth, 2.0);
            float edge = smoothstep(-bw, 0.0, d);
            float lensCurve = edge * edge * (3.0 - 2.0 * edge);

            float2 inner = max(abs(p) - (halfSize - float2(r)), 0.0);
            float2 normal = (length(inner) > 0.001)
                ? normalize(inner) * sign(p)
                : normalize(p / max(halfSize, float2(1.0)));

            // 边缘向内折射位移
            float2 shift = -normal * lensCurve * refraction;
            float2 chromaShift = normal * lensCurve * aberration;

            float2 minCoord = float2(1.0, 1.0);
            float2 maxCoord = max(size - float2(1.0, 1.0), minCoord);

            float2 uvR = clamp(fragCoord + shift + chromaShift, minCoord, maxCoord);
            float2 uvG = clamp(fragCoord + shift, minCoord, maxCoord);
            float2 uvB = clamp(fragCoord + shift - chromaShift, minCoord, maxCoord);

            half4 cR = content.eval(uvR);
            half4 cG = content.eval(uvG);
            half4 cB = content.eval(uvB);

            return half4(cR.r, cG.g, cB.b, cG.a);
        }
    """

    fun createSurfaceShader(): RuntimeShader? =
        runCatching { RuntimeShader(SURFACE_GLASS_SKSL) }.getOrNull()

    fun createBackdropRefractionShader(): RuntimeShader? =
        runCatching { RuntimeShader(BACKDROP_REFRACTION_SKSL) }.getOrNull()

    fun configureSurfaceShader(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        cornerRadius: Float,
        bevelWidth: Float,
        intensity: Float,
        isDark: Boolean,
    ): ShaderBrush? = runCatching {
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("cornerRadius", cornerRadius)
        shader.setFloatUniform("bevelWidth", bevelWidth)
        shader.setFloatUniform("intensity", intensity)
        shader.setFloatUniform("isDark", if (isDark) 1f else 0f)
        ShaderBrush(shader)
    }.getOrNull()

    fun configureBackdropEffect(
        layer: GraphicsLayer,
        shader: RuntimeShader,
        width: Float,
        height: Float,
        cornerRadius: Float,
        bevelWidth: Float,
        refraction: Float,
        aberration: Float,
        blurRadius: Float,
    ) {
        runCatching {
            shader.setFloatUniform("size", width, height)
            shader.setFloatUniform("cornerRadius", cornerRadius)
            shader.setFloatUniform("bevelWidth", bevelWidth)
            shader.setFloatUniform("refraction", refraction)
            shader.setFloatUniform("aberration", aberration)
            val refractionEffect = AndroidRenderEffect.createRuntimeShaderEffect(shader, "content")
            val r = blurRadius.coerceAtLeast(1f)
            val blurEffect = AndroidRenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
            // 先对底层画面做高斯模糊，再通过透镜折射着色器对边缘做弯曲折射与色散
            layer.renderEffect = AndroidRenderEffect.createChainEffect(refractionEffect, blurEffect)
                .asComposeRenderEffect()
        }
    }
}
