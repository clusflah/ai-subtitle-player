package com.voxmusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxmusic.app.AppBackground
import com.voxmusic.app.AppDarkMode
import com.voxmusic.app.BuildConfig
import com.voxmusic.app.MusicDiscoverSource
import com.voxmusic.app.MusicViewModel
import com.voxmusic.app.Provider
import com.voxmusic.app.media.LIKED_PLAYLIST
import com.voxmusic.app.media.MediaEntry
import com.voxmusic.app.media.TrackMetadataRegistry
import com.voxmusic.app.media.formatDuration
import com.voxmusic.app.music.MusicSourceStore
import com.voxmusic.app.music.WebMusicLoginDialog
import com.voxmusic.app.music.WebMusicService
import com.voxmusic.app.music.WebStreamingMusic
import com.voxmusic.app.net.AppProxy
import com.voxmusic.app.net.ProxyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 底部导航「我的」页面：
 * - Apple Music & Spotify 账号登录状态、切换与高音质联动配置；
 * - 我喜欢的音乐、自建歌单、最近播放；
 * - 快捷进入完整设置（代理 / AI 与歌词翻译 / 榜单数据源 / 外观主题）。
 */
@Composable
fun MusicMeTab(
    vm: MusicViewModel,
    bottomPadding: Dp = 88.dp,
    onPlayQueue: (List<MediaEntry>, Int) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by vm.state.collectAsStateWithLifecycle()
    val loggedIn by WebStreamingMusic.loggedInServices.collectAsStateWithLifecycle()
    val activeService by remember(context) { MusicSourceStore.ensureInit(context) }.collectAsStateWithLifecycle()
    LaunchedEffect(context) { WebStreamingMusic.refreshAuthState(context) }

    var loginTarget by remember { mutableStateOf<WebMusicService?>(null) }
    var creatingPlaylist by remember { mutableStateOf(false) }

    val likedPlaylist = remember(uiState.playlists) { uiState.playlists.firstOrNull { it.name == LIKED_PLAYLIST } }
    val customPlaylists = remember(uiState.playlists) { uiState.playlists.filter { it.name != LIKED_PLAYLIST } }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "me_header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("我的", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    val mins = uiState.stats.musicMs / 60_000L
                    Text(
                        text = if (mins > 0L) "累计收听 $mins 分钟 · 播放 ${uiState.stats.starts} 次" else "VoxMusic · Apple Music & Spotify",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(AppIcons.Settings, "设置")
                }
            }
        }

        // 音乐源账号管理卡片（仅 Apple Music & Spotify）
        item(key = "me_accounts") {
            SurfaceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("流媒体音乐源", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WebMusicService.entries.forEachIndexed { idx, svc ->
                        if (idx > 0) CardDivider()
                        val isLogged = svc in loggedIn
                        val isActive = svc == activeService
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (isLogged) MusicSourceStore.select(context, svc)
                                    else loginTarget = svc
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            WebMusicServiceIcon(svc, size = 34.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(svc.label, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                                    if (isLogged && isActive) {
                                        Text(
                                            "默认",
                                            fontSize = 10.sp,
                                            color = Orange,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Orange.copy(alpha = 0.14f))
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                                Text(
                                    WebStreamingMusic.serviceStatusSubtitle(context, svc, isLogged),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (isLogged) {
                                TextButton(onClick = { loginTarget = svc }) {
                                    Text(if (svc == WebMusicService.SPOTIFY) "管理" else "重登", fontSize = 12.sp)
                                }
                                TextButton(onClick = { WebStreamingMusic.logout(context, svc) }) {
                                    Text("退出", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                Button(
                                    onClick = { loginTarget = svc },
                                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                                    shape = RoundedCornerShape(50),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Text("登录", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 我的歌单与历史
        item(key = "me_library") {
            SurfaceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    MeListRow(
                        icon = AppIcons.Favorite,
                        iconTint = Color(0xFFFF5A6E),
                        title = LIKED_PLAYLIST,
                        subtitle = "${likedPlaylist?.items?.size ?: 0} 首红心歌曲",
                        onClick = {
                            if (likedPlaylist != null) onOpenPlaylist(likedPlaylist.id)
                            else {
                                vm.createPlaylist(LIKED_PLAYLIST)
                            }
                        },
                    )
                    CardDivider(inset = 54.dp)
                    MeListRow(
                        icon = AppIcons.History,
                        iconTint = Orange,
                        title = "最近播放",
                        subtitle = "${uiState.history.size} 首播放记录",
                        onClick = onOpenHistory,
                    )
                    if (customPlaylists.isNotEmpty()) {
                        CardDivider(inset = 54.dp)
                        customPlaylists.forEachIndexed { idx, pl ->
                            if (idx > 0) CardDivider(inset = 54.dp)
                            MeListRow(
                                icon = AppIcons.QueueMusic,
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                title = pl.name,
                                subtitle = "${pl.items.size} 首歌曲",
                                onClick = { onOpenPlaylist(pl.id) },
                            )
                        }
                    }
                    CardDivider(inset = 54.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { creatingPlaylist = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Add, null, tint = Orange, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("新建歌单", color = Orange, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // 设置入口
        item(key = "me_settings") {
            SurfaceCard(Modifier.fillMaxWidth()) {
                MeListRow(
                    icon = AppIcons.Settings,
                    iconTint = Orange,
                    title = "应用设置",
                    subtitle = "网络代理 · AI 歌词翻译与荐歌模型 · 榜单源 · 外观主题",
                    onClick = onOpenSettings,
                )
            }
        }
    }

    loginTarget?.let { svc ->
        WebMusicLoginDialog(
            service = svc,
            onDismiss = { loginTarget = null },
            onLoggedIn = {
                loginTarget = null
                MusicSourceStore.select(context, svc)
            },
        )
    }

    if (creatingPlaylist) {
        TextInputDialog(
            title = "新建歌单",
            label = "歌单名称",
            confirm = "创建",
            onDismiss = { creatingPlaylist = false },
            onConfirm = { name ->
                creatingPlaylist = false
                if (name.isNotBlank()) vm.createPlaylist(name)
            },
        )
    }
}

@Composable
private fun MeListRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(iconTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(AppIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** 歌单详情页 */
@Composable
fun MusicPlaylistDetailScreen(
    playlistId: String,
    vm: MusicViewModel,
    onBack: () -> Unit,
    onPlayQueue: (List<MediaEntry>, Int) -> Unit,
) {
    val uiState by vm.state.collectAsStateWithLifecycle()
    val playlist = uiState.playlists.firstOrNull { it.id == playlistId }
    DetailScaffold(
        title = playlist?.name ?: "歌单",
        onBack = onBack,
        actions = {
            if (playlist != null && playlist.items.isNotEmpty()) {
                TextButton(onClick = { onPlayQueue(playlist.items, 0) }) {
                    Icon(AppIcons.PlayArrow, null, tint = Orange, modifier = Modifier.size(18.dp))
                    Text("全部播放", color = Orange, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
            if (playlist != null && playlist.name != LIKED_PLAYLIST) {
                IconButton(onClick = { vm.deletePlaylist(playlist.id); onBack() }) {
                    Icon(AppIcons.Delete, "删除歌单")
                }
            }
        },
    ) {
        if (playlist == null || playlist.items.isEmpty()) {
            EmptyState("歌单里还没有歌曲")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 92.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(playlist.items, key = { i, e -> "$i:${e.uri}" }) { idx, entry ->
                    TrackEntryCardRow(
                        entry = entry,
                        onClick = { onPlayQueue(playlist.items, idx) },
                        onDelete = { vm.removeFromPlaylist(playlist.id, entry.uri) },
                    )
                }
            }
        }
    }
}

/** 最近播放历史页 */
@Composable
fun MusicHistoryScreen(
    vm: MusicViewModel,
    onBack: () -> Unit,
    onPlayQueue: (List<MediaEntry>, Int) -> Unit,
) {
    val uiState by vm.state.collectAsStateWithLifecycle()
    val entries = remember(uiState.history) { uiState.history.map { it.entry } }
    DetailScaffold(
        title = "最近播放",
        onBack = onBack,
        actions = {
            if (entries.isNotEmpty()) {
                TextButton(onClick = { onPlayQueue(entries, 0) }) {
                    Text("全部播放", color = Orange, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                IconButton(onClick = { vm.clearHistory() }) {
                    Icon(AppIcons.Delete, "清空记录")
                }
            }
        },
    ) {
        if (entries.isEmpty()) {
            EmptyState("暂无播放记录")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 92.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(entries, key = { i, e -> "$i:${e.uri}" }) { idx, entry ->
                    TrackEntryCardRow(
                        entry = entry,
                        onClick = { onPlayQueue(entries, idx) },
                        onDelete = { vm.removeHistory(entry.uri) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackEntryCardRow(
    entry: MediaEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val meta = TrackMetadataRegistry.find(entry.uri)
    val svc = WebMusicService.fromUri(entry.uri)
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                NetImage(url = meta?.coverUrl ?: entry.coverUrl, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, fallback = AppIcons.MusicNote)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meta?.title ?: entry.displayTitle, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    svc?.let { WebMusicServiceIcon(it, size = 13.dp) }
                    Text(
                        listOfNotNull(meta?.artist, meta?.album).joinToString(" - ").ifBlank { entry.folder },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (entry.durationMs > 0L) {
                Text(formatDuration(entry.durationMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(AppIcons.Close, "移除", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 完整设置页（整合原「我的」账号/歌单/历史 + 播放/榜单源/网络代理/AI 大模型/外观主题）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicSettingsScreen(
    vm: MusicViewModel,
    onBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit = {},
    onOpenHistory: () -> Unit = {},
) {
    val context = LocalContext.current
    val uiState by vm.state.collectAsStateWithLifecycle()
    val settings = uiState.settings
    val scope = rememberCoroutineScope()
    val loggedIn by WebStreamingMusic.loggedInServices.collectAsStateWithLifecycle()
    val activeService by remember(context) { MusicSourceStore.ensureInit(context) }.collectAsStateWithLifecycle()
    LaunchedEffect(context) { WebStreamingMusic.refreshAuthState(context) }

    var loginTarget by remember { mutableStateOf<WebMusicService?>(null) }
    var creatingPlaylist by remember { mutableStateOf(false) }
    val likedPlaylist = remember(uiState.playlists) { uiState.playlists.firstOrNull { it.name == LIKED_PLAYLIST } }
    val customPlaylists = remember(uiState.playlists) { uiState.playlists.filter { it.name != LIKED_PLAYLIST } }

    var proxyHost by remember(settings.proxyHost) { mutableStateOf(settings.proxyHost) }
    var proxyPort by remember(settings.proxyPort) { mutableStateOf(settings.proxyPort.toString()) }
    var proxyTesting by remember { mutableStateOf(false) }
    var proxyTestResult by remember { mutableStateOf<String?>(null) }

    var editingProvider by remember { mutableStateOf(settings.provider) }
    val providerCfg = settings.configOf(editingProvider)
    var apiKey by remember(editingProvider, providerCfg.apiKey) { mutableStateOf(providerCfg.apiKey) }
    var modelName by remember(editingProvider, providerCfg.model) { mutableStateOf(providerCfg.model) }
    var baseUrl by remember(editingProvider, providerCfg.baseUrl) { mutableStateOf(providerCfg.baseUrl) }
    var aiTestResult by remember { mutableStateOf<String?>(null) }
    var aiTesting by remember { mutableStateOf(false) }

    DetailScaffold(title = "设置", onBack = onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .padding(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 0. 我的账号与歌单（收起在设置中）
            val mins = uiState.stats.musicMs / 60_000L
            if (mins > 0L || uiState.stats.starts > 0) {
                Text(
                    text = "累计收听 $mins 分钟 · 播放 ${uiState.stats.starts} 次",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            SurfaceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("流媒体音乐源", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Orange)
                    WebMusicService.entries.forEachIndexed { idx, svc ->
                        if (idx > 0) CardDivider()
                        val isLogged = svc in loggedIn
                        val isActive = svc == activeService
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (isLogged) MusicSourceStore.select(context, svc)
                                    else loginTarget = svc
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            WebMusicServiceIcon(svc, size = 34.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(svc.label, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
                                    if (isLogged && isActive) {
                                        Text(
                                            "默认",
                                            fontSize = 10.sp,
                                            color = Orange,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Orange.copy(alpha = 0.14f))
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                                Text(
                                    WebStreamingMusic.serviceStatusSubtitle(context, svc, isLogged),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (isLogged) {
                                TextButton(onClick = { loginTarget = svc }) {
                                    Text(if (svc == WebMusicService.SPOTIFY) "管理" else "重登", fontSize = 12.sp)
                                }
                                TextButton(onClick = { WebStreamingMusic.logout(context, svc) }) {
                                    Text("退出", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                Button(
                                    onClick = { loginTarget = svc },
                                    colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                                    shape = RoundedCornerShape(50),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Text("登录", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            SurfaceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        "我的歌单与历史",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Orange,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
                    )
                    MeListRow(
                        icon = AppIcons.Favorite,
                        iconTint = Color(0xFFFF5A6E),
                        title = LIKED_PLAYLIST,
                        subtitle = "${likedPlaylist?.items?.size ?: 0} 首红心歌曲",
                        onClick = {
                            if (likedPlaylist != null) onOpenPlaylist(likedPlaylist.id)
                            else vm.createPlaylist(LIKED_PLAYLIST)
                        },
                    )
                    CardDivider(inset = 54.dp)
                    MeListRow(
                        icon = AppIcons.History,
                        iconTint = Orange,
                        title = "最近播放",
                        subtitle = "${uiState.history.size} 首播放记录",
                        onClick = onOpenHistory,
                    )
                    if (customPlaylists.isNotEmpty()) {
                        CardDivider(inset = 54.dp)
                        customPlaylists.forEachIndexed { idx, pl ->
                            if (idx > 0) CardDivider(inset = 54.dp)
                            MeListRow(
                                icon = AppIcons.QueueMusic,
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                title = pl.name,
                                subtitle = "${pl.items.size} 首歌曲",
                                onClick = { onOpenPlaylist(pl.id) },
                            )
                        }
                    }
                    CardDivider(inset = 54.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { creatingPlaylist = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Add, null, tint = Orange, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("新建歌单", color = Orange, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // 1. 播放与歌词
            SurfaceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("播放与歌词", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Orange)
                    SettingsSwitchRow(
                        title = "后台继续播放",
                        subtitle = "锁屏或切换应用后继续播放音乐",
                        checked = settings.musicBackground,
                        onCheckedChange = { vm.updateSettings(settings.copy(musicBackground = it)) },
                    )
                    SettingsSwitchRow(
                        title = "自动在线匹配歌词与封面",
                        subtitle = "播放时自动从 LRCLIB / 网易云 / iTunes 匹配同步歌词与封面",
                        checked = settings.musicOnlineLookup,
                        onCheckedChange = { vm.updateSettings(settings.copy(musicOnlineLookup = it)) },
                    )
                    SettingsSwitchRow(
                        title = "中文译文标注拼音",
                        subtitle = "翻译目标为中文时标注拼音",
                        checked = settings.showPinyin,
                        onCheckedChange = { vm.updateSettings(settings.copy(showPinyin = it)) },
                    )
                }
            }

            // 2. 发现页榜单数据源
            SurfaceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("发现页榜单数据源", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Orange)
                    Text("仅决定发现页展示的榜单和搜索目录；点击播放时始终智能选用已登录的 Apple Music / Spotify / YouTube Music 源。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MusicDiscoverSource.entries.forEach { src ->
                            FilterChip(
                                selected = settings.musicDiscoverSource == src,
                                onClick = { vm.updateSettings(settings.copy(musicDiscoverSource = src)) },
                                label = { Text(src.shortLabel, fontSize = 12.5.sp) },
                            )
                        }
                    }
                }
            }

            // 3. 网络代理（方便国内访问 Apple Music / Spotify）
            SurfaceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("网络代理", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Orange)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProxyType.entries.forEach { t ->
                            FilterChip(
                                selected = settings.proxyType == t,
                                onClick = { vm.updateSettings(settings.copy(proxyType = t)) },
                                label = { Text(t.label, fontSize = 12.5.sp) },
                            )
                        }
                    }
                    if (settings.proxyType != ProxyType.OFF) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = proxyHost,
                                onValueChange = {
                                    proxyHost = it.trim()
                                    vm.updateSettings(settings.copy(proxyHost = proxyHost))
                                },
                                label = { Text("代理主机 IP") },
                                singleLine = true,
                                modifier = Modifier.weight(1.8f),
                            )
                            OutlinedTextField(
                                value = proxyPort,
                                onValueChange = { raw ->
                                    proxyPort = raw.filter(Char::isDigit).take(5)
                                    proxyPort.toIntOrNull()?.takeIf { it in 1..65535 }?.let { p ->
                                        vm.updateSettings(settings.copy(proxyPort = p))
                                    }
                                },
                                label = { Text("端口") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        SettingsSwitchRow(
                            title = "国内地址直连绕过",
                            subtitle = "大陆网站与局域网地址不走代理",
                            checked = settings.proxyBypassCn,
                            onCheckedChange = { vm.updateSettings(settings.copy(proxyBypassCn = it)) },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                enabled = !proxyTesting,
                                onClick = {
                                    proxyTesting = true
                                    proxyTestResult = null
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) {
                                            AppProxy.test(settings.proxyType, proxyHost, proxyPort.toIntOrNull() ?: settings.proxyPort)
                                        }
                                        proxyTestResult = res
                                        proxyTesting = false
                                    }
                                },
                            ) {
                                if (proxyTesting) {
                                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text("测试连接", fontSize = 13.sp)
                            }
                        }
                        proxyTestResult?.let {
                            Text(it, fontSize = 12.sp, color = if (it.startsWith("连接成功")) Color(0xFF1ED760) else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // 4. AI 歌词翻译与 AI 荐歌大模型配置
            SurfaceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("AI 歌词翻译与智能荐歌", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Orange)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Provider.entries.forEach { p ->
                            FilterChip(
                                selected = editingProvider == p,
                                onClick = {
                                    editingProvider = p
                                    vm.updateSettings(settings.copy(provider = p))
                                    aiTestResult = null
                                },
                                label = { Text(p.label, fontSize = 12.sp) },
                            )
                        }
                    }
                    if (editingProvider.editableBaseUrl) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Base URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (editingProvider.needsApiKey || editingProvider == Provider.OPENAI_COMPAT) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("API Key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (editingProvider != Provider.INDEX_TRANSLATE) {
                        OutlinedTextField(
                            value = modelName,
                            onValueChange = { modelName = it },
                            label = { Text(if (editingProvider == Provider.AZURE_TRANSLATOR) "区域（如 eastasia）" else "模型名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val updated = settings.copy(provider = editingProvider).withConfig(
                                    editingProvider,
                                    com.voxmusic.app.ProviderConfig(apiKey = apiKey.trim(), model = modelName.trim(), baseUrl = baseUrl.trim()),
                                )
                                vm.updateSettings(updated)
                                aiTestResult = "已保存 ${editingProvider.label} 配置"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                        ) {
                            Text("保存配置", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            enabled = !aiTesting,
                            onClick = {
                                val updated = settings.copy(provider = editingProvider).withConfig(
                                    editingProvider,
                                    com.voxmusic.app.ProviderConfig(apiKey = apiKey.trim(), model = modelName.trim(), baseUrl = baseUrl.trim()),
                                )
                                vm.updateSettings(updated)
                                aiTesting = true
                                aiTestResult = null
                                scope.launch {
                                    val tr = updated.createSingleTranslator(editingProvider)
                                    aiTestResult = if (tr == null) {
                                        updated.translatorProblemFor(editingProvider) ?: "配置不完整"
                                    } else {
                                        runCatching {
                                            withContext(Dispatchers.IO) { tr.translate(listOf("Hello world"), emptyList(), updated.targetLanguage) }
                                        }.fold(
                                            onSuccess = { "连接成功：${it.firstOrNull().orEmpty()}" },
                                            onFailure = { "测试失败：${it.message}" },
                                        )
                                    }
                                    aiTesting = false
                                }
                            },
                        ) {
                            Text(if (aiTesting) "测试中…" else "测试模型", fontSize = 13.sp)
                        }
                    }
                    aiTestResult?.let {
                        Text(it, fontSize = 12.sp, color = if (it.startsWith("连接成功") || it.startsWith("已保存")) Color(0xFF1ED760) else MaterialTheme.colorScheme.error)
                    }
                }
            }

            // 5. 外观与主题
            SurfaceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("外观主题", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Orange)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppDarkMode.entries.forEach { m ->
                            FilterChip(
                                selected = settings.appDarkMode == m,
                                onClick = { vm.updateSettings(settings.copy(appDarkMode = m)) },
                                label = { Text(m.label, fontSize = 12.5.sp) },
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppBackground.entries.forEach { bg ->
                            FilterChip(
                                selected = settings.appBackground == bg,
                                onClick = { vm.updateSettings(settings.copy(appBackground = bg)) },
                                label = { Text(bg.label, fontSize = 11.5.sp) },
                            )
                        }
                    }
                    SettingsSwitchRow(
                        title = "液态玻璃效果",
                        subtitle = "卡片与悬浮控件使用通透玻璃材质",
                        checked = settings.liquidGlass,
                        onCheckedChange = { vm.updateSettings(settings.copy(liquidGlass = it)) },
                    )
                }
            }

            // 6. 关于
            SurfaceCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("VoxMusic v${BuildConfig.VERSION_NAME}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("专注 Apple Music、Spotify 与 YouTube Music 的纯净音乐播放器，支持榜单智能选源即点即播、黑胶唱机、双语同步歌词、AI 荐歌与桌面小组件。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    loginTarget?.let { svc ->
        WebMusicLoginDialog(
            service = svc,
            onDismiss = { loginTarget = null },
            onLoggedIn = {
                loginTarget = null
                MusicSourceStore.select(context, svc)
            },
        )
    }

    if (creatingPlaylist) {
        TextInputDialog(
            title = "新建歌单",
            label = "歌单名称",
            confirm = "创建",
            onDismiss = { creatingPlaylist = false },
            onConfirm = { name ->
                creatingPlaylist = false
                if (name.isNotBlank()) vm.createPlaylist(name)
            },
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onCheckedChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
