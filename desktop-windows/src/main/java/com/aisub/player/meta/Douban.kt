package com.aisub.player.meta

import com.aisub.player.remote.cloud.cloudHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** 豆瓣条目：评分，以及移动端接口给出的简介、海报、演职员（info）。 */
data class DoubanRating(val id: String, val title: String, val rating: Double?, val votes: Int?, val info: MediaInfo? = null) {
    val url get() = "https://movie.douban.com/subject/$id/"
}

/**
 * 豆瓣。没有公开 API，这里用网页自己用的接口：
 * 搜索建议接口（找不到时用移动端搜索）按 IMDb 编号或片名找到条目，再读移动端详情（评分、简介、海报、演职员）。
 * 都是非官方接口，可能被限流或改版。
 */
object Douban {

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    private fun fetch(url: String, referer: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Referer", referer).build()
        return cloudHttp.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }

    /** 阻塞调用。imdbId 能精确匹配；否则按片名 + 年份 + 季挑最像的一条。tv 决定详情接口先试哪个。 */
    fun lookup(titles: List<String>, year: Int?, season: Int?, imdbId: String?, tv: Boolean = season != null, doubanId: String? = null): DoubanRating? {
        val id = doubanId
            ?: imdbId?.let { suggest(it).firstOrNull()?.first }
            ?: titles.firstNotNullOfOrNull { title ->
                val query = if (season != null && season > 1) "$title 第${NameParser.toCn(season)}季" else title
                pick(search(query), year, season) ?: if (query != title) pick(search(title), year, season) else null
            }
            ?: return null
        val detail = detail(id, tv)
        if (detail == null) {
            // 移动端接口被限流时退回网页，只拿评分
            val (rating, votes) = ratingFromPage(id)
            return DoubanRating(id, titles.firstOrNull().orEmpty(), rating, votes)
        }
        val r = detail.optJSONObject("rating")
        return DoubanRating(
            id = id,
            title = detail.optString("title"),
            rating = r?.optDouble("value", 0.0)?.takeIf { it > 0 },
            votes = r?.optInt("count")?.takeIf { it > 0 },
            info = toInfo(detail, tv),
        )
    }

    /** 候选条目：id、片名、年份。 */
    private fun search(q: String): List<Triple<String, String, Int?>> = suggest(q).ifEmpty { mobileSearch(q) }

    private fun suggest(q: String): List<Triple<String, String, Int?>> {
        val url = "https://movie.douban.com/j/subject_suggest".toHttpUrl().newBuilder().addQueryParameter("q", q).build().toString()
        val text = runCatching { fetch(url, "https://movie.douban.com/") }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return arr.objects().filter { it.optString("type") == "movie" && it.optString("id").isNotEmpty() }
            .map { Triple(it.optString("id"), it.optString("title"), it.optString("year").toIntOrNull()) }
    }

    /** 移动端搜索：结果比联想搜索全（联想只给前几条）。 */
    private fun mobileSearch(q: String): List<Triple<String, String, Int?>> {
        val url = "https://m.douban.com/rexxar/api/v2/search/movie".toHttpUrl().newBuilder()
            .addQueryParameter("q", q).addQueryParameter("count", "20").build().toString()
        val text = runCatching { fetch(url, "https://m.douban.com/") }.getOrNull() ?: return emptyList()
        val items = runCatching { JSONObject(text).optJSONArray("items") }.getOrNull()
        return items.objects().filter { it.optString("target_type") in setOf("movie", "tv") }.mapNotNull { o ->
            val t = o.optJSONObject("target") ?: return@mapNotNull null
            Triple(t.optString("id").ifEmpty { return@mapNotNull null }, t.optString("title"), t.optString("year").toIntOrNull())
        }
    }

    private fun pick(list: List<Triple<String, String, Int?>>, year: Int?, season: Int?): String? {
        if (list.isEmpty()) return null
        return list.withIndex().maxByOrNull { (i, o) ->
            var score = -i
            val y = o.third
            if (year != null && y != null) score += if (y == year) 10 else if (kotlin.math.abs(y - year) <= 1) 4 else -4
            if (season != null && season > 1 && o.second.contains("第${NameParser.toCn(season)}季")) score += 8
            score
        }?.value?.first
    }

    /** 移动端详情：电影和剧集是两个接口，先试预期的那个。 */
    private fun detail(id: String, tv: Boolean): JSONObject? {
        for (kind in if (tv) listOf("tv", "movie") else listOf("movie", "tv")) {
            val text = runCatching { fetch("https://m.douban.com/rexxar/api/v2/$kind/$id?ck=&for_mobile=1", "https://m.douban.com/movie/subject/$id/") }
                .getOrNull() ?: continue
            val j = runCatching { JSONObject(text) }.getOrNull() ?: continue
            if (j.has("title")) return j
        }
        return null
    }

    private fun toInfo(j: JSONObject, tvHint: Boolean): MediaInfo {
        val tv = j.optBoolean("is_tv", tvHint) || j.optString("subtype") == "tv"
        val date = j.optJSONArray("pubdate").strings().firstOrNull()?.substringBefore('(')
        val r = j.optJSONObject("rating")
        return MediaInfo(
            kind = if (tv) "tv" else "movie",
            tmdbId = 0,
            imdbId = null,
            title = j.optString("title"),
            originalTitle = j.optString("original_title"),
            year = j.optString("year").ifEmpty { date?.take(4).orEmpty() },
            overview = j.optString("intro").trim(),
            posterUrl = j.optJSONObject("pic")?.optString("large")?.takeIf { it.startsWith("http") },
            backdropUrl = null,
            genres = j.optJSONArray("genres").strings(),
            length = if (tv) j.optInt("episodes_count").takeIf { it > 0 }?.let { "共 $it 集" }.orEmpty()
            else j.optJSONArray("durations").strings().firstOrNull().orEmpty(),
            rating = r?.optDouble("value", 0.0) ?: 0.0,
            voteCount = r?.optInt("count") ?: 0,
            directors = j.optJSONArray("directors").objects().map { it.optString("name") }.filter { it.isNotEmpty() },
            cast = j.optJSONArray("actors").objects().map { it.optString("name") }.filter { it.isNotEmpty() }.take(12),
            countries = j.optJSONArray("countries").strings(),
            ratingLabel = "豆瓣",
            source = "豆瓣",
            doubanRating = r?.optDouble("value", 0.0)?.takeIf { it > 0 },
            doubanVotes = r?.optInt("count")?.takeIf { it > 0 },
            doubanId = j.optString("id").ifEmpty { null },
        )
    }

    private fun ratingFromPage(id: String): Pair<Double?, Int?> {
        val html = runCatching { fetch("https://movie.douban.com/subject/$id/", "https://movie.douban.com/") }.getOrNull()
            ?: return null to null
        val value = Regex("""property="v:average">\s*([0-9.]+)\s*<""").find(html)?.groupValues?.get(1)?.toDoubleOrNull()
        val votes = Regex("""property="v:votes">\s*(\d+)\s*<""").find(html)?.groupValues?.get(1)?.toIntOrNull()
        return value to votes
    }
}
