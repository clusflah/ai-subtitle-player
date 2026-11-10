package com.aisub.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * 根据已保存的 [Server] 渲染对应的品牌图标；
 * 若旧版保存为 [ServerType.EMBY] 但名称或地址包含 jellyfin，则自动显示 Jellyfin 图标。
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
 * 为各个 [ServerType]（WebDAV、OpenList、Emby、Jellyfin、Plex、115、阿里云盘、百度网盘、PikPak、OneDrive、Dropbox、Google Drive）
 * 绘制高辨识度的品牌与协议图标。
 */
@Composable
fun ServerTypeIcon(type: ServerType, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        when (type) {
            ServerType.WEBDAV -> drawWebDavIcon(s)
            ServerType.OPENLIST -> drawOpenListIcon(s)
            ServerType.EMBY -> drawEmbyIcon(s)
            ServerType.JELLYFIN -> drawJellyfinIcon(s)
            ServerType.PLEX -> drawPlexIcon(s)
            ServerType.PAN115 -> drawPan115Icon(s)
            ServerType.ALIYUN -> drawAliyunIcon(s)
            ServerType.BAIDU -> drawBaiduIcon(s)
            ServerType.PIKPAK -> drawPikPakIcon(s)
            ServerType.ONEDRIVE -> drawOneDriveIcon(s)
            ServerType.DROPBOX -> drawDropboxIcon(s)
            ServerType.GDRIVE -> drawGoogleDriveIcon(s)
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
        SourceBadgeKind.LOCAL_MUSIC -> listOf(Color(0xFFEC407A), Color(0xFFD81B60)) to AppIcons.MusicNote
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
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier
            .size(size)
            .liquidGlass(
                shape = shape,
                style = GlassStyle.Pill,
                tint = colors.first().copy(alpha = 0.88f),
                enableBackdrop = false,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                brush = Brush.linearGradient(colors),
                cornerRadius = CornerRadius(size.toPx() * 0.28f, size.toPx() * 0.28f),
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.54f),
        )
    }
}

/** WebDAV：银色底座支架 + 绿色立体文件夹。 */
private fun DrawScope.drawWebDavIcon(s: Float) {
    // 显示器银灰底座与支架
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
    // 文件夹后层（深绿）
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
    // 夹层白色内页高光
    drawRoundRect(
        color = Color(0xFFF5F7FA),
        topLeft = Offset(4.0f * s, 7.0f * s),
        size = Size(16.0f * s, 4.0f * s),
        cornerRadius = CornerRadius(1.2f * s),
    )
    // 文件夹前层（亮绿立体渐变）
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

/** OpenList / AList：多色几何环绕云盘标识。 */
private fun DrawScope.drawOpenListIcon(s: Float) {
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(Color(0xFF00BFA5), Color(0xFF2979FF)),
        ),
        topLeft = Offset(2.0f * s, 2.0f * s),
        size = Size(20.0f * s, 20.0f * s),
        cornerRadius = CornerRadius(5.5f * s),
    )
    // 内部白色多层目录环
    drawCircle(
        color = Color.White,
        radius = 5.6f * s,
        center = Offset(12f * s, 12f * s),
        style = Stroke(width = 2.4f * s),
    )
    drawCircle(
        color = Color(0xFFFFCA28),
        radius = 2.2f * s,
        center = Offset(12f * s, 12f * s),
    )
}

/** Emby：经典绿色交错旋转方块 + 中心白色播放三角。 */
private fun DrawScope.drawEmbyIcon(s: Float) {
    // 旋转菱形主背景
    val diamond = Path().apply {
        moveTo(12.0f * s, 1.8f * s)
        lineTo(17.4f * s, 7.2f * s)
        lineTo(16.2f * s, 8.4f * s)
        lineTo(22.2f * s, 12.0f * s)
        lineTo(16.8f * s, 17.4f * s)
        lineTo(15.6f * s, 16.2f * s)
        lineTo(12.0f * s, 22.2f * s)
        lineTo(6.6f * s, 16.8f * s)
        lineTo(7.8f * s, 15.6f * s)
        lineTo(1.8f * s, 12.0f * s)
        lineTo(7.2f * s, 6.6f * s)
        lineTo(8.4f * s, 7.8f * s)
        close()
    }
    drawPath(
        path = diamond,
        brush = Brush.linearGradient(
            listOf(Color(0xFF66BB6A), Color(0xFF52B54B), Color(0xFF388E3C)),
        ),
    )
    // 中心白色播放三角形
    val play = Path().apply {
        moveTo(9.8f * s, 8.0f * s)
        lineTo(16.2f * s, 12.0f * s)
        lineTo(9.8f * s, 16.0f * s)
        close()
    }
    drawPath(play, color = Color.White)
}

