package com.aisub.player.meta

import android.content.Context
import com.aisub.player.AppSettings
import com.aisub.player.MusicDiscoverSource
import com.aisub.player.media.MediaEntry
import com.aisub.player.music.Lyrics
import com.aisub.player.music.MusicLookup
import com.aisub.player.music.TrackTags
import com.aisub.player.remote.cloud.cloudHttp
import com.aisub.player.translate.ChineseScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** 发现页顶栏的「影视 / 音乐」模式枚举（默认影视，持久化记忆上次切换状态）。 */
enum class DiscoverMode(val label: String) {
    VIDEO("影视"),
    MUSIC("音乐"),
    ;

    companion object {
        fun fromStored(raw: String?): DiscoverMode = when (raw?.trim()?.uppercase()) {
            "MUSIC" -> MUSIC
            else -> VIDEO
        }
    }
}

/** 发现页当前模式持久化存储：默认「影视」，每次切换后自动保存，下次打开保持一致。 */
object DiscoverModeStore {
    private const val PREFS = "discover_mode_prefs"
    private const val KEY_MODE = "discover_mode"

    private val _mode = MutableStateFlow(DiscoverMode.VIDEO)

    @Volatile
    private var initialized = false

    fun ensureInit(context: Context): StateFlow<DiscoverMode> {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    val raw = context.applicationContext
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_MODE, null)
                    _mode.value = DiscoverMode.fromStored(raw)
                    initialized = true
                }
            }
        }
        return _mode.asStateFlow()
    }

    fun current(context: Context): DiscoverMode {
        ensureInit(context)
        return _mode.value
    }

    fun select(context: Context, mode: DiscoverMode) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
        initialized = true
        _mode.value = mode
    }

    internal fun resetForTest(mode: DiscoverMode = DiscoverMode.VIDEO) {
        synchronized(this) {
            _mode.value = mode
            initialized = false
        }
    }
}

/** 音乐流媒体平台规范化目录（歌曲详情页与搜索快捷跳转使用）。 */
object MusicPlatformCatalog {
    data class Spec(
        val canonical: String,
        val badge: String,
        val accentHex: Long,
        val searchUrlTemplate: String,
    )

    val specs: List<Spec> = listOf(
        Spec("网易云音乐", "云", 0xFFE60026, "https://y.music.163.com/m/search?s={q}"),
        Spec("QQ音乐", "QQ", 0xFF1ECF74, "https://y.qq.com/n/ryqq/search?w={q}"),
        Spec("Apple Music", "AM", 0xFFFA243C, "https://music.apple.com/cn/search?term={q}"),
        Spec("汽水音乐", "汽水", 0xFF00C2A8, "https://www.douyin.com/search/{q}"),
        Spec("酷狗音乐", "酷狗", 0xFF0093FF, "https://www.kugou.com/yy/html/search.html#searchType=song&searchKeyWord={q}"),
        Spec("Spotify", "SP", 0xFF1DB954, "https://open.spotify.com/search/{q}"),
        Spec("YouTube Music", "YTM", 0xFFFF0033, "https://music.youtube.com/search?q={q}"),
        Spec("哔哩哔哩", "B站", 0xFFFB7299, "https://m.bilibili.com/search?keyword={q}"),
    )

    fun specFor(name: String): Spec? = specs.firstOrNull { it.canonical.equals(name.trim(), ignoreCase = true) }

    fun accentHex(name: String): Long = specFor(name)?.accentHex ?: 0xFFE65100

    fun resolvePlayUrl(platform: PlatformSource, item: DiscoverItem): String {
        platform.url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { return it }
        if (platform.name == "网易云音乐" && !item.neteaseId.isNullOrBlank()) {
            return "https://y.music.163.com/m/song?id=${item.neteaseId}"
        }
        if (platform.name == "Apple Music" && !item.appleUrl.isNullOrBlank()) {
            return item.appleUrl
        }
        val query = listOfNotNull(
            item.title.trim().takeIf { it.isNotEmpty() },
            item.artist.trim().takeIf { it.isNotEmpty() },
        ).joinToString(" ")
        return resolveSearchUrl(platform.name, query)
    }

    fun resolveSearchUrl(platformName: String, keyword: String): String {
        val q = URLEncoder.encode(keyword.trim(), "UTF-8").replace("+", "%20")
        val spec = specFor(platformName)
        return spec?.searchUrlTemplate?.replace("{q}", q)
            ?: "https://www.bing.com/search?q=${URLEncoder.encode("${keyword.trim()} $platformName", "UTF-8")}"
    }

    fun platformsForItem(item: DiscoverItem): List<PlatformSource> {
        val detected = LinkedHashMap<String, PlatformSource>()
        if (!item.neteaseId.isNullOrBlank()) {
            detected["网易云音乐"] = PlatformSource("网易云音乐", "https://y.music.163.com/m/song?id=${item.neteaseId}")
        }
        if (!item.appleUrl.isNullOrBlank()) {
            detected["Apple Music"] = PlatformSource("Apple Music", item.appleUrl)
        }
        for (p in item.platforms) {
            if (p.name.isNotBlank() && !detected.containsKey(p.name)) {
                detected[p.name] = p
            }
        }
        for (s in specs) {
            if (!detected.containsKey(s.canonical)) {
                detected[s.canonical] = PlatformSource(s.canonical)
            }
        }
        return detected.values.toList()
    }
}

enum class MusicChartSource {
    NETEASE_TOPLIST,
    NETEASE_NEW_EXPRESS,
    QQ_TOPLIST,
    APPLE_MOST_PLAYED,
}

data class MusicQuickTag(
    val chartId: String,
    val name: String,
    val badge: String,
    val accentHex: Long,
)

data class MusicChartDef(
    val id: String,
    val title: String,
    val subtitle: String,
    val source: MusicChartSource,
    val remoteId: String,
    val neteasePlaylistId: String = "",
    val qqTopId: Int = 26,
    val fallbackAppleCountry: String = "tw",
    val fallbackKeyword: String = "流行",
)

data class MusicHomeSection(
    val chart: MusicChartDef,
    val items: List<DiscoverItem>,
)

data class MusicChartPage(
    val chart: MusicChartDef,
    val items: List<DiscoverItem>,
)

object MusicDiscoverCharts {
    val quickTags: List<MusicQuickTag> = listOf(
        MusicQuickTag("music_hot", "流行热歌", "HOT", 0xFFE60026),
        MusicQuickTag("music_new_express", "新音乐", "NEW", 0xFFFF6D00),
        MusicQuickTag("music_soar", "飙升趋势", "UP", 0xFFD81B60),
        MusicQuickTag("music_apple_tw", "华语流行", "华语", 0xFFFA243C),
        MusicQuickTag("music_billboard", "Billboard", "BB", 0xFF2B2F3A),
        MusicQuickTag("music_oricon", "日本 Oricon", "JP", 0xFF0066CC),
        MusicQuickTag("music_korean", "韩语热歌", "KR", 0xFF00A88F),
    )

    val homeCharts: List<MusicChartDef> = listOf(
        MusicChartDef(
            id = "music_hot",
            title = "流行热歌榜",
            subtitle = "当下全网高热度热门流行单曲",
            source = MusicChartSource.NETEASE_TOPLIST,
            remoteId = "3778678",
            neteasePlaylistId = "3778678",
            qqTopId = 26,
            fallbackAppleCountry = "us",
            fallbackKeyword = "Pop Hits",
        ),
        MusicChartDef(
            id = "music_new_express",
            title = "新音乐速递",
            subtitle = "近期最新上架与编辑推荐新歌",
            source = MusicChartSource.NETEASE_NEW_EXPRESS,
            remoteId = "newsong",
            neteasePlaylistId = "3779629",
            qqTopId = 4,
            fallbackAppleCountry = "us",
            fallbackKeyword = "New Music ${Calendar.getInstance().get(Calendar.YEAR)}",
        ),
        MusicChartDef(
            id = "music_soar",
            title = "飙升趋势榜",
            subtitle = "近期收听热度快速飙升的流行单曲",
            source = MusicChartSource.NETEASE_TOPLIST,
            remoteId = "19723756",
            neteasePlaylistId = "19723756",
            qqTopId = 62,
            fallbackAppleCountry = "hk",
            fallbackKeyword = "飙升热歌",
        ),
        MusicChartDef(
            id = "music_new_chart",
            title = "新歌排行榜",
            subtitle = "近期新发行的高人气热门单曲",
            source = MusicChartSource.QQ_TOPLIST,
            remoteId = "27",
            neteasePlaylistId = "3779629",
            qqTopId = 27,
            fallbackAppleCountry = "sg",
            fallbackKeyword = "华语新歌",
        ),
        MusicChartDef(
            id = "music_apple_tw",
            title = "华语流行热播榜",
            subtitle = "实时高频播放华语流行单曲",
            source = MusicChartSource.APPLE_MOST_PLAYED,
            remoteId = "tw",
            neteasePlaylistId = "6723173524",
            qqTopId = 5,
            fallbackAppleCountry = "tw",
            fallbackKeyword = "周杰伦 林俊杰 邓紫棋",
        ),
        MusicChartDef(
            id = "music_apple_us",
            title = "全球欧美热播榜",
            subtitle = "欧美流行热播趋势单曲",
            source = MusicChartSource.APPLE_MOST_PLAYED,
            remoteId = "us",
            neteasePlaylistId = "2809577409",
            qqTopId = 3,
            fallbackAppleCountry = "us",
            fallbackKeyword = "Pop Hits",
        ),
        MusicChartDef(
            id = "music_original",
            title = "原创与独立音乐",
            subtitle = "独立音乐人与原创音乐口碑新作",
            source = MusicChartSource.NETEASE_TOPLIST,
            remoteId = "2884035",
            neteasePlaylistId = "2884035",
            qqTopId = 52,
            fallbackAppleCountry = "tw",
            fallbackKeyword = "独立民谣摇滚",
        ),
    )

