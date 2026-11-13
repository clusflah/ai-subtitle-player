package com.aisub.player.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import okhttp3.OkHttpClient
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

    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val p = proxy
            // 代理连不上（电脑关了、代理软件没开局域网连接）时接着直连，免得什么都打不开
            if (p != Proxy.NO_PROXY && !isLocal(uri?.host)) return listOf(p, Proxy.NO_PROXY)
            return system?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            system?.connectFailed(uri, sa, ioe)
        }
    }

    /** 应用里的 OkHttp 都从这里建：直接用 [selector]，不受系统改默认 ProxySelector 的影响 */
    fun builder(): OkHttpClient.Builder {
        val b = OkHttpClient.Builder().proxySelector(selector)
        applyCompatSsl(b)
        return b
    }

    private var compatSslPair: Pair<javax.net.ssl.SSLSocketFactory, javax.net.ssl.X509TrustManager>? = null

    private fun applyCompatSsl(builder: OkHttpClient.Builder) {
        if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.N_MR1) return
        val pair = compatSslPair ?: synchronized(this) {
            compatSslPair ?: runCatching {
                if (java.security.Security.getProvider(org.conscrypt.Conscrypt.newProvider().name) == null) {
                    java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
                }
                val cf = java.security.cert.CertificateFactory.getInstance("X.509")
                val isrgCert = ISRG_ROOT_X1_PEM.byteInputStream().use { cf.generateCertificate(it) as java.security.cert.X509Certificate }
                val certificates = okhttp3.tls.HandshakeCertificates.Builder()
                    .addPlatformTrustedCertificates()
                    .addTrustedCertificate(isrgCert)
                    .build()
                (certificates.sslSocketFactory() to certificates.trustManager).also { compatSslPair = it }
            }.getOrNull()
        } ?: return
        builder.sslSocketFactory(pair.first, pair.second)
    }

    fun install(context: Context) {
        ProxySelector.setDefault(selector)
        // 网络切换（WiFi ↔ 移动数据）、系统代理变化时，系统会把每个进程的默认 ProxySelector 换回它自己的
        // （android.net.Proxy.setHttpProxyConfiguration），之后新建的连接就不走这里的代理了。收到变化后装回来；
        // 系统那边也是在主线程上换，先后不定，所以稍后再补两次
        val main = Handler(Looper.getMainLooper())
        val reinstall = Runnable { if (ProxySelector.getDefault() !== selector) ProxySelector.setDefault(selector) }
        fun later() {
            main.post(reinstall)
            main.postDelayed(reinstall, 1000)
            main.postDelayed(reinstall, 5000)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = later()
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = later()
            })
        } else {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val req = android.net.NetworkRequest.Builder().build()
            cm?.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = later()
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = later()
            })
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = later()
        }, IntentFilter(android.net.Proxy.PROXY_CHANGE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
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
            val exec = ContextCompat.getMainExecutor(context)
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
        val http = builder().proxy(p)
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

/** Let's Encrypt ISRG Root X1 根证书（Android 7.1.1 及以下系统未内置，此处嵌入供 Conscrypt / OkHttp-TLS 信任） */
private const val ISRG_ROOT_X1_PEM = """-----BEGIN CERTIFICATE-----
MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
-----END CERTIFICATE-----"""
