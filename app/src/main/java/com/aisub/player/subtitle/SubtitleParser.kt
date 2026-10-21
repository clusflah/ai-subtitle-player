package com.aisub.player.subtitle

/**
 * 解析外挂字幕文件（SRT / WebVTT / ASS / SSA）。
 * 双语 SRT（两行：译文在上、原文在下，本应用导出的格式）会拆成 text + translation。
 */
object SubtitleParser {
    private val TIME = Regex("""(\d{1,2}:)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(\d{1,2}:)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})""")
    private val TAGS = Regex("<[^>]+>|\\{\\\\[^}]*\\}")

    fun parse(content: String): List<Cue> {
        if (ASS_EVENTS.containsMatchIn(content)) return parseAss(content)
        val lines = content.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            val m = TIME.find(lines[i])
            if (m == null) {
                i++
                continue
            }
            val g = m.groupValues
            val start = toMs(g[1], g[2], g[3], g[4])
            val end = toMs(g[5], g[6], g[7], g[8])
            i++
            val body = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                // 下一条的时间行（有的文件缺空行）
                if (TIME.containsMatchIn(lines[i])) break
                body.add(lines[i].replace(TAGS, "").trim())
                i++
            }
            // 去掉 SRT 序号行被误读的情况
            val text = body.filter { it.isNotEmpty() }
            if (text.isEmpty()) continue
            val cue = if (text.size == 2 && looksBilingual(text[0], text[1])) {
                Cue(cues.size + 1, start, end, text[1], translation = text[0])
            } else {
                Cue(cues.size + 1, start, end, text.joinToString(" "))
            }
            cues.add(cue)
        }
        return cues.sortedBy { it.startMs }.mapIndexed { idx, c -> c.copy(id = idx + 1) }
    }

    private val ASS_EVENTS = Regex("""^\s*\[Events]""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val ASS_TIME = Regex("""(\d+):(\d{1,2}):(\d{1,2})[.:](\d{1,3})""")
    private val ASS_OVERRIDE = Regex("""\{[^}]*\}""")
    private val ASS_DRAWING = Regex("""\\p[1-9]""")

    /**
     * ASS/SSA：按 [Events] 里的 Format 行找到 Start、End、Text 列。
     * 去掉 {\...} 样式代码，\N 换行；绘图指令（\p1）和空行跳过；同一时间的多条（如中英分两条）合并。
     */
    private fun parseAss(content: String): List<Cue> {
        var format = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        val raw = ArrayList<Triple<Long, Long, List<String>>>()
        var inEvents = false
        for (line in content.removePrefix("\uFEFF").lineSequence()) {
            val t = line.trim()
            if (t.startsWith("[")) { inEvents = t.equals("[Events]", ignoreCase = true); continue }
            if (!inEvents) continue
            when {
                t.startsWith("Format:", ignoreCase = true) ->
                    format = t.substringAfter(':').split(',').map { it.trim().lowercase() }
                t.startsWith("Dialogue:", ignoreCase = true) -> {
                    val parts = t.substringAfter(':').split(',', limit = format.size)
                    if (parts.size < format.size) continue
                    val start = assTime(parts[format.indexOf("start")]) ?: continue
                    val end = assTime(parts[format.indexOf("end")]) ?: continue
                    val text = parts[format.indexOf("text").takeIf { it >= 0 } ?: (format.size - 1)]
                    if (ASS_DRAWING.containsMatchIn(text)) continue
                    val lines = text.replace(ASS_OVERRIDE, "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                        .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                    if (lines.isNotEmpty()) raw += Triple(start, end, lines)
                }
            }
        }
        val merged = raw.groupBy { it.first to it.second }.map { (k, v) -> Triple(k.first, k.second, v.flatMap { it.third }.distinct()) }
        return merged.sortedBy { it.first }.mapIndexed { idx, (start, end, text) ->
            if (text.size == 2 && looksBilingual(text[0], text[1])) {
                // 中文在上、外文在下是最常见的双语排版
                val (cn, other) = if (hasCjk(text[0])) text[0] to text[1] else text[1] to text[0]
                Cue(idx + 1, start, end, other, translation = cn)
            } else {
                Cue(idx + 1, start, end, text.joinToString(" "))
            }
        }
    }

    private fun assTime(s: String): Long? {
        val m = ASS_TIME.find(s.trim()) ?: return null
        val (h, mi, se, frac) = m.destructured
        // ASS 用百分之一秒
        val millis = when (frac.length) { 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
        return ((h.toLong() * 60 + mi.toLong()) * 60 + se.toLong()) * 1000 + millis
    }

    /** 一行中文一行西文时，视为"译文 + 原文"的双语字幕。 */
    private fun looksBilingual(first: String, second: String): Boolean =
        hasCjk(first) != hasCjk(second)

    private fun hasCjk(s: String) = s.any { CueSplitter.isCjk(it) }

    private fun toMs(h: String, m: String, s: String, frac: String): Long {
        val hours = h.removeSuffix(":").toLongOrNull() ?: 0
        val millis = frac.padEnd(3, '0').take(3).toLong()
        return ((hours * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + millis
    }
}
