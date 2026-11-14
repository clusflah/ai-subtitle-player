package com.aisub.player.meta

import com.aisub.player.media.RemoteFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverTest {

    @Test
    fun parseDoubanCollectionExtractsFieldsAndStatus() {
        val json = """
        {
          "subject_collection_items": [
            {
              "id": "36501234",
              "title": "葬送的芙莉莲",
              "type": "tv",
              "year": "2023",
              "episodes_info": "更新至28集",
              "card_subtitle": "2023 / 日本 / 剧情 动画 奇幻 / 斋藤圭一郎",
              "pic": {
                "large": "https://img1.doubanio.com/view/photo/l/public/p1.jpg",
                "normal": "https://img1.doubanio.com/view/photo/s/public/p1.jpg"
              },
              "rating": { "value": 9.4, "count": 120000 }
            },
            {
              "id": "1292052",
              "title": "肖申克的救赎",
              "type": "movie",
              "year": "",
              "episodes_info": "",
              "episodes_count": 0,
              "card_subtitle": "1994 / 美国 / 剧情 犯罪",
              "cover_url": "https://img2.doubanio.com/view/photo/l/public/p2.jpg",
              "rating": { "value": 9.7, "count": 3000000 }
            },
            {
              "id": "35000001",
              "title": "测试完结剧",
              "type": "tv",
              "year": "2024",
              "episodes_info": "全12集",
              "card_subtitle": "2024 / 英国 / 悬疑",
              "rating": { "value": 8.1, "count": 5000 }
            }
          ]
        }
        """.trimIndent()

        val list = DiscoverRepository.parseDoubanCollectionJson(json, defaultKind = "tv")
        assertEquals(3, list.size)

        val anime = list[0]
        assertEquals("douban:36501234", anime.id)
        assertEquals("tv", anime.kind)
        assertEquals(2023, anime.year)
        assertEquals("更新至28集", anime.episodeInfo)
        assertEquals(true, anime.ongoing)
        assertEquals(listOf("日本"), anime.countries)
        assertEquals(listOf("剧情", "动画", "奇幻"), anime.genres)
        assertEquals(9.4, anime.rating!!, 0.001)
        assertEquals("豆瓣", anime.ratingLabel)
        assertEquals("https://img1.doubanio.com/view/photo/l/public/p1.jpg", anime.posterUrl)

        val movie = list[1]
        assertEquals("movie", movie.kind)
        assertEquals(1994, movie.year)
        assertNull(movie.ongoing)
        assertEquals("https://img2.doubanio.com/view/photo/l/public/p2.jpg", movie.posterUrl)

        val finished = list[2]
        assertEquals(false, finished.ongoing)
    }

    @Test
    fun parseDoubanSearchExtractsTargets() {
        val json = """
        {
          "subjects": {
            "items": [
              {
                "target_type": "movie",
                "target": {
                  "id": "26752088",
                  "title": "我不是药神",
                  "type": "movie",
                  "year": "2018",
                  "card_subtitle": "2018 / 中国大陆 / 剧情 喜剧",
                  "cover_url": "https://img9.doubanio.com/view/photo/s/public/p2561305376.jpg",
                  "rating": { "value": 9.0, "count": 2100000 }
                }
              },
              {
                "target_type": "book",
                "target": { "id": "111", "title": "" }
              }
            ]
          }
        }
        """.trimIndent()

        val items = DiscoverRepository.parseDoubanSearchJson(json)
        assertEquals(1, items.size)
        assertEquals("我不是药神", items[0].title)
        assertEquals(2018, items[0].year)
        assertEquals(9.0, items[0].rating!!, 0.001)
    }

    @Test
    fun parseTmdbListFiltersPersonAndBuildsUrls() {
        val json = """
        {
          "results": [
            {
              "id": 94605,
              "media_type": "tv",
              "name": "双城之战",
              "original_name": "Arcane",
              "first_air_date": "2021-11-06",
              "overview": "英雄联盟动画剧集",
              "poster_path": "/arc.jpg",
              "backdrop_path": "/arc_bg.jpg",
              "vote_average": 8.74,
              "vote_count": 4200,
              "genre_ids": [16, 10765],
              "origin_country": ["US", "FR"]
            },
            {
              "id": 999,
              "media_type": "person",
              "name": "Some Actor"
            }
          ]
        }
        """.trimIndent()

        val items = DiscoverRepository.parseTmdbListJson(
            jsonText = json,
            fallbackKind = "movie",
            imageBase = "https://proxy.example/t/p/",
        )
        assertEquals(1, items.size)
        val tv = items[0]
        assertEquals("tmdb:tv:94605", tv.id)
        assertEquals("tv", tv.kind)
        assertEquals("双城之战", tv.title)
        assertEquals("Arcane", tv.originalTitle)
        assertEquals(2021, tv.year)
        assertEquals("https://proxy.example/t/p/w500/arc.jpg", tv.posterUrl)
        assertEquals("https://proxy.example/t/p/w1280/arc_bg.jpg", tv.backdropUrl)
        assertEquals(listOf("动画", "科幻奇幻"), tv.genres)
        assertEquals(listOf("美国", "法国"), tv.countries)
        assertEquals("TMDB", tv.ratingLabel)
    }

    @Test
    fun searchSourceBuildUrlReplacesPlaceholdersAndEncodesSpaces() {
        val item = DiscoverItem(
            id = "tmdb:movie:157336",
            kind = "movie",
            title = "星际穿越",
            originalTitle = "Interstellar",
            year = 2014,
            tmdbId = 157336,
            imdbId = "tt0816692",
        )
        val src = SearchSource(
            id = "test",
            name = "Test",
            category = SearchCategory.RE0,
            urlTemplate = "https://re0.me/search?q={q}&imdb={imdb}&tmdb={tmdb}&kind={kind}",
        )
        val url = src.buildUrl("星际穿越 2014", item)
        assertTrue(url.contains("q=%E6%98%9F%E9%99%85%E7%A9%BF%E8%B6%8A%202014"))
        assertTrue(url.contains("imdb=tt0816692"))
        assertTrue(url.contains("tmdb=157336"))
        assertTrue(url.contains("kind=movie"))
    }

    @Test
    fun customSourcesSerializeRoundTrip() {
        val sources = listOf(
            SearchSource("c1", "自定义网盘", SearchCategory.PAN, "https://pan.example/s?k={q}", "说明", enabled = false),
            SearchSource("c2", "自定义磁力", SearchCategory.MAGNET, "https://bt.example/{raw}", "", enabled = true),
        )
        val encoded = DiscoverSearchStore.encodeCustomSources(sources)
        val decoded = DiscoverSearchStore.parseCustomSources(encoded)
        assertEquals(2, decoded.size)
        assertEquals("c1", decoded[0].id)
        assertEquals(SearchCategory.PAN, decoded[0].category)
        assertFalse(decoded[0].enabled)
        assertEquals("https://bt.example/{raw}", decoded[1].urlTemplate)
        assertTrue(decoded[1].enabled)
    }

    @Test
    fun magnetLinksExtractNormalizeAndParseCloudShares() {
        val text = """
            资源1：magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&dn=The%20Matrix%201999.mkv
            资源2：https://pan.quark.cn/s/abc123def456
            资源3：https://115.com/s/sw3x99a88bc?password=1234
            资源4：https://mypikpak.com/s/VOabcXYZ
        """.trimIndent()

        val magnets = MagnetLinks.extractMagnets(text)
        assertEquals(1, magnets.size)
        assertEquals("The Matrix 1999.mkv", MagnetLinks.displayName(magnets[0]))

        val hexHash = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"
        assertEquals("magnet:?xt=urn:btih:$hexHash", MagnetLinks.normalizeMagnet(hexHash))
        val base32 = "MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U"
        assertEquals("magnet:?xt=urn:btih:$base32", MagnetLinks.normalizeMagnet(base32))
        assertNull(MagnetLinks.normalizeMagnet("not a magnet"))

        val shares = MagnetLinks.extractCloudShareUrls(text)
        assertEquals(
            listOf(
                "https://pan.quark.cn/s/abc123def456",
                "https://115.com/s/sw3x99a88bc?password=1234",
                "https://mypikpak.com/s/VOabcXYZ",
            ),
            shares,
        )
    }

    @Test
    fun parseDoubanUpgradesHttpPosterAndReadsCoverUrlFallback() {
        val json = """
        {
          "subject_collection_items": [
            {
              "id": "37000001",
              "title": "HttpPoster",
              "type": "tv",
              "cover": { "url": "http://img3.doubanio.com/view/photo/m_ratio_poster/public/p2935903589.jpg" }
            }
          ]
        }
        """.trimIndent()
        val list = DiscoverRepository.parseDoubanCollectionJson(json, defaultKind = "tv")
        assertEquals(1, list.size)
        assertEquals(
            "https://img3.doubanio.com/view/photo/m_ratio_poster/public/p2935903589.jpg",
            list[0].posterUrl,
        )
    }

    @Test
    fun remoteFilesBuildRequestAddsDoubanHeadersOnlyForDoubanAndPreservesCallerHeaders() {
        val doubanReq = RemoteFiles.buildRequest(
            realUrl = "https://img3.doubanio.com/view/photo/m_ratio_poster/public/p2935903589.jpg",
            headers = emptyMap(),
            truncateMaxBytes = 4096,
        )
        assertEquals("https://movie.douban.com/", doubanReq.header("Referer"))
        val ua = doubanReq.header("User-Agent") ?: ""
        assertTrue("Expected browser UA for Douban CDN, got: $ua", ua.contains("Mozilla/5.0"))
        assertEquals("bytes=0-4095", doubanReq.header("Range"))

        val tmdbReq = RemoteFiles.buildRequest(
            realUrl = "https://image.tmdb.org/t/p/w500/abc.jpg",
            headers = emptyMap(),
        )
        assertNull(tmdbReq.header("Referer"))
        assertNull(tmdbReq.header("User-Agent"))
        assertNull(tmdbReq.header("Range"))

        val customReq = RemoteFiles.buildRequest(
            realUrl = "https://img1.doubanio.com/view/photo/l/public/p1.jpg",
            headers = mapOf("Referer" to "https://custom.example/", "User-Agent" to "CustomUA/1.0"),
        )
        assertEquals("https://custom.example/", customReq.header("Referer"))
        assertEquals("CustomUA/1.0", customReq.header("User-Agent"))
    }
}
