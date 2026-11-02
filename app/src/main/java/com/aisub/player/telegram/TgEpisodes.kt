package com.aisub.player.telegram

/**
 * 同一部剧的各集排成播放顺序：从文件名认出集数，同一集有几个版本（不同字幕组、不同来源）时
 * 接着放和点的那个同一版本的（文件名除了集数都一样），认不出集数就按发送时间。
 */
object TgEpisodes {
    private val PATTERNS = listOf(
        Regex("""第\s*(\d{1,4})\s*[集话話]"""),
        Regex("""(?i)S\d{1,2}E(\d{1,3})"""),
        Regex("""\[(\d{1,3})(?:v\d)?]"""),
        Regex("""(?i)\bE[Pp]?(\d{1,3})\b"""),
        Regex("""(?:^|[_\s\-])(\d{1,3})(?:v\d)?(?=[_\s.\[\-]|$)"""),
    )

    /** 文件名里的集数，和集数在文件名里的位置（去掉它就是「版本」） */
    private fun find(fileName: String): MatchResult? {
        val name = fileName.substringBeforeLast('.')
        return PATTERNS.firstNotNullOfOrNull { it.find(name) }
    }

    fun episodeOf(fileName: String): Int? = find(fileName)?.groupValues?.get(1)?.toIntOrNull()

    /** 文件名去掉集数：同一个版本的各集是一样的 */
    fun releaseOf(fileName: String): String {
        val name = fileName.substringBeforeLast('.')
        val m = find(fileName) ?: return name.lowercase()
        val g = m.groups[1]!!.range
        return (name.substring(0, g.first) + "#" + name.substring(g.last + 1)).lowercase()
    }

    /** [all]（同一部剧的视频）排成播放顺序，[clicked] 一定在里面 */
    fun playOrder(clicked: TgVideo, all: List<TgVideo>): List<TgVideo> {
        val byTime = all.sortedBy { it.messageId }
        val ep = episodeOf(clicked.fileName) ?: return byTime
        val withEp = all.filter { episodeOf(it.fileName) != null }
        if (withEp.size * 2 < all.size) return byTime
        // 同一个版本的有好几集：只放这个版本的
        val release = releaseOf(clicked.fileName)
        val same = withEp.filter { releaseOf(it.fileName) == release }
        val pool = if (same.size >= 2) same else {
            // 每集挑一个（点的那集用点的那个）
            withEp.groupBy { episodeOf(it.fileName) }.map { (e, vs) -> if (e == ep) clicked else vs.minBy { it.messageId } }
        }
        return (pool + clicked).distinctBy { it.messageId }
            .sortedWith(compareBy<TgVideo> { episodeOf(it.fileName) }.thenBy { it.messageId })
    }
}
