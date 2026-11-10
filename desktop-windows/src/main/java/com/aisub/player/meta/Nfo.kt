package com.aisub.player.meta

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Kodi / Emby / Jellyfin / tinyMediaManager 生成的 .nfo 文件里的信息。 */
data class NfoInfo(
    /** "movie" / "tv" / "episode"；null 表示不是 XML（只有一行链接）或不认识的根元素 */
    val kind: String? = null,
    val title: String = "",
    val originalTitle: String = "",
    val showTitle: String = "",
    val year: Int? = null,
    val plot: String = "",
    val genres: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val rating: Double? = null,
    /** 评分来源，例如 "TMDB"、"IMDb" */
    val ratingSource: String? = null,
    val votes: Int? = null,
    val doubanRating: Double? = null,
    val doubanVotes: Int? = null,
    val doubanId: String? = null,
    val tmdbId: String? = null,
    val imdbId: String? = null,
    val tvdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** NFO 里写的海报、背景图地址（只取 http 地址） */
    val posterUrl: String? = null,
    val fanartUrl: String? = null,
)

object Nfo {

    private val ROOT_END = Regex("""</(movie|tvshow|episodedetails)\s*>""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): NfoInfo? {
        val text = raw.removePrefix("﻿").trim()
        // Kodi 允许 XML 后面再跟一行链接，解析前去掉
        val xml = ROOT_END.findAll(text).lastOrNull()?.let { text.substring(0, it.range.last + 1) }
        val root = xml?.let { runCatching { parseXml(it) }.getOrNull() }
        return root?.let(::fromXml) ?: fromLinks(text)
    }

    private fun parseXml(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        // 不解析 DTD 和外部实体
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        factory.isExpandEntityReferences = false
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
    }

    private fun fromXml(root: Element): NfoInfo {
        fun children(name: String): List<Element> = (0 until root.childNodes.length)
            .mapNotNull { root.childNodes.item(it) as? Element }
            .filter { it.tagName.equals(name, ignoreCase = true) }
        fun text(name: String) = children(name).firstOrNull()?.textContent?.trim().orEmpty()
        fun texts(name: String) = children(name).map { it.textContent.trim() }.filter { it.isNotEmpty() }

        // 新格式：<ratings><rating name="themoviedb" default="true"><value>7.9</value><votes>…</votes></rating></ratings>
        val ratings = children("ratings").flatMap { r ->
            (0 until r.childNodes.length).mapNotNull { r.childNodes.item(it) as? Element }.filter { it.tagName.equals("rating", true) }
        }
        fun value(e: Element, tag: String) = e.getElementsByTagName(tag).item(0)?.textContent?.trim()
        val douban = ratings.firstOrNull { it.getAttribute("name").contains("douban", ignoreCase = true) }
        val main = ratings.filter { it != douban }.let { list -> list.firstOrNull { it.getAttribute("default") == "true" } ?: list.firstOrNull() }
        val oldRating = text("rating").toDoubleOrNull()

        val ids = children("uniqueid").associate { it.getAttribute("type").lowercase() to it.textContent.trim() }
        val plainId = text("id")
        val thumbs = children("thumb")
        val poster = (thumbs.firstOrNull { it.getAttribute("aspect").equals("poster", true) } ?: thumbs.firstOrNull { it.getAttribute("aspect").isEmpty() })
            ?.textContent?.trim()?.takeIf { it.startsWith("http") }
        val fanart = children("fanart").firstOrNull()?.getElementsByTagName("thumb")?.item(0)?.textContent?.trim()?.takeIf { it.startsWith("http") }
            ?: thumbs.firstOrNull { it.getAttribute("aspect").equals("landscape", true) || it.getAttribute("aspect").equals("fanart", true) }
                ?.textContent?.trim()?.takeIf { it.startsWith("http") }

        return NfoInfo(
            kind = when (root.tagName.lowercase()) {
                "movie" -> "movie"
                "tvshow" -> "tv"
                "episodedetails" -> "episode"
                else -> null
            },
            title = text("title"),
            originalTitle = text("originaltitle"),
            showTitle = text("showtitle"),
            year = text("year").toIntOrNull()?.takeIf { it > 1800 }
                ?: listOf("premiered", "releasedate", "aired").firstNotNullOfOrNull { text(it).take(4).toIntOrNull() },
            plot = text("plot").ifEmpty { text("outline") },
            genres = texts("genre"),
            directors = texts("director"),
            cast = children("actor").mapNotNull { a -> a.getElementsByTagName("name").item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() } },
            countries = texts("country"),
            runtimeMinutes = text("runtime").toIntOrNull()?.takeIf { it > 0 },
            rating = main?.let { value(it, "value")?.toDoubleOrNull() } ?: oldRating,
            ratingSource = main?.getAttribute("name")?.let(::ratingLabel) ?: oldRating?.let { "NFO" },
            votes = main?.let { value(it, "votes")?.replace(",", "")?.toIntOrNull() } ?: text("votes").replace(",", "").toIntOrNull(),
            doubanRating = douban?.let { value(it, "value")?.toDoubleOrNull() }?.takeIf { it > 0 },
            doubanVotes = douban?.let { value(it, "votes")?.replace(",", "")?.toIntOrNull() },
            doubanId = ids["douban"] ?: text("doubanid").ifEmpty { text("douban_id") }.ifEmpty { null },
            tmdbId = (ids["tmdb"] ?: text("tmdbid").ifEmpty { null })?.takeIf { it.all(Char::isDigit) },
            imdbId = (ids["imdb"] ?: text("imdbid").ifEmpty { text("imdb_id") }.ifEmpty { plainId })?.takeIf { it.startsWith("tt") },
            tvdbId = (ids["tvdb"] ?: text("tvdbid").ifEmpty { null })?.takeIf { it.all(Char::isDigit) },
            season = text("season").toIntOrNull()?.takeIf { it >= 0 },
            episode = text("episode").toIntOrNull()?.takeIf { it > 0 },
            posterUrl = poster,
            fanartUrl = fanart,
        )
    }

    private fun ratingLabel(name: String) = when (name.lowercase()) {
        "themoviedb", "tmdb" -> "TMDB"
        "imdb" -> "IMDb"
        "tvdb" -> "TVDB"
        "trakt" -> "Trakt"
        "" -> "NFO"
        else -> name
    }

    /** 不是 XML 的 NFO：只有一行 IMDb / TMDB / 豆瓣链接。 */
    private fun fromLinks(text: String): NfoInfo? {
        val tmdb = Regex("""themoviedb\.org/(movie|tv)/(\d+)""").find(text)
        val imdb = Regex("""\btt\d{7,}\b""").find(text)?.value
        val douban = Regex("""douban\.com/subject/(\d+)""").find(text)?.groupValues?.get(1)
        if (tmdb == null && imdb == null && douban == null) return null
        return NfoInfo(
            kind = tmdb?.groupValues?.get(1),
            tmdbId = tmdb?.groupValues?.get(2),
            imdbId = imdb,
            doubanId = douban,
        )
    }

    /** Emby / Jellyfin / Plex 命名约定里写在文件夹名或文件名中的编号：{tmdb-123}、[tmdbid=123]、{imdb-tt123}。 */
    fun idsFromName(name: String): Pair<String?, String?> {
        val tmdb = Regex("""[\[{(]\s*tmdb(?:id)?\s*[-=:]\s*(\d+)\s*[\]})]""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)
        val imdb = Regex("""[\[{(]\s*imdb(?:id)?\s*[-=:]\s*(tt\d+)\s*[\]})]""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)
        return tmdb to imdb
    }
}
