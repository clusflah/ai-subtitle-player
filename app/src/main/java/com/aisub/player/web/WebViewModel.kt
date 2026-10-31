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

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<WebPage>> = _history.asStateFlow()

    /** 上次看的网页：离开网页页面（例如去播放页）再回来时接着打开 */
    var lastUrl: String?
        get() = prefs.getString("last", null)
        set(v) = prefs.edit().putString("last", v).apply()

    /** 页面在加载新网址：之前找到的视频地址作废 */
    fun onPageStarted(url: String) {
        _sniffed.value = emptyList()
        _videoRect.value = null
        lastUrl = url
    }

    fun onPageFinished(title: String?, url: String) {
        if (!url.startsWith("http")) return
        val page = WebPage(title?.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: Uri.parse(url).host.orEmpty(), url)
        val list = (listOf(page) + _history.value.filter { it.url != url }).take(MAX_HISTORY)
        _history.value = list
        prefs.edit().putString("history", JSONArray(list.map { JSONObject().put("t", it.title).put("u", it.url) }).toString()).apply()
    }

    fun removeHistory(page: WebPage) {
        val list = _history.value.filter { it.url != page.url }
        _history.value = list
        prefs.edit().putString("history", JSONArray(list.map { JSONObject().put("t", it.title).put("u", it.url) }).toString()).apply()
    }

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
        fun audio(frame: String, base64: String, startMs: Double, rate: Double) = captions.onAudio(frame, base64, startMs, rate)

        @JavascriptInterface
        fun tick(frame: String, href: String, ms: Double, paused: Boolean, rate: Double, top: Double, bottom: Double, viewport: Double) {
            captions.onTick(frame, href, ms, paused, rate)
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

        /** 视频文件和播放列表；DASH、HLS 的分片（m4s、ts）不算 */
        private val MEDIA = Regex("\\.(m3u8|mpd|mp4|m4v|webm|flv|mov|mkv)$", RegexOption.IGNORE_CASE)
    }
}
