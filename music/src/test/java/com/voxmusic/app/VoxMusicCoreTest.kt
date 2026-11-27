package com.voxmusic.app

import com.voxmusic.app.media.MediaEntry
import com.voxmusic.app.media.MusicAppStore
import com.voxmusic.app.media.SavedPlaybackSession
import com.voxmusic.app.media.TrackMetadataRegistry
import com.voxmusic.app.media.WebStreamingMusicPlayer
import com.voxmusic.app.meta.DiscoverItem
import com.voxmusic.app.meta.MusicDiscoverCharts
import com.voxmusic.app.meta.MusicDiscoverRepository
import com.voxmusic.app.meta.MusicPlatformCatalog
import com.voxmusic.app.meta.PlatformSource
import com.voxmusic.app.music.Lyrics
import com.voxmusic.app.music.SmartMusicSourceResolver
import com.voxmusic.app.music.WebMusicService
import com.voxmusic.app.music.WebStreamingMusic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxMusicCoreTest {

    @Test
    fun supportedSourcesIncludeAppleSpotifyAndYouTubeMusic() {
        assertEquals(
            listOf(
                WebMusicService.APPLE_MUSIC,
                WebMusicService.SPOTIFY,
                WebMusicService.YOUTUBE_MUSIC,
            ),
            WebMusicService.entries,
        )
    }

    @Test
    fun smartSourceResolverSelectsLoggedInServiceWithoutPrompt() {
        // 均未登录 -> null
        assertNull(
            SmartMusicSourceResolver.resolve(
                loggedIn = emptySet(),
                activeService = WebMusicService.APPLE_MUSIC,
            ),
        )
        // 仅登录 Spotify（即便默认偏好是 Apple Music）-> 自动选 Spotify
        assertEquals(
            WebMusicService.SPOTIFY,
            SmartMusicSourceResolver.resolve(
                loggedIn = setOf(WebMusicService.SPOTIFY),
                activeService = WebMusicService.APPLE_MUSIC,
            ),
        )
        // 仅登录 Apple Music（即便默认偏好是 Spotify）-> 自动选 Apple Music
        assertEquals(
            WebMusicService.APPLE_MUSIC,
            SmartMusicSourceResolver.resolve(
                loggedIn = setOf(WebMusicService.APPLE_MUSIC),
                activeService = WebMusicService.SPOTIFY,
            ),
        )
        // 两个都已登录 -> 遵循当前活跃源
        assertEquals(
            WebMusicService.SPOTIFY,
            SmartMusicSourceResolver.resolve(
                loggedIn = setOf(WebMusicService.APPLE_MUSIC, WebMusicService.SPOTIFY),
                activeService = WebMusicService.SPOTIFY,
            ),
        )
        assertEquals(
            WebMusicService.APPLE_MUSIC,
            SmartMusicSourceResolver.resolve(
                loggedIn = setOf(WebMusicService.APPLE_MUSIC, WebMusicService.SPOTIFY),
                activeService = WebMusicService.APPLE_MUSIC,
            ),
        )
        // 仅登录 YouTube Music -> 自动选 YouTube Music
        assertEquals(
            WebMusicService.YOUTUBE_MUSIC,
            SmartMusicSourceResolver.resolve(
                loggedIn = setOf(WebMusicService.YOUTUBE_MUSIC),
                activeService = WebMusicService.APPLE_MUSIC,
            ),
        )
        // 三个均已登录且当前激活 YouTube Music -> 优先 YouTube Music
        assertEquals(
            WebMusicService.YOUTUBE_MUSIC,
            SmartMusicSourceResolver.resolve(
                loggedIn = setOf(WebMusicService.APPLE_MUSIC, WebMusicService.SPOTIFY, WebMusicService.YOUTUBE_MUSIC),
                activeService = WebMusicService.YOUTUBE_MUSIC,
            ),
        )
    }

    @Test
    fun buildQueueFromDiscoverItemsPreservesIndexAndMetadata() {
        val items = listOf(
            DiscoverItem(
                id = "apple:10001",
                title = "晴天",
                artist = "周杰伦",
                album = "叶惠美",
                durationMs = 269_000L,
                posterUrl = "https://example.com/1.jpg",
                appleUrl = "https://music.apple.com/cn/song/10001",
            ),
            DiscoverItem(
                id = "spotify:abc123XYZ",
                title = "Blinding Lights",
                artist = "The Weeknd",
                album = "After Hours",
                durationMs = 200_000L,
                posterUrl = "https://example.com/2.jpg",
                platforms = listOf(PlatformSource("Spotify", "https://open.spotify.com/track/abc123XYZ")),
            ),
        )

        val (amQueue, amIdx) = WebStreamingMusic.buildQueueFromDiscoverItems(
            service = WebMusicService.APPLE_MUSIC,
            currentItem = items[0],
            queueItems = items,
            preferredIndex = 0,
        )
        assertEquals(2, amQueue.size)
        assertEquals(0, amIdx)
        assertTrue(amQueue[0].uri.startsWith("applemusic://track/10001"))
        assertEquals("周杰伦", TrackMetadataRegistry.find(amQueue[0].uri)?.artist)

        val (spQueue, spIdx) = WebStreamingMusic.buildQueueFromDiscoverItems(
            service = WebMusicService.SPOTIFY,
            currentItem = items[1],
            queueItems = items,
            preferredIndex = 1,
        )
        assertEquals(2, spQueue.size)
        assertEquals(1, spIdx)
        assertTrue(spQueue[1].uri.startsWith("spotify://track/abc123XYZ"))
        assertEquals("The Weeknd", TrackMetadataRegistry.find(spQueue[1].uri)?.artist)
    }

    @Test
    fun lyricsParsingAndSyncLookupWork() {
        val raw = """
            [00:01.00]First line
            [00:05.50]Second line
            [00:12.00]Third line
        """.trimIndent()
        val lrc = Lyrics.parse(raw, "test")
        assertNotNull(lrc)
        assertTrue(lrc!!.synced)
        assertEquals(3, lrc.lines.size)
        assertEquals(-1, lrc.indexAt(500L))
        assertEquals(0, lrc.indexAt(1_000L))
        assertEquals(1, lrc.indexAt(7_000L))
        assertEquals(2, lrc.indexAt(15_000L))
    }

    @Test
    fun appleMusicChartsDefaultToTaiwanRegion() {
        assertEquals("tw", MusicDiscoverRepository.APPLE_DEFAULT_COUNTRY)
        val generalChartIds = listOf(
            "music_hot",
            "music_new_express",
            "music_soar",
            "music_new_chart",
            "music_apple_tw",
            "music_original",
        )
        for (id in generalChartIds) {
            val chart = MusicDiscoverCharts.findChart(id)
            assertNotNull("Chart $id should exist", chart)
            assertEquals("Chart $id should use Taiwan Apple Music storefront", "tw", chart!!.fallbackAppleCountry)
        }
        assertEquals("tw", MusicDiscoverCharts.findChart("music_apple_tw")!!.remoteId)
        assertTrue(
            MusicPlatformCatalog.resolveSearchUrl("Apple Music", "晴天")
                .startsWith("https://music.apple.com/tw/search?term="),
        )
    }

    @Test
    fun savedPlaybackSessionRoundTripsQueueProgressAndMetadata() {
        val uri1 = "applemusic://track/tw_1001"
        val uri2 = "spotify://track/sp_2002"
        TrackMetadataRegistry.register(
            uri1,
            TrackMetadataRegistry.Entry(
                sourceId = "web:APPLE_MUSIC",
                ratingKey = "tw_1001",
                durationMs = 240_000L,
                title = "七里香",
                artist = "周杰伦",
                album = "七里香",
                coverUrl = "https://example.com/qilixiang.jpg",
            ),
        )
        val queue = listOf(
            MediaEntry(uri1, "七里香.mp3", 240_000L, "周杰伦 - 七里香", "https://example.com/qilixiang.jpg"),
            MediaEntry(uri2, "Blinding Lights.mp3", 200_000L, "The Weeknd - After Hours", "https://example.com/bl.jpg"),
        )
        val sessionJson = MusicAppStore.encodePlaybackSessionJson(
            SavedPlaybackSession(queue = queue, index = 0, positionMs = 45_000L, savedAt = 1_000L),
        )
        val newerProgressJson = MusicAppStore.encodePlaybackProgressJson(
            uri = uri2,
            index = 1,
            positionMs = 112_500L,
            durationMs = 200_000L,
            savedAt = 2_000L,
        )

        val restored = MusicAppStore.decodePlaybackSessionJson(sessionJson, newerProgressJson)
        assertNotNull(restored)
        assertEquals(2, restored!!.queue.size)
        assertEquals(1, restored.index)
        assertEquals(112_500L, restored.positionMs)
        assertEquals(uri2, restored.queue[restored.index].uri)
        assertEquals("周杰伦", TrackMetadataRegistry.find(uri1)?.artist)

        // 接近播完的进度自动归零，避免重开直接卡在结尾
        assertEquals(0L, MusicAppStore.sanitizeResumePosition(199_000L, 200_000L))
        assertEquals(95_000L, MusicAppStore.sanitizeResumePosition(95_000L, 200_000L))

        // 超长队列裁剪时以当前播放曲目为中心保留窗口
        val largeQueue = List(250) { i -> MediaEntry("applemusic://track/$i", "Song $i") }
        val (trimmed, trimmedIdx) = MusicAppStore.trimQueueAroundIndex(largeQueue, 210, maxItems = 200)
        assertEquals(200, trimmed.size)
        assertEquals("applemusic://track/210", trimmed[trimmedIdx].uri)

        // 播放器被清空/释放后 SimpleBasePlayer.currentMediaItemIndex 会回退为 0，resolveQueueIndex 必须仍命中真实曲目下标
        val sampleQueue = List(6) { i -> MediaEntry("spotify://search?title=Song$i", "Song$i.mp3") }
        assertEquals(
            4,
            MusicViewModel.resolveQueueIndex(
                queue = sampleQueue,
                currentUri = "spotify://search?title=Song4",
                playerMediaItemCount = 0,
                playerIndex = 0,
            ),
        )
        assertEquals(
            4,
            MusicViewModel.resolveQueueIndex(
                queue = sampleQueue,
                currentUri = "spotify://search?title=Song4",
                playerMediaItemCount = 6,
                playerIndex = 4,
            ),
        )
    }

    @Test
    fun normalizeAppleStorefrontValidatesTwoLetterCodes() {
        assertEquals("tr", WebStreamingMusic.normalizeAppleStorefront("TR"))
        assertEquals("tw", WebStreamingMusic.normalizeAppleStorefront(" tw "))
        assertNull(WebStreamingMusic.normalizeAppleStorefront("library"))
        assertNull(WebStreamingMusic.normalizeAppleStorefront(""))
        assertNull(WebStreamingMusic.normalizeAppleStorefront(null))
    }

    @Test
    fun externalPauseAndResumeSyncPlayWhenReadyCorrectly() {
        // 底层已暂停且超出起播保护期 -> 同步为暂停（false）
        assertEquals(
            false,
            WebStreamingMusicPlayer.resolveExternalPlayWhenReady(
                currentPlayWhenReady = true,
                hasStartedAudio = true,
                snapPlaying = false,
                snapBuffering = false,
                snapEnded = false,
                inSeekHold = false,
                sinceUserPlayMs = 2_000L,
                sinceUserPauseMs = 10_000L,
            ),
        )
        // 用户刚点播放的保护期内不误判为外部暂停
        assertEquals(
            true,
            WebStreamingMusicPlayer.resolveExternalPlayWhenReady(
                currentPlayWhenReady = true,
                hasStartedAudio = true,
                snapPlaying = false,
                snapBuffering = false,
                snapEnded = false,
                inSeekHold = false,
                sinceUserPlayMs = 300L,
                sinceUserPauseMs = 10_000L,
            ),
        )
        // 外部蓝牙/客户端恢复播放 -> 同步为播放（true）
        assertEquals(
            true,
            WebStreamingMusicPlayer.resolveExternalPlayWhenReady(
                currentPlayWhenReady = false,
                hasStartedAudio = true,
                snapPlaying = true,
                snapBuffering = false,
                snapEnded = false,
                inSeekHold = false,
                sinceUserPlayMs = 10_000L,
                sinceUserPauseMs = 2_000L,
            ),
        )
    }

    @Test
    fun youtubeMusicApiAuthHashItemParsingAndBestAudioFormatWork() {
        val hash = com.voxmusic.app.music.YouTubeMusicApi.buildSapisidHash(
            cookies = "SID=abc; SAPISID=test_sapisid_123; HSID=xyz",
            origin = "https://music.youtube.com",
            nowSec = 1_700_000_000L,
        )
        assertNotNull(hash)
        assertTrue(hash!!.startsWith("SAPISIDHASH 1700000000_"))

        val playerJson = org.json.JSONObject(
            """
            {
              "playabilityStatus": { "status": "OK" },
              "videoDetails": { "lengthSeconds": "215" },
              "streamingData": {
                "adaptiveFormats": [
                  { "itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 129000, "url": "https://rr1.googlevideo.com/140" },
                  { "itag": 251, "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000, "url": "https://rr1.googlevideo.com/251" },
                  { "itag": 774, "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 256000, "url": "https://rr1.googlevideo.com/774" }
                ]
              }
            }
            """.trimIndent(),
        )
        val best = com.voxmusic.app.music.YouTubeMusicApi.parseBestYouTubeAudioFormat(playerJson)
        assertNotNull(best)
        assertEquals("https://rr1.googlevideo.com/774", best!!.url)
        assertEquals("256k Opus", best.qualityLabel)
        assertEquals(215_000L, best.durationMs)

        val ytDiscover = DiscoverItem(
            id = "yt:dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            platforms = listOf(PlatformSource("YouTube Music", "https://music.youtube.com/watch?v=dQw4w9WgXcQ")),
        )
        val (ytQueue, ytIdx) = WebStreamingMusic.buildQueueFromDiscoverItems(
            service = WebMusicService.YOUTUBE_MUSIC,
            currentItem = ytDiscover,
            queueItems = listOf(ytDiscover),
            preferredIndex = 0,
        )
        assertEquals(0, ytIdx)
        assertTrue(ytQueue[0].uri.startsWith("ytmusic://track/dQw4w9WgXcQ"))
        assertEquals(WebMusicService.YOUTUBE_MUSIC, WebMusicService.fromUri(ytQueue[0].uri))
    }

    @Test
    fun crossSourceFallbackOrderCoversRemainingLoggedInServices() {
        val allThree = setOf(
            WebMusicService.APPLE_MUSIC,
            WebMusicService.SPOTIFY,
            WebMusicService.YOUTUBE_MUSIC,
        )
        // 首选 Spotify 失败 -> 先兜底 Apple Music，再兜底 YouTube Music
        assertEquals(
            listOf(WebMusicService.APPLE_MUSIC, WebMusicService.YOUTUBE_MUSIC),
            SmartMusicSourceResolver.fallbackOrder(WebMusicService.SPOTIFY, allThree),
        )
        // 首选 Apple Music 失败 -> 先兜底 Spotify，再兜底 YouTube Music
        assertEquals(
            listOf(WebMusicService.SPOTIFY, WebMusicService.YOUTUBE_MUSIC),
            SmartMusicSourceResolver.fallbackOrder(WebMusicService.APPLE_MUSIC, allThree),
        )
        // 首选 YouTube Music 失败 -> 未启用 Spotify Remote 时按 Apple Music > Spotify；启用时 Spotify 优先
        assertEquals(
            listOf(WebMusicService.APPLE_MUSIC, WebMusicService.SPOTIFY),
            SmartMusicSourceResolver.fallbackOrder(WebMusicService.YOUTUBE_MUSIC, allThree, spotifyRemoteReady = false),
        )
        assertEquals(
            listOf(WebMusicService.SPOTIFY, WebMusicService.APPLE_MUSIC),
            SmartMusicSourceResolver.fallbackOrder(WebMusicService.YOUTUBE_MUSIC, allThree, spotifyRemoteReady = true),
        )
        // 仅登录 1 个音源时无兜底候选
        assertEquals(
            emptyList<WebMusicService>(),
            SmartMusicSourceResolver.fallbackOrder(WebMusicService.SPOTIFY, setOf(WebMusicService.SPOTIFY)),
        )
    }

    @Test
    fun discoverSearchMergesLoggedInStreamingAndRoutesExclusiveItemsDirectly() {
        val appleLoose = listOf(
            DiscoverItem(
                id = "apple:1",
                title = "Completely Different Song",
                artist = "Other Artist",
                appleUrl = "https://music.apple.com/tw/song/1",
            ),
            DiscoverItem(
                id = "apple:2",
                title = "晴天",
                artist = "周杰伦",
                appleUrl = "https://music.apple.com/tw/song/2",
            ),
        )
        val spotifyHits = listOf(
            DiscoverItem(
                id = "spotify:spQingtian",
                title = "晴天",
                artist = "周杰伦",
                platforms = listOf(PlatformSource("Spotify", "https://open.spotify.com/track/spQingtian")),
            ),
        )
        val ytHits = listOf(
            DiscoverItem(
                id = "yt:ytExclusive99",
                title = "神秘翻唱Live",
                artist = "独立音乐人",
                platforms = listOf(PlatformSource("YouTube Music", "https://music.youtube.com/watch?v=ytExclusive99")),
            ),
        )

        // 搜索 YouTube Music 独占歌曲时：即使 Apple Music 排在前面，精准命中的 YT 曲目排到首位且保留完整直达 ID
        val mergedYt = WebStreamingMusic.mergeDiscoverSearchResults(
            query = "神秘翻唱Live",
            catalog = appleLoose,
            streamingGroups = listOf(spotifyHits, ytHits),
        )
        assertEquals("yt:ytExclusive99", mergedYt.first().id)

        // 苹果与 Spotify 均有的「晴天」自动去重合并并保留双平台链接
        val qingtian = mergedYt.first { it.title == "晴天" }
        assertEquals("https://music.apple.com/tw/song/2", qingtian.appleUrl)
        assertTrue(qingtian.platforms.any { it.name == "Spotify" })

        val allThree = setOf(
            WebMusicService.APPLE_MUSIC,
            WebMusicService.SPOTIFY,
            WebMusicService.YOUTUBE_MUSIC,
        )
        // 多源共有歌曲 -> 仍遵循当前激活的 Spotify
        assertEquals(
            WebMusicService.SPOTIFY,
            SmartMusicSourceResolver.resolveForItem(qingtian, allThree, activeService = WebMusicService.SPOTIFY),
        )
        // 仅在 YouTube Music 搜到的独占曲目 -> 直接由 YouTube Music 播放，避免在 Spotify 空搜
        assertEquals(
            WebMusicService.YOUTUBE_MUSIC,
            SmartMusicSourceResolver.resolveForItem(mergedYt.first(), allThree, activeService = WebMusicService.SPOTIFY),
        )
    }

    @Test
    fun appleDocumentStartScriptContainsWidevinePersistentStateFallback() {
        assertTrue(WebStreamingMusic.DOCUMENT_START_SCRIPT.contains("requestMediaKeySystemAccess"))
        assertTrue(WebStreamingMusic.DOCUMENT_START_SCRIPT.contains("persistentState"))
        assertTrue(WebStreamingMusic.DOCUMENT_START_SCRIPT.toByteArray(Charsets.UTF_8).size < 65_000)
    }

    @Test
    fun resolveDisplayCoverNeverLeaksFirstSongStandbyCoverDuringPlayback() {
        val standby = "first_song_in_list_cover"
        val clickedCover = "clicked_song_cover"
        // 纯待机未点播任何歌曲时：允许展示待机封面
        assertEquals(
            standby,
            com.voxmusic.app.ui.resolveDisplayCover(
                activeMusic = false,
                hasCurrentEntry = false,
                activeCover = null,
                standbyCover = standby,
            ),
        )
        // 点击任意歌曲刚开播、自身封面尚在加载（activeCover == null）时：绝不回退到列表第 1 首歌的待机封面
        assertEquals(
            null,
            com.voxmusic.app.ui.resolveDisplayCover(
                activeMusic = true,
                hasCurrentEntry = true,
                activeCover = null,
                standbyCover = standby,
            ),
        )
        assertEquals(
            null,
            com.voxmusic.app.ui.resolveDisplayCover(
                activeMusic = false,
                hasCurrentEntry = true,
                activeCover = null,
                standbyCover = standby,
            ),
        )
        // 当前歌曲封面加载完成后：正常展示当前歌曲封面
        assertEquals(
            clickedCover,
            com.voxmusic.app.ui.resolveDisplayCover(
                activeMusic = true,
                hasCurrentEntry = true,
                activeCover = clickedCover,
                standbyCover = standby,
            ),
        )
    }

    @Test
    fun detectNaturalTrackEndCatchesEofPauseAndWebAutoplayHijack() {
        val first = WebStreamingMusicPlayer.normalizeTrackTitle("因為你 所以我")
        val second = WebStreamingMusicPlayer.normalizeTrackTitle("突然好想你")
        val officialMv = WebStreamingMusicPlayer.normalizeTrackTitle("因為你 所以我 (Official MV)")

        // 曲末短暂 !playing 且未发 ended 时直接判定自然播完
        assertTrue(
            WebStreamingMusicPlayer.detectNaturalTrackEnd(
                snapEnded = false,
                snapPlaying = false,
                snapPositionMs = 285_600L,
                effectiveDurationMs = 286_000L,
                maxPosReachedMs = 285_600L,
                confirmedTitleNorm = first,
                expectedTitleNorm = first,
                incomingTitleNorm = first,
                inSeekHold = false,
                sinceUserSeekMs = 30_000L,
            ),
        )

        // YouTube Music / Spotify 网页端私自连播切到下一首：歌名变化触发交回应用队列切歌
        assertTrue(
            WebStreamingMusicPlayer.detectNaturalTrackEnd(
                snapEnded = false,
                snapPlaying = true,
                snapPositionMs = 12_000L,
                effectiveDurationMs = 858_000L,
                maxPosReachedMs = 285_000L,
                confirmedTitleNorm = first,
                expectedTitleNorm = first,
                incomingTitleNorm = second,
                inSeekHold = false,
                sinceUserSeekMs = 30_000L,
            ),
        )

        // 同一首歌仅带版本后缀时绝不误判
        assertEquals(
            false,
            WebStreamingMusicPlayer.detectNaturalTrackEnd(
                snapEnded = false,
                snapPlaying = true,
                snapPositionMs = 45_000L,
                effectiveDurationMs = 286_000L,
                maxPosReachedMs = 45_000L,
                confirmedTitleNorm = first,
                expectedTitleNorm = first,
                incomingTitleNorm = officialMv,
                inSeekHold = false,
                sinceUserSeekMs = 30_000L,
            ),
        )
    }

    @Test
    fun autoLyricsOnlySchedulesWhenPlayingWithSyncedLyricsAndNotSuppressed() {
        // 正常条件：歌曲页、手机/平板端、正在播放、有同步歌词、当前为黑胶、本首未锁定、无弹层/拖动 -> 允许自动切歌词
        assertTrue(
            com.voxmusic.app.ui.shouldScheduleAutoLyrics(
                onBrowsePage = false,
                isTv = false,
                activeMusic = true,
                isPlaying = true,
                hasSyncedLyrics = true,
                showLyrics = false,
                suppressedForTrack = false,
                hasOverlayOrDragging = false,
            ),
        )
        // 手动切回唱片后（本首锁定）-> 不再自动跳走
        assertEquals(
            false,
            com.voxmusic.app.ui.shouldScheduleAutoLyrics(
                onBrowsePage = false,
                isTv = false,
                activeMusic = true,
                isPlaying = true,
                hasSyncedLyrics = true,
                showLyrics = false,
                suppressedForTrack = true,
                hasOverlayOrDragging = false,
            ),
        )
        // 暂停或纯音乐无同步歌词 -> 不自动切换
        assertEquals(
            false,
            com.voxmusic.app.ui.shouldScheduleAutoLyrics(
                onBrowsePage = false,
                isTv = false,
                activeMusic = true,
                isPlaying = false,
                hasSyncedLyrics = true,
                showLyrics = false,
                suppressedForTrack = false,
                hasOverlayOrDragging = false,
            ),
        )
        assertEquals(
            false,
            com.voxmusic.app.ui.shouldScheduleAutoLyrics(
                onBrowsePage = false,
                isTv = false,
                activeMusic = true,
                isPlaying = true,
                hasSyncedLyrics = false,
                showLyrics = false,
                suppressedForTrack = false,
                hasOverlayOrDragging = false,
            ),
        )
        // 浏览发现/乐库、TV 遥控器、已在歌词态、弹层打开或拖动进度条 -> 不触发自动切换
        assertEquals(
            false,
            com.voxmusic.app.ui.shouldScheduleAutoLyrics(
                onBrowsePage = true,
                isTv = false,
                activeMusic = true,
                isPlaying = true,
                hasSyncedLyrics = true,
                showLyrics = false,
                suppressedForTrack = false,
                hasOverlayOrDragging = false,
            ),
        )
        assertEquals(
            false,
            com.voxmusic.app.ui.shouldScheduleAutoLyrics(
                onBrowsePage = false,
                isTv = true,
                activeMusic = true,
                isPlaying = true,
                hasSyncedLyrics = true,
                showLyrics = false,
                suppressedForTrack = false,
                hasOverlayOrDragging = false,
            ),
        )
        assertEquals(
            false,
            com.voxmusic.app.ui.shouldScheduleAutoLyrics(
                onBrowsePage = false,
                isTv = false,
                activeMusic = true,
                isPlaying = true,
                hasSyncedLyrics = true,
                showLyrics = true,
                suppressedForTrack = false,
                hasOverlayOrDragging = false,
            ),
        )
        assertEquals(
            false,
            com.voxmusic.app.ui.shouldScheduleAutoLyrics(
                onBrowsePage = false,
                isTv = false,
                activeMusic = true,
                isPlaying = true,
                hasSyncedLyrics = true,
                showLyrics = false,
                suppressedForTrack = false,
                hasOverlayOrDragging = true,
            ),
        )
    }
}
