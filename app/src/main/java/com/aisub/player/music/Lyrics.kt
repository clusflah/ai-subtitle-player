package com.aisub.player.music

/** 一行歌词；timeMs 为 null 表示不带时间的纯文本歌词。 */
data class LyricLine(val timeMs: Long?, val text: String)

/**
 * 歌词。synced 为 true 时每行都有时间，按时间排好序，可以跟着播放滚动；
 * 否则是纯文本，只能整篇显示。
 */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String = "") {

    /** 播放到 positionMs 时该高亮的行；还没到第一行返回 -1。 */
    fun indexAt(positionMs: Long): Int {
        if (!synced) return -1
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs!! <= positionMs) {
                found = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        return found
    }

    /** 存回 LRC 文本（纯文本歌词按行存）。 */
    fun toLrc(): String = lines.joinToString("\n") { l ->
        l.timeMs?.let { t -> "[%02d:%02d.%02d]".format(t / 60000, t / 1000 % 60, t % 1000 / 10) + l.text } ?: l.text
    }

    companion object {
        private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
        /** 标签行：[ti:]、[ar:]、[al:]、[by:]、[length:] 等 */
        private val TAG = Regex("""^\[[a-zA-Z#]+:.*]$""")
        /** 逐字时间：<00:12.34> 或 [00:12.34] 夹在文字中间 */
        private val WORD_TIME = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

        /**
         * 解析 LRC。一行可以有多个时间（[00:12.00][01:30.00]同一句），支持 [offset:]，去掉逐字时间。
         * 一个时间都没有时当作纯文本歌词。空文本返回 null。
         */
        fun parse(text: String, source: String = ""): Lyrics? {
            val raw = text.replace("\r\n", "\n").replace('\r', '\n').lines()
            var offset = 0L
            val timed = mutableListOf<LyricLine>()
            val plain = mutableListOf<String>()
            for (line in raw) {
                val l = line.trim()
                OFFSET.find(l)?.let { offset = it.groupValues[1].toLongOrNull() ?: 0L; continue }
                var rest = l
                val times = mutableListOf<Long>()
                while (true) {
                    val m = TIME.matchAt(rest, 0) ?: break
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val frac = m.groupValues[3]
                    val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                    times += min * 60_000 + sec * 1000 + ms
                    rest = rest.substring(m.range.last + 1)
                }
                val body = rest.replace(WORD_TIME, "").replace(TIME, "").trim()
                if (times.isNotEmpty()) times.forEach { timed += LyricLine(it, body) }
                else if (!TAG.matches(l)) plain += body
            }
            if (timed.isNotEmpty()) {
                // LRC 的 offset 为正表示歌词提前出现
                val lines = timed.map { it.copy(timeMs = (it.timeMs!! - offset).coerceAtLeast(0)) }.sortedBy { it.timeMs }
                return Lyrics(lines, synced = true, source = source)
            }
            val lines = plain.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
            if (lines.isEmpty()) return null
            return Lyrics(lines.map { LyricLine(null, it) }, synced = false, source = source)
        }
    }
}
