package com.aisub.player.live

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveTimelineTest {

    private fun playlist(firstSeq: Long, count: Int, segSec: Int = 6): HlsMediaPlaylist {
        val text = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:$segSec")
            appendLine("#EXT-X-MEDIA-SEQUENCE:$firstSeq")
            repeat(count) { i ->
                appendLine("#EXTINF:$segSec.0,")
                appendLine("seg${firstSeq + i}.ts")
            }
        }
        return HlsPlaylistParser().parse(Uri.parse("http://live.example/a.m3u8"), text.byteInputStream()) as HlsMediaPlaylist
    }

    private fun window(positionInPeriodMs: Long, startTimeMs: Long = C.TIME_UNSET) = Timeline.Window().set(
        Any(), MediaItem.EMPTY, null, C.TIME_UNSET, startTimeMs, C.TIME_UNSET,
        true, true, null, 0, C.TIME_UNSET, 0, 0, positionInPeriodMs * 1000,
    )

    @Test
    fun hlsSequenceMapsBetweenTwoPlayers() {
        // 解码器先加载、播放列表更新：第一个分片是 102；画面那边的列表从 100 开始，窗口已经在周期里往前滑了 30 秒
        val decoder = playlist(firstSeq = 102, count = 5)
        val main = playlist(firstSeq = 100, count = 5)
        val key = LiveTimeline.keyOf(window(0), decoder, positionMs = 20_500)!!
        assertEquals(LiveKey(105, 2_500), key)
        // 105 还没出现在画面那边的列表里（100..104）：接在最后一个分片后面
        assertEquals(30_000 + 30_000 + 2_500L, LiveTimeline.positionOf(key, window(30_000), main))
        // 窗口里有的分片直接查
        assertEquals(30_000 + 18_000 + 1_000L, LiveTimeline.positionOf(LiveKey(103, 1_000), window(30_000), main))
    }

    @Test
    fun keyArithmeticCrossesSegmentBoundary() {
        val main = playlist(firstSeq = 10, count = 4)
        val key = LiveKey(11, 5_000) + 3_000 // 第 11 片之后 8 秒 = 第 12 片的 2 秒处
        assertEquals(12_000 + 2_000L, LiveTimeline.positionOf(key, window(0), main))
    }

    @Test
    fun absoluteTimeUsedWhenAvailable() {
        val key = LiveTimeline.keyOf(window(0, startTimeMs = 1_700_000_000_000), null, positionMs = 4_000)!!
        assertEquals(LiveKey(LiveKey.UTC, 1_700_000_004_000), key)
        assertEquals(500 + 9_000L, LiveTimeline.positionOf(key, window(500, startTimeMs = 1_699_999_995_000), null))
        // 一边有绝对时间、另一边只有分片序号：换算不了
        assertNull(LiveTimeline.positionOf(key, window(0), playlist(1, 3)))
    }

    @Test
    fun anchorsInterpolateWithinSegmentOnly() {
        val anchors = LiveAnchors()
        assertNull(anchors.keyAt(1_000))
        anchors.add(10_000, LiveKey(50, 1_000))
        assertEquals(LiveKey(50, 4_000), anchors.keyAt(13_000))
        assertEquals(LiveKey(50, -1_000), anchors.keyAt(8_000))
        // 重连后的音频不能用重连前的锚点
        anchors.newSegment(20_000)
        assertNull(anchors.keyAt(21_000))
        anchors.add(22_000, LiveKey(80, 0))
        assertEquals(LiveKey(80, 1_000), anchors.keyAt(23_000))
        assertEquals(LiveKey(50, 5_000), anchors.keyAt(14_000))
    }
}
