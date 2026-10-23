package com.aisub.player.meta

import com.aisub.player.remote.cloud.cloudHttp
import okhttp3.Request
import org.json.JSONObject

/** Bangumi 条目：评分、简介、海报，以及（单集时）这一集的标题和简介。 */
data class BangumiResult(val id: Int, val rating: Double?, val votes: Int?, val info: MediaInfo) {
    val url get() = "https://bgm.tv/subject/$id"
}

/**
 * Bangumi 番组计划：动画最全，不用 Key。每一季是单独的条目，第二季以后按「片名 第二季」搜索。
 * 接口要求 User-Agent 写明应用名。
 */
object Bangumi {

    private const val API = "https://api.bgm.tv"
    private const val UA = "clusflah/Voxtitle (https://github.com/clusflah/ai-subtitle-player)"

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json").build()
        return cloudHttp.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }

    /** 阻塞调用；找不到返回 null。 */
    fun lookup(titles: List<String>, year: Int?, season: Int?, episode: Int?): BangumiResult? {
        val queries = titles.flatMap { t ->
            if (season != null && season > 1) listOf("$t 第${NameParser.toCn(season)}季", "$t Season $season") else listOf(t)
        }.distinct()
        for (q in queries) {
            val path = java.net.URLEncoder.encode(q, "UTF-8").replace("+", "%20")
            val text = get("$API/search/subject/$path?type=2&responseGroup=small&max_results=10") ?: continue
            val list = runCatching { JSONObject(text).optJSONArray("list") }.getOrNull().objects()
            // 名字对得上的排前面，最多看 3 个条目的详情，按年份挑
            val cands = list.sortedBy { o ->
                val names = listOf(o.optString("name_cn"), o.optString("name"))
                when {
                    names.any { it == q } -> 0
                    names.any { it.contains(q) || (it.isNotEmpty() && q.contains(it)) } -> 1
                    else -> 2
                }
            }.take(3)
            for (c in cands) {
                val subject = get("$API/v0/subjects/${c.optInt("id")}")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
                val y = subject.optString("date").take(4).toIntOrNull()
                if (year != null && y != null && kotlin.math.abs(year - y) > 1 && (season == null || season <= 1)) continue
                return toResult(subject, episode)
            }
        }
        return null
    }

    private fun toResult(j: JSONObject, episode: Int?): BangumiResult {
        val id = j.optInt("id")
        val rating = j.optJSONObject("rating")
        val platform = j.optString("platform")
        val movie = platform.contains("剧场") || platform.contains("电影")
        var info = MediaInfo(
            kind = if (movie) "movie" else "tv",
            tmdbId = 0,
            imdbId = null,
            title = j.optString("name_cn").ifEmpty { j.optString("name") },
            originalTitle = j.optString("name"),
            year = j.optString("date").take(4),
            overview = j.optString("summary").trim(),
            posterUrl = j.optJSONObject("images")?.let { it.optString("large").ifEmpty { it.optString("common") } }
                ?.takeIf { it.startsWith("http") }?.replace("http://", "https://"),
            backdropUrl = null,
            genres = listOf("动画") + j.optJSONArray("meta_tags").strings().filter { it != "动画" }.take(3),
            length = j.optInt("total_episodes").takeIf { it > 0 && !movie }?.let { "共 $it 集" }.orEmpty(),
            rating = rating?.optDouble("score", 0.0) ?: 0.0,
            voteCount = rating?.optInt("total") ?: 0,
            directors = emptyList(),
            cast = emptyList(),
            countries = emptyList(),
            ratingLabel = "Bangumi",
            source = "Bangumi",
        )
        if (episode != null && !movie) {
            val eps = get("$API/v0/episodes?subject_id=$id&type=0&limit=200")
                ?.let { runCatching { JSONObject(it).optJSONArray("data") }.getOrNull() }.objects()
            // ep 是本季内的集数，sort 是整部作品的连续编号；优先用 ep
            val ep = eps.firstOrNull { it.optDouble("ep", -1.0).toInt() == episode } ?: eps.firstOrNull { it.optDouble("sort", -1.0).toInt() == episode }
            if (ep != null) {
                info = info.copy(
                    episodeTitle = "第 $episode 集 · " + ep.optString("name_cn").ifEmpty { ep.optString("name") },
                    episodeOverview = ep.optString("desc").trim().ifEmpty { null },
                )
            }
        }
        return BangumiResult(id, rating?.optDouble("score", 0.0)?.takeIf { it > 0 }, rating?.optInt("total")?.takeIf { it > 0 }, info)
    }
}
