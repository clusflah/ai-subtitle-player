package com.aisub.player.meta

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aisub.player.AppSettings
import com.aisub.player.remote.cloud.cloudHttp
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** 发现页条目：可来自豆瓣片单、TMDB 发现页 / 流媒体榜 / 搜索，点开后进入影视详情页。 */
data class DiscoverItem(
    val id: String,
    val kind: String, // "movie" | "tv"
    val title: String,
    val originalTitle: String = "",
    val year: Int? = null,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val genres: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val rating: Double? = null,
    val ratingLabel: String? = null,
    val voteCount: Int = 0,
    val episodeInfo: String? = null,
    val ongoing: Boolean? = null,
    val tmdbId: Int? = null,
    val doubanId: String? = null,
    val imdbId: String? = null,
) {
    /** 转为元信息流水线查询参数；详情页用它继续补齐演员、导演、Logo 与多平台评分。 */
    fun toMetaQuery(): MetaQuery = MetaQuery(
        titles = listOfNotNull(
            title.takeIf { it.isNotBlank() },
            originalTitle.takeIf { it.isNotBlank() && it != title },
        ).distinct(),
        year = year,
        kind = kind,
        tmdbId = tmdbId?.toString(),
        imdbId = imdbId,
    )

    /** 副标题一行摘要，形如「2026 · 日本 · 12集」或「2024 · 美国 · 科幻 / 动作」。 */
    fun subtitleLine(): String = buildList {
        year?.let { add(it.toString()) }
        countries.firstOrNull()?.takeIf { it.isNotBlank() }?.let { add(it) }
        when {
            !episodeInfo.isNullOrBlank() -> add(episodeInfo)
            genres.isNotEmpty() -> add(genres.take(2).joinToString(" / "))
        }
    }.joinToString(" · ")
}

/** 发现页顶部的流媒体平台入口（参考 re0.me 的流媒体榜单条）。 */
data class StreamingPlatform(
    val id: String,
    val name: String,
    val badge: String,
    val accentHex: Long,
    val tmdbNetworkId: Int,
    val fallbackDoubanId: String,
)

enum class ChartSource { DOUBAN_COLLECTION, TMDB_NETWORK, TMDB_TRENDING }

/** 一条榜单的定义。 */
data class DiscoverChartDef(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: String, // "tv" | "movie" | "all"
    val source: ChartSource,
    val remoteId: String,
    val fallbackRemoteId: String? = null,
)

data class DiscoverHomeSection(
    val chart: DiscoverChartDef,
    val items: List<DiscoverItem>,
)

data class DiscoverChartPage(
    val chart: DiscoverChartDef,
    val items: List<DiscoverItem>,
    val note: String? = null,
    val usedFallback: Boolean = false,
)

object DiscoverCharts {
    val platforms: List<StreamingPlatform> = listOf(
        StreamingPlatform("netflix", "Netflix", "N", 0xFFE50914, 213, "tv_global_best_weekly"),
        StreamingPlatform("hbo", "HBO", "HBO", 0xFF7B2CBF, 49, "tv_american"),
        StreamingPlatform("apple_tv", "Apple TV+", "tv+", 0xFF2B2F3A, 2552, "tv_global_best_weekly"),
        StreamingPlatform("disney_plus", "Disney+", "D+", 0xFF0063E5, 2739, "tv_animation"),
        StreamingPlatform("prime_video", "Amazon Prime", "PRIME", 0xFF00A8E1, 1024, "tv_american"),
    )

    val homeCharts: List<DiscoverChartDef> = listOf(
        DiscoverChartDef("tv_hot", "剧集上新", "近期热门播出的剧集", "tv", ChartSource.DOUBAN_COLLECTION, "tv_hot"),
        DiscoverChartDef("movie_hot_gaia", "电影上新", "近期高热度院线与口碑电影", "movie", ChartSource.DOUBAN_COLLECTION, "movie_hot_gaia"),
        DiscoverChartDef("tv_global_best_weekly", "全球口碑剧集榜", "本周豆瓣全球高分剧集", "tv", ChartSource.DOUBAN_COLLECTION, "tv_global_best_weekly"),
        DiscoverChartDef("movie_weekly_best", "一周口碑电影榜", "本周高口碑精选电影", "movie", ChartSource.DOUBAN_COLLECTION, "movie_weekly_best"),
        DiscoverChartDef("tv_animation", "热门动画", "近期高热度新番与动画剧集", "tv", ChartSource.DOUBAN_COLLECTION, "tv_animation"),
    )

