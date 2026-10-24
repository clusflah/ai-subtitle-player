package com.aisub.player.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.aisub.player.media.Bookmark
import io.github.peerless2012.ass.media.kt.withAssSupport
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.aisub.player.AppSettings
import com.aisub.player.MainViewModel
import com.aisub.player.Task
import com.aisub.player.UiState
import com.aisub.player.media.formatDuration
import com.aisub.player.subtitle.Cue
import com.aisub.player.subtitle.Pinyin
import com.aisub.player.subtitle.SrtMode
import com.aisub.player.subtitle.cueAt
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val PlayerColors = darkColorScheme(
    primary = Orange,
    onPrimary = Color.Black,
    background = Color.Black,
    secondaryContainer = Color(0x66F28C00),
    onSecondaryContainer = Color.White,
    surface = Color(0xFF111111),
    surfaceContainerLow = Color(0xFF111111),
    surfaceContainer = Color(0xFF161616),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    surfaceContainerHighest = Color(0xFF222222),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFB0B0B0),
    outlineVariant = Color(0xFF333333),
)

private enum class DragMode { NONE, SEEK, BRIGHTNESS, VOLUME }

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)
private val RESIZE_MODES = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "适应屏幕",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "裁剪填满",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "拉伸",
)

/** 播放页里弹出的对话框。 */
private enum class PlayerDialog { SPEED, SLEEP, GOTO, DELAY, INFO, EXPORT, SAVE_PLAYLIST, ERROR }

/** 画中画状态，由 MainActivity 更新。 */
object PipState {
    var inPip by mutableStateOf(false)
}

