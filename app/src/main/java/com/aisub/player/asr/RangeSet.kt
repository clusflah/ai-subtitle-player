package com.aisub.player.asr

/** 已经识别过的时间段（毫秒，左闭右开）；重叠和相邻的段自动合并。识别线程和界面线程都会读写，所以加锁。 */
class RangeSet {
    private val ranges = ArrayList<LongArray>()

    @Synchronized
    fun clear() = ranges.clear()

    @Synchronized
    fun add(start: Long, end: Long) {
        if (end <= start) return
        var s = start
        var e = end
        val out = ArrayList<LongArray>(ranges.size + 1)
        var inserted = false
        for (r in ranges) {
            when {
                r[1] < s -> out.add(r)
                r[0] > e -> {
                    if (!inserted) { out.add(longArrayOf(s, e)); inserted = true }
                    out.add(r)
                }
                else -> { s = minOf(s, r[0]); e = maxOf(e, r[1]) }
            }
        }
        if (!inserted) out.add(longArrayOf(s, e))
        ranges.clear()
        ranges.addAll(out)
    }

    @Synchronized
    fun contains(ms: Long): Boolean = ranges.any { ms >= it[0] && ms < it[1] }

    /** 从 from 往后第一段还没识别的时间 [start, end)，end 不超过 limit；都识别过了返回 null。 */
    @Synchronized
    fun nextGap(from: Long, limit: Long): Pair<Long, Long>? {
        var cursor = from.coerceAtLeast(0)
        for (r in ranges) {
            if (r[1] <= cursor) continue
            if (r[0] > cursor) return if (cursor < limit) cursor to minOf(r[0], limit) else null
            cursor = r[1]
        }
        return if (cursor < limit) cursor to limit else null
    }

    /** [0, limit) 里已经识别过的总长度 */
    @Synchronized
    fun coveredMs(limit: Long): Long = ranges.sumOf { (minOf(it[1], limit) - minOf(it[0], limit)).coerceAtLeast(0) }

    @Synchronized
    fun snapshot(): List<Pair<Long, Long>> = ranges.map { it[0] to it[1] }

    @Synchronized
    fun load(list: List<Pair<Long, Long>>) {
        ranges.clear()
        list.forEach { add(it.first, it.second) }
    }

    /** 到 limit 为止都识别过了 */
    @Synchronized
    fun isFull(limit: Long): Boolean = ranges.size == 1 && ranges[0][0] <= 0 && ranges[0][1] >= limit
}
