package com.aisub.player.web

import java.net.URI
import java.net.URLDecoder

/**
 * 网页「打开 App」弹窗屏蔽与白名单规则：
 * 1. 网盘转存相关应用（115 网盘、夸克、阿里云盘、百度网盘、PikPak、天翼云盘、迅雷、UC、123 云盘、移动云盘、微云、OneDrive、Dropbox、Google Drive、MEGA、Telegram）始终在白名单内：
 *    - 不隐藏其页面内的「打开 App 转存」按钮或底部面板；
 *    - 允许唤起对应外部 App（scheme:// 与 intent://）；
 *    - 允许同系域名与网盘分享域名之间的跨站跳转（例如 115cdn.com -> m.115.com），不误报「已拦截跳转广告」。
 * 2. 发现页跳转播放（腾讯视频、爱奇艺、优酷、哔哩哔哩、芒果 TV、咪咕视频、Netflix、HBO/Max、Apple TV+、Disney+、Prime Video、Hulu、Paramount+、Peacock、YouTube 等）：
 *    - 从发现页播放入口打开标签页（fromDiscoverPlay = true）时加入白名单，放行「打开 App」界面、外部 App 唤起与跨站播放跳转。
 */
object WebAppWhitelist {

    data class ServiceGroup(
        val id: String,
        val domains: Set<String>,
        val schemes: Set<String>,
        val packages: Set<String>,
        /** 网盘转存类始终放行；流媒体播放类仅在发现页跳转播放时放行唤起 App */
        val alwaysAllow: Boolean,
    )

    data class ParsedIntentUri(
        val scheme: String?,
        val packageName: String?,
        val fallbackUrl: String?,
    )

    private val CLOUD_TRANSFER_GROUPS: List<ServiceGroup> = listOf(
        ServiceGroup(
            id = "115",
            domains = setOf("115.com", "115cdn.com", "115cdn.net", "115cdn.org", "anxia.com"),
            schemes = setOf("115", "oof.disk", "anxia", "115browser"),
            packages = setOf("com.ylmf.androidclient", "com.ylmf.androidclient.vip"),
            alwaysAllow = true,
        ),
        ServiceGroup(
            id = "quark",
            domains = setOf("quark.cn", "quark.sm.cn"),
            schemes = setOf("quark", "qklink"),
            packages = setOf("com.quark.browser"),
            alwaysAllow = true,
        ),
        ServiceGroup(
            id = "alipan",
            domains = setOf("alipan.com", "aliyundrive.com"),
            schemes = setOf("alipan", "aliyundrive", "smartdrive"),
            packages = setOf("com.alicloud.databox"),
            alwaysAllow = true,
        ),
        ServiceGroup(
            id = "baidupan",
            domains = setOf("pan.baidu.com", "yun.baidu.com"),
            schemes = setOf("bdnetdisk", "baidunetdisk"),
            packages = setOf("com.baidu.netdisk", "com.baidu.netdisk.samsung"),
            alwaysAllow = true,
        ),
        ServiceGroup(
            id = "pikpak",
            domains = setOf("mypikpak.com", "mypikpak.net"),
            schemes = setOf("pikpak"),
            packages = setOf("com.pikcloud.pikpak"),
            alwaysAllow = true,
        ),
        ServiceGroup(
            id = "cloud189",
            domains = setOf("cloud.189.cn", "e.189.cn"),
            schemes = setOf("ecloud", "cloud189"),
            packages = setOf("com.cn21.ecloud"),
            alwaysAllow = true,
        ),
        ServiceGroup(
            id = "xunlei",
            domains = setOf("pan.xunlei.com", "xunlei.com"),
            schemes = setOf("xunlei", "thunder", "ed2k"),
            packages = setOf("com.xunlei.downloadprovider"),
            alwaysAllow = true,
        ),
        ServiceGroup(
            id = "ucdrive",
            domains = setOf("drive.uc.cn", "fast.uc.cn", "yun.uc.cn"),
            schemes = setOf("ucdrive", "ucbrowser"),
            packages = setOf("com.UCMobile", "com.ucmobile.lite"),
            alwaysAllow = true,
        ),
        ServiceGroup(
            id = "123pan",
            domains = setOf("123pan.com", "123pan.cn", "123684.com", "123865.com", "123912.com", "123592.com"),
            schemes = setOf("pan123", "mfcloudcalculate"),
            packages = setOf("com.mfcloudcalculate.networkdisk"),
            alwaysAllow = true,
        ),
        ServiceGroup(
            id = "mcloud",
            domains = setOf("caiyun.139.com", "yun.139.com"),
            schemes = setOf("mcloud", "caiyun"),
            packages = setOf("com.chinamobile.mcloud"),
            alwaysAllow = true,
        ),
        ServiceGroup(
            id = "weiyun",
            domains = setOf("weiyun.com"),
            schemes = setOf("weiyun"),
            packages = setOf("com.qq.qcloud"),
            alwaysAllow = true,
        ),
        ServiceGroup(
            id = "onedrive",
            domains = setOf("onedrive.live.com", "1drv.ms", "sharepoint.com"),
            schemes = setOf("ms-onedrive", "onedrive"),
            packages = setOf("com.microsoft.skydrive"),
            alwaysAllow = true,
        ),
        ServiceGroup(
            id = "dropbox",
            domains = setOf("dropbox.com", "db.tt"),
            schemes = setOf("dropbox", "dbx-auth"),
            packages = setOf("com.dropbox.android"),
            alwaysAllow = true,
        ),
        ServiceGroup(
            id = "gdrive",
            domains = setOf("drive.google.com", "docs.google.com"),
            schemes = setOf("googledrive"),
            packages = setOf("com.google.android.apps.docs"),
            alwaysAllow = true,
        ),
        ServiceGroup(
            id = "mega",
            domains = setOf("mega.nz", "mega.io"),
            schemes = setOf("mega"),
            packages = setOf("mega.privacy.android.app"),
            alwaysAllow = true,
        ),
        ServiceGroup(
            id = "telegram",
            domains = setOf("t.me", "telegram.me", "telegram.org", "telegra.ph"),
            schemes = setOf("tg"),
            packages = setOf(
                "org.telegram.messenger",
                "org.telegram.messenger.web",
                "org.thunderdog.challegram",
                "tw.nekomimi.nekogram",
            ),
            alwaysAllow = true,
        ),
    )

