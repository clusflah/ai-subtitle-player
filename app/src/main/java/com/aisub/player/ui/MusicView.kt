package com.aisub.player.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aisub.player.music.Lyrics
import com.aisub.player.music.MusicState
import com.aisub.player.music.SongCandidate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class MusicAction { SEARCH_LYRICS, IMPORT_LYRICS, LYRICS_EARLIER, LYRICS_LATER, SEARCH_COVER }

/**
 * 播放音乐时的画面：封面、歌名歌手、跟着播放滚动的歌词（点一句跳过去）。
 * 点空白处显示 / 隐藏控制栏。
 */
@Composable
fun MusicView(
    music: MusicState,
    positionMs: Long,
    topPadding: Dp,
    bottomPadding: Dp,
    onTap: () -> Unit,
    onSeek: (Long) -> Unit,
    onAction: (MusicAction) -> Unit,
    /** 在封面上左右滑：true 为下一首；null 表示不能切 */
    onSwipe: ((Boolean) -> Unit)? = null,
) {
    val tags = music.tags ?: return
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }) {
        // 背景：模糊的封面压暗。缩成很小的图再放大就是模糊效果（Modifier.blur 要 Android 12）
        music.cover?.let { bmp ->
            val tiny = remember(bmp) { android.graphics.Bitmap.createScaledBitmap(bmp, 24, 24, true).asImageBitmap() }
            Image(
                tiny, null, contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                modifier = Modifier.fillMaxSize().alpha(0.45f),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66000000), Color(0xCC000000)))))
        }
        val header: @Composable (Modifier) -> Unit = { mod ->
            Column(mod, horizontalAlignment = Alignment.CenterHorizontally) {
                Cover(music, Modifier.fillMaxWidth().aspectRatio(1f), onSwipe)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tags.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        listOfNotNull(tags.artist, tags.album).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                            Text(it, color = Color(0xFFB8B8B8), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    MusicMenu(music, onAction)
                }
            }
        }
        if (landscape) {
            Row(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = topPadding, bottom = bottomPadding, start = 32.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                header(Modifier.widthIn(max = 260.dp).weight(0.4f))
                Spacer(Modifier.size(24.dp))
                LyricsPane(music, positionMs, onSeek, Modifier.weight(0.6f).fillMaxSize())
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = topPadding, bottom = bottomPadding, start = 24.dp, end = 12.dp)) {
                // 有歌词时封面小一些，给歌词留地方
                val coverWidth = if (music.lyrics != null) 0.55f else 0.85f
                header(Modifier.fillMaxWidth(coverWidth).align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(16.dp))
                LyricsPane(music, positionMs, onSeek, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Cover(music: MusicState, modifier: Modifier, onSwipe: ((Boolean) -> Unit)?) {
    val threshold = with(LocalDensity.current) { 60.dp.toPx() }
    // 拖动时封面跟着手指走一点，松手超过阈值就切歌
    var dragX by remember { mutableStateOf(0f) }
    val swipe = if (onSwipe == null) Modifier else Modifier.pointerInput(onSwipe) {
        detectHorizontalDragGestures(
            onDragEnd = {
                if (abs(dragX) > threshold) onSwipe(dragX < 0)
                dragX = 0f
            },
            onDragCancel = { dragX = 0f },
        ) { change, amount ->
            change.consume()
            dragX += amount
        }
    }
    Box(
        modifier.then(swipe).graphicsLayer { translationX = dragX * 0.4f }
            .clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2A2A)),
        contentAlignment = Alignment.Center,
    ) {
        val cover = music.cover
        if (cover != null) Image(cover.asImageBitmap(), "封面", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(AppIcons.MusicNote, null, tint = Color(0xFF777777), modifier = Modifier.size(72.dp))
    }
}

@Composable
private fun MusicMenu(music: MusicState, onAction: (MusicAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(AppIcons.MoreVert, "歌词与封面", tint = Color.White) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            val item = { label: String, action: MusicAction ->
                @Composable { DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onAction(action) }) }
            }
            item("搜索歌词", MusicAction.SEARCH_LYRICS)()
            item("导入歌词文件（LRC）", MusicAction.IMPORT_LYRICS)()
            if (music.lyrics?.synced == true) {
                item("歌词提前 0.5 秒", MusicAction.LYRICS_EARLIER)()
                item("歌词延后 0.5 秒", MusicAction.LYRICS_LATER)()
            }
            HorizontalDivider()
            item("更换封面", MusicAction.SEARCH_COVER)()
        }
    }
}

