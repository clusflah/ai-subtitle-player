package com.aisub.player.download

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * 下载版：yt-dlp 用的登录 cookies（Netscape cookies.txt 格式），存在应用私有目录，只在下载时交给 yt-dlp。
 * 来源：应用内网页登录后从 WebView 读取，或导入电脑浏览器扩展导出的 cookies.txt。
 */
class CookieJar(context: Context) {

    val file = File(context.filesDir, "yt-dlp-cookies.txt")

    /** 一条 cookie；key 用于合并（同域名同名的后来者覆盖）。 */
    data class Entry(val domain: String, val subdomains: Boolean, val path: String, val secure: Boolean, val expiry: Long, val name: String, val value: String) {
        val key get() = "$domain\t$path\t$name"
        fun line() = listOf(domain, if (subdomains) "TRUE" else "FALSE", path, if (secure) "TRUE" else "FALSE", expiry.toString(), name, value).joinToString("\t")
    }

    val exists: Boolean get() = file.length() > 0

    /** 已保存 cookies 的网站（主域名），用于设置页显示。 */
    fun sites(): List<String> = read().map { registrable(it.domain.trimStart('.')) }.distinct().sorted()

    fun clear() {
        file.delete()
    }

    /** 导入 cookies.txt，与已有的合并。返回导入的条数。 */
    fun import(context: Context, uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("无法读取文件")
        val entries = parse(text)
        if (entries.isEmpty()) throw IOException("文件里没有找到 cookies，请导出 Netscape 格式的 cookies.txt")
        write(merge(read(), entries))
        return entries.size
    }

    /**
     * 保存 WebView 里某个网站的 cookies。cookieHeader 是 CookieManager.getCookie 的结果（name=value; ...），
     * 没有域名和过期时间，按主域名、一年后过期写入。
     */
    fun saveFromWebView(host: String, cookieHeader: String): Int {
        val domain = "." + registrable(host)
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val entries = cookieHeader.split(';').mapNotNull { part ->
            val kv = part.trim()
            val i = kv.indexOf('=')
            if (i <= 0) null else Entry(domain, true, "/", true, expiry, kv.substring(0, i), kv.substring(i + 1))
        }
        if (entries.isNotEmpty()) write(merge(read(), entries))
        return entries.size
    }

    private fun read(): List<Entry> = if (file.exists()) parse(file.readText()) else emptyList()

    private fun write(entries: List<Entry>) {
        file.writeText("# Netscape HTTP Cookie File\n" + entries.joinToString("\n") { it.line() } + "\n")
    }

    companion object {
        fun parse(text: String): List<Entry> = text.lines().mapNotNull { raw ->
            // #HttpOnly_ 前缀表示仅 HTTP 的 cookie，内容照常使用
            val line = raw.removePrefix("#HttpOnly_")
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val f = line.split('\t')
            if (f.size < 7) return@mapNotNull null
            Entry(f[0], f[1].equals("TRUE", true), f[2], f[3].equals("TRUE", true), f[4].toLongOrNull() ?: 0, f[5], f.drop(6).joinToString("\t"))
        }

        fun merge(old: List<Entry>, new: List<Entry>): List<Entry> {
            val map = LinkedHashMap<String, Entry>()
            (old + new).forEach { map[it.key] = it }
            return map.values.toList()
        }

        /** 粗略取主域名：www.bilibili.com → bilibili.com（不处理 co.uk 这类二级后缀） */
        fun registrable(host: String): String = host.trimStart('.').split('.').takeLast(2).joinToString(".")
    }
}

/** 应用内网页登录的预设网站。cookieUrls 是登录后要读取 cookies 的地址。 */
data class LoginSite(val name: String, val loginUrl: String, val cookieUrls: List<String>, val hint: String = "")

val LOGIN_SITES = listOf(
    LoginSite("哔哩哔哩", "https://passport.bilibili.com/login", listOf("https://www.bilibili.com", "https://passport.bilibili.com")),
    LoginSite("抖音", "https://www.douyin.com/", listOf("https://www.douyin.com")),
    LoginSite(
        "YouTube", "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F",
        listOf("https://www.youtube.com", "https://accounts.google.com"),
        hint = "Google 可能不允许在应用内网页登录，失败时请在电脑浏览器导出 cookies.txt 后导入",
    ),
)
