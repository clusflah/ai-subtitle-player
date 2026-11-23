package com.aisub.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aisub.player.music.WebMusicService
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType

/**
 * 在线内容与本地分类的彩色图标类型。
 */
enum class SourceBadgeKind {
    LIVE,
    STREAM,
    WEB,
    TELEGRAM,
    LOCAL_PHONE,
    LOCAL_DOWNLOAD,
    LOCAL_MOVIES,
    LOCAL_MUSIC,
    LOCAL_DCIM,
    LOCAL_PICKER,
    MANGA,
    BOOK,
}

/**
 * 判断某个 [ServerType] 自身图标是否已包含完整底板（圆角方块或圆形底色）。
 * 不含底板的透明镂空标志在深浅复杂背景独立展示时可叠加轻度毛玻璃底衬。
 */
fun ServerType.hasSolidIconBackdrop(): Boolean = when (this) {
    ServerType.PLEX,
    ServerType.SUBSONIC,
    ServerType.PAN115,
    ServerType.PIKPAK,
    -> true
    else -> false
}

/**
 * Apple Music / Spotify / YouTube Music / 网易云音乐 / QQ音乐 官方品牌矢量图标。
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
            WebMusicService.YOUTUBE_MUSIC -> buildYouTubeMusicOfficialVector()
            WebMusicService.NETEASE_MUSIC -> buildNetEaseMusicOfficialVector()
            WebMusicService.QQ_MUSIC -> buildQqMusicOfficialVector()
        }
    }
    ImageIcon(vector = vector, contentDescription = service.label, size = size, modifier = modifier)
}

/**
 * 常见音乐流媒体平台图标（Apple Music、Spotify、网易云音乐、QQ音乐、YouTube Music、酷狗音乐、汽水音乐等）。
 */
@Composable
fun MusicPlatformIcon(
    platformName: String,
    accentHex: Long,
    fallbackBadge: String,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    val norm = platformName.trim().lowercase()
    when {
        norm.contains("apple") -> WebMusicServiceIcon(WebMusicService.APPLE_MUSIC, size = size, modifier = modifier)
        norm.contains("spotify") -> WebMusicServiceIcon(WebMusicService.SPOTIFY, size = size, modifier = modifier)
        norm.contains("网易") || norm.contains("netease") -> {
            val vec = remember { buildNetEaseMusicOfficialVector() }
            ImageIcon(vector = vec, contentDescription = platformName, size = size, modifier = modifier)
        }
        norm.contains("youtube") -> {
            val vec = remember { buildYouTubeMusicOfficialVector() }
            ImageIcon(vector = vec, contentDescription = platformName, size = size, modifier = modifier)
        }
        norm.contains("qq") -> {
            val vec = remember { buildQqMusicOfficialVector() }
            ImageIcon(vector = vec, contentDescription = platformName, size = size, modifier = modifier)
        }
        else -> {
            Box(
                modifier
                    .size(size)
                    .clip(RoundedCornerShape(size * 0.24f))
                    .background(Color(accentHex))
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = fallbackBadge.take(2),
                    color = Color.White,
                    fontSize = (size.value * 0.40f).sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ImageIcon(
    vector: ImageVector,
    contentDescription: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.Image(
        imageVector = vector,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
    )
}

/**
 * 根据已保存的 [Server] 渲染对应的品牌图标；
 * 若旧版保存为 [ServerType.EMBY] 但名称或地址包含 jellyfin，则自动显示 Jellyfin 官方图标。
 */
@Composable
fun ServerSourceIcon(server: Server, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val effectiveType = if (
        server.type == ServerType.EMBY &&
        (server.name.contains("jellyfin", ignoreCase = true) || server.baseUrl.contains("jellyfin", ignoreCase = true))
    ) {
        ServerType.JELLYFIN
    } else {
        server.type
    }
    ServerTypeIcon(type = effectiveType, size = size, modifier = modifier)
}

/**
 * 为各个 [ServerType]（Plex、Navidrome/Subsonic、Emby、Jellyfin、OpenList、WebDAV、115、阿里云盘、百度网盘、PikPak、OneDrive、Dropbox、Google Drive）
 * 渲染官方品牌与协议图标。
 */
@Composable
fun ServerTypeIcon(type: ServerType, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    when (type) {
        ServerType.PLEX -> {
            val vec = remember { buildPlexOfficialVector() }
            ImageIcon(vec, type.label, size, modifier)
        }
        ServerType.SUBSONIC -> {
            val vec = remember { buildNavidromeOfficialVector() }
            ImageIcon(vec, type.label, size, modifier)
        }
        ServerType.EMBY -> {
            val vec = remember { buildEmbyOfficialVector() }
            ImageIcon(vec, type.label, size, modifier)
        }
        ServerType.JELLYFIN -> {
            val vec = remember { buildJellyfinOfficialVector() }
            ImageIcon(vec, type.label, size, modifier)
        }
        ServerType.OPENLIST -> {
            val vec = remember { buildOpenListOfficialVector() }
            ImageIcon(vec, type.label, size, modifier)
        }
        else -> {
            Canvas(modifier.size(size)) {
                val s = this.size.minDimension / 24f
                when (type) {
                    ServerType.WEBDAV -> drawWebDavIcon(s)
                    ServerType.PAN115 -> drawPan115Icon(s)
                    ServerType.ALIYUN -> drawAliyunIcon(s)
                    ServerType.BAIDU -> drawBaiduIcon(s)
                    ServerType.PIKPAK -> drawPikPakIcon(s)
                    ServerType.ONEDRIVE -> drawOneDriveIcon(s)
                    ServerType.DROPBOX -> drawDropboxIcon(s)
                    ServerType.GDRIVE -> drawGoogleDriveIcon(s)
                    else -> {}
                }
            }
        }
    }
}

/**
 * 为在线内容（直播、串流、网页、Telegram）及本地分类绘制彩色圆角徽章图标。
 */
@Composable
fun SourceBadgeIcon(kind: SourceBadgeKind, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    if (kind == SourceBadgeKind.TELEGRAM) {
        Canvas(modifier.size(size)) {
            drawTelegramBrandIcon(this.size.minDimension / 24f)
        }
        return
    }
    val (colors, icon) = when (kind) {
        SourceBadgeKind.LIVE -> listOf(Color(0xFFFF5252), Color(0xFFD50000)) to AppIcons.Live
        SourceBadgeKind.STREAM -> listOf(Color(0xFF7C4DFF), Color(0xFF3D5AFE)) to AppIcons.Link
        SourceBadgeKind.WEB -> listOf(Color(0xFF29B6F6), Color(0xFF0288D1)) to AppIcons.Web
        SourceBadgeKind.TELEGRAM -> listOf(Color(0xFF37AEE2), Color(0xFF1E96C8)) to AppIcons.Telegram
        SourceBadgeKind.LOCAL_PHONE -> listOf(Color(0xFF5C6BC0), Color(0xFF3949AB)) to AppIcons.Phone
        SourceBadgeKind.LOCAL_DOWNLOAD -> listOf(Color(0xFF26A69A), Color(0xFF00897B)) to AppIcons.Download
        SourceBadgeKind.LOCAL_MOVIES -> listOf(Color(0xFFFFA726), Color(0xFFF57C00)) to AppIcons.Movie
        SourceBadgeKind.LOCAL_MUSIC -> listOf(Color(0xFF7E57C2), Color(0xFF512DA8)) to AppIcons.MusicNote
        SourceBadgeKind.LOCAL_DCIM -> listOf(Color(0xFFAB47BC), Color(0xFF8E24AA)) to AppIcons.Image
        SourceBadgeKind.LOCAL_PICKER -> listOf(Color(0xFFFFCA28), Color(0xFFF9A825)) to AppIcons.Folder
        SourceBadgeKind.MANGA -> listOf(Color(0xFFFF8A65), Color(0xFFF4511E)) to AppIcons.MenuBook
        SourceBadgeKind.BOOK -> listOf(Color(0xFF66BB6A), Color(0xFF2E7D32)) to AppIcons.Description
    }
    ColoredVectorBadge(icon = icon, colors = colors, size = size, modifier = modifier)
}

@Composable
private fun ColoredVectorBadge(
    icon: ImageVector,
    colors: List<Color>,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(size * 0.24f)
    Box(
        modifier
            .size(size)
            .liquidGlass(
                shape = shape,
                style = GlassStyle.Pill,
                tint = colors.first().copy(alpha = 0.92f),
                enableBackdrop = false,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (colors.size > 1) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(Color.Transparent, colors.last().copy(alpha = 0.45f)),
                    ),
                    cornerRadius = CornerRadius(size.toPx() * 0.24f, size.toPx() * 0.24f),
                )
            }
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.56f),
        )
    }
}