/**
 * 全屏黑底播放页（仿 VLC）：顶部标题，底部进度条与五个按钮
 * （字幕/音轨、旋转、播放、画面比例、更多）。AI 字幕功能收在"字幕/音轨"面板里。
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: MainViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    MaterialTheme(colorScheme = PlayerColors, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        PlayerContent(vm, onBack, onOpenSettings)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerContent(vm: MainViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    // 播放内核可能在 ExoPlayer 和 VLC 之间切换
    val player by vm.playerFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // ---- 播放状态轮询 ----
    var positionMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    // 按钮图标跟随"想要播放"，缓冲时也显示暂停，避免看起来像没反应
    var wantsPlay by remember { mutableStateOf(player.playWhenReady) }
    var buffering by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            isPlaying = player.isPlaying
            wantsPlay = player.playWhenReady && player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED
            buffering = player.playbackState == Player.STATE_BUFFERING
            vm.checkAbLoop(positionMs)
            vm.extras.checkSkip(positionMs)
            delay(100)
        }
    }
    val current = state.cues.cueAt(positionMs - state.subtitleDelayMs)

    // ---- 横屏隐藏系统栏；离开页面恢复方向、系统栏、亮度 ----
    DisposableEffect(landscape) {
        activity?.window?.let { w ->
            val insets = WindowCompat.getInsetsController(w, w.decorView)
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (landscape) insets.hide(WindowInsetsCompat.Type.systemBars()) else insets.show(WindowInsetsCompat.Type.systemBars())
            insets.isAppearanceLightStatusBars = false
        }
        onDispose {}
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { w ->
                val insets = WindowCompat.getInsetsController(w, w.decorView)
                insets.show(WindowInsetsCompat.Type.systemBars())
                insets.isAppearanceLightStatusBars = true
                w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
    }

    // ---- 界面状态 ----
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var interactions by remember { mutableIntStateOf(0) }
    var resizeIndex by remember { mutableIntStateOf(0) }
    var indicator by remember { mutableStateOf<Pair<ImageVector?, String>?>(null) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var audioOnly by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showCues by remember { mutableStateOf(false) }
    var showOnline by remember { mutableStateOf(false) }
    var showRoles by remember { mutableStateOf(false) }
    var showNav by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showSkip by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<PlayerDialog?>(null) }
    /** 正在标记的跳过区间起点 */
    var markStart by remember { mutableStateOf<Long?>(null) }
    /** 手势横滑拖动进度时的目标位置，用于显示预览图 */
    var gesturePreview by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<Bookmark?>(null) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val extras by vm.extras.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.extras.events.collect { indicator = null to it } }
    LaunchedEffect(gesturePreview) { gesturePreview?.let(vm.extras::requestThumbnail) ?: vm.extras.clearThumbnail() }

    LaunchedEffect(controls, interactions, isPlaying, showMore, showTracks) {
        if (controls && isPlaying && !showMore && !showTracks) {
            delay(3500)
            controls = false
        }
    }
    LaunchedEffect(indicator) {
        if (indicator != null) {
            delay(1000)
            indicator = null
        }
    }
    BackHandler(enabled = showMore) { showMore = false }
    BackHandler(enabled = locked) { indicator = AppIcons.Lock to "已锁定，点左侧锁图标解锁" }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) vm.exportSrt(uri, pendingExport)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSubtitles(uri)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- 画面 ----
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    // 遮罩透明：画面背景本来就是黑色；VLC 内核不一定按 ExoPlayer 的方式通知首帧
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    // ASS / SSA 字幕由 libass 画在字幕视图里的这一层上
                    subtitleView?.withAssSupport(vm.assHandler)
                }.also { playerView = it }
            },
            update = {
                if (it.player !== player) it.player = player
                it.resizeMode = RESIZE_MODES[resizeIndex].first
                // 视频自带字幕和 AI 字幕同时显示时，把自带字幕抬高，避免叠在一起
                val aiVisible = state.settings.showAiSubtitles && state.cues.isNotEmpty()
                it.subtitleView?.setBottomPaddingFraction(if (aiVisible) 0.32f else androidx.media3.ui.SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (audioOnly) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                IconBadge(AppIcons.MusicNote, size = 96.dp)
                Spacer(Modifier.height(12.dp))
                Text("仅播放声音", color = Color(0xFFB0B0B0))
            }
        }

        // ---- 字幕 ----
        SubtitleOverlay(
            cue = current, settings = state.settings,
            // 暂停时底栏多一行逐帧、截图按钮，字幕再往上让一些
            bottomPadding = when {
                !controls || PipState.inPip -> 0
                !wantsPlay && !audioOnly && state.current?.isVideo != false -> 210
                else -> 150
            }.dp + state.settings.subtitleOffset.dp,
            topPadding = (if (controls && !PipState.inPip) 72 else 0).dp,
        )
        if (PipState.inPip) return@Box

        // ---- 缓冲 / 出错提示 ----
        val playbackError = state.playbackError
        if (playbackError != null) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xCC000000)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("无法播放", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(playbackError, color = Color(0xFFCCCCCC), fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Row {
                    TextButton(onClick = { vm.togglePlay() }) { Text("重试", color = Orange) }
                    if (!vm.usingVlc) TextButton(onClick = { vm.switchToVlc() }) { Text("换用 VLC 内核播放", color = Orange) }
                }
            }
        } else if (buffering && wantsPlay) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Orange)
                Spacer(Modifier.height(8.dp))
                Text("正在缓冲…", color = Color.White, fontSize = 13.sp)
            }
        }

        // ---- 手势层 ----
        GestureLayer(
            locked = locked,
            onTap = { controls = !controls; interactions++ },
            onIndicator = { indicator = it },
            onSeekPreview = { gesturePreview = it },
            speed = { speed },
            player = player,
            onTogglePlay = vm::togglePlay,
        )

        // ---- 顶部：返回 + 标题 + 任务状态 ----
        AnimatedVisibility(controls && !locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Column(
                Modifier.fillMaxWidth().background(Color(0x66000000)).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, "返回", tint = Color.White) }
                    Text(
                        state.current?.title ?: "", color = Color.White, fontSize = 20.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                }
                TaskChip(state, onClick = { showTracks = true }, modifier = Modifier.padding(start = 52.dp, bottom = 4.dp))
                extras.detecting?.let { msg ->
                    Text(
                        msg, color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 52.dp, bottom = 4.dp).clip(RoundedCornerShape(50)).background(Color(0x5580DEEA))
                            .clickable { showSkip = true }.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
        }

        // ---- 底部：时间 + 进度条 + 五个按钮 ----
        AnimatedVisibility(controls && !locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(Color(0x66000000)).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                val duration = player.duration.takeIf { it > 0 } ?: 0L
                var dragging by remember { mutableStateOf<Float?>(null) }
                // 暂停时：逐帧、截图、书签
                if (!wantsPlay && !audioOnly && state.current?.isVideo != false) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        FrameButton(AppIcons.SkipPrevious, "上一帧") { indicator = null to formatTime(vm.extras.stepFrame(false)); interactions++ }
                        FrameButton(AppIcons.Image, "截图") {
                            interactions++
                            scope.launch {
                                val pos = player.currentPosition
                                val bmp = vm.extras.captureFrame(playerView?.videoSurfaceView)
                                val ok = bmp != null && vm.extras.saveToGallery(bmp, pos)
                                vm.extras.emit(if (ok) "截图已保存到相册 Pictures/译幕" else "截图失败")
                            }
                        }
                        FrameButton(AppIcons.Star, "书签") { interactions++; scope.launch { vm.extras.addBookmark(playerView?.videoSurfaceView) } }
                        FrameButton(AppIcons.SkipNext, "下一帧") { indicator = null to formatTime(vm.extras.stepFrame(true)); interactions++ }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(formatClock(dragging?.toLong() ?: positionMs), color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    state.loopA?.let { Text(if (state.loopB != null) "A-B 循环中  " else "A 点已设置  ", color = Orange, fontSize = 13.sp) }
                    Text(formatClock(duration), color = Color.White, fontSize = 14.sp)
                }
                val fraction = if (duration > 0) ((dragging ?: positionMs.toFloat()) / duration).coerceIn(0f, 1f) else 0f
                dragging?.let { ms ->
                    SeekPreview(
                        fraction, extras.thumbnail?.second, formatClock(ms.toLong()),
                        extras.chapters.lastOrNull { it.startMs <= ms }?.title,
                    )
                }
                SeekBar(
                    fraction = fraction,
                    durationMs = duration,
                    chapters = extras.chapters.map { it.startMs },
                    skips = extras.skipRanges.map { it.startMs to it.endMs },
                    bookmarks = extras.bookmarks.map { it.positionMs },
                    onDrag = { f -> dragging = f * duration; vm.extras.requestThumbnail((f * duration).toLong()); interactions++ },
                    onDragEnd = { dragging?.let { player.seekTo(it.toLong()) }; dragging = null; vm.extras.clearThumbnail() },
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarButton(AppIcons.Subtitles, "字幕与音轨") { showTracks = true }
                    Spacer(Modifier.weight(1f))
                    BarButton(AppIcons.ScreenRotation, "旋转屏幕") {
                        activity?.requestedOrientation =
                            if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { vm.togglePlay(); interactions++ },
                        modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.Transparent),
                    ) {
                        Box(
                            Modifier.size(60.dp).clip(CircleShape).background(Color(0x22FFFFFF)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (wantsPlay) AppIcons.Pause else AppIcons.PlayArrow, if (wantsPlay) "暂停" else "播放", tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    BarButton(AppIcons.AspectRatio, "画面比例") {
                        resizeIndex = (resizeIndex + 1) % RESIZE_MODES.size
                        indicator = AppIcons.AspectRatio to RESIZE_MODES[resizeIndex].second
                        interactions++
                    }
                    Spacer(Modifier.weight(1f))
                    BarButton(AppIcons.MoreHoriz, "更多") { showMore = true }
                }
            }
        }

        // ---- 锁屏后只留一个解锁按钮 ----
        if (locked && controls) {
            IconButton(
                onClick = { locked = false; interactions++ },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).clip(CircleShape).background(Color(0x66000000)),
            ) { Icon(AppIcons.Lock, "解锁", tint = Color.White) }
        }

        // ---- 横滑拖动进度时的画面预览 ----
        if (gesturePreview != null) {
            extras.thumbnail?.second?.let { bmp ->
                Image(
                    bmp.asImageBitmap(), null, contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.Center).offset(y = (-80).dp).width(200.dp).aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp)).background(Color.Black),
                )
            }
        }

        // ---- 片头片尾：没开自动跳过、或往回拖进区间时显示跳过按钮 ----
        extras.activeSkip?.let { r ->
            TextButton(
                onClick = vm.extras::skipActive,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = (if (!controls || locked) 48 else if (!wantsPlay) 250 else 190).dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)),
            ) { Text("跳过${r.kind.short} »", color = Color.White, fontSize = 15.sp) }
        }

        // ---- 中央提示 ----
        indicator?.let { (icon, text) ->
            Row(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color(0xAA000000)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(text, color = Color.White, fontSize = 16.sp)
            }
        }

        // ---- 更多菜单（右侧滑出） ----
        if (showMore) Box(Modifier.fillMaxSize().clickable(onClick = { showMore = false }))
        AnimatedVisibility(
            showMore, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            MorePanel(
                state = state, audioOnly = audioOnly, repeatMode = player.repeatMode,
                onAction = { action ->
                    showMore = false
                    when (action) {
                        MoreAction.LOCK -> { locked = true; controls = true; indicator = AppIcons.Lock to "已锁定屏幕" }
                        MoreAction.SLEEP -> dialog = PlayerDialog.SLEEP
                        MoreAction.SPEED -> dialog = PlayerDialog.SPEED
                        MoreAction.GOTO -> dialog = PlayerDialog.GOTO
                        MoreAction.NAVIGATE -> showNav = true
                        MoreAction.BOOKMARKS -> showBookmarks = true
                        MoreAction.SKIP -> showSkip = true
                        MoreAction.EQUALIZER -> openEqualizer(context, (player as? androidx.media3.exoplayer.ExoPlayer)?.audioSessionId ?: 0)
                        MoreAction.AUDIO_ONLY -> {
                            audioOnly = !audioOnly
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly).build()
                        }
                        MoreAction.PIP -> enterPip(activity)
                        MoreAction.REPEAT -> {
                            player.repeatMode = when (player.repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                                else -> Player.REPEAT_MODE_OFF
                            }
                            indicator = AppIcons.Repeat to repeatLabel(player.repeatMode)
                        }
                        MoreAction.INFO -> dialog = PlayerDialog.INFO
                        MoreAction.AB_LOOP -> indicator = AppIcons.Repeat to vm.toggleAbLoop()
                        MoreAction.SAVE_PLAYLIST -> dialog = PlayerDialog.SAVE_PLAYLIST
                        MoreAction.SETTINGS -> onOpenSettings()
                    }
                },
            )
        }
    }

    // ---- 字幕与音轨面板 ----
    if (showTracks) {
        ModalBottomSheet(
            onDismissRequest = { showTracks = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF111111),
        ) {
            TrackPanel(
                vm = vm, state = state,
                onSubtitleAction = { action ->
                    when (action) {
                        SubtitleAction.GENERATE -> if (state.transcribing) vm.cancelAll() else vm.generateSubtitles()
                        SubtitleAction.TRANSLATE -> if (state.translating) vm.cancelAll() else vm.startTranslation()
                        SubtitleAction.LIST -> { showTracks = false; showCues = true }
                        SubtitleAction.DELAY -> dialog = PlayerDialog.DELAY
                        SubtitleAction.IMPORT -> importLauncher.launch(arrayOf("application/x-subrip", "text/*", "application/octet-stream"))
                        SubtitleAction.ONLINE -> { showTracks = false; showOnline = true }
                        SubtitleAction.EXPORT -> dialog = PlayerDialog.EXPORT
                        SubtitleAction.ERROR -> dialog = PlayerDialog.ERROR
                        SubtitleAction.ROLES -> { showTracks = false; showRoles = true }
                        SubtitleAction.EMBEDDED -> if (state.extractingEmbedded) vm.cancelAll() else vm.extractEmbeddedSubtitles()
                        SubtitleAction.STYLE -> { showTracks = false; showStyle = true }
                    }
                },
            )
        }
    }

    // ---- 视频自带多条字幕轨时选择一条 ----
    if (state.choosingEmbedded) {
        val tracks = state.embeddedTracks.orEmpty().filter { it.cues.isNotEmpty() }
        AlertDialog(
            onDismissRequest = vm::dismissEmbeddedChoice,
            title = { Text("选择字幕轨") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("选一条作为字幕，之后可以翻译成${state.settings.targetLanguage}", fontSize = 13.sp, color = Color(0xFFB0B0B0))
                    tracks.forEach { t ->
                        Column(Modifier.fillMaxWidth().clickable { vm.useEmbeddedTrack(t) }.padding(vertical = 10.dp)) {
                            Text(t.title, fontSize = 16.sp)
                            Text(
                                "${t.cues.size} 句 · " + t.cues.first().let { c -> listOfNotNull(c.translation, c.text).joinToString(" / ") }.take(60),
                                fontSize = 12.sp, color = Color(0xFF999999), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissEmbeddedChoice) { Text("取消") } },
        )
    }

    // ---- 角色与配音 ----
    if (showRoles) {
        ModalBottomSheet(
            onDismissRequest = { showRoles = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF111111),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) { RolesPanel(vm, state) }
        }
    }

    // ---- 章节与片段 ----
    if (showNav) {
        ModalBottomSheet(onDismissRequest = { showNav = false }, containerColor = Color(0xFF111111)) {
            NavigatorSheet(vm, positionMs) { ms -> player.seekTo(ms); showNav = false }
        }
    }

    // ---- 书签与关键帧 ----
    if (showBookmarks) {
        ModalBottomSheet(onDismissRequest = { showBookmarks = false }, containerColor = Color(0xFF111111)) {
            BookmarksSheet(
                vm,
                onAdd = { scope.launch { vm.extras.addBookmark(playerView?.videoSurfaceView) } },
                onSeek = { ms -> player.seekTo(ms); showBookmarks = false },
                onRename = { renaming = it },
            )
        }
    }
    renaming?.let { b ->
        TextInputDialog("书签备注", "备注", confirm = "保存", initial = b.note, onDismiss = { renaming = null }) { note ->
            vm.extras.renameBookmark(b, note)
            renaming = null
        }
    }

    // ---- 片头片尾与跳过 ----
    if (showSkip) {
        ModalBottomSheet(
            onDismissRequest = { showSkip = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF111111),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SkipSheet(
                    vm, positionMs, markStart,
                    onMarkStart = { markStart = positionMs; showSkip = false; indicator = null to "已记下起点，播放到结尾后再打开这里" },
                    onMarkEnd = { kind -> markStart?.let { vm.extras.markRange(kind, it, positionMs) }; markStart = null },
                    onCancelMark = { markStart = null },
                    onSeek = { ms -> player.seekTo(ms) },
                )
            }
        }
    }

    // ---- 字幕样式（边调边看） ----
    if (showStyle) {
        ModalBottomSheet(
            onDismissRequest = { showStyle = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFF111111),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                Text("字幕样式", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                SubtitleStyleEditor(state.settings) { vm.updateSettings(it) }
            }
        }
    }

    // ---- 在线下载字幕 ----
    if (showOnline) {
        OnlineSubtitleSheet(vm, onDismiss = { showOnline = false }, onOpenSettings = { showOnline = false; onOpenSettings() })
    }

    // ---- 字幕列表 ----
    if (showCues) {
        ModalBottomSheet(
            onDismissRequest = { showCues = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = Color(0xFF111111),
        ) {
            Column(Modifier.fillMaxHeight(0.85f)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("字幕列表", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("${state.cues.size} 句", color = Color(0xFFB0B0B0), fontSize = 13.sp)
                }
                Box(Modifier.padding(16.dp)) {
                    DisplayModeSelector(state.settings.showOriginal, state.settings.showTranslation) { o, t ->
                        vm.updateSettings(state.settings.copy(showOriginal = o, showTranslation = t))
                    }
                }
                CueList(
                    cues = state.cues, current = current, settings = state.settings, roles = state.roles,
                    onSeek = { player.seekTo(it.startMs + state.subtitleDelayMs); player.play() },
                    onRetranslate = { vm.retranslate(it.id) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // ---- 对话框 ----
    when (dialog) {
        null -> {}
        PlayerDialog.SPEED -> ChoiceDialog(
            "播放速度", SPEEDS.map { "${formatSpeed(it)}x" }, SPEEDS.indexOf(speed),
            onDismiss = { dialog = null },
        ) { i -> speed = SPEEDS[i]; player.setPlaybackSpeed(speed); dialog = null }
        PlayerDialog.SLEEP -> {
            val options = listOf<Pair<String, Int?>>("关闭" to null, "本集结束后" to 0, "15 分钟" to 15, "30 分钟" to 30, "45 分钟" to 45, "60 分钟" to 60, "90 分钟" to 90)
            val selected = when (val at = state.sleepAt) { null -> 0; -1L -> 1; else -> -1.also { at } }
            ChoiceDialog("睡眠计时器", options.map { it.first }, selected, onDismiss = { dialog = null }) { i ->
                vm.setSleepTimer(options[i].second)
                indicator = AppIcons.Timer to if (options[i].second == null) "已关闭睡眠计时" else "将在${options[i].first}暂停"
                dialog = null
            }
        }
        PlayerDialog.GOTO -> GotoDialog(onDismiss = { dialog = null }) { ms -> player.seekTo(ms); dialog = null }
        PlayerDialog.DELAY -> DelayDialog(state.subtitleDelayMs, onChange = vm::setSubtitleDelay, onDismiss = { dialog = null })
        PlayerDialog.INFO -> InfoDialog(vm, onDismiss = { dialog = null })
        PlayerDialog.EXPORT -> ChoiceDialog(
            "导出字幕（SRT）", listOf("双语", "仅译文", "仅原文"), -1, onDismiss = { dialog = null },
        ) { i ->
            pendingExport = listOf(SrtMode.BILINGUAL, SrtMode.TRANSLATION, SrtMode.ORIGINAL)[i]
            val base = state.current?.title?.substringBeforeLast('.') ?: "subtitles"
            exportLauncher.launch("$base.${listOf("bilingual", "translated", "original")[i]}.srt")
            dialog = null
        }
        PlayerDialog.SAVE_PLAYLIST -> TextInputDialog(
            "保存播放列表", "名称（当前队列 ${vm.queueSize} 项）", confirm = "保存",
            onDismiss = { dialog = null },
        ) { name -> vm.saveQueueAsPlaylist(name); indicator = AppIcons.PlaylistAdd to "已保存"; dialog = null }
        PlayerDialog.ERROR -> AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("知道了") } },
            title = { Text("出错了") },
            text = { Text(state.error ?: "") },
        )
    }
}

/** 导出模式在选择后、文件选择器返回前暂存。 */
private var pendingExport = SrtMode.BILINGUAL

/** 拖动进度条时浮在上方的预览：画面、时间、所在章节，跟着手指左右移动。 */
@Composable
private fun SeekPreview(fraction: Float, image: android.graphics.Bitmap?, time: String, chapter: String?) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        val w = 168.dp
        val x = (maxWidth * fraction - w / 2).coerceIn(0.dp, (maxWidth - w).coerceAtLeast(0.dp))
        Column(
            Modifier.offset(x = x).width(w).clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF222222)), contentAlignment = Alignment.Center) {
                if (image != null) Image(image.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            }
            Text(time, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
            if (!chapter.isNullOrBlank()) Text(chapter, color = Color(0xFFCCCCCC), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun FrameButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp))
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

/**
 * VLC 风格的细进度条：灰色细线 + 橙色已播放段 + 小圆点；点按或拖动跳转。
 * 线上标出章节（白色刻度）、片头片尾等跳过区间（浅色段）和书签（黄点）。
 */
@Composable
private fun SeekBar(
    fraction: Float,
    durationMs: Long,
    chapters: List<Long>,
    skips: List<Pair<Long, Long>>,
    bookmarks: List<Long>,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    Box(
        Modifier.fillMaxWidth().height(32.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { o -> onDrag((o.x / widthPx).coerceIn(0f, 1f)); onDragEnd() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> onDrag((o.x / widthPx).coerceIn(0f, 1f)) },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                ) { change, _ -> change.consume(); onDrag((change.position.x / widthPx).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF)))
        if (durationMs > 0) {
            Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                val cy = size.height / 2
                for ((s, e) in skips) {
                    val x0 = s.toFloat() / durationMs * size.width
                    val x1 = e.toFloat() / durationMs * size.width
                    drawRect(Color(0x8880DEEA), topLeft = Offset(x0, cy - 1.5.dp.toPx()), size = Size((x1 - x0).coerceAtLeast(2f), 3.dp.toPx()))
                }
                for (c in chapters) {
                    if (c <= 0) continue
                    val x = c.toFloat() / durationMs * size.width
                    drawRect(Color.White, topLeft = Offset(x - 1f, cy - 4.dp.toPx()), size = Size(2.dp.toPx(), 8.dp.toPx()))
                }
                for (b in bookmarks) {
                    drawCircle(Color(0xFFFFE066), radius = 3.dp.toPx(), center = Offset(b.toFloat() / durationMs * size.width, cy - 5.dp.toPx()))
                }
            }
        }
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Orange))
        Box(
            Modifier.offset { IntOffset((fraction * widthPx - 8.dp.toPx()).roundToInt(), 0) }
                .size(16.dp).clip(CircleShape).background(Orange),
        )
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

