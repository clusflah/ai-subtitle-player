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
import com.aisub.player.media.MediaProxy
import com.aisub.player.media.VlcPlayer
import com.aisub.player.subtitle.EmbeddedSubtitles
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
}
