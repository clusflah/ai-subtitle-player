package com.aisub.player.desktop

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.GlobalDesktopApp
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Size
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.aisub.player.SettingsRepository
import com.aisub.player.asr.AudioDecoder
import com.aisub.player.asr.ExoAudioDecoder
import com.aisub.player.live.LiveSources
import com.aisub.player.media.AppStore
import com.aisub.player.media.FileOps
import com.aisub.player.media.FrameGrabber
import com.aisub.player.media.LocalBrowser
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.MediaLibrary
import com.aisub.player.media.MediaProxy
import com.aisub.player.media.VlcPlayer
import com.aisub.player.music.TrackTags
import com.aisub.player.subtitle.Cue
import com.aisub.player.subtitle.SidecarSrt
import com.aisub.player.telegram.Telegram
import com.aisub.player.telegram.TgAuth
import com.aisub.player.update.AppUpdater
import com.aisub.player.update.appUpdater
import com.k2fsa.sherpa.onnx.VersionInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DesktopIntegrationTest {

    private lateinit var tempHome: File
    private lateinit var tempAppData: File
    private var origUserHome: String? = null

    @Before
    fun setUp() {
        origUserHome = System.getProperty("user.home")
        tempHome = Files.createTempDirectory("voxtitle-test-home").toFile()
        tempAppData = Files.createTempDirectory("voxtitle-test-appdata").toFile()
        System.setProperty("user.home", tempHome.absolutePath)
        Context.customAppDataRoot = tempAppData
    }

    @After
    fun tearDown() {
        origUserHome?.let { System.setProperty("user.home", it) }
        Context.customAppDataRoot = null
        runCatching { tempHome.deleteRecursively() }
        runCatching { tempAppData.deleteRecursively() }
    }

    @Test
    fun test1_SherpaOnnxNativeWindowsJniLoads() {
        val ver = VersionInfo.version
        println("Sherpa-ONNX JNI Version: $ver, gitSha1=${VersionInfo.gitSha1}")
        assertEquals("1.13.8", ver)
    }

    @Test
    fun test2_AppUpdaterPicksWindowsInstaller() {
        val assets = listOf(
            "Voxtitle-2.9.74-arm64-v8a.apk",
            "Voxtitle-2.9.74-x86_64.apk",
            "Voxtitle-2.9.74-universal.apk",
            "Voxtitle-dl-2.9.74-arm64-v8a.apk",
            "Voxtitle-2.9.74-windows-x64-Setup.exe",
            "Voxtitle-2.9.74.msi",
            "Voxtitle-2.9.74-windows-x64-portable.zip",
        )
        // Use reflection to invoke private pickAsset on appUpdater
        val field = AppUpdater::class.java.getDeclaredField("pickAsset")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val picker = field.get(appUpdater) as (List<String>) -> String?
        val picked = picker(assets)
        assertEquals("Voxtitle-2.9.74-windows-x64-Setup.exe", picked)
    }

    @Test
    fun test3_FfmpegMediaLibraryThumbnailsTagsSidecarAndFileOps() {
        val ffmpeg = DesktopBinaryFinder.ensureFfmpeg()
        assertNotNull("FFmpeg must be available", ffmpeg)
        val ffprobe = DesktopBinaryFinder.findFfprobe()
        assertNotNull("FFprobe must be available", ffprobe)

        val videosDir = File(tempHome, "Videos/Anime/Season 1").apply { mkdirs() }
        val musicDir = File(tempHome, "Music/JPop").apply { mkdirs() }

        val mp4File = File(videosDir, "TestShow - S01E01.mp4")
        val mp3File = File(musicDir, "Artist - SongName.mp3")
        val coverPng = File(musicDir, "cover_src.png")

        // Generate a 2-second test video with color bars + sine wave audio
        val genVideo = ProcessBuilder(
            ffmpeg!!, "-y", "-v", "error",
            "-f", "lavfi", "-i", "testsrc=duration=2:size=320x180:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000:duration=2",
            "-c:v", "libx264", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-shortest",
            mp4File.absolutePath,
        ).start()
        assertEquals(0, genVideo.waitFor())
        assertTrue(mp4File.exists() && mp4File.length() > 1000L)

        // Generate a cover image and an MP3 with ID3 tags + embedded cover
        val genCover = ProcessBuilder(
            ffmpeg, "-y", "-v", "error",
            "-f", "lavfi", "-i", "color=c=orange:s=200x200:d=1",
            "-frames:v", "1",
            coverPng.absolutePath,
        ).start()
        assertEquals(0, genCover.waitFor())

        val genMp3 = ProcessBuilder(
            ffmpeg, "-y", "-v", "error",
            "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100:duration=2",
            "-i", coverPng.absolutePath,
            "-map", "0:a", "-map", "1:v",
            "-c:a", "libmp3lame", "-c:v", "mjpeg",
            "-id3v2_version", "3",
            "-metadata", "title=MyCustomTitle",
            "-metadata", "artist=MyCustomArtist",
            "-metadata", "album=MyCustomAlbum",
            mp3File.absolutePath,
        ).start()
        assertEquals(0, genMp3.waitFor())
        coverPng.delete()
        assertTrue(mp3File.exists() && mp3File.length() > 1000L)

        // 1. Scan videos and audios via MediaLibrary
        val ctx = GlobalDesktopApp
        val videos = MediaLibrary.videos(ctx)
        val audios = MediaLibrary.audios(ctx)
        assertEquals(1, videos.size)
        assertEquals(1, audios.size)

        val vEntry = videos.first()
        val aEntry = audios.first()
        println("Scanned video entry: uri=${vEntry.uri}, folder=${vEntry.folder}, title=${vEntry.title}")
        println("Scanned audio entry: uri=${aEntry.uri}, folder=${aEntry.folder}, title=${aEntry.title}")

        assertTrue("Video URI must be content://media/...", vEntry.uri.startsWith("content://media/"))
        assertTrue("FileOps.isLocal must be true", FileOps.isLocal(vEntry))
        assertEquals("Videos/Anime/Season 1", vEntry.folder)
        assertEquals("Music/JPop", aEntry.folder)

        // 2. Folder hierarchy via LocalBrowser
        val (rootFolders, _) = LocalBrowser.children(videos, "")
        assertEquals(1, rootFolders.size)
        assertEquals("Videos", rootFolders.first().name)
        val (subFolders, _) = LocalBrowser.children(videos, "Videos/Anime")
        assertEquals("Season 1", subFolders.first().name)

        // 3. Thumbnails via ContentResolver.loadThumbnail
        val vThumb = ctx.contentResolver.loadThumbnail(Uri.parse(vEntry.uri), Size(320, 180), null)
        assertNotNull("Video thumbnail must not be null", vThumb)
        assertTrue(vThumb!!.width > 0 && vThumb.height > 0)

        val aThumb = ctx.contentResolver.loadThumbnail(Uri.parse(aEntry.uri), Size(200, 200), null)
        assertNotNull("Audio thumbnail (embedded cover) must not be null", aThumb)
        assertTrue(aThumb!!.width > 0 && aThumb.height > 0)

        // 4. FrameGrabber on content://media/... URI
        FrameGrabber(ctx, vEntry.uri).use { grabber ->
            assertTrue("FrameGrabber duration > 1500ms", grabber.durationMs() >= 1500L)
            val frame = grabber.frameAt(500L)
            assertNotNull("FrameGrabber frameAt(500L) must not be null", frame)
        }

        // 5. TrackTags.fromRetriever on content://media/... URI
        val tags = TrackTags.fromRetriever(ctx, Uri.parse(aEntry.uri), aEntry.title)
        assertNotNull("TrackTags must not be null", tags)
        assertEquals("MyCustomTitle", tags!!.title)
        assertEquals("MyCustomArtist", tags.artist)
        assertEquals("MyCustomAlbum", tags.album)
        assertNotNull("Embedded cover must be extracted", tags.cover)

        // 6. SidecarSrt.write on local video
        val cues = listOf(Cue(1, 0L, 1500L, "Hello world", "你好世界"))
        val srtName = SidecarSrt.write(ctx, vEntry, cues)
        assertEquals("TestShow - S01E01.srt", srtName)
        val srtFile = File(videosDir, "TestShow - S01E01.srt")
        assertTrue("Sidecar SRT file must exist", srtFile.exists())
        val srtText = srtFile.readText()
        assertTrue(srtText.contains("Hello world") && srtText.contains("你好世界"))
        // Overwriting our own SidecarSrt should also succeed
        val srtName2 = SidecarSrt.write(ctx, vEntry, listOf(Cue(1, 0L, 1500L, "Updated", "已更新")))
        assertEquals("TestShow - S01E01.srt", srtName2)
        assertTrue(srtFile.readText().contains("已更新"))

        // 7. AudioDecoder & ExoAudioDecoder on content://media/... URI
        var decodedSamples = 0
        AudioDecoder(ctx, Uri.parse(vEntry.uri)).decode(
            targetRate = 16_000,
            startMs = 0L,
            isActive = { true },
            onProgress = {},
            onChunk = { decodedSamples += it.size },
        )
        println("AudioDecoder decoded samples: $decodedSamples")
        assertTrue("AudioDecoder should decode ~32000 samples for 2s video", decodedSamples > 16_000)

        var exoDecodedSamples = 0
        ExoAudioDecoder(ctx, Uri.parse(vEntry.uri), androidx.media3.datasource.DefaultDataSource.Factory(ctx)).decode(
            targetRate = 16_000,
            startMs = 0L,
            isActive = { true },
            onProgress = {},
            onChunk = { exoDecodedSamples += it.size },
        )
        assertTrue("ExoAudioDecoder should decode > 16000 samples", exoDecodedSamples > 16_000)

        // 8. FileOps: details, copy, rename, move, delete
        val details = FileOps.details(ctx, vEntry)
        assertEquals("TestShow - S01E01.mp4", details.name)
        assertTrue(details.sizeBytes > 1000L)

        FileOps.copy(ctx, vEntry, "Videos/Copied")
        assertTrue(File(tempHome, "Videos/Copied/TestShow - S01E01.mp4").exists())

        FileOps.rename(ctx, vEntry, "TestShow - S01E01_Renamed")
        val renamedFile = File(videosDir, "TestShow - S01E01_Renamed.mp4")
        assertTrue("Renamed file must exist", renamedFile.exists())

        val renamedEntry = vEntry.copy(title = renamedFile.name)
        FileOps.move(ctx, renamedEntry, "Videos/Moved")
        val movedFile = File(tempHome, "Videos/Moved/TestShow - S01E01_Renamed.mp4")
        assertTrue("Moved file must exist", movedFile.exists())

        FileOps.delete(ctx, listOf(renamedEntry))
        assertFalse("Deleted file must not exist", movedFile.exists())
    }

    @Test
    fun test4_DesktopExoPlayerAndVlcPlayerPlayback() {
        val ffmpeg = DesktopBinaryFinder.ensureFfmpeg()!!
        val videoFile = File(tempHome, "play_test_1080p.mkv")
        val gen = ProcessBuilder(
            ffmpeg, "-y", "-v", "error",
            "-f", "lavfi", "-i", "testsrc=duration=4:size=1920x1080:rate=30",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=4",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-shortest",
            videoFile.absolutePath,
        ).start()
        assertEquals(0, gen.waitFor())

        val video4kFile = File(tempHome, "play_test_4k.mkv")
        val gen4k = ProcessBuilder(
            ffmpeg, "-y", "-v", "error",
            "-f", "lavfi", "-i", "testsrc=duration=3:size=3840x2160:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=520:sample_rate=48000:duration=3",
            "-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-shortest",
            video4kFile.absolutePath,
        ).start()
        assertEquals(0, gen4k.waitFor())

        DesktopPlayerEngine.updateDisplaySize(1280, 720)
        val player = DesktopExoPlayer(GlobalDesktopApp, androidx.media3.datasource.DefaultDataSource.Factory(GlobalDesktopApp))
        try {
            // 1. 验证调用线程（UI 线程）绝不阻塞
            val callStartMs = System.currentTimeMillis()
            player.setMediaItems(listOf(MediaItem.fromUri(Uri.fromFile(videoFile))), 0, 0L)
            player.prepare()
            player.play()
            val uiCallCostMs = System.currentTimeMillis() - callStartMs
            println("UI thread call cost for setMediaItems+prepare+play: ${uiCallMs(uiCallCostMs)}ms")
            assertTrue("setMediaItems+prepare+play must not block UI thread (< 200ms)", uiCallCostMs < 200L)

            val deadline = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < deadline) {
                if (player.playbackState == Player.STATE_READY && DesktopPlayerEngine.currentDrawBitmap != null) {
                    break
                }
                Thread.sleep(50)
            }
            assertEquals(Player.STATE_READY, player.playbackState)
            assertNotNull("Player should render video frame", DesktopPlayerEngine.latestFrameBitmap)

            // 2. 验证静音 (volume = 0f) 时画面依然持续更新（修复静音卡画面 bug）
            player.volume = 0f
            val tickBeforeMuteWait = DesktopPlayerEngine.frameTick.longValue
            val muteDeadline = System.currentTimeMillis() + 3_000L
            while (System.currentTimeMillis() < muteDeadline && DesktopPlayerEngine.frameTick.longValue <= tickBeforeMuteWait + 3) {
                Thread.sleep(50)
            }
            assertTrue(
                "Frames must keep rendering when volume == 0f (was $tickBeforeMuteWait, now ${DesktopPlayerEngine.frameTick.longValue})",
                DesktopPlayerEngine.frameTick.longValue > tickBeforeMuteWait,
            )
            player.volume = 1f

            // 3. 验证倍速与拖动进度不阻塞 UI 线程
            player.playbackParameters = androidx.media3.common.PlaybackParameters(1.5f)
            val seekStartMs = System.currentTimeMillis()
            player.seekTo(1000L)
            assertTrue("seekTo must not block UI thread", (System.currentTimeMillis() - seekStartMs) < 100L)

            // 4. 验证切换 4K HEVC 视频：片源尺寸为 3840x2160，渲染缓冲按窗口尺寸上限缩放
            val tickBefore4k = DesktopPlayerEngine.frameTick.longValue
            val switchStartMs = System.currentTimeMillis()
            player.setMediaItems(listOf(MediaItem.fromUri(Uri.fromFile(video4kFile))), 0, 0L)
            player.prepare()
            player.play()
            val switchCostMs = System.currentTimeMillis() - switchStartMs
            println("UI thread call cost for switching to 4K video: ${switchCostMs}ms")
            assertTrue("Switching video must not block UI thread (< 200ms)", switchCostMs < 200L)

            val deadline4k = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < deadline4k) {
                if (player.videoSize.width == 3840 && DesktopPlayerEngine.frameTick.longValue > tickBefore4k + 2) {
                    break
                }
                Thread.sleep(50)
            }
            assertEquals(3840, player.videoSize.width)
            assertEquals(2160, player.videoSize.height)
            val drawBmp = DesktopPlayerEngine.currentDrawBitmap
            assertNotNull(drawBmp)
            assertTrue("4K buffer width should be scaled down to <= 1920 (got ${drawBmp!!.width})", drawBmp.width <= 1920)

            player.pause()
            assertFalse(player.playWhenReady)
            val stopStartMs = System.currentTimeMillis()
            player.stop()
            assertTrue("stop() must not block UI thread (< 100ms)", (System.currentTimeMillis() - stopStartMs) < 100L)
        } finally {
            player.release()
        }

        // Also test LibVLC compatibility wrapper (used by VlcPlayer / "兼容模式播放")
        val libVlc = org.videolan.libvlc.LibVLC(GlobalDesktopApp, arrayListOf())
        val compatMp = org.videolan.libvlc.MediaPlayer(libVlc)
        try {
            val media = org.videolan.libvlc.Media(libVlc, Uri.fromFile(videoFile))
            compatMp.media = media
            media.release()
            compatMp.play()
            val deadline2 = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline2 && !compatMp.isPlaying) {
                Thread.sleep(100)
            }
            assertTrue("LibVLC compat MediaPlayer should play", compatMp.isPlaying)
            compatMp.stop()
        } finally {
            compatMp.release()
            libVlc.release()
        }

        assertTrue("Log file should be created and non-empty", android.util.Log.logFile.exists() && android.util.Log.logFile.length() > 0L)
    }

    private fun uiCallMs(ms: Long): Long = ms

    @Test
    fun test5_WindowsTextToSpeechSapi() {
        var initStatus = -1
        val initLatch = CountDownLatch(1)
        val tts = TextToSpeech(GlobalDesktopApp) { status ->
            initStatus = status
            initLatch.countDown()
        }
        try {
            assertTrue(initLatch.await(5, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, initStatus)
            val voices = tts.voices
            println("Windows SAPI voices count: ${voices.size}")
            if (voices.isNotEmpty()) {
                val doneLatch = CountDownLatch(1)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {}
                    override fun onDone(utteranceId: String) {
                        doneLatch.countDown()
                    }
                    override fun onError(utteranceId: String) {
                        doneLatch.countDown()
                    }
                })
                val outWav = File(tempHome, "tts_out.wav")
                tts.synthesizeToFile("你好，测试语音合成。", null, outWav, "u1")
                assertTrue("TTS synthesis should complete within 15s", doneLatch.await(15, TimeUnit.SECONDS))
                assertTrue("Synthesized WAV should exist and be non-empty", outWav.exists() && outWav.length() > 500L)
            }
        } finally {
            tts.shutdown()
        }
    }

    @Test
    fun test6_WasapiLoopbackHelperCompiles() {
        val chunks = ArrayList<FloatArray>()
        val listener: (FloatArray) -> Unit = { pcm ->
            synchronized(chunks) { chunks.add(pcm) }
        }
        WasapiLoopbackCapture.addListener(listener)
        try {
            Thread.sleep(1500)
            assertTrue("WasapiLoopbackCapture should be running", WasapiLoopbackCapture.isCapturing)
        } finally {
            WasapiLoopbackCapture.removeListener(listener)
        }
    }

    @Test
    fun test7_TelegramWithoutDllDoesNotCrash() {
        Telegram.init(GlobalDesktopApp)
        Telegram.setCredentials(12345, "0123456789abcdef0123456789abcdef")
        val state = Telegram.auth.value
        println("Telegram auth state on Windows without tdjni.dll: $state")
        assertTrue(state is TgAuth.Unsupported)
    }

    @Test
    fun test8_RealUserBackupDataLoadsCleanly() {
        val realAppData = File(System.getenv("APPDATA") ?: return, "Voxtitle/prefs")
        if (!realAppData.isDirectory) return
        val testPrefsDir = File(tempAppData, "prefs").apply { mkdirs() }
        realAppData.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".json")) {
                f.copyTo(File(testPrefsDir, f.name), overwrite = true)
            }
        }
        val settings = SettingsRepository(GlobalDesktopApp).load()
        val store = AppStore(GlobalDesktopApp)
        val live = LiveSources(GlobalDesktopApp)
        println(
            "Loaded user backup: provider=${settings.provider}, asr=${settings.asrEngine}, " +
                "servers=${store.servers().size}, history=${store.history().size}, " +
                "liveSources=${live.list().size}",
        )
        assertTrue("User backup has servers", store.servers().isNotEmpty())
        assertTrue("User backup has live sources", live.list().isNotEmpty())
    }
}
