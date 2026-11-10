package com.aisub.player.live

/**
 * 把直播自带的隐藏式字幕（CEA-608 / 708 等）拼成整句，好交给翻译。
 *
 * 播放器每次刷新给出屏幕上当前的几行字：
 * - 滚动式（roll-up）：最下面一行逐词变长，换行时整体上移；
 * - 逐字式（paint-on）：一行逐字变长；
 * - 弹出式（pop-on）：一两行一下子出现，过一会儿被下一屏换掉或清空。
 * 最下面一行还在变长时先不收；它被顶上去、被换掉、清空，或者好几秒没变，才算这一行说完。
 * 收下的行接成一段，按句末标点和换人说话的「>>」切成句子。
 *
 * 只在主线程用。
 */
class LiveCaptions {
    /** 最下面那行（可能还在变长）；null 表示屏幕上没有正在写的行 */
    private var bottom: String? = null

    /** bottom 里已经收进 [buffer] 的字数（好几秒没变时先收下，之后变长只收新增的部分） */
    private var bottomUsed = 0
    private var bottomChangedAt = 0L

    /** 最近收下的整行：滚动式的旧行还会在屏幕上停留几次刷新，不能重复收 */
    private val recent = ArrayDeque<String>()

    /** 收下了、还没凑成整句的文字 */
    private val buffer = StringBuilder()
    private var bufferedAt = 0L

    /**
     * 屏幕上的字幕变了。
     * @param lines 从上到下的各行（已去掉空行）
     * @return 这次凑成的整句，可能为空
     */
    fun update(lines: List<String>, nowMs: Long): List<String> {
        val out = ArrayList<String>()
        val cleaned = lines.map { clean(it) }.filter { it.isNotEmpty() }
        val last = cleaned.lastOrNull()
        val b = bottom
        // 上面几行：原来的最下一行被顶上去了，或者弹出式一下子出了好几行
        for (line in cleaned.dropLast(1)) {
            if (b != null && (line == b || line.startsWith(b))) {
                commitBottom(line, nowMs, out)
            } else if (line !in recent) {
                commit(line, line, nowMs, out)
            }
        }
        when {
            last == null -> bottom?.let { commitBottom(it, nowMs, out) }
            // 还是同一行：变长了（或者改了错字、退格）
            bottom != null && (last.startsWith(bottom!!) || bottom!!.startsWith(last)) -> {
                if (last != bottom) {
                    bottom = last
                    bottomUsed = minOf(bottomUsed, last.length)
                    bottomChangedAt = nowMs
                }
            }
            last in recent -> {}
            else -> {
                bottom?.let { commitBottom(it, nowMs, out) }
                bottom = last
                bottomUsed = 0
                bottomChangedAt = nowMs
            }
        }
        return out
    }

    /**
     * 定时调用：最下面一行好几秒没变就先收下；收下的文字好几秒没凑成整句就当一句发出去
     * （隐藏式字幕常常不打标点）。
     */
    fun tick(nowMs: Long): List<String> {
        val out = ArrayList<String>()
        val b = bottom
        if (b != null && bottomUsed < b.length && nowMs - bottomChangedAt >= IDLE_MS) {
            append(b.substring(bottomUsed), nowMs, out)
            bottomUsed = b.length
        }
        if (buffer.isNotBlank() && nowMs - bufferedAt >= IDLE_MS) {
            out += buffer.toString().trim()
            buffer.clear()
        }
        return out
    }

    fun reset() {
        bottom = null
        bottomUsed = 0
        recent.clear()
        buffer.clear()
    }

    private fun commitBottom(line: String, nowMs: Long, out: MutableList<String>) {
        // 被顶上去时可能又多了几个字：只收还没收过的部分
        val used = if (bottom != null && line.startsWith(bottom!!)) bottomUsed else 0
        commit(line, line.substring(minOf(used, line.length)), nowMs, out)
        bottom = null
        bottomUsed = 0
    }

    private fun commit(line: String, text: String, nowMs: Long, out: MutableList<String>) {
        recent.addLast(line)
        while (recent.size > RECENT) recent.removeFirst()
        append(text, nowMs, out)
    }

    private fun append(text: String, nowMs: Long, out: MutableList<String>) {
        val t = text.trim()
        if (t.isEmpty()) return
        // 「>>」表示换人说话：前面的先算一句
        val parts = t.split(">>")
        for ((i, part) in parts.withIndex()) {
            if (i > 0 && buffer.isNotBlank()) {
                out += buffer.toString().trim()
                buffer.clear()
            }
            val p = part.trim()
            if (p.isEmpty()) continue
            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(p)
        }
        bufferedAt = nowMs
        // 切出所有已经说完的句子，剩下的留着等后文
        while (true) {
            val end = SENTENCE_END.find(buffer)?.range?.last ?: break
            out += buffer.substring(0, end + 1).trim()
            buffer.delete(0, end + 1)
        }
        // 一直没有标点：太长了就在最后一个逗号处（没有就整段）断开
        if (buffer.length > MAX_CHARS) {
            val comma = buffer.lastIndexOf(",").takeIf { it > MAX_CHARS / 2 } ?: (buffer.length - 1)
            out += buffer.substring(0, comma + 1).trim()
            buffer.delete(0, comma + 1)
        }
        if (buffer.isBlank()) buffer.clear()
    }

    private fun clean(line: String) = line.replace(SPACES, " ").trim()

    companion object {
        /** 一行或一段这么久没变就算说完 */
        const val IDLE_MS = 2_500L
        private const val RECENT = 12
        private const val MAX_CHARS = 160
        private val SPACES = Regex("\\s+")

        /** 句末标点（后面可以跟引号、括号），后面是空白或已经收到的末尾：「Mr. Smith」这种会切错，但影响不大 */
        private val SENTENCE_END = Regex("[.?!。？！…]+[\"'”’)\\]]*(?=\\s|$)")
    }
}
