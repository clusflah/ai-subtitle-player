package com.aisub.player.ui

import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.aisub.player.MainViewModel
import com.aisub.player.media.MediaEntry
import com.aisub.player.music.LyricLine
import com.aisub.player.music.Lyrics
import com.aisub.player.music.MusicState
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

/** 上方标签页：0 歌曲（唱片），1 歌词，2 乐库（仅音乐主页面提供） */
private const val LYRICS_PAGE = 1
private const val LIBRARY_PAGE = 2

/**
 * 播放音乐时的整页：上方「歌曲 / 歌词」（音乐主页含「乐库」）左右滑，
 * 下方固定显示歌名、喜欢、菜单、进度条和播放按钮。背景是模糊的封面。
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
    onBack: (() -> Unit)?,
    onSeek: (Long) -> Unit,
    onAction: (MusicAction) -> Unit,
    onMessage: (String) -> Unit,
    inHomeTab: Boolean = false,
    bottomOverlayPadding: androidx.compose.ui.unit.Dp = 0.dp,
    applyNavigationBarInset: Boolean = true,
    standbyTags: com.aisub.player.music.TrackTags? = null,
    standbyCover: android.graphics.Bitmap? = null,
    onIdlePlay: (() -> Unit)? = null,
    topTrailingContent: (@Composable () -> Unit)? = null,
    externalHoldVisible: Boolean = false,
    onBarsVisibleChange: ((Boolean) -> Unit)? = null,
    externalPager: androidx.compose.foundation.pager.PagerState? = null,
    libraryTabContent: (@Composable () -> Unit)? = null,
) {
    val activeMusic = music.tags != null
    val tags = music.tags ?: standbyTags ?: return
    val displayCover = music.cover ?: standbyCover
    val effectiveEntry = current?.takeIf { activeMusic && !it.isVideo && !it.isLive }
    val effectiveWantsPlay = activeMusic && wantsPlay
    val config = LocalConfiguration.current
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screenH = config.screenHeightDp.dp
    val scope = rememberCoroutineScope()
    val pageCount = if (libraryTabContent != null) 3 else 2
    val internalPager = rememberPagerState(initialPage = 0) { pageCount }
    val pager = externalPager ?: internalPager
    val onLyricsPage = pager.currentPage == LYRICS_PAGE
    val onLibraryPage = libraryTabContent != null && pager.currentPage == LIBRARY_PAGE
    var showQueue by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var liked by remember(effectiveEntry?.uri) { mutableStateOf(effectiveEntry?.let { vm.isLiked(it.uri) } == true) }
    val toggleLike = {
        if (effectiveEntry != null) {
            liked = vm.toggleLiked()
            onMessage(if (liked) "已加入「${MainViewModel.LIKED_PLAYLIST}」" else "已从「${MainViewModel.LIKED_PLAYLIST}」移除")
        } else {
            onMessage("开始播放歌曲后即可加入我喜欢的音乐")
        }
    }
    // 没有歌词、但生成过 AI 字幕时，把字幕当歌词
    val lyrics = if (!activeMusic) null else music.lyrics ?: remember(cues) {
        cues.takeIf { it.isNotEmpty() }?.let { c -> Lyrics(c.sortedBy { it.startMs }.map { LyricLine(it.startMs, it.text) }, synced = true, source = "AI 字幕") }
    }
    val translations = if (!activeMusic) null else if (music.lyrics != null) music.translations else remember(cues) {
        cues.sortedBy { it.startMs }.map { it.translation }.takeIf { t -> t.any { it != null } }
    }
    // 播放满 3 秒无操作时自动隐藏顶部栏与底部按钮行；暂停、拖动或移动鼠标/轻点屏幕立即恢复显示
    var touches by remember { mutableIntStateOf(0) }
    var sliderAdjusting by remember { mutableStateOf(false) }
    var seekDragging by remember { mutableStateOf(false) }
    var barsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(pager.currentPage, touches, effectiveWantsPlay, showMenu, showQueue, sliderAdjusting, seekDragging, externalHoldVisible, onLibraryPage) {
        if (onLibraryPage) {
            barsVisible = true
            return@LaunchedEffect
        }
        barsVisible = true
        if (effectiveWantsPlay && !showMenu && !showQueue && !sliderAdjusting && !seekDragging && !externalHoldVisible) {
            delay(3_000)
            barsVisible = false
        }
    }
    LaunchedEffect(barsVisible, onLibraryPage) {
        onBarsVisibleChange?.invoke(barsVisible || onLibraryPage)
    }
    val controlsPanelShown = when {
        onLibraryPage && !landscape -> false
        onLyricsPage && !landscape -> barsVisible
        else -> true
    }
    val wakeDistPx = with(LocalDensity.current) { 36.dp.toPx() }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF1E201F))
            .pointerInput(wakeDistPx) {
                var lastPos: Offset? = null
                var lastMoveMs = 0L
                var movedPx = 0f
                var lastBumpMs = 0L
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.type == androidx.compose.ui.input.pointer.PointerEventType.Move) {
                            val ch = ev.changes.firstOrNull() ?: continue
                            if (ch.pressed) {
                                lastPos = ch.position
                                movedPx = 0f
                                continue
                            }
                            val now = System.currentTimeMillis()
                            val prev = lastPos
                            lastPos = ch.position
                            if (prev == null || now - lastMoveMs > 600L) {
                                movedPx = 0f
                            } else {
                                movedPx += kotlin.math.hypot(ch.position.x - prev.x, ch.position.y - prev.y)
                            }
                            lastMoveMs = now
                            if (!barsVisible && movedPx >= wakeDistPx) {
                                touches++
                                lastBumpMs = now
                                movedPx = 0f
                            } else if (barsVisible && movedPx >= wakeDistPx && now - lastBumpMs >= 350L) {
                                touches++
                                lastBumpMs = now
                                movedPx = 0f
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                // 只观察、不消费，歌词的点击、滑动照常。轻点才算操作（重新显示控件）；
                // 拖着歌词滑动不算，免得一滑控件就冒出来
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var dragged = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
                        if (!change.pressed) break
                    }
                    if (!dragged) touches++
                }
            }
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Box(Modifier.fillMaxSize()) {
            Backdrop(displayCover)
        }
        val immersive = !barsVisible && !onLibraryPage
        val immersiveVinyl = immersive && !onLyricsPage
        val vinylWidthFraction by animateFloatAsState(
            targetValue = when {
                landscape && immersiveVinyl -> 0.78f
                landscape -> 0.70f
                immersiveVinyl -> 0.88f
                else -> 0.82f
            },
            animationSpec = tween(360),
            label = "vinylWidthFraction",
        )
        val vinylMaxHeightFraction by animateFloatAsState(
            targetValue = when {
                landscape -> 0.86f
                immersiveVinyl -> 0.78f
                else -> 0.85f
            },
            animationSpec = tween(360),
            label = "vinylMaxHeightFraction",
        )
        val vinylMaxDiameter by animateDpAsState(
            targetValue = if (immersiveVinyl) 450.dp else 410.dp,
            animationSpec = tween(360),
            label = "vinylMaxDiameter",
        )
        val vinylBiasY by animateFloatAsState(
            targetValue = if (immersiveVinyl && !landscape) 0.22f else 0f,
            animationSpec = tween(360),
            label = "vinylBiasY",
        )
        val controlsBottomPad by animateDpAsState(
            targetValue = when {
                landscape -> 8.dp
                immersiveVinyl -> ((screenH - 480.dp) * 0.30f).coerceIn(28.dp, 124.dp)
                inHomeTab -> 10.dp
                else -> 20.dp
            },
            animationSpec = tween(360),
            label = "controlsBottomPad",
        )
        val landscapeOuterPad by animateDpAsState(
            targetValue = if (landscape && immersive) 20.dp else 0.dp,
            animationSpec = tween(360),
            label = "landscapeOuterPad",
        )
        val lyricTopPad by animateDpAsState(
            targetValue = if (immersiveVinyl) 18.dp else 12.dp,
            animationSpec = tween(340),
            label = "lyricTopPad",
        )
        val lyricBottomPad by animateDpAsState(
            targetValue = if (immersiveVinyl) 10.dp else 4.dp,
            animationSpec = tween(340),
            label = "lyricBottomPad",
        )
        val pages: @Composable (Modifier) -> Unit = { mod ->
            Column(mod) {
                AnimatedVisibility(
                    visible = barsVisible || onLibraryPage,
                    enter = fadeIn(tween(240)) + expandVertically(tween(280)),
                    exit = fadeOut(tween(340)) + shrinkVertically(tween(360)),
                ) {
                    TopTabs(
                        page = pager.currentPage,
                        showLibraryTab = libraryTabContent != null,
                        onBack = onBack,
                        trailingContent = topTrailingContent,
                    ) {
                        touches++
                        scope.launch { pager.animateScrollToPage(it) }
                    }
                }
                val vinylVisible = pager.currentPage == 0 || pager.targetPage == 0
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.weight(1f).fillMaxWidth().graphicsLayer { clip = true },
                    userScrollEnabled = !LocalTv.current,
                ) { page ->
                    when (page) {
                        LYRICS_PAGE -> LyricsPage(
                            vm, music, lyrics, translations, positionMs, onSeek, onAction, onMessage,
                            toolsShown = barsVisible, activeMusic = activeMusic,
                        )
                        LIBRARY_PAGE -> libraryTabContent?.invoke()
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = BiasAlignment(0f, vinylBiasY)) {
                            Vinyl(
                                cover = displayCover,
                                playing = effectiveWantsPlay && vinylVisible,
                                modifier = Modifier.vinylSquareSize(
                                    widthFraction = vinylWidthFraction,
                                    maxDiameter = vinylMaxDiameter,
                                    maxHeightFraction = vinylMaxHeightFraction,
                                ),
                            )
                        }
                    }
                }
            }
        }
        val controls: @Composable (Modifier) -> Unit = { mod ->
            val masterVol = com.aisub.player.desktop.DesktopPlayerEngine.masterVolumeState.floatValue
            val isMuted = com.aisub.player.desktop.DesktopPlayerEngine.mutedState.value
            val effVol = if (isMuted) 0f else masterVol
            val volIcon = when {
                effVol <= 0.005f -> AppIcons.VolumeOff
                effVol < 0.5f -> AppIcons.VolumeDown
                else -> AppIcons.Volume
            }
            Column(mod) {
                SongTitleRow(
                    title = tags.title,
                    subtitle = listOfNotNull(tags.artist, tags.album).joinToString(" - "),
                    liked = liked,
                    immersive = immersive,
                    onLike = { touches++; toggleLike() },
                    onMenu = { touches++; showMenu = true },
                )
                // 歌词页以外，在歌名下面显示正在唱的一句（沉浸态字号与留白放大，并可带出译文）
                val lyricIdx = lyrics?.takeIf { it.synced && !onLyricsPage }?.indexAt(positionMs) ?: -1
                val line = if (lyricIdx >= 0) lyrics?.lines?.getOrNull(lyricIdx)?.text else null
                val transLine = if (lyricIdx >= 0) translations?.getOrNull(lyricIdx)?.takeIf { it.isNotBlank() } else null
                if (line != null) {
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(start = 28.dp, end = 28.dp, top = lyricTopPad, bottom = lyricBottomPad),
                    ) {
                        Text(
                            line.ifEmpty { "♪" },
                            color = if (immersiveVinyl) Color(0xEBFFFFFF) else Color(0xCCFFFFFF),
                            fontSize = if (immersiveVinyl) 16.5.sp else 15.sp,
                            fontWeight = if (immersiveVinyl) FontWeight.Medium else FontWeight.Normal,
                            lineHeight = 23.sp,
                            maxLines = if (immersiveVinyl) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (immersiveVinyl && transLine != null) {
                            Text(
                                transLine,
                                color = Color(0x99FFFFFF),
                                fontSize = 14.sp,
                                lineHeight = 19.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(if (immersiveVinyl) 18.dp else 14.dp))
                }
                val effectiveDuration = if (activeMusic) (player.duration.takeIf { it > 0 } ?: tags.durationMs) else tags.durationMs
                MusicSeekBar(
                    positionMs = if (activeMusic) positionMs else positionMs.coerceAtMost(effectiveDuration),
                    durationMs = effectiveDuration,
                    immersive = immersive,
                    onSeek = { target ->
                        touches++
                        if (activeMusic) onSeek(target) else onIdlePlay?.invoke()
                    },
                    onDragActiveChange = { seekDragging = it; touches++ },
                )
                AnimatedVisibility(
                    visible = barsVisible,
                    enter = fadeIn(tween(240)) + expandVertically(tween(280)),
                    exit = fadeOut(tween(340)) + shrinkVertically(tween(360)),
                ) {
                    Column {
                        PlayControls(
                            vm = vm,
                            player = player,
                            wantsPlay = effectiveWantsPlay,
                            activeMusic = activeMusic,
                            onIdlePlay = onIdlePlay,
                            onMessage = { touches++; onMessage(it) },
                            onQueue = {
                                touches++
                                if (activeMusic && vm.queueSize > 0) showQueue = true
                                else if (libraryTabContent != null) scope.launch { pager.animateScrollToPage(LIBRARY_PAGE) }
                            },
                        )
                        DesktopAdjustPill(
                            icon = volIcon,
                            label = if (isMuted || effVol <= 0.005f) "取消静音" else "静音",
                            fraction = effVol,
                            valueText = "${(effVol * 100).roundToInt()}%",
                            height = 46.dp,
                            iconTint = if (isMuted || effVol <= 0.005f) Color(0xFFFF8A80) else Color(0xFFD5DCE6),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp, vertical = 6.dp),
                            onIconClick = {
                                com.aisub.player.desktop.DesktopPlayerEngine.toggleMute()
                                touches++
                            },
                            onFractionChange = { f ->
                                com.aisub.player.desktop.DesktopPlayerEngine.setMasterVolume(f)
                                touches++
                            },
                            onWheelStep = { dir ->
                                val next = (effVol + dir * 0.05f).coerceIn(0f, 1f)
                                com.aisub.player.desktop.DesktopPlayerEngine.setMasterVolume(next)
                                if (next <= 0.001f) com.aisub.player.desktop.DesktopPlayerEngine.setMuted(true)
                                touches++
                            },
                            onDragActiveChange = { sliderAdjusting = it },
                        )
                    }
                }
            }
        }
        val insetMod = Modifier.fillMaxSize()
            .statusBarsPadding()
            .let { if (applyNavigationBarInset) it.navigationBarsPadding() else it }
            .padding(bottom = bottomOverlayPadding)
        if (landscape) {
            // 横屏与 TV 下左侧 Pager 宽度保持恒定，严禁随 pager.currentPage 动态跳变，彻底消除 Pager 重测导致的 60Hz 抖动重叠
            Row(insetMod.padding(horizontal = landscapeOuterPad)) {
                pages(Modifier.weight(if (libraryTabContent != null) 1.25f else 1f).fillMaxHeight())
                controls(Modifier.weight(1f).align(Alignment.CenterVertically).padding(bottom = controlsBottomPad))
            }
        } else {
            Column(insetMod) {
                pages(Modifier.weight(1f).fillMaxWidth())
                AnimatedVisibility(
                    controlsPanelShown,
                    enter = fadeIn(tween(240)) + expandVertically(tween(280)),
                    exit = fadeOut(tween(360)) + shrinkVertically(tween(380)),
                ) { controls(Modifier.fillMaxWidth().padding(bottom = controlsBottomPad)) }
            }
        }
    }

    CompositionLocalProvider(LocalForceDarkPopup provides true) {
        if (showQueue) {
            var queueVersion by remember { mutableIntStateOf(0) }
            val entries = remember(queueVersion, effectiveEntry?.uri) { vm.queueEntries }
            val (mode, next) = rememberPlayMode(player)
            AppSheet(
                onDismissRequest = { showQueue = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                title = "播放列表 (${entries.size})",
                headerActions = {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable { onMessage(next().label) }.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(mode.icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(mode.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    }
                },
            ) {
                QueueSheet(vm, entries, effectiveEntry, onRemoved = { queueVersion++ })
            }
        }

        if (showMenu) AppSheet(
            onDismissRequest = { showMenu = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            title = "歌曲操作",
        ) {
            ActionSheet(tags, displayCover, effectiveEntry, activeMusic, liked, toggleLike) { action -> showMenu = false; onAction(action) }
        }
    }
}

/** 背景：封面缩成很小再放大（就是模糊，Modifier.blur 要 Android 12），再压暗 */
@Composable
private fun Backdrop(bmp: android.graphics.Bitmap?) {
    if (bmp == null || bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return
    val tiny = remember(bmp) {
        runCatching {
            android.graphics.Bitmap.createScaledBitmap(bmp, 16, 16, true).asImageBitmap()
        }.getOrNull()
    } ?: return
    Image(
        tiny, null, contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
        modifier = Modifier.fillMaxSize().alpha(0.7f),
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xB3101010), Color(0x80202020), Color(0x66303030)))))
}

