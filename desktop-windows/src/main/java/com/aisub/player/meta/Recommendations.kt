package com.aisub.player.meta

import android.content.Context
import com.aisub.player.AppSettings
import com.aisub.player.Provider
import com.aisub.player.media.CustomMediaLibraryStore
import com.aisub.player.remote.cloud.cloudHttp
import com.aisub.player.translate.ChatCompletionsTranslator
import com.aisub.player.translate.ClaudeTranslator
import com.aisub.player.translate.Translator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** AI 推荐单条作品结果（附带大模型个性化推荐理由及本机媒体库可播状态） */
data class AiRecommendedItem(
    val item: DiscoverItem,
    val reason: String,
    /** 非空表示已在用户的自定义媒体库中刮削入库，可直接播放 */
    val inLibraryName: String? = null,
)

/** 发现页 AI 按需求荐片的类型过滤 */
enum class AiDiscoverFilter(val label: String, val promptConstraint: String) {
    ALL("不限类型", "电影或剧集均可（择优推荐最契合需求的作品）"),
    MOVIE("仅电影", "只推荐电影，kind 字段必须全部为 \"movie\""),
    TV("仅剧集", "只推荐电视剧、迷你剧、纪录剧集或动画番剧，kind 字段必须全部为 \"tv\""),
}

/** 发现页「AI 推荐影片」会话快照与需求历史存储（从详情页返回时保持上次推荐结果免重复请求） */
object AiDiscoverStore {
    private const val PREF_NAME = "discover_search"
    private const val KEY_AI_HISTORY = "ai_prompt_history"
    private const val MAX_HISTORY = 12

    @Volatile
    var sessionPrompt: String = ""

    @Volatile
    var sessionFilter: AiDiscoverFilter = AiDiscoverFilter.ALL

    @Volatile
    var sessionModelLabel: String = ""

    @Volatile
    var sessionResults: List<AiRecommendedItem> = emptyList()

    @Volatile
    var sessionSeenTitles: List<String> = emptyList()

    fun history(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AI_HISTORY, null)
            .orEmpty()
        return parseHistoryJson(raw)
    }

    fun recordHistory(context: Context, prompt: String) {
        val clean = prompt.trim()
        if (clean.isEmpty()) return
        val updated = (listOf(clean) + history(context).filterNot { it.equals(clean, ignoreCase = true) })
            .take(MAX_HISTORY)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AI_HISTORY, encodeHistoryJson(updated))
            .apply()
    }

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_AI_HISTORY)
            .apply()
    }

    internal fun encodeHistoryJson(items: List<String>): String {
        val arr = JSONArray()
        items.forEach { if (it.isNotBlank()) arr.put(it.trim()) }
        return arr.toString()
    }

    internal fun parseHistoryJson(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val list = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) list += s
        }
        return list
    }
}

/** AI 推荐角度预设 */
enum class AiRecommendAngle(val label: String, val promptInstruction: String) {
    SIMILAR_VIBE("同风格佳作", "推荐与本片核心叙事气质、视听美学或情感共鸣最相近的高分代表作"),
    MIND_BENDING("高能反转/深度", "侧重剧本结构精妙、悬念张力强或思想内核深刻的口碑佳作"),
    HIDDEN_GEMS("冷门宝藏", "避开烂大街榜单，挖掘同类型中评分高但相对小众的沧海遗珠"),
    SAME_CREATORS("同主创/同宇宙", "优先考虑同导演、编剧、核心主演代表作或同系列衍生精神续作"),
}

/**
 * 影片详情页「相似作品推荐」与「AI 智能推荐」引擎：
 * - 相似作品：同时支持 TMDB (`/recommendations` + `/similar`) 与豆瓣免 Key 接口（移动端 Rexxar `/recommendations` + 网页端相似推荐解析）；
 * - AI 推荐：自动复用应用内已配置的任意对话大模型（DeepSeek、Gemini、通义千问、豆包、Claude、自定义 OpenAI、本地 Ollama 等），
 *   生成定制推荐理由，并自动并发补齐高清海报、豆瓣/TMDB 评分以及本机媒体库入库角标。
 */
object RecommendationEngine {

