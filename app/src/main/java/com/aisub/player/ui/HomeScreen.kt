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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
    /** 添加：kinds 为空 = 全部（浏览页右上角），否则只列那几类（分区的「添加」） */
    data class AddSource(val kinds: Set<AddKind> = emptySet()) : HomeDialog
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
    onRequestImagePermission: () -> Unit,
) {
    val state by lib.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 标签和本机页的视频 / 音乐存在 ViewModel 里，进播放页再回来不会跳回第一个标签
    val tab = Tab.entries[lib.homeTab.coerceIn(Tab.entries.indices)]
    val localVideo = lib.localShowsVideo
    var localPhotos by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tab) { lib.reloadStored() }
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
            // 漫画压缩包、PDF：用漫画阅读打开
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull().orEmpty()
            val type = context.contentResolver.getType(uri)
            if (com.aisub.player.manga.MangaArchive.isArchiveType(type) || com.aisub.player.manga.MangaArchive.isArchiveName(name) ||
                type == "text/plain" || com.aisub.player.book.Ebooks.isBookName(name)
            ) {
                nav.push(Screen.MangaRead(MangaOpen.Archive(name.substringBeforeLast('.').ifEmpty { "漫画" }, uri.toString())))
            } else play(listOf(MediaLibrary.entryFor(context, uri)), 0)
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

    GlassBackdropBox { backdrop ->
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
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when (tab) {
                            Tab.HOME -> "译幕"
                            Tab.BROWSE -> "资源库"
                            else -> tab.label
                        },
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val mediaTab = tab == Tab.LOCAL
                        // 浏览页与首页右上角：点击「+」直接弹出液态玻璃来源菜单（仿演示视频与截图）
                        if (tab == Tab.BROWSE || tab == Tab.HOME) {
                            var addMenu by remember { mutableStateOf(false) }
                            Box {
                                LiquidGlassIconButton(
                                    onClick = { addMenu = true },
                                    icon = AppIcons.Add,
                                    contentDescription = "添加",
                                )
                                AddSourceDropdownMenu(
                                    expanded = addMenu,
                                    onDismiss = { addMenu = false },
                                    onPick = { type ->
                                        addMenu = false
                                        dialog = when (type) {
                                            ServerType.WEBDAV, ServerType.OPENLIST ->
                                                HomeDialog.EditServer(Server(type = type, name = if (type == ServerType.OPENLIST) "OpenList" else "", baseUrl = ""))
                                            else -> HomeDialog.CloudLogin(type, null)
                                        }
                                    },
                                    onLive = { addMenu = false; nav.push(Screen.Live) },
                                    onStream = { addMenu = false; dialog = HomeDialog.Stream },
                                    onTelegram = { addMenu = false; nav.push(Screen.Telegram) },
                                )
                            }
                        }
                        if (mediaTab) {
                            LiquidGlassIconButton(
                                onClick = {
                                    searching = !searching
                                    if (!searching) { query = ""; lib.searchSubtitles("") }
                                },
                                icon = if (searching) AppIcons.Close else AppIcons.Search,
                                contentDescription = "搜索",
                            )
                        }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            LiquidGlassIconButton(
                                onClick = { menu = true },
                                icon = AppIcons.MoreHoriz,
                                contentDescription = "更多",
                            )
                            AppMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
                                DropdownMenuItem(text = { Text("隐藏的文件夹") }, onClick = { menu = false; nav.push(Screen.HiddenFolders) })
                                DropdownMenuItem(text = { Text("设置") }, onClick = { menu = false; openSettings() })
                            }
                        }
                    }
                }
                AnimatedVisibility(searching) {
                    val searchPillShape = RoundedCornerShape(50)
                    Row(
                        Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth()
                            .liquidGlass(shape = searchPillShape, style = GlassStyle.Pill)
                            .padding(start = 14.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        TextField(
                            value = query,
                            onValueChange = { query = it; lib.searchSubtitles(it) },
                            placeholder = {
                                Text(
                                    "搜索文件名、文件夹或字幕内容", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; lib.searchSubtitles("") }) {
                                Icon(AppIcons.Close, "清除")
                            }
                        }
                    }
                }
            }
            }
        },
        bottomBar = {
          Column {
            // 音乐在后台放着时的「正在播放」小条，放在标签栏上面，占自己的位置
            NowPlayingBar(main, bottomPadding = 4.dp, onOpen = { nav.push(Screen.Player) }, navigationBarInset = false)
            // 电视、横屏平板：标签放在左侧竖栏（见下面），底部不放
            if (!wide) AnimatedVisibility(barVisible, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                LiquidGlassBottomBar(
                    items = remember {
                        Tab.entries.map { t ->
                            LiquidNavTabItem(
                                value = t,
                                label = if (t == Tab.BROWSE) "资源库" else t.label,
                                icon = t.icon,
                            )
                        }
                    },
                    selected = tab,
                    onSelect = selectTab,
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
          }
        },
    ) { padding ->
      Row(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
        if (wide) {
            NavigationRail(
                containerColor = Color.Transparent,
                modifier = Modifier
                    .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 8.dp)
                    .liquidGlass(shape = RoundedCornerShape(24.dp), style = GlassStyle.Bar),
            ) {
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
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
        Box(
            Modifier.weight(1f).fillMaxSize()
                .glassBackdropSource(backdrop)
                .nestedScroll(scrollWatcher)
                // 内容不够长、没法滚动时（比如分组都折叠了），往下拉也能叫回底部导航
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.position.y - down.position.y > 24.dp.toPx()) { barVisible = true; break }
                        }
                    }
                }
        ) {
            val openServer: (Server) -> Unit = { s ->
                // Plex、Emby / Jellyfin 用官方客户端那样的首页；首页菜单里还能切回按文件夹浏览
                if (s.type.isMediaServer) nav.push(Screen.ServerHome(s.id, s.name))
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
                    addSource = { dialog = HomeDialog.AddSource(setOf(AddKind.STORAGE, AddKind.MEDIA_SERVER)) },
                    openReading = { nav.push(Screen.MangaRead(it)) },
                    openWebManga = { url -> com.aisub.player.web.WebLaunch.pending = url; nav.push(Screen.Web) },
                )
                Tab.LOCAL -> Column {
                    // 视频 / 音乐 / 照片 液态玻璃分段切换器（仿演示视频 00:03）
                    val selectedMediaIdx = when {
                        localPhotos -> 2
                        localVideo -> 0
                        else -> 1
                    }
                    LiquidGlassSegmentedControl(
                        options = listOf(
                            "视频 ${state.videos.size}",
                            "音乐 ${state.audios.size}",
                            "照片 ${state.images.size}",
                        ),
                        selectedIndex = selectedMediaIdx,
                        onSelect = { index ->
                            localPhotos = index == 2
                            if (index < 2) lib.localShowsVideo = index == 0
                            selected = emptySet()
                            if (index == 2) {
                                if (!state.hasImagePermission) onRequestImagePermission() else lib.refreshImages()
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    if (localPhotos) {
                        if (!state.hasImagePermission) EmptyState("需要照片访问权限才能显示手机里的图片", "授予权限", icon = AppIcons.Image, onButton = onRequestImagePermission)
                        else if (state.images.isEmpty()) EmptyState("手机里还没有照片", "刷新照片", icon = AppIcons.Image, onButton = lib::refreshImages)
                        else LazyVerticalGrid(GridCells.Adaptive(if (LocalTv.current) 180.dp else 112.dp), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 104.dp)) {
                            itemsIndexed(state.images, key = { _, e -> e.uri }) { index, e ->
                                NetImage(e.uri, Modifier.padding(3.dp).aspectRatio(1f).clip(MaterialTheme.shapes.small)
                                    .clickable { nav.push(Screen.ImageViewer(null, state.images.map { image ->
                                        com.aisub.player.remote.RemoteItem(image.title, image.uri, false, image.sizeBytes, image.uri)
                                    }, index)) }, contentScale = androidx.compose.ui.layout.ContentScale.Crop, fallback = AppIcons.Image)
                            }
                        }
                    } else MediaTab(
                        state, isVideo = localVideo, query, onRequestPermission, lib::refreshMedia, play, entryMenu, selected, toggle,
                        onRemoveHistory = lib::removeHistory,
                        listState = lib.listState(if (localVideo) "local.video" else "local.audio"),
                        gridState = lib.gridState("local.video"),
                    )
                }
                Tab.BROWSE -> BrowseTab(
                    state = state,
                    onRequestPermission = onRequestPermission,
                    openLocal = { path, title -> nav.push(Screen.LocalFolder(path, title)) },
                    openRemote = openServer,
                    pickFile = {
                        pickFile.launch(arrayOf(
                            "video/*", "audio/*", "application/x-iso9660-image", "application/octet-stream",
                        ) + com.aisub.player.manga.MangaArchive.TYPES + "text/plain")
                    },
                    addSource = { kinds -> dialog = HomeDialog.AddSource(kinds) },
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
                    openManga = { nav.push(Screen.Manga) },
                    openBooks = { nav.push(Screen.Books) },
                    openTelegram = { nav.push(Screen.Telegram) },
                    openStream = { dialog = HomeDialog.Stream },
                    onNotScrollable = { barVisible = true },
                )
                Tab.ME -> MeTab(
                    state = state,
                    onSettings = openSettings,
                    onAbout = { dialog = HomeDialog.About },
                    onLink = if (main.linkDownloader != null) ({ dialog = HomeDialog.Link }) else null,
                    onHistory = { music -> nav.push(Screen.History(music)) },
                    onPlayHistory = { item -> play(listOf(item.entry), 0) },
                    onRemoveHistory = lib::removeHistory,
                    onPlayMusic = play,
                    onOpenPlaylist = { nav.push(Screen.PlaylistDetail(it.id)) },
                    onPlayPlaylist = { p -> if (p.items.isNotEmpty()) play(p.items, 0) },
                    onNewPlaylist = { dialog = HomeDialog.NewPlaylist },
                    onPlaylistFromHistory = lib::playlistFromHistory,
                    onDeletePlaylist = { lib.deletePlaylist(it.id) },
                    onHiddenFolders = { nav.push(Screen.HiddenFolders) },
                    onRefreshStats = lib::refreshPlaybackStats,
                    openFavorite = { f ->
                        if (f.serverId == null) nav.push(Screen.LocalFolder(f.path, f.title))
                        else nav.push(Screen.Remote(f.serverId, f.path, f.title))
                    },
                    removeFavorite = { f -> lib.toggleFavoriteFolder(f.serverId, f.path, f.title) },
                    openWebBookmarks = { com.aisub.player.web.WebLaunch.pending = null; nav.push(Screen.Web) },
                    openLiveFavorites = { nav.push(Screen.Live) },
                    openManga = { nav.push(Screen.Manga) },
                    openBooks = { nav.push(Screen.Books) },
                )
            }
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
        is HomeDialog.AddSource -> AddSourceDialog(
            kinds = d.kinds,
            onDismiss = { dialog = null },
            onLive = { dialog = null; nav.push(Screen.Live) },
            onStream = { dialog = HomeDialog.Stream },
            onTelegram = { dialog = null; nav.push(Screen.Telegram) },
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
    onRemoveHistory: (String) -> Unit = {},
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
        LazyVerticalGrid(GridCells.Adaptive(if (LocalTv.current) 200.dp else 160.dp), state = gridState, contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 104.dp)) {
            if (resume != null) item(span = { GridItemSpan(maxLineSpan) }) {
                ResumeCard(resume, onRemove = { onRemoveHistory(resume.entry.uri) }) { play(listOf(resume.entry), 0) }
            }
            itemsIndexed(list, key = { _, e -> e.uri }) { i, e ->
                VideoCard(e, menu(e), selected = if (selecting) e.uri in selected else null, onLongClick = { onToggle(e) }) {
                    if (selecting) onToggle(e) else play(list, i)
                }
            }
        }
    } else {
        LazyColumn(state = listState, contentPadding = PaddingValues(top = 4.dp, bottom = 104.dp)) {
            if (resume != null) item {
                ResumeCard(resume, onRemove = { onRemoveHistory(resume.entry.uri) }) { play(listOf(resume.entry), 0) }
            }
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
private fun ResumeCard(item: HistoryItem, onRemove: () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .tvFocusRing(MaterialTheme.shapes.large)
            .liquidGlass(
                shape = MaterialTheme.shapes.large,
                style = GlassStyle.Card,
                tint = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
            ),
    ) {
        Row(Modifier.padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaThumbnail(item.entry, Modifier.size(width = 96.dp, height = 54.dp).clip(MaterialTheme.shapes.small))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("继续${if (item.entry.isVideo) "观看" else "收听"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(item.entry.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text("看到 ${formatDuration(item.positionMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(AppIcons.PlayArrow, "继续播放", tint = MaterialTheme.colorScheme.primary)
            RemoveRecentButton(onRemove)
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
    addSource: (Set<AddKind>) -> Unit,
    serverMenu: (Server) -> List<MenuAction>,
    openLive: () -> Unit,
    openWeb: () -> Unit,
    openManga: () -> Unit,
    openBooks: () -> Unit,
    openTelegram: () -> Unit,
    openStream: () -> Unit,
    onNotScrollable: () -> Unit,
) {
    val all = remember(state.videos, state.audios) { state.videos + state.audios }
    val scroll = rememberScrollState()
    // 分组折叠后页面短到滚不动时，底部导航不能一直藏着
    LaunchedEffect(scroll.maxValue) { if (scroll.maxValue == 0) onNotScrollable() }
    Column(Modifier.verticalScroll(scroll).padding(bottom = 104.dp)) {
        CollapsibleSection("browse.local", "本地文件") {
            data class LocalSpec(val name: String, val icon: ImageVector, val badge: SourceBadgeKind, val path: String)
            val local = listOf(
                LocalSpec("内部存储", AppIcons.Phone, SourceBadgeKind.LOCAL_PHONE, ""),
                LocalSpec("Download", AppIcons.Download, SourceBadgeKind.LOCAL_DOWNLOAD, "Download"),
                LocalSpec("Movies", AppIcons.Movie, SourceBadgeKind.LOCAL_MOVIES, "Movies"),
                LocalSpec("Music", AppIcons.MusicNote, SourceBadgeKind.LOCAL_MUSIC, "Music"),
                LocalSpec("DCIM", AppIcons.Folder, SourceBadgeKind.LOCAL_DCIM, "DCIM"),
            )
            data class Card(val icon: ImageVector, val badge: SourceBadgeKind, val title: String, val subtitle: String, val onClick: () -> Unit)
            val cards = remember(all, state.hasPermission) {
                local.map { (name, icon, badge, path) ->
                    val sum = LocalBrowser.summary(all, path)
                    Card(icon, badge, name, if (state.hasPermission) countText(sum.folderCount, sum.fileCount) else "需要权限") {
                        if (state.hasPermission) openLocal(path, name) else onRequestPermission()
                    }
                } + Card(AppIcons.File, SourceBadgeKind.LOCAL_PICKER, "选择文件…", "系统文件选择器", pickFile)
            }
            // 两列网格（大屏四列）
            val columns = if (LocalWideLayout.current) 4 else 2
            cards.chunked(columns).forEach { row ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { c ->
                        SourceCard(
                            c.icon, c.title, c.subtitle, Modifier.weight(1f),
                            badge = { SourceBadgeIcon(c.badge, size = 36.dp) },
                            onClick = c.onClick,
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        // 文件存储（NAS、网盘）和媒体服务器（有自己的媒体库、海报墙）分开放
        val storage = state.servers.filter { !it.type.isMediaServer }
        val mediaServers = state.servers.filter { it.type.isMediaServer }
        val serverRow: @Composable (Int, Server) -> Unit = { i, s ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            // 状态：上次打开的时间；上次连接失败时右边标出来
            val visit = state.serverVisits[s.id]
            val seen = when {
                visit == null -> "还没打开过"
                else -> DateUtils.getRelativeTimeSpanString(kotlin.math.abs(visit), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() +
                    if (visit > 0) "打开" else "连接失败"
            }
            ListRow(
                title = s.name,
                subtitle = s.type.label + " · " + seen,
                leading = { ServerSourceIcon(s) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (visit != null && visit < 0) StatusChip("连接失败", StatusKind.ERROR)
                        OverflowMenu(serverMenu(s))
                    }
                },
                onClick = { openRemote(s) },
            )
        }

        CollapsibleSection("browse.servers", "网络存储", actionLabel = "添加", onAction = { addSource(setOf(AddKind.STORAGE)) }, count = storage.size) {
            SurfaceCard {
                if (storage.isEmpty()) {
                    ListRow(
                        title = "添加 NAS 或网盘",
                        subtitle = "WebDAV、OpenList、115、阿里云盘、百度网盘、PikPak、OneDrive 等",
                        icon = AppIcons.Add, mutedIcon = true, onClick = { addSource(setOf(AddKind.STORAGE)) },
                    )
                }
                storage.forEachIndexed { i, s -> serverRow(i, s) }
            }
        }

        CollapsibleSection("browse.mediaservers", "媒体服务器", actionLabel = "添加", onAction = { addSource(setOf(AddKind.MEDIA_SERVER)) }, count = mediaServers.size) {
            SurfaceCard {
                if (mediaServers.isEmpty()) {
                    ListRow(
                        title = "添加媒体服务器",
                        subtitle = "Plex、Emby、Jellyfin：海报墙、继续观看，进度同步回服务器",
                        icon = AppIcons.Add, mutedIcon = true, onClick = { addSource(setOf(AddKind.MEDIA_SERVER)) },
                    )
                }
                mediaServers.forEachIndexed { i, s -> serverRow(i, s) }
            }
        }

        CollapsibleSection("browse.streams", "在线内容", count = 4) {
            SurfaceCard {
                ListRow("电视直播", "添加 M3U / TXT 直播源，按分组看电视直播", leading = { SourceBadgeIcon(SourceBadgeKind.LIVE) }, onClick = openLive)
                HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ListRow("网络串流", "播放网络地址（http、rtsp、m3u8 等）", leading = { SourceBadgeIcon(SourceBadgeKind.STREAM) }, onClick = openStream)
                HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ListRow("网页", "在网页里看视频，边播边生成 AI 字幕", leading = { SourceBadgeIcon(SourceBadgeKind.WEB) }, onClick = openWeb)
                HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ListRow("Telegram", "看频道和群里的视频，边下边播", leading = { SourceBadgeIcon(SourceBadgeKind.TELEGRAM) }, onClick = openTelegram)
            }
        }

        // 阅读是完整的独立功能：用大卡片，和上面的服务列表区分开
        CollapsibleSection("browse.reading", "阅读", count = 2) {
            val context = androidx.compose.ui.platform.LocalContext.current
            remember { com.aisub.player.manga.MangaStore.init(context); com.aisub.player.book.BookStore.init(context) }
            val mangaRecent by com.aisub.player.manga.MangaStore.recent.collectAsStateWithLifecycle()
            val bookRecent by com.aisub.player.book.BookStore.recent.collectAsStateWithLifecycle()
            Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceCard(
                    AppIcons.MenuBook, "漫画",
                    if (mangaRecent.isEmpty()) "网页漫画、CBZ、PDF" else "继续阅读 ${mangaRecent.size} 部",
                    Modifier.weight(1f),
                    badge = { SourceBadgeIcon(SourceBadgeKind.MANGA, size = 36.dp) },
                    onClick = openManga,
                )
                SourceCard(
                    AppIcons.Description, "电子书",
                    if (bookRecent.isEmpty()) "TXT、EPUB、MOBI，可听书" else "继续阅读 ${bookRecent.size} 本",
                    Modifier.weight(1f),
                    badge = { SourceBadgeIcon(SourceBadgeKind.BOOK, size = 36.dp) },
                    onClick = openBooks,
                )
                if (LocalWideLayout.current) { Spacer(Modifier.weight(1f)); Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 「添加」分哪几类 */
internal enum class AddKind(val title: String) { STORAGE("网络存储"), MEDIA_SERVER("媒体服务器"), ONLINE("在线内容") }

/**
 * 右上角「+」弹出的液态玻璃悬浮菜单（对应参考截图中的彩色图标下拉列表）。
 */
@Composable
private fun AddSourceDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPick: (ServerType) -> Unit,
    onLive: () -> Unit,
    onStream: () -> Unit,
    onTelegram: () -> Unit,
) {
    AppMenu(expanded = expanded, onDismissRequest = onDismiss) {
        @Composable
        fun ServerMenuItem(type: ServerType) {
            DropdownMenuItem(
                leadingIcon = { ServerTypeIcon(type, size = 28.dp) },
                text = { Text(type.label, style = MaterialTheme.typography.bodyLarge) },
                onClick = { onPick(type) },
            )
        }
        ServerMenuItem(ServerType.WEBDAV)
        ServerMenuItem(ServerType.OPENLIST)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        ServerMenuItem(ServerType.EMBY)
        ServerMenuItem(ServerType.JELLYFIN)
        ServerMenuItem(ServerType.PLEX)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        ServerMenuItem(ServerType.PAN115)
        ServerMenuItem(ServerType.ALIYUN)
        ServerMenuItem(ServerType.BAIDU)
        ServerMenuItem(ServerType.PIKPAK)
        ServerMenuItem(ServerType.ONEDRIVE)
        ServerMenuItem(ServerType.DROPBOX)
        ServerMenuItem(ServerType.GDRIVE)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        DropdownMenuItem(
            leadingIcon = { SourceBadgeIcon(SourceBadgeKind.LIVE, size = 28.dp) },
            text = { Text("电视直播", style = MaterialTheme.typography.bodyLarge) },
            onClick = onLive,
        )
        DropdownMenuItem(
            leadingIcon = { SourceBadgeIcon(SourceBadgeKind.STREAM, size = 28.dp) },
            text = { Text("网络串流", style = MaterialTheme.typography.bodyLarge) },
            onClick = onStream,
        )
        DropdownMenuItem(
            leadingIcon = { SourceBadgeIcon(SourceBadgeKind.TELEGRAM, size = 28.dp) },
            text = { Text("Telegram", style = MaterialTheme.typography.bodyLarge) },
            onClick = onTelegram,
        )
    }
}

/** 添加来源：按类分组列出；kinds 为空时列全部（浏览页右上角的「添加」） */
@Composable
private fun AddSourceDialog(
    kinds: Set<AddKind>,
    onDismiss: () -> Unit,
    onPick: (ServerType) -> Unit,
    onLive: () -> Unit,
    onStream: () -> Unit,
    onTelegram: () -> Unit,
) {
    val storage = listOf(
        ServerType.WEBDAV to "NAS、Alist 等 WebDAV 服务",
        ServerType.OPENLIST to "OpenList / AList 站点",
        ServerType.PAN115 to "用 115 App 扫码登录",
        ServerType.ALIYUN to "用阿里云盘 App 扫码登录",
        ServerType.BAIDU to "自有 AppKey 扫码，或粘贴 refresh_token",
        ServerType.PIKPAK to "账号密码登录",
        ServerType.ONEDRIVE to "需要自己注册的应用 ID（免费）",
        ServerType.DROPBOX to "需要自己创建的 App key（免费）",
        ServerType.GDRIVE to "需要自己创建的 OAuth 客户端（免费）",
    )
    val media = listOf(
        ServerType.EMBY to "Emby 媒体服务器，账号密码登录",
        ServerType.JELLYFIN to "Jellyfin 媒体服务器，账号密码登录",
        ServerType.PLEX to "plex.tv 账号授权，或填服务器地址和令牌",
    )
    val show = kinds.ifEmpty { AddKind.entries.toSet() }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (show.size == 1) "添加" + show.first().title else "添加") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val header: @Composable (AddKind) -> Unit = { k ->
                    if (show.size > 1) Text(
                        k.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
                    )
                }
                if (AddKind.STORAGE in show) {
                    header(AddKind.STORAGE)
                    storage.forEach { (type, desc) ->
                        ListRow(type.label, desc, leading = { ServerTypeIcon(type) }, onClick = { onPick(type) })
                    }
                }
                if (AddKind.MEDIA_SERVER in show) {
                    header(AddKind.MEDIA_SERVER)
                    media.forEach { (type, desc) ->
                        ListRow(type.label, desc, leading = { ServerTypeIcon(type) }, onClick = { onPick(type) })
                    }
                }
                if (AddKind.ONLINE in show) {
                    header(AddKind.ONLINE)
                    ListRow("直播源", "M3U / TXT 播放列表网址或文件", leading = { SourceBadgeIcon(SourceBadgeKind.LIVE) }, onClick = onLive)
                    ListRow("网络串流", "http、rtsp、m3u8 等地址", leading = { SourceBadgeIcon(SourceBadgeKind.STREAM) }, onClick = onStream)
                    ListRow("Telegram 账号", "用手机号登录，看频道和群里的视频", leading = { SourceBadgeIcon(SourceBadgeKind.TELEGRAM) }, onClick = onTelegram)
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
    /** 继续阅读：本地 / 网盘漫画和电子书 */
    openReading: (MangaOpen) -> Unit,
    /** 继续阅读：网页漫画回到那一话的网页 */
    openWebManga: (String) -> Unit,
) {
    val mediaServers = state.servers.filter { it.type.isMediaServer }
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
    // 继续阅读：最近读过的漫画和电子书按时间排在一起
    val context = LocalContext.current
    remember { com.aisub.player.manga.MangaStore.init(context); com.aisub.player.book.BookStore.init(context) }
    val mangaRecent by com.aisub.player.manga.MangaStore.recent.collectAsStateWithLifecycle()
    val bookRecent by com.aisub.player.book.BookStore.recent.collectAsStateWithLifecycle()
    val reading = remember(mangaRecent, bookRecent) {
        (mangaRecent.map { r ->
            ReadingItem(
                r.key, r.title, if (r.total > 0) "第 ${r.page + 1} / ${r.total} 页" else "漫画", r.time, book = false,
                thumbnail = r.coverUrl?.let { com.aisub.player.manga.MangaPage(it, r.coverReferer, r.coverEntry) },
                // 网页漫画没有记下首图的旧记录不能从章节网址直接取文件，避免误下载网页源码。
                sourceUrl = r.location.takeUnless { r.web },
                open = { if (r.web) openWebManga(r.location) else mangaOpenOf(r)?.let(openReading) },
                remove = { com.aisub.player.manga.MangaStore.removeRecent(r.key) },
            )
        } + bookRecent.map { r ->
            val pct = if (r.total > 0) (r.item + 1) * 100 / r.total else 0
            ReadingItem(
                r.key, r.title, "读到 $pct%", r.time, book = true,
                thumbnail = r.coverEntry?.let { com.aisub.player.manga.MangaPage(r.location, entry = it) },
                sourceUrl = r.location,
                open = { openReading(MangaOpen.Archive(r.title, r.location)) },
                remove = { com.aisub.player.book.BookStore.remove(r.key) },
            )
        }).sortedByDescending { it.time }.take(12)
    }
    val recentVideos = remember(state.videos) { state.videos.sortedBy(SortOrder.RECENT).take(12) }
    val recentLive = remember(state.history) { state.history.filter { it.entry.isLive }.distinctBy { it.entry.uri }.take(5) }
    val recentTelegram = remember(state.history) {
        state.history.filter { com.aisub.player.telegram.Telegram.isTelegram(it.entry.uri) }.take(5)
    }
    // 删掉网页记录后要重新读一遍
    var webHistoryVersion by remember { mutableStateOf(0) }
    val recentWeb = remember(state.history, webHistoryVersion) { com.aisub.player.web.WebViewModel.savedPages(context, "history").take(6) }
    // 服务器删掉后它的收藏也不显示
    val favorites = remember(state.favoriteFolders, state.servers) {
        state.favoriteFolders.filter { f -> f.serverId == null || state.servers.any { it.id == f.serverId } }
    }
    LazyColumn(state = lib.listState("home"), contentPadding = PaddingValues(bottom = 104.dp)) {
        if (resume.isNotEmpty()) {
            item { SectionHeader("继续观看") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(resume, key = { it.entry.uri }) { h ->
                        ResumeTile(h, settings, onRemove = { lib.removeHistory(h.entry.uri) }) { play(listOf(h.entry), 0) }
                    }
                }
            }
        }
        if (recentSongs.isNotEmpty()) {
            item { SectionHeader("继续收听") }
            item {
                val songs = recentSongs.map { it.entry }
                LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(recentSongs, key = { _, h -> h.entry.uri }) { i, h ->
                        SongTile(h.entry, h.positionMs, onRemove = { lib.removeHistory(h.entry.uri) }) { play(songs, i) }
                    }
                }
            }
        }
        if (reading.isNotEmpty()) {
            item { SectionHeader("继续阅读") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(reading, key = { (if (it.book) "b:" else "m:") + it.key }) { r -> ReadingTile(r) }
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item { SectionHeader("收藏的文件夹") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(favorites, key = { it.key }) { f ->
                        val srv = f.serverId?.let { id -> state.servers.firstOrNull { it.id == id } }
                        val source = srv?.name ?: "本机"
                        FavoriteFolderTile(
                            f, source, srv,
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
        if (recentWeb.isNotEmpty() || recentLive.isNotEmpty() || recentTelegram.isNotEmpty()) {
            item { SectionHeader("最近浏览") }
            if (recentWeb.isNotEmpty()) item {
                SurfaceCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    recentWeb.take(4).forEachIndexed { index, page ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        ListRow(page.title.ifBlank { android.net.Uri.parse(page.url).host.orEmpty() }, android.net.Uri.parse(page.url).host,
                            leading = { SourceBadgeIcon(SourceBadgeKind.WEB) },
                            trailing = {
                                RemoveRecentButton {
                                    com.aisub.player.web.WebViewModel.removeSavedPage(context, "history", page.url)
                                    webHistoryVersion++
                                }
                            },
                            onClick = { com.aisub.player.web.WebLaunch.pending = page.url; openWebManga(page.url) })
                    }
                }
            }
            if (recentLive.isNotEmpty()) item {
                SurfaceCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    recentLive.forEachIndexed { index, h ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        ListRow(h.entry.title, "最近看过的直播", leading = { SourceBadgeIcon(SourceBadgeKind.LIVE) },
                            trailing = { RemoveRecentButton { lib.removeHistory(h.entry.uri) } },
                            onClick = { play(listOf(h.entry), 0) })
                    }
                }
            }
            if (recentTelegram.isNotEmpty()) item {
                SurfaceCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    recentTelegram.forEachIndexed { index, h ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        ListRow(h.entry.displayTitle, "最近看过的 Telegram 视频", leading = { SourceBadgeIcon(SourceBadgeKind.TELEGRAM) },
                            trailing = { RemoveRecentButton { lib.removeHistory(h.entry.uri) } },
                            onClick = { play(listOf(h.entry), 0) })
                    }
                }
            }
        }
        if (resume.isEmpty() && recentSongs.isEmpty() && reading.isEmpty() && favorites.isEmpty() && mediaServers.isEmpty() && recentVideos.isEmpty() && recentWeb.isEmpty() && recentLive.isEmpty() && recentTelegram.isEmpty()) item {
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
                    Button(onClick = addSource) { Text("添加 NAS / 媒体服务器") }
                }
            }
        }
    }
}

/** 首页「最近浏览」每一条右边的删除按钮 */
@Composable
private fun RemoveRecentButton(onRemove: () -> Unit) {
    IconButton(onClick = onRemove) {
        Icon(AppIcons.Close, "删除这条记录", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** 首页「继续观看 / 继续收听 / 继续阅读」卡片右上角的删除按钮 */
@Composable
private fun TileRemoveButton(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(CircleShape)
            .clickable(onClick = onRemove)
            .padding(5.dp)
            .size(22.dp)
            .background(Color(0x88000000), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.Close, "删除这条记录", tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

/** 首页「收藏的文件夹」卡片：文件夹名 + 来源（本机或服务器名），「⋮」里取消收藏 */
@Composable
private fun FavoriteFolderTile(folder: FavoriteFolder, source: String, server: Server? = null, onRemove: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .width(200.dp)
            .tvFocusRing(MaterialTheme.shapes.medium, scale = true)
            .liquidGlass(
                shape = MaterialTheme.shapes.medium,
                style = GlassStyle.Card,
                tint = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.76f),
                enableBackdrop = false,
            )
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (server != null) ServerSourceIcon(server, size = 28.dp)
        else Icon(AppIcons.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(folder.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(source, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OverflowMenu(listOf(MenuAction("取消收藏", onClick = onRemove)), compact = true)
    }
}

/** 歌曲卡片（首页继续收听、我的 → 最近听过的音乐）：方形封面 + 歌名；positionMs 为没听完时的进度 */
@Composable
private fun SongTile(entry: MediaEntry, positionMs: Long = 0, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(Modifier.width(110.dp).tvFocusRing(MaterialTheme.shapes.medium, scale = true).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium)) {
            MediaThumbnail(entry, Modifier.fillMaxSize())
            if (onRemove != null) TileRemoveButton(onRemove, Modifier.align(Alignment.TopEnd))
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

/** 首页「继续阅读」的一项（漫画或电子书） */
private data class ReadingItem(
    val key: String, val title: String, val progress: String, val time: Long, val book: Boolean,
    val thumbnail: com.aisub.player.manga.MangaPage? = null, val sourceUrl: String? = null,
    val open: () -> Unit, val remove: () -> Unit,
)

/** 「继续阅读」卡片：优先用漫画首页或电子书插图；还没有读过的旧记录才显示类型图标。 */
@Composable
private fun ReadingTile(r: ReadingItem) {
    Column(Modifier.width(110.dp).tvFocusRing(MaterialTheme.shapes.medium, scale = true).clip(MaterialTheme.shapes.medium).clickable(onClick = r.open)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            ReadingThumbnail(r.thumbnail, r.sourceUrl, r.book, Modifier.fillMaxSize())
            TileRemoveButton(r.remove, Modifier.align(Alignment.TopEnd))
        }
        Text(
            r.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp),
        )
        Text(
            (if (r.book) "电子书 · " else "漫画 · ") + r.progress, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun ReadingThumbnail(page: com.aisub.player.manga.MangaPage?, sourceUrl: String?, book: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(page?.let { com.aisub.player.manga.MangaImages.cachedThumb(it) }, page, sourceUrl, book) {
        val cover = page ?: sourceUrl?.let { url ->
            if (book) com.aisub.player.book.Ebooks.coverPage(context, url)
            else runCatching { com.aisub.player.manga.MangaArchive.pages(context, url).firstOrNull() }.getOrNull()
        }
        if (cover != null && value == null) value = com.aisub.player.manga.MangaImages.thumb(context, cover)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        val thumb = image
        if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(if (book) AppIcons.Description else AppIcons.MenuBook, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
    }
}

/** 「继续观看」卡片：横版画面（封面图，没有用播放时截的画面）+ 进度条 + 名字 */
@Composable
private fun ResumeTile(item: HistoryItem, settings: com.aisub.player.AppSettings, onRemove: () -> Unit, onClick: () -> Unit) {
    val context = LocalContext.current
    // 关掉了识别（总开关或这个视频单独关）：不用网上的封面，直接用视频画面
    val online = remember(item.entry.uri, settings.metaOnline, com.aisub.player.meta.MetaRepository.disabledVersion) {
        com.aisub.player.meta.MetaRepository.onlineAllowed(context, settings, item.entry.uri)
    }
    val cover by produceState(
        if (online) com.aisub.player.meta.VideoCovers.saved(context, item.entry.uri)?.ifEmpty { null } else null,
        item.entry.uri, online,
    ) {
        if (value == null && online) value = com.aisub.player.meta.VideoCovers.find(context, settings, item.entry)
    }
    var coverFailed by remember(cover) { mutableStateOf(false) }
    Column(Modifier.width(180.dp).tvFocusRing(MaterialTheme.shapes.medium, scale = true).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)) {
            if (cover != null && !coverFailed) {
                NetImage(cover, Modifier.fillMaxSize(), maxSize = 480, onError = { coverFailed = true })
            } else {
                MediaThumbnail(item.entry, Modifier.fillMaxSize())
            }
            TileRemoveButton(onRemove, Modifier.align(Alignment.TopEnd))
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
            SurfaceCard { ListRow(server.name, "${server.type.label} · 点「进入」浏览媒体库", leading = { ServerSourceIcon(server) }, onClick = onOpen) }
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
    onRemoveHistory: (String) -> Unit,
    onPlayMusic: (List<MediaEntry>, Int) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onPlayPlaylist: (Playlist) -> Unit,
    onNewPlaylist: () -> Unit,
    onPlaylistFromHistory: () -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onHiddenFolders: () -> Unit,
    onRefreshStats: () -> Unit,
    openFavorite: (FavoriteFolder) -> Unit,
    removeFavorite: (FavoriteFolder) -> Unit,
    openWebBookmarks: () -> Unit,
    openLiveFavorites: () -> Unit,
    openManga: () -> Unit,
    openBooks: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            onRefreshStats()
        }
    }
    // 「稍后观看」单独放最上面，不和普通播放列表混在一起
    val watchLater = state.playlists.firstOrNull { it.name == com.aisub.player.media.WATCH_LATER_PLAYLIST }
    val favoriteVideos = state.playlists.firstOrNull { it.name == com.aisub.player.media.FAVORITE_VIDEOS_PLAYLIST }
    val likedMusic = state.playlists.firstOrNull { it.name == com.aisub.player.media.LIKED_PLAYLIST }
    val playlists = state.playlists.filter { it.name !in setOf(
        com.aisub.player.media.WATCH_LATER_PLAYLIST, com.aisub.player.media.FAVORITE_VIDEOS_PLAYLIST, com.aisub.player.media.LIKED_PLAYLIST,
    ) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 104.dp)) {
        val later = watchLater?.items.orEmpty()
        SectionHeader(
            "稍后观看" + if (later.isNotEmpty()) " ${later.size}" else "",
            actionLabel = if (later.isNotEmpty()) "全部" else null, onAction = { watchLater?.let(onOpenPlaylist) },
        )
        if (later.isEmpty()) {
            Text(
                "在本机或网盘视频的「⋮」菜单里选「稍后观看」，想看的先存在这里", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                itemsIndexed(later.take(10), key = { _, e -> e.uri }) { i, e ->
                    Box(Modifier.width(170.dp)) { VideoCard(e, emptyList()) { onPlayMusic(later, i) } }
                }
            }
        }

        // ---- 我的收藏：各处收藏的东西集中在这里，点进去到各自的地方 ----
        SectionHeader("我的收藏")
        val favorites = state.favoriteFolders.filter { f -> f.serverId == null || state.servers.any { it.id == f.serverId } }
        if (favorites.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                items(favorites, key = { it.key }) { f ->
                    val srv = f.serverId?.let { id -> state.servers.firstOrNull { it.id == id } }
                    val source = srv?.name ?: "本机"
                    FavoriteFolderTile(f, source, srv, onRemove = { removeFavorite(f) }) { openFavorite(f) }
                }
            }
        }
        remember { com.aisub.player.manga.MangaStore.init(context); com.aisub.player.book.BookStore.init(context) }
        val mangaRecent by com.aisub.player.manga.MangaStore.recent.collectAsStateWithLifecycle()
        val bookRecent by com.aisub.player.book.BookStore.recent.collectAsStateWithLifecycle()
        // 网页收藏、收藏的频道存在各自的地方；回到这一页时重新读
        val bookmarks = remember(state.history) { com.aisub.player.web.WebViewModel.savedPages(context, "bookmarks") }
        val channels = remember(state.history) { com.aisub.player.live.LiveSources(context).favorites() }
        val favoriteManga = mangaRecent.filter { it.favorite }
        val favoriteBooks = bookRecent.filter { it.favorite }
        SurfaceCard {
            if (favorites.isEmpty()) {
                ListRow("收藏的文件夹", "文件夹右上角的 ☆ 或「⋮ → 收藏到首页」", AppIcons.Folder, mutedIcon = true)
                HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            ListRow(
                "收藏的视频", if (favoriteVideos?.items.isNullOrEmpty()) "视频右侧「⋮ → 收藏视频」" else "${favoriteVideos!!.items.size} 个",
                AppIcons.Movie, mutedIcon = favoriteVideos?.items.isNullOrEmpty(), onClick = favoriteVideos?.let { { onOpenPlaylist(it) } },
            )
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListRow(
                "我喜欢的音乐", if (likedMusic?.items.isNullOrEmpty()) "听歌时点 ♡ 收藏" else "${likedMusic!!.items.size} 首",
                AppIcons.MusicNote, mutedIcon = likedMusic?.items.isNullOrEmpty(), onClick = likedMusic?.let { { onOpenPlaylist(it) } },
            )
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListRow(
                "网页收藏", if (bookmarks.isEmpty()) "网页里「⋮ → 收藏本页」" else "${bookmarks.size} 个网页",
                AppIcons.Web, mutedIcon = bookmarks.isEmpty(), onClick = if (bookmarks.isEmpty()) null else openWebBookmarks,
            )
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListRow(
                "收藏的频道", if (channels.isEmpty()) "直播频道右边的星标" else "${channels.size} 个频道",
                AppIcons.Live, mutedIcon = channels.isEmpty(), onClick = if (channels.isEmpty()) null else openLiveFavorites,
            )
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListRow("收藏的漫画", if (favoriteManga.isEmpty()) "在漫画阅读列表里点星标" else "${favoriteManga.size} 部", AppIcons.MenuBook, onClick = openManga)
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListRow("收藏的电子书", if (favoriteBooks.isEmpty()) "在电子书阅读列表里点星标" else "${favoriteBooks.size} 本", AppIcons.Description, onClick = openBooks)
        }

        SectionHeader("播放列表", actionLabel = "新建", onAction = onNewPlaylist)
        SurfaceCard {
            if (playlists.isEmpty()) {
                ListRow(
                    "还没有播放列表", "在视频或音频的「⋮」菜单里加入；或者用最近播放生成一个", AppIcons.QueueMusic, mutedIcon = true,
                    onClick = if (state.history.isNotEmpty()) onPlaylistFromHistory else onNewPlaylist,
                )
            }
            playlists.forEachIndexed { i, p ->
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
                    Box(Modifier.width(170.dp)) {
                        VideoCard(h.entry, listOf(MenuAction("删除记录") { onRemoveHistory(h.entry.uri) })) { onPlayHistory(h) }
                    }
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
                itemsIndexed(songs, key = { _, e -> e.uri }) { i, e ->
                    SongTile(e, onRemove = { onRemoveHistory(e.uri) }) { onPlayMusic(songs, i) }
                }
            }
        }

        SectionHeader("播放统计")
        fun statsTime(ms: Long) = if (ms <= 0) "0:00" else formatDuration(ms)
        SurfaceCard {
            ListRow("累计播放", "${statsTime(state.playbackStats.totalMs)} · ${state.playbackStats.starts} 次开始播放", AppIcons.PlayArrow)
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListRow("视频", statsTime(state.playbackStats.videoMs), AppIcons.Movie)
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListRow("音乐", statsTime(state.playbackStats.musicMs), AppIcons.MusicNote)
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListRow("直播", statsTime(state.playbackStats.liveMs), AppIcons.Live)
        }

        SectionHeader("管理")
        SurfaceCard {
            ListRow("下载管理", "网盘、WebDAV 下载的文件和进度（系统下载列表）", AppIcons.Download, onClick = {
                runCatching { context.startActivity(android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                    .onFailure { android.widget.Toast.makeText(context, "这台设备没有系统下载列表；文件在 Download 文件夹里", android.widget.Toast.LENGTH_LONG).show() }
            })
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListRow(
                "隐藏的文件夹",
                if (state.hiddenFolders.isEmpty()) "不想让孩子看到的文件夹可以藏起来" else "${state.hiddenFolders.size} 个" + if (state.hasHiddenPin) " · 有密码" else "",
                AppIcons.Lock, onClick = onHiddenFolders,
            )
            if (onLink != null) {
                HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ListRow("从链接下载", "下载视频网站的视频，自动准备字幕后播放（也可以在其他 App 里分享给译幕）", AppIcons.Download, onClick = onLink)
            }
        }

        SectionHeader("设置与帮助")
        SurfaceCard {
            ListRow("设置", "语音识别、字幕翻译、播放、朗读", AppIcons.Settings, onClick = onSettings)
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
