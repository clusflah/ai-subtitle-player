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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisub.player.LibraryState
import com.aisub.player.LibraryViewModel
import com.aisub.player.MainViewModel
import com.aisub.player.media.FavoriteFolder
import com.aisub.player.media.HistoryItem
import com.aisub.player.media.LocalBrowser
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.MediaLibrary
import com.aisub.player.media.Playlist
import com.aisub.player.media.SortOrder
import com.aisub.player.media.formatDuration
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType

/**
 * 底部四个标签：首页汇总「继续看」（本机和各媒体服务器），本机放手机里的视频和音乐，
 * 浏览是各种来源（本机文件夹、网盘、媒体服务器、直播），我的放播放列表、最近播放和设置。
 */
private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("首页", AppIcons.Home),
    LOCAL("本机", AppIcons.Phone),
    BROWSE("浏览", AppIcons.Folder),
    ME("我的", AppIcons.Person),
}

/** 首页里需要弹出的对话框。 */
private sealed interface HomeDialog {
    data object About : HomeDialog
    data object Stream : HomeDialog
    data object Link : HomeDialog
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
    // 标签和本机页的视频 / 音乐存在 ViewModel 里，进播放页再回来不会跳回第一个标签
    val tab = Tab.entries[lib.homeTab.coerceIn(Tab.entries.indices)]
    val localVideo = lib.localShowsVideo
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
    val selectable = if (tab == Tab.LOCAL) (if (localVideo) state.videos else state.audios) else emptyList()
    val selectedEntries = selectable.filter { it.uri in selected }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    val toggle: (MediaEntry) -> Unit = { e -> selected = if (e.uri in selected) selected - e.uri else selected + e.uri }
    val openSettings = { nav.push(Screen.Settings) }
    val wide = LocalWideLayout.current
    val selectTab: (Tab) -> Unit = { t ->
        lib.homeTab = t.ordinal
        selected = emptySet()
        searching = false
        query = ""
        barVisible = true
    }

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
                            // 标题栏只有一行高：提示文字不换行，输入内容超长时横向滚动
                            placeholder = {
                                Text(
                                    "搜索文件名、文件夹或字幕内容", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        // 标题就是当前标签的名字，一行，把地方留给内容
                        Text(if (tab == Tab.HOME) "译幕" else tab.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    val mediaTab = tab == Tab.LOCAL
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
                                val page = if (localVideo) "video" else "audio"
                                val current = if (localVideo) state.videoSort else state.audioSort
                                SortOrder.entries.forEach { o ->
                                    DropdownMenuItem(
                                        text = { Text((if (o == current) "✓ " else "    ") + "按${o.label}排序") },
                                        onClick = { menu = false; lib.setSort(page, o) },
                                    )
                                }
                                if (localVideo) {
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
          Column {
            // 音乐在后台放着时的「正在播放」小条，放在标签栏上面，占自己的位置
            NowPlayingBar(main, bottomPadding = 4.dp, onOpen = { nav.push(Screen.Player) }, navigationBarInset = false)
            // 电视、横屏平板：标签放在左侧竖栏（见下面），底部不放
            if (!wide) AnimatedVisibility(barVisible, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { selectTab(t) },
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
          }
        },
    ) { padding ->
      Row(Modifier.padding(padding).fillMaxSize()) {
        if (wide) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.padding(end = 8.dp)) {
                Spacer(Modifier.weight(1f))
                Tab.entries.forEach { t ->
                    NavigationRailItem(
                        selected = tab == t,
                        onClick = { selectTab(t) },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
        Box(Modifier.weight(1f).fillMaxSize().nestedScroll(scrollWatcher)) {
            val openServer: (Server) -> Unit = { s ->
                // Plex、Emby / Jellyfin 用官方客户端那样的首页；首页菜单里还能切回按文件夹浏览
                if (s.type == ServerType.PLEX || s.type == ServerType.EMBY) nav.push(Screen.ServerHome(s.id, s.name))
                else nav.push(Screen.Remote(s.id, s.rootPath, s.name))
            }
            when (tab) {
                Tab.HOME -> HomeTab(
                    lib, state, main.state.collectAsStateWithLifecycle().value.settings, play,
                    openServer = openServer,
                    openServerItem = { s, item -> nav.push(Screen.ServerDetail(s.id, item.ratingKey)) },
                    openFavorite = { f ->
                        if (f.serverId == null) nav.push(Screen.LocalFolder(f.path, f.title))
                        else nav.push(Screen.Remote(f.serverId, f.path, f.title))
                    },
                    goLocal = { lib.homeTab = Tab.LOCAL.ordinal },
                    goBrowse = { lib.homeTab = Tab.BROWSE.ordinal },
                    addSource = { dialog = HomeDialog.AddSource },
                )
                Tab.LOCAL -> Column {
                    // 视频 / 音乐 切换
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(true to "视频", false to "音乐").forEach { (video, label) ->
                            FilterChip(
                                selected = localVideo == video,
                                onClick = { lib.localShowsVideo = video; selected = emptySet() },
                                label = { Text(label + " " + (if (video) state.videos.size else state.audios.size)) },
                            )
                        }
                    }
                    MediaTab(
                        state, isVideo = localVideo, query, onRequestPermission, lib::refreshMedia, play, entryMenu, selected, toggle,
                        listState = lib.listState(if (localVideo) "local.video" else "local.audio"),
                        gridState = lib.gridState("local.video"),
                    )
                }
                Tab.BROWSE -> BrowseTab(
                    state = state,
                    onRequestPermission = onRequestPermission,
                    openLocal = { path, title -> nav.push(Screen.LocalFolder(path, title)) },
                    openRemote = openServer,
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
                    openLive = { nav.push(Screen.Live) },
                    openWeb = { nav.push(Screen.Web) },
                    openStream = { dialog = HomeDialog.Stream },
                )
                Tab.ME -> MeTab(
                    state = state,
                    onSettings = openSettings,
                    onAbout = { dialog = HomeDialog.About },
                    onLink = if (main.linkDownloader != null) ({ dialog = HomeDialog.Link }) else null,
                    onHistory = { music -> nav.push(Screen.History(music)) },
                    onPlayHistory = { item -> play(listOf(item.entry), 0) },
                    onPlayMusic = play,
                    onOpenPlaylist = { nav.push(Screen.PlaylistDetail(it.id)) },
                    onPlayPlaylist = { p -> if (p.items.isNotEmpty()) play(p.items, 0) },
                    onNewPlaylist = { dialog = HomeDialog.NewPlaylist },
                    onPlaylistFromHistory = lib::playlistFromHistory,
                    onDeletePlaylist = { lib.deletePlaylist(it.id) },
                )
            }
        }
      }
    }

    when (val d = dialog) {
        null -> {}
        HomeDialog.About -> AboutDialog { dialog = null }
        HomeDialog.Link -> TextInputDialog(
            title = "从链接下载", label = "视频网页链接（YouTube、B 站等）", confirm = "下载",
            onDismiss = { dialog = null },
            onConfirm = { text ->
                dialog = null
                com.aisub.player.download.extractUrl(text)?.let { url ->
                    main.downloadLink(url)
                    nav.push(Screen.Link)
                }
            },
        )
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
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
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
        LazyVerticalGrid(GridCells.Adaptive(if (LocalTv.current) 200.dp else 160.dp), state = gridState, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
            if (resume != null) item(span = { GridItemSpan(maxLineSpan) }) { ResumeCard(resume) { play(listOf(resume.entry), 0) } }
            itemsIndexed(list, key = { _, e -> e.uri }) { i, e ->
                VideoCard(e, menu(e), selected = if (selecting) e.uri in selected else null, onLongClick = { onToggle(e) }) {
                    if (selecting) onToggle(e) else play(list, i)
                }
            }
        }
    } else {
        LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 4.dp)) {
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp).tvFocusRing(MaterialTheme.shapes.large),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaThumbnail(item.entry, Modifier.size(width = 96.dp, height = 54.dp).clip(MaterialTheme.shapes.small))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("继续${if (item.entry.isVideo) "观看" else "收听"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(item.entry.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
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
    openLive: () -> Unit,
    openWeb: () -> Unit,
    openStream: () -> Unit,
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
        // 两列网格（大屏四列）
        val columns = if (LocalWideLayout.current) 4 else 2
        cards.chunked(columns).forEach { row ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c -> SourceCard(c.icon, c.title, c.subtitle, Modifier.weight(1f), c.onClick) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
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

        SectionHeader("直播与串流")
        SurfaceCard {
            ListRow("直播", "添加 M3U / TXT 直播源，按分组看电视直播", AppIcons.Live, onClick = openLive)
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListRow("新建串流", "播放网络地址（http、rtsp、m3u8 等）", AppIcons.Link, onClick = openStream)
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListRow("网页", "在网页里看视频，边播边生成 AI 字幕", AppIcons.Web, onClick = openWeb)
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

/** 首页：继续看（本机）、各媒体服务器的继续观看、本机最近添加的视频。 */
@Composable
private fun HomeTab(
    lib: LibraryViewModel,
    state: LibraryState,
    settings: com.aisub.player.AppSettings,
    play: (List<MediaEntry>, Int) -> Unit,
    openServer: (Server) -> Unit,
    openServerItem: (Server, com.aisub.player.remote.cloud.ServerItem) -> Unit,
    openFavorite: (FavoriteFolder) -> Unit,
    goLocal: () -> Unit,
    goBrowse: () -> Unit,
    addSource: () -> Unit,
) {
    val mediaServers = state.servers.filter { it.type == ServerType.PLEX || it.type == ServerType.EMBY }
    val resume = remember(state.history, mediaServers) {
        // Plex、Emby 的条目在它们自己那一行里显示（进度以服务器为准），这里不重复
        val serverIds = mediaServers.map { it.id }.toSet()
        state.history.filter { h ->
            val fromServer = h.entry.uri.startsWith("cloud://") && android.net.Uri.parse(h.entry.uri).host in serverIds
            !fromServer && !h.entry.isLive && !h.entry.isMusic && h.positionMs > 10_000 &&
                (h.entry.durationMs <= 0 || h.positionMs < h.entry.durationMs - 30_000)
        }.take(12)
    }
    // 继续收听：最近听的歌，点一首从它开始连着放；没听完的从上次的位置接着放
    val recentSongs = remember(state.history) { state.history.filter { it.entry.isMusic }.take(12) }
    val recentVideos = remember(state.videos) { state.videos.sortedBy(SortOrder.RECENT).take(12) }
    // 服务器删掉后它的收藏也不显示
    val favorites = remember(state.favoriteFolders, state.servers) {
        state.favoriteFolders.filter { f -> f.serverId == null || state.servers.any { it.id == f.serverId } }
    }
    LazyColumn(state = lib.listState("home"), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (resume.isNotEmpty()) {
            item { SectionHeader("继续观看") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(resume, key = { it.entry.uri }) { h -> ResumeTile(h, settings) { play(listOf(h.entry), 0) } }
                }
            }
        }
        if (recentSongs.isNotEmpty()) {
            item { SectionHeader("继续收听") }
            item {
                val songs = recentSongs.map { it.entry }
                LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(recentSongs, key = { _, h -> h.entry.uri }) { i, h -> SongTile(h.entry, h.positionMs) { play(songs, i) } }
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item { SectionHeader("收藏的文件夹") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(favorites, key = { it.key }) { f ->
                        val source = f.serverId?.let { id -> state.servers.firstOrNull { it.id == id }?.name } ?: "本机"
                        FavoriteFolderTile(
                            f, source,
                            onRemove = { lib.toggleFavoriteFolder(f.serverId, f.path, f.title) },
                        ) { openFavorite(f) }
                    }
                }
            }
        }
        items(mediaServers, key = { it.id }) { s -> ServerContinueRow(lib, s, onOpen = { openServer(s) }, onItem = { openServerItem(s, it) }) }
        if (recentVideos.isNotEmpty()) {
            item { SectionHeader("最近添加 · 本机视频", actionLabel = "全部", onAction = goLocal) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(recentVideos, key = { it.uri }) { e ->
                        Box(Modifier.width(170.dp)) { VideoCard(e, emptyList()) { play(recentVideos, recentVideos.indexOf(e)) } }
                    }
                }
            }
        }
        if (resume.isEmpty() && recentSongs.isEmpty() && favorites.isEmpty() && mediaServers.isEmpty() && recentVideos.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                IconBadge(AppIcons.Movie, size = 72.dp)
                Spacer(Modifier.height(16.dp))
                Text("还没有可以看的内容", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "看手机里的视频，或者添加 NAS、网盘、Plex / Emby 媒体服务器",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = goBrowse) { Text("浏览文件") }
                    Button(onClick = addSource) { Text("添加媒体服务器") }
                }
            }
        }
    }
}

/** 首页「收藏的文件夹」卡片：文件夹名 + 来源（本机或服务器名），「⋮」里取消收藏 */
@Composable
private fun FavoriteFolderTile(folder: FavoriteFolder, source: String, onRemove: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.width(200.dp).tvFocusRing(MaterialTheme.shapes.medium, scale = true).clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onClick)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(folder.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(source, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OverflowMenu(listOf(MenuAction("取消收藏", onClick = onRemove)), compact = true)
    }
}

/** 歌曲卡片（首页继续收听、我的 → 最近听过的音乐）：方形封面 + 歌名；positionMs 为没听完时的进度 */
@Composable
private fun SongTile(entry: MediaEntry, positionMs: Long = 0, onClick: () -> Unit) {
    Column(Modifier.width(110.dp).tvFocusRing(MaterialTheme.shapes.medium, scale = true).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium)) {
            MediaThumbnail(entry, Modifier.fillMaxSize())
            val d = entry.durationMs
            if (d > 0 && positionMs > 5_000 && positionMs < d - 10_000) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66000000))) {
                    Box(Modifier.fillMaxWidth(positionMs.toFloat() / d).height(3.dp).background(Orange))
                }
            }
        }
        // 留出左右边距，不被卡片的圆角裁掉
        Text(
            entry.displayTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        )
    }
}

