package com.aisub.player.ui

import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.aisub.player.MainViewModel
import com.aisub.player.media.MediaEntry
import com.aisub.player.music.LyricLine
import com.aisub.player.music.Lyrics
import com.aisub.player.music.MusicState
import com.aisub.player.music.RelatedSong
import com.aisub.player.music.RelatedSongs
import com.aisub.player.music.SongCandidate
import com.aisub.player.subtitle.Cue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 音乐播放页里要交给播放页处理的操作（对话框、选文件等） */
enum class MusicAction {
    EDIT_INFO, SEARCH_LYRICS, IMPORT_LYRICS, SEARCH_COVER,
    SLEEP, SPEED, EQUALIZER, INFO, TRACKS, ADD_TO_PLAYLIST, SETTINGS,
}

private val Dim = Color(0x99FFFFFF)
private val Faint = Color(0x66FFFFFF)
private val SheetColor = Color(0xFF2B2E2D)

/**
 * 播放音乐时的整页：上面「推荐 / 歌曲 / 歌词」三页左右滑，
 * 下面固定显示歌名、喜欢、菜单、进度条和播放按钮。背景是模糊的封面。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MusicView(
    vm: MainViewModel,
    player: Player,
    music: MusicState,
    current: MediaEntry?,
    /** AI 生成的字幕：没有歌词时当作歌词显示 */
    cues: List<Cue>,
    positionMs: Long,
    wantsPlay: Boolean,
    onBack: () -> Unit,
    onSeek: (Long) -> Unit,
    onAction: (MusicAction) -> Unit,
    onMessage: (String) -> Unit,
) {
    val tags = music.tags ?: return
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = 1) { 3 }
    var showQueue by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var liked by remember(current?.uri) { mutableStateOf(current?.let { vm.isLiked(it.uri) } == true) }
    val toggleLike = {
        liked = vm.toggleLiked()
        onMessage(if (liked) "已加入「${MainViewModel.LIKED_PLAYLIST}」" else "已从「${MainViewModel.LIKED_PLAYLIST}」移除")
    }
    // 没有歌词、但生成过 AI 字幕时，把字幕当歌词
    val lyrics = music.lyrics ?: remember(cues) {
        cues.takeIf { it.isNotEmpty() }?.let { c -> Lyrics(c.sortedBy { it.startMs }.map { LyricLine(it.startMs, it.text) }, synced = true, source = "AI 字幕") }
    }
    val translations = if (music.lyrics != null) music.translations else remember(cues) {
        cues.sortedBy { it.startMs }.map { it.translation }.takeIf { t -> t.any { it != null } }
    }
    // 竖屏停在歌词页时，一会儿不操作就淡出下方控件，让歌词占满；点一下屏幕再显示
    var touches by remember { mutableIntStateOf(0) }
    var controlsShown by remember { mutableStateOf(true) }
    LaunchedEffect(pager.currentPage, touches, landscape, showMenu, showQueue) {
        controlsShown = true
        if (pager.currentPage == 2 && !landscape && !showMenu && !showQueue) {
            delay(4_000)
            controlsShown = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF1E201F))
            .pointerInput(Unit) {
                // 只观察按下，不消费，歌词的点击、滑动照常
                awaitEachGesture {
                    awaitPointerEvent(PointerEventPass.Initial)
                    touches++
                }
            }
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Backdrop(music)
        val pages: @Composable (Modifier) -> Unit = { mod ->
            Column(mod) {
                TopTabs(pager.currentPage, onBack) { scope.launch { pager.animateScrollToPage(it) } }
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { page ->
                    when (page) {
                        0 -> RecommendPage(vm, music, current, onMessage)
                        1 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Vinyl(music, wantsPlay, Modifier.fillMaxWidth(if (landscape) 0.62f else 0.84f).widthIn(max = 420.dp))
                        }
                        else -> LyricsPage(vm, music, lyrics, translations, positionMs, onSeek, onAction, onMessage)
                    }
                }
            }
        }
        val controls: @Composable (Modifier) -> Unit = { mod ->
            Column(mod) {
                SongTitleRow(tags.title, listOfNotNull(tags.artist, tags.album).joinToString(" - "), liked, toggleLike) { showMenu = true }
                // 歌词页以外，在歌名下面显示正在唱的一句
                val line = lyrics?.takeIf { it.synced && pager.currentPage != 2 }?.let { l -> l.lines.getOrNull(l.indexAt(positionMs))?.text }
                if (line != null && !landscape) Text(
                    line.ifEmpty { "♪" }, color = Color(0xCCFFFFFF), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 14.dp),
                ) else Spacer(Modifier.height(14.dp))
                MusicSeekBar(positionMs, player.duration.takeIf { it > 0 } ?: tags.durationMs, onSeek)
                PlayControls(vm, player, wantsPlay, onMessage, onQueue = { showQueue = true })
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                pages(Modifier.weight(1f).fillMaxHeight())
                controls(Modifier.weight(1f).align(Alignment.CenterVertically).padding(bottom = 8.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                pages(Modifier.weight(1f).fillMaxWidth())
                AnimatedVisibility(
                    controlsShown,
                    enter = fadeIn(tween(250)) + expandVertically(tween(250)),
                    exit = fadeOut(tween(800)) + shrinkVertically(tween(800)),
                ) { controls(Modifier.fillMaxWidth().padding(bottom = 20.dp)) }
            }
        }
    }

    if (showQueue) ModalBottomSheet(
        onDismissRequest = { showQueue = false }, containerColor = SheetColor,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) { QueueSheet(vm, player, current, onMessage) }

    if (showMenu) ModalBottomSheet(
        onDismissRequest = { showMenu = false }, containerColor = SheetColor,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ActionSheet(music, current, liked, toggleLike) { action -> showMenu = false; onAction(action) }
    }
}

