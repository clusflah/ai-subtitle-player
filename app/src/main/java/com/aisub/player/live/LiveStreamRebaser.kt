package com.aisub.player.live

import com.aisub.player.subtitle.Cue
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * 直播流无损录制时间戳归零器（零重编码）：
 * 直播流自带编码器运行至今的连续时钟（MPEG-TS 的 33-bit 90kHz PCR/PTS/DTS、fMP4 的 tfdt/sidx、HTTP-FLV 的 Tag Timestamp），
 * 若原样落盘，录出的视频在播放器或系统相册里会显示从几小时前/固定时刻开始或总时长异常。
 * 这里在写入录制文件时按首包时间基准将容器时间戳原地平移到 00:00 起点。
 */
object LiveStreamRebaser {

    /** 过滤掉点击录屏之前的历史直播字幕，并将录屏期间的字幕时间轴以点击时刻为 00:00 归零。 */
    fun filterAndRebaseLiveCues(cues: List<Cue>, startCueTimeMs: Long): List<Cue> {
        if (cues.isEmpty()) return emptyList()
        val anchor = startCueTimeMs.coerceAtLeast(0L)
        val filtered = if (anchor > 0L) cues.filter { it.endMs > anchor + 150L } else cues
        if (filtered.isEmpty()) return emptyList()
        val baseOffset = if (anchor > 0L) anchor else filtered.first().startMs.coerceAtLeast(0L)
        return filtered.mapIndexed { idx, c ->
            val s = (c.startMs - baseOffset).coerceAtLeast(0L)
            val e = (c.endMs - baseOffset).coerceAtLeast(s + 200L)
            c.copy(id = idx + 1, startMs = s, endMs = e)
        }
    }

    /** 判断一段分片字节是否像 ISO BMFF / fMP4（含 ftyp / styp / moov / sidx / moof 盒）。 */
    fun looksLikeMp4Segment(bytes: ByteArray, length: Int = bytes.size): Boolean {
        if (length < 8) return false
        val type = readFourCc(bytes, 4)
        return type == "ftyp" || type == "styp" || type == "moov" || type == "sidx" || type == "moof" || type == "emsg"
    }

    internal fun readFourCc(b: ByteArray, off: Int): String {
        if (off + 4 > b.size) return ""
        return String(
            charArrayOf(
                (b[off].toInt() and 0xFF).toChar(),
                (b[off + 1].toInt() and 0xFF).toChar(),
                (b[off + 2].toInt() and 0xFF).toChar(),
                (b[off + 3].toInt() and 0xFF).toChar(),
            ),
        )
    }

    /**
     * MPEG-TS（188 字节包）流式同步与 PCR / OPCR / PTS / DTS 归零器。
     * 兼容 HLS `.ts` 整段输入及 HTTP-TS 任意长度网络分块输入。
     */
    class TsRebaser {
        private var carry = ByteArray(0)
        private var anchor90k: Long = UNSET

        fun reset() {
            carry = ByteArray(0)
            anchor90k = UNSET
        }

        /** 处理一整块 HLS TS 分片并返回归零后的字节数组（若并非 TS 数据则原样返回）。 */
        fun processSegment(segment: ByteArray): ByteArray {
            if (segment.size < TS_PACKET_SIZE) return segment
            val syncOffset = findSyncOffset(segment, segment.size)
            if (syncOffset < 0) return segment
            val out = ByteArrayOutputStream(segment.size)
            processChunk(segment, segment.size, out)
            return out.toByteArray()
        }

