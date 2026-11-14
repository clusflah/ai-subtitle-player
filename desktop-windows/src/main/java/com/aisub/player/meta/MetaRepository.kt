package com.aisub.player.meta

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.aisub.player.AppSettings
import com.aisub.player.remote.RemoteItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 查影片信息的条件：片名候选、年份、季集，以及媒体服务器或发现页给出的外部编号。 */
data class MetaQuery(
    val titles: List<String>,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** "movie" / "tv"；null 表示不确定 */
    val kind: String? = null,
    val tmdbId: String? = null,
    val imdbId: String? = null,
    val doubanId: String? = null,
) {
    val key get() = listOf(titles.firstOrNull(), year, season, episode, kind, tmdbId, imdbId, doubanId).joinToString("|")
    val episodic get() = kind == "tv" || season != null || episode != null

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

/** 各来源查询结果的内存缓存，以及用户手动指定的匹配（按文件地址保存）。网络错误不缓存，下次进来重试。 */
object MetaRepository {

    private val cache = LruCache<String, Result<Any?>>(200)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> memo(key: String, block: () -> T?): T? {
        cache.get(key)?.let { return it.getOrThrow() as T? }
        val result = withContext(Dispatchers.IO) { runCatching(block) }
        if (result.isSuccess) cache.put(key, result)
        return result.getOrThrow()
    }

    suspend fun tmdb(settings: AppSettings, q: MetaQuery): MediaInfo? {
        if (settings.tmdbKey.isBlank() || q.titles.isEmpty() && q.tmdbId == null && q.imdbId == null) return null
        return memo("tmdb|${q.key}") { Tmdb(settings.tmdbKey, settings.tmdbBaseUrl, settings.tmdbImageBase).lookup(q) }
    }

    /** 豆瓣：优先用已查到的豆瓣编号、IMDb 编号和中文片名。 */
    suspend fun douban(q: MetaQuery, info: MediaInfo?): DoubanRating? {
        val titles = (listOfNotNull(info?.title, info?.originalTitle) + q.titles).filter { it.isNotEmpty() }.distinct()
        val imdb = info?.imdbId ?: q.imdbId
        val year = q.year ?: info?.year?.toIntOrNull()?.takeIf { q.season == null || q.season <= 1 }
        val id = info?.doubanId ?: q.doubanId
        return memo("douban|" + listOf(id, imdb, titles.firstOrNull(), year, q.season).joinToString("|")) {
            Douban.lookup(titles, year, q.season, imdb, tv = q.episodic || info?.kind == "tv", doubanId = id)
        }
    }

    suspend fun bangumi(q: MetaQuery, info: MediaInfo?): BangumiResult? {
        val titles = (listOfNotNull(info?.originalTitle, info?.title) + q.titles).filter { it.isNotEmpty() }.distinct()
        val year = info?.year?.toIntOrNull() ?: q.year
        return memo("bgm|" + listOf(titles.firstOrNull(), year, q.season, q.episode).joinToString("|")) {
            Bangumi.lookup(titles, year, q.season, q.episode)
        }
    }

    suspend fun tvdb(settings: AppSettings, q: MetaQuery, info: MediaInfo?): MediaInfo? {
        if (settings.tvdbKey.isBlank()) return null
        val titles = (listOfNotNull(info?.originalTitle, info?.title) + q.titles).filter { it.isNotEmpty() }.distinct()
        val query = q.copy(titles = titles, kind = q.kind ?: info?.kind)
        return memo("tvdb|${info?.tvdbId}|${info?.imdbId}|${query.key}") {
            Tvdb(settings.tvdbKey, settings.tvdbPin).lookup(query, info?.tvdbId, info?.imdbId ?: q.imdbId)
        }
    }

    suspend fun fanart(settings: AppSettings, info: MediaInfo): FanartArt? {
        if (settings.fanartKey.isBlank()) return null
        return memo("fanart|${info.kind}|${info.tmdbId}|${info.imdbId}|${info.tvdbId}") {
            FanartTv.lookup(settings.fanartKey, info.kind, info.tmdbId, info.imdbId, info.tvdbId)
        }
    }

    private val posterGate = kotlinx.coroutines.sync.Semaphore(2)

    suspend fun poster(settings: AppSettings, item: RemoteItem): String? {
        val kind = item.meta?.kind
        if (!settings.metaOnline || kind == "episode" || item.meta?.title == null) return null
        return posterGate.withPermit {
            val q = MetaQuery.of(item)
            runCatching { tmdb(settings, q)?.posterUrl }.getOrNull()
                ?: if (settings.doubanEnabled) runCatching { douban(q, null)?.info?.posterUrl }.getOrNull() else null
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("meta_overrides", Context.MODE_PRIVATE)

    fun override(context: Context, fileKey: String): Pair<String, Int?>? =
        prefs(context).getString(fileKey, null)?.let { v -> v.substringBefore('\n') to v.substringAfter('\n', "").toIntOrNull() }

    fun setOverride(context: Context, fileKey: String, title: String, year: Int?) {
        prefs(context).edit().apply {
            if (title.isBlank()) remove(fileKey) else putString(fileKey, title.trim() + "\n" + (year ?: ""))
        }.apply()
    }

    private fun offPrefs(context: Context) = context.getSharedPreferences("meta_disabled", Context.MODE_PRIVATE)

    fun isDisabled(context: Context, fileKey: String): Boolean = offPrefs(context).getBoolean(fileKey, false)

    fun setDisabled(context: Context, fileKey: String, disabled: Boolean) {
        offPrefs(context).edit().apply { if (disabled) putBoolean(fileKey, true) else remove(fileKey) }.apply()
        disabledVersion++
    }

    var disabledVersion by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun onlineAllowed(context: Context, settings: AppSettings, fileKey: String): Boolean =
        settings.metaOnline && !isDisabled(context, fileKey)
}