    val allCharts: List<DiscoverChartDef> = homeCharts + listOf(
        DiscoverChartDef("tv_domestic", "热门国产剧", "豆瓣近期热门国产剧集", "tv", ChartSource.DOUBAN_COLLECTION, "tv_domestic"),
        DiscoverChartDef("tv_american", "热门欧美剧", "豆瓣近期热门英美剧集", "tv", ChartSource.DOUBAN_COLLECTION, "tv_american"),
        DiscoverChartDef("tv_japanese", "热门日剧", "豆瓣近期热门日本剧集", "tv", ChartSource.DOUBAN_COLLECTION, "tv_japanese"),
        DiscoverChartDef("tv_korean", "热门韩剧", "豆瓣近期热门韩国剧集", "tv", ChartSource.DOUBAN_COLLECTION, "tv_korean"),
        DiscoverChartDef("movie_showing", "院线热映", "正在上映的热门院线电影", "movie", ChartSource.DOUBAN_COLLECTION, "movie_showing"),
        DiscoverChartDef("movie_top250", "豆瓣电影 Top250", "影史高分经典电影长榜", "movie", ChartSource.DOUBAN_COLLECTION, "movie_top250"),
        DiscoverChartDef("tmdb_trending_tv", "TMDB 本周热门剧集", "全球流媒体趋势剧集（需 TMDB Key）", "tv", ChartSource.TMDB_TRENDING, "tv", "tv_global_best_weekly"),
        DiscoverChartDef("tmdb_trending_movie", "TMDB 本周热门电影", "全球电影热度趋势（需 TMDB Key）", "movie", ChartSource.TMDB_TRENDING, "movie", "movie_weekly_best"),
    ) + platforms.map { p ->
        DiscoverChartDef(
            id = "platform_${p.id}",
            title = "${p.name} 热门剧集",
            subtitle = "按 ${p.name} 出品网络筛选的热门剧集",
            kind = "tv",
            source = ChartSource.TMDB_NETWORK,
            remoteId = p.tmdbNetworkId.toString(),
            fallbackRemoteId = p.fallbackDoubanId,
        )
    }

    fun findChart(id: String): DiscoverChartDef? = allCharts.firstOrNull { it.id == id }
}

/** 发现页数据仓库：豆瓣 Rexxar 无需 Key 即可使用；配置了 TMDB Key 后解锁流媒体平台榜与全球趋势。 */
object DiscoverRepository {

    private const val CACHE_TTL_MS = 15 * 60_000L
    private const val DOUBAN_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private data class CacheEntry<T>(val value: T, val expiresAt: Long)

    private val listCache = ConcurrentHashMap<String, CacheEntry<List<DiscoverItem>>>()

    private val tmdbGenreMap = mapOf(
        12 to "冒险", 14 to "奇幻", 16 to "动画", 18 to "剧情", 27 to "恐怖", 28 to "动作",
        35 to "喜剧", 36 to "历史", 37 to "西部", 53 to "惊悚", 80 to "犯罪", 99 to "纪录片",
        878 to "科幻", 9648 to "悬疑", 10402 to "音乐", 10749 to "爱情", 10751 to "家庭",
        10752 to "战争", 10759 to "动作冒险", 10762 to "儿童", 10763 to "新闻", 10764 to "真人秀",
        10765 to "科幻奇幻", 10766 to "肥皂剧", 10767 to "脱口秀", 10768 to "战争政治", 10770 to "电视电影",
    )

    private val countryNameMap = mapOf(
        "US" to "美国", "JP" to "日本", "KR" to "韩国", "CN" to "中国大陆", "TW" to "中国台湾",
        "HK" to "中国香港", "GB" to "英国", "FR" to "法国", "DE" to "德国", "CA" to "加拿大",
        "ES" to "西班牙", "IT" to "意大利", "TH" to "泰国", "IN" to "印度", "AU" to "澳大利亚",
    )

    fun clearCache() {
        listCache.clear()
    }

    /** 加载发现页首页的榜单行；优先展示豆瓣免 Key 榜单，TMDB Key 存在时追加趋势榜。 */
    fun loadHomeSections(settings: AppSettings, forceRefresh: Boolean = false): List<DiscoverHomeSection> {
        val charts = buildList {
            addAll(DiscoverCharts.homeCharts)
            if (settings.tmdbKey.isNotBlank()) {
                DiscoverCharts.findChart("tmdb_trending_tv")?.let { add(it) }
                DiscoverCharts.findChart("tmdb_trending_movie")?.let { add(it) }
            }
        }
        return charts.mapNotNull { chart ->
            val items = runCatching {
                fetchDoubanOrTmdbChart(chart, settings, page = 1, count = 18, forceRefresh = forceRefresh).items
            }.getOrDefault(emptyList())
            if (items.isEmpty()) null else DiscoverHomeSection(chart, items)
        }
    }

