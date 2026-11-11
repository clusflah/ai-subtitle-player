package com.aisub.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 与主页底栏同款的 60dp 悬浮液态玻璃胶囊 + 底层三弹簧水滴指示器（供视频播放页与漫画阅读页共用）。
 *
 * @param count 槽位总数（等宽按钮个数）
 * @param activeSlot 当前激活/点击目标槽位（0 until count），传 -1 时水滴平滑淡出隐藏
 * @param focusedSlot TV 遥控器焦点所在槽位（0 until count），无焦点传 -1
 * @param pulsing 是否处于点击脉冲或拖动状态（驱动胶囊与水滴微弹）
 */
@Composable
internal fun LiquidDock(
    count: Int,
    activeSlot: Int,
    focusedSlot: Int,
    pulsing: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    if (!LocalLiquidGlassEnabled.current) {
        Row(
            modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        return
    }
    val tv = LocalTv.current
    val safeCount = count.coerceAtLeast(1)
    val rawSlot = if (tv && focusedSlot in 0 until safeCount) focusedSlot else activeSlot
    val hasDroplet = rawSlot in 0 until safeCount
    var lastSlot by remember(safeCount) {
        mutableIntStateOf(if (rawSlot in 0 until safeCount) rawSlot else safeCount / 2)
    }
    if (hasDroplet) lastSlot = rawSlot

    val targetPos = lastSlot.toFloat()
    val fastPos by animateFloatAsState(
        targetValue = targetPos,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 560f),
        label = "dockDropletFast",
    )
    val slowPos by animateFloatAsState(
        targetValue = targetPos,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 135f),
        label = "dockDropletSlow",
    )
    val jellyPos by animateFloatAsState(
        targetValue = targetPos,
        animationSpec = spring(dampingRatio = 0.46f, stiffness = 210f),
        label = "dockDropletJelly",
    )
    val dockPress by animateFloatAsState(
        targetValue = if (pulsing) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.54f, stiffness = 380f),
        label = "dockPress",
    )
    val dropletAlpha by animateFloatAsState(
        targetValue = if (hasDroplet) 1f else 0f,
        animationSpec = tween(durationMillis = if (hasDroplet) 140 else 260),
        label = "dockDropletAlpha",
    )

    val rawStretch = abs(fastPos - slowPos)
    val wobble = fastPos - jellyPos
    val lightPhase = fastPos * 0.9f + rawStretch * 1.0f + wobble * 0.65f + dockPress * 0.6f
    val barScaleX = 1f + (rawStretch * 0.015f).coerceAtMost(0.025f) + dockPress * 0.009f
    val barScaleY = 1f - (rawStretch * 0.012f).coerceAtMost(0.020f) + dockPress * 0.012f

    val barShape = RoundedCornerShape(50)
    val barTint = GlassTokens.DarkDockTint
    val dropletTint = if (tv && focusedSlot >= 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.88f) else GlassTokens.DarkDropletTint
    val activeAccent = GlassTokens.DarkActiveBlue

    Box(
        modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .height(60.dp)
            .graphicsLayer {
                scaleX = barScaleX
                scaleY = barScaleY
            }
            .shadow(
                elevation = 18.dp,
                shape = barShape,
                ambientColor = Color(0x28000000),
                spotColor = Color(0x36000000),
            )
            .liquidGlass(
                shape = barShape,
                style = GlassStyle.Floating,
                tint = barTint,
                lightShift = lightPhase * 0.5f,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (dropletAlpha > 0.01f) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val safeMaxW = maxWidth.coerceAtLeast(1.dp)
                val slotWidth = (safeMaxW / safeCount).coerceIn(1.dp, safeMaxW)
                val leftEdgeIndex = min(fastPos, slowPos)
                val rightEdgeIndex = max(fastPos, slowPos)
                val stretch = (rightEdgeIndex - leftEdgeIndex).coerceIn(0f, safeCount.toFloat())
                val extraWidthFactor = (stretch * 1.05f + abs(wobble) * 0.24f + dockPress * 0.08f).coerceIn(0f, safeCount.toFloat())
                val centerShift = (extraWidthFactor - stretch) * 0.5f
                val dropletWidth = (slotWidth * (1f + extraWidthFactor)).coerceIn(slotWidth, safeMaxW)
                val maxLeft = (safeMaxW - dropletWidth).coerceAtLeast(0.dp)
                val dropletLeft = (slotWidth * (leftEdgeIndex - centerShift)).coerceIn(0.dp, maxLeft)
                val dropletScaleY = (1f - (stretch * 0.12f).coerceAtMost(0.14f) + abs(wobble) * 0.06f - dockPress * 0.04f)
                    .coerceIn(0.86f, 1.04f)

                Box(
                    Modifier
                        .padding(start = dropletLeft)
                        .size(width = dropletWidth, height = maxHeight)
                        .graphicsLayer {
                            scaleY = dropletScaleY
                            alpha = dropletAlpha
                        }
                        .shadow(
                            elevation = 8.dp,
                            shape = barShape,
                            ambientColor = activeAccent.copy(alpha = 0.22f * dropletAlpha),
                            spotColor = activeAccent.copy(alpha = 0.32f * dropletAlpha),
                        )
                        .liquidGlass(
                            shape = barShape,
                            style = GlassStyle.Floating,
                            tint = dropletTint,
                            borderColor = if (tv && focusedSlot >= 0) Color.White else Color(0x88FFFFFF),
                            lightShift = lightPhase,
                        ),
                )
            }
        }

        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * 底部液态胶囊内的等宽图标按钮（带按压微缩、TV 焦点上报与视觉大小补偿）。
 */
