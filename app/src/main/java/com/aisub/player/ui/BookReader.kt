package com.aisub.player.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aisub.player.MainViewModel
import androidx.media3.common.util.UnstableApi
import com.aisub.player.book.BookChapter
import com.aisub.player.book.BookBookmark
import com.aisub.player.book.BookRecent
import com.aisub.player.book.BookStore
import com.aisub.player.book.BookTts
import com.aisub.player.book.Ebook
import com.aisub.player.manga.MangaImages
import com.aisub.player.manga.MangaPage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** 电子书首页：正在朗读、继续阅读、打开电子书文件 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun BooksScreen(onBack: () -> Unit, open: (title: String, url: String) -> Unit) {
    val context = LocalContext.current
    remember { BookStore.init(context) }
    val recent by BookStore.recent.collectAsStateWithLifecycle()
    val tts by BookTts.status.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        open(name?.substringBeforeLast('.') ?: "电子书", uri.toString())
    }

    DetailScaffold("电子书", onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (tts.key != null) item {
                SectionHeader("正在朗读")
                SurfaceCard(Modifier.padding(horizontal = 16.dp)) {
                    ListRow(
                        title = tts.title,
                        subtitle = tts.chapter.ifEmpty { if (tts.playing) "朗读中" else "已暂停" },
                        icon = AppIcons.Volume,
                        trailing = {
                            Row {
                                IconButton(onClick = { BookTts.toggle() }) { Icon(if (tts.playing) AppIcons.Pause else AppIcons.PlayArrow, if (tts.playing) "暂停" else "继续") }
                                IconButton(onClick = { BookTts.stop() }) { Icon(AppIcons.Close, "停止朗读") }
                            }
                        },
                        onClick = { recent.firstOrNull { it.key == tts.key }?.let { open(it.title, it.location) } },
                    )
                }
            }
            item {
                SectionHeader("打开电子书")
                SurfaceCard(Modifier.padding(horizontal = 16.dp)) {
                    ListRow("TXT / EPUB / MOBI / AZW3 文件", "手机里的电子书；网盘、WebDAV 里的直接在文件列表里点开，文件管理器里也可以「用译幕打开」。有 DRM 的 Kindle 书打不开", AppIcons.MenuBook, subtitleMaxLines = 3) {
                        runCatching {
                            pick.launch(arrayOf("text/plain", "application/epub+zip", "application/x-mobipocket-ebook", "application/vnd.amazon.ebook", "application/vnd.amazon.mobi8-ebook", "application/octet-stream"))
                        }.onFailure { Toast.makeText(context, "这台设备没有系统文件选择器，可以把书放到网盘、WebDAV 里打开", Toast.LENGTH_LONG).show() }
                    }
                }
            }
            val favorites = recent.filter { it.favorite }
            if (favorites.isNotEmpty()) {
                item { SectionHeader("我的收藏") }
                items(favorites, key = { "favorite:" + it.key }) { r ->
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
                        SurfaceCard {
                            ListRow(
                                r.title, recentText(r), AppIcons.MenuBook,
                                trailing = { IconButton(onClick = { BookStore.toggleFavorite(r.key) }) { Icon(AppIcons.Star, "取消收藏") } },
                                onClick = { open(r.title, r.location) },
                            )
                        }
                    }
                }
            }
            item { SectionHeader("继续阅读") }
            if (recent.isEmpty()) item {
                Text(
                    "还没有读过电子书。读过的书会记在这里，下次从上次读到的地方接着读。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(recent, key = { it.key }) { r ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
                    SurfaceCard {
                        ListRow(
                            title = r.title,
                            subtitle = recentText(r),
                            icon = AppIcons.MenuBook,
                            trailing = {
                                Row {
                                    IconButton(onClick = { BookStore.toggleFavorite(r.key) }) {
                                        Icon(if (r.favorite) AppIcons.Star else AppIcons.StarBorder, if (r.favorite) "取消收藏" else "收藏电子书")
                                    }
                                    IconButton(onClick = { BookStore.remove(r.key) }) { Icon(AppIcons.Close, "从列表移除") }
                                }
                            },
                            onClick = { open(r.title, r.location) },
                        )
                    }
                }
            }
            item {
                Text(
                    "朗读：阅读时点屏幕中间打开工具栏，点「朗读」从当前位置读，长按某一段从那一段读。用的是设置里的朗读引擎（Azure 没填 Key 时用系统 TTS），锁屏、切到别的 App 也会接着读。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }
}

private fun recentText(r: BookRecent): String {
    val pct = if (r.total > 0) (r.item + 1) * 100 / r.total else 0
    val days = ((System.currentTimeMillis() - r.time) / 86_400_000L).toInt()
    val time = when (days) { 0 -> "今天"; 1 -> "昨天"; else -> "$days 天前" }
    return listOf(r.chapter, "$pct%", time).filter { it.isNotEmpty() }.joinToString(" · ")
}

private data class BookTheme(val background: Color, val text: Color, val muted: Color, val highlight: Color)

private val THEMES = listOf(
    BookTheme(Color(0xFFFFFFFF), Color(0xFF222222), Color(0xFF888888), Color(0x55FFC94D)),
    BookTheme(Color(0xFFF4ECD8), Color(0xFF3B3024), Color(0xFF8C7B63), Color(0x66E0A84A)),
    BookTheme(Color(0xFF141414), Color(0xFFB9B9B9), Color(0xFF6E6E6E), Color(0x55C08A2E)),
)
private val THEME_NAMES = listOf("白天", "护眼", "夜间")
private val SPEEDS = listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** 一块在整本书里的位置：第几章，章里第几块已经连起来数成 [index]（和朗读用的一样） */
private data class BookRow(val index: Int, val chapter: Int, val block: com.aisub.player.book.BookBlock)

/**
 * 读一本电子书：上下滚动，点屏幕中间显示工具栏（目录、字号、主题、朗读），长按一段从那里朗读。
 * [url] 是打开它的地址（记进度、取插图用）
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun BookReaderScreen(title: String, url: String, book: Ebook, onBack: () -> Unit) {
    val context = LocalContext.current
    remember { BookStore.init(context) }
    val prefs by BookStore.settings.collectAsStateWithLifecycle()
    val bookmarks by BookStore.bookmarks.collectAsStateWithLifecycle()
    val tts by BookTts.status.collectAsStateWithLifecycle()
    val theme = THEMES[prefs.theme.coerceIn(0, THEMES.lastIndex)]
    val rows = remember(book) {
        val out = ArrayList<BookRow>()
        book.chapters.forEachIndexed { ci, ch -> ch.blocks.forEach { b -> out.add(BookRow(out.size, ci, b)) } }
        out
    }
    val coverEntry = remember(book) {
        book.chapters.asSequence().flatMap { it.blocks.asSequence() }.mapNotNull { it.image }.firstOrNull()
    }
    val chapterStart = remember(book) {
        var i = 0
        book.chapters.map { ch -> i.also { i += ch.blocks.size } }
    }
    val saved = remember(url) { BookStore.progress(url) }
    val list = rememberLazyListState(
        (saved?.item ?: 0).coerceIn(0, (rows.size - 1).coerceAtLeast(0)), saved?.offset ?: 0,
    )
    val scope = rememberCoroutineScope()
    var controls by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showReadSettings by remember { mutableStateOf(false) }
    var showVoicePicker by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    val reading = tts.key == url

    fun chapterAt(index: Int): Int = rows.getOrNull(index)?.chapter ?: 0

    fun save() {
        val i = list.firstVisibleItemIndex
        BookStore.save(
            BookRecent(
                key = url, title = title, location = url, item = i, offset = list.firstVisibleItemScrollOffset,
                total = rows.size, chapter = book.chapters.getOrNull(chapterAt(i))?.title.orEmpty(), time = System.currentTimeMillis(),
                coverEntry = coverEntry,
            ),
        )
    }

    // 进度：滚动停下一会儿就记一下，离开时也记
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.debounce(800).collectLatest { save() }
    }
    DisposableEffect(Unit) { onDispose { save() } }

    // 朗读时跟着滚：读到的那段不在屏幕上就滚过去（手动滚过的 8 秒内不跟）
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(list.isScrollInProgress) {
        if (list.isScrollInProgress && !autoScrolling) lastUserScroll = System.currentTimeMillis()
    }
    LaunchedEffect(tts.block, reading) {
        if (!reading || tts.block < 0 || !tts.playing) return@LaunchedEffect
        if (System.currentTimeMillis() - lastUserScroll < 8_000) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo
        val shown = visible.firstOrNull { it.index == tts.block }
        val viewport = list.layoutInfo.viewportEndOffset
        if (shown == null || shown.offset + shown.size > viewport * 0.85 || shown.offset < 0) {
            autoScrolling = true
            runCatching { list.animateScrollToItem(tts.block, -(viewport / 4)) }
            autoScrolling = false
        }
    }

    BackHandler(enabled = showToc || showBookmarks || controls || showReadSettings || showVoicePicker) {
        when {
            showVoicePicker -> showVoicePicker = false
            showReadSettings -> showReadSettings = false
            showBookmarks -> showBookmarks = false
            showToc -> showToc = false
            else -> controls = false
        }
    }

    fun speakFrom(index: Int) {
        BookTts.start(context, url, title, book.chapters, index)
    }

    fun toggleCurrentBookmark() {
        val item = list.firstVisibleItemIndex
        val chapter = book.chapters.getOrNull(chapterAt(item))?.title.orEmpty()
        val preview = rows.getOrNull(item)?.block?.text?.replace(Regex("\\s+"), " ")?.take(80).orEmpty()
        BookStore.toggleBookmark(url, item, list.firstVisibleItemScrollOffset, chapter, preview)
    }

    val tapModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(onTap = { controls = !controls })
    }

    GlassBackdropBox(Modifier.fillMaxSize()) { backdrop ->
    Box(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent { e ->
                // 遥控器：上下翻一屏，确认键显示工具栏
                if (e.type != KeyEventType.KeyDown || controls || showToc) return@onPreviewKeyEvent false
                val page = list.layoutInfo.viewportEndOffset * 0.85f
                when (e.key) {
                    Key.DirectionDown, Key.PageDown -> { scope.launch { list.animateScrollBy(page) }; true }
                    Key.DirectionUp, Key.PageUp -> { scope.launch { list.animateScrollBy(-page) }; true }
                    Key.DirectionCenter, Key.Enter, Key.Menu -> { controls = true; true }
                    else -> false
                }
            },
    ) {
        Box(Modifier.fillMaxSize().glassBackdropSource(backdrop).background(theme.background)) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().then(tapModifier),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 48.dp, bottom = if (reading) 140.dp else 72.dp),
            ) {
                items(rows, key = { it.index }) { row ->
                    val b = row.block
                    val image = b.image
                    if (image != null) {
                        BookImage(url, image, Modifier.fillMaxWidth().padding(vertical = 10.dp))
                        return@items
                    }
                    val highlight = reading && tts.block == row.index
                    val text = if (highlight) buildAnnotatedString {
                        val s = tts.start.coerceIn(0, b.text.length)
                        val e = tts.end.coerceIn(s, b.text.length)
                        append(b.text.substring(0, s))
                        withStyle(SpanStyle(background = theme.highlight)) { append(b.text.substring(s, e)) }
                        append(b.text.substring(e))
                    } else buildAnnotatedString { append(b.text) }
                    val size = if (b.heading) prefs.fontSize + 5 else prefs.fontSize
                    val chapterTop = row.index == chapterStart.getOrNull(row.chapter) && row.index > 0
                    Text(
                        text,
                        color = theme.text,
                        fontSize = size.sp,
                        lineHeight = (size * prefs.lineSpacing).sp,
                        fontWeight = if (b.heading) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth()
                            .padding(top = if (chapterTop) 36.dp else if (b.heading) 16.dp else 0.dp, bottom = if (b.heading) 14.dp else (prefs.fontSize * 0.6f).dp)
                            .pointerInput(row.index) {
                                detectTapGestures(onTap = { controls = !controls }, onLongPress = { speakFrom(row.index) })
                            },
                    )
                }
            }
        }

        // 顶上一行：章名
        val current = chapterAt(list.firstVisibleItemIndex)
        Text(
            book.chapters.getOrNull(current)?.title.orEmpty(),
            color = theme.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(horizontal = 22.dp, vertical = 8.dp),
        )

        if (controls) {
            Surface(
                color = Color.Transparent,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .liquidGlass(shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp), style = GlassStyle.Bar),
            ) {
                Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.tvFocusRing(RoundedCornerShape(50))) { Icon(AppIcons.ArrowBack, "返回") }
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showBookmarks = true }, modifier = Modifier.tvFocusRing(RoundedCornerShape(50))) {
                        Icon(AppIcons.Bookmark, "书签（${bookmarks.count { it.key == url }}）")
                    }
                    IconButton(onClick = { showToc = true }, modifier = Modifier.tvFocusRing(RoundedCornerShape(50))) { Icon(AppIcons.ListIcon, "目录") }
                }
            }
            Surface(
                color = Color.Transparent,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .liquidGlass(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), style = GlassStyle.Bar),
            ) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    val pct = if (rows.isEmpty()) 0 else (list.firstVisibleItemIndex + 1) * 100 / rows.size
                    Text(
                        "${book.chapters.getOrNull(current)?.title.orEmpty()} · $pct%",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        @Composable
                        fun toolbarButton(label: String, onClick: () -> Unit) {
                            TextButton(
                                onClick = onClick,
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f).tvFocusRing(RoundedCornerShape(50)),
                            ) { Text(label, maxLines = 1, softWrap = false) }
                        }
                        toolbarButton("A-") { BookStore.update { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(12)) } }
                        toolbarButton("A+") { BookStore.update { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(36)) } }
                        toolbarButton("行距") { BookStore.update { it.copy(lineSpacing = if (it.lineSpacing >= 2.1f) 1.4f else it.lineSpacing + 0.35f) } }
                        toolbarButton(THEME_NAMES[prefs.theme.coerceIn(0, 2)]) { BookStore.update { it.copy(theme = (it.theme + 1) % THEMES.size) } }
                        val marked = bookmarks.any { it.key == url && it.item == list.firstVisibleItemIndex }
                        toolbarButton(if (marked) "取消书签" else "书签") { toggleCurrentBookmark() }
                        toolbarButton("朗读") {
                            controls = false
                            if (!reading) speakFrom(list.firstVisibleItemIndex)
                        }
                    }
                }
            }
        }

        // 朗读条
        if (reading && !controls) {
            Surface(
                color = Color.Transparent, shape = RoundedCornerShape(22.dp),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth()
                    .liquidGlass(shape = RoundedCornerShape(22.dp), style = GlassStyle.Floating),
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    val err = tts.error
                    if (err != null) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { BookTts.prev() }, modifier = Modifier.tvFocusRing(RoundedCornerShape(50))) { Icon(AppIcons.SkipPrevious, "上一句") }
                        Box(contentAlignment = Alignment.Center) {
                            IconButton(onClick = { BookTts.toggle() }, modifier = Modifier.tvFocusRing(RoundedCornerShape(50))) {
                                Icon(if (tts.playing) AppIcons.Pause else AppIcons.PlayArrow, if (tts.playing) "暂停" else "继续")
                            }
                            if (tts.playing && tts.buffering) CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 2.dp)
                        }
                        IconButton(onClick = { BookTts.next() }, modifier = Modifier.tvFocusRing(RoundedCornerShape(50))) { Icon(AppIcons.SkipNext, "下一句") }
                        TextButton(onClick = {
                            val i = SPEEDS.indexOfFirst { it >= tts.speed - 0.01f }.let { if (it < 0) 0 else it }
                            BookTts.setSpeed(SPEEDS[(i + 1) % SPEEDS.size])
                        }, modifier = Modifier.tvFocusRing(RoundedCornerShape(50))) { Text("${"%.2f".format(tts.speed).trimEnd('0').trimEnd('.')}x") }
                        Box {
                            TextButton(onClick = { sleepMenu = true }, modifier = Modifier.tvFocusRing(RoundedCornerShape(50))) {
                                Icon(AppIcons.Timer, "定时停止", Modifier.size(18.dp))
                                val left = if (tts.sleepAt > 0) ((tts.sleepAt - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1) else 0
                                if (tts.sleepChapterEnd) Text(" 本章") else if (left > 0) Text(" ${left}分")
                            }
                            AppMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                                listOf(15 to "15 分钟后", 30 to "30 分钟后", 60 to "1 小时后", 90 to "1.5 小时后", -1 to "读完这一章", 0 to "不定时").forEach { (m, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = { sleepMenu = false; BookTts.setSleep(m) })
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { showReadSettings = true }, modifier = Modifier.tvFocusRing(RoundedCornerShape(50))) {
                            Icon(AppIcons.Settings, "朗读设置")
                        }
                        IconButton(onClick = { BookTts.stop() }, modifier = Modifier.tvFocusRing(RoundedCornerShape(50))) { Icon(AppIcons.Close, "停止朗读") }
                    }
                }
            }
        }

        if (showToc) TocDialog(book.chapters, book.volumes, current, onDismiss = { showToc = false }) { ci ->
            showToc = false
            controls = false
            scope.launch { list.scrollToItem(chapterStart.getOrElse(ci) { 0 }) }
        }
        if (showBookmarks) BookmarkDialog(bookmarks.filter { it.key == url }, onDismiss = { showBookmarks = false }, onPick = { bookmark ->
            showBookmarks = false
            controls = false
            scope.launch { list.scrollToItem(bookmark.item.coerceIn(0, (rows.size - 1).coerceAtLeast(0)), bookmark.offset) }
        }, onRemove = BookStore::removeBookmark)
        if (showReadSettings) {
            AppDialog(
                onDismissRequest = { showReadSettings = false },
                title = { Text("朗读设置") },
                text = {
                    Column {
                        Row(Modifier.fillMaxWidth().clickable { BookStore.update { it.copy(skipFrontMatter = !it.skipFrontMatter) } }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(prefs.skipFrontMatter, onCheckedChange = { BookStore.update { s -> s.copy(skipFrontMatter = it) } })
                            Column(Modifier.padding(start = 8.dp)) {
                                Text("跳过目录与版权信息")
                                Text("下次开始朗读时生效", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(true, onCheckedChange = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text("统一旁白")
                                Text("电子书不进行角色识别，使用一个选定语音朗读", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TextButton(onClick = { showReadSettings = false; showVoicePicker = true }) { Text("选择朗读语音") }
                    }
                },
                confirmButton = { TextButton(onClick = { showReadSettings = false }) { Text("完成") } },
            )
        }
        if (showVoicePicker) {
            val main: MainViewModel = viewModel()
            val settings by main.state.collectAsStateWithLifecycle()
            VoicePickerDialog(
                main, settings.settings, "朗读语音", prefs.ttsVoice ?: settings.settings.ttsVoices[settings.settings.ttsEngine],
                allowAuto = true, onDismiss = { showVoicePicker = false },
            ) { voice ->
                BookStore.update { it.copy(ttsVoice = voice) }
                showVoicePicker = false
            }
        }
    }
    }
}

@Composable
private fun BookmarkDialog(bookmarks: List<BookBookmark>, onDismiss: () -> Unit, onPick: (BookBookmark) -> Unit, onRemove: (Long) -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("书签（${bookmarks.size}）") },
        text = {
            if (bookmarks.isEmpty()) {
                Text("还没有书签。在阅读工具栏点「书签」即可保存当前位置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(bookmarks, key = { it.id }) { bookmark ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(bookmark) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(bookmark.chapter.ifBlank { "当前位置" }, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (bookmark.preview.isNotBlank()) Text(bookmark.preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onRemove(bookmark.id) }) { Icon(AppIcons.Delete, "删除书签") }
                    }
                    HorizontalDivider()
                }
            }
        },
    )
}

@Composable
private fun TocDialog(chapters: List<BookChapter>, volumes: List<com.aisub.player.book.BookVolume>, current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberLazyListState((current - 3).coerceAtLeast(0))
    AppDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("目录（${chapters.size}）") },
        text = {
            LazyColumn(state = state, modifier = Modifier.heightIn(max = 480.dp)) {
                if (volumes.isNotEmpty()) {
                    item { Text("识别到 ${volumes.size} 册", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(volumes) { volume ->
                        Text(volume.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(volume.firstChapter) }.tvFocusRing().padding(vertical = 8.dp, horizontal = 4.dp))
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
                }
                itemsIndexed(chapters) { i, ch ->
                    Text(
                        ch.title,
                        color = if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (i == current) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(i) }.tvFocusRing().padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                    if (i < chapters.lastIndex) HorizontalDivider()
                }
            }
        },
    )
}

/** 书里的插图：和漫画一样从压缩包 / MOBI 里取 */
@Composable
private fun BookImage(url: String, entry: String, modifier: Modifier) {
    val context = LocalContext.current
    val page = remember(url, entry) { MangaPage(url, entry = entry) }
    val bmp by produceState<ImageBitmap?>(MangaImages.cached(page), page) {
        if (value == null) value = runCatching { MangaImages.load(context, page) }.getOrNull()
    }
    val b = bmp
    if (b == null) Box(modifier.height(120.dp)) else Image(b, null, modifier, contentScale = ContentScale.FillWidth)
}
