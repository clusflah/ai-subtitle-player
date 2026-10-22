package com.aisub.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisub.player.LibraryViewModel
import com.aisub.player.MainViewModel
import com.aisub.player.media.LocalBrowser
import com.aisub.player.media.Downloads
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.formatSize
import com.aisub.player.media.formatDuration
import com.aisub.player.remote.RemoteItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, "返回") } },
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { actions() },
            )
        },
    ) { padding -> Box(Modifier.padding(padding).fillMaxSize()) { content() } }
}

/** 本地目录：子文件夹 + 媒体文件。 */
@Composable
fun LocalFolderScreen(
    lib: LibraryViewModel,
    path: String,
    title: String,
    onBack: () -> Unit,
    openFolder: (String, String) -> Unit,
    play: (List<MediaEntry>, Int) -> Unit,
) {
    val state by lib.state.collectAsStateWithLifecycle()
    val all = remember(state.videos, state.audios) { state.videos + state.audios }
    val (folders, files) = remember(all, path) { LocalBrowser.children(all, path) }
    var addTo by remember { mutableStateOf<List<MediaEntry>?>(null) }
    val fm = rememberFileManager(lib) { addTo = it }
    addTo?.let { entries ->
        AddToPlaylistDialog(
            playlists = state.playlists,
            onDismiss = { addTo = null },
            onPick = { p -> entries.forEach { lib.addToPlaylist(p.id, it) }; addTo = null },
            onCreate = { lib.createPlaylistWith(it, entries); addTo = null },
        )
    }
    // 长按文件进入多选
    var selected by remember(path) { mutableStateOf(emptySet<String>()) }
    val selectedEntries = files.filter { it.uri in selected }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    val toggle: (MediaEntry) -> Unit = { e -> selected = if (e.uri in selected) selected - e.uri else selected + e.uri }
    val inFolder: (String) -> List<MediaEntry> = { p -> all.filter { it.folder == p || it.folder.startsWith("$p/") } }

    Box(Modifier.fillMaxSize()) {
        DetailScaffold(
            title, onBack,
            actions = {
                if (files.isNotEmpty()) {
                    OverflowMenu(
                        listOf(
                            MenuAction("全部播放") { play(files, 0) },
                            MenuAction("选择…") { selected = setOf(files.first().uri) },
                        ) + if (path.isNotEmpty()) fm.folderMenu(path, inFolder(path)) { play(inFolder(path), 0) }.drop(1) else emptyList(),
                    )
                }
            },
        ) {
            if (folders.isEmpty() && files.isEmpty()) {
                EmptyState("这个文件夹里没有媒体文件", icon = AppIcons.Folder)
                return@DetailScaffold
            }
            val selecting = selected.isNotEmpty()
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(folders, key = { "d:" + it.path }) { f ->
                    val entries = inFolder(f.path)
                    FolderRow(
                        f.name, subtitle = countText(f.folderCount, f.fileCount),
                        menu = if (selecting) emptyList() else fm.folderMenu(f.path, entries) { play(entries, 0) },
                    ) { openFolder(f.path, f.name) }
                }
                itemsIndexed(files, key = { _, e -> e.uri }) { i, e ->
                    MediaRow(e, fm.menu(e), selected = if (selecting) e.uri in selected else null, onLongClick = { toggle(e) }) {
                        if (selecting) toggle(e) else play(files, i)
                    }
                }
            }
        }
        if (selected.isNotEmpty()) {
            SelectionBar(
                count = selected.size,
                onClose = { selected = emptySet() },
                onSelectAll = { selected = files.map { it.uri }.toSet() },
                onShare = { fm.share(selectedEntries) },
                onAddToPlaylist = { addTo = selectedEntries },
                onMove = { fm.move(selectedEntries); selected = emptySet() },
                onCopy = { fm.copy(selectedEntries); selected = emptySet() },
                onDelete = { fm.delete(selectedEntries); selected = emptySet() },
            )
        }
    }
}