    /** 加载指定榜单页面；当 TMDB Key 缺失时自动回落到对应的豆瓣榜单。 */
    fun loadChartPage(
        chart: DiscoverChartDef,
        settings: AppSettings,
        page: Int = 1,
        count: Int = 24,
        forceRefresh: Boolean = false,
    ): DiscoverChartPage = fetchDoubanOrTmdbChart(chart, settings, page, count, forceRefresh)

    private fun fetchDoubanOrTmdbChart(
        chart: DiscoverChartDef,
        settings: AppSettings,
        page: Int,
        count: Int,
        forceRefresh: Boolean,
    ): DiscoverChartPage {
        val hasTmdb = settings.tmdbKey.isNotBlank()
        return when (chart.source) {
            ChartSource.DOUBAN_COLLECTION -> {
                val start = ((page - 1).coerceAtLeast(0)) * count
                val items = fetchDoubanCollection(chart.remoteId, chart.kind, start, count, forceRefresh)
                DiscoverChartPage(chart, items)
            }
            ChartSource.TMDB_NETWORK -> {
                if (hasTmdb) {
                    val items = fetchTmdbDiscoverNetwork(chart.remoteId, settings, page, forceRefresh)
                    DiscoverChartPage(chart, items)
                } else {
                    val fallbackId = chart.fallbackRemoteId ?: "tv_global_best_weekly"
                    val start = ((page - 1).coerceAtLeast(0)) * count
                    val items = fetchDoubanCollection(fallbackId, "tv", start, count, forceRefresh)
                    DiscoverChartPage(
                        chart = chart,
                        items = items,
                        note = "未填写 TMDB API Key，当前暂展示豆瓣海外口碑剧集榜；在「我的 → 设置 → 影视信息」填写 TMDB Key 后可拉取该平台实时榜单。",
                        usedFallback = true,
                    )
                }
            }
            ChartSource.TMDB_TRENDING -> {
                if (hasTmdb) {
                    val items = fetchTmdbTrending(chart.remoteId, settings, page, forceRefresh)
                    DiscoverChartPage(chart, items)
                } else {
                    val fallbackId = chart.fallbackRemoteId ?: "movie_weekly_best"
                    val start = ((page - 1).coerceAtLeast(0)) * count
                    val items = fetchDoubanCollection(fallbackId, chart.kind, start, count, forceRefresh)
                    DiscoverChartPage(
                        chart = chart,
                        items = items,
                        note = "未填写 TMDB API Key，当前暂展示豆瓣对应口碑榜。",
                        usedFallback = true,
                    )
                }
            }
        }
    }

    /** 发现页搜索：同时使用 TMDB（若有 Key）与豆瓣搜索并去重合并。 */
    fun search(settings: AppSettings, query: String): List<DiscoverItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val cacheKey = "search:${settings.tmdbKey.isNotBlank()}:$q"
        if (cached(cacheKey) != null) return cached(cacheKey)!!

