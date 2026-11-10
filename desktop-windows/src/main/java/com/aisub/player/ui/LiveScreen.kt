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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.aisub.player.live.EpgRepository
import com.aisub.player.live.LiveSource
import com.aisub.player.live.LiveState
import com.aisub.player.live.LiveViewModel
import com.aisub.player.media.MediaEntry
import kotlinx.coroutines.delay

/**
 * 直播：添加 M3U / TXT 直播源，按分组浏览、搜索频道，点频道播放（播放页里可以切台）。
 */
@Composable
fun LiveScreen(onBack: () -> Unit, play: (List<MediaEntry>, Int) -> Unit) {
    val vm: LiveViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LiveSource?>(null) }
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    DetailScaffold(
        "直播", onBack,
        actions = {
            IconButton(onClick = { adding = true }) { Icon(AppIcons.Add, "添加直播源") }
            state.selected?.let { src ->
                IconButton(onClick = vm::refresh, enabled = !state.loading) { Icon(AppIcons.Refresh, "刷新频道") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(AppIcons.MoreVert, "更多") }
                    AppMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("编辑「${src.name}」") }, onClick = { menu = false; editing = src })
                        // 手机里的文件别人打不开，只分享网址
                        if (src.url.startsWith("http://") || src.url.startsWith("https://")) {
                            DropdownMenuItem(text = { Text("分享直播源地址") }, onClick = {
                                menu = false
                                shareText(context, "${src.name}\n${src.url}", src.name)
                            })
                        }
                        DropdownMenuItem(text = { Text("删除这个直播源") }, onClick = { menu = false; vm.remove(src) })
                    }
                }
            }
        },
    ) {
        if (state.sources.isEmpty() && state.favorites.isEmpty()) {
            EmptyState(
                "还没有直播源\n支持 M3U / M3U8 播放列表和「频道名,地址」TXT 格式",
                buttonText = "添加直播源", icon = AppIcons.Live,
            ) { adding = true }
        } else {
            Column(Modifier.fillMaxSize()) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                OutlinedTextField(
                    state.query, vm::setQuery, singleLine = true,
                    placeholder = { Text("搜索频道（共 ${state.channels.size} 个）", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(AppIcons.Search, null) },
                    // 直播源收在搜索框右边的下拉菜单里，不再单独占一行
                    trailingIcon = if (state.sources.isEmpty()) null else {
                        {
                            var sourceMenu by remember { mutableStateOf(false) }
                            Box {
                                Row(
                                    Modifier.clip(RoundedCornerShape(50)).clickable { sourceMenu = true }
                                        .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        state.selected?.name ?: "直播源", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.widthIn(max = 110.dp),
                                    )
                                    Icon(AppIcons.ArrowDropDown, "切换直播源", tint = MaterialTheme.colorScheme.primary)
                                }
                                AppMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                                    state.sources.forEach { s ->
                                        val chosen = state.selected?.id == s.id
                                        DropdownMenuItem(
                                            text = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            leadingIcon = {
                                                if (chosen) Icon(AppIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
                                                else Spacer(Modifier.size(24.dp))
                                            },
                                            onClick = { sourceMenu = false; if (!chosen) vm.select(s, refresh = false) },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("添加直播源…") },
                                        leadingIcon = { Icon(AppIcons.Add, null) },
                                        onClick = { sourceMenu = false; adding = true },
                                    )
                                }
                            }
                        }
                    },
                    colors = appFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                )
                // 分组（收藏排在最前）
                if (state.groups.isNotEmpty() || state.favorites.isNotEmpty()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.favorites.isNotEmpty()) item {
                            FilterChip(
                                state.group == LiveState.FAVORITES, { vm.setGroup(LiveState.FAVORITES) },
                                label = { Text("★ 收藏 ${state.favorites.size}") },
                            )
                        }
                        item { FilterChip(state.group == null, { vm.setGroup(null) }, label = { Text("全部") }) }
                        items(state.groups) { g -> FilterChip(state.group == g, { vm.setGroup(g) }, label = { Text(g, maxLines = 1) }) }
                    }
                }
                val visible = state.visible
                val epgVer = EpgRepository.version
                var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(60_000L)
                        nowMs = System.currentTimeMillis()
                    }
                }
                if (visible.isEmpty() && !state.loading) {
                    EmptyState(
                        when {
                            state.group == LiveState.FAVORITES -> "没有符合的收藏频道"
                            state.channels.isEmpty() -> "这个源还没有频道"
                            else -> "没有符合的频道"
                        },
                        icon = AppIcons.Live,
                    )
                }
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed(visible, key = { i, c -> "$i|${c.url}" }) { i, c ->
                        val curProg = remember(c.tvgId, c.tvgName, c.name, epgVer, nowMs) {
                            EpgRepository.currentProgram(c, nowMs)
                        }
                        LaunchedEffect(c.tvgId, c.tvgName, c.name, state.selected?.id) {
                            delay(250)
                            EpgRepository.ensureChannel(c, state.selected?.epgUrl.orEmpty())
                        }
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
                                if (curProg != null) {
                                    Text(
                                        "正在播放：${curProg.title}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    LinearProgressIndicator(
                                        progress = { curProg.progressAt(nowMs) },
                                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(2.dp).clip(RoundedCornerShape(1.dp)),
                                    )
                                } else if (c.group.isNotBlank() && (state.group == null || state.group == LiveState.FAVORITES)) {
                                    Text(c.group, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                            val fav = state.isFavorite(c)
                            IconButton(onClick = { vm.toggleFavorite(c) }) {
                                Icon(
                                    AppIcons.Star, if (fav) "取消收藏" else "收藏",
                                    tint = if (fav) Orange else MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) LiveSourceDialog(onDismiss = { adding = false }) { name, url, epgUrl -> vm.add(name, url, epgUrl); adding = false }
    editing?.let { s ->
        LiveSourceDialog(
            initial = s,
            detectedEpgUrl = state.headerEpgUrls.firstOrNull().orEmpty(),
            onDismiss = { editing = null },
        ) { name, url, epgUrl -> vm.edit(s, name, url, epgUrl); editing = null }
    }
}

/** 添加 / 编辑直播源：填网址，或选手机里的 M3U / TXT 文件。 */
@Composable
private fun LiveSourceDialog(
    initial: LiveSource? = null,
    detectedEpgUrl: String = "",
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, epgUrl: String) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var epgUrl by remember { mutableStateOf(initial?.epgUrl.orEmpty()) }
    val title = if (initial == null) "添加直播源" else "编辑直播源"
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // 记住读权限，之后刷新还能读这个文件
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            url = uri.toString()
            if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.substringBeforeLast('.').orEmpty()
        }
    }
    val valid = url.startsWith("http://") || url.startsWith("https://") || url.startsWith("content://")
    val epgPlaceholder = detectedEpgUrl.ifBlank { "https://…/epg.xml.gz（不填则自动识别）" }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it.trim() }, label = { Text("播放列表地址") }, placeholder = { Text("https://…/live.m3u") }, singleLine = true, modifier = Modifier.fillMaxWidth(), phoneInput = false)
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("或选择手机里的 M3U / TXT 文件") }
                OutlinedTextField(name, { name = it }, label = { Text("名称（可不填）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), phoneInput = false)
                OutlinedTextField(
                    epgUrl, { epgUrl = it.trim() },
                    label = { Text("节目单地址（可不填）") },
                    placeholder = { Text(epgPlaceholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), phoneInput = false,
                )
                // 电视：长地址用手机粘贴
                PhoneInputButton(
                    listOf(
                        PhoneField("url", "播放列表地址", url),
                        PhoneField("name", "名称（可不填）", name),
                        PhoneField("epgUrl", "节目单地址（可不填）", epgUrl),
                    ),
                    title = title,
                ) { v ->
                    url = v["url"]?.trim() ?: url
                    name = v["name"] ?: name
                    epgUrl = v["epgUrl"]?.trim() ?: epgUrl
                }
                Text(
                    "支持 M3U / M3U8 播放列表（含 x-tvg-url 节目单、tvg-logo、group-title 分组、回看 catchup），以及「分组,#genre#」「频道名,地址」TXT 格式（自动匹配在线节目单）",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, url, epgUrl) }, enabled = valid) { Text(if (initial == null) "添加" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
