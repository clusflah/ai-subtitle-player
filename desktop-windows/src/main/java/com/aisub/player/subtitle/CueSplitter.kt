package com.aisub.player.subtitle

/** 带开始时间的识别单元：一个字、一个 token 或一个词。结束时间取下一个单元的开始。 */
data class TimedToken(val text: String, val startMs: Long)

/**
 * 把逐字/逐词的识别结果切成适合屏幕阅读的短句字幕：
 * 在句末标点处断开；逗号处在句子够长时断开；超过字数或时长上限强制断开。
 * 返回 (开始, 结束, 文本)。
 */
object CueSplitter {
    private const val MAX_WIDTH = 20.0       // 一行最多约 20 个汉字（西文字符按半个算）
    private const val COMMA_BREAK_WIDTH = 10.0
    private const val MAX_DURATION_MS = 5_000L
    private const val MIN_DURATION_MS = 800L
    private const val STRONG = "。！？!?；;…"
    private const val WEAK = "，、,：:"
    const val PUNCTUATION = "。，、！？；：,.!?;:…\"'“”‘’"

    /**
     * 统一各模型的 token 写法，让词首带空格：
     * SentencePiece 的 "▁"、Paraformer 的 "@@" 续接符；都没有时在相邻西文词之间补空格。
     */
    fun normalizeTokens(tokens: List<String>): List<String> {
        if (tokens.any { it.contains('▁') }) return tokens.map { it.replace('▁', ' ') }
        if (tokens.any { it.startsWith(' ') }) return tokens // 已带空格（如 Whisper）
        val out = ArrayList<String>(tokens.size)
        var prevJoins = false // 上一个 token 以 @@ 结尾：与本 token 连成一个词
        var prevLatin = false
        for (t in tokens) {
            val joinsNext = t.endsWith("@@")
            val core = t.removeSuffix("@@")
            val latin = core.isNotEmpty() && core.all { it.isLetterOrDigit() && !isCjk(it) || it == '\'' }
            out.add(if (latin && prevLatin && !prevJoins) " $core" else core)
            prevJoins = joinsNext
            prevLatin = latin
        }
        return out
    }

    /**
     * 标点模型只给出整句带标点的文本，这里把标点"挂"回对应的 token 后面，保留每个 token 的时间。
     */
    fun attachPunctuation(tokens: List<String>, punctuated: String): List<String> {
        var p = 0
        return tokens.map { token ->
            val core = token.trim()
            var matched = 0
            while (p < punctuated.length && matched < core.length) {
                if (punctuated[p].equals(core[matched], ignoreCase = true)) matched++
                p++
            }
            val sb = StringBuilder(token)
            while (p < punctuated.length && (punctuated[p] in PUNCTUATION)) sb.append(punctuated[p++])
            sb.toString()
        }
    }

    /** 没有逐字时间戳时：在标点处拆句，按字数比例分配时间。 */
    fun splitProportional(text: String, startMs: Long, endMs: Long): List<Triple<Long, Long, String>> {
        val duration = endMs - startMs
        val pieces = Regex("[^。！？!?；;，,]+[。！？!?；;，,]*").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        if (duration <= MAX_DURATION_MS || pieces.size <= 1) return listOf(Triple(startMs, endMs, text.trim()))
        // 把短句合并到宽度上限以内
        val lines = ArrayList<String>()
        for (piece in pieces) {
            val last = lines.lastOrNull()
            if (last != null && width(last + piece) <= MAX_WIDTH && last.last() !in STRONG) lines[lines.lastIndex] = last + piece
            else lines.add(piece)
        }
        val total = lines.sumOf { width(it) }
        val result = ArrayList<Triple<Long, Long, String>>()
        var cursor = startMs
        for ((i, line) in lines.withIndex()) {
            val end = if (i == lines.lastIndex) endMs else cursor + (duration * width(line) / total).toLong()
            result.add(Triple(cursor, end, line))
            cursor = end
        }
        return result
    }

    fun split(tokens: List<TimedToken>, segmentEndMs: Long): List<Triple<Long, Long, String>> {
        val lines = ArrayList<Pair<Long, StringBuilder>>()
        var current: StringBuilder? = null
        var lineStart = 0L
        for ((i, t) in tokens.withIndex()) {
            if (current == null) {
                current = StringBuilder()
                lineStart = t.startMs
                lines.add(lineStart to current)
            }
            current.append(t.text)
            val text = current.toString().trim()
            val width = width(text)
            val last = text.lastOrNull()
            val nextStart = tokens.getOrNull(i + 1)?.startMs ?: segmentEndMs
            val shouldBreak = when {
                i == tokens.lastIndex -> false
                last != null && last in STRONG && width >= 2 -> true
                last != null && last in WEAK && width >= COMMA_BREAK_WIDTH -> true
                width >= MAX_WIDTH && isBoundary(t.text, tokens[i + 1].text) -> true
                nextStart - lineStart >= MAX_DURATION_MS && isBoundary(t.text, tokens[i + 1].text) -> true
                else -> false
            }
            if (shouldBreak) current = null
        }

        val result = ArrayList<Triple<Long, Long, String>>()
        for ((i, line) in lines.withIndex()) {
            val text = line.second.toString().trim()
            if (text.isEmpty() || text.all { it in STRONG || it in WEAK || it.isWhitespace() }) continue
            val start = line.first
            val end = lines.getOrNull(i + 1)?.first ?: segmentEndMs
            result.add(Triple(start, maxOf(end, start + MIN_DURATION_MS), text))
        }
        return result
    }

    /** 西文单词中间不断开：下一个单元以空格开头，或任一侧是中日韩文字时才算边界。 */
    private fun isBoundary(current: String, next: String): Boolean =
        next.startsWith(' ') || isCjk(current.lastOrNull()) || isCjk(next.firstOrNull())

    private fun width(s: String): Double = s.sumOf { if (isCjk(it)) 1.0 else 0.5 }

    fun isCjk(c: Char?): Boolean {
        if (c == null) return false
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }

    /** 纯西文、字母全是大写（模型的输出习惯，不是原文就这样）；夹着中文的 OK、AI 之类不算 */
    fun isAllCaps(text: String): Boolean {
        val letters = text.filter { it in 'A'..'Z' || it in 'a'..'z' }
        return letters.length >= 2 && letters.all { it in 'A'..'Z' } && text.none { isCjk(it) }
    }

    /** 全大写改成普通写法：句首、I 和 I'm 之类大写，其余小写 */
    fun sentenceCase(text: String): String {
        val lower = text.lowercase()
        val sb = StringBuilder(lower.length)
        var capitalize = true
        for (c in lower) {
            if (capitalize && c.isLetter()) {
                sb.append(c.uppercaseChar())
                capitalize = false
            } else sb.append(c)
            if (c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？') capitalize = true
        }
        return sb.toString().replace(Regex("(?<![\\p{L}'])i(?=$|[\\s'’,.!?])"), "I")
    }
}