/** 标题下方的 AI 任务状态小标签：识别中 / 翻译中 / 失败。 */
@Composable
private fun TaskChip(state: UiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val text = when {
        state.extractingEmbedded -> "读取视频自带字幕 ${((state.progress ?: 0f) * 100).toInt()}%"
        state.transcribing -> state.progress?.takeIf { it >= 0 && state.status.startsWith("正在识别") }
            ?.let { "AI 识别字幕中 ${(it * 100).toInt()}%" } ?: state.status
        state.translating -> "AI 翻译中 ${state.cues.count { it.translation != null }}/${state.cues.size}"
        state.failedTask != null -> if (state.failedTask == Task.TRANSCRIBE) "字幕识别失败，点此查看" else "翻译失败，点此查看"
        else -> return
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(if (state.failedTask != null) Color(0x55D93025) else Color(0x55F28C00))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.transcribing || state.translating || state.extractingEmbedded) {
            CircularProgressIndicator(Modifier.size(12.dp), color = Color.White, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = Color.White, fontSize = 12.sp)
    }
}

/** 画面上的 AI 字幕。译文行、原文行的样式和位置见 [StyledSubtitleLines]。 */
@Composable
private fun BoxScope.SubtitleOverlay(cue: Cue?, settings: AppSettings, bottomPadding: Dp, topPadding: Dp) {
    if (cue == null || !settings.showAiSubtitles) return
    // 原文已是目标语言时译文和原文相同，只显示一行
    val lines = if (cue.translation == cue.text) listOf(cue.text to true) else buildList {
        if (settings.showTranslation && cue.translation != null) add(cue.translation to true)
        if (settings.showOriginal || cue.translation == null) add(cue.text to false)
    }
    StyledSubtitleLines(lines, settings, bottomPadding, topPadding)
}

