package com.aisub.player.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisub.player.LibraryState
import com.aisub.player.LibraryViewModel
import com.aisub.player.MainViewModel
import com.aisub.player.media.LocalBrowser
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.MediaLibrary
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
    data object Settings : HomeDialog
    data object About : HomeDialog
    data object Stream : HomeDialog
    data object NewPlaylist : HomeDialog
    data class AddToPlaylist(val entry: MediaEntry) : HomeDialog
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
    val mainState by main.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.VIDEO) }
    var searching by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }

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
    val entryMenu: (MediaEntry) -> List<MenuAction> = { e ->
        listOf(MenuAction("加入播放列表") { dialog = HomeDialog.AddToPlaylist(e) })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = {
                    if (searching) {
                        TextField(
                            value = query, onValueChange = { query = it },
                            placeholder = { Text("搜索${tab.label}") }, singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("AI 字幕播放器", fontSize = 24.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                },
                actions = {
                    if (tab == Tab.VIDEO || tab == Tab.AUDIO) {
                        IconButton(onClick = {
                            searching = !searching
                            if (!searching) query = ""
                        }) { Icon(if (searching) AppIcons.Close else AppIcons.Search, "搜索") }
                        if (!searching) {
                            IconButton(
                                onClick = { state.history.firstOrNull()?.let { play(listOf(it.entry), 0) } },
                                enabled = state.history.isNotEmpty(),
                            ) { Icon(AppIcons.History, "继续播放") }
                        }
                    }
                    OverflowMenu(
                        listOf(
                            MenuAction("刷新媒体库") { lib.refreshMedia() },
                            MenuAction("打开文件…") { pickFile.launch(arrayOf("video/*", "audio/*")) },
                            MenuAction("设置") { dialog = HomeDialog.Settings },
                        )
                    )
                },
            )
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = {
                                tab = t
                                searching = false
                                query = ""
                            },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = Color.Transparent,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.VIDEO -> MediaTab(state, state.videos, query, isVideo = true, onRequestPermission, lib::refreshMedia, play, entryMenu)
                Tab.AUDIO -> MediaTab(state, state.audios, query, isVideo = false, onRequestPermission, lib::refreshMedia, play, entryMenu)
                Tab.BROWSE -> BrowseTab(
                    state = state,
                    onRequestPermission = onRequestPermission,
                    openLocal = { path, title -> nav.push(Screen.LocalFolder(path, title)) },
                    openRemote = { s -> nav.push(Screen.Remote(s.id, s.rootPath, s.name)) },
                    pickFile = { pickFile.launch(arrayOf("video/*", "audio/*")) },
                    editServer = { s, hint -> dialog = HomeDialog.EditServer(s, hint) },
                    deleteServer = { lib.deleteServer(it.id) },
                    cloudLogin = { t, s -> dialog = HomeDialog.CloudLogin(t, s) },
                    renameServer = { dialog = HomeDialog.RenameServer(it) },
                )
                Tab.PLAYLISTS -> PlaylistsTab(
                    state = state,
                    onOpen = { nav.push(Screen.PlaylistDetail(it.id)) },
                    onPlay = { p -> if (p.items.isNotEmpty()) play(p.items, 0) },
                    onNew = { dialog = HomeDialog.NewPlaylist },
                    onDelete = { lib.deletePlaylist(it.id) },
                )
                Tab.MORE -> MoreTab(
                    state = state,
                    onSettings = { dialog = HomeDialog.Settings },
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
        HomeDialog.Settings -> SettingsDialog(
            initial = mainState.settings,
            onDismiss = { dialog = null },
            onSave = { main.updateSettings(it); dialog = null },
            modelInstalled = main::isModelInstalled,
            onDeleteModel = main::deleteModel,
        )
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
        is HomeDialog.AddToPlaylist -> AddToPlaylistDialog(
            playlists = state.playlists,
            onDismiss = { dialog = null },
            onPick = { lib.addToPlaylist(it.id, d.entry); dialog = null },
            onCreate = { lib.createPlaylist(it, d.entry); dialog = null },
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

@Composable
private fun MediaTab(
    state: LibraryState,
    all: List<MediaEntry>,
    query: String,
    isVideo: Boolean,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    play: (List<MediaEntry>, Int) -> Unit,
    menu: (MediaEntry) -> List<MenuAction>,
) {
    if (!state.hasPermission) {
        EmptyState("需要媒体访问权限才能显示手机里的${if (isVideo) "视频" else "音频"}。", "授予权限", onButton = onRequestPermission)
        return
    }
    val list = if (query.isBlank()) all else all.filter { it.title.contains(query.trim(), ignoreCase = true) }
    if (list.isEmpty()) {
        if (query.isNotBlank()) EmptyState("没有找到「$query」", icon = AppIcons.Search)
        else EmptyState(
            "未找到媒体文件，请传输一些文件到您的设备，或刷新媒体库。", "刷新媒体库",
            icon = if (isVideo) AppIcons.Movie else AppIcons.MusicNote, onButton = onRefresh,
        )
        return
    }
    if (isVideo) {
        LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(8.dp)) {
            itemsIndexed(list, key = { _, e -> e.uri }) { i, e -> VideoCard(e, menu(e)) { play(list, i) } }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(list, key = { _, e -> e.uri }) { i, e -> MediaRow(e, menu(e)) { play(list, i) } }
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
    editServer: (Server, String?) -> Unit,
    deleteServer: (Server) -> Unit,
    cloudLogin: (ServerType, Server?) -> Unit,
    renameServer: (Server) -> Unit,
) {
    val all = remember(state.videos, state.audios) { state.videos + state.audios }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SectionHeader("收藏")
        TileRow {
            listOf(
                Triple("Download", AppIcons.Download, "Download"),
                Triple("Movies", AppIcons.Movie, "Movies"),
                Triple("Music", AppIcons.MusicNote, "Music"),
                Triple("DCIM", AppIcons.Folder, "DCIM"),
            ).forEach { (name, icon, path) ->
                val sum = LocalBrowser.summary(all, path)
                TileCard(icon, name, subtitle = { CountLine(sum.folderCount, sum.fileCount) }) {
                    if (state.hasPermission) openLocal(path, name) else onRequestPermission()
                }
            }
        }

        SectionHeader("存储设备")
        TileRow {
            val sum = LocalBrowser.summary(all, "")
            TileCard(AppIcons.Phone, "内部存储", subtitle = { CountLine(sum.folderCount, sum.fileCount) }) {
                if (state.hasPermission) openLocal("", "内部存储") else onRequestPermission()
            }
            TileCard(AppIcons.File, "选择文件…", subtitle = { Text("系统文件选择器", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }) {
                pickFile()
            }
        }

        SectionHeader("网络")
        TileRow {
            state.servers.filter { !it.type.isCloud }.forEach { s ->
                TileCard(
                    if (s.type == ServerType.OPENLIST) AppIcons.Cloud else AppIcons.Dns, s.name,
                    subtitle = { Text(s.type.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) },
                    menu = listOf(
                        MenuAction("编辑") { editServer(s, null) },
                        MenuAction("删除") { deleteServer(s) },
                    ),
                ) { openRemote(s) }
            }
            TileCard(AppIcons.Add, "WebDAV") { editServer(Server(type = ServerType.WEBDAV, name = "", baseUrl = ""), null) }
            TileCard(AppIcons.Add, "OpenList") {
                editServer(Server(type = ServerType.OPENLIST, name = "OpenList", baseUrl = ""), null)
            }
        }

        SectionHeader("网盘")
        TileRow {
            state.servers.filter { it.type.isCloud }.forEach { s ->
                TileCard(
                    AppIcons.Cloud, s.name,
                    subtitle = { Text(s.type.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) },
                    menu = listOf(
                        MenuAction("重新登录") { cloudLogin(s.type, s) },
                        MenuAction("重命名") { renameServer(s) },
                        MenuAction("删除") { deleteServer(s) },
                    ),
                ) { openRemote(s) }
            }
            listOf(ServerType.PAN115, ServerType.ALIYUN, ServerType.BAIDU, ServerType.PIKPAK).forEach { t ->
                TileCard(
                    AppIcons.Add, t.label,
                    subtitle = {
                        Text(
                            when (t) {
                                ServerType.PIKPAK -> "账号密码登录"
                                ServerType.BAIDU -> "AppKey / token"
                                else -> "扫码登录"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                        )
                    },
                ) { cloudLogin(t, null) }
            }
        }
    }
}

@Composable
private fun TileRow(content: @Composable () -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
    }
}

@Composable
private fun PlaylistsTab(
    state: LibraryState,
    onOpen: (com.aisub.player.media.Playlist) -> Unit,
    onPlay: (com.aisub.player.media.Playlist) -> Unit,
    onNew: () -> Unit,
    onDelete: (com.aisub.player.media.Playlist) -> Unit,
) {
    if (state.playlists.isEmpty()) {
        EmptyState("还没有播放列表。\n在视频或音频的「⋮」菜单里可以加入播放列表。", "新建播放列表", icon = AppIcons.QueueMusic, onButton = onNew)
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(state.playlists, key = { it.id }) { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    FolderRow(p.name, subtitle = {
                        Text("${p.items.size} 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }) { onOpen(p) }
                }
                OverflowMenu(
                    listOf(
                        MenuAction("播放全部") { onPlay(p) },
                        MenuAction("删除") { onDelete(p) },
                    )
                )
            }
        }
        item {
            OutlinedButton(onClick = onNew, shape = RoundedCornerShape(6.dp), modifier = Modifier.padding(16.dp)) {
                Text("新建播放列表", color = MaterialTheme.colorScheme.primary)
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
    onPlayHistory: (com.aisub.player.media.HistoryItem) -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigButton(AppIcons.Settings, "设置", onSettings, Modifier.weight(1f))
            BigButton(AppIcons.Info, "关于", onAbout, Modifier.weight(1f))
        }

        SectionHeader("串流")
        TileRow {
            TileCard(AppIcons.Add, "新建串流") { onStream() }
        }

        SectionHeader("历史", onMore = onHistory)
        if (state.history.isEmpty()) {
            Text("还没有播放记录", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 20.dp))
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(state.history.take(10), key = { it.entry.uri }) { h ->
                    Box(Modifier.width(180.dp)) { VideoCard(h.entry, emptyList()) { onPlayHistory(h) } }
                }
            }
        }
    }
}

@Composable
private fun BigButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(6.dp), modifier = modifier.height(52.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 17.sp)
    }
}
