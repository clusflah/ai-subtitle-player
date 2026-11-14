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
        compatSsl()?.let { (sf, tm) -> b.sslSocketFactory(sf, tm) }
        return b
    }

    @Volatile
    private var compatSslPair: Pair<javax.net.ssl.SSLSocketFactory, javax.net.ssl.X509TrustManager>? = null

    /** Android 6/7 兼容：安装 Conscrypt TLS 1.3 并追加现代公共根证书，同时设为全局默认 SSLSocketFactory。 */
    fun initCompatTls(context: Context) {
        if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.N_MR1) return
        if (compatSslPair != null) return
        synchronized(this) {
            if (compatSslPair != null) return
            runCatching {
                val newProvider = org.conscrypt.Conscrypt.newProvider()
                val provider = java.security.Security.getProvider(newProvider.name) ?: newProvider.also {
                    java.security.Security.insertProviderAt(it, 1)
                }
                val cf = java.security.cert.CertificateFactory.getInstance("X.509")
                val certs = context.resources.openRawResource(com.aisub.player.R.raw.compat_ca_roots).use { ins ->
                    cf.generateCertificates(ins).filterIsInstance<java.security.cert.X509Certificate>()
                }
                val hb = okhttp3.tls.HandshakeCertificates.Builder().addPlatformTrustedCertificates()
                for (cert in certs) hb.addTrustedCertificate(cert)
                val certificates = hb.build()
                val sslContext = javax.net.ssl.SSLContext.getInstance("TLS", provider).apply {
                    init(arrayOf(certificates.keyManager), arrayOf(certificates.trustManager), java.security.SecureRandom())
                }
                javax.net.ssl.SSLContext.setDefault(sslContext)
                javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
                compatSslPair = sslContext.socketFactory to certificates.trustManager
            }.onFailure { android.util.Log.w("Voxtitle", "初始化兼容 TLS 失败", it) }
        }
    }

    fun compatSsl(): Pair<javax.net.ssl.SSLSocketFactory, javax.net.ssl.X509TrustManager>? = compatSslPair

    fun currentProxy(): Proxy? = proxy.takeIf { it != Proxy.NO_PROXY }

    /** 兼容 API 23 的默认网络变化监听：统一加 NET_CAPABILITY_INTERNET 与异常保护。 */
    fun observeDefaultNetwork(context: Context, callback: ConnectivityManager.NetworkCallback) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                val req = android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(req, callback)
            }
        }
    }

    fun install(context: Context) {
        initCompatTls(context)
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
        observeDefaultNetwork(context, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = later()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = later()
        })
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