/** 网络目录（WebDAV / OpenList）。 */
@Composable
fun RemoteFolderScreen(
    lib: LibraryViewModel,
    serverId: String,
    path: String,
    title: String,
    onBack: () -> Unit,
    openFolder: (RemoteItem) -> Unit,
    play: (List<MediaEntry>, Int) -> Unit,
    openDetail: (RemoteItem, List<MediaEntry>, Int) -> Unit,
    openImages: (List<RemoteItem>, Int) -> Unit,
) {
    val server = lib.server(serverId)
    val appContext = LocalContext.current.applicationContext
    val viewPrefs = remember { com.aisub.player.media.ViewPrefs(appContext) }
    var posterWall by remember { mutableStateOf(viewPrefs.grid("remote")) }
    val state by lib.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<RemoteItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<RemoteItem?>(null) }
    var deleting by remember { mutableStateOf<RemoteItem?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var addTo by remember { mutableStateOf<List<MediaEntry>?>(null) }
    var downloadPlan by remember { mutableStateOf<Downloads.Plan?>(null) }

    LaunchedEffect(serverId, path, reload) {
        if (server == null) return@LaunchedEffect
        items = null
        error = null
        try {
            items = lib.client(server).list(path)
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    /** 执行一个写操作：完成后提示并刷新目录。 */
    fun operate(done: String, op: suspend (com.aisub.player.remote.RemoteClient) -> Unit) {
        if (server == null || busy) return
        busy = true
        scope.launch {
            try {
                op(lib.client(server))
                Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
                reload++
            } catch (e: Exception) {
                Toast.makeText(context, "操作失败：${e.message ?: e}", Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    fun download(jobs: List<Downloads.Job>) {
        scope.launch {
            try {
                val n = Downloads.enqueue(context, jobs)
                Toast.makeText(context, "已加入下载 $n 个文件，保存到 Download/${Downloads.ROOT}，进度见通知栏", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "下载失败：${e.message ?: e}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 文件夹先统计文件数和大小，确认后再下载。 */
    fun downloadFolder(folder: RemoteItem) {
        if (server == null || busy) return
        busy = true
        scope.launch {
            try {
                downloadPlan = Downloads.planFolder(lib.client(server), folder)
            } catch (e: Exception) {
                Toast.makeText(context, "读取文件夹失败：${e.message ?: e}", Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    /** 同一目录里的媒体文件，作为详情页「播放」时的队列。 */
    fun showDetail(item: RemoteItem) {
        val mediaFiles = items.orEmpty().filter { !it.isDir && it.playUrl != null && MediaEntry.isMediaName(it.name) }
        val i = mediaFiles.indexOf(item)
        if (i >= 0) openDetail(item, mediaFiles.map { it.toMediaEntry() }, i) else openDetail(item, emptyList(), 0)
    }

    /** 点击：文件夹进入，图片看图，音频直接播放，视频和其他文件进详情页。 */
    fun open(item: RemoteItem) {
        val all = items.orEmpty()
        when {
            item.isDir -> openFolder(item)
            MediaEntry.isImageName(item.name) -> {
                val images = all.filter { !it.isDir && it.playUrl != null && MediaEntry.isImageName(it.name) }
                openImages(images, images.indexOf(item).coerceAtLeast(0))
            }
            MediaEntry.isAudioName(item.name) -> {
                val media = all.filter { !it.isDir && it.playUrl != null && MediaEntry.isMediaName(it.name) }
                play(media.map { it.toMediaEntry() }, media.indexOf(item).coerceAtLeast(0))
            }
            else -> showDetail(item)
        }
    }

    fun itemMenu(item: RemoteItem): List<MenuAction> = listOfNotNull(
        MenuAction("详情") { showDetail(item) }.takeIf { !item.isDir || item.meta != null },
        item.takeIf { !it.isDir && it.playUrl != null && MediaEntry.isMediaName(it.name) }?.toMediaEntry()
            ?.let { MenuAction("加入播放列表") { addTo = listOf(it) } },
        if (item.isDir) MenuAction("下载文件夹") { downloadFolder(item) }
        else MenuAction("下载") { download(listOf(Downloads.Job(item, ""))) },
        MenuAction("重命名") { renaming = item },
        MenuAction("删除") { deleting = item },
    )

    DetailScaffold(
        title, onBack,
        actions = {
            if (busy) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            if (items.orEmpty().any { it.meta?.posterUrl != null }) {
                IconButton(onClick = { posterWall = !posterWall; viewPrefs.setGrid("remote", posterWall) }) {
                    Icon(if (posterWall) AppIcons.ListIcon else AppIcons.GridView, if (posterWall) "列表" else "海报墙")
                }
            }
            IconButton(onClick = { reload++ }) { Icon(AppIcons.Refresh, "刷新") }
            OverflowMenu(
                listOf(
                    MenuAction("新建文件夹") { creatingFolder = true },
                    MenuAction("下载整个文件夹") { downloadFolder(RemoteItem(title, path, isDir = true)) },
                    MenuAction("查看下载") {
                        context.startActivity(android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
                    },
                ),
            )
        },
    ) {
        val list = items
        when {
            server == null -> EmptyState("服务器不存在", icon = AppIcons.Cloud)
            error != null -> EmptyState("加载失败：$error", "重试", icon = AppIcons.Cloud) { reload++ }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val shown = list.filter { it.isDir } + list.filter { !it.isDir && it.playUrl != null }
                when {
                    shown.isEmpty() -> EmptyState("这个文件夹是空的", icon = AppIcons.Folder)
                    posterWall && shown.any { it.meta?.posterUrl != null } -> PosterWall(shown, ::itemMenu, ::open)
                    else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(shown, key = { (if (it.isDir) "d:" else "f:") + it.path }) { item ->
                            when {
                                item.isDir -> FolderRow(item.name, menu = itemMenu(item)) { open(item) }
                                MediaEntry.isMediaName(item.name) -> MediaRow(item.toMediaEntry(), itemMenu(item)) { open(item) }
                                else -> FileRow(item, itemMenu(item)) { open(item) }
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { item ->
        val ext = if (item.isDir) "" else item.name.substringAfterLast('.', "")
        TextInputDialog(
            title = if (item.isDir) "重命名文件夹" else "重命名",
            label = if (ext.isEmpty()) "新名字" else "新名字（扩展名会保留）",
            initial = if (ext.isEmpty()) item.name else item.name.substringBeforeLast('.'),
            onDismiss = { renaming = null },
        ) { input ->
            renaming = null
            val name = input.trim().let { if (ext.isNotEmpty() && !it.endsWith(".$ext", ignoreCase = true)) "$it.$ext" else it }
            if (name != item.name) operate("已重命名") { it.rename(path, item, name) }
        }
    }
    deleting?.let { item ->
        val cloud = server?.type?.isCloud == true
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${item.name}」？") },
            text = {
                Text(
                    if (cloud) "会移到网盘的回收站，可以在网盘 App 里恢复。"
                    else "会直接从服务器上删除${if (item.isDir) "整个文件夹" else ""}，不能恢复。",
                )
            },
            confirmButton = {
                TextButton(onClick = { deleting = null; operate("已删除") { it.delete(path, listOf(item)) } }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    downloadPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { downloadPlan = null },
            title = { Text("下载 ${plan.jobs.size} 个文件？") },
            text = {
                Text(
                    buildString {
                        append("共 ${formatSize(plan.totalBytes)}，保存到 Download/${Downloads.ROOT}，保留原有的文件夹结构。")
                        if (plan.truncated) append("\n文件太多，只下载前 ${Downloads.MAX_FILES} 个。")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { downloadPlan = null; download(plan.jobs) }, enabled = plan.jobs.isNotEmpty()) { Text("下载") }
            },
            dismissButton = { TextButton(onClick = { downloadPlan = null }) { Text("取消") } },
        )
    }
    if (creatingFolder) {
        TextInputDialog(title = "新建文件夹", label = "文件夹名", onDismiss = { creatingFolder = false }) { name ->
            creatingFolder = false
            operate("已新建文件夹") { it.mkdir(path, name) }
        }
    }
    addTo?.let { entries ->
        AddToPlaylistDialog(
            playlists = state.playlists,
            onDismiss = { addTo = null },
            onPick = { p -> entries.forEach { lib.addToPlaylist(p.id, it) }; addTo = null },
            onCreate = { lib.createPlaylistWith(it, entries); addTo = null },
        )
    }
}

@Composable
fun PlaylistDetailScreen(
    lib: LibraryViewModel,
    id: String,
    onBack: () -> Unit,
    play: (List<MediaEntry>, Int) -> Unit,
) {
    val state by lib.state.collectAsStateWithLifecycle()
    val playlist = state.playlists.firstOrNull { it.id == id }
    var renaming by remember { mutableStateOf(false) }
    DetailScaffold(
        playlist?.name ?: "播放列表", onBack,
        actions = {
            if (playlist != null) {
                TextButton(onClick = { play(playlist.items, 0) }, enabled = playlist.items.isNotEmpty()) { Text("播放全部") }
                OverflowMenu(listOf(MenuAction("重命名") { renaming = true }))
            }
        },
    ) {
        if (playlist == null || playlist.items.isEmpty()) {
            EmptyState("播放列表是空的", icon = AppIcons.QueueMusic)
            return@DetailScaffold
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(playlist.items, key = { _, e -> e.uri }) { i, e ->
                MediaRow(
                    e,
                    listOf(
                        MenuAction("上移", enabled = i > 0) { lib.moveInPlaylist(id, e.uri, -1) },
                        MenuAction("下移", enabled = i < playlist.items.lastIndex) { lib.moveInPlaylist(id, e.uri, 1) },
                        MenuAction("从列表移除") { lib.removeFromPlaylist(id, e.uri) },
                    ),
                ) { play(playlist.items, i) }
            }
        }
    }
    if (renaming && playlist != null) {
        TextInputDialog(
            "重命名", "名称", initial = playlist.name,
            onDismiss = { renaming = false },
            onConfirm = { lib.renamePlaylist(id, it); renaming = false },
        )
    }
}

@Composable
fun HistoryScreen(lib: LibraryViewModel, onBack: () -> Unit, play: (List<MediaEntry>, Int) -> Unit) {
    val state by lib.state.collectAsStateWithLifecycle()
    DetailScaffold(
        "历史", onBack,
        actions = { TextButton(onClick = lib::clearHistory, enabled = state.history.isNotEmpty()) { Text("清空") } },
    ) {
        if (state.history.isEmpty()) {
            EmptyState("还没有播放记录", icon = AppIcons.History)
            return@DetailScaffold
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(state.history, key = { it.entry.uri }) { h ->
                val progress = formatDuration(h.positionMs)
                val ago = android.text.format.DateUtils.getRelativeTimeSpanString(h.playedAt)
                MediaRow(
                    h.entry, emptyList(),
                    subtitle = if (progress.isEmpty()) "$ago" else "看到 $progress · $ago",
                ) { play(listOf(h.entry), 0) }
            }
        }
    }
}