    val allCharts: List<MusicChartDef> = homeCharts + listOf(
        MusicChartDef(
            id = "music_billboard",
            title = "美国 Billboard 榜",
            subtitle = "公告牌全球热门单曲风向标",
            source = MusicChartSource.NETEASE_TOPLIST,
            remoteId = "60198",
            neteasePlaylistId = "60198",
            qqTopId = 108,
            fallbackAppleCountry = "us",
            fallbackKeyword = "Billboard Hot",
        ),
        MusicChartDef(
            id = "music_oricon",
            title = "日本 Oricon / 热播榜",
            subtitle = "日本公信榜与 J-Pop / 动漫热门单曲",
            source = MusicChartSource.APPLE_MOST_PLAYED,
            remoteId = "jp",
            neteasePlaylistId = "60131",
            qqTopId = 17,
            fallbackAppleCountry = "jp",
            fallbackKeyword = "YOASOBI 米津玄師",
        ),
        MusicChartDef(
            id = "music_korean",
            title = "韩语 K-Pop 榜",
            subtitle = "近期热门韩语流行单曲",
            source = MusicChartSource.NETEASE_TOPLIST,
            remoteId = "745956260",
            neteasePlaylistId = "745956260",
            qqTopId = 16,
            fallbackAppleCountry = "kr",
            fallbackKeyword = "K-Pop",
        ),
        MusicChartDef(
            id = "music_uk",
            title = "英国 UK 流行榜",
            subtitle = "英伦流行与电子摇滚热单",
            source = MusicChartSource.APPLE_MOST_PLAYED,
            remoteId = "gb",
            neteasePlaylistId = "180106",
            qqTopId = 107,
            fallbackAppleCountry = "gb",
            fallbackKeyword = "UK Pop",
        ),
    )

    fun findChart(id: String): MusicChartDef? = allCharts.firstOrNull { it.id == id }
}

/** 音乐发现页搜索历史与 AI 音乐推荐历史存储 */
object MusicDiscoverSearchStore {
    private const val PREF_NAME = "discover_music_search"
    private const val KEY_HISTORY = "search_history"
    private const val KEY_AI_HISTORY = "ai_music_history"
    private const val MAX_HISTORY = 14

    @Volatile
    var sessionAiPrompt: String = ""

    @Volatile
    var sessionAiModelLabel: String = ""

    @Volatile
    var sessionAiResults: List<AiRecommendedItem> = emptyList()

    @Volatile
    var sessionAiSeenTitles: List<String> = emptyList()

    fun history(context: Context): List<String> =
        readList(context, KEY_HISTORY)

    fun recordHistory(context: Context, query: String) =
        writeEntry(context, KEY_HISTORY, query)

    fun clearHistory(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply()

    fun aiHistory(context: Context): List<String> =
        readList(context, KEY_AI_HISTORY)

    fun recordAiHistory(context: Context, prompt: String) =
        writeEntry(context, KEY_AI_HISTORY, prompt)

    fun clearAiHistory(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().remove(KEY_AI_HISTORY).apply()

    private fun readList(context: Context, key: String): List<String> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(key, null).orEmpty()
        return AiDiscoverStore.parseHistoryJson(raw)
    }

    private fun writeEntry(context: Context, key: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val updated = (listOf(clean) + readList(context, key).filterNot { it.equals(clean, ignoreCase = true) })
            .take(MAX_HISTORY)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, AiDiscoverStore.encodeHistoryJson(updated))
            .apply()
    }
}

/**
 * 音乐发现数据仓库：
 * - 支持手动切换推荐源（自动融合 / 网易云音乐 / QQ音乐 / Apple Music），任一源受限时自动回退；
 * - 内置内存与本地磁盘快照持久化缓存，冷启动或弱网切回秒开。
 */
object MusicDiscoverRepository {
    private const val CACHE_TTL_MS = 20 * 60_000L
    private const val DISK_CACHE_FILE = "discover_music_cache_v2.json"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private data class CacheEntry<T>(val value: T, val expiresAt: Long)

    private val listCache = ConcurrentHashMap<String, CacheEntry<List<DiscoverItem>>>()
    private val searchCache = ConcurrentHashMap<String, List<DiscoverItem>>()
    private val lyricsCache = ConcurrentHashMap<String, String>()
    private val persistedCharts = ConcurrentHashMap<String, List<DiscoverItem>>()
    private val sessionAutoRefreshed = ConcurrentHashMap.newKeySet<String>()
    private val cachedHomeSectionsBySource = ConcurrentHashMap<MusicDiscoverSource, List<MusicHomeSection>>()
    private val diskLock = Any()

    @Volatile
    private var loadedDiskPath: String? = null

    @Volatile
    var sessionQueryInput: String = ""

    @Volatile
    var sessionAppliedQuery: String = ""

