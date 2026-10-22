package com.aisub.player.meta

import com.aisub.player.remote.cloud.cloudHttp
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** Fanart.tv 的高清图：透明标题图（logo）和背景图，已按语言挑好（中文 > 英文 > 无文字）。 */
data class FanartArt(val logo: String?, val background: String?)

/** Fanart.tv：电影认 TMDB / IMDb 编号，剧集认 TheTVDB 编号。需要在设置里填个人 API Key。 */
object FanartTv {

    fun lookup(apiKey: String, kind: String, tmdbId: Int?, imdbId: String?, tvdbId: Int?): FanartArt? {
        val path = if (kind == "tv") tvdbId?.let { "tv/$it" } else (tmdbId?.takeIf { it > 0 }?.toString() ?: imdbId)?.let { "movies/$it" }
        path ?: return null
        val req = Request.Builder().url("https://webservice.fanart.tv/v3/$path?api_key=${apiKey.trim()}").build()
        val j = cloudHttp.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> return null
                resp.code == 401 -> throw IOException("Fanart.tv API Key 无效")
                !resp.isSuccessful -> throw IOException("Fanart.tv 请求失败 HTTP ${resp.code}")
                else -> JSONObject(resp.body?.string().orEmpty())
            }
        }
        fun pick(vararg keys: String): String? = keys.flatMap { j.optJSONArray(it).objects() }
            .sortedWith(compareBy<JSONObject>({ rank(it.optString("lang")) }, { -(it.optString("likes").toIntOrNull() ?: 0) }))
            .firstOrNull()?.optString("url")?.takeIf { it.startsWith("http") }
        return if (kind == "tv") FanartArt(pick("hdtvlogo", "clearlogo"), pick("showbackground"))
        else FanartArt(pick("hdmovielogo", "movielogo"), pick("moviebackground"))
    }

    private fun rank(lang: String) = when (lang) {
        "zh" -> 0
        "en" -> 1
        "00", "" -> 2
        else -> 3
    }
}
