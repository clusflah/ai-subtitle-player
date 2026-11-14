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

/** 一个广告规则订阅：常用的（[AdBlock.PRESETS]）或者用户自己加的 */
data class AdList(val id: String, val name: String, val url: String, val note: String = "", val custom: Boolean = false)

/** 一个订阅的状态：显示在「广告过滤规则」里 */
data class AdListState(
    val list: AdList,
    val enabled: Boolean,
    /** 下载到手机里的规则条数，0 是还没下载 */
    val rules: Int = 0,
    val updatedAt: Long = 0,
    val error: String? = null,
)

/** 广告规则的状态：显示在网页菜单里 */
data class AdBlockInfo(
    val rules: Int = 0,
    /** 规则文件最近一次下载的时间（毫秒），0 是还没有 */
    val updatedAt: Long = 0,
    val updating: Boolean = false,
    val error: String? = null,
    val lists: List<AdListState> = emptyList(),
)

/**
 * 网页广告拦截：用公开的过滤规则订阅（在线下载、定期更新，不打包进安装包），
 * 常用的几个可以勾选，也可以加自己的订阅地址；规则怎么认见 [AdRules]。整个进程一份，所有标签页共用。
 */
object AdBlock {
    /** 常用订阅：AdGuard 的用精简版（给手机用的）；默认开前三个 */
    val PRESETS = listOf(
        AdList("base", "AdGuard 基础过滤", "https://filters.adtidy.org/android/filters/2_optimized.txt", "通用广告，各国网站都管用"),
        AdList("mobile", "AdGuard 移动端广告", "https://filters.adtidy.org/android/filters/11_optimized.txt", "手机网页上的广告"),
        AdList("chinese", "AdGuard 中文过滤", "https://filters.adtidy.org/android/filters/224_optimized.txt", "中文网站的广告"),
        AdList("easylistchina", "EasyList China", "https://easylist-downloads.adblockplus.org/easylistchina.txt", "中文网站的广告（Adblock Plus 维护，和上面的有重叠）"),
        AdList("cjx", "CJX's Annoyance List", "https://raw.githubusercontent.com/cjx82630/cjxlist/master/cjx-annoyance.txt", "中文网站上的自我推广、弹窗、悬浮条（配合 EasyList China 用）"),
        AdList("xinggsf", "乘风视频广告过滤", "https://raw.githubusercontent.com/xinggsf/Adblock-Plus-Rule/master/mv.txt", "国内视频网站的片头广告"),
        AdList("annoyances", "AdGuard 烦人元素", "https://filters.adtidy.org/android/filters/14_optimized.txt", "Cookie 提示、弹窗、「打开 App」横幅等"),
        AdList("tracking", "AdGuard 隐私保护", "https://filters.adtidy.org/android/filters/3_optimized.txt", "统计、追踪脚本（规则很多，几十万条，占内存、打开网页会慢一点）"),
        AdList("easylist", "EasyList", "https://easylist.to/easylist/easylist.txt", "国外网站的广告（和 AdGuard 基础有重叠）"),
    )
    private val DEFAULT_ON = setOf("base", "mobile", "chinese")
    private const val MAX_AGE_MS = 4L * 24 * 3600 * 1000

    /** 订阅设置：开了哪些、自己加的有哪些（存在 adblock/lists.json） */
    private var enabledIds: Set<String>? = null
    private var customLists: List<AdList> = emptyList()
    /** 每个订阅最近一次下载失败的原因 */
    private val errors = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun listsFile(context: Context) = File(dir(context), "lists.json")
    private fun fileOf(context: Context, list: AdList) = File(dir(context), "${list.id}.txt")