// ============================================================================
// 官方品牌 SVG 矢量定义（Simple Icons / 官方品牌规范）
// ============================================================================

internal const val OFFICIAL_APPLE_MUSIC_PATH =
    "M23.997 6.124c0-.738-.065-1.47-.24-2.19-.317-1.31-1.062-2.31-2.18-3.043C21.003.517 20.373.285 19.7.164c-.517-.093-1.038-.135-1.564-.15-.04-.003-.083-.01-.124-.013H5.988c-.152.01-.303.017-.455.026C4.786.07 4.043.15 3.34.428 2.004.958 1.04 1.88.475 3.208c-.192.448-.292.925-.363 1.408-.056.392-.088.785-.1 1.18 0 .032-.007.062-.01.093v12.223c.01.14.017.283.027.424.05.815.154 1.624.497 2.373.65 1.42 1.738 2.353 3.234 2.802.42.127.856.187 1.293.228.555.053 1.11.06 1.667.06h11.03c.525 0 1.048-.034 1.57-.1.823-.106 1.597-.35 2.296-.81.84-.553 1.472-1.287 1.88-2.208.186-.42.293-.87.37-1.324.113-.675.138-1.358.137-2.04-.002-3.8 0-7.595-.003-11.393zm-6.423 3.99v5.712c0 .417-.058.827-.244 1.206-.29.59-.76.962-1.388 1.14-.35.1-.706.157-1.07.173-.95.045-1.773-.6-1.943-1.536-.142-.773.227-1.624 1.038-2.022.323-.16.67-.25 1.018-.324.378-.082.758-.153 1.134-.24.274-.063.457-.23.51-.516.014-.063.02-.13.02-.193 0-1.815 0-3.63-.002-5.443 0-.062-.01-.125-.026-.185-.04-.15-.15-.243-.304-.234-.16.01-.318.035-.475.066-.76.15-1.52.303-2.28.456l-2.326.47-1.374.278c-.016.003-.032.01-.048.013-.277.077-.377.203-.39.49-.002.042 0 .086 0 .13-.002 2.602 0 5.204-.003 7.805 0 .42-.047.836-.215 1.227-.278.64-.77 1.04-1.434 1.233-.35.1-.71.16-1.075.172-.96.036-1.755-.6-1.92-1.544-.14-.812.23-1.685 1.154-2.075.357-.15.73-.232 1.108-.31.287-.06.575-.116.86-.177.383-.083.583-.323.6-.714v-.15c0-2.96 0-5.922.002-8.882 0-.123.013-.25.042-.37.07-.285.273-.448.546-.518.255-.066.515-.112.774-.165.733-.15 1.466-.296 2.2-.444l2.27-.46c.67-.134 1.34-.27 2.01-.403.22-.043.443-.088.664-.106.31-.025.523.17.554.482.008.073.012.148.012.223.002 1.91.002 3.822 0 5.732z"

