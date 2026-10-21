package com.aisub.player.subtitle

/**
 * 解析外挂字幕文件（SRT / WebVTT）。
 * 双语 SRT（两行：译文在上、原文在下，本应用导出的格式）会拆成 text + translation。
 */
object SubtitleParser {
    private val TIME = Regex("""(\d{1,2}:)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(\d{1,2}:)?(\d{1,2}):(\d{1,2})[,.](\d{1,3})""")
    private val TAGS = Regex("<[^>]+>|\\{\\\\[^}]*\\}")

    fun parse(content: String): List<Cue> {
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
