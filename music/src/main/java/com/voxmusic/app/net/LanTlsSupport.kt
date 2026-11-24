package com.voxmusic.app.net

import android.annotation.SuppressLint
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 局域网自签证书与标准公网 TLS 支持：公网域名严格走系统信任链校验，局域网/本机允许自签证书。
 */
object LanTlsSupport {

    fun isLanHost(host: String?): Boolean = AppProxy.isPrivateOrLocalHost(host)

    fun wrapTrustManager(delegate: X509TrustManager): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkServerTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }

    fun sslPair(): Pair<SSLSocketFactory, X509TrustManager> {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val systemTm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        @SuppressLint("CustomX509TrustManager")
        val lanTm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.acceptedIssuers
        }
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(lanTm), SecureRandom())
        }
        return ctx.socketFactory to systemTm
    }

    fun routingSocketFactory(publicFactory: SSLSocketFactory, lanFactory: SSLSocketFactory): SSLSocketFactory =
        object : SSLSocketFactory() {
            private fun pick(host: String?) = if (isLanHost(host)) lanFactory else publicFactory
            override fun getDefaultCipherSuites(): Array<String> = publicFactory.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = publicFactory.supportedCipherSuites
            override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
                pick(host).createSocket(s, host, port, autoClose)
            override fun createSocket(host: String?, port: Int): Socket = pick(host).createSocket(host, port)
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                pick(host).createSocket(host, port, localHost, localPort)
            override fun createSocket(host: InetAddress?, port: Int): Socket =
                pick(host?.hostAddress).createSocket(host, port)
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                pick(address?.hostAddress).createSocket(address, port, localAddress, localPort)
        }

    fun hostnameVerifier(delegate: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()): HostnameVerifier =
        HostnameVerifier { hostname, session ->
            if (isLanHost(hostname)) true else delegate.verify(hostname, session)
        }
}