internal const val OFFICIAL_SPOTIFY_PATH =
    "M12 0C5.4 0 0 5.4 0 12s5.4 12 12 12 12-5.4 12-12S18.66 0 12 0zm5.521 17.34c-.24.359-.66.48-1.021.24-2.82-1.74-6.36-2.101-10.561-1.141-.418.122-.779-.179-.899-.539-.12-.421.18-.78.54-.9 4.56-1.021 8.52-.6 11.64 1.32.42.18.479.659.301 1.02zm1.44-3.3c-.301.42-.841.6-1.262.3-3.239-1.98-8.159-2.58-11.939-1.38-.479.12-1.02-.12-1.14-.6-.12-.48.12-1.021.6-1.141C9.6 9.9 15 10.561 18.72 12.84c.361.181.54.78.241 1.2zm.12-3.36C15.24 8.4 8.82 8.16 5.16 9.301c-.6.179-1.2-.181-1.38-.721-.18-.601.18-1.2.72-1.381 4.26-1.26 11.28-1.02 15.721 1.621.539.3.719 1.02.419 1.56-.299.421-1.02.599-1.559.3z"

internal const val OFFICIAL_EMBY_OUTER_PATH =
    "M11.041 0c-.007 0-1.456 1.43-3.219 3.176L4.615 6.352l.512.513.512.512-2.819 2.791L0 12.961l1.83 1.848c1.006 1.016 2.438 2.46 3.182 3.209l1.351 1.359.508-.496c.28-.273.515-.498.524-.498.008 0 1.266 1.264 2.794 2.808L12.97 24l.187-.182c.23-.225 5.007-4.95 5.717-5.656l.52-.516-.502-.513c-.276-.282-.5-.52-.496-.53.003-.009 1.264-1.26 2.802-2.783 1.538-1.522 2.8-2.776 2.803-2.785.005-.012-3.617-3.684-6.107-6.193L17.65 4.6l-.505.505c-.279.278-.517.501-.53.497-.013-.005-1.27-1.267-2.793-2.805L11.041 0z"

internal const val OFFICIAL_EMBY_PLAY_PATH =
    "M9.223 7.367L17.18 11.994L9.219 16.621Z"

internal const val OFFICIAL_JELLYFIN_PATH =
    "M12 .002C8.826.002-1.398 18.537.16 21.666c1.56 3.129 22.14 3.094 23.682 0C25.384 18.573 15.177 0 12 0zm7.76 18.949c-1.008 2.028-14.493 2.05-15.514 0C3.224 16.9 9.92 4.755 12.003 4.755c2.081 0 8.77 12.166 7.759 14.196zM12 9.198c-1.054 0-4.446 6.15-3.93 7.189.518 1.04 7.348 1.027 7.86 0 .511-1.027-2.874-7.19-3.93-7.19z"

internal const val OFFICIAL_NAVIDROME_GROOVES_512 =
    "M256 0C114.8 0 0 114.8 0 256s114.8 256 256 256 256-114.8 256-256S397.2 0 256 0m0 491.3C126.3 491.3 20.7 385.7 20.7 256S126.3 20.7 256 20.7 491.3 126.3 491.3 256 385.7 491.3 256 491.3m0-137.9c-53.7 0-97.4-43.7-97.4-97.4s43.7-97.4 97.4-97.4 97.4 43.7 97.4 97.4-43.7 97.4-97.4 97.4m0-174.8c-42.7 0-77.4 34.7-77.4 77.4s34.7 77.4 77.4 77.4 77.4-34.7 77.4-77.4-34.7-77.4-77.4-77.4m0 235c-5.5 0-10-4.5-10-10s4.5-10 10-10c75.9 0 137.6-61.7 137.6-137.6 0-5.5 4.5-10 10-10s10 4.5 10 10c0 86.9-70.7 157.6-157.6 157.6M108.4 266c-5.5 0-10-4.5-10-10 0-86.9 70.7-157.6 157.6-157.6 5.5 0 10 4.5 10 10s-4.5 10-10 10c-75.9 0-137.6 61.7-137.6 137.6 0 5.5-4.5 10-10 10M256 459.8c-5.5 0-10-4.5-10-10s4.5-10 10-10c85.4 0 160.9-60.3 179.5-143.4 1.2-5.4 6.6-8.8 11.9-7.6 5.4 1.2 8.8 6.6 7.6 11.9-10 44.5-35.1 84.8-70.7 113.7-36.1 29.3-81.7 45.4-128.3 45.4m193.6-186.4h-.8c-5.5-.5-9.6-5.3-9.1-10.8.2-2 .2-4.3.2-6.6 0-5.5 4.5-10 10-10s10 4.5 10 10c0 2.5 0 5.4-.2 8.2-.5 5.3-4.9 9.2-10.1 9.2M62.2 266c-5.5 0-10-4.5-10-10 0-2.4 0-4.9.2-7.5.5-5.5 5.4-9.5 10.9-9.1 5.5.5 9.6 5.4 9.1 10.9-.2 1.7-.2 3.7-.2 5.7 0 5.5-4.5 10-10 10m3.8-38.6c-.7 0-1.3-.1-2-.2-5.4-1.1-8.9-6.4-7.8-11.8C75.4 120.8 159.5 52.2 256 52.2c5.5 0 10 4.5 10 10s-4.5 10-10 10c-87.1 0-162.9 61.9-180.2 147.2-1 4.7-5.2 8-9.8 8M256 284c-15.4 0-28-12.6-28-28s12.6-28 28-28 28 12.6 28 28-12.6 28-28 28m0-36c-4.4 0-8 3.6-8 8s3.6 8 8 8 8-3.6 8-8-3.6-8-8-8z"

