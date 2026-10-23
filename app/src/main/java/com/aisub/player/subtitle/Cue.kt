package com.aisub.player.subtitle

/** 一条字幕。时间单位毫秒；translation 为空表示还没翻译；speaker 是说话角色的 id（见 [Role]），未识别为空。 */
data class Cue(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String? = null,
    val speaker: String? = null,
)

/**
 * 视频里的一个角色：由大模型根据台词识别。
 * gender 为 male / female / unknown；voice 是朗读用的语音 ID，voiceEngine 记录它属于哪个 TTS 引擎。
 */
data class Role(
    val id: String,
    val name: String,
    val gender: String = "unknown",
    val voice: String? = null,
    val voiceEngine: String? = null,
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