    private const val DOUBAN_MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private const val DOUBAN_DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val similarCache = ConcurrentHashMap<String, List<DiscoverItem>>()
    private val aiCache = ConcurrentHashMap<String, List<AiRecommendedItem>>()

    /** 支持对话推荐的 LLM 服务商优先级（当前选中的优先，其次自动寻找已填 Key 的服务商） */
    private val CHAT_PROVIDERS = listOf(
        Provider.DEEPSEEK,
        Provider.GEMINI,
        Provider.ALIYUN,
        Provider.VOLC,
        Provider.CLAUDE,
        Provider.OPENAI_COMPAT,
        Provider.LOCAL,
    )

    /** 解析当前可用的对话大模型及其名称标签；若均未配置则返回 null */
    fun resolveChatTranslator(settings: AppSettings): Pair<String, Translator>? {
        val candidates = listOf(settings.provider) + CHAT_PROVIDERS.filter { it != settings.provider }
        for (p in candidates) {
            if (p !in CHAT_PROVIDERS) continue
            val c = settings.configOf(p)
            val ready = when (p) {
                Provider.LOCAL -> c.model.isNotBlank() && c.baseUrl.isNotBlank()
                Provider.CLAUDE -> c.apiKey.isNotBlank() && c.model.isNotBlank()
                Provider.OPENAI_COMPAT -> c.baseUrl.isNotBlank() && c.model.isNotBlank() &&
                    (c.apiKey.isNotBlank() || c.baseUrl.contains("127.0.0.1") || c.baseUrl.contains("localhost") || c.baseUrl.contains("192.168."))
                else -> c.apiKey.isNotBlank() && c.model.isNotBlank()
            }
            if (!ready) continue
            val translator: Translator = when (p) {
                Provider.CLAUDE -> ClaudeTranslator(c.apiKey, c.model)
                Provider.GEMINI -> ChatCompletionsTranslator(
                    c.baseUrl, c.apiKey, c.model,
                    temperature = 0.6,
                    extraBody = mapOf("reasoning_effort" to "low"),
                )
                Provider.ALIYUN -> ChatCompletionsTranslator(
                    c.baseUrl, c.apiKey, c.model,
                    temperature = 0.6,
                    extraBody = mapOf("enable_thinking" to false),
                )
                Provider.VOLC -> ChatCompletionsTranslator(
                    c.baseUrl, c.apiKey, c.model,
                    temperature = 0.6,
                    extraBody = mapOf("thinking" to JSONObject().put("type", "disabled")),
                )
                Provider.LOCAL -> ChatCompletionsTranslator(
                    c.baseUrl, c.apiKey, c.model,
                    temperature = 0.5,
                    extraBody = mapOf("chat_template_kwargs" to JSONObject().put("enable_thinking", false)),
                )
                else -> ChatCompletionsTranslator(c.baseUrl, c.apiKey, c.model, temperature = 0.6)
            }
            return "${p.label} · ${c.model}" to translator
        }
        return null
    }