        /** 流式处理网络读取块，自动对齐 0x47 同步字节并输出归零后的 188 字节整数倍包。 */
        fun processChunk(buf: ByteArray, len: Int, out: OutputStream): Int {
            if (len <= 0) return 0
            val combined: ByteArray
            val total: Int
            if (carry.isEmpty()) {
                combined = buf
                total = len
            } else {
                total = carry.size + len
                combined = ByteArray(total)
                System.arraycopy(carry, 0, combined, 0, carry.size)
                System.arraycopy(buf, 0, combined, carry.size, len)
                carry = ByteArray(0)
            }

            var pos = findSyncOffset(combined, total)
            if (pos < 0) {
                // 尚未凑够可确认同步头的字节，保留尾部最多 375 字节等待下一块；若完全不是 TS 则原样透传
                if (total < TS_PACKET_SIZE * 2) {
                    carry = combined.copyOfRange(0, total)
                    return 0
                }
                out.write(combined, 0, total)
                return total
            }

            var written = 0
            while (pos + TS_PACKET_SIZE <= total) {
                if (combined[pos] != SYNC_BYTE) {
                    val nextSync = findSyncOffset(combined, total, pos + 1)
                    if (nextSync < 0) break
                    pos = nextSync
                    continue
                }
                // 若在共享的输入缓冲区上原地修改，先拷出单包或确保安全原地写
                rebaseTsPacketInPlace(combined, pos)
                out.write(combined, pos, TS_PACKET_SIZE)
                written += TS_PACKET_SIZE
                pos += TS_PACKET_SIZE
            }

            if (pos < total) {
                carry = combined.copyOfRange(pos, total)
            }
            return written
        }

        private fun findSyncOffset(data: ByteArray, limit: Int, startFrom: Int = 0): Int {
            val maxSearch = minOf(limit - TS_PACKET_SIZE, startFrom + TS_PACKET_SIZE * 4)
            var i = startFrom
            while (i <= maxSearch) {
                if (data[i] == SYNC_BYTE) {
                    if (i + TS_PACKET_SIZE >= limit || data[i + TS_PACKET_SIZE] == SYNC_BYTE) {
                        return i
                    }
                }
                i++
            }
            return -1
        }

        private fun rebase90k(rawTicks: Long): Long {
            val masked = rawTicks and MAX_PTS_MASK
            if (anchor90k == UNSET) {
                // 预留 100ms (9000 ticks) 安全余量，防止紧随其后的音频包或 B 帧 DTS 略早于首个时间戳时下溢
                anchor90k = (masked - SAFETY_LEAD_90K) and MAX_PTS_MASK
            }
            val forward = (masked - anchor90k) and MAX_PTS_MASK
            // 若某路流起始包比锚点略早（10 秒以内），钳制到 0，绝不回绕成 26.5 小时
            val backward = (anchor90k - masked) and MAX_PTS_MASK
            return if (backward in 1L..MAX_JITTER_BACK_90K) 0L else forward
        }

        private fun rebaseTsPacketInPlace(pkt: ByteArray, p: Int) {
            val b1 = pkt[p + 1].toInt() and 0xFF
            if ((b1 and 0x80) != 0) return // transport_error_indicator
            val pusi = (b1 and 0x40) != 0
            val afc = (pkt[p + 3].toInt() ushr 4) and 0x03

            var payloadOff = 4
            if (afc == 2 || afc == 3) {
                val afLen = pkt[p + 4].toInt() and 0xFF
                payloadOff = 5 + afLen
                if (afLen >= 7 && payloadOff <= TS_PACKET_SIZE) {
                    val flags = pkt[p + 5].toInt() and 0xFF
                    var tsOff = p + 6
                    if ((flags and 0x10) != 0 && tsOff + 6 <= p + payloadOff) {
                        val pcr = readPcrBase(pkt, tsOff)
                        writePcrBase(pkt, tsOff, rebase90k(pcr))
                        tsOff += 6
                    }
                    if ((flags and 0x08) != 0 && tsOff + 6 <= p + payloadOff) {
                        val opcr = readPcrBase(pkt, tsOff)
                        writePcrBase(pkt, tsOff, rebase90k(opcr))
                    }
                }
            }

            if (pusi && (afc == 1 || afc == 3) && payloadOff + 14 <= TS_PACKET_SIZE) {
                val pes = p + payloadOff
                if (pkt[pes].toInt() == 0 && pkt[pes + 1].toInt() == 0 && pkt[pes + 2].toInt() == 1) {
                    val streamId = pkt[pes + 3].toInt() and 0xFF
                    if (hasPesOptionalHeader(streamId) && (pkt[pes + 6].toInt() and 0xC0) == 0x80) {
                        val ptsDtsFlags = (pkt[pes + 7].toInt() ushr 6) and 0x03
                        val hdrLen = pkt[pes + 8].toInt() and 0xFF
                        if (ptsDtsFlags == 3 && hdrLen >= 10 && payloadOff + 19 <= TS_PACKET_SIZE &&
                            hasValidPtsMarkers(pkt, pes + 9) && hasValidPtsMarkers(pkt, pes + 14)
                        ) {
                            // 先读取 DTS 建立基准（DTS <= PTS），保证首帧 PTS-DTS 差值恒定
                            val dts = readPesPts(pkt, pes + 14)
                            val pts = readPesPts(pkt, pes + 9)
                            val newDts = rebase90k(dts)
                            val newPts = rebase90k(pts)
                            writePesPts(pkt, pes + 9, newPts)
                            writePesPts(pkt, pes + 14, newDts)
                        } else if (ptsDtsFlags == 2 && hdrLen >= 5 && payloadOff + 14 <= TS_PACKET_SIZE &&
                            hasValidPtsMarkers(pkt, pes + 9)
                        ) {
                            val pts = readPesPts(pkt, pes + 9)
                            writePesPts(pkt, pes + 9, rebase90k(pts))
                        }
                    }
                }
            }
        }

