package com.aisub.player

import com.aisub.player.media.CustomMediaLibrary
import com.aisub.player.media.CustomMediaLibraryStore
import com.aisub.player.media.LibraryFolderBinding
import com.aisub.player.media.LocalMediaFilter
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.MediaLibraryType
import com.aisub.player.media.ScrapedEpisode
import com.aisub.player.media.ScrapedMediaItem
import com.aisub.player.meta.RecommendationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFeaturesTest {

    @Test
    fun customMediaLibraryAndCatalogJsonRoundTrip() {
        val lib = CustomMediaLibrary(
            id = "lib-movies-1",
            name = "4K 蓝光电影库",
            type = MediaLibraryType.MOVIE,
            folders = listOf(
                LibraryFolderBinding(
                    serverId = "srv-115",
                    serverName = "115网盘",
                    path = "0/电影/4K",
                    displayPath = "115网盘 / 电影 / 4K",
                ),
            ),
            createdAt = 1700000000000L,
            lastScrapedAt = 1700000500000L,
            itemCount = 1,
        )
        val decodedLib = CustomMediaLibrary.fromJson(lib.toJson())
        assertEquals(lib, decodedLib)

        val item = ScrapedMediaItem(
            id = "tv:1396",
            libraryId = "lib-tv-1",
            kind = "tv",
            title = "绝命毒师",
            originalTitle = "Breaking Bad",
            year = 2008,
            rating = 9.6,
            overview = "高中化学老师制毒传奇",
            posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/w780/backdrop.jpg",
            genres = listOf("剧情", "犯罪"),
            tmdbId = 1396,
            episodes = listOf(
                ScrapedEpisode(
                    season = 1,
                    episode = 1,
                    title = "Pilot",
                    fileName = "Breaking.Bad.S01E01.1080p.mkv",
                    playUrl = "pan115://srv-115/pick123",
                    serverId = "srv-115",
                    sizeBytes = 2_147_483_648L,
                ),
            ),
        )
        val decodedItem = ScrapedMediaItem.fromJson(item.toJson())
        assertEquals("绝命毒师", decodedItem.title)
        assertEquals(1396, decodedItem.tmdbId)
        assertEquals(1, decodedItem.episodes.size)
        assertEquals("pan115://srv-115/pick123", decodedItem.episodes.first().playUrl)
    }

    @Test
    fun normalizeMatchTextStripsPunctuationAndCase() {
        assertEquals("inception2010", CustomMediaLibraryStore.normalizeMatchText("Inception (2010)!"))
        assertEquals("星际穿越interstellar", CustomMediaLibraryStore.normalizeMatchText("《星际穿越》 - Interstellar"))
    }

    @Test
    fun parseAiRecommendationsHandlesThinkTagsAndCodeFences() {
        val raw = """
            <think>
            用户刚看了《星际穿越》，适合推荐硬科幻与时空情感主题作品。
            </think>
            ```json
            [
              {"title": "降临", "originalTitle": "Arrival", "year": 2016, "kind": "movie", "reason": "同为硬核语言学科幻与非线性时间叙事情感杰作"},
              {"title": "暗黑", "originalTitle": "Dark", "year": 2017, "kind": "tv", "reason": "精密闭环的时空穿梭悬疑剧集"}
            ]
            ```
        """.trimIndent()
        val parsed = RecommendationEngine.parseAiRecommendationsJson(raw)
        assertEquals(2, parsed.size)
        assertEquals("降临", parsed[0].title)
        assertEquals("Arrival", parsed[0].originalTitle)
        assertEquals(2016, parsed[0].year)
        assertEquals("movie", parsed[0].kind)
        assertTrue(parsed[0].reason.contains("科幻"))
        assertEquals("tv", parsed[1].kind)
    }

    @Test
    fun localMediaFilterMatchesAndSerializes() {
        val filter = LocalMediaFilter(
            minVideoDurationSec = 60,
            minVideoSizeKb = 10 * 1024L,
            videoExtensions = setOf("mkv", "mp4"),
            excludeVideoJunk = true,
            minAudioDurationSec = 30,
            excludeAudioRingtones = true,
            minImageSizeKb = 100L,
            excludeScreenshots = true,
            excludeCacheImages = true,
        )
        val roundTrip = LocalMediaFilter.fromJson(filter.toJson())
        assertEquals(filter, roundTrip)
        assertEquals(4, roundTrip.videoActiveCount)
        assertEquals(2, roundTrip.audioActiveCount)
        assertEquals(3, roundTrip.imageActiveCount)

        // Video checks
        val goodMovie = MediaEntry("content://v/1", "Movie.mkv", isVideo = true, durationMs = 120_000L, folder = "Movies", sizeBytes = 50L * 1024 * 1024)
        val shortClip = MediaEntry("content://v/2", "Clip.mp4", isVideo = true, durationMs = 12_000L, folder = "Movies", sizeBytes = 20L * 1024 * 1024)
        val screenRec = MediaEntry("content://v/3", "Screenrecord_1.mp4", isVideo = true, durationMs = 180_000L, folder = "DCIM/ScreenRecorder", sizeBytes = 30L * 1024 * 1024)
        assertTrue(roundTrip.matchesVideo(goodMovie))
        assertFalse(roundTrip.matchesVideo(shortClip))
        assertFalse(roundTrip.matchesVideo(screenRec))

        // Audio checks
        val flacSong = MediaEntry("content://a/1", "Track.flac", isVideo = false, durationMs = 210_000L, folder = "Music", sizeBytes = 25L * 1024 * 1024)
        val ringtone = MediaEntry("content://a/2", "Ding.mp3", isVideo = false, durationMs = 45_000L, folder = "Ringtones", sizeBytes = 2L * 1024 * 1024)
        assertTrue(roundTrip.matchesAudio(flacSong))
        assertFalse(roundTrip.matchesAudio(ringtone))

        // Image checks
        val cameraPhoto = MediaEntry("content://i/1", "IMG_2026.JPG", isVideo = false, folder = "DCIM/Camera", sizeBytes = 2L * 1024 * 1024)
        val screenshot = MediaEntry("content://i/2", "Screenshot_2026.png", isVideo = false, folder = "Pictures/Screenshots", sizeBytes = 800L * 1024)
        val tinyIcon = MediaEntry("content://i/3", "icon.png", isVideo = false, folder = "Pictures", sizeBytes = 12L * 1024)
        assertTrue(roundTrip.matchesImage(cameraPhoto))
        assertFalse(roundTrip.matchesImage(screenshot))
        assertFalse(roundTrip.matchesImage(tinyIcon))
    }

    @Test
    fun multiSourceNasAnd115TvGroupingHandlesLargeNasAndChineseFolders() {
        val discovered = ArrayList<CustomMediaLibraryStore.DiscoveredVideo>()
        // 模拟 NAS 目录包含超过 1600 集电视剧（验证不再挤占后续 115 目录扫描与分组）
        for (showIdx in 1..40) {
            for (ep in 1..42) {
                discovered += CustomMediaLibraryStore.DiscoveredVideo(
                    serverId = "srv-nas",
                    bindingIndex = 0,
                    fileName = "NAS剧集$showIdx.S01E%02d.1080p.mkv".format(ep),
                    path = "/downloads/影视/电视剧/NAS剧集$showIdx/S01E%02d.mkv".format(ep),
                    playUrl = "http://nas.local/tv/$showIdx/$ep.mkv",
                    sizeBytes = 1024L,
                    parentFolder = "NAS剧集$showIdx",
                    grandParentFolder = "电视剧",
                    posterSidecar = null,
                )
            }
        }
        // 模拟 115 网盘电视剧目录：1) 剧名文件夹 + S01E01 集名开头；2) 国产剧分类目录 + 两字剧名（如「繁花」「狂飙」）
        discovered += CustomMediaLibraryStore.DiscoveredVideo(
            serverId = "srv-115",
            bindingIndex = 1,
            fileName = "S01E01 初入京都.2160p.WEB-DL.mkv",
            path = "cid_ep1",
            playUrl = "cloud://srv-115/pc1?n=S01E01.mkv",
            sizeBytes = 2048L,
            parentFolder = "庆余年 (2019)",
            grandParentFolder = "电视剧",
            posterSidecar = null,
        )
        discovered += CustomMediaLibraryStore.DiscoveredVideo(
            serverId = "srv-115",
            bindingIndex = 1,
            fileName = "S01E02 再起波澜.2160p.WEB-DL.mkv",
            path = "cid_ep2",
            playUrl = "cloud://srv-115/pc2?n=S01E02.mkv",
            sizeBytes = 2048L,
            parentFolder = "庆余年 (2019)",
            grandParentFolder = "电视剧",
            posterSidecar = null,
        )
        discovered += CustomMediaLibraryStore.DiscoveredVideo(
            serverId = "srv-115",
            bindingIndex = 1,
            fileName = "繁花.S01E01.4K.mkv",
            path = "cid_fh1",
            playUrl = "cloud://srv-115/pc3?n=fh01.mkv",
            sizeBytes = 4096L,
            parentFolder = "国产剧",
            grandParentFolder = "电视剧",
            posterSidecar = null,
        )

        val candidates = CustomMediaLibraryStore.groupTvCandidates(discovered)
        assertEquals(42, candidates.size)
        val qyn = candidates.firstOrNull { it.titles.contains("庆余年") }
        assertTrue("115 庆余年 must be grouped under show folder title", qyn != null)
        assertEquals(2, qyn!!.hits.size)
        assertEquals(2019, qyn.year)

        val fanhua = candidates.firstOrNull { it.titles.contains("繁花") }
        assertTrue("115 2-char show 繁花 under 国产剧 must not be merged into 国产剧", fanhua != null)
        assertEquals(1, fanhua!!.hits.size)
    }

    @Test
    fun decodeModeAndDolbyProfile5TitleHeuristicsAreAccurate() {
        assertEquals(DecodeMode.AUTO, AppSettings().decodeMode)
        assertEquals(null, DecodeMode.AUTO.forceHwdecFlag)
        assertEquals(true, DecodeMode.HW.forceHwdecFlag)
        assertEquals(false, DecodeMode.SW.forceHwdecFlag)
        assertEquals(DecodeMode.HW, DecodeMode.fromForceHwdecFlag(true))
        assertEquals(DecodeMode.SW, DecodeMode.fromForceHwdecFlag(false))
        assertEquals(DecodeMode.AUTO, DecodeMode.fromForceHwdecFlag(null))

        // Generic DV / DoVi filenames (mostly Profile 7/8 hybrids) must NOT lock pure software decode
        assertTrue(com.aisub.player.media.DolbyVisionHelper.looksLikeDolbyVisionTitle("Dune.Part.Two.2024.2160p.WEB-DL.DV.HDR.HEVC.mkv"))
        assertFalse(com.aisub.player.media.DolbyVisionHelper.looksLikeProfile5Title("Dune.Part.Two.2024.2160p.WEB-DL.DV.HDR.HEVC.mkv"))

        // Explicit Profile 5 markers should be recognized accurately
        assertTrue(com.aisub.player.media.DolbyVisionHelper.looksLikeProfile5Title("The.Secret.Agent.2160p.DV.Profile.5.HEVC.mp4"))
        assertTrue(com.aisub.player.media.DolbyVisionHelper.looksLikeProfile5Title("Movie.2026.dvh1.05.06.mp4"))
        assertTrue(com.aisub.player.media.DolbyVisionHelper.looksLikeProfile5Title("Series.S01E01.2160p.DVP5.mp4"))
    }

    @Test
    fun desktopContainerBinarySnifferDetectsMp4AndMkvDolbyWithoutFilenameTags() {
        // 1) MP4 dvcC box (ISO/IEC 14496-15 DOVIDecoderConfigurationRecord, Profile 5:
        //    byte0=1, byte1=0, byte2=(5<<1)|0=0x0A, byte3=(6<<3)|rpu(4)|bl(1)=0x35, byte4=0x00)
        val mp4Header = ByteArray(128)
        // ftyp at 0..15
        mp4Header[3] = 16
        mp4Header[4] = 'f'.code.toByte(); mp4Header[5] = 't'.code.toByte(); mp4Header[6] = 'y'.code.toByte(); mp4Header[7] = 'p'.code.toByte()
        mp4Header[8] = 'i'.code.toByte(); mp4Header[9] = 's'.code.toByte(); mp4Header[10] = 'o'.code.toByte(); mp4Header[11] = 'm'.code.toByte()
        // dvcC box at offset 40: [0,0,0,32, 'd','v','c','C', 1, 0, 0x0A, 0x35, 0x00, ...]
        mp4Header[43] = 32
        mp4Header[44] = 'd'.code.toByte(); mp4Header[45] = 'v'.code.toByte(); mp4Header[46] = 'c'.code.toByte(); mp4Header[47] = 'C'.code.toByte()
        mp4Header[48] = 1
        mp4Header[49] = 0
        mp4Header[50] = 0x0A.toByte()
        mp4Header[51] = 0x35.toByte()
        mp4Header[52] = 0x00.toByte()
        assertEquals(5, com.aisub.player.desktop.DesktopExoPlayer.scanDolbyVisionProfileFromBytes(mp4Header, mp4Header.size))

        // 2a) MKV BlockAdditionMapping Profile 5 (exact real-world layout):
        //     0x41 0xE7 0x84 'd''v''c''C' 0x41 0xED 0x98 [1, 0, 0x0A, 0x35, 0x00]
        val mkvP5Header = ByteArray(96)
        mkvP5Header[10] = 0x41.toByte(); mkvP5Header[11] = 0xE7.toByte(); mkvP5Header[12] = 0x84.toByte()
        mkvP5Header[13] = 'd'.code.toByte(); mkvP5Header[14] = 'v'.code.toByte(); mkvP5Header[15] = 'c'.code.toByte(); mkvP5Header[16] = 'C'.code.toByte()
        mkvP5Header[17] = 0x41.toByte(); mkvP5Header[18] = 0xED.toByte(); mkvP5Header[19] = 0x98.toByte()
        mkvP5Header[20] = 1
        mkvP5Header[21] = 0
        mkvP5Header[22] = 0x0A.toByte()
        mkvP5Header[23] = 0x35.toByte()
        mkvP5Header[24] = 0x00.toByte()
        assertEquals(5, com.aisub.player.desktop.DesktopExoPlayer.scanDolbyVisionProfileFromBytes(mkvP5Header, mkvP5Header.size))

        // 2b) MKV BlockAdditionMapping Profile 8.1: [1, 0, 0x10, 0x35, 0x10] (compat=1)
        val mkvP8Header = ByteArray(96)
        mkvP8Header[10] = 0x41.toByte(); mkvP8Header[11] = 0xE7.toByte(); mkvP8Header[12] = 0x84.toByte()
        mkvP8Header[13] = 'd'.code.toByte(); mkvP8Header[14] = 'v'.code.toByte(); mkvP8Header[15] = 'c'.code.toByte(); mkvP8Header[16] = 'C'.code.toByte()
        mkvP8Header[17] = 0x41.toByte(); mkvP8Header[18] = 0xED.toByte(); mkvP8Header[19] = 0x98.toByte()
        mkvP8Header[20] = 1
        mkvP8Header[21] = 0
        mkvP8Header[22] = 0x10.toByte()
        mkvP8Header[23] = 0x35.toByte()
        mkvP8Header[24] = 0x10.toByte()
        assertEquals(8, com.aisub.player.desktop.DesktopExoPlayer.scanDolbyVisionProfileFromBytes(mkvP8Header, mkvP8Header.size))

        // 3) Normal SDR MP4 header must return null (zero false positives)
        val sdrMp4 = ByteArray(128)
        sdrMp4[3] = 16
        sdrMp4[4] = 'f'.code.toByte(); sdrMp4[5] = 't'.code.toByte(); sdrMp4[6] = 'y'.code.toByte(); sdrMp4[7] = 'p'.code.toByte()
        sdrMp4[8] = 'm'.code.toByte(); sdrMp4[9] = 'p'.code.toByte(); sdrMp4[10] = '4'.code.toByte(); sdrMp4[11] = '2'.code.toByte()
        assertEquals(null, com.aisub.player.desktop.DesktopExoPlayer.scanDolbyVisionProfileFromBytes(sdrMp4, sdrMp4.size))
    }

    @Test
    fun desktopSwitchToMpvKernelPlays10BitHevcSmoothlyWithoutFreeze() {
        val ffmpeg = com.aisub.player.desktop.DesktopBinaryFinder.ensureFfmpeg()!!
        val tempDir = java.nio.file.Files.createTempDirectory("voxtitle-mpv-switch-test").toFile()
        val mkvFile = java.io.File(tempDir, "hevc_10bit_sample.mkv")
        android.content.Context.customAppDataRoot = tempDir
        try {
            val gen = ProcessBuilder(
                ffmpeg, "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=duration=4:size=1280x720:rate=24",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=4",
                "-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le",
                "-c:a", "aac", "-shortest",
                mkvFile.absolutePath,
            ).start()
            assertEquals(0, gen.waitFor())

            com.aisub.player.desktop.DesktopPlayerEngine.updateDisplaySize(1280, 720)
            val dsFactory = androidx.media3.datasource.DefaultDataSource.Factory(android.content.GlobalDesktopApp)
            val exo = com.aisub.player.desktop.DesktopExoPlayer(android.content.GlobalDesktopApp, dsFactory)
            val mpv = com.aisub.player.media.MpvPlayer(android.content.GlobalDesktopApp, dsFactory)
            try {
                val item = androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(mkvFile))
                exo.playWhenReady = true
                exo.setMediaItem(item, 0L)
                exo.prepare()
                val exoDeadline = System.currentTimeMillis() + 6_000L
                while (System.currentTimeMillis() < exoDeadline && exo.playbackState != androidx.media3.common.Player.STATE_READY) {
                    Thread.sleep(50)
                }

                // 模拟播放过程中用户切换至 MPV 内核，从 1_000ms 处无缝接续播放
                exo.stop()
                exo.clearMediaItems()
                com.aisub.player.desktop.DesktopPlayerEngine.activePlayer = null

                val tickBefore = com.aisub.player.desktop.DesktopPlayerEngine.frameTick.longValue
                mpv.playWhenReady = true
                mpv.setMediaItem(item, 1_000L)
                mpv.prepare()
                mpv.play()

                val mpvDeadline = System.currentTimeMillis() + 8_000L
                while (System.currentTimeMillis() < mpvDeadline) {
                    if (mpv.playbackState == androidx.media3.common.Player.STATE_READY &&
                        com.aisub.player.desktop.DesktopPlayerEngine.frameTick.longValue >= tickBefore + 8L &&
                        mpv.currentPosition >= 1_200L
                    ) {
                        break
                    }
                    Thread.sleep(50)
                }
                assertEquals(
                    "MpvPlayer must reach STATE_READY cleanly (err=${mpv.playerError?.message})",
                    androidx.media3.common.Player.STATE_READY,
                    mpv.playbackState,
                )
                assertTrue(
                    "MpvPlayer must render frames without freezing (before=$tickBefore, now=${com.aisub.player.desktop.DesktopPlayerEngine.frameTick.longValue})",
                    com.aisub.player.desktop.DesktopPlayerEngine.frameTick.longValue >= tickBefore + 8L,
                )
                assertTrue(
                    "MpvPlayer position must advance past 1200ms (got ${mpv.currentPosition})",
                    mpv.currentPosition >= 1_200L,
                )
            } finally {
                val releaseStart = System.currentTimeMillis()
                exo.stop()
                exo.release()
                mpv.stop()
                mpv.release()
                val releaseCost = System.currentTimeMillis() - releaseStart
                assertTrue("stop/release must not block calling thread (took ${releaseCost}ms)", releaseCost < 500L)
            }
        } finally {
            android.content.Context.customAppDataRoot = null
            tempDir.deleteRecursively()
        }
    }
}
