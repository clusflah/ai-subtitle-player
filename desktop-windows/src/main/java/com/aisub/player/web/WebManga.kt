package com.aisub.player.web

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aisub.player.manga.MangaBook
import com.aisub.player.manga.MangaPage
import com.aisub.player.manga.MangaStore
import org.json.JSONObject

/** 网页上识别出的一话漫画 */
data class WebManga(
    val pages: List<MangaPage>,
    val title: String,
    val pageUrl: String,
    val prev: String?,
    val next: String?,
    val toc: String?,
) {
    fun toBook(): MangaBook {
        val host = Uri.parse(pageUrl).host?.removePrefix("www.")?.removePrefix("m.").orEmpty()
        return MangaBook(
            key = pageUrl.substringBefore('#'),
            title = title.ifBlank { host },
            pages = pages,
            site = host.ifEmpty { "web" },
            prev = prev, next = next, toc = toc,
            webUrl = pageUrl,
        )
    }
}

/**
 * 收集一个标签页里各个框架（web_manga.js）的报告：漫画图片取图最多的框架（有的网站放在另一个域名的 iframe 里），
 * 上一话 / 下一话 / 标题优先用顶层网页的。少于 3 张图不算漫画。
 */
class WebMangaCollector {
    private class Report(val frame: String, val top: Boolean, val title: String, val pages: List<String>, val next: String?, val prev: String?, val toc: String?)

    private val main = Handler(Looper.getMainLooper())
    private val frames = LinkedHashMap<String, Report>()

    /** 识别出的漫画；没有是 null（主线程读写） */
    var manga by mutableStateOf<WebManga?>(null)
        private set

    /** JS 线程调用 */
    fun report(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val arr = o.optJSONArray("pages")
        val pages = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it)?.takeIf { s -> s.isNotEmpty() } }
        val r = Report(
            o.optString("frame"), o.optBoolean("top"), o.optString("title"), pages,
            o.optString("next").ifEmpty { null }, o.optString("prev").ifEmpty { null }, o.optString("toc").ifEmpty { null },
        )
        main.post {
            if (r.frame.isEmpty()) return@post
            frames[r.frame] = r
            // 顶层网页换了（旧网页的框架还没清掉）：只留同一个顶层网页的
            if (r.top) frames.entries.removeAll { it.value.top && it.key != r.frame }
            recompute()
        }
    }

    fun reset() {
        main.post {
            frames.clear()
            manga = null
        }
    }

    private fun recompute() {
        val best = frames.values.maxByOrNull { it.pages.size }
        val top = frames.values.firstOrNull { it.top }
        if (best == null || best.pages.size < 3) { manga = null; return }
        val pageUrl = top?.frame ?: best.frame
        manga = WebManga(
            // 网页里解密出来的图（web_manga.js 报的是「voxblob:序号」）：要的时候请网页交出来
            pages = best.pages.map { p ->
                val blob = if (p.startsWith(WebPageImages.SCHEME)) p.removePrefix(WebPageImages.SCHEME).toIntOrNull() else null
                MangaPage(if (blob != null) WebPageImages.url(best.frame, blob) else p, referer = best.frame)
            },
            title = top?.title?.let(::cleanTitle).orEmpty(),
            pageUrl = pageUrl,
            prev = top?.prev ?: best.prev,
            next = top?.next ?: best.next,
            toc = top?.toc,
        )
    }

    /** 网页标题常带「 - 网站名 - 在线漫画」之类的尾巴，去掉第二段以后的 */
    private fun cleanTitle(t: String): String {
        val parts = t.split(" - ", " | ", "_", "－", "—").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) return t.trim()
        // 第一段常是「漫画名 第 N 话」；只有「第 N 话」时把下一段（漫画名）接上；第二段是「第 N 话」时也接上
        return when {
            MangaStore.seriesName(parts[0]).isEmpty() -> parts[1] + " " + parts[0]
            MangaStore.seriesName(parts[1]).isEmpty() -> parts[0] + " " + parts[1]
            else -> parts[0]
        }
    }
}

data class WebLaunchRequest(
    val url: String,
    val mangaMode: Boolean,
    val allowOpenApp: Boolean,
)

data class WebLaunchTabPlan(
    val urls: List<String?>,
    val activeIndex: Int,
)

/** 别的页面（漫画首页/我的页的「继续阅读」、发现页跳转搜索或发现页跳转播放）要网页打开一个地址 */
object WebLaunch {
    @Volatile
    var pending: String? = null

    /** true 时识别出漫画页会自动进入阅读模式（漫画「继续阅读」使用）；普通搜索网页设为 false */
    @Volatile
    var autoReadManga: Boolean = true