    /**
     * 获取相似作品推荐列表（支持 TMDB + 豆瓣双源融合去重）。
     */
    suspend fun fetchSimilar(
        settings: AppSettings,
        kind: String,
        title: String,
        originalTitle: String = "",
        year: Int? = null,
        tmdbId: Int? = null,
        doubanId: String? = null,
        genres: List<String> = emptyList(),
        forceRefresh: Boolean = false,
    ): List<DiscoverItem> = withContext(Dispatchers.IO) {
        val cleanKind = if (kind == "tv" || kind == "show" || kind == "season" || kind == "episode") "tv" else "movie"
        val cacheKey = "$cleanKind|${tmdbId ?: 0}|${doubanId.orEmpty()}|${title.trim()}|${settings.tmdbKey.isNotBlank()}"
        if (!forceRefresh) {
            similarCache[cacheKey]?.let { if (it.isNotEmpty()) return@withContext it }
        }

        val collected = mutableListOf<DiscoverItem>()

        // 1. TMDB 官方推荐与相似接口
        if (settings.tmdbKey.isNotBlank()) {
            val resolvedTmdbId = tmdbId?.takeIf { it > 0 } ?: runCatching {
                MetaRepository.tmdb(
                    settings,
                    MetaQuery(titles = listOfNotNull(title, originalTitle.ifBlank { null }), year = year, kind = cleanKind),
                )?.tmdbId?.takeIf { it > 0 }
            }.getOrNull()

            if (resolvedTmdbId != null) {
                runCatching { fetchTmdbSimilarList(cleanKind, resolvedTmdbId, "recommendations", settings) }
                    .getOrNull()?.let { collected += it }
                if (collected.size < 10) {
                    runCatching { fetchTmdbSimilarList(cleanKind, resolvedTmdbId, "similar", settings) }
                        .getOrNull()?.let { collected += it }
                }
            }
        }

        // 2. 豆瓣免 Key 相似作品推荐（移动端 Rexxar 接口 + 网页端「喜欢这部电影的人也喜欢」兜底）
        if (settings.doubanEnabled && collected.size < 12) {
            val resolvedDoubanId = doubanId?.takeIf { it.isNotBlank() } ?: runCatching {
                Douban.lookup(
                    titles = listOfNotNull(title, originalTitle.ifBlank { null }).filter { it.isNotBlank() },
                    year = year,
                    season = null,
                    imdbId = null,
                    tv = cleanKind == "tv",
                )?.id
            }.getOrNull()

            if (!resolvedDoubanId.isNullOrBlank()) {
                val rexxar = runCatching { fetchDoubanRexxarRecommendations(resolvedDoubanId, cleanKind) }.getOrDefault(emptyList())
                if (rexxar.isNotEmpty()) {
                    collected += rexxar
                } else {
                    runCatching { fetchDoubanHtmlRecommendations(resolvedDoubanId, cleanKind) }
                        .getOrNull()?.let { collected += it }
                }
            }
        }

        // 3. 若仍为空（如非常冷门条目），用同类型高分搜索兜底
        if (collected.isEmpty() && genres.isNotEmpty()) {
            val fallbackQuery = genres.first()
            runCatching { DiscoverRepository.search(settings, fallbackQuery) }
                .getOrNull()?.let { collected += it }
        }

        val normSelf = normalizeTitle(title)
        val normOrig = normalizeTitle(originalTitle)
        val deduped = LinkedHashMap<String, DiscoverItem>()
        for (item in collected) {
            val nt = normalizeTitle(item.title)
            if (nt.isEmpty() || nt == normSelf || (normOrig.isNotEmpty() && nt == normOrig)) continue
            if (tmdbId != null && item.tmdbId == tmdbId) continue
            if (!doubanId.isNullOrBlank() && item.doubanId == doubanId) continue
            if (!deduped.containsKey(nt)) {
                deduped[nt] = item
            } else {
                val prev = deduped[nt]!!
                deduped[nt] = prev.copy(
                    posterUrl = prev.posterUrl ?: item.posterUrl,
                    rating = prev.rating ?: item.rating,
                    ratingLabel = prev.ratingLabel ?: item.ratingLabel,
                    tmdbId = prev.tmdbId ?: item.tmdbId,
                    doubanId = prev.doubanId ?: item.doubanId,
                )
            }
        }

        val finalItems = deduped.values.take(16).toList()
        if (finalItems.isNotEmpty()) {
            similarCache[cacheKey] = finalItems
        }
        finalItems
    }