@Composable
internal fun DockButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
    isCenter: Boolean = false,
    active: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    var focusedNode by remember { mutableStateOf(false) }
    var clickPulse by remember { mutableStateOf(false) }
    val tv = LocalTv.current
    val focused = tv && (isFocused || focusedNode)

    LaunchedEffect(focused) {
        onFocusChange(focused)
    }

    if (!LocalLiquidGlassEnabled.current) {
        val classicColor = when {
            !enabled -> Color(0x55FFFFFF)
            focused -> Color.White
            active && !isCenter -> Orange
            else -> Color.White
        }
        Box(
            modifier = modifier
                .fillMaxHeight()
                .onFocusChanged { focusedNode = it.isFocused }
                .clip(RoundedCornerShape(50))
                .let {
                    if (enabled) {
                        it.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        )
                    } else it
                },
            contentAlignment = Alignment.Center,
        ) {
            if (isCenter) {
                Box(
                    Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(if (focused) Orange else Color(0x24FFFFFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = classicColor,
                        modifier = Modifier.size(iconSize),
                    )
                }
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = classicColor,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        return
    }

    LaunchedEffect(clickPulse) {
        if (clickPulse) {
            delay(85)
            clickPulse = false
        }
    }

    val activePress = isPressed || clickPulse
    val scaleX by animateFloatAsState(
        targetValue = when {
            activePress -> 0.90f
            focused -> 1.08f
            active && !isCenter -> 1.04f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 360f),
        label = "dockBtnScaleX",
    )
    val scaleY by animateFloatAsState(
        targetValue = when {
            activePress -> 0.86f
            focused -> 1.08f
            active && !isCenter -> 1.04f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.50f, stiffness = 320f),
        label = "dockBtnScaleY",
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color(0x55FFFFFF)
            focused -> Color.White
            active && !isCenter -> Color(0xFF4DA3FF)
            isCenter -> Color.White
            else -> Color(0xFFD5DCE6)
        },
        animationSpec = tween(durationMillis = 150),
        label = "dockBtnColor",
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .onFocusChanged { focusedNode = it.isFocused }
            .graphicsLayer {
                this.scaleX = scaleX
                this.scaleY = scaleY
            }
            .clip(RoundedCornerShape(50))
            .let {
                if (enabled) {
                    it.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { clickPulse = true; onClick() },
                    )
                } else it
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * 细橙色进度条（4dp 轨道 + 16dp 橙色圆点，拖动时圆点微弹放大到 1.18 倍）。
 */
@Composable
internal fun ThinSeekBar(
    fraction: Float,
    isDragging: Boolean = false,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    markers: (@Composable BoxScope.() -> Unit)? = null,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    var widthPx by remember { mutableFloatStateOf(1f) }
    val thumbScale by animateFloatAsState(
        targetValue = if (isDragging) 1.18f else 1f,
        animationSpec = spring(dampingRatio = 0.54f, stiffness = 380f),
        label = "seekThumbScale",
    )
    val trackHeight = if (isDragging) 5.dp else 4.dp
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(32.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { o -> onDrag((o.x / widthPx).coerceIn(0f, 1f)); onDragEnd() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> onDrag((o.x / widthPx).coerceIn(0f, 1f)) },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                ) { change, _ -> change.consume(); onDrag((change.position.x / widthPx).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(trackHeight).clip(RoundedCornerShape(50)).background(Color(0x44FFFFFF)))
        markers?.invoke(this)
        Box(Modifier.fillMaxWidth(clamped).height(trackHeight).clip(RoundedCornerShape(50)).background(Orange))
        Box(
            Modifier.offset {
                val thumbPx = 16.dp.toPx()
                val travel = (widthPx - thumbPx).coerceAtLeast(0f)
                IntOffset((clamped * travel).roundToInt(), 0)
            }
                .size(16.dp)
                .graphicsLayer {
                    scaleX = thumbScale
                    scaleY = thumbScale
                }
                .shadow(4.dp, CircleShape, ambientColor = Orange.copy(alpha = 0.45f), spotColor = Orange.copy(alpha = 0.65f))
                .clip(CircleShape)
                .background(Orange),
        )
    }
}
