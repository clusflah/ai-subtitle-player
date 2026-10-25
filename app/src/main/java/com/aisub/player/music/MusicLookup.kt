package com.aisub.player.music

import com.aisub.player.remote.cloud.cloudHttp
import com.aisub.player.translate.ChineseScript
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** 在线搜到的一首歌。lyrics 不为空表示搜索结果里已经带了歌词（LRCLIB）。 */
data class SongCandidate(
    val source: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String? = null,
    val lyrics: String? = null,
    val synced: Boolean = false,
)

/**
 * 在线找歌词和封面。都是阻塞调用，放在 IO 线程里用。
 * - 歌词：LRCLIB（开放接口，按歌名、歌手、时长匹配）→ 网易云音乐（非官方接口，中文歌多）
 * - 封面：网易云音乐 → iTunes Search API
 */
object MusicLookup {
    private const val UA = "Voxtitle (https://github.com/clusflah/ai-subtitle-player)"
    private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    const val LRCLIB = "LRCLIB"
    const val NETEASE = "网易云音乐"
    const val ITUNES = "iTunes"

    private fun get(url: String, referer: String? = null, ua: String = UA): String? {
        val req = Request.Builder().url(url).header("User-Agent", ua).apply { referer?.let { header("Referer", it) } }.build()
        return runCatching { cloudHttp.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null } }.getOrNull()
    }

    fun download(url: String): ByteArray? {
        val req = Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()
        return runCatching { cloudHttp.newCall(req).execute().use { if (it.isSuccessful) it.body?.bytes() else null } }.getOrNull()
    }

    // ---------------- 自动匹配 ----------------

    /** 自动找最匹配的歌词；找不到返回 null。 */
    fun bestLyrics(tags: TrackTags): Lyrics? {
        lrclibGet(tags)?.let { c -> lyricsOf(c)?.let { return it } }
        val candidates = (lrclibSearch(tags.title, tags.artist) + neteaseSearch(tags.title, tags.artist))
            .map { it to score(it, tags) }.filter { it.second >= MIN_SCORE }.sortedByDescending { it.second }
        for ((c, _) in candidates.take(4)) lyricsOf(c)?.let { return it }
        return null
    }

    /** 自动找最匹配的封面图片；找不到返回 null。 */
    fun bestCover(tags: TrackTags): ByteArray? {
        val candidates = (neteaseSearch(tags.title, tags.artist) + itunesSearch(tags.title, tags.artist))
            .filter { it.coverUrl != null }.map { it to score(it, tags) }.filter { it.second >= MIN_SCORE }.sortedByDescending { it.second }
        for ((c, _) in candidates.take(3)) download(c.coverUrl!!)?.takeIf { it.size > 1024 }?.let { return it }
        return null
    }

    // ---------------- 手动搜索 ----------------

    /** 手动搜歌词：两个来源的结果合在一起，最像的排前面。 */
    fun searchLyrics(title: String, artist: String?, durationMs: Long): List<SongCandidate> {
        val tags = TrackTags(title, artist, durationMs = durationMs)
        return (lrclibSearch(title, artist) + neteaseSearch(title, artist)).sortedByDescending { score(it, tags) }
    }

    /** 手动换封面：带封面的结果。 */
    fun searchCovers(title: String, artist: String?): List<SongCandidate> {
        val tags = TrackTags(title, artist)
        return (neteaseSearch(title, artist) + itunesSearch(title, artist)).filter { it.coverUrl != null }
            .distinctBy { it.coverUrl }.sortedByDescending { score(it, tags) }
    }

    /** 取候选的歌词（LRCLIB 的结果自带，网易云要再请求一次）。 */
    fun lyricsOf(c: SongCandidate): Lyrics? = when (c.source) {
        LRCLIB -> c.lyrics?.let { Lyrics.parse(it, LRCLIB) }
        NETEASE -> neteaseLyrics(c.id)?.let { Lyrics.parse(it, NETEASE) }
        else -> null
    }

    // ---------------- LRCLIB ----------------

    private fun lrclibGet(tags: TrackTags): SongCandidate? {
        val artist = tags.artist ?: return null
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", tags.title)
            .addQueryParameter("artist_name", artist)
            .apply {
                tags.album?.let { addQueryParameter("album_name", it) }
                if (tags.durationMs > 0) addQueryParameter("duration", (tags.durationMs / 1000).toString())
            }.build().toString()
        return get(url)?.let { runCatching { lrclibItem(JSONObject(it)) }.getOrNull() }
    }

    private fun lrclibSearch(title: String, artist: String?): List<SongCandidate> {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .apply { artist?.let { addQueryParameter("artist_name", it) } }
            .build().toString()
        val arr = get(url)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return arr.objects().mapNotNull(::lrclibItem).take(10)
    }

    private fun lrclibItem(o: JSONObject): SongCandidate? {
        val synced = o.optString("syncedLyrics").takeIf { !o.isNull("syncedLyrics") && it.isNotBlank() }
        val plain = o.optString("plainLyrics").takeIf { !o.isNull("plainLyrics") && it.isNotBlank() }
        val lyrics = synced ?: plain ?: return null
        return SongCandidate(
            LRCLIB, o.optString("id"), o.optString("trackName"), o.optString("artistName"), o.optString("albumName"),
            (o.optDouble("duration", 0.0) * 1000).toLong(), lyrics = lyrics, synced = synced != null,
        )
    }

    // ---------------- 网易云音乐 ----------------

    private fun neteaseSearch(title: String, artist: String?): List<SongCandidate> {
        val url = "https://music.163.com/api/cloudsearch/pc".toHttpUrl().newBuilder()
            .addQueryParameter("s", listOfNotNull(title, artist).joinToString(" "))
            .addQueryParameter("type", "1")
            .addQueryParameter("limit", "10")
            .build().toString()
        val json = get(url, "https://music.163.com/", BROWSER_UA)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        return json.optJSONObject("result")?.optJSONArray("songs").objects().map { s ->
            val al = s.optJSONObject("al")
            SongCandidate(
                NETEASE, s.optLong("id").toString(), s.optString("name"),
                s.optJSONArray("ar").objects().joinToString(" / ") { it.optString("name") },
                al?.optString("name").orEmpty(), s.optLong("dt"),
                coverUrl = al?.optString("picUrl")?.takeIf { it.startsWith("http") }?.let { it.replace("http://", "https://") + "?param=800y800" },
            )
        }
    }

    private fun neteaseLyrics(id: String): String? {
        val url = "https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1"
        val json = get(url, "https://music.163.com/", BROWSER_UA)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return json.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
    }

    // ---------------- iTunes ----------------

    private fun itunesSearch(title: String, artist: String?): List<SongCandidate> {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", listOfNotNull(artist, title).joinToString(" "))
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "10")
            // 默认的美区搜不到大多数华语歌，台区有
            .apply { if (hasHan(title) || artist?.let(::hasHan) == true) addQueryParameter("country", "tw") }
            .build().toString()
        val json = get(url)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        return json.optJSONArray("results").objects().map { r ->
            SongCandidate(
                ITUNES, r.optLong("trackId").toString(), r.optString("trackName"), r.optString("artistName"),
                r.optString("collectionName"), r.optLong("trackTimeMillis"),
                coverUrl = r.optString("artworkUrl100").takeIf { it.startsWith("http") }?.replace("100x100bb", "800x800bb"),
            )
        }
    }

    // ---------------- 匹配打分 ----------------

    private const val MIN_SCORE = 3

    /** 歌名对得上是前提；歌手、时长对得上加分，时长差太多扣分。 */
    internal fun score(c: SongCandidate, tags: TrackTags): Int {
        val t1 = norm(c.title)
        val t2 = norm(tags.title)
        if (t1.isEmpty() || t2.isEmpty()) return 0
        var s = when {
            t1 == t2 -> 3
            t1.contains(t2) || t2.contains(t1) -> 2
            else -> return 0
        }
        tags.artist?.let { a ->
            val want = norm(a)
            val got = norm(c.artist)
            if (want.isNotEmpty() && got.isNotEmpty()) s += if (got == want || got.contains(want) || want.contains(got)) 2 else -1
        }
        if (tags.durationMs > 0 && c.durationMs > 0) {
            val diff = abs(tags.durationMs - c.durationMs)
            s += when {
                diff <= 3000 -> 2
                diff <= 10_000 -> 1
                diff > 30_000 -> -2
                else -> 0
            }
        }
        if (c.synced) s += 1
        return s
    }

    /** 去掉括号里的版本说明、空白和标点，转小写，繁体转简体（「周杰倫」和「周杰伦」算同一个）。 */
    private fun norm(s: String): String = ChineseScript.toSimplified(s).lowercase()
        .replace(Regex("""[(（\[【].*?[)）\]】]"""), "")
        .replace(Regex("""[\s\p{P}\p{S}]"""), "")

    internal fun hasHan(s: String) = s.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else List(length()) { optJSONObject(it) }.filterNotNull()
}