        private fun hasPesOptionalHeader(streamId: Int): Boolean =
            streamId != 0xBC && streamId != 0xBE && streamId != 0xBF &&
                streamId != 0xF0 && streamId != 0xF1 && streamId != 0xFF &&
                streamId != 0xF2 && streamId != 0xF8

        private fun hasValidPtsMarkers(a: ByteArray, o: Int): Boolean =
            (a[o].toInt() and 0x01) == 1 &&
                (a[o + 2].toInt() and 0x01) == 1 &&
                (a[o + 4].toInt() and 0x01) == 1

        companion object {
            const val TS_PACKET_SIZE = 188
            private const val SYNC_BYTE: Byte = 0x47
            private const val UNSET = Long.MIN_VALUE
            private const val MAX_PTS_MASK = (1L shl 33) - 1L
            private const val SAFETY_LEAD_90K = 9_000L // 100ms
            private const val MAX_JITTER_BACK_90K = 10L * 90_000L // 10s

            internal fun readPcrBase(a: ByteArray, o: Int): Long {
                val b0 = a[o].toLong() and 0xFF
                val b1 = a[o + 1].toLong() and 0xFF
                val b2 = a[o + 2].toLong() and 0xFF
                val b3 = a[o + 3].toLong() and 0xFF
                val b4 = a[o + 4].toLong() and 0xFF
                return (b0 shl 25) or (b1 shl 17) or (b2 shl 9) or (b3 shl 1) or (b4 ushr 7)
            }

            internal fun writePcrBase(a: ByteArray, o: Int, ticks: Long) {
                val v = ticks and MAX_PTS_MASK
                val b4Low = a[o + 4].toInt() and 0x7F
                a[o] = ((v ushr 25) and 0xFF).toByte()
                a[o + 1] = ((v ushr 17) and 0xFF).toByte()
                a[o + 2] = ((v ushr 9) and 0xFF).toByte()
                a[o + 3] = ((v ushr 1) and 0xFF).toByte()
                a[o + 4] = ((((v and 1L) shl 7).toInt()) or b4Low).toByte()
            }

            internal fun readPesPts(a: ByteArray, o: Int): Long {
                val c0 = a[o].toLong() and 0xFF
                val c1 = a[o + 1].toLong() and 0xFF
                val c2 = a[o + 2].toLong() and 0xFF
                val c3 = a[o + 3].toLong() and 0xFF
                val c4 = a[o + 4].toLong() and 0xFF
                return ((c0 and 0x0EL) shl 29) or
                    (c1 shl 22) or
                    ((c2 and 0xFEL) shl 14) or
                    (c3 shl 7) or
                    (c4 ushr 1)
            }

            internal fun writePesPts(a: ByteArray, o: Int, ticks: Long) {
                val v = ticks and MAX_PTS_MASK
                val prefix = a[o].toInt() and 0xF0
                a[o] = (prefix or (((v ushr 29) and 0x0EL).toInt()) or 0x01).toByte()
                a[o + 1] = ((v ushr 22) and 0xFF).toByte()
                a[o + 2] = (((v ushr 14) and 0xFEL).toInt() or 0x01).toByte()
                a[o + 3] = ((v ushr 7) and 0xFF).toByte()
                a[o + 4] = (((v shl 1) and 0xFEL).toInt() or 0x01).toByte()
            }
        }
    }

