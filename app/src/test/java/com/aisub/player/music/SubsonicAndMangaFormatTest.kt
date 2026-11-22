package com.aisub.player.music

import com.aisub.player.manga.MangaImages
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType
import com.aisub.player.remote.cloud.SubsonicClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubsonicAndMangaFormatTest {

    @Test
    fun sniffImageFormatDetectsMagicBytesAndFallsBackToExtension() {
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1,
        )
        assertEquals("jpg" to "image/jpeg", MangaImages.sniffImageFormat(jpeg))

        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D,
        )
        assertEquals("png" to "image/png", MangaImages.sniffImageFormat(png))

        val webp = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0x24, 0, 0, 0,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
        )
        assertEquals("webp" to "image/webp", MangaImages.sniffImageFormat(webp))

        val avif = byteArrayOf(
            0, 0, 0, 0x1C,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'a'.code.toByte(), 'v'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(),
        )
        assertEquals("avif" to "image/avif", MangaImages.sniffImageFormat(avif))

        assertEquals("png" to "image/png", MangaImages.sniffImageFormat(byteArrayOf(1, 2, 3), "chapter01/003.PNG?token=1"))
    }

    @Test
    fun subsonicResponseParserHandlesOkAndApiErrorCodes() {
        val okJson = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.53.3"}}"""
        val parsed = SubsonicClient.parseSubsonicResponse(okJson)
        assertEquals("navidrome", parsed.optString("type"))

        val errJson = """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}"""
        try {
            SubsonicClient.parseSubsonicResponse(errJson)
            fail("Expected SubsonicApiException")
        } catch (e: SubsonicClient.Companion.SubsonicApiException) {
            assertEquals(40, e.code)
            assertTrue(e.message.orEmpty().contains("Wrong username"))
        }
    }

    @Test
    fun musicSourceStoreRecognizesStructuredServersAndAudioExtensions() {
        assertTrue(MusicSourceStore.isStructuredMusicServer(ServerType.PLEX))
        assertTrue(MusicSourceStore.isStructuredMusicServer(ServerType.SUBSONIC))
        assertTrue(MusicSourceStore.isStructuredMusicServer(ServerType.EMBY))
        assertTrue(MusicSourceStore.isStructuredMusicServer(ServerType.JELLYFIN))
        assertTrue(MusicSourceStore.isAudioFileName("Track 01.FLAC"))
        assertTrue(MusicSourceStore.isAudioFileName("symphony.dsf"))

        val srv = Server(id = "s1", type = ServerType.SUBSONIC, name = "Navidrome", baseUrl = "https://music.example.com")
        val spec = MusicSourceSpec(kind = MusicSourceKind.SERVER, serverId = "s1", label = "Navidrome")
        val (resolved, matched) = MusicSourceStore.resolveEffective(spec, listOf(srv))
        assertEquals("s1", resolved.serverId)
        assertEquals(srv, matched)

        // 服务器被删除后自动安全回退到本机音乐
        val (fallback, none) = MusicSourceStore.resolveEffective(spec, emptyList())
        assertTrue(fallback.isLocal)
        assertEquals(null, none)
    }
}
