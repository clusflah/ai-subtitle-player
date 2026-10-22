package com.aisub.player.meta

import com.aisub.player.AppSettings

/** 影片信息的来源。主数据源在设置里选，其余按顺序补全缺的字段。 */
enum class MetaSource(val label: String) {
    TMDB("TMDB"),
    DOUBAN("豆瓣"),
    BANGUMI("Bangumi"),
    TVDB("TheTVDB"),
}

/** 一个评分标签：loading 表示还在查，value 为 null 表示查过但没有评分。 */
data class RatingInfo(val label: String, val value: Double?, val votes: Int?, val loading: Boolean = false, val failed: Boolean = false)

/** 详情页要显示的影片信息、各来源评分和外部链接。 */
data class DetailMeta(
    val info: MediaInfo? = null,
    val loading: Boolean = false,
    /** 各来源查询失败的说明 */
    val errors: List<String> = emptyList(),
    /** 查过网上来源，但一个都没找到 */
    val notFound: Boolean = false,
    val ratings: List<RatingInfo> = emptyList(),
    /** 「在豆瓣打开」等：名称 → 地址 */
    val links: List<Pair<String, String>> = emptyList(),
)

/**
 * 详情页的查询流程：本地 NFO → 主数据源 → 其余来源补全缺的简介、海报、背景图、本集信息；
 * 再查豆瓣 / Bangumi 评分，最后用 Fanart.tv 补标题图和背景图。每一步都通过 emit 刷新页面。
 */
object MetaPipeline {