    @Synchronized
    private fun loadSettings(context: Context) {
        if (enabledIds != null) return
        val f = listsFile(context)
        val j = runCatching { org.json.JSONObject(f.readText()) }.getOrNull()
        if (j == null) {
            enabledIds = DEFAULT_ON
            return
        }
        enabledIds = j.optJSONArray("enabled")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: DEFAULT_ON
        customLists = j.optJSONArray("custom")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { AdList(it.getString("id"), it.getString("name"), it.getString("url"), custom = true) }
        }.orEmpty()
    }

    @Synchronized
    private fun saveSettings(context: Context) {
        val j = org.json.JSONObject()
            .put("enabled", org.json.JSONArray(enabledIds.orEmpty().toList()))
            .put("custom", org.json.JSONArray(customLists.map { org.json.JSONObject().put("id", it.id).put("name", it.name).put("url", it.url) }))
        listsFile(context).writeText(j.toString())
    }

    private fun allLists(): List<AdList> = PRESETS + customLists
    private fun enabledLists(context: Context): List<AdList> {
        loadSettings(context)
        return allLists().filter { it.id in enabledIds.orEmpty() }
    }

    /** 打开 / 关掉一个订阅；打开时还没下载就去下载 */
    fun setListEnabled(context: Context, scope: CoroutineScope, id: String, on: Boolean) {
        loadSettings(context)
        enabledIds = if (on) enabledIds.orEmpty() + id else enabledIds.orEmpty() - id
        saveSettings(context)
        // 关掉的不留着占地方（有的有十几 MB），再打开时重新下载
        if (!on) allLists().firstOrNull { it.id == id }?.let { fileOf(context, it).delete() }
        refresh(context, scope, onlyMissing = true)
    }

    /** 加一个订阅地址（默认打开，马上下载）；地址已经有了返回 false */
    fun addList(context: Context, scope: CoroutineScope, name: String, url: String): Boolean {
        loadSettings(context)
        if (allLists().any { it.url == url }) return false
        val list = AdList("c" + System.currentTimeMillis(), name.ifBlank { android.net.Uri.parse(url).host ?: url }, url, custom = true)
        customLists = customLists + list
        enabledIds = enabledIds.orEmpty() + list.id
        saveSettings(context)
        refresh(context, scope, onlyMissing = true)
        return true
    }

    fun removeList(context: Context, scope: CoroutineScope, id: String) {
        loadSettings(context)
        val list = customLists.firstOrNull { it.id == id } ?: return
        customLists = customLists - list
        enabledIds = enabledIds.orEmpty() - id
        saveSettings(context)
        errors.remove(id)
        fileOf(context, list).delete()
        refresh(context, scope, onlyMissing = true)
    }

    /** 改了订阅后：下载缺的，重新读规则 */
    private fun refresh(context: Context, scope: CoroutineScope, onlyMissing: Boolean) {
        val app = context.applicationContext
        showStates(app)
        scope.launch(Dispatchers.IO) {
            if (!update(app, onlyMissing)) load(app)
        }
    }

    @Volatile
    private var rules: AdRules? = null
    private val lock = Mutex()
    private val _info = MutableStateFlow(AdBlockInfo())
    val info: StateFlow<AdBlockInfo> = _info.asStateFlow()

    private val http by lazy {
        com.aisub.player.net.AppProxy.builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun dir(context: Context) = File(context.filesDir, "adblock").apply { mkdirs() }

    /** 打开拦截时调：先读手机里存的规则，没有或者旧了就联网更新 */
    fun ensure(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            reloadCustom(app)
            if (rules == null) load(app)
            val oldest = enabledLists(app).minOfOrNull { fileOf(app, it).let { f -> if (f.exists()) f.lastModified() else 0L } } ?: Long.MAX_VALUE
            if (System.currentTimeMillis() - oldest > MAX_AGE_MS) update(app)
        }
    }

    /** 下载打开着的订阅（[onlyMissing] 时只下还没下过的）；返回是否下载过 */
    suspend fun update(context: Context, onlyMissing: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        // 连着勾了几个订阅：排队，前一个下完再看还缺哪些
        lock.lock()
        try {
            val todo = enabledLists(context).filter { !onlyMissing || !fileOf(context, it).exists() }
            if (todo.isEmpty()) return@withContext false
            _info.update { it.copy(updating = true, error = null) }
            for (list in todo) {
                try {
                    http.newCall(Request.Builder().url(list.url).build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        val text = r.body!!.string()
                        // 被劫持成网页的、不是规则的不要
                        val head = text.trimStart().take(200).lowercase()
                        if (head.startsWith("<") || head.contains("<html")) error("不是规则文件")
                        if (text.lineSequence().none { it.isNotBlank() && !it.startsWith("!") && !it.startsWith("[") }) error("里面没有规则")
                        val tmp = File(dir(context), "${list.id}.tmp")
                        tmp.writeText(text)
                        tmp.renameTo(fileOf(context, list))
                    }
                    errors.remove(list.id)
                } catch (e: Exception) {
                    errors[list.id] = e.message ?: e.javaClass.simpleName
                }
            }
            loadLocked(context)
            _info.update { it.copy(updating = false) }
            true
        } finally {
            lock.unlock()
        }
    }

    private suspend fun load(context: Context) = lock.withLock { loadLocked(context) }

    /** 每个规则文件的条数（按文件修改时间缓存，不用每次都数） */
    private val counts = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int>>()

    private fun states(context: Context): List<AdListState> {
        loadSettings(context)
        val on = enabledIds.orEmpty()
        return allLists().map { list ->
            val f = fileOf(context, list)
            val time = if (f.exists()) f.lastModified() else 0L
            val rules = if (time == 0L) 0 else counts[list.id]?.takeIf { it.first == time }?.second
                ?: f.bufferedReader().useLines { s -> s.count { it.isNotBlank() && !it.startsWith("!") && !it.startsWith("[") } }
                    .also { counts[list.id] = time to it }
            AdListState(list, list.id in on, rules, time, errors[list.id])
        }
    }

    /** 勾选、增删订阅后马上显示出来（重新读规则要几秒） */
    private fun showStates(context: Context) {
        _info.update { it.copy(lists = states(context)) }
    }

    private fun loadLocked(context: Context) {
        val states = states(context)
        val files = enabledLists(context).map { fileOf(context, it) }.filter { it.exists() }
        val parsed = AdRules.parse(files.asSequence().flatMap { f -> f.bufferedReader().lineSequence() })
        rules = parsed
        val failed = states.filter { it.enabled && it.error != null }
        _info.update {
            it.copy(
                rules = parsed.size,
                updatedAt = files.maxOfOrNull { f -> f.lastModified() } ?: 0L,
                lists = states,
                error = failed.takeIf { f -> f.isNotEmpty() }?.let { f -> "更新失败：" + f.joinToString("、") { s -> s.list.name } },
            )
        }
    }

    /** 读一下订阅的状态（打开「广告过滤规则」时） */
    fun loadInfo(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) { load(app) }
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
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return
        if (swHooked) return
        swHooked = true
        hookServiceWorkersApi24()
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.N)
    private fun hookServiceWorkersApi24() {
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
        val text = customText(context)
        custom = AdRules.parse((BUILTIN + "\n" + text).lineSequence(), custom = true)
        floatHosts = text.lineSequence().map { it.trim() }.filter { it.endsWith("##$FLOAT") }
            .map { it.removeSuffix("##$FLOAT").lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    /** 自定义规则「网站##:vox-float」：这个网站上贴边的浮动广告（随机名字、拆成小块的）都藏起来 */
    const val FLOAT = ":vox-float"

    @Volatile
    private var floatHosts: Set<String> = emptySet()

    fun floating(host: String): Boolean {
        var h = host.lowercase().removePrefix("www.")
        while (true) {
            if (h in floatHosts) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
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
