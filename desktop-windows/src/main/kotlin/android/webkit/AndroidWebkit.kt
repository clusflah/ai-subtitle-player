package android.webkit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class JavascriptInterface

fun interface ValueCallback<T> {
    fun onReceiveValue(value: T)
}

interface WebResourceRequest {
    val url: Uri
    val isForMainFrame: Boolean
    val isRedirect: Boolean
    val hasGesture: Boolean
    val method: String
    val requestHeaders: Map<String, String>
}

class WebResourceResponse(
    val mimeType: String?,
    val encoding: String?,
    val data: InputStream?,
) {
    constructor(
        mimeType: String?,
        encoding: String?,
        statusCode: Int,
        reasonPhrase: String,
        responseHeaders: Map<String, String>?,
        data: InputStream?,
    ) : this(mimeType, encoding, data)
}

class RenderProcessGoneDetail {
    fun didCrash(): Boolean = false
}

open class ServiceWorkerClient {
    open fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
}

class ServiceWorkerController {
    fun setServiceWorkerClient(client: ServiceWorkerClient?) {}
    companion object {
        private val INSTANCE = ServiceWorkerController()
        @JvmStatic
        fun getInstance(): ServiceWorkerController = INSTANCE
    }
}

class CookieManager {
    private val cookies = ConcurrentHashMap<String, String>()

    fun setAcceptCookie(accept: Boolean) {}
    fun setAcceptThirdPartyCookies(webview: WebView?, accept: Boolean) {}

    fun getCookie(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val host = Uri.parse(url).host?.lowercase() ?: return null
        return cookies[host]
    }

    fun setCookie(url: String?, value: String?) {
        if (url.isNullOrBlank() || value.isNullOrBlank()) return
        val host = Uri.parse(url).host?.lowercase() ?: return
        cookies[host] = value
    }

    fun removeAllCookies(callback: ValueCallback<Boolean>?) {
        cookies.clear()
        callback?.onReceiveValue(true)
    }

    fun flush() {}

    companion object {
        private val INSTANCE = CookieManager()
        @JvmStatic
        fun getInstance(): CookieManager = INSTANCE
    }
}

class WebSettings {
    var javaScriptEnabled: Boolean = true
    var domStorageEnabled: Boolean = true
    var mediaPlaybackRequiresUserGesture: Boolean = false
    var mixedContentMode: Int = MIXED_CONTENT_COMPATIBILITY_MODE
    var useWideViewPort: Boolean = true
    var loadWithOverviewMode: Boolean = true
    var builtInZoomControls: Boolean = true
    var displayZoomControls: Boolean = false
    var userAgentString: String = DEFAULT_UA

    fun setSupportMultipleWindows(support: Boolean) {}

    companion object {
        const val MIXED_CONTENT_ALWAYS_ALLOW = 0
        const val MIXED_CONTENT_NEVER_ALLOW = 1
        const val MIXED_CONTENT_COMPATIBILITY_MODE = 2
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        @JvmStatic
        fun getDefaultUserAgent(context: Context?): String = DEFAULT_UA
    }
}

open class WebViewClient {
    open fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
    open fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {}
    open fun onPageFinished(view: WebView, url: String) {}
    open fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {}
    open fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = null
    open fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = true
}

open class WebChromeClient {
    fun interface CustomViewCallback {
        fun onCustomViewHidden()
    }

    open fun onProgressChanged(view: WebView, newProgress: Int) {}
    open fun onReceivedTitle(view: WebView, title: String?) {}
    open fun onShowCustomView(view: View, callback: CustomViewCallback) {}
    open fun onHideCustomView() {}
    open fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false
}

open class WebView(context: Context = android.content.GlobalDesktopApp) : ViewGroup(context) {
    class WebViewTransport {
        var webView: WebView? = null
    }

    class HitTestResult(val type: Int = 0, val extra: String? = null) {
        companion object {
            const val SRC_ANCHOR_TYPE = 7
        }
    }

    data class ExtractedLink(
        val title: String,
        val subtitle: String = "",
        val url: String,
        val isChapter: Boolean = false,
    )

