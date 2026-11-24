package com.voxmusic.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voxmusic.app.music.WebMusicService

/**
 * Apple Music / Spotify 官方品牌矢量图标。
 */
@Composable
fun WebMusicServiceIcon(
    service: WebMusicService,
    size: Dp = 28.dp,
    insetScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val vector = remember(service, insetScale) {
        when (service) {
            WebMusicService.APPLE_MUSIC -> buildAppleMusicOfficialVector(insetScale)
            WebMusicService.SPOTIFY -> buildSpotifyOfficialVector(insetScale)
        }
    }
    Image(
        imageVector = vector,
        contentDescription = service.label,
        modifier = modifier.size(size),
    )
}

internal const val OFFICIAL_APPLE_MUSIC_PATH =
    "M23.997 6.124c0-.738-.065-1.47-.24-2.19-.317-1.31-1.062-2.31-2.18-3.043C21.003.517 20.373.285 19.7.164c-.517-.093-1.038-.135-1.564-.15-.04-.003-.083-.01-.124-.013H5.988c-.152.01-.303.017-.455.026C4.786.07 4.043.15 3.34.428 2.004.958 1.04 1.88.475 3.208c-.192.448-.292.925-.363 1.408-.056.392-.088.785-.1 1.18 0 .032-.007.062-.01.093v12.223c.01.14.017.283.027.424.05.815.154 1.624.497 2.373.65 1.42 1.738 2.353 3.234 2.802.42.127.856.187 1.293.228.555.053 1.11.06 1.667.06h11.03c.525 0 1.048-.034 1.57-.1.823-.106 1.597-.35 2.296-.81.84-.553 1.472-1.287 1.88-2.208.186-.42.293-.87.37-1.324.113-.675.138-1.358.137-2.04-.002-3.8 0-7.595-.003-11.393zm-6.423 3.99v5.712c0 .417-.058.827-.244 1.206-.29.59-.76.962-1.388 1.14-.35.1-.706.157-1.07.173-.95.045-1.773-.6-1.943-1.536-.142-.773.227-1.624 1.038-2.022.323-.16.67-.25 1.018-.324.378-.082.758-.153 1.134-.24.274-.063.457-.23.51-.516.014-.063.02-.13.02-.193 0-1.815 0-3.63-.002-5.443 0-.062-.01-.125-.026-.185-.04-.15-.15-.243-.304-.234-.16.01-.318.035-.475.066-.76.15-1.52.303-2.28.456l-2.326.47-1.374.278c-.016.003-.032.01-.048.013-.277.077-.377.203-.39.49-.002.042 0 .086 0 .13-.002 2.602 0 5.204-.003 7.805 0 .42-.047.836-.215 1.227-.278.64-.77 1.04-1.434 1.233-.35.1-.71.16-1.075.172-.96.036-1.755-.6-1.92-1.544-.14-.812.23-1.685 1.154-2.075.357-.15.73-.232 1.108-.31.287-.06.575-.116.86-.177.383-.083.583-.323.6-.714v-.15c0-2.96 0-5.922.002-8.882 0-.123.013-.25.042-.37.07-.285.273-.448.546-.518.255-.066.515-.112.774-.165.733-.15 1.466-.296 2.2-.444l2.27-.46c.67-.134 1.34-.27 2.01-.403.22-.043.443-.088.664-.106.31-.025.523.17.554.482.008.073.012.148.012.223.002 1.91.002 3.822 0 5.732z"

internal const val OFFICIAL_SPOTIFY_PATH =
    "M12 0C5.4 0 0 5.4 0 12s5.4 12 12 12 12-5.4 12-12S18.66 0 12 0zm5.521 17.34c-.24.359-.66.48-1.021.24-2.82-1.74-6.36-2.101-10.561-1.141-.418.122-.779-.179-.899-.539-.12-.421.18-.78.54-.9 4.56-1.021 8.52-.6 11.64 1.32.42.18.479.659.301 1.02zm1.44-3.3c-.301.42-.841.6-1.262.3-3.239-1.98-8.159-2.58-11.939-1.38-.479.12-1.02-.12-1.14-.6-.12-.48.12-1.021.6-1.141C9.6 9.9 15 10.561 18.72 12.84c.361.181.54.78.241 1.2zm.12-3.36C15.24 8.4 8.82 8.16 5.16 9.301c-.6.179-1.2-.181-1.38-.721-.18-.601.18-1.2.72-1.381 4.26-1.26 11.28-1.02 15.721 1.621.539.3.719 1.02.419 1.56-.299.421-1.02.599-1.559.3z"

internal fun buildAppleMusicOfficialVector(scale: Float = 1f): ImageVector = ImageVector.Builder(
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    group(scaleX = scale, scaleY = scale, pivotX = 12f, pivotY = 12f) {
        addPath(
            pathData = addPathNodes("M4.2,3.4 h15.6 v17.4 h-15.6 z"),
            fill = SolidColor(Color.White),
        )
        addPath(
            pathData = addPathNodes(OFFICIAL_APPLE_MUSIC_PATH),
            pathFillType = PathFillType.EvenOdd,
            fill = Brush.linearGradient(
                colors = listOf(Color(0xFFFB5C74), Color(0xFFFA233B)),
                start = Offset(12f, 0f),
                end = Offset(12f, 24f),
            ),
        )
    }
}.build()

internal fun buildSpotifyOfficialVector(scale: Float = 1f): ImageVector = ImageVector.Builder(
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    group(scaleX = scale, scaleY = scale, pivotX = 12f, pivotY = 12f) {
        addPath(
            pathData = addPathNodes("M12,2.4 A9.6,9.6 0 1,0 12,21.6 A9.6,9.6 0 1,0 12,2.4 Z"),
            fill = SolidColor(Color(0xFF121212)),
        )
        addPath(
            pathData = addPathNodes(OFFICIAL_SPOTIFY_PATH),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color(0xFF1ED760)),
        )
    }
}.build()