internal const val OFFICIAL_OPENLIST_MINT_512 =
    "M207.5 2.7c20.9-3.4 42.3-3 63.4-1.7 19.2 1.7 38.1 6.2 56.6 11.5.5.3 1 .7 1.5 1 2 .2 3.9.9 5.9 1.5l1.1 1.1c16.8 5.3 32.3 13.9 47.5 22.6 15.5 9.6 29.6 21.2 42.9 33.5 2.9 3.2 6.2 6 9.2 9.2 3.5 5.3 8.8 9.1 12.1 14.5 12.4 15 22.5 31.6 30.7 49.2 1 2.4 2.5 4.6 3.1 7.1 1.2 3.3 3.6 6.3 3.7 9.8.4.5.8.9 1.3 1.4v2c.3.2.9.7 1.2 1 0 .7.1 1.4.1 2.1 6.5 16.4 10 34.1 12.9 51.5 4.6 38.4 1.1 77.8-11.4 114.5-1.1 4.5-3 8.6-4.4 13-.2.3-.7.8-.9 1.1-.3 2.4-1.4 4.5-2.5 6.6-1 3.6-3.4 6.7-4.4 10.4-3.3 5.6-5.7 11.8-9 17.5-.3.2-.8.5-1 .7 0 .4-.1.8-.1 1.2-1.5 1.6-2.2 3.8-3.6 5.5-5 8.2-10.4 16.4-17.1 23.3 0 .3.1.6.1.9-18.1 20.4-37.6 39.8-60.6 54.6-.5 0-1.4-.1-1.8-.2v1.6c-6.4 3.7-12.7 7.6-19 11.5h-1.4c-5 3.9-11.3 5.7-16.7 8.9-3.3.7-6.1 2.5-9.1 3.9-1.4.2-2.7.7-3.8 1.5-.5 0-1.1.1-1.6.1-1 .7-2.1 1.2-3.2 1.4-16 5.8-32.7 10-49.6 12-16.4 2.8-33.2 2.2-49.9 2-10.6-1.4-21.8-.9-31.7-4.9-20.1-6.4-31.8-31.5-22.6-50.8 3.9-7.1 7.9-14.1 12.6-20.7 0-.3.1-1 .1-1.4 2.5-1.7 3.2-4.8 4.9-7.1 8.5-13 16.3-26.4 24.7-39.5 0-.5.1-.9.1-1.4.3-.2.8-.5 1.1-.7 11.6-18.2 23.1-36.5 34.4-54.9 8.4-12.5 15.9-25.6 24.3-38.1 7.1-11.4 14-23 22-33.9.3-.1.9-.4 1.2-.5 2.9-4.7 8.4-6.4 12.5-9.7 3.9-.8 7.6-2.7 11.6-2.9 7-1.3 14 .5 20.5 2.9 7.1 3.8 13.7 8.9 17.8 15.9.8 1.8 1.7 3.6 2.7 5.3 3 8.5 4.8 18.2 1.4 26.9-2.8 10.3-9.8 18.7-15.1 27.7-3.1 4.9-6.1 9.9-9.3 14.7-.1.3-.2 1-.2 1.3-7.1 9.3-12.6 19.7-19.2 29.3-.1.3-.2.8-.3 1.1-4.2 6.1-7.9 12.5-11.9 18.7-3.3 5-6.6 10-9.6 15.2-3.8 5.5-7 11.3-10.4 16.9-1.8 3.2-4.3 5.9-5.7 9.3-1.1 1.1-2 2.5-2.1 4.1 12.4-.8 23.9-6.5 35.4-10.7 4.2-2.2 8.7-3.8 12.7-6.2h1.4c1.8-1.9 4.6-2.6 6.8-4 21.4-12.2 40-29.1 54.9-48.8 6-9.1 12.2-18 17-27.7 4.8-9.9 8.8-20.2 11.9-30.7.7-2.3 2.1-4.5 1.5-7.1.2.1.7.2.9.3 2.8-14 5.4-28.2 5.8-42.6.3-27.4-5.2-54.8-15.8-80-4.8-9-8.7-18.8-15.4-26.7-.1-.4-.1-.9-.1-1.3-10.3-14-21.6-27.8-35.8-38-.2-.3-.5-.8-.6-1.1-4.2-2.5-7.6-6.2-12-8.4-4.3-3.5-9.4-5.7-14-8.7-.4-.1-.7-.1-1-.2-.4-.4-.8-.9-1.2-1.3-.3 0-1-.1-1.4-.1-3.4-2.3-7.2-4-11.1-5.4-1.9-1.1-3.9-2-6-2.5-.3-.2-.8-.7-1-1-15.5-5.4-31.4-10.1-47.8-11.8-12.6-2.3-25.5-.9-38.2-1.3-17.7 1.9-35.3 4.9-51.9 11.6h-1.4c-4.7 3-10.7 4.4-15.4 7.8h-1.6c-9 6.1-19 11-27.3 18.2-2.4 2-4.8 3.9-7.3 5.6-3.3 4.4-8.4 7.1-11.5 11.8-12.1 12.2-22.3 26.1-30.7 41-2.1 2.9-3 6.5-4.9 9.4v1.3c-3.5 4.6-5 10.9-10 14.3-2.7 3.4-6.9 5-10.4 7.4h-2.2l-1.5 1.2c-8.9 1.3-18.5 1-26.4-3.7-.3-.1-1-.1-1.3-.2-2.6-3-6.4-4.6-8.7-7.9-3-2.6-4-6.5-6.1-9.7 0-.4.1-1.3.2-1.7-.4-.7-.9-1.4-1.3-2.1-.1-4.4-1.5-8.7-.6-13 .3-6.5 3.5-12.3 5.9-18.2 1.2-2.8 3.1-5.2 4-8.2 1.1-1.3 2-2.8 2.5-4.5 18.3-32.3 43.2-61 73.2-83.1 3.3-2.4 7-4.5 10-7.4.4-.1 1.1-.2 1.4-.2 11.9-8 25-14.3 38-20.4 1.8-.5 3.7-1.1 5.5-1.7 17.7-7.6 37.3-12 56.9-14.4z"

