package com.aisub.player.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.aisub.player.web.CaptureService
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import com.aisub.player.asr.AsrLanguages
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aisub.player.AppSettings
import com.aisub.player.MainViewModel
import com.aisub.player.media.MediaEntry
import com.aisub.player.subtitle.Cue
import com.aisub.player.subtitle.cueAt
import com.aisub.player.web.WebCaptionState
import com.aisub.player.web.WebViewModel
import kotlinx.coroutines.delay

/** 首页上的常用视频网站 */
private val QUICK_SITES = listOf(
    "哔哩哔哩" to "https://m.bilibili.com",
    "YouTube" to "https://m.youtube.com",
    "西瓜视频" to "https://www.ixigua.com",
    "优酷" to "https://www.youku.com",
    "腾讯视频" to "https://m.v.qq.com",
    "爱奇艺" to "https://m.iqiyi.com",
)

/**
 * 网页：在内置浏览器里看视频（需要登录、只能在网页里放、播放器接不进来的），
 * 点「AI 字幕」后边播边识别、翻译，字幕叠在网页视频的下沿，网页全屏时叠在全屏画面上。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(main: MainViewModel, onBack: () -> Unit, play: (List<MediaEntry>, Int) -> Unit) {
    val vm: WebViewModel = viewModel()
    val context = LocalContext.current
    val activity = context as? Activity
    val captionState by vm.captions.state.collectAsStateWithLifecycle()
    val sniffed by vm.sniffed.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val rect by vm.videoRect.collectAsStateWithLifecycle()
    val videoAspect by vm.videoAspect.collectAsStateWithLifecycle()
    val settings = main.state.collectAsStateWithLifecycle().value.settings

    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val script = remember { context.assets.open("web_captions.js").bufferedReader().use { it.readText() } }

    // ---- 标签页 ----
    var nextTabId by remember { mutableLongStateOf(1L) }
    val tabs = remember {
        mutableStateListOf<WebTab>().apply { vm.savedTabs().first.forEach { add(WebTab(nextTabId++, it)) } }
    }
    var activeIndex by remember { mutableIntStateOf(vm.savedTabs().second.coerceIn(0, tabs.lastIndex)) }
    val tab = tabs[activeIndex.coerceIn(0, tabs.lastIndex)]
    LaunchedEffect(Unit) { tab.active = true }
    fun saveTabs() = vm.saveTabs(tabs.map { it.pageUrl ?: it.url }, activeIndex)

    var address by remember { mutableStateOf(tab.pageUrl.orEmpty()) }
    var editing by remember { mutableStateOf(false) }
    /** 地址栏里正在输入的内容（含选中范围） */
    var field by remember { mutableStateOf(TextFieldValue(address)) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    var fullscreen by remember { mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null) }
    var showSniffed by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<WebDialog?>(null) }
    /** 网页全屏时横屏还是竖屏（竖着拍的视频可以切成竖屏） */
    var fullscreenLandscape by remember { mutableStateOf(true) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var zoom by remember { mutableIntStateOf(100) }
    // 每个网站、横竖屏分别记住缩放；网页脚本每 250 毫秒读一次
    LaunchedEffect(tab.pageUrl?.let { Uri.parse(it).host }, landscape) { zoom = vm.zoomFor(tab.pageUrl, landscape) }

    fun showAddress(text: String) {
        editing = false
        address = text
        field = TextFieldValue(text)
    }

    fun go(input: String) {
        val target = toUrl(input) ?: return
        showAddress(target)
        if (tab.url == null) {
            tab.url = target
            tab.pageUrl = target
            saveTabs()
        } else tab.webView?.loadUrl(target)
    }

    fun playInPlayer(mediaUrl: String, page: String, title: String) {
        vm.captions.stop()
        tab.webView?.onPause()
        play(listOf(vm.entryFor(mediaUrl, page, title, tab.webView?.settings?.userAgentString.orEmpty())), 0)
    }

    // 读取系统声音：先要录音权限，再请用户在系统对话框里同意捕获
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) CaptureService.start(context, r.resultCode, data)
        else vm.captions.note("没有同意读取系统声音")
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) projectionLauncher.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        else vm.captions.note("读取系统声音需要录音权限（只读取译幕自己放出的声音，不录麦克风）")
    }

    fun startCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            projectionLauncher.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        } else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // 网页视频开始放了：音乐、播放页的视频先停下
    LaunchedEffect(Unit) { main.player.pause() }
    DisposableEffect(Unit) {
        onDispose {
            vm.captions.stop()
            tabs.forEach { t ->
                t.webView?.destroy()
                t.webView = null
            }
        }
    }

    /** 退出网页全屏。自己调回调时 WebView 不一定再通知 onHideCustomView，这里先清掉 */
    fun exitFullscreen() {
        val callback = fullscreen?.second
        fullscreen = null
        callback?.onCustomViewHidden()
    }

    /** 切到另一个标签页：切走的那个暂停视频（网页留着，回来接着看），字幕跟着换 */
    fun switchTo(index: Int) {
        if (index == activeIndex || index !in tabs.indices) return
        exitFullscreen()
        tab.active = false
        tab.webView?.let { pauseMedia(it); it.onPause() }
        activeIndex = index
        val next = tabs[index]
        next.active = true
        next.webView?.onResume()
        vm.onTabSwitched()
        showAddress(next.pageUrl.orEmpty())
        saveTabs()
    }

    fun newTab(url: String? = null) {
        tabs.add(WebTab(nextTabId++, url))
        switchTo(tabs.lastIndex)
        if (url == null) showAddress("")
    }

    fun closeTab(t: WebTab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        val wasActive = i == activeIndex
        if (wasActive) {
            exitFullscreen()
            vm.captions.stop()
            vm.onTabSwitched()
        }
        t.active = false
        t.webView?.destroy()
        t.webView = null
        tabs.removeAt(i)
        if (tabs.isEmpty()) tabs.add(WebTab(nextTabId++, null))
        if (wasActive) {
            activeIndex = i.coerceAtMost(tabs.lastIndex)
            tabs[activeIndex].let { it.active = true; it.webView?.onResume(); showAddress(it.pageUrl.orEmpty()) }
        } else if (i < activeIndex) activeIndex--
        saveTabs()
    }

    /** 这个标签页回到首页（常用网站、收藏、最近访问） */
    fun goHome() {
        exitFullscreen()
        vm.captions.stop()
        vm.onTabSwitched()
        tab.webView?.destroy()
        tab.webView = null
        tab.url = null
        tab.pageUrl = null
        tab.title = ""
        tab.canGoBack = false
        tab.progress = 100
        showAddress("")
        saveTabs()
    }

    // 网页全屏：隐藏状态栏，按选的方向横屏或竖屏；退出时恢复
    val inFullscreen = fullscreen != null
    DisposableEffect(inFullscreen) {
        val window = activity?.window
        if (inFullscreen && window != null) {
            // 竖着拍的视频竖屏全屏，其余横屏（横着的视频转到竖屏时 WebView 会自己退出全屏）
            fullscreenLandscape = vm.videoAspect.value?.let { it >= 1f } ?: true
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            // 用进入时的值判断：到这里时 fullscreen 已经是 null 了
            if (inFullscreen && window != null) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    LaunchedEffect(inFullscreen, fullscreenLandscape) {
        if (inFullscreen) activity?.requestedOrientation =
            if (fullscreenLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    BackHandler {
        when {
            fullscreen != null -> exitFullscreen()
            editing && tab.url != null -> focusManager.clearFocus()
            tab.canGoBack -> tab.webView?.goBack()
            tab.url != null -> goHome()
            // 空白的标签页：关掉它，回到别的标签页
            tabs.size > 1 -> closeTab(tab)
            else -> onBack()
        }
    }

    /** 建一个标签页的网页 */
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(ctx: android.content.Context, t: WebTab): WebView {
        // 调试版可以用电脑上的 chrome://inspect 查看网页和注入的脚本
        if (com.aisub.player.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        return WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            with(getSettings()) {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(vm.Bridge { t.active }, "VoxBridge")
            val startScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            if (startScript) WebViewCompat.addDocumentStartJavaScript(this, script, setOf("*"))
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // 「打开 App」之类的跳转（bilibili://、intent://）不处理，留在网页里
                    val scheme = request.url.scheme?.lowercase()
                    return scheme != "http" && scheme != "https"
                }

                override fun onPageStarted(view: WebView, startedUrl: String, favicon: Bitmap?) {
                    t.pageUrl = startedUrl
                    if (!t.active) return
                    vm.onPageStarted()
                    if (!editing) showAddress(startedUrl)
                }

                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    // 不支持开头注入的旧 WebView：加载完再注入（只有顶层页面）
                    if (!startScript) view.evaluateJavascript(script, null)
                    vm.onPageFinished(view.title, finishedUrl)
                    t.canGoBack = view.canGoBack()
                }

                override fun doUpdateVisitedHistory(view: WebView, visitedUrl: String, isReload: Boolean) {
                    t.canGoBack = view.canGoBack()
                    t.pageUrl = visitedUrl
                    // 单页应用里点进视频只改地址、不重新加载：也记下来，回来时打开的是这个视频
                    saveTabs()
                    if (t.active && !editing) showAddress(visitedUrl)
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (!request.isForMainFrame && t.active) {
                        val page = request.requestHeaders["Referer"] ?: t.pageUrl.orEmpty()
                        vm.onRequest(request.url.toString(), page, "")
                    }
                    return null
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    t.progress = newProgress
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    t.title = title.orEmpty()
                    vm.onTitle(title, view.url)
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (t.active) fullscreen = view to callback else callback.onCustomViewHidden()
                }

                override fun onHideCustomView() {
                    if (t.active) fullscreen = null
                }
            }
            vm.captions.userAgent = getSettings().userAgentString
            t.url?.let { loadUrl(it) }
        }
    }

    // Surface 提供文字颜色（深色模式下标题不会变成黑字）
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // 地址栏
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, "返回") }
                // 没在输入时只显示网站名，给右边的按钮留地方；点一下展开完整网址（全选，直接输入就替换）
                val showingHost = !(editing || tab.url == null)
                val shown = if (!showingHost) field else TextFieldValue(tab.pageUrl?.let { Uri.parse(it).host?.removePrefix("www.") } ?: address)
                BasicTextField(
                    value = shown,
                    onValueChange = { v ->
                        // 点进来那一下的光标变化还带着网站名：忽略，用上面换好的完整网址
                        if (!(showingHost && v.text == shown.text)) {
                            field = v
                            address = v.text
                            editing = true
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { focusManager.clearFocus(); go(field.text) }),
                    modifier = Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp))
                        // 实体键盘、电视遥控器的确认键：输入法的「前往」管不到
                        .onPreviewKeyEvent { e ->
                            val enter = e.key == androidx.compose.ui.input.key.Key.Enter || e.key == androidx.compose.ui.input.key.Key.NumPadEnter
                            if (enter && editing && e.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) {
                                focusManager.clearFocus()
                                go(field.text)
                            }
                            enter && editing
                        }
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .onFocusChanged { f ->
                            if (f.isFocused && !editing) {
                                val full = tab.pageUrl ?: address
                                field = TextFieldValue(full, TextRange(0, full.length))
                                editing = true
                                // 换了一整段文字后有的输入法不会自己弹出来：主动叫一下
                                keyboard?.show()
                            } else if (!f.isFocused) editing = false
                        }
                        .padding(horizontal = 14.dp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            if (shown.text.isEmpty()) Text("输入网址或搜索", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        }
                    },
                )
                if (tab.url != null) {
                    if (sniffed.isNotEmpty()) {
                        IconButton(onClick = { showSniffed = true }) {
                            BadgedBox(badge = { Badge { Text("${sniffed.size}") } }) { Icon(AppIcons.Movie, "网页里的视频地址") }
                        }
                    }
                    IconButton(onClick = {
                        if (captionState.running) vm.captions.stop() else vm.captions.start(vm.captionSettings(main.state.value.settings))
                    }) {
                        Icon(
                            AppIcons.Subtitles, if (captionState.running) "关闭 AI 字幕" else "开启 AI 字幕",
                            tint = if (captionState.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 标签页：方框里是打开的个数
                IconButton(onClick = { dialog = WebDialog.TABS }) {
                    Box(
                        Modifier.size(22.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(5.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text("${tabs.size}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(AppIcons.MoreVert, "更多") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("新标签页") }, onClick = { menu = false; newTab() })
                        if (tab.url != null) {
                            DropdownMenuItem(text = { Text("回到主页") }, onClick = { menu = false; goHome() })
                            val marked = bookmarks.isNotEmpty() && vm.isBookmarked(tab.pageUrl)
                            DropdownMenuItem(text = { Text(if (marked) "取消收藏" else "收藏本页") }, onClick = {
                                menu = false
                                tab.pageUrl?.let { u ->
                                    val now = vm.toggleBookmark(tab.title, u)
                                    android.widget.Toast.makeText(context, if (now) "已收藏，在主页的「收藏」里" else "已取消收藏", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            })
                            DropdownMenuItem(text = { Text("刷新") }, onClick = { menu = false; tab.webView?.reload() })
                            DropdownMenuItem(text = { Text("复制网址") }, onClick = {
                                menu = false
                                tab.pageUrl?.let { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) }
                            })
                            DropdownMenuItem(text = { Text("字幕语言") }, onClick = { menu = false; dialog = WebDialog.LANGUAGE })
                            DropdownMenuItem(text = { Text("页面缩放（$zoom%）") }, onClick = { menu = false; dialog = WebDialog.ZOOM })
                        }
                    }
                }
            }
            if (tab.url != null && tab.progress < 100) LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))

            if (tab.url == null) {
                WebHome(
                    bookmarks, history, onOpen = { go(it) },
                    onRemoveBookmark = vm::removeBookmark, onRemove = vm::removeHistory,
                )
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val viewHeight = maxHeight
                    val viewWidth = maxWidth
                    // 每个标签页一个网页；切走时从这里拿下来（不销毁），切回来再放上
                    key(tab.id) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                (tab.webView ?: createWebView(ctx, tab).also { tab.webView = it })
                                    .also { (it.parent as? ViewGroup)?.removeView(it) }
                            },
                        )
                    }
                    // 字幕放在网页视频的下沿；不知道视频在哪（iframe 里的）就放在底部
                    val videoBottom = rect?.let { r -> if (r.viewportWidth <= 0) null else viewWidth * (r.bottom / r.viewportWidth) }
                    val bottom = videoBottom?.let { b -> (viewHeight - b + 6.dp).takeIf { b in 40.dp..viewHeight } }
                    // 竖屏时视频下面通常还有地方：字幕放在视频下面，不挡画面、也不和视频里烧进去的字幕叠在一起
                    val below = videoBottom?.takeIf { !landscape && it >= 40.dp && viewHeight - it >= 96.dp }
                    // 网页报告的位置有小抖动（滚动条、工具栏伸缩）：变化超过一点才挪，字幕不会跟着颤；暂时不知道位置时留在原处
                    var stableBottom by remember { mutableStateOf(24.dp) }
                    if (bottom != null && (bottom - stableBottom).value.let { kotlin.math.abs(it) } > 12f) stableBottom = bottom
                    var stableBelow by remember { mutableStateOf<androidx.compose.ui.unit.Dp?>(null) }
                    if (below == null) stableBelow = null
                    else if (stableBelow.let { it == null || kotlin.math.abs((below - it).value) > 12f }) stableBelow = below
                    if (fullscreen == null) WebSubtitles(
                        vm, captionState, settings, stableBottom, stableBelow,
                        onOpenDirect = { playInPlayer(it, tab.webView?.url.orEmpty(), tab.webView?.title.orEmpty()) },
                        onCapture = ::startCapture,
                    )
                }
            }
        }

        // 网页全屏的画面（含字幕）
        fullscreen?.let { (view, _) ->
            // 右上角的「横竖屏」「退出全屏」按钮：碰一下屏幕出现，4 秒后自动隐藏
            var controlsAt by remember(view) { mutableLongStateOf(System.currentTimeMillis()) }
            var showControls by remember(view) { mutableStateOf(true) }
            LaunchedEffect(controlsAt) {
                showControls = true
                delay(4_000)
                showControls = false
            }
            Box(
                Modifier.fillMaxSize().background(Color.Black).pointerInput(view) {
                    awaitPointerEventScope {
                        while (true) {
                            // 只看不拦：触摸照常交给网页的播放器
                            awaitPointerEvent(PointerEventPass.Initial)
                            controlsAt = System.currentTimeMillis()
                        }
                    }
                },
            ) {
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                WebSubtitles(
                    vm, captionState, settings, settings.subtitleOffset.dp, null,
                    onOpenDirect = {
                        exitFullscreen()
                        playInPlayer(it, tab.webView?.url.orEmpty(), tab.webView?.title.orEmpty())
                    },
                    onCapture = ::startCapture,
                )
                if (showControls) Row(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    // 横着的视频转成竖屏时 WebView 会直接退出全屏：只有竖视频才给切换横竖屏
                    if (videoAspect?.let { it < 1f } == true) IconButton(
                        onClick = { fullscreenLandscape = !fullscreenLandscape },
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x66000000)),
                    ) { Icon(AppIcons.ScreenRotation, if (fullscreenLandscape) "切换到竖屏" else "切换到横屏", tint = Color.White) }
                    Spacer(Modifier.size(8.dp))
                    IconButton(
                        onClick = ::exitFullscreen,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x66000000)),
                    ) { Icon(AppIcons.FullscreenExit, "退出全屏", tint = Color.White) }
                }
            }
        }
    }
    }

    if (dialog == WebDialog.TABS) WebTabsDialog(
        tabs, activeIndex,
        onPick = { dialog = null; switchTo(it) },
        onClose = ::closeTab,
        onNew = { dialog = null; newTab() },
        onDismiss = { dialog = null },
    )

    when (dialog) {
        WebDialog.LANGUAGE -> WebLanguageDialog(
            asr = vm.asrLanguage.collectAsStateWithLifecycle().value,
            target = vm.targetLanguage.collectAsStateWithLifecycle().value,
            settings = settings,
            onConfirm = { asr, target ->
                dialog = null
                vm.setLanguages(main.state.value.settings, asr, target)
            },
            onDismiss = { dialog = null },
        )
        WebDialog.ZOOM -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("页面缩放") },
            text = {
                Column {
                    Text(
                        "网页内容太大（横屏时常见）或太小时调整。每个网站的横屏、竖屏分别记住；也可以双指缩放。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { zoom = (zoom - 10).coerceAtLeast(WebViewModel.ZOOM_MIN); vm.setPageZoom(zoom) }, enabled = zoom > WebViewModel.ZOOM_MIN) { Text("缩小", fontSize = 16.sp) }
                        Text("$zoom%", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        TextButton(onClick = { zoom = (zoom + 10).coerceAtMost(WebViewModel.ZOOM_MAX); vm.setPageZoom(zoom) }, enabled = zoom < WebViewModel.ZOOM_MAX) { Text("放大", fontSize = 16.sp) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("完成") } },
            dismissButton = { TextButton(onClick = { zoom = 100; vm.setPageZoom(100) }) { Text("恢复 100%") } },
        )
        WebDialog.TABS, null -> {}
    }

    if (showSniffed) {
        AlertDialog(
            onDismissRequest = { showSniffed = false },
            title = { Text("网页里的视频地址") },
            text = {
                LazyColumn {
                    item {
                        Text(
                            "网页加载过这些视频文件。用译幕播放器打开可以提前识别、调字幕样式、后台播放；打不开时继续在网页里看。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    items(sniffed.reversed()) { m ->
                        Text(
                            m.url, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().clickable {
                                showSniffed = false
                                playInPlayer(m.url, m.page, tab.webView?.title.orEmpty())
                            }.padding(vertical = 10.dp),
                        )
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSniffed = false }) { Text("关闭") } },
        )
    }
}

/** 网页视频上的 AI 字幕和状态说明 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.WebSubtitles(
    vm: WebViewModel,
    state: WebCaptionState,
    settings: AppSettings,
    bottom: androidx.compose.ui.unit.Dp,
    /** 不为 null 时字幕放在视频下面（这里是视频下沿离顶部的距离），否则叠在视频底部 */
    below: androidx.compose.ui.unit.Dp?,
    onOpenDirect: (String) -> Unit,
    onCapture: () -> Unit,
) {
    if (!state.running && state.cues.isEmpty() && state.status == null) return
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            now = vm.captions.displayTime() ?: -1
            delay(100)
        }
    }
    // 只看正在放的那一路（放广告时不显示正片的字幕）
    val visible = remember(state.cues, state.source) { state.cues.filter { state.sources[it.id] == state.source } }
    val cue: Cue? = if (now >= 0) cueNear(visible, now) else null
    if (cue != null && settings.showAiSubtitles) {
        val lines = if (cue.translation == cue.text) listOf(cue.text to true) else buildList {
            if (settings.showTranslation && cue.translation != null) add(cue.translation to true)
            if (settings.showOriginal || cue.translation == null) add(cue.text to false)
        }
        // 上下两行分开放的设置在网页里不好用（不知道画面顶部在哪）：始终叠在一起
        val style = settings.copy(topLineFromTop = -1)
        if (below != null) {
            // 视频下面接一条深色底，像是播放器的延伸，网页是白底也看得清
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = below).background(Color(0xE6000000)).padding(vertical = 6.dp)) {
                StyledSubtitleLines(lines, style, 0.dp)
            }
        } else StyledSubtitleLines(lines, style, bottom)
    } else if (state.status != null) {
        Column(
            (if (below != null) Modifier.align(Alignment.TopCenter).padding(top = below + 8.dp, start = 24.dp, end = 24.dp)
            else Modifier.align(Alignment.BottomCenter).padding(bottom = bottom, start = 24.dp, end = 24.dp)).widthIn(max = 520.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(state.status, color = Color.White, fontSize = 13.sp)
            if (state.directUrl != null || state.canCapture) {
                Row(Modifier.padding(top = 6.dp)) {
                    state.directUrl?.let { direct ->
                        Text(
                            "用播放器打开", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                            modifier = Modifier.clickable { onOpenDirect(direct) }.padding(end = 16.dp),
                        )
                    }
                    if (state.canCapture) {
                        Text(
                            "读取系统声音", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                            modifier = Modifier.clickable(onClick = onCapture),
                        )
                    }
                }
            }
        }
    }
}