    private val DISCOVER_PLAY_GROUPS: List<ServiceGroup> = listOf(
        ServiceGroup(
            id = "qqvideo",
            domains = setOf("v.qq.com", "film.qq.com", "wetv.vip"),
            schemes = setOf("tenvideo", "tenvideo2", "tenvideo3", "txvideo", "qqlive", "wetv"),
            packages = setOf("com.tencent.qqlive", "com.tencent.qqlivei18n"),
            alwaysAllow = false,
        ),
        ServiceGroup(
            id = "iqiyi",
            domains = setOf("iqiyi.com", "iq.com", "pps.tv"),
            schemes = setOf("iqiyi", "qiyi-iphone", "qyplayer", "iqiyimobile"),
            packages = setOf("com.qiyi.video", "com.iqiyi.i18n", "tv.pps.mobile"),
            alwaysAllow = false,
        ),
        ServiceGroup(
            id = "youku",
            domains = setOf("youku.com", "tudou.com"),
            schemes = setOf("youku", "youkuhd"),
            packages = setOf("com.youku.phone"),
            alwaysAllow = false,
        ),
        ServiceGroup(
            id = "bilibili",
            domains = setOf("bilibili.com", "b23.tv", "hdslb.com", "biligame.com"),
            schemes = setOf("bilibili", "bili"),
            packages = setOf("tv.danmaku.bili", "com.bilibili.app.in", "com.bilibili.app.blue"),
            alwaysAllow = false,
        ),
        ServiceGroup(
            id = "mgtv",
            domains = setOf("mgtv.com", "hunantv.com"),
            schemes = setOf("imgotv", "mgtv"),
            packages = setOf("com.hunantv.imgo.activity"),
            alwaysAllow = false,
        ),
        ServiceGroup(
            id = "migu",
            domains = setOf("miguvideo.com", "migu.cn"),
            schemes = setOf("miguvideo", "migumoview", "cmvideo"),
            packages = setOf("com.cmcc.mitv"),
            alwaysAllow = false,
        ),
        ServiceGroup(
            id = "bytedance_video",
            domains = setOf("ixigua.com", "douyin.com"),
            schemes = setOf("snssdk32", "snssdk1128"),
            packages = setOf("com.ss.android.article.video", "com.ss.android.ugc.aweme"),
            alwaysAllow = false,
        ),
        ServiceGroup(
            id = "domestic_other_video",
            domains = setOf("tv.sohu.com", "1905.com", "yangshipin.cn", "cctv.com", "le.com"),
            schemes = setOf("sohuvideo", "yangshipin", "letvclient"),
            packages = setOf("com.sohu.sohuvideo", "com.cctv.yangshipin.app.androidp", "com.letv.android.client"),
            alwaysAllow = false,
        ),
        ServiceGroup(
            id = "netflix",
            domains = setOf("netflix.com", "nflx.it", "nflxext.com"),
            schemes = setOf("nflx"),
            packages = setOf("com.netflix.mediaclient"),
            alwaysAllow = false,
        ),
        ServiceGroup(
            id = "hbo_max",
            domains = setOf("max.com", "hbo.com", "hbomax.com"),
            schemes = setOf("max", "hbomax", "hbonow"),
            packages = setOf("com.wbd.stream", "com.hbo.hbonow"),
            alwaysAllow = false,
        ),
        ServiceGroup(
            id = "apple_tv",
            domains = setOf("tv.apple.com"),
            schemes = setOf("videos", "com.apple.tv"),
            packages = setOf("com.apple.atve.androidtv.appletv", "com.apple.atve.sony.appletv"),
            alwaysAllow = false,
        ),
        ServiceGroup(
            id = "disney_plus",
            domains = setOf("disneyplus.com"),
            schemes = setOf("disneyplus"),
            packages = setOf("com.disney.disneyplus"),
            alwaysAllow = false,
        ),
        ServiceGroup(
            id = "prime_video",
            domains = setOf("primevideo.com", "amazon.com"),
            schemes = setOf("primevideo", "aiv"),
            packages = setOf("com.amazon.avod.thirdpartyclient"),
            alwaysAllow = false,
        ),
        ServiceGroup(
            id = "hulu",
            domains = setOf("hulu.com"),
            schemes = setOf("hulu"),
            packages = setOf("com.hulu.plus"),
            alwaysAllow = false,
        ),
        ServiceGroup(
            id = "paramount_plus",
            domains = setOf("paramountplus.com"),
            schemes = setOf("paramountplus", "cbsaa"),
            packages = setOf("com.cbs.app"),
            alwaysAllow = false,
        ),
        ServiceGroup(
            id = "peacock",
            domains = setOf("peacocktv.com"),
            schemes = setOf("peacock"),
            packages = setOf("com.peacocktv.peacockandroid"),
            alwaysAllow = false,
        ),
        ServiceGroup(
            id = "youtube",
            domains = setOf("youtube.com", "youtu.be"),
            schemes = setOf("vnd.youtube", "youtube"),
            packages = setOf("com.google.android.youtube"),
            alwaysAllow = false,
        ),
    )

