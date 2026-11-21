package com.aisub.player.net

import android.os.Build
import androidx.annotation.RequiresApi
import com.aisub.player.remote.Endpoints
import okhttp3.internal.tls.OkHostnameVerifier
import java.net.Socket
import java.security.KeyStore
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * 为已显式登记的双地址服务器（`baseUrl` + `lanUrl`）及私网自签名 NAS 提供安全的局域网 HTTPS 兼容层：
 * 1. 当通过内网地址连接但服务端返回外网配对域名证书（如 Let's Encrypt）时，对配对域名做严格 SAN 验证；
 * 2. 当对端地址属于已登记服务器的私网/回环地址（RFC1918 / `.local` / `.lan` / `127.0.0.1` 等）且使用自签名证书时予以放行；
 * 3. 对一切公网地址与未登记主机保持完整严格的默认 CA 与主机名校验，绝不放宽公网 TLS 安全性。
 */
internal object LanTlsSupport {

    internal interface LanTrustMarker

    private class LanHostnameVerifier(
        private val delegate: HostnameVerifier,
    ) : HostnameVerifier {
        override fun verify(hostname: String?, session: SSLSession?): Boolean {
            if (hostname.isNullOrBlank() || session == null) return false
            if (delegate.verify(hostname, session)) return true
            for (paired in Endpoints.pairedHostsFor(hostname)) {
                if (OkHostnameVerifier.verify(paired, session)) return true
            }
            if (Endpoints.isRegisteredPrivateServerHost(hostname)) {
                val hasPeerCerts = runCatching { session.peerCertificates.isNotEmpty() }.getOrDefault(false)
                if (hasPeerCerts) return true
            }
            return false
        }
    }

    fun hostnameVerifier(delegate: HostnameVerifier = OkHostnameVerifier): HostnameVerifier =
        if (delegate is LanHostnameVerifier) delegate else LanHostnameVerifier(delegate)

