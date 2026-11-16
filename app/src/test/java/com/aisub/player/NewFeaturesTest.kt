package com.aisub.player

import com.aisub.player.media.CustomMediaLibrary
import com.aisub.player.media.CustomMediaLibraryStore
import com.aisub.player.media.LibraryFolderBinding
import com.aisub.player.media.MediaLibraryType
import com.aisub.player.media.ScrapedEpisode
import com.aisub.player.media.ScrapedMediaItem
import com.aisub.player.meta.RecommendationEngine
import org.junit.Assert.assertEquals
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
}
