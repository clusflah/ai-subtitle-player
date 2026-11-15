package com.aisub.player.desktop

import android.content.Context
import android.content.GlobalDesktopApp
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import com.aisub.player.media.MediaProxy
import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType
import com.aisub.player.remote.cloud.CloudClient
import com.aisub.player.remote.cloud.CloudRegistry
import com.aisub.player.remote.cloud.ResolvedLink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedOutputStream
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

@OptIn(UnstableApi::class)
class Pan115PlaybackAndSpeedTest {

    private lateinit var tempAppData: File
    private lateinit var mediaDir: File
    private val serversToClose = ArrayList<StrictSingleConn115Server>()
    private val createdServerIds = ArrayList<String>()

    @Before
    fun setUp() {
        tempAppData = Files.createTempDirectory("voxtitle-115-appdata").toFile()
        mediaDir = Files.createTempDirectory("voxtitle-115-media").toFile()
        Context.customAppDataRoot = tempAppData
        DesktopPlayerEngine.resetNetworkSpeed()
    }

    @After
    fun tearDown() {
        serversToClose.forEach { runCatching { it.close() } }
        serversToClose.clear()
        createdServerIds.forEach { runCatching { CloudRegistry.forget(it) } }
        createdServerIds.clear()
        CloudRegistry.serverLookup = { null }
        DesktopPlayerEngine.resetNetworkSpeed()
        Context.customAppDataRoot = null
        runCatching { tempAppData.deleteRecursively() }
        runCatching { mediaDir.deleteRecursively() }
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
    fun strictSingleConnectionMock115Server_mediaProxyAndVlcSeekWithout403OrFallback() {
        val mkvFile = generateTestMkv23976WithChapters(durationSec = 10)
        val cdn = StrictSingleConn115Server(mkvFile, requiredCookie = "UID=mock115", chunkDelayMs = 3L).also {
            serversToClose += it
        }

        val serverId = "mock115-${UUID.randomUUID()}"
        createdServerIds += serverId
        val server = Server(id = serverId, type = ServerType.PAN115, name = "Mock115", baseUrl = "")
        CloudRegistry.serverLookup = { id -> if (id == serverId) server else null }
        injectMockCloudClient(server) {
            ResolvedLink(
                url = cdn.url("/file.mkv"),
                headers = mapOf("Cookie" to "UID=mock115", "User-Agent" to "Mock115/1.0"),
            )
        }

        val meteredFactory = createCloudHttpFactory(recordBytesToEngine = true)
        DesktopPlayerEngine.updateDisplaySize(1280, 720)
        val player = DesktopExoPlayer(GlobalDesktopApp, meteredFactory)
        try {
            val cloudUri = "cloud://$serverId/ep03?n=${Uri.encode("中头奖还是要上班.S01E03.mkv")}"
            val item = MediaItem.Builder()
                .setMediaId(cloudUri)
                .setUri(cloudUri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle("中头奖还是要上班.S01E03.mkv").build())
                .build()

            var sawBufferingWithSpeed = false
            val tStart = System.currentTimeMillis()
            player.setMediaItems(listOf(item), 0, 4_500L)
            player.prepare()
            player.play()

            val readyDeadline = System.currentTimeMillis() + 12_000L
            while (System.currentTimeMillis() < readyDeadline) {
                if (player.playbackState == Player.STATE_BUFFERING &&
                    DesktopPlayerEngine.currentDownloadSpeedBytesPerSec() > 0L
                ) {
                    sawBufferingWithSpeed = true
                }
                if (player.playbackState == Player.STATE_READY &&
                    DesktopPlayerEngine.currentDrawBitmap != null &&
                    player.currentPosition >= 4_200L
                ) {
                    break
                }
                Thread.sleep(50)
            }

            val startupMs = System.currentTimeMillis() - tStart
            println(
                "Mock 115 startupMs=${startupMs}ms, pos=${player.currentPosition}ms, " +
                    "maxConcurrent=${cdn.maxConcurrentSeen.get()}, 403=${cdn.rejected403Count.get()}, " +
                    "requests=${cdn.totalRequests.get()}, chapters=${player.chapters().size}",
            )

            assertEquals("Player must reach STATE_READY via VLC before 25s watchdog", Player.STATE_READY, player.playbackState)
            assertTrue("Must open via VLC fast (< 12s, was ${startupMs}ms)", startupMs < 12_000L)
            assertFalseIfFfmpegRunning(player)
            assertNotNull("Video frame must be rendered", DesktopPlayerEngine.latestFrameBitmap)
            assertEquals("Strict 1-connection server must reject 0 concurrent collisions (got ${cdn.rejected403Count.get()})", 0, cdn.rejected403Count.get())
            assertTrue("Upstream active connections must stay <= 1 (was ${cdn.maxConcurrentSeen.get()})", cdn.maxConcurrentSeen.get() <= 1)
            assertTrue("Speed tracker should report positive bytes/s during stream download", sawBufferingWithSpeed || DesktopPlayerEngine.currentDownloadSpeedBytesPerSec() > 0L)

            val chapDeadline = System.currentTimeMillis() + 2_000L
            while (System.currentTimeMillis() < chapDeadline && player.chapters().isEmpty()) {
                Thread.sleep(50)
            }
            assertTrue("MKV chapters should load from active VLC stream", player.chapters().size >= 2)

            // 验证拖动进度后依旧不触发 403 并正常继续出帧
            val tickBeforeSeek = DesktopPlayerEngine.frameTick.longValue
            player.seekTo(7_000L)
            val seekDeadline = System.currentTimeMillis() + 6_000L
            while (System.currentTimeMillis() < seekDeadline) {
                if (player.playbackState == Player.STATE_READY &&
                    DesktopPlayerEngine.frameTick.longValue > tickBeforeSeek + 2 &&
                    player.currentPosition >= 6_500L
                ) {
                    break
                }
                Thread.sleep(50)
            }
            assertEquals("Seek must not trigger 403 collision", 0, cdn.rejected403Count.get())
            assertTrue("Position should advance after seek (pos=${player.currentPosition})", player.currentPosition >= 6_500L)
            assertTrue("Frames should keep rendering after seek", DesktopPlayerEngine.frameTick.longValue > tickBeforeSeek)
        } finally {
            player.release()
        }
    }

    @Test
    fun singleProcessFfmpegEngine_plays23976MkvAgainstStrictSingleConnectionServerWithZero403() {
        val mkvFile = generateTestMkv23976WithChapters(durationSec = 8)
        val cdn = StrictSingleConn115Server(mkvFile, requiredCookie = "UID=mock115", chunkDelayMs = 2L).also {
            serversToClose += it
        }

        val serverId = "mock115-${UUID.randomUUID()}"
        createdServerIds += serverId
        val server = Server(id = serverId, type = ServerType.PAN115, name = "Mock115", baseUrl = "")
        CloudRegistry.serverLookup = { id -> if (id == serverId) server else null }
        injectMockCloudClient(server) {
            ResolvedLink(
                url = cdn.url("/fallback.mkv"),
                headers = mapOf("Cookie" to "UID=mock115"),
            )
        }

        val meteredFactory = createCloudHttpFactory(recordBytesToEngine = true)
        val proxy = MediaProxy(meteredFactory)
        val player = DesktopExoPlayer(GlobalDesktopApp, meteredFactory)
        try {
            val cloudUri = Uri.parse("cloud://$serverId/ep03?n=test23976.mkv")
            val proxyUrl = proxy.register(cloudUri, "test23976.mkv")
            DesktopPlayerEngine.activePlayer = player
            player.playWhenReady = true

            val genField = DesktopExoPlayer::class.java.getDeclaredField("mediaGeneration").apply { isAccessible = true }
            val gen = (genField.get(player) as AtomicInteger).incrementAndGet()
            val startMethod = DesktopExoPlayer::class.java.getDeclaredMethod(
                "startWithFfmpeg",
                Int::class.javaPrimitiveType,
                String::class.java,
                Map::class.java,
                Long::class.javaPrimitiveType,
            ).apply { isAccessible = true }

            val tick0 = DesktopPlayerEngine.frameTick.longValue
            startMethod.invoke(player, gen, proxyUrl, emptyMap<String, String>(), 3_500L)

            val deadline = System.currentTimeMillis() + 12_000L
            while (System.currentTimeMillis() < deadline) {
                if (player.playbackState == Player.STATE_READY &&
                    DesktopPlayerEngine.frameTick.longValue > tick0 + 5 &&
                    player.currentPosition >= 3_600L
                ) {
                    break
                }
                Thread.sleep(50)
            }

            assertEquals("FFmpeg fallback must hit STATE_READY", Player.STATE_READY, player.playbackState)
            assertTrue("FFmpeg fallback must render frames", DesktopPlayerEngine.frameTick.longValue > tick0 + 2)
            assertEquals("Single-process FFmpeg must cause zero 403s", 0, cdn.rejected403Count.get())
            assertTrue("Single-process FFmpeg max concurrent upstream <= 1 (was ${cdn.maxConcurrentSeen.get()})", cdn.maxConcurrentSeen.get() <= 1)
            val fps = player.videoFormat?.frameRate ?: 0f
            assertTrue("FFprobe must preserve 23.976fps instead of 25fps fallback (got $fps)", fps in 23.8f..24.1f)
            assertTrue("Playback position must resume around startPosMs=3500 (got ${player.currentPosition})", player.currentPosition >= 3_500L)
        } finally {
            player.release()
            proxy.close()
        }
    }

    @Test
    fun networkSpeedTracker_slidingWindowFormattingAndLoopbackExclusion() {
        DesktopPlayerEngine.resetNetworkSpeed()
        assertEquals(0L, DesktopPlayerEngine.currentDownloadSpeedBytesPerSec(10_000L))
        assertEquals("0 KB/s", DesktopPlayerEngine.formatNetworkSpeed(0L))
        assertEquals("45.2 KB/s", DesktopPlayerEngine.formatNetworkSpeed((45.2 * 1024).toLong()))
        assertEquals("512 KB/s", DesktopPlayerEngine.formatNetworkSpeed(512L * 1024L))
        assertEquals("3.4 MB/s", DesktopPlayerEngine.formatNetworkSpeed((3.4 * 1024 * 1024).toLong()))
        assertEquals("125 MB/s", DesktopPlayerEngine.formatNetworkSpeed(125L * 1024L * 1024L))

        // 连续 15 个 100ms 桶各写入 200 KB => 1.5s 内共 3000 KB => 2000 KB/s
        for (i in 0 until 15) {
            DesktopPlayerEngine.recordNetworkBytes(200L * 1024L, nowMs = 10_000L + i * 100L)
        }
        val measured = DesktopPlayerEngine.currentDownloadSpeedBytesPerSec(nowMs = 11_450L)
        assertTrue("Expected ~2 MB/s (2048000 B/s), got $measured", measured in 1_850_000L..2_250_000L)

        // 超过 2s 无新流量时自动归零
        assertEquals(0L, DesktopPlayerEngine.currentDownloadSpeedBytesPerSec(nowMs = 13_600L))

        // 验证 127.0.0.1 回环读取不会重复计入网速统计
        val smallFile = File(mediaDir, "loopback_probe.bin").apply { writeBytes(ByteArray(128 * 1024) { 0x5A }) }
        val srv = StrictSingleConn115Server(smallFile, requiredCookie = null, chunkDelayMs = 2L).also {
            serversToClose += it
        }
        DesktopPlayerEngine.resetNetworkSpeed()
        val ds = DefaultHttpDataSource.Factory().createDataSource()
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
            "Loopback 127.0.0.1 reads in DefaultHttpDataSource must not pollute network speed",
            0L,
            DesktopPlayerEngine.currentDownloadSpeedBytesPerSec(),
        )

        // 验证 MediaProxy 本机零延迟内存分块缓存命中（128KB > 64KB 边界）可完整读完且不计入外网网速
        val proxy = MediaProxy(androidx.media3.datasource.FileDataSource.Factory())
        try {
            val proxyUrl = proxy.register(Uri.fromFile(smallFile), "loopback_probe.bin")
            repeat(2) { pass ->
                val pds = DefaultHttpDataSource.Factory().createDataSource()
                var proxyRead = 0
                try {
                    pds.open(DataSpec.Builder().setUri(proxyUrl).build())
                    val buf = ByteArray(16_384)
                    while (true) {
                        val n = pds.read(buf, 0, buf.size)
                        if (n == C.RESULT_END_OF_INPUT) break
                        proxyRead += n
                    }
                } finally {
                    pds.close()
                }
                assertEquals("MediaProxy pass $pass must return full 128KB without truncation", smallFile.length().toInt(), proxyRead)
            }
            assertEquals(
                "MediaProxy 127.0.0.1 cache hits must not pollute network speed",
                0L,
                DesktopPlayerEngine.currentDownloadSpeedBytesPerSec(),
            )
        } finally {
            proxy.close()
        }
    }

