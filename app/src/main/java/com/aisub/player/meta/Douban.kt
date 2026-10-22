package com.aisub.player.meta

import com.aisub.player.remote.cloud.cloudHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class DoubanRating(val id: String, val title: String, val rating: Double?, val votes: Int?) {
    val url get() = "https://movie.douban.com/subject/$id/"
}

/**
 * 豆瓣评分。豆瓣没有公开 API，这里用网页自己用的接口：
 * 搜索建议接口按 IMDb 编号或片名找到条目，再读移动版接口的评分，失败时解析条目网页。
 * 都是非官方接口，可能被限流或改版。
 */
object Douban {

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    private fun fetch(url: String, referer: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Referer", referer).build()
        return cloudHttp.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }

    /** 阻塞调用。imdbId 能精确匹配；否则按片名 + 年份 + 季挑最像的一条。 */
    fun lookup(titles: List<String>, year: Int?, season: Int?, imdbId: String?): DoubanRating? {
        val hit = imdbId?.let { suggest(it).firstOrNull() }
            ?: titles.firstNotNullOfOrNull { title ->
                val query = if (season != null && season > 1) "$title 第${NameParser.toCn(season)}季" else title
                pick(suggest(query), year, season) ?: if (query != title) pick(suggest(title), year, season) else null
            }
            ?: return null
        val id = hit.optString("id")
        val (rating, votes) = rating(id)
        return DoubanRating(id, hit.optString("title"), rating, votes)
    }

    private fun suggest(q: String): List<JSONObject> {
        val url = "https://movie.douban.com/j/subject_suggest".toHttpUrl().newBuilder().addQueryParameter("q", q).build().toString()
        val text = runCatching { fetch(url, "https://movie.douban.com/") }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return arr.objects().filter { it.optString("type") == "movie" && it.optString("id").isNotEmpty() }
    }

    private fun pick(list: List<JSONObject>, year: Int?, season: Int?): JSONObject? {
        if (list.isEmpty()) return null
        return list.withIndex().maxByOrNull { (i, o) ->
            var score = -i
            val y = o.optString("year").toIntOrNull()
            if (year != null && y != null) score += if (y == year) 10 else if (kotlin.math.abs(y - year) <= 1) 4 else -4
            if (season != null && season > 1 && o.optString("title").contains("第${NameParser.toCn(season)}季")) score += 8
            score
        }?.value
    }

    /** 评分和评价人数；没有评分（人数太少、未上映）时为 null。 */
    private fun rating(id: String): Pair<Double?, Int?> {
        for (kind in listOf("movie", "tv")) {
            val text = runCatching { fetch("https://m.douban.com/rexxar/api/v2/$kind/$id?ck=&for_mobile=1", "https://m.douban.com/movie/subject/$id/") }
                .getOrNull() ?: continue
            val r = runCatching { JSONObject(text).optJSONObject("rating") }.getOrNull() ?: continue
            val value = r.optDouble("value", 0.0)
            return (value.takeIf { it > 0 }) to r.optInt("count").takeIf { it > 0 }
        }
        val html = runCatching { fetch("https://movie.douban.com/subject/$id/", "https://movie.douban.com/") }.getOrNull()
            ?: return null to null
        val value = Regex("""property="v:average">\s*([0-9.]+)\s*<""").find(html)?.groupValues?.get(1)?.toDoubleOrNull()
        val votes = Regex("""property="v:votes">\s*(\d+)\s*<""").find(html)?.groupValues?.get(1)?.toIntOrNull()
        return value to votes
    }
}
