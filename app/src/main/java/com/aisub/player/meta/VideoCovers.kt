package com.aisub.player.meta

import android.content.Context
import com.aisub.player.AppSettings
import com.aisub.player.media.MediaEntry

/**
 * 「继续观看」卡片用的封面图（横的背景图优先，没有用海报），按视频地址记住：
 * - 详情页查到影片信息时顺手记下（[remember]）；
 * - 没进过详情页的，按文件名到网上（TMDB / 豆瓣）找一次（[find]），找不到也记下，不反复查。
 * 都没有时卡片用播放时截的画面。
 */
object VideoCovers {

    private fun prefs(context: Context) = context.getSharedPreferences("video_covers", Context.MODE_PRIVATE)

    fun remember(context: Context, uri: String, url: String?) {
        if (url.isNullOrEmpty()) return
        prefs(context).edit().putString(uri, url).apply()
    }

    /** 记下的封面；null 表示还没查过，"" 表示查过、没有 */
    fun saved(context: Context, uri: String): String? = prefs(context).getString(uri, null)

    /** 封面地址；找不到返回 null。会联网，网络出错时不记，下次再查 */
    suspend fun find(context: Context, settings: AppSettings, entry: MediaEntry): String? {
        if (!entry.isVideo || entry.isLive) return null
        saved(context, entry.uri)?.let { return it.ifEmpty { null } }
        if (settings.tmdbKey.isBlank() && !settings.doubanEnabled) return null
        val query = MetaRepository.override(context, entry.uri)?.let { (t, y) -> MetaQuery.manual(t, y) }
            ?: MetaQuery.manual(entry.title, null)
        val url = try {
            val info = MetaRepository.tmdb(settings, query)
            info?.backdropUrl ?: info?.posterUrl
                ?: if (settings.doubanEnabled) MetaRepository.douban(query, info)?.info?.posterUrl else null
        } catch (e: Exception) {
            return null
        }
        prefs(context).edit().putString(entry.uri, url.orEmpty()).apply()
        return url
    }
}