/**
 * 手势：单击显示/隐藏控制栏；双击左/右快退快进 10 秒、中间暂停；
 * 左半边上下滑调亮度、右半边调音量、横滑拖动进度；长按 2 倍速。
 */
@Composable
private fun GestureLayer(
    locked: Boolean,
    onTap: () -> Unit,
    onIndicator: (Pair<ImageVector?, String>) -> Unit,
    onSeekPreview: (Long?) -> Unit,
    speed: () -> Float,
    player: Player,
    onTogglePlay: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    var seekTarget by remember { mutableLongStateOf(0L) }
    var brightness by remember { mutableFloatStateOf(0.5f) }
    var volumeF by remember { mutableFloatStateOf(0f) }
    var longPressing by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(locked) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { offset ->
                        if (locked) return@detectTapGestures
                        val third = size.width / 3f
                        when {
                            offset.x < third -> { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); onIndicator(null to "« 10 秒") }
                            offset.x > third * 2 -> { player.seekTo(player.currentPosition + 10_000); onIndicator(null to "10 秒 »") }
                            else -> onTogglePlay()
                        }
                    },
                    onLongPress = {
                        if (locked || !player.isPlaying) return@detectTapGestures
                        longPressing = true
                        player.setPlaybackSpeed(2f)
                        onIndicator(null to "2 倍速播放中")
                    },
                    onPress = {
                        tryAwaitRelease()
                        if (longPressing) {
                            longPressing = false
                            player.setPlaybackSpeed(speed())
                        }
                    },
                )
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                var accX = 0f
                var accY = 0f
                var startX = 0f
                detectDragGestures(
                    onDragStart = { o ->
                        accX = 0f; accY = 0f; startX = o.x
                        dragMode = DragMode.NONE
                        seekTarget = player.currentPosition
                        val cur = activity?.window?.attributes?.screenBrightness ?: -1f
                        brightness = if (cur < 0) 0.5f else cur
                        volumeF = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                    },
                    onDragEnd = {
                        if (dragMode == DragMode.SEEK) player.seekTo(seekTarget)
                        dragMode = DragMode.NONE
                        onSeekPreview(null)
                    },
                    onDragCancel = { dragMode = DragMode.NONE; onSeekPreview(null) },
                    onDrag = { change, delta ->
                        change.consume()
                        accX += delta.x
                        accY += delta.y
                        if (dragMode == DragMode.NONE && (abs(accX) > 24 || abs(accY) > 24)) {
                            dragMode = when {
                                abs(accX) > abs(accY) -> DragMode.SEEK
                                startX < size.width / 2f -> DragMode.BRIGHTNESS
                                else -> DragMode.VOLUME
                            }
                        }
                        when (dragMode) {
                            DragMode.SEEK -> {
                                val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                seekTarget = (seekTarget + (delta.x / size.width * 120_000).toLong()).coerceIn(0, duration)
                                val diff = seekTarget - player.currentPosition
                                onIndicator(null to "${if (diff >= 0) "+" else "-"}${formatDuration(abs(diff).coerceAtLeast(1000))}  ${formatClock(seekTarget)}")
                                onSeekPreview(seekTarget)
                            }
                            DragMode.BRIGHTNESS -> {
                                brightness = (brightness - delta.y / size.height).coerceIn(0.01f, 1f)
                                activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = brightness } }
                                onIndicator(AppIcons.Brightness to "${(brightness * 100).roundToInt()}%")
                            }
                            DragMode.VOLUME -> {
                                volumeF = (volumeF - delta.y / size.height * maxVolume * 1.5f).coerceIn(0f, maxVolume.toFloat())
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, volumeF.roundToInt(), 0)
                                onIndicator(AppIcons.Volume to "${(volumeF / maxVolume * 100).roundToInt()}%")
                            }
                            DragMode.NONE -> {}
                        }
                    },
                )
            },
    )
}

