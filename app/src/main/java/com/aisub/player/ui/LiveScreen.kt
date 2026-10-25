package com.aisub.player.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aisub.player.live.LiveSource
import com.aisub.player.live.LiveViewModel
import com.aisub.player.media.MediaEntry

/**
 * 直播：添加 M3U / TXT 直播源，按分组浏览、搜索频道，点频道播放（播放页里可以切台）。
 */
@Composable
fun LiveScreen(onBack: () -> Unit, play: (List<MediaEntry>, Int) -> Unit) {
    val vm: LiveViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LiveSource?>(null) }
    var menu by remember { mutableStateOf(false) }

    DetailScaffold(
        "直播", onBack,
        actions = {
            IconButton(onClick = { adding = true }) { Icon(AppIcons.Add, "添加直播源") }
            state.selected?.let { src ->
                IconButton(onClick = vm::refresh, enabled = !state.loading) { Icon(AppIcons.Refresh, "刷新频道") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(AppIcons.MoreVert, "更多") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("重命名「${src.name}」") }, onClick = { menu = false; renaming = src })
                        DropdownMenuItem(text = { Text("删除这个直播源") }, onClick = { menu = false; vm.remove(src) })
                    }
                }
            }
        },
    ) {
        if (state.sources.isEmpty()) {
            EmptyState(
                "还没有直播源\n支持 M3U / M3U8 播放列表和「频道名,地址」TXT 格式",
                buttonText = "添加直播源", icon = AppIcons.Live,
            ) { adding = true }
        } else {
            Column(Modifier.fillMaxSize()) {
                // 直播源
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.sources, key = { it.id }) { s ->
                        FilterChip(state.selected?.id == s.id, { vm.select(s, refresh = false) }, label = { Text(s.name, maxLines = 1) })
                    }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                OutlinedTextField(
                    state.query, vm::setQuery, singleLine = true,
                    placeholder = { Text("搜索频道（共 ${state.channels.size} 个）") },
                    leadingIcon = { Icon(AppIcons.Search, null) },
                    colors = appFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
                // 分组
                if (state.groups.isNotEmpty()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(state.group == null, { vm.setGroup(null) }, label = { Text("全部") }) }
                        items(state.groups) { g -> FilterChip(state.group == g, { vm.setGroup(g) }, label = { Text(g, maxLines = 1) }) }
                    }
                }
                val visible = state.visible
                if (visible.isEmpty() && !state.loading) {
                    EmptyState(if (state.channels.isEmpty()) "这个源还没有频道" else "没有符合的频道", icon = AppIcons.Live)
                }
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed(visible, key = { i, c -> "$i|${c.url}" }) { i, c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { play(vm.playlist(), i) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NetImage(
                                c.logo.ifEmpty { null },
                                Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Fit, maxSize = 200, fallback = AppIcons.Live,
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (c.group.isNotBlank() && state.group == null) {
                                    Text(c.group, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) AddLiveSourceDialog(onDismiss = { adding = false }) { name, url -> vm.add(name, url); adding = false }
    renaming?.let { s ->
        TextInputDialog("重命名直播源", "名称", initial = s.name, confirm = "保存", onDismiss = { renaming = null }) { vm.rename(s, it); renaming = null }
    }
}

/** 添加直播源：填网址，或选手机里的 M3U / TXT 文件。 */
@Composable
private fun AddLiveSourceDialog(onDismiss: () -> Unit, onAdd: (name: String, url: String) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // 记住读权限，之后刷新还能读这个文件
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            url = uri.toString()
            if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.substringBeforeLast('.').orEmpty()
        }
    }
    val valid = url.startsWith("http://") || url.startsWith("https://") || url.startsWith("content://")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加直播源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it.trim() }, label = { Text("播放列表地址") }, placeholder = { Text("https://…/live.m3u") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("或选择手机里的 M3U / TXT 文件") }
                OutlinedTextField(name, { name = it }, label = { Text("名称（可不填）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "支持 M3U / M3U8 播放列表（含 tvg-logo、group-title 分组、User-Agent / Referer），以及「分组,#genre#」「频道名,地址」TXT 格式",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, url) }, enabled = valid) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
