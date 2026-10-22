package com.aisub.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.formatDuration
import com.aisub.player.media.formatSize

data class MenuAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/** 右侧"⋮"菜单按钮。compact 用于卡片内，触控区域更小、不抢文件名的视觉注意力。 */
@Composable
fun OverflowMenu(actions: List<MenuAction>, modifier: Modifier = Modifier, compact: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = if (compact) Modifier.size(32.dp) else Modifier) {
            Icon(
                AppIcons.MoreVert, contentDescription = "更多",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (compact) Modifier.size(18.dp) else Modifier,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(text = { Text(a.label) }, enabled = a.enabled, onClick = { open = false; a.onClick() })
            }
        }
    }
}

/** 分区标题：深色中号字，不用大号橙色；右侧可放一个文字操作。 */
@Composable
fun SectionHeader(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 6.dp).heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

/** 白色圆角卡片，用背景层次与浅灰页面区分，不加边框和阴影。 */
@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) { Column(content = content) }
}

/** 浅橙圆底图标，用于来源、设置项等。 */
@Composable
fun IconBadge(icon: ImageVector, size: Dp = 40.dp, muted: Boolean = false) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (muted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, null,
            tint = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** 状态小标签，例如"已配置""未配置""连接失败"。 */
enum class StatusKind { OK, NEUTRAL, ERROR }

@Composable
fun StatusChip(text: String, kind: StatusKind) {
    val (bg, fg) = when (kind) {
        StatusKind.OK -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        StatusKind.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        StatusKind.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f) to MaterialTheme.colorScheme.error
    }
    Text(
        text, color = fg, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 列表行：图标 + 标题 + 说明 + 尾部内容（状态、菜单）。设置页、来源列表通用。 */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    mutedIcon: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleMaxLines: Int = 1,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> { leading(); Spacer(Modifier.width(12.dp)) }
            icon != null -> { IconBadge(icon, muted = mutedIcon); Spacer(Modifier.width(14.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

/** 浏览页里的紧凑来源卡片（两列网格）。 */
@Composable
fun SourceCard(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
        modifier = modifier,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, size = 36.dp)
            Column(Modifier.padding(start = 10.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

fun countText(folders: Int, files: Int): String =
    if (folders > 0) "$folders 文件夹 · $files 文件" else "$files 个文件"

/** 多选时的勾选标记。 */
@Composable
private fun SelectionBadge(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.size(24.dp).clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color(0x66000000))
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(AppIcons.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/**
 * 视频网格里的一项。
 * @param selected null 表示不在多选模式；非 null 时显示勾选框
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(
    entry: MediaEntry,
    menu: List<MenuAction>,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier.clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected == true) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .padding(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)) {
            MediaThumbnail(entry, Modifier.fillMaxSize())
            if (selected != null) SelectionBadge(selected, Modifier.align(Alignment.TopStart).padding(6.dp))
            val d = formatDuration(entry.durationMs)
            if (d.isNotEmpty()) {
                Text(
                    d, color = Color.White, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
            Text(
                entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 2.dp, top = 4.dp),
            )
            if (menu.isNotEmpty() && selected == null) OverflowMenu(menu, compact = true)
        }
    }
}

/** 列表里的一行媒体（音频、文件夹内容、播放列表内容）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRow(
    entry: MediaEntry,
    menu: List<MenuAction>,
    subtitle: String? = null,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected == true) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            SelectionBadge(selected)
            Spacer(Modifier.width(12.dp))
        }
        if (entry.isVideo) {
            MediaThumbnail(entry, Modifier.size(width = 80.dp, height = 45.dp).clip(MaterialTheme.shapes.small))
        } else {
            IconBadge(AppIcons.MusicNote, size = 40.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge)
            val info = subtitle ?: listOf(formatDuration(entry.durationMs), if (entry.sizeBytes > 0) formatSize(entry.sizeBytes) else "")
                .filter { it.isNotEmpty() }.joinToString(" · ")
            if (info.isNotEmpty()) Text(info, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (menu.isNotEmpty() && selected == null) OverflowMenu(menu) else Spacer(Modifier.width(12.dp))
    }
}

/** 列表里的一行文件夹。 */
@Composable
fun FolderRow(name: String, subtitle: String? = null, menu: List<MenuAction> = emptyList(), onClick: () -> Unit) {
    ListRow(
        title = name, subtitle = subtitle, icon = AppIcons.Folder, mutedIcon = true, titleMaxLines = Int.MAX_VALUE,
        trailing = if (menu.isEmpty()) null else ({ OverflowMenu(menu) }),
        onClick = onClick,
    )
}

/** 统一的空状态：简洁图标 + 明确说明 + 一个主要操作（可选一个次要操作）。 */
@Composable
fun EmptyState(
    message: String,
    buttonText: String? = null,
    icon: ImageVector = AppIcons.Movie,
    secondaryText: String? = null,
    onSecondary: () -> Unit = {},
    onButton: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon, size = 72.dp, muted = true)
        Spacer(Modifier.height(20.dp))
        Text(
            message, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (buttonText != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onButton, modifier = Modifier.heightIn(min = 48.dp)) { Text(buttonText) }
        }
        if (secondaryText != null) {
            TextButton(onClick = onSecondary) { Text(secondaryText) }
        }
    }
}
