package com.aisub.player.meta

import com.aisub.player.remote.cloud.cloudHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** TMDB 上的一部电影或剧集（单集时附带这一集的信息）。 */
data class MediaInfo(
    /** "movie" 或 "tv" */
    val kind: String,
    val tmdbId: Int,
    val imdbId: String?,
    val title: String,
    val originalTitle: String,
    val year: String,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val genres: List<String>,
    /** "2 小时 16 分" 或 "共 3 季 · 24 集" */
    val length: String,
    val rating: Double,
    val voteCount: Int,
    val directors: List<String>,
    val cast: List<String>,
    val countries: List<String>,
    val episodeTitle: String? = null,
    val episodeOverview: String? = null,
    val episodeStill: String? = null,
    /** 主评分的来源 */
    val ratingLabel: String = "TMDB",
    /** 信息来源，显示在详情页：「TMDB」「本地 NFO」「本地 NFO + TMDB」 */
    val source: String = "TMDB",
    val doubanRating: Double? = null,
    val doubanVotes: Int? = null,
    val doubanId: String? = null,
    val tvdbId: Int? = null,
    /** 透明标题图（Fanart.tv） */
    val logoUrl: String? = null,
) {
    /** 0 表示不知道 TMDB 编号（只有本地 NFO） */
    val tmdbUrl get() = if (tmdbId > 0) "https://www.themoviedb.org/$kind/$tmdbId" else null

    /** 用远程结果补全本地信息：本地已有的字段保留。 */
    fun fillFrom(remote: MediaInfo): MediaInfo = MediaInfo(
        kind = kind,
        tmdbId = if (tmdbId > 0) tmdbId else remote.tmdbId,
        imdbId = imdbId ?: remote.imdbId,
        title = title.ifEmpty { remote.title },
        originalTitle = originalTitle.ifEmpty { remote.originalTitle },
        year = year.ifEmpty { remote.year },
        overview = overview.ifBlank { remote.overview },
        posterUrl = posterUrl ?: remote.posterUrl,
        backdropUrl = backdropUrl ?: remote.backdropUrl,
        genres = genres.ifEmpty { remote.genres },
        length = length.ifEmpty { remote.length },
        rating = if (rating > 0) rating else remote.rating,
        voteCount = if (rating > 0) voteCount else remote.voteCount,
        directors = directors.ifEmpty { remote.directors },
        cast = cast.ifEmpty { remote.cast },
        countries = countries.ifEmpty { remote.countries },
        episodeTitle = episodeTitle ?: remote.episodeTitle,
        episodeOverview = episodeOverview?.takeIf { it.isNotBlank() } ?: remote.episodeOverview,
        episodeStill = episodeStill ?: remote.episodeStill,
        ratingLabel = if (rating > 0) ratingLabel else remote.ratingLabel,
        source = "$source + ${remote.source}",
        doubanRating = doubanRating ?: remote.doubanRating,
        doubanVotes = if (doubanRating != null) doubanVotes else remote.doubanVotes,
        doubanId = doubanId ?: remote.doubanId,
        tvdbId = tvdbId ?: remote.tvdbId,
        logoUrl = logoUrl ?: remote.logoUrl,
    )
}

/**
 * TMDB v3 接口。key 可以是 v3 API Key，也可以是 v4 的 Read Access Token（eyJ 开头）。
 * 国内直连 api.themoviedb.org 不稳定，地址和图片地址都可以在设置里换成镜像或反代。
 */
class Tmdb(key: String, baseUrl: String, imageBase: String, private val language: String = "zh-CN") {