    private val fetchPool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "voxtitle-music-discover").apply { isDaemon = true }
    }
    /** 二级并发拉取池：避免 loadHomeSections 占满 fetchPool 后内部嵌套 supplyAsync().get() 造成线程饥饿死锁。 */
    private val subFetchPool = Executors.newCachedThreadPool { r ->
        Thread(r, "voxtitle-music-subfetch").apply { isDaemon = true }
    }

    fun consumeFirstVisitAutoRefresh(sourcePref: MusicDiscoverSource = MusicDiscoverSource.AUTO): Boolean =
        sessionAutoRefreshed.add("music_home:${sourcePref.name}")

    fun clearCache() {
        listCache.clear()
        searchCache.clear()
        cachedHomeSectionsBySource.clear()
    }

    private fun persistedKey(sourcePref: MusicDiscoverSource, chartId: String): String =
        "${sourcePref.name}:$chartId"

    private fun persistedFor(sourcePref: MusicDiscoverSource, chartId: String): List<DiscoverItem>? =
        persistedCharts[persistedKey(sourcePref, chartId)]
            ?: if (sourcePref == MusicDiscoverSource.AUTO) persistedCharts[chartId] else null

    fun peekHomeSections(
        context: Context? = null,
        sourcePref: MusicDiscoverSource = MusicDiscoverSource.AUTO,
    ): List<MusicHomeSection> {
        ensureDiskLoaded(context?.filesDir)
        cachedHomeSectionsBySource[sourcePref]?.takeIf { it.isNotEmpty() }?.let { return it }
        val built = MusicDiscoverCharts.homeCharts.mapNotNull { chart ->
            val items = persistedFor(sourcePref, chart.id)
            if (items.isNullOrEmpty()) null else MusicHomeSection(chart, items)
        }
        if (built.isNotEmpty()) cachedHomeSectionsBySource[sourcePref] = built
        return built
    }

    fun peekChartPage(
        chart: MusicChartDef,
        context: Context? = null,
        sourcePref: MusicDiscoverSource = MusicDiscoverSource.AUTO,
    ): MusicChartPage? {
        ensureDiskLoaded(context?.filesDir)
        cached("chart:${sourcePref.name}:${chart.id}")?.takeIf { it.isNotEmpty() }?.let { return MusicChartPage(chart, it) }
        persistedFor(sourcePref, chart.id)?.takeIf { it.isNotEmpty() }?.let { return MusicChartPage(chart, it) }
        return null
    }

    fun peekSearchResults(
        query: String,
        sourcePref: MusicDiscoverSource = MusicDiscoverSource.AUTO,
    ): List<DiscoverItem>? {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return searchCache["${sourcePref.name}:$q"]
    }

    fun loadHomeSections(
        forceRefresh: Boolean = false,
        context: Context? = null,
        sourcePref: MusicDiscoverSource = MusicDiscoverSource.AUTO,
    ): List<MusicHomeSection> {
        ensureDiskLoaded(context?.filesDir)
        val previous = peekHomeSections(context, sourcePref)
        if (!forceRefresh) {
            cachedHomeSectionsBySource[sourcePref]?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val charts = MusicDiscoverCharts.homeCharts
        val futures = charts.map { chart ->
            chart.id to CompletableFuture.supplyAsync({
                runCatching {
                    fetchChartItems(chart, count = 24, forceRefresh = forceRefresh, sourcePref = sourcePref)
                }.getOrDefault(emptyList())
            }, fetchPool)
        }
        val freshMap = LinkedHashMap<String, List<DiscoverItem>>()
        for ((id, fut) in futures) {
            val list = runCatching { fut.get() }.getOrDefault(emptyList())
            if (list.isNotEmpty()) {
                freshMap[id] = list
                persistedCharts[persistedKey(sourcePref, id)] = list
            }
        }
        if (freshMap.isEmpty()) return previous
        val merged = charts.mapNotNull { chart ->
            val fresh = freshMap[chart.id]
            if (!fresh.isNullOrEmpty()) MusicHomeSection(chart, fresh)
            else previous.firstOrNull { it.chart.id == chart.id }
        }
        if (merged.isNotEmpty()) {
            cachedHomeSectionsBySource[sourcePref] = merged
            saveDiskCache(context?.filesDir)
        }
        return merged
    }

    fun loadChartPage(
        chart: MusicChartDef,
        count: Int = 50,
        forceRefresh: Boolean = false,
        context: Context? = null,
        sourcePref: MusicDiscoverSource = MusicDiscoverSource.AUTO,
    ): MusicChartPage {
        ensureDiskLoaded(context?.filesDir)
        val items = fetchChartItems(chart, count = count, forceRefresh = forceRefresh, sourcePref = sourcePref)
        if (items.isNotEmpty()) {
            persistedCharts[persistedKey(sourcePref, chart.id)] = items
            saveDiskCache(context?.filesDir)
            return MusicChartPage(chart, items)
        }
        persistedFor(sourcePref, chart.id)?.takeIf { it.isNotEmpty() }?.let { return MusicChartPage(chart, it) }
        return MusicChartPage(chart, emptyList())
    }

    /** 搜索歌曲：支持按指定推荐源优先检索，自动融合多源直达链接与高清正方形封面。 */
    fun search(
        query: String,
        limit: Int = 30,
        sourcePref: MusicDiscoverSource = MusicDiscoverSource.AUTO,
    ): List<DiscoverItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val cacheKey = "${sourcePref.name}:$q"
        searchCache[cacheKey]?.let { return it }
        cached("search:$cacheKey")?.let {
            searchCache[cacheKey] = it
            return it
        }

        val combined: List<DiscoverItem> = when (sourcePref) {
            MusicDiscoverSource.NETEASE -> {
                val primary = runCatching { fetchNeteaseSearch(q, limit) }.getOrDefault(emptyList())
                if (primary.size >= 5) primary
                else primary +
                    runCatching { fetchQqSearch(q, limit) }.getOrDefault(emptyList()) +
                    runCatching { fetchItunesSearch(q, limit) }.getOrDefault(emptyList())
            }
            MusicDiscoverSource.QQ -> {
                val primary = runCatching { fetchQqSearch(q, limit) }.getOrDefault(emptyList())
                if (primary.size >= 5) primary
                else primary +
                    runCatching { fetchNeteaseSearch(q, limit) }.getOrDefault(emptyList()) +
                    runCatching { fetchItunesSearch(q, limit) }.getOrDefault(emptyList())
            }
            MusicDiscoverSource.APPLE -> {
                val primary = runCatching { fetchItunesSearch(q, limit) }.getOrDefault(emptyList())
                if (primary.size >= 5) primary
                else primary +
                    runCatching { fetchNeteaseSearch(q, limit) }.getOrDefault(emptyList()) +
                    runCatching { fetchQqSearch(q, limit) }.getOrDefault(emptyList())
            }
            MusicDiscoverSource.AUTO -> {
                val neteaseFut = CompletableFuture.supplyAsync({
                    runCatching { fetchNeteaseSearch(q, limit) }.getOrDefault(emptyList())
                }, fetchPool)
                val qqFut = CompletableFuture.supplyAsync({
                    runCatching { fetchQqSearch(q, limit) }.getOrDefault(emptyList())
                }, fetchPool)
                val itunesFut = CompletableFuture.supplyAsync({
                    runCatching { fetchItunesSearch(q, limit) }.getOrDefault(emptyList())
                }, fetchPool)
                neteaseFut.get() + qqFut.get() + itunesFut.get()
            }
        }

        val merged = deduplicateSongs(combined).take(limit)
        if (merged.isNotEmpty()) {
            putCache("search:$cacheKey", merged)
            searchCache[cacheKey] = merged
        }
        return merged
    }

    /** 获取歌曲纯文本歌词摘要（供歌曲详情页展示）。 */
    fun fetchLyricsPreview(item: DiscoverItem): String? {
        lyricsCache[item.id]?.let { return it.ifBlank { null } }
        if (!item.neteaseId.isNullOrBlank()) {
            fetchNeteaseLyricText(item.neteaseId)?.let { text ->
                lyricsCache[item.id] = text
                return text
            }
        }
        val tags = TrackTags(
            title = item.title,
            artist = item.artist.ifBlank { null },
            album = item.album.ifBlank { null },
            durationMs = item.durationMs,
        )
        val parsed: Lyrics? = runCatching { MusicLookup.bestLyrics(tags) }.getOrNull()
        val plain = parsed?.lines
            ?.map { it.text.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString("\n")
            .orEmpty()
        lyricsCache[item.id] = plain
        return plain.ifBlank { null }
    }

    private fun fetchNeteaseForChart(chart: MusicChartDef, count: Int): List<DiscoverItem> {
        if (chart.source == MusicChartSource.NETEASE_NEW_EXPRESS) {
            val express = runCatching { fetchNeteaseNewExpress(count) }.getOrDefault(emptyList())
            if (express.size >= 12) return express
            return deduplicateSongs(express + runCatching { fetchNeteaseToplist("3779629", count) }.getOrDefault(emptyList()))
        }
        val pid = chart.neteasePlaylistId.ifBlank { chart.remoteId }
        val primary = runCatching { fetchNeteaseToplist(pid, count) }.getOrDefault(emptyList())
        if (primary.isNotEmpty()) return primary
        if (chart.id == "music_apple_us") {
            return runCatching { fetchNeteaseToplist("60198", count) }.getOrDefault(emptyList())
        }
        return emptyList()
    }

    private fun fetchQqForChart(chart: MusicChartDef, count: Int): List<DiscoverItem> =
        runCatching { fetchQqToplist(chart.qqTopId, count) }.getOrDefault(emptyList())

    private fun fetchAppleForChart(chart: MusicChartDef, count: Int): List<DiscoverItem> {
        when (chart.id) {
            "music_hot" -> {
                val pop = runCatching { fetchAppleGlobalPopHot(count) }.getOrDefault(emptyList())
                if (pop.isNotEmpty()) return pop
            }
            "music_new_express" -> {
                val fresh = runCatching { fetchAppleNewReleases(count, preferChinese = false) }.getOrDefault(emptyList())
                if (fresh.isNotEmpty()) return fresh
            }
            "music_new_chart" -> {
                val freshRegional = runCatching { fetchAppleNewReleases(count, preferChinese = true) }.getOrDefault(emptyList())
                if (freshRegional.isNotEmpty()) return freshRegional
            }
            "music_original" -> {
                val indie = runCatching { fetchAppleIndieChart(count) }.getOrDefault(emptyList())
                if (indie.isNotEmpty()) return indie
            }
            "music_soar" -> {
                val rising = runCatching { fetchAppleRisingChart(count) }.getOrDefault(emptyList())
                if (rising.isNotEmpty()) return rising
            }
        }
        val country = if (chart.source == MusicChartSource.APPLE_MOST_PLAYED) chart.remoteId else chart.fallbackAppleCountry
        val primary = runCatching { fetchAppleMostPlayed(country, count) }.getOrDefault(emptyList())
        if (primary.isNotEmpty()) return primary
        val rssFallback = runCatching { fetchItunesTopSongsRss(country, count) }.getOrDefault(emptyList())
        if (rssFallback.isNotEmpty()) return rssFallback
        if (country != "us") {
            val us = runCatching { fetchAppleMostPlayed("us", count) }.getOrDefault(emptyList())
            if (us.isNotEmpty()) return us
        }
        return runCatching { fetchItunesSearch(chart.fallbackKeyword, count) }.getOrDefault(emptyList())
    }

    private fun fetchChartItems(
        chart: MusicChartDef,
        count: Int,
        forceRefresh: Boolean,
        sourcePref: MusicDiscoverSource,
    ): List<DiscoverItem> {
        val key = "chart:${sourcePref.name}:${chart.id}"
        if (!forceRefresh) {
            cached(key)?.takeIf { it.size >= minOf(count, 12) }?.let { return it.take(count) }
        }
        val attempts: List<() -> List<DiscoverItem>> = when (sourcePref) {
            MusicDiscoverSource.NETEASE -> listOf(
                { fetchNeteaseForChart(chart, count) },
                { fetchQqForChart(chart, count) },
                { fetchAppleForChart(chart, count) },
            )
            MusicDiscoverSource.QQ -> listOf(
                { fetchQqForChart(chart, count) },
                { fetchNeteaseForChart(chart, count) },
                { fetchAppleForChart(chart, count) },
            )
            MusicDiscoverSource.APPLE -> listOf(
                { fetchAppleForChart(chart, count) },
                { fetchNeteaseForChart(chart, count) },
                { fetchQqForChart(chart, count) },
            )
            MusicDiscoverSource.AUTO -> when (chart.source) {
                MusicChartSource.NETEASE_TOPLIST, MusicChartSource.NETEASE_NEW_EXPRESS -> listOf(
                    { fetchNeteaseForChart(chart, count) },
                    { fetchQqForChart(chart, count) },
                    { fetchAppleForChart(chart, count) },
                )
                MusicChartSource.QQ_TOPLIST -> listOf(
                    { fetchQqForChart(chart, count) },
                    { fetchNeteaseForChart(chart, count) },
                    { fetchAppleForChart(chart, count) },
                )
                MusicChartSource.APPLE_MOST_PLAYED -> listOf(
                    { fetchAppleForChart(chart, count) },
                    { fetchQqForChart(chart, count) },
                    { fetchNeteaseForChart(chart, count) },
                )
            }
        }
        var result: List<DiscoverItem> = emptyList()
        for (attempt in attempts) {
            val candidate = attempt()
            if (candidate.isNotEmpty()) {
                result = candidate.take(count)
                break
            }
        }
        if (result.isNotEmpty()) {
            putCache(key, result)
        }
        return result
    }

    internal fun fetchNeteaseToplist(playlistId: String, limit: Int = 40): List<DiscoverItem> {
        val body = FormBody.Builder()
            .add("id", playlistId)
            .add("n", limit.coerceIn(10, 100).toString())
            .add("s", "0")
            .build()
        val req = Request.Builder()
            .url("https://music.163.com/api/v6/playlist/detail")
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://music.163.com/")
            .post(body)
            .build()
        val text = httpText(req) ?: httpText(
            Request.Builder()
                .url("https://music.163.com/api/playlist/detail?id=$playlistId")
                .header("User-Agent", BROWSER_UA)
                .header("Referer", "https://music.163.com/")
                .build(),
        ) ?: return emptyList()

        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val pl = root.optJSONObject("playlist") ?: root.optJSONObject("result") ?: return emptyList()
        val directTracks = pl.optJSONArray("tracks").objects().mapNotNull(::parseNeteaseSongJson)

        val targetIds = pl.optJSONArray("trackIds").objects()
            .mapNotNull { it.optLong("id", 0L).takeIf { id -> id > 0L }?.toString() }
            .take(limit)

        if (directTracks.size >= limit || targetIds.size <= directTracks.size) {
            return directTracks.take(limit)
        }

        val existingIds = directTracks.mapNotNull { it.neteaseId }.toSet()
        val missingIds = targetIds.filterNot { it in existingIds }.take(limit - directTracks.size)
        val extra = if (missingIds.isNotEmpty()) fetchNeteaseSongsByIds(missingIds) else emptyList()
        val byId = (directTracks + extra).associateBy { it.neteaseId.orEmpty() }
        return targetIds.mapNotNull { byId[it] }.ifEmpty { directTracks }.take(limit)
    }

    private fun fetchNeteaseSongsByIds(ids: List<String>): List<DiscoverItem> {
        if (ids.isEmpty()) return emptyList()
        val cArr = JSONArray().apply {
            ids.forEach { id -> id.toLongOrNull()?.let { put(JSONObject().put("id", it)) } }
        }
        val body = FormBody.Builder()
            .add("c", cArr.toString())
            .build()
        val req = Request.Builder()
            .url("https://music.163.com/api/v3/song/detail")
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://music.163.com/")
            .post(body)
            .build()
        val text = httpText(req) ?: return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        return root.optJSONArray("songs").objects().mapNotNull(::parseNeteaseSongJson)
    }

    internal fun fetchNeteaseNewExpress(limit: Int = 24): List<DiscoverItem> {
        val req = Request.Builder()
            .url("https://music.163.com/api/personalized/newsong?limit=${limit.coerceIn(10, 40)}")
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://music.163.com/")
            .build()
        val text = httpText(req) ?: return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        return root.optJSONArray("result").objects().mapNotNull { entry ->
            val songObj = entry.optJSONObject("song") ?: entry
            val parsed = parseNeteaseSongJson(songObj) ?: return@mapNotNull null
            val fallbackPic = entry.optString("picUrl").takeIf { it.startsWith("http") }?.let(::upgradeNeteaseCover)
            val copywriter = entry.optString("copywriter").trim()
            parsed.copy(
                posterUrl = parsed.posterUrl ?: fallbackPic,
                backdropUrl = parsed.backdropUrl ?: fallbackPic,
                overview = parsed.overview.ifBlank { copywriter },
            )
        }
    }

    internal fun fetchNeteaseSearch(query: String, limit: Int = 24): List<DiscoverItem> {
        val url = "https://music.163.com/api/cloudsearch/pc".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("type", "1")
            .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
            .build()
            .toString()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://music.163.com/")
            .build()
        val text = httpText(req) ?: return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        return root.optJSONObject("result")?.optJSONArray("songs").objects().mapNotNull(::parseNeteaseSongJson)
    }

    private data class DatedAppleSong(val item: DiscoverItem, val releaseDateIso: String)

    private fun isoDateDaysAgo(days: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -days)
        return "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun interleaveLists(lists: List<List<DiscoverItem>>, limit: Int): List<DiscoverItem> {
        val raw = ArrayList<DiscoverItem>()
        val maxLen = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) {
            for (l in lists) {
                if (i < l.size) raw += l[i]
            }
            if (raw.size >= limit * 2) break
        }
        return deduplicateSongs(raw).take(limit)
    }

    /** Apple Music「流行热歌榜」：聚合欧美主流流行流派榜 (US/GB Pop genre=14) 与全球热门单曲，避免落入中国区全是华语老歌。 */
    internal fun fetchAppleGlobalPopHot(limit: Int = 30): List<DiscoverItem> {
        val usPopFut = CompletableFuture.supplyAsync({
            runCatching { fetchItunesTopSongsRss("us", 35, genreId = 14) }.getOrDefault(emptyList())
        }, subFetchPool)
        val gbPopFut = CompletableFuture.supplyAsync({
            runCatching { fetchItunesTopSongsRss("gb", 35, genreId = 14) }.getOrDefault(emptyList())
        }, subFetchPool)
        val usAllFut = CompletableFuture.supplyAsync({
            runCatching { fetchAppleMostPlayed("us", 30) }.getOrDefault(emptyList())
        }, subFetchPool)
        val hkFut = CompletableFuture.supplyAsync({
            runCatching { fetchAppleMostPlayed("hk", 15) }.getOrDefault(emptyList())
        }, subFetchPool)
        val lists = listOf(
            runCatching { usPopFut.get() }.getOrDefault(emptyList()),
            runCatching { gbPopFut.get() }.getOrDefault(emptyList()),
            runCatching { usAllFut.get() }.getOrDefault(emptyList()),
            runCatching { hkFut.get() }.getOrDefault(emptyList()),
        ).filter { it.isNotEmpty() }
        return interleaveLists(lists, limit)
    }

    /** Apple Music「新音乐速递 / 新歌榜」：汇总多区实时在榜与流行流派单曲，严格按发行日期过滤近半年新歌并按最新发行时间倒序排列。 */
    internal fun fetchAppleNewReleases(limit: Int = 30, preferChinese: Boolean = false): List<DiscoverItem> {
        val sources: List<() -> List<DatedAppleSong>> = if (preferChinese) {
            listOf(
                { fetchItunesTopSongsRssDated("tw", 45, null) },
                { fetchItunesTopSongsRssDated("hk", 45, null) },
                { fetchAppleMostPlayedDated("hk", 45) },
                { fetchAppleMostPlayedDated("tw", 45) },
                { fetchItunesTopSongsRssDated("us", 35, 14) },
                { fetchAppleMostPlayedDated("jp", 30) },
                { fetchAppleMostPlayedDated("kr", 30) },
            )
        } else {
            listOf(
                { fetchItunesTopSongsRssDated("us", 50, 14) },
                { fetchItunesTopSongsRssDated("us", 50, null) },
                { fetchItunesTopSongsRssDated("gb", 45, 14) },
                { fetchAppleMostPlayedDated("us", 50) },
                { fetchAppleMostPlayedDated("gb", 45) },
                { fetchAppleMostPlayedDated("hk", 30) },
                { fetchAppleMostPlayedDated("jp", 30) },
                { fetchAppleMostPlayedDated("kr", 30) },
            )
        }
        val futures = sources.map { fn ->
            CompletableFuture.supplyAsync({ runCatching { fn() }.getOrDefault(emptyList()) }, subFetchPool)
        }
        val all = futures.flatMap { runCatching { it.get() }.getOrDefault(emptyList()) }
        if (all.isEmpty()) return emptyList()

        // 按曲目规范键去重，保留发行日期最明确的一条
        val byKey = LinkedHashMap<String, DatedAppleSong>()
        for (entry in all) {
            val key = songNormKey(entry.item.title, entry.item.artist)
            if (key.isEmpty()) continue
            val prev = byKey[key]
            if (prev == null || (entry.releaseDateIso.length >= 10 && entry.releaseDateIso > prev.releaseDateIso)) {
                byKey[key] = entry
            }
        }
        val unique = byKey.values.toList()
        val cutoffRecent = isoDateDaysAgo(120)
        val cutoffYear = isoDateDaysAgo(365)
        val recent = unique
            .filter { it.releaseDateIso.length >= 10 && it.releaseDateIso >= cutoffRecent }
            .sortedByDescending { it.releaseDateIso }
        if (recent.size >= minOf(limit, 12)) {
            return recent.map { it.item }.take(limit)
        }
        val withinYear = unique
            .filter { it.releaseDateIso.length >= 10 && it.releaseDateIso >= cutoffYear }
            .sortedByDescending { it.releaseDateIso }
        if (withinYear.isNotEmpty()) {
            return withinYear.map { it.item }.take(limit)
        }
        return unique
            .sortedByDescending { it.releaseDateIso }
            .map { it.item }
            .take(limit)
    }

    /** Apple Music「原创与独立音乐」：拉取美/英区 Alternative & Indie (genre=20) 热单。 */
    internal fun fetchAppleIndieChart(limit: Int = 30): List<DiscoverItem> {
        val usFut = CompletableFuture.supplyAsync({
            runCatching { fetchItunesTopSongsRss("us", 35, genreId = 20) }.getOrDefault(emptyList())
        }, subFetchPool)
        val gbFut = CompletableFuture.supplyAsync({
            runCatching { fetchItunesTopSongsRss("gb", 35, genreId = 20) }.getOrDefault(emptyList())
        }, subFetchPool)
        val lists = listOf(
            runCatching { usFut.get() }.getOrDefault(emptyList()),
            runCatching { gbFut.get() }.getOrDefault(emptyList()),
        ).filter { it.isNotEmpty() }
        return interleaveLists(lists, limit)
    }

    /** Apple Music「飙升趋势榜」：聚合英/美/港/日多区实时飙升热单，优先近期发行高热度曲目。 */
    internal fun fetchAppleRisingChart(limit: Int = 30): List<DiscoverItem> {
        val gbFut = CompletableFuture.supplyAsync({
            runCatching { fetchAppleMostPlayedDated("gb", 35) }.getOrDefault(emptyList())
        }, subFetchPool)
        val hkFut = CompletableFuture.supplyAsync({
            runCatching { fetchAppleMostPlayedDated("hk", 35) }.getOrDefault(emptyList())
        }, subFetchPool)
        val krFut = CompletableFuture.supplyAsync({
            runCatching { fetchAppleMostPlayedDated("kr", 25) }.getOrDefault(emptyList())
        }, subFetchPool)
        val usFut = CompletableFuture.supplyAsync({
            runCatching { fetchItunesTopSongsRssDated("us", 35, 14) }.getOrDefault(emptyList())
        }, subFetchPool)
        val cutoff = isoDateDaysAgo(365)
        val pools = listOf(gbFut, hkFut, usFut, krFut).map { f ->
            val raw = runCatching { f.get() }.getOrDefault(emptyList())
            val fresh = raw.filter { it.releaseDateIso >= cutoff }.map { it.item }
            fresh.ifEmpty { raw.map { it.item } }
        }.filter { it.isNotEmpty() }
        return interleaveLists(pools, limit)
    }

    private fun fetchAppleMostPlayedDated(country: String, limit: Int = 30): List<DatedAppleSong> {
        val capped = if (limit <= 25) 25 else 50
        val urls = listOf(
            "https://rss.applemarketingtools.com/api/v2/$country/music/most-played/$capped/songs.json",
            "https://rss.marketingtools.apple.com/api/v2/$country/music/most-played/$capped/songs.json",
        )
        for (url in urls) {
            val text = httpText(Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()) ?: continue
            val parsed = parseAppleRssSongsWithDate(text)
            if (parsed.isNotEmpty()) return parsed.take(limit)
        }
        return emptyList()
    }

    internal fun fetchAppleMostPlayed(country: String, limit: Int = 30): List<DiscoverItem> =
        fetchAppleMostPlayedDated(country, limit).map { it.item }

    internal fun fetchItunesTopSongsRss(country: String, limit: Int = 30, genreId: Int? = null): List<DiscoverItem> =
        fetchItunesTopSongsRssDated(country, limit, genreId).map { it.item }

    private fun fetchItunesTopSongsRssDated(country: String, limit: Int = 30, genreId: Int? = null): List<DatedAppleSong> {
        val n = limit.coerceIn(10, 50)
        val genreSeg = genreId?.let { "/genre=$it" }.orEmpty()
        val url = "https://itunes.apple.com/$country/rss/topsongs/limit=$n$genreSeg/json"
        val text = httpText(Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()) ?: return emptyList()
        return parseItunesTopSongsRssJson(text)
    }

    private fun parseItunesTopSongsRssJson(rawJson: String): List<DatedAppleSong> {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        val feed = root.optJSONObject("feed") ?: return emptyList()
        val entryObj = feed.opt("entry")
        val entries: List<JSONObject> = when (entryObj) {
            is JSONArray -> entryObj.objects()
            is JSONObject -> listOf(entryObj)
            else -> emptyList()
        }
        return entries.mapNotNull { e ->
            val id = e.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id")?.trim().orEmpty()
            val title = e.optJSONObject("im:name")?.optString("label")?.trim().orEmpty()
            if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
            val artist = e.optJSONObject("im:artist")?.optString("label")?.trim().orEmpty()
            val album = e.optJSONObject("im:collection")?.optJSONObject("im:name")?.optString("label")?.trim().orEmpty()
            val releaseIso = e.optJSONObject("im:releaseDate")?.optString("label")?.trim()?.take(10).orEmpty()
            val year = releaseIso.take(4).toIntOrNull()?.takeIf { it in 1900..2100 }
            val images = e.optJSONArray("im:image").objects()
            val cover = images.lastOrNull()?.optString("label")?.trim()?.takeIf { it.startsWith("http") }
                ?.replace(Regex("""\d+x\d+bb"""), "600x600bb")
            val linkField = e.opt("link")
            val links = when (linkField) {
                is JSONArray -> linkField.objects()
                is JSONObject -> listOf(linkField)
                else -> emptyList()
            }
            val songUrl = links.firstNotNullOfOrNull { l ->
                val attr = l.optJSONObject("attributes")
                if (attr?.optString("rel") == "alternate") attr.optString("href").trim().takeIf { it.startsWith("http") } else null
            } ?: e.optJSONObject("id")?.optString("label")?.trim()?.takeIf { it.startsWith("http") }
            val genre = e.optJSONObject("category")?.optJSONObject("attributes")?.optString("label")?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("Music", ignoreCase = true) && it != "音乐" }
            val item = DiscoverItem(
                id = "apple:$id",
                kind = "music",
                title = title,
                originalTitle = artist,
                year = year,
                posterUrl = cover,
                backdropUrl = cover,
                genres = listOfNotNull(genre),
                artist = artist,
                album = album,
                appleUrl = songUrl,
                platforms = listOfNotNull(songUrl?.let { PlatformSource("Apple Music", it) }),
            )
            DatedAppleSong(item, releaseIso)
        }
    }

    internal fun parseAppleRssSongsJson(rawJson: String): List<DiscoverItem> =
        parseAppleRssSongsWithDate(rawJson).map { it.item }

    private fun parseAppleRssSongsWithDate(rawJson: String): List<DatedAppleSong> {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        val results = root.optJSONObject("feed")?.optJSONArray("results").objects()
        return results.mapNotNull { r ->
            val id = r.optString("id").trim()
            val title = r.optString("name").trim()
            if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
            val artist = r.optString("artistName").trim()
            val releaseDate = r.optString("releaseDate").trim().take(10)
            val year = releaseDate.take(4).toIntOrNull()?.takeIf { it in 1900..2100 }
            val cover = r.optString("artworkUrl100").takeIf { it.startsWith("http") }
                ?.replace("100x100bb", "600x600bb")
                ?.replace("200x200bb", "600x600bb")
            val songUrl = r.optString("url").takeIf { it.startsWith("http") }
            val genres = r.optJSONArray("genres").objects()
                .mapNotNull { it.optString("name").trim().takeIf { g -> g.isNotEmpty() && !g.equals("Music", ignoreCase = true) && g != "音乐" } }
            val item = DiscoverItem(
                id = "apple:$id",
                kind = "music",
                title = title,
                originalTitle = artist,
                year = year,
                posterUrl = cover,
                backdropUrl = cover,
                genres = genres,
                artist = artist,
                appleUrl = songUrl,
                platforms = listOfNotNull(songUrl?.let { PlatformSource("Apple Music", it) }),
            )
            DatedAppleSong(item, releaseDate)
        }
    }

    internal fun fetchItunesSearch(query: String, limit: Int = 24): List<DiscoverItem> {
        val hasChinese = MusicLookup.hasHan(query)
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
            .apply { if (hasChinese) addQueryParameter("country", "tw") }
            .build()
            .toString()
        val text = httpText(Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()) ?: return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        return root.optJSONArray("results").objects().mapNotNull { r ->
            val id = r.optLong("trackId", 0L).takeIf { it > 0L }?.toString() ?: return@mapNotNull null
            val title = r.optString("trackName").trim()
            if (title.isEmpty()) return@mapNotNull null
            val artist = r.optString("artistName").trim()
            val album = r.optString("collectionName").trim()
            val durationMs = r.optLong("trackTimeMillis", 0L).coerceAtLeast(0L)
            val year = r.optString("releaseDate").take(4).toIntOrNull()?.takeIf { it in 1900..2100 }
            val cover = r.optString("artworkUrl100").takeIf { it.startsWith("http") }
                ?.replace("100x100bb", "800x800bb")
            val viewUrl = r.optString("trackViewUrl").takeIf { it.startsWith("http") }
            val genre = r.optString("primaryGenreName").trim().takeIf { it.isNotEmpty() && !it.equals("Music", ignoreCase = true) }
            DiscoverItem(
                id = "itunes:$id",
                kind = "music",
                title = title,
                originalTitle = artist,
                year = year,
                posterUrl = cover,
                backdropUrl = cover,
                genres = listOfNotNull(genre),
                artist = artist,
                album = album,
                durationMs = durationMs,
                appleUrl = viewUrl,
                platforms = listOfNotNull(viewUrl?.let { PlatformSource("Apple Music", it) }),
            )
        }
    }

    internal fun parseNeteaseSongJson(s: JSONObject): DiscoverItem? {
        val id = s.optLong("id", 0L).takeIf { it > 0L }?.toString() ?: return null
        val title = s.optString("name").trim()
        if (title.isEmpty()) return null
        val artistsArr = s.optJSONArray("ar") ?: s.optJSONArray("artists")
        val artist = artistsArr.objects()
            .mapNotNull { it.optString("name").trim().takeIf(String::isNotEmpty) }
            .joinToString(" / ")
        val al = s.optJSONObject("al") ?: s.optJSONObject("album")
        val album = al?.optString("name")?.trim().orEmpty()
        val rawPic = al?.optString("picUrl")?.takeIf { it.startsWith("http") }
            ?: s.optString("picUrl").takeIf { it.startsWith("http") }
        val cover = rawPic?.let(::upgradeNeteaseCover)
        val durationMs = s.optLong("dt", 0L).takeIf { it > 0L } ?: s.optLong("duration", 0L).coerceAtLeast(0L)
        val publishMs = s.optLong("publishTime", 0L).takeIf { it > 0L } ?: al?.optLong("publishTime", 0L) ?: 0L
        val year = if (publishMs > 0L) {
            Calendar.getInstance().apply { timeInMillis = publishMs }.get(Calendar.YEAR).takeIf { it in 1950..2100 }
        } else null
        val alia = (s.optJSONArray("alia") ?: s.optJSONArray("alias")).objectsString().firstOrNull().orEmpty()
        val neteaseUrl = "https://y.music.163.com/m/song?id=$id"
        return DiscoverItem(
            id = "netease:$id",
            kind = "music",
            title = title,
            originalTitle = artist,
            year = year,
            overview = alia,
            posterUrl = cover,
            backdropUrl = cover,
            artist = artist,
            album = album,
            durationMs = durationMs,
            neteaseId = id,
            platforms = listOf(PlatformSource("网易云音乐", neteaseUrl)),
        )
    }

    internal fun fetchQqToplist(topId: Int, limit: Int = 40): List<DiscoverItem> {
        val payload = JSONObject()
            .put(
                "detail",
                JSONObject()
                    .put("module", "musicToplist.ToplistInfoServer")
                    .put("method", "GetDetail")
                    .put(
                        "param",
                        JSONObject()
                            .put("topId", topId)
                            .put("offset", 0)
                            .put("num", limit.coerceIn(10, 100))
                            .put("period", ""),
                    ),
            )
            .put("comm", JSONObject().put("ct", 24).put("cv", 0))
            .toString()
        val req = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://y.qq.com/")
            .header("Origin", "https://y.qq.com")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val text = httpText(req) ?: return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val dataObj = root.optJSONObject("detail")?.optJSONObject("data")
        val songArr = dataObj?.optJSONArray("songInfoList")
            ?: dataObj?.optJSONObject("data")?.optJSONArray("songInfoList")
        return songArr.objects().mapNotNull(::parseQqSongJson).take(limit)
    }

    internal fun fetchQqSearch(query: String, limit: Int = 24): List<DiscoverItem> {
        val payload = JSONObject()
            .put(
                "req_1",
                JSONObject()
                    .put("module", "music.search.SearchCgiService")
                    .put("method", "DoSearchForQQMusicDesktop")
                    .put(
                        "param",
                        JSONObject()
                            .put("num_per_page", limit.coerceIn(1, 50))
                            .put("page_num", 1)
                            .put("query", query)
                            .put("search_type", 0),
                    ),
            )
            .put("comm", JSONObject().put("ct", 24).put("cv", 0))
            .toString()
        val req = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://y.qq.com/")
            .header("Origin", "https://y.qq.com")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val text = httpText(req) ?: return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val dataObj = root.optJSONObject("req_1")?.optJSONObject("data")
        val songArr = dataObj?.optJSONObject("body")?.optJSONObject("song")?.optJSONArray("list")
            ?: dataObj?.optJSONArray("list")
        return songArr.objects().mapNotNull(::parseQqSongJson).take(limit)
    }

    internal fun parseQqSongJson(s: JSONObject): DiscoverItem? {
        val mid = s.optString("mid").ifBlank { s.optString("songmid") }.trim()
        val numericId = s.optLong("id", 0L).takeIf { it > 0L } ?: s.optLong("songid", 0L)
        if (mid.isEmpty() && numericId <= 0L) return null
        val title = s.optString("title")
            .ifBlank { s.optString("name") }
            .ifBlank { s.optString("songname") }
            .trim()
        if (title.isEmpty()) return null
        val artist = s.optJSONArray("singer").objects()
            .mapNotNull { it.optString("name").ifBlank { it.optString("title") }.trim().takeIf(String::isNotEmpty) }
            .joinToString(" / ")
        val al = s.optJSONObject("album")
        val album = al?.optString("name")?.ifBlank { al.optString("title") }?.trim().orEmpty()
            .ifBlank { s.optString("albumname").trim() }
        val albumMid = al?.optString("mid")?.ifBlank { al.optString("pmid") }?.trim().orEmpty()
            .ifBlank { s.optString("albummid").trim() }
        val vsMid = s.optJSONArray("vs")?.optString(0)?.trim().orEmpty()
        val cover = when {
            albumMid.isNotEmpty() -> "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg"
            vsMid.isNotEmpty() -> "https://y.gtimg.cn/music/photo_new/T062R500x500M000$vsMid.jpg"
            else -> null
        }
        val durationMs = s.optLong("interval", 0L).coerceAtLeast(0L) * 1000L
        val year = s.optString("time_public").trim().take(4).toIntOrNull()?.takeIf { it in 1950..2100 }
        val subtitle = s.optString("subtitle").trim()
        val songKey = mid.ifEmpty { numericId.toString() }
        val qqUrl = "https://y.qq.com/n/ryqq/songDetail/$songKey"
        return DiscoverItem(
            id = "qq:$songKey",
            kind = "music",
            title = title,
            originalTitle = artist,
            year = year,
            overview = subtitle,
            posterUrl = cover,
            backdropUrl = cover,
            artist = artist,
            album = album,
            durationMs = durationMs,
            platforms = listOf(PlatformSource("QQ音乐", qqUrl)),
        )
    }

    private fun fetchNeteaseLyricText(songId: String): String? {
        val req = Request.Builder()
            .url("https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1")
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://music.163.com/")
            .build()
        val text = httpText(req) ?: return null
        val lrcRaw = runCatching { JSONObject(text).optJSONObject("lrc")?.optString("lyric") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val parsed = Lyrics.parse(lrcRaw, MusicLookup.NETEASE) ?: return null
        return parsed.lines.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n").ifBlank { null }
    }

    internal fun deduplicateSongs(raw: List<DiscoverItem>): List<DiscoverItem> {
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<DiscoverItem>()
        val indexByKey = HashMap<String, Int>()
        for (item in raw) {
            val key = songNormKey(item.title, item.artist)
            if (key.isEmpty()) continue
            val existingIdx = indexByKey[key]
            if (existingIdx == null) {
                indexByKey[key] = out.size
                out += item
            } else {
                val cur = out[existingIdx]
                out[existingIdx] = cur.copy(
                    posterUrl = cur.posterUrl ?: item.posterUrl,
                    backdropUrl = cur.backdropUrl ?: item.backdropUrl,
                    album = cur.album.ifBlank { item.album },
                    year = cur.year ?: item.year,
                    durationMs = if (cur.durationMs > 0L) cur.durationMs else item.durationMs,
                    overview = cur.overview.ifBlank { item.overview },
                    genres = if (cur.genres.isNotEmpty()) cur.genres else item.genres,
                    neteaseId = cur.neteaseId ?: item.neteaseId,
                    appleUrl = cur.appleUrl ?: item.appleUrl,
                    platforms = mergeMusicPlatforms(cur.platforms, item.platforms),
                )
            }
        }
        return out
    }

    internal fun songNormKey(title: String, artist: String): String {
        val t = normMusicText(title)
        if (t.isEmpty()) return ""
        val firstArtist = artist.split('/', '、', ',', '&', '，').firstOrNull().orEmpty()
        val a = normMusicText(firstArtist)
        return if (a.isNotEmpty()) "$t|$a" else t
    }

    internal fun normMusicText(s: String): String =
        ChineseScript.toSimplified(s)
            .lowercase()
            .replace(Regex("""[(（\[【].*?[)）\]】]"""), "")
            .replace(Regex("""[\s·・:：\-—_!！?？.'"`()\[\]【】《》,，/\\&]"""), "")

    internal fun mergeMusicPlatforms(a: List<PlatformSource>, b: List<PlatformSource>): List<PlatformSource> {
        val map = LinkedHashMap<String, PlatformSource>()
        for (p in a + b) {
            if (p.name.isBlank()) continue
            val prev = map[p.name]
            if (prev == null || (prev.url == null && p.url != null)) {
                map[p.name] = p
            }
        }
        return map.values.toList()
    }

    private fun upgradeNeteaseCover(url: String): String {
        val https = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
        val clean = https.substringBefore('?')
        return "$clean?param=600y600"
    }

    internal fun httpText(req: Request): String? = runCatching {
        cloudHttp.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }.getOrNull()

    private fun cached(key: String): List<DiscoverItem>? {
        val entry = listCache[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            listCache.remove(key)
            return null
        }
        return entry.value
    }

    private fun putCache(key: String, value: List<DiscoverItem>) {
        listCache[key] = CacheEntry(value, System.currentTimeMillis() + CACHE_TTL_MS)
    }

    private fun ensureDiskLoaded(storageDir: File?) {
        if (storageDir == null) return
        val path = storageDir.absolutePath
        if (loadedDiskPath == path) return
        synchronized(diskLock) {
            if (loadedDiskPath == path) return
            val file = File(storageDir, DISK_CACHE_FILE)
            if (file.isFile && file.length() > 0L) {
                runCatching {
                    val root = JSONObject(file.readText(Charsets.UTF_8))
                    root.optJSONObject("charts")?.let { chartsObj ->
                        val keys = chartsObj.keys()
                        while (keys.hasNext()) {
                            val cid = keys.next()
                            val arr = chartsObj.optJSONArray(cid) ?: continue
                            val parsed = (0 until arr.length()).mapNotNull { idx ->
                                arr.optJSONObject(idx)?.let(DiscoverRepository::decodeItemJson)
                            }
                            if (parsed.isNotEmpty()) persistedCharts[cid] = parsed
                        }
                    }
                }
            }
            loadedDiskPath = path
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
                        "charts",
                        JSONObject().apply {
                            persistedCharts.forEach { (cid, items) ->
                                put(cid, JSONArray().apply { items.forEach { put(DiscoverRepository.encodeItemJson(it)) } })
                            }
                        },
                    )
                }
                val tmp = File(storageDir, "$DISK_CACHE_FILE.tmp")
                val target = File(storageDir, DISK_CACHE_FILE)
                tmp.writeText(root.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }
}