    /**
     * 调用大模型生成个性化「AI 影视推荐」，并自动匹配海报评分与本机自定义媒体库状态。
     */
    suspend fun fetchAiRecommendations(
        context: Context,
        settings: AppSettings,
        kind: String,
        title: String,
        originalTitle: String = "",
        year: Int? = null,
        genres: List<String> = emptyList(),
        directors: List<String> = emptyList(),
        cast: List<String> = emptyList(),
        overview: String = "",
        recentWatchedTitles: List<String> = emptyList(),
        angle: AiRecommendAngle = AiRecommendAngle.SIMILAR_VIBE,
        customRequirement: String = "",
        excludeTitles: List<String> = emptyList(),
        forceRefresh: Boolean = false,
    ): Pair<String, List<AiRecommendedItem>> = withContext(Dispatchers.IO) {
        val cleanKind = if (kind == "tv" || kind == "show" || kind == "season" || kind == "episode") "tv" else "movie"
        val cacheKey = "$cleanKind|${title.trim()}|${angle.name}|${customRequirement.trim()}"
        val resolved = resolveChatTranslator(settings)
            ?: throw IllegalStateException("尚未配置可用的 AI 模型，请先在设置中填写 DeepSeek / Gemini / 通义千问 / 豆包 / 本地模型")
        val (modelLabel, translator) = resolved

        if (!forceRefresh) {
            aiCache[cacheKey]?.let { if (it.isNotEmpty()) return@withContext modelLabel to it }
        }

        val systemPrompt = """
            你是资深电影与剧集策展顾问。请根据用户正在浏览的影视作品，精准推荐 6 部值得观看的相关电影或剧集。
            规则：
            1. 绝不推荐当前正在浏览的作品本身，也不推荐用户要求排除的作品；
            2. 推荐理由（reason）用中文撰写，控制在 22～48 字，直击两部作品的深层看点关联、叙事魅力或主创风格，避免空泛套话；
            3. kind 字段只能填 "movie" 或 "tv"；title 填最通用的中文正式译名；
            4. 严格只输出合法 JSON 对象，格式如下，不要输出任何 Markdown 代码块以外的解释文字：
            {"recommendations":[{"title":"片名","originalTitle":"原名","year":2020,"kind":"movie","reason":"推荐理由"}]}
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("当前作品：《$title》" + (if (originalTitle.isNotBlank() && originalTitle != title) " ($originalTitle)" else ""))
            appendLine("类型：${if (cleanKind == "tv") "剧集" else "电影"}" + (year?.let { " · $it 年" }.orEmpty()))
            if (genres.isNotEmpty()) appendLine("题材标签：${genres.joinToString(" / ")}")
            if (directors.isNotEmpty()) appendLine("导演/主创：${directors.take(4).joinToString("、")}")
            if (cast.isNotEmpty()) appendLine("主要演员：${cast.take(6).joinToString("、")}")
            if (overview.isNotBlank()) appendLine("剧情简介：${overview.take(260)}")
            if (recentWatchedTitles.isNotEmpty()) {
                appendLine("用户近期观看偏好参考：${recentWatchedTitles.take(6).joinToString("、")}")
            }
            appendLine("推荐侧重点：${angle.promptInstruction}")
            if (customRequirement.isNotBlank()) {
                appendLine("用户额外要求：${customRequirement.trim()}")
            }
            if (excludeTitles.isNotEmpty()) {
                appendLine("请避开以下已推荐过的作品：${excludeTitles.take(12).joinToString("、")}")
            }
        }

        val rawReply = translator.chat(systemPrompt, userPrompt)
        val rawItems = parseAiRecommendationsJson(rawReply)
        if (rawItems.isEmpty()) {
            throw IllegalStateException("AI 返回的推荐列表为空，请重试")
        }

        val normSelf = normalizeTitle(title)
        val filteredRaw = rawItems.filter { normalizeTitle(it.title) != normSelf }.take(6)

        val enriched = coroutineScope {
            filteredRaw.map { rec ->
                async {
                    val searchHits = runCatching {
                        DiscoverRepository.search(settings, rec.title)
                    }.getOrDefault(emptyList())

                    val bestMatch = searchHits.firstOrNull { hit ->
                        val ht = normalizeTitle(hit.title)
                        val rt = normalizeTitle(rec.title)
                        ht == rt || (rec.year != null && hit.year == rec.year)
                    } ?: searchHits.firstOrNull()

                    val discoverItem = bestMatch?.copy(
                        kind = bestMatch.kind.ifBlank { rec.kind },
                    ) ?: DiscoverItem(
                        id = "ai:${rec.kind}:${rec.title}:${rec.year ?: 0}",
                        kind = rec.kind,
                        title = rec.title,
                        originalTitle = rec.originalTitle,
                        year = rec.year,
                        overview = rec.reason,
                    )

                    val libHit = CustomMediaLibraryStore.matchInScrapedLibraries(
                        context = context,
                        title = discoverItem.title,
                        origTitle = discoverItem.originalTitle.ifBlank { rec.originalTitle },
                        tmdbId = discoverItem.tmdbId,
                        doubanId = discoverItem.doubanId,
                        imdbId = discoverItem.imdbId,
                        kind = discoverItem.kind,
                    ).firstOrNull()

                    AiRecommendedItem(
                        item = discoverItem,
                        reason = rec.reason,
                        inLibraryName = libHit?.library?.name,
                    )
                }
            }.awaitAll()
        }

        aiCache[cacheKey] = enriched
        modelLabel to enriched
    }

    /**
     * 发现页「AI 推荐影片」：根据用户自然语言描述的需求（题材、氛围、剧情设定、年代、地区、观影场景等），
     * 智能推荐匹配的影视作品，并并发补齐高清海报、豆瓣/TMDB 评分与本地刮削媒体库可播角标。
     */
    suspend fun fetchAiRecommendationsByDescription(
        context: Context,
        settings: AppSettings,
        requirement: String,
        filter: AiDiscoverFilter = AiDiscoverFilter.ALL,
        recentWatchedTitles: List<String> = emptyList(),
        excludeTitles: List<String> = emptyList(),
        forceRefresh: Boolean = false,
    ): Pair<String, List<AiRecommendedItem>> = withContext(Dispatchers.IO) {
        val cleanReq = requirement.trim()
        require(cleanReq.isNotEmpty()) { "请先输入你想看的影视需求描述" }

        val resolved = resolveChatTranslator(settings)
            ?: throw IllegalStateException("尚未配置可用的 AI 模型，请先配置 DeepSeek / Gemini / 通义千问 / 豆包 / Claude / 本地模型")
        val (modelLabel, translator) = resolved

        val cacheKey = "discover_desc|${filter.name}|$cleanReq"
        if (!forceRefresh && excludeTitles.isEmpty()) {
            aiCache[cacheKey]?.let { if (it.isNotEmpty()) return@withContext modelLabel to it }
        }

        val systemPrompt = """
            你是资深电影与剧集策展顾问。用户会用自然语言描述想看的影视作品需求（如题材类型、剧情设定、叙事节奏、情绪氛围、年代地区、类似作品或避雷条件）。
            请精准推荐 8 部最契合用户需求的真实电影或剧集。
            规则：
            1. 严格遵循用户的类型筛选、题材设定、年代地区与避雷要求，绝不虚构不存在的作品，也不推荐要求排除的作品；
            2. 推荐理由（reason）用中文撰写，控制在 24～56 字，具体点明该作如何契合用户的描述需求与核心看点，不剧透关键结局；
            3. kind 字段只能填 "movie" 或 "tv"；title 填最通用的中文正式译名，originalTitle 填原片名；
            4. 严格只输出合法 JSON 对象，格式如下，不要输出任何额外解释文字：
            {"recommendations":[{"title":"片名","originalTitle":"原名","year":2022,"kind":"movie","reason":"契合需求的推荐理由"}]}
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("用户观影需求：$cleanReq")
            appendLine("作品类型限制：${filter.promptConstraint}")
            if (recentWatchedTitles.isNotEmpty()) {
                appendLine("用户近期看过的作品（可辅助参考口味，但以本次需求描述为最高优先）：${recentWatchedTitles.take(8).joinToString("、")}")
            }
            if (excludeTitles.isNotEmpty()) {
                appendLine("请务必避开以下已推荐过的作品，换一批新的高分选择：${excludeTitles.take(18).joinToString("、")}")
            }
        }

