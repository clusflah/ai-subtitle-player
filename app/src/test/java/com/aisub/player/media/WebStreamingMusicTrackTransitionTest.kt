package com.aisub.player.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebStreamingMusicTrackTransitionTest {

    @Test
    fun detectNaturalTrackEndCatchesExplicitEndedAndEofPause() {
        assertTrue(
            WebStreamingMusicPlayer.detectNaturalTrackEnd(
                snapEnded = true,
                snapPlaying = false,
                snapPositionMs = 286_000L,
                effectiveDurationMs = 286_000L,
                maxPosReachedMs = 285_500L,
                confirmedTitleNorm = WebStreamingMusicPlayer.normalizeTrackTitle("因為你 所以我"),
                expectedTitleNorm = WebStreamingMusicPlayer.normalizeTrackTitle("因為你 所以我"),
                incomingTitleNorm = WebStreamingMusicPlayer.normalizeTrackTitle("因為你 所以我"),
                inSeekHold = false,
                sinceUserSeekMs = 60_000L,
            ),
        )
        // 网页播放器在曲末先 paused 且未发 ended 时，也应直接判定自然播完
        assertTrue(
            WebStreamingMusicPlayer.detectNaturalTrackEnd(
                snapEnded = false,
                snapPlaying = false,
                snapPositionMs = 285_500L,
                effectiveDurationMs = 286_000L,
                maxPosReachedMs = 285_500L,
                confirmedTitleNorm = WebStreamingMusicPlayer.normalizeTrackTitle("因為你 所以我"),
                expectedTitleNorm = WebStreamingMusicPlayer.normalizeTrackTitle("因為你 所以我"),
                incomingTitleNorm = WebStreamingMusicPlayer.normalizeTrackTitle("因為你 所以我"),
                inSeekHold = false,
                sinceUserSeekMs = 60_000L,
            ),
        )
    }

    @Test
    fun detectNaturalTrackEndCatchesWebAutoplayHijackByTitleOrWrapAround() {
        val firstNorm = WebStreamingMusicPlayer.normalizeTrackTitle("因為你 所以我")
        val secondNorm = WebStreamingMusicPlayer.normalizeTrackTitle("突然好想你")

        // YouTube Music / Spotify 网页端私自连播到下一首歌：MediaSession 歌名跳变
        assertTrue(
            WebStreamingMusicPlayer.detectNaturalTrackEnd(
                snapEnded = false,
                snapPlaying = true,
                snapPositionMs = 15_000L,
                effectiveDurationMs = 858_000L,
                maxPosReachedMs = 285_000L,
                confirmedTitleNorm = firstNorm,
                expectedTitleNorm = firstNorm,
                incomingTitleNorm = secondNorm,
                inSeekHold = false,
                sinceUserSeekMs = 60_000L,
            ),
        )

        // 曲末未发 ended 直接进度归零切入下一首
        assertTrue(
            WebStreamingMusicPlayer.detectNaturalTrackEnd(
                snapEnded = false,
                snapPlaying = true,
                snapPositionMs = 800L,
                effectiveDurationMs = 286_000L,
                maxPosReachedMs = 284_000L,
                confirmedTitleNorm = firstNorm,
                expectedTitleNorm = firstNorm,
                incomingTitleNorm = firstNorm,
                inSeekHold = false,
                sinceUserSeekMs = 10_000L,
            ),
        )

        // 用户手动拖回开头（刚 seek 且处于 seek 保护期）绝不误判为自动连播切歌
        assertFalse(
            WebStreamingMusicPlayer.detectNaturalTrackEnd(
                snapEnded = false,
                snapPlaying = true,
                snapPositionMs = 500L,
                effectiveDurationMs = 286_000L,
                maxPosReachedMs = 284_000L,
                confirmedTitleNorm = firstNorm,
                expectedTitleNorm = firstNorm,
                incomingTitleNorm = firstNorm,
                inSeekHold = false,
                sinceUserSeekMs = 1_200L,
            ),
        )
    }

    @Test
    fun detectNaturalTrackEndDoesNotFalseTriggerOnAdTransitionOrTitleSuffix() {
        val expected = WebStreamingMusicPlayer.normalizeTrackTitle("因為你 所以我")
        val adTitle = WebStreamingMusicPlayer.normalizeTrackTitle("Sponsored Ad")
        val officialMv = WebStreamingMusicPlayer.normalizeTrackTitle("因為你 所以我 (Official MV)")

        // 片头广告播完进入目标正曲时，incomingTitle 命中 expectedTitle，不误判切歌
        assertFalse(
            WebStreamingMusicPlayer.detectNaturalTrackEnd(
                snapEnded = false,
                snapPlaying = true,
                snapPositionMs = 1_000L,
                effectiveDurationMs = 286_000L,
                maxPosReachedMs = 15_000L,
                confirmedTitleNorm = adTitle,
                expectedTitleNorm = expected,
                incomingTitleNorm = expected,
                inSeekHold = false,
                sinceUserSeekMs = 60_000L,
            ),
        )

        // 同一首歌带上 (Official MV) 后缀时宽松匹配，不误判切歌
        assertFalse(
            WebStreamingMusicPlayer.detectNaturalTrackEnd(
                snapEnded = false,
                snapPlaying = true,
                snapPositionMs = 60_000L,
                effectiveDurationMs = 286_000L,
                maxPosReachedMs = 60_000L,
                confirmedTitleNorm = expected,
                expectedTitleNorm = expected,
                incomingTitleNorm = officialMv,
                inSeekHold = false,
                sinceUserSeekMs = 60_000L,
            ),
        )
    }

    @Test
    fun nearEndPauseDoesNotFlipPlayWhenReadyToFalse() {
        assertTrue(
            WebStreamingMusicPlayer.resolveExternalPlayWhenReady(
                currentPlayWhenReady = true,
                hasStartedAudio = true,
                snapPlaying = false,
                snapBuffering = false,
                snapEnded = true,
                inSeekHold = false,
                sinceUserPlayMs = 200_000L,
                sinceUserPauseMs = 200_000L,
            ),
        )
    }
}