/** 歌曲详情页 AI 音乐推荐侧重点 */
enum class MusicAiRecommendAngle(val label: String, val promptInstruction: String) {
    SIMILAR_STYLE("同曲风佳作", "推荐与当前歌曲编曲风格、节奏律动、听感气质最相近的高口碑代表作"),
    SIMILAR_MOOD("同情绪氛围", "推荐与当前歌曲情感氛围、适合收听场景高度契合的动人单曲"),
    SAME_ARTIST("同歌手/同圈层", "优先推荐同歌手高口碑代表作或音乐风格相近的同流派音乐人作品"),
    HIDDEN_GEMS("冷门宝藏单曲", "避开烂大街口水歌，挖掘同曲风中编曲出彩、耐听度高的小众宝藏好歌"),
}

/**
 * 音乐详情页「相似歌曲推荐」与「AI 音乐推荐」引擎：
 * - 相似歌曲：按所选音乐推荐源优先匹配相似歌曲与同歌手/同风格代表作，并支持多源互备；
 * - AI 推荐：复用应用内已配置的任意对话大模型推荐契合曲风与心境的歌曲，自动匹配高清封面与本机可播状态。
 */
object MusicRecommendationEngine {
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val similarCache = ConcurrentHashMap<String, List<DiscoverItem>>()
    private val aiCache = ConcurrentHashMap<String, List<AiRecommendedItem>>()

