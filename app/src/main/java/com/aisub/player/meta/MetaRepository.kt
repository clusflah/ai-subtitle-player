package com.aisub.player.meta

import android.content.Context
import android.util.LruCache
import com.aisub.player.AppSettings
import com.aisub.player.remote.RemoteItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 查影片信息的条件：片名候选、年份、季集，以及媒体服务器给出的外部编号。 */
data class MetaQuery(
    val titles: List<String>,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** "movie" / "tv"；null 表示不确定 */
    val kind: String? = null,
    val tmdbId: String? = null,
    val imdbId: String? = null,
) {
    val key get() = listOf(titles.firstOrNull(), year, season, episode, kind, tmdbId, imdbId).joinToString("|")

    companion object {
        /** 媒体服务器条目用服务器给的片名和编号；普通文件从文件名里解析。 */
        fun of(item: RemoteItem): MetaQuery {
            val m = item.meta
            if (m?.title != null) {
                val kind = if (m.kind == "episode" || m.kind == "season") "tv" else m.kind
                return MetaQuery(
                    titles = listOfNotNull(m.seriesName ?: m.title).filter { it.isNotEmpty() },
                    year = m.year, season = m.season, episode = m.episode, kind = kind,
                    // 单集的编号是这一集的，不能当成剧集的编号用
                    tmdbId = m.tmdbId.takeIf { m.kind == "movie" || m.kind == "tv" },
                    imdbId = m.imdbId.takeIf { m.kind == "movie" || m.kind == "tv" },
                )
            }
            val p = NameParser.parse(item.name)
            return MetaQuery(p.candidates(), p.year, p.season, p.episode)
        }

        fun manual(title: String, year: Int?): MetaQuery {
            val p = NameParser.parse(title)
            return MetaQuery(p.candidates(), year ?: p.year, p.season, p.episode)
        }
    }
}

/** TMDB / 豆瓣查询结果的内存缓存，以及用户手动指定的匹配（按文件地址保存）。 */
object MetaRepository {

    private val tmdbCache = LruCache<String, Result<MediaInfo?>>(100)
    private val doubanCache = LruCache<String, Result<DoubanRating?>>(100)

    suspend fun tmdb(settings: AppSettings, q: MetaQuery): MediaInfo? {
        if (settings.tmdbKey.isBlank() || q.titles.isEmpty() && q.tmdbId == null && q.imdbId == null) return null
        val key = q.key
        tmdbCache.get(key)?.let { return it.getOrThrow() }
        val result = withContext(Dispatchers.IO) {
            runCatching { Tmdb(settings.tmdbKey, settings.tmdbBaseUrl, settings.tmdbImageBase).lookup(q) }
        }
        // 网络错误不缓存，下次进来重试
        if (result.isSuccess) tmdbCache.put(key, result)
        return result.getOrThrow()
    }

    /** 豆瓣：优先用 TMDB 查到的 IMDb 编号和中文片名。 */
    suspend fun douban(q: MetaQuery, info: MediaInfo?): DoubanRating? {
        val titles = (listOfNotNull(info?.title, info?.originalTitle) + q.titles).filter { it.isNotEmpty() }.distinct()
        val imdb = info?.imdbId ?: q.imdbId
        val year = q.year ?: info?.year?.toIntOrNull()?.takeIf { q.season == null || q.season <= 1 }
        val key = listOf(imdb, titles.firstOrNull(), year, q.season).joinToString("|")
        doubanCache.get(key)?.let { return it.getOrThrow() }
        val result = withContext(Dispatchers.IO) { runCatching { Douban.lookup(titles, year, q.season, imdb) } }
        if (result.isSuccess) doubanCache.put(key, result)
        return result.getOrThrow()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("meta_overrides", Context.MODE_PRIVATE)

    /** 用户在「重新匹配」里填的片名和年份。 */
    fun override(context: Context, fileKey: String): Pair<String, Int?>? =
        prefs(context).getString(fileKey, null)?.let { v -> v.substringBefore('\n') to v.substringAfter('\n', "").toIntOrNull() }

    fun setOverride(context: Context, fileKey: String, title: String, year: Int?) {
        prefs(context).edit().apply {
            if (title.isBlank()) remove(fileKey) else putString(fileKey, title.trim() + "\n" + (year ?: ""))
        }.apply()
    }
}
