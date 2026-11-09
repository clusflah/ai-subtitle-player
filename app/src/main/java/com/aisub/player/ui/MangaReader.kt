package com.aisub.player.ui

import android.app.Activity
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aisub.player.manga.MangaBook
import com.aisub.player.manga.MangaImages
import com.aisub.player.manga.MangaMode
import com.aisub.player.manga.MangaPage
import com.aisub.player.manga.MangaSettings
import com.aisub.player.manga.MangaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private val ZOOMS = listOf(1f, 1.5f, 2f, 3f)

/**
 * 漫画阅读：黑底全屏，四种显示方式（适应屏幕、适应宽度、双页、长条）。
 * 遥控器：左右翻页（日漫从右往左时反过来），放大后方向键移动画面，确认键显示 / 隐藏控制栏，
 * 长按确认键或菜单键打开阅读设置，返回键退出。手机：点左右两边翻页、点中间显示控制栏，双指缩放。
 *
 * onOpenUrl：网页漫画的上一话、下一话、目录（打开网页；reader 为 true 时打开后接着用阅读模式看）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MangaReader(
    book: MangaBook,
    onExit: () -> Unit,
    startPage: Int? = null,
    onOpenUrl: ((url: String, reader: Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val tv = LocalTv.current
    val scope = rememberCoroutineScope()
    val pages = book.pages
    remember { MangaStore.init(context) }

    // 按屏幕定解码宽度：电视、手机都够放大两三倍
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    remember(config.screenWidthDp, config.screenHeightDp) {
        val w = with(density) { config.screenWidthDp.dp.toPx() }
        val h = with(density) { config.screenHeightDp.dp.toPx() }
        MangaImages.targetWidth = maxOf(w, h * 0.75f).toInt().coerceIn(720, 1600)
    }

    var settings by remember(book.site) { mutableStateOf(MangaStore.settings(book.site)) }
    fun update(s: MangaSettings) { settings = s; MangaStore.saveSettings(book.site, s) }
    // 没选过显示方式：看第一张图，很长（条漫）就用长条，否则适应屏幕
    var autoMode by remember(book.key) { mutableStateOf<MangaMode?>(null) }
    val mode = settings.mode ?: autoMode ?: MangaMode.FIT
    val rtl = settings.rtl

    val initial = remember(book.key) { (startPage ?: MangaStore.progress(book.key)).coerceIn(0, (pages.size - 1).coerceAtLeast(0)) }
    var page by remember(book.key) { mutableIntStateOf(initial) }
    var controls by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf<ReaderPanel?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val failed = remember(book.key) { mutableStateMapOf<Int, Boolean>() }
    var retryTick by remember { mutableIntStateOf(0) }
    /** 最后一页再按「下一页」：提示一次，再按进下一话 */
    var endHint by remember { mutableStateOf<String?>(null) }
    var boxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val rootFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }

    // 双页：封面单独时 [0] [1,2] [3,4]…，否则 [0,1] [2,3]…
    val units: List<List<Int>> = remember(pages.size, mode, settings.coverSingle) {
        if (mode != MangaMode.DOUBLE) pages.indices.map { listOf(it) }
        else {
            val list = ArrayList<List<Int>>()
            var i = 0
            if (settings.coverSingle && pages.isNotEmpty()) { list.add(listOf(0)); i = 1 }
            while (i < pages.size) { list.add(if (i + 1 < pages.size) listOf(i, i + 1) else listOf(i)); i += 2 }
            list
        }
    }
    fun unitOf(p: Int) = units.indexOfFirst { p in it }.coerceAtLeast(0)
    /** 最后有一张「本话完」：有下一话时在那里进下一话 */
    val hasEnd = book.next != null && onOpenUrl != null

    // 沉浸：隐藏状态栏、导航栏，阅读时屏幕不灭
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }

    // 控制栏一会儿没操作就藏起来
    LaunchedEffect(controls, lastInteraction, panel) {
        if (!controls || panel != null) return@LaunchedEffect
        delay(5_000)
        controls = false
    }
    LaunchedEffect(controls, panel) {
        if (panel != null) return@LaunchedEffect
        // 控制栏出来时焦点给它（电视上直接用方向键选按钮）；藏起来后焦点回到画面，方向键翻页
        delay(80)
        runCatching { if (controls && tv) controlsFocus.requestFocus() else rootFocus.requestFocus() }
    }
    LaunchedEffect(endHint) { if (endHint != null) { delay(2_500); endHint = null } }

    // 进度：停在一页半秒后记下；附近几页提前加载
    LaunchedEffect(page, book.key) {
        // 翻页后放大还原（长条模式的「放大」是加宽，一直保持）
        if (mode != MangaMode.STRIP) { zoom = 1f; pan = Offset.Zero }
        delay(500)
        MangaStore.saveProgress(book, page)
    }
    LaunchedEffect(page, mode, book.key) {
        val ahead = if (mode == MangaMode.DOUBLE) 4 else 2
        val order = (1..ahead).map { page + it } + listOf(page - 1, page - 2)
        launch(Dispatchers.IO) { MangaImages.preload(context, order.filter { it in pages.indices }.map { pages[it] }) }
    }

    val pager = key(mode, units.size, hasEnd, book.key) {
        rememberPagerState(unitOf(page)) { units.size + if (hasEnd) 1 else 0 }
    }
    val strip = key(mode, book.key) { rememberLazyListState(if (mode == MangaMode.STRIP) page else 0) }
    val widthScrolls = remember(book.key) { HashMap<Int, ScrollState>() }
    var landAtBottom by remember { mutableIntStateOf(-1) }

    if (mode == MangaMode.STRIP) {
        LaunchedEffect(strip) { snapshotFlow { strip.firstVisibleItemIndex }.collectLatest { if (it in pages.indices) page = it } }
    } else {
        LaunchedEffect(pager, units) {
            snapshotFlow { pager.currentPage }.collectLatest { u -> units.getOrNull(u)?.let { page = it.first() } }
        }
    }

    /** 跳到第 p 页（所有模式） */
    fun jumpTo(p: Int) {
        val target = p.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        page = target
        scope.launch {
            if (mode == MangaMode.STRIP) strip.scrollToItem(target) else pager.scrollToPage(unitOf(target))
        }
    }

    fun openChapter(url: String?) {
        if (url != null && onOpenUrl != null) onOpenUrl(url, true)
    }

    /** 下一页 / 上一页（forward = 往后读） */
    fun turn(forward: Boolean) {
        endHint = null
        if (mode == MangaMode.STRIP) {
            scope.launch { strip.animateScrollBy((if (forward) 1 else -1) * strip.layoutInfo.viewportSize.height * 0.85f) }
            return
        }
        val cur = pager.currentPage
        val target = if (forward) cur + 1 else cur - 1
        when {
            target < 0 -> if (book.prev != null && onOpenUrl != null) endHint = "已经是第一页" + "，按确认键看上一话" else endHint = "已经是第一页"
            target >= pager.pageCount -> if (cur == units.size && hasEnd) openChapter(book.next) else endHint = "已经是最后一页"
            else -> scope.launch { pager.scrollToPage(target) }
        }
    }

    /** 方向键：放大后先移动画面，到边了再翻页 */
    fun panOr(dx: Int, dy: Int, w: Float, h: Float): Boolean {
        if (zoom <= 1f) return false
        val maxX = (zoom - 1) * w / 2
        val maxY = (zoom - 1) * h / 2
        val next = Offset((pan.x - dx * w * 0.3f).coerceIn(-maxX, maxX), (pan.y - dy * h * 0.3f).coerceIn(-maxY, maxY))
        if (next == pan) return false
        pan = next
        return true
    }

    fun setZoom(z: Float) {
        zoom = z.coerceIn(1f, 3f)
        if (zoom == 1f) pan = Offset.Zero
    }

    fun showControls() { controls = true; lastInteraction = System.currentTimeMillis() }

    BackHandler {
        when {
            panel != null -> panel = null
            controls && tv -> controls = false
            zoom > 1f -> setZoom(1f)
            else -> onExit()
        }
    }

    // 不用 GlassBackdropBox：阅读页没有要采样背景的玻璃，整页大图每一帧再录一遍图层是白费
    Box(Modifier.fillMaxSize()) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .onSizeChanged { boxSize = it }
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                val ev = e.nativeKeyEvent
                lastInteraction = System.currentTimeMillis()
                if (panel != null) return@onPreviewKeyEvent false
                // 控制栏开着：方向键在按钮间移动（照常）
                if (controls && tv) {
                    if (ev.keyCode == KeyEvent.KEYCODE_MENU && ev.action == KeyEvent.ACTION_UP) { panel = ReaderPanel.SETTINGS; return@onPreviewKeyEvent true }
                    return@onPreviewKeyEvent false
                }
                readerKey(
                    ev, mode, rtl,
                    turn = ::turn,
                    pan = { dx, dy -> panOr(dx, dy, boxSize.width.toFloat(), boxSize.height.toFloat()) },
                    scrollWidth = { down ->
                        val s = widthScrolls[page]
                        val step = boxSize.height * 0.8f
                        if (s != null && (if (down) s.value < s.maxValue else s.value > 0)) {
                            scope.launch { s.animateScrollBy(if (down) step else -step) }
                            true
                        } else {
                            if (!down && pager.currentPage > 0) landAtBottom = units.getOrNull(pager.currentPage - 1)?.first() ?: -1
                            false
                        }
                    },
                    ok = {
                        when {
                            pager.currentPage == units.size && hasEnd && mode != MangaMode.STRIP -> openChapter(book.next)
                            mode == MangaMode.STRIP && hasEnd && !strip.canScrollForward -> openChapter(book.next)
                            endHint?.contains("上一话") == true -> openChapter(book.prev)
                            failed[page] == true -> retryTick++
                            else -> showControls()
                        }
                    },
                    menu = { panel = ReaderPanel.SETTINGS },
                )
            },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val onLoaded: (Int, ImageBitmap) -> Unit = { index, img ->
            failed.remove(index)
            if (settings.mode == null && autoMode == null) {
                autoMode = if (img.height > img.width * 2.2f) MangaMode.STRIP else MangaMode.FIT
            }
        }
        val onFailed: (Int) -> Unit = { failed[it] = true }

        Box(Modifier.fillMaxSize()) {
        if (pages.isEmpty()) {
            Text("没有找到漫画图片", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
        } else if (mode == MangaMode.STRIP) {
            // 长条：横屏（电视）时不铺满宽度，按高度的 0.8 倍宽，放大时变宽
            val landscape = w > h
            val base = if (landscape) minOf(w, h * 0.8f) else w
            val stripWidth = with(density) { (base * zoom).coerceAtMost(w).toDp() }
            LazyColumn(
                state = strip,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { if (controls) controls = false else showControls() }) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(pages.size, key = { it }) { i ->
                    MangaPageImage(
                        pages[i], i, retryTick, Modifier.width(stripWidth), fill = PageFill.WIDTH,
                        onLoaded = onLoaded, onFailed = onFailed,
                    )
                }
                item {
                    ChapterEnd(book, onOpenUrl, Modifier.width(stripWidth).height(220.dp))
                }
            }
        } else {
            val zoomable = mode == MangaMode.FIT || mode == MangaMode.DOUBLE
            val transform = rememberTransformableState { z, p, _ ->
                setZoom(zoom * z)
                if (zoom > 1f) {
                    val maxX = (zoom - 1) * w / 2
                    val maxY = (zoom - 1) * h / 2
                    pan = Offset((pan.x + p.x * zoom).coerceIn(-maxX, maxX), (pan.y + p.y * zoom).coerceIn(-maxY, maxY))
                }
            }
            HorizontalPager(
                pager, Modifier.fillMaxSize(),
                reverseLayout = rtl,
                userScrollEnabled = zoom <= 1f,
                beyondViewportPageCount = 1,
                key = { it },
            ) { u ->
                val current = u == pager.currentPage
                val tapModifier = Modifier.pointerInput(rtl, mode) {
                    detectTapGestures(
                        onTap = { o ->
                            // 点左右三分之一翻页，中间显示 / 隐藏控制栏
                            val third = size.width / 3f
                            when {
                                controls -> controls = false
                                o.x < third -> turn(rtl)
                                o.x > third * 2 -> turn(!rtl)
                                else -> showControls()
                            }
                        },
                        onDoubleTap = if (zoomable) { _: Offset -> setZoom(if (zoom > 1f) 1f else 2f) } else null,
                    )
                }
                if (u >= units.size) {
                    ChapterEnd(book, onOpenUrl, Modifier.fillMaxSize().then(tapModifier))
                    return@HorizontalPager
                }
                val unit = units[u]
                val layer = if (current && zoomable) Modifier.graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y) else Modifier
                val gestures = if (zoomable) Modifier.transformable(transform, canPan = { zoom > 1f }) else Modifier
                when (mode) {
                    MangaMode.WIDTH -> {
                        val i = unit.first()
                        val scroll = widthScrolls.getOrPut(i) { ScrollState(0) }
                        LaunchedEffect(scroll.maxValue, landAtBottom) {
                            if (landAtBottom == i && scroll.maxValue > 0) { scroll.scrollTo(scroll.maxValue); landAtBottom = -1 }
                        }
                        Box(Modifier.fillMaxSize().then(tapModifier).verticalScroll(scroll), contentAlignment = Alignment.TopCenter) {
                            MangaPageImage(pages[i], i, retryTick, Modifier.fillMaxWidth(), fill = PageFill.WIDTH, onLoaded = onLoaded, onFailed = onFailed)
                        }
                    }
                    MangaMode.DOUBLE -> Row(
                        Modifier.fillMaxSize().then(tapModifier).then(gestures).then(layer),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 日漫从右往左：第一页在右边
                        val ordered = if (rtl) unit.reversed() else unit
                        if (ordered.size == 1) {
                            MangaPageImage(pages[ordered[0]], ordered[0], retryTick, Modifier.fillMaxSize(), rotation = settings.rotation, onLoaded = onLoaded, onFailed = onFailed)
                        } else ordered.forEachIndexed { k, i ->
                            MangaPageImage(
                                pages[i], i, retryTick, Modifier.weight(1f).fillMaxHeight(),
                                align = if (k == 0) Alignment.CenterEnd else Alignment.CenterStart,
                                onLoaded = onLoaded, onFailed = onFailed,
                            )
                        }
                    }
                    else -> Box(Modifier.fillMaxSize().then(tapModifier).then(gestures).then(layer)) {
                        val i = unit.first()
                        MangaPageImage(pages[i], i, retryTick, Modifier.fillMaxSize(), rotation = settings.rotation, onLoaded = onLoaded, onFailed = onFailed)
                    }
                }
            }
        }
        }

        // 提示（已经是最后一页之类）
        endHint?.let {
            Text(
                it, color = Color.White,
                modifier = Modifier.align(Alignment.Center)
                    .liquidGlass(shape = RoundedCornerShape(14.dp), style = GlassStyle.Floating, tint = Color(0x8810141C))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        // 不显示控制栏时右下角小小的页码
        if (!controls && pages.isNotEmpty()) {
            Text(
                "${page + 1} / ${pages.size}", color = Color(0xD9FFFFFF), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                    .liquidGlass(shape = RoundedCornerShape(8.dp), style = GlassStyle.Pill, tint = Color(0x660D1117), enableBackdrop = false)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }

        AnimatedVisibility(controls, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0xB3000000))
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExit, modifier = Modifier.tvFocusRing(RoundedCornerShape(50))) { Icon(AppIcons.ArrowBack, "退出阅读", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(book.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    val percent = if (pages.size > 1) page * 100 / (pages.size - 1) else 100
                    Text("第 ${page + 1} / ${pages.size} 页 · $percent% · ${mode.label}${if (rtl) " · 从右往左" else ""}", color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall)
                }
                // 手机竖屏时底部按钮可能放不下；始终在顶部保留显示方式入口。
                IconButton(onClick = { panel = ReaderPanel.SETTINGS }, modifier = Modifier.tvFocusRing(RoundedCornerShape(50))) {
                    Icon(AppIcons.Settings, "阅读设置", tint = Color.White)
                }
            }
        }

        AnimatedVisibility(controls, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier.fillMaxWidth()
                    .liquidGlass(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), style = GlassStyle.Bar, tint = Color(0x780E121A))
                    .navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                if (!tv && pages.size > 1) {
                    // 手机：拖动跳页（日漫从右往左时滑块也反过来）
                    var dragging by remember { mutableStateOf<Float?>(null) }
                    val v = dragging ?: page.toFloat()
                    Slider(
                        value = if (rtl) (pages.size - 1) - v else v,
                        onValueChange = { dragging = if (rtl) (pages.size - 1) - it else it; lastInteraction = System.currentTimeMillis() },
                        onValueChangeFinished = { dragging?.let { jumpTo(it.toInt()) }; dragging = null },
                        valueRange = 0f..(pages.size - 1).toFloat(),
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val chapterNav = onOpenUrl != null
                    if (chapterNav && book.prev != null) ReaderButton("上一话") { openChapter(book.prev) }
                    // 上一页 / 下一页按钮按屏幕方向摆：从右往左读时「下一页」在左边
                    val back = @Composable { ReaderButton(if (rtl) "下一页" else "上一页", Modifier) { turn(rtl) } }
                    val fwd = @Composable { ReaderButton(if (rtl) "上一页" else "下一页", Modifier.focusRequester(controlsFocus)) { turn(!rtl) } }
                    back()
                    Text("${page + 1} / ${pages.size}", color = Color.White, modifier = Modifier.padding(horizontal = 6.dp))
                    fwd()
                    if (chapterNav && book.next != null) ReaderButton("下一话") { openChapter(book.next) }
                    ReaderButton("目录") { panel = ReaderPanel.THUMBS }
                    if (mode != MangaMode.WIDTH) {
                        ReaderButton("缩小") { setZoom(ZOOMS.lastOrNull { it < zoom - 0.01f } ?: 1f) }
                        ReaderButton("放大") { setZoom(ZOOMS.firstOrNull { it > zoom + 0.01f } ?: 3f) }
                    }
                    ReaderButton("设置") { panel = ReaderPanel.SETTINGS }
                }
                if (tv) Text(
                    "←→ 翻页 · 放大后方向键移动 · 确认键显示控制栏 · 长按确认 / 菜单键：阅读设置 · 返回退出",
                    color = Color(0x99FFFFFF), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp),
                )
            }
        }

        when (panel) {
            ReaderPanel.THUMBS -> ThumbnailPanel(pages, page, onPick = { jumpTo(it); panel = null; controls = false }, onClose = { panel = null })
            ReaderPanel.SETTINGS -> ReaderSettingsDialog(
                settings, mode, book.toc?.takeIf { onOpenUrl != null },
                onChange = ::update,
                onToc = { url -> panel = null; onOpenUrl?.invoke(url, false) },
                onDismiss = { panel = null },
            )
            null -> {}
        }
    }
    }
}