    /** 匹配本机音乐库中的同名曲目（优先同时匹配歌名与歌手）。 */
    fun matchLocalTracks(
        item: DiscoverItem,
        localAudios: List<MediaEntry>,
        historyEntries: List<MediaEntry> = emptyList(),
    ): List<MediaEntry> {
        val wantTitle = MusicDiscoverRepository.normMusicText(item.title)
        if (wantTitle.length < 2) return emptyList()
        val wantArtist = item.artist.split('/', '、', ',', '&', '，')
            .map(MusicDiscoverRepository::normMusicText)
            .filter { it.length >= 2 }

        val localUris = localAudios.mapTo(HashSet()) { it.uri }
        val localHistory = historyEntries.filter { it.isMusic && (it.uri in localUris || it.uri.startsWith("content://") || it.uri.startsWith("file:")) }
        val pool = (localAudios + localHistory).distinctBy { it.uri }

        return pool.mapNotNull { e ->
            val rawName = MusicDiscoverRepository.normMusicText(e.displayTitle)
            val folderNorm = MusicDiscoverRepository.normMusicText(e.folder)
            if (rawName.isEmpty()) return@mapNotNull null
            val titleExact = rawName == wantTitle
            val titleContains = rawName.contains(wantTitle)
            if (!titleExact && !titleContains) return@mapNotNull null
            val artistMatched = wantArtist.any { a -> rawName.contains(a) || folderNorm.contains(a) }
            val score = (if (titleExact) 4 else 2) + (if (artistMatched) 3 else 0)
            if (!titleExact && !artistMatched && wantTitle.length < 3) return@mapNotNull null
            e to score
        }.sortedByDescending { it.second }.map { it.first }.take(6)
    }