@Composable
private fun TopTabs(
    page: Int,
    showLibraryTab: Boolean,
    onBack: (() -> Unit)?,
    trailingContent: (@Composable () -> Unit)?,
    onSelect: (Int) -> Unit,
) {
    val tabs = if (showLibraryTab) listOf("歌曲", "歌词", "乐库") else listOf("歌曲", "歌词")
    if (onBack == null && trailingContent != null) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 22.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { i, label ->
                    val selected = i == page
                    Text(
                        label,
                        color = if (selected) Color.White else Dim,
                        fontSize = if (selected) 19.sp else 17.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .tvFocusRing(RoundedCornerShape(6.dp), color = Color.White)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSelect(i) }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            trailingContent()
        }
    } else {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
                    Icon(AppIcons.ExpandMore, "返回", tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(if (showLibraryTab) 22.dp else 28.dp)) {
                tabs.forEachIndexed { i, label ->
                    Text(
                        label, color = if (i == page) Color.White else Dim, fontSize = 18.sp,
                        fontWeight = if (i == page) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .tvFocusRing(RoundedCornerShape(6.dp), color = Color.White)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSelect(i) }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
            }
            if (trailingContent != null) {
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) {
                    trailingContent()
                }
            }
        }
    }
}

// ---------------- 歌曲：转动的唱片 ----------------

