package com.aisub.player.desktop

import android.content.Context
import android.content.GlobalDesktopApp
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.aisub.player.media.MediaProxy
import com.aisub.player.media.VlcPlayer
import com.aisub.player.subtitle.EmbeddedSubtitles
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class DesktopPlaybackDiagnosticTest {

    private lateinit var tempHome: File
    private lateinit var tempAppData: File
    private lateinit var tempWork: File
    private var origUserHome: String? = null

    @Before
    fun setUp() {
        origUserHome = System.getProperty("user.home")
        tempHome = Files.createTempDirectory("voxtitle-diag-home").toFile()
        tempAppData = Files.createTempDirectory("voxtitle-diag-appdata").toFile()
        tempWork = Files.createTempDirectory("voxtitle-diag-media").toFile()
        System.setProperty("user.home", tempHome.absolutePath)
        Context.customAppDataRoot = tempAppData
        EmbeddedSubtitles.resetDemuxCache()
        EmbeddedSubtitles.setDemuxListener(null)
    }

    @After
    fun tearDown() {
        EmbeddedSubtitles.setDemuxListener(null)
        EmbeddedSubtitles.resetDemuxCache()
        origUserHome?.let { System.setProperty("user.home", it) }
        Context.customAppDataRoot = null
        runCatching { tempHome.deleteRecursively() }
        runCatching { tempAppData.deleteRecursively() }
        runCatching { tempWork.deleteRecursively() }
    }

    private fun createSyntheticDualSrtMkv(): File {
        val ffmpeg = DesktopBinaryFinder.ensureFfmpeg()
        assumeTrue("FFmpeg binary must be available for desktop playback diagnostics", ffmpeg != null)

        val forcedSrt = File(tempWork, "forced.srt").apply {
            writeText(
                """
                1
                00:00:04,500 --> 00:00:04,900
                FORCED SIGN

                """.trimIndent(),
                Charsets.UTF_8,
            )
        }
        val mainSrt = File(tempWork, "canadian.srt").apply {
            writeText(
                """
                1
                00:00:00,150 --> 00:00:02,200
                Fernando ain't around?

                2
                00:00:02,300 --> 00:00:04,600
                He's enjoying carnival.

                """.trimIndent(),
                Charsets.UTF_8,
            )
        }
        val outMkv = File(tempWork, "dual_sub_sample.mkv")
        val proc = ProcessBuilder(
            ffmpeg!!,
            "-y",
            "-f", "lavfi", "-i", "testsrc=size=640x360:rate=24",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
            "-i", forcedSrt.absolutePath,
            "-i", mainSrt.absolutePath,
            "-t", "5",
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-map", "2:s:0",
            "-map", "3:s:0",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "96k",
            "-c:s", "srt",
            "-metadata:s:s:0", "language=eng", "-metadata:s:s:0", "title=Forced",
            "-disposition:s:0", "forced",
            "-metadata:s:s:1", "language=eng", "-metadata:s:s:1", "title=Canadian",
            "-disposition:s:1", "0",
            outMkv.absolutePath,
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        assertEquals("FFmpeg sample generation failed ($code): $output", 0, code)
        assertTrue(outMkv.isFile && outMkv.length() > 1024L)
        return outMkv
    }

    @Test
    fun mpvLibplaceboRendersFramesAndLiveSrtSubtitles() {
        val mkv = createSyntheticDualSrtMkv()
        val player = DesktopExoPlayer(
            GlobalDesktopApp,
            DefaultDataSource.Factory(GlobalDesktopApp),
            DesktopBackendMode.MPV_LIBPLACEBO,
        )
        val cuesSeen = CopyOnWriteArrayList<String>()
        val tracksReady = CountDownLatch(1)
        val gotDialogueCue = CountDownLatch(1)
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }) {
                    tracksReady.countDown()
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                val joined = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
                if (joined.isNotBlank()) {
                    cuesSeen += joined
                    if ("Fernando" in joined || "carnival" in joined) {
                        gotDialogueCue.countDown()
                    }
                }
            }
        }
        player.addListener(listener)
        try {
            val baseTicks = DesktopPlayerEngine.frameTick.longValue
            player.setMediaItems(listOf(MediaItem.fromUri(Uri.fromFile(mkv))), 0, 0L)
            player.prepare()
            player.play()

            assertTrue("MPV should emit text track metadata", tracksReady.await(12, TimeUnit.SECONDS))

            val canadianGroup = player.currentTracks.groups.firstOrNull { g ->
                g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { idx ->
                    g.getTrackFormat(idx).label?.contains("Canadian", ignoreCase = true) == true
                }
            }
            assertTrue("Expected 'Canadian' text group in ${player.currentTracks.groups.size} groups", canadianGroup != null)

            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(canadianGroup!!.mediaTrackGroup, 0))
                .build()

            val frameDeadline = System.currentTimeMillis() + 10_000L
            while (System.currentTimeMillis() < frameDeadline && DesktopPlayerEngine.frameTick.longValue <= baseTicks + 5) {
                Thread.sleep(25)
            }
            assertTrue("MPV pipeline should render video frames smoothly", DesktopPlayerEngine.frameTick.longValue > baseTicks + 5)

            assertTrue(
                "Expected live dialogue cue from MPV backend, saw=$cuesSeen, overlay='${DesktopPlayerEngine.currentEmbeddedSubtitleText.value}'",
                gotDialogueCue.await(10, TimeUnit.SECONDS) ||
                    DesktopPlayerEngine.currentEmbeddedSubtitleText.value.contains("Fernando") ||
                    DesktopPlayerEngine.currentEmbeddedSubtitleText.value.contains("carnival"),
            )

            val demuxed = EmbeddedSubtitles.demuxedTracks()
            assertTrue(
                "Expected EmbeddedSubtitles.demuxedTracks to record live SRT cues",
                demuxed.any { t -> t.cues.any { "Fernando" in it.text || "carnival" in it.text } },
            )

            player.setEmbeddedSubtitlesSuppressed(true)
            Thread.sleep(100)
            assertEquals("", DesktopPlayerEngine.currentEmbeddedSubtitleText.value)
        } finally {
            player.removeListener(listener)
            player.release()
        }
    }

    @Test
    fun vlcOnlyExposesAudioAndSubtitleTracksAndSelectsCanadianSub() {
        val mkv = createSyntheticDualSrtMkv()
        val proxy = MediaProxy(DefaultDataSource.Factory(GlobalDesktopApp))
        val vlc = VlcPlayer(GlobalDesktopApp, proxy)
        val tracksReady = CountDownLatch(1)
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (tracks.groups.any { it.type == C.TRACK_TYPE_TEXT } &&
                    tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
                ) {
                    tracksReady.countDown()
                }
            }
        }
        vlc.addListener(listener)
        try {
            vlc.setMediaItem(MediaItem.fromUri(Uri.fromFile(mkv)), 0L)
            vlc.playWhenReady = true
            vlc.prepare()
            vlc.play()

            assertTrue("VLC kernel should emit audio + text tracks", tracksReady.await(15, TimeUnit.SECONDS))
            val textGroups = vlc.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            assertEquals("Expected 2 subtitle tracks on VLC", 2, textGroups.size)

            val forcedGroup = textGroups.firstOrNull { (0 until it.length).any { i -> it.getTrackFormat(i).label?.contains("Forced", true) == true } }
            val canadianGroup = textGroups.firstOrNull { (0 until it.length).any { i -> it.getTrackFormat(i).label?.contains("Canadian", true) == true } }
            assertTrue("Expected both Forced and Canadian groups in VLC tracks", forcedGroup != null && canadianGroup != null)
            assertEquals("en", canadianGroup!!.getTrackFormat(0).language)
            assertTrue(
                "Forced group should preserve SELECTION_FLAG_FORCED",
                (forcedGroup!!.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_FORCED) != 0,
            )

            vlc.trackSelectionParameters = vlc.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(canadianGroup.mediaTrackGroup, 0))
                .build()

            val selDeadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < selDeadline) {
                val curCanadian = vlc.currentTracks.groups.firstOrNull { it.mediaTrackGroup.id == canadianGroup.mediaTrackGroup.id }
                if (curCanadian?.isSelected == true) break
                Thread.sleep(50)
            }
            val selectedNow = vlc.currentTracks.groups.first { it.mediaTrackGroup.id == canadianGroup.mediaTrackGroup.id }.isSelected
            assertTrue("VLC track selection should mark Canadian subtitle track selected", selectedNow)

            vlc.setEmbeddedSubtitlesSuppressed(true)
            Thread.sleep(100)
            assertEquals("", DesktopPlayerEngine.currentEmbeddedSubtitleText.value)
        } finally {
            vlc.removeListener(listener)
            vlc.release()
            proxy.close()
        }
    }

    @Test
    fun recordExternalCueDeliversBurstWithoutDroppingLastCue() {
        val observedCounts = mutableListOf<Int>()
        EmbeddedSubtitles.setDemuxListener { tracks ->
            val track = tracks.firstOrNull { it.id == 7 } ?: return@setDemuxListener
            observedCounts += track.cues.size
        }
        repeat(5) { idx ->
            val start = 1000L + idx * 1000L
            EmbeddedSubtitles.recordExternalCue(
                trackId = 7,
                language = "en",
                label = "Canadian",
                codec = "application/x-subrip",
                startMs = start,
                endMs = start + 800L,
                lines = listOf("Burst cue ${idx + 1}"),
            )
        }
        assertEquals("Every new burst cue must fire listener without dropping trailing cue", listOf(1, 2, 3, 4, 5), observedCounts)
        val finalTrack = EmbeddedSubtitles.demuxedTracks().single { it.id == 7 }
        assertEquals(5, finalTrack.cues.size)
        assertFalse(finalTrack.imageBased)
        assertEquals("Burst cue 5", finalTrack.cues.last().text)
        assertEquals("en", EmbeddedSubtitles.parseLanguageFromLabel("English [Canadian]"))
        assertEquals("zh", EmbeddedSubtitles.parseLanguageFromLabel("简体中文"))
        assertEquals("fr", EmbeddedSubtitles.parseLanguageFromLabel("Français"))
    }

    @Test
    fun libplaceboFilterUsesRgbaDownloadWithoutSwappingWarmOrangePixels() {
        val player = DesktopExoPlayer(GlobalDesktopApp, null)
        try {
            val hdrVf = player.buildColorMappedVfFilter(64, 64, mode = 1, sourceFps = 24.0, isHdrOrDv = true)
            assertTrue("HDR libplacebo filter must download RGBA: $hdrVf", hdrVf.contains("format=rgba,hwdownload,format=rgba"))
            assertTrue("HDR libplacebo filter must apply bt.2390 tonemapping: $hdrVf", hdrVf.contains("tonemapping=bt.2390"))

            val sdrVf = player.buildColorMappedVfFilter(64, 64, mode = 1, sourceFps = 24.0, isHdrOrDv = false)
            assertTrue("SDR libplacebo filter must download RGBA: $sdrVf", sdrVf.contains("format=rgba,hwdownload,format=rgba"))
            assertTrue("SDR libplacebo filter must disable DV mapping: $sdrVf", sdrVf.contains("apply_dolbyvision=false"))
            assertFalse("SDR libplacebo filter must not apply HDR tonemapping: $sdrVf", sdrVf.contains("tonemapping=bt.2390"))

            val ffmpeg = DesktopBinaryFinder.ensureFfmpeg() ?: return
            val proc = ProcessBuilder(
                ffmpeg, "-nostdin", "-v", "error",
                "-init_hw_device", "vulkan=vk", "-filter_hw_device", "vk",
                "-f", "lavfi", "-i", "color=c=#C88264:s=64x64:d=0.04",
                "-frames:v", "1",
                "-vf", sdrVf,
                "-pix_fmt", "bgra",
                "-f", "rawvideo", "pipe:1",
            ).start()
            val bytes = proc.inputStream.readNBytes(64 * 64 * 4)
            val err = proc.errorStream.bufferedReader().readText()
            assertEquals("FFmpeg libplacebo Vulkan test failed: $err", 0, proc.waitFor())
            assumeTrue("Vulkan libplacebo filter requires working Vulkan device on host", bytes.size == 64 * 64 * 4)

            val centerIdx = ((32 * 64) + 32) * 4
            val b = bytes[centerIdx].toInt() and 0xFF
            val g = bytes[centerIdx + 1].toInt() and 0xFF
            val r = bytes[centerIdx + 2].toInt() and 0xFF
            assertTrue("Expected warm orange non-swapped RGB (r > g > b), got R=$r G=$g B=$b", r > g && g > b && (r - b) >= 60)
        } finally {
            player.release()
        }
    }

    @Test
    fun mkv10BitHevcCodecPrivateByte17_0xFA_DetectedWithoutTransferTag() {
        // Construct MKV EBML Header + Tracks (0x1654AE6B) + CodecPrivate (0x63A2) with HEVC 10-bit byte16=0xFD, byte17=0xFA
        val buf = ByteArray(128)
        buf[0] = 0x1A; buf[1] = 0x45; buf[2] = 0xDF.toByte(); buf[3] = 0xA3.toByte()
        buf[16] = 0x16; buf[17] = 0x54; buf[18] = 0xAE.toByte(); buf[19] = 0x6B
        val cpHdr = 24
        buf[cpHdr] = 0x63
        buf[cpHdr + 1] = 0xA2.toByte()
        buf[cpHdr + 2] = 0x40
        buf[cpHdr + 3] = 0x40
        val cp = cpHdr + 4
        buf[cp] = 1 // configurationVersion
        buf[cp + 16] = 0xFD.toByte()
        buf[cp + 17] = 0xFA.toByte() // 5 reserved bits (11111) + bitDepthLumaMinus8 = 2 (10-bit)
        buf[cp + 18] = 0xFA.toByte()
        buf[cp + 22] = 1 // numOfArrays = 1
        // Array: NAL type 39 (PREFIX_SEI_NUT), 1 NALU, payloadType=137 (MasteringDisplay)
        buf[cp + 23] = 39
        buf[cp + 24] = 0; buf[cp + 25] = 1
        buf[cp + 26] = 0; buf[cp + 27] = 5
        buf[cp + 28] = 0x4E; buf[cp + 29] = 0x01; buf[cp + 30] = 137.toByte()

        assertTrue("MKV Tracks must be detected", DesktopExoPlayer.containsMkvTracksElement(buf, buf.size))
        assertTrue("MKV 10-bit HEVC byte17=0xFA must not be misclassified as 8-bit SDR", DesktopExoPlayer.has10BitMkvWithoutTransferTag(buf, buf.size))
        assertTrue("HDR PREFIX_SEI inside 0xFA CodecPrivate must be detected", DesktopExoPlayer.scanHdrOrWideGamutFromBytes(buf, buf.size))
    }

    @Test
    fun concurrentMediaProxyServesParallelHttpRangesAndClearRoutesOnlyPreservesCache() {
        val totalSize = 512 * 1024
        val payload = ByteArray(totalSize) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
        val upstreamHits = AtomicInteger(0)
        val activeConns = AtomicInteger(0)
        val peakConcurrent = AtomicInteger(0)
        val connBarrier = CountDownLatch(2)
        val srv = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val running = AtomicInteger(1)
        val acceptThread = thread(name = "test-concurrent-http", isDaemon = true) {
            while (running.get() == 1 && !srv.isClosed) {
                val sock = runCatching { srv.accept() }.getOrNull() ?: continue
                thread(isDaemon = true) {
                    sock.use { s ->
                        val rawIn = s.getInputStream()
                        fun readHeaderLine(): String? {
                            val buf = java.io.ByteArrayOutputStream()
                            while (true) {
                                val b = rawIn.read()
                                if (b == -1) return if (buf.size() == 0) null else buf.toString(Charsets.ISO_8859_1)
                                if (b == '\n'.code) break
                                if (b != '\r'.code) buf.write(b)
                            }
                            return buf.toString(Charsets.ISO_8859_1)
                        }
                        readHeaderLine() ?: return@use
                        val headers = generateSequence { readHeaderLine()?.takeIf { it.isNotEmpty() } }
                            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                        upstreamHits.incrementAndGet()
                        val cur = activeConns.incrementAndGet()
                        peakConcurrent.updateAndGet { prev -> maxOf(prev, cur) }
                        try {
                            connBarrier.countDown()
                            connBarrier.await(2, TimeUnit.SECONDS)
                            val parts = headers["range"]?.removePrefix("bytes=")?.split('-')
                            val start = parts?.getOrNull(0)?.toIntOrNull() ?: 0
                            val end = (parts?.getOrNull(1)?.toIntOrNull() ?: (totalSize - 1)).coerceAtMost(totalSize - 1)
                            val len = end - start + 1
                            val out = BufferedOutputStream(s.getOutputStream())
                            out.write(
                                (
                                    "HTTP/1.1 206 Partial Content\r\n" +
                                        "Content-Range: bytes $start-$end/$totalSize\r\n" +
                                        "Content-Length: $len\r\n" +
                                        "Accept-Ranges: bytes\r\nConnection: close\r\n\r\n"
                                    ).toByteArray(),
                            )
                            out.write(payload, start, len)
                            out.flush()
                            runCatching { s.shutdownOutput() }
                            runCatching {
                                s.soTimeout = 1000
                                val drain = ByteArray(256)
                                while (rawIn.read(drain) >= 0) {
                                }
                            }
                        } finally {
                            activeConns.decrementAndGet()
                        }
                    }
                }
            }
        }

        val proxy = MediaProxy(DefaultHttpDataSource.Factory())
        try {
            val rawUrl = "http://127.0.0.1:${srv.localPort}/movie.mkv"
            val proxyUrl1 = proxy.register(Uri.parse(rawUrl), "movie.mkv")

            fun fetchRange(urlStr: String, start: Int, end: Int): ByteArray {
                val conn = URL(urlStr).openConnection(Proxy.NO_PROXY) as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("Range", "bytes=$start-$end")
                assertEquals(206, conn.responseCode)
                return conn.inputStream.use { it.readAllBytes() }
            }

            var b1: ByteArray? = null
            var b2: ByteArray? = null
            val t1 = thread { b1 = fetchRange(proxyUrl1, 0, 131_071) }
            val t2 = thread { b2 = fetchRange(proxyUrl1, 262_144, 393_215) }
            t1.join(5000)
            t2.join(5000)
            assertTrue("First concurrent range payload mismatch", payload.copyOfRange(0, 131_072).contentEquals(b1))
            assertTrue("Second concurrent range payload mismatch", payload.copyOfRange(262_144, 393_216).contentEquals(b2))
            assertTrue("HTTP upstream should handle concurrent range reads (peak=${peakConcurrent.get()})", peakConcurrent.get() >= 2)

            val hitsBeforeCacheReuse = upstreamHits.get()
            proxy.clearRoutesOnly()
            val proxyUrl2 = proxy.register(Uri.parse(rawUrl), "movie.mkv")
            val cached = fetchRange(proxyUrl2, 0, 65_535)
            assertTrue("Cached block after clearRoutesOnly must match", payload.copyOfRange(0, 65_536).contentEquals(cached))
            assertEquals("clearRoutesOnly must preserve cached RAM blocks without hitting upstream", hitsBeforeCacheReuse, upstreamHits.get())
        } finally {
            running.set(0)
            runCatching { srv.close() }
            proxy.close()
            acceptThread.join(1000)
        }
    }
}