/** 背景：封面缩成很小再放大（就是模糊，Modifier.blur 要 Android 12），再压暗 */
@Composable
private fun Backdrop(music: MusicState) {
    val bmp = music.cover ?: return
    val tiny = remember(bmp) { android.graphics.Bitmap.createScaledBitmap(bmp, 16, 16, true).asImageBitmap() }
    Image(
        tiny, null, contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
        modifier = Modifier.fillMaxSize().alpha(0.7f),
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xB3101010), Color(0x80202020), Color(0x66303030)))))
}

@Composable
private fun TopTabs(page: Int, onBack: () -> Unit, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().height(56.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
            Icon(AppIcons.ExpandMore, "返回", tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            listOf("推荐", "歌曲", "歌词").forEachIndexed { i, label ->
                Text(
                    label, color = if (i == page) Color.White else Dim, fontSize = 18.sp,
                    fontWeight = if (i == page) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSelect(i) }.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// ---------------- 歌曲：转动的唱片 ----------------

@Composable
private fun Vinyl(music: MusicState, playing: Boolean, modifier: Modifier) {
    // 播放时慢慢转，暂停停在原处
    val angle = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        while (playing) {
            angle.animateTo(angle.value + 360f, tween(24_000, easing = LinearEasing))
            angle.snapTo(angle.value % 360f)
        }
    }
    Box(modifier.aspectRatio(1f).graphicsLayer { rotationZ = angle.value }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(Color(0xFF0E0E0E), r)
            drawCircle(Color(0x33FFFFFF), r - 1.dp.toPx(), style = Stroke(1.dp.toPx()))
            // 纹路
            var gr = r * 0.97f
            while (gr > r * 0.7f) {
                drawCircle(Color(0x14FFFFFF), gr, style = Stroke(0.6.dp.toPx()))
                gr -= 3.dp.toPx()
            }
            // 斜着的一道反光
            drawCircle(
                Brush.linearGradient(listOf(Color.Transparent, Color(0x1AFFFFFF), Color.Transparent), Offset(0f, 0f), Offset(size.width, size.height)),
                r,
            )
        }
        Box(
            Modifier.fillMaxSize(0.68f).clip(CircleShape).background(Color(0xFF2A2A2A)).border(3.dp, Color(0xFF050505), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val cover = music.cover
            if (cover != null) Image(cover.asImageBitmap(), "封面", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(AppIcons.MusicNote, null, tint = Color(0xFF777777), modifier = Modifier.size(72.dp))
        }
    }
}

// ---------------- 歌名、喜欢、菜单 ----------------

@Composable
private fun SongTitleRow(title: String, subtitle: String, liked: Boolean, onLike: () -> Unit, onMenu: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        IconButton(onClick = onLike) {
            Icon(if (liked) AppIcons.Favorite else AppIcons.FavoriteBorder, if (liked) "取消喜欢" else "喜欢", tint = if (liked) Color(0xFFFF5A6E) else Color.White, modifier = Modifier.size(26.dp))
        }
        IconButton(onClick = onMenu) { Icon(AppIcons.MoreVert, "更多", tint = Color.White) }
    }
}

// ---------------- 进度条 ----------------

@Composable
private fun MusicSeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val finish = { dragging?.let { onSeek((it * durationMs).toLong()) }; dragging = null }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Box(
            Modifier.fillMaxWidth().height(32.dp)
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(durationMs) {
                    detectTapGestures { o -> if (durationMs > 0) onSeek(((o.x / widthPx).coerceIn(0f, 1f) * durationMs).toLong()) }
                }
                .pointerInput(durationMs) {
                    if (durationMs <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = (o.x / widthPx).coerceIn(0f, 1f) },
                        onDragEnd = finish, onDragCancel = finish,
                    ) { change, _ -> change.consume(); dragging = (change.position.x / widthPx).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF)))
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xE6FFFFFF)))
            val thumb = if (dragging != null) 22.dp else 18.dp
            Box(
                Modifier.offset { IntOffset((fraction * widthPx - thumb.toPx() / 2).roundToInt(), 0) }
                    .size(thumb).clip(CircleShape).background(Color(0xFFE4E6EA)),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatClock(dragging?.let { (it * durationMs).toLong() } ?: positionMs), color = Faint, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(formatClock(durationMs), color = Faint, fontSize = 13.sp)
        }
    }
}

