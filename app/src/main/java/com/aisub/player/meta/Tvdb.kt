package com.aisub.player.meta

import com.aisub.player.remote.cloud.cloudHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * TheTVDB v4：剧集的中文标题、简介和分集信息比较全。需要在设置里填 API Key（订阅用户另填 PIN）。
 * 中文取 zho 翻译，没有翻译用原文。
 */
class Tvdb(private val apiKey: String, private val pin: String) {

    private fun auth(): String {
        tokens[apiKey]?.let { return it }
        val body = JSONObject().put("apikey", apiKey.trim()).apply { if (pin.isNotBlank()) put("pin", pin.trim()) }
            .toString().toRequestBody("application/json".toMediaType())
        val text = cloudHttp.newCall(Request.Builder().url("$API/login").post(body).build()).execute().use {
            if (it.code == 401) throw IOException("TheTVDB API Key 无效")
            if (!it.isSuccessful) throw IOException("TheTVDB 登录失败 HTTP ${it.code}")
            it.body?.string().orEmpty()
        }
        return JSONObject(text).getJSONObject("data").getString("token").also { tokens[apiKey] = it }
    }

    private fun get(url: String): JSONObject? {
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${auth()}").header("Accept", "application/json").build()
        return cloudHttp.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> null
                resp.code == 401 -> { tokens.remove(apiKey); throw IOException("TheTVDB 登录已失效") }
                !resp.isSuccessful -> throw IOException("TheTVDB 请求失败 HTTP ${resp.code}")
                else -> JSONObject(resp.body?.string().orEmpty())
            }
        }
    }

    /** 阻塞调用。tvdbId 已知（TMDB / NFO 给的）时直接取，否则按片名搜索。 */
    fun lookup(q: MetaQuery, tvdbId: Int?, imdbId: String?): MediaInfo? {
        val tv = q.kind == "tv" || q.season != null || q.episode != null
        val id = tvdbId ?: imdbId?.let { byRemoteId(it, tv) } ?: q.titles.firstNotNullOfOrNull { search(it, q.year, tv) } ?: return null
        val j = get("$API/${if (tv) "series" else "movies"}/$id/extended?meta=translations&short=true")?.optJSONObject("data") ?: return null
        val name = j.zh("nameTranslations", "name") ?: j.optString("name")
        val date = if (tv) j.optString("firstAired") else j.optJSONObject("first_release")?.optString("date").orEmpty()
        var info = MediaInfo(
            kind = if (tv) "tv" else "movie",
            tmdbId = 0,
            imdbId = j.optJSONArray("remoteIds").objects().firstOrNull { it.optString("sourceName").contains("IMDB", true) }?.optString("id"),
            title = name,
            originalTitle = j.optString("name").takeIf { it != name }.orEmpty(),
            year = date.take(4).ifEmpty { j.optString("year") },
            overview = (j.zh("overviewTranslations", "overview") ?: j.optString("overview")).trim(),
            posterUrl = img(j.optString("image")),
            backdropUrl = j.optJSONArray("artworks").objects().filter { it.optInt("type") in setOf(3, 15) }
                .maxByOrNull { it.optDouble("score", 0.0) }?.optString("image")?.let(::img),
            genres = j.optJSONArray("genres").objects().map { it.optString("name") }.filter { it.isNotEmpty() },
            length = if (tv) "" else j.optInt("runtime").takeIf { it > 0 }?.let { m -> if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else "$m 分钟" }.orEmpty(),
            rating = 0.0,
            voteCount = 0,
            directors = j.optJSONArray("characters").objects().filter { it.optString("peopleType") == "Director" }.map { it.optString("personName") },
            cast = j.optJSONArray("characters").objects().filter { it.optString("peopleType") == "Actor" }
                .sortedBy { it.optInt("sort", 999) }.map { it.optString("personName") }.filter { it.isNotEmpty() }.take(12),
            countries = listOfNotNull(j.optString("originalCountry").ifEmpty { null }),
            source = "TheTVDB",
            tvdbId = id,
        )
        if (tv && q.episode != null) episode(id, q.season ?: 1, q.episode)?.let { (title, overview, still) ->
            info = info.copy(episodeTitle = "第 ${q.season ?: 1} 季第 ${q.episode} 集 · $title", episodeOverview = overview, episodeStill = still)
        }
        return info
    }

    /** 某一集的中文标题、简介、截图。 */
    fun episode(seriesId: Int, season: Int, episode: Int): Triple<String, String?, String?>? {
        for (page in 0 until 10) {
            val url = "$API/series/$seriesId/episodes/default/zho".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString()).addQueryParameter("season", season.toString()).build().toString()
            val j = get(url) ?: return null
            val ep = j.optJSONObject("data")?.optJSONArray("episodes").objects()
                .firstOrNull { it.optInt("seasonNumber") == season && it.optInt("number") == episode }
            if (ep != null) return Triple(ep.optString("name"), ep.optString("overview").ifBlank { null }, img(ep.optString("image")))
            if (j.optJSONObject("links")?.isNull("next") != false) break
        }
        return null
    }

    private fun search(title: String, year: Int?, tv: Boolean): Int? {
        val url = "$API/search".toHttpUrl().newBuilder().addQueryParameter("query", title)
            .addQueryParameter("type", if (tv) "series" else "movie").addQueryParameter("limit", "5")
            .apply { if (year != null) addQueryParameter("year", year.toString()) }.build().toString()
        return get(url)?.optJSONArray("data").objects().firstNotNullOfOrNull { it.optString("tvdb_id").toIntOrNull() }
    }

    private fun byRemoteId(imdb: String, tv: Boolean): Int? = get("$API/search/remoteid/$imdb")?.optJSONArray("data").objects()
        .firstNotNullOfOrNull { (if (tv) it.optJSONObject("series") else it.optJSONObject("movie"))?.optInt("id")?.takeIf { id -> id > 0 } }

    private fun JSONObject.zh(kind: String, field: String): String? =
        optJSONObject("translations")?.optJSONArray(kind).objects().firstOrNull { it.optString("language") == "zho" }
            ?.optString(field)?.takeIf { it.isNotBlank() }

    private fun img(s: String?) = s?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "https://artworks.thetvdb.com$it" }

    companion object {
        private const val API = "https://api4.thetvdb.com/v4"
        /** 登录令牌一个月有效，按 Key 缓存 */
        private val tokens = java.util.concurrent.ConcurrentHashMap<String, String>()
    }
}
