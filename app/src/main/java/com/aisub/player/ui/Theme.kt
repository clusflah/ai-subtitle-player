package com.aisub.player.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** VLC 风格的橙色主题。 */
val Orange = Color(0xFFFF8800)

private val Light = lightColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    secondary = Orange,
    secondaryContainer = Color(0xFFFFE6CC),
    onSecondaryContainer = Color(0xFF4A2800),
    background = Color.White,
    surface = Color.White,
    surfaceContainer = Color.White,
    onSurface = Color(0xFF333333),
    onSurfaceVariant = Color(0xFF666666),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFFE0E0E0),
)

private val Dark = darkColorScheme(
    primary = Orange,
    onPrimary = Color.Black,
    secondary = Orange,
    secondaryContainer = Color(0xFF5A3300),
    onSecondaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    surfaceContainer = Color(0xFF1E1E1E),
    outlineVariant = Color(0xFF3A3A3A),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
