package com.aisub.player.live

/**
 * 一个直播频道。userAgent / referrer 来自 M3U 里的 #EXTVLCOPT 或属性，有些源必须带上才能播放。
 */
data class LiveChannel(
    val name: String,
    val url: String,
    val group: String = "",
    val logo: String = "",
    val tvgId: String = "",
    val userAgent: String = "",
    val referrer: String = "",
    val tvgName: String = "",
    val catchup: String = "",
    val catchupSource: String = "",
)

/** 解析出的播放列表：频道列表 + 表头里写的节目单地址（x-tvg-url / url-tvg）。 */
data class ParsedPlaylist(
    val channels: List<LiveChannel>,
    val epgUrls: List<String> = emptyList(),
)

/**
 * 解析直播源：
 * - M3U / M3U8 播放列表（#EXTM3U、#EXTINF 带 tvg-name、tvg-logo、group-title 等属性，#EXTGRP，#EXTVLCOPT）
 * - 国内常见的 TXT 格式：「分组名,#genre#」开始一个分组，之后每行「频道名,地址」；一行多个地址用 # 分隔
 */
object LivePlaylist {
    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(text: String): List<LiveChannel> = parseWithHeader(text).channels

    fun parseWithHeader(text: String): ParsedPlaylist {
        val body = text.removePrefix("\uFEFF")
        return if (body.lineSequence().any { it.trimStart().startsWith("#EXTINF", ignoreCase = true) } ||
            body.trimStart().startsWith("#EXTM3U", ignoreCase = true)
        ) parseM3u(body) else ParsedPlaylist(parseTxt(body))
    }

    private fun parseM3u(text: String): ParsedPlaylist {
        val out = mutableListOf<LiveChannel>()
        val epgUrls = mutableListOf<String>()
        var defaultCatchup = ""
        var defaultCatchupSource = ""
        var info: Map<String, String> = emptyMap()
        var name = ""
        var group = ""
        var userAgent = ""
        var referrer = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val rawEpg = listOfNotNull(attrs["x-tvg-url"], attrs["url-tvg"], attrs["tvg-url"]).joinToString(",")
                    rawEpg.split(',', ';').map { it.trim() }
                        .filter { it.startsWith("http://") || it.startsWith("https://") }
                        .forEach { if (it !in epgUrls) epgUrls += it }
                    defaultCatchup = attrs["catchup"] ?: attrs["catchup-type"].orEmpty()
                    defaultCatchupSource = attrs["catchup-source"].orEmpty()
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val head = line.substringAfter(':')
                    info = ATTR.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    // 名称是最后一个不在引号里的逗号之后的部分
                    name = head.substring(lastCommaOutsideQuotes(head) + 1).trim()
                    group = info["group-title"].orEmpty()
                    userAgent = info["user-agent"] ?: info["http-user-agent"].orEmpty()
                    referrer = info["referrer"] ?: info["http-referrer"].orEmpty()
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    when {
                        opt.startsWith("http-user-agent=", true) -> userAgent = opt.substringAfter('=').trim()
                        opt.startsWith("http-referrer=", true) || opt.startsWith("http-referer=", true) -> referrer = opt.substringAfter('=').trim()
                    }
                }
                line.startsWith("#") -> {}
                else -> {
                    val url = line
                    val tvgName = info["tvg-name"].orEmpty()
                    val title = name.ifEmpty { tvgName }.ifEmpty { url.substringAfterLast('/') }
                    val catchup = info["catchup"] ?: info["catchup-type"] ?: defaultCatchup
                    val catchupSource = info["catchup-source"] ?: defaultCatchupSource
                    out += LiveChannel(
                        name = title, url = url, group = group,
                        logo = info["tvg-logo"].orEmpty(), tvgId = info["tvg-id"].orEmpty(),
                        userAgent = userAgent, referrer = referrer,
                        tvgName = tvgName, catchup = catchup, catchupSource = catchupSource,
                    )
                    info = emptyMap(); name = ""; userAgent = ""; referrer = ""
                    // #EXTGRP 只作用于下一条；group-title 每条 #EXTINF 都会重新给
                    group = ""
                }
            }
        }
        return ParsedPlaylist(out, epgUrls)
    }

    private fun parseTxt(text: String): List<LiveChannel> {
        val out = mutableListOf<LiveChannel>()
        var group = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//")) continue
            val comma = line.indexOf(',').takeIf { it > 0 } ?: line.indexOf('，').takeIf { it > 0 } ?: continue
            val name = line.substring(0, comma).trim()
            val rest = line.substring(comma + 1).trim()
            if (rest.equals("#genre#", ignoreCase = true)) {
                group = name
                continue
            }
            // 一个频道多个备用地址：用 # 分开
            rest.split('#').map { it.trim() }.filter { it.contains("://") }.forEach { url ->
                out += LiveChannel(name, url, group)
            }
        }
        return out
    }

    private fun lastCommaOutsideQuotes(s: String): Int {
        var quoted = false
        var last = -1
        s.forEachIndexed { i, c ->
            when (c) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) last = i
            }
        }
        return last
    }
}
