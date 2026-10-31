package com.aisub.player.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val settings = main.state.collectAsStateWithLifecycle().value.settings

    var url by remember { mutableStateOf(vm.lastUrl) }
    var address by remember { mutableStateOf(url.orEmpty()) }
    var editing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(100) }
    var canGoBack by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf<Pair<View, WebChromeClient.CustomViewCallback>?>(null) }
    var showSniffed by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val script = remember { context.assets.open("web_captions.js").bufferedReader().use { it.readText() } }

    fun go(input: String) {
        val target = toUrl(input) ?: return
        editing = false
        if (url == null) url = target else webView?.loadUrl(target)
        address = target
    }

    fun playInPlayer(mediaUrl: String, page: String, title: String) {
        vm.captions.stop()
        webView?.onPause()
        play(listOf(vm.entryFor(mediaUrl, page, title, webView?.settings?.userAgentString.orEmpty())), 0)
    }

    // 网页视频开始放了：音乐、播放页的视频先停下
    LaunchedEffect(Unit) { main.player.pause() }
    DisposableEffect(Unit) {
        onDispose {
            vm.captions.stop()
            webView?.destroy()
            webView = null
        }
    }

    // 网页全屏：横屏、隐藏状态栏
    DisposableEffect(fullscreen != null) {
        val window = activity?.window
        if (fullscreen != null && window != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (fullscreen != null && window != null) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler {
        when {
            fullscreen != null -> fullscreen?.second?.onCustomViewHidden()
            editing && url != null -> editing = false
            canGoBack -> webView?.goBack()
            url != null -> {
                vm.captions.stop()
                webView?.destroy()
                webView = null
                url = null
                vm.lastUrl = null
                address = ""
            }
            else -> onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // 地址栏
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, "返回") }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; editing = true },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("输入网址或搜索") },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go(address) }),
                )
                if (url != null) {
                    IconButton(onClick = { webView?.reload() }) { Icon(AppIcons.Refresh, "刷新") }
                    if (sniffed.isNotEmpty()) {
                        IconButton(onClick = { showSniffed = true }) {
                            BadgedBox(badge = { Badge { Text("${sniffed.size}") } }) { Icon(AppIcons.Movie, "网页里的视频地址") }
                        }
                    }
                    IconButton(onClick = {
                        if (captionState.running) vm.captions.stop() else vm.captions.start(main.state.value.settings)
                    }) {
                        Icon(
                            AppIcons.Subtitles, if (captionState.running) "关闭 AI 字幕" else "开启 AI 字幕",
                            tint = if (captionState.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (url != null && progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))

            if (url == null) {
                WebHome(history, onOpen = { go(it) }, onRemove = vm::removeHistory)
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val viewHeight = maxHeight
                    val viewWidth = maxWidth
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
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
                                addJavascriptInterface(vm.Bridge(), "VoxBridge")
                                val startScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                                if (startScript) WebViewCompat.addDocumentStartJavaScript(this, script, setOf("*"))
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        // 「打开 App」之类的跳转（bilibili://、intent://）不处理，留在网页里
                                        val scheme = request.url.scheme?.lowercase()
                                        return scheme != "http" && scheme != "https"
                                    }

                                    override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
                                        vm.onPageStarted(pageUrl)
                                        if (!editing) address = pageUrl
                                    }

                                    override fun onPageFinished(view: WebView, pageUrl: String) {
                                        // 不支持开头注入的旧 WebView：加载完再注入（只有顶层页面）
                                        if (!startScript) view.evaluateJavascript(script, null)
                                        vm.onPageFinished(view.title, pageUrl)
                                        canGoBack = view.canGoBack()
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView, pageUrl: String, isReload: Boolean) {
                                        canGoBack = view.canGoBack()
                                        if (!editing) address = pageUrl
                                    }

                                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                        if (!request.isForMainFrame) {
                                            val page = request.requestHeaders["Referer"] ?: vm.lastUrl.orEmpty()
                                            vm.onRequest(request.url.toString(), page, "")
                                        }
                                        return null
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                                        progress = newProgress
                                    }

                                    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                        fullscreen = view to callback
                                    }

                                    override fun onHideCustomView() {
                                        fullscreen = null
                                    }
                                }
                                webView = this
                                loadUrl(url!!)
                            }
                        },
                    )
                    // 字幕放在网页视频的下沿；不知道视频在哪（iframe 里的）就放在底部
                    val bottom = rect?.let { r ->
                        if (r.viewportWidth <= 0) return@let null
                        val bottomDp = viewWidth * (r.bottom / r.viewportWidth)
                        (viewHeight - bottomDp + 6.dp).takeIf { bottomDp in 40.dp..viewHeight }
                    } ?: 24.dp
                    WebSubtitles(vm, captionState, settings, bottom) { playInPlayer(it, webView?.url.orEmpty(), webView?.title.orEmpty()) }
                }
            }
        }

        // 网页全屏的画面（含字幕）
        fullscreen?.let { (view, _) ->
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                WebSubtitles(vm, captionState, settings, settings.subtitleOffset.dp) {
                    fullscreen?.second?.onCustomViewHidden()
                    playInPlayer(it, webView?.url.orEmpty(), webView?.title.orEmpty())
                }
            }
        }
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
                                playInPlayer(m.url, m.page, webView?.title.orEmpty())
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
    onOpenDirect: (String) -> Unit,
) {
    if (!state.running && state.cues.isEmpty() && state.status == null) return
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            now = vm.captions.videoTimeNow() ?: -1
            delay(100)
        }
    }
    val cue: Cue? = if (now >= 0) state.cues.cueAt(now) else null
    if (cue != null && settings.showAiSubtitles) {
        val lines = if (cue.translation == cue.text) listOf(cue.text to true) else buildList {
            if (settings.showTranslation && cue.translation != null) add(cue.translation to true)
            if (settings.showOriginal || cue.translation == null) add(cue.text to false)
        }
        // 上下两行分开放的设置在网页里不好用（不知道画面顶部在哪）：始终叠在一起
        StyledSubtitleLines(lines, settings.copy(topLineFromTop = -1), bottom)
    } else if (state.status != null) {
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = bottom, start = 24.dp, end = 24.dp).widthIn(max = 520.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.status, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
            state.directUrl?.let { direct ->
                Spacer(Modifier.size(8.dp))
                Text(
                    "用播放器打开", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { onOpenDirect(direct) },
                )
            }
        }
    }
}

/** 还没打开网页时：常用网站和最近看过的网页 */
@Composable
private fun WebHome(history: List<com.aisub.player.web.WebPage>, onOpen: (String) -> Unit, onRemove: (com.aisub.player.web.WebPage) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "在网页里播放视频，点右上角的字幕按钮，边播边生成 AI 字幕（识别、翻译用设置里选的服务）。" +
                    "网页能拿到视频地址时，也可以改用译幕播放器打开。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
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
