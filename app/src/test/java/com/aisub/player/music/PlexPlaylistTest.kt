package com.aisub.player.music

import android.app.Application
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType
import com.aisub.player.remote.cloud.PlexClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlexPlaylistTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun extractPlaylistSectionIdHandlesDirectAndEncodedContentUris() {
        assertEquals(
            "5",
            PlexClient.extractPlaylistSectionId(JSONObject().put("librarySectionID", 5)),
        )
        assertEquals(
            "3",
            PlexClient.extractPlaylistSectionId(
                JSONObject().put("content", "server://abc123/com.plexapp.plugins.library/library/sections/3/all?type=10"),
            ),
        )
        assertEquals(
            "12",
            PlexClient.extractPlaylistSectionId(
                JSONObject().put("content", "library://uuid/directory/%2Flibrary%2Fsections%2F12%2Fall%3Ftype%3D10"),
            ),
        )
        assertNull(PlexClient.extractPlaylistSectionId(JSONObject().put("smart", false)))
    }

    @Test
    fun loadServerPlaylistsFiltersOrphanedSmartPlaylistsAndKeepsManualAndCompositeCover() = runBlocking {
        val summaryItems = JSONArray()
            .put(playlistJson("101", "❤️ Tracks", smart = true, leafCount = 0, summary = "All your highly rated tracks, in one convenient place."))
            .put(playlistJson("102", "❤️ Tracks", smart = true, leafCount = 2, summary = "All your highly rated tracks, in one convenient place."))
            .put(playlistJson("103", "❤️ Tracks", smart = true, leafCount = 1, summary = "All your highly rated tracks, in one convenient place."))
            .put(playlistJson("104", "All Music", smart = true, leafCount = 1966, summary = "The easiest way to play all your music."))
            .put(
                playlistJson("105", "All Music", smart = true, leafCount = 2227, summary = "The easiest way to play all your music.")
                    .put("composite", "/playlists/105/composite/1700000000"),
            )
            .put(playlistJson("106", "Fresh ❤️", smart = true, leafCount = 1, summary = "Tracks you love but haven't played in a while."))
            .put(playlistJson("107", "Fresh ❤️", smart = true, leafCount = 1, summary = "Tracks you love but haven't played in a while."))
            .put(playlistJson("201", "我的自建歌单", smart = false, leafCount = 18, summary = "周末常听精选"))

        val sectionByRk = mapOf(
            "101" to "1",
            "102" to "5",
            "103" to "3",
            "104" to "3",
            "105" to "5",
            "106" to "3",
            "107" to "5",
        )

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                return when {
                    path == "/library/sections" -> jsonResp(
                        JSONObject().put(
                            "Directory",
                            JSONArray()
                                .put(JSONObject().put("key", "2").put("title", "电影").put("type", "movie"))
                                .put(JSONObject().put("key", "5").put("title", "音乐").put("type", "artist")),
                        ),
                    )
                    // 模拟服务端未按 sectionID 过滤，原样返回全部智能歌单与手动歌单
                    path == "/playlists" || path == "/playlists/all" -> jsonResp(
                        JSONObject().put("Metadata", summaryItems),
                    )
                    path.startsWith("/playlists/") && !path.endsWith("/items") -> {
                        val rk = path.removePrefix("/playlists/")
                        val sec = sectionByRk[rk]
                        val base = (0 until summaryItems.length())
                            .map { summaryItems.getJSONObject(it) }
                            .firstOrNull { it.optString("ratingKey") == rk }
                        if (base == null) {
                            MockResponse().setResponseCode(404).addHeader("X-Plex-Protocol", "1.0")
                        } else {
                            val detail = JSONObject(base.toString())
                            if (sec != null) {
                                detail.put("content", "server://mach1/com.plexapp.plugins.library/library/sections/$sec/all?type=10")
                            }
                            jsonResp(JSONObject().put("Metadata", JSONArray().put(detail)))
                        }
                    }
                    else -> MockResponse().setResponseCode(404).addHeader("X-Plex-Protocol", "1.0")
                }
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val srv = Server(
            id = "plex-test-1",
            type = ServerType.PLEX,
            name = "Plex",
            baseUrl = baseUrl,
            extra = PlexClient.manual(baseUrl, "test-token"),
        )
        val client = PlexClient(srv)

        // 即使旧配置中残留了已删除的 sectionKey="3"，也会自动切到有效音乐库 "5" 并剔除 1966 首的失效 All Music
        val playlists = MusicSourceStore.loadServerPlaylists(client, sectionKey = "3")
        assertEquals(listOf("102", "105", "107", "201"), playlists.map { it.ratingKey })

        val allMusic = playlists.first { it.ratingKey == "105" }
        assertEquals("All Music", allMusic.title)
        assertEquals(2227, allMusic.leafCount)
        assertEquals("The easiest way to play all your music.", allMusic.summary)
        assertNotNull(allMusic.thumb)
        assertTrue(allMusic.thumb!!.contains("composite"))
    }

    @Test
    fun playlistTracksPaginatesLargeAllMusicPlaylistCleanly() = runBlocking {
        val totalTracks = 1205
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (url.encodedPath == "/playlists/105/items") {
                    val start = url.queryParameter("X-Plex-Container-Start")?.toIntOrNull() ?: 0
                    val size = url.queryParameter("X-Plex-Container-Size")?.toIntOrNull() ?: totalTracks
                    val end = (start + size).coerceAtMost(totalTracks)
                    val arr = JSONArray()
                    for (i in start until end) {
                        arr.put(
                            JSONObject()
                                .put("ratingKey", "${10000 + i}")
                                .put("type", "track")
                                .put("title", "Song $i")
                                .put("grandparentTitle", "Artist")
                                .put("parentTitle", "Album")
                                .put("duration", 180_000L)
                                .put(
                                    "Media",
                                    JSONArray().put(
                                        JSONObject().put(
                                            "Part",
                                            JSONArray().put(
                                                JSONObject()
                                                    .put("key", "/library/parts/${10000 + i}/file.flac")
                                                    .put("container", "flac")
                                                    .put("size", 25_000_000L),
                                            ),
                                        ),
                                    ),
                                ),
                        )
                    }
                    return jsonResp(
                        JSONObject()
                            .put("totalSize", totalTracks)
                            .put("offset", start)
                            .put("size", arr.length())
                            .put("Metadata", arr),
                    )
                }
                return MockResponse().setResponseCode(404).addHeader("X-Plex-Protocol", "1.0")
            }
        }

        val baseUrl = server.url("/").toString().trimEnd('/')
        val srv = Server(
            id = "plex-test-2",
            type = ServerType.PLEX,
            name = "Plex",
            baseUrl = baseUrl,
            extra = PlexClient.manual(baseUrl, "test-token"),
        )
        val client = PlexClient(srv)
        val tracks = client.playlistTracks("105")
        assertEquals(totalTracks, tracks.size)
        assertEquals("Song 0", tracks.first().title)
        assertEquals("Song 1204", tracks.last().title)
    }

    private fun playlistJson(
        rk: String,
        title: String,
        smart: Boolean,
        leafCount: Int,
        summary: String,
    ): JSONObject = JSONObject()
        .put("ratingKey", rk)
        .put("type", "playlist")
        .put("playlistType", "audio")
        .put("title", title)
        .put("smart", smart)
        .put("leafCount", leafCount)
        .put("summary", summary)

    private fun jsonResp(container: JSONObject): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .addHeader("X-Plex-Protocol", "1.0")
        .setBody(JSONObject().put("MediaContainer", container).toString())
}