/** 「继续观看」卡片：横版画面（封面图，没有用播放时截的画面）+ 进度条 + 名字 */
@Composable
private fun ResumeTile(item: HistoryItem, settings: com.aisub.player.AppSettings, onClick: () -> Unit) {
    val context = LocalContext.current
    val cover by produceState(com.aisub.player.meta.VideoCovers.saved(context, item.entry.uri)?.ifEmpty { null }, item.entry.uri) {
        if (value == null) value = com.aisub.player.meta.VideoCovers.find(context, settings, item.entry)
    }
    var coverFailed by remember(cover) { mutableStateOf(false) }
    Column(Modifier.width(180.dp).tvFocusRing(MaterialTheme.shapes.medium, scale = true).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)) {
            if (cover != null && !coverFailed) {
                NetImage(cover, Modifier.fillMaxSize(), maxSize = 480, onError = { coverFailed = true })
            } else {
                MediaThumbnail(item.entry, Modifier.fillMaxSize())
            }
            val d = item.entry.durationMs
            if (d > 0) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66000000))) {
                    Box(Modifier.fillMaxWidth((item.positionMs.toFloat() / d).coerceIn(0f, 1f)).height(3.dp).background(Orange))
                }
            }
        }
        Text(item.entry.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Text(
            "看到 ${formatDuration(item.positionMs)}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一个媒体服务器的继续观看（读不到时只显示入口） */
@Composable
private fun ServerContinueRow(
    lib: LibraryViewModel,
    server: Server,
    onOpen: () -> Unit,
    onItem: (com.aisub.player.remote.cloud.ServerItem) -> Unit,
) {
    val items = lib.serverContinue[server.id]
    LaunchedEffect(server.id) {
        val browser = lib.client(server) as? com.aisub.player.remote.cloud.MediaBrowser ?: return@LaunchedEffect
        runCatching { browser.hubs() }.getOrNull()?.let { hubs ->
            lib.serverContinue[server.id] = hubs.filter { it.id == "home.continue" || it.id == "home.ondeck" }
                .flatMap { it.items }.distinctBy { it.ratingKey }.take(12)
        }
    }
    Column {
        SectionHeader(server.name, actionLabel = "进入", onAction = onOpen)
        if (items.isNullOrEmpty()) {
            SurfaceCard { ListRow(server.name, "${server.type.label} · 点「进入」浏览媒体库", AppIcons.Cloud, onClick = onOpen) }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.ratingKey }) { item -> WideCard(item, Modifier.width(180.dp)) { onItem(item) } }
            }
        }
    }
}