// ---------------- 更多菜单 ----------------

private enum class MoreAction { LOCK, SLEEP, SPEED, GOTO, NAVIGATE, BOOKMARKS, SKIP, EQUALIZER, AUDIO_ONLY, PIP, REPEAT, INFO, AB_LOOP, SAVE_PLAYLIST, SETTINGS }

@Composable
private fun MorePanel(state: UiState, audioOnly: Boolean, repeatMode: Int, onAction: (MoreAction) -> Unit) {
    val items = listOf(
        Triple(MoreAction.LOCK, AppIcons.Lock, "锁定"),
        Triple(MoreAction.SLEEP, AppIcons.Timer, "睡眠计时器" + if (state.sleepAt != null) "（已开启）" else ""),
        Triple(MoreAction.SPEED, AppIcons.Speed, "播放速度"),
        Triple(MoreAction.GOTO, AppIcons.AccessTime, "转到时间"),
        Triple(MoreAction.NAVIGATE, AppIcons.GridView, "章节与片段"),
        Triple(MoreAction.BOOKMARKS, AppIcons.Star, "书签与关键帧"),
        Triple(MoreAction.SKIP, AppIcons.SkipNext, "片头片尾与跳过"),
        Triple(MoreAction.EQUALIZER, AppIcons.Equalizer, "均衡器"),
        Triple(MoreAction.AUDIO_ONLY, AppIcons.MusicNote, if (audioOnly) "恢复视频画面" else "作为音频播放"),
        Triple(MoreAction.PIP, AppIcons.Pip, "弹出播放器"),
        Triple(MoreAction.REPEAT, AppIcons.Repeat, "循环模式：${repeatLabel(repeatMode)}"),
        Triple(MoreAction.INFO, AppIcons.Info, "视频信息"),
        Triple(MoreAction.AB_LOOP, AppIcons.Repeat, if (state.loopA == null) "A-B 循环" else if (state.loopB == null) "A-B 循环：设置 B 点" else "关闭 A-B 循环"),
        Triple(MoreAction.SAVE_PLAYLIST, AppIcons.PlaylistAdd, "保存播放列表"),
        Triple(MoreAction.SETTINGS, AppIcons.Settings, "字幕与 AI 设置"),
    )
    Column(
        Modifier.fillMaxHeight().width(300.dp).background(Color(0xF2111111))
            .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
    ) {
        items.forEach { (action, icon, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { onAction(action) }.heightIn(min = 56.dp).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(24.dp))
                Text(label, color = Color.White, fontSize = 17.sp)
            }
        }
    }
}

