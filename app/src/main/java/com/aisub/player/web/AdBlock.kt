package com.aisub.player.web

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** 广告规则的状态：显示在网页菜单里 */
data class AdBlockInfo(
    val rules: Int = 0,
    /** 规则文件最近一次下载的时间（毫秒），0 是还没有 */
    val updatedAt: Long = 0,
    val updating: Boolean = false,
    val error: String? = null,
)

/**
 * 网页广告拦截：用 AdGuard 的公开过滤规则（在线下载、定期更新，不打包进安装包），
 * 规则怎么认见 [AdRules]。整个进程一份，所有标签页共用。
 */
object AdBlock {
    /** AdGuard 基础、移动端广告、中文过滤规则（精简版，给手机用的） */
    private val LISTS = listOf(
        "base" to "https://filters.adtidy.org/android/filters/2_optimized.txt",
        "mobile" to "https://filters.adtidy.org/android/filters/11_optimized.txt",
        "chinese" to "https://filters.adtidy.org/android/filters/224_optimized.txt",
    )
    private const val MAX_AGE_MS = 4L * 24 * 3600 * 1000

    @Volatile
    private var rules: AdRules? = null
    private val lock = Mutex()
    private val _info = MutableStateFlow(AdBlockInfo())
    val info: StateFlow<AdBlockInfo> = _info.asStateFlow()

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun dir(context: Context) = File(context.filesDir, "adblock").apply { mkdirs() }

