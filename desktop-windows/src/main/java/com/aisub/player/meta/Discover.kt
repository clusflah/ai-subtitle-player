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
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** 发现页条目：可来自豆瓣片单、TMDB 发现页 / 流媒体榜 / 搜索，或音乐发现榜单 / 新音乐（kind == "music"）。 */
data class DiscoverItem(
    val id: String,
    val kind: String, // "movie" | "tv" | "music"
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
    val platforms: List<PlatformSource> = emptyList(),
    /** 音乐发现专用字段（影视条目保持默认空值） */
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val neteaseId: String? = null,
    val appleUrl: String? = null,
) {
    val isMusicItem: Boolean get() = kind == "music"

    /** 转为元信息流水线查询参数；详情页用它继续补齐演员、导演、Logo、来源平台与多平台评分。 */
    fun toMetaQuery(): MetaQuery = MetaQuery(
        titles = listOfNotNull(
            title.takeIf { it.isNotBlank() },
            originalTitle.takeIf { it.isNotBlank() && it != title },
        ).distinct(),
        year = year,
        kind = kind,
        tmdbId = tmdbId?.toString(),
        imdbId = imdbId,
        doubanId = doubanId,
    )

    /** 副标题一行摘要：影视形如「2026 · 日本 · 12集」；歌曲形如「歌手 · 专辑」。 */
    fun subtitleLine(): String = if (isMusicItem) {
        buildList {
            artist.takeIf { it.isNotBlank() }?.let { add(it) }
            album.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }?.let { add(it) }
            if (isEmpty() && year != null) add(year.toString())
        }.joinToString(" · ")
    } else {
        buildList {
            year?.let { add(it.toString()) }
            countries.firstOrNull()?.takeIf { it.isNotBlank() }?.let { add(it) }
            when {
                !episodeInfo.isNullOrBlank() -> add(episodeInfo)
                genres.isNotEmpty() -> add(genres.take(2).joinToString(" / "))
            }
        }.joinToString(" · ")
    }
}

/** 流媒体与影视播出平台规范化目录（名称归一、品牌配色与原平台官方播放/检索跳转地址）。 */
object PlatformCatalog {

    data class Spec(
        val canonical: String,
        val badge: String,
        val accentHex: Long,
        val searchUrlTemplate: String,
    )

    private val specs: List<Spec> = listOf(
        Spec("Netflix", "N", 0xFFE50914, "https://www.netflix.com/search?q={q}"),
        Spec("HBO", "HBO", 0xFF7B2CBF, "https://www.max.com/search?q={q}"),
        Spec("Apple TV+", "tv+", 0xFF2B2F3A, "https://tv.apple.com/search?term={q}"),
        Spec("Disney+", "D+", 0xFF0063E5, "https://www.disneyplus.com/search/{q}"),
        Spec("Prime Video", "PRIME", 0xFF00A8E1, "https://www.primevideo.com/search/ref=atv_nb_sr?phrase={q}"),
        Spec("腾讯视频", "腾讯", 0xFFFF6A00, "https://m.v.qq.com/search.html?keyWord={q}"),
        Spec("爱奇艺", "爱奇艺", 0xFF00BE06, "https://m.iqiyi.com/search.html?key={q}"),
        Spec("优酷", "优酷", 0xFF148AFF, "https://so.youku.com/search_video/q_{q}"),
        Spec("哔哩哔哩", "B站", 0xFFFB7299, "https://m.bilibili.com/search?keyword={q}"),
        Spec("芒果TV", "芒果", 0xFFFF7A00, "https://m.mgtv.com/so/?k={q}"),
        Spec("咪咕视频", "咪咕", 0xFFE60012, "https://www.miguvideo.com/p/search/{q}"),
        Spec("Hulu", "Hulu", 0xFF1CE783, "https://www.hulu.com/search?q={q}"),
        Spec("Paramount+", "P+", 0xFF0064FF, "https://www.paramountplus.com/search/?q={q}"),
        Spec("Peacock", "Peacock", 0xFF1E222B, "https://www.peacocktv.com/watch/search?q={q}"),
        Spec("YouTube", "YT", 0xFFFF0000, "https://m.youtube.com/results?search_query={q}"),
    )

