package com.aisub.player.desktop

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import com.aisub.player.asr.RelayTimelinePlayer
import com.aisub.player.live.LiveAnchors
import com.aisub.player.live.LiveTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DesktopLiveRelayTest {

    private fun buildPatPacket(pmtPid: Int = 0x1000): ByteArray {
        val pkt = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = 0x40 // PUSI = 1, PID = 0x0000
        pkt[2] = 0x00
        pkt[3] = 0x10 // payload only
        pkt[4] = 0x00 // pointer_field = 0
        // PAT section
        pkt[5] = 0x00 // table_id = 0
        pkt[6] = 0xB0.toByte()
        pkt[7] = 0x0D // section_length = 13 (5 header + 4 entry + 4 CRC)
        pkt[8] = 0x00; pkt[9] = 0x01 // transport_stream_id
        pkt[10] = 0xC1.toByte()
        pkt[11] = 0x00; pkt[12] = 0x00
        // program 1 -> pmtPid
        pkt[13] = 0x00; pkt[14] = 0x01
        pkt[15] = (0xE0 or ((pmtPid ushr 8) and 0x1F)).toByte()
        pkt[16] = (pmtPid and 0xFF).toByte()
        return pkt
    }

    private fun buildPmtPacket(pmtPid: Int = 0x1000, videoPid: Int = 0x0100, audioPid: Int = 0x0101): ByteArray {
        val pkt = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = (0x40 or ((pmtPid ushr 8) and 0x1F)).toByte()
        pkt[2] = (pmtPid and 0xFF).toByte()
        pkt[3] = 0x10
        pkt[4] = 0x00 // pointer_field
        pkt[5] = 0x02 // table_id = PMT
        pkt[6] = 0xB0.toByte()
        pkt[7] = 0x17 // section_length = 23 (9 header + 5 video + 5 audio + 4 CRC)
        pkt[8] = 0x00; pkt[9] = 0x01
        pkt[10] = 0xC1.toByte()
        pkt[11] = 0x00; pkt[12] = 0x00
        pkt[13] = (0xE0 or ((videoPid ushr 8) and 0x1F)).toByte()
        pkt[14] = (videoPid and 0xFF).toByte()
        pkt[15] = 0xF0.toByte(); pkt[16] = 0x00 // program_info_length = 0
        // video H.264 (0x1B)
        pkt[17] = 0x1B
        pkt[18] = (0xE0 or ((videoPid ushr 8) and 0x1F)).toByte()
        pkt[19] = (videoPid and 0xFF).toByte()
        pkt[20] = 0xF0.toByte(); pkt[21] = 0x00
        // audio AAC (0x0F)
        pkt[22] = 0x0F
        pkt[23] = (0xE0 or ((audioPid ushr 8) and 0x1F)).toByte()
        pkt[24] = (audioPid and 0xFF).toByte()
        pkt[25] = 0xF0.toByte(); pkt[26] = 0x00
        return pkt
    }

    private fun buildVideoPesPacket(pid: Int, ptsMs: Long, isKeyframe: Boolean): ByteArray {
        val pkt = ByteArray(TS_PACKET_SIZE) { 0x00 }
        pkt[0] = 0x47
        pkt[1] = (0x40 or ((pid ushr 8) and 0x1F)).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        pkt[3] = 0x30 // adaptation + payload
        pkt[4] = 0x01 // adaptation_field_length = 1
        pkt[5] = if (isKeyframe) 0x40 else 0x00 // random_access_indicator

        val off = 6
        pkt[off] = 0x00; pkt[off + 1] = 0x00; pkt[off + 2] = 0x01
        pkt[off + 3] = 0xE0.toByte() // video stream_id
        pkt[off + 4] = 0x00; pkt[off + 5] = 0x00
        pkt[off + 6] = 0x80.toByte()
        pkt[off + 7] = 0x80.toByte() // PTS present
        pkt[off + 8] = 0x05 // header_data_length = 5

        val ticks = ptsMs * 90L
        pkt[off + 9] = (0x21L or (((ticks ushr 30) and 0x07L) shl 1)).toByte()
        pkt[off + 10] = ((ticks ushr 22) and 0xFFL).toByte()
        pkt[off + 11] = (0x01L or (((ticks ushr 15) and 0x7FL) shl 1)).toByte()
        pkt[off + 12] = ((ticks ushr 7) and 0xFFL).toByte()
        pkt[off + 13] = (0x01L or ((ticks and 0x7FL) shl 1)).toByte()

        // Annex-B start code + NAL
        pkt[off + 14] = 0x00; pkt[off + 15] = 0x00; pkt[off + 16] = 0x00; pkt[off + 17] = 0x01
        pkt[off + 18] = if (isKeyframe) 0x65 else 0x41
        return pkt
    }

    @Test
    fun chunkerParsesUnalignedTsAndKeepsMonotonicTimelineAcrossReconnect() {
        val chunks = ArrayList<TsChunk>()
        var gotPat = false
        var gotPmt = false
        val chunker = MpegTsChunker(
            minKeyframeCutMs = 400L,
            maxChunkDurationMs = 900L,
            onPat = { gotPat = true },
            onPmt = { gotPmt = true },
            onChunk = { chunks.add(it) },
        )

        val stream1 = ByteArrayOutputStream()
        stream1.write(buildPatPacket())
        stream1.write(buildPmtPacket())
        // 0ms..2400ms，每 200ms 一帧，每 1000ms 一个关键帧
        for (step in 0..12) {
            val ptsMs = step * 200L
            stream1.write(buildVideoPesPacket(0x0100, ptsMs, isKeyframe = (ptsMs % 1000L == 0L)))
        }
        val raw1 = stream1.toByteArray()

        // 故意按非 188 对齐的 73 字节碎片喂入，验证跨包拼接
        var off = 0
        while (off < raw1.size) {
            val len = minOf(73, raw1.size - off)
            chunker.feed(raw1, off, len, nowWallMs = 1_000L + off)
            off += len
        }
        chunker.flushCurrentChunk()

        assertTrue(gotPat)
        assertTrue(gotPmt)
        assertTrue(chunks.size >= 2)
        val beforeReconnectEnd = chunks.last().endMs
        assertEquals(2_400L, beforeReconnectEnd)

        // 模拟上游断流重连：新 FFmpeg 进程 PTS 从 0ms 重新开始，时间轴必须继续单调递增
        chunker.onReconnect(nowWallMs = 5_000L)
        for (step in 0..5) {
            val ptsMs = step * 200L
            chunker.feed(buildVideoPesPacket(0x0100, ptsMs, isKeyframe = (step == 0)), nowWallMs = 5_000L + ptsMs)
        }
        chunker.flushCurrentChunk()

        for (i in 1 until chunks.size) {
            assertTrue("分片时间轴应严格单调不退", chunks[i].startMs >= chunks[i - 1].startMs)
            assertTrue(chunks[i].endMs > chunks[i].startMs)
        }
        assertTrue(chunks.last().endMs > beforeReconnectEnd + 900L)
    }

    @Test
    fun ringBufferEvictsOldestBeyond120SecondsAndSnapsToKeyframe() {
        val ring = RelayRingBuffer(maxDurationMs = 120_000L)
        ring.updatePat(buildPatPacket())
        ring.updatePmt(buildPmtPacket())

        // 写入 150 秒分片（每片 1 秒，每 2 秒一个关键帧）
        for (sec in 0 until 150) {
            val startMs = sec * 1_000L
            ring.append(
                TsChunk(
                    seq = sec.toLong(),
                    startMs = startMs,
                    endMs = startMs + 1_000L,
                    isKeyframe = (sec % 2 == 0),
                    data = buildVideoPesPacket(0x0100, startMs, isKeyframe = (sec % 2 == 0)),
                ),
            )
        }

        assertEquals(150_000L, ring.latestTimelineMs)
        assertTrue(ring.oldestTimelineMs >= 30_000L)
        assertTrue(ring.latestTimelineMs - ring.oldestTimelineMs <= 120_000L)

        // 请求回退到 85_300ms：应吸附到其前最近的关键帧分片 84_000ms
        var selectedStart = -1L
        val ins = ring.openStream(desiredContentMs = 85_300L, waitFirstChunkMs = 100L) {
            selectedStart = it
        }
        assertNotNull(ins)
        assertEquals(84_000L, selectedStart)

        // 首包应预置 PAT(188) + PMT(188) + 数据包(188)
        val firstRead = ByteArray(TS_PACKET_SIZE * 3)
        val n = ins!!.read(firstRead)
        assertEquals(TS_PACKET_SIZE * 2, n) // 先读出预置的 PAT + PMT
        assertEquals(0x47, firstRead[0].toInt() and 0xFF)
        val n2 = ins.read(firstRead)
        assertEquals(TS_PACKET_SIZE, n2)
        ins.close()
    }

    @Test
    fun liveTimelineMatchesAcrossSlidingWindowEvictionAndDelayLiveBackwardSeek() {
        val item = MediaItem.Builder().setUri("http://example.com/live.flv").setTag("live").build()
        // 识别解码器从直播中继的 40_000ms 处开始解码音频
        val decoderStartContentMs = 40_000L
        var latestRelayMs = 60_000L
        val decoderPlayer = RelayTimelinePlayer(
            mediaItemRef = item,
            windowStartUtcMs = DesktopLiveRelay.EPOCH_MS + decoderStartContentMs,
            latestTimelineMsProvider = { latestRelayMs },
            streamStartContentMs = decoderStartContentMs,
        )

        val anchors = LiveAnchors()
        anchors.newSegment(0L)
        decoderPlayer.emittedMs = 5_000L // 已解码到中继内容 45_000ms
        val keyAt5s = LiveTimeline.keyOf(decoderPlayer)
        assertNotNull(keyAt5s)
        anchors.add(5_000L, keyAt5s!!)

        // 识别出音频 8_200ms 处的一句台词（对应中继内容时间轴 48_200ms）
        val cueKey = anchors.keyAt(8_200L)
        assertNotNull(cueKey)

        // 模拟主画面播放器：此时环形缓冲区已淘汰到 oldest = 25_000ms，且因 delayLive 回退到了内容 46_500ms 处播放
        var windowOldestMs = 25_000L
        var screenContentMs = 46_500L
        val screenPlayer = object : RelayTimelinePlayer(item, DesktopLiveRelay.EPOCH_MS) {
            override val currentPosition: Long get() = screenContentMs - windowOldestMs
            override val currentTimeline: Timeline
                get() = object : Timeline() {
                    override val windowCount: Int get() = 1
                    override fun getWindow(windowIndex: Int, window: Window): Window {
                        window.windowStartTimeMs = DesktopLiveRelay.EPOCH_MS + windowOldestMs
                        window.positionInFirstPeriodUs = windowOldestMs * 1_000L
                        window.isSeekable = true
                        window.isDynamic = true
                        return window
                    }
                }
        }

        // 字幕在画面周期坐标上的落点必须精确等于 48_200ms
        val mappedCueStart = LiveTimeline.positionOf(cueKey!!, screenPlayer)
        assertEquals(48_200L, mappedCueStart)

        // 当画面继续播到 48_200ms、同时环形缓冲区又淘汰到了 oldest = 32_000ms 时，periodPosition 仍精确对齐 48_200ms
        windowOldestMs = 32_000L
        screenContentMs = 48_200L
        latestRelayMs = 152_000L
        assertEquals(48_200L, LiveTimeline.periodPosition(screenPlayer))
        assertEquals(mappedCueStart, LiveTimeline.periodPosition(screenPlayer))
    }
}