    fun platformDefaultTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    fun wrapTrustManager(base: X509TrustManager): X509TrustManager {
        if (base is LanTrustMarker) return base
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Api24.wrap(base)
        } else {
            Api23TrustManager(base)
        }
    }

    fun sslPair(
        baseTrustManager: X509TrustManager? = null,
        keyManagers: Array<KeyManager>? = null,
        provider: Provider? = null,
    ): Pair<SSLSocketFactory, X509TrustManager> {
        val tm = wrapTrustManager(baseTrustManager ?: platformDefaultTrustManager())
        val ctx = if (provider != null) {
            SSLContext.getInstance("TLS", provider)
        } else {
            SSLContext.getInstance("TLS")
        }
        ctx.init(keyManagers, arrayOf(tm), SecureRandom())
        trackedContexts.add(ctx)
        return ctx.socketFactory to tm
    }

    /**
     * 公网域名直通系统原生默认 [SSLSocketFactory]（完整保留 Android Conscrypt 会话缓存与底层快速路径），
     * 仅对已登记的局域网私网服务器切换至 [lanFactory]，确保公网流媒体（如 115 CDN）与 v2.10.12 零差异。
     */
    fun routingSocketFactory(defaultFactory: SSLSocketFactory, lanFactory: SSLSocketFactory): SSLSocketFactory {
        if (defaultFactory === lanFactory) return defaultFactory
        return object : SSLSocketFactory() {
            private fun pick(host: String?): SSLSocketFactory =
                if (Endpoints.isRegisteredPrivateServerHost(host)) lanFactory else defaultFactory

            override fun getDefaultCipherSuites(): Array<String> = defaultFactory.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = defaultFactory.supportedCipherSuites
            override fun createSocket(): Socket = defaultFactory.createSocket()
            override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
                pick(host).createSocket(s, host, port, autoClose)
            override fun createSocket(host: String?, port: Int): Socket =
                pick(host).createSocket(host, port)
            override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): Socket =
                pick(host).createSocket(host, port, localHost, localPort)
            override fun createSocket(host: java.net.InetAddress?, port: Int): Socket =
                pick(host?.hostAddress).createSocket(host, port)
            override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): Socket =
                pick(address?.hostAddress).createSocket(address, port, localAddress, localPort)
        }
    }

    private val trackedContexts = java.util.concurrent.CopyOnWriteArrayList<SSLContext>()

    internal fun invalidateSessions() {
        for (ctx in trackedContexts) {
            runCatching {
                val sc = ctx.clientSessionContext ?: return@runCatching
                val ids = sc.ids
                while (ids.hasMoreElements()) {
                    sc.getSession(ids.nextElement())?.invalidate()
                }
            }
        }
    }

    private fun shouldAllowPrivateUntrusted(chain: Array<out X509Certificate>?, peerHost: String?): Boolean {
        if (chain.isNullOrEmpty()) return false
        return Endpoints.isRegisteredPrivateServerHost(peerHost)
    }

    private fun extractSocketPeerHost(socket: Socket?): String? {
        val ssl = socket as? SSLSocket
        val sessionHost = ssl?.handshakeSession?.peerHost?.takeIf { it.isNotBlank() }
            ?: ssl?.session?.peerHost?.takeIf { it.isNotBlank() }
        return sessionHost ?: socket?.inetAddress?.hostAddress
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private object Api24 {
        fun wrap(base: X509TrustManager): X509TrustManager = object : X509ExtendedTrustManager(), LanTrustMarker {
            private val extBase = base as? X509ExtendedTrustManager
            private val androidExt = runCatching { android.net.http.X509TrustManagerExtensions(base) }.getOrNull()

            @androidx.annotation.Keep
            @Throws(CertificateException::class)
            fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                host: String?,
            ): List<X509Certificate> {
                try {
                    val ext = androidExt
                    if (ext != null) return ext.checkServerTrusted(chain, authType, host)
                    base.checkServerTrusted(chain, authType)
                    return chain?.toList().orEmpty()
                } catch (e: CertificateException) {
                    if (shouldAllowPrivateUntrusted(chain, host)) {
                        return chain?.toList().orEmpty()
                    }
                    throw e
                }
            }

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                base.checkClientTrusted(chain, authType)
            }

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
                if (extBase != null) extBase.checkClientTrusted(chain, authType, socket)
                else base.checkClientTrusted(chain, authType)
            }

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
                if (extBase != null) extBase.checkClientTrusted(chain, authType, engine)
                else base.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // 无对端主机上下文时保持严格校验，绝不放行未验证证书
                base.checkServerTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
                try {
                    if (extBase != null) extBase.checkServerTrusted(chain, authType, socket)
                    else base.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    if (!shouldAllowPrivateUntrusted(chain, extractSocketPeerHost(socket))) throw e
                }
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
                try {
                    if (extBase != null) extBase.checkServerTrusted(chain, authType, engine)
                    else base.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    if (!shouldAllowPrivateUntrusted(chain, engine?.peerHost)) throw e
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers
        }
    }

    /** API 23（Android 6）无 X509ExtendedTrustManager：仅当证书自身携带已登记私网或配对域名身份时才允许自签名降级。 */
    private class Api23TrustManager(
        private val base: X509TrustManager,
    ) : X509TrustManager, LanTrustMarker {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            base.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                base.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                val leaf = chain?.firstOrNull() ?: throw e
                val sanNames = runCatching {
                    leaf.subjectAlternativeNames.orEmpty()
                        .mapNotNull { entry -> entry.getOrNull(1)?.toString() }
                }.getOrDefault(emptyList())
                val allow = sanNames.any { name ->
                    Endpoints.isRegisteredPrivateServerHost(name) ||
                        Endpoints.pairedHostsFor(name).any { Endpoints.isRegisteredPrivateServerHost(it) }
                }
                if (!allow) throw e
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers
    }
}