// ---------------- 播放按钮 ----------------

/** 播放顺序：顺序播放 → 列表循环 → 单曲循环 → 随机播放 */
private enum class PlayMode(val label: String, val icon: ImageVector) {
    ORDER("顺序播放", AppIcons.PlaylistPlay),
    LOOP("列表循环", AppIcons.Repeat),
    ONE("单曲循环", AppIcons.RepeatOne),
    SHUFFLE("随机播放", AppIcons.Shuffle);

    fun apply(player: Player) {
        player.shuffleModeEnabled = this == SHUFFLE
        player.repeatMode = when (this) {
            ORDER -> Player.REPEAT_MODE_OFF
            ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_ALL
        }
    }

    companion object {
        fun of(player: Player) = when {
            player.shuffleModeEnabled -> SHUFFLE
            player.repeatMode == Player.REPEAT_MODE_ONE -> ONE
            player.repeatMode == Player.REPEAT_MODE_ALL -> LOOP
            else -> ORDER
        }
    }
}

@Composable
private fun rememberPlayMode(player: Player): Pair<PlayMode, () -> PlayMode> {
    var mode by remember(player) { mutableStateOf(PlayMode.of(player)) }
    return mode to {
        mode = PlayMode.entries[(mode.ordinal + 1) % PlayMode.entries.size]
        mode.apply(player)
        mode
    }
}