    private val webSettings = WebSettings()
    var webViewClient: WebViewClient = WebViewClient()
    var webChromeClient: WebChromeClient = WebChromeClient()
    var url: String? by androidx.compose.runtime.mutableStateOf(null)
        private set
    var title: String? by androidx.compose.runtime.mutableStateOf(null)
        private set
    var loadingState: Boolean by androidx.compose.runtime.mutableStateOf(false)
        internal set
    var errorState: String? by androidx.compose.runtime.mutableStateOf(null)
        internal set
    var extractedMangaPages: List<String> by androidx.compose.runtime.mutableStateOf(emptyList())
        internal set
    var extractedPrevUrl: String? by androidx.compose.runtime.mutableStateOf(null)
        internal set
    var extractedNextUrl: String? by androidx.compose.runtime.mutableStateOf(null)
        internal set
    var extractedTocUrl: String? by androidx.compose.runtime.mutableStateOf(null)
        internal set
    var extractedLinks: List<ExtractedLink> by androidx.compose.runtime.mutableStateOf(emptyList())
        internal set
    var onOpenReaderCallback: (() -> Unit)? = null
    var onGoHomeCallback: (() -> Unit)? = null

    val hitTestResult: HitTestResult = HitTestResult()
    private val history = mutableListOf<String>()
    private val jsInterfaces = ConcurrentHashMap<String, Any>()
    @Volatile
    internal var lastHtmlPreview: String = ""

    @get:JvmName("settingsProp")
    val settings: WebSettings get() = webSettings
    fun getSettings(): WebSettings = webSettings

    fun addJavascriptInterface(obj: Any, name: String) {
        jsInterfaces[name] = obj
    }

    fun loadUrl(targetUrl: String) {
        if (targetUrl.startsWith("javascript:", ignoreCase = true)) {
            evaluateJavascript(targetUrl.substring("javascript:".length), null)
            return
        }
        val previousUrl = url
        url = targetUrl
        loadingState = true
        errorState = null
        extractedMangaPages = emptyList()
        extractedPrevUrl = null
        extractedNextUrl = null
        extractedTocUrl = null
        extractedLinks = emptyList()
        if (history.lastOrNull() != targetUrl) history.add(targetUrl)
        post {
            webViewClient.onPageStarted(this, targetUrl, null)
            webChromeClient.onProgressChanged(this, 20)
        }
        webExecutor.submit {
            runCatching {
                val reqBuilder = okhttp3.Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", webSettings.userAgentString)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                if (!previousUrl.isNullOrBlank() && previousUrl.startsWith("http")) {
                    reqBuilder.header("Referer", previousUrl)
                }
                CookieManager.getInstance().getCookie(targetUrl)?.takeIf { it.isNotBlank() }?.let {
                    reqBuilder.header("Cookie", it)
                }
                val (finalUrl, html) = com.aisub.player.remote.cloud.cloudHttp.newCall(reqBuilder.build()).execute().use { resp ->
                    resp.headers("Set-Cookie").forEach { cookie ->
                        CookieManager.getInstance().setCookie(targetUrl, cookie.substringBefore(';'))
                    }
                    if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    resp.request.url.toString() to body
                }
                lastHtmlPreview = html
                val rawTitle = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.let { DesktopWebMangaParser.unescapeHtml(it) }?.trim().orEmpty()
                val parsed = DesktopWebMangaParser.parse(finalUrl, html, rawTitle, webSettings.userAgentString)
                val pageTitle = parsed.title.ifBlank { rawTitle.ifBlank { finalUrl } }

                // 自动嗅探页面里的直链视频 (m3u8 / mp4)
                val mediaUrls = Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
                    .findAll(html).map { it.value.replace("\\/", "/") }.distinct().toMutableList()
                if (mediaUrls.isEmpty() && parsed.pages.size < 3) {
                    com.aisub.player.desktop.DesktopBinaryFinder.findYtDlp()?.let { ytdlp ->
                        runCatching {
                            val proc = ProcessBuilder(ytdlp, "-g", "--no-playlist", "--no-warnings", finalUrl).start()
                            val lines = proc.inputStream.bufferedReader().readLines().map { it.trim() }.filter { it.startsWith("http") }
                            proc.waitFor()
                            mediaUrls.addAll(lines)
                        }
                    }
                }
                for (mUrl in mediaUrls) {
                    val req = object : WebResourceRequest {
                        override val url: Uri = Uri.parse(mUrl)
                        override val isForMainFrame: Boolean = false
                        override val isRedirect: Boolean = false
                        override val hasGesture: Boolean = false
                        override val method: String = "GET"
                        override val requestHeaders: Map<String, String> = mapOf("Referer" to finalUrl)
                    }
                    webViewClient.shouldInterceptRequest(this, req)
                }

                if (parsed.pages.size >= 3) {
                    val reportJson = org.json.JSONObject()
                        .put("frame", finalUrl)
                        .put("top", true)
                        .put("title", pageTitle)
                        .put("pages", org.json.JSONArray(parsed.pages))
                        .put("next", parsed.next)
                        .put("prev", parsed.prev)
                        .put("toc", parsed.toc)
                        .toString()
                    (jsInterfaces["VoxBridge"] as? com.aisub.player.web.WebViewModel.Bridge)?.mangaFound(reportJson)
                }

                post {
                    url = finalUrl
                    title = pageTitle
                    extractedMangaPages = parsed.pages
                    extractedPrevUrl = parsed.prev.ifBlank { null }
                    extractedNextUrl = parsed.next.ifBlank { null }
                    extractedTocUrl = parsed.toc.ifBlank { null }
                    extractedLinks = parsed.links
                    loadingState = false
                    webChromeClient.onReceivedTitle(this, pageTitle)
                    webChromeClient.onProgressChanged(this, 100)
                    webViewClient.doUpdateVisitedHistory(this, finalUrl, false)
                    webViewClient.onPageFinished(this, finalUrl)
                }
            }.onFailure { err ->
                post {
                    loadingState = false
                    errorState = err.message ?: err.toString()
                    webChromeClient.onProgressChanged(this, 100)
                    webViewClient.onPageFinished(this, targetUrl)
                }
            }
        }
    }

    fun reload() {
        url?.let { loadUrl(it) }
    }

    fun stopLoading() {}
    fun canGoBack(): Boolean = history.size > 1
    fun goBack() {
        if (history.size > 1) {
            history.removeAt(history.lastIndex)
            loadUrl(history.last())
        }
    }

    fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
        resultCallback?.onReceiveValue("null")
    }

    fun onPause() {}
    fun onResume() {}
    fun setNetworkAvailable(networkUp: Boolean) {}
    fun destroy() {}

    companion object {
        private val webExecutor = Executors.newCachedThreadPool { r ->
            Thread(r, "Voxtitle-DesktopWebView").apply { isDaemon = true }
        }

        @JvmStatic
        fun setWebContentsDebuggingEnabled(enabled: Boolean) {}
    }
}


