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
        // 第一段常是「漫画名 第 N 话」；只有「第 N 话」时把下一段（漫画名）接上
        return if (MangaStore.seriesName(parts[0]).isEmpty()) parts[1] + " " + parts[0] else parts[0]
    }
}

/** 别的页面（漫画首页的「继续阅读」）要网页打开一个地址，打开后识别出漫画就直接进阅读模式 */
object WebLaunch {
    @Volatile
    var pending: String? = null
}
