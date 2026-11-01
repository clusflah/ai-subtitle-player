package com.aisub.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * 弹出层（底部面板、侧边菜单、悬浮菜单、对话框）的两套颜色。
 * 结构、尺寸、圆角两套一样，只换颜色：亮色走奶油白 + 纯白 + 浅灰 + 焦糖橙，轻盈通透；
 * 暗色走深黑 + 炭灰 + 中灰 + 琥珀橙，沉浸深邃。
 * 跟随系统亮色、暗色（播放页、音乐页自己是黑底，弹窗不跟页面走）。
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
    /** 悬浮层阴影：亮色靠柔和阴影区分层次，暗色靠面板明度，不要阴影 */
    val shadow: Dp,
    val dark: Boolean,
)

val LightPopupColors = PopupColors(
    page = Color(0xFFF3F2EF),
    surface = Color(0xFFFFFFFF),
    card = Color(0xFFF4F3F0),
    button = Color(0xFFE9E7E3),
    textPrimary = Color(0xFF242424),
    textSecondary = Color(0xFF858585),
    divider = Color(0xFFEAE8E4),
    accent = Color(0xFFC47B20),
    disabled = Color(0xFFC8C6C1),
    selectedBg = Color(0xFFFBEBD6),
    selectedText = Color(0xFFB0661A),
    scrim = 0.35f,
    shadow = 8.dp,
    dark = false,
)

val DarkPopupColors = PopupColors(
    page = Color(0xFF171819),
    surface = Color(0xFF292B2C),
    card = Color(0xFF414344),
    button = Color(0xFF55585A),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFAAAAAA),
    divider = Color(0xFF484A4A),
    accent = Color(0xFFE49A36),
    disabled = Color(0xFF666666),
    selectedBg = Color(0xFF4A3B2A),
    selectedText = Color(0xFFF2AA4A),
    scrim = 0.6f,
    shadow = 0.dp,
    dark = true,
)

val LocalPopupColors = staticCompositionLocalOf { DarkPopupColors }

@Composable
fun popupColors(): PopupColors = if (isSystemInDarkTheme()) DarkPopupColors else LightPopupColors

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
    ModalBottomSheet(
        onDismissRequest = onDismissRequest, modifier = modifier, sheetState = sheetState,
        containerColor = Color.Transparent, contentColor = c.textPrimary, tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = c.scrim), dragHandle = null,
    ) {
        // 悬浮的完整圆角框，不贴屏幕边缘
        val shape = RoundedCornerShape(28.dp)
        Column(
            Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth()
                .shadow(c.shadow, shape).clip(shape).background(c.surface),
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
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, offset = offset,
        scrollState = scrollState, shape = MaterialTheme.shapes.medium, containerColor = c.surface,
        tonalElevation = 0.dp, shadowElevation = c.shadow, content = content,
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
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest, confirmButton = confirmButton, modifier = modifier,
        dismissButton = dismissButton, icon = icon, title = title, text = text,
        containerColor = c.surface, titleContentColor = c.textPrimary, textContentColor = c.textSecondary,
        iconContentColor = c.accent, tonalElevation = 0.dp, properties = properties,
    )
}