    /** true 时来自发现页跳转播放：加入白名单，放行原平台「打开 App」按钮、App 唤起与跨站播放跳转 */
    @Volatile
    var allowOpenApp: Boolean = false

    @Synchronized
    fun open(url: String?, mangaMode: Boolean = false, allowOpenApp: Boolean = false) {
        autoReadManga = mangaMode
        this.allowOpenApp = allowOpenApp
        pending = url
    }

    @Synchronized
    fun consume(): WebLaunchRequest? {
        val raw = pending?.trim()?.takeIf { it.isNotEmpty() }
        val manga = autoReadManga
        val allowApp = allowOpenApp
        pending = null
        autoReadManga = true
        allowOpenApp = false
        return raw?.let { WebLaunchRequest(it, manga, allowApp) }
    }

    /** 归一化比对两个网页地址是否指向同一页（忽略锚点 #、末尾斜杠、http/https 与前导 www.） */
    fun normalizeTargetUrl(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val noHash = s.substringBefore('#').trimEnd('/')
        val schemeSep = noHash.indexOf("://")
        if (schemeSep <= 0) return noHash
        val scheme = noHash.substring(0, schemeSep).lowercase()
        val rest = noHash.substring(schemeSep + 3)
        val slash = rest.indexOf('/')
        val authority = (if (slash >= 0) rest.substring(0, slash) else rest).lowercase().removePrefix("www.")
        val pathAndQuery = if (slash >= 0) rest.substring(slash) else ""
        val normScheme = if (scheme == "http") "https" else scheme
        return "$normScheme://$authority$pathAndQuery"
    }

    fun sameTargetUrl(a: String?, b: String?): Boolean {
        val na = normalizeTargetUrl(a) ?: return false
        val nb = normalizeTargetUrl(b) ?: return false
        return na == nb
    }

    private fun extractHost(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val schemeSep = s.indexOf("://")
        if (schemeSep <= 0) return null
        val rest = s.substring(schemeSep + 3)
        val end = rest.indexOfAny(charArrayOf('/', '?', '#'))
        val authority = (if (end >= 0) rest.substring(0, end) else rest).substringAfterLast('@')
        val host = authority.substringBefore(':').trim().lowercase()
        return host.takeIf { it.isNotEmpty() }
    }

    fun sameSiteHost(a: String?, b: String?): Boolean {
        val ha = extractHost(a) ?: return false
        val hb = extractHost(b) ?: return false
        return AdRules.siteOf(ha) == AdRules.siteOf(hb)
    }

    /**
     * 解析外部唤起网页时的标签页复用方案：
     * 1. 若已有标签页打开了同一目标页，直接切换到该标签并清理历史遗留的同网址重复标签；
     * 2. 若存在空白标签页（null），直接在空白标签页打开；
     * 3. 若为漫画「继续阅读」（mangaMode = true）且已有同漫画站点的标签页，直接复用该站点标签页跳转章节，避免每次点继续阅读新增标签；
     * 4. 其余情况才新建标签页，不覆盖其他无关网站。
     */
    fun resolveTabs(
        existingUrls: List<String?>,
        currentActiveIndex: Int,
        targetUrl: String,
        mangaMode: Boolean,
    ): WebLaunchTabPlan {
        val base = if (existingUrls.isEmpty()) mutableListOf<String?>(null) else existingUrls.toMutableList()
        val safeActive = currentActiveIndex.coerceIn(0, base.lastIndex)

        val exactMatches = base.indices.filter { sameTargetUrl(base[it], targetUrl) }
        if (exactMatches.isNotEmpty()) {
            var keepIdx = if (safeActive in exactMatches) safeActive else exactMatches.first()
            base[keepIdx] = targetUrl
            for (idx in exactMatches.reversed()) {
                if (idx != keepIdx) {
                    base.removeAt(idx)
                    if (idx < keepIdx) keepIdx--
                }
            }
            return WebLaunchTabPlan(base, keepIdx)
        }

        val blankIdx = if (base[safeActive].isNullOrBlank()) safeActive else base.indexOfFirst { it.isNullOrBlank() }
        if (blankIdx >= 0) {
            base[blankIdx] = targetUrl
            return WebLaunchTabPlan(base, blankIdx)
        }

        if (mangaMode) {
            val siteIdx = if (sameSiteHost(base[safeActive], targetUrl)) {
                safeActive
            } else {
                base.indexOfLast { sameSiteHost(it, targetUrl) }
            }
            if (siteIdx >= 0) {
                base[siteIdx] = targetUrl
                return WebLaunchTabPlan(base, siteIdx)
            }
        }

        base.add(targetUrl)
        return WebLaunchTabPlan(base, base.lastIndex)
    }
}