    private val key = key.trim()
    private val base = baseUrl.trim().trimEnd('/').ifEmpty { DEFAULT_BASE }
    private val imageBase = imageBase.trim().trimEnd('/').ifEmpty { DEFAULT_IMAGE_BASE }
    private val bearer = this.key.startsWith("eyJ")

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = (base + path).toHttpUrl().newBuilder().apply {
            if (!bearer) addQueryParameter("api_key", key)
            addQueryParameter("language", language)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).header("Accept", "application/json")
            .apply { if (bearer) header("Authorization", "Bearer $key") }.build()
        return cloudHttp.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            when {
                resp.code == 401 -> throw IOException("TMDB API Key 无效")
                resp.code == 404 -> JSONObject()
                !resp.isSuccessful -> throw IOException("TMDB 请求失败 HTTP ${resp.code}")
                else -> runCatching { JSONObject(text) }.getOrElse { throw IOException("TMDB 返回异常：${text.take(100)}") }
            }
        }
    }

    /** 查找并返回详情；找不到返回 null。阻塞调用。 */
    fun lookup(q: MetaQuery): MediaInfo? {
        val (kind, id) = locate(q) ?: return null
        return details(kind, id, q.season, q.episode)
    }

    private fun locate(q: MetaQuery): Pair<String, Int>? {
        q.tmdbId?.toIntOrNull()?.let { id -> if (q.kind == "movie" || q.kind == "tv") return q.kind to id }
        q.imdbId?.takeIf { it.startsWith("tt") }?.let { imdb ->
            val r = get("/find/$imdb", mapOf("external_source" to "imdb_id"))
            r.optJSONArray("movie_results")?.optJSONObject(0)?.let { return "movie" to it.getInt("id") }
            r.optJSONArray("tv_results")?.optJSONObject(0)?.let { return "tv" to it.getInt("id") }
            // 单集的 IMDb 编号会落在 tv_episode_results 里
            r.optJSONArray("tv_episode_results")?.optJSONObject(0)?.optInt("show_id")?.takeIf { it > 0 }?.let { return "tv" to it }
        }
        val episodic = q.kind == "tv" || q.season != null || q.episode != null
        for (title in q.titles) {
            if (episodic) {
                search("tv", title, q.year)?.let { return "tv" to it }
                if (q.year != null) search("tv", title, null)?.let { return "tv" to it }
            } else {
                search("movie", title, q.year)?.let { return "movie" to it }
                search("tv", title, q.year)?.let { return "tv" to it }
            }
        }
        // 年份可能是错的（发布年份、文件名里的其他数字），去掉年份再试一次
        if (!episodic && q.year != null) {
            for (title in q.titles) {
                val r = get("/search/multi", mapOf("query" to title)).optJSONArray("results") ?: continue
                for (i in 0 until r.length()) {
                    val o = r.getJSONObject(i)
                    val type = o.optString("media_type")
                    if (type == "movie" || type == "tv") return type to o.getInt("id")
                }
            }
        }
        return null
    }

    private fun search(kind: String, title: String, year: Int?): Int? {
        val params = buildMap {
            put("query", title)
            if (year != null) put(if (kind == "movie") "year" else "first_air_date_year", year.toString())
        }
        return get("/search/$kind", params).optJSONArray("results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
    }

    private fun details(kind: String, id: Int, season: Int?, episode: Int?): MediaInfo? {
        val d = get("/$kind/$id", mapOf("append_to_response" to "credits,external_ids"))
        if (!d.has("id")) return null
        val credits = d.optJSONObject("credits")
        val directors = if (kind == "movie") {
            credits?.optJSONArray("crew").objects().filter { it.optString("job") == "Director" }.map { it.optString("name") }
        } else {
            d.optJSONArray("created_by").objects().map { it.optString("name") }
        }.orEmpty().distinct()
        val length = if (kind == "movie") {
            d.optInt("runtime").takeIf { it > 0 }?.let { m -> if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else "$m 分钟" }.orEmpty()
        } else {
            listOfNotNull(
                d.optInt("number_of_seasons").takeIf { it > 0 }?.let { "共 $it 季" },
                d.optInt("number_of_episodes").takeIf { it > 0 }?.let { "$it 集" },
            ).joinToString(" · ")
        }
        val date = d.optString(if (kind == "movie") "release_date" else "first_air_date")
        var info = MediaInfo(
            kind = kind,
            tmdbId = id,
            imdbId = (d.optString("imdb_id").ifEmpty { d.optJSONObject("external_ids")?.optString("imdb_id").orEmpty() })
                .takeIf { it.startsWith("tt") },
            title = d.optString(if (kind == "movie") "title" else "name"),
            originalTitle = d.optString(if (kind == "movie") "original_title" else "original_name"),
            year = date.take(4),
            overview = d.optString("overview"),
            posterUrl = image(d.optString("poster_path"), "w500"),
            backdropUrl = image(d.optString("backdrop_path"), "w1280"),
            genres = d.optJSONArray("genres").objects().map { it.optString("name") },
            length = length,
            rating = d.optDouble("vote_average", 0.0),
            voteCount = d.optInt("vote_count"),
            directors = directors,
            cast = credits?.optJSONArray("cast").objects().take(12).map { it.optString("name") },
            countries = d.optJSONArray("production_countries").objects().map { it.optString("name") }
                .ifEmpty { d.optJSONArray("origin_country").strings() },
            tvdbId = d.optJSONObject("external_ids")?.optInt("tvdb_id")?.takeIf { it > 0 },
        )
        if (kind == "tv" && episode != null) {
            val e = runCatching { get("/tv/$id/season/${season ?: 1}/episode/$episode") }.getOrNull()
            if (e != null && e.has("id")) {
                info = info.copy(
                    episodeTitle = "第 ${season ?: 1} 季第 $episode 集 · ${e.optString("name")}",
                    episodeOverview = e.optString("overview"),
                    episodeStill = image(e.optString("still_path"), "w780"),
                )
            }
        }
        return info
    }

    private fun image(path: String, size: String): String? =
        path.takeIf { it.startsWith("/") }?.let { "$imageBase/$size$it" }

    companion object {
        const val DEFAULT_BASE = "https://api.themoviedb.org/3"
        const val DEFAULT_IMAGE_BASE = "https://image.tmdb.org/t/p"
    }
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }
