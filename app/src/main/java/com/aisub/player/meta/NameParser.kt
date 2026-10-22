package com.aisub.player.meta

/** 从文件名里猜出的片名、年份、季和集。 */
data class ParsedName(val title: String, val year: Int? = null, val season: Int? = null, val episode: Int? = null) {
    val isEpisode get() = episode != null || season != null

    /**
     * 搜索用的候选片名：完整片名在前；中英混合的名字（"流浪地球 The Wandering Earth"）
     * 再拆成中文部分和英文部分各试一次。
     */
    fun candidates(): List<String> {
        val out = linkedSetOf(title)
        if (CJK.containsMatchIn(title) && LATIN.containsMatchIn(title)) {
            out += title.split(Regex("""\s+""")).filter { CJK.containsMatchIn(it) }.joinToString(" ")
            out += title.split(Regex("""\s+""")).filter { !CJK.containsMatchIn(it) }.joinToString(" ")
        }
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private companion object {
        val CJK = Regex("""[一-鿿]""")
        val LATIN = Regex("""[A-Za-z]{2,}""")
    }
}

/**
 * 文件名解析：去掉扩展名、字幕组标签、分辨率编码等发布信息，留下片名。
 * 支持 "The.Matrix.1999.1080p.BluRay"、"[字幕组] 某剧 第03集"、"Show.S02E05"、"流浪地球2 (2023)" 等常见写法。
 */
object NameParser {

    private val KNOWN_EXT = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "mts", "rmvb", "rm", "3gp", "mpg", "mpeg",
        "vob", "iso", "strm", "mp3", "flac", "aac", "m4a", "wav", "ogg", "opus", "wma", "ape", "dsf",
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "nfo", "txt", "srt", "ass", "ssa", "vtt",
    )

    private val SE = Regex("""(?i)(?<![a-z0-9])S(\d{1,2})[ ._-]?E[P]?(\d{1,4})(?![0-9])""")
    private val SEASON_WORD = Regex("""(?i)(?<![a-z0-9])(?:Season[ ._-]?(\d{1,2})|S(\d{1,2}))(?![0-9a-z])""")
    private val EP_WORD = Regex("""(?i)(?<![a-z0-9])(?:EP?|Episode[ ._-]?)(\d{1,4})(?![0-9])""")
    private val CN_EP = Regex("""第\s*([0-9零〇一二两三四五六七八九十百]+)\s*[集话話]""")
    private val CN_SEASON = Regex("""第\s*([0-9零〇一二两三四五六七八九十]+)\s*季""")
    private val YEAR = Regex("""(?<![0-9])(19[2-9]\d|20[0-4]\d)(?![0-9pP])""")
    private val LEADING_TAG = Regex("""^\s*[\[【][^\]】]*[\]】]\s*""")
    private val BRACKETS = Regex("""[\[【][^\]】]*[\]】]|[(（]\s*[)）]""")
    private val NOISE = Regex(
        """(?i)(?<![a-z0-9])(2160p|1080[pi]|720p|576p|480p|4k|uhd|hdr(10)?\+?|dovi|dolby[ .]?vision|web-?dl|webrip|web|bluray|blu-ray|bdrip|brrip|bd|remux|hdtv|hdrip|dvdrip|dvd|x26[45]|h[ .]?26[45]|hevc|avc|aac(2\.0)?|e?ac-?3|ddp?\d?|dts(-?hd)?|truehd|atmos|flac|10bit|8bit|proper|repack|extended|unrated|uncut|complete|imax|internal|limited|中英字幕|中英双字|国英双语|国语|粤语|简繁|中字|双语|特效字幕|内封|内嵌)(?![a-z0-9])""",
    )

    fun parse(fileName: String): ParsedName {
        var s = fileName.trim()
        val ext = s.substringAfterLast('.', "")
        if (ext.lowercase() in KNOWN_EXT) s = s.dropLast(ext.length + 1)
        // 开头的字幕组标签；如果整个名字都在括号里就保留
        LEADING_TAG.find(s)?.let { if (it.range.last < s.lastIndex) s = s.substring(it.range.last + 1) }
        s = s.replace('_', ' ').replace(Regex("""(?<=[^\s.])\.(?=[^\s.])"""), " ").trim()

        var season: Int? = null
        var episode: Int? = null
        val cuts = ArrayList<Int>()
        SE.find(s)?.let { m ->
            season = m.groupValues[1].toInt()
            episode = m.groupValues[2].toInt()
            cuts += m.range.first
        }
        CN_SEASON.find(s)?.let { m -> season = season ?: cnNumber(m.groupValues[1]); cuts += m.range.first }
        CN_EP.find(s)?.let { m -> episode = episode ?: cnNumber(m.groupValues[1]); cuts += m.range.first }
        if (season == null) SEASON_WORD.find(s)?.let { m ->
            season = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt()
            cuts += m.range.first
        }
        if (episode == null) EP_WORD.find(s)?.let { m -> episode = m.groupValues[1].toInt(); cuts += m.range.first }
        // 片名本身可能是年份（"1917"、"2012"），所以只认不在开头的年份，取最后一个
        val yearMatch = YEAR.findAll(s).lastOrNull { it.range.first > 0 }
        val year = yearMatch?.groupValues?.get(1)?.toInt()
        yearMatch?.let { cuts += it.range.first }
        NOISE.find(s)?.let { if (it.range.first > 0) cuts += it.range.first }
        // 片名后面的 [xxx] 标签
        Regex("""[\[【]""").find(s, 1)?.let { cuts += it.range.first }

        val cut = cuts.filter { it > 0 }.minOrNull() ?: s.length
        // 集数写在最前面时，片名在后面，走下面的去除法
        var title = if (0 in cuts) "" else clean(s.substring(0, cut))
        if (title.isEmpty()) {
            // "第 3 集 · 标题" 这类片名在后面的写法
            title = clean(
                s.replace(SE, " ").replace(CN_EP, " ").replace(CN_SEASON, " ").replace(BRACKETS, " ").replace(NOISE, " "),
            )
        }
        if (title.isEmpty()) title = fileName
        return ParsedName(title, year, season, episode)
    }

    private fun clean(s: String): String = s.replace(BRACKETS, " ")
        .replace(Regex("""\s+"""), " ")
        .trim { it.isWhitespace() || it in "-–—·.,:：([（【_" }

    /** "12"、"十二"、"二十三" → 数字。 */
    internal fun cnNumber(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var total = 0
        var current = 0
        for (c in s) {
            when (c) {
                '百' -> { total += (if (current == 0) 1 else current) * 100; current = 0 }
                '十' -> { total += (if (current == 0) 1 else current) * 10; current = 0 }
                else -> current = digits[c] ?: return null
            }
        }
        return (total + current).takeIf { it > 0 }
    }

    /** 1 → "一"，用于拼豆瓣的 "第二季"。 */
    internal fun toCn(n: Int): String {
        val d = "零一二三四五六七八九"
        return when {
            n < 10 -> d[n].toString()
            n < 20 -> "十" + (if (n % 10 == 0) "" else d[n % 10].toString())
            n < 100 -> d[n / 10] + "十" + (if (n % 10 == 0) "" else d[n % 10].toString())
            else -> n.toString()
        }
    }
}
