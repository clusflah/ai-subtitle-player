package com.aisub.player.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.formatDuration

data class MenuAction(val label: String, val onClick: () -> Unit)

/** 右侧"⋮"菜单按钮。 */
@Composable
fun OverflowMenu(actions: List<MenuAction>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(AppIcons.MoreVert, contentDescription = "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(text = { Text(a.label) }, onClick = { open = false; a.onClick() })
            }
        }
    }
}

/** 分区标题，右侧可选"→"。 */
@Composable
fun SectionHeader(title: String, onMore: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (onMore != null) {
            IconButton(onClick = onMore) { Icon(AppIcons.ArrowForward, contentDescription = "查看全部") }
        }
    }
}

/** VLC"浏览"页那种方形卡片：大图标 + 名称 + 统计。 */
@Composable
fun TileCard(
    icon: ImageVector,
    title: String,
    subtitle: @Composable (() -> Unit)? = null,
    menu: List<MenuAction> = emptyList(),
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.width(150.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp)) {
            Icon(
                icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    subtitle?.invoke()
                }
                if (menu.isNotEmpty()) OverflowMenu(menu) else Spacer(Modifier.width(12.dp))
            }
        }
    }
}

/** "📁 15 · 📄 107" 这样的统计行。 */
@Composable
fun CountLine(folders: Int, files: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val color = MaterialTheme.colorScheme.outline
        if (folders > 0) {
            Text("$folders", color = color, style = MaterialTheme.typography.bodySmall)
            Icon(AppIcons.Folder, null, tint = color, modifier = Modifier.size(14.dp).padding(start = 2.dp))
            Text(" · ", color = color, style = MaterialTheme.typography.bodySmall)
        }
        Text("$files", color = color, style = MaterialTheme.typography.bodySmall)
        Icon(AppIcons.File, null, tint = color, modifier = Modifier.size(14.dp).padding(start = 2.dp))
    }
}

/** 视频网格里的一项。 */
@Composable
fun VideoCard(entry: MediaEntry, menu: List<MenuAction>, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp))) {
            MediaThumbnail(entry, Modifier.fillMaxSize())
            val d = formatDuration(entry.durationMs)
            if (d.isNotEmpty()) {
                Text(
                    d, color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 3.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Text(
                entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(top = 6.dp),
            )
            if (menu.isNotEmpty()) OverflowMenu(menu)
        }
    }
}

/** 列表里的一行媒体（音频、文件夹内容、播放列表内容）。 */
@Composable
fun MediaRow(entry: MediaEntry, menu: List<MenuAction>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaThumbnail(
            entry,
            Modifier.size(width = if (entry.isVideo) 80.dp else 48.dp, height = 48.dp).clip(RoundedCornerShape(4.dp)),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            val d = formatDuration(entry.durationMs)
            if (d.isNotEmpty()) Text(d, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
        }
        if (menu.isNotEmpty()) OverflowMenu(menu) else Spacer(Modifier.width(16.dp))
    }
}

/** 列表里的一行文件夹。 */
@Composable
fun FolderRow(name: String, subtitle: @Composable (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Column(Modifier.padding(start = 16.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.invoke()
        }
    }
}

/** VLC 那种居中的空状态：灰色图标 + 提示 + 橙色描边按钮。 */
@Composable
fun EmptyState(message: String, buttonText: String? = null, icon: ImageVector = AppIcons.Movie, onButton: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            message, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (buttonText != null) {
            Spacer(Modifier.height(32.dp))
            OutlinedButton(onClick = onButton, shape = RoundedCornerShape(6.dp)) {
                Text(buttonText, color = MaterialTheme.colorScheme.primary, fontSize = 17.sp)
            }
        }
    }
}
