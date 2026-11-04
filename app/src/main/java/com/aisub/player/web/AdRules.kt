package com.aisub.player.web

/** 请求类型：拦截时从地址后缀和 Accept 头猜，猜不出是 null（这时限定了类型的规则照样算命中） */
enum class AdType { SCRIPT, IMAGE, STYLESHEET, XHR, MEDIA, SUBDOCUMENT, FONT, OTHER }

/**
 * 拦下的请求回什么：
 * - EMPTY 空内容（默认）；
 * - NODATA / NOLIST 网站接口「没有广告」时的结果 `{"code":200,"msg":"操作成功","data":null}` / `"data":[]`，
 *   影视网站自己的广告接口用，网页以为没有广告，不会因为请求失败出错（`$nodata`、`$nolist`）
 */
enum class AdResponse { EMPTY, NODATA, NOLIST }

/** 规则对一个请求的结论：拦（回什么），或者例外放行 */
sealed interface AdDecision {
    data class Block(val response: AdResponse) : AdDecision
    data object Allow : AdDecision
}

/**
 * 广告拦截规则（AdGuard / EasyList 格式）的一个子集：
 * - 请求：`||域名^`、`||域名/路径`，例外 `@@`，选项 third-party、domain=、请求类型、important；
 * - 元素隐藏：`##选择器`（通用）、`域名##选择器`（只对这些网站），例外 `#@#`，`$elemhide` / `$generichide`。
 * AdGuard 自己的扩展（`#$#` 样式、`#%#` 脚本、`#?#` 扩展选择器、`$redirect` 等）不认。
 * 不带域名的规则（像 `/api/ad/`，比对整个地址）只在 [custom] 时认：下载的规则里这种容易误伤，自己写的是有意的。
 */