    suspend fun run(
        settings: AppSettings,
        q: MetaQuery,
        localInfo: MediaInfo?,
        hasLocalPoster: Boolean,
        hasLocalBackdrop: Boolean,
        emit: (DetailMeta) -> Unit,
    ) {
        var info = localInfo
        val errors = ArrayList<String>()
        // 评分按显示顺序：豆瓣、Bangumi、TMDB，其余（NFO 里的 IMDb 等）在后
        val ratings = LinkedHashMap<String, RatingInfo>()
        val links = LinkedHashMap<String, String>()
        if (settings.doubanEnabled) ratings["豆瓣"] = RatingInfo("豆瓣", null, null, loading = true)
        localInfo?.doubanRating?.let { ratings["豆瓣"] = RatingInfo("豆瓣", it, localInfo.doubanVotes) }
        localInfo?.doubanId?.let { links["在豆瓣打开"] = "https://movie.douban.com/subject/$it/" }
        if (settings.tmdbKey.isNotBlank()) ratings["TMDB"] = RatingInfo("TMDB", null, null, loading = true)
        localInfo?.takeIf { it.rating > 0 }?.let { ratings[it.ratingLabel] = RatingInfo(it.ratingLabel, it.rating, it.voteCount) }
        var doubanDone = localInfo?.doubanRating != null
        var bangumiDone = false
        var searched = false

        fun snapshot(loading: Boolean) = DetailMeta(
            info = info,
            loading = loading,
            errors = errors.toList(),
            notFound = !loading && searched && info == null,
            ratings = ratings.values.toList(),
            links = buildList {
                addAll(links.toList())
                info?.tmdbUrl?.let { add("在 TMDB 打开" to it) }
                info?.tvdbId?.let { add("在 TheTVDB 打开" to "https://thetvdb.com/dereferrer/${if (info?.kind == "tv") "series" else "movie"}/$it") }
                info?.imdbId?.let { add("在 IMDb 打开" to "https://www.imdb.com/title/$it/") }
            }.distinctBy { it.first },
        )
        emit(snapshot(true))

        fun complete(i: MediaInfo?) = i != null && i.overview.isNotBlank() &&
            (hasLocalPoster || i.posterUrl != null) && (hasLocalBackdrop || i.backdropUrl != null) &&
            (!q.episodic || i.episodeTitle != null)

        suspend fun fetch(src: MetaSource): MediaInfo? = when (src) {
            MetaSource.TMDB -> MetaRepository.tmdb(settings, q.withIds(info)).also { r ->
                ratings["TMDB"] = RatingInfo("TMDB", r?.rating?.takeIf { it > 0 }, r?.voteCount)
            }
            MetaSource.DOUBAN -> MetaRepository.douban(q, info).also { d ->
                doubanDone = true
                ratings["豆瓣"] = RatingInfo("豆瓣", d?.rating, d?.votes)
                d?.let { links["在豆瓣打开"] = it.url }
            }?.info
            MetaSource.BANGUMI -> MetaRepository.bangumi(q, info).also { b ->
                bangumiDone = true
                ratings["Bangumi"] = RatingInfo("Bangumi", b?.rating, b?.votes)
                b?.let { links["在 Bangumi 打开"] = it.url }
            }?.info
            MetaSource.TVDB -> MetaRepository.tvdb(settings, q, info)
        }

        suspend fun tryFetch(src: MetaSource) {
            searched = true
            try {
                fetch(src)?.let { r -> info = info?.fillFrom(r) ?: r }
            } catch (e: Exception) {
                errors += "${src.label}：${e.message ?: e}"
                when (src) {
                    MetaSource.TMDB -> ratings["TMDB"] = RatingInfo("TMDB", null, null, failed = true)
                    MetaSource.DOUBAN -> { doubanDone = true; ratings["豆瓣"] = RatingInfo("豆瓣", null, null, failed = true) }
                    MetaSource.BANGUMI -> { bangumiDone = true; ratings["Bangumi"] = RatingInfo("Bangumi", null, null, failed = true) }
                    MetaSource.TVDB -> Unit
                }
            }
            emit(snapshot(true))
        }

        // 1. 主数据源 + 其余来源依次补全，信息齐了就停
        val primary = runCatching { MetaSource.valueOf(settings.metaPrimary) }.getOrDefault(MetaSource.TMDB)
        val order = (listOf(primary) + listOf(MetaSource.TMDB, MetaSource.DOUBAN, MetaSource.TVDB)).distinct().filter { available(settings, it) }
        for (src in order) {
            if (complete(info)) break
            tryFetch(src)
        }

        // 2. 评分：动画查 Bangumi，豆瓣没查过的补查
        if (settings.bangumiEnabled && !bangumiDone && isAnime(info)) tryFetch(MetaSource.BANGUMI)
        if (settings.doubanEnabled && !doubanDone) tryFetch(MetaSource.DOUBAN)

        // 3. Fanart.tv：透明标题图、背景图
        info?.let { i ->
            if (settings.fanartKey.isNotBlank() && i.logoUrl == null) {
                try {
                    MetaRepository.fanart(settings, i)?.let { art ->
                        info = i.copy(logoUrl = art.logo, backdropUrl = i.backdropUrl ?: art.background.takeIf { !hasLocalBackdrop })
                    }
                } catch (e: Exception) {
                    errors += "Fanart.tv：${e.message ?: e}"
                }
            }
        }

        // 没查的来源（本地信息已经齐了）不显示转圈
        ratings.entries.removeAll { it.value.loading }
        emit(snapshot(false))
    }

    private fun available(s: AppSettings, src: MetaSource) = when (src) {
        MetaSource.TMDB -> s.tmdbKey.isNotBlank()
        MetaSource.DOUBAN -> s.doubanEnabled
        MetaSource.BANGUMI -> s.bangumiEnabled
        MetaSource.TVDB -> s.tvdbKey.isNotBlank()
    }

    private fun isAnime(info: MediaInfo?) = info != null && info.genres.any { it == "动画" || it.equals("Animation", true) }

    /** 已经查到的编号带进下一次查询，TMDB 就能按编号精确取。 */
    private fun MetaQuery.withIds(info: MediaInfo?): MetaQuery = if (info == null) this else copy(
        titles = (listOfNotNull(info.title.ifEmpty { null }, info.originalTitle.ifEmpty { null }) + titles).distinct(),
        kind = kind ?: info.kind,
        tmdbId = tmdbId ?: info.tmdbId.takeIf { it > 0 }?.toString(),
        imdbId = imdbId ?: info.imdbId,
    )
}