        val rawReply = translator.chat(systemPrompt, userPrompt)
        val rawItems = parseAiRecommendationsJson(rawReply)
        if (rawItems.isEmpty()) {
            throw IllegalStateException("AI 未返回有效的影视推荐列表，请重试或换个描述方式")
        }

        val normExcluded = excludeTitles.map(::normalizeTitle).toSet()
        val filteredRaw = rawItems
            .filter { rec ->
                val nt = normalizeTitle(rec.title)
                nt.isNotEmpty() && nt !in normExcluded && when (filter) {
                    AiDiscoverFilter.ALL -> true
                    AiDiscoverFilter.MOVIE -> rec.kind == "movie"
                    AiDiscoverFilter.TV -> rec.kind == "tv"
                }
            }
            .ifEmpty { rawItems }
            .take(8)

        val enriched = coroutineScope {
            filteredRaw.map { rec ->
                async {
                    val searchHits = runCatching {
                        DiscoverRepository.search(settings, rec.title)
                    }.getOrDefault(emptyList())

                    val rt = normalizeTitle(rec.title)
                    val ro = normalizeTitle(rec.originalTitle)
                    val bestMatch = searchHits.firstOrNull { hit ->
                        val ht = normalizeTitle(hit.title)
                        val ho = normalizeTitle(hit.originalTitle)
                        val titleExact = ht == rt || (ro.isNotEmpty() && (ho == ro || ht == ro))
                        titleExact && (rec.year == null || hit.year == null || kotlin.math.abs(hit.year - rec.year) <= 1)
                    } ?: searchHits.firstOrNull { hit ->
                        val ht = normalizeTitle(hit.title)
                        ht == rt || (rec.year != null && hit.year == rec.year)
                    } ?: searchHits.firstOrNull()

                    val finalKind = when (filter) {
                        AiDiscoverFilter.MOVIE -> "movie"
                        AiDiscoverFilter.TV -> "tv"
                        AiDiscoverFilter.ALL -> bestMatch?.kind?.ifBlank { rec.kind } ?: rec.kind
                    }

                    val discoverItem = bestMatch?.copy(
                        kind = finalKind,
                    ) ?: DiscoverItem(
                        id = "ai:${finalKind}:${rec.title}:${rec.year ?: 0}",
                        kind = finalKind,
                        title = rec.title,
                        originalTitle = rec.originalTitle,
                        year = rec.year,
                        overview = rec.reason,
                    )

                    val libHit = CustomMediaLibraryStore.matchInScrapedLibraries(
                        context = context,
                        title = discoverItem.title,
                        origTitle = discoverItem.originalTitle.ifBlank { rec.originalTitle },
                        tmdbId = discoverItem.tmdbId,
                        doubanId = discoverItem.doubanId,
                        imdbId = discoverItem.imdbId,
                        kind = discoverItem.kind,
                    ).firstOrNull()

                    AiRecommendedItem(
                        item = discoverItem,
                        reason = rec.reason,
                        inLibraryName = libHit?.library?.name,
                    )
                }
            }.awaitAll()
        }