    private val ALL_GROUPS: List<ServiceGroup> = CLOUD_TRANSFER_GROUPS + DISCOVER_PLAY_GROUPS

    /** 发现页条目常关联的资料与聚合站点：用户在这些页面主动点击跳转到流媒体或网盘时不误拦为广告 */
    private val DISCOVER_BRIDGE_DOMAINS = setOf(
        "douban.com",
        "themoviedb.org",
        "imdb.com",
        "re0.me",
        "hdhive.com",
        "bing.com",
    )

    private val NON_APP_SCHEMES = setOf(
        "http", "https", "blob", "data", "javascript", "about", "file", "content", "magnet",
    )

    private val OPEN_APP_PROMPT_REGEX = Regex(
        """(打开|下载|安装|跳转|前往|启动|拉起|唤起|使用)[\s\S]{0,28}(app|客户端|应用)|""" +
            """(app|客户端|应用)[\s\S]{0,28}(打开|下载|安装|观看|播放|查看|阅读|浏览|体验|操作)|""" +
            """即将离开[\s\S]{0,24}(打开|跳转|前往)|""" +
            """open\s+(in\s+)?(the\s+)?app|download\s+(the\s+)?app|continue\s+in\s+(the\s+)?app""",
        RegexOption.IGNORE_CASE,
    )

