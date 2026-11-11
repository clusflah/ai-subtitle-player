package com.aisub.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.aisub.player.AppBackground

/**
 * 弹出层（底部面板、侧边菜单、悬浮菜单、对话框）的语义配色。
 * 结构、尺寸、圆角完全统一，色温与当前选中的 [AppBackground] 协调适配：
 * 暖米灰配温润羊皮米白，淡青/薄荷配清透青瓷白，冰川蓝紫配轻盈雾紫白，中性灰/极浅灰配干净冷白，夜间配深邃炭蓝黑。
 */
@Immutable
data class PopupColors(
    val page: Color,
    val surface: Color,
    val card: Color,
    /** 卡片上的圆形操作按钮 */
    val button: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val accent: Color,
    val disabled: Color,
    val selectedBg: Color,
    val selectedText: Color,
    /** 弹窗后面的遮罩（黑色）不透明度 */
    val scrim: Float,
    /** 悬浮层阴影 */
    val shadow: Dp,
    val dark: Boolean,
)

val LightPopupColors = PopupColors(
    page = Color(0xFFF6F7FA),
    surface = Color(0xE4F8FAFC),
    card = Color(0xD4F0F3F8),
    button = Color(0xDEE4E9F1),
    textPrimary = Color(0xFF1C2026),
    textSecondary = Color(0xFF606773),
    divider = Color(0x221C2026),
    accent = Color(0xFFC47B20),
    disabled = Color(0xFFC2C7D0),
    selectedBg = Color(0xDCFBEBD6),
    selectedText = Color(0xFFB0661A),
    scrim = 0.24f,
    shadow = 16.dp,
    dark = false,
)

private val NeutralGrayPopupColors = LightPopupColors.copy(
    page = Color(0xFFF2F4F7),
    surface = Color(0xE4F6F8FB),
    card = Color(0xD4EDF0F6),
    button = Color(0xDEE2E7F0),
)

private val WarmBeigePopupColors = LightPopupColors.copy(
    page = Color(0xFFF5F2EC),
    surface = Color(0xE6FAF7F2),
    card = Color(0xD6F1ECE3),
    button = Color(0xDEE8E1D6),
    textPrimary = Color(0xFF26211B),
    textSecondary = Color(0xFF6B6258),
    divider = Color(0x2226211B),
    accent = Color(0xFFBF6E19),
    disabled = Color(0xFFC9C2B8),
    selectedBg = Color(0xE0F9E9D2),
    selectedText = Color(0xFFA85D12),
)

private val PaleCyanPopupColors = LightPopupColors.copy(
    page = Color(0xFFEEF3F1),
    surface = Color(0xE4F4F9F7),
    card = Color(0xD4E8F0EC),
    button = Color(0xDEDDE7E3),
    textPrimary = Color(0xFF1C2623),
    textSecondary = Color(0xFF586963),
    divider = Color(0x221C2623),
    disabled = Color(0xFFBDCAC5),
)

private val MintGradientPopupColors = LightPopupColors.copy(
    page = Color(0xFFECF4F0),
    surface = Color(0xE2F2F8F5),
    card = Color(0xD2E5F0EB),
    button = Color(0xDCD9E8E1),
    textPrimary = Color(0xFF1B2622),
    textSecondary = Color(0xFF556861),
    divider = Color(0x221B2622),
    disabled = Color(0xFFBAC9C3),
)

private val GlacierGradientPopupColors = LightPopupColors.copy(
    page = Color(0xFFF0F0F8),
    surface = Color(0xE2F5F5FC),
    card = Color(0xD2EAEBF6),
    button = Color(0xDCE0E2F1),
    textPrimary = Color(0xFF1E202C),
    textSecondary = Color(0xFF5D6175),
    divider = Color(0x221E202C),
    disabled = Color(0xFFC0C3D4),
)

private val AuroraGradientPopupColors = LightPopupColors.copy(
    page = Color(0xFFF2F3F6),
    surface = Color(0xE4F6F8FB),
    card = Color(0xD4ECF0F5),
    button = Color(0xDEE0E6EE),
    textPrimary = Color(0xFF1F232A),
    textSecondary = Color(0xFF5B626E),
    divider = Color(0x221F232A),
    disabled = Color(0xFFC0C6CF),
)