private fun Modifier.vinylSquareSize(
    widthFraction: Float,
    maxDiameter: Dp,
    maxHeightFraction: Float,
): Modifier = this.layout { measurable, constraints ->
    val maxByW = if (constraints.hasBoundedWidth) constraints.maxWidth * widthFraction else maxDiameter.toPx()
    val maxByH = if (constraints.hasBoundedHeight) constraints.maxHeight * maxHeightFraction else maxDiameter.toPx()
    val side = minOf(maxByW, maxByH, maxDiameter.toPx()).roundToInt().coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(side, side))
    layout(side, side) { placeable.placeRelative(0, 0) }
}

@Composable
private fun Vinyl(cover: android.graphics.Bitmap?, playing: Boolean, modifier: Modifier) {
    // 播放时慢慢转，暂停停在原处
    val angle = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        while (playing) {
            angle.animateTo(angle.value + 360f, tween(24_000, easing = LinearEasing))
            angle.snapTo(angle.value % 360f)
        }
    }
    val coverBitmap = remember(cover) {
        cover?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.asImageBitmap()
    }
    Box(
        modifier.shadow(24.dp, CircleShape, ambientColor = Color(0x66000000), spotColor = Color(0x88000000)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle.value }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                drawCircle(Color(0xFF0E0E0E), r)
                drawCircle(Color(0x33FFFFFF), r - 1.dp.toPx(), style = Stroke(1.dp.toPx()))
                // 纹路
                var gr = r * 0.97f
                while (gr > r * 0.72f) {
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
                Modifier.fillMaxSize(0.70f).clip(CircleShape).background(Color(0xFF2A2A2A)).border(3.dp, Color(0xFF050505), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (coverBitmap != null) Image(coverBitmap, "封面", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(AppIcons.MusicNote, null, tint = Color(0xFF777777), modifier = Modifier.size(72.dp))
            }
        }
    }
}