class MimeTypeMap private constructor() {
    fun getFileExtensionFromUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val clean = url.substringBefore('#').substringBefore('?')
        val slash = clean.lastIndexOf('/')
        val name = if (slash >= 0) clean.substring(slash + 1) else clean
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot + 1).lowercase() else ""
    }

    fun getMimeTypeFromExtension(extension: String?): String? {
        val ext = extension?.lowercase() ?: return null
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m4a", "aac" -> "audio/mp4"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            "ass", "ssa" -> "text/x-ssa"
            else -> java.net.URLConnection.guessContentTypeFromName("file.$ext")
        }
    }

    fun getExtensionFromMimeType(mimeType: String?): String? {
        val mime = mimeType?.lowercase() ?: return null
        return when {
            mime.contains("video/mp4") -> "mp4"
            mime.contains("video/x-matroska") -> "mkv"
            mime.contains("video/webm") -> "webm"
            mime.contains("audio/mpeg") -> "mp3"
            mime.contains("audio/flac") -> "flac"
            mime.contains("image/jpeg") -> "jpg"
            mime.contains("image/png") -> "png"
            mime.contains("image/webp") -> "webp"
            mime.contains("application/pdf") -> "pdf"
            else -> null
        }
    }

    companion object {
        private val INSTANCE = MimeTypeMap()
        @JvmStatic
        fun getSingleton(): MimeTypeMap = INSTANCE
    }
}

internal object DesktopWebMangaParser {
    data class ParseResult(
        val title: String,
        val pages: List<String>,
        val prev: String,
        val next: String,
        val toc: String,
        val links: List<WebView.ExtractedLink>,
    )

