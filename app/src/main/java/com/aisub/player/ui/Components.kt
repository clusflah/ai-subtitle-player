package com.aisub.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
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
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

/**
 * 可折叠的分区：标题栏和 [SectionHeader] 一样，右边多一个箭头，点标题栏收起或展开下面的内容。
 * 收起与否按 key 记在本机，下次打开照旧；默认展开。
 */
@Composable
fun CollapsibleSection(
    key: String,
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /** 标题后面显示的数量（收起时也知道里面有几项）；null 不显示 */
    count: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("app", android.content.Context.MODE_PRIVATE) }
    val prefKey = "section.collapsed.$key"
    var expanded by remember(key) { mutableStateOf(!prefs.getBoolean(prefKey, false)) }
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")
    Row(
        Modifier.fillMaxWidth()
            .tvFocusRing(RoundedCornerShape(10.dp))
            .tvSecondaryClick(allowDpadRight = true) {
                expanded = !expanded
                prefs.edit().apply { if (expanded) remove(prefKey) else putBoolean(prefKey, true) }.apply()
            }
            .clickable {
                expanded = !expanded
                prefs.edit().apply { if (expanded) remove(prefKey) else putBoolean(prefKey, true) }.apply()
            }
            .padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 6.dp).heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (count != null) Text(
            " $count", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            AppIcons.ExpandMore, contentDescription = if (expanded) "收起" else "展开",
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp).size(20.dp).rotate(arrow),
        )
        Spacer(Modifier.weight(1f))
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
    AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(content = content)
    }
}

/** 圆角液态玻璃卡片，带柔和折射边缘高光与通透基底。 */
@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = Color.Transparent,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PageGutter - 4.dp)
            .liquidGlass(
                shape = MaterialTheme.shapes.large,
                style = GlassStyle.Card,
                tint = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.78f),
            ),
    ) { Column(content = content) }
}

/**
 * 可折叠的分组卡片：标题前一条橙色短条，收起时标题下显示一行摘要（warn 时标红），点标题栏展开或收起。
 * 展开状态在旋转屏幕后保留。
 */
@Composable
fun CollapsibleCard(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")
    val sectionIcon = when {
        "识别" in title -> AppIcons.AutoAwesome
        "翻译" in title -> AppIcons.Translate
        "样式" in title || "字幕" in title -> AppIcons.Subtitles
        "朗读" in title || "配音" in title -> AppIcons.Volume
        "播放" in title -> AppIcons.PlayArrow
        "代理" in title || "网络" in title -> AppIcons.Web
        "备份" in title -> AppIcons.Cloud
        else -> AppIcons.Settings
    }
    Surface(
        color = Color.Transparent,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PageGutter - 4.dp, vertical = 6.dp)
            .liquidGlass(
                shape = MaterialTheme.shapes.large,
                style = GlassStyle.Card,
                tint = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.82f),
            ),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().tvFocusRing(MaterialTheme.shapes.large).tvSecondaryClick(allowDpadRight = true) { expanded = !expanded }.clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(sectionIcon, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (!expanded && summary.isNotEmpty()) Text(
                        summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    AppIcons.ExpandMore, contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(arrow),
                )
            }
            AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(bottom = 6.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    content()
                }
            }
        }
    }
}

/** 输入框：细而浅的边框、12dp 圆角，长地址和 Key 仍然看得清边界 */
@Composable
fun appFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
)

/** 卡片内各小节之间的分隔线 */
@Composable
fun CardDivider(inset: Dp = 0.dp) = HorizontalDivider(Modifier.padding(start = inset), color = MaterialTheme.colorScheme.outlineVariant)

