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
        // 1) MP4 dvcC box (Profile 5: major=1, minor=0, profile=5 -> 5<<1 = 0x0A, flags=0xA0 rpu=1 bl=1)
        val mp4Header = ByteArray(128)
        // ftyp at 0..15
        mp4Header[3] = 16
        mp4Header[4] = 'f'.code.toByte(); mp4Header[5] = 't'.code.toByte(); mp4Header[6] = 'y'.code.toByte(); mp4Header[7] = 'p'.code.toByte()
        mp4Header[8] = 'i'.code.toByte(); mp4Header[9] = 's'.code.toByte(); mp4Header[10] = 'o'.code.toByte(); mp4Header[11] = 'm'.code.toByte()
        // dvcC box at offset 40: [0,0,0,32, 'd','v','c','C', 1, 0, 0x0A, 0x00, 0xA0, ...]
        mp4Header[43] = 32
        mp4Header[44] = 'd'.code.toByte(); mp4Header[45] = 'v'.code.toByte(); mp4Header[46] = 'c'.code.toByte(); mp4Header[47] = 'C'.code.toByte()
        mp4Header[48] = 1
        mp4Header[49] = 0
        mp4Header[50] = (5 shl 1).toByte()
        mp4Header[52] = 0xA0.toByte()
        assertEquals(5, com.aisub.player.desktop.DesktopExoPlayer.scanDolbyVisionProfileFromBytes(mp4Header, mp4Header.size))

        // 2) MKV BlockAdditionMapping: 0x41 0xE7 0x84 'd''v''c''C' 0x41 0xED 0x98 [major=1, minor=0, profile=8 -> 0x10, 0, 0xA1]
        val mkvHeader = ByteArray(96)
        mkvHeader[10] = 0x41.toByte(); mkvHeader[11] = 0xE7.toByte(); mkvHeader[12] = 0x84.toByte()
        mkvHeader[13] = 'd'.code.toByte(); mkvHeader[14] = 'v'.code.toByte(); mkvHeader[15] = 'c'.code.toByte(); mkvHeader[16] = 'C'.code.toByte()
        mkvHeader[17] = 0x41.toByte(); mkvHeader[18] = 0xED.toByte(); mkvHeader[19] = 0x98.toByte()
        mkvHeader[20] = 1
        mkvHeader[21] = 0
        mkvHeader[22] = (8 shl 1).toByte()
        mkvHeader[24] = 0xA1.toByte()
        assertEquals(8, com.aisub.player.desktop.DesktopExoPlayer.scanDolbyVisionProfileFromBytes(mkvHeader, mkvHeader.size))

        // 3) Normal SDR MP4 header must return null (zero false positives)
        val sdrMp4 = ByteArray(128)
        sdrMp4[3] = 16
        sdrMp4[4] = 'f'.code.toByte(); sdrMp4[5] = 't'.code.toByte(); sdrMp4[6] = 'y'.code.toByte(); sdrMp4[7] = 'p'.code.toByte()
        sdrMp4[8] = 'm'.code.toByte(); sdrMp4[9] = 'p'.code.toByte(); sdrMp4[10] = '4'.code.toByte(); sdrMp4[11] = '2'.code.toByte()
        assertEquals(null, com.aisub.player.desktop.DesktopExoPlayer.scanDolbyVisionProfileFromBytes(sdrMp4, sdrMp4.size))
    }
}