    /**
     * HLS CMAF / fMP4（`#EXT-X-MAP` + `.m4s`）分片时间戳归零器：
     * 按 `track_ID` 记录首个分片的 `tfdt.baseMediaDecodeTime` 与 `sidx.earliest_presentation_time`，后续分片减去该基准。
     */
    class Fmp4Rebaser {
        private val trackBaseDecodeTime = HashMap<Int, Long>()
        private val sidxBaseTime = HashMap<Int, Long>()

        fun reset() {
            trackBaseDecodeTime.clear()
            sidxBaseTime.clear()
        }

        fun processSegment(segment: ByteArray): ByteArray {
            val copy = segment.copyOf()
            walkBoxes(copy, 0, copy.size)
            return copy
        }

        private fun walkBoxes(b: ByteArray, start: Int, end: Int) {
            var pos = start
            while (pos + 8 <= end) {
                val size32 = readUInt32(b, pos)
                val type = readFourCc(b, pos + 4)
                val headerLen: Int
                val boxEnd: Int
                when (size32) {
                    0L -> {
                        headerLen = 8
                        boxEnd = end
                    }
                    1L -> {
                        if (pos + 16 > end) return
                        val size64 = readInt64(b, pos + 8)
                        if (size64 < 16L || pos + size64 > end) return
                        headerLen = 16
                        boxEnd = (pos + size64).toInt()
                    }
                    else -> {
                        if (size32 < 8L || pos + size32 > end) return
                        headerLen = 8
                        boxEnd = (pos + size32).toInt()
                    }
                }
                val payload = pos + headerLen
                when (type) {
                    "moof" -> processMoof(b, payload, boxEnd)
                    "sidx" -> processSidx(b, payload, boxEnd)
                }
                pos = boxEnd
            }
        }

        private fun processMoof(b: ByteArray, start: Int, end: Int) {
            var pos = start
            while (pos + 8 <= end) {
                val size32 = readUInt32(b, pos)
                val type = readFourCc(b, pos + 4)
                val boxEnd = when (size32) {
                    0L -> end
                    1L -> {
                        if (pos + 16 > end) return
                        val s64 = readInt64(b, pos + 8)
                        if (s64 < 16L || pos + s64 > end) return
                        (pos + s64).toInt()
                    }
                    else -> {
                        if (size32 < 8L || pos + size32 > end) return
                        (pos + size32).toInt()
                    }
                }
                val hdr = if (size32 == 1L) 16 else 8
                if (type == "traf") {
                    processTraf(b, pos + hdr, boxEnd)
                }
                pos = boxEnd
            }
        }