private enum class ReaderPanel { SETTINGS, THUMBS }

/** 阅读时的按键：返回 true 表示处理了 */
private fun readerKey(
    ev: KeyEvent,
    mode: MangaMode,
    rtl: Boolean,
    turn: (Boolean) -> Unit,
    pan: (Int, Int) -> Boolean,
    scrollWidth: (Boolean) -> Boolean,
    ok: () -> Unit,
    menu: () -> Unit,
): Boolean {
    val down = ev.action == KeyEvent.ACTION_DOWN
    // 按住左右连续翻页：系统每秒发十几次重复，太快了看不清，隔一下
    if (down && ev.repeatCount > 0 && ev.repeatCount % 3 != 0 && ev.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && ev.keyCode != KeyEvent.KEYCODE_ENTER) return true
    when (ev.keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
            if (!down) return true
            val right = ev.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (pan(if (right) 1 else -1, 0)) return true
            turn(right != rtl)
            return true
        }
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
            if (!down) return true
            val d = ev.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            if (pan(0, if (d) 1 else -1)) return true
            when (mode) {
                MangaMode.WIDTH -> if (!scrollWidth(d)) turn(d)
                // 长条：上下滚动；适应屏幕、双页：上一页 / 下一页
                else -> turn(d)
            }
            return true
        }
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_SPACE -> { if (down) turn(true); return true }
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) turn(false); return true }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            // 长按：阅读设置；短按（松开时）：显示控制栏
            if (down && ev.repeatCount == 3) menu()
            if (!down && !ev.isCanceled && (ev.flags and KeyEvent.FLAG_LONG_PRESS) == 0 && ev.eventTime - ev.downTime < 500) ok()
            return true
        }
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { if (!down) menu(); return true }
    }
    return false
}