/** 句子之间常有几百毫秒的停顿：上一句在下一句出来前多留一会儿，不会一下没了又出来（看起来像闪烁） */
private const val LINGER_MS = 1_200L

private fun cueNear(cues: List<Cue>, positionMs: Long): Cue? {
    var lo = 0
    var hi = cues.size - 1
    var i = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (cues[mid].startMs <= positionMs) {
            i = mid
            lo = mid + 1
        } else hi = mid - 1
    }
    if (i < 0) return null
    // 识别有时会出时间重叠的句子：从最近开始的往前找，还没结束的优先
    for (k in i downTo maxOf(0, i - 3)) if (positionMs <= cues[k].endMs) return cues[k]
    return cues[i].takeIf { positionMs <= it.endMs + LINGER_MS }
}

private enum class WebDialog { LANGUAGE, ZOOM, TABS }

/** 一个标签页。网页（WebView）第一次切到时才建，切走后留着（视频暂停），回来接着看 */
private class WebTab(val id: Long, startUrl: String?) {
    /** null：空白的新标签页，显示收藏、常用网站和最近访问 */
    var url by mutableStateOf(startUrl)

    /** 当前页面地址（地址栏里可能是正在输入的文字） */
    var pageUrl by mutableStateOf(startUrl)
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var webView: WebView? = null