/** Jellyfin：紫到蓝青渐变的圆角三角环 + 内部渐变三角。 */
private fun DrawScope.drawJellyfinIcon(s: Float) {
    val brush = Brush.linearGradient(
        listOf(Color(0xFFAA5CC3), Color(0xFF5E7CE2), Color(0xFF00A4DC)),
        start = Offset(4f * s, 3f * s),
        end = Offset(20f * s, 21f * s),
    )
    val outer = Path().apply {
        moveTo(12.0f * s, 3.2f * s)
        cubicTo(13.5f * s, 3.2f * s, 21.2f * s, 16.8f * s, 20.4f * s, 18.8f * s)
        cubicTo(19.6f * s, 20.6f * s, 4.4f * s, 20.6f * s, 3.6f * s, 18.8f * s)
        cubicTo(2.8f * s, 16.8f * s, 10.5f * s, 3.2f * s, 12.0f * s, 3.2f * s)
        close()
    }
    drawPath(
        path = outer,
        brush = brush,
        style = Stroke(width = 2.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    val inner = Path().apply {
        moveTo(12.0f * s, 9.2f * s)
        cubicTo(12.6f * s, 9.2f * s, 15.8f * s, 14.8f * s, 15.4f * s, 15.8f * s)
        cubicTo(15.0f * s, 16.6f * s, 9.0f * s, 16.6f * s, 8.6f * s, 15.8f * s)
        cubicTo(8.2f * s, 14.8f * s, 11.4f * s, 9.2f * s, 12.0f * s, 9.2f * s)
        close()
    }
    drawPath(path = inner, brush = brush)
}

/** Plex：深黑圆形背景 + 金黄色折角箭头 `>`。 */
private fun DrawScope.drawPlexIcon(s: Float) {
    drawCircle(
        color = Color(0xFF1F2326),
        radius = 10.2f * s,
        center = Offset(12f * s, 12f * s),
    )
    val chevron = Path().apply {
        moveTo(8.8f * s, 6.0f * s)
        lineTo(12.5f * s, 6.0f * s)
        lineTo(16.6f * s, 12.0f * s)
        lineTo(12.5f * s, 18.0f * s)
        lineTo(8.8f * s, 18.0f * s)
        lineTo(12.9f * s, 12.0f * s)
        close()
    }
    drawPath(
        path = chevron,
        brush = Brush.verticalGradient(
            listOf(Color(0xFFF9BE03), Color(0xFFE5A00D), Color(0xFFCC7B19)),
        ),
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
    // 顶部横撇与书法体 "5" 的饱满弧线
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
    // 右下角特征切线尾翼
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

    // 连接桥（底层）
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

    // 顶部红环
    drawCircle(color = Color(0xFFFF3B4E), radius = r, center = topCenter, style = stroke)
    // 左下蓝环
    drawCircle(color = Color(0xFF2979FF), radius = r, center = leftCenter, style = stroke)
    // 右下青蓝环
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
    // 后层深蓝云峰
    drawCircle(
        color = Color(0xFF0364B8),
        radius = 4.6f * s,
        center = Offset(14.2f * s, 10.6f * s),
    )
    // 左主云朵（中蓝）
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
    // 右侧浅蓝云朵与平滑底部
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
    // 左上、右上、左下、右下四个敞口翼片
    diamond(cx = 7.2f, cy = 7.2f, rx = 4.8f, ry = 3.0f)
    diamond(cx = 16.8f, cy = 7.2f, rx = 4.8f, ry = 3.0f)
    diamond(cx = 7.2f, cy = 13.2f, rx = 4.8f, ry = 3.0f)
    diamond(cx = 16.8f, cy = 13.2f, rx = 4.8f, ry = 3.0f)
    // 底部中央托盘菱形
    diamond(cx = 12.0f, cy = 17.6f, rx = 4.8f, ry = 2.8f)
}

/** Google Drive：四色三角缎带标识。 */
private fun DrawScope.drawGoogleDriveIcon(s: Float) {
    // 左臂（绿）
    val leftArm = Path().apply {
        moveTo(9.2f * s, 3.6f * s)
        lineTo(14.8f * s, 3.6f * s)
        lineTo(7.6f * s, 16.0f * s)
        lineTo(2.0f * s, 16.0f * s)
        close()
    }
    drawPath(leftArm, color = Color(0xFF00AC47))

    // 右臂（黄）
    val rightArm = Path().apply {
        moveTo(9.2f * s, 3.6f * s)
        lineTo(14.8f * s, 3.6f * s)
        lineTo(22.0f * s, 16.0f * s)
        lineTo(16.4f * s, 16.0f * s)
        close()
    }
    drawPath(rightArm, color = Color(0xFFFFBA00))

    // 底臂（蓝）
    val bottomArm = Path().apply {
        moveTo(7.6f * s, 15.6f * s)
        lineTo(22.0f * s, 15.6f * s)
        lineTo(19.2f * s, 20.4f * s)
        lineTo(4.8f * s, 20.4f * s)
        close()
    }
    drawPath(bottomArm, color = Color(0xFF2684FC))

    // 左下红色折角
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
