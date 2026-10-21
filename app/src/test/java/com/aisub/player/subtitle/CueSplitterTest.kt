package com.aisub.player.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CueSplitterTest {

    /** 中文：每个字一个 token，间隔 250ms。 */
    private fun chars(text: String, start: Long = 0, step: Long = 250) =
        text.mapIndexed { i, c -> TimedToken(c.toString(), start + i * step) }

    @Test
    fun splitsChineseAtSentenceEnd() {
        val tokens = chars("你昨晚在哪儿？我跟你说了，我在办公室。")
        val lines = CueSplitter.split(tokens, tokens.size * 250L)
        assertEquals(listOf("你昨晚在哪儿？", "我跟你说了，我在办公室。"), lines.map { it.third })
        // 第二句从"我"开始：第 7 个字 → 1750ms
        assertEquals(1750L, lines[1].first)
        // 上一句结束 = 下一句开始，字幕不重叠
        assertEquals(lines[1].first, lines[0].second)
    }

    @Test
    fun longLinesAreBrokenByWidthAndDuration() {
        val tokens = chars("这是一段没有任何标点符号的非常长的识别结果需要被强制切开才能在屏幕上正常显示", step = 200)
        val lines = CueSplitter.split(tokens, tokens.size * 200L)
        assertTrue("应切成多行：$lines", lines.size >= 2)
        lines.forEach { (s, e, t) ->
            assertTrue("行太长：$t", t.length <= 25)
            assertTrue("时长异常：$s-$e", e - s <= 5_200)
        }
    }

    @Test
    fun englishWordsAreNotCutInHalf() {
        // SentencePiece 风格：词首带空格
        val words = "Where were you last night? I told you, I was at the office the whole evening and then I went home"
            .split(' ').mapIndexed { i, w -> TimedToken(if (i == 0) w else " $w", i * 300L) }
        val lines = CueSplitter.split(words, words.size * 300L)
        assertEquals("Where were you last night?", lines[0].third)
        lines.forEach { (_, _, t) -> assertTrue("词被切断：$t", !t.startsWith(" ")) }
        val rejoined = lines.joinToString(" ") { it.third }
        assertEquals(words.joinToString("") { it.text }, rejoined)
    }

    @Test
    fun punctuationOnlyLinesAreDropped() {
        val lines = CueSplitter.split(listOf(TimedToken("。", 0)), 500)
        assertTrue(lines.isEmpty())
    }
}
