package com.aisub.player.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisub.player.LibraryState
import com.aisub.player.LibraryViewModel
import com.aisub.player.MainViewModel
import com.aisub.player.media.HistoryItem
import com.aisub.player.media.LocalBrowser
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.MediaLibrary
import com.aisub.player.media.Playlist
import com.aisub.player.media.SortOrder
import com.aisub.player.media.formatDuration
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType

private enum class Tab(val label: String, val icon: ImageVector) {
    VIDEO("视频", AppIcons.Movie),
    AUDIO("音频", AppIcons.MusicNote),
    BROWSE("浏览", AppIcons.Folder),
    PLAYLISTS("播放列表", AppIcons.QueueMusic),
    MORE("更多", AppIcons.MoreHoriz),
}

/** 首页里需要弹出的对话框。 */
private sealed interface HomeDialog {
    data object About : HomeDialog
    data object Stream : HomeDialog
    data object NewPlaylist : HomeDialog
    data object AddSource : HomeDialog
    data class AddToPlaylist(val entries: List<MediaEntry>) : HomeDialog
    data class EditServer(val server: Server, val hint: String? = null) : HomeDialog
    data class CloudLogin(val type: ServerType, val existing: Server?) : HomeDialog
    data class RenameServer(val server: Server) : HomeDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    lib: LibraryViewModel,
    main: MainViewModel,
    nav: Navigator,
    onRequestPermission: () -> Unit,
) {
    val state by lib.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.VIDEO) }
    var searching by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    var barVisible by remember { mutableStateOf(true) }

    // 向下滚动时隐藏底部导航，向上滚动时恢复
    val scrollWatcher = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -8) barVisible = false else if (available.y > 8) barVisible = true
                return Offset.Zero
            }
        }
    }

    val play: (List<MediaEntry>, Int) -> Unit = { list, i ->
        main.play(list, i)
        nav.push(Screen.Player)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            play(listOf(MediaLibrary.entryFor(context, uri)), 0)
        }
    }
    val files = rememberFileManager(lib) { dialog = HomeDialog.AddToPlaylist(it) }
    val entryMenu: (MediaEntry) -> List<MenuAction> = { e -> files.menu(e) }

    // 多选：长按进入，返回键或右上角关闭退出
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val selectable = (if (tab == Tab.VIDEO) state.videos else if (tab == Tab.AUDIO) state.audios else emptyList())
    val selectedEntries = selectable.filter { it.uri in selected }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    val toggle: (MediaEntry) -> Unit = { e -> selected = if (e.uri in selected) selected - e.uri else selected + e.uri }
    val openSettings = { nav.push(Screen.Settings) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selected.isNotEmpty()) {
                SelectionBar(
                    count = selected.size,
                    onClose = { selected = emptySet() },
                    onSelectAll = { selected = selectable.map { it.uri }.toSet() },
                    onShare = { files.share(selectedEntries) },
                    onAddToPlaylist = { dialog = HomeDialog.AddToPlaylist(selectedEntries) },
                    onMove = { files.move(selectedEntries); selected = emptySet() },
                    onCopy = { files.copy(selectedEntries); selected = emptySet() },
                    onDelete = { files.delete(selectedEntries); selected = emptySet() },
                )
            } else {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it; lib.searchSubtitles(it) },
                            placeholder = { Text("搜索文件名、文件夹或字幕内容") }, singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(AppIcons.PlayArrow, size = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("AI 字幕播放器", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                },
                actions = {
                    val mediaTab = tab == Tab.VIDEO || tab == Tab.AUDIO
                    if (mediaTab) {
                        IconButton(onClick = {
                            searching = !searching
                            if (!searching) { query = ""; lib.searchSubtitles("") }
                        }) { Icon(if (searching) AppIcons.Close else AppIcons.Search, "搜索") }
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(AppIcons.MoreVert, "更多") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (mediaTab) {
                                val page = if (tab == Tab.VIDEO) "video" else "audio"
                                val current = if (tab == Tab.VIDEO) state.videoSort else state.audioSort
                                SortOrder.entries.forEach { o ->
                                    DropdownMenuItem(
                                        text = { Text((if (o == current) "✓ " else "    ") + "按${o.label}排序") },
                                        onClick = { menu = false; lib.setSort(page, o) },
                                    )
                                }
                                if (tab == Tab.VIDEO) {
                                    DropdownMenuItem(
                                        text = { Text(if (state.videoGrid) "    列表视图" else "    网格视图") },
                                        onClick = { menu = false; lib.setVideoGrid(!state.videoGrid) },
                                    )
                                }
                                HorizontalDivider()
                            }
                            DropdownMenuItem(text = { Text("刷新媒体库") }, onClick = { menu = false; lib.refreshMedia() })
                            DropdownMenuItem(text = { Text("打开文件…") }, onClick = { menu = false; pickFile.launch(arrayOf("video/*", "audio/*", "application/x-iso9660-image", "application/octet-stream")) })
                            DropdownMenuItem(text = { Text("设置") }, onClick = { menu = false; openSettings() })
                        }
                    }
                },
            )
            }
        },
        bottomBar = {
            AnimatedVisibility(barVisible, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = {
                                tab = t
                                selected = emptySet()
                                searching = false
                                query = ""
                                barVisible = true
                            },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().nestedScroll(scrollWatcher)) {
            when (tab) {
                Tab.VIDEO -> MediaTab(state, isVideo = true, query, onRequestPermission, lib::refreshMedia, play, entryMenu, selected, toggle)
                Tab.AUDIO -> MediaTab(state, isVideo = false, query, onRequestPermission, lib::refreshMedia, play, entryMenu, selected, toggle)
                Tab.BROWSE -> BrowseTab(
                    state = state,
                    onRequestPermission = onRequestPermission,
                    openLocal = { path, title -> nav.push(Screen.LocalFolder(path, title)) },
                    openRemote = { s -> nav.push(Screen.Remote(s.id, s.rootPath, s.name)) },
                    pickFile = { pickFile.launch(arrayOf("video/*", "audio/*", "application/x-iso9660-image", "application/octet-stream")) },
                    addSource = { dialog = HomeDialog.AddSource },
                    serverMenu = { s ->
                        if (s.type.isCloud) listOf(
                            MenuAction("重新登录") { dialog = HomeDialog.CloudLogin(s.type, s) },
                            MenuAction("重命名") { dialog = HomeDialog.RenameServer(s) },
                            MenuAction("删除") { lib.deleteServer(s.id) },
                        ) else listOf(
                            MenuAction("编辑") { dialog = HomeDialog.EditServer(s) },
                            MenuAction("删除") { lib.deleteServer(s.id) },
                        )
                    },
                )
                Tab.PLAYLISTS -> PlaylistsTab(
                    state = state,
                    onOpen = { nav.push(Screen.PlaylistDetail(it.id)) },
                    onPlay = { p -> if (p.items.isNotEmpty()) play(p.items, 0) },
                    onNew = { dialog = HomeDialog.NewPlaylist },
                    onFromHistory = lib::playlistFromHistory,
                    onDelete = { lib.deletePlaylist(it.id) },
                )
                Tab.MORE -> MoreTab(
                    state = state,
                    onSettings = openSettings,
                    onAbout = { dialog = HomeDialog.About },
                    onStream = { dialog = HomeDialog.Stream },
                    onHistory = { nav.push(Screen.History) },
                    onPlayHistory = { item -> play(listOf(item.entry), 0) },
                )
            }
        }
    }

    when (val d = dialog) {
        null -> {}
        HomeDialog.About -> AboutDialog { dialog = null }
        HomeDialog.Stream -> TextInputDialog(
            title = "新建串流", label = "网络地址（http / https / rtsp …）", confirm = "播放",
            onDismiss = { dialog = null },
            onConfirm = { url ->
                dialog = null
                play(listOf(MediaEntry(url, url.substringAfterLast('/').ifEmpty { url })), 0)
            },
        )
        HomeDialog.NewPlaylist -> TextInputDialog(
            title = "新建播放列表", label = "名称", confirm = "创建",
            onDismiss = { dialog = null },
            onConfirm = { lib.createPlaylist(it); dialog = null },
        )
        HomeDialog.AddSource -> AddSourceDialog(
            onDismiss = { dialog = null },
            onPick = { type ->
                dialog = when (type) {
                    ServerType.WEBDAV, ServerType.OPENLIST ->
                        HomeDialog.EditServer(Server(type = type, name = if (type == ServerType.OPENLIST) "OpenList" else "", baseUrl = ""))
                    else -> HomeDialog.CloudLogin(type, null)
                }
            },
        )
        is HomeDialog.AddToPlaylist -> AddToPlaylistDialog(
            playlists = state.playlists,
            onDismiss = { dialog = null },
            onPick = { p -> d.entries.forEach { lib.addToPlaylist(p.id, it) }; dialog = null },
            onCreate = { lib.createPlaylistWith(it, d.entries); dialog = null },
        )
        is HomeDialog.CloudLogin -> CloudLoginDialog(
            type = d.type, existing = d.existing,
            onDismiss = { dialog = null },
            onLoggedIn = { lib.saveServer(it); dialog = null },
        )
        is HomeDialog.RenameServer -> TextInputDialog(
            title = "重命名", label = "名称", initial = d.server.name,
            onDismiss = { dialog = null },
            onConfirm = { lib.saveServer(d.server.copy(name = it)); dialog = null },
        )
        is HomeDialog.EditServer -> ServerDialog(
            initial = d.server, hint = d.hint,
            onDismiss = { dialog = null },
            onSave = { lib.saveServer(it); dialog = null },
        )
    }
}