@Composable
private fun LyricsPane(music: MusicState, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier) {
    val lyrics = music.lyrics
    if (lyrics == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(music.lyricsStatus ?: "", color = Color(0xFF9A9A9A), fontSize = 14.sp)
        }
        return
    }
    if (!lyrics.synced) {
        Column(modifier.verticalScroll(rememberScrollState())) {
            lyrics.lines.forEach { Text(it.text, color = Color(0xFFDDDDDD), fontSize = 16.sp, lineHeight = 26.sp) }
            Spacer(Modifier.height(24.dp))
        }
        return
    }
    SyncedLyrics(lyrics, positionMs, onSeek, modifier)
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier) {
    val index = lyrics.indexAt(positionMs)
    val listState = rememberLazyListState()
    // 手动滑动后 4 秒内不自动滚
    var touchedAt by remember { mutableLongStateOf(0L) }
    val dragging = listState.isScrollInProgress
    LaunchedEffect(dragging) { if (dragging) touchedAt = System.currentTimeMillis() }
    var viewportPx by remember { mutableStateOf(0) }
    LaunchedEffect(index, lyrics) {
        if (index < 0) return@LaunchedEffect
        val wait = 4000 - (System.currentTimeMillis() - touchedAt)
        if (wait > 0) delay(wait)
        // 当前行放在可见区域上方三分之一处
        listState.animateScrollToItem(index, -viewportPx / 3)
    }
    LazyColumn(
        modifier.onSizeChanged { viewportPx = it.height },
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        itemsIndexed(lyrics.lines) { i, line ->
            val active = i == index
            Text(
                line.text.ifEmpty { "♪" },
                color = if (active) Orange else Color(0xB3FFFFFF),
                fontSize = if (active) 19.sp else 16.sp,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                lineHeight = 26.sp,
                modifier = Modifier.fillMaxWidth()
                    .clickable(remember { MutableInteractionSource() }, indication = null) { line.timeMs?.let(onSeek) }
                    .padding(vertical = 7.dp),
            )
        }
        item { Spacer(Modifier.height(160.dp)) }
    }
}

/**
 * 搜索歌词或封面：可以改歌名、歌手再搜；点一条结果使用。
 * covers 为 true 时只列有封面的结果。
 */
@Composable
fun MusicSearchDialog(
    covers: Boolean,
    initialTitle: String,
    initialArtist: String,
    search: suspend (String, String) -> List<SongCandidate>,
    onPick: suspend (SongCandidate) -> Boolean,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }
    var results by remember { mutableStateOf<List<SongCandidate>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val run = {
        scope.launch {
            busy = true
            message = null
            results = search(title.trim(), artist.trim())
            busy = false
            if (results.isNullOrEmpty()) message = "没有找到，试试只填歌名，或换个写法"
        }
    }
    LaunchedEffect(Unit) { if (title.isNotBlank()) run() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (covers) "更换封面" else "搜索歌词") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("歌名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(artist, { artist = it }, label = { Text("歌手（可不填）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                when {
                    busy -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }
                    message != null -> Text(message!!, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        itemsIndexed(results.orEmpty()) { _, c ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    scope.launch {
                                        busy = true
                                        val ok = onPick(c)
                                        busy = false
                                        if (ok) onDismiss() else message = if (covers) "封面下载失败" else "这条没有可用的歌词，换一条试试"
                                    }
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (covers) {
                                    NetImage(c.coverUrl, Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)), maxSize = 200, fallback = AppIcons.MusicNote)
                                    Spacer(Modifier.size(12.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(c.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(
                                            c.artist.ifEmpty { null }, c.album.ifEmpty { null },
                                            c.durationMs.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60000, it / 1000 % 60) },
                                        ).joinToString(" · "),
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    c.source + if (!covers && c.source == com.aisub.player.music.MusicLookup.LRCLIB) (if (c.synced) "\n滚动" else "\n纯文本") else "",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { run() }, enabled = !busy && title.isNotBlank()) { Text("搜索") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