        private fun processTraf(b: ByteArray, start: Int, end: Int) {
            var trackId = 0
            var tfdtPayload = -1
            var tfdtEnd = -1

            var pos = start
            while (pos + 8 <= end) {
                val size32 = readUInt32(b, pos)
                val type = readFourCc(b, pos + 4)
                val boxEnd = when (size32) {
                    0L -> end
                    1L -> {
                        if (pos + 16 > end) break
                        val s64 = readInt64(b, pos + 8)
                        if (s64 < 16L || pos + s64 > end) break
                        (pos + s64).toInt()
                    }
                    else -> {
                        if (size32 < 8L || pos + size32 > end) break
                        (pos + size32).toInt()
                    }
                }
                val payload = pos + if (size32 == 1L) 16 else 8
                if (type == "tfhd" && payload + 8 <= boxEnd) {
                    trackId = readUInt32(b, payload + 4).toInt()
                } else if (type == "tfdt") {
                    tfdtPayload = payload
                    tfdtEnd = boxEnd
                }
                pos = boxEnd
            }

            if (tfdtPayload >= 0 && tfdtPayload + 4 <= tfdtEnd) {
                val version = b[tfdtPayload].toInt() and 0xFF
                if (version == 1 && tfdtPayload + 12 <= tfdtEnd) {
                    val raw = readInt64(b, tfdtPayload + 4)
                    if (raw >= 0L) {
                        val base = trackBaseDecodeTime.getOrPut(trackId) { raw }
                        writeInt64(b, tfdtPayload + 4, (raw - base).coerceAtLeast(0L))
                    }
                } else if (version == 0 && tfdtPayload + 8 <= tfdtEnd) {
                    val raw = readUInt32(b, tfdtPayload + 4)
                    val base = trackBaseDecodeTime.getOrPut(trackId) { raw }
                    writeUInt32(b, tfdtPayload + 4, (raw - base).coerceAtLeast(0L))
                }
            }
        }

        private fun processSidx(b: ByteArray, payload: Int, boxEnd: Int) {
            if (payload + 12 > boxEnd) return
            val version = b[payload].toInt() and 0xFF
            val refId = readUInt32(b, payload + 4).toInt()
            if (version == 0 && payload + 20 <= boxEnd) {
                val ept = readUInt32(b, payload + 12)
                val base = sidxBaseTime.getOrPut(refId) { ept }
                writeUInt32(b, payload + 12, (ept - base).coerceAtLeast(0L))
            } else if (version == 1 && payload + 28 <= boxEnd) {
                val ept = readInt64(b, payload + 12)
                if (ept >= 0L) {
                    val base = sidxBaseTime.getOrPut(refId) { ept }
                    writeInt64(b, payload + 12, (ept - base).coerceAtLeast(0L))
                }
            }
        }

