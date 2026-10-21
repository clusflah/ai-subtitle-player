package com.aisub.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisub.player.LibraryViewModel
import com.aisub.player.MainViewModel
import com.aisub.player.media.LocalBrowser
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.formatDuration
import com.aisub.player.remote.RemoteItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
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
    DetailScaffold(title, onBack) {
        if (folders.isEmpty() && files.isEmpty()) {
            EmptyState("这个文件夹里没有媒体文件", icon = AppIcons.Folder)
            return@DetailScaffold
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(folders, key = { "d:" + it.path }) { f ->
                FolderRow(f.name, subtitle = { CountLine(f.folderCount, f.fileCount) }) { openFolder(f.path, f.name) }
            }
            itemsIndexed(files, key = { _, e -> e.uri }) { i, e -> MediaRow(e, emptyList()) { play(files, i) } }
        }
    }
}

/** 网络目录（WebDAV / OpenList）。 */
@Composable
fun RemoteFolderScreen(
    lib: LibraryViewModel,
    serverId: String,
    path: String,
    onBack: () -> Unit,
    openFolder: (String) -> Unit,
    play: (List<MediaEntry>, Int) -> Unit,
) {
    val server = lib.server(serverId)
    var items by remember { mutableStateOf<List<RemoteItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

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

    val title = if (path == "/" || path.isEmpty()) server?.name.orEmpty() else path.trimEnd('/').substringAfterLast('/')
    DetailScaffold(
        title, onBack,
        actions = { IconButton(onClick = { reload++ }) { Icon(AppIcons.Refresh, "刷新") } },
    ) {
        val list = items
        when {
            server == null -> EmptyState("服务器不存在", icon = AppIcons.Cloud)
            error != null -> EmptyState("加载失败：$error", "重试", icon = AppIcons.Cloud) { reload++ }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val dirs = list.filter { it.isDir }
                val media = list.filter { !it.isDir && it.playUrl != null && MediaEntry.isMediaName(it.name) }
                    .map { MediaEntry(it.playUrl!!, it.name, isVideo = MediaEntry.isVideoName(it.name)) }
                if (dirs.isEmpty() && media.isEmpty()) {
                    EmptyState("这里没有文件夹或媒体文件", icon = AppIcons.Folder)
                } else {
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(dirs, key = { "d:" + it.path }) { d -> FolderRow(d.name) { openFolder(d.path) } }
                        itemsIndexed(media, key = { _, e -> e.uri }) { i, e -> MediaRow(e, emptyList()) { play(media, i) } }
                    }
                }
            }
        }
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
                MediaRow(e, listOf(MenuAction("从列表移除") { lib.removeFromPlaylist(id, e.uri) })) { play(playlist.items, i) }
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
                MediaRow(
                    h.entry.copy(title = if (progress.isEmpty()) h.entry.title else "${h.entry.title}\n看到 $progress"),
                    emptyList(),
                ) { play(listOf(h.entry), 0) }
            }
        }
    }
}
