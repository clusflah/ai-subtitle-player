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

        // 榜单连续播放队列转换：保留当前曲目的补全元数据并精确定位起始下标
        val chartItems = listOf(
            appleDiscover,
            spotifyDiscover,
            DiscoverItem(id = "itunes:999", kind = "music", title = "Go Easy", artist = "Dua Lipa", album = "DL4"),
        )
        val enrichedCurrent = spotifyDiscover.copy(album = "叶惠美", durationMs = 269_000L)
        val (chartQueue, startIdx) = com.aisub.player.music.WebStreamingMusic.buildQueueFromDiscoverItems(
            service = com.aisub.player.music.WebMusicService.SPOTIFY,
            currentItem = enrichedCurrent,
            queueItems = chartItems,
            preferredIndex = 1,
        )
        assertEquals(3, chartQueue.size)
        assertEquals(1, startIdx)
        assertEquals("叶惠美", com.aisub.player.remote.cloud.ServerProgress.find(chartQueue[1].uri)?.album)
        assertEquals("Go Easy", com.aisub.player.remote.cloud.ServerProgress.find(chartQueue[2].uri)?.title)
    }

    @Test
    fun officialSourceVectorIconsAndSpotifyPathfinderHookBuildCleanly() {
        val vectors = listOf(
            com.aisub.player.ui.buildAppleMusicOfficialVector(),
            com.aisub.player.ui.buildSpotifyOfficialVector(),
            com.aisub.player.ui.buildPlexOfficialVector(),
            com.aisub.player.ui.buildNavidromeOfficialVector(),
            com.aisub.player.ui.buildEmbyOfficialVector(),
            com.aisub.player.ui.buildJellyfinOfficialVector(),
            com.aisub.player.ui.buildOpenListOfficialVector(),
            com.aisub.player.ui.buildNetEaseMusicOfficialVector(),
            com.aisub.player.ui.buildYouTubeMusicOfficialVector(),
            com.aisub.player.ui.buildQqMusicOfficialVector(),
            com.aisub.player.ui.buildQishuiMusicOfficialVector(),
            com.aisub.player.ui.buildKugouMusicOfficialVector(),
            com.aisub.player.ui.buildBilibiliOfficialVector(),
        )
        vectors.forEach { vec ->
            assertTrue(vec.root.size > 0)
        }
        assertTrue(com.aisub.player.music.WebStreamingMusic.DOCUMENT_START_SCRIPT.contains("pathfinder"))
        assertTrue(com.aisub.player.music.WebStreamingMusic.DOCUMENT_START_SCRIPT.contains("XMLHttpRequest"))
        assertTrue(com.aisub.player.music.WebStreamingMusic.DOCUMENT_START_SCRIPT.contains("api\\/token"))
        assertTrue(com.aisub.player.music.WebStreamingMusic.DOCUMENT_START_SCRIPT.contains("clienttoken"))
        assertTrue(com.aisub.player.music.WebStreamingMusic.DOCUMENT_START_SCRIPT.contains("media-user-token"))
        assertEquals("token+abc/123==", com.aisub.player.music.WebStreamingMusic.extractCookieValue("itua=cn; media-user-token=token%2Babc%2F123%3D%3D; xp=1", "media-user-token"))
    }

    @Test
    fun condenseArtistBioKeepsCompleteSentencesWithinHalfPageBudget() {
        val arianaBio = """
            With her four-octave vocal acrobatics and iconic image, American singer, songwriter, and actress Ariana Grande has solidified her place as one of the quintessential pop stars of her generation, racking up stacks of awards and chart records in the process. Emerging in the early 2010s with the hit single "The Way," Grande was primed to follow in the footsteps of her biggest influences, Whitney Houston and Mariah Carey, due in part to her powerhouse vocals. With its Babyface production, her debut, Yours Truly, underscored her debt to '90s R&B, but Grande quickly incorporated hip-hop and EDM into her sound. "Problem," a 2014 smash duet with Iggy Azalea, was the first indication of her development, an evolution reinforced by the hits "Bang Bang" and "Love Me Harder."
        """.trimIndent()

        val condensedEn = com.aisub.player.ui.condenseArtistBio(arianaBio)
        assertTrue(condensedEn.length in 120..340)
        assertTrue(condensedEn.endsWith("in the process."))

        // 含 G.E.M. / U.S. 等缩写句点时不会误截断在缩写中间
        val gemBio = "Gloria Tang Zhi Kei (鄧紫棋), also known as G.E.M., is a singer-songwriter in Hong Kong and topped U.S. Mandopop charts. " +
            "She made her debut in 2008 and achieved widespread acclaim across Asia with powerhouse live performances. " +
            "Later albums expanded into electronic pop, hip-hop, and rock production across multiple world stadium tours and television appearances."
        val condensedGem = com.aisub.player.ui.condenseArtistBio(gemBio)
        assertTrue(condensedGem.contains("G.E.M., is a singer-songwriter"))
        assertTrue(condensedGem.endsWith("performances.") || condensedGem.endsWith("charts."))

        val zhBio = "周杰伦（Jay Chou），1979年1月18日出生于台湾省新北市，华语流行乐男歌手、音乐人、演员、导演。2000年发行首张个人专辑《Jay》，融合R&B、嘻哈与中国风元素，迅速在华语乐坛崭露头角。" +
            "2001年凭专辑《范特西》奠定个人音乐风格，此后陆续推出《八度空间》《叶惠美》《七里香》《十一月的萧邦》《依然范特西》等多张经典唱片，多次斩获金曲奖最佳专辑与最佳制作人奖，对当代华语流行音乐产生深远影响。"
        val condensedZh = com.aisub.player.ui.condenseArtistBio(zhBio)
        assertTrue(condensedZh.length <= 180)
        assertTrue(condensedZh.endsWith("。"))
    }

    @Test
    fun appUpdaterIgnoresTopDownloadLinksAndCollapsedHistoryWrappers() {
        val composedBody = """
            ## 安装包下载

            - [📱 Android 标准版 (`Voxtitle-2.10.25-arm64-v8a.apk`)](https://github.com/clusflah/ai-subtitle-player/releases/download/v2.10.25/Voxtitle-2.10.25-arm64-v8a.apk) · `68.4 MB`

            ## 本版更新（2.10.25）

            - 修复搜索后点击专辑无法展开的问题

            ## 历史版本

            <details>
            <summary>展开查看历史版本更新记录</summary>

            ## 2.10.24 更新

            - 修复 Spotify 登录后曲库空白问题

            ## 
            </details>
        """.trimIndent()

        val notesFrom24 = com.aisub.player.update.AppUpdater.notesSince(composedBody, "2.10.24")
        assertEquals("• 修复搜索后点击专辑无法展开的问题", notesFrom24)

        val notesFrom23 = com.aisub.player.update.AppUpdater.notesSince(composedBody, "2.10.23")
        assertTrue(notesFrom23.contains("【2.10.25】"))
        assertTrue(notesFrom23.contains("【2.10.24】"))
        assertTrue(!notesFrom23.contains("安装包下载"))
        assertTrue(!notesFrom23.contains("<details>"))
    }

    @Test
    fun spotifyAppRemoteSolution3PkceAndFallbackWorkCleanly() {
        val ctx = RuntimeEnvironment.getApplication()
        com.aisub.player.music.SpotifyAppRemoteBridge.saveClientId(ctx, "")
        com.aisub.player.music.SpotifyAppRemoteBridge.logoutAll(ctx)

        // 1. RFC 7636 S256 PKCE 标准向量验证
        val rfcVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            com.aisub.player.music.SpotifyAppRemoteBridge.sha256Base64Url(rfcVerifier),
        )

        // 2. SHA-1 格式化、封面 URI 转换与 OAuth 回调协议识别
        assertEquals(
            "AA:0B:FF",
            com.aisub.player.music.SpotifyAppRemoteBridge.formatSha1Bytes(
                byteArrayOf(0xAA.toByte(), 0x0B.toByte(), 0xFF.toByte()),
            ),
        )
        assertEquals(
            "https://i.scdn.co/image/ab67616d0000b2731234",
            com.aisub.player.music.SpotifyAppRemoteBridge.spotifyImageUriToHttps("spotify:image:ab67616d0000b2731234"),
        )
        assertTrue(
            com.aisub.player.music.SpotifyAppRemoteBridge.isCallbackUri(
                android.net.Uri.parse("voxtitle://spotify-callback?code=test_code_123&state=xyz"),
            ),
        )

        // 3. 保存 Client ID 并生成 PKCE 授权 URL；测试环境未装官方 Spotify App 时自动回退，不会误判为可遥控发声
        val sampleClientId = "0123456789abcdef0123456789abcdef"
        com.aisub.player.music.SpotifyAppRemoteBridge.saveClientId(ctx, "  $sampleClientId  ")
        assertEquals(sampleClientId, com.aisub.player.music.SpotifyAppRemoteBridge.getClientId(ctx))

        val authUrl = com.aisub.player.music.SpotifyAppRemoteBridge.buildPkceAuthorizeUrl(ctx)
        assertNotNull(authUrl)
        assertTrue(authUrl!!.startsWith("https://accounts.spotify.com/authorize?"))
        assertTrue(authUrl.contains("client_id=$sampleClientId"))
        assertTrue(authUrl.contains("code_challenge_method=S256"))
        assertTrue(authUrl.contains("voxtitle%3A%2F%2Fspotify-callback"))

        val st = com.aisub.player.music.SpotifyAppRemoteBridge.refreshStatus(ctx)
        assertTrue(st.isConfigured)
        assertTrue(st.remoteEnabled)
        assertTrue(!st.spotifyAppInstalled)
        assertTrue(!st.canAttemptRemotePlayback)

        // 4. 退出登录会关闭高音质自动连接开关，但保留输入框 Client ID 便于下次一键重连
        com.aisub.player.music.SpotifyAppRemoteBridge.logoutAll(ctx)
        val afterLogout = com.aisub.player.music.SpotifyAppRemoteBridge.refreshStatus(ctx)
        assertEquals(sampleClientId, afterLogout.clientId)
        assertTrue(!afterLogout.remoteEnabled)
        assertTrue(!com.aisub.player.music.SpotifyAppRemoteBridge.isReadyAsLoggedIn(ctx))
    }

    @Test
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun youtubeNetEaseAndQqMusicDirectParsersAndRoutingWork() {
        // 1. 全部 5 个流媒体服务的协议识别、音源枚举与 DiscoverItem ID 提取
        assertEquals(com.aisub.player.music.WebMusicService.YOUTUBE_MUSIC, com.aisub.player.music.WebMusicService.fromUri("ytmusic://track/dQw4w9WgXcQ"))
        assertEquals(com.aisub.player.music.WebMusicService.NETEASE_MUSIC, com.aisub.player.music.WebMusicService.fromUri("neteasemusic://track/186016"))
        assertEquals(com.aisub.player.music.WebMusicService.QQ_MUSIC, com.aisub.player.music.WebMusicService.fromUri("qqmusic://track/0039MnYb0qxYhV"))
        assertTrue(!com.aisub.player.music.WebStreamingMusic.needsLoginHookScript(com.aisub.player.music.WebMusicService.YOUTUBE_MUSIC))
        assertTrue(!com.aisub.player.music.WebStreamingMusic.needsLoginHookScript(com.aisub.player.music.WebMusicService.NETEASE_MUSIC))
        assertTrue(!com.aisub.player.music.WebStreamingMusic.needsLoginHookScript(com.aisub.player.music.WebMusicService.QQ_MUSIC))
        assertTrue(com.aisub.player.music.WebStreamingMusic.needsLoginHookScript(com.aisub.player.music.WebMusicService.APPLE_MUSIC))
        assertTrue(com.aisub.player.music.WebStreamingMusic.needsLoginHookScript(com.aisub.player.music.WebMusicService.SPOTIFY))

        val ytDiscover = DiscoverItem(
            id = "yt:dQw4w9WgXcQ",
            kind = "music",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            platforms = listOf(PlatformSource("YouTube Music", "https://music.youtube.com/watch?v=dQw4w9WgXcQ")),
        )
        assertEquals("dQw4w9WgXcQ", com.aisub.player.music.WebStreamingMusic.extractYouTubeVideoId(ytDiscover))
        val ytEntry = com.aisub.player.music.WebStreamingMusic.buildEntryFromDiscoverItem(
            com.aisub.player.music.WebMusicService.YOUTUBE_MUSIC,
            ytDiscover,
        )
        assertTrue(ytEntry.uri.startsWith("ytmusic://track/dQw4w9WgXcQ"))

        val neDiscover = DiscoverItem(
            id = "netease:186016",
            kind = "music",
            title = "晴天",
            artist = "周杰伦",
            neteaseId = "186016",
        )
        assertEquals("186016", com.aisub.player.music.WebStreamingMusic.extractNetEaseSongId(neDiscover))
        val neEntry = com.aisub.player.music.WebStreamingMusic.buildEntryFromDiscoverItem(
            com.aisub.player.music.WebMusicService.NETEASE_MUSIC,
            neDiscover,
        )
        assertTrue(neEntry.uri.startsWith("neteasemusic://track/186016"))

        val qqDiscover = DiscoverItem(
            id = "qq:0039MnYb0qxYhV",
            kind = "music",
            title = "晴天",
            artist = "周杰伦",
            platforms = listOf(PlatformSource("QQ音乐", "https://y.qq.com/n/ryqq/songDetail/0039MnYb0qxYhV")),
        )
        assertEquals("0039MnYb0qxYhV", com.aisub.player.music.WebStreamingMusic.extractQqSongMid(qqDiscover))
        val qqEntry = com.aisub.player.music.WebStreamingMusic.buildEntryFromDiscoverItem(
            com.aisub.player.music.WebMusicService.QQ_MUSIC,
            qqDiscover,
        )
        assertTrue(qqEntry.uri.startsWith("qqmusic://track/0039MnYb0qxYhV"))

        // 2. YouTube Music SAPISIDHASH 签名、曲库条目解析与音质优先级（256k Opus 774 > 256k AAC 141 > 160k Opus 251 > 128k AAC 140）
        val sapisidHash = com.aisub.player.music.DirectMusicServiceApi.buildSapisidHash(
            cookies = "SAPISID=test_secret_123; SID=abc",
            origin = "https://music.youtube.com",
            nowSec = 1700000000L,
        )
        assertNotNull(sapisidHash)
        assertTrue(sapisidHash!!.startsWith("SAPISIDHASH 1700000000_"))
        assertEquals(40, sapisidHash.substringAfter('_').length)

        val ytBrowseJson = JSONObject().put(
            "contents",
            JSONArray()
                .put(
                    JSONObject().put(
                        "musicResponsiveListItemRenderer",
                        JSONObject()
                            .put("playlistItemData", JSONObject().put("videoId", "abc123XYZ"))
                            .put(
                                "flexColumns",
                                JSONArray()
                                    .put(
                                        JSONObject().put(
                                            "musicResponsiveListItemFlexColumnRenderer",
                                            JSONObject().put("text", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "稻香")))),
                                        ),
                                    )
                                    .put(
                                        JSONObject().put(
                                            "musicResponsiveListItemFlexColumnRenderer",
                                            JSONObject().put("text", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "周杰伦 • 魔杰座 • 3:43")))),
                                        ),
                                    ),
                            ),
                    ),
                )
                .put(
                    JSONObject().put(
                        "musicTwoRowItemRenderer",
                        JSONObject()
                            .put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "叶惠美"))))
                            .put("subtitle", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "专辑 • 周杰伦"))))
                            .put(
                                "navigationEndpoint",
                                JSONObject().put(
                                    "browseEndpoint",
                                    JSONObject().put("browseId", "MPREb_123456"),
                                ),
                            ),
                    ),
                ),
        )
        val ytTracks = com.aisub.player.music.DirectMusicServiceApi.parseYouTubeItems(ytBrowseJson, wantType = "track")
        assertEquals(1, ytTracks.size)
        assertEquals("abc123XYZ", ytTracks.first().ratingKey)
        assertEquals("稻香", ytTracks.first().title)
        assertEquals("周杰伦", ytTracks.first().grandparentTitle)
        assertEquals(223_000L, ytTracks.first().durationMs)

        val ytAlbums = com.aisub.player.music.DirectMusicServiceApi.parseYouTubeItems(ytBrowseJson, wantType = "album")
        assertEquals(1, ytAlbums.size)
        assertEquals("album|MPREb_123456", ytAlbums.first().ratingKey)

        val ytPlayerResp = JSONObject()
            .put("playabilityStatus", JSONObject().put("status", "OK"))
            .put("videoDetails", JSONObject().put("lengthSeconds", "223"))
            .put(
                "streamingData",
                JSONObject().put(
                    "adaptiveFormats",
                    JSONArray()
                        .put(JSONObject().put("itag", 140).put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"").put("bitrate", 128000).put("url", "https://rr1.googlevideo.com/140"))
                        .put(JSONObject().put("itag", 251).put("mimeType", "audio/webm; codecs=\"opus\"").put("bitrate", 160000).put("url", "https://rr1.googlevideo.com/251"))
                        .put(JSONObject().put("itag", 141).put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"").put("bitrate", 256000).put("url", "https://rr1.googlevideo.com/141"))
                        .put(JSONObject().put("itag", 774).put("mimeType", "audio/webm; codecs=\"opus\"").put("bitrate", 256000).put("url", "https://rr1.googlevideo.com/774")),
                ),
            )
        val bestYt = com.aisub.player.music.DirectMusicServiceApi.parseBestYouTubeAudioFormat(ytPlayerResp)
        assertNotNull(bestYt)
        assertEquals("https://rr1.googlevideo.com/774", bestYt!!.url)
        assertEquals("256k Opus", bestYt.qualityLabel)
        assertEquals(223_000L, bestYt.durationMs)

        // 3. 网易云音乐无损 FLAC / 320k 直链响应解析
        val neFlacResp = JSONObject().put(
            "data",
            JSONArray().put(
                JSONObject()
                    .put("url", "http://m8.music.126.net/song.flac")
                    .put("level", "lossless")
                    .put("br", 999000)
                    .put("time", 269000L),
            ),
        )
        val neStream = com.aisub.player.music.DirectMusicServiceApi.parseNetEasePlayerUrlResponse(neFlacResp)
        assertNotNull(neStream)
        assertEquals("https://m8.music.126.net/song.flac", neStream!!.url)
        assertEquals("无损 FLAC", neStream.qualityLabel)
        assertEquals(269_000L, neStream.durationMs)

        // 4. QQ音乐 Cookie 解析（含微信 wxuin 跳过 uin=0）与 vkey 响应优先级（跳过空 purl、支持 opi30surl 试听兜底）
        val (qqUin, qqKey) = com.aisub.player.music.DirectMusicServiceApi.extractQqUinAndKey("p_uin=o012345678; qm_keyst=Q_H_L_testkey")
        assertEquals("12345678", qqUin)
        assertEquals("Q_H_L_testkey", qqKey)

        val (wxUin, wxKey) = com.aisub.player.music.DirectMusicServiceApi.extractQqUinAndKey("uin=0; wxuin=11529215046068; qm_keyst=W_X_wxkey")
        assertEquals("11529215046068", wxUin)
        assertEquals("W_X_wxkey", wxKey)

        val qqVkeyResp = JSONObject().put(
            "req_0",
            JSONObject().put(
                "data",
                JSONObject()
                    .put("sip", JSONArray().put("http://aqqmusic.tc.qq.com/"))
                    .put(
                        "midurlinfo",
                        JSONArray()
                            .put(JSONObject().put("filename", "F0000039MnYb0qxYhV.flac").put("purl", ""))
                            .put(JSONObject().put("filename", "M8000039MnYb0qxYhV.mp3").put("purl", "M8000039MnYb0qxYhV.mp3?guid=10000&vkey=XYZ")),
                    ),
            ),
        )
        val qqStream = com.aisub.player.music.DirectMusicServiceApi.parseQqVkeyResponse(qqVkeyResp)
        assertNotNull(qqStream)
        assertEquals("http://aqqmusic.tc.qq.com/M8000039MnYb0qxYhV.mp3?guid=10000&vkey=XYZ", qqStream!!.url)
        assertEquals("HQ 320k", qqStream.qualityLabel)

        val qqPreviewResp = JSONObject().put(
            "req_0",
            JSONObject().put(
                "data",
                JSONObject()
                    .put("sip", JSONArray().put("http://aqqmusic.tc.qq.com/"))
                    .put(
                        "midurlinfo",
                        JSONArray().put(
                            JSONObject()
                                .put("filename", "C400001.m4a")
                                .put("purl", "")
                                .put("opi30surl", "RS02001.mp3?vkey=PREVIEW"),
                        ),
                    ),
            ),
        )
        val qqPreview = com.aisub.player.music.DirectMusicServiceApi.parseQqVkeyResponse(qqPreviewResp)
        assertNotNull(qqPreview)
        assertEquals("http://aqqmusic.tc.qq.com/RS02001.mp3?vkey=PREVIEW", qqPreview!!.url)
        assertEquals("试听 30s", qqPreview.qualityLabel)

        val qqRs02Resp = JSONObject().put(
            "req_0",
            JSONObject().put(
                "data",
                JSONObject()
                    .put("sip", JSONArray().put("http://aqqmusic.tc.qq.com/"))
                    .put(
                        "midurlinfo",
                        JSONArray().put(
                            JSONObject()
                                .put("filename", "RS02003yrAfR3G899X.mp3")
                                .put("purl", "RS02003yrAfR3G899X.mp3?vkey=RS02KEY"),
                        ),
                    ),
            ),
        )
        val qqRs02 = com.aisub.player.music.DirectMusicServiceApi.parseQqVkeyResponse(qqRs02Resp)
        assertNotNull(qqRs02)
        assertEquals("http://aqqmusic.tc.qq.com/RS02003yrAfR3G899X.mp3?vkey=RS02KEY", qqRs02!!.url)
        assertEquals("试听版", qqRs02.qualityLabel)

        // 4b. Apple Music 流行热歌榜统一指向台湾区（tw）榜单
        val hotChart = MusicDiscoverCharts.findChart("music_hot")
        assertNotNull(hotChart)
        assertEquals("tw", hotChart!!.fallbackAppleCountry)
        assertEquals("tr", com.aisub.player.music.WebStreamingMusic.normalizeAppleStorefront("TR"))
        assertEquals(
            false,
            com.aisub.player.media.WebStreamingMusicPlayer.resolveExternalPlayWhenReady(
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
        val musicQueue = listOf(
            com.aisub.player.media.MediaEntry(uri = "applemusic://track/tw_1", title = "Song 1.mp3", isVideo = false, durationMs = 200_000L),
            com.aisub.player.media.MediaEntry(uri = "spotify://search?title=Song2", title = "Song 2.mp3", isVideo = false, durationMs = 210_000L),
        )
        val sessionJson = com.aisub.player.media.AppStore.encodeMusicSessionJson(
            com.aisub.player.media.SavedMusicSession(musicQueue, index = 0, positionMs = 15_000L, savedAt = 1_000L),
        )
        val progressJson = com.aisub.player.media.AppStore.encodeMusicProgressJson(
            uri = musicQueue[1].uri,
            index = 1,
            positionMs = 88_000L,
            durationMs = 210_000L,
            savedAt = 2_000L,
        )
        val restoredSession = com.aisub.player.media.AppStore.decodeMusicSessionJson(sessionJson, progressJson)
        assertNotNull(restoredSession)
        assertEquals(1, restoredSession!!.index)
        assertEquals(88_000L, restoredSession.positionMs)
        assertEquals(
            1,
            com.aisub.player.MainViewModel.resolveMusicQueueIndex(
                queue = musicQueue,
                currentUri = musicQueue[1].uri,
                playerMediaItemCount = 0,
                playerIndex = 0,
            ),
        )

        // 5. 歌单顶部固定「已点赞的歌曲」逻辑
        val normalPlaylists = listOf(
            com.aisub.player.remote.cloud.ServerItem(ratingKey = "playlist|101", type = "playlist", title = "运动歌单"),
            com.aisub.player.remote.cloud.ServerItem(ratingKey = "playlist|102", type = "playlist", title = "睡前轻音乐"),
        )
        val pinnedSpotify = com.aisub.player.music.WebStreamingMusic.pinLikedPlaylistAtTop(
            com.aisub.player.music.WebMusicService.SPOTIFY,
            normalPlaylists,
        )
        assertEquals("playlist|liked", pinnedSpotify.first().ratingKey)
        assertEquals("已点赞的歌曲", pinnedSpotify.first().title)
        assertEquals(3, pinnedSpotify.size)

        val withExistingLiked = listOf(
            com.aisub.player.remote.cloud.ServerItem(ratingKey = "playlist|201", type = "playlist", title = "日语精选"),
            com.aisub.player.remote.cloud.ServerItem(ratingKey = "playlist|202", type = "playlist", title = "我喜欢的音乐"),
        )
        val movedToTop = com.aisub.player.music.WebStreamingMusic.pinLikedPlaylistAtTop(
            com.aisub.player.music.WebMusicService.NETEASE_MUSIC,
            withExistingLiked,
        )
        assertEquals("playlist|202", movedToTop.first().ratingKey)
        assertEquals(2, movedToTop.size)

        // 6. 含重复歌曲的歌单队列在 WebStreamingMusicPlayer 中不会触发 SimpleBasePlayer Duplicate UID 闪退，
        //    且 setMediaItems 切歌时会立即将状态复位至 IDLE，避免 prepare() 前的 playWhenReady=true 误续播上一首
        val ctx = RuntimeEnvironment.getApplication()
        val dupPlayer = com.aisub.player.media.WebStreamingMusicPlayer(ctx)
        val dupUri = "spotify://track/3DYVWvPh3kGwPasp7yjahc?title=Test"
        val item1 = androidx.media3.common.MediaItem.Builder().setUri(dupUri).setMediaId(dupUri).build()
        val item2 = androidx.media3.common.MediaItem.Builder().setUri(dupUri).setMediaId(dupUri).build()
        dupPlayer.setMediaItems(listOf(item1, item2), 1, 0L)
        assertEquals(2, dupPlayer.mediaItemCount)
        assertEquals(1, dupPlayer.currentMediaItemIndex)
        assertEquals(androidx.media3.common.Player.STATE_IDLE, dupPlayer.playbackState)
        val newItem = androidx.media3.common.MediaItem.Builder()
            .setUri("spotify://search?title=Patient%20Zero&artist=Taylor%20Swift")
            .setMediaId("spotify://search?title=Patient%20Zero&artist=Taylor%20Swift")
            .build()
        dupPlayer.setMediaItems(listOf(newItem), 0, 0L)
        assertEquals(androidx.media3.common.Player.STATE_IDLE, dupPlayer.playbackState)
        dupPlayer.release()
    }

    @Test
    fun infiniteShuffleIconAndUnplayedFirstShuffleSelectionWork() {
        assertNotNull(com.aisub.player.ui.AppIcons.AllInclusive)

        val uris = listOf(
            "spotify://track/A",
            "spotify://track/B",
            "spotify://track/C",
            "spotify://track/D",
        )
        val played = HashSet<String>(listOf("spotify://track/A", "spotify://track/B", "spotify://track/C"))
        // 仅 D 未播放过，必须优先选中 D（下标 3）
        val nextIdx = com.aisub.player.media.WebStreamingMusicPlayer.pickNextShuffleIndex(
            itemUris = uris,
            currentIndex = 2,
            playedUris = played,
        )
        assertEquals(3, nextIdx)

        // 当全部曲目都已播过一轮时，清空旧记录（仅保留当前曲目）并在非当前曲目中继续随机
        played.add("spotify://track/D")
        val cycledIdx = com.aisub.player.media.WebStreamingMusicPlayer.pickNextShuffleIndex(
            itemUris = uris,
            currentIndex = 3,
            playedUris = played,
        )
        assertTrue(cycledIdx in 0..2)
        assertEquals(setOf("spotify://track/D"), played)
    }

    @Test
    fun smartMusicSourceResolverPrefersActiveThenSingleThenPriorityOrder() {
        val resolver = com.aisub.player.music.SmartMusicSourceResolver
        val apple = com.aisub.player.music.WebMusicService.APPLE_MUSIC
        val spotify = com.aisub.player.music.WebMusicService.SPOTIFY
        val netease = com.aisub.player.music.WebMusicService.NETEASE_MUSIC

        // 无已登录音源 -> null
        assertEquals(null, resolver.resolve(emptySet(), apple))

        // 当前激活源已登录 -> 优先使用当前激活源
        assertEquals(spotify, resolver.resolve(setOf(apple, spotify), spotify))
        assertEquals(netease, resolver.resolve(setOf(apple, netease), netease))

        // 仅登录单个音源 -> 直接使用该源（即使当前激活源不是流媒体或未登录）
        assertEquals(spotify, resolver.resolve(setOf(spotify), null))
        assertEquals(netease, resolver.resolve(setOf(netease), apple))

        // 多个音源已登录且当前激活源未登录/为本地 -> 按优先级 Apple Music > Spotify > 网易云
        assertEquals(apple, resolver.resolve(setOf(spotify, apple, netease), null))
        assertEquals(spotify, resolver.resolve(setOf(spotify, netease), apple))
    }
}