private fun repeatLabel(mode: Int) = when (mode) {
    Player.REPEAT_MODE_ONE -> "单个循环"
    Player.REPEAT_MODE_ALL -> "列表循环"
    else -> "不循环"
}

private fun openEqualizer(context: Context, sessionId: Int) {
    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MOVIE)
    try {
        (context as? Activity)?.startActivity(intent) ?: throw IllegalStateException()
    } catch (e: Exception) {
        Toast.makeText(context, "这台设备没有系统均衡器", Toast.LENGTH_SHORT).show()
    }
}

private fun enterPip(activity: Activity?) {
    if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    runCatching {
        activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
    }.onFailure { Toast.makeText(activity, "这台设备不支持画中画", Toast.LENGTH_SHORT).show() }
}

// ---------------- 字幕与音轨面板 ----------------

private enum class SubtitleAction { GENERATE, TRANSLATE, LIST, DELAY, IMPORT, ONLINE, EXPORT, ERROR, ROLES, EMBEDDED, STYLE }

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun TrackPanel(vm: MainViewModel, state: UiState, onSubtitleAction: (SubtitleAction) -> Unit) {
    val player by vm.playerFlow.collectAsStateWithLifecycle()
    var audioOpen by remember { mutableStateOf(true) }
    var subtitleOpen by remember { mutableStateOf(true) }
    var embeddedOpen by remember { mutableStateOf(true) }
    var version by remember { mutableIntStateOf(0) } // 选轨后刷新

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SheetHeader("音频", audioOpen) { audioOpen = !audioOpen }
        if (audioOpen) {
            Text(
                "选过的音轨和字幕按视频记住，下次打开自动选好；首选语言在「设置 → 播放」里改",
                color = Color(0xFF999999), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp),
            )
            val groups = remember(version, state.current?.uri, player) { player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO } }
            val disabled = remember(version, state.current?.uri) { C.TRACK_TYPE_AUDIO in player.trackSelectionParameters.disabledTrackTypes }
            TrackRow("禁用轨道", disabled) {
                vm.extras.disableTrackType(C.TRACK_TYPE_AUDIO)
                version++
            }
            if (groups.isEmpty()) TrackRow("没有音轨", false, enabled = false) {}
            groups.forEachIndexed { gi, group ->
                for (i in 0 until group.length) {
                    val f = group.getTrackFormat(i)
                    val name = listOfNotNull(
                        f.label ?: f.language?.takeIf { it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage } ?: "Track ${gi + 1}",
                        f.sampleMimeType?.substringAfter('/')?.uppercase(),
                        f.channelCount.takeIf { it > 0 }?.let { "${it} 声道" },
                    ).joinToString(" · ")
                    TrackRow(name, !disabled && group.isTrackSelected(i)) {
                        vm.extras.selectTrack(C.TRACK_TYPE_AUDIO, group, i)
                        version++
                    }
                }
            }
        }
        HorizontalDivider(color = Color(0xFF333333))

        // 视频文件自带的字幕轨（MKV、蓝光、DVD 等）
        val textGroups = remember(version, state.current?.uri, player) { player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT } }
        if (textGroups.isNotEmpty()) {
            SheetHeader("视频自带字幕", embeddedOpen) { embeddedOpen = !embeddedOpen }
            if (embeddedOpen) {
                val textDisabled = remember(version, player) { C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes }
                TrackRow("不显示", textDisabled || textGroups.none { it.isSelected }) {
                    vm.extras.disableTrackType(C.TRACK_TYPE_TEXT)
                    version++
                }
                textGroups.forEachIndexed { gi, group ->
                    for (i in 0 until group.length) {
                        val f = group.getTrackFormat(i)
                        val name = f.label ?: f.language?.takeIf { it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage } ?: "字幕 ${gi + 1}"
                        TrackRow(name, !textDisabled && group.isTrackSelected(i)) {
                            vm.extras.selectTrack(C.TRACK_TYPE_TEXT, group, i)
                            version++
                        }
                    }
                }
            }
            HorizontalDivider(color = Color(0xFF333333))
        }

        SheetHeader("字幕", subtitleOpen) { subtitleOpen = !subtitleOpen }
        if (subtitleOpen) {
            val gen = when {
                state.transcribing -> "停止识别（${state.progress?.takeIf { it >= 0 }?.let { "${(it * 100).toInt()}%" } ?: "准备中"}）"
                state.cues.isEmpty() -> "AI 生成字幕"
                else -> "AI 重新识别字幕"
            }
            ActionRow(AppIcons.AutoAwesome, gen, subtitle = "用语音识别为当前视频生成带时间轴的字幕") { onSubtitleAction(SubtitleAction.GENERATE) }
            // 视频自带文字字幕轨时，直接拿来翻译，比语音识别准
            if (textGroups.isNotEmpty() || vm.usingVlc || state.extractingEmbedded) {
                ActionRow(
                    AppIcons.Subtitles,
                    if (state.extractingEmbedded) "停止读取（${state.progress?.let { "${(it * 100).toInt()}%" } ?: "准备中"}）" else "使用视频自带字幕",
                    subtitle = "把视频里的外语字幕轨读出来作为字幕，可以直接翻译、朗读",
                ) { onSubtitleAction(SubtitleAction.EMBEDDED) }
            }
            ActionRow(
                AppIcons.Translate,
                if (state.translating) "停止翻译" else "AI 翻译字幕",
                subtitle = "翻译成${state.settings.targetLanguage}",
                enabled = state.translating || state.cues.any { it.translation == null },
            ) { onSubtitleAction(SubtitleAction.TRANSLATE) }
            if (state.failedTask != null) {
                ActionRow(AppIcons.Info, "上次${if (state.failedTask == Task.TRANSCRIBE) "识别" else "翻译"}失败，查看原因", tint = Color(0xFFFF6B5E)) {
                    onSubtitleAction(SubtitleAction.ERROR)
                }
            }
            ActionRow(AppIcons.ListIcon, "字幕列表", subtitle = if (state.cues.isEmpty()) "还没有字幕" else "${state.cues.size} 句，点击可跳转、长按可复制", enabled = state.cues.isNotEmpty()) {
                onSubtitleAction(SubtitleAction.LIST)
            }
            SwitchRow(
                AppIcons.Subtitles, "在画面上显示 AI 字幕",
                subtitle = if (textGroups.isNotEmpty()) "视频自带字幕在上方「视频自带字幕」里单独开关" else "关掉后仍可翻译、朗读",
                checked = state.settings.showAiSubtitles,
            ) { vm.updateSettings(state.settings.copy(showAiSubtitles = it)) }
            Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                DisplayModeSelector(state.settings.showOriginal, state.settings.showTranslation) { o, t ->
                    vm.updateSettings(state.settings.copy(showOriginal = o, showTranslation = t))
                }
            }
            if (Pinyin.available && state.settings.targetIsChinese) {
                ActionRow(
                    AppIcons.Translate, if (state.settings.showPinyin) "关闭译文拼音" else "译文加拼音",
                    subtitle = "在中文译文的每个字上方标注拼音",
                ) { vm.updateSettings(state.settings.copy(showPinyin = !state.settings.showPinyin)) }
            }
            ActionRow(
                AppIcons.Volume, if (state.settings.ttsEnabled) "停止朗读译文" else "朗读译文",
                subtitle = "${state.settings.ttsEngine.label} · 跟随字幕时间朗读",
                enabled = state.settings.ttsEnabled || state.cues.any { it.translation != null },
            ) { vm.updateSettings(state.settings.copy(ttsEnabled = !state.settings.ttsEnabled)) }
            ActionRow(
                AppIcons.People, "角色与配音",
                subtitle = if (state.roles.isEmpty()) "识别说话人，给每个角色配不同的语音" else "${state.roles.size} 个角色",
                enabled = state.cues.isNotEmpty(),
            ) { onSubtitleAction(SubtitleAction.ROLES) }
            HorizontalDivider(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp), color = Color(0xFF333333))
            ActionRow(AppIcons.Edit, "字幕样式", subtitle = "译文、原文两行分别调字号、颜色、底板和位置") { onSubtitleAction(SubtitleAction.STYLE) }
            ActionRow(AppIcons.History, "字幕延迟", subtitle = if (state.subtitleDelayMs == 0L) null else "%+.1f 秒".format(state.subtitleDelayMs / 1000f)) {
                onSubtitleAction(SubtitleAction.DELAY)
            }
            ActionRow(AppIcons.File, "选择字幕文件", subtitle = "SRT / VTT / ASS") { onSubtitleAction(SubtitleAction.IMPORT) }
            ActionRow(AppIcons.Search, "在线下载字幕", subtitle = "射手网 / OpenSubtitles") { onSubtitleAction(SubtitleAction.ONLINE) }
            ActionRow(AppIcons.Download, "导出字幕", enabled = state.cues.isNotEmpty()) { onSubtitleAction(SubtitleAction.EXPORT) }
        }
    }
}

