package com.voxmusic.app

import com.voxmusic.app.media.MediaEntry
import com.voxmusic.app.media.MusicAppStore
import com.voxmusic.app.media.SavedPlaybackSession
import com.voxmusic.app.media.TrackMetadataRegistry
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
    fun onlyAppleMusicAndSpotifySourcesExist() {
        assertEquals(
            listOf(WebMusicService.APPLE_MUSIC, WebMusicService.SPOTIFY),
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
    }
}