internal const val OFFICIAL_OPENLIST_BLUE_512 =
    "M204.5 147.7c16.5-2.6 34.2 7.6 39.5 23.5 1.3 5.5 1.8 11.2 2.2 16.9-.5 10.1-7.1 18.3-12.2 26.6-42.6 67.5-86 134.5-128.4 202.2-5 8.8-13.3 15.9-23.3 18.2-19.1 6-41.4-6.9-46-26.3-1.9-9.2-1-19.2 3.8-27.4C66.4 339.3 93.7 298 119.8 256c19.5-30.2 38.5-60.8 58.1-90.9 5.8-9.3 15.7-15.8 26.6-17.4z"

internal const val OFFICIAL_NETEASE_MUSIC_PATH =
    "M13.046 9.388a3.919 3.919 0 0 0-.66.19c-.809.312-1.447.991-1.666 1.775a2.269 2.269 0 0 0-.074.81c.048.546.333 1.05.764 1.35a1.483 1.483 0 0 0 2.01-.286c.406-.531.355-1.183.24-1.636-.098-.387-.22-.816-.345-1.249a64.76 64.76 0 0 1-.269-.954zm-.82 10.07c-3.984 0-7.224-3.24-7.224-7.223 0-.98.226-3.02 1.884-4.822A7.188 7.188 0 0 1 9.502 5.6a.792.792 0 1 1 .587 1.472 5.619 5.619 0 0 0-2.795 2.462 5.538 5.538 0 0 0-.707 2.7 5.645 5.645 0 0 0 5.638 5.638c1.844 0 3.627-.953 4.542-2.428 1.042-1.68.772-3.931-.627-5.238a3.299 3.299 0 0 0-1.437-.777c.172.589.334 1.18.494 1.772.284 1.12.1 2.181-.519 2.989-.39.51-.956.888-1.592 1.064a3.038 3.038 0 0 1-2.58-.44 3.45 3.45 0 0 1-1.44-2.514c-.04-.467.002-.93.128-1.376.35-1.256 1.356-2.339 2.622-2.826a5.5 5.5 0 0 1 .823-.246l-.134-.505c-.37-1.371.25-2.579 1.547-3.007.329-.109.68-.145 1.025-.105.792.09 1.476.592 1.709 1.023.258.507-.096 1.153-.706 1.153a.788.788 0 0 1-.54-.213c-.088-.08-.163-.174-.259-.247a.825.825 0 0 0-.632-.166.807.807 0 0 0-.634.551c-.056.191-.031.406.02.595.07.256.159.597.217.82 1.11.098 2.162.54 2.97 1.296 1.974 1.844 2.35 4.886.892 7.233-1.197 1.93-3.509 3.177-5.889 3.177zM0 12c0 6.627 5.373 12 12 12s12-5.373 12-12S18.627 0 12 0 0 5.373 0 12Z"

internal const val OFFICIAL_YOUTUBE_MUSIC_PATH =
    "M12 0C5.376 0 0 5.376 0 12s5.376 12 12 12 12-5.376 12-12S18.624 0 12 0zm0 19.104c-3.924 0-7.104-3.18-7.104-7.104S8.076 4.896 12 4.896s7.104 3.18 7.104 7.104-3.18 7.104-7.104 7.104zm0-13.332c-3.432 0-6.228 2.796-6.228 6.228S8.568 18.228 12 18.228s6.228-2.796 6.228-6.228S15.432 5.772 12 5.772zM9.684 15.54V8.46L15.816 12l-6.132 3.54z"

internal fun buildAppleMusicOfficialVector(scale: Float = 1f): ImageVector = ImageVector.Builder(
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    group(scaleX = scale, scaleY = scale, pivotX = 12f, pivotY = 12f) {
        // 内部纯白衬底（填充镂空双连音符，完全收纳于外圆角框之内）
        addPath(
            pathData = addPathNodes("M4.2,3.4 h15.6 v17.4 h-15.6 z"),
            fill = SolidColor(Color.White),
        )
        // 官方 Apple Music 粉红到亮红垂直渐变圆角框 + 镂空八分连音符
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
        // 内部深黑衬底（填充三道弧形声波纹）
        addPath(
            pathData = addPathNodes("M12,2.5 C17.24,2.5 21.5,6.76 21.5,12 C21.5,17.24 17.24,21.5 12,21.5 C6.76,21.5 2.5,17.24 2.5,12 C2.5,6.76 6.76,2.5 12,2.5 Z"),
            fill = SolidColor(Color(0xFF121212)),
        )
        // 官方 Spotify Green (#1ED760) 圆盘 + 镂空三道声波纹
        addPath(
            pathData = addPathNodes(OFFICIAL_SPOTIFY_PATH),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color(0xFF1ED760)),
        )
    }
}.build()

