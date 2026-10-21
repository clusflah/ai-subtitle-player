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
 * 设计基线：品牌橙只用于主要操作和选中状态；
 * 分区靠背景层次（页面浅灰、卡片白色）区分，不叠加粗边框和阴影。
 */
val Orange = Color(0xFFF28C00)

private val Light = lightColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFF1DE),
    onPrimaryContainer = Color(0xFF5A3300),
    secondary = Color(0xFFB86A00),
    secondaryContainer = Color(0xFFFFF1DE),
    onSecondaryContainer = Color(0xFF5A3300),
    background = Color(0xFFF7F7F7),
    onBackground = Color(0xFF292929),
    surface = Color(0xFFF7F7F7),
    onSurface = Color(0xFF292929),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF5F5F5F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF1F1F1),
    surfaceContainerHighest = Color(0xFFEAEAEA),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFFE3E3E3),
    error = Color(0xFFD93025),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFA733),
    onPrimary = Color(0xFF3A2000),
    primaryContainer = Color(0xFF3D2A12),
    onPrimaryContainer = Color(0xFFFFDDB3),
    secondary = Color(0xFFFFB866),
    secondaryContainer = Color(0xFF3D2A12),
    onSecondaryContainer = Color(0xFFFFDDB3),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFB5B5B5),
    surfaceContainerLowest = Color(0xFF1A1A1A),
    surfaceContainerLow = Color(0xFF1C1C1C),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF303030),
    outline = Color(0xFF8C8C8C),
    outlineVariant = Color(0xFF333333),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium),
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
