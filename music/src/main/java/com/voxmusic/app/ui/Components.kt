package com.voxmusic.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxmusic.app.MusicViewModel
import com.voxmusic.app.media.Playlist
import com.voxmusic.app.media.formatDuration
import com.voxmusic.app.music.WebMusicService

@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val glass = LocalLiquidGlassEnabled.current
    val palette = currentVisualPalette()
    val shape = RoundedCornerShape(16.dp)
    if (glass) {
        Column(
            modifier
                .glassShadow(shape, GlassStyle.Card)
                .liquidGlass(shape = shape, style = GlassStyle.Card, tint = palette.cardTint),
            content = content,
        )
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun CardDivider(inset: Dp = 0.dp) =
    HorizontalDivider(Modifier.padding(start = inset), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

@Composable
fun appFieldColors() = if (LocalLiquidGlassEnabled.current) {
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedBorderColor = Orange,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
    )
} else {
    OutlinedTextFieldDefaults.colors(focusedBorderColor = Orange)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val glass = LocalLiquidGlassEnabled.current
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, "返回")
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                ),
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            content()
        }
    }
}

@Composable
fun EmptyState(
    message: String,
    icon: ImageVector = AppIcons.MusicNote,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(44.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun DiscoverQuickJumpButton(
    icon: ImageVector,
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val playerAccent = LocalPlayerAccentColor.current
    val dark = isAppDarkTheme()
    val borderBrush = if (accent) {
        Brush.linearGradient(
            colors = listOf(
                playerAccent.copy(alpha = if (dark) 0.65f else 0.75f),
                Color.White.copy(alpha = if (dark) 0.24f else 0.45f),
                playerAccent.copy(alpha = if (dark) 0.32f else 0.45f),
            ),
        )
    } else {
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.06f)))
    }
    Row(
        Modifier
            .clip(shape)
            .background(if (dark) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f))
            .border(0.75.dp, borderBrush, shape)
            .tvFocusRing(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            icon,
            null,
            tint = if (accent) playerAccent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (dark) Color.White.copy(alpha = 0.90f) else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
fun ChoiceDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { i, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(i) }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = i == selected, onClick = { onPick(i) })
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirm: String = "确定",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = false,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPick: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextInputDialog(
            title = "新建歌单",
            label = "歌单名称",
            confirm = "创建并加入",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                if (name.isNotBlank()) onCreate(name)
            },
        )
        return
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入歌单") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { creating = true }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Add, null, tint = Orange, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("新建歌单", color = Orange, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                if (playlists.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                playlists.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(p) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(p.name, modifier = Modifier.weight(1f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.items.size} 首", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun TrackInfoDialog(vm: MusicViewModel, onDismiss: () -> Unit) {
    val cur = vm.state.value.current
    val tags = vm.music.state.value.tags
    val svc = WebMusicService.fromUri(cur?.uri)
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("歌曲信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("歌名：${tags?.title ?: cur?.displayTitle.orEmpty()}", fontSize = 14.sp)
                tags?.artist?.takeIf { it.isNotBlank() }?.let { Text("歌手：$it", fontSize = 14.sp) }
                tags?.album?.takeIf { it.isNotBlank() }?.let { Text("专辑：$it", fontSize = 14.sp) }
                val dur = vm.player.duration.takeIf { it > 0L } ?: tags?.durationMs ?: 0L
                if (dur > 0L) Text("时长：${formatDuration(dur)}", fontSize = 14.sp)
                svc?.let { Text("播放源：${it.label}", fontSize = 14.sp) }
                vm.music.state.value.lyrics?.source?.takeIf { it.isNotBlank() }?.let {
                    Text("歌词来源：$it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("确定") } },
    )
}

/** 底部三标签悬浮导航栏（音乐 / 发现 / 我的），自动适配深色黑胶画布与液态玻璃主题。 */
data class DockTabItem(val label: String, val icon: ImageVector)

@Composable
fun VoxMusicBottomDock(
    items: List<DockTabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDarkCanvas: Boolean,
    modifier: Modifier = Modifier,
) {
    val glass = LocalLiquidGlassEnabled.current
    val dark = isAppDarkTheme() || onDarkCanvas
    val shape = RoundedCornerShape(32.dp)
    val bgTint = when {
        onDarkCanvas -> Color(0xCC181A20)
        glass -> currentVisualPalette().dockTint
        dark -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)
        else -> MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.96f)
    }
    val borderColor = if (dark) Color(0x28FFFFFF) else Color(0x18000000)

    Row(
        modifier = modifier
            .height(60.dp)
            .let {
                if (glass) {
                    it.glassShadow(shape, GlassStyle.Floating)
                        .liquidGlass(shape = shape, style = GlassStyle.Floating, tint = bgTint)
                } else {
                    it.clip(shape)
                        .background(bgTint)
                        .border(0.8.dp, borderColor, shape)
                }
            }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { idx, item ->
            val selected = idx == selectedIndex
            val itemShape = RoundedCornerShape(24.dp)
            val activeBg = if (selected) Orange.copy(alpha = if (dark) 0.22f else 0.14f) else Color.Transparent
            val contentColor = when {
                selected -> Orange
                onDarkCanvas -> Color(0xB3FFFFFF)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(itemShape)
                    .background(activeBg)
                    .clickable { onSelect(idx) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(item.icon, item.label, tint = contentColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = item.label,
                    color = contentColor,
                    fontSize = 13.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 未登录任何流媒体源时的极简快捷登录弹窗（Apple Music / Spotify / YouTube Music）。 */
@Composable
fun QuickLoginPromptDialog(
    songTitle: String?,
    onDismiss: () -> Unit,
    onChooseService: (WebMusicService) -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("登录音乐源即可播放") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (!songTitle.isNullOrBlank()) {
                        "登录 Apple Music、Spotify 或 YouTube Music 后，将立即为你播放「$songTitle」，之后点击榜单也会直接智能选择已登录源开播。"
                    } else {
                        "登录 Apple Music、Spotify 或 YouTube Music 账号后即可直接播放榜单与个人乐库。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WebMusicService.entries.forEach { svc ->
                    Button(
                        onClick = { onChooseService(svc) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(svc.accentHex),
                            contentColor = if (svc == WebMusicService.SPOTIFY) Color.Black else Color.White,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        WebMusicServiceIcon(svc, size = 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("登录 ${svc.label}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("暂不登录") } },
    )
}