        if (excludeTitles.isEmpty()) {
            aiCache[cacheKey] = enriched
        }
        modelLabel to enriched
    }

    internal data class RawAiRec(
        val title: String,
        val originalTitle: String,
        val year: Int?,
        val kind: String,
        val reason: String,
    )

    internal fun parseAiRecommendationsJson(raw: String): List<RawAiRec> {
        val cleaned = raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        val arr: JSONArray = run {
            val objStart = cleaned.indexOf('{')
            val objEnd = cleaned.lastIndexOf('}')
            if (objStart >= 0 && objEnd > objStart) {
                val obj = runCatching { JSONObject(cleaned.substring(objStart, objEnd + 1)) }.getOrNull()
                if (obj != null) {
                    obj.optJSONArray("recommendations")
                        ?: obj.optJSONArray("items")
                        ?: obj.optJSONArray("list")
                } else null
            } else null
        } ?: run {
            val arrStart = cleaned.indexOf('[')
            val arrEnd = cleaned.lastIndexOf(']')
            if (arrStart >= 0 && arrEnd > arrStart) {
                runCatching { JSONArray(cleaned.substring(arrStart, arrEnd + 1)) }.getOrNull()
            } else null
        } ?: return emptyList()

        val result = mutableListOf<RawAiRec>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("title").trim().removeSurrounding("《", "》")
            if (t.isEmpty()) continue
            val k = if (o.optString("kind").lowercase() in setOf("tv", "show", "series", "剧集", "电视剧")) "tv" else "movie"
            val y = o.optInt("year", 0).takeIf { it in 1900..2100 }
            val r = o.optString("reason").trim().ifEmpty { "AI 精选同类型高口碑佳作" }
            result += RawAiRec(
                title = t,
                originalTitle = o.optString("originalTitle").trim(),
                year = y,
                kind = k,
                reason = r,
            )
        }
        return result
    }

    private fun fetchTmdbSimilarList(
        kind: String,
        tmdbId: Int,
        endpoint: String,
        settings: AppSettings,
    ): List<DiscoverItem> {
        val base = settings.tmdbBaseUrl.ifBlank { Tmdb.DEFAULT_BASE }.trimEnd('/')
        val key = settings.tmdbKey.trim()
        val bearer = key.startsWith("eyJ") && key.length > 40
        val url = buildString {
            append("$base/$kind/$tmdbId/$endpoint?language=zh-CN&page=1")
            if (!bearer) append("&api_key=${URLEncoder.encode(key, "UTF-8")}")
        }
        val req = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (bearer) header("Authorization", "Bearer $key")
        }.build()
        val body = cloudHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string().orEmpty()
        }
        return DiscoverRepository.parseTmdbListJson(body, fallbackKind = kind, imageBase = settings.tmdbImageBase)
    }

    private fun fetchDoubanRexxarRecommendations(doubanId: String, defaultKind: String): List<DiscoverItem> {
        val kinds = if (defaultKind == "tv") listOf("tv", "movie") else listOf("movie", "tv")
        for (k in kinds) {
            val url = "https://m.douban.com/rexxar/api/v2/$k/$doubanId/recommendations?ck=&for_mobile=1"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DOUBAN_MOBILE_UA)
                .header("Referer", "https://m.douban.com/movie/subject/$doubanId/")
                .build()
            val text = runCatching {
                cloudHttp.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
            }.getOrNull() ?: continue

            val arr = runCatching {
                when {
                    text.trimStart().startsWith("[") -> JSONArray(text)
                    else -> JSONObject(text).let {
                        it.optJSONArray("recommendations") ?: it.optJSONArray("subjects") ?: it.optJSONArray("items")
                    }
                }
            }.getOrNull() ?: continue

            val parsed = arr.objects().mapNotNull { obj ->
                val id = obj.optString("id").trim()
                val title = obj.optString("title").trim()
                if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
                val itemKind = when (obj.optString("type").lowercase()) {
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
                val poster = rawPoster?.let {
                    if (it.startsWith("http://") && it.contains(".doubanio.com/")) "https://" + it.removePrefix("http://") else it
                }
                val ratingVal = obj.optJSONObject("rating")?.optDouble("value", 0.0)?.takeIf { !it.isNaN() && it > 0.0 }
                val year = obj.optString("year").toIntOrNull()
                DiscoverItem(
                    id = "douban:$id",
                    kind = itemKind,
                    title = title,
                    year = year,
                    posterUrl = poster,
                    rating = ratingVal,
                    ratingLabel = if (ratingVal != null) "豆瓣" else null,
                    doubanId = id,
                )
            }
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun fetchDoubanHtmlRecommendations(doubanId: String, defaultKind: String): List<DiscoverItem> {
        val req = Request.Builder()
            .url("https://movie.douban.com/subject/$doubanId/")
            .header("User-Agent", DOUBAN_DESKTOP_UA)
            .header("Referer", "https://movie.douban.com/")
            .build()
        val html = cloudHttp.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return emptyList()
        val section = html.substringAfter("recommendations-bd", "").substringBefore("</div>", "")
        if (section.isBlank()) return emptyList()

        val dlRegex = Regex("""(?s)<dl>.*?</dl>""")
        val hrefRegex = Regex("""subject/(\d+)/""")
        val imgRegex = Regex("""<img[^>]+src="([^"]+)"""")
        val altRegex = Regex("""alt="([^"]+)"""")
        val rateRegex = Regex("""<span class="subject-rate">\s*([0-9.]+)\s*</span>""")

        return dlRegex.findAll(section).mapNotNull { m ->
            val block = m.value
            val id = hrefRegex.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = altRegex.find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val poster = imgRegex.find(block)?.groupValues?.get(1)?.trim()?.let {
                if (it.startsWith("http://") && it.contains(".doubanio.com/")) "https://" + it.removePrefix("http://") else it
            }
            val rating = rateRegex.find(block)?.groupValues?.get(1)?.toDoubleOrNull()
            DiscoverItem(
                id = "douban:$id",
                kind = defaultKind,
                title = title,
                posterUrl = poster,
                rating = rating,
                ratingLabel = if (rating != null) "豆瓣" else null,
                doubanId = id,
            )
        }.toList()
    }

    private fun normalizeTitle(s: String): String =
        s.lowercase().replace(Regex("""[\s·・:：\-—_!！?？.'"`()\[\]【】《》,，]"""), "")
}