private fun List<MediaEntry>.sortedBy(order: SortOrder): List<MediaEntry> = when (order) {
    SortOrder.RECENT -> this // 媒体库本身按添加时间倒序
    SortOrder.NAME -> sortedBy { it.title.lowercase() }
    SortOrder.DURATION -> sortedByDescending { it.durationMs }
    SortOrder.SIZE -> sortedByDescending { it.sizeBytes }
}

@Composable
private fun MediaTab(
    state: LibraryState,
    isVideo: Boolean,
    query: String,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    play: (List<MediaEntry>, Int) -> Unit,
    menu: (MediaEntry) -> List<MenuAction>,
    selected: Set<String> = emptySet(),
    onToggle: (MediaEntry) -> Unit = {},
) {
    val selecting = selected.isNotEmpty()
    if (!state.hasPermission) {
        EmptyState("需要媒体访问权限才能显示手机里的${if (isVideo) "视频" else "音频"}", "授予权限", onButton = onRequestPermission)
        return
    }
    val all = if (isVideo) state.videos else state.audios
    val q = query.trim()
    val list = remember(all, q, state.subtitleMatches, if (isVideo) state.videoSort else state.audioSort) {
        val filtered = if (q.isEmpty()) all else all.filter {
            it.title.contains(q, ignoreCase = true) || it.folder.contains(q, ignoreCase = true) || it.uri in state.subtitleMatches
        }
        filtered.sortedBy(if (isVideo) state.videoSort else state.audioSort)
    }
    if (list.isEmpty()) {
        when {
            q.isNotEmpty() -> EmptyState("没有找到「$q」\n可以搜索文件名、文件夹名，或已生成字幕里的内容", icon = AppIcons.Search)
            state.loading -> EmptyState("正在读取媒体库…", icon = if (isVideo) AppIcons.Movie else AppIcons.MusicNote)
            else -> EmptyState(
                "手机里还没有${if (isVideo) "视频" else "音频"}\n可以从「浏览」打开网络或网盘里的文件",
                "刷新媒体库", icon = if (isVideo) AppIcons.Movie else AppIcons.MusicNote, onButton = onRefresh,
            )
        }
        return
    }
    // 继续观看：最近一次没看完的视频
    val resume = state.history.firstOrNull { h ->
        h.entry.isVideo == isVideo && h.positionMs > 10_000 && (h.entry.durationMs <= 0 || h.positionMs < h.entry.durationMs - 30_000)
    }?.takeIf { q.isEmpty() }

    if (isVideo && state.videoGrid) {
        LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
            if (resume != null) item(span = { GridItemSpan(maxLineSpan) }) { ResumeCard(resume) { play(listOf(resume.entry), 0) } }
            itemsIndexed(list, key = { _, e -> e.uri }) { i, e ->
                VideoCard(e, menu(e), selected = if (selecting) e.uri in selected else null, onLongClick = { onToggle(e) }) {
                    if (selecting) onToggle(e) else play(list, i)
                }
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
            if (resume != null) item { ResumeCard(resume) { play(listOf(resume.entry), 0) } }
            itemsIndexed(list, key = { _, e -> e.uri }) { i, e ->
                MediaRow(e, menu(e), selected = if (selecting) e.uri in selected else null, onLongClick = { onToggle(e) }) {
                    if (selecting) onToggle(e) else play(list, i)
                }
            }
        }
    }
}

