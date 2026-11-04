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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.focusRequester
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

/**
 * 电视：按遥控器的这几秒里，网页播放器的控制栏（进度条、全屏按钮）一直显示，不自动藏起来；
 * 不然指针还没挪到全屏按钮，控制栏就藏了，再点只会暂停。认得西瓜播放器、video.js、DPlayer
 */
private val TV_ACTIVE_JS = """
    (function(){var d=document.documentElement;if(!d)return;
    if(!window.__voxTvCss&&window.CSSStyleSheet&&document.adoptedStyleSheets){try{var s=new CSSStyleSheet();s.replaceSync(
    '.vox-tv-active .xgplayer.xgplayer-inactive .controls-autohide{visibility:visible!important;opacity:1!important;pointer-events:auto!important}'+
    '.vox-tv-active .video-js.vjs-user-inactive .vjs-control-bar{visibility:visible!important;opacity:1!important;pointer-events:auto!important}'+
    '.vox-tv-active .dplayer.dplayer-hide-controller .dplayer-controller,.vox-tv-active .dplayer.dplayer-hide-controller .dplayer-controller-mask{opacity:1!important;transform:none!important;pointer-events:auto!important}');
    document.adoptedStyleSheets=document.adoptedStyleSheets.concat([s]);window.__voxTvCss=1}catch(e){}}
    d.classList.add('vox-tv-active');clearTimeout(window.__voxTvT);
    window.__voxTvT=setTimeout(function(){d.classList.remove('vox-tv-active')},4000)})()
""".trimIndent()