// ---------------- 歌名、喜欢、菜单 ----------------

@Composable
private fun SongTitleRow(
    title: String,
    subtitle: String,
    liked: Boolean,
    immersive: Boolean = false,
    onLike: () -> Unit,
    onMenu: () -> Unit,
) {
    val endPad by animateDpAsState(if (immersive) 28.dp else 16.dp, tween(280), label = "titleEndPad")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 28.dp, end = endPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // 放不下时来回滚动显示全名（只在放不下时才动）
            Text(
                title,
                color = Color.White,
                fontSize = if (immersive) 24.sp else 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp,
                maxLines = 1,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_500, repeatDelayMillis = 2_000),
            )
            if (subtitle.isNotEmpty()) Text(
                subtitle, color = Dim, fontSize = 15.sp, maxLines = 1,
                modifier = Modifier.padding(top = 4.dp).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_500, repeatDelayMillis = 2_000),
            )
        }
        AnimatedVisibility(
            visible = !immersive,
            enter = fadeIn(tween(220)) + expandHorizontally(tween(260)),
            exit = fadeOut(tween(220)) + shrinkHorizontally(tween(260)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onLike, modifier = Modifier.tvFocusRing(CircleShape, color = Color.White)) {
                    Icon(if (liked) AppIcons.Favorite else AppIcons.FavoriteBorder, if (liked) "取消喜欢" else "喜欢", tint = if (liked) Color(0xFFFF5A6E) else Color.White, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = onMenu, modifier = Modifier.tvFocusRing(CircleShape, color = Color.White)) {
                    Icon(AppIcons.MoreVert, "更多", tint = Color.White)
                }
            }
        }
    }
}