        val results = mutableListOf<DiscoverItem>()
        if (settings.tmdbKey.isNotBlank()) {
            runCatching { results += fetchTmdbSearch(q, settings) }
        }
        if (settings.doubanEnabled) {
            runCatching { results += fetchDoubanSearch(q) }
        }
        val merged = mergeSearchResults(results)
        putCache(cacheKey, merged)
        return merged
    }

    private fun mergeSearchResults(raw: List<DiscoverItem>): List<DiscoverItem> {
        if (raw.isEmpty()) return emptyList()
        val out = mutableListOf<DiscoverItem>()
        val seen = mutableMapOf<String, Int>()
        for (item in raw) {
            val normTitle = item.title.lowercase().replace(Regex("[\\s·・:：\\-—_!！?？]"), "")
            val dedupKey = "${item.kind}:$normTitle:${item.year ?: 0}"
            val existingIdx = seen[dedupKey]
            if (existingIdx == null) {
                seen[dedupKey] = out.size
                out += item
            } else {
                val cur = out[existingIdx]
                out[existingIdx] = cur.copy(
                    posterUrl = cur.posterUrl ?: item.posterUrl,
                    backdropUrl = cur.backdropUrl ?: item.backdropUrl,
                    overview = cur.overview.ifBlank { item.overview },
                    tmdbId = cur.tmdbId ?: item.tmdbId,
                    doubanId = cur.doubanId ?: item.doubanId,
                    imdbId = cur.imdbId ?: item.imdbId,
                    rating = cur.rating ?: item.rating,
                    ratingLabel = cur.ratingLabel ?: item.ratingLabel,
                )
            }
        }
        return out
    }

    private fun fetchDoubanCollection(
        collectionId: String,
        defaultKind: String,
        start: Int,
        count: Int,
        forceRefresh: Boolean,
    ): List<DiscoverItem> {
        val cacheKey = "douban_col:$collectionId:$start:$count"
        if (!forceRefresh) cached(cacheKey)?.let { return it }
        val url = "https://m.douban.com/rexxar/api/v2/subject_collection/${enc(collectionId)}/items?os=android&for_mobile=1&start=$start&count=$count"
        val body = httpGet(
            url = url,
            headers = mapOf(
                "User-Agent" to DOUBAN_UA,
                "Referer" to "https://m.douban.com/movie/",
            ),
        )
        val parsed = parseDoubanCollectionJson(body, defaultKind)
        putCache(cacheKey, parsed)
        return parsed
    }

    private fun fetchDoubanSearch(query: String): List<DiscoverItem> {
        val url = "https://m.douban.com/rexxar/api/v2/search?q=${enc(query)}&type=movie&count=20"
        val body = httpGet(
            url = url,
            headers = mapOf(
                "User-Agent" to DOUBAN_UA,
                "Referer" to "https://m.douban.com/search",
            ),
        )
        return parseDoubanSearchJson(body)
    }

    private fun fetchTmdbDiscoverNetwork(
        networkId: String,
        settings: AppSettings,
        page: Int,
        forceRefresh: Boolean,
    ): List<DiscoverItem> {
        val cacheKey = "tmdb_net:$networkId:$page:${settings.tmdbBaseUrl}"
        if (!forceRefresh) cached(cacheKey)?.let { return it }
        val base = settings.tmdbBaseUrl.ifBlank { "https://api.themoviedb.org/3" }.trimEnd('/')
        val url = "$base/discover/tv?language=zh-CN&sort_by=popularity.desc&with_networks=${enc(networkId)}&page=$page"
        val body = tmdbGet(url, settings.tmdbKey)
        val parsed = parseTmdbListJson(body, fallbackKind = "tv", imageBase = settings.tmdbImageBase)
        putCache(cacheKey, parsed)
        return parsed
    }

    private fun fetchTmdbTrending(
        mediaType: String,
        settings: AppSettings,
        page: Int,
        forceRefresh: Boolean,
    ): List<DiscoverItem> {
        val cacheKey = "tmdb_trend:$mediaType:$page:${settings.tmdbBaseUrl}"
        if (!forceRefresh) cached(cacheKey)?.let { return it }
        val base = settings.tmdbBaseUrl.ifBlank { "https://api.themoviedb.org/3" }.trimEnd('/')
        val url = "$base/trending/$mediaType/week?language=zh-CN&page=$page"
        val body = tmdbGet(url, settings.tmdbKey)
        val parsed = parseTmdbListJson(body, fallbackKind = mediaType, imageBase = settings.tmdbImageBase)
        putCache(cacheKey, parsed)
        return parsed
    }

    private fun fetchTmdbSearch(query: String, settings: AppSettings): List<DiscoverItem> {
        val base = settings.tmdbBaseUrl.ifBlank { "https://api.themoviedb.org/3" }.trimEnd('/')
        val url = "$base/search/multi?language=zh-CN&include_adult=false&query=${enc(query)}"
        val body = tmdbGet(url, settings.tmdbKey)
        return parseTmdbListJson(body, fallbackKind = "movie", imageBase = settings.tmdbImageBase)
    }

    private fun tmdbGet(url: String, rawKey: String): String {
        val key = rawKey.trim()
        val isV4Token = key.startsWith("eyJ") && key.length > 40
        val finalUrl = if (isV4Token) url else "$url${if ('?' in url) '&' else '?'}api_key=${enc(key)}"
        val headers = buildMap {
            put("Accept", "application/json")
            if (isV4Token) put("Authorization", "Bearer $key")
        }
        return httpGet(finalUrl, headers)
    }

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        return cloudHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("请求失败 HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    private fun cached(key: String): List<DiscoverItem>? {
        val hit = listCache[key] ?: return null
        if (System.currentTimeMillis() > hit.expiresAt) {
            listCache.remove(key)
            return null
        }
        return hit.value
    }

    private fun putCache(key: String, value: List<DiscoverItem>) {
        if (value.isNotEmpty()) {
            listCache[key] = CacheEntry(value, System.currentTimeMillis() + CACHE_TTL_MS)
        }
    }

    /** 解析豆瓣 Rexxar `subject_collection/{id}/items` JSON。公开以便单元测试直接覆盖。 */
    fun parseDoubanCollectionJson(jsonText: String, defaultKind: String = "movie"): List<DiscoverItem> {
        if (jsonText.isBlank()) return emptyList()
        val root = JSONObject(jsonText)
        val arr = root.optJSONArray("subject_collection_items") ?: return emptyList()
        return arr.objects().mapNotNull { obj -> parseDoubanSubject(obj, defaultKind) }
    }

    /** 解析豆瓣 Rexxar `/search` JSON（subjects.items[].target）。 */
    fun parseDoubanSearchJson(jsonText: String): List<DiscoverItem> {
        if (jsonText.isBlank()) return emptyList()
        val root = JSONObject(jsonText)
        val items = root.optJSONObject("subjects")?.optJSONArray("items") ?: return emptyList()
        return items.objects().mapNotNull { entry ->
            val target = entry.optJSONObject("target") ?: return@mapNotNull null
            val kind = when (entry.optString("target_type").ifBlank { target.optString("type") }) {
                "tv" -> "tv"
                "movie" -> "movie"
                else -> "movie"
            }
            parseDoubanSubject(target, kind)
        }
    }

    private fun parseDoubanSubject(obj: JSONObject, defaultKind: String): DiscoverItem? {
        val doubanId = obj.optString("id").trim()
        val title = obj.optString("title").trim()
        if (doubanId.isEmpty() || title.isEmpty()) return null
        val kind = when (obj.optString("type").lowercase()) {
            "tv" -> "tv"
            "movie" -> "movie"
            else -> defaultKind
        }
        val pic = obj.optJSONObject("pic")
        val cover = obj.optJSONObject("cover")
        val rawPoster = pic?.optString("large")?.takeIf { it.startsWith("http") }
            ?: pic?.optString("normal")?.takeIf { it.startsWith("http") }
            ?: cover?.optString("url")?.takeIf { it.startsWith("http") }
            ?: obj.optString("cover_url").takeIf { it.startsWith("http") }
        val posterUrl = rawPoster?.let {
            if (it.startsWith("http://") && it.contains(".doubanio.com/")) "https://" + it.removePrefix("http://") else it
        }
        val subtitle = obj.optString("card_subtitle").trim()
        val parsed = parseDoubanSubtitle(subtitle)
        val year = obj.optString("year").toIntOrNull() ?: parsed.year
        val epRaw = obj.optString("episodes_info").trim().takeIf { it.isNotEmpty() }
        val ongoing = when {
            epRaw == null -> null
            epRaw.contains("更新至") || epRaw.contains("连载") -> true
            epRaw.contains("全") || epRaw.contains("共") -> false
            else -> null
        }
        val episodeCount = obj.optInt("episodes_count", 0).takeIf { it > 0 }
        val episodeInfo = epRaw ?: episodeCount?.let { "共${it}集" }
        val ratingObj = obj.optJSONObject("rating")
        val ratingVal = ratingObj?.optDouble("value", 0.0)?.takeIf { !it.isNaN() && it > 0.0 }
        val voteCount = ratingObj?.optInt("count", 0) ?: 0
        val overview = obj.optJSONObject("honor_infos")?.optString("title")
            ?: obj.optString("recommendation_reason").trim()
        return DiscoverItem(
            id = "douban:$doubanId",
            kind = kind,
            title = title,
            year = year,
            overview = overview,
            posterUrl = posterUrl,
            genres = parsed.genres,
            countries = parsed.countries,
            rating = ratingVal,
            ratingLabel = if (ratingVal != null) "豆瓣" else null,
            voteCount = voteCount,
            episodeInfo = episodeInfo,
            ongoing = ongoing,
            doubanId = doubanId,
        )
    }

    private data class ParsedSubtitle(val year: Int?, val countries: List<String>, val genres: List<String>)

    private fun parseDoubanSubtitle(subtitle: String): ParsedSubtitle {
        if (subtitle.isBlank()) return ParsedSubtitle(null, emptyList(), emptyList())
        val parts = subtitle.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        val year = parts.firstOrNull { Regex("^\\d{4}$").matches(it) }?.toIntOrNull()
        val nonYear = parts.filterNot { Regex("^\\d{4}$").matches(it) }
        val countries = nonYear.getOrNull(0)?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
        val genres = nonYear.getOrNull(1)?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
        return ParsedSubtitle(year, countries, genres)
    }

    /** 解析 TMDB `/discover/...`、`/trending/...` 或 `/search/multi` JSON。 */
    fun parseTmdbListJson(jsonText: String, fallbackKind: String, imageBase: String): List<DiscoverItem> {
        if (jsonText.isBlank()) return emptyList()
        val root = JSONObject(jsonText)
        val arr = root.optJSONArray("results") ?: return emptyList()
        val imgBase = imageBase.ifBlank { "https://image.tmdb.org/t/p" }.trimEnd('/')
        return arr.objects().mapNotNull { obj ->
            val mediaType = obj.optString("media_type").lowercase().ifBlank { fallbackKind }
            if (mediaType != "movie" && mediaType != "tv") return@mapNotNull null
            val tmdbId = obj.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
            val title = obj.optString("title").ifBlank { obj.optString("name") }.trim()
            if (title.isEmpty()) return@mapNotNull null
            val origTitle = obj.optString("original_title").ifBlank { obj.optString("original_name") }.trim()
            val dateStr = obj.optString("release_date").ifBlank { obj.optString("first_air_date") }
            val year = dateStr.take(4).toIntOrNull()
            val posterPath = obj.optString("poster_path").takeIf { it.startsWith("/") }
            val backdropPath = obj.optString("backdrop_path").takeIf { it.startsWith("/") }
            val rating = obj.optDouble("vote_average", 0.0).takeIf { !it.isNaN() && it > 0.0 }
            val voteCount = obj.optInt("vote_count", 0)
            val genreIds = obj.optJSONArray("genre_ids")?.let { ga ->
                (0 until ga.length()).mapNotNull { tmdbGenreMap[ga.optInt(it)] }
            } ?: emptyList()
            val countries = obj.optJSONArray("origin_country").strings().map { code ->
                countryNameMap[code.uppercase()] ?: code
            }
            DiscoverItem(
                id = "tmdb:$mediaType:$tmdbId",
                kind = mediaType,
                title = title,
                originalTitle = origTitle.takeIf { it != title }.orEmpty(),
                year = year,
                overview = obj.optString("overview").trim(),
                posterUrl = posterPath?.let { "$imgBase/w500$it" },
                backdropUrl = backdropPath?.let { "$imgBase/w1280$it" },
                genres = genreIds,
                countries = countries,
                rating = rating,
                ratingLabel = if (rating != null) "TMDB" else null,
                voteCount = voteCount,
                tmdbId = tmdbId,
            )
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

enum class SearchCategory(val label: String) {
    RE0("影巢"),
    PAN("网盘搜索"),
    MAGNET("磁力搜索"),
    TELEGRAM("Telegram"),
}

/**
 * 外部资源搜索站点模板。
 * 模板支持占位符：`{q}`（URL 编码关键词）、`{raw}`（原样关键词）、`{tmdb}`、`{imdb}`、`{kind}`（movie/tv）。
 */
data class SearchSource(
    val id: String,
    val name: String,
    val category: SearchCategory,
    val urlTemplate: String,
    val description: String = "",
    val enabled: Boolean = true,
    val builtin: Boolean = false,
) {
    fun buildUrl(query: String, item: DiscoverItem? = null): String {
        val clean = query.trim()
        // 使用两参数重载保持对 Android API 23 的兼容；搜索站普遍将空格编码为 %20。
        val encoded = URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
        return urlTemplate
            .replace("{q}", encoded)
            .replace("{raw}", clean)
            .replace("{tmdb}", item?.tmdbId?.toString().orEmpty())
            .replace("{imdb}", item?.imdbId.orEmpty())
            .replace("{kind}", item?.kind ?: "movie")
    }
}

/** 发现页搜索站与搜索历史的本地存储（SharedPreferences 名称为 discover_search，已接入备份导出）。 */
object DiscoverSearchStore {

    private const val PREFS = "discover_search"
    private const val KEY_CUSTOM_SOURCES = "custom_sources"
    private const val KEY_DISABLED_BUILTIN = "disabled_builtin"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 20

    val builtinSources: List<SearchSource> = listOf(
        SearchSource(
            id = "re0_search",
            name = "影巢 (re0.me) 搜索",
            category = SearchCategory.RE0,
            urlTemplate = "https://re0.me/search?q={q}",
            description = "在影巢检索片名并查看网友分享的网盘/磁力资源",
            builtin = true,
        ),
        SearchSource(
            id = "re0_home",
            name = "影巢 (re0.me) 首页 / 榜单",
            category = SearchCategory.RE0,
            urlTemplate = "https://re0.me/",
            description = "直接访问影巢主页与流媒体片单",
            builtin = true,
        ),
        SearchSource(
            id = "pan_pansearch",
            name = "PanSearch（全网网盘）",
            category = SearchCategory.PAN,
            urlTemplate = "https://www.pansearch.me/search?keyword={q}",
            description = "阿里、夸克、迅雷、百度网盘聚合搜索",
            builtin = true,
        ),
        SearchSource(
            id = "pan_hunhepan",
            name = "混合盘（多网盘聚合）",
            category = SearchCategory.PAN,
            urlTemplate = "https://hunhepan.com/search?q={q}",
            description = "多网盘分享链接检索",
            builtin = true,
        ),
        SearchSource(
            id = "pan_qupansou",
            name = "趣盘搜（夸克 / 阿里 / 115）",
            category = SearchCategory.PAN,
            urlTemplate = "https://www.qupansou.com/s?q={q}",
            description = "夸克、阿里云盘、115 等网盘资源检索",
            builtin = true,
        ),
        SearchSource(
            id = "pan_yiso",
            name = "易搜（网盘精准搜索）",
            category = SearchCategory.PAN,
            urlTemplate = "https://yiso.fun/info?searchKey={q}",
            description = "阿里、夸克网盘资源检索",
            builtin = true,
        ),
        SearchSource(
            id = "mag_btdig",
            name = "BTDigg（DHT 磁力）",
            category = SearchCategory.MAGNET,
            urlTemplate = "https://btdig.com/search?q={q}",
            description = "DHT 网络磁力链接全文检索",
            builtin = true,
        ),
        SearchSource(
            id = "mag_bt4g",
            name = "BT4G（综合磁力）",
            category = SearchCategory.MAGNET,
            urlTemplate = "https://bt4gprx.com/search?q={q}",
            description = "常见影视种子与磁力聚合",
            builtin = true,
        ),
        SearchSource(
            id = "mag_nyaa",
            name = "Nyaa（动漫 / 番剧磁力）",
            category = SearchCategory.MAGNET,
            urlTemplate = "https://nyaa.si/?f=0&c=0_0&q={q}",
            description = "适合日漫、OVA 与生肉/外挂字幕资源",
            builtin = true,
        ),
        SearchSource(
            id = "mag_1337x",
            name = "1337x（国际影视磁力）",
            category = SearchCategory.MAGNET,
            urlTemplate = "https://1337x.to/search/{q}/1/",
            description = "美剧、英剧、外语电影高清片源",
            builtin = true,
        ),
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun sources(ctx: Context): List<SearchSource> {
        val p = prefs(ctx)
        val disabled = parseStringList(p.getString(KEY_DISABLED_BUILTIN, null)).toSet()
        val builtins = builtinSources.map { it.copy(enabled = it.id !in disabled) }
        val custom = parseCustomSources(p.getString(KEY_CUSTOM_SOURCES, null))
        return builtins + custom
    }

    fun setSourceEnabled(ctx: Context, sourceId: String, enabled: Boolean) {
        val p = prefs(ctx)
        if (builtinSources.any { it.id == sourceId }) {
            val disabled = parseStringList(p.getString(KEY_DISABLED_BUILTIN, null)).toMutableSet()
            if (enabled) disabled.remove(sourceId) else disabled.add(sourceId)
            p.edit().putString(KEY_DISABLED_BUILTIN, encodeStringList(disabled.toList())).apply()
        } else {
            val updated = parseCustomSources(p.getString(KEY_CUSTOM_SOURCES, null)).map {
                if (it.id == sourceId) it.copy(enabled = enabled) else it
            }
            p.edit().putString(KEY_CUSTOM_SOURCES, encodeCustomSources(updated)).apply()
        }
    }

    fun addCustomSource(ctx: Context, name: String, category: SearchCategory, urlTemplate: String): SearchSource {
        val source = SearchSource(
            id = "custom_${System.currentTimeMillis()}",
            name = name.trim(),
            category = category,
            urlTemplate = urlTemplate.trim(),
            description = "自定义搜索站",
            enabled = true,
            builtin = false,
        )
        val p = prefs(ctx)
        val list = parseCustomSources(p.getString(KEY_CUSTOM_SOURCES, null)) + source
        p.edit().putString(KEY_CUSTOM_SOURCES, encodeCustomSources(list)).apply()
        return source
    }

    fun removeCustomSource(ctx: Context, sourceId: String) {
        val p = prefs(ctx)
        val list = parseCustomSources(p.getString(KEY_CUSTOM_SOURCES, null)).filterNot { it.id == sourceId }
        p.edit().putString(KEY_CUSTOM_SOURCES, encodeCustomSources(list)).apply()
    }

    fun history(ctx: Context): List<String> =
        parseStringList(prefs(ctx).getString(KEY_HISTORY, null))

    fun recordHistory(ctx: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val next = (listOf(q) + history(ctx).filterNot { it.equals(q, ignoreCase = true) }).take(MAX_HISTORY)
        prefs(ctx).edit().putString(KEY_HISTORY, encodeStringList(next)).apply()
    }

    fun clearHistory(ctx: Context) {
        prefs(ctx).edit().remove(KEY_HISTORY).apply()
    }

    private fun parseStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
        }.getOrDefault(emptyList())
    }

    private fun encodeStringList(list: List<String>): String =
        JSONArray().apply { list.forEach { put(it) } }.toString()

    internal fun parseCustomSources(raw: String?): List<SearchSource> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            JSONArray(raw).objects().mapNotNull { o ->
                val id = o.optString("id")
                val name = o.optString("name")
                val tpl = o.optString("urlTemplate")
                val cat = runCatching { SearchCategory.valueOf(o.optString("category")) }.getOrNull()
                    ?: SearchCategory.PAN
                if (id.isBlank() || name.isBlank() || tpl.isBlank()) null
                else SearchSource(
                    id = id,
                    name = name,
                    category = cat,
                    urlTemplate = tpl,
                    description = o.optString("description"),
                    enabled = o.optBoolean("enabled", true),
                    builtin = false,
                )
            }
        }.getOrDefault(emptyList())
    }

    internal fun encodeCustomSources(list: List<SearchSource>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("category", s.category.name)
                put("urlTemplate", s.urlTemplate)
                put("description", s.description)
                put("enabled", s.enabled)
            })
        }
        return arr.toString()
    }
}