@Composable
private fun SheetHeader(title: String, open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color.White, fontSize = 20.sp, modifier = Modifier.weight(1f))
        Icon(if (open) AppIcons.ArrowDropUp else AppIcons.ArrowDropDown, null, tint = Color.White)
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(48.dp)) { if (selected) Icon(AppIcons.Check, null, tint = Color.White) }
        Text(label, color = if (enabled) Color.White else Color(0xFF888888), fontSize = 17.sp)
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, label: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 17.sp)
            if (subtitle != null) Text(subtitle, color = Color(0xFF999999), fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    val color = if (enabled) tint else Color(0xFF777777)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(24.dp))
        Column {
            Text(label, color = color, fontSize = 17.sp)
            if (subtitle != null) Text(subtitle, color = Color(0xFF999999), fontSize = 13.sp)
        }
    }
}

// ---------------- 字幕列表 ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CueList(
    cues: List<Cue>,
    current: Cue?,
    settings: AppSettings,
    roles: List<com.aisub.player.subtitle.Role>,
    onSeek: (Cue) -> Unit,
    onRetranslate: (Cue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(current?.id, settings.followPlayback) {
        if (!settings.followPlayback) return@LaunchedEffect
        val index = cues.indexOfFirst { it.id == current?.id }
        if (index >= 0) listState.animateScrollToItem((index - 2).coerceAtLeast(0))
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
        items(cues, key = { it.id }) { cue ->
            val active = cue.id == current?.id
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                        .background(if (active) Color(0x33F28C00) else Color.Transparent)
                        .combinedClickable(onClick = { onSeek(cue) }, onLongClick = { menu = true }),
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(if (active) Orange else Color.Transparent))
                    Column(Modifier.padding(start = 13.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
                        val speaker = cue.speaker?.let { id -> roles.firstOrNull { it.id == id }?.name }
                        Text(
                            "${formatTime(cue.startMs)} – ${formatTime(cue.endMs)}" + (speaker?.let { "  ·  $it" } ?: ""),
                            fontSize = 11.sp, color = Color(0xFF888888),
                        )
                        val sameText = cue.translation == cue.text
                        if (!sameText && (settings.showOriginal || cue.translation == null)) Text(cue.text, color = Color.White, fontSize = 15.sp)
                        if ((settings.showTranslation || sameText) && cue.translation != null) {
                            if (settings.pinyinActive) PinyinText(cue.translation, Orange, 15.sp)
                            else Text(cue.translation, color = Orange, fontSize = 15.sp)
                        }
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("从这里播放") }, onClick = { menu = false; onSeek(cue) })
                    DropdownMenuItem(text = { Text("复制原文") }, onClick = { menu = false; clipboard.setText(AnnotatedString(cue.text)) })
                    cue.translation?.let { t ->
                        DropdownMenuItem(text = { Text("复制译文") }, onClick = { menu = false; clipboard.setText(AnnotatedString(t)) })
                        DropdownMenuItem(text = { Text("复制双语") }, onClick = { menu = false; clipboard.setText(AnnotatedString("${cue.text}\n$t")) })
                    }
                    DropdownMenuItem(text = { Text("重新翻译这句") }, onClick = { menu = false; onRetranslate(cue) })
                }
            }
        }
    }
}