@Composable
private fun PlayControls(vm: MainViewModel, player: Player, wantsPlay: Boolean, onMessage: (String) -> Unit, onQueue: () -> Unit) {
    val (mode, next) = rememberPlayMode(player)
    val canSkip = vm.queueSize > 1 && !vm.usingVlc
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onMessage(next().label) }) { Icon(mode.icon, mode.label, tint = Color.White, modifier = Modifier.size(28.dp)) }
        IconButton(onClick = { if (canSkip) vm.skipInQueue(false, restartFirst = true) else player.seekTo(0) }) {
            Icon(AppIcons.SkipPrevious, "上一首", tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Box(
            Modifier.size(68.dp).clip(CircleShape).border(2.5.dp, Color.White, CircleShape).clickable { vm.togglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (wantsPlay) AppIcons.Pause else AppIcons.PlayArrow, if (wantsPlay) "暂停" else "播放", tint = Color.White, modifier = Modifier.size(38.dp))
        }
        IconButton(onClick = { vm.skipInQueue(true) }, enabled = canSkip) {
            Icon(AppIcons.SkipNext, "下一首", tint = if (canSkip) Color.White else Faint, modifier = Modifier.size(34.dp))
        }
        IconButton(onClick = onQueue, enabled = !vm.usingVlc) {
            Icon(AppIcons.QueueMusic, "播放列表", tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

// ---------------- 推荐：同一专辑、同一歌手 ----------------

@Composable
private fun RecommendPage(vm: MainViewModel, music: MusicState, current: MediaEntry?, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val tags = music.tags
    val uri = current?.uri.orEmpty()
    val related by produceState<Pair<List<RelatedSong>, List<RelatedSong>>?>(null, uri, tags?.artist, tags?.album) {
        value = withContext(Dispatchers.IO) {
            RelatedSongs.sameAlbum(context, tags?.album, tags?.artist, uri) to
                RelatedSongs.sameArtist(context, tags?.artist, uri, excludeAlbum = tags?.album)
        }
    }
    val (album, artist) = related ?: (emptyList<RelatedSong>() to emptyList())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { SectionTitle(tags?.album?.let { "同一专辑《$it》" } ?: "同一专辑") }
        if (album.isEmpty()) item { EmptyHint(if (related == null) "正在查找…" else "暂无数据") }
        items(album, key = { "al" + it.entry.uri }) { s -> SongRow(s, vm, onMessage) }
        item { SectionTitle(tags?.artist?.let { "$it 的其他歌" } ?: "同一歌手") }
        if (artist.isEmpty()) item { EmptyHint(if (related == null) "正在查找…" else "暂无数据") }
        items(artist, key = { "ar" + it.entry.uri }) { s -> SongRow(s, vm, onMessage) }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp))

@Composable
private fun EmptyHint(text: String) =
    Text(text, color = Dim, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp))

@Composable
private fun SongRow(s: RelatedSong, vm: MainViewModel, onMessage: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { vm.playFromRecommend(s.entry) }.padding(start = 28.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaThumbnail(s.entry, Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(s.artist, s.album).filter { it.isNotBlank() }.joinToString(" - "), color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { vm.playFromRecommend(s.entry, playNow = false); onMessage("下一首播放") }) {
            Icon(AppIcons.PlaylistAdd, "下一首播放", tint = Dim)
        }
    }
}

// ---------------- 歌词 ----------------

@Composable
private fun LyricsPage(
    vm: MainViewModel,
    music: MusicState,
    lyrics: Lyrics?,
    translations: List<String?>?,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onAction: (MusicAction) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adjusting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                lyrics == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(music.lyricsStatus?.takeIf { it.startsWith("正在") } ?: "暂无歌词", color = Dim, fontSize = 17.sp)
                    TextButton(onClick = { onAction(MusicAction.SEARCH_LYRICS) }) { Text("搜索歌词", color = Color.White) }
                }
                !lyrics.synced -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    lyrics.lines.forEachIndexed { i, l ->
                        Text(l.text, color = Color(0xDDFFFFFF), fontSize = 17.sp, lineHeight = 28.sp, textAlign = TextAlign.Center)
                        translations?.getOrNull(i)?.let { Text(it, color = Dim, fontSize = 14.sp, textAlign = TextAlign.Center) }
                    }
                }
                else -> SyncedLyrics(lyrics, translations, positionMs, onSeek, Modifier.fillMaxSize())
            }
        }
        if (adjusting && lyrics?.synced == true && music.lyrics != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("提前 0.5 秒") { vm.music.shiftLyrics(-500) }
                Spacer(Modifier.width(10.dp))
                Pill("延后 0.5 秒") { vm.music.shiftLyrics(500) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { adjusting = false }) { Text("完成", color = Color.White) }
            }
        }
        Row(Modifier.padding(start = 28.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            var menu by remember { mutableStateOf(false) }
            Box {
                Text(
                    "LRC", color = Color(0xCCFFFFFF), fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, Dim, RoundedCornerShape(4.dp))
                        .clickable { menu = true }.padding(horizontal = 6.dp, vertical = 4.dp),
                )
                DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Color(0xFF2E2E2E)) {
                    val own = music.lyrics
                    MenuRow("调整歌词", AppIcons.SwapVert, enabled = own?.synced == true) { menu = false; adjusting = true }
                    when {
                        music.translating -> MenuRow("正在翻译…", AppIcons.Translate, enabled = false) {}
                        music.translations != null -> MenuRow("隐藏翻译", AppIcons.Translate) { menu = false; vm.music.clearTranslations() }
                        else -> MenuRow("生成翻译", AppIcons.Translate, enabled = own != null) {
                            menu = false
                            onMessage("正在翻译歌词…")
                            scope.launch { vm.music.translateLyrics()?.let(onMessage) }
                        }
                    }
                    MenuRow("切换歌词", AppIcons.Search) { menu = false; onAction(MusicAction.SEARCH_LYRICS) }
                    MenuRow("导入歌词文件", AppIcons.File) { menu = false; onAction(MusicAction.IMPORT_LYRICS) }
                }
            }
            Spacer(Modifier.width(16.dp))
            IconButton(onClick = {
                // 系统音量条
                (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
            }) { Icon(AppIcons.Volume, "音量", tint = Color(0xCCFFFFFF)) }
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) = Text(
    text, color = Color.White, fontSize = 13.sp,
    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
)

