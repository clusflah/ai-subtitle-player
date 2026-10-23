package com.aisub.player.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计基线（与 MediaOrganizer 一致）：柔和的紫色为主色，页面是很浅的紫、卡片接近纯白，
 * 靠底色和留白分层，不加阴影；橙色只用在分组标题前的短条上。
 */

/** 播放器（深色背景）上的强调色：比浅色主题的主色更亮的紫，在黑底上看得清 */
val PlayerAccent = Color(0xFFB4A3FF)

private val Light = lightColorScheme(
    primary = Color(0xFF7562C9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAE4FB),
    onPrimaryContainer = Color(0xFF2A1D66),
    secondary = Color(0xFF5F5A72),
    secondaryContainer = Color(0xFFE8E4F3),
    onSecondaryContainer = Color(0xFF1D1A2C),
    // 分组标题前的橙色短条
    tertiary = Color(0xFFFF7A00),
    background = Color(0xFFF6F3FC),
    onBackground = Color(0xFF1C1B22),
    surface = Color(0xFFF6F3FC),
    onSurface = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFFE8E4F3),
    onSurfaceVariant = Color(0xFF5C5868),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFAFE),
    surfaceContainer = Color(0xFFF1EDF9),
    surfaceContainerHigh = Color(0xFFEBE6F5),
    surfaceContainerHighest = Color(0xFFE5E0F0),
    outline = Color(0xFF8C8799),
    outlineVariant = Color(0xFFE4E0ED),
    error = Color(0xFFC62828),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFCBBEFF),
    onPrimary = Color(0xFF3A2A86),
    primaryContainer = Color(0xFF52429E),
    onPrimaryContainer = Color(0xFFEAE4FB),
    secondary = Color(0xFFC5C4DD),
    secondaryContainer = Color(0xFF444559),
    onSecondaryContainer = Color(0xFFE1E0F9),
    tertiary = Color(0xFFFF9E40),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE4E1EA),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE4E1EA),
    surfaceVariant = Color(0xFF2B2A33),
    onSurfaceVariant = Color(0xFFC8C4D2),
    surfaceContainerLowest = Color(0xFF1A1B21),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
    outline = Color(0xFF938F9C),
    outlineVariant = Color(0xFF34343A),
    error = Color(0xFFEF9A9A),
)

/** 圆角：输入框、按钮 12dp，卡片 20dp，比默认的更圆润 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 页面左右边距 */
val PageGutter = 20.dp

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.2.sp),
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