    private val LAZY_ATTRS = listOf(
        "data-src", "data-original", "data-lazy-src", "data-lazy", "data-echo",
        "data-url", "data-img", "data-original-src", "data-actualsrc", "data-kksrc",
        "data-srcset", "file", "_src", "srcset", "src",
    )

    private val NEXT_RE = Regex("""^\s*(?:下一[话話章回节節集卷页頁]|下[一]?章|下一篇|next\s*(?:chapter|episode|›|»|>)?|›|»)\s*[>›»]?\s*$""", RegexOption.IGNORE_CASE)
    private val PREV_RE = Regex("""^\s*[<‹«]?\s*(?:上一[话話章回节節集卷]|上[一]?章|上一篇|prev(?:ious)?\s*(?:chapter|episode)?|‹|«)\s*$""", RegexOption.IGNORE_CASE)
    private val TOC_RE = Regex("""^\s*(?:目录|目錄|章节目录|章節目錄|章节列表|返回目录|返回目錄|全部章节|漫画首页|漫畫首頁|详情|詳情|table of contents|chapters?)\s*$""", RegexOption.IGNORE_CASE)
    private val CHAPTER_LINK_RE = Regex("""(?:第\s*[0-9零一二三四五六七八九十百千.]+\s*[话話章回节節集卷册冊]|单行本|番外|最终[话話章]|chapter\s*\d+|ch\.?\s*\d+|vol\.?\s*\d+|/comic/\d+/\d+\.html)""", RegexOption.IGNORE_CASE)

