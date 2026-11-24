package com.voxmusic.app.meta

import com.voxmusic.app.AppSettings
import com.voxmusic.app.Provider
import com.voxmusic.app.translate.ChatCompletionsTranslator
import com.voxmusic.app.translate.ClaudeTranslator
import com.voxmusic.app.translate.Translator
import org.json.JSONArray
import org.json.JSONObject

data class PlatformSource(
    val name: String,
    val url: String? = null,
)

/** 音乐发现页条目（榜单 / 搜索 / 相似歌曲 / AI 荐歌）。 */
data class DiscoverItem(
    val id: String,
    val kind: String = "music",
    val title: String,
    val originalTitle: String = "",
    val year: Int? = null,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val genres: List<String> = emptyList(),
    val platforms: List<PlatformSource> = emptyList(),
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val neteaseId: String? = null,
    val appleUrl: String? = null,
) {
    fun subtitleLine(): String = buildList {
        artist.takeIf { it.isNotBlank() }?.let { add(it) }
        album.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }?.let { add(it) }
        if (isEmpty() && year != null) add(year.toString())
    }.joinToString(" · ")
}

/** AI 推荐单曲结果（附带大模型个性化推荐理由）。 */
data class AiRecommendedItem(
    val item: DiscoverItem,
    val reason: String,
)

/** 搜索历史与 AI 需求历史 JSON 编解码工具。 */
object AiDiscoverStore {
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

/** 解析当前可用的对话大模型（用于 AI 智能荐歌与相似曲风推荐）。 */
object RecommendationEngine {
    private val CHAT_PROVIDERS = listOf(
        Provider.DEEPSEEK,
        Provider.GEMINI,
        Provider.ALIYUN,
        Provider.VOLC,
        Provider.CLAUDE,
        Provider.OPENAI_COMPAT,
        Provider.LOCAL,
    )

    fun resolveChatTranslator(settings: AppSettings): Pair<String, Translator>? {
        val preferred = listOfNotNull(settings.aiRecommendProvider, settings.provider).distinct()
        val candidates = preferred + CHAT_PROVIDERS.filter { it !in preferred }
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
}

internal fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (i in 0 until length()) optJSONObject(i)?.let(::add)
    }
}

internal object DiscoverRepository {
    fun encodeItemJson(item: DiscoverItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("kind", item.kind)
        put("title", item.title)
        put("originalTitle", item.originalTitle)
        item.year?.let { put("year", it) }
        put("overview", item.overview)
        item.posterUrl?.let { put("posterUrl", it) }
        item.backdropUrl?.let { put("backdropUrl", it) }
        put("genres", JSONArray(item.genres))
        put(
            "platforms",
            JSONArray().apply {
                item.platforms.forEach { p ->
                    put(JSONObject().put("name", p.name).apply { p.url?.let { put("url", it) } })
                }
            },
        )
        put("artist", item.artist)
        put("album", item.album)
        put("durationMs", item.durationMs)
        item.neteaseId?.let { put("neteaseId", it) }
        item.appleUrl?.let { put("appleUrl", it) }
    }

    fun decodeItemJson(o: JSONObject): DiscoverItem? {
        val id = o.optString("id").trim()
        val title = o.optString("title").trim()
        if (id.isEmpty() || title.isEmpty()) return null
        val genresArr = o.optJSONArray("genres")
        val genres = if (genresArr != null) {
            (0 until genresArr.length()).mapNotNull { genresArr.optString(it)?.trim()?.takeIf(String::isNotEmpty) }
        } else emptyList()
        val platforms = o.optJSONArray("platforms").objects().mapNotNull { p ->
            val n = p.optString("name").trim()
            if (n.isEmpty()) null else PlatformSource(n, p.optString("url").trim().takeIf { it.isNotEmpty() })
        }
        return DiscoverItem(
            id = id,
            kind = o.optString("kind").ifBlank { "music" },
            title = title,
            originalTitle = o.optString("originalTitle"),
            year = o.optInt("year", 0).takeIf { it in 1900..2100 },
            overview = o.optString("overview"),
            posterUrl = o.optString("posterUrl").takeIf { it.isNotBlank() },
            backdropUrl = o.optString("backdropUrl").takeIf { it.isNotBlank() },
            genres = genres,
            platforms = platforms,
            artist = o.optString("artist"),
            album = o.optString("album"),
            durationMs = o.optLong("durationMs", 0L),
            neteaseId = o.optString("neteaseId").takeIf { it.isNotBlank() },
            appleUrl = o.optString("appleUrl").takeIf { it.isNotBlank() },
        )
    }
}
