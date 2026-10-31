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
import org.json.JSONArray
import org.json.JSONObject

/** 网页里加载过的视频地址（m3u8、mp4 等），可以改用播放器打开。 */
data class SniffedMedia(val url: String, val page: String, val pageTitle: String)

data class WebPage(val title: String, val url: String)

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

    /** 上次看的网页：离开网页页面（例如去播放页）再回来时接着打开 */
    var lastUrl: String?
        get() = prefs.getString("last", null)
        set(v) = prefs.edit().putString("last", v).apply()

    /** 页面在加载新网址：之前找到的视频地址作废 */
    fun onPageStarted(url: String) {
        _sniffed.value = emptyList()
        _videoRect.value = null
        _videoAspect.value = null
        lastUrl = url
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
        prefs.edit().putString("history", JSONArray(list.map { JSONObject().put("t", it.title).put("u", it.url) }).toString()).apply()
    }

    /** 标题是网址、空的，或者只是网站名（还没换成视频标题）时不用 */
    private fun goodTitle(title: String?): String? = title?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("http") }

    private fun loadHistory(): List<WebPage> = runCatching {
        val arr = JSONArray(prefs.getString("history", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> WebPage(o.optString("t"), o.optString("u")) } }
    }.getOrDefault(emptyList())

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

    /** 注入网页的接口（在 WebView 的后台线程上被调用） */
    inner class Bridge {
        @JavascriptInterface
        fun enabled(): Boolean = captions.enabled

        @JavascriptInterface
        fun generation(): Int = captions.generation

        /** 顶层网页的缩放（百分比） */
        @JavascriptInterface
        fun pageZoom(): Int = this@WebViewModel.pageZoom

        @JavascriptInterface
        fun audio(frame: String, base64: String, startMs: Double, rate: Double, source: String) = captions.onAudio(frame, base64, startMs, rate, source)

        @JavascriptInterface
        fun ahead(frame: String, base64: String, startMs: Double, source: String) = captions.onAhead(frame, base64, startMs, source)

        @JavascriptInterface
        fun tick(frame: String, href: String, ms: Double, paused: Boolean, rate: Double, top: Double, bottom: Double, viewport: Double, width: Double, height: Double, source: String) {
            captions.onTick(frame, href, ms, paused, rate, source)
            if (width > 0 && height > 0) _videoAspect.value = (width / height).toFloat()
            _videoRect.value = if (bottom > 0) VideoRect(top.toFloat(), bottom.toFloat(), viewport.toFloat()) else null
        }

        @JavascriptInterface
        fun problem(frame: String, kind: String, detail: String) = captions.onProblem(kind, detail)
    }

    override fun onCleared() {
        captions.stop()
    }

    companion object {
        private const val MAX_HISTORY = 30
        const val ZOOM_MIN = 40
        const val ZOOM_MAX = 150

        /** 分享、统计用的参数：同一个视频带不带它算同一条最近访问 */
        private val TRACKING = Regex("^(pp|si|feature|spm_id_from|vd_source|share_.*|from_.*|utm_.*|t|ref|from|source)$")

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