private val ChampagneGradientPopupColors = LightPopupColors.copy(
    page = Color(0xFFF5F2EE),
    surface = Color(0xE5FAF8F4),
    card = Color(0xD6F0ECE5),
    button = Color(0xDEE7E1D8),
    textPrimary = Color(0xFF26221D),
    textSecondary = Color(0xFF675F56),
    divider = Color(0x2226221D),
    accent = Color(0xFFB5691D),
    disabled = Color(0xFFC8C2BA),
    selectedBg = Color(0xE0F9E9D4),
    selectedText = Color(0xFFA35B14),
)

val DarkPopupColors = PopupColors(
    page = Color(0xFF12161F),
    surface = GlassTokens.DarkSheetTint,
    card = Color(0xA6263042),
    button = Color(0xBF323F54),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFB8C2D0),
    divider = Color(0x32FFFFFF),
    accent = Color(0xFFE49A36),
    disabled = Color(0xFF68707D),
    selectedBg = Color(0xB84A3824),
    selectedText = Color(0xFFF2AA4A),
    scrim = 0.44f,
    shadow = 20.dp,
    dark = true,
)

private val DarkWarmPopupColors = DarkPopupColors.copy(
    page = Color(0xFF191613),
    surface = Color(0xD6221E1A),
    card = Color(0xAB302A24),
    button = Color(0xC23D352D),
    textSecondary = Color(0xFFC8BFB4),
    selectedBg = Color(0xBA4E3A24),
)

private val DarkCyanPopupColors = DarkPopupColors.copy(
    page = Color(0xFF121816),
    surface = Color(0xD418221F),
    card = Color(0xA824332F),
    button = Color(0xBF2E403B),
    textSecondary = Color(0xFFB4C6C0),
)

private val DarkGlacierPopupColors = DarkPopupColors.copy(
    page = Color(0xFF151621),
    surface = Color(0xD41B1D2C),
    card = Color(0xA8282B40),
    button = Color(0xBF343852),
    textSecondary = Color(0xFFB9BCD2),
)

private val DarkAuroraPopupColors = DarkPopupColors.copy(
    page = Color(0xFF15171B),
    surface = Color(0xD41A1F26),
    card = Color(0xA8272E38),
    button = Color(0xBF323B48),
    textSecondary = Color(0xFFB8C1CE),
)

val LocalPopupColors = staticCompositionLocalOf { DarkPopupColors }

@Composable
fun popupColors(): PopupColors {
    val bg = LocalAppBackground.current
    if (isAppDarkTheme()) {
        return when (bg) {
            AppBackground.WARM_BEIGE_GRAY,
            AppBackground.WARM_CHAMPAGNE_GRADIENT,
            -> DarkWarmPopupColors
            AppBackground.PALE_CYAN_GRAY,
            AppBackground.MINT_GREEN_GRADIENT,
            -> DarkCyanPopupColors
            AppBackground.GLACIER_BLUE_PURPLE_GRADIENT -> DarkGlacierPopupColors
            AppBackground.AURORA_GRAY_WHITE_GRADIENT -> DarkAuroraPopupColors
            AppBackground.NEUTRAL_GRAY,
            AppBackground.VERY_LIGHT_GRAY,
            -> DarkPopupColors
        }
    }
    return when (bg) {
        AppBackground.NEUTRAL_GRAY -> NeutralGrayPopupColors
        AppBackground.WARM_BEIGE_GRAY -> WarmBeigePopupColors
        AppBackground.PALE_CYAN_GRAY -> PaleCyanPopupColors
        AppBackground.MINT_GREEN_GRADIENT -> MintGradientPopupColors
        AppBackground.GLACIER_BLUE_PURPLE_GRADIENT -> GlacierGradientPopupColors
        AppBackground.AURORA_GRAY_WHITE_GRADIENT -> AuroraGradientPopupColors
        AppBackground.WARM_CHAMPAGNE_GRADIENT -> ChampagneGradientPopupColors
        AppBackground.VERY_LIGHT_GRAY -> LightPopupColors
    }
}