    /** 获取相似歌曲推荐列表（遵循设置中的音乐推荐源偏好）。 */
    suspend fun fetchSimilarSongs(
        item: DiscoverItem,
        sourcePref: MusicDiscoverSource = MusicDiscoverSource.AUTO,
        forceRefresh: Boolean = false,
    ): List<DiscoverItem> = withContext(Dispatchers.IO) {
        val cacheKey = "${sourcePref.name}|${item.id}|${item.title}|${item.artist}"
        if (!forceRefresh) {
            similarCache[cacheKey]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        }

        val collected = ArrayList<DiscoverItem>()
        val primaryArtist = item.artist.split('/', '、', ',', '&', '，').firstOrNull()?.trim().orEmpty()

        fun appendNeteaseSimilar() {
            val resolvedNeteaseId = item.neteaseId?.takeIf { it.isNotBlank() } ?: runCatching {
                val q = listOfNotNull(item.title, item.artist.takeIf { it.isNotBlank() }).joinToString(" ")
                MusicDiscoverRepository.fetchNeteaseSearch(q, 5).firstOrNull { cand ->
                    MusicDiscoverRepository.normMusicText(cand.title) == MusicDiscoverRepository.normMusicText(item.title)
                }?.neteaseId
            }.getOrNull()
            if (!resolvedNeteaseId.isNullOrBlank()) {
                collected += fetchNeteaseSimiSongs(resolvedNeteaseId)
            }
            if (collected.size < 10 && primaryArtist.isNotEmpty()) {
                runCatching { MusicDiscoverRepository.fetchNeteaseSearch(primaryArtist, 14) }
                    .getOrNull()?.let { collected += it }
            }
        }

        when (sourcePref) {
            MusicDiscoverSource.QQ -> {
                if (primaryArtist.isNotEmpty()) {
                    runCatching { MusicDiscoverRepository.fetchQqSearch(primaryArtist, 14) }
                        .getOrNull()?.let { collected += it }
                }
                if (item.album.isNotBlank()) {
                    runCatching { MusicDiscoverRepository.fetchQqSearch("${item.album} $primaryArtist".trim(), 8) }
                        .getOrNull()?.let { collected += it }
                }
                if (collected.size < 10) appendNeteaseSimilar()
            }
            MusicDiscoverSource.APPLE -> {
                if (primaryArtist.isNotEmpty()) {
                    runCatching { MusicDiscoverRepository.fetchItunesSearch(primaryArtist, 14) }
                        .getOrNull()?.let { collected += it }
                }
                val genreKw = item.genres.firstOrNull()?.takeIf { it.isNotBlank() }
                if (collected.size < 10 && !genreKw.isNullOrBlank()) {
                    runCatching { MusicDiscoverRepository.fetchItunesSearch("$genreKw $primaryArtist".trim(), 10) }
                        .getOrNull()?.let { collected += it }
                }
                if (collected.size < 10) appendNeteaseSimilar()
            }
            MusicDiscoverSource.NETEASE, MusicDiscoverSource.AUTO -> {
                appendNeteaseSimilar()
                if (collected.size < 10 && primaryArtist.isNotEmpty()) {
                    runCatching { MusicDiscoverRepository.fetchQqSearch(primaryArtist, 10) }
                        .getOrNull()?.let { collected += it }
                    if (collected.size < 10) {
                        runCatching { MusicDiscoverRepository.fetchItunesSearch(primaryArtist, 10) }
                            .getOrNull()?.let { collected += it }
                    }
                }
            }
        }

        if (collected.size < 8) {
            val fallbackKw = item.genres.firstOrNull()?.takeIf { it.isNotBlank() } ?: item.title
            runCatching { MusicDiscoverRepository.search(fallbackKw, 14, sourcePref) }
                .getOrNull()?.let { collected += it }
        }

        val selfTitleNorm = MusicDiscoverRepository.normMusicText(item.title)
        val selfKey = MusicDiscoverRepository.songNormKey(item.title, item.artist)
        val filtered = MusicDiscoverRepository.deduplicateSongs(collected).filterNot { cand ->
            val candTitleNorm = MusicDiscoverRepository.normMusicText(cand.title)
            val candKey = MusicDiscoverRepository.songNormKey(cand.title, cand.artist)
            cand.id == item.id ||
                (!item.neteaseId.isNullOrBlank() && cand.neteaseId == item.neteaseId) ||
                candKey == selfKey ||
                (candTitleNorm == selfTitleNorm && (primaryArtist.isEmpty() || cand.artist.contains(primaryArtist, ignoreCase = true)))
        }.take(16)

        if (filtered.isNotEmpty()) {
            similarCache[cacheKey] = filtered
        }
        filtered
    }

