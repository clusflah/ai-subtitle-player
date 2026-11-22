package com.aisub.player.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAppWhitelistTest {

    @Test
    fun cloudTransferDomainsAreAlwaysWhitelistedForCleanAndNavigation() {
        // 115 分享页 115cdn.com -> m.115.com 跨域名跳转不再误报拦截广告
        assertTrue(WebAppWhitelist.isCleanPageWhitelisted("https://115cdn.com/s/sw3abc", fromDiscoverPlay = false))
        assertTrue(WebAppWhitelist.isCleanPageWhitelisted("https://pan.quark.cn/s/xyz", fromDiscoverPlay = false))
        assertTrue(WebAppWhitelist.isCleanPageWhitelisted("https://www.alipan.com/s/123", fromDiscoverPlay = false))
        assertTrue(WebAppWhitelist.isCleanPageWhitelisted("https://pan.baidu.com/s/1abc", fromDiscoverPlay = false))

        assertTrue(
            WebAppWhitelist.isWhitelistedCrossSiteNavigation(
                targetUrl = "https://m.115.com/transfer?code=qd64",
                pageUrl = "https://115cdn.com/s/sw3abc",
                fromDiscoverPlay = false,
            ),
        )
        assertTrue(
            WebAppWhitelist.isWhitelistedCrossSiteNavigation(
                targetUrl = "https://pan.quark.cn/s/987654",
                pageUrl = "https://www.re0.me/subject/123",
                fromDiscoverPlay = false,
            ),
        )
    }

    @Test
    fun cloudTransferAppSchemesAndIntentsAreAllowedWhileAdSchemesAreBlocked() {
        // 115 App 唤起协议与 Android intent://
        assertTrue(
            WebAppWhitelist.shouldAllowAppLaunch(
                rawUrl = "oof.disk://share/qd64",
                pageUrl = "https://115cdn.com/s/sw3abc",
                fromDiscoverPlay = false,
            ),
        )
        val intent115 = "intent://share/qd64#Intent;scheme=115;package=com.ylmf.androidclient;" +
            "S.browser_fallback_url=https%3A%2F%2Fm.115.com%2Fshare%2Fqd64;end"
        val parsed = WebAppWhitelist.parseIntentUri(intent115)
        assertNotNull(parsed)
        assertEquals("115", parsed?.scheme)
        assertEquals("com.ylmf.androidclient", parsed?.packageName)
        assertEquals("https://m.115.com/share/qd64", parsed?.fallbackUrl)
        assertTrue(
            WebAppWhitelist.shouldAllowAppLaunch(
                rawUrl = intent115,
                pageUrl = "https://115cdn.com/s/sw3abc",
                fromDiscoverPlay = false,
            ),
        )

        // 普通网页上的知乎 / 百度 App 强行拉起弹窗一律拦截
        assertFalse(
            WebAppWhitelist.shouldAllowAppLaunch(
                rawUrl = "zhihu://questions/123456",
                pageUrl = "https://www.zhihu.com/question/123456",
                fromDiscoverPlay = false,
            ),
        )
        assertFalse(
            WebAppWhitelist.shouldAllowAppLaunch(
                rawUrl = "intent://article/1#Intent;scheme=baiduboxapp;package=com.baidu.searchbox;end",
                pageUrl = "https://m.baidu.com/s?wd=test",
                fromDiscoverPlay = false,
            ),
        )
    }

    @Test
    fun discoverJumpToPlayWhitelistsStreamingAppsOnlyWhenOpenedFromDiscoverPlay() {
        val biliUrl = "https://m.bilibili.com/bangumi/play/ep12345"
        val biliScheme = "bilibili://bangumi/season/12345"

        // 普通网页浏览模式下屏蔽流媒体网页的「打开 App」弹窗与自动唤起
        assertFalse(WebAppWhitelist.isCleanPageWhitelisted(biliUrl, fromDiscoverPlay = false))
        assertFalse(WebAppWhitelist.shouldAllowAppLaunch(biliScheme, biliUrl, fromDiscoverPlay = false))

        // 发现页跳转播放模式（fromDiscoverPlay = true）下放行
        assertTrue(WebAppWhitelist.isCleanPageWhitelisted(biliUrl, fromDiscoverPlay = true))
        assertTrue(WebAppWhitelist.shouldAllowAppLaunch(biliScheme, biliUrl, fromDiscoverPlay = true))
        assertTrue(
            WebAppWhitelist.shouldAllowAppLaunch(
                rawUrl = "tenvideo2://?action=1&cover_id=mzc00200",
                pageUrl = "https://m.v.qq.com/x/cover/mzc00200.html",
                fromDiscoverPlay = true,
            ),
        )
        assertTrue(
            WebAppWhitelist.isWhitelistedCrossSiteNavigation(
                targetUrl = "https://www.iqiyi.com/v_19rr.html",
                pageUrl = "https://m.douban.com/movie/subject/123/",
                fromDiscoverPlay = true,
            ),
        )
    }

    @Test
    fun openAppJsConfirmPromptsAreDetectedAccurately() {
        assertTrue(WebAppWhitelist.isOpenAppPromptText("是否打开客户端继续观看高清完整版？"))
        assertTrue(WebAppWhitelist.isOpenAppPromptText("即将离开网页，打开 App 查看全文"))
        assertTrue(WebAppWhitelist.isOpenAppPromptText("Continue in the app for full experience?"))
        assertFalse(WebAppWhitelist.isOpenAppPromptText("确定要删除这条评论吗？"))
    }
}
