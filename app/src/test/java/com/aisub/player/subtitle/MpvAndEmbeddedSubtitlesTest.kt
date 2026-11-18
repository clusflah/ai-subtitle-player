package com.aisub.player.subtitle

import androidx.media3.common.util.UnstableApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MpvAndEmbeddedSubtitlesTest {

    @Before
    fun setUp() {
        EmbeddedSubtitles.setDemuxListener(null)
        EmbeddedSubtitles.resetDemuxCache()
    }

    @After
    fun tearDown() {
        EmbeddedSubtitles.setDemuxListener(null)
        EmbeddedSubtitles.resetDemuxCache()
    }

    @Test
    fun parseLanguageFromLabelNormalizesCommonNames() {
        assertEquals("en", EmbeddedSubtitles.parseLanguageFromLabel("Track 1 - [English]"))
        assertEquals("zh", EmbeddedSubtitles.parseLanguageFromLabel("简体中文 (SRT)"))
        assertEquals("fr", EmbeddedSubtitles.parseLanguageFromLabel("Français (Canadian)"))
        assertEquals("pt", EmbeddedSubtitles.parseLanguageFromLabel("Português"))
        assertEquals("ja", EmbeddedSubtitles.parseLanguageFromLabel("日本語"))
        assertNull(EmbeddedSubtitles.parseLanguageFromLabel("Commentary"))
    }

    @Test
    fun recordExternalCueProducesTextTrackAndDeliversEveryCueInBurst() {
        val notifiedSizes = mutableListOf<Int>()
        EmbeddedSubtitles.setDemuxListener { list ->
            val t = list.firstOrNull { it.id == 2 } ?: return@setDemuxListener
            notifiedSizes += t.cues.size
        }

        val samples = listOf(
            900_000L to 902_400L to "Fernando ain't around?",
            902_500L to 904_800L to "He's enjoying carnival.",
            905_000L to 907_100L to "We should wait until evening.",
        )
        for ((times, text) in samples) {
            EmbeddedSubtitles.recordExternalCue(
                trackId = 2,
                language = "en",
                label = "Canadian",
                codec = "application/x-subrip",
                startMs = times.first,
                endMs = times.second,
                lines = listOf(text),
            )
        }

        assertEquals(listOf(1, 2, 3), notifiedSizes)
        val track = EmbeddedSubtitles.demuxedTracks().single { it.id == 2 }
        assertFalse("Live external SRT cues must remain text-based", track.imageBased)
        assertEquals("en", track.language)
        assertEquals("Canadian", track.label)
        assertEquals("application/x-subrip", track.codec)
        assertEquals(3, track.cues.size)
        assertEquals(900_000L, track.cues[0].startMs)
        assertEquals(902_400L, track.cues[0].endMs)
        assertEquals("He's enjoying carnival.", track.cues[1].text)
        assertEquals("We should wait until evening.", track.cues[2].text)
    }

    @Test
    fun mergeTracksCombinesExtractedAndExternalCuesWithoutDuplicates() {
        val extracted = listOf(
            EmbeddedSubtitles.Track(
                id = 2,
                language = "en",
                label = "Canadian",
                codec = "application/x-subrip",
                imageBased = false,
                cues = listOf(
                    SubtitleParser.cueOf(1, 900_000L, 902_400L, listOf("Fernando ain't around?")),
                ),
            ),
        )
        val live = listOf(
            EmbeddedSubtitles.Track(
                id = 2,
                language = "en",
                label = "Canadian",
                codec = "application/x-subrip",
                imageBased = false,
                cues = listOf(
                    SubtitleParser.cueOf(1, 900_000L, 902_400L, listOf("Fernando ain't around?")),
                    SubtitleParser.cueOf(2, 902_500L, 904_800L, listOf("He's enjoying carnival.")),
                ),
            ),
        )

        val merged = EmbeddedSubtitles.mergeTracks(extracted, live)
        assertEquals(1, merged.size)
        assertEquals(2, merged[0].cues.size)
        assertEquals(listOf("Fernando ain't around?", "He's enjoying carnival."), merged[0].cues.map { it.text })
    }
}
