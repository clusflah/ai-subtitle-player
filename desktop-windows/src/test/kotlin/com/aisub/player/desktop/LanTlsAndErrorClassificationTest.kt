package com.aisub.player.desktop

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.aisub.player.media.MediaProxy
import com.aisub.player.net.AppProxy
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException

@OptIn(UnstableApi::class)
class LanTlsAndErrorClassificationTest {

    private lateinit var tempDir: File
    private val servers = ArrayList<MockWebServer>()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("voxtitle-desktop-lantls").toFile()
        AppProxy.systemSelectorForTest = null
        Endpoints.clearForTest()
        MediaProxy.clearRecentErrorsForTest()
        DesktopPlayerEngine.vulkanPlaceboSupported = null
    }

    @After
    fun tearDown() {
        AppProxy.systemSelectorForTest = null
        Endpoints.clearForTest()
        MediaProxy.clearRecentErrorsForTest()
        DesktopPlayerEngine.vulkanPlaceboSupported = null
        servers.forEach { runCatching { it.shutdown() } }
        servers.clear()
        runCatching { tempDir.deleteRecursively() }
    }

    private fun createSelfSignedServerSsl(sanDns: String): SSLContext {
        val ksFile = File(tempDir, "test-server-$sanDns.p12")
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val keytool = File(System.getProperty("java.home"), "bin/keytool" + if (isWin) ".exe" else "").absolutePath
        val pass = "changeit"
        val proc = ProcessBuilder(
            keytool,
            "-genkeypair",
            "-alias", "nas",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "30",
            "-storetype", "PKCS12",
            "-keystore", ksFile.absolutePath,
            "-storepass", pass,
            "-keypass", pass,
            "-dname", "CN=$sanDns",
            "-ext", "SAN=dns:$sanDns",
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        check(code == 0 && ksFile.isFile) { "keytool failed ($code): $out" }

        val ks = KeyStore.getInstance("PKCS12")
        ksFile.inputStream().use { ks.load(it, pass.toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, pass.toCharArray())
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, null)
        }
    }

    @Test
    fun proxyOffBypassesSystemProxyForLanWhileAppProxyBypassesCnHosts() {
        val fakeClash = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 7890))
        AppProxy.systemSelectorForTest = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(fakeClash)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        }

        // 应用内代理为 OFF 时：局域网/回环始终直连，公网域名交由系统代理（Clash 规则）统一调度
        AppProxy.apply(android.content.GlobalDesktopApp, com.aisub.player.net.ProxyType.OFF, "", 0, bypassCn = true)
        assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("https://192.168.1.10:5006/dav")))
        assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("https://127.0.0.1:5006/dav")))
        assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("https://nas.lan:5006/dav")))
        assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("https://[fd00::1]:5006/dav")))
        assertEquals(listOf(fakeClash), AppProxy.selector.select(URI("https://proapi.115.com/app/chrome/downurl")))
        assertEquals(listOf(fakeClash), AppProxy.selector.select(URI("https://api.openai.com/v1/models")))

        // 开启应用内代理且勾选国内直连时：国内站点（含 115 全系 CDN 域名）直连，海外走应用内代理
        val appProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 10809))
        AppProxy.apply(android.content.GlobalDesktopApp, com.aisub.player.net.ProxyType.HTTP, "127.0.0.1", 10809, bypassCn = true)
        assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("https://www.bilibili.com/")))
        assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("https://cdn.115cdn.org/file.mkv")))
        assertEquals(listOf(Proxy.NO_PROXY), AppProxy.selector.select(URI("https://nas.example.cn:5006/dav")))
        assertEquals(listOf(appProxy, Proxy.NO_PROXY), AppProxy.selector.select(URI("https://api.openai.com/v1/models")))
        AppProxy.apply(android.content.GlobalDesktopApp, com.aisub.player.net.ProxyType.OFF, "", 0, bypassCn = true)
    }

    @Test
    fun endpointsProbingAndHttpClientSupportRegisteredDualUrlSelfSignedNasAndRejectUnregistered() {
        val sslCtx = createSelfSignedServerSsl("nas.example.com")
        val mockServer = MockWebServer().apply {
            useHttps(sslCtx.socketFactory, false)
            enqueue(MockResponse().setResponseCode(200))
            enqueue(MockResponse().setResponseCode(200).setBody("stream-ok"))
            enqueue(MockResponse().setResponseCode(200).setBody("should-fail"))
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        servers += mockServer

        val lanUrl = "https://127.0.0.1:${mockServer.port}"
        val wanUrl = "https://nas.example.com:${mockServer.port}"
        val srv = Server(
            id = "win-dual-nas",
            type = ServerType.WEBDAV,
            name = "WinNAS",
            baseUrl = wanUrl,
            lanUrl = lanUrl,
        )
        Endpoints.register(srv)

        assertTrue(Endpoints.isRegisteredPrivateServerHost("127.0.0.1"))
        assertFalse(Endpoints.isRegisteredPrivateServerHost("nas.example.com"))
        assertEquals(lanUrl, Endpoints.base(srv))

        val client = AppProxy.builder().build()
        client.newCall(Request.Builder().url("$lanUrl/movie.mkv").build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("stream-ok", resp.body?.string())
        }

        Endpoints.clearForTest()
        client.connectionPool.evictAll()
        var rejected = false
        try {
            client.newCall(Request.Builder().url("$lanUrl/movie.mkv").build()).execute().close()
        } catch (_: IOException) {
            rejected = true
        }
        assertTrue("Unregistered self-signed server must fail TLS verification", rejected)
    }

    @Test
    fun mediaProxyRecordsTlsAndHttpErrorsAndDesktopEngineDoesNotMisclassifyAsDecodeError() {
        val tlsErrorProxy = MediaProxy(
            DataSource.Factory {
                object : DataSource {
                    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}
                    override fun open(dataSpec: DataSpec): Long {
                        throw SSLHandshakeException("Remote host terminated the handshake")
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                    override fun getUri(): Uri? = null
                    override fun close() {}
                }
            },
        )
        try {
            val localUrl = tlsErrorProxy.register(Uri.parse("https://nas.example.com:5006/movie.mkv"), "movie.mkv")
            val conn = URI(localUrl).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
            assertEquals(502, conn.responseCode)

            val classified = DesktopPlayerEngine.classifyMediaOpenFailure(
                url = localUrl,
                rawItemUri = "https://nas.example.com:5006/movie.mkv",
                stderrText = "Server returned 502 Bad Gateway",
            )
            assertNotNull(classified)
            assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, classified!!.errorCode)
            assertNull(DesktopPlayerEngine.vulkanPlaceboSupported)
        } finally {
            tlsErrorProxy.close()
        }

        val http403Proxy = MediaProxy(
            DataSource.Factory {
                object : DataSource {
                    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}
                    override fun open(dataSpec: DataSpec): Long {
                        throw HttpDataSource.InvalidResponseCodeException(
                            403,
                            "Forbidden",
                            null,
                            emptyMap(),
                            dataSpec,
                            ByteArray(0),
                        )
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                    override fun getUri(): Uri? = null
                    override fun close() {}
                }
            },
        )
        try {
            val localUrl = http403Proxy.register(Uri.parse("https://nas.example.com:5006/secret.mkv"), "secret.mkv")
            val conn = URI(localUrl).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
            assertEquals(502, conn.responseCode)

            val classified = DesktopPlayerEngine.classifyMediaOpenFailure(
                url = localUrl,
                rawItemUri = "https://nas.example.com:5006/secret.mkv",
                stderrText = "Server returned 502 Bad Gateway",
            )
            assertNotNull(classified)
            assertEquals(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, classified!!.errorCode)
            assertTrue(classified.cause is HttpDataSource.InvalidResponseCodeException)
            assertEquals(403, (classified.cause as HttpDataSource.InvalidResponseCodeException).responseCode)
        } finally {
            http403Proxy.close()
        }

        // 对非网络/非 I/O 的真实解码错误（例如本地损坏文件）应返回 null，保留原有的解码 fallback 行为
        assertNull(
            DesktopPlayerEngine.classifyMediaOpenFailure(
                url = "C:\\videos\\corrupt.mkv",
                stderrText = "Invalid data found when processing input",
            ),
        )
    }
}