    /** 打开拦截时调：先读手机里存的规则，没有或者旧了就联网更新 */
    fun ensure(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            reloadCustom(app)
            if (rules == null) load(app)
            val files = LISTS.map { File(dir(app), "${it.first}.txt") }
            val oldest = files.minOf { if (it.exists()) it.lastModified() else 0L }
            if (System.currentTimeMillis() - oldest > MAX_AGE_MS) update(app)
        }
    }

    /** 重新下载规则；返回是否成功 */
    suspend fun update(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext false
        try {
            _info.update { it.copy(updating = true, error = null) }
            var failed: String? = null
            for ((name, url) in LISTS) {
                try {
                    http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        val text = r.body!!.string()
                        // 下到一半的、被劫持成网页的不要
                        if (!text.contains("! Title:")) error("内容不对")
                        val tmp = File(dir(context), "$name.tmp")
                        tmp.writeText(text)
                        tmp.renameTo(File(dir(context), "$name.txt"))
                    }
                } catch (e: Exception) {
                    failed = e.message ?: e.javaClass.simpleName
                }
            }
            loadLocked(context)
            _info.update { it.copy(updating = false, error = failed?.let { m -> "更新广告规则失败：$m" }) }
            failed == null
        } finally {
            lock.unlock()
        }
    }

    private suspend fun load(context: Context) = lock.withLock { loadLocked(context) }

    private fun loadLocked(context: Context) {
        val files = LISTS.map { File(dir(context), "${it.first}.txt") }.filter { it.exists() }
        if (files.isEmpty()) return
        val parsed = AdRules.parse(files.asSequence().flatMap { f -> f.bufferedReader().lineSequence() })
        rules = parsed
        _info.update { it.copy(rules = parsed.size, updatedAt = files.maxOf { f -> f.lastModified() }) }
    }

    /** 网页里打开了拦截广告（Service Worker 的请求不经过网页，靠这个判断） */
    @Volatile
    var enabled = false

    /** 拦下一个请求时调（网页菜单里的「本页已拦截」） */
    @Volatile
    var onBlocked: (() -> Unit)? = null

    private var swHooked = false

    /**
     * 有的网站装了 Service Worker，网页的请求都由它转发，不经过 WebView 的 shouldInterceptRequest：
     * 给 Service Worker 也装上同样的拦截。整个进程装一次。
     */
    fun hookServiceWorkers() {
        if (swHooked) return
        swHooked = true
        runCatching {
            android.webkit.ServiceWorkerController.getInstance().setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    if (!enabled) return null
                    return intercept(request, request.requestHeaders["Referer"])?.also { onBlocked?.invoke() }
                }
            })
        }
    }

    /** 要拦就返回一个代替的响应（交给 WebView 当作这个请求的结果），不拦返回 null */
    fun intercept(request: WebResourceRequest, pageUrl: String?): WebResourceResponse? {
        if (request.isForMainFrame) return null
        val url = request.url.toString()
        val type = typeOf(url, request.requestHeaders["Accept"])
        // 自己写的规则优先：里面的例外能放行下载的规则误拦的
        val decision = when (val own = custom.decide(url, pageUrl, type)) {
            null -> rules?.decide(url, pageUrl, type)
            else -> own
        }
        val block = decision as? AdDecision.Block ?: return null
        if (com.aisub.player.BuildConfig.DEBUG) android.util.Log.d("VoxAd", "拦截 ${block.response} $url（网页 $pageUrl）")
        return response(block.response)
    }

    private fun response(r: AdResponse): WebResourceResponse {
        val body = when (r) {
            AdResponse.EMPTY -> return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            AdResponse.NODATA -> """{"code":200,"msg":"操作成功","data":null}"""
            AdResponse.NOLIST -> """{"code":200,"msg":"操作成功","data":[]}"""
        }
        return WebResourceResponse("application/json", "utf-8", ByteArrayInputStream(body.toByteArray()))
    }

    // ---- 自己的规则：内置的（影视网站模板自己的广告接口等）+ 用户写的、点选屏蔽加的 ----

    /**
     * 内置规则，写法同自定义规则。影视网站模板自己的广告接口和正片同一个域名、网站还常换域名，
     * 下载的规则里没有，按接口路径认
     */
    private val BUILTIN = """
        ! 新视讯一类影视网站：暂停时的弹窗（getPauseCc）、片头视频广告（getVideoCc）、各处横幅
        /api/mw-movie/anonymous/cc/getPauseCc${'$'}nodata
        /api/mw-movie/anonymous/cc/${'$'}nolist
    """.trimIndent()

    @Volatile
    private var custom: AdRules = AdRules.parse(BUILTIN.lineSequence(), custom = true)

    private fun customFile(context: Context) = File(dir(context), "custom.txt")

    /** 用户写的规则（原文） */
    fun customText(context: Context): String = customFile(context).takeIf { it.exists() }?.readText().orEmpty()

    fun saveCustom(context: Context, text: String) {
        customFile(context).writeText(text.trimEnd() + if (text.isBlank()) "" else "\n")
        reloadCustom(context)
    }

    /** 点选屏蔽：加一条规则（已经有了就不重复加） */
    fun addCustom(context: Context, rule: String) {
        val text = customText(context)
        if (text.lineSequence().any { it.trim() == rule }) return
        saveCustom(context, text.trimEnd().let { if (it.isEmpty()) rule else "$it\n$rule" })
    }

    fun reloadCustom(context: Context) {
        custom = AdRules.parse((BUILTIN + "\n" + customText(context)).lineSequence(), custom = true)
    }

    /** 这个网站要藏起来的广告元素（CSS）：自己的规则 + 下载的规则 */
    fun css(host: String): String = custom.css(host) + rules?.css(host).orEmpty()

    /** 从地址后缀和 Accept 头猜请求类型 */
    private fun typeOf(url: String, accept: String?): AdType? {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        return when {
            ext == "js" || ext == "mjs" -> AdType.SCRIPT
            ext == "css" -> AdType.STYLESHEET
            ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif") -> AdType.IMAGE
            ext in setOf("mp4", "m3u8", "ts", "m4s", "webm", "mp3", "aac", "flv", "mpd") -> AdType.MEDIA
            ext in setOf("woff", "woff2", "ttf", "otf") -> AdType.FONT
            accept == null -> null
            accept.startsWith("text/css") -> AdType.STYLESHEET
            accept.startsWith("image/") -> AdType.IMAGE
            accept.startsWith("text/html") -> AdType.SUBDOCUMENT
            else -> null
        }
    }
}