private enum class PageFill { FIT, WIDTH }

/** 漫画的一页：加载中转圈，失败可重试（按确认键或点一下） */
@Composable
private fun MangaPageImage(
    page: MangaPage,
    index: Int,
    retryTick: Int,
    modifier: Modifier,
    fill: PageFill = PageFill.FIT,
    rotation: Int = 0,
    align: Alignment = Alignment.Center,
    onLoaded: (Int, ImageBitmap) -> Unit,
    onFailed: (Int) -> Unit,
) {
    val context = LocalContext.current
    var localRetry by remember { mutableIntStateOf(0) }
    // null：加载中；Result.failure：失败
    val state by produceState<Result<ImageBitmap>?>(MangaImages.cached(page)?.let { Result.success(it) }, page, retryTick, localRetry) {
        if (value?.isSuccess == true) return@produceState
        value = null
        var r = runCatching { MangaImages.load(context, page) }
        // 网络抖一下：自动再试一次
        if (r.isFailure) { delay(1_500); r = runCatching { MangaImages.load(context, page) } }
        value = r
    }
    LaunchedEffect(state) {
        state?.onSuccess { onLoaded(index, it) }?.onFailure { onFailed(index) }
    }
    val img = state?.getOrNull()
    if (img != null) {
        if (fill == PageFill.WIDTH) {
            Image(img, null, contentScale = ContentScale.FillWidth, modifier = modifier.aspectRatio(img.width.toFloat() / img.height.coerceAtLeast(1)))
        } else {
            Image(
                img, null, contentScale = ContentScale.Fit, alignment = align,
                modifier = modifier.then(if (rotation % 360 != 0) Modifier.rotated(rotation) else Modifier),
            )
        }
        return
    }
    // 还没加载好：竖版漫画大概的比例占位，长条模式下不会挤在一起
    val placeholder = if (fill == PageFill.WIDTH) modifier.aspectRatio(0.7f) else modifier
    Box(placeholder, contentAlignment = Alignment.Center) {
        if (state == null) CircularProgressIndicator(color = Color(0x99FFFFFF), modifier = Modifier.size(36.dp))
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("第 ${index + 1} 页加载失败", color = Color.Gray)
            Text(state?.exceptionOrNull()?.message.orEmpty(), color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { localRetry++ }) { Text("重试（确认键）") }
        }
    }
}

