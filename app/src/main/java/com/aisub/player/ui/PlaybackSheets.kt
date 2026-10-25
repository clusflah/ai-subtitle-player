package com.aisub.player.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.aisub.player.MainViewModel
import com.aisub.player.PlaybackFeatures.Companion.formatMs
import com.aisub.player.media.Bookmark
import com.aisub.player.media.SkipKind
import com.aisub.player.media.SkipSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

// ---------------- 章节与片段 ----------------

/** 章节列表 + 均分的片段预览图，点一下跳过去。 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun NavigatorSheet(vm: MainViewModel, positionMs: Long, onSeek: (Long) -> Unit) {
    val extras by vm.extras.state.collectAsStateWithLifecycle()
    val segments = remember { vm.extras.segmentPositions() }
    LaunchedEffect(Unit) { vm.extras.loadSegments() }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxHeight(0.85f),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { SheetTitle("章节") }
        if (extras.chapters.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("这个视频没有章节（MKV、DVD / 蓝光常带章节）", color = Muted, fontSize = 13.sp)
            }
        }
        extras.chapters.forEachIndexed { i, c ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                val next = extras.chapters.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
                val active = positionMs in c.startMs until next
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (active) Color(0x33F28C00) else Color.Transparent)
                        .clickable { onSeek(c.startMs) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(formatMs(c.startMs), color = if (active) Orange else Muted, fontSize = 13.sp, modifier = Modifier.width(72.dp))
                    Text(c.title.ifEmpty { "第 ${i + 1} 章" }, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { SheetTitle("片段") }
        if (segments.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("视频时长还不知道，开始播放后再试", color = Muted, fontSize = 13.sp) }
        }
        items(segments) { pos ->
            Thumb(extras.segmentThumbs[pos]?.asImageBitmap(), formatMs(pos)) { onSeek(pos) }
        }
    }
}

// ---------------- 书签与关键帧 ----------------

@OptIn(ExperimentalFoundationApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun BookmarksSheet(vm: MainViewModel, onAdd: () -> Unit, onSeek: (Long) -> Unit, onRename: (Bookmark) -> Unit) {
    val extras by vm.extras.state.collectAsStateWithLifecycle()
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxHeight(0.85f),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SheetTitle("书签与关键帧", Modifier.weight(1f))
                TextButton(onClick = onAdd) { Text("添加当前画面", color = Orange) }
                TextButton(onClick = vm.extras::exportBookmarks, enabled = extras.bookmarks.isNotEmpty()) { Text("全部存到相册", color = Orange) }
            }
        }
        if (extras.bookmarks.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("暂停后点「书签」或这里的「添加当前画面」，会记下位置和画面；点开可跳转，长按改名、删除", color = Muted, fontSize = 13.sp)
            }
        }
        items(extras.bookmarks, key = { it.id }) { b ->
            var menu by remember { mutableStateOf(false) }
            val image by produceState<ImageBitmap?>(null, b.image) {
                value = b.image?.let { path -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() } }
            }
            Box {
                Thumb(image, formatMs(b.positionMs) + (if (b.note.isNotEmpty()) "  ${b.note}" else ""), onLongClick = { menu = true }) { onSeek(b.positionMs) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("跳到这里") }, onClick = { menu = false; onSeek(b.positionMs) })
                    DropdownMenuItem(text = { Text("改备注") }, onClick = { menu = false; onRename(b) })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; vm.extras.removeBookmark(b) })
                }
            }
        }
    }
}

// ---------------- 片头片尾与跳过区间 ----------------

/**
 * @param markStart 正在标记的区间起点；null 表示没在标记
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SkipSheet(
    vm: MainViewModel,
    positionMs: Long,
    markStart: Long?,
    onMarkStart: () -> Unit,
    onMarkEnd: (SkipKind) -> Unit,
    onCancelMark: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val extras by vm.extras.state.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val series = vm.extras.canMarkSeries()
    Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        SheetTitle("片头片尾与跳过")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自动跳过", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                Text("关掉后播到这些区间只显示「跳过」按钮", color = Muted, fontSize = 12.sp)
            }
            Switch(state.settings.autoSkip, { vm.updateSettings(state.settings.copy(autoSkip = it)) })
        }
        Spacer(Modifier.height(8.dp))
        if (extras.skipRanges.isEmpty()) Text("还没有记录。可以用下面的按钮标记，或者自动识别。", color = Muted, fontSize = 13.sp)
        extras.skipRanges.forEach { r ->
            Row(Modifier.fillMaxWidth().clickable { onSeek(r.startMs) }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${r.kind.label}  ${formatMs(r.startMs)} – ${formatMs(r.endMs)}", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                    Text(r.source.label + if (r.series) " · 整部剧" else " · 仅本集", color = Muted, fontSize = 12.sp)
                }
                if (r.source != SkipSource.CHAPTER) TextButton(onClick = { vm.extras.removeRange(r) }) { Text("删除", color = Color(0xFFFF6B5E)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (markStart == null) {
            Text("标记：播放到区间开头点「标记起点」，到结尾再点一次选类型", color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onMarkStart) { Text("在 ${formatMs(positionMs)} 标记起点", color = Orange) }
            }
        } else {
            Text("起点 ${formatMs(markStart)}，终点 ${formatMs(positionMs)}。这段是：", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onMarkEnd(SkipKind.INTRO) }) { Text("片头" + if (series) "（整部剧）" else "", color = Orange) }
                TextButton(onClick = { onMarkEnd(SkipKind.OUTRO) }) { Text("片尾" + if (series) "（整部剧）" else "", color = Orange) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onMarkEnd(SkipKind.CUSTOM) }) { Text("跳过区间（仅本集）", color = Orange) }
                TextButton(onClick = onCancelMark) { Text("取消", color = Muted) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自动识别片头片尾", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                Text(
                    extras.detecting ?: "和播放列表里同一部剧的另一集比对声音，找出两集都有的片段；MKV 章节名里有 OP / ED 时直接用章节",
                    color = Muted, fontSize = 12.sp,
                )
            }
            if (extras.detecting != null) CircularProgressIndicator(Modifier.size(20.dp), color = Orange, strokeWidth = 2.dp)
            else TextButton(onClick = vm.extras::detectNow) { Text("开始", color = Orange) }
        }
    }
}

// ---------------- 共用 ----------------

@Composable
private fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = modifier.padding(vertical = 8.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumb(image: ImageBitmap?, label: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (image != null) Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            else Icon(AppIcons.Movie, null, tint = MaterialTheme.colorScheme.outlineVariant)
        }
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
    }
}
