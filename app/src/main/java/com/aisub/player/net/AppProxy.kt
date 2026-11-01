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
                if (p != Proxy.NO_PROXY && !isLocal(uri?.host)) return listOf(p)
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

    private fun isLocal(host: String?): Boolean {
        val h = host?.lowercase()?.trim('[', ']') ?: return true
        if (h == "localhost" || h.endsWith(".local") || h == "::1" || (!h.contains('.') && !h.contains(':'))) return true
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 127 || p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254)
    }
}