class AdRules private constructor(
    private val block: Map<String, List<NetRule>>,
    private val allow: Map<String, List<NetRule>>,
    /** 不带域名的规则，按顺序比对整个地址 */
    private val blockAnywhere: List<NetRule>,
    private val allowAnywhere: List<NetRule>,
    /** 整个网站不隐藏元素 / 不用通用的隐藏规则（`@@||域名^$elemhide` / `$generichide`） */
    private val noCosmetic: Set<String>,
    private val noGeneric: Set<String>,
    private val generic: List<GenericSelector>,
    private val site: Map<String, List<GenericSelector>>,
    /** 例外 `域名#@#选择器`：这些网站上不隐藏这个选择器；"" 是对所有网站 */
    private val cosmeticExceptions: Map<String, Set<String>>,
) {
    /** 规则条数（请求 + 元素隐藏） */
    val size: Int = block.values.sumOf { it.size } + allow.values.sumOf { it.size } + blockAnywhere.size + allowAnywhere.size +
        generic.size + site.values.sumOf { it.size }

    private class NetRule(
        val path: Regex?,
        /** true 只拦第三方的，false 只拦同站的，null 都拦 */
        val thirdParty: Boolean?,
        val domains: List<String>,
        val notDomains: List<String>,
        val types: Set<AdType>?,
        val notTypes: Set<AdType>,
        val important: Boolean,
        val response: AdResponse,
    )

    /** 选择器和不用它的网站（`~域名`） */
    private class GenericSelector(val selector: String, val notDomains: List<String>)

    /** 这个请求要不要拦（[pageUrl] 是所在的网页，判断第三方和 domain= 用） */
    fun shouldBlock(url: String, pageUrl: String?, type: AdType?): Boolean = decide(url, pageUrl, type) is AdDecision.Block

    /** 规则对这个请求的结论；没有规则管它是 null */
    fun decide(url: String, pageUrl: String?, type: AdType?): AdDecision? {
        val host = hostOf(url) ?: return null
        val pageHost = pageUrl?.let(::hostOf)
        // 规则写的是 ||域名/路径：路径从域名后面开始比（请求的域名以规则的域名结尾，后面的部分一样）
        val lower = url.lowercase()
        val tail = lower.indexOf(host, lower.indexOf("://") + 3).let { if (it < 0) "" else lower.substring(it + host.length) }
        fun find(byHost: Map<String, List<NetRule>>, anywhere: List<NetRule>): NetRule? {
            if (byHost.isNotEmpty()) for (h in suffixes(host)) byHost[h]?.firstOrNull { it.matches(tail, host, pageHost, type) }?.let { return it }
            return anywhere.firstOrNull { it.matches(lower, host, pageHost, type) }
        }
        val hit = find(block, blockAnywhere)
        if (hit != null && hit.important) return AdDecision.Block(hit.response)
        if (find(allow, allowAnywhere) != null) return AdDecision.Allow
        return hit?.let { AdDecision.Block(it.response) }
    }

    private fun NetRule.matches(tail: String, host: String, pageHost: String?, type: AdType?): Boolean {
        if (thirdParty != null && pageHost != null && thirdParty != (siteOf(host) != siteOf(pageHost))) return false
        if (domains.isNotEmpty() && (pageHost == null || domains.none { matchesDomain(pageHost, it) })) return false
        if (pageHost != null && notDomains.any { matchesDomain(pageHost, it) }) return false
        if (type != null && (types?.contains(type) == false || type in notTypes)) return false
        return path == null || path.containsMatchIn(tail)
    }

    /** 这个网站要藏起来的元素（CSS），注入到网页里；没有是空串 */
    fun css(host: String): String {
        val h = host.lowercase()
        val hs = suffixes(h).toList()
        if (hs.any { it in noCosmetic }) return ""
        val except = HashSet<String>()
        cosmeticExceptions[""]?.let(except::addAll)
        for (s in hs) cosmeticExceptions[s]?.let(except::addAll)
        val out = StringBuilder()
        fun add(sel: String) {
            if (sel !in except) out.append(sel).append("{display:none!important}\n")
        }
        if (hs.none { it in noGeneric }) {
            for (g in generic) if (g.notDomains.none { matchesDomain(h, it) }) add(g.selector)
        }
        for (s in hs) site[s]?.forEach { if (it.notDomains.none { d -> matchesDomain(h, d) }) add(it.selector) }
        return out.toString()
    }

    companion object {
        /** [custom]：自己写的规则（内置的、用户加的），认不带域名的规则 */
        fun parse(lines: Sequence<String>, custom: Boolean = false): AdRules {
            val block = HashMap<String, MutableList<NetRule>>()
            val allow = HashMap<String, MutableList<NetRule>>()
            val blockAnywhere = ArrayList<NetRule>()
            val allowAnywhere = ArrayList<NetRule>()
            val noCosmetic = HashSet<String>()
            val noGeneric = HashSet<String>()
            val generic = ArrayList<GenericSelector>()
            val site = HashMap<String, MutableList<GenericSelector>>()
            val exceptions = HashMap<String, MutableSet<String>>()
            val seenGeneric = HashSet<String>()

            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line[0] == '!' || line[0] == '[') continue
                val cosmetic = COSMETIC.matchEntire(line)
                if (cosmetic != null) {
                    val (domainPart, sep, selector) = cosmetic.destructured
                    if (!usableSelector(selector)) continue
                    val doms = domainPart.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    val pos = doms.filterNot { it.startsWith("~") }
                    val neg = doms.filter { it.startsWith("~") }.map { it.substring(1) }
                    if (sep == "#@#") {
                        if (pos.isEmpty()) exceptions.getOrPut("") { HashSet() }.add(selector)
                        else pos.forEach { exceptions.getOrPut(it) { HashSet() }.add(selector) }
                    } else if (pos.isEmpty()) {
                        if (neg.isNotEmpty() || seenGeneric.add(selector)) generic.add(GenericSelector(selector, neg))
                    } else {
                        val sel = GenericSelector(selector, neg)
                        pos.forEach { site.getOrPut(it) { ArrayList() }.add(sel) }
                    }
                    continue
                }
                if (line.contains("#$#") || line.contains("#%#") || line.contains("#?#") || line.contains("#@$#") ||
                    line.contains("#@%#") || line.contains("#@?#") || line.contains("##+js") || line.contains("##^")
                ) continue
                parseNet(line, custom, block, allow, blockAnywhere, allowAnywhere, noCosmetic, noGeneric)
            }
            return AdRules(block, allow, blockAnywhere, allowAnywhere, noCosmetic, noGeneric, generic, site, exceptions)
        }

        private fun parseNet(
            line: String,
            custom: Boolean,
            block: HashMap<String, MutableList<NetRule>>,
            allow: HashMap<String, MutableList<NetRule>>,
            blockAnywhere: MutableList<NetRule>,
            allowAnywhere: MutableList<NetRule>,
            noCosmetic: MutableSet<String>,
            noGeneric: MutableSet<String>,
        ) {
            val exception = line.startsWith("@@")
            var body = if (exception) line.substring(2) else line
            var options = ""
            val dollar = body.lastIndexOf('$')
            if (dollar >= 0 && OPTIONS.matches(body.substring(dollar + 1))) {
                options = body.substring(dollar + 1)
                body = body.substring(0, dollar)
            }
            val host: String?
            val rest: String
            if (body.startsWith("||")) {
                body = body.substring(2)
                val hostEnd = body.indexOfFirst { it == '^' || it == '/' || it == '*' || it == '|' || it == ':' || it == '?' }
                    .let { if (it < 0) body.length else it }
                host = body.substring(0, hostEnd).lowercase()
                if (host.isEmpty() || !HOST.matches(host)) return
                rest = body.substring(hostEnd)
            } else {
                // 不带域名：比对整个地址里的任何位置；只认自己写的，而且要有点长度，免得一条 "ad" 把半个网拦了
                if (!custom || body.length < 4 || body.startsWith("/") && body.endsWith("/") && body.length > 2 && body.count { it == '/' } == 2) return
                host = null
                rest = body.removePrefix("|")
            }

            var thirdParty: Boolean? = null
            val domains = ArrayList<String>()
            val notDomains = ArrayList<String>()
            var types: MutableSet<AdType>? = null
            val notTypes = HashSet<AdType>()
            var important = false
            var elemhide = false
            var generichide = false
            var response = AdResponse.EMPTY
            if (options.isNotEmpty()) for (o in options.lowercase().split(',')) {
                val opt = o.trim()
                val neg = opt.startsWith("~")
                val name = opt.removePrefix("~")
                when {
                    name == "third-party" || name == "3p" -> thirdParty = !neg
                    name == "first-party" || name == "1p" -> thirdParty = neg
                    name.startsWith("domain=") -> name.removePrefix("domain=").split('|').forEach { d ->
                        if (d.startsWith("~")) notDomains.add(d.substring(1)) else if (d.isNotEmpty()) domains.add(d)
                    }
                    name == "important" -> important = true
                    name == "match-case" || name == "all" -> {}
                    name == "empty" -> response = AdResponse.EMPTY
                    name == "nodata" -> response = AdResponse.NODATA
                    name == "nolist" -> response = AdResponse.NOLIST
                    name == "elemhide" || name == "ehide" -> elemhide = true
                    name == "generichide" || name == "ghide" -> generichide = true
                    TYPES.containsKey(name) -> {
                        val t = TYPES.getValue(name)
                        if (neg) notTypes.add(t) else (types ?: HashSet<AdType>().also { types = it }).add(t)
                    }
                    // 其余选项（redirect、popup、document、removeparam、csp……）这里做不到，整条不要
                    else -> return
                }
            }
            if (elemhide || generichide) {
                // 只是关掉元素隐藏的例外，不影响请求
                if (host != null && exception && (rest.isEmpty() || rest == "^")) {
                    if (elemhide) noCosmetic.add(host) else noGeneric.add(host)
                }
                return
            }
            if (host == null) {
                val path = runCatching { Regex(globToRegex(rest.lowercase())) }.getOrNull() ?: return
                val rule = NetRule(path, thirdParty, domains, notDomains, types, notTypes, important, response)
                (if (exception) allowAnywhere else blockAnywhere).add(rule)
                return
            }
            val path = when (rest) {
                "", "^", "^|", "|" -> null
                else -> runCatching { Regex("^" + globToRegex(rest)) }.getOrNull() ?: return
            }
            val rule = NetRule(path, thirdParty, domains, notDomains, types, notTypes, important, response)
            (if (exception) allow else block).getOrPut(host) { ArrayList(1) }.add(rule)
        }

        /** 规则里的 `*` 任意、`^` 分隔符、结尾 `|` 结束 */
        private fun globToRegex(s: String): String {
            val sb = StringBuilder()
            s.forEachIndexed { i, c ->
                when {
                    c == '*' -> sb.append(".*")
                    c == '^' -> sb.append("(?:[^a-zA-Z0-9_.%-]|$)")
                    c == '|' && i == s.lastIndex -> sb.append('$')
                    else -> sb.append(Regex.escape(c.toString()))
                }
            }
            return sb.toString()
        }

        /** 浏览器不认的 AdGuard 伪类：这种选择器放进样式表也没用 */
        private fun usableSelector(s: String): Boolean =
            s.isNotBlank() && s.length < 2000 && !s.startsWith(":vox-") && EXTENDED.none { s.contains(it, ignoreCase = true) }

        fun hostOf(url: String): String? {
            val start = url.indexOf("://").takeIf { it > 0 }?.plus(3) ?: return null
            val scheme = url.substring(0, start - 3).lowercase()
            if (scheme != "http" && scheme != "https" && scheme != "ws" && scheme != "wss") return null
            var end = start
            while (end < url.length && url[end] !in "/?#") end++
            var host = url.substring(start, end).substringAfterLast('@')
            if (host.startsWith("[")) return null
            host = host.substringBefore(':')
            return host.lowercase().trimEnd('.').takeIf { it.isNotEmpty() }
        }

        /** a.b.example.com → a.b.example.com、b.example.com、example.com、com */
        private fun suffixes(host: String) = sequence {
            var h = host
            while (true) {
                yield(h)
                val dot = h.indexOf('.')
                if (dot < 0) break
                h = h.substring(dot + 1)
            }
        }

        private fun matchesDomain(host: String, domain: String) =
            host == domain || host.endsWith(".$domain")

        /** 粗略的「同一个网站」：最后两段，像 com.cn、co.uk 这种取最后三段 */
        internal fun siteOf(host: String): String {
            val parts = host.split('.')
            if (parts.size <= 2) return host
            val last2 = parts.takeLast(2).joinToString(".")
            return if (parts[parts.size - 2] in SECOND_LEVEL && parts.size >= 3) parts.takeLast(3).joinToString(".") else last2
        }

        private val SECOND_LEVEL = setOf("com", "net", "org", "gov", "edu", "co", "ac", "or", "ne", "go")
        private val COSMETIC = Regex("""^([\w.,~*-]*)(##|#@#)(.+)$""")
        private val OPTIONS = Regex("""[\w~,=|.*-]+""")
        private val HOST = Regex("""[a-z0-9.-]+""")
        private val TYPES = mapOf(
            "script" to AdType.SCRIPT, "image" to AdType.IMAGE, "stylesheet" to AdType.STYLESHEET,
            "xmlhttprequest" to AdType.XHR, "xhr" to AdType.XHR, "media" to AdType.MEDIA,
            "subdocument" to AdType.SUBDOCUMENT, "frame" to AdType.SUBDOCUMENT, "font" to AdType.FONT,
            "object" to AdType.OTHER, "other" to AdType.OTHER, "ping" to AdType.OTHER,
        )
        private val EXTENDED = listOf(
            ":-abp-", ":contains(", ":has-text(", ":matches-css", ":xpath(", ":nth-ancestor(", ":upward(",
            ":remove(", ":style(", ":-ext-", ":if(", ":if-not(", ":matches-property(", ":min-text-length(",
            ":matches-attr(", ":watch-attr(", "[-ext-",
        )
    }
}
