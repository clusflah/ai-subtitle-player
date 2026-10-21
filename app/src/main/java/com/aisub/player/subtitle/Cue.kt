package com.aisub.player.subtitle

/** 一条字幕。时间单位毫秒；translation 为空表示还没翻译。 */
data class Cue(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String? = null,
)

/** 在按开始时间排序的字幕列表里找出 positionMs 时刻应该显示的那一条。 */
fun List<Cue>.cueAt(positionMs: Long): Cue? {
    var lo = 0
    var hi = size - 1
    var candidate = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].startMs <= positionMs) {
            candidate = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    if (candidate < 0) return null
    val cue = this[candidate]
    return if (positionMs <= cue.endMs) cue else null
}