    private fun fetchNeteaseSimiSongs(songId: String): List<DiscoverItem> {
        val urls = listOf(
            "https://music.163.com/api/v1/discovery/simiSong?songid=$songId&limit=16&offset=0",
            "https://music.163.com/api/discovery/simiSong?songid=$songId&limit=16&offset=0",
        )
        for (url in urls) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Referer", "https://music.163.com/")
                .build()
            val text = MusicDiscoverRepository.httpText(req) ?: continue
            val root = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val parsed = root.optJSONArray("songs").objects().mapNotNull(MusicDiscoverRepository::parseNeteaseSongJson)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    /** 歌曲详情页 AI 相似歌曲推荐 */
    suspend fun fetchAiSongRecommendations(
        settings: AppSettings,
        item: DiscoverItem,
        localAudios: List<MediaEntry> = emptyList(),
        recentMusicTitles: List<String> = emptyList(),
        angle: MusicAiRecommendAngle = MusicAiRecommendAngle.SIMILAR_STYLE,
        customRequirement: String = "",
        excludeTitles: List<String> = emptyList(),
        forceRefresh: Boolean = false,
    ): Pair<String, List<AiRecommendedItem>> = withContext(Dispatchers.IO) {
        val resolved = RecommendationEngine.resolveChatTranslator(settings)
            ?: throw IllegalStateException("尚未配置可用的 AI 模型，请先在设置中配置 DeepSeek / Gemini / 通义千问 / 豆包 / 本地模型")
        val (modelLabel, translator) = resolved

        val cacheKey = "song_ai|${settings.musicDiscoverSource.name}|${item.title}|${item.artist}|${angle.name}|${customRequirement.trim()}"
        if (!forceRefresh && excludeTitles.isEmpty()) {
            aiCache[cacheKey]?.takeIf { it.isNotEmpty() }?.let { return@withContext modelLabel to it }
        }

        val systemPrompt = """
            你是资深音乐企划与电台策展人。请根据用户正在浏览的歌曲，精准推荐 6 首风格或听感高度契合的真实歌曲。
            规则：
            1. 绝不推荐当前歌曲本身，也不推荐用户要求排除的歌曲，绝不虚构不存在的歌曲；
            2. 推荐理由（reason）用中文撰写，控制在 20～46 字，点明编曲风格、旋律气质或情绪共鸣，避免空泛套话；
            3. 严格只输出合法 JSON 对象，格式如下，不要输出任何额外文字：
            {"recommendations":[{"title":"歌名","artist":"歌手","year":2020,"reason":"推荐理由"}]}
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("当前歌曲：《${item.title}》" + (if (item.artist.isNotBlank()) " - ${item.artist}" else ""))
            if (item.album.isNotBlank()) appendLine("所属专辑：《${item.album}》")
            item.year?.let { appendLine("发行年份：$it") }
            if (item.genres.isNotEmpty()) appendLine("流派标签：${item.genres.joinToString(" / ")}")
            if (recentMusicTitles.isNotEmpty()) {
                appendLine("用户近期常听参考：${recentMusicTitles.take(6).joinToString("、")}")
            }
            appendLine("推荐侧重点：${angle.promptInstruction}")
            if (customRequirement.isNotBlank()) appendLine("用户额外要求：${customRequirement.trim()}")
            if (excludeTitles.isNotEmpty()) {
                appendLine("请避开以下已推荐过的歌曲：${excludeTitles.take(12).joinToString("、")}")
            }
        }

        val reply = translator.chat(systemPrompt, userPrompt)
        val rawSongs = parseAiSongsJson(reply)
        if (rawSongs.isEmpty()) throw IllegalStateException("AI 返回的歌曲推荐为空，请重试")

        val selfNorm = MusicDiscoverRepository.normMusicText(item.title)
        val enriched = enrichAiSongs(
            rawSongs.filterNot { MusicDiscoverRepository.normMusicText(it.title) == selfNorm }.take(6),
            localAudios,
            settings.musicDiscoverSource,
        )
        if (excludeTitles.isEmpty()) aiCache[cacheKey] = enriched
        modelLabel to enriched
    }

    /** 音乐发现页右上角「AI 推荐音乐」：按自然语言描述推荐歌曲。 */
    suspend fun fetchAiSongsByDescription(
        settings: AppSettings,
        requirement: String,
        localAudios: List<MediaEntry> = emptyList(),
        recentMusicTitles: List<String> = emptyList(),
        excludeTitles: List<String> = emptyList(),
        forceRefresh: Boolean = false,
    ): Pair<String, List<AiRecommendedItem>> = withContext(Dispatchers.IO) {
        val cleanReq = requirement.trim()
        require(cleanReq.isNotEmpty()) { "请先输入你想听的音乐风格、场景或心境描述" }

        val resolved = RecommendationEngine.resolveChatTranslator(settings)
            ?: throw IllegalStateException("尚未配置可用的 AI 模型，请先配置 DeepSeek / Gemini / 通义千问 / 豆包 / Claude / 本地模型")
        val (modelLabel, translator) = resolved

        val cacheKey = "music_desc|${settings.musicDiscoverSource.name}|$cleanReq"
        if (!forceRefresh && excludeTitles.isEmpty()) {
            aiCache[cacheKey]?.takeIf { it.isNotEmpty() }?.let { return@withContext modelLabel to it }
        }

        val systemPrompt = """
            你是资深音乐策展人与私人电台 DJ。用户会用自然语言描述想听的音乐需求（如曲风流派、情绪氛围、收听场景、年代语种、类似歌手或节奏偏好）。
            请精准推荐 8 首最契合需求的真实好歌。
            规则：
            1. 严格遵循用户的语种、曲风、年代与情绪要求，绝不虚构不存在的歌曲，也不推荐要求排除的歌曲；
            2. 推荐理由（reason）用中文撰写，控制在 22～52 字，具体说明旋律编曲、人声质感或如何契合该场景；
            3. 严格只输出合法 JSON 对象，格式如下，不要输出任何额外文字：
            {"recommendations":[{"title":"歌名","artist":"歌手","year":2021,"reason":"契合需求的推荐理由"}]}
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("用户听歌需求：$cleanReq")
            if (recentMusicTitles.isNotEmpty()) {
                appendLine("用户近期听歌口味参考（以本次需求描述为最优先）：${recentMusicTitles.take(8).joinToString("、")}")
            }
            if (excludeTitles.isNotEmpty()) {
                appendLine("请避开以下已推荐过的歌曲，换一批新的高口碑单曲：${excludeTitles.take(18).joinToString("、")}")
            }
        }

        val reply = translator.chat(systemPrompt, userPrompt)
        val rawSongs = parseAiSongsJson(reply)
        if (rawSongs.isEmpty()) throw IllegalStateException("AI 未返回有效的歌曲推荐列表，请重试")

        val excludedNorm = excludeTitles.map(MusicDiscoverRepository::normMusicText).toSet()
        val filtered = rawSongs.filterNot { MusicDiscoverRepository.normMusicText(it.title) in excludedNorm }
            .ifEmpty { rawSongs }
            .take(8)
        val enriched = enrichAiSongs(filtered, localAudios, settings.musicDiscoverSource)
        if (excludeTitles.isEmpty()) aiCache[cacheKey] = enriched
        modelLabel to enriched
    }