/**
 * 弹窗里面的内容用弹窗的颜色：把颜色 Token 映射到 Material 配色上，
 * 弹窗里的文字、分隔线、按钮、选中状态就都跟着换，不用每处单独判断亮暗。
 */
@Composable
fun PopupTheme(content: @Composable () -> Unit) {
    val c = popupColors()
    val base = if (c.dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = c.accent,
        onPrimary = Color.White,
        primaryContainer = c.selectedBg,
        onPrimaryContainer = c.selectedText,
        secondary = c.accent,
        secondaryContainer = c.selectedBg,
        onSecondaryContainer = c.selectedText,
        tertiary = c.accent,
        background = c.page,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.card,
        onSurfaceVariant = c.textSecondary,
        surfaceContainerLowest = c.surface,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.card,
        surfaceContainerHighest = c.card,
        outline = c.textSecondary,
        outlineVariant = c.divider,
    )
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalPopupColors provides c, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) = PopupTheme {
    val c = LocalPopupColors.current
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val scaleX by animateFloatAsState(
        targetValue = if (entered) 1f else 0.93f,
        animationSpec = spring(dampingRatio = 0.48f, stiffness = 340f),
        label = "sheetScaleX",
    )
    val scaleY by animateFloatAsState(
        targetValue = if (entered) 1f else 0.90f,
        animationSpec = spring(dampingRatio = 0.44f, stiffness = 300f),
        label = "sheetScaleY",
    )
    val lightShift by animateFloatAsState(
        targetValue = if (entered) 1.5f else 0f,
        animationSpec = spring(dampingRatio = 0.56f, stiffness = 200f),
        label = "sheetLight",
    )
    ModalBottomSheet(
        onDismissRequest = onDismissRequest, modifier = modifier, sheetState = sheetState,
        containerColor = Color.Transparent, contentColor = c.textPrimary, tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = c.scrim), dragHandle = null,
    ) {
        WindowBlurEffect(blurBehindRadius = 56, backgroundBlurRadius = 72)
        // 悬浮的完整圆角液态玻璃框，不贴屏幕边缘
        val shape = RoundedCornerShape(28.dp)
        Column(
            Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth()
                .graphicsLayer {
                    this.scaleX = scaleX
                    this.scaleY = scaleY
                }
                .glassShadow(shape, GlassStyle.Sheet, c.shadow)
                .liquidGlass(shape = shape, style = GlassStyle.Sheet, tint = c.surface, lightShift = lightShift),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally)) { BottomSheetDefaults.DragHandle() }
            content()
        }
    }
}

@Composable
fun AppMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) = PopupTheme {
    val c = LocalPopupColors.current
    val shape = RoundedCornerShape(20.dp)
    val lightShift by animateFloatAsState(
        targetValue = if (expanded) 1.2f else 0f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 240f),
        label = "menuLight",
    )
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.liquidGlass(shape = shape, style = GlassStyle.Sheet, tint = c.surface, lightShift = lightShift),
        offset = offset,
        scrollState = scrollState,
        shape = shape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 14.dp,
        content = {
            WindowBlurEffect(blurBehindRadius = 40, backgroundBlurRadius = 56)
            content()
        },
    )
}

@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) = PopupTheme {
    val c = LocalPopupColors.current
    val shape = RoundedCornerShape(28.dp)
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val scale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.54f, stiffness = 360f),
        label = "dialogScale",
    )
    val lightShift by animateFloatAsState(
        targetValue = if (entered) 1.3f else 0f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 220f),
        label = "dialogLight",
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            WindowBlurEffect(blurBehindRadius = 56, backgroundBlurRadius = 72)
            confirmButton()
        },
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .glassShadow(shape, GlassStyle.Sheet, c.shadow)
            .liquidGlass(shape = shape, style = GlassStyle.Sheet, tint = c.surface, lightShift = lightShift),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = Color.Transparent,
        titleContentColor = c.textPrimary,
        textContentColor = c.textSecondary,
        iconContentColor = c.accent,
        tonalElevation = 0.dp,
        properties = properties,
    )
}