/** "继续观看"卡片。 */
@Composable
private fun ResumeCard(item: HistoryItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaThumbnail(item.entry, Modifier.size(width = 96.dp, height = 54.dp).clip(MaterialTheme.shapes.small))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("继续${if (item.entry.isVideo) "观看" else "收听"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(item.entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text("看到 ${formatDuration(item.positionMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(AppIcons.PlayArrow, "继续播放", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun BrowseTab(
    state: LibraryState,
    onRequestPermission: () -> Unit,
    openLocal: (String, String) -> Unit,
    openRemote: (Server) -> Unit,
    pickFile: () -> Unit,
    addSource: () -> Unit,
    serverMenu: (Server) -> List<MenuAction>,
) {
    val all = remember(state.videos, state.audios) { state.videos + state.audios }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SectionHeader("本机")
        val local = listOf(
            Triple("内部存储", AppIcons.Phone, ""),
            Triple("Download", AppIcons.Download, "Download"),
            Triple("Movies", AppIcons.Movie, "Movies"),
            Triple("Music", AppIcons.MusicNote, "Music"),
            Triple("DCIM", AppIcons.Folder, "DCIM"),
        )
        data class Card(val icon: ImageVector, val title: String, val subtitle: String, val onClick: () -> Unit)
        val cards = remember(all, state.hasPermission) {
            local.map { (name, icon, path) ->
                val sum = LocalBrowser.summary(all, path)
                Card(icon, name, if (state.hasPermission) countText(sum.folderCount, sum.fileCount) else "需要权限") {
                    if (state.hasPermission) openLocal(path, name) else onRequestPermission()
                }
            } + Card(AppIcons.File, "选择文件…", "系统文件选择器", pickFile)
        }
        // 两列网格
        cards.chunked(2).forEach { row ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c -> SourceCard(c.icon, c.title, c.subtitle, Modifier.weight(1f), c.onClick) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        SectionHeader("网络与网盘", actionLabel = "添加", onAction = addSource)
        SurfaceCard {
            if (state.servers.isEmpty()) {
                ListRow(
                    title = "添加网络位置或网盘",
                    subtitle = "WebDAV、OpenList、115、阿里云盘、百度网盘、PikPak",
                    icon = AppIcons.Add, mutedIcon = true, onClick = addSource,
                )
            }
            state.servers.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ListRow(
                    title = s.name,
                    subtitle = s.type.label + if (s.type.isCloud) " · 已登录" else " · ${s.baseUrl}",
                    icon = if (s.type.isCloud || s.type == ServerType.OPENLIST) AppIcons.Cloud else AppIcons.Dns,
                    trailing = { OverflowMenu(serverMenu(s)) },
                    onClick = { openRemote(s) },
                )
            }
        }
    }
}

/** 添加来源：一个列表里选类型，未配置的入口不再常驻占位。 */
@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onPick: (ServerType) -> Unit) {
    val options = listOf(
        ServerType.PAN115 to "用 115 App 扫码登录",
        ServerType.ALIYUN to "用阿里云盘 App 扫码登录",
        ServerType.BAIDU to "自有 AppKey 扫码，或粘贴 refresh_token",
        ServerType.PIKPAK to "账号密码登录",
        ServerType.EMBY to "Emby / Jellyfin 媒体服务器，账号密码登录",
        ServerType.PLEX to "plex.tv 账号授权，或填服务器地址和令牌",
        ServerType.ONEDRIVE to "需要自己注册的应用 ID（免费）",
        ServerType.DROPBOX to "需要自己创建的 App key（免费）",
        ServerType.GDRIVE to "需要自己创建的 OAuth 客户端（免费）",
        ServerType.WEBDAV to "NAS、Alist 等 WebDAV 服务",
        ServerType.OPENLIST to "OpenList / AList 站点",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加网络位置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (type, desc) ->
                    ListRow(
                        title = type.label, subtitle = desc,
                        icon = if (type == ServerType.WEBDAV) AppIcons.Dns else AppIcons.Cloud,
                        onClick = { onPick(type) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PlaylistsTab(
    state: LibraryState,
    onOpen: (Playlist) -> Unit,
    onPlay: (Playlist) -> Unit,
    onNew: () -> Unit,
    onFromHistory: () -> Unit,
    onDelete: (Playlist) -> Unit,
) {
    if (state.playlists.isEmpty()) {
        EmptyState(
            "还没有播放列表\n在视频或音频的「⋮」菜单里也可以加入列表",
            "新建播放列表", icon = AppIcons.QueueMusic, onButton = onNew,
            secondaryText = if (state.history.isNotEmpty()) "用最近播放生成一个" else null,
            onSecondary = onFromHistory,
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item { SectionHeader("${state.playlists.size} 个播放列表", actionLabel = "新建", onAction = onNew) }
        items(state.playlists, key = { it.id }) { p ->
            Surface(
                onClick = { onOpen(p) },
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val cover = p.items.firstOrNull()
                    if (cover != null) MediaThumbnail(cover, Modifier.size(56.dp).clip(MaterialTheme.shapes.medium))
                    else IconBadge(AppIcons.QueueMusic, size = 56.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val updated = if (p.updatedAt > 0) " · " + DateUtils.getRelativeTimeSpanString(p.updatedAt) + "更新" else ""
                        Text("${p.items.size} 项$updated", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onPlay(p) }, enabled = p.items.isNotEmpty()) {
                        Icon(AppIcons.PlayArrow, "播放全部", tint = MaterialTheme.colorScheme.primary)
                    }
                    OverflowMenu(listOf(MenuAction("删除") { onDelete(p) }))
                }
            }
        }
    }
}

@Composable
private fun MoreTab(
    state: LibraryState,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onStream: () -> Unit,
    onHistory: () -> Unit,
    onPlayHistory: (HistoryItem) -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SectionHeader("最近播放", actionLabel = if (state.history.isNotEmpty()) "全部" else null, onAction = onHistory)
        if (state.history.isEmpty()) {
            Text(
                "还没有播放记录", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                items(state.history.take(10), key = { it.entry.uri }) { h ->
                    Box(Modifier.width(170.dp)) { VideoCard(h.entry, emptyList()) { onPlayHistory(h) } }
                }
            }
        }

        SectionHeader("工具")
        SurfaceCard {
            ListRow("新建串流", "播放网络地址（http、rtsp、m3u8 等）", AppIcons.Link, onClick = onStream)
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListRow("设置", "语音识别、字幕翻译、字幕样式", AppIcons.Settings, onClick = onSettings)
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListRow("关于", null, AppIcons.Info, onClick = onAbout)
        }
    }
}

/** 多选模式的顶栏：已选数量 + 批量操作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        navigationIcon = { IconButton(onClick = onClose) { Icon(AppIcons.Close, "退出多选") } },
        title = { Text("已选 $count 项", style = MaterialTheme.typography.titleMedium) },
        actions = {
            IconButton(onClick = onShare) { Icon(AppIcons.Share, "分享") }
            IconButton(onClick = onAddToPlaylist) { Icon(AppIcons.PlaylistAdd, "加入播放列表") }
            IconButton(onClick = onDelete) { Icon(AppIcons.Delete, "删除") }
            OverflowMenu(
                listOf(
                    MenuAction("全选", onClick = onSelectAll),
                    MenuAction("移动到…", onClick = onMove),
                    MenuAction("复制到…", onClick = onCopy),
                )
            )
        },
    )
}
