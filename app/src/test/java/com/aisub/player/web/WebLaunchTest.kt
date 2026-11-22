package com.aisub.player.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLaunchTest {

    @Test
    fun `clicking continue reading repeatedly reuses existing tab without adding tabs`() {
        val chapterUrl = "https://www.baozimh.com/comic/chapter/onepunch-man/0_232.html"
        var tabs: List<String?> = listOf(chapterUrl)
        var active = 0

        repeat(5) {
            val plan = WebLaunch.resolveTabs(tabs, active, chapterUrl, mangaMode = true)
            tabs = plan.urls
            active = plan.activeIndex
        }

        assertEquals(1, tabs.size)
        assertEquals(0, active)
        assertEquals(chapterUrl, tabs[0])
    }

    @Test
    fun `collapses accumulated duplicate tabs of the same manga chapter`() {
        val chapterUrl = "https://baozimh.com/comic/chapter/onepunch-man/0_232.html"
        val existing = listOf(
            "https://115cdn.com/s/sw1234",
            "http://www.baozimh.com/comic/chapter/onepunch-man/0_232.html/",
            "https://baozimh.com/comic/chapter/onepunch-man/0_232.html#p=37",
            chapterUrl,
        )

        val plan = WebLaunch.resolveTabs(existing, currentActiveIndex = 3, targetUrl = chapterUrl, mangaMode = true)

        assertEquals(listOf("https://115cdn.com/s/sw1234", chapterUrl), plan.urls)
        assertEquals(1, plan.activeIndex)
    }

    @Test
    fun `continue reading reuses same manga site tab when chapter or subdomain changed`() {
        val existing = listOf(
            "https://m.bilibili.com/video/BV1xx",
            "https://m.manhuagui.com/comic/7580/231.html",
        )
        val nextChapter = "https://www.manhuagui.com/comic/7580/232.html"

        val plan = WebLaunch.resolveTabs(existing, currentActiveIndex = 0, targetUrl = nextChapter, mangaMode = true)

        assertEquals(listOf("https://m.bilibili.com/video/BV1xx", nextChapter), plan.urls)
        assertEquals(1, plan.activeIndex)
    }

    @Test
    fun `reuses blank tab before creating any new tab`() {
        val existing = listOf("https://m.bilibili.com", null)
        val target = "https://www.manhuagui.com/comic/7580/232.html"

        val plan = WebLaunch.resolveTabs(existing, currentActiveIndex = 0, targetUrl = target, mangaMode = true)

        assertEquals(listOf("https://m.bilibili.com", target), plan.urls)
        assertEquals(1, plan.activeIndex)
    }

    @Test
    fun `consume atomically returns request and resets defaults`() {
        WebLaunch.open("https://manhuagui.com/comic/1/2.html", mangaMode = true, allowOpenApp = false)
        val req = WebLaunch.consume()
        assertTrue(req != null && req.mangaMode && !req.allowOpenApp)
        assertNull(WebLaunch.consume())
    }
}
