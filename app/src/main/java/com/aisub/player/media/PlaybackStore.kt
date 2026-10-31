package com.aisub.player.media

import android.content.Context
import com.aisub.player.meta.NameParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** @param short 用在"跳过…"、"已跳过…"里的说法 */
enum class SkipKind(val label: String, val short: String) { INTRO("片头", "片头"), OUTRO("片尾", "片尾"), CUSTOM("跳过区间", "这段") }

/** 区间从哪来：视频章节、两集比对、用户标记 */
enum class SkipSource(val label: String) { CHAPTER("章节"), AUTO("自动识别"), USER("手动标记") }

/**
 * 一个要跳过的区间。
 * @param fromEnd true 时 start/end 是离片尾的距离（片尾在不同集里时长不同，按结尾对齐）
 * @param series true 表示整部剧通用，false 只对这一集
 */
data class SkipRange(
    val startMs: Long,
    val endMs: Long,
    val kind: SkipKind,
    val source: SkipSource,
    val fromEnd: Boolean = false,
    val series: Boolean = false,
) {
    /** 换算成这一集里的绝对时间 */
    fun resolve(durationMs: Long): SkipRange? {
        if (!fromEnd) return this
        if (durationMs <= 0) return null
        return copy(startMs = (durationMs - startMs).coerceAtLeast(0), endMs = (durationMs - endMs).coerceAtLeast(0), fromEnd = false)
    }

    fun toJson(): JSONObject = JSONObject().put("start", startMs).put("end", endMs).put("kind", kind.name)
        .put("source", source.name).put("fromEnd", fromEnd)

    companion object {
        fun fromJson(o: JSONObject, series: Boolean) = SkipRange(
            o.getLong("start"), o.getLong("end"),
            runCatching { SkipKind.valueOf(o.getString("kind")) }.getOrDefault(SkipKind.CUSTOM),
            runCatching { SkipSource.valueOf(o.getString("source")) }.getOrDefault(SkipSource.USER),
            o.optBoolean("fromEnd"), series,
        )
    }
}

/** 记住的轨道：语言 + 名称优先匹配，对不上再用序号。 */
data class TrackChoice(val language: String?, val label: String?, val index: Int) {
    fun toJson(): JSONObject = JSONObject().put("lang", language ?: "").put("label", label ?: "").put("index", index)

    companion object {
        fun fromJson(o: JSONObject?) = o?.let {
            TrackChoice(it.optString("lang").ifEmpty { null }, it.optString("label").ifEmpty { null }, it.optInt("index"))
        }
    }
}

/** 某个视频上次选的音轨、视频自带字幕（textOff 为选了"不显示"）。 */
data class TrackMemory(val audio: TrackChoice? = null, val text: TrackChoice? = null, val textOff: Boolean = false)

/** 书签 / 关键帧：位置、备注和一张缩略图。 */
data class Bookmark(val id: String, val positionMs: Long, val note: String, val image: String?, val createdAt: Long)

/**
 * 按视频或整部剧记住的播放信息：跳过区间、选过的轨道、书签。
 * 整部剧的 key 由文件名解析出的剧名和季决定（解析不出集数的文件只按单个文件记）。
 */
class PlaybackStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private val imageDir get() = File(context.filesDir, "bookmarks").apply { mkdirs() }

    // ---- 跳过区间 ----

    fun skipRanges(entry: MediaEntry): List<SkipRange> {
        val series = seriesKey(entry)?.let { key -> read("skip.series.$key").map { SkipRange.fromJson(it, series = true) } }.orEmpty()
        val file = read("skip.file.${entry.uri}").map { SkipRange.fromJson(it, series = false) }
        // 同类的片头片尾，这一集自己的记录优先
        return file + series.filter { s -> s.kind == SkipKind.CUSTOM || file.none { it.kind == s.kind } }
    }

    /** 保存一个区间。片头片尾每类只留一个，跳过区间可以有多个。 */
    fun addSkipRange(entry: MediaEntry, range: SkipRange) {
        val key = keyFor(entry, range.series)
        val list = read(key).map { SkipRange.fromJson(it, range.series) }
            .filter { range.kind == SkipKind.CUSTOM || it.kind != range.kind }
        write(key, (list + range).map { it.toJson() })
    }

    fun removeSkipRange(entry: MediaEntry, range: SkipRange) {
        val key = keyFor(entry, range.series)
        write(key, read(key).map { SkipRange.fromJson(it, range.series) }.filter { it != range }.map { it.toJson() })
    }

    /** 自动比对过（不论有没有找到），之后打开不再自动比对 */
    fun detectTried(uri: String) = prefs.getBoolean("skip.tried.$uri", false)

    fun markDetectTried(uri: String) = prefs.edit().putBoolean("skip.tried.$uri", true).apply()

    /** 能不能按整部剧记（文件名里解析得出集数） */
    fun hasSeries(entry: MediaEntry) = seriesKey(entry) != null

    private fun keyFor(entry: MediaEntry, series: Boolean) =
        if (series) seriesKey(entry)?.let { "skip.series.$it" } ?: "skip.file.${entry.uri}" else "skip.file.${entry.uri}"

    // ---- 直播频道的字幕延迟：每个台的直播流延迟不一样，调过一次就记住 ----

    fun liveSubtitleDelay(uri: String): Long = prefs.getLong("delay.live.$uri", 0)

    fun saveLiveSubtitleDelay(uri: String, ms: Long) {
        prefs.edit().apply { if (ms == 0L) remove("delay.live.$uri") else putLong("delay.live.$uri", ms) }.apply()
    }

    // ---- 轨道 ----

    fun trackMemory(uri: String): TrackMemory? {
        val s = prefs.getString("tracks.$uri", null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            TrackMemory(TrackChoice.fromJson(o.optJSONObject("audio")), TrackChoice.fromJson(o.optJSONObject("text")), o.optBoolean("textOff"))
        }.getOrNull()
    }

    fun saveTrackMemory(uri: String, m: TrackMemory) {
        val o = JSONObject().put("textOff", m.textOff)
        m.audio?.let { o.put("audio", it.toJson()) }
        m.text?.let { o.put("text", it.toJson()) }
        prefs.edit().putString("tracks.$uri", o.toString()).apply()
    }

    // ---- 书签 ----

    fun bookmarks(uri: String): List<Bookmark> = read("bookmarks.$uri").map {
        Bookmark(it.getString("id"), it.getLong("position"), it.optString("note"), it.optString("image").ifEmpty { null }, it.optLong("createdAt"))
    }.sortedBy { it.positionMs }

    fun addBookmark(uri: String, positionMs: Long, note: String, image: android.graphics.Bitmap?): Bookmark {
        val id = UUID.randomUUID().toString()
        val path = image?.let { bmp ->
            File(imageDir, "$id.jpg").also { f -> f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) } }.absolutePath
        }
        val b = Bookmark(id, positionMs, note, path, System.currentTimeMillis())
        writeBookmarks(uri, bookmarks(uri) + b)
        return b
    }

    fun updateBookmark(uri: String, b: Bookmark) = writeBookmarks(uri, bookmarks(uri).map { if (it.id == b.id) b else it })

    fun removeBookmark(uri: String, b: Bookmark) {
        b.image?.let { File(it).delete() }
        writeBookmarks(uri, bookmarks(uri).filter { it.id != b.id })
    }

    private fun writeBookmarks(uri: String, list: List<Bookmark>) = write("bookmarks.$uri", list.map {
        JSONObject().put("id", it.id).put("position", it.positionMs).put("note", it.note).put("image", it.image ?: "").put("createdAt", it.createdAt)
    })

    // ---- 工具 ----

    private fun read(key: String): List<JSONObject> = try {
        val arr = JSONArray(prefs.getString(key, "[]"))
        List(arr.length()) { arr.getJSONObject(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun write(key: String, items: List<JSONObject>) {
        prefs.edit().putString(key, JSONArray(items).toString()).apply()
    }

    companion object {
        /** 剧集的 key：剧名 + 季。电影、解析不出集数的文件返回 null。 */
        fun seriesKey(entry: MediaEntry): String? {
            val p = NameParser.parse(entry.title)
            if (p.episode == null || p.title.isBlank()) return null
            return p.title.lowercase().replace(Regex("""\s+"""), " ").trim() + "#" + (p.season ?: 1)
        }
    }
}
