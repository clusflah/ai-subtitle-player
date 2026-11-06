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

/** 书签保存的是整本书中的块位置和滚动偏移，重新打开后仍可准确跳回。 */
data class BookBookmark(
    val id: Long,
    val key: String,
    val item: Int,
    val offset: Int,
    val chapter: String,
    val preview: String,
    val time: Long,
)

/** 阅读设置：字号（sp）、行距倍数、主题（0 白底 1 护眼 2 夜间）、朗读速度 */
data class BookPrefs(
    val fontSize: Int = 19, val lineSpacing: Float = 1.7f, val theme: Int = 0, val ttsSpeed: Float = 1f,
    /** 目录、版权、封面等导航内容不进入朗读队列。 */
    val skipFrontMatter: Boolean = true,
    /** 听书默认统一使用一个旁白语音，不做角色推断。 */
    val unifiedNarrator: Boolean = true,
    /** 本书阅读器的旁白语音；空则沿用应用的默认语音。 */
    val ttsVoice: String? = null,
)

/** 电子书的阅读进度、最近读过、阅读设置（存在 SharedPreferences） */
object BookStore {
    private const val MAX_RECENT = 50
    private var prefs: SharedPreferences? = null

    private val _recent = MutableStateFlow<List<BookRecent>>(emptyList())
    val recent: StateFlow<List<BookRecent>> = _recent.asStateFlow()

    private val _prefs = MutableStateFlow(BookPrefs())
    val settings: StateFlow<BookPrefs> = _prefs.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<BookBookmark>>(emptyList())
    val bookmarks: StateFlow<List<BookBookmark>> = _bookmarks.asStateFlow()

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
            p.getBoolean("skipFrontMatter", true), p.getBoolean("unifiedNarrator", true), p.getString("ttsVoice", null),
        )
        _bookmarks.value = runCatching {
            val a = JSONArray(p.getString("bookmarks", "[]"))
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                BookBookmark(
                    it.optLong("id"), it.getString("key"), it.optInt("item"), it.optInt("offset"),
                    it.optString("chapter"), it.optString("preview"), it.optLong("time"),
                )
            }
        }.getOrDefault(emptyList())
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

    fun bookmarks(key: String): List<BookBookmark> = _bookmarks.value.filter { it.key == key }

    /** 同一段落只保留一个书签；再次点击即可取消。 */
    fun toggleBookmark(key: String, item: Int, offset: Int, chapter: String, preview: String): Boolean {
        val old = _bookmarks.value.firstOrNull { it.key == key && it.item == item }
        _bookmarks.value = if (old == null) {
            listOf(BookBookmark(System.currentTimeMillis(), key, item, offset, chapter, preview, System.currentTimeMillis())) + _bookmarks.value
        } else {
            _bookmarks.value.filterNot { it.id == old.id }
        }
        persistBookmarks()
        return old == null
    }

    fun removeBookmark(id: Long) {
        _bookmarks.value = _bookmarks.value.filterNot { it.id == id }
        persistBookmarks()
    }

    fun update(change: (BookPrefs) -> BookPrefs) {
        val s = change(_prefs.value)
        _prefs.value = s
        prefs?.edit()?.putInt("fontSize", s.fontSize)?.putFloat("lineSpacing", s.lineSpacing)?.putInt("theme", s.theme)
            ?.putFloat("ttsSpeed", s.ttsSpeed)?.putBoolean("skipFrontMatter", s.skipFrontMatter)
            ?.putBoolean("unifiedNarrator", s.unifiedNarrator)?.putString("ttsVoice", s.ttsVoice)?.apply()
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

    private fun persistBookmarks() {
        val a = JSONArray()
        for (b in _bookmarks.value) a.put(
            JSONObject().put("id", b.id).put("key", b.key).put("item", b.item).put("offset", b.offset)
                .put("chapter", b.chapter).put("preview", b.preview).put("time", b.time),
        )
        prefs?.edit()?.putString("bookmarks", a.toString())?.apply()
    }
}
