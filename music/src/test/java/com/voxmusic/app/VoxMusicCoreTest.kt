package com.voxmusic.app

import com.voxmusic.app.media.TrackMetadataRegistry
import com.voxmusic.app.meta.DiscoverItem
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
}