internal fun buildPlexOfficialVector(): ImageVector = ImageVector.Builder(
    defaultWidth = 512.dp,
    defaultHeight = 512.dp,
    viewportWidth = 512f,
    viewportHeight = 512f,
).apply {
    group(scaleX = 0.90f, scaleY = 0.90f, pivotX = 256f, pivotY = 256f) {
        addPath(
            pathData = addPathNodes(
                "M76.8,0 H435.2 C477.6,0 512,34.4 512,76.8 V435.2 C512,477.6 477.6,512 435.2,512 H76.8 C34.4,512 0,477.6 0,435.2 V76.8 C0,34.4 34.4,0 76.8,0 Z",
            ),
            fill = SolidColor(Color(0xFF282A2D)),
        )
        addPath(
            pathData = addPathNodes("M256 70H148l108 186-108 186h108l108-186z"),
            fill = SolidColor(Color(0xFFE5A00D)),
        )
    }
}.build()

internal fun buildNavidromeOfficialVector(): ImageVector = ImageVector.Builder(
    defaultWidth = 512.dp,
    defaultHeight = 512.dp,
    viewportWidth = 512f,
    viewportHeight = 512f,
).apply {
    group(scaleX = 0.90f, scaleY = 0.90f, pivotX = 256f, pivotY = 256f) {
        addPath(
            pathData = addPathNodes("M256,10.4 C391.6,10.4 501.6,120.4 501.6,256 C501.6,391.6 391.6,501.6 256,501.6 C120.4,501.6 10.4,391.6 10.4,256 C10.4,120.4 120.4,10.4 256,10.4 Z"),
            fill = SolidColor(Color(0xFF0084FF)),
        )
        addPath(
            pathData = addPathNodes("M256,168.6 C304.3,168.6 343.4,207.7 343.4,256 C343.4,304.3 304.3,343.4 256,343.4 C207.7,343.4 168.6,304.3 168.6,256 C168.6,207.7 207.7,168.6 256,168.6 Z"),
            fill = SolidColor(Color.White),
        )
        addPath(
            pathData = addPathNodes(OFFICIAL_NAVIDROME_GROOVES_512),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color(0xFF0B1320)),
        )
    }
}.build()

internal fun buildEmbyOfficialVector(): ImageVector = ImageVector.Builder(
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    group(scaleX = 0.90f, scaleY = 0.90f, pivotX = 12f, pivotY = 12f) {
        addPath(
            pathData = addPathNodes(OFFICIAL_EMBY_OUTER_PATH),
            fill = SolidColor(Color(0xFF52B54B)),
        )
        addPath(
            pathData = addPathNodes(OFFICIAL_EMBY_PLAY_PATH),
            fill = SolidColor(Color.White),
        )
    }
}.build()

internal fun buildJellyfinOfficialVector(): ImageVector = ImageVector.Builder(
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    group(scaleX = 0.90f, scaleY = 0.90f, pivotX = 12f, pivotY = 12f) {
        addPath(
            pathData = addPathNodes(OFFICIAL_JELLYFIN_PATH),
            pathFillType = PathFillType.EvenOdd,
            fill = Brush.linearGradient(
                colors = listOf(Color(0xFFAA5CC3), Color(0xFF00A4DC)),
                start = Offset(2f, 2f),
                end = Offset(22f, 22f),
            ),
        )
    }
}.build()

internal fun buildOpenListOfficialVector(): ImageVector = ImageVector.Builder(
    defaultWidth = 512.dp,
    defaultHeight = 512.dp,
    viewportWidth = 512f,
    viewportHeight = 512f,
).apply {
    group(scaleX = 0.88f, scaleY = 0.88f, pivotX = 256f, pivotY = 256f) {
        addPath(
            pathData = addPathNodes(OFFICIAL_OPENLIST_MINT_512),
            fill = SolidColor(Color(0xFF79DDD1)),
        )
        addPath(
            pathData = addPathNodes(OFFICIAL_OPENLIST_BLUE_512),
            fill = SolidColor(Color(0xFF10B0EA)),
        )
    }
}.build()

internal fun buildNetEaseMusicOfficialVector(): ImageVector = ImageVector.Builder(
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    addPath(
        pathData = addPathNodes("M12,2.5 C17.24,2.5 21.5,6.76 21.5,12 C21.5,17.24 17.24,21.5 12,21.5 C6.76,21.5 2.5,17.24 2.5,12 C2.5,6.76 6.76,2.5 12,2.5 Z"),
        fill = SolidColor(Color.White),
    )
    addPath(
        pathData = addPathNodes(OFFICIAL_NETEASE_MUSIC_PATH),
        pathFillType = PathFillType.EvenOdd,
        fill = SolidColor(Color(0xFFE60026)),
    )
}.build()

internal fun buildYouTubeMusicOfficialVector(): ImageVector = ImageVector.Builder(
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    addPath(
        pathData = addPathNodes("M12,3 C16.97,3 21,7.03 21,12 C21,16.97 16.97,21 12,21 C7.03,21 3,16.97 3,12 C3,7.03 7.03,3 12,3 Z"),
        fill = SolidColor(Color.White),
    )
    addPath(
        pathData = addPathNodes(OFFICIAL_YOUTUBE_MUSIC_PATH),
        pathFillType = PathFillType.EvenOdd,
        fill = SolidColor(Color(0xFFFF0000)),
    )
}.build()

