package com.aisub.player.live

import com.aisub.player.subtitle.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class LiveStreamRebaserTest {

    private fun buildTsPacketWithPcrAndPes(
        pcr90k: Long,
        pts90k: Long,
        dts90k: Long? = null,
    ): ByteArray {
        val pkt = ByteArray(188)
        pkt[0] = 0x47
        pkt[1] = 0x41 // PUSI = 1, PID = 0x0101
        pkt[2] = 0x01
        pkt[3] = 0x30 // adaptation_field + payload (afc = 3)
        pkt[4] = 7 // adaptation_field_length
        pkt[5] = 0x10 // PCR_flag = 1
        LiveStreamRebaser.TsRebaser.writePcrBase(pkt, 6, pcr90k)

        val pes = 12 // 4 + 1 + 7
        pkt[pes] = 0x00
        pkt[pes + 1] = 0x00
        pkt[pes + 2] = 0x01
        pkt[pes + 3] = 0xE0.toByte() // video stream
        pkt[pes + 4] = 0x00
        pkt[pes + 5] = 0x20
        pkt[pes + 6] = 0x80.toByte()
        if (dts90k != null) {
            pkt[pes + 7] = 0xC0.toByte() // PTS + DTS
            pkt[pes + 8] = 10
            pkt[pes + 9] = 0x30
            LiveStreamRebaser.TsRebaser.writePesPts(pkt, pes + 9, pts90k)
            pkt[pes + 14] = 0x10
            LiveStreamRebaser.TsRebaser.writePesPts(pkt, pes + 14, dts90k)
        } else {
            pkt[pes + 7] = 0x80.toByte() // PTS only
            pkt[pes + 8] = 5
            pkt[pes + 9] = 0x20
            LiveStreamRebaser.TsRebaser.writePesPts(pkt, pes + 9, pts90k)
        }
        return pkt
    }

    @Test
    fun tsRebaserShiftsHoursOldBroadcastClockToZeroOrigin() {
        val rebaser = LiveStreamRebaser.TsRebaser()
        // 模拟编码器已连续运行 5 小时（5 * 3600 * 90000 = 1,620,000,000 ticks）
        val baseTicks = 5L * 3600L * 90_000L
        val pkt1 = buildTsPacketWithPcrAndPes(
            pcr90k = baseTicks,
            pts90k = baseTicks + 3_600L, // +40ms B-frame CTS
            dts90k = baseTicks,
        )
        val pkt2 = buildTsPacketWithPcrAndPes(
            pcr90k = baseTicks + 90_000L, // +1.0s
            pts90k = baseTicks + 93_600L,
            dts90k = baseTicks + 90_000L,
        )

        // 前面故意拼 13 字节脏数据，验证流式 0x47 同步对齐与任意长度分块输入
        val garbage = ByteArray(13) { 0x12 }
        val stream = garbage + pkt1 + pkt2
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < stream.size) {
            val step = minOf(73, stream.size - offset)
            val chunk = stream.copyOfRange(offset, offset + step)
            rebaser.processChunk(chunk, chunk.size, out)
            offset += step
        }

        val result = out.toByteArray()
        assertEquals(376, result.size)
        assertEquals(0x47.toByte(), result[0])
        assertEquals(0x47.toByte(), result[188])

        // 首个包带 100ms (9000 ticks) 安全余量归零，PTS-DTS 差值严格保持 3600 ticks (40ms)
        val pcr1 = LiveStreamRebaser.TsRebaser.readPcrBase(result, 6)
        val pts1 = LiveStreamRebaser.TsRebaser.readPesPts(result, 12 + 9)
        val dts1 = LiveStreamRebaser.TsRebaser.readPesPts(result, 12 + 14)
        assertEquals(9_000L, pcr1)
        assertEquals(9_000L, dts1)
        assertEquals(12_600L, pts1)

        // 第二个包（+1 秒后）精确落在 99000 ticks (1.1s)
        val pcr2 = LiveStreamRebaser.TsRebaser.readPcrBase(result, 188 + 6)
        val pts2 = LiveStreamRebaser.TsRebaser.readPesPts(result, 188 + 12 + 9)
        val dts2 = LiveStreamRebaser.TsRebaser.readPesPts(result, 188 + 12 + 14)
        assertEquals(99_000L, pcr2)
        assertEquals(99_000L, dts2)
        assertEquals(102_600L, pts2)
    }

    private fun buildMoofSegment(trackId: Int, decodeTime64: Long): ByteArray {
        // tfhd: size=16, 'tfhd', version/flags=0, track_ID
        val tfhd = ByteBuffer.allocate(16)
            .putInt(16)
            .put("tfhd".toByteArray())
            .putInt(0)
            .putInt(trackId)
            .array()
        // tfdt v1: size=20, 'tfdt', version=1 (0x01000000), baseMediaDecodeTime (64-bit)
        val tfdt = ByteBuffer.allocate(20)
            .putInt(20)
            .put("tfdt".toByteArray())
            .putInt(0x01000000)
            .putLong(decodeTime64)
            .array()
        val trafSize = 8 + tfhd.size + tfdt.size
        val moofSize = 8 + trafSize
        return ByteBuffer.allocate(moofSize)
            .putInt(moofSize)
            .put("moof".toByteArray())
            .putInt(trafSize)
            .put("traf".toByteArray())
            .put(tfhd)
            .put(tfdt)
            .array()
    }

    @Test
    fun fmp4RebaserZeroesTfdtAcrossSegments() {
        val seg1 = buildMoofSegment(trackId = 1, decodeTime64 = 987_654_000L)
        val seg2 = buildMoofSegment(trackId = 1, decodeTime64 = 987_834_000L) // +180_000 ticks
        assertTrue(LiveStreamRebaser.looksLikeMp4Segment(seg1))
        assertFalse(LiveStreamRebaser.looksLikeMp4Segment(ByteArray(188) { 0x47 }))

        val rebaser = LiveStreamRebaser.Fmp4Rebaser()
        val out1 = rebaser.processSegment(seg1)
        val out2 = rebaser.processSegment(seg2)

        // tfdt v1 64-bit baseMediaDecodeTime 位于偏移 8(moof) + 8(traf) + 16(tfhd) + 12(tfdt header+v/f) = 44
        val rebased1 = ByteBuffer.wrap(out1, 44, 8).long
        val rebased2 = ByteBuffer.wrap(out2, 44, 8).long
        assertEquals(0L, rebased1)
        assertEquals(180_000L, rebased2)
    }

    private fun buildFlvTag(tagType: Int, timestampMs: Long, payload: ByteArray): ByteArray {
        val total = 11 + payload.size + 4
        val buf = ByteArray(total)
        buf[0] = tagType.toByte()
        buf[1] = ((payload.size ushr 16) and 0xFF).toByte()
        buf[2] = ((payload.size ushr 8) and 0xFF).toByte()
        buf[3] = (payload.size and 0xFF).toByte()
        buf[4] = ((timestampMs ushr 16) and 0xFF).toByte()
        buf[5] = ((timestampMs ushr 8) and 0xFF).toByte()
        buf[6] = (timestampMs and 0xFF).toByte()
        buf[7] = ((timestampMs ushr 24) and 0xFF).toByte()
        System.arraycopy(payload, 0, buf, 11, payload.size)
        val prevSize = 11 + payload.size
        buf[total - 4] = ((prevSize ushr 24) and 0xFF).toByte()
        buf[total - 3] = ((prevSize ushr 16) and 0xFF).toByte()
        buf[total - 2] = ((prevSize ushr 8) and 0xFF).toByte()
        buf[total - 1] = (prevSize and 0xFF).toByte()
        return buf
    }

    private fun readFlvTagTimestamp(buf: ByteArray, tagOffset: Int): Long =
        ((buf[tagOffset + 4].toLong() and 0xFF) shl 16) or
            ((buf[tagOffset + 5].toLong() and 0xFF) shl 8) or
            (buf[tagOffset + 6].toLong() and 0xFF) or
            ((buf[tagOffset + 7].toLong() and 0xFF) shl 24)

    @Test
    fun flvRebaserPreservesSequenceHeaderZeroAndRebasesMediaFrames() {
        val flvHeader = byteArrayOf(
            'F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 0x01, 0x05,
            0x00, 0x00, 0x00, 0x09,
            0x00, 0x00, 0x00, 0x00, // PreviousTagSize0
        )
        // AVC Sequence Header (0x17, 0x00)
        val seqTag = buildFlvTag(9, timestampMs = 0L, payload = byteArrayOf(0x17, 0x00, 0x01, 0x02))
        // 直播已播 3600 秒后的关键帧与下一帧
        val frame1 = buildFlvTag(9, timestampMs = 3_600_500L, payload = byteArrayOf(0x17, 0x01, 0xAA.toByte()))
        val frame2 = buildFlvTag(9, timestampMs = 3_600_540L, payload = byteArrayOf(0x27, 0x01, 0xBB.toByte()))

        val raw = flvHeader + seqTag + frame1 + frame2
        val out = ByteArrayOutputStream()
        val rebaser = LiveStreamRebaser.FlvRebaser()
        rebaser.processChunk(raw, raw.size, out)
        val res = out.toByteArray()

        val seqOff = 13
        val f1Off = seqOff + seqTag.size
        val f2Off = f1Off + frame1.size
        assertEquals(0L, readFlvTagTimestamp(res, seqOff))
        assertEquals(0L, readFlvTagTimestamp(res, f1Off))
        assertEquals(40L, readFlvTagTimestamp(res, f2Off))
    }

    @Test
    fun filterAndRebaseLiveCuesDropsPreClickHistoryAndAnchorsToClickTime() {
        val cues = listOf(
            Cue(1, 10_000L, 14_000L, "点击录屏之前的旧字幕"),
            Cue(2, 24_800L, 27_500L, "点击录屏瞬间正在说的字幕"),
            Cue(3, 28_000L, 31_200L, "录屏期间的新字幕"),
        )
        val rebased = LiveStreamRebaser.filterAndRebaseLiveCues(cues, startCueTimeMs = 25_000L)
        assertEquals(2, rebased.size)
        assertEquals(1, rebased[0].id)
        assertEquals(0L, rebased[0].startMs)
        assertEquals(2_500L, rebased[0].endMs)
        assertEquals("点击录屏瞬间正在说的字幕", rebased[0].text)

        assertEquals(2, rebased[1].id)
        assertEquals(3_000L, rebased[1].startMs)
        assertEquals(6_200L, rebased[1].endMs)
    }
}