    /** 正在看的标签页；网页脚本（另一个线程）按它决定要不要送声音 */
    @Volatile
    var active = false
}

/** 切走的标签页：网页里的视频、音乐先停下 */
private fun pauseMedia(view: WebView) =
    view.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})", null)

/** 标签页列表：点一个切过去，叉号关掉 */
@Composable
private fun WebTabsDialog(tabs: List<WebTab>, active: Int, onPick: (Int) -> Unit, onClose: (WebTab) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标签页（${tabs.size}）") },
        text = {
            LazyColumn {
                items(tabs.size, key = { tabs[it].id }) { i ->
                    val t = tabs[i]
                    val selected = i == active
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onPick(i) }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                t.title.ifBlank { t.pageUrl?.let { Uri.parse(it).host } ?: "新标签页" },
                                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            t.pageUrl?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        IconButton(onClick = { onClose(t) }) { Icon(AppIcons.Close, "关闭这个标签页") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNew) { Text("新标签页") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 网页字幕的视频语言和翻译语言；「跟随设置」表示用设置页里的 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WebLanguageDialog(
    asr: String?,
    target: String?,
    settings: AppSettings,
    onConfirm: (String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var asrChoice by remember { mutableStateOf(asr) }
    var targetChoice by remember { mutableStateOf(target) }
    val targets = (TARGET_LANGUAGES + listOfNotNull(target, settings.targetLanguage)).distinct()
    // 选项用小标签平铺：两组都在一屏里，不用往下翻
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("字幕语言") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("视频说的语言", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(asrChoice == null, { asrChoice = null }, label = { Text("同设置（${AsrLanguages.label(settings.asrLanguage)}）") })
                    AsrLanguages.COMMON.forEach { (code, label) ->
                        FilterChip(asrChoice == code, { asrChoice = code }, label = { Text(label) })
                    }
                }
                Text("翻译成", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(targetChoice == null, { targetChoice = null }, label = { Text("同设置（${settings.targetLanguage}）") })
                    targets.forEach { lang -> FilterChip(targetChoice == lang, { targetChoice = lang }, label = { Text(lang) }) }
                }
                Text(
                    "只对网页字幕生效。换了视频语言会从当前位置重新识别；只换翻译语言时保留原文、重新翻译。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(asrChoice, targetChoice) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 还没打开网页时：常用网站和最近看过的网页 */
@Composable
private fun WebHome(
    bookmarks: List<com.aisub.player.web.WebPage>,
    history: List<com.aisub.player.web.WebPage>,
    onOpen: (String) -> Unit,
    onRemoveBookmark: (com.aisub.player.web.WebPage) -> Unit,
    onRemove: (com.aisub.player.web.WebPage) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "在网页里播放视频，点右上角的字幕按钮，边播边生成 AI 字幕（识别、翻译用设置里选的服务）。" +
                    "网页能拿到视频地址时，也可以改用译幕播放器打开。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        if (bookmarks.isNotEmpty()) {
            item { SectionHeader("收藏") }
            item {
                SurfaceCard {
                    bookmarks.forEachIndexed { i, page ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        ListRow(
                            page.title, page.url, AppIcons.Star,
                            trailing = { IconButton(onClick = { onRemoveBookmark(page) }) { Icon(AppIcons.Close, "取消收藏") } },
                            onClick = { onOpen(page.url) },
                        )
                    }
                }
            }
        }
        item { SectionHeader("常用网站") }
        item {
            SurfaceCard {
                QUICK_SITES.forEachIndexed { i, (name, site) ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    ListRow(name, site.removePrefix("https://"), AppIcons.Web, onClick = { onOpen(site) })
                }
            }
        }
        if (history.isNotEmpty()) {
            item { SectionHeader("最近访问") }
            item {
                SurfaceCard {
                    history.forEachIndexed { i, page ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        ListRow(
                            page.title, page.url, AppIcons.History,
                            trailing = { IconButton(onClick = { onRemove(page) }) { Icon(AppIcons.Close, "删除") } },
                            onClick = { onOpen(page.url) },
                        )
                    }
                }
            }
        }
    }
}

/** 地址栏的输入：像网址的直接打开，否则用必应搜索 */
private fun toUrl(input: String): String? {
    val t = input.trim()
    if (t.isEmpty()) return null
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) return t
    if (!t.contains(' ') && Regex("^[^/]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$").matches(t)) return "https://$t"
    return "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
}
