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

    private val webSettings = WebSettings()
    var webViewClient: WebViewClient = WebViewClient()
    var webChromeClient: WebChromeClient = WebChromeClient()
    var url: String? by androidx.compose.runtime.mutableStateOf(null)
        private set
    var title: String? by androidx.compose.runtime.mutableStateOf(null)
        private set
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
        if (targetUrl.startsWith("javascript:")) {
            evaluateJavascript(targetUrl.removePrefix("javascript:"), null)
            return
        }
        url = targetUrl
        if (history.lastOrNull() != targetUrl) history.add(targetUrl)
        post {
            webViewClient.onPageStarted(this, targetUrl, null)
            webChromeClient.onProgressChanged(this, 20)
        }
        webExecutor.submit {
            runCatching {
                val conn = URL(targetUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", webSettings.userAgentString)
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                lastHtmlPreview = html
                val pageTitle = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.trim() ?: targetUrl
                title = pageTitle

                // 自动嗅探页面里的直链视频 (m3u8 / mp4)
                val mediaUrls = Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
                    .findAll(html).map { it.value.replace("\\/", "/") }.distinct().toMutableList()
                if (mediaUrls.isEmpty()) {
                    com.aisub.player.desktop.DesktopBinaryFinder.findYtDlp()?.let { ytdlp ->
                        runCatching {
                            val proc = ProcessBuilder(ytdlp, "-g", "--no-playlist", "--no-warnings", targetUrl).start()
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
                        override val requestHeaders: Map<String, String> = mapOf("Referer" to targetUrl)
                    }
                    webViewClient.shouldInterceptRequest(this, req)
                }

                post {
                    webChromeClient.onReceivedTitle(this, pageTitle)
                    webChromeClient.onProgressChanged(this, 100)
                    webViewClient.doUpdateVisitedHistory(this, targetUrl, false)
                    webViewClient.onPageFinished(this, targetUrl)
                }
            }.onFailure {
                post {
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
