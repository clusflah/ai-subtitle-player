package com.aisub.player.net

import android.app.Application
import android.net.Uri
import com.aisub.player.media.MediaProxy
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LanTlsAndProxyTest {

    private val servers = ArrayList<MockWebServer>()

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        AppProxy.apply(ctx, ProxyType.OFF, "", 0, bypassCn = true)
        AppProxy.systemSelectorForTest = null
        Endpoints.clearForTest()
        MediaProxy.clearRecentErrorsForTest()
    }

    @After
    fun tearDown() {
        val ctx = RuntimeEnvironment.getApplication()
        AppProxy.apply(ctx, ProxyType.OFF, "", 0, bypassCn = true)
        AppProxy.systemSelectorForTest = null
        Endpoints.clearForTest()
        MediaProxy.clearRecentErrorsForTest()
        servers.forEach { runCatching { it.shutdown() } }
        servers.clear()
    }

    @Test
    fun proxyOffBypassesSystemProxyForLanWhileAppProxyBypassesCnHosts() {
        val ctx = RuntimeEnvironment.getApplication()
        val fakeClash = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 7890))
        AppProxy.systemSelectorForTest = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(fakeClash)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        }

        AppProxy.apply(ctx, ProxyType.OFF, "", 0, bypassCn = true)
        assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("https://192.168.1.10:5006/dav")))
        assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("https://127.0.0.1:5006/dav")))
        assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("https://nas.lan:5006/dav")))
        assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("https://[fd00::1]:5006/dav")))
        assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("https://[fe80::1%25eth0]:5006/dav")))
        assertEquals(listOf(fakeClash), AppProxy.selector.select(URI("https://proapi.115.com/app/chrome/downurl")))
        assertEquals(listOf(fakeClash), AppProxy.selector.select(URI("https://api.openai.com/v1/models")))

        val appProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 10809))
        AppProxy.apply(ctx, ProxyType.HTTP, "127.0.0.1", 10809, bypassCn = true)
        assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("https://www.bilibili.com/")))
        assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("https://cdn.115cdn.org/file.mkv")))
        assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("https://nas.example.cn:5006/dav")))
        assertEquals(listOf(appProxy, Proxy.NO_PROXY), AppProxy.selector.select(URI("https://api.openai.com/v1/models")))
    }

    @Test
    fun registeredDualUrlHttpsAllowsPairedLanSelfSignedCertButRejectsUnregistered() {
        val leafCert = HeldCertificate.Builder()
            .commonName("nas.example.com")
            .addSubjectAlternativeName("nas.example.com")
            .build()
        val serverHold = HandshakeCertificates.Builder()
            .heldCertificate(leafCert)
            .build()
        val mockServer = MockWebServer().apply {
            useHttps(serverHold.sslSocketFactory(), false)
            enqueue(MockResponse().setResponseCode(200))
            enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            enqueue(MockResponse().setResponseCode(200).setBody("should-fail"))
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        servers += mockServer

        val lanUrl = "https://127.0.0.1:${mockServer.port}"
        val wanUrl = "https://nas.example.com:${mockServer.port}"
        val srv = Server(
            id = "test-dual-nas",
            type = ServerType.WEBDAV,
            name = "TestNAS",
            baseUrl = wanUrl,
            lanUrl = lanUrl,
        )
        Endpoints.register(srv)

        // 公网域名绝不因已登记而被放宽私网自签名放行规则
        assertTrue(Endpoints.isRegisteredPrivateServerHost("127.0.0.1"))
        assertFalse(Endpoints.isRegisteredPrivateServerHost("nas.example.com"))
        assertEquals(setOf("nas.example.com"), Endpoints.pairedHostsFor("127.0.0.1"))

        assertEquals(lanUrl, Endpoints.base(srv))

        val client = AppProxy.builder().build()
        client.newCall(Request.Builder().url("$lanUrl/video.mkv").build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("ok", resp.body?.string())
        }

        // 注销登记并清连接池后，未登记主机使用自签名证书必须被严格拒绝
        Endpoints.clearForTest()
        client.connectionPool.evictAll()
        assertFalse(Endpoints.isRegisteredPrivateServerHost("127.0.0.1"))
        assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url("$lanUrl/video.mkv").build()).execute().close()
        }
    }

    @Test
    fun registeredDualUrlHttpsVerifiesCaSignedPairedDomainCertOverLanIp() {
        val caCert = HeldCertificate.Builder()
            .certificateAuthority(0)
            .commonName("Example CA")
            .build()
        val leafCert = HeldCertificate.Builder()
            .signedBy(caCert)
            .commonName("nas.example.com")
            .addSubjectAlternativeName("nas.example.com")
            .build()
        val serverHold = HandshakeCertificates.Builder()
            .heldCertificate(leafCert, caCert.certificate)
            .build()
        val caTrust = HandshakeCertificates.Builder()
            .addTrustedCertificate(caCert.certificate)
            .build()

        val mockServer = MockWebServer().apply {
            useHttps(serverHold.sslSocketFactory(), false)
            enqueue(MockResponse().setResponseCode(200).setBody("ca-ok"))
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        servers += mockServer

        val (sf, tm) = LanTlsSupport.sslPair(baseTrustManager = caTrust.trustManager)
        val client = AppProxy.builder().sslSocketFactory(sf, tm).build()

        // 未登记时：CA 信任但 IP 不匹配 CN=nas.example.com -> 必须拒绝
        assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url("https://127.0.0.1:${mockServer.port}/test").build()).execute().close()
        }

        // 登记双地址后：通过内网 IP 连接可使用外网配对域名 SAN 校验通过
        Endpoints.register(
            Server(
                id = "test-ca-nas",
                type = ServerType.WEBDAV,
                name = "CaNas",
                baseUrl = "https://nas.example.com:${mockServer.port}",
                lanUrl = "https://127.0.0.1:${mockServer.port}",
            ),
        )
        client.newCall(Request.Builder().url("https://127.0.0.1:${mockServer.port}/test").build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("ca-ok", resp.body?.string())
        }
    }

    @Test
    fun mediaProxyRecordsAndClearsUpstreamErrors() {
        val err = SSLHandshakeException("Remote host terminated the handshake")
        val proxyUrl = "http://127.0.0.1:41234/deadbeef01/movie.mkv"
        val target = Uri.parse("https://nas.example.com:5006/movie.mkv").toString()
        MediaProxy.recordUpstreamError(
            token = "deadbeef01",
            targetUri = target,
            error = MediaProxy.UpstreamError(
                targetUri = target,
                httpStatusCode = null,
                causeClass = err.javaClass.name,
                message = err.message ?: "handshake failed",
                cause = err,
            ),
        )
        val found = MediaProxy.findRecentUpstreamError(proxyUrl)
        assertNotNull(found)
        assertEquals(target, found?.targetUri)

        MediaProxy.clearUpstreamError("deadbeef01", target)
        assertEquals(null, MediaProxy.findRecentUpstreamError(proxyUrl))
    }
}