/** 旋转 90° / 270° 时宽高互换，转完正好放进原来的区域 */
private fun Modifier.rotated(degrees: Int): Modifier = layout { measurable, c ->
    val swap = (degrees / 90) % 2 != 0
    val w = c.maxWidth
    val h = c.maxHeight
    if (w == Constraints.Infinity || h == Constraints.Infinity) {
        val p = measurable.measure(c)
        return@layout layout(p.width, p.height) { p.place(0, 0) }
    }
    val p = measurable.measure(if (swap) Constraints.fixed(h, w) else Constraints.fixed(w, h))
    layout(w, h) {
        p.placeWithLayer((w - p.width) / 2, (h - p.height) / 2) { rotationZ = degrees.toFloat() }
    }
}

/** 一话的最后：有下一话就进下一话 */
@Composable
private fun ChapterEnd(book: MangaBook, onOpenUrl: ((String, Boolean) -> Unit)?, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("本话完", color = Color.White, style = MaterialTheme.typography.titleMedium)
            val next = book.next
            if (next != null && onOpenUrl != null) {
                Text("按确认键或右键进入下一话", color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                Button(onClick = { onOpenUrl(next, true) }, modifier = Modifier.padding(top = 12.dp)) { Text("下一话") }
            }
        }
    }
}