@Composable
private fun MenuRow(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) = DropdownMenuItem(
    text = { Text(text, fontSize = 17.sp, modifier = Modifier.widthIn(min = 150.dp)) },
    trailingIcon = { Icon(icon, null) },
    enabled = enabled, onClick = onClick,
)

@Composable
private fun SyncedLyrics(lyrics: Lyrics, translations: List<String?>?, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier) {
    val index = lyrics.indexAt(positionMs)
    val listState = rememberLazyListState()
    // 手动滑动后 4 秒内不自动滚
    var touchedAt by remember { mutableLongStateOf(0L) }
    val dragging = listState.isScrollInProgress
    LaunchedEffect(dragging) { if (dragging) touchedAt = System.currentTimeMillis() }
    var viewportPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(index, lyrics, viewportPx) {
        if (viewportPx == 0) return@LaunchedEffect
        val wait = 4000 - (System.currentTimeMillis() - touchedAt)
        if (wait > 0) delay(wait)
        // 当前行放在中间偏上（第 0 项是顶部留白）
        listState.animateScrollToItem(index.coerceAtLeast(0) + 1, -(viewportPx * 0.42f).toInt())
    }
    LazyColumn(
        modifier.onSizeChanged { viewportPx = it.height }
            // 上下边缘渐隐
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.12f to Color.Black, 0.85f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
        state = listState,
    ) {
        item { Spacer(Modifier.height(with(density) { (viewportPx * 0.42f).toDp() })) }
        itemsIndexed(lyrics.lines) { i, line ->
            val active = i == index
            Column(
                Modifier.fillMaxWidth()
                    .clickable(remember { MutableInteractionSource() }, indication = null) { line.timeMs?.let(onSeek) }
                    .padding(horizontal = 28.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    line.text.ifEmpty { "♪" },
                    color = if (active) Color.White else Color(0x80FFFFFF),
                    fontSize = if (active) 20.sp else 17.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    lineHeight = 28.sp, textAlign = TextAlign.Center,
                )
                translations?.getOrNull(i)?.let {
                    Text(it, color = if (active) Color(0xDDFFFFFF) else Color(0x66FFFFFF), fontSize = if (active) 15.sp else 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        item { Spacer(Modifier.height(with(density) { (viewportPx * 0.55f).toDp() })) }
    }
}

// ---------------- 播放列表 ----------------

@Composable
private fun QueueSheet(vm: MainViewModel, player: Player, current: MediaEntry?, onMessage: (String) -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val entries = remember(version, current?.uri) { vm.queueEntries }
    val currentIndex = entries.indexOfFirst { it.uri == current?.uri }
    val (mode, next) = rememberPlayMode(player)
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("播放列表", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("(${entries.size})", color = Dim, fontSize = 15.sp, modifier = Modifier.padding(start = 2.dp))
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable { onMessage(next().label) }.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(mode.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp))
            Text(mode.label, color = Color.White, fontSize = 15.sp)
        }
    }
    HorizontalDivider(color = Color(0x22FFFFFF))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
    LazyColumn(Modifier.fillMaxHeight(0.7f), state = listState) {
        itemsIndexed(entries, key = { i, e -> "$i:${e.uri}" }) { i, e ->
            val active = i == currentIndex
            Row(
                Modifier.fillMaxWidth().clickable { vm.playQueueIndex(i) }.padding(start = 24.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (active) {
                    Icon(AppIcons.Equalizer, "正在播放", tint = Orange, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    e.displayTitle, color = if (active) Orange else Color.White, fontSize = 17.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                )
                if (entries.size > 1) IconButton(onClick = { vm.removeQueueIndex(i); version++ }) {
                    Icon(AppIcons.Close, "从播放列表移除", tint = Dim)
                }
            }
        }
    }
}

// ---------------- 「⋮」菜单 ----------------

@Composable
private fun ActionSheet(music: MusicState, current: MediaEntry?, liked: Boolean, onLike: () -> Unit, onAction: (MusicAction) -> Unit) {
    val tags = music.tags ?: return
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x1FFFFFFF)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2A)), contentAlignment = Alignment.Center) {
                music.cover?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    ?: Icon(AppIcons.MusicNote, null, tint = Color(0xFF777777))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tags.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(tags.artist, tags.album).joinToString(" - ").ifEmpty { current?.folder.orEmpty() },
                    color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onLike) {
                Icon(if (liked) AppIcons.Favorite else AppIcons.FavoriteBorder, if (liked) "取消喜欢" else "喜欢", tint = if (liked) Color(0xFFFF5A6E) else Color.White)
            }
        }
        ActionGroup(
            listOf(
                Triple(MusicAction.ADD_TO_PLAYLIST, AppIcons.PlaylistAdd, "添加到"),
                Triple(MusicAction.SLEEP, AppIcons.Timer, "定时停止"),
                Triple(MusicAction.SPEED, AppIcons.Speed, "播放速度"),
                Triple(MusicAction.EQUALIZER, AppIcons.Equalizer, "均衡器"),
            ),
            onAction,
        )
        ActionGroup(
            listOf(
                Triple(MusicAction.SEARCH_LYRICS, AppIcons.Search, "搜索歌词"),
                Triple(MusicAction.IMPORT_LYRICS, AppIcons.File, "导入歌词"),
                Triple(MusicAction.SEARCH_COVER, AppIcons.Image, "更换封面"),
                Triple(MusicAction.EDIT_INFO, AppIcons.Edit, "编辑信息"),
            ),
            onAction,
        )
        ActionGroup(
            listOf(
                Triple(MusicAction.TRACKS, AppIcons.Subtitles, "音轨字幕"),
                Triple(MusicAction.INFO, AppIcons.Info, "歌曲信息"),
                Triple(MusicAction.SETTINGS, AppIcons.Settings, "设置"),
            ),
            onAction,
        )
    }
}