    private suspend fun enrichAiSongs(
        rawList: List<RawAiSong>,
        localAudios: List<MediaEntry>,
        sourcePref: MusicDiscoverSource,
    ): List<AiRecommendedItem> = coroutineScope {
        rawList.map { rec ->
            async {
                val query = listOfNotNull(rec.title, rec.artist.takeIf { it.isNotBlank() }).joinToString(" ")
                val hits = runCatching { MusicDiscoverRepository.search(query, 8, sourcePref) }.getOrDefault(emptyList())
                val wantT = MusicDiscoverRepository.normMusicText(rec.title)
                val wantA = MusicDiscoverRepository.normMusicText(rec.artist)
                val matched = hits.firstOrNull { h ->
                    val ht = MusicDiscoverRepository.normMusicText(h.title)
                    val ha = MusicDiscoverRepository.normMusicText(h.artist)
                    ht == wantT && (wantA.isEmpty() || ha.contains(wantA) || wantA.contains(ha))
                } ?: hits.firstOrNull { h ->
                    MusicDiscoverRepository.normMusicText(h.title) == wantT
                } ?: hits.firstOrNull()

                val item = matched ?: DiscoverItem(
                    id = "ai_music:${rec.title}:${rec.artist}",
                    kind = "music",
                    title = rec.title,
                    originalTitle = rec.artist,
                    year = rec.year,
                    overview = rec.reason,
                    artist = rec.artist,
                )
                val localHit = matchLocalTracks(item, localAudios).firstOrNull()
                AiRecommendedItem(
                    item = item,
                    reason = rec.reason,
                    inLibraryName = if (localHit != null) "本机音乐" else null,
                )
            }
        }.awaitAll()
    }

    internal data class RawAiSong(
        val title: String,
        val artist: String,
        val year: Int?,
        val reason: String,
    )

    internal fun parseAiSongsJson(raw: String): List<RawAiSong> {
        val cleaned = raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        val arr: JSONArray = run {
            val objStart = cleaned.indexOf('{')
            val objEnd = cleaned.lastIndexOf('}')
            if (objStart >= 0 && objEnd > objStart) {
                val obj = runCatching { JSONObject(cleaned.substring(objStart, objEnd + 1)) }.getOrNull()
                obj?.optJSONArray("recommendations") ?: obj?.optJSONArray("songs") ?: obj?.optJSONArray("items")
            } else null
        } ?: run {
            val arrStart = cleaned.indexOf('[')
            val arrEnd = cleaned.lastIndexOf(']')
            if (arrStart >= 0 && arrEnd > arrStart) {
                runCatching { JSONArray(cleaned.substring(arrStart, arrEnd + 1)) }.getOrNull()
            } else null
        } ?: return emptyList()

        val out = ArrayList<RawAiSong>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("title").ifBlank { o.optString("name") }.trim().removeSurrounding("《", "》")
            if (t.isEmpty()) continue
            val artist = o.optString("artist").ifBlank { o.optString("originalTitle") }.trim()
            val y = o.optInt("year", 0).takeIf { it in 1900..2100 }
            val r = o.optString("reason").trim().ifEmpty { "AI 精选同曲风高口碑单曲" }
            out += RawAiSong(t, artist, y, r)
        }
        return out
    }
}

private fun JSONArray?.objectsString(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it)?.trim()?.takeIf(String::isNotEmpty) }