    fun parse(pageUrl: String, html: String, rawTitle: String, userAgent: String): ParseResult {
        var mangaTitle = rawTitle
        val pages = ArrayList<String>()
        var prevUrl = ""
        var nextUrl = ""
        var tocUrl = ""

        // 1. 尝试解析「看漫画」(manhuagui.com / m.manhuagui.com) 或其他使用 p,a,c,k,e,d + LZString 的站点
        val packedMatches = Regex(
            """\}\(\s*'((?:\\.|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']+)'(?:\s*\[\s*'[^']*'\s*\]\(\s*'([^']*)'\s*\)|\.split\(\s*'\|'\s*\))""",
        ).findAll(html)

        for (m in packedMatches) {
            val p = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
            val a = m.groupValues[2].toIntOrNull() ?: continue
            val c = m.groupValues[3].toIntOrNull() ?: continue
            val dictRaw = m.groupValues[4]
            val isSplic = m.value.contains("\\x73\\x70\\x6c\\x69\\x63") || m.value.contains("splic")
            val words = if (isSplic) {
                decompressFromBase64(dictRaw)?.split('|')
            } else {
                dictRaw.split('|')
            } ?: continue

            val unpacked = unpackDeanEdwards(p, a, c, words)
            val smhMatch = Regex("""SMH\.(?:imgData|reader)\((\{[\s\S]*?\})\)""").find(unpacked)
            if (smhMatch != null) {
                runCatching {
                    val d = org.json.JSONObject(smhMatch.groupValues[1])
                    val sl = d.optJSONObject("sl")
                    val q = if (sl != null) "?e=${sl.opt("e")}&m=${sl.optString("m")}" else ""
                    val origin = "https://i.hamreus.com"
                    if (d.has("files")) {
                        val basePath = d.optString("path")
                        val arr = d.getJSONArray("files")
                        for (i in 0 until arr.length()) {
                            val f = arr.optString(i)
                            if (f.isNotEmpty()) pages.add(origin + encodeUriPath(basePath + f) + q)
                        }
                    } else if (d.has("images")) {
                        val arr = d.getJSONArray("images")
                        for (i in 0 until arr.length()) {
                            val img = arr.optString(i)
                            if (img.isNotEmpty()) pages.add(origin + encodeUriPath(img) + q)
                        }
                    }
                    val bname = d.optString("bname").ifEmpty { d.optString("bookName") }
                    val cname = d.optString("cname").ifEmpty { d.optString("chapterTitle") }
                    if (bname.isNotEmpty() || cname.isNotEmpty()) {
                        mangaTitle = listOf(bname, cname).filter { it.isNotEmpty() }.joinToString(" ")
                    }
                    val baseDir = pageUrl.substringBefore('#').substringBefore('?').substringBeforeLast('/') + "/"
                    val nextId = d.optInt("nextId", 0)
                    val prevId = d.optInt("prevId", 0)
                    if (nextId > 0) nextUrl = "${baseDir}${nextId}.html"
                    if (prevId > 0) prevUrl = "${baseDir}${prevId}.html"
                    tocUrl = baseDir
                }
            } else {
                // 通用打包脚本（例如 dm5 等）：提取里面的图片 URL
                for (imgMatch in Regex("""https?://[^\s"'<>\\]+?\.(?:jpg|jpeg|png|webp|avif)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE).findAll(unpacked)) {
                    val u = imgMatch.value
                    if (u !in pages) pages.add(u)
                }
            }
        }

        // 2. 动漫屋 (dm5.com / m.dm5.com)：若页面含 DM5_CID，请求 chapterfun.ashx 解包图片
        if (pages.size < 3 && html.contains("DM5_CID")) {
            runCatching {
                val cid = Regex("""var\s+DM5_CID\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)
                val mid = Regex("""var\s+DM5_MID\s*=\s*(\d+)""").find(html)?.groupValues?.get(1).orEmpty()
                val count = Regex("""var\s+DM5_IMAGE_COUNT\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val dt = Regex("""var\s+DM5_VIEWSIGN_DT\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1).orEmpty()
                val sign = Regex("""var\s+DM5_VIEWSIGN\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1).orEmpty()
                if (!cid.isNullOrEmpty() && count > 0) {
                    val baseDir = pageUrl.substringBefore('#').substringBefore('?').trimEnd('/')
                    var pageIdx = 1
                    while (pageIdx <= count.coerceAtMost(80) && pages.size < count) {
                        val funUrl = "$baseDir/chapterfun.ashx?cid=$cid&page=$pageIdx&key=&language=1&gtk=6&_cid=$cid&_mid=$mid&_dt=${java.net.URLEncoder.encode(dt, "UTF-8")}&_sign=$sign"
                        val req = okhttp3.Request.Builder()
                            .url(funUrl)
                            .header("Referer", pageUrl)
                            .header("User-Agent", userAgent)
                            .header("X-Requested-With", "XMLHttpRequest")
                            .build()
                        val js = com.aisub.player.remote.cloud.cloudHttp.newCall(req).execute().use { it.body?.string().orEmpty() }
                        val pm = Regex("""\}\(\s*'((?:\\.|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']*)'\.split\('\|'\)""").find(js)
                            ?: break
                        val unpacked = unpackDeanEdwards(
                            pm.groupValues[1].replace("\\'", "'"),
                            pm.groupValues[2].toInt(),
                            pm.groupValues[3].toInt(),
                            pm.groupValues[4].split('|'),
                        )
                        val pix = Regex("""var\s+pix\s*=\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1).orEmpty()
                        val pvals = Regex("""var\s+pvalue\s*=\s*\[([^\]]+)\]""").find(unpacked)?.groupValues?.get(1).orEmpty()
                        val cidParam = Regex("""cid=\d+&key=[a-f0-9]+""").find(unpacked)?.value.orEmpty()
                        var added = 0
                        for (pv in Regex("""["']([^"']+)["']""").findAll(pvals)) {
                            val path = pv.groupValues[1]
                            val full = if (path.startsWith("http")) path else "$pix$path" + (if (cidParam.isNotEmpty() && "?" !in path) "?$cidParam" else "")
                            if (full !in pages) {
                                pages.add(full)
                                added++
                            }
                        }
                        if (added == 0) break
                        pageIdx += added
                    }
                }
            }
        }

        // 3. 通用网页漫画：扫描 HTML 中的 <img> 标签（含懒加载属性）与脚本中的图片数组
        if (pages.size < 3) {
            val candidates = LinkedHashSet<String>()
            for (imgMatch in Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)) {
                val tag = imgMatch.value
                var src: String? = null
                for (attr in LAZY_ATTRS) {
                    val v = extractAttr(tag, attr)?.trim()
                    if (!v.isNullOrEmpty() && !v.startsWith("data:") && !v.startsWith("blob:") && !v.startsWith("about:")) {
                        src = if (attr.contains("srcset", ignoreCase = true)) {
                            v.split(',').mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull() }.lastOrNull()
                        } else v
                        if (!src.isNullOrEmpty()) break
                    }
                }
                val resolved = resolveUrl(pageUrl, src) ?: continue
                if (!resolved.startsWith("http")) continue
                val lower = resolved.lowercase()
                if (lower.contains(".svg") || lower.contains(".gif") || lower.contains("logo") ||
                    lower.contains("avatar") || lower.contains("favicon") || lower.contains("/cpic/") ||
                    lower.contains("banner") || lower.contains("/static/images/")
                ) continue
                candidates.add(resolved)
            }
            if (candidates.size >= 3) {
                pages.addAll(candidates)
            } else {
                // 扫描脚本里的 JSON 图片数组
                val scriptImgs = LinkedHashSet<String>()
                for (m in Regex("""["'](https?:\\?/\\?/[^"'\s<>]+?\.(?:jpg|jpeg|png|webp|avif)(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE).findAll(html)) {
                    val u = m.groupValues[1].replace("\\/", "/")
                    val lower = u.lowercase()
                    if (!lower.contains("logo") && !lower.contains("cover") && !lower.contains("avatar") && !lower.contains("/cpic/")) {
                        scriptImgs.add(u)
                    }
                }
                if (scriptImgs.size >= 3) pages.addAll(scriptImgs)
            }
        }

        // 4. 解析页面上的所有链接（同时解压 manhuagui 目录页隐藏在 __VIEWSTATE 里的章节列表）
        val fullHtml = buildString {
            append(html)
            for (vs in Regex("""id=["']__VIEWSTATE["'][^>]+value=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html)) {
                val decompressed = decompressFromBase64(vs.groupValues[1])
                if (!decompressed.isNullOrBlank()) {
                    append("\n")
                    append(decompressed)
                }
            }
        }

        val links = ArrayList<WebView.ExtractedLink>()
        val seenUrls = HashSet<String>()
        for (aMatch in Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE).findAll(fullHtml)) {
            val attrs = aMatch.groupValues[1]
            val inner = aMatch.groupValues[2]
            val href = extractAttr("<a $attrs>", "href")?.trim() ?: continue
            if (href.isEmpty() || href.startsWith("javascript:", ignoreCase = true) || href.startsWith("#")) continue
            val absUrl = resolveUrl(pageUrl, href) ?: continue
            if (!absUrl.startsWith("http") || absUrl == pageUrl) continue

            val h3 = Regex("""<h[1-5][^>]*>([\s\S]*?)</h[1-5]>""", RegexOption.IGNORE_CASE).find(inner)?.groupValues?.get(1)
            val titleAttr = extractAttr("<a $attrs>", "title")
            val stripped = stripHtml(inner)
            val linkTitle = unescapeHtml((h3?.let(::stripHtml) ?: titleAttr ?: stripped).trim()).replace(Regex("\\s+"), " ")
            if (linkTitle.isEmpty() || linkTitle.length > 80) continue

            if (nextUrl.isEmpty() && NEXT_RE.matches(linkTitle)) nextUrl = absUrl
            if (prevUrl.isEmpty() && PREV_RE.matches(linkTitle)) prevUrl = absUrl
            if (tocUrl.isEmpty() && TOC_RE.matches(linkTitle)) tocUrl = absUrl

            if (seenUrls.add(absUrl)) {
                val sub = if (h3 != null) {
                    unescapeHtml(stripHtml(inner.replace(Regex("""<h[1-5][^>]*>[\s\S]*?</h[1-5]>""", RegexOption.IGNORE_CASE), " ")))
                        .replace(Regex("\\s+"), " ").trim()
                } else ""
                val isChap = CHAPTER_LINK_RE.containsMatchIn(linkTitle) || CHAPTER_LINK_RE.containsMatchIn(absUrl)
                links.add(WebView.ExtractedLink(title = linkTitle, subtitle = sub, url = absUrl, isChapter = isChap))
            }
        }

        return ParseResult(
            title = mangaTitle,
            pages = pages,
            prev = prevUrl,
            next = nextUrl,
            toc = tocUrl,
            links = links,
        )
    }

    private fun extractAttr(tag: String, name: String): String? {
        val m = Regex("""(?:^|\s)${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>"']+))""", RegexOption.IGNORE_CASE).find(tag)
            ?: return null
        val raw = m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }
        return unescapeHtml(raw)
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ")

    fun unescapeHtml(s: String): String =
        s.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("&#(\\d+);")) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
            }

    private fun resolveUrl(baseUrl: String, rel: String?): String? {
        if (rel.isNullOrBlank()) return null
        val t = rel.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        return runCatching {
            java.net.URI(baseUrl).resolve(t).toString()
        }.getOrElse {
            if (t.startsWith("//")) "https:$t" else null
        }
    }

    private fun encodeUriPath(path: String): String {
        val sb = StringBuilder()
        for (ch in path) {
            if (ch.code < 128 && (ch.isLetterOrDigit() || ch in "/-_.~!*'();:@&=+\$,?#[]")) {
                sb.append(ch)
            } else {
                for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                    sb.append('%')
                    sb.append("%02X".format(b.toInt() and 0xFF))
                }
            }
        }
        return sb.toString()
    }

