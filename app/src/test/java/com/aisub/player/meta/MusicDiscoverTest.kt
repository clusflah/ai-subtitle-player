package com.aisub.player.meta

import android.app.Application
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
}