/** 磁力链接与常见网盘分享链接的识别、格式化工具。 */
object MagnetLinks {
    private val MAGNET_REGEX = Regex("""magnet:\?[^\s"'<>()\[\]{}]+""", RegexOption.IGNORE_CASE)
    private val HEX_HASH_REGEX = Regex("""^[0-9a-fA-F]{40}$""")
    private val BASE32_HASH_REGEX = Regex("""^[A-Z2-7]{32}$""", RegexOption.IGNORE_CASE)
    private val CLOUD_SHARE_REGEX = Regex(
        """https?://(?:pan\.quark\.cn|115\.com|115cdn\.com|anxia\.com|mypikpak\.com|www\.alipan\.com|alipan\.com|www\.aliyundrive\.com|aliyundrive\.com|pan\.baidu\.com|cloud\.189\.cn|pan\.xunlei\.com|drive\.uc\.cn)/(?:s|t)/[A-Za-z0-9_-]+[^\s"'<>()\[\]{}]*""",
        RegexOption.IGNORE_CASE,
    )

    fun extractMagnets(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return MAGNET_REGEX.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', '。', '，', '；') }
            .distinct()
            .toList()
    }

    fun normalizeMagnet(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("magnet:?", ignoreCase = true)) return trimmed
        if (HEX_HASH_REGEX.matches(trimmed)) return "magnet:?xt=urn:btih:${trimmed.lowercase()}"
        if (BASE32_HASH_REGEX.matches(trimmed)) return "magnet:?xt=urn:btih:${trimmed.uppercase()}"
        return null
    }

    fun displayName(magnet: String): String {
        val qIndex = magnet.indexOf('?')
        if (qIndex >= 0) {
            val params = magnet.substring(qIndex + 1).split('&')
            for (p in params) {
                if (p.startsWith("dn=", ignoreCase = true)) {
                    val encoded = p.substring(3)
                    val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
                    if (decoded.isNotBlank()) return decoded
                }
            }
            for (p in params) {
                if (p.startsWith("xt=urn:btih:", ignoreCase = true)) {
                    return "BTIH: ${p.substring(12).take(16)}…"
                }
            }
        }
        return magnet.take(40)
    }

    fun extractCloudShareUrls(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return CLOUD_SHARE_REGEX.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', '。', '，', '；') }
            .distinct()
            .toList()
    }
}

/** 全局待处理的磁力链接（网页浏览器点击 magnet: 链接时弹出磁力操作/离线下载弹窗）。 */
object MagnetLaunch {
    var pending: String? by mutableStateOf(null)
}
