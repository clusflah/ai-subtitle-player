package com.aisub.player.subtitle

import kotlin.math.abs

/**
 * 解析外挂字幕文件（SRT / WebVTT / ASS / SSA / SAMI .smi / MicroDVD .sub / LRC）。
 * 双语字幕（译文 + 原文分行，或 ASS 中英双 Dialogue 轴）会自动拆分为 text + translation。
 */
object SubtitleParser {
    private val TIME = Regex(
        """(\d{1,3}\s*[:：]\s*)?(\d{1,2})\s*[:：]\s*(\d{1,2})(?:\s*[,.．:：]\s*(\d{1,3}))?\s*-->\s*(\d{1,3}\s*[:：]\s*)?(\d{1,2})\s*[:：]\s*(\d{1,2})(?:\s*[,.．:：]\s*(\d{1,3}))?""",
    )
    private val TAGS = Regex("<[^>]+>|\\{\\\\[^}]*\\}")
    private val SAMI_SYNC = Regex("""<SYNC\b[^>]*\bStart\s*=\s*["']?(\d+)["']?[^>]*>""", RegexOption.IGNORE_CASE)
    private val SAMI_BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val MICRODVD_LINE = Regex("""^\s*\{(\d+)\}\{(\d+)\}(.+)$""")
    private val LRC_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(content: String): List<Cue> {
        val cleaned = content.replace("\u0000", "").removePrefix("\uFEFF")
        if (cleaned.isBlank()) return emptyList()
        if (ASS_EVENTS.containsMatchIn(cleaned)) return parseAss(cleaned)
        if (SAMI_SYNC.containsMatchIn(cleaned)) {
            val sami = parseSami(cleaned)
            if (sami.isNotEmpty()) return sami
        }
        val lines = cleaned.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            val lineStr = lines[i].removePrefix("\uFEFF")
            val m = TIME.find(lineStr)
            if (m == null) {
                i++
                continue
            }
            val g = m.groupValues
            val start = toMs(g[1], g[2], g[3], g[4])
            val end = toMs(g[5], g[6], g[7], g[8]).coerceAtLeast(start + 200L)
            i++
            val body = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                // 下一条的时间行（有的文件缺空行，或上一行紧接下一条序号+时间行）
                if (TIME.containsMatchIn(lines[i])) break
                if (lines[i].trim().all { it.isDigit() } && i + 1 < lines.size && TIME.containsMatchIn(lines[i + 1])) break
                val stripped = decodeHtmlEntities(lines[i].replace(TAGS, "")).trim()
                if (stripped.isNotEmpty()) body.add(stripped)
                i++
            }
            if (body.isEmpty()) continue
            cues.add(cueOf(cues.size + 1, start, end, body))
        }
        if (cues.isNotEmpty()) {
            return mergeNearSimultaneousBilingual(cues)
        }
        val subCues = parseMicroDvd(lines)
        if (subCues.isNotEmpty()) return subCues
        return parseLrc(lines)
    }

    private val ASS_EVENTS = Regex("""^\s*\[\s*Events\s*]""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val ASS_SECTION = Regex("""^\s*\[\s*([^\]]+?)\s*]""")
    private val ASS_TIME = Regex("""(\d+):(\d{1,2}):(\d{1,2})[.,:](\d{1,3})""")
    private val ASS_DRAWING_SPAN = Regex("""\{[^}]*\\p[1-9]\b[^}]*\}.*?(?:\{[^}]*\\p0\b[^}]*\}|$)""", RegexOption.DOT_MATCHES_ALL)
    private val ASS_OVERRIDE = Regex("""\{[^}]*\}""")

    /**
     * ASS/SSA：按 [Events] 里的 Format 行找到 Start、End、Text 列。
     * 去掉 {\...} 样式代码与 {\p1}...{\p0} 矢量绘图区，\N 换行；同一时间或毫秒级相邻的双语双轴自动合并。
     */
    private fun parseAss(content: String): List<Cue> {
        var format = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        val raw = ArrayList<Triple<Long, Long, List<String>>>()
        var inEvents = false
        for (rawLine in content.removePrefix("\uFEFF").lineSequence()) {
            val t = rawLine.trim().removePrefix("\uFEFF")
            if (t.isEmpty() || t.startsWith(";")) continue
            val sec = ASS_SECTION.find(t)
            if (sec != null) {
                inEvents = sec.groupValues[1].trim().equals("Events", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            when {
                t.startsWith("Format:", ignoreCase = true) -> {
                    val cols = t.substringAfter(':').split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    if ("start" in cols && "end" in cols) format = cols
                }
                t.startsWith("Dialogue:", ignoreCase = true) -> {
                    val startIdx = format.indexOf("start").takeIf { it >= 0 } ?: 1
                    val endIdx = format.indexOf("end").takeIf { it >= 0 } ?: 2
                    val textIdx = format.indexOf("text").takeIf { it >= 0 } ?: (format.size - 1)
                    val parts = t.substringAfter(':').split(',', limit = maxOf(format.size, textIdx + 1))
                    if (parts.size <= maxOf(startIdx, endIdx, textIdx)) continue
                    val start = assTime(parts[startIdx]) ?: continue
                    val end = (assTime(parts[endIdx]) ?: continue).coerceAtLeast(start + 100L)
                    val rawText = parts[textIdx]
                    val withoutDrawing = rawText.replace(ASS_DRAWING_SPAN, "")
                    val lines = withoutDrawing
                        .replace(ASS_OVERRIDE, "")
                        .replace("\\N", "\n")
                        .replace("\\n", "\n")
                        .replace("\\h", " ")
                        .split('\n')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (lines.isNotEmpty()) raw += Triple(start, end, lines)
                }
            }
        }
        val exactMerged = raw.groupBy { it.first to it.second }.map { (k, v) ->
            Triple(k.first, k.second, v.flatMap { it.third }.distinct())
        }
        val built = exactMerged.sortedBy { it.first }.mapIndexed { idx, (start, end, text) ->
            cueOf(idx + 1, start, end, text)
        }
        return mergeNearSimultaneousBilingual(built)
    }

    /**
     * 合并字幕组常见的「中文一行 Dialogue + 外文一行 Dialogue」（起止时间相同或仅差 <=250ms）。
     */
    private fun mergeNearSimultaneousBilingual(sortedCues: List<Cue>): List<Cue> {
        if (sortedCues.size < 2) {
            return sortedCues.sortedBy { it.startMs }.mapIndexed { idx, c -> c.copy(id = idx + 1) }
        }
        val ordered = sortedCues.sortedWith(compareBy<Cue> { it.startMs }.thenBy { it.endMs })
        val out = ArrayList<Cue>(ordered.size)
        var i = 0
        while (i < ordered.size) {
            val cur = ordered[i]
            val next = ordered.getOrNull(i + 1)
            if (next != null &&
                cur.translation == null &&
                next.translation == null &&
                abs(next.startMs - cur.startMs) <= 250L &&
                abs(next.endMs - cur.endMs) <= 800L
            ) {
                val paired = tryPairBilingual(cur, next)
                if (paired != null) {
                    out.add(paired.copy(id = out.size + 1))
                    i += 2
                    continue
                }
            }
            out.add(cur.copy(id = out.size + 1))
            i++
        }
        return out
    }

    /** 韩国/老片常见的 SAMI (.smi) 字幕格式：`<SYNC Start=1200><P Class=KRCC>...` */
    private fun parseSami(content: String): List<Cue> {
        val matches = SAMI_SYNC.findAll(content).toList()
        if (matches.isEmpty()) return emptyList()
        data class SyncBlock(val startMs: Long, val lines: List<String>)
        val blocks = ArrayList<SyncBlock>(matches.size)
        for (idx in matches.indices) {
            val startMs = matches[idx].groupValues[1].toLongOrNull() ?: continue
            val bodyStart = matches[idx].range.last + 1
            val bodyEnd = if (idx + 1 < matches.size) matches[idx + 1].range.first else content.length
            val rawBody = content.substring(bodyStart, bodyEnd)
            val lines = rawBody
                .replace(SAMI_BR, "\n")
                .replace(Regex("""</p>""", RegexOption.IGNORE_CASE), "\n")
                .replace(TAGS, "")
                .let(::decodeHtmlEntities)
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            blocks.add(SyncBlock(startMs, lines))
        }
        // 同一毫秒可能针对不同语言 Class 出现多个 <SYNC Start=X>，先合并相同时间点
        val collapsed = ArrayList<SyncBlock>()
        for (b in blocks) {
            val prev = collapsed.lastOrNull()
            if (prev != null && prev.startMs == b.startMs) {
                collapsed[collapsed.lastIndex] = SyncBlock(prev.startMs, (prev.lines + b.lines).distinct())
            } else {
                collapsed.add(b)
            }
        }
        val cues = ArrayList<Cue>()
        for (idx in collapsed.indices) {
            val cur = collapsed[idx]
            if (cur.lines.isEmpty()) continue // 空 SYNC（如 &nbsp;）在 SAMI 中表示上一句结束清屏
            val nextStart = collapsed.getOrNull(idx + 1)?.startMs
            val endMs = if (nextStart != null && nextStart > cur.startMs) {
                minOf(nextStart, cur.startMs + 8_000L)
            } else {
                cur.startMs + 3_500L
            }
            cues.add(cueOf(cues.size + 1, cur.startMs, endMs, cur.lines))
        }
        return mergeNearSimultaneousBilingual(cues)
    }

    /** MicroDVD (.sub)：`{startFrame}{endFrame}第一行|第二行` */
    private fun parseMicroDvd(lines: List<String>): List<Cue> {
        var fps = 23.976
        val out = ArrayList<Cue>()
        for (line in lines) {
            val m = MICRODVD_LINE.matchEntire(line.trim()) ?: continue
            val startFrame = m.groupValues[1].toLongOrNull() ?: continue
            val endFrame = m.groupValues[2].toLongOrNull() ?: continue
            val rawText = m.groupValues[3].trim()
            if (out.isEmpty() && startFrame <= 1L && endFrame <= 1L) {
                rawText.replace(',', '.').toDoubleOrNull()?.takeIf { it in 10.0..120.0 }?.let {
                    fps = it
                    continue
                }
            }
            val subLines = rawText.replace(Regex("""\{[^}]*\}"""), "").split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (subLines.isEmpty()) continue
            val startMs = (startFrame * 1000.0 / fps).toLong()
            val endMs = (endFrame * 1000.0 / fps).toLong().coerceAtLeast(startMs + 500L)
            out.add(cueOf(out.size + 1, startMs, endMs, subLines))
        }
        return out
    }

    /** LRC 时间戳文本：`[01:02.34]台词` */
    private fun parseLrc(lines: List<String>): List<Cue> {
        val timed = ArrayList<Pair<Long, String>>()
        for (line in lines) {
            val tags = LRC_TAG.findAll(line).toList()
            if (tags.isEmpty()) continue
            val text = line.substring(tags.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (tag in tags) {
                val min = tag.groupValues[1].toLongOrNull() ?: continue
                val sec = tag.groupValues[2].toLongOrNull() ?: continue
                val frac = tag.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100L
                    2 -> frac.toLong() * 10L
                    else -> frac.take(3).toLong()
                }
                timed.add((min * 60_000L + sec * 1_000L + ms) to text)
            }
        }
        if (timed.size < 2) return emptyList()
        timed.sortBy { it.first }
        return timed.mapIndexed { idx, (startMs, text) ->
            val nextStart = timed.getOrNull(idx + 1)?.first
            val endMs = if (nextStart != null && nextStart > startMs) minOf(nextStart, startMs + 6_000L) else startMs + 4_000L
            Cue(idx + 1, startMs, endMs, text)
        }
    }

    private fun decodeHtmlEntities(s: String): String {
        if ('&' !in s) return s
        return s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
    }

    private fun assTime(s: String): Long? {
        val m = ASS_TIME.find(s.trim()) ?: return null
        val (h, mi, se, frac) = m.destructured
        // ASS 用百分之一秒
        val millis = when (frac.length) { 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
        return ((h.toLong() * 60 + mi.toLong()) * 60 + se.toLong()) * 1000 + millis
    }

    /** 一条字幕的几行文字转成 [Cue]：一行中文一行外文时拆成译文和原文，否则合成一行。 */
    fun cueOf(id: Int, start: Long, end: Long, lines: List<String>): Cue {
        if (lines.size == 2 && looksBilingual(lines[0], lines[1])) {
            val (cn, other) = if (isChineseLine(lines[0])) lines[0] to lines[1] else lines[1] to lines[0]
            return Cue(id, start, end, other, translation = cn)
        }
        return Cue(id, start, end, lines.joinToString(" "))
    }

    internal fun tryPairBilingual(a: Cue, b: Cue): Cue? {
        if (!looksBilingual(a.text, b.text)) return null
        val (cn, other) = if (isChineseLine(a.text)) a.text to b.text else b.text to a.text
        return Cue(
            id = a.id,
            startMs = minOf(a.startMs, b.startMs),
            endMs = maxOf(a.endMs, b.endMs),
            text = other,
            translation = cn,
            speaker = a.speaker ?: b.speaker,
        )
    }

    /**
     * 一行中文一行外文时，视为"译文 + 原文"的双语字幕：
     * 严格要求中文行含汉字且不含日文假名/韩文谚文主导（同时兼容中文译文中夹带英文人名/缩写），另一行是有实际文字的外文。
     */
    private fun looksBilingual(first: String, second: String): Boolean {
        val c1 = isChineseLine(first)
        val c2 = isChineseLine(second)
        if (c1 == c2) return false
        val foreign = if (c1) second else first
        return com.aisub.player.translate.LanguageCheck.script(foreign) != null && !containsHan(foreign)
    }

    private fun isChineseLine(s: String): Boolean {
        if (com.aisub.player.translate.LanguageCheck.script(s) == "zh") return true
        var han = 0
        var kana = 0
        var hangul = 0
        s.codePoints().forEach { cp ->
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                else -> {}
            }
        }
        return han >= 2 && kana == 0 && hangul == 0
    }

    private fun containsHan(s: String): Boolean =
        s.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }

    private fun toMs(h: String, m: String, s: String, frac: String): Long {
        val hours = h.replace(":", "").replace("：", "").trim().toLongOrNull() ?: 0L
        val millis = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return ((hours * 60 + m.trim().toLong()) * 60 + s.trim().toLong()) * 1000 + millis
    }
}
