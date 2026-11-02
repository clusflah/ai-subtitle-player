package com.aisub.player.web

import android.app.Application
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisub.player.live.LiveHeaders
import com.aisub.player.media.MediaEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 网页里加载过的视频地址（m3u8、mp4 等），可以改用播放器打开。 */
data class SniffedMedia(val url: String, val page: String, val pageTitle: String)

data class WebPage(val title: String, val url: String)

/** 点选屏蔽选中的元素：CSS 选择器、网页上一共匹配几个、简短说明（标签和大小） */
data class PickedElement(val selector: String, val count: Int, val label: String)

/** 视频在网页里的位置（CSS 像素，相对网页可见区域）；字幕放在它的下沿 */
data class VideoRect(val top: Float, val bottom: Float, val viewportWidth: Float)

/**
 * 网页：浏览网站，在网页里播放现有方式接不进来的视频（需要登录、只能在网页里放的），
 * 边播边识别生成 AI 字幕（见 [WebCaptions]）；也会记下网页加载的视频地址，能直接拿到的可以改用播放器打开。
 */
class WebViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("web", Context.MODE_PRIVATE)

    val captions = WebCaptions(app, viewModelScope)

    private val _sniffed = MutableStateFlow<List<SniffedMedia>>(emptyList())
    val sniffed: StateFlow<List<SniffedMedia>> = _sniffed.asStateFlow()

    private val _videoRect = MutableStateFlow<VideoRect?>(null)
    val videoRect: StateFlow<VideoRect?> = _videoRect.asStateFlow()

    private val _videoAspect = MutableStateFlow<Float?>(null)
    /** 正在放的视频的宽高比（宽 / 高）；网页全屏时按它选横屏还是竖屏 */
    val videoAspect: StateFlow<Float?> = _videoAspect.asStateFlow()

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<WebPage>> = _history.asStateFlow()

    // ---- 网页字幕的语言：不填就跟设置里的一样 ----

    private val _asrLanguage = MutableStateFlow(prefs.getString("asrLanguage", null))
    /** 网页视频说的语言（识别用）；null 跟设置 */
    val asrLanguage: StateFlow<String?> = _asrLanguage.asStateFlow()

    private val _targetLanguage = MutableStateFlow(prefs.getString("targetLanguage", null))
    /** 翻译成的语言；null 跟设置 */
    val targetLanguage: StateFlow<String?> = _targetLanguage.asStateFlow()

    /** 网页字幕实际用的设置 */
    fun captionSettings(base: com.aisub.player.AppSettings) = base.copy(
        asrLanguage = _asrLanguage.value ?: base.asrLanguage,
        targetLanguage = _targetLanguage.value ?: base.targetLanguage,
    )

    fun setLanguages(base: com.aisub.player.AppSettings, asr: String?, target: String?) {
        val asrChanged = asr != _asrLanguage.value
        val targetChanged = target != _targetLanguage.value
        _asrLanguage.value = asr
        _targetLanguage.value = target
        prefs.edit().putString("asrLanguage", asr).putString("targetLanguage", target).apply()
        val s = captionSettings(base)
        if (asrChanged) captions.restart(s) else if (targetChanged) captions.retranslate(s)
    }

    // ---- 页面缩放：每个网站、横竖屏分别记住 ----

    /** 网页脚本读的当前缩放（百分比） */
    @Volatile
    var pageZoom = 100
        private set
    private var zoomKey: String? = null

    /** 当前网站和屏幕方向：读出这个组合记住的缩放 */
    fun zoomFor(url: String?, landscape: Boolean): Int {
        val host = url?.let { Uri.parse(it).host } ?: return 100.also { pageZoom = it; zoomKey = null }
        zoomKey = "zoom.$host.${if (landscape) "land" else "port"}"
        pageZoom = prefs.getInt(zoomKey, 100)
        return pageZoom
    }

    fun setPageZoom(percent: Int) {
        pageZoom = percent.coerceIn(ZOOM_MIN, ZOOM_MAX)
        zoomKey?.let { k -> prefs.edit().apply { if (pageZoom == 100) remove(k) else putInt(k, pageZoom) }.apply() }
    }

    // ---- 屏蔽「打开 App」推广（悬浮按钮、横幅、跳转失败的弹窗），见 web_cleanup.js ----

    private val _cleanPages = MutableStateFlow(prefs.getBoolean("cleanPages", true))
    val cleanPages: StateFlow<Boolean> = _cleanPages.asStateFlow()

    fun setCleanPages(on: Boolean) {
        _cleanPages.value = on
        prefs.edit().putBoolean("cleanPages", on).apply()
    }

    // ---- 拦截广告：AdGuard 的过滤规则，见 [AdBlock] ----

    private val _adBlock = MutableStateFlow(prefs.getBoolean("adBlock", false))
    val adBlock: StateFlow<Boolean> = _adBlock.asStateFlow()
    val adBlockInfo: StateFlow<AdBlockInfo> = AdBlock.info

    /** 这个网页拦了几个请求（网页开始加载时清零） */
    private val blocked = java.util.concurrent.atomic.AtomicInteger()
    val blockedOnPage: Int get() = blocked.get()

    init {
        AdBlock.enabled = _adBlock.value
        AdBlock.onBlocked = { blocked.incrementAndGet() }
        AdBlock.hookServiceWorkers()
        if (_adBlock.value) AdBlock.ensure(app, viewModelScope)
    }

    fun setAdBlock(on: Boolean) {
        _adBlock.value = on
        AdBlock.enabled = on
        prefs.edit().putBoolean("adBlock", on).apply()
        if (on) AdBlock.ensure(getApplication(), viewModelScope)
    }

    fun updateAdRules() {
        viewModelScope.launch { AdBlock.update(getApplication()) }
    }

    // 广告规则订阅：勾选常用的、加自己的
    fun loadAdLists() = AdBlock.loadInfo(getApplication(), viewModelScope)
    fun setAdListEnabled(id: String, on: Boolean) = AdBlock.setListEnabled(getApplication(), viewModelScope, id, on)
    fun addAdList(name: String, url: String): Boolean = AdBlock.addList(getApplication(), viewModelScope, name, url)
    fun removeAdList(id: String) = AdBlock.removeList(getApplication(), viewModelScope, id)

    /** 自己写的广告规则（原文） */
    fun customAdRules(): String = AdBlock.customText(getApplication())

    fun saveCustomAdRules(text: String) = AdBlock.saveCustom(getApplication(), text)

    // ---- 点选屏蔽：网页里点选一个元素（web_picker.js），屏蔽时加一条「网站##选择器」的自定义规则 ----

    private val _picked = MutableStateFlow<PickedElement?>(null)
    /** 点选中的元素；还没点是 null */
    val picked: StateFlow<PickedElement?> = _picked.asStateFlow()

    fun clearPicked() {
        _picked.value = null
    }

    /** 屏蔽这个网站上的这类元素；拦截广告没开的话顺手打开（自定义规则靠它生效） */
    fun blockElement(pageUrl: String, selector: String) {
        val host = AdRules.hostOf(pageUrl)?.removePrefix("www.") ?: return
        AdBlock.addCustom(getApplication(), "$host##$selector")
        if (!_adBlock.value) setAdBlock(true)
    }

    /** 在 WebView 的后台线程上调：要拦的广告请求返回空响应 */
    fun interceptAd(request: android.webkit.WebResourceRequest, pageUrl: String?): android.webkit.WebResourceResponse? {
        if (!_adBlock.value) return null
        return AdBlock.intercept(request, pageUrl)?.also { blocked.incrementAndGet() }
    }

    // ---- 桌面版网页：按网站记住（手机网页功能不全的，例如 B 站手机网页没有进度条、不能换清晰度） ----

    private var desktopSites: Set<String> = prefs.getStringSet("desktopSites", DEFAULT_DESKTOP).orEmpty().toSet()

    fun isDesktop(url: String?): Boolean = siteOf(url)?.let { it in desktopSites } ?: false

    fun setDesktop(url: String?, on: Boolean) {
        val site = siteOf(url) ?: return
        desktopSites = if (on) desktopSites + site else desktopSites - site
        prefs.edit().putStringSet("desktopSites", desktopSites).apply()
    }

    // ---- 标签页：打开着的网页记下来，离开网页页面（例如去播放页）再回来、重开 App 时都还在 ----

    /** 记住的标签页（null 是新标签页）和当前是第几个 */
    fun savedTabs(): Pair<List<String?>, Int> {
        val saved = prefs.getString("tabs", null)
        if (saved == null) return listOf(prefs.getString("last", null)) to 0 // 2.9.22 以前只记一个网页
        val arr = runCatching { JSONArray(saved) }.getOrDefault(JSONArray())
        val urls = List(arr.length()) { arr.optString(it).ifEmpty { null } }
        return urls.ifEmpty { listOf(null) } to prefs.getInt("activeTab", 0)
    }

    fun saveTabs(urls: List<String?>, active: Int) {
        prefs.edit().putString("tabs", JSONArray(urls.map { it.orEmpty() }).toString()).putInt("activeTab", active).remove("last").apply()
    }

    /** 页面在加载新网址、换了标签页：之前找到的视频地址、视频位置作废 */
    fun onPageStarted() {
        blocked.set(0)
        _sniffed.value = emptyList()
        _videoRect.value = null
        _videoAspect.value = null
    }

    fun onTabSwitched() {
        onPageStarted()
        captions.reset()
    }

    // ---- 收藏 ----

    private val _bookmarks = MutableStateFlow(loadPages("bookmarks"))
    val bookmarks: StateFlow<List<WebPage>> = _bookmarks.asStateFlow()

    fun isBookmarked(url: String?) = url != null && _bookmarks.value.any { historyKey(it.url) == historyKey(url) }

    /** 收藏或取消收藏这个网页，返回现在是否收藏着 */
    fun toggleBookmark(title: String?, url: String): Boolean {
        val key = historyKey(url)
        val now = !isBookmarked(url)
        val list = if (now) listOf(WebPage(goodTitle(title) ?: Uri.parse(url).host.orEmpty(), url)) + _bookmarks.value
        else _bookmarks.value.filter { historyKey(it.url) != key }
        _bookmarks.value = list
        savePages("bookmarks", list)
        return now
    }

    fun removeBookmark(page: WebPage) {
        _bookmarks.value = _bookmarks.value.filter { it.url != page.url }
        savePages("bookmarks", _bookmarks.value)
    }

    fun onPageFinished(title: String?, url: String) {
        if (!url.startsWith("http")) return
        val key = historyKey(url)
        val known = _history.value.firstOrNull { historyKey(it.url) == key }
        val page = WebPage(goodTitle(title) ?: known?.title ?: Uri.parse(url).host.orEmpty(), url)
        saveHistory((listOf(page) + _history.value.filter { historyKey(it.url) != key }).take(MAX_HISTORY))
    }

    /** 单页网站（YouTube、B 站）加载完以后才换标题：换了就更新最近访问里的那一条 */
    fun onTitle(title: String?, url: String?) {
        val t = goodTitle(title) ?: return
        val key = historyKey(url ?: return)
        if (_history.value.none { historyKey(it.url) == key && it.title != t }) return
        saveHistory(_history.value.map { if (historyKey(it.url) == key) it.copy(title = t) else it })
    }

    fun removeHistory(page: WebPage) = saveHistory(_history.value.filter { it.url != page.url })

    private fun saveHistory(list: List<WebPage>) {
        _history.value = list
        savePages("history", list)
    }

    private fun savePages(key: String, list: List<WebPage>) =
        prefs.edit().putString(key, JSONArray(list.map { JSONObject().put("t", it.title).put("u", it.url) }).toString()).apply()

    private fun loadPages(key: String): List<WebPage> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> WebPage(o.optString("t"), o.optString("u")) } }
    }.getOrDefault(emptyList())

    /** 标题是网址、空的，或者只是网站名（还没换成视频标题）时不用 */
    private fun goodTitle(title: String?): String? = title?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("http") }

    private fun loadHistory(): List<WebPage> = loadPages("history")

    /** WebView 的每个请求（后台线程）：看起来是视频文件或播放列表的记下来 */
    fun onRequest(url: String, page: String, pageTitle: String) {
        if (!MEDIA.containsMatchIn(Uri.parse(url).path.orEmpty())) return
        _sniffed.update { list ->
            if (list.any { it.url == url } || list.size >= 30) list else list + SniffedMedia(url, page, pageTitle)
        }
    }

    /** 改用播放器打开：带上网页的 Referer、User-Agent、Cookie */
    fun entryFor(url: String, page: String, title: String, userAgent: String): MediaEntry {
        val headers = buildMap {
            put("Referer", page)
            put("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { put("Cookie", it) }
        }
        LiveHeaders.put(url, headers)
        val name = title.ifBlank { Uri.parse(url).lastPathSegment ?: url }
        return MediaEntry(url, name, isVideo = true)
    }

    /**
     * 注入网页的接口（在 WebView 的后台线程上被调用）。每个标签页一个；
     * 只有正在看的那个标签页的网页能送声音、报告播放位置（切走的标签页里网页的脚本还在跑）
     */
    inner class Bridge(private val active: () -> Boolean) {
        @JavascriptInterface
        fun enabled(): Boolean = captions.enabled && active()

        @JavascriptInterface
        fun generation(): Int = captions.generation

        @JavascriptInterface
        fun cleanPage(): Boolean = _cleanPages.value

        /** 点选屏蔽：选中了一个元素（选择器、网页上一共几个、简短说明） */
        @JavascriptInterface
        fun pickerSelect(selector: String, count: Int, label: String) {
            if (active()) _picked.value = PickedElement(selector, count, label)
        }

        /** 这个网站要藏起来的广告元素（CSS）；没开拦截是空串 */
        @JavascriptInterface
        fun adCss(host: String): String = if (_adBlock.value) AdBlock.css(host) else ""

        /** 顶层网页的缩放（百分比） */
        @JavascriptInterface
        fun pageZoom(): Int = this@WebViewModel.pageZoom

        @JavascriptInterface
        fun audio(frame: String, base64: String, startMs: Double, rate: Double, source: String) {
            if (active()) captions.onAudio(frame, base64, startMs, rate, source)
        }

        @JavascriptInterface
        fun ahead(frame: String, base64: String, startMs: Double, source: String) {
            if (active()) captions.onAhead(frame, base64, startMs, source)
        }

        @JavascriptInterface
        fun tick(frame: String, href: String, ms: Double, paused: Boolean, rate: Double, top: Double, bottom: Double, viewport: Double, width: Double, height: Double, source: String) {
            if (!active()) return
            captions.onTick(frame, href, ms, paused, rate, source)
            if (width > 0 && height > 0) _videoAspect.value = (width / height).toFloat()
            _videoRect.value = if (bottom > 0) VideoRect(top.toFloat(), bottom.toFloat(), viewport.toFloat()) else null
        }

        @JavascriptInterface
        fun problem(frame: String, kind: String, detail: String) {
            if (active()) captions.onProblem(kind, detail)
        }
    }

    override fun onCleared() {
        captions.stop()
    }

    companion object {
        private const val MAX_HISTORY = 30
        const val ZOOM_MIN = 40
        const val ZOOM_MAX = 150

        /** 默认用桌面版的网站：B 站手机网页只有 360P、没有进度条，换清晰度要开 App */
        private val DEFAULT_DESKTOP = setOf("bilibili.com")

        /** 分享、统计用的参数：同一个视频带不带它算同一条最近访问 */
        private val TRACKING = Regex("^(pp|si|feature|spm_id_from|vd_source|share_.*|from_.*|utm_.*|t|ref|from|source)$")

        /** 网站：手机版、电脑版算同一个（m.bilibili.com 和 www.bilibili.com） */
        fun siteOf(url: String?): String? =
            url?.let { Uri.parse(it).host }?.removePrefix("www.")?.removePrefix("m.")?.takeIf { it.isNotEmpty() }

        fun historyKey(url: String): String {
            val u = Uri.parse(url)
            val query = u.queryParameterNames.filterNot { TRACKING.matches(it) }.sorted()
                .joinToString("&") { "$it=" + u.getQueryParameter(it) }
            return (u.host.orEmpty().removePrefix("www.").removePrefix("m.") + u.path.orEmpty().trimEnd('/') + "?" + query)
        }

        /** 视频文件和播放列表；DASH、HLS 的分片（m4s、ts）不算 */
        private val MEDIA = Regex("\\.(m3u8|mpd|mp4|m4v|webm|flv|mov|mkv)$", RegexOption.IGNORE_CASE)
    }
}