    fun canonicalName(raw: String): String {
        val t = raw.trim()
        val low = t.lowercase()
        return when {
            low.contains("netflix") || t.contains("奈飞") || t.contains("网飞") -> "Netflix"
            low == "hbo" || low == "max" || low.contains("hbo ") || low.contains("hbomax") -> "HBO"
            low.contains("apple tv") -> "Apple TV+"
            low.contains("disney") || t.contains("迪士尼") -> "Disney+"
            low.contains("prime video") || low.contains("amazon") -> "Prime Video"
            t.contains("腾讯视频") || low == "tencent video" || low == "wetv" -> "腾讯视频"
            t.contains("爱奇艺") || low.contains("iqiyi") -> "爱奇艺"
            t.contains("优酷") || low.contains("youku") -> "优酷"
            t.contains("哔哩") || low.contains("bilibili") || t.equals("B站", ignoreCase = true) -> "哔哩哔哩"
            t.contains("芒果") || low.contains("mgtv") || low.contains("mango tv") -> "芒果TV"
            t.contains("湖南卫视") || low == "hunan television" || low == "hunan tv" -> "湖南卫视"
            t.contains("咪咕") || low.contains("migu") -> "咪咕视频"
            low.contains("hulu") -> "Hulu"
            low.contains("paramount") -> "Paramount+"
            low.contains("peacock") -> "Peacock"
            low.contains("youtube") -> "YouTube"
            else -> t
        }
    }

    fun detectFromUrl(url: String): String? {
        val low = url.lowercase()
        return when {
            "netflix.com" in low -> "Netflix"
            "max.com" in low || "hbo.com" in low -> "HBO"
            "tv.apple.com" in low -> "Apple TV+"
            "disneyplus.com" in low -> "Disney+"
            "primevideo.com" in low || "amazon.com/gp/video" in low -> "Prime Video"
            "v.qq.com" in low -> "腾讯视频"
            "iqiyi.com" in low -> "爱奇艺"
            "youku.com" in low -> "优酷"
            "bilibili.com" in low -> "哔哩哔哩"
            "mgtv.com" in low -> "芒果TV"
            "miguvideo.com" in low -> "咪咕视频"
            "hulu.com" in low -> "Hulu"
            else -> null
        }
    }

    fun specFor(name: String): Spec? {
        val c = canonicalName(name)
        return specs.firstOrNull { it.canonical.equals(c, ignoreCase = true) }
    }

    fun accentHex(name: String): Long = specFor(name)?.accentHex ?: 0xFF3949AB

    fun resolvePlayUrl(platform: PlatformSource, title: String): String {
        platform.url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { return it }
        val q = URLEncoder.encode(title.trim(), "UTF-8").replace("+", "%20")
        val spec = specFor(platform.name)
        return spec?.searchUrlTemplate?.replace("{q}", q)
            ?: "https://www.bing.com/search?q=${URLEncoder.encode("${title.trim()} ${platform.name} 在线观看", "UTF-8")}"
    }

    /** 兜底主流正版平台列表（当条目为院线或元数据暂未标注流媒体来源时，供原平台播放面板选用）。 */
    fun fallbackPlatforms(): List<PlatformSource> = listOf(
        PlatformSource("腾讯视频"),
        PlatformSource("爱奇艺"),
        PlatformSource("优酷"),
        PlatformSource("哔哩哔哩"),
        PlatformSource("Netflix"),
    )

    fun merge(first: List<PlatformSource>, second: List<PlatformSource> = emptyList()): List<PlatformSource> {
        val map = LinkedHashMap<String, PlatformSource>()
        for (p in first + second) {
            val c = canonicalName(p.name)
            if (c.isBlank()) continue
            val existing = map[c]
            val cleanUrl = p.url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            if (existing == null) {
                map[c] = PlatformSource(c, cleanUrl)
            } else if (existing.url == null && cleanUrl != null) {
                map[c] = PlatformSource(c, cleanUrl)
            }
        }
        return map.values.toList()
    }
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
        StreamingPlatform("prime_video", "Prime Video", "PRIME", 0xFF00A8E1, 1024, "tv_american"),
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
    private const val HOME_CACHE_FILE = "discover_cache_v1.json"
    private const val DOUBAN_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private data class CacheEntry<T>(val value: T, val expiresAt: Long)

    private val listCache = ConcurrentHashMap<String, CacheEntry<List<DiscoverItem>>>()
    private val diskLock = Any()

    @Volatile
    private var loadedDiskDirPath: String? = null

    @Volatile
    private var cachedHomeConfigKey: String? = null