@Composable
private fun ActionGroup(items: List<Triple<MusicAction, ImageVector, String>>, onAction: (MusicAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x1FFFFFFF)).padding(vertical = 12.dp),
    ) {
        items.forEach { (action, icon, label) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onAction(action) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(50.dp).clip(CircleShape).background(Color(0x26FFFFFF)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        // 不满四个时留空，图标和上面一行对齐
        repeat(4 - items.size) { Spacer(Modifier.weight(1f)) }
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

/**
 * 编辑歌曲信息：改歌名、歌手、专辑，或者「在线匹配」从搜索结果里挑一条自动填好（可顺便换封面）。
 * 改动只存在译幕里，不改音乐文件。
 */
@Composable
fun SongInfoDialog(
    music: MusicState,
    search: suspend (String, String) -> List<SongCandidate>,
    onSave: suspend (title: String, artist: String, album: String, coverFrom: SongCandidate?) -> Unit,
    onReset: suspend () -> Unit,
    onDismiss: () -> Unit,
) {
    val tags = music.tags ?: return
    var title by remember { mutableStateOf(tags.title) }
    var artist by remember { mutableStateOf(tags.artist.orEmpty()) }
    var album by remember { mutableStateOf(tags.album.orEmpty()) }
    var results by remember { mutableStateOf<List<SongCandidate>?>(null) }
    var picked by remember { mutableStateOf<SongCandidate?>(null) }
    var useCover by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑歌曲信息") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("歌名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(artist, { artist = it }, label = { Text("歌手") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(album, { album = it }, label = { Text("专辑") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (picked?.coverUrl != null) {
                    Row(
                        Modifier.fillMaxWidth().clickable { useCover = !useCover }.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(useCover, { useCover = it })
                        Text("同时使用这首的封面", fontSize = 14.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !busy && title.isNotBlank(),
                        onClick = {
                            scope.launch {
                                busy = true
                                message = null
                                results = search(title.trim(), artist.trim())
                                busy = false
                                if (results.isNullOrEmpty()) message = "没有找到，试试只填歌名，或换个写法"
                            }
                        },
                    ) { Text("在线匹配") }
                    Text("按上面的歌名、歌手搜索，点一条自动填好", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    busy -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }
                    message != null -> Text(message!!, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    results != null -> LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        itemsIndexed(results.orEmpty()) { _, c ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (c == picked) Color(0x33F28C00) else Color.Transparent)
                                    .clickable {
                                        picked = c
                                        title = c.title
                                        artist = c.artist
                                        if (c.album.isNotBlank()) album = c.album
                                    }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NetImage(c.coverUrl, Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)), maxSize = 160, fallback = AppIcons.MusicNote)
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(
                                            c.artist.ifEmpty { null }, c.album.ifEmpty { null },
                                            c.durationMs.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60000, it / 1000 % 60) },
                                        ).joinToString(" · "),
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(c.source, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Text(
                    "只改译幕里显示的信息（以及用它查歌词、封面），不会修改音乐文件",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && title.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    onSave(title, artist, album, picked?.takeIf { useCover })
                    busy = false
                    onDismiss()
                }
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (music.edited) TextButton(onClick = { scope.launch { onReset(); onDismiss() } }) { Text("恢复文件里的") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