internal fun buildQqMusicOfficialVector(): ImageVector = ImageVector.Builder(
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    // QQ 音乐标志性亮黄圆角底 + 翠绿音符
    addPath(
        pathData = addPathNodes("M5.6,0 H18.4 C21.5,0 24,2.5 24,5.6 V18.4 C24,21.5 21.5,24 18.4,24 H5.6 C2.5,24 0,21.5 0,18.4 V5.6 C0,2.5 2.5,0 5.6,0 Z"),
        fill = SolidColor(Color(0xFFFFD200)),
    )
    addPath(
        pathData = addPathNodes("M13.2,4.8 L18.6,6.3 L18.6,9.2 L15.0,8.2 L15.0,15.6 C15.0,18.1 12.9,20.0 10.2,20.0 C7.6,20.0 5.6,18.2 5.6,15.9 C5.6,13.6 7.6,11.8 10.2,11.8 C11.3,11.8 12.4,12.2 13.2,12.8 Z"),
        fill = SolidColor(Color(0xFF12B775)),
    )
}.build()

// ============================================================================
// 云盘与本地协议矢量绘制
// ============================================================================

/** WebDAV：银色底座支架 + 绿色立体文件夹。 */
private fun DrawScope.drawWebDavIcon(s: Float) {
    drawRoundRect(
        color = Color(0xFF90A4AE),
        topLeft = Offset(9.5f * s, 17.5f * s),
        size = Size(5f * s, 2.6f * s),
        cornerRadius = CornerRadius(0.8f * s),
    )
    drawRoundRect(
        brush = Brush.horizontalGradient(
            listOf(Color(0xFFB0BEC5), Color(0xFFECEFF1), Color(0xFF90A4AE)),
        ),
        topLeft = Offset(5.5f * s, 19.6f * s),
        size = Size(13f * s, 2.0f * s),
        cornerRadius = CornerRadius(1.0f * s),
    )
    val backPath = Path().apply {
        moveTo(2.6f * s, 5.5f * s)
        lineTo(9.2f * s, 5.5f * s)
        lineTo(11.0f * s, 7.4f * s)
        lineTo(21.4f * s, 7.4f * s)
        lineTo(21.4f * s, 17.6f * s)
        lineTo(2.6f * s, 17.6f * s)
        close()
    }
    drawPath(backPath, color = Color(0xFF2E7D32))
    drawRoundRect(
        color = Color(0xFFF5F7FA),
        topLeft = Offset(4.0f * s, 7.0f * s),
        size = Size(16.0f * s, 4.0f * s),
        cornerRadius = CornerRadius(1.2f * s),
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF9CCC65), Color(0xFF43A047), Color(0xFF2E7D32)),
            startY = 8.2f * s,
            endY = 18.2f * s,
        ),
        topLeft = Offset(2.2f * s, 8.4f * s),
        size = Size(19.6f * s, 9.8f * s),
        cornerRadius = CornerRadius(2.2f * s),
    )
}

/** 115 网盘：经典深海蓝艺术字 "5" 标识。 */
private fun DrawScope.drawPan115Icon(s: Float) {
    drawRoundRect(
        color = Color(0xFFF2F6FC),
        topLeft = Offset(2.0f * s, 2.0f * s),
        size = Size(20.0f * s, 20.0f * s),
        cornerRadius = CornerRadius(5.5f * s),
    )
    val blueBrush = Brush.verticalGradient(
        listOf(Color(0xFF1E5BB0), Color(0xFF0E3A75)),
    )
    val fivePath = Path().apply {
        moveTo(8.2f * s, 5.5f * s)
        lineTo(16.8f * s, 5.5f * s)
        lineTo(16.0f * s, 7.7f * s)
        lineTo(10.0f * s, 7.7f * s)
        lineTo(9.3f * s, 10.5f * s)
        cubicTo(14.5f * s, 9.8f * s, 17.5f * s, 12.2f * s, 16.8f * s, 15.8f * s)
        cubicTo(16.2f * s, 18.6f * s, 13.0f * s, 20.2f * s, 8.2f * s, 19.4f * s)
        lineTo(8.6f * s, 17.2f * s)
        cubicTo(11.8f * s, 17.8f * s, 13.9f * s, 16.8f * s, 14.2f * s, 14.8f * s)
        cubicTo(14.5f * s, 12.6f * s, 12.2f * s, 11.6f * s, 7.4f * s, 12.4f * s)
        close()
    }
    drawPath(fivePath, brush = blueBrush)
}

/** 阿里云盘：紫蓝色圆环 + 右下延伸尾翼。 */
private fun DrawScope.drawAliyunIcon(s: Float) {
    val brush = Brush.linearGradient(
        listOf(Color(0xFF5B67FF), Color(0xFF7E8BFF)),
        start = Offset(4f * s, 4f * s),
        end = Offset(20f * s, 20f * s),
    )
    drawCircle(
        brush = brush,
        radius = 7.4f * s,
        center = Offset(11.6f * s, 11.6f * s),
        style = Stroke(width = 3.2f * s, cap = StrokeCap.Round),
    )
    drawLine(
        brush = brush,
        start = Offset(14.5f * s, 17.8f * s),
        end = Offset(19.2f * s, 17.8f * s),
        strokeWidth = 3.2f * s,
        cap = StrokeCap.Round,
    )
}

/** 百度网盘：红、蓝、青三色相扣云环（品字形三圆）。 */
private fun DrawScope.drawBaiduIcon(s: Float) {
    val stroke = Stroke(width = 2.5f * s, cap = StrokeCap.Round)
    val topCenter = Offset(12.0f * s, 7.4f * s)
    val leftCenter = Offset(7.4f * s, 15.2f * s)
    val rightCenter = Offset(16.6f * s, 15.2f * s)
    val r = 3.5f * s

    drawLine(
        color = Color(0xFF2979FF),
        start = Offset(9.4f * s, 9.6f * s),
        end = Offset(7.8f * s, 12.0f * s),
        strokeWidth = 2.4f * s,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = Color(0xFF00B8D4),
        start = Offset(10.6f * s, 15.2f * s),
        end = Offset(13.4f * s, 15.2f * s),
        strokeWidth = 2.4f * s,
        cap = StrokeCap.Round,
    )

    drawCircle(color = Color(0xFFFF3B4E), radius = r, center = topCenter, style = stroke)
    drawCircle(color = Color(0xFF2979FF), radius = r, center = leftCenter, style = stroke)
    drawCircle(color = Color(0xFF00B8D4), radius = r, center = rightCenter, style = stroke)
}

