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
    fun legacyDolbyExoMatrixAndRpuRbspParserRestoreColorsAccurately() {
        val snap = com.aisub.player.media.RpuShaderSnapshot.DEFAULT_PROFILE_5
        assertEquals(9, snap.iptToLmsColMajor.size)
        assertEquals(9, snap.lmsToRgb709ColMajor.size)
        assertEquals(0.0f, snap.iptOffset[0], 1e-5f)
        assertEquals(0.5f, snap.iptOffset[1], 1e-5f)
        assertEquals(0.5f, snap.iptOffset[2], 1e-5f)

        // Verify matrix inversion round-trip (M * M^-1 == Identity)
        val forward = floatArrayOf(
            0.3048015f, 0.6310284f, 0.0641775f,
            0.1522829f, 0.7332429f, 0.1144062f,
            0.0263598f, 0.1328402f, 0.8407810f,
        )
        val inv = com.aisub.player.media.RpuShaderSnapshot.invert3x3Row(forward)!!
        val prod = com.aisub.player.media.RpuShaderSnapshot.mul3x3Row(forward, inv)
        assertEquals(1.0f, prod[0], 1e-4f)
        assertEquals(1.0f, prod[4], 1e-4f)
        assertEquals(1.0f, prod[8], 1e-4f)
        assertEquals(0.0f, prod[1], 1e-4f)

        // Construct a minimal valid Dolby Vision Profile 5 UNSPEC62 NALU (with 0x00 0x00 0x03 RBSP emulation prevention)
        // Header: 0x7C 0x01 (NAL type 62), RBSP prefix 0x19 (25), rpu_type=2 (6b), rpu_format=18 (11b),
        // profile=5 (4b), level=0 (4b), seq_info=0 (1b), dm_present=0 (1b), use_prev=0 (1b), rpu_id=0 (ue=1b)...
        val bits = StringBuilder()
        fun putBits(v: Int, n: Int) {
            for (i in n - 1 downTo 0) bits.append(if (((v ushr i) and 1) != 0) '1' else '0')
        }
        fun putUe(v: Int) {
            val code = v + 1
            val len = 32 - Integer.numberOfLeadingZeros(code)
            repeat(len - 1) { bits.append('0') }
            putBits(code, len)
        }
        putBits(25, 8) // rpu_nal_prefix = 25
        putBits(2, 6)  // rpu_type = 2
        putBits(18, 11) // rpu_format
        putBits(5, 4)  // vdr_rpu_profile = 5
        putBits(0, 4)  // vdr_rpu_level = 0
        putBits(0, 1)  // vdr_seq_info_present_flag = 0 (defaults: 10-bit, fixed denom 23)
        putBits(0, 1)  // vdr_dm_metadata_present_flag = 0
        putBits(0, 1)  // use_prev_vdr_rpu_flag = 0
        putUe(0)       // vdr_rpu_id = 0
        putUe(0)       // mapping_color_space
        putUe(0)       // mapping_chroma_format_idc
        repeat(3) {
            putUe(0)           // num_pivots_minus2 = 0 -> 2 pivots
            putBits(0, 10)     // pivot[0] = 0
            putBits(1023, 10)  // pivot[1] = 1023
        }
        putUe(0) // num_x_partitions_minus1
        putUe(0) // num_y_partitions_minus1
        repeat(3) {
            putUe(0)          // mapping_idc = 0 (polynomial)
            putBits(0, 1)     // linear_interp_flag = 0
            putUe(0)          // poly_order_minus1 = 0 (order 1)
            putUe(0)          // coef[0] int = 0 (se=0 -> ue=0)
            putBits(0, 23)    // coef[0] frac = 0
            putUe(1)          // coef[1] int = +1 (se=+1 -> ue=1)
            putBits(0, 23)    // coef[1] frac = 0
        }
        while (bits.length % 8 != 0) bits.append('0')
        val rbsp = ByteArray(bits.length / 8) { idx ->
            bits.substring(idx * 8, idx * 8 + 8).toInt(2).toByte()
        }
        val nalu = byteArrayOf(0x7C.toByte(), 0x01.toByte()) + rbsp
        val scratch = ByteArray(512)
        com.aisub.player.media.DolbyRpuParser.reset()
        val parsed = com.aisub.player.media.DolbyRpuParser.parseNaluToSnapshot(nalu, 0, nalu.size, scratch)
        assertTrue("Valid UNSPEC62 RPU NALU must parse to non-null snapshot", parsed != null)
        assertEquals(0.0f, parsed!!.lumaPoly0[0], 1e-5f)
        assertEquals(1.0f, parsed.lumaPoly0[1], 1e-5f)
    }
}
