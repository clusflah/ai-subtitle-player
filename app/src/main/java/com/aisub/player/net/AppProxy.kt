package com.aisub.player.net

import android.content.Context
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** 代理类型 */
enum class ProxyType(val label: String) { OFF("不使用"), HTTP("HTTP"), SOCKS("SOCKS5") }

/**
 * 应用内代理（便于走局域网里另一台设备的代理）。
 * 启动时装一个全局 ProxySelector：OkHttp、HttpURLConnection、播放器的网络请求都会经过它，
 * 改设置后立即生效，不用重建客户端。本机和局域网地址始终直连（本地媒体转发、内网 WebDAV 等）。
 */
object AppProxy {
    @Volatile private var proxy: Proxy = Proxy.NO_PROXY
    private val system: ProxySelector? = ProxySelector.getDefault()

    fun install() {
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                val p = proxy
                // 代理连不上（电脑关了、代理软件没开局域网连接）时接着直连，免得什么都打不开
                if (p != Proxy.NO_PROXY && !isLocal(uri?.host)) return listOf(p, Proxy.NO_PROXY)
                return system?.select(uri) ?: listOf(Proxy.NO_PROXY)
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                system?.connectFailed(uri, sa, ioe)
            }
        })
    }

    fun apply(context: Context, type: ProxyType, host: String, port: Int) {
        val h = host.trim()
        val on = type != ProxyType.OFF && h.isNotEmpty() && port in 1..65535
        proxy = if (!on) Proxy.NO_PROXY else Proxy(
            if (type == ProxyType.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP,
            InetSocketAddress.createUnresolved(h, port),
        )
        // 内置浏览器（WebView）单独设置
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val exec = context.mainExecutor
            runCatching {
                if (on) {
                    val scheme = if (type == ProxyType.SOCKS) "socks5" else "http"
                    val cfg = ProxyConfig.Builder().addProxyRule("$scheme://$h:$port").bypassSimpleHostnames()
                        .addBypassRule("127.0.0.1").addBypassRule("localhost")
                        .addBypassRule("10.*").addBypassRule("192.168.*").addBypassRule("172.16.*").build()
                    ProxyController.getInstance().setProxyOverride(cfg, exec) {}
                } else {
                    ProxyController.getInstance().clearProxyOverride(exec) {}
                }
            }
        }
    }

    /**
     * 设置页的「测试连接」：先看代理端口连不连得上，再通过代理访问一次 Google 和 GitHub。阻塞调用。
     * 返回「连接成功…」或说明哪一步不通。
     */
    fun test(type: ProxyType, host: String, port: Int): String {
        val h = host.trim()
        if (type == ProxyType.OFF) return "没有使用代理"
        if (h.isEmpty() || port !in 1..65535) return "请先填写代理地址和端口"
        try {
            java.net.Socket().use { it.connect(InetSocketAddress(h, port), 5000) }
        } catch (e: IOException) {
            return "连接失败：连不上代理 $h:$port（${e.message ?: e.javaClass.simpleName}）。" +
                "请确认代理设备开着、和这台设备在同一个网络、地址和端口没变（手机或电脑的局域网地址可能会变），" +
                "并在代理软件里打开「允许局域网连接」"
        }
        val p = Proxy(if (type == ProxyType.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress.createUnresolved(h, port))
        val http = okhttp3.OkHttpClient.Builder().proxy(p)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
        val results = listOf("Google" to "https://www.google.com/generate_204", "GitHub" to "https://api.github.com/zen").map { (name, url) ->
            val start = System.currentTimeMillis()
            name to runCatching {
                http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.code }
                System.currentTimeMillis() - start
            }
        }
        val failed = results.filter { it.second.isFailure }
        return if (failed.isEmpty()) "连接成功：" + results.joinToString("，") { (n, r) -> "$n ${r.getOrNull()} 毫秒" }
        else "连接失败：代理端口能连上，但通过它访问 ${failed.joinToString("、") { it.first }} 失败" +
            "（${failed.first().second.exceptionOrNull()?.message ?: "未知错误"}）。检查代理类型（HTTP / SOCKS5）是否选对、代理软件里的节点是否可用"
    }

    private fun isLocal(host: String?): Boolean {
        val h = host?.lowercase()?.trim('[', ']') ?: return true
        if (h == "localhost" || h.endsWith(".local") || h == "::1" || (!h.contains('.') && !h.contains(':'))) return true
        // Plex 的「192-168-1-5.<编号>.plex.direct」就是局域网地址
        if (h.endsWith(".plex.direct")) h.substringBefore('.').takeIf { Regex("""\d+(-\d+){3}""").matches(it) }?.let { return isLocal(it.replace('-', '.')) }
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 127 || p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254)
    }
}