// ---------------- 进度条 ----------------

@Composable
private fun MusicSeekBar(
    positionMs: Long,
    durationMs: Long,
    immersive: Boolean = false,
    onSeek: (Long) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnDragActive by rememberUpdatedState(onDragActiveChange)
    val fraction = dragging ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val trackHeight by animateDpAsState(
        targetValue = when {
            dragging != null -> 5.dp
            immersive -> 3.dp
            else -> 4.dp
        },
        animationSpec = tween(260),
        label = "seekTrackHeight",
    )
    val thumb by animateDpAsState(
        targetValue = when {
            dragging != null -> 20.dp
            immersive -> 7.dp
            else -> 14.dp
        },
        animationSpec = tween(260),
        label = "seekThumbSize",
    )
    val finish = {
        dragging?.let { currentOnSeek((it * durationMs).toLong()) }
        dragging = null
        currentOnDragActive(false)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
        Box(
            Modifier.fillMaxWidth().height(28.dp)
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(durationMs) {
                    detectTapGestures { o -> if (durationMs > 0) currentOnSeek(((o.x / widthPx).coerceIn(0f, 1f) * durationMs).toLong()) }
                }
                .pointerInput(durationMs) {
                    if (durationMs <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = (o.x / widthPx).coerceIn(0f, 1f); currentOnDragActive(true) },
                        onDragEnd = finish, onDragCancel = finish,
                    ) { change, _ -> change.consume(); dragging = (change.position.x / widthPx).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier.fillMaxWidth().height(trackHeight).clip(RoundedCornerShape(50))
                    .background(if (immersive && dragging == null) Color(0x33FFFFFF) else Color(0x40FFFFFF)),
            )
            Box(
                Modifier.fillMaxWidth(fraction).height(trackHeight).clip(RoundedCornerShape(50))
                    .background(if (immersive && dragging == null) Color(0xCCFFFFFF) else Color(0xEBFFFFFF)),
            )
            Box(
                Modifier.offset { IntOffset((fraction * widthPx - thumb.toPx() / 2).roundToInt(), 0) }
                    .size(thumb).clip(CircleShape).background(Color(0xFFECEEF2)),
            )
        }
        val clockColor = if (immersive && dragging == null) Color(0x59FFFFFF) else Faint
        Row(Modifier.fillMaxWidth()) {
            Text(formatClock(dragging?.let { (it * durationMs).toLong() } ?: positionMs), color = clockColor, fontSize = 12.5.sp)
            Spacer(Modifier.weight(1f))
            Text(formatClock(durationMs), color = clockColor, fontSize = 12.5.sp)
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
private fun PlayControls(
    vm: MainViewModel,
    player: Player,
    wantsPlay: Boolean,
    activeMusic: Boolean = true,
    onIdlePlay: (() -> Unit)? = null,
    onMessage: (String) -> Unit,
    onQueue: () -> Unit,
) {
    val (mode, next) = rememberPlayMode(player)
    val canSkip = activeMusic && vm.queueSize > 1
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onMessage(next().label) },
            modifier = Modifier.tvFocusRing(CircleShape, color = Color.White),
        ) {
            Icon(mode.icon, mode.label, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        IconButton(
            onClick = {
                if (!activeMusic) onIdlePlay?.invoke()
                else if (canSkip) vm.skipInQueue(false, restartFirst = true)
                else player.seekTo(0)
            },
            modifier = Modifier.tvFocusRing(CircleShape, color = Color.White),
        ) {
            Icon(AppIcons.SkipPrevious, "上一首", tint = Color.White, modifier = Modifier.size(34.dp))
        }
        GlassBox(
            modifier = Modifier
                .size(68.dp)
                .tvFocusRing(CircleShape, color = Color.White),
            shape = CircleShape,
            style = GlassStyle.Floating,
            tint = Color(0x36FFFFFF),
            onClick = { if (activeMusic) vm.togglePlay() else onIdlePlay?.invoke() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (wantsPlay) AppIcons.Pause else AppIcons.PlayArrow, if (wantsPlay) "暂停" else "播放", tint = Color.White, modifier = Modifier.size(38.dp))
        }
        IconButton(
            onClick = { if (activeMusic) vm.skipInQueue(true) else onIdlePlay?.invoke() },
            enabled = canSkip || (!activeMusic && onIdlePlay != null),
            modifier = Modifier.tvFocusRing(CircleShape, color = Color.White),
        ) {
            Icon(AppIcons.SkipNext, "下一首", tint = if (canSkip || (!activeMusic && onIdlePlay != null)) Color.White else Faint, modifier = Modifier.size(34.dp))
        }
        IconButton(
            onClick = onQueue,
            enabled = (activeMusic && vm.queueSize > 0) || !activeMusic,
            modifier = Modifier.tvFocusRing(CircleShape, color = Color.White),
        ) {
            Icon(AppIcons.QueueMusic, "播放列表", tint = Color.White, modifier = Modifier.size(28.dp))
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
    /** 下方控件淡出时，LRC、音量这一行也一起收起 */
    toolsShown: Boolean,
    activeMusic: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adjusting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !activeMusic -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("开始播放音乐后自动加载同步歌词", color = Dim, fontSize = 16.sp)
                }
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
                else -> SyncedLyrics(lyrics, translations, positionMs, onSeek, Modifier.fillMaxSize(), immersive = !toolsShown)
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
        var menu by remember { mutableStateOf(false) }
        // 菜单开着、正在调整歌词时不收起
        AnimatedVisibility(
            toolsShown || menu || adjusting,
            enter = fadeIn(tween(250)) + expandVertically(tween(250)),
            exit = fadeOut(tween(800)) + shrinkVertically(tween(800)),
        ) {
            Row(Modifier.padding(start = 28.dp, top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Text(
                        "LRC", color = Color(0xCCFFFFFF), fontSize = 13.sp,
                        modifier = Modifier
                            .liquidGlass(shape = RoundedCornerShape(6.dp), style = GlassStyle.Pill, tint = Color(0x28FFFFFF))
                            .clickable { menu = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    CompositionLocalProvider(LocalForceDarkPopup provides true) {
                    AppMenu(menu, onDismissRequest = { menu = false }) {
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
                }
                Spacer(Modifier.width(16.dp))
                val lrcMuted = com.aisub.player.desktop.DesktopPlayerEngine.mutedState.value
                val lrcVol = if (lrcMuted) 0f else com.aisub.player.desktop.DesktopPlayerEngine.masterVolumeState.floatValue
                IconButton(onClick = {
                    val mutedNow = com.aisub.player.desktop.DesktopPlayerEngine.toggleMute()
                    onMessage(if (mutedNow) "已静音" else "音量 ${(com.aisub.player.desktop.DesktopPlayerEngine.masterVolume * 100).roundToInt()}%")
                }) {
                    Icon(
                        when {
                            lrcVol <= 0.005f -> AppIcons.VolumeOff
                            lrcVol < 0.5f -> AppIcons.VolumeDown
                            else -> AppIcons.Volume
                        },
                        "静音切换",
                        tint = if (lrcVol <= 0.005f) Color(0xFFFF8A80) else Color(0xCCFFFFFF),
                    )
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) = Text(
    text, color = Color.White, fontSize = 13.sp,
    modifier = Modifier
        .liquidGlass(shape = RoundedCornerShape(50), style = GlassStyle.Pill, tint = Color(0x33FFFFFF))
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp),
)

@Composable
private fun MenuRow(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) = DropdownMenuItem(
    text = { Text(text, fontSize = 16.sp, modifier = Modifier.widthIn(min = 150.dp)) },
    trailingIcon = { Icon(icon, null) },
    enabled = enabled, onClick = onClick,
    colors = LocalPopupColors.current.let {
        MenuDefaults.itemColors(
            textColor = it.textPrimary, trailingIconColor = it.textPrimary,
            disabledTextColor = it.disabled, disabledTrailingIconColor = it.disabled,
        )
    },
)

@Composable
private fun SyncedLyrics(
    lyrics: Lyrics,
    translations: List<String?>?,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
    immersive: Boolean = false,
) {
    val index = lyrics.indexAt(positionMs)
    val listState = rememberLazyListState()
    // 手动滑动后 4 秒内不自动滚
    var touchedAt by remember { mutableLongStateOf(0L) }
    val dragging = listState.isScrollInProgress
    LaunchedEffect(dragging) { if (dragging) touchedAt = System.currentTimeMillis() }
    var viewportPx by remember { mutableIntStateOf(0) }
    val hasViewport = viewportPx > 0
    val anchorRatio = if (immersive) 0.44f else 0.42f
    val lineVertPad by animateDpAsState(if (immersive) 12.dp else 9.dp, tween(320), label = "lyricVertPad")
    val density = LocalDensity.current
    LaunchedEffect(index, lyrics, hasViewport) {
        if (!hasViewport) return@LaunchedEffect
        val wait = 4000 - (System.currentTimeMillis() - touchedAt)
        if (wait > 0) delay(wait)
        // 当前行放在中间偏上（第 0 项是顶部留白）
        listState.animateScrollToItem(index.coerceAtLeast(0) + 1, -(viewportPx * anchorRatio).toInt())
    }
    LaunchedEffect(immersive) {
        if (!hasViewport) return@LaunchedEffect
        delay(380)
        if (System.currentTimeMillis() - touchedAt >= 4000 && viewportPx > 0) {
            listState.animateScrollToItem(index.coerceAtLeast(0) + 1, -(viewportPx * anchorRatio).toInt())
        }
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
        item { Spacer(Modifier.height(with(density) { (viewportPx * anchorRatio).toDp() })) }
        itemsIndexed(lyrics.lines) { i, line ->
            val active = i == index
            Column(
                Modifier.fillMaxWidth()
                    .clickable(remember { MutableInteractionSource() }, indication = null) { line.timeMs?.let(onSeek) }
                    .padding(horizontal = 30.dp, vertical = lineVertPad),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    line.text.ifEmpty { "♪" },
                    color = if (active) Color.White else Color(0x78FFFFFF),
                    fontSize = if (active) (if (immersive) 23.sp else 20.sp) else 17.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    lineHeight = if (active && immersive) 32.sp else 28.sp,
                    textAlign = TextAlign.Center,
                )
                translations?.getOrNull(i)?.let {
                    Text(
                        it,
                        color = if (active) Color(0xDDFFFFFF) else Color(0x60FFFFFF),
                        fontSize = if (active) (if (immersive) 16.sp else 15.sp) else 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
        item { Spacer(Modifier.height(with(density) { (viewportPx * 0.55f).toDp() })) }
    }
}

// ---------------- 播放列表 ----------------

@Composable
private fun QueueSheet(vm: MainViewModel, entries: List<MediaEntry>, current: MediaEntry?, onRemoved: () -> Unit) {
    val currentIndex = entries.indexOfFirst { it.uri == current?.uri }
    val c = LocalPopupColors.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
    // 歌曲放在一块浅灰卡片里；正在播放的那首除了文字变色，整行也有底色
    LazyColumn(
        Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.72f).dp)
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
            .liquidGlass(shape = RoundedCornerShape(14.dp), style = GlassStyle.Card, tint = c.card, enableBackdrop = false),
        state = listState, contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        itemsIndexed(entries, key = { i, e -> "$i:${e.uri}" }) { i, e ->
            val active = i == currentIndex
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (active) c.selectedBg else Color.Transparent)
                    .clickable { vm.playQueueIndex(i) }.padding(start = 14.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (active) {
                    Icon(AppIcons.Equalizer, "正在播放", tint = c.selectedText, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    e.displayTitle, color = if (active) c.selectedText else c.textPrimary, fontSize = 16.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 11.dp),
                )
                if (entries.size > 1) IconButton(onClick = { vm.removeQueueIndex(i); onRemoved() }) {
                    Icon(AppIcons.Close, "从播放列表移除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------- 「⋮」菜单 ----------------

@Composable
private fun ActionSheet(
    tags: com.aisub.player.music.TrackTags,
    cover: android.graphics.Bitmap?,
    current: MediaEntry?,
    activeMusic: Boolean,
    liked: Boolean,
    onLike: () -> Unit,
    onAction: (MusicAction) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .liquidGlass(shape = RoundedCornerShape(14.dp), style = GlassStyle.Card, tint = LocalPopupColors.current.card, enableBackdrop = false)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).background(LocalPopupColors.current.button), contentAlignment = Alignment.Center) {
                cover?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    ?: Icon(AppIcons.MusicNote, null, tint = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tags.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(tags.artist, tags.album).joinToString(" - ").ifEmpty { current?.folder.orEmpty() },
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (activeMusic) {
                IconButton(onClick = onLike) {
                    Icon(if (liked) AppIcons.Favorite else AppIcons.FavoriteBorder, if (liked) "取消喜欢" else "喜欢", tint = if (liked) Color(0xFFFF5A6E) else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        if (activeMusic) {
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
        } else {
            ActionGroup(
                listOf(
                    Triple(MusicAction.SLEEP, AppIcons.Timer, "定时停止"),
                    Triple(MusicAction.SPEED, AppIcons.Speed, "播放速度"),
                    Triple(MusicAction.EQUALIZER, AppIcons.Equalizer, "均衡器"),
                    Triple(MusicAction.SETTINGS, AppIcons.Settings, "设置"),
                ),
                onAction,
            )
        }
    }
}

@Composable
private fun ActionGroup(items: List<Triple<MusicAction, ImageVector, String>>, onAction: (MusicAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp)
            .liquidGlass(shape = RoundedCornerShape(14.dp), style = GlassStyle.Card, tint = LocalPopupColors.current.card, enableBackdrop = false)
            .padding(vertical = 12.dp),
    ) {
        items.forEach { (action, icon, label) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onAction(action) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 亮色是浅灰按钮，暗色是中灰按钮
                Box(Modifier.size(50.dp).clip(CircleShape).background(LocalPopupColors.current.button), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                }
                Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
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
    AppDialog(
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
    AppDialog(
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
