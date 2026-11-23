package com.aisub.player.meta

import android.app.Application
import com.aisub.player.MusicDiscoverSource
import com.aisub.player.SettingsRepository
import com.aisub.player.media.MediaEntry
import com.aisub.player.web.WebAppWhitelist
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MusicDiscoverTest {

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("discover_mode_prefs", Application.MODE_PRIVATE).edit().clear().commit()
        DiscoverModeStore.resetForTest()
    }

    @Test
    fun discoverModeDefaultsToVideoAndPersistsAcrossRestarts() {
        val ctx = RuntimeEnvironment.getApplication()
        // 默认是影视
        assertEquals(DiscoverMode.VIDEO, DiscoverModeStore.current(ctx))
        assertEquals("影视", DiscoverModeStore.current(ctx).label)

        // 切换到音乐后，模拟冷启动重新初始化，依然恢复为音乐
        DiscoverModeStore.select(ctx, DiscoverMode.MUSIC)
        assertEquals(DiscoverMode.MUSIC, DiscoverModeStore.current(ctx))
        assertEquals("音乐", DiscoverModeStore.current(ctx).label)

        DiscoverModeStore.resetForTest()
        assertEquals(DiscoverMode.MUSIC, DiscoverModeStore.ensureInit(ctx).value)

        // 再次切回影视并重读
        DiscoverModeStore.select(ctx, DiscoverMode.VIDEO)
        DiscoverModeStore.resetForTest()
        assertEquals(DiscoverMode.VIDEO, DiscoverModeStore.ensureInit(ctx).value)
    }

    @Test
    fun parsesNeteaseAndAppleSongsMergesDuplicatesAndRoundTripsCacheJson() {
        val neteaseJson = JSONObject()
            .put("id", 186016L)
            .put("name", "晴天")
            .put("dt", 269_000L)
            .put("ar", JSONArray().put(JSONObject().put("name", "周杰伦")))
            .put(
                "al",
                JSONObject()
                    .put("name", "叶惠美")
                    .put("picUrl", "http://p1.music.126.net/cover123.jpg"),
            )

        val neteaseItem = MusicDiscoverRepository.parseNeteaseSongJson(neteaseJson)
        assertNotNull(neteaseItem)
        assertEquals("music", neteaseItem!!.kind)
        assertEquals("晴天", neteaseItem.title)
        assertEquals("周杰伦", neteaseItem.artist)
        assertEquals("叶惠美", neteaseItem.album)
        assertEquals("186016", neteaseItem.neteaseId)
        assertTrue(neteaseItem.posterUrl!!.startsWith("https://"))

        val appleRss = JSONObject().put(
            "feed",
            JSONObject().put(
                "results",
                JSONArray().put(
                    JSONObject()
                        .put("id", "999001")
                        .put("name", "晴天")
                        .put("artistName", "周杰倫")
                        .put("releaseDate", "2003-07-31")
                        .put("artworkUrl100", "https://is1-ssl.mzstatic.com/image/thumb/100x100bb.jpg")
                        .put("url", "https://music.apple.com/tw/album/999001"),
                ),
            ),
        ).toString()

        val appleItems = MusicDiscoverRepository.parseAppleRssSongsJson(appleRss)
        assertEquals(1, appleItems.size)
        assertEquals(2003, appleItems.first().year)

        // 简繁同名歌手 + 同名歌曲去重合并，同时保留网易云 ID 与 Apple Music 直达地址
        val deduped = MusicDiscoverRepository.deduplicateSongs(listOf(neteaseItem) + appleItems)
        assertEquals(1, deduped.size)
        val merged = deduped.first()
        assertEquals("186016", merged.neteaseId)
        assertEquals("https://music.apple.com/tw/album/999001", merged.appleUrl)
        assertEquals(2003, merged.year)
        assertEquals("周杰伦 · 叶惠美", merged.subtitleLine())

        // 磁盘缓存 JSON 编解码往返一致
        val encoded = DiscoverRepository.encodeItemJson(merged)
        val decoded = DiscoverRepository.decodeItemJson(encoded)
        assertEquals(merged, decoded)
    }

    @Test
    fun localTrackMatchingAndPlatformPlayJumpWhitelistWork() {
        val song = DiscoverItem(
            id = "netease:186016",
            kind = "music",
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            neteaseId = "186016",
        )
        val audios = listOf(
            MediaEntry("content://media/1", "七里香 - 周杰伦.flac", folder = "周杰伦", isVideo = false),
            MediaEntry("content://media/2", "周杰倫 - 晴天 (Live).flac", folder = "Music", isVideo = false),
        )
        val matches = MusicRecommendationEngine.matchLocalTracks(song, audios)
        assertEquals(1, matches.size)
        assertEquals("content://media/2", matches.first().uri)

        val neteaseJump = MusicPlatformCatalog.resolvePlayUrl(PlatformSource("网易云音乐"), song)
        assertEquals("https://y.music.163.com/m/song?id=186016", neteaseJump)
        assertTrue(WebAppWhitelist.isPlayPlatformDomain(neteaseJump))
        assertTrue(WebAppWhitelist.shouldAllowAppLaunch("orpheus://song/186016", neteaseJump, fromDiscoverPlay = true))
    }

    @Test
    fun musicDiscoverSourcePersistsInSettingsAndParsesQqSongs() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("settings", Application.MODE_PRIVATE).edit().clear().commit()
        val repo = SettingsRepository(ctx)

        assertEquals(MusicDiscoverSource.AUTO, repo.load().musicDiscoverSource)
        for (src in listOf(MusicDiscoverSource.QQ, MusicDiscoverSource.NETEASE, MusicDiscoverSource.APPLE, MusicDiscoverSource.AUTO)) {
            repo.save(repo.load().copy(musicDiscoverSource = src))
            assertEquals(src, SettingsRepository(ctx).load().musicDiscoverSource)
        }

        val qqJson = JSONObject()
            .put("mid", "0039MnYb0qxYhV")
            .put("id", 97773L)
            .put("title", "晴天")
            .put("interval", 269L)
            .put("time_public", "2003-07-31")
            .put("singer", JSONArray().put(JSONObject().put("name", "周杰伦")))
            .put(
                "album",
                JSONObject()
                    .put("name", "叶惠美")
                    .put("mid", "000MkMni19ClKG"),
            )

        val qqSong = MusicDiscoverRepository.parseQqSongJson(qqJson)
        assertNotNull(qqSong)
        assertEquals("qq:0039MnYb0qxYhV", qqSong!!.id)
        assertEquals("晴天", qqSong.title)
        assertEquals("周杰伦", qqSong.artist)
        assertEquals("叶惠美", qqSong.album)
        assertEquals(269_000L, qqSong.durationMs)
        assertEquals(2003, qqSong.year)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R500x500M000000MkMni19ClKG.jpg", qqSong.posterUrl)

        val qqPlatform = MusicPlatformCatalog.platformsForItem(qqSong).first { it.name == "QQ音乐" }
        val qqJump = MusicPlatformCatalog.resolvePlayUrl(qqPlatform, qqSong)
        assertEquals("https://y.qq.com/n/ryqq/songDetail/0039MnYb0qxYhV", qqJump)
        assertTrue(WebAppWhitelist.isPlayPlatformDomain(qqJump))

        val rawBio = """G.E.M. (鄧紫棋) is a singer-songwriter.<br/>Famous for powerhouse vocals. <a target="_blank" href="https://last.fm">Read more on Last.fm</a>"""
        assertEquals(
            "G.E.M. (鄧紫棋) is a singer-songwriter.\nFamous for powerhouse vocals.",
            com.aisub.player.remote.cloud.SubsonicClient.stripSimpleHtml(rawBio),
        )
    }

    @Test
    fun appleMusicAndSpotifySourceSelectionAndStreamingEntryBuildWork() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("music_source", Application.MODE_PRIVATE).edit().clear().commit()

        for (svc in com.aisub.player.music.WebMusicService.entries) {
            com.aisub.player.music.MusicSourceStore.selectWebService(ctx, svc)
            val active = com.aisub.player.music.MusicSourceStore.active(ctx)
            assertEquals(svc, active.webService)
            assertTrue(!active.isLocal && active.isWebStreaming)
            val (resolved, srv) = com.aisub.player.music.MusicSourceStore.resolveEffective(active, emptyList())
            assertEquals(svc, resolved.webService)
            assertEquals(null, srv)
        }

        val appleDiscover = DiscoverItem(
            id = "itunes:1440857781",
            kind = "music",
            title = "Blank Space",
            artist = "Taylor Swift",
            album = "1989",
            durationMs = 231_000L,
            posterUrl = "https://is1-ssl.mzstatic.com/image/thumb/800x800bb.jpg",
            appleUrl = "https://music.apple.com/us/album/1989/1440857105?i=1440857781",
        )
        assertEquals("1440857781", com.aisub.player.music.WebStreamingMusic.extractAppleSongId(appleDiscover))

        val appleEntry = com.aisub.player.music.WebStreamingMusic.buildEntryFromDiscoverItem(
            com.aisub.player.music.WebMusicService.APPLE_MUSIC,
            appleDiscover,
        )
        assertTrue(com.aisub.player.music.WebStreamingMusic.isStreamingUri(appleEntry.uri))
        assertTrue(appleEntry.uri.startsWith("applemusic://track/1440857781"))
        val spMeta = com.aisub.player.remote.cloud.ServerProgress.find(appleEntry.uri)
        assertNotNull(spMeta)
        assertEquals("Blank Space", spMeta!!.title)
        assertEquals("Taylor Swift", spMeta.artist)
        assertEquals("1989", spMeta.album)

        val spotifyDiscover = DiscoverItem(
            id = "netease:186016",
            kind = "music",
            title = "晴天",
            artist = "周杰伦",
            platforms = listOf(PlatformSource("Spotify", "https://open.spotify.com/track/3DYVWvPh3kGwPasp7yjahc?si=abc")),
        )
        assertEquals("3DYVWvPh3kGwPasp7yjahc", com.aisub.player.music.WebStreamingMusic.extractSpotifyTrackId(spotifyDiscover))
        val spotifyEntry = com.aisub.player.music.WebStreamingMusic.buildEntryFromDiscoverItem(
            com.aisub.player.music.WebMusicService.SPOTIFY,
            spotifyDiscover,
        )
        assertTrue(com.aisub.player.music.WebStreamingMusic.isStreamingUri(spotifyEntry.uri))
        assertTrue(spotifyEntry.uri.startsWith("spotify://track/3DYVWvPh3kGwPasp7yjahc"))
    }
}
