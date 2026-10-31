package com.aisub.player.live

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCaptionsTest {

    @Test
    fun rollUpGrowsThenScrolls() {
        val c = LiveCaptions()
        val out = ArrayList<String>()
        out += c.update(listOf("YOU'RE A KID"), 0)
        out += c.update(listOf("YOU'RE A KID THAT'S GOING TO"), 300)
        assertEquals(emptyList<String>(), out)
        // 换行：上一行被顶上去
        out += c.update(listOf("YOU'RE A KID THAT'S GOING TO", "YOUR COMMUNITY CENTRE"), 600)
        out += c.update(listOf("YOU'RE A KID THAT'S GOING TO", "YOUR COMMUNITY CENTRE FROM HOME."), 900)
        assertEquals(emptyList<String>(), out)
        out += c.update(listOf("YOU'RE A KID THAT'S GOING TO", "YOUR COMMUNITY CENTRE FROM HOME.", "AND"), 1200)
        assertEquals(listOf("YOU'RE A KID THAT'S GOING TO YOUR COMMUNITY CENTRE FROM HOME."), out)
        // 最上面一行滚出屏幕不会重复收
        assertEquals(emptyList<String>(), c.update(listOf("YOUR COMMUNITY CENTRE FROM HOME.", "AND THEN"), 1500))
    }

    @Test
    fun popOnAndSpeakerChange() {
        val c = LiveCaptions()
        assertEquals(emptyList<String>(), c.update(listOf("We're live from Ottawa", "this evening."), 0))
        assertEquals(listOf("We're live from Ottawa this evening."), c.update(listOf(">> Thanks, Adrienne"), 2000))
        // 清屏后没有标点的半句等一会儿再发
        assertEquals(emptyList<String>(), c.update(emptyList(), 3000))
        assertEquals(listOf("Thanks, Adrienne"), c.tick(3000 + LiveCaptions.IDLE_MS))
    }

    @Test
    fun idleFlushesUnpunctuated() {
        val c = LiveCaptions()
        c.update(listOf("NO PUNCTUATION HERE"), 0)
        assertEquals(emptyList<String>(), c.tick(1000))
        // 行好几秒没变：先收下；收下的又好几秒没凑成整句：发出去
        assertEquals(emptyList<String>(), c.tick(LiveCaptions.IDLE_MS))
        assertEquals(listOf("NO PUNCTUATION HERE"), c.tick(LiveCaptions.IDLE_MS * 2))
        // 之后这一行又变长了：只收新增的部分
        c.update(listOf("NO PUNCTUATION HERE AT ALL."), LiveCaptions.IDLE_MS * 2 + 100)
        assertEquals(listOf("AT ALL."), c.update(emptyList(), LiveCaptions.IDLE_MS * 2 + 200))
    }
}