    fun extractHost(urlOrHost: String?): String? {
        val raw = urlOrHost?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if ("://" in raw) {
            runCatching { URI(raw).host }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it.lowercase().trim('.') }
            val afterScheme = raw.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            val hostPart = afterScheme.substringAfterLast('@').substringBefore(':')
            return hostPart.lowercase().trim('.').takeIf { it.isNotEmpty() }
        }
        val clean = raw.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        return clean.lowercase().trim('.').takeIf { it.isNotEmpty() }
    }

    private fun matchesDomain(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    fun isCloudTransferDomain(urlOrHost: String?): Boolean {
        val host = extractHost(urlOrHost) ?: return false
        return CLOUD_TRANSFER_GROUPS.any { g -> g.domains.any { matchesDomain(host, it) } }
    }

    fun isPlayPlatformDomain(urlOrHost: String?): Boolean {
        val host = extractHost(urlOrHost) ?: return false
        return DISCOVER_PLAY_GROUPS.any { g -> g.domains.any { matchesDomain(host, it) } }
    }

    private fun isDiscoverBridgeDomain(urlOrHost: String?): Boolean {
        val host = extractHost(urlOrHost) ?: return false
        return DISCOVER_BRIDGE_DOMAINS.any { matchesDomain(host, it) }
    }

    /** 两个网址是否归属同一个网盘或流媒体服务生态（如 115cdn.com 与 m.115.com、aliyundrive.com 与 alipan.com） */
    fun sameServiceGroup(urlA: String?, urlB: String?): Boolean {
        val hostA = extractHost(urlA) ?: return false
        val hostB = extractHost(urlB) ?: return false
        if (hostA == hostB) return true
        if (matchesDomain(hostA, "re0.me") || matchesDomain(hostA, "hdhive.com")) {
            if (matchesDomain(hostB, "re0.me") || matchesDomain(hostB, "hdhive.com")) return true
        }
        return ALL_GROUPS.any { g ->
            g.domains.any { matchesDomain(hostA, it) } && g.domains.any { matchesDomain(hostB, it) }
        }
    }

    /** 当前网页是否处于「屏蔽打开 App 弹窗」白名单中（返回 true 表示不清理该网页的打开 App 元素） */
    fun isCleanPageWhitelisted(urlOrHost: String?, fromDiscoverPlay: Boolean): Boolean {
        if (isCloudTransferDomain(urlOrHost)) return true
        if (fromDiscoverPlay && isPlayPlatformDomain(urlOrHost)) return true
        return false
    }

    /** 解析 Android intent:// 链接里的目标 scheme、package 与兜底网页地址 */
    fun parseIntentUri(rawUrl: String): ParsedIntentUri? {
        if (!rawUrl.startsWith("intent:", ignoreCase = true)) return null
        val markerIdx = rawUrl.indexOf("#Intent;", ignoreCase = true)
        val uriSchemePart = rawUrl.substringAfter("://", "").substringBefore("#")
        if (markerIdx < 0) {
            return ParsedIntentUri(scheme = null, packageName = null, fallbackUrl = null)
        }
        val tail = rawUrl.substring(markerIdx + "#Intent;".length)
        var scheme: String? = null
        var pkg: String? = null
        var fallback: String? = null
        for (token in tail.split(';')) {
            when {
                token.equals("end", ignoreCase = true) -> break
                token.startsWith("scheme=", ignoreCase = true) -> {
                    scheme = token.substringAfter('=').trim().lowercase().ifEmpty { null }
                }
                token.startsWith("package=", ignoreCase = true) -> {
                    pkg = token.substringAfter('=').trim().ifEmpty { null }
                }
                token.startsWith("S.browser_fallback_url=", ignoreCase = true) -> {
                    val encoded = token.substringAfter('=').trim()
                    val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
                    if (decoded.startsWith("https://", ignoreCase = true) || decoded.startsWith("http://", ignoreCase = true)) {
                        fallback = decoded
                    }
                }
            }
        }
        if (scheme == null && uriSchemePart.contains("://")) {
            scheme = uriSchemePart.substringBefore("://").lowercase().ifEmpty { null }
        }
        return ParsedIntentUri(scheme = scheme, packageName = pkg, fallbackUrl = fallback)
    }

    /**
     * 判断非 http(s) 的自定义协议或 intent:// 链接是否允许唤起外部 App：
     * - 网盘转存类 App 始终允许；
     * - 流媒体播放类 App 在发现页跳转播放（fromDiscoverPlay = true）时允许；
     * - 其他站点的拉起请求一律拦截留在网页内。
     */
    fun shouldAllowAppLaunch(rawUrl: String?, pageUrl: String?, fromDiscoverPlay: Boolean): Boolean {
        val url = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val colon = url.indexOf(':')
        if (colon <= 0) return false
        val outerScheme = url.substring(0, colon).lowercase()
        if (outerScheme in NON_APP_SCHEMES) return false

        if (outerScheme == "intent") {
            val parsed = parseIntentUri(url) ?: return false
            val innerScheme = parsed.scheme?.lowercase()
            val pkg = parsed.packageName
            val cloudMatch = CLOUD_TRANSFER_GROUPS.any { g ->
                (innerScheme != null && innerScheme in g.schemes) ||
                    (pkg != null && g.packages.any { it.equals(pkg, ignoreCase = true) })
            }
            if (cloudMatch) return true
            if (fromDiscoverPlay) {
                val playMatch = DISCOVER_PLAY_GROUPS.any { g ->
                    (innerScheme != null && innerScheme in g.schemes) ||
                        (pkg != null && g.packages.any { it.equals(pkg, ignoreCase = true) })
                }
                if (playMatch) return true
            }
            return false
        }

        if (CLOUD_TRANSFER_GROUPS.any { outerScheme in it.schemes }) return true
        if (fromDiscoverPlay && DISCOVER_PLAY_GROUPS.any { outerScheme in it.schemes }) return true
        // 若当前页面本身就是受信任网盘域名（如 115 / 夸克等更新了私有唤醒前缀），且并非通用危险协议，放行
        if (isCloudTransferDomain(pageUrl) && outerScheme.matches(Regex("^[a-z][a-z0-9+.-]{1,31}$"))) {
            return true
        }
        return false
    }

    /**
     * 判断跨站 HTTP(S) 跳转是否属于白名单正常跳转（不拦截为「已拦截跳转广告」）：
     * 1. 跳往任何白名单网盘域名（如从搜索站 / 发现页 / 115cdn.com 跳往 m.115.com、pan.quark.cn、alipan.com 等）；
     * 2. 同一服务生态内多域名互跳（如 115cdn.com <-> 115.com <-> anxia.com、iqiyi.com <-> iq.com、bilibili.com <-> b23.tv）；
     * 3. 发现页跳转播放标签页跳往正版流媒体平台；
     * 4. 用户主动点击（userTapped）从豆瓣 / TMDB / 影巢等发现页关联站跳往正版流媒体平台。
     */
    fun isWhitelistedCrossSiteNavigation(
        targetUrl: String?,
        pageUrl: String?,
        fromDiscoverPlay: Boolean,
        userTapped: Boolean = false,
    ): Boolean {
        if (targetUrl.isNullOrBlank()) return false
        if (isCloudTransferDomain(targetUrl)) return true
        if (sameServiceGroup(targetUrl, pageUrl)) return true
        if (fromDiscoverPlay && (isPlayPlatformDomain(targetUrl) || isDiscoverBridgeDomain(targetUrl))) return true
        if (userTapped && isPlayPlatformDomain(targetUrl) && isDiscoverBridgeDomain(pageUrl)) return true
        return false
    }

    /** 识别网页 JS confirm() 弹出的「是否打开某某 App」确认框文案 */
    fun isOpenAppPromptText(message: String?): Boolean {
        val text = message?.trim()?.takeIf { it.isNotEmpty() && it.length <= 160 } ?: return false
        return OPEN_APP_PROMPT_REGEX.containsMatchIn(text)
    }
}
