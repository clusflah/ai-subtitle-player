package com.aisub.player.manga

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 漫画的显示方式 */
enum class MangaMode(val label: String, val hint: String) {
    FIT("适应屏幕", "整页完整显示，左右翻页"),
    WIDTH("适应宽度", "图片撑满宽度，上下滚动看完一页再翻"),
    DOUBLE("双页", "两页并排，适合横屏、跨页"),
    STRIP("长条滚动", "一直往下滚，适合韩漫、条漫"),
}

/** 阅读设置：每个网站分别记住（本地漫画共用一套） */
data class MangaSettings(
    /** null：还没选过，打开时按图片自动判断（很长的图用长条，其余适应屏幕） */
    val mode: MangaMode? = null,
    /** 从右往左翻（日漫） */
    val rtl: Boolean = false,
    /** 双页模式下第一页（封面）单独显示 */
    val coverSingle: Boolean = true,
    /** 旋转角度：0、90、180、270 */
    val rotation: Int = 0,
)

/** 最近读过的漫画（网页的一话、本地的一个压缩包 / 文件夹） */
data class MangaRecent(
    val key: String,
    val title: String,
    /** 网页漫画：这一话的网页；本地：压缩包地址 */
    val location: String,
    val page: Int,
    val total: Int,
    val time: Long,
    val web: Boolean,
)

/** 阅读进度、阅读设置、最近阅读，存在 SharedPreferences 里 */
object MangaStore {
    private const val MAX_RECENT = 40
    private const val MAX_PROGRESS = 500

    private var prefs: android.content.SharedPreferences? = null
    private val _recent = MutableStateFlow<List<MangaRecent>>(emptyList())
    val recent: StateFlow<List<MangaRecent>> = _recent

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("manga", Context.MODE_PRIVATE)
        _recent.value = loadRecent()
    }

    fun settings(site: String): MangaSettings {
        val o = prefs?.getString("settings:$site", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return MangaSettings()
        return MangaSettings(
            mode = o.optString("mode").takeIf { it.isNotEmpty() }?.let { m -> MangaMode.entries.firstOrNull { it.name == m } },
            rtl = o.optBoolean("rtl"),
            coverSingle = o.optBoolean("coverSingle", true),
            rotation = o.optInt("rotation"),
        )
    }

    fun saveSettings(site: String, s: MangaSettings) {
        val o = JSONObject().put("mode", s.mode?.name ?: "").put("rtl", s.rtl).put("coverSingle", s.coverSingle).put("rotation", s.rotation)
        prefs?.edit()?.putString("settings:$site", o.toString())?.apply()
    }

    /** 上次读到第几页（从 0 开始） */
    fun progress(key: String): Int = progressMap()[key] ?: 0

    fun saveProgress(book: MangaBook, page: Int) {
        val map = LinkedHashMap(progressMap())
        map.remove(book.key)
        map[book.key] = page
        while (map.size > MAX_PROGRESS) map.remove(map.keys.first())
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs?.edit()?.putString("progress", o.toString())?.apply()
        cachedProgress = map
        if (!book.recent) return

        val location = book.webUrl ?: book.key
        val item = MangaRecent(book.key, book.title, location, page, book.pages.size, System.currentTimeMillis(), book.webUrl != null)
        // 网页漫画：同一部漫画（同一网站、标题前半段一样）的不同话只留最新的一条
        val list = listOf(item) + _recent.value.filter { it.key != book.key && !(it.web && item.web && sameSeries(it, item)) }
        _recent.value = list.take(MAX_RECENT)
        saveRecent()
    }

    fun removeRecent(key: String) {
        _recent.value = _recent.value.filter { it.key != key }
        saveRecent()
    }

    private var cachedProgress: Map<String, Int>? = null

    private fun progressMap(): Map<String, Int> {
        cachedProgress?.let { return it }
        val o = prefs?.getString("progress", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val map = LinkedHashMap<String, Int>()
        o?.keys()?.forEach { map[it] = o.optInt(it) }
        cachedProgress = map
        return map
    }

    /** 同一部漫画：同一个网站，标题去掉「第 N 话」之后一样 */
    private fun sameSeries(a: MangaRecent, b: MangaRecent): Boolean {
        val ha = runCatching { android.net.Uri.parse(a.location).host }.getOrNull()
        val hb = runCatching { android.net.Uri.parse(b.location).host }.getOrNull()
        if (ha == null || ha != hb) return false
        return seriesName(a.title).isNotEmpty() && seriesName(a.title) == seriesName(b.title)
    }

    private val CHAPTER = Regex("""(第\s*[0-9零一二三四五六七八九十百千]+\s*[话話章回卷集册冊]|chapter\s*\d+|ch\.?\s*\d+|vol\.?\s*\d+|#\d+).*$""", RegexOption.IGNORE_CASE)

    fun seriesName(title: String): String = title.replace(CHAPTER, "").trim(' ', '-', '_', '|', '·', '–', '—')

    private fun loadRecent(): List<MangaRecent> {
        val arr = prefs?.getString("recent", null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            MangaRecent(
                o.optString("key"), o.optString("title"), o.optString("location"),
                o.optInt("page"), o.optInt("total"), o.optLong("time"), o.optBoolean("web"),
            )
        }
    }

    private fun saveRecent() {
        val arr = JSONArray()
        _recent.value.forEach {
            arr.put(
                JSONObject().put("key", it.key).put("title", it.title).put("location", it.location)
                    .put("page", it.page).put("total", it.total).put("time", it.time).put("web", it.web),
            )
        }
        prefs?.edit()?.putString("recent", arr.toString())?.apply()
    }
}