/** 电视上网页全屏时遥控器控制视频：确认键暂停 / 继续，左右键快退 / 快进 10 秒 */
private fun tvMediaJs(action: String) = """
    (function(){var v=null,a=0;document.querySelectorAll('video').forEach(function(e){var r=e.getBoundingClientRect(),s=r.width*r.height;if(s>a){a=s;v=e}});
    if(!v)return;var f=document.fullscreenElement||document.webkitFullscreenElement;if(f&&f!==v&&!f.contains(v)&&f.querySelector('video'))v=f.querySelector('video');
    ${TV_ACTIVE_JS.replace("\n", "")};
    if('$action'==='toggle'){if(v.paused)v.play();else v.pause()}
    else{var d='$action'==='back'?-10:10;v.currentTime=Math.max(0,Math.min((v.duration||1e9)-1,v.currentTime+d))}})()
""".trimIndent()

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
    val cleanScript = remember { context.assets.open("web_cleanup.js").bufferedReader().use { it.readText() } }
    val pickerScript = remember { context.assets.open("web_picker.js").bufferedReader().use { it.readText() } }
    val mangaScript = remember { context.assets.open("web_manga.js").bufferedReader().use { it.readText() } }
    /** 漫画阅读模式开着 */
    var reading by remember { mutableStateOf(false) }
    /** 从阅读模式里点了上一话 / 下一话（记下原来那一话的网址）：新网页识别出漫画后自动接着用阅读模式看 */
    var reopenAfter by remember { mutableStateOf<String?>(null) }
    // 点选屏蔽：开着时点网页只是选元素，底部显示操作栏
    var picking by remember { mutableStateOf(false) }
    val picked by vm.picked.collectAsStateWithLifecycle()
    val cleanPages by vm.cleanPages.collectAsStateWithLifecycle()
    val adBlock by vm.adBlock.collectAsStateWithLifecycle()
    val adInfo by vm.adBlockInfo.collectAsStateWithLifecycle()

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
    /** 选好翻译模型后要做的事（换翻译语言后重新翻译） */
    var afterModel by remember { mutableStateOf<(() -> Unit)?>(null) }
    /** 网页全屏时横屏还是竖屏（竖着拍的视频可以切成竖屏） */
    var fullscreenLandscape by remember { mutableStateOf(true) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var zoom by remember { mutableIntStateOf(100) }
    // 每个网站、横竖屏分别记住缩放；网页脚本每 250 毫秒读一次
    LaunchedEffect(tab.pageUrl?.let { Uri.parse(it).host }, landscape) { zoom = vm.zoomFor(tab.pageUrl, landscape) }

    // ---- 电视：网页里用方向键移动指针、确认键点击；地址用手机输入 ----
    val tv = LocalTv.current
    /** 指针位置（网页里的像素坐标）；null 时在中间 */
    var cursor by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var webFocused by remember { mutableStateOf(false) }
    val toolbarFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val touchDown = remember { longArrayOf(0L) }
    /** 电视：网页全屏时遥控器按键的处理（全屏画面那边设置） */
    val fullscreenKey = remember { arrayOfNulls<(Int, android.view.KeyEvent) -> Boolean>(1) }

    fun showAddress(text: String) {
        editing = false
        address = text
        field = TextFieldValue(text)
    }

    // 手机版、桌面版网页用的 User-Agent（桌面版按网站记住）
    val mobileAgent = remember { WebSettings.getDefaultUserAgent(context) }
    val desktopAgent = remember { desktopAgentFor(mobileAgent) }

    /** 打开这个网址前换成它要的 User-Agent；换了返回 true */
    fun WebView.agentFor(url: String): Boolean {
        val want = if (vm.isDesktop(url)) desktopAgent else mobileAgent
        if (getSettings().userAgentString == want) return false
        getSettings().userAgentString = want
        vm.captions.userAgent = want
        return true
    }

    fun go(input: String) {
        val target = toUrl(input) ?: return
        showAddress(target)
        if (tab.url == null) {
            tab.url = target
            tab.pageUrl = target
            saveTabs()
        } else tab.webView?.let { it.agentFor(target); it.loadUrl(target) }
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

    // 网页自己判断的联网状态（navigator.onLine）有时会卡在「断网」：切 VPN、换网络或在后台时漏了恢复的通知。
    // 有的网站（新视讯）这时只显示「当前网络异常」，点刷新重试也没用（它先看 onLine）。
    // 按系统的网络状态主动告诉每个网页：刚打开、网络变了、回到前台时都同步一次
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun sync() {
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val online = cm == null || caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            main.post { tabs.forEach { it.webView?.setNetworkAvailable(online) } }
        }
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = sync()
            override fun onLost(network: android.net.Network) = sync()
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) = sync()
        }
        val resumed = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) sync() }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        lifecycle.addObserver(resumed)
        onDispose {
            runCatching { cm?.unregisterNetworkCallback(callback) }
            lifecycle.removeObserver(resumed)
        }
    }

    fun stopPicking() {
        tab.webView?.evaluateJavascript("window.__voxPicker && __voxPicker.stop()", null)
        picking = false
        vm.clearPicked()
    }

    fun startPicking() {
        vm.clearPicked()
        picking = true
        tab.webView?.evaluateJavascript(pickerScript, null)
    }

    BackHandler {
        when {
            picking -> stopPicking()
            fullscreen != null -> exitFullscreen()
            editing && tab.url != null -> focusManager.clearFocus()
            tab.canGoBack -> tab.webView?.goBack()
            tab.url != null -> goHome()
            // 空白的标签页：关掉它，回到别的标签页
            tabs.size > 1 -> closeTab(tab)
            else -> onBack()
        }
    }

    /** 在指针位置模拟一次触摸 */
    fun touch(w: WebView, at: androidx.compose.ui.geometry.Offset, action: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        if (action == android.view.MotionEvent.ACTION_DOWN) touchDown[0] = now
        val ev = android.view.MotionEvent.obtain(touchDown[0], now, action, at.x, at.y, 0)
        ev.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        w.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** 电视遥控器在网页上的按键：移动指针，到边缘滚动网页，顶端再按上回到工具栏 */
    fun tvCursorKey(w: WebView, keyCode: Int, e: android.view.KeyEvent): Boolean {
        val dx = when (keyCode) { android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1; android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
        val dy = when (keyCode) { android.view.KeyEvent.KEYCODE_DPAD_UP -> -1; android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 1; else -> 0 }
        val click = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
            keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        if (dx == 0 && dy == 0 && !click) return false
        if (e.action == android.view.KeyEvent.ACTION_DOWN) w.evaluateJavascript(TV_ACTIVE_JS, null)
        val c = cursor ?: androidx.compose.ui.geometry.Offset(w.width / 2f, w.height / 2f)
        cursor = c
        if (click) {
            if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0) touch(w, c, android.view.MotionEvent.ACTION_DOWN)
            else if (e.action == android.view.KeyEvent.ACTION_UP) touch(w, c, android.view.MotionEvent.ACTION_UP)
            return true
        }
        if (e.action != android.view.KeyEvent.ACTION_DOWN) return true
        val density = w.resources.displayMetrics.density
        // 按住越久走得越快
        val step = density * 10f * (1 + minOf(e.repeatCount, 24) / 4f)
        val maxX = (w.width - 1).toFloat()
        val maxY = (w.height - 1).toFloat()
        val nx = (c.x + dx * step).coerceIn(0f, maxX)
        val ny = (c.y + dy * step).coerceIn(0f, maxY)
        // 已经在边上还往外按：滚动网页（指针下面能滚的区域优先，没有就滚整页）
        val overX = dx != 0 && nx == c.x
        val overY = dy != 0 && ny == c.y
        if (overY && dy < 0 && e.repeatCount == 0 && !w.canScrollVertically(-1)) {
            runCatching { toolbarFocus.requestFocus() }
            return true
        }
        if (overX || overY) {
            // 坐标按比例给网页（网页的 CSS 像素不一定是 px / density）。找指针下面还能往这个方向滚的区域，
            // 没有就找画面中间的；整页也滚不动（有的网站把 body 锁住、内容放在一个能滚的 div 里）就滚画面上最大的那个
            w.evaluateJavascript(
                "(function(fx,fy,ux,uy){var dx=ux*innerWidth*0.25,dy=uy*innerHeight*0.25;" +
                    "function can(e){var s=getComputedStyle(e);if(dy){if(!/(auto|scroll|overlay)/.test(s.overflowY))return false;" +
                    "return dy>0?e.scrollTop+e.clientHeight<e.scrollHeight-1:e.scrollTop>0}" +
                    "if(!/(auto|scroll|overlay)/.test(s.overflowX))return false;return dx>0?e.scrollLeft+e.clientWidth<e.scrollWidth-1:e.scrollLeft>0}" +
                    "function up(e){while(e&&e!==document.body&&e!==document.documentElement){if(can(e))return e;e=e.parentElement}return null}" +
                    "var t=up(document.elementFromPoint(fx*innerWidth,fy*innerHeight))||up(document.elementFromPoint(innerWidth/2,innerHeight/2));" +
                    "if(!t){var se=document.scrollingElement||document.documentElement;" +
                    "var win=dy?(dy>0?innerHeight+scrollY<se.scrollHeight-1:scrollY>0):(dx>0?innerWidth+scrollX<se.scrollWidth-1:scrollX>0);" +
                    "if(!win){var area=0;document.querySelectorAll('body *').forEach(function(e){if(e.clientHeight<40||!can(e))return;" +
                    "var r=e.getBoundingClientRect(),a=Math.max(0,Math.min(r.right,innerWidth)-Math.max(r.left,0))*Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(r.top,0));" +
                    "if(a>area){area=a;t=e}})}}" +
                    "(t||window).scrollBy({left:dx,top:dy,behavior:'smooth'})})" +
                    "(${c.x / w.width},${c.y / w.height},${if (overX) dx else 0},${if (overY) dy else 0})",
                null,
            )
        }
        cursor = androidx.compose.ui.geometry.Offset(nx, ny)
        return true
    }

    /** 进入漫画阅读模式（网页上识别出了漫画） */
    fun openReader() {
        if (tab.manga.manga == null) return
        if (picking) stopPicking()
        exitFullscreen()
        com.aisub.player.manga.MangaFetcher.userAgent = tab.webView?.settings?.userAgentString
        reading = true
    }

    /** 拦下了跳去别的网站的广告：提示一下 */
    fun blockedPopup(url: String) {
        val host = Uri.parse(url).host?.removePrefix("www.") ?: url
        android.widget.Toast.makeText(context, "已拦截跳转广告：$host", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 建一个标签页的网页 */
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(ctx: android.content.Context, t: WebTab): WebView {
        // 调试版可以用电脑上的 chrome://inspect 查看网页和注入的脚本
        if (com.aisub.player.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        return object : WebView(ctx) {
            // 电视：网页有焦点时系统把它当输入框，按键先给输入法；有的输入法没弹出来也会吞掉方向键。
            // 输入法没在显示就先接住遥控器的方向键、确认键交给指针（在网页输入框里打字时照常给输入法）
            override fun dispatchKeyEventPreIme(event: android.view.KeyEvent): Boolean {
                val ime = androidx.core.view.ViewCompat.getRootWindowInsets(this)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                // 遥控器菜单键：网页上有漫画时进入阅读模式
                if (t.active && event.keyCode == android.view.KeyEvent.KEYCODE_MENU && t.manga.manga != null && fullscreen == null) {
                    if (event.action == android.view.KeyEvent.ACTION_UP) openReader()
                    return true
                }
                if (tv && t.active && !ime) {
                    val handled = if (fullscreen == null) tvCursorKey(this, event.keyCode, event)
                    else fullscreenKey[0]?.invoke(event.keyCode, event) == true
                    if (handled) return true
                }
                return super.dispatchKeyEventPreIme(event)
            }
        }.apply {
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
                // 网页开新窗口（window.open、target=_blank）交给 onCreateWindow 判断：广告弹窗拦下，正常链接在当前标签页打开
                setSupportMultipleWindows(true)
            }
            // 记下点中的链接：点了「第 2 话」却被脚本带去别的网站，就是广告
            setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    val hit = (v as WebView).hitTestResult
                    t.tappedLink = hit.extra.takeIf { hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE }
                    t.tappedAt = android.os.SystemClock.uptimeMillis()
                }
                false
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(vm.Bridge({ t.active }, t.manga), "VoxBridge")
            val startScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            if (startScript) {
                WebViewCompat.addDocumentStartJavaScript(this, script, setOf("*"))
                WebViewCompat.addDocumentStartJavaScript(this, cleanScript, setOf("*"))
                WebViewCompat.addDocumentStartJavaScript(this, mangaScript, setOf("*"))
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // 「打开 App」之类的跳转（bilibili://、intent://）不处理，留在网页里
                    val scheme = request.url.scheme?.lowercase()
                    if (scheme != "http" && scheme != "https") return true
                    // 点的是本站链接，网页脚本却把整页带去另一个网站（点一下就跳广告）：拦下，照常打开点的那个链接
                    if (request.isForMainFrame && request.hasGesture() && !request.isRedirect) {
                        val link = t.recentLink()
                        val target = request.url.toString()
                        if (link != null && isHijack(target, link, t.pageUrl)) {
                            t.tappedLink = null
                            blockedPopup(target)
                            if (link.startsWith("http://") || link.startsWith("https://")) view.loadUrl(link)
                            return true
                        }
                    }
                    // 去的网站要换手机版、桌面版：换好再打开
                    if (request.isForMainFrame && view.agentFor(request.url.toString())) {
                        view.loadUrl(request.url.toString())
                        return true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView, startedUrl: String, favicon: Bitmap?) {
                    t.pageUrl = startedUrl
                    t.manga.reset()
                    if (!t.active) return
                    vm.onPageStarted()
                    // 换了网页：点选的脚本跟着旧网页没了
                    if (picking) { picking = false; vm.clearPicked() }
                    if (!editing) showAddress(startedUrl)
                }

                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    // 不支持开头注入的旧 WebView：加载完再注入（只有顶层页面）
                    if (!startScript) {
                        view.evaluateJavascript(script, null)
                        view.evaluateJavascript(cleanScript, null)
                        view.evaluateJavascript(mangaScript, null)
                    }
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
                    vm.interceptAd(request, request.requestHeaders["Referer"] ?: t.pageUrl)?.let { return it }
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

                // 网页要开新窗口：先用一个临时网页接住，看它要去哪，再决定在当前标签页打开还是当广告拦下
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                    val link = t.recentLink()
                    val temp = WebView(view.context)
                    var decided = false
                    fun decide(url: String): Boolean {
                        if (decided || url.isEmpty() || url.startsWith("about:")) return decided
                        decided = true
                        view.post { temp.destroy() }
                        val scheme = Uri.parse(url).scheme?.lowercase()
                        if (scheme != "http" && scheme != "https") return true
                        // 点中的链接本身就要开新窗口（target=_blank），或者是本站自己开的：在当前标签页打开（和以前一样）
                        val own = if (link != null) sameSite(url, link) else sameSite(url, t.pageUrl)
                        if (own) {
                            view.agentFor(url)
                            view.loadUrl(url)
                        } else blockedPopup(url)
                        return true
                    }
                    temp.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                            decide(request.url.toString())

                        override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                            if (decide(url)) v.stopLoading()
                        }
                    }
                    // 一直没去任何地方（空白窗口）：过一会儿收掉
                    view.postDelayed({ if (!decided) { decided = true; temp.destroy() } }, 10_000)
                    (resultMsg.obj as? WebView.WebViewTransport)?.webView = temp
                    resultMsg.sendToTarget()
                    return true
                }
            }
            vm.captions.userAgent = getSettings().userAgentString
            if (tv) {
                isFocusable = true
                post { requestFocus() }
                // 从播放器、网页全屏回来，或者界面重建后，网页有焦点但 Compose 不知道：按键直接到网页，
                // 网页会自己按方向找链接、不会滚动。网页这一层也接一下，交给指针
                setOnKeyListener { v, keyCode, e -> t.active && fullscreen == null && tvCursorKey(v as WebView, keyCode, e) }
                setOnFocusChangeListener { _, has -> if (t.active) webFocused = has }
            }
            t.url?.let { agentFor(it); loadUrl(it) }
        }
    }

    // Surface 提供文字颜色（深色模式下标题不会变成黑字）
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // 地址栏
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).onPreviewKeyEvent { e ->
                    // 电视：从工具栏按下键回到网页指针；不让网页按方向去找链接（会跳到页面底部）
                    val w = tab.webView
                    val down = tv && w != null && fullscreen == null && e.key == androidx.compose.ui.input.key.Key.DirectionDown
                    if (down && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) w!!.requestFocus()
                    down
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, "返回") }
                // 没在输入时只显示网站名，给右边的按钮留地方；点一下展开完整网址（全选，直接输入就替换）
                val showingHost = !(editing || tab.url == null)
                val shown = if (!showingHost) field else TextFieldValue(tab.pageUrl?.let { Uri.parse(it).host?.removePrefix("www.") } ?: address)
                // 电视：地址栏是个按钮，焦点经过时不弹输入法；按确认键用手机输入
                if (tv) Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .focusRequester(toolbarFocus)
                        .clickable { dialog = WebDialog.ADDRESS }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val empty = shown.text.isEmpty()
                    Text(
                        if (empty) "输入网址或搜索（可用手机输入）" else shown.text,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (empty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                } else BasicTextField(
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
                    if (tab.manga.manga != null) {
                        IconButton(onClick = ::openReader) { Icon(AppIcons.MenuBook, "漫画阅读模式", tint = MaterialTheme.colorScheme.primary) }
                    }
                    if (sniffed.isNotEmpty()) {
                        IconButton(onClick = { showSniffed = true }) {
                            BadgedBox(badge = { Badge { Text("${sniffed.size}") } }) { Icon(AppIcons.Movie, "网页里的视频地址") }
                        }
                    }
                    IconButton(onClick = {
                        // 开始前和播放页一样先选识别服务、视频语言（自动翻译时连翻译模型一起选）
                        if (captionState.running) vm.captions.stop() else dialog = WebDialog.GENERATE
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
                    AppMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
                            DropdownMenuItem(text = { Text("分享网址") }, onClick = {
                                menu = false
                                tab.pageUrl?.let { url ->
                                    val title = tab.webView?.title?.takeIf { it.isNotBlank() && it != url }
                                    shareText(context, if (title != null) "$title\n$url" else url, title)
                                }
                            })
                            val desktop = vm.isDesktop(tab.pageUrl)
                            DropdownMenuItem(
                                text = { Text("桌面版网页") },
                                trailingIcon = { Checkbox(checked = desktop, onCheckedChange = null) },
                                onClick = {
                                    menu = false
                                    vm.setDesktop(tab.pageUrl, !desktop)
                                    tab.webView?.let { w -> tab.pageUrl?.let { w.agentFor(it) }; w.reload() }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("屏蔽「打开 App」推广") },
                                trailingIcon = { Checkbox(checked = cleanPages, onCheckedChange = null) },
                                onClick = { menu = false; vm.setCleanPages(!cleanPages); tab.webView?.reload() },
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("拦截广告")
                                        if (adBlock) Text(
                                            adBlockSummary(adInfo, vm.blockedOnPage),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (adInfo.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                trailingIcon = { Checkbox(checked = adBlock, onCheckedChange = null) },
                                onClick = { menu = false; vm.setAdBlock(!adBlock); tab.webView?.reload() },
                            )
                            DropdownMenuItem(text = { Text("广告规则订阅") }, onClick = { menu = false; dialog = WebDialog.AD_LISTS })
                            if (tab.url != null) DropdownMenuItem(text = { Text("点选屏蔽广告") }, onClick = { menu = false; startPicking() })
                            if (tab.manga.manga != null) DropdownMenuItem(
                                text = { Text("漫画阅读模式（${tab.manga.manga?.pages?.size ?: 0} 页）") },
                                onClick = { menu = false; openReader() },
                            )
                            DropdownMenuItem(text = { Text("自定义广告规则") }, onClick = { menu = false; dialog = WebDialog.AD_RULES })
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
                            // 电视：按键先到这里（Compose 管焦点），方向键、确认键交给指针
                            modifier = Modifier.fillMaxSize().then(
                                if (!tv) Modifier else Modifier
                                    .onFocusChanged { webFocused = it.hasFocus }
                                    .onPreviewKeyEvent { e ->
                                        val w = tab.webView
                                        w != null && fullscreen == null && tvCursorKey(w, e.nativeKeyEvent.keyCode, e.nativeKeyEvent)
                                    },
                            ),
                            factory = { ctx ->
                                (tab.webView ?: createWebView(ctx, tab).also { tab.webView = it })
                                    .also { (it.parent as? ViewGroup)?.removeView(it) }
                            },
                        )
                    }
                    if (tv && webFocused && fullscreen == null) {
                        val density = LocalDensity.current
                        val c = cursor ?: with(density) { androidx.compose.ui.geometry.Offset(viewWidth.toPx() / 2, viewHeight.toPx() / 2) }
                        TvCursor(Modifier.offset { androidx.compose.ui.unit.IntOffset(c.x.toInt(), c.y.toInt()) })
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
                    // 横屏时视频一般铺满（有的网站把画面转 90° 或拉满冒充全屏，App 还在内嵌模式）：
                    // 网页报的位置不准、转屏瞬间还会来回跳，这时像全屏画面那样把字幕按固定高度叠在底部
                    if (fullscreen == null) WebSubtitles(
                        vm, captionState, settings,
                        if (landscape) settings.subtitleOffset.dp else stableBottom,
                        if (landscape) null else stableBelow,
                        onOpenDirect = { playInPlayer(it, tab.webView?.url.orEmpty(), tab.webView?.title.orEmpty()) },
                        onCapture = ::startCapture,
                    )
                    // 识别出漫画：右下角提示一下，点了进入阅读模式（电视上用工具栏的书本按钮或菜单键）
                    val found = tab.manga.manga
                    var chipClosed by remember(tab.id, found?.pageUrl) { mutableStateOf(false) }
                    if (found != null && !picking && !chipClosed && fullscreen == null && !tv) {
                        LaunchedEffect(found.pageUrl) { delay(12_000); chipClosed = true }
                        androidx.compose.material3.Surface(
                            onClick = ::openReader,
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shadowElevation = 6.dp,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        ) {
                            Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.MenuBook, null, Modifier.size(18.dp))
                                Text("发现漫画 ${found.pages.size} 页 · 阅读模式", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                                IconButton(onClick = { chipClosed = true }, modifier = Modifier.size(36.dp)) { Icon(AppIcons.Close, "不用", Modifier.size(16.dp)) }
                            }
                        }
                    }
                }
                // 点选屏蔽的操作栏放在网页下面、不盖住网页：贴在底部的广告也能点到
                if (picking) PickerBar(
                        picked,
                        onParent = { tab.webView?.evaluateJavascript("__voxPicker.parent()", null) },
                        onChild = { tab.webView?.evaluateJavascript("__voxPicker.child()", null) },
                        onCancel = ::stopPicking,
                        onBlock = { p ->
                            tab.webView?.evaluateJavascript("__voxPicker.hide(${org.json.JSONObject.quote(p.selector)})", null)
                            tab.pageUrl?.let { vm.blockElement(it, p.selector) }
                            picking = false
                            vm.clearPicked()
                            android.widget.Toast.makeText(context, "已屏蔽，规则在「自定义广告规则」里，删掉那一行就恢复", android.widget.Toast.LENGTH_LONG).show()
                        },
                    )
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
            // 电视：全屏后指针没了，遥控器直接控制视频（返回键照常退出全屏）
            fun tvFullscreenKey(keyCode: Int, e: android.view.KeyEvent): Boolean {
                val action = when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "toggle"
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> "back"
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "forward"
                    android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "show"
                    else -> return false
                }
                if (e.action == android.view.KeyEvent.ACTION_DOWN && (e.repeatCount == 0 || action == "back" || action == "forward")) {
                    tab.webView?.evaluateJavascript(if (action == "show") TV_ACTIVE_JS else tvMediaJs(action), null)
                    controlsAt = System.currentTimeMillis()
                }
                return true
            }
            if (tv) DisposableEffect(view) {
                fullscreenKey[0] = ::tvFullscreenKey
                view.post { view.requestFocus() }
                onDispose { fullscreenKey[0] = null }
            }
            Box(
                Modifier.fillMaxSize().background(Color.Black)
                    .then(if (tv) Modifier.onPreviewKeyEvent { tvFullscreenKey(it.nativeKeyEvent.keyCode, it.nativeKeyEvent) } else Modifier)
                    .pointerInput(view) {
                    awaitPointerEventScope {
                        while (true) {
                            // 只看不拦：触摸照常交给网页的播放器
                            awaitPointerEvent(PointerEventPass.Initial)
                            controlsAt = System.currentTimeMillis()
                        }
                    }
                },
            ) {
                AndroidView(
                    factory = { ctx ->
                        if (!tv) view
                        // 电视：全屏画面里获得焦点的是网页内部的视图，按键先给输入法（有的输入法会吞掉方向键）：
                        // 在外面包一层，输入法之前先接住遥控器按键
                        else object : android.widget.FrameLayout(ctx) {
                            override fun dispatchKeyEventPreIme(event: android.view.KeyEvent): Boolean {
                                val ime = androidx.core.view.ViewCompat.getRootWindowInsets(this)
                                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                                return (!ime && tvFullscreenKey(event.keyCode, event)) || super.dispatchKeyEventPreIme(event)
                            }
                        }.apply {
                            (view.parent as? ViewGroup)?.removeView(view)
                            addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                val openDirect: (String) -> Unit = {
                    exitFullscreen()
                    playInPlayer(it, tab.webView?.url.orEmpty(), tab.webView?.title.orEmpty())
                }
                // 状态说明（带「用播放器打开」等按钮）照常画在这里，能点
                WebSubtitles(
                    vm, captionState, settings, settings.subtitleOffset.dp, null,
                    onOpenDirect = openDirect, onCapture = ::startCapture, showCue = false,
                )
                // 字幕画在单独的窗口里：全屏视频在有的手机上走独立的视频图层，盖在应用自己画的东西上面，
                // 只有网页控件盖在视频上（点一下屏幕）时才露出来。窗口不接触摸，点按照常交给网页播放器
                // 用铺满整块屏幕的透明窗口（弹出层会按可见区域缩小，扣掉隐藏的系统栏，字幕偏高）；
                // 不接触摸也不抢焦点：点按交给网页播放器，返回键照常退出全屏
                Dialog(
                    onDismissRequest = {},
                    properties = DialogProperties(
                        dismissOnBackPress = false, dismissOnClickOutside = false,
                        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
                    ),
                ) {
                    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
                    SideEffect {
                        window?.run {
                            addFlags(
                                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            )
                            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                            setWindowAnimations(0)
                            setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) attributes = attributes.apply {
                                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                            }
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        WebSubtitles(
                            vm, captionState, settings, settings.subtitleOffset.dp, null,
                            onOpenDirect = openDirect, onCapture = ::startCapture, showStatus = false,
                        )
                    }
                }
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

        // 漫画阅读模式：盖在网页上面（网页留着，退出后还在原处）
        val readingManga = tab.manga.manga
        if (reading && readingManga != null) {
            MangaReader(
                readingManga.toBook(),
                onExit = { reading = false },
                onOpenUrl = { url, reader ->
                    reading = false
                    reopenAfter = if (reader) readingManga.pageUrl else null
                    go(url)
                },
            )
        } else if (reading && readingManga == null) {
            // 网页换了、漫画没了
            LaunchedEffect(Unit) { reading = false }
        }
    }
    }

    // 漫画首页点了「继续阅读」：在新标签页（当前是空白页就在当前页）打开，识别出漫画后直接进阅读模式
    LaunchedEffect(Unit) {
        val url = com.aisub.player.web.WebLaunch.pending ?: return@LaunchedEffect
        com.aisub.player.web.WebLaunch.pending = null
        if (tab.url == null) go(url) else newTab(url)
        reopenAfter = ""
    }

    // 上一话 / 下一话打开后：新网页识别出漫画（图片数稳定一会儿）就接着用阅读模式看；太久没识别出来就算了
    val foundManga = tab.manga.manga
    LaunchedEffect(reopenAfter, foundManga?.pageUrl, foundManga?.pages?.size) {
        val from = reopenAfter ?: return@LaunchedEffect
        if (foundManga != null && foundManga.pageUrl != from) {
            delay(1_200)
            reopenAfter = null
            openReader()
        }
    }
    LaunchedEffect(reopenAfter) {
        if (reopenAfter != null) { delay(20_000); reopenAfter = null }
    }

    if (dialog == WebDialog.TABS) WebTabsDialog(
        tabs, activeIndex,
        onPick = { dialog = null; switchTo(it) },
        onClose = ::closeTab,
        onNew = { dialog = null; newTab() },
        onDismiss = { dialog = null },
    )

    when (dialog) {
        WebDialog.ADDRESS -> TvAddressDialog(
            initial = tab.pageUrl.orEmpty(),
            onGo = { dialog = null; go(it) },
            onDismiss = { dialog = null },
        )
        WebDialog.LANGUAGE -> WebLanguageDialog(
            asr = vm.asrLanguage.collectAsStateWithLifecycle().value,
            target = vm.targetLanguage.collectAsStateWithLifecycle().value,
            settings = settings,
            onConfirm = { asr, target ->
                dialog = null
                // 只换翻译语言、要重新翻译时：先选翻译模型
                if (asr == vm.asrLanguage.value && target != vm.targetLanguage.value && captionState.running && settings.autoTranslate) {
                    afterModel = { vm.setLanguages(main.state.value.settings, asr, target) }
                    dialog = WebDialog.TRANSLATE_MODEL
                } else vm.setLanguages(main.state.value.settings, asr, target)
            },
            onDismiss = { dialog = null },
        )
        WebDialog.GENERATE -> {
            val base = vm.captionSettings(settings)
            AsrLanguageDialog(
                base.asrLanguage, settings, translate = settings.autoTranslate, onDismiss = { dialog = null },
                languageHint = "视频语言（网页字幕都用它，菜单「字幕语言」里也能改；不确定选「自动」；点一下就开始识别）",
            ) { code, engine, pick ->
                var updated = settings.copy(asrEngine = engine)
                if (pick != null && pick.valid) updated = pick.applyTo(updated)
                if (updated != settings) main.updateSettings(updated)
                // 选的和现在用的一样就不改（保持「同设置」）
                if (code != base.asrLanguage) vm.setLanguages(updated, code, vm.targetLanguage.value)
                dialog = null
                vm.captions.start(vm.captionSettings(updated))
            }
        }
        WebDialog.TRANSLATE_MODEL -> TranslateModelDialog(
            settings = settings, title = "选择翻译模型",
            onDismiss = { dialog = null; afterModel = null },
        ) { updated ->
            if (updated != settings) main.updateSettings(updated)
            dialog = null
            val then = afterModel
            afterModel = null
            then?.invoke()
        }
        WebDialog.ZOOM -> AppDialog(
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
        WebDialog.AD_RULES -> AdRulesDialog(vm.customAdRules(), onDismiss = { dialog = null }) { text ->
            vm.saveCustomAdRules(text)
            dialog = null
            tab.webView?.reload()
        }
        WebDialog.AD_LISTS -> AdListsDialog(vm, adBlock, adInfo) { changed ->
            dialog = null
            if (changed && adBlock) tab.webView?.reload()
        }
        WebDialog.TABS, null -> {}
    }

    if (showSniffed) {
        AppDialog(
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
    /** 网页全屏时字幕和状态分开画（字幕在单独的窗口里，见调用处），这里选画哪个 */
    showCue: Boolean = true,
    showStatus: Boolean = true,
) {
    // 关了 AI 字幕就不再显示（已识别的字幕常比播放提前几十秒，留着会像没关掉一样接着出字）
    if (!state.running && state.status == null) return
    // 初值 -1：拿到真实播放时间前不显示，不然刚挂载（进全屏、转屏）时会按"时间 0"闪出最开头那几句字幕
    var now by remember { mutableLongStateOf(-1L) }
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
        if (!showCue) return
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
    } else if (state.status != null && showStatus) {
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

/** 菜单里「拦截广告」下面那行：本页拦了几个、规则多少条、什么时候更新的 */
private fun adBlockSummary(info: com.aisub.player.web.AdBlockInfo, blocked: Int): String = when {
    info.rules == 0 && info.updating -> "正在下载广告规则…"
    info.rules == 0 -> info.error ?: "广告规则还没下载"
    else -> {
        val days = ((System.currentTimeMillis() - info.updatedAt) / (24 * 3600 * 1000L)).toInt()
        val age = if (days <= 0) "今天更新" else "$days 天前更新"
        info.error ?: "本页已拦截 $blocked 个 · ${info.rules} 条规则 · $age"
    }
}

/** 两个网址是不是同一个网站（a.example.com 和 b.example.com 算同一个） */
private fun sameSite(a: String?, b: String?): Boolean {
    val ha = a?.let { Uri.parse(it).host?.lowercase() } ?: return false
    val hb = b?.let { Uri.parse(it).host?.lowercase() } ?: return false
    return com.aisub.player.web.AdRules.siteOf(ha) == com.aisub.player.web.AdRules.siteOf(hb)
}

/** 点的是一个链接，整页却要去既不是那个链接、也不是当前网站的地方：网页脚本截了点击去跳广告 */
private fun isHijack(target: String, link: String, page: String?): Boolean =
    !sameSite(target, link) && !sameSite(target, page)

/** 用系统分享发一段文字（网址、直播源地址） */
internal fun shareText(context: android.content.Context, text: String, subject: String? = null) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_TEXT, text)
    if (subject != null) send.putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
    runCatching { context.startActivity(android.content.Intent.createChooser(send, "分享").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** 点选屏蔽时底部的操作栏 */
@Composable
private fun PickerBar(
    picked: com.aisub.player.web.PickedElement?,
    onParent: () -> Unit,
    onChild: () -> Unit,
    onCancel: () -> Unit,
    onBlock: (com.aisub.player.web.PickedElement) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (picked == null) {
                Text("点一下网页上要屏蔽的广告", style = MaterialTheme.typography.titleSmall)
                Text("点选时网页上的链接不会打开；按返回键退出", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("已选中 ${picked.label}（网页上共 ${picked.count} 处）", style = MaterialTheme.typography.titleSmall)
                Text(
                    picked.selector, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
                Text("没选全就点「扩大」选外面一层", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onParent, enabled = picked != null) { Text("扩大") }
                TextButton(onClick = onChild, enabled = picked != null) { Text("缩小") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("取消") }
                androidx.compose.material3.Button(onClick = { picked?.let(onBlock) }, enabled = picked != null) { Text("屏蔽") }
            }
        }
    }
}

/** 广告规则订阅：勾选常用的，加自己的订阅地址，手动更新 */
@Composable
private fun AdListsDialog(
    vm: com.aisub.player.web.WebViewModel,
    adBlock: Boolean,
    info: com.aisub.player.web.AdBlockInfo,
    onDismiss: (changed: Boolean) -> Unit,
) {
    LaunchedEffect(Unit) { vm.loadAdLists() }
    var changed by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val context = LocalContext.current
    AppDialog(
        onDismissRequest = { onDismiss(changed) },
        title = { Text("广告规则订阅") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    (if (adBlock) "" else "「拦截广告」还没打开，勾选的订阅打开后才生效。\n") +
                        "订阅越多拦得越全，但也更容易误拦、占内存；一般开 AdGuard 的前三个就够了。每 4 天自动更新。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                info.lists.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { changed = true; vm.setAdListEnabled(s.list.id, !s.enabled) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = s.enabled, onCheckedChange = null)
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.list.name, style = MaterialTheme.typography.bodyMedium)
                            val status = when {
                                s.error != null -> "下载失败：${s.error}"
                                s.rules > 0 -> {
                                    val days = ((System.currentTimeMillis() - s.updatedAt) / (24 * 3600 * 1000L)).toInt()
                                    "${s.rules} 条 · " + if (days <= 0) "今天更新" else "$days 天前更新"
                                }
                                s.enabled && info.updating -> "正在下载…"
                                else -> null
                            }
                            Text(
                                listOfNotNull(s.list.note.ifBlank { s.list.url.takeIf { s.list.custom } }, status).joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (s.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (s.list.custom) IconButton(onClick = { changed = true; vm.removeAdList(s.list.id) }) {
                            Icon(AppIcons.Close, "删除这个订阅")
                        }
                    }
                }
                TextButton(onClick = { adding = true }) { Text("添加订阅地址") }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(changed) }) { Text("完成") } },
        dismissButton = {
            TextButton(onClick = { changed = true; vm.updateAdRules() }, enabled = !info.updating) {
                Text(if (info.updating) "正在更新…" else "立即更新")
            }
        },
    )
    if (adding) AddAdListDialog(onDismiss = { adding = false }) { name, url ->
        if (vm.addAdList(name, url)) {
            changed = true
            adding = false
        } else android.widget.Toast.makeText(context, "这个订阅已经有了", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun AddAdListDialog(onDismiss: () -> Unit, onAdd: (name: String, url: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val valid = url.trim().let { it.startsWith("https://") || it.startsWith("http://") } && url.trim().length > 10
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加订阅地址") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "填 Adblock Plus / AdGuard 格式的规则地址（.txt），比如在 filterlists.com 上找到的。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    url, { url = it.trim() }, label = { Text("订阅地址") }, singleLine = true,
                    placeholder = { Text("https://example.com/filter.txt") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                    colors = appFieldColors(), modifier = Modifier.fillMaxWidth(), phoneInput = false,
                )
                OutlinedTextField(
                    name, { name = it }, label = { Text("名称（可不填）") }, singleLine = true,
                    colors = appFieldColors(), modifier = Modifier.fillMaxWidth(), phoneInput = false,
                )
                // 电视：长地址用手机粘贴
                PhoneInputButton(listOf(PhoneField("url", "订阅地址", url), PhoneField("name", "名称", name)), title = "广告规则订阅") { v ->
                    url = v["url"]?.trim() ?: url
                    name = v["name"] ?: name
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name.trim(), url.trim()) }, enabled = valid) { Text("添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 自定义广告规则：一行一条，写法见说明 */
@Composable
private fun AdRulesDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义广告规则") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "一行一条，开着「拦截广告」时生效；点选屏蔽加的规则也在这里，删掉那一行就恢复。\n" +
                        "||ad.example.com^　拦这个域名的请求\n" +
                        "example.com##.popup-ad　在这个网站上藏起这个元素\n" +
                        "/api/ad/list\$nolist　接口回「空列表」（\$nodata 回「没有数据」）\n" +
                        "@@||cdn.example.com/player.js　放行被误拦的\n" +
                        "! 开头的是注释",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    text, { text = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).defaultMinSize(minHeight = 160.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    placeholder = { Text("example.com##.ad-banner") },
                    colors = appFieldColors(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private enum class WebDialog { LANGUAGE, ZOOM, TABS, ADDRESS, GENERATE, TRANSLATE_MODEL, AD_RULES, AD_LISTS }

/** 电视上输入网址：手机扫码输入（主要），也可以用遥控器打字 */
@Composable
private fun TvAddressDialog(initial: String, onGo: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入网址或搜索") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PhoneFormPanel(
                    title = "在电视上打开网页",
                    caption = "用手机扫码（手机和电视要在同一个局域网），输入网址或搜索词后提交，电视上直接打开。",
                    fields = listOf(PhoneField("url", "网址或搜索词", initial)),
                    onCancel = onDismiss,
                    onValues = { v -> v["url"]?.trim()?.takeIf { it.isNotEmpty() }?.let(onGo) },
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("或用遥控器输入") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (text.isNotBlank()) onGo(text) }),
                    modifier = Modifier.fillMaxWidth(), phoneInput = false,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onGo(text) }, enabled = text.isNotBlank()) { Text("打开") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 电视上网页里的鼠标指针（尖端在左上角） */
@Composable
private fun TvCursor(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(24.dp)) {
        val w = size.width
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0f); lineTo(0f, w * 0.85f); lineTo(w * 0.24f, w * 0.64f)
            lineTo(w * 0.40f, w); lineTo(w * 0.54f, w * 0.94f); lineTo(w * 0.38f, w * 0.58f)
            lineTo(w * 0.66f, w * 0.58f); close()
        }
        drawPath(path, Color.White)
        drawPath(path, Color.Black, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
    }
}

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

    /** 最近一次点网页时点中的链接（没点中链接是 null）和时间：用来认出点链接后被脚本拐到别的网站的广告 */
    var tappedLink: String? = null
    var tappedAt = 0L

    /** 刚才点中的链接（3 秒内）；没点中链接返回 null */
    fun recentLink(): String? = tappedLink?.takeIf { android.os.SystemClock.uptimeMillis() - tappedAt < 3_000 }

    /** 网页上识别出的漫画（各个框架报告的汇总） */
    val manga = com.aisub.player.web.WebMangaCollector()

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
    AppDialog(
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
    AppDialog(
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

/** 手机 WebView 的 User-Agent 改成同版本的电脑 Chrome（网站按它给电脑版网页） */
private fun desktopAgentFor(mobile: String): String {
    // 太旧的版本号有的网站会提示升级浏览器（实测 B 站电脑版在 87 的 WebView 上也能正常播放）
    val chrome = Regex("Chrome/(\\d+)[\\d.]*").find(mobile)?.takeIf { it.groupValues[1].toInt() >= 120 }?.value?.removePrefix("Chrome/") ?: "120.0.0.0"
    return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
}