    private fun encodeBase(c: Int, a: Int): String {
        val prefix = if (c < a) "" else encodeBase(c / a, a)
        val rem = c % a
        val suffix = if (rem > 35) (rem + 29).toChar().toString() else rem.toString(36)
        return prefix + suffix
    }

    private fun unpackDeanEdwards(p: String, a: Int, c: Int, k: List<String>): String {
        if (a <= 0 || c <= 0) return p
        val dict = HashMap<String, String>(c * 2)
        for (i in (c - 1) downTo 0) {
            val key = encodeBase(i, a)
            val value = k.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key
            dict[key] = value
        }
        return Regex("\\b\\w+\\b").replace(p) { m ->
            dict[m.value] ?: m.value
        }
    }

    private const val KEY_STR_BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
    private val baseReverseDic = IntArray(128) { -1 }.apply {
        for (i in KEY_STR_BASE64.indices) {
            this[KEY_STR_BASE64[i].code] = i
        }
    }

    fun decompressFromBase64(input: String?): String? {
        if (input.isNullOrEmpty()) return ""
        return runCatching {
            decompressLz(input.length, 32) { index ->
                val code = input[index].code
                if (code in 0..127) baseReverseDic[code].coerceAtLeast(0) else 0
            }
        }.getOrNull()
    }