    @Volatile
    private var cachedHomeSections: List<DiscoverHomeSection> = emptyList()

    private val persistedHomeByConfig = ConcurrentHashMap<String, List<DiscoverHomeSection>>()

    @Volatile
    private var persistedLastHomeSections: List<DiscoverHomeSection> = emptyList()

    private val persistedChartPages = ConcurrentHashMap<String, DiscoverChartPage>()

    private val chartFirstPageCache = ConcurrentHashMap<String, DiscoverChartPage>()
    private val searchSnapshotCache = ConcurrentHashMap<String, List<DiscoverItem>>()
    private val sessionInitialRefreshConsumed = ConcurrentHashMap.newKeySet<String>()

    private val fetchPool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "voxtitle-discover-fetch").apply { isDaemon = true }
    }

    @Volatile
    var sessionSearchExpanded: Boolean = false
        private set

    @Volatile
    var sessionQueryInput: String = ""

    @Volatile
    var sessionAppliedQuery: String = ""

    fun setSessionSearchExpanded(expanded: Boolean) {
        sessionSearchExpanded = expanded
        if (!expanded) {
            sessionQueryInput = ""
            sessionAppliedQuery = ""
        }
    }

    private fun homeConfigKey(settings: AppSettings): String =
        "${settings.tmdbKey.trim()}|${settings.doubanEnabled}"

    private fun chartFirstPageKey(chart: DiscoverChartDef, settings: AppSettings): String =
        "${chart.id}|${settings.tmdbKey.isNotBlank()}|${settings.doubanEnabled}"

    private fun searchSnapshotKey(settings: AppSettings, query: String): String =
        "${settings.tmdbKey.isNotBlank()}|${settings.doubanEnabled}|${query.trim()}"

    /** 每个应用进程首次进入发现页时返回 true（用于触发一次后台自动刷新），同一次会话内后续返回不再重复刷新。 */
    fun consumeFirstVisitAutoRefresh(settings: AppSettings): Boolean =
        sessionInitialRefreshConsumed.add(homeConfigKey(settings))

    internal fun resetSessionForTest(clearInMemoryData: Boolean = false) {
        sessionInitialRefreshConsumed.clear()
        if (clearInMemoryData) {
            listCache.clear()
            cachedHomeConfigKey = null
            cachedHomeSections = emptyList()
            chartFirstPageCache.clear()
            searchSnapshotCache.clear()
            persistedHomeByConfig.clear()
            persistedLastHomeSections = emptyList()
            persistedChartPages.clear()
            synchronized(diskLock) {
                loadedDiskDirPath = null
            }
        }
    }

    /** 读取当前会话已加载过的或本地持久化保存的发现页首页快照，断网或冷启动时立即可见。 */
    fun peekHomeSections(settings: AppSettings, context: Context? = null): List<DiscoverHomeSection> =
        peekHomeSectionsFromDir(settings, context?.filesDir)

    internal fun peekHomeSectionsFromDir(settings: AppSettings, storageDir: File?): List<DiscoverHomeSection> {
        ensureDiskLoaded(storageDir)
        val cfgKey = homeConfigKey(settings)
        if (cachedHomeConfigKey == cfgKey && cachedHomeSections.isNotEmpty()) {
            return cachedHomeSections
        }
        persistedHomeByConfig[cfgKey]?.takeIf { it.isNotEmpty() }?.let { return it }
        return persistedLastHomeSections
    }

    /** 仅检查当前会话内存中的单榜单首屏快照。 */
    fun peekInMemoryChartFirstPage(chart: DiscoverChartDef, settings: AppSettings): DiscoverChartPage? =
        chartFirstPageCache[chartFirstPageKey(chart, settings)]

    /** 读取当前会话或持久化保存的单榜单首屏快照，从详情页退回或离线打开时直接复用。 */
    fun peekChartFirstPage(chart: DiscoverChartDef, settings: AppSettings, context: Context? = null): DiscoverChartPage? {
        ensureDiskLoaded(context?.filesDir)
        return chartFirstPageCache[chartFirstPageKey(chart, settings)] ?: persistedChartPages[chart.id]
    }

    /** 读取当前会话已搜索过的关键词结果快照，从详情页退回时免重复请求。 */
    fun peekSearchResults(settings: AppSettings, query: String): List<DiscoverItem>? {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return searchSnapshotCache[searchSnapshotKey(settings, q)]
    }

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

    /** 清理瞬时内存请求缓存（保留已持久化榜单与搜索兜底以外的新请求资格，确保手动刷新失败时不清空已有内容）。 */
    fun clearCache() {
        listCache.clear()
        cachedHomeConfigKey = null
        cachedHomeSections = emptyList()
        chartFirstPageCache.clear()
        searchSnapshotCache.clear()
    }

    /** 加载发现页首页的榜单行；优先展示豆瓣免 Key 榜单，TMDB Key 存在时追加趋势榜；离线或失败时自动保留先前持久化内容。 */
    fun loadHomeSections(
        settings: AppSettings,
        forceRefresh: Boolean = false,
        context: Context? = null,
    ): List<DiscoverHomeSection> = loadHomeSectionsInternal(
        settings = settings,
        forceRefresh = forceRefresh,
        storageDir = context?.filesDir,
    )

    internal fun loadHomeSectionsInternal(
        settings: AppSettings,
        forceRefresh: Boolean,
        storageDir: File?,
        chartFetcher: ((DiscoverChartDef, Boolean) -> List<DiscoverItem>)? = null,
    ): List<DiscoverHomeSection> {
        ensureDiskLoaded(storageDir)
        val cfgKey = homeConfigKey(settings)
        val previous = peekHomeSectionsFromDir(settings, storageDir)
        if (!forceRefresh && cachedHomeConfigKey == cfgKey && cachedHomeSections.isNotEmpty()) {
            return cachedHomeSections
        }
        val charts = buildList {
            addAll(DiscoverCharts.homeCharts)
            if (settings.tmdbKey.isNotBlank()) {
                DiscoverCharts.findChart("tmdb_trending_tv")?.let { add(it) }
                DiscoverCharts.findChart("tmdb_trending_movie")?.let { add(it) }
            }
        }
        val futures = charts.map { chart ->
            chart.id to CompletableFuture.supplyAsync({
                runCatching {
                    chartFetcher?.invoke(chart, forceRefresh)
                        ?: fetchDoubanOrTmdbChart(chart, settings, page = 1, count = 18, forceRefresh = forceRefresh).items
                }.getOrDefault(emptyList())
            }, fetchPool)
        }
        val freshMap = LinkedHashMap<String, List<DiscoverItem>>()
        for ((chartId, fut) in futures) {
            val items = runCatching { fut.get() }.getOrDefault(emptyList())
            if (items.isNotEmpty()) {
                freshMap[chartId] = items
            }
        }
        if (freshMap.isEmpty()) {
            return previous
        }
        val merged = charts.mapNotNull { chart ->
            val fresh = freshMap[chart.id]
            if (!fresh.isNullOrEmpty()) {
                DiscoverHomeSection(chart, fresh)
            } else {
                previous.firstOrNull { it.chart.id == chart.id }
            }
        }
        if (merged.isNotEmpty()) {
            cachedHomeSections = merged
            cachedHomeConfigKey = cfgKey
            persistedHomeByConfig[cfgKey] = merged
            persistedLastHomeSections = merged
            for ((chartId, freshItems) in freshMap) {
                DiscoverCharts.findChart(chartId)?.let { def ->
                    persistedChartPages[chartId] = DiscoverChartPage(def, freshItems)
                }
            }
            saveDiskCache(storageDir)
        }
        return merged
    }

    /** 加载指定榜单页面；当 TMDB Key 缺失时自动回落到对应的豆瓣榜单；首屏失败或为空时回退持久化缓存。 */
    fun loadChartPage(
        chart: DiscoverChartDef,
        settings: AppSettings,
        page: Int = 1,
        count: Int = 24,
        forceRefresh: Boolean = false,
        context: Context? = null,
    ): DiscoverChartPage {
        ensureDiskLoaded(context?.filesDir)
        val key = chartFirstPageKey(chart, settings)
        if (page == 1 && !forceRefresh) {
            chartFirstPageCache[key]?.let { return it }
        }
        val fetched = runCatching {
            fetchDoubanOrTmdbChart(chart, settings, page, count, forceRefresh)
        }.onSuccess { res ->
            if (page == 1 && res.items.isNotEmpty()) {
                chartFirstPageCache[key] = res
                persistedChartPages[chart.id] = res
                saveDiskCache(context?.filesDir)
            }
        }
        if (page == 1) {
            val res = fetched.getOrNull()
            if (res == null || res.items.isEmpty()) {
                persistedChartPages[chart.id]?.takeIf { it.items.isNotEmpty() }?.let { return it }
            }
        }
        return fetched.getOrThrow()
    }

    internal fun ensureDiskLoaded(storageDir: File?) {
        if (storageDir == null) return
        val path = storageDir.absolutePath
        if (loadedDiskDirPath == path) return
        synchronized(diskLock) {
            if (loadedDiskDirPath == path) return
            val file = File(storageDir, HOME_CACHE_FILE)
            if (file.isFile && file.length() > 0L) {
                runCatching {
                    val root = JSONObject(file.readText(Charsets.UTF_8))
                    root.optJSONArray("lastHome")?.let { arr ->
                        val parsed = (0 until arr.length()).mapNotNull { idx ->
                            arr.optJSONObject(idx)?.let(::decodeSectionJson)
                        }
                        if (parsed.isNotEmpty()) {
                            persistedLastHomeSections = parsed
                        }
                    }
                    root.optJSONObject("homeByConfig")?.let { obj ->
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val arr = obj.optJSONArray(k) ?: continue
                            val parsed = (0 until arr.length()).mapNotNull { idx ->
                                arr.optJSONObject(idx)?.let(::decodeSectionJson)
                            }
                            if (parsed.isNotEmpty()) {
                                persistedHomeByConfig[k] = parsed
                            }
                        }
                    }
                    root.optJSONObject("charts")?.let { obj ->
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val chartId = keys.next()
                            val pObj = obj.optJSONObject(chartId) ?: continue
                            decodeChartPageJson(pObj)?.let { page ->
                                persistedChartPages[chartId] = page
                            }
                        }
                    }
                }
            }
            loadedDiskDirPath = path
        }
    }

    private fun saveDiskCache(storageDir: File?) {
        if (storageDir == null) return
        synchronized(diskLock) {
            runCatching {
                if (!storageDir.exists()) storageDir.mkdirs()
                val root = JSONObject().apply {
                    put("version", 1)
                    put(
                        "lastHome",
                        JSONArray().apply {
                            persistedLastHomeSections.forEach { put(encodeSectionJson(it)) }
                        },
                    )
                    put(
                        "homeByConfig",
                        JSONObject().apply {
                            persistedHomeByConfig.forEach { (cfgKey, list) ->
                                put(cfgKey, JSONArray().apply { list.forEach { put(encodeSectionJson(it)) } })
                            }
                        },
                    )
                    put(
                        "charts",
                        JSONObject().apply {
                            persistedChartPages.forEach { (chartId, page) ->
                                put(chartId, encodeChartPageJson(page))
                            }
                        },
                    )
                }
                val tmp = File(storageDir, "$HOME_CACHE_FILE.tmp")
                val target = File(storageDir, HOME_CACHE_FILE)
                tmp.writeText(root.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

    private fun encodeSectionJson(section: DiscoverHomeSection): JSONObject = JSONObject().apply {
        put("chartId", section.chart.id)
        put("items", JSONArray().apply { section.items.forEach { put(encodeItemJson(it)) } })
    }

    private fun decodeSectionJson(obj: JSONObject): DiscoverHomeSection? {
        val chartId = obj.optString("chartId").trim()
        val chart = DiscoverCharts.findChart(chartId) ?: return null
        val arr = obj.optJSONArray("items") ?: return null
        val items = (0 until arr.length()).mapNotNull { idx -> arr.optJSONObject(idx)?.let(::decodeItemJson) }
        return if (items.isEmpty()) null else DiscoverHomeSection(chart, items)
    }

    private fun encodeChartPageJson(page: DiscoverChartPage): JSONObject = JSONObject().apply {
        put("chartId", page.chart.id)
        put("items", JSONArray().apply { page.items.forEach { put(encodeItemJson(it)) } })
        if (page.note != null) put("note", page.note)
        put("usedFallback", page.usedFallback)
    }

    private fun decodeChartPageJson(obj: JSONObject): DiscoverChartPage? {
        val chartId = obj.optString("chartId").trim()
        val chart = DiscoverCharts.findChart(chartId) ?: return null
        val arr = obj.optJSONArray("items") ?: return null
        val items = (0 until arr.length()).mapNotNull { idx -> arr.optJSONObject(idx)?.let(::decodeItemJson) }
        if (items.isEmpty()) return null
        val note = if (obj.has("note") && !obj.isNull("note")) obj.optString("note").takeIf(String::isNotEmpty) else null
        return DiscoverChartPage(chart, items, note, obj.optBoolean("usedFallback", false))
    }

    internal fun encodeItemJson(item: DiscoverItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("kind", item.kind)
        put("title", item.title)
        if (item.originalTitle.isNotEmpty()) put("originalTitle", item.originalTitle)
        if (item.year != null) put("year", item.year)
        if (item.overview.isNotEmpty()) put("overview", item.overview)
        if (item.posterUrl != null) put("posterUrl", item.posterUrl)
        if (item.backdropUrl != null) put("backdropUrl", item.backdropUrl)
        if (item.genres.isNotEmpty()) put("genres", JSONArray(item.genres))
        if (item.countries.isNotEmpty()) put("countries", JSONArray(item.countries))
        if (item.rating != null) put("rating", item.rating)
        if (item.ratingLabel != null) put("ratingLabel", item.ratingLabel)
        if (item.voteCount != 0) put("voteCount", item.voteCount)
        if (item.episodeInfo != null) put("episodeInfo", item.episodeInfo)
        if (item.ongoing != null) put("ongoing", item.ongoing)
        if (item.tmdbId != null) put("tmdbId", item.tmdbId)
        if (item.doubanId != null) put("doubanId", item.doubanId)
        if (item.imdbId != null) put("imdbId", item.imdbId)
        if (item.artist.isNotEmpty()) put("artist", item.artist)
        if (item.album.isNotEmpty()) put("album", item.album)
        if (item.durationMs > 0L) put("durationMs", item.durationMs)
        if (item.neteaseId != null) put("neteaseId", item.neteaseId)
        if (item.appleUrl != null) put("appleUrl", item.appleUrl)
        if (item.platforms.isNotEmpty()) {
            put(
                "platforms",
                JSONArray().apply {
                    item.platforms.forEach { p ->
                        put(JSONObject().apply {
                            put("name", p.name)
                            if (p.url != null) put("url", p.url)
                        })
                    }
                },
            )
        }
    }

    internal fun decodeItemJson(obj: JSONObject): DiscoverItem? {
        val id = obj.optString("id").trim()
        val kind = obj.optString("kind").trim()
        val title = obj.optString("title").trim()
        if (id.isEmpty() || kind.isEmpty() || title.isEmpty()) return null
        fun optStr(k: String): String? =
            if (obj.has(k) && !obj.isNull(k)) obj.optString(k).takeIf(String::isNotEmpty) else null
        fun optIntOrNull(k: String): Int? =
            if (obj.has(k) && !obj.isNull(k)) obj.optInt(k) else null
        val genres = obj.optJSONArray("genres").strings()
        val countries = obj.optJSONArray("countries").strings()
        val platforms = obj.optJSONArray("platforms")?.let { arr ->
            (0 until arr.length()).mapNotNull { idx ->
                val pObj = arr.optJSONObject(idx) ?: return@mapNotNull null
                val pName = pObj.optString("name").trim()
                if (pName.isEmpty()) return@mapNotNull null
                val pUrl = if (pObj.has("url") && !pObj.isNull("url")) pObj.optString("url").takeIf(String::isNotEmpty) else null
                PlatformSource(pName, pUrl)
            }
        } ?: emptyList()
        return DiscoverItem(
            id = id,
            kind = kind,
            title = title,
            originalTitle = optStr("originalTitle").orEmpty(),
            year = optIntOrNull("year"),
            overview = optStr("overview").orEmpty(),
            posterUrl = optStr("posterUrl"),
            backdropUrl = optStr("backdropUrl"),
            genres = genres,
            countries = countries,
            rating = if (obj.has("rating") && !obj.isNull("rating")) obj.optDouble("rating").takeIf { !it.isNaN() } else null,
            ratingLabel = optStr("ratingLabel"),
            voteCount = obj.optInt("voteCount", 0),
            episodeInfo = optStr("episodeInfo"),
            ongoing = if (obj.has("ongoing") && !obj.isNull("ongoing")) obj.optBoolean("ongoing") else null,
            tmdbId = optIntOrNull("tmdbId"),
            doubanId = optStr("doubanId"),
            imdbId = optStr("imdbId"),
            platforms = platforms,
            artist = optStr("artist").orEmpty(),
            album = optStr("album").orEmpty(),
            durationMs = obj.optLong("durationMs", 0L).coerceAtLeast(0L),
            neteaseId = optStr("neteaseId"),
            appleUrl = optStr("appleUrl"),
        )
    }

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
                val matchedPlatform = DiscoverCharts.platforms.firstOrNull { it.tmdbNetworkId.toString() == chart.remoteId }
                if (hasTmdb) {
                    val items = fetchTmdbDiscoverNetwork(chart.remoteId, settings, page, forceRefresh).map { item ->
                        if (matchedPlatform != null) {
                            item.copy(platforms = PlatformCatalog.merge(item.platforms, listOf(PlatformSource(matchedPlatform.name))))
                        } else item
                    }
                    DiscoverChartPage(chart, items)
                } else {
                    val fallbackId = chart.fallbackRemoteId ?: "tv_global_best_weekly"
                    val start = ((page - 1).coerceAtLeast(0)) * count
                    val items = fetchDoubanCollection(fallbackId, "tv", start, count, forceRefresh)
                    DiscoverChartPage(
                        chart = chart,
                        items = items,
                        note = "未填写 TMDB API Key，当前暂展示豆瓣海外口碑剧集榜；在「我的 → 设置 → 影音信息」填写 TMDB Key 后可拉取该平台实时榜单。",
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
        val snapKey = searchSnapshotKey(settings, q)
        searchSnapshotCache[snapKey]?.let { return it }
        val cacheKey = "search:${settings.tmdbKey.isNotBlank()}:$q"
        cached(cacheKey)?.let {
            searchSnapshotCache[snapKey] = it
            return it
        }

        val results = mutableListOf<DiscoverItem>()
        if (settings.tmdbKey.isNotBlank()) {
            runCatching { results += fetchTmdbSearch(q, settings) }
        }
        if (settings.doubanEnabled) {
            runCatching { results += fetchDoubanSearch(q) }
        }
        val merged = mergeSearchResults(results)
        putCache(cacheKey, merged)
        searchSnapshotCache[snapKey] = merged
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
                    platforms = PlatformCatalog.merge(cur.platforms, item.platforms),
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
        val vendors = Douban.parseDoubanPlatforms(obj)
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
            platforms = vendors,
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

    /**
     * 影巢（re0.me）按 TMDB 编号直达详情页地址：
     * 影巢的 `/movie/{slug}` 与 `/tv/{slug}` 为站内内部标识，使用 TMDB ID 跳转必须走 `/tmdb/{movie|tv}/{tmdbId}` 才不会 404。
     */
    fun buildRe0DirectUrl(kind: String, tmdbId: Int): String {
        val mediaType = if (kind == "tv") "tv" else "movie"
        return "https://re0.me/tmdb/$mediaType/$tmdbId"
    }

    /** 构造影巢（re0.me）搜索页地址（同时携带 keyword / search / q 参数，配合内置浏览器自动回填搜索框触发检索）。 */
    fun buildRe0SearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return "https://re0.me/search?keyword=$encoded&search=$encoded&q=$encoded"
    }

    val builtinSources: List<SearchSource> = listOf(
        SearchSource(
            id = "re0_search",
            name = "影巢 (re0.me)",
            category = SearchCategory.RE0,
            urlTemplate = "https://re0.me/search?keyword={q}&search={q}&q={q}",
            description = "通过系统浏览器检索影巢片源（详情页支持词条直达）",
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
        val custom = parseCustomSources(p.getString(KEY_CUSTOM_SOURCES, null)).filterNot { it.id == "re0_home" }
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
        """https?://(?:pan\.quark\.cn|115\.com|115cdn\.com|115cdn\.net|115cdn\.org|anxia\.com|mypikpak\.com|www\.alipan\.com|alipan\.com|www\.aliyundrive\.com|aliyundrive\.com|pan\.baidu\.com|yun\.baidu\.com|cloud\.189\.cn|pan\.xunlei\.com|drive\.uc\.cn|fast\.uc\.cn|(?:www\.)?123pan\.(?:com|cn)|(?:www\.)?123(?:684|865|912|592)\.com|caiyun\.139\.com|share\.weiyun\.com|1drv\.ms|mega\.nz)/(?:s|t|m|j|file|folder)?/?[A-Za-z0-9_-]+[^\s"'<>()\[\]{}]*""",
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