/** 彩色圆角方块（Squircle）液态玻璃图标徽章，按图标语义自动配色（仿演示视频 00:02 风格）。 */
@Composable
fun IconBadge(icon: ImageVector, size: Dp = 38.dp, muted: Boolean = false) {
    val shape = RoundedCornerShape(size * 0.28f)
    if (muted) {
        Box(
            Modifier
                .size(size)
                .liquidGlass(
                    shape = shape,
                    style = GlassStyle.Pill,
                    tint = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                    enableBackdrop = false,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.54f),
            )
        }
        return
    }
    val badgeColor = when (icon) {
        AppIcons.AutoAwesome -> Color(0xFF9C27B0)
        AppIcons.Translate -> Color(0xFF2979FF)
        AppIcons.Subtitles -> Color(0xFFFF8F00)
        AppIcons.Volume -> Color(0xFFEC407A)
        AppIcons.PlayArrow -> Color(0xFFFF5252)
        AppIcons.Movie -> Color(0xFFE53935)
        AppIcons.MusicNote, AppIcons.QueueMusic -> Color(0xFFD81B60)
        AppIcons.Web -> Color(0xFF039BE5)
        AppIcons.Live -> Color(0xFFF4511E)
        AppIcons.Telegram -> Color(0xFF29B6F6)
        AppIcons.MenuBook -> Color(0xFFFF7043)
        AppIcons.Description -> Color(0xFF43A047)
        AppIcons.Download -> Color(0xFF00897B)
        AppIcons.Lock -> Color(0xFF5C6BC0)
        AppIcons.Settings -> Color(0xFF1E88E5)
        AppIcons.SystemUpdate -> Color(0xFF43A047)
        AppIcons.Info -> Color(0xFF8E24AA)
        AppIcons.Folder -> Color(0xFFFBC02D)
        AppIcons.Phone -> Color(0xFF3949AB)
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        Modifier
            .size(size)
            .liquidGlass(
                shape = shape,
                style = GlassStyle.Pill,
                tint = badgeColor.copy(alpha = 0.90f),
                enableBackdrop = false,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.54f),
        )
    }
}

/** 状态小标签，例如"已配置""未配置""连接失败"。 */
enum class StatusKind { OK, NEUTRAL, ERROR }

@Composable
fun StatusChip(text: String, kind: StatusKind) {
    val (bg, fg) = when (kind) {
        StatusKind.OK -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f) to MaterialTheme.colorScheme.onPrimaryContainer
        StatusKind.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f) to MaterialTheme.colorScheme.onSurfaceVariant
        StatusKind.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f) to MaterialTheme.colorScheme.error
    }
    Text(
        text, color = fg, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .liquidGlass(
                shape = RoundedCornerShape(50),
                style = GlassStyle.Pill,
                tint = bg,
                enableBackdrop = false,
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
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
    subtitleMaxLines: Int = 2,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.tvFocusRing(MaterialTheme.shapes.small).clickable(onClick = onClick) else Modifier)
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
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

/** 浏览页里的紧凑来源卡片（两列网格）。 */
@Composable
fun SourceCard(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, badge: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .tvFocusRing(MaterialTheme.shapes.large)
            .liquidGlass(
                shape = MaterialTheme.shapes.large,
                style = GlassStyle.Card,
                tint = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.78f),
            ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (badge != null) badge() else IconBadge(icon, size = 36.dp)
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
        modifier
            .size(24.dp)
            .liquidGlass(
                shape = CircleShape,
                style = GlassStyle.Pill,
                tint = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.88f) else Color(0x66000000),
                borderColor = Color.White.copy(alpha = 0.88f),
                enableBackdrop = false,
            ),
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
        Modifier.tvFocusRing(MaterialTheme.shapes.medium, scale = true).clip(MaterialTheme.shapes.medium)
            .tvSecondaryClick(allowDpadRight = false, onSecondaryClick = onLongClick)
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
                        .liquidGlass(
                            shape = RoundedCornerShape(6.dp),
                            style = GlassStyle.Pill,
                            tint = Color(0x78000000),
                            enableBackdrop = false,
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
            Text(
                entry.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
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
        Modifier.fillMaxWidth().tvFocusRing(MaterialTheme.shapes.small)
            .tvSecondaryClick(allowDpadRight = true, onSecondaryClick = onLongClick)
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
            Text(entry.displayTitle, style = MaterialTheme.typography.bodyLarge)
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
