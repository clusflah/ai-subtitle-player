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

/**
 * 在按开始时间排序的字幕列表里找出 [positionMs] 时刻正在显示的字幕：
 * 向前扫描重叠时间轴（解决 ASS/SRT 中短促屏幕字/特效轴嵌套在长对白轴内导致后半句对白消失，或中英双轴时间差几毫秒未合并的问题）。
 */
fun List<Cue>.cueAt(positionMs: Long): Cue? {
    if (isEmpty()) return null
    var lo = 0
    var hi = size - 1
    var lastIdx = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].startMs <= positionMs) {
            lastIdx = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    if (lastIdx < 0) return null

    // 快路径：检查最后一条及往前有限窗口内是否存在重叠活跃轴
    var firstMatch: Cue? = null
    var extraMatches: ArrayList<Cue>? = null
    val minIdx = (lastIdx - 64).coerceAtLeast(0)
    for (i in lastIdx downTo minIdx) {
        val c = this[i]
        if (positionMs - c.startMs > 180_000L) break
        if (positionMs <= c.endMs && (c.text.isNotBlank() || !c.translation.isNullOrBlank())) {
            if (firstMatch == null) {
                firstMatch = c
            } else {
                val list = extraMatches ?: ArrayList<Cue>(4).also {
                    it.add(firstMatch!!)
                    extraMatches = it
                }
                list.add(c)
            }
        }
    }
    val extras = extraMatches ?: return firstMatch
    // 还原按开始时间升序排列
    extras.reverse()
    // 若同时存在超长压制组水印横幅（>=25s）与正常对白轴（<25s），优先展示正常对白与屏幕注释避免遮挡刷屏
    val normalCues = extras.filter { it.endMs - it.startMs < 25_000L }
    val active = if (normalCues.isNotEmpty()) normalCues else extras
    if (active.size == 1) return active[0]

    // 两条单语轴恰好构成「一行中文 + 一行外文」（如双轨分开排版的双语 ASS/SRT）时自动配对为译文+原文
    if (active.size == 2 && active[0].translation == null && active[1].translation == null) {
        val paired = SubtitleParser.tryPairBilingual(active[0], active[1])
        if (paired != null) return paired
    }

    val texts = LinkedHashSet<String>()
    val translations = LinkedHashSet<String>()
    var minStart = active[0].startMs
    var maxEnd = active[0].endMs
    var speaker: String? = null
    for (c in active) {
        if (c.startMs < minStart) minStart = c.startMs
        if (c.endMs > maxEnd) maxEnd = c.endMs
        if (speaker == null && c.speaker != null) speaker = c.speaker
        if (c.text.isNotBlank() && texts.size < 3) texts.add(c.text.trim())
        if (!c.translation.isNullOrBlank() && translations.size < 3) translations.add(c.translation.trim())
    }
    val mergedText = texts.joinToString("\n")
    val mergedTranslation = translations.takeIf { it.isNotEmpty() }?.joinToString("\n")
    return Cue(
        id = active.last().id,
        startMs = minStart,
        endMs = maxEnd,
        text = mergedText.ifEmpty { mergedTranslation.orEmpty() },
        translation = if (mergedText.isEmpty()) null else mergedTranslation,
        speaker = speaker,
    )
}