/** PikPak：亮蓝圆角底 + 白色闪电纸飞机标识。 */
private fun DrawScope.drawPikPakIcon(s: Float) {
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(Color(0xFF2B68FF), Color(0xFF00A6FF)),
        ),
        topLeft = Offset(2.0f * s, 2.0f * s),
        size = Size(20.0f * s, 20.0f * s),
        cornerRadius = CornerRadius(5.5f * s),
    )
    val wing = Path().apply {
        moveTo(6.2f * s, 12.2f * s)
        lineTo(18.0f * s, 6.4f * s)
        lineTo(14.2f * s, 17.8f * s)
        lineTo(11.4f * s, 14.0f * s)
        close()
    }
    drawPath(wing, color = Color.White)
}

/** OneDrive：微软经典多层蓝色云朵。 */
private fun DrawScope.drawOneDriveIcon(s: Float) {
    drawCircle(
        color = Color(0xFF0364B8),
        radius = 4.6f * s,
        center = Offset(14.2f * s, 10.6f * s),
    )
    drawCircle(
        color = Color(0xFF0078D4),
        radius = 5.4f * s,
        center = Offset(9.6f * s, 11.4f * s),
    )
    drawCircle(
        color = Color(0xFF1490DF),
        radius = 4.0f * s,
        center = Offset(6.2f * s, 13.8f * s),
    )
    drawCircle(
        color = Color(0xFF28A8EA),
        radius = 3.8f * s,
        center = Offset(17.4f * s, 14.0f * s),
    )
    drawRoundRect(
        brush = Brush.horizontalGradient(
            listOf(Color(0xFF1490DF), Color(0xFF28A8EA)),
        ),
        topLeft = Offset(5.2f * s, 13.2f * s),
        size = Size(14.8f * s, 4.6f * s),
        cornerRadius = CornerRadius(2.3f * s),
    )
}

/** Dropbox：官方五菱形敞口盒标志。 */
private fun DrawScope.drawDropboxIcon(s: Float) {
    val blue = Color(0xFF0061FF)
    fun diamond(cx: Float, cy: Float, rx: Float, ry: Float) {
        val p = Path().apply {
            moveTo(cx * s, (cy - ry) * s)
            lineTo((cx + rx) * s, cy * s)
            lineTo(cx * s, (cy + ry) * s)
            lineTo((cx - rx) * s, cy * s)
            close()
        }
        drawPath(p, color = blue)
    }
    diamond(cx = 7.2f, cy = 7.2f, rx = 4.8f, ry = 3.0f)
    diamond(cx = 16.8f, cy = 7.2f, rx = 4.8f, ry = 3.0f)
    diamond(cx = 7.2f, cy = 13.2f, rx = 4.8f, ry = 3.0f)
    diamond(cx = 16.8f, cy = 13.2f, rx = 4.8f, ry = 3.0f)
    diamond(cx = 12.0f, cy = 17.6f, rx = 4.8f, ry = 2.8f)
}

/** Google Drive：四色三角缎带标识。 */
private fun DrawScope.drawGoogleDriveIcon(s: Float) {
    val leftArm = Path().apply {
        moveTo(9.2f * s, 3.6f * s)
        lineTo(14.8f * s, 3.6f * s)
        lineTo(7.6f * s, 16.0f * s)
        lineTo(2.0f * s, 16.0f * s)
        close()
    }
    drawPath(leftArm, color = Color(0xFF00AC47))

    val rightArm = Path().apply {
        moveTo(9.2f * s, 3.6f * s)
        lineTo(14.8f * s, 3.6f * s)
        lineTo(22.0f * s, 16.0f * s)
        lineTo(16.4f * s, 16.0f * s)
        close()
    }
    drawPath(rightArm, color = Color(0xFFFFBA00))

    val bottomArm = Path().apply {
        moveTo(7.6f * s, 15.6f * s)
        lineTo(22.0f * s, 15.6f * s)
        lineTo(19.2f * s, 20.4f * s)
        lineTo(4.8f * s, 20.4f * s)
        close()
    }
    drawPath(bottomArm, color = Color(0xFF2684FC))

    val redCorner = Path().apply {
        moveTo(2.0f * s, 16.0f * s)
        lineTo(7.6f * s, 16.0f * s)
        lineTo(4.8f * s, 20.4f * s)
        close()
    }
    drawPath(redCorner, color = Color(0xFFEA4335))
}

/** Telegram：天蓝渐变圆形 + 白色纸飞机。 */
private fun DrawScope.drawTelegramBrandIcon(s: Float) {
    drawCircle(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF37AEE2), Color(0xFF1E96C8)),
        ),
        radius = 10.4f * s,
        center = Offset(12f * s, 12f * s),
    )
    val plane = Path().apply {
        moveTo(5.2f * s, 11.8f * s)
        lineTo(17.6f * s, 6.8f * s)
        lineTo(15.4f * s, 17.6f * s)
        lineTo(11.4f * s, 14.4f * s)
        lineTo(9.4f * s, 16.2f * s)
        lineTo(9.6f * s, 13.2f * s)
        lineTo(15.2f * s, 8.6f * s)
        lineTo(8.4f * s, 12.6f * s)
        close()
    }
    drawPath(plane, color = Color.White)
}