/** 我的：播放列表、最近播放、工具。 */
@Composable
private fun MeTab(
    state: LibraryState,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onLink: (() -> Unit)?,
    /** 参数：true 打开音乐的最近播放，false 打开视频的 */
    onHistory: (Boolean) -> Unit,
    onPlayHistory: (HistoryItem) -> Unit,
    onPlayMusic: (List<MediaEntry>, Int) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onPlayPlaylist: (Playlist) -> Unit,
    onNewPlaylist: () -> Unit,
    onPlaylistFromHistory: () -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SectionHeader("播放列表", actionLabel = "新建", onAction = onNewPlaylist)
        SurfaceCard {
            if (state.playlists.isEmpty()) {
                ListRow(
                    "还没有播放列表", "在视频或音频的「⋮」菜单里加入；或者用最近播放生成一个", AppIcons.QueueMusic, mutedIcon = true,
                    onClick = if (state.history.isNotEmpty()) onPlaylistFromHistory else onNewPlaylist,
                )
            }
            state.playlists.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val updated = if (p.updatedAt > 0) " · " + DateUtils.getRelativeTimeSpanString(p.updatedAt) + "更新" else ""
                ListRow(
                    p.name, "${p.items.size} 项$updated", AppIcons.QueueMusic,
                    trailing = {
                        Row {
                            IconButton(onClick = { onPlayPlaylist(p) }, enabled = p.items.isNotEmpty()) {
                                Icon(AppIcons.PlayArrow, "播放全部", tint = MaterialTheme.colorScheme.primary)
                            }
                            OverflowMenu(listOf(MenuAction("删除") { onDeletePlaylist(p) }))
                        }
                    },
                    onClick = { onOpenPlaylist(p) },
                )
            }
        }

        // 视频和音乐的最近播放分开放
        val videoHistory = remember(state.history) { state.history.filter { !it.entry.isMusic } }
        val musicHistory = remember(state.history) { state.history.filter { it.entry.isMusic } }
        SectionHeader("最近看过的视频", actionLabel = if (videoHistory.isNotEmpty()) "全部" else null, onAction = { onHistory(false) })
        if (videoHistory.isEmpty()) {
            Text(
                "还没有看过的视频", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                items(videoHistory.take(10), key = { it.entry.uri }) { h ->
                    Box(Modifier.width(170.dp)) { VideoCard(h.entry, emptyList()) { onPlayHistory(h) } }
                }
            }
        }

        SectionHeader("最近听过的音乐", actionLabel = if (musicHistory.isNotEmpty()) "全部" else null, onAction = { onHistory(true) })
        if (musicHistory.isEmpty()) {
            Text(
                "还没有听过的音乐", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            val songs = musicHistory.take(20).map { it.entry }
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(songs, key = { _, e -> e.uri }) { i, e -> SongTile(e) { onPlayMusic(songs, i) } }
            }
        }

        SectionHeader("工具")
        SurfaceCard {
            if (onLink != null) {
                ListRow("从链接下载", "下载视频网站的视频，自动准备字幕后播放（也可以在其他 App 里分享给译幕）", AppIcons.Download, onClick = onLink)
                HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            ListRow("设置", "语音识别、字幕翻译、播放、朗读", AppIcons.Settings, onClick = onSettings)
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
            ListRow("检查更新", "当前版本 ${com.aisub.player.BuildConfig.VERSION_NAME}", AppIcons.SystemUpdate,
                onClick = { activity?.let { com.aisub.player.update.appUpdater.checkNow(it) } })
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
