package com.aisub.player.book

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 最近读过的一本书：location 是打开它的地址，item 是读到第几块（段落 / 插图），total 是一共几块 */
data class BookRecent(
    val key: String,
    val title: String,
    val location: String,
    val item: Int,
    val offset: Int,
    val total: Int,
    val chapter: String,
    val time: Long,
    val favorite: Boolean = false,
    /** EPUB/MOBI 里第一张可用插图，用作继续阅读的封面。 */
    val coverEntry: String? = null,
)

/** 阅读设置：字号（sp）、行距倍数、主题（0 白底 1 护眼 2 夜间）、朗读速度 */
data class BookPrefs(val fontSize: Int = 19, val lineSpacing: Float = 1.7f, val theme: Int = 0, val ttsSpeed: Float = 1f)

/** 电子书的阅读进度、最近读过、阅读设置（存在 SharedPreferences） */
object BookStore {
    private const val MAX_RECENT = 50
    private var prefs: SharedPreferences? = null

    private val _recent = MutableStateFlow<List<BookRecent>>(emptyList())
    val recent: StateFlow<List<BookRecent>> = _recent.asStateFlow()

    private val _prefs = MutableStateFlow(BookPrefs())
    val settings: StateFlow<BookPrefs> = _prefs.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("books", Context.MODE_PRIVATE)
        prefs = p
        _recent.value = runCatching {
            val a = JSONArray(p.getString("recent", "[]"))
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                BookRecent(
                    it.getString("key"), it.optString("title"), it.optString("location"), it.optInt("item"), it.optInt("offset"),
                    it.optInt("total"), it.optString("chapter"), it.optLong("time"), it.optBoolean("favorite"), it.optString("coverEntry").ifBlank { null },
                )
            }
        }.getOrDefault(emptyList())
        _prefs.value = BookPrefs(
            p.getInt("fontSize", 19), p.getFloat("lineSpacing", 1.7f), p.getInt("theme", 0), p.getFloat("ttsSpeed", 1f),
        )
    }

    fun progress(key: String): BookRecent? = _recent.value.firstOrNull { it.key == key }

    fun save(r: BookRecent) {
        val favorite = _recent.value.firstOrNull { it.key == r.key }?.favorite ?: r.favorite
        val list = listOf(r.copy(favorite = favorite)) + _recent.value.filter { it.key != r.key }
        _recent.value = list.take(MAX_RECENT)
        persist()
    }

    fun toggleFavorite(key: String): Boolean {
        val now = _recent.value.firstOrNull { it.key == key }?.favorite != true
        _recent.value = _recent.value.map { if (it.key == key) it.copy(favorite = now) else it }
        persist()
        return now
    }

    fun remove(key: String) {
        _recent.value = _recent.value.filter { it.key != key }
        persist()
    }

    fun update(change: (BookPrefs) -> BookPrefs) {
        val s = change(_prefs.value)
        _prefs.value = s
        prefs?.edit()?.putInt("fontSize", s.fontSize)?.putFloat("lineSpacing", s.lineSpacing)?.putInt("theme", s.theme)
            ?.putFloat("ttsSpeed", s.ttsSpeed)?.apply()
    }

    private fun persist() {
        val a = JSONArray()
        for (r in _recent.value) a.put(
            JSONObject().put("key", r.key).put("title", r.title).put("location", r.location).put("item", r.item)
                .put("offset", r.offset).put("total", r.total).put("chapter", r.chapter).put("time", r.time).put("favorite", r.favorite)
                .put("coverEntry", r.coverEntry),
        )
        prefs?.edit()?.putString("recent", a.toString())?.apply()
    }
}