        private fun readUInt32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xFF) shl 24) or
                ((b[o + 1].toLong() and 0xFF) shl 16) or
                ((b[o + 2].toLong() and 0xFF) shl 8) or
                (b[o + 3].toLong() and 0xFF)

        private fun writeUInt32(b: ByteArray, o: Int, v: Long) {
            b[o] = ((v ushr 24) and 0xFF).toByte()
            b[o + 1] = ((v ushr 16) and 0xFF).toByte()
            b[o + 2] = ((v ushr 8) and 0xFF).toByte()
            b[o + 3] = (v and 0xFF).toByte()
        }

        private fun readInt64(b: ByteArray, o: Int): Long =
            (readUInt32(b, o) shl 32) or readUInt32(b, o + 4)

        private fun writeInt64(b: ByteArray, o: Int, v: Long) {
            writeUInt32(b, o, v ushr 32)
            writeUInt32(b, o + 4, v and 0xFFFFFFFFL)
        }
    }

    /**
     * HTTP-FLV 流式时间戳归零器：
     * 保持元数据与音视频解码配置头（Sequence Header）时间戳为 0，首个实际音视频帧时间戳归零，后续帧减去同一基准。
     */
    class FlvRebaser {
        private var state = STATE_HEADER
        private val headerBuf = ByteArray(13)
        private var headerFilled = 0
        private val tagHeader = ByteArray(13) // 11 bytes tag header + 2 bytes payload peek
        private var tagHeaderFilled = 0
        private var remainingTagBytes = 0 // remaining payload + 4 bytes PreviousTagSize
        private var baseMs: Long? = null

        fun processChunk(buf: ByteArray, len: Int, out: OutputStream) {
            var pos = 0
            while (pos < len) {
                when (state) {
                    STATE_PASSTHROUGH -> {
                        out.write(buf, pos, len - pos)
                        return
                    }
                    STATE_HEADER -> {
                        val take = minOf(13 - headerFilled, len - pos)
                        System.arraycopy(buf, pos, headerBuf, headerFilled, take)
                        headerFilled += take
                        pos += take
                        if (headerFilled == 13) {
                            val isFlv = headerBuf[0] == 'F'.code.toByte() &&
                                headerBuf[1] == 'L'.code.toByte() &&
                                headerBuf[2] == 'V'.code.toByte()
                            out.write(headerBuf, 0, 13)
                            state = if (isFlv) STATE_TAG_HEADER else STATE_PASSTHROUGH
                        }
                    }
                    STATE_TAG_HEADER -> {
                        val needBase = minOf(11 - tagHeaderFilled, len - pos)
                        if (needBase > 0) {
                            System.arraycopy(buf, pos, tagHeader, tagHeaderFilled, needBase)
                            tagHeaderFilled += needBase
                            pos += needBase
                        }
                        if (tagHeaderFilled < 11) return
                        val dataSize = ((tagHeader[1].toInt() and 0xFF) shl 16) or
                            ((tagHeader[2].toInt() and 0xFF) shl 8) or
                            (tagHeader[3].toInt() and 0xFF)
                        val peekLen = minOf(2, dataSize)
                        val needPeek = minOf(11 + peekLen - tagHeaderFilled, len - pos)
                        if (needPeek > 0) {
                            System.arraycopy(buf, pos, tagHeader, tagHeaderFilled, needPeek)
                            tagHeaderFilled += needPeek
                            pos += needPeek
                        }
                        if (tagHeaderFilled < 11 + peekLen) return

                        val tagType = tagHeader[0].toInt() and 0xFF
                        val rawTs = (((tagHeader[4].toLong() and 0xFF) shl 16) or
                            ((tagHeader[5].toLong() and 0xFF) shl 8) or
                            (tagHeader[6].toLong() and 0xFF) or
                            ((tagHeader[7].toLong() and 0xFF) shl 24))

                        val isSeqHeader = isSequenceHeader(tagType, peekLen)
                        val newTs = if (tagType == 18 || isSeqHeader) {
                            0L
                        } else {
                            val b = baseMs ?: rawTs.also { baseMs = it }
                            (rawTs - b).coerceAtLeast(0L)
                        }
                        tagHeader[4] = ((newTs ushr 16) and 0xFF).toByte()
                        tagHeader[5] = ((newTs ushr 8) and 0xFF).toByte()
                        tagHeader[6] = (newTs and 0xFF).toByte()
                        tagHeader[7] = ((newTs ushr 24) and 0xFF).toByte()

                        out.write(tagHeader, 0, 11 + peekLen)
                        remainingTagBytes = (dataSize - peekLen) + 4
                        tagHeaderFilled = 0
                        state = if (remainingTagBytes > 0) STATE_TAG_BODY else STATE_TAG_HEADER
                    }
                    STATE_TAG_BODY -> {
                        val take = minOf(remainingTagBytes, len - pos)
                        out.write(buf, pos, take)
                        remainingTagBytes -= take
                        pos += take
                        if (remainingTagBytes == 0) {
                            state = STATE_TAG_HEADER
                        }
                    }
                }
            }
        }

        private fun isSequenceHeader(tagType: Int, peekLen: Int): Boolean {
            if (peekLen < 2) return false
            val d0 = tagHeader[11].toInt() and 0xFF
            val d1 = tagHeader[12].toInt() and 0xFF
            return when (tagType) {
                8 -> ((d0 ushr 4) == 10 || (d0 ushr 4) == 13) && d1 == 0
                9 -> {
                    val isExHeader = (d0 and 0x80) != 0
                    if (isExHeader) (d0 and 0x0F) == 0 // PacketTypeSequenceStart
                    else ((d0 and 0x0F) == 7 || (d0 and 0x0F) == 12 || (d0 and 0x0F) == 13) && d1 == 0
                }
                else -> false
            }
        }

        companion object {
            private const val STATE_HEADER = 0
            private const val STATE_TAG_HEADER = 1
            private const val STATE_TAG_BODY = 2
            private const val STATE_PASSTHROUGH = 3
        }
    }
}
