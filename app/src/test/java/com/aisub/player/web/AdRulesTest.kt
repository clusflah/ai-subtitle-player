package com.aisub.player.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdRulesTest {
    private fun rules(vararg lines: String) = AdRules.parse(lines.asSequence())

    @Test
    fun blocksDomainAndSubdomains() {
        val r = rules("||ads.example.com^")
        assertTrue(r.shouldBlock("https://ads.example.com/x.js", "https://site.cn/", null))
        assertTrue(r.shouldBlock("https://cdn.ads.example.com/a.png", "https://site.cn/", null))
        assertFalse(r.shouldBlock("https://example.com/a.png", "https://site.cn/", null))
        assertFalse(r.shouldBlock("https://badads.example.com/a.png", "https://site.cn/", null))
    }

    @Test
    fun exceptionsAndImportant() {
        val r = rules("||track.com^", "@@||track.com/ok/", "||bad.com^\$important", "@@||bad.com^")
        assertTrue(r.shouldBlock("https://track.com/pixel", null, null))
        assertFalse(r.shouldBlock("https://track.com/ok/a.js", null, null))
        assertTrue(r.shouldBlock("https://bad.com/a", null, null))
    }

    @Test
    fun pathRules() {
        val r = rules("||example.com/ads/*.js|", "||video.cn/ad^")
        assertTrue(r.shouldBlock("https://example.com/ads/x/y.js", null, null))
        assertFalse(r.shouldBlock("https://example.com/ads/x/y.js?v=1", null, null))
        assertFalse(r.shouldBlock("https://example.com/news/y.js", null, null))
        assertTrue(r.shouldBlock("https://video.cn/ad?x=1", null, null))
        assertFalse(r.shouldBlock("https://video.cn/admin", null, null))
    }

    @Test
    fun thirdPartyDomainAndTypeOptions() {
        val r = rules(
            "||cdn.net^\$third-party",
            "||stats.org^\$domain=a.com|~b.a.com",
            "||img.io^\$image",
            "||x.io^\$~script",
        )
        assertTrue(r.shouldBlock("https://cdn.net/a.js", "https://site.com/", null))
        assertFalse(r.shouldBlock("https://cdn.net/a.js", "https://www.cdn.net/", null))
        assertTrue(r.shouldBlock("https://stats.org/p", "https://www.a.com/", null))
        assertFalse(r.shouldBlock("https://stats.org/p", "https://b.a.com/", null))
        assertFalse(r.shouldBlock("https://stats.org/p", "https://other.com/", null))
        assertTrue(r.shouldBlock("https://img.io/a.png", null, AdType.IMAGE))
        assertFalse(r.shouldBlock("https://img.io/a.js", null, AdType.SCRIPT))
        assertTrue(r.shouldBlock("https://img.io/unknown", null, null))
        assertFalse(r.shouldBlock("https://x.io/a.js", null, AdType.SCRIPT))
        assertTrue(r.shouldBlock("https://x.io/a.png", null, AdType.IMAGE))
    }

    @Test
    fun skipsUnsupportedRules() {
        val r = rules("||a.com^\$redirect=noopjs", "||b.com^\$popup", "/banner/*", "-ad-", "||c*.com^", "example.com#\$#body { color: red }")
        assertFalse(r.shouldBlock("https://a.com/x", null, null))
        assertFalse(r.shouldBlock("https://b.com/x", null, null))
        assertFalse(r.shouldBlock("https://site.com/banner/1.png", null, null))
        assertEquals("", r.css("example.com"))
    }

    @Test
    fun cosmetic() {
        val r = rules(
            "##.ad-banner",
            "~quiet.com##.float-ad",
            "video.cn,~m.video.cn##.vip-box",
            "#@#.ad-banner",
            "##.side-ad",
            "keep.com#@#.side-ad",
            "@@||clean.com^\$elemhide",
            "@@||nogeneric.com^\$generichide",
            "nogeneric.com##.own-ad",
            "##div:-abp-has(.x)",
        )
        val css = r.css("www.video.cn")
        assertTrue(css.contains(".vip-box{"))
        assertTrue(css.contains(".float-ad{"))
        assertTrue(css.contains(".side-ad{"))
        assertFalse(css.contains(".ad-banner"))
        assertFalse(css.contains("abp-has"))
        assertFalse(r.css("m.video.cn").contains(".vip-box"))
        assertFalse(r.css("quiet.com").contains(".float-ad"))
        assertFalse(r.css("keep.com").contains(".side-ad"))
        assertEquals("", r.css("www.clean.com"))
        val ng = r.css("nogeneric.com")
        assertTrue(ng.contains(".own-ad"))
        assertFalse(ng.contains(".side-ad"))
    }

    @Test
    fun customAnywhereRulesAndResponses() {
        val lines = arrayOf(
            "/api/mw-movie/anonymous/cc/getPauseCc\$nodata",
            "/api/mw-movie/anonymous/cc/\$nolist",
            "/static/ad/*.gif",
            "@@/static/ad/keep.gif",
        )
        val custom = AdRules.parse(lines.asSequence(), custom = true)
        assertEquals(
            AdDecision.Block(AdResponse.NODATA),
            custom.decide("https://m.a.com/api/mw-movie/anonymous/cc/getPauseCc?nid=1", null, null),
        )
        assertEquals(
            AdDecision.Block(AdResponse.NOLIST),
            custom.decide("https://other.cn/api/mw-movie/anonymous/cc/getVideoCc", null, null),
        )
        assertEquals(AdDecision.Block(AdResponse.EMPTY), custom.decide("https://x.cn/static/ad/1.gif", null, null))
        assertEquals(AdDecision.Allow, custom.decide("https://x.cn/static/ad/keep.gif", null, null))
        assertEquals(null, custom.decide("https://x.cn/static/img/1.gif", null, null))
        // 下载的规则里不带域名的不认
        assertEquals(null, AdRules.parse(lines.asSequence()).decide("https://x.cn/static/ad/1.gif", null, null))
    }

    @Test
    fun customExceptionForListRule() {
        val custom = AdRules.parse(sequenceOf("@@||cdn.ads.com/player.js"), custom = true)
        assertEquals(AdDecision.Allow, custom.decide("https://cdn.ads.com/player.js", null, null))
        assertEquals(null, custom.decide("https://cdn.ads.com/other.js", null, null))
    }

    @Test
    fun hostParsing() {
        assertEquals("a.b.com", AdRules.hostOf("https://user@A.B.com:8080/x?y"))
        assertEquals(null, AdRules.hostOf("data:text/plain,hi"))
        assertEquals(null, AdRules.hostOf("blob:https://a.com/uuid"))
    }
}