// ---------------- 对话框 ----------------

@Composable
private fun ChoiceDialog(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { i, label ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(36.dp)) { if (i == selected) Icon(AppIcons.Check, null, tint = Orange) }
                        Text(label, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun GotoDialog(onDismiss: () -> Unit, onGo: (Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = parseClock(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("转到时间") },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = true,
                label = { Text("时间，例如 12:30 或 1:02:03") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onGo) }, enabled = parsed != null) { Text("转到") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DelayDialog(delayMs: Long, onChange: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("字幕延迟") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("%+.1f 秒".format(delayMs / 1000f), fontSize = 28.sp, fontWeight = FontWeight.Medium)
                Text("字幕比声音早就调大，晚就调小", fontSize = 13.sp, color = Color(0xFF999999))
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(-1000L, -100L, 100L, 1000L).forEach { step ->
                        TextButton(onClick = { onChange(delayMs + step) }) { Text("%+.1f".format(step / 1000f)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = { onChange(0) }) { Text("重置") } },
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun InfoDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val p = vm.player
    val exo = p as? androidx.media3.exoplayer.ExoPlayer
    val v = exo?.videoFormat
    val a = exo?.audioFormat
    val entry = vm.state.value.current
    val rows = listOfNotNull(
        "文件" to (entry?.title ?: ""),
        "时长" to formatClock(p.duration.coerceAtLeast(0)),
        "播放内核" to if (exo != null) "ExoPlayer" else "VLC",
        if (exo == null && p.videoSize.width > 0) "画面" to "${p.videoSize.width}×${p.videoSize.height}" else null,
        v?.let { "画面" to "${it.width}×${it.height}" + (it.frameRate.takeIf { f -> f > 0 }?.let { f -> " · %.0f fps".format(f) } ?: "") },
        v?.let { "视频编码" to (it.sampleMimeType ?: "") + (it.codecs?.let { c -> " ($c)" } ?: "") },
        a?.let { "音频编码" to (it.sampleMimeType ?: "") },
        a?.let { "音频" to "${it.sampleRate} Hz · ${it.channelCount} 声道" },
        "地址" to (entry?.uri ?: ""),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("视频信息") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { (k, value) ->
                    Column {
                        Text(k, fontSize = 12.sp, color = Color(0xFF999999))
                        Text(value, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun parseClock(s: String): Long? {
    val parts = s.trim().split(':', '：').map { it.trim().toLongOrNull() ?: return null }
    if (parts.isEmpty() || parts.size > 3) return null
    return parts.fold(0L) { acc, v -> acc * 60 + v } * 1000
}

private fun formatSpeed(s: Float) = if (s == s.toInt().toFloat()) "${s.toInt()}.0" else s.toString()

private fun formatClock(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/** 字幕时间，精确到 0.1 秒，例如 01:23.4 或 1:02:03.4 */
private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val tenth = ms % 1000 / 100
    return if (total >= 3600) "%d:%02d:%02d.%d".format(total / 3600, total / 60 % 60, total % 60, tenth)
    else "%02d:%02d.%d".format(total / 60, total % 60, tenth)
}