@Composable
private fun ReaderButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.tvFocusRing(RoundedCornerShape(50))) { Text(label, color = Color.White) }
}

/** 缩略图：快速跳到某一页 */
@Composable
private fun ThumbnailPanel(pages: List<MangaPage>, current: Int, onPick: (Int) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val grid = rememberLazyGridState((current - 4).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxSize()
            .liquidGlass(shape = RoundedCornerShape(0.dp), style = GlassStyle.Sheet, tint = Color(0xCC0D1117))
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.tvFocusRing(RoundedCornerShape(50))) { Icon(AppIcons.ArrowBack, "返回", tint = Color.White) }
            Text("目录 · 共 ${pages.size} 页", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        LazyVerticalGrid(GridCells.Adaptive(110.dp), state = grid, modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            itemsIndexed(pages, key = { i, _ -> i }) { i, p ->
                val thumb by produceState(MangaImages.cachedThumb(p), p) { if (value == null) value = MangaImages.thumb(context, p) }
                Column(
                    Modifier.padding(4.dp)
                        .then(if (i == current) Modifier.focusRequester(focus) else Modifier)
                        .tvFocusRing(RoundedCornerShape(8.dp), scale = true)
                        .clickable { onPick(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(0.7f).background(if (i == current) Color(0xFF5A3A00) else Color(0xFF222222), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val t = thumb
                        if (t != null) Image(t, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        else CircularProgressIndicator(color = Color(0x66FFFFFF), modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Text("${i + 1}", color = if (i == current) MaterialTheme.colorScheme.primary else Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 阅读设置：显示方式、翻页方向、双页封面、旋转 */
@Composable
private fun ReaderSettingsDialog(
    settings: MangaSettings,
    mode: MangaMode,
    toc: String?,
    onChange: (MangaSettings) -> Unit,
    onToc: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 先更新弹窗内的状态，让单选圆点在写入设置的同一帧切换；外层重组后会用实际模式同步。
    var selectedMode by remember(mode) { mutableStateOf(mode) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("阅读设置") },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.tvFocusRing(RoundedCornerShape(50))) { Text("完成") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).widthIn(max = 480.dp)) {
                MangaMode.entries.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().tvFocusRing(RoundedCornerShape(8.dp)).clickable {
                            selectedMode = m
                            onChange(settings.copy(mode = m))
                        }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = m == selectedMode, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(m.label, style = MaterialTheme.typography.bodyLarge)
                            Text(m.hint, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SettingToggle("从右往左翻页", "日本漫画的阅读顺序", settings.rtl) { onChange(settings.copy(rtl = it)) }
                if (selectedMode == MangaMode.DOUBLE) SettingToggle("封面单独一页", "跨页对不齐时切换试试", settings.coverSingle) { onChange(settings.copy(coverSingle = it)) }
                if (selectedMode == MangaMode.FIT || selectedMode == MangaMode.DOUBLE) Row(
                    Modifier.fillMaxWidth().tvFocusRing(RoundedCornerShape(8.dp))
                        .clickable { onChange(settings.copy(rotation = (settings.rotation + 90) % 360)) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("旋转图片", style = MaterialTheme.typography.bodyLarge)
                        Text("横版跨页在竖屏上看", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${settings.rotation}°", style = MaterialTheme.typography.bodyLarge)
                }
                if (settings.mode != null) TextButton(
                    onClick = { onChange(settings.copy(mode = null)) },
                    modifier = Modifier.tvFocusRing(RoundedCornerShape(50)),
                ) { Text("显示方式改回自动（条漫用长条，其余适应屏幕）") }
                if (toc != null) TextButton(onClick = { onToc(toc) }, modifier = Modifier.tvFocusRing(RoundedCornerShape(50))) { Text("打开网页上的章节目录") }
                Text(
                    "设置按网站分别记住。", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
    )
}

@Composable
private fun SettingToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().tvFocusRing(RoundedCornerShape(8.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
