package com.aisub.player.media

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import com.aisub.player.authDataSourceFactory
import com.aisub.player.net.NetworkSpeedTracker
import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType
import com.aisub.player.remote.cloud.CloudClient
import com.aisub.player.remote.cloud.CloudRegistry
import com.aisub.player.remote.cloud.ResolvedLink
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Pan115AndroidPlaybackAndSpeedTest {

    private lateinit var tempDir: File
    private val serversToClose = ArrayList<StrictSingleConn115Server>()
    private val createdServerIds = ArrayList<String>()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("voxtitle-android-115-test").toFile()
        CloudRegistry.serverLookup = { null }
        CloudStreamDataSource.clearAll()
        NetworkSpeedTracker.resetNetworkSpeed()
    }

    @After
    fun tearDown() {
        serversToClose.forEach { runCatching { it.close() } }
        serversToClose.clear()
        createdServerIds.forEach { runCatching { CloudRegistry.forget(it) } }
        createdServerIds.clear()
        CloudRegistry.serverLookup = { null }
        CloudStreamDataSource.clearAll()
        NetworkSpeedTracker.resetNetworkSpeed()
        runCatching { tempDir.deleteRecursively() }
    }

    @Test
    fun networkSpeedTracker_slidingWindowFormattingAndLoopbackExclusion() {
        NetworkSpeedTracker.resetNetworkSpeed()
        assertEquals(0L, NetworkSpeedTracker.currentDownloadSpeedBytesPerSec(10_000L))
        assertEquals("0 KB/s", NetworkSpeedTracker.formatNetworkSpeed(0L))
        assertEquals("45.2 KB/s", NetworkSpeedTracker.formatNetworkSpeed((45.2 * 1024).toLong()))
        assertEquals("512 KB/s", NetworkSpeedTracker.formatNetworkSpeed(512L * 1024L))
        assertEquals("3.4 MB/s", NetworkSpeedTracker.formatNetworkSpeed((3.4 * 1024 * 1024).toLong()))
        assertEquals("125 MB/s", NetworkSpeedTracker.formatNetworkSpeed(125L * 1024L * 1024L))

        assertTrue(NetworkSpeedTracker.isLoopbackHost("127.0.0.1"))
        assertTrue(NetworkSpeedTracker.isLoopbackHost("localhost"))
        assertTrue(NetworkSpeedTracker.isLoopbackHost("::1"))
        assertTrue(NetworkSpeedTracker.isLoopbackHost("[::1]"))
        assertFalse(NetworkSpeedTracker.isLoopbackHost("cdn.115.com"))

        // 连续 15 个 100ms 桶各写入 200 KB => 1.5s 窗口约 2000 KB/s
        for (i in 0 until 15) {
            NetworkSpeedTracker.recordNetworkBytes(200L * 1024L, nowMs = 10_000L + i * 100L)
        }
        val measured = NetworkSpeedTracker.currentDownloadSpeedBytesPerSec(nowMs = 11_450L)
        assertTrue("Expected ~2 MB/s (2048000 B/s), got $measured", measured in 1_850_000L..2_250_000L)

        // 超过 2s 无新流量后归零
        assertEquals(0L, NetworkSpeedTracker.currentDownloadSpeedBytesPerSec(nowMs = 13_600L))

        // 验证 Media3 TransferListener 对 127.0.0.1 回环读流不计入外网网速，仅外网域名计入
        val probeBytes = ByteArray(128 * 1024) { 0x5A }
        val smallFile = File(tempDir, "loopback_probe.bin").apply { writeBytes(probeBytes) }
        val srv = StrictSingleConn115Server(smallFile, requiredCookie = null, chunkDelayMs = 1L).also {
            serversToClose += it
        }

        NetworkSpeedTracker.resetNetworkSpeed()
        val ds = DefaultHttpDataSource.Factory()
            .setTransferListener(NetworkSpeedTracker.transferListener)
            .createDataSource()
        var totalRead = 0
        try {
            ds.open(DataSpec.Builder().setUri(srv.url("/probe.bin")).build())
            val buf = ByteArray(16_384)
            while (true) {
                val n = ds.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                totalRead += n
            }
        } finally {
            ds.close()
        }
        assertEquals(smallFile.length().toInt(), totalRead)
        assertEquals(
            "Loopback 127.0.0.1 reads must not pollute network speed",
            0L,
            NetworkSpeedTracker.currentDownloadSpeedBytesPerSec(),
        )

        // 外网 URI 通过 transferListener 上报应当产生正数速度
        val externalSpec = DataSpec.Builder().setUri("https://cdn.115.com/video.mkv").build()
        NetworkSpeedTracker.transferListener.onBytesTransferred(ds, externalSpec, true, 512 * 1024)
        assertTrue(
            "External URI transfer must be recorded in NetworkSpeedTracker",
            NetworkSpeedTracker.currentDownloadSpeedBytesPerSec() > 0L,
        )
    }

    @Test
    fun cloudRegistryResolve_isSingleFlightUnderConcurrentThreads() {
        val serverId = "mock115-${UUID.randomUUID()}"
        createdServerIds += serverId
        val server = Server(id = serverId, type = ServerType.PAN115, name = "Mock115", baseUrl = "")
        CloudRegistry.serverLookup = { id -> if (id == serverId) server else null }

        val resolveCount = AtomicInteger(0)
        injectMockCloudClient(server) { fileRef ->
            resolveCount.incrementAndGet()
            Thread.sleep(150L)
            ResolvedLink(
                url = "http://127.0.0.1:1/mock/$fileRef",
                headers = mapOf("Cookie" to "UID=test115", "User-Agent" to "Mock115/1.0"),
            )
        }

        val uri = "cloud://$serverId/pickcode123?n=video.mkv"
        val results = ConcurrentHashMap.newKeySet<String>()
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(8)
        repeat(8) {
            thread(isDaemon = true) {
                startGate.await()
                runCatching {
                    results += CloudRegistry.resolve(uri).url
                }
                doneGate.countDown()
            }
        }
        startGate.countDown()
        assertTrue(doneGate.await(5, TimeUnit.SECONDS))
        assertEquals("Concurrent CloudRegistry.resolve calls should share a single upstream request", 1, resolveCount.get())
        assertEquals(setOf("http://127.0.0.1:1/mock/pickcode123"), results)
    }

    @Test
    fun cloudStreamDataSource_preemptsCachesAndRecovers403AgainstStrictSingleConnServer() {
        val payload = ByteArray(384 * 1024) { i -> ((i * 131 + 17) and 0xFF).toByte() }
        val binFile = File(tempDir, "ep03_stream.mkv").apply { writeBytes(payload) }
        val cdn = StrictSingleConn115Server(binFile, requiredCookie = "UID=mock115", chunkDelayMs = 2L).also {
            serversToClose += it
        }

        val serverId = "mock115-${UUID.randomUUID()}"
        createdServerIds += serverId
        val server = Server(id = serverId, type = ServerType.PAN115, name = "Mock115", baseUrl = "")
        CloudRegistry.serverLookup = { id -> if (id == serverId) server else null }
        injectMockCloudClient(server) {
            ResolvedLink(
                url = cdn.url("/ep03.mkv"),
                headers = mapOf("Cookie" to "UID=mock115", "User-Agent" to "Mock115/1.0"),
            )
        }

        val factory = authDataSourceFactory(RuntimeEnvironment.getApplication(), wrapCloudSingleConn = true)
        val cloudUri = "cloud://$serverId/ep03?n=${Uri.encode("中头奖还是要上班.S01E03.mkv")}"

        // 1) 保持 ds1 占着前部流未关闭，ds2 并发抢占并从 192KB 处 seek 读取到底
        val ds1 = factory.createDataSource()
        val ds2 = factory.createDataSource()
        val seekOffset = 192 * 1024
        val tailBytes = ByteArrayOutputStream()
        try {
            ds1.open(DataSpec.Builder().setUri(cloudUri).setPosition(0L).build())
            val headBuf = ByteArray(8 * 1024)
            var headRead = 0
            while (headRead < headBuf.size) {
                val n = ds1.read(headBuf, headRead, headBuf.size - headRead)
                if (n <= 0) break
                headRead += n
            }
            assertEquals(headBuf.size, headRead)
            assertArrayEquals(payload.copyOfRange(0, headBuf.size), headBuf)

            ds2.open(DataSpec.Builder().setUri(cloudUri).setPosition(seekOffset.toLong()).build())
            val tmp = ByteArray(16_384)
            while (true) {
                val n = ds2.read(tmp, 0, tmp.size)
                if (n == C.RESULT_END_OF_INPUT) break
                tailBytes.write(tmp, 0, n)
            }
        } finally {
            runCatching { ds2.close() }
            runCatching { ds1.close() }
        }
        assertArrayEquals(payload.copyOfRange(seekOffset, payload.size), tailBytes.toByteArray())
        assertEquals("Preemptive single-conn DataSource must trigger zero 403 collisions", 0, cdn.rejected403Count.get())
        assertTrue("Max concurrent connections must stay <= 1 (was ${cdn.maxConcurrentSeen.get()})", cdn.maxConcurrentSeen.get() <= 1)

        // 2) 顺序读取 [0..128KB) 后再次读取 [0..128KB) 应完全命中内存分块缓存，不再请求上游
        val first128Kb = readRangeFully(factory.createDataSource(), cloudUri, 0L, 128L * 1024L)
        assertArrayEquals(payload.copyOfRange(0, 128 * 1024), first128Kb)
        val reqBeforeCacheHit = cdn.totalRequests.get()

        val cached128Kb = readRangeFully(factory.createDataSource(), cloudUri, 0L, 128L * 1024L)
        assertArrayEquals(payload.copyOfRange(0, 128 * 1024), cached128Kb)
        assertEquals(
            "Repeat read inside 64KB block cache must hit zero upstream requests",
            reqBeforeCacheHit,
            cdn.totalRequests.get(),
        )

        // 3) 测试 403 失效直链自动重解析恢复：首次返回 /stale-403 -> 触发 CloudRegistry.invalidate -> 第二次拿到 /fresh.mkv
        val retryServerId = "mock115-retry-${UUID.randomUUID()}"
        createdServerIds += retryServerId
        val retryServer = Server(id = retryServerId, type = ServerType.PAN115, name = "Mock115Retry", baseUrl = "")
        val retryCdn = StrictSingleConn115Server(
            binFile,
            requiredCookie = "UID=mock115",
            chunkDelayMs = 0L,
            stalePath = "/stale-403",
        ).also { serversToClose += it }

        CloudRegistry.serverLookup = { id ->
            when (id) {
                serverId -> server
                retryServerId -> retryServer
                else -> null
            }
        }
        val resolveCalls = AtomicInteger(0)
        injectMockCloudClient(retryServer) {
            val idx = resolveCalls.incrementAndGet()
            val path = if (idx == 1) "/stale-403" else "/fresh.mkv"
            ResolvedLink(
                url = retryCdn.url(path),
                headers = mapOf("Cookie" to "UID=mock115"),
            )
        }

        val retryUri = "cloud://$retryServerId/ep04?n=fresh.mkv"
        val recovered = readRangeFully(factory.createDataSource(), retryUri, 0L, 64L * 1024L)
        assertArrayEquals(payload.copyOfRange(0, 64 * 1024), recovered)
        assertEquals("403 on stale path should trigger re-resolve via CloudRegistry.invalidate", 2, resolveCalls.get())
        assertTrue(retryCdn.rejected403Count.get() >= 1)
    }

    @Test
    fun mediaProxy_serves128KbCachedLoopbackAndSingleConnSeekWithout403() {
        val payload = ByteArray(256 * 1024) { i -> ((i * 73 + 9) and 0xFF).toByte() }
        val binFile = File(tempDir, "proxy_sample.mkv").apply { writeBytes(payload) }
        val cdn = StrictSingleConn115Server(binFile, requiredCookie = "UID=mock115", chunkDelayMs = 2L).also {
            serversToClose += it
        }

        val serverId = "mock115-${UUID.randomUUID()}"
        createdServerIds += serverId
        val server = Server(id = serverId, type = ServerType.PAN115, name = "Mock115", baseUrl = "")
        CloudRegistry.serverLookup = { id -> if (id == serverId) server else null }
        injectMockCloudClient(server) {
            ResolvedLink(
                url = cdn.url("/proxy.mkv"),
                headers = mapOf("Cookie" to "UID=mock115"),
            )
        }

        val proxy = MediaProxy(authDataSourceFactory(RuntimeEnvironment.getApplication(), wrapCloudSingleConn = false))
        try {
            val cloudUri = Uri.parse("cloud://$serverId/ep03?n=proxy.mkv")
            val proxyUrl = proxy.register(cloudUri, "proxy.mkv")

            // 第一次读取前 128KB（跨越 2 个 64KB 缓存块）
            val pass1 = readRangeFully(DefaultHttpDataSource.Factory().createDataSource(), proxyUrl, 0L, 128L * 1024L)
            assertArrayEquals(payload.copyOfRange(0, 128 * 1024), pass1)
            val reqAfterPass1 = cdn.totalRequests.get()

            // 第二次从 MediaProxy 回环读取同一段 128KB，应直接命中缓存且不新增上游请求、不污染外网网速
            val pass2 = readRangeFully(DefaultHttpDataSource.Factory().createDataSource(), proxyUrl, 0L, 128L * 1024L)
            assertArrayEquals(payload.copyOfRange(0, 128 * 1024), pass2)
            assertEquals("MediaProxy repeat read inside cached 128KB must not reopen upstream", reqAfterPass1, cdn.totalRequests.get())
            assertEquals(
                "MediaProxy 127.0.0.1 loopback reads must not pollute network speed",
                0L,
                NetworkSpeedTracker.currentDownloadSpeedBytesPerSec(),
            )

            // 保持连接 A 占用流不关闭，连接 B 通过 MediaProxy 抢占并 seek 到 160KB 处读取到底
            val holdDs = DefaultHttpDataSource.Factory().createDataSource()
            val seekOffset = 160 * 1024
            try {
                holdDs.open(DataSpec.Builder().setUri(proxyUrl).setPosition(128L * 1024L).build())
                val holdBuf = ByteArray(4 * 1024)
                val firstRead = holdDs.read(holdBuf, 0, holdBuf.size)
                assertTrue("Hold stream must read bytes before preemption", firstRead > 0)

                val seekData = readRangeFully(DefaultHttpDataSource.Factory().createDataSource(), proxyUrl, seekOffset.toLong(), C.LENGTH_UNSET.toLong())
                assertArrayEquals(payload.copyOfRange(seekOffset, payload.size), seekData)
            } finally {
                runCatching { holdDs.close() }
            }

            assertEquals("MediaProxy preemption + seek must trigger zero 403 collisions", 0, cdn.rejected403Count.get())
            assertTrue("Upstream active connections must stay <= 1 (was ${cdn.maxConcurrentSeen.get()})", cdn.maxConcurrentSeen.get() <= 1)
        } finally {
            proxy.close()
        }
    }

    private fun readRangeFully(ds: androidx.media3.datasource.DataSource, uri: String, pos: Long, len: Long): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            ds.open(DataSpec.Builder().setUri(uri).setPosition(pos).setLength(len).build())
            val buf = ByteArray(16_384)
            while (true) {
                val n = ds.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
        } finally {
            runCatching { ds.close() }
        }
        return out.toByteArray()
    }

    private fun injectMockCloudClient(server: Server, resolver: (String) -> ResolvedLink) {
        val client = object : CloudClient(server) {
            override suspend fun list(path: String): List<RemoteItem> = emptyList()
            override fun resolve(fileRef: String): ResolvedLink = resolver(fileRef)
        }
        val clientsField = CloudRegistry::class.java.getDeclaredField("clients").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = clientsField.get(CloudRegistry) as ConcurrentHashMap<String, CloudClient>
        map[server.id] = client
    }

    private class StrictSingleConn115Server(
        private val file: File,
        private val requiredCookie: String?,
        private val chunkDelayMs: Long,
        private val stalePath: String? = null,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        private val activeCount = AtomicInteger(0)
        val maxConcurrentSeen = AtomicInteger(0)
        val rejected403Count = AtomicInteger(0)
        val totalRequests = AtomicLong(0)
        val port: Int get() = server.localPort

        init {
            thread(name = "mock-android-115-cdn", isDaemon = true) {
                while (!server.isClosed) {
                    val sock = runCatching { server.accept() }.getOrNull() ?: continue
                    thread(name = "mock-android-115-conn", isDaemon = true) {
                        sock.use(::handle)
                    }
                }
            }
        }

        fun url(path: String): String = "http://127.0.0.1:$port$path"

        private fun handle(sock: Socket) {
            runCatching { sock.tcpNoDelay = true }
            val ins = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val reqLine = ins.readLine() ?: return
            val headers = generateSequence { ins.readLine()?.takeIf { it.isNotEmpty() } }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val partsLine = reqLine.split(' ')
            val method = partsLine.getOrNull(0).orEmpty()
            val reqPath = partsLine.getOrNull(1).orEmpty()
            val out = BufferedOutputStream(sock.getOutputStream(), 65_536)

            if (stalePath != null && reqPath == stalePath) {
                rejected403Count.incrementAndGet()
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                drainClientClose(sock, ins)
                return
            }

            if (requiredCookie != null && !headers["cookie"].orEmpty().contains(requiredCookie)) {
                rejected403Count.incrementAndGet()
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                drainClientClose(sock, ins)
                return
            }

            totalRequests.incrementAndGet()
            val cur = activeCount.incrementAndGet()
            maxConcurrentSeen.updateAndGet { prev -> maxOf(prev, cur) }
            try {
                if (cur > 1) {
                    rejected403Count.incrementAndGet()
                    out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }

                val len = file.length()
                val rawRange = headers["range"]?.lowercase()?.removePrefix("bytes=")?.trim()
                val parts = rawRange?.split('-')
                val start = parts?.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
                val end = parts?.getOrNull(1)?.trim()?.toLongOrNull()?.coerceAtMost(len - 1) ?: (len - 1)
                if (start < 0 || start >= len || end < start) {
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$len\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }

                val respLen = end - start + 1
                val head = StringBuilder().apply {
                    if (rawRange != null) {
                        append("HTTP/1.1 206 Partial Content\r\n")
                        append("Content-Range: bytes $start-$end/$len\r\n")
                    } else {
                        append("HTTP/1.1 200 OK\r\n")
                    }
                    append("Content-Type: application/octet-stream\r\n")
                    append("Content-Length: $respLen\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(head.toString().toByteArray())
                out.flush()

                if (method == "GET") {
                    RandomAccessFile(file, "r").use { raf ->
                        raf.seek(start)
                        val buf = ByteArray(16_384)
                        var remaining = respLen
                        while (remaining > 0L && !sock.isClosed) {
                            val want = minOf(buf.size.toLong(), remaining).toInt()
                            val n = raf.read(buf, 0, want)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            out.flush()
                            remaining -= n
                            if (chunkDelayMs > 0L) Thread.sleep(chunkDelayMs)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is java.net.SocketException && e !is java.io.InterruptedIOException) {
                    System.err.println("StrictSingleConn115Server error: $e")
                }
            } finally {
                activeCount.decrementAndGet()
            }
            drainClientClose(sock, ins)
        }

        private fun drainClientClose(sock: Socket, ins: java.io.Reader) {
            if (sock.isClosed) return
            runCatching {
                sock.soTimeout = 1_000
                val drain = CharArray(256)
                while (ins.read(drain) >= 0) {
                    // 等待客户端按 Content-Length 读完并关闭，避免 Windows 回环提前关闭触发 RST
                }
            }
            if (!sock.isClosed) {
                runCatching { sock.shutdownOutput() }
            }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }
}