    private fun assertFalseIfFfmpegRunning(player: DesktopExoPlayer) {
        val f = DesktopExoPlayer::class.java.getDeclaredField("ffmpegRunning").apply { isAccessible = true }
        val running = (f.get(player) as AtomicBoolean).get()
        assertTrue("Expected VLC playback without falling back to FFmpeg", !running)
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

    private fun createCloudHttpFactory(recordBytesToEngine: Boolean): DataSource.Factory {
        val baseHttp = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        return DataSource.Factory {
            val delegate = baseHttp.createDataSource()
            object : BaseDataSource(true) {
                override fun open(dataSpec: DataSpec): Long {
                    val uri = dataSpec.uri.toString()
                    val resolvedSpec = if (CloudRegistry.isCloud(uri)) {
                        val link = CloudRegistry.resolve(uri)
                        dataSpec.withUri(Uri.parse(link.url)).withAdditionalHeaders(link.headers)
                    } else {
                        dataSpec
                    }
                    return delegate.open(resolvedSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val n = delegate.read(buffer, offset, length)
                    if (n > 0 && recordBytesToEngine) {
                        bytesTransferred(n)
                    }
                    return n
                }

                override fun getUri(): Uri? = delegate.getUri()
                override fun getResponseHeaders(): Map<String, List<String>> = delegate.getResponseHeaders()
                override fun close() = delegate.close()
            }
        }
    }

    private fun generateTestMkv23976WithChapters(durationSec: Int): File {
        val ffmpeg = DesktopBinaryFinder.ensureFfmpeg()!!
        val out = File(mediaDir, "test_23976_${durationSec}s_${System.nanoTime()}.mkv")
        val meta = File(mediaDir, "chapters_${System.nanoTime()}.txt")
        meta.writeText(
            """
            ;FFMETADATA1
            [CHAPTER]
            TIMEBASE=1/1000
            START=0
            END=4000
            title=片头
            [CHAPTER]
            TIMEBASE=1/1000
            START=4000
            END=${durationSec * 1000}
            title=正文
            """.trimIndent(),
        )
        val proc = ProcessBuilder(
            ffmpeg, "-y", "-v", "error",
            "-f", "lavfi", "-i", "testsrc=duration=$durationSec:size=1280x720:rate=24000/1001",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=$durationSec",
            "-i", meta.absolutePath,
            "-map", "0:v:0", "-map", "1:a:0", "-map_chapters", "2",
            "-c:v", "libx264", "-preset", "ultrafast", "-g", "24", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-shortest",
            out.absolutePath,
        ).start()
        val code = proc.waitFor()
        meta.delete()
        assertEquals("FFmpeg sample MKV generation must succeed", 0, code)
        assertTrue(out.exists() && out.length() > 4096L)
        return out
    }

    /**
     * 严格模拟 115 CDN 单连接限制的本地测试服务器：
     * - 校验必要 Cookie
     * - 同时活跃请求超过 1 个直接返回 HTTP 403 并累计 rejected403Count
     */
    private class StrictSingleConn115Server(
        private val file: File,
        private val requiredCookie: String?,
        private val chunkDelayMs: Long,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        private val activeCount = AtomicInteger(0)
        val maxConcurrentSeen = AtomicInteger(0)
        val rejected403Count = AtomicInteger(0)
        val totalRequests = AtomicLong(0)
        val port: Int get() = server.localPort

        init {
            thread(name = "mock-115-cdn", isDaemon = true) {
                while (!server.isClosed) {
                    val sock = runCatching { server.accept() }.getOrNull() ?: continue
                    thread(name = "mock-115-conn", isDaemon = true) {
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
            val (method, _) = reqLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            val out = BufferedOutputStream(sock.getOutputStream(), 65_536)

            if (requiredCookie != null && !headers["cookie"].orEmpty().contains(requiredCookie)) {
                rejected403Count.incrementAndGet()
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
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
                runCatching { sock.shutdownOutput() }
            } catch (e: Exception) {
                if (e !is java.net.SocketException && e !is java.io.InterruptedIOException) {
                    System.err.println("StrictSingleConn115Server error: $e")
                }
            } finally {
                activeCount.decrementAndGet()
            }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }
}