    private fun decompressLz(length: Int, resetValue: Int, getNextValue: (Int) -> Int): String? {
        val dictionary = ArrayList<String>()
        var enlargeIn = 4
        var dictSize = 4
        var numBits = 3
        val result = StringBuilder()
        var dataVal = getNextValue(0)
        var dataPosition = resetValue
        var dataIndex = 1

        for (i in 0..2) dictionary.add(i.toString())

        fun readBits(n: Int): Int {
            var bits = 0
            val maxpower = 1 shl n
            var power = 1
            while (power != maxpower) {
                val resb = dataVal and dataPosition
                dataPosition = dataPosition shr 1
                if (dataPosition == 0) {
                    dataPosition = resetValue
                    dataVal = if (dataIndex < length) getNextValue(dataIndex++) else 0
                }
                if (resb > 0) bits = bits or power
                power = power shl 1
            }
            return bits
        }

        val firstChar = when (readBits(2)) {
            0 -> readBits(8).toChar().toString()
            1 -> readBits(16).toChar().toString()
            2 -> return ""
            else -> return null
        }
        dictionary.add(firstChar)
        var w = firstChar
        result.append(firstChar)

        while (true) {
            if (dataIndex > length + 1) return result.toString()
            var c = readBits(numBits)
            when (c) {
                0 -> {
                    dictionary.add(readBits(8).toChar().toString())
                    c = dictSize++
                    enlargeIn--
                }
                1 -> {
                    dictionary.add(readBits(16).toChar().toString())
                    c = dictSize++
                    enlargeIn--
                }
                2 -> return result.toString()
            }
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits++
            }
            val entry = when {
                c < dictionary.size -> dictionary[c]
                c == dictSize -> w + w[0]
                else -> return result.toString()
            }
            result.append(entry)
            dictionary.add(w + entry[0])
            dictSize++
            enlargeIn--
            w = entry
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits++
            }
        }
    }
}
