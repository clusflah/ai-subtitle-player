package com.aisub.player.desktop

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val TAG = "Voxtitle-sync"
internal const val TS_PACKET_SIZE = 188

internal data class TsChunk(
    val seq: Long,
    val startMs: Long,
    val endMs: Long,
    val isKeyframe: Boolean,
    val data: ByteArray,
)

/**
 * 将连续的 MPEG-TS 字节流切分为带单调时间轴（毫秒）与关键帧索引的 [TsChunk]，
 * 同时提取最新的 PAT / PMT 表以便播放器或识别解码器从任意分片切入时零等待初始化。
 */
internal class MpegTsChunker(
    private val minKeyframeCutMs: Long = 400L,
    private val maxChunkDurationMs: Long = 900L,
    private val maxChunkBytes: Int = 256 * 1024,
    private val onPat: (ByteArray) -> Unit = {},
    private val onPmt: (ByteArray) -> Unit = {},
    private val onChunk: (TsChunk) -> Unit,
) {
    private val carry = ByteArray(TS_PACKET_SIZE * 2)
    private var carryLen = 0

    private var pmtPid: Int = -1
    private var videoPid: Int = -1
    private var audioPid: Int = -1
    private var videoStreamType: Int = 0

    private var prevRawTicks90k: Long = -1L
    private var lastPacketWallMs: Long = -1L
    private var timelineMs: Long = 0L
    private var latestEmittedEndMs: Long = 0L

    private val currentBuf = ByteArrayOutputStream(64 * 1024)
    private var currentStartMs: Long = -1L
    private var currentHasKeyframe: Boolean = false
    private var nextSeq: Long = 0L

    fun onReconnect(nowWallMs: Long) {
        flushCurrentChunk()
        carryLen = 0
        prevRawTicks90k = -1L
        lastPacketWallMs = nowWallMs
        timelineMs = maxOf(timelineMs, latestEmittedEndMs) + 40L
    }

    fun flushCurrentChunk() {
        if (currentBuf.size() <= 0) return
        val start = if (currentStartMs >= 0L) currentStartMs else timelineMs
        val end = maxOf(timelineMs, start + 40L)
        val chunk = TsChunk(
            seq = nextSeq++,
            startMs = start,
            endMs = end,
            isKeyframe = currentHasKeyframe,
            data = currentBuf.toByteArray(),
        )
        latestEmittedEndMs = end
        currentBuf.reset()
        currentStartMs = -1L
        currentHasKeyframe = false
        onChunk(chunk)
    }

    fun feed(input: ByteArray, offset: Int = 0, length: Int = input.size - offset, nowWallMs: Long = System.currentTimeMillis()) {
        var pos = offset
        val limit = offset + length

        if (carryLen > 0) {
            val need = TS_PACKET_SIZE - carryLen
            if (limit - pos < need) {
                System.arraycopy(input, pos, carry, carryLen, limit - pos)
                carryLen += (limit - pos)
                return
            }
            System.arraycopy(input, pos, carry, carryLen, need)
            if ((carry[0].toInt() and 0xFF) == 0x47) {
                processPacket(carry, 0, nowWallMs)
                pos += need
                carryLen = 0
            } else {
                // 重新寻找 0x47 同步字节
                val total = carryLen + minOf(limit - pos, TS_PACKET_SIZE)
                System.arraycopy(input, pos, carry, carryLen, total - carryLen)
                var syncIdx = -1
                for (i in 1 until total) {
                    if ((carry[i].toInt() and 0xFF) == 0x47) {
                        syncIdx = i
                        break
                    }
                }
                if (syncIdx < 0) {
                    pos += (total - carryLen)
                    carryLen = 0
                } else {
                    val consumedFromInput = maxOf(0, syncIdx - carryLen)
                    val remainingInCarry = total - syncIdx
                    System.arraycopy(carry, syncIdx, carry, 0, remainingInCarry)
                    carryLen = remainingInCarry
                    pos += (total - carryLen - (syncIdx - consumedFromInput).coerceAtLeast(0)).coerceAtLeast(0)
                }
            }
        }

        while (pos + TS_PACKET_SIZE <= limit) {
            if ((input[pos].toInt() and 0xFF) != 0x47) {
                pos++
                continue
            }
            if (pos + TS_PACKET_SIZE < limit && (input[pos + TS_PACKET_SIZE].toInt() and 0xFF) != 0x47) {
                pos++
                continue
            }
            processPacket(input, pos, nowWallMs)
            pos += TS_PACKET_SIZE
        }

        if (pos < limit) {
            carryLen = limit - pos
            System.arraycopy(input, pos, carry, 0, carryLen)
        }
    }

    private fun processPacket(buf: ByteArray, off: Int, nowWallMs: Long) {
        val b1 = buf[off + 1].toInt() and 0xFF
        val b2 = buf[off + 2].toInt() and 0xFF
        val b3 = buf[off + 3].toInt() and 0xFF

        val tei = (b1 and 0x80) != 0
        if (tei) return

        val pusi = (b1 and 0x40) != 0
        val pid = ((b1 and 0x1F) shl 8) or b2
        val afc = (b3 ushr 4) and 0x03

        var payloadOff = off + 4
        var randomAccess = false
        var rawTicks90k = -1L

        if (afc == 2 || afc == 3) {
            val afLen = buf[off + 4].toInt() and 0xFF
            if (afLen > 0 && off + 5 < off + TS_PACKET_SIZE) {
                val flags = buf[off + 5].toInt() and 0xFF
                randomAccess = (flags and 0x40) != 0
                if ((flags and 0x10) != 0 && afLen >= 7 && off + 11 <= off + TS_PACKET_SIZE) {
                    rawTicks90k = readPcrBase90k(buf, off + 6)
                }
            }
            payloadOff = off + 5 + afLen
        }

        val hasPayload = (afc == 1 || afc == 3) && payloadOff < off + TS_PACKET_SIZE

        if (pid == 0 && hasPayload) {
            parsePat(buf, payloadOff, off + TS_PACKET_SIZE, pusi)
            onPat(buf.copyOfRange(off, off + TS_PACKET_SIZE))
        } else if (pid == pmtPid && pid > 0 && hasPayload) {
            parsePmt(buf, payloadOff, off + TS_PACKET_SIZE, pusi)
            onPmt(buf.copyOfRange(off, off + TS_PACKET_SIZE))
        }

        var packetIsKeyframe = false
        if (hasPayload && pusi && (pid == videoPid || pid == audioPid || (videoPid < 0 && audioPid < 0 && pid in 0x0020..0x1FFE))) {
            val pts = readPesPts90k(buf, payloadOff, off + TS_PACKET_SIZE)
            if (pts >= 0L && rawTicks90k < 0L) {
                rawTicks90k = pts
            }
            if (pid == videoPid || (videoPid < 0 && pid != audioPid)) {
                packetIsKeyframe = randomAccess || detectAnnexBKeyframe(buf, payloadOff, off + TS_PACKET_SIZE, videoStreamType)
            } else if (videoPid < 0 && pid == audioPid) {
                packetIsKeyframe = true
            }
        } else if (pid == videoPid && randomAccess) {
            packetIsKeyframe = true
        }

        updateTimeline(rawTicks90k, nowWallMs)

        // 若已积攒至少 minKeyframeCutMs 且新包是关键帧起点，先切出上一块，让关键帧落在下一块开头
        if (currentBuf.size() > 0 && currentStartMs >= 0L) {
            val dur = timelineMs - currentStartMs
            if ((packetIsKeyframe && dur >= minKeyframeCutMs) || dur >= maxChunkDurationMs || currentBuf.size() >= maxChunkBytes) {
                flushCurrentChunk()
            }
        }

        if (currentStartMs < 0L) {
            currentStartMs = timelineMs
        }
        if (packetIsKeyframe) {
            currentHasKeyframe = true
        }
        currentBuf.write(buf, off, TS_PACKET_SIZE)
    }

    private var packetsWithoutPts: Int = 0

    private fun updateTimeline(rawTicks90k: Long, nowWallMs: Long) {
        if (rawTicks90k >= 0L) {
            packetsWithoutPts = 0
            if (prevRawTicks90k >= 0L) {
                val wrap = 1L shl 33
                var diffTicks = rawTicks90k - prevRawTicks90k
                if (diffTicks < -(wrap / 2)) diffTicks += wrap
                val diffMs = diffTicks / 90L
                timelineMs += when {
                    diffMs in 0L..8_000L -> diffMs
                    diffMs in -2_000L..-1L -> 0L // B 帧显示序重排导致的微小回退保持单调不退
                    else -> wallStepMs(nowWallMs)
                }
            }
            prevRawTicks90k = rawTicks90k
            lastPacketWallMs = nowWallMs
        } else {
            packetsWithoutPts++
            // 仅当整路流确实不含任何 PTS/PCR（连续超过 128 个包仍未见到时间戳）时才启用挂钟兜底，避免与 PTS 叠加双倍计时
            if (prevRawTicks90k < 0L && packetsWithoutPts > 128 && lastPacketWallMs >= 0L) {
                val wallDiff = nowWallMs - lastPacketWallMs
                if (wallDiff >= 80L) {
                    timelineMs += wallDiff.coerceAtMost(500L)
                    lastPacketWallMs = nowWallMs
                }
            } else if (lastPacketWallMs < 0L) {
                lastPacketWallMs = nowWallMs
            }
        }
    }

    private fun wallStepMs(nowWallMs: Long): Long {
        if (lastPacketWallMs < 0L) return 40L
        return (nowWallMs - lastPacketWallMs).coerceIn(20L, 1_000L)
    }

    private fun parsePat(buf: ByteArray, payloadOff: Int, pktEnd: Int, pusi: Boolean) {
        var idx = payloadOff
        if (pusi) {
            if (idx >= pktEnd) return
            val pointer = buf[idx].toInt() and 0xFF
            idx += 1 + pointer
        }
        if (idx + 8 > pktEnd) return
        val tableId = buf[idx].toInt() and 0xFF
        if (tableId != 0x00) return
        val sectionLen = ((buf[idx + 1].toInt() and 0x0F) shl 8) or (buf[idx + 2].toInt() and 0xFF)
        val end = minOf(pktEnd, idx + 3 + sectionLen - 4)
        var pos = idx + 8
        while (pos + 4 <= end) {
            val programNum = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            val pid = ((buf[pos + 2].toInt() and 0x1F) shl 8) or (buf[pos + 3].toInt() and 0xFF)
            if (programNum != 0 && pid > 0) {
                pmtPid = pid
                return
            }
            pos += 4
        }
    }

    private fun parsePmt(buf: ByteArray, payloadOff: Int, pktEnd: Int, pusi: Boolean) {
        var idx = payloadOff
        if (pusi) {
            if (idx >= pktEnd) return
            val pointer = buf[idx].toInt() and 0xFF
            idx += 1 + pointer
        }
        if (idx + 12 > pktEnd) return
        val tableId = buf[idx].toInt() and 0xFF
        if (tableId != 0x02) return
        val sectionLen = ((buf[idx + 1].toInt() and 0x0F) shl 8) or (buf[idx + 2].toInt() and 0xFF)
        val progInfoLen = ((buf[idx + 10].toInt() and 0x0F) shl 8) or (buf[idx + 11].toInt() and 0xFF)
        val end = minOf(pktEnd, idx + 3 + sectionLen - 4)
        var pos = idx + 12 + progInfoLen
        while (pos + 5 <= end) {
            val streamType = buf[pos].toInt() and 0xFF
            val elemPid = ((buf[pos + 1].toInt() and 0x1F) shl 8) or (buf[pos + 2].toInt() and 0xFF)
            val esInfoLen = ((buf[pos + 3].toInt() and 0x0F) shl 8) or (buf[pos + 4].toInt() and 0xFF)
            when (streamType) {
                0x1B, 0x24, 0x02, 0x01, 0x10, 0x42, 0xD1 -> if (videoPid < 0) {
                    videoPid = elemPid
                    videoStreamType = streamType
                }
                0x0F, 0x11, 0x03, 0x04, 0x81, 0x87, 0x06 -> if (audioPid < 0) {
                    audioPid = elemPid
                }
            }
            pos += 5 + esInfoLen
        }
    }

    companion object {
        internal fun readPcrBase90k(buf: ByteArray, off: Int): Long {
            val b0 = buf[off].toLong() and 0xFFL
            val b1 = buf[off + 1].toLong() and 0xFFL
            val b2 = buf[off + 2].toLong() and 0xFFL
            val b3 = buf[off + 3].toLong() and 0xFFL
            val b4 = buf[off + 4].toLong() and 0xFFL
            return (b0 shl 25) or (b1 shl 17) or (b2 shl 9) or (b3 shl 1) or (b4 ushr 7)
        }

        internal fun readPesPts90k(buf: ByteArray, off: Int, pktEnd: Int): Long {
            if (off + 14 > pktEnd) return -1L
            if ((buf[off].toInt() and 0xFF) != 0x00 ||
                (buf[off + 1].toInt() and 0xFF) != 0x00 ||
                (buf[off + 2].toInt() and 0xFF) != 0x01
            ) return -1L
            val streamId = buf[off + 3].toInt() and 0xFF
            if (streamId < 0xC0 || streamId == 0xBE || streamId == 0xBF) return -1L
            val ptsDtsFlags = (buf[off + 7].toInt() ushr 6) and 0x03
            if ((ptsDtsFlags and 0x02) == 0) return -1L
            val p0 = buf[off + 9].toLong() and 0xFFL
            val p1 = buf[off + 10].toLong() and 0xFFL
            val p2 = buf[off + 11].toLong() and 0xFFL
            val p3 = buf[off + 12].toLong() and 0xFFL
            val p4 = buf[off + 13].toLong() and 0xFFL
            return ((p0 and 0x0EL) shl 29) or
                (p1 shl 22) or
                ((p2 and 0xFEL) shl 14) or
                (p3 shl 7) or
                (p4 ushr 1)
        }

        internal fun detectAnnexBKeyframe(buf: ByteArray, pesOff: Int, pktEnd: Int, streamType: Int): Boolean {
            if (pesOff + 9 > pktEnd) return false
            val headerDataLen = buf[pesOff + 8].toInt() and 0xFF
            var i = pesOff + 9 + headerDataLen
            while (i + 4 < pktEnd) {
                if ((buf[i].toInt() and 0xFF) == 0x00 &&
                    (buf[i + 1].toInt() and 0xFF) == 0x00
                ) {
                    val nalPos = when {
                        (buf[i + 2].toInt() and 0xFF) == 0x01 -> i + 3
                        i + 5 < pktEnd && (buf[i + 2].toInt() and 0xFF) == 0x00 && (buf[i + 3].toInt() and 0xFF) == 0x01 -> i + 4
                        else -> -1
                    }
                    if (nalPos in 0 until pktEnd) {
                        val nalHeader = buf[nalPos].toInt() and 0xFF
                        if (streamType == 0x24) {
                            // HEVC NAL unit type: bits 1..6
                            val hevcType = (nalHeader ushr 1) and 0x3F
                            if (hevcType in 16..21 || hevcType == 32 || hevcType == 33) return true
                        } else {
                            // H.264 NAL unit type: bits 0..4
                            val avcType = nalHeader and 0x1F
                            if (avcType == 5 || avcType == 7) return true
                        }
                        i = nalPos + 1
                        continue
                    }
                }
                i++
            }
            return false
        }
    }
}

/**
 * 线程安全的直播时移环形缓冲区（默认保留最近 120 秒或最多 84MB MPEG-TS 分片）。
 */
internal class RelayRingBuffer(
    private val maxDurationMs: Long = 120_000L,
    private val maxBytes: Long = 84L * 1024L * 1024L,
) {
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val chunks = ArrayDeque<TsChunk>()
    private var totalBytes: Long = 0L
    private var patPacket: ByteArray? = null
    private var pmtPacket: ByteArray? = null
    private var closed: Boolean = false

    @Volatile
    var oldestTimelineMs: Long = 0L
        private set

    @Volatile
    var latestTimelineMs: Long = 0L
        private set

    val chunkCount: Int
        get() = lock.withLock { chunks.size }

    fun updatePat(pkt: ByteArray) = lock.withLock { patPacket = pkt }
    fun updatePmt(pkt: ByteArray) = lock.withLock { pmtPacket = pkt }

    fun append(chunk: TsChunk) = lock.withLock {
        if (closed) return@withLock
        chunks.addLast(chunk)
        totalBytes += chunk.data.size
        latestTimelineMs = maxOf(latestTimelineMs, chunk.endMs)
        while (chunks.size > 4 && (
                (chunks.last().endMs - chunks.first().startMs) > maxDurationMs ||
                    totalBytes > maxBytes
                )
        ) {
            val removed = chunks.removeFirst()
            totalBytes -= removed.data.size
        }
        oldestTimelineMs = chunks.firstOrNull()?.startMs ?: latestTimelineMs
        cond.signalAll()
    }

    fun close() = lock.withLock {
        closed = true
        cond.signalAll()
    }

    fun clearAndClose() = lock.withLock {
        closed = true
        chunks.clear()
        totalBytes = 0L
        cond.signalAll()
    }

    /**
     * 按目标内容时间 [desiredContentMs] 选择最合适的起始分片：
     * - `< 0`：表示切入直播前沿（取最近 2.5 秒内最后一个关键帧分片，兼顾秒开与画面无花屏）；
     * - `>= 0`：表示时移回退到目标毫秒（优先取目标之前 3.5 秒内最近的关键帧分片）。
     */
    internal fun selectStartChunkLocked(desiredContentMs: Long): TsChunk? {
        if (chunks.isEmpty()) return null
        if (desiredContentMs < 0L) {
            val latestEnd = chunks.last().endMs
            val minEdgeStart = latestEnd - 2_500L
            for (i in chunks.size - 1 downTo 0) {
                val c = chunks[i]
                if (c.startMs < minEdgeStart) break
                if (c.isKeyframe) return c
            }
            return chunks.last()
        }
        val clamped = desiredContentMs.coerceIn(chunks.first().startMs, chunks.last().startMs)
        var coveringIdx = 0
        for (i in chunks.indices) {
            val c = chunks[i]
            if (c.startMs <= clamped) coveringIdx = i else break
        }
        val minKeyframeStart = clamped - 3_500L
        for (i in coveringIdx downTo 0) {
            val c = chunks[i]
            if (c.startMs < minKeyframeStart) break
            if (c.isKeyframe) return c
        }
        return chunks[coveringIdx]
    }

    fun openStream(
        desiredContentMs: Long = -1L,
        waitFirstChunkMs: Long = 8_000L,
        onSelectedStartMs: ((Long) -> Unit)? = null,
    ): RelayInputStream? {
        lock.withLock {
            val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitFirstChunkMs)
            while (chunks.isEmpty() && !closed) {
                val rem = deadlineNs - System.nanoTime()
                if (rem <= 0L) break
                cond.awaitNanos(rem)
            }
            val startChunk = selectStartChunkLocked(desiredContentMs) ?: return null
            val preamble = ByteArrayOutputStream(TS_PACKET_SIZE * 2).apply {
                patPacket?.let { write(it) }
                pmtPacket?.let { write(it) }
            }.toByteArray()
            onSelectedStartMs?.invoke(startChunk.startMs)
            return RelayInputStream(startChunk.seq, startChunk.startMs, preamble)
        }
    }

    inner class RelayInputStream internal constructor(
        private var nextSeq: Long,
        val startContentMs: Long,
        preamble: ByteArray,
    ) : InputStream() {
        private var curBytes: ByteArray? = preamble.takeIf { it.isNotEmpty() }
        private var curOffset: Int = 0
        private var streamClosed = false

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else (single[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                val active = curBytes
                if (active != null && curOffset < active.size) {
                    val toCopy = minOf(len, active.size - curOffset)
                    System.arraycopy(active, curOffset, b, off, toCopy)
                    curOffset += toCopy
                    if (curOffset >= active.size) {
                        curBytes = null
                        curOffset = 0
                    }
                    return toCopy
                }

                val nextChunk = awaitNextChunk() ?: return -1
                curBytes = nextChunk.data
                curOffset = 0
            }
        }

        private fun awaitNextChunk(): TsChunk? {
            lock.lock()
            try {
                while (true) {
                    if (streamClosed) return null
                    if (chunks.isNotEmpty()) {
                        val firstSeq = chunks.first().seq
                        if (nextSeq < firstSeq) {
                            nextSeq = firstSeq
                        }
                        val idx = (nextSeq - firstSeq).toInt()
                        if (idx in 0 until chunks.size) {
                            val found = chunks[idx]
                            nextSeq = found.seq + 1
                            return found
                        }
                    }
                    if (closed) return null
                    cond.await(500, TimeUnit.MILLISECONDS)
                }
            } finally {
                lock.unlock()
            }
        }

        override fun close() {
            lock.withLock {
                streamClosed = true
                cond.signalAll()
            }
        }
    }
}

/**
 * Windows 桌面直播单连接时移中继引擎：
 * 1. 对同一路直播源仅建立单路 FFmpeg 上游拉流，避免播放器与识别解码器双连接被源站限流或时间轴错位；
 * 2. 在内存环形缓冲区维护最近 120 秒 MPEG-TS 切片与单调时钟；
 * 3. 同时向 [DesktopExoPlayer]（本地 HTTP `.ts` 流，支持 `delayLive` 往回跳秒）与 `ExoAudioDecoder`（零拷贝内存流）供数，
 *    使桌面端原封不动复用 `MainViewModel.runLive` / `LiveTimeline` / `delayLive` 的全部音画对齐算法。
 */
object DesktopLiveRelay {
    /** 合成 UTC 基准时间（2023-11-14T22:13:20Z），让 [com.aisub.player.live.LiveTimeline] 走精确的毫秒级 UTC 映射分支 */
    const val EPOCH_MS: Long = 1_700_000_000_000L
    private const val IDLE_GRACE_MS: Long = 8_000L
    private const val MAX_SESSIONS: Int = 3

    private val sessionsByUrl = ConcurrentHashMap<String, RelaySession>()
    private val sessionsById = ConcurrentHashMap<String, RelaySession>()
    private val nextSessionNum = AtomicInteger(1)
    private val nextConnNum = AtomicLong(1L)

    private val pullerPool = Executors.newCachedThreadPool { r ->
        Thread(r, "Voxtitle-LiveRelayPull").apply { isDaemon = true }
    }
    private val httpPool = Executors.newCachedThreadPool { r ->
        Thread(r, "Voxtitle-LiveRelayHttp").apply { isDaemon = true }
    }
    private val cleanupScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Voxtitle-LiveRelayGc").apply { isDaemon = true }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    private fun ensureHttpServerPort(): Int {
        serverSocket?.let { if (!it.isClosed) return it.localPort }
        synchronized(this) {
            serverSocket?.let { if (!it.isClosed) return it.localPort }
            val srv = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            serverSocket = srv
            Thread({
                while (!srv.isClosed) {
                    val client = runCatching { srv.accept() }.getOrNull() ?: break
                    httpPool.execute { handleHttpClient(client) }
                }
            }, "Voxtitle-LiveRelayAccept").apply {
                isDaemon = true
                start()
            }
            return srv.localPort
        }
    }

    fun shouldRelay(uri: Uri, item: MediaItem?): Boolean {
        val raw = uri.toString()
        if (raw.startsWith("http://127.0.0.1:") || raw.startsWith("http://localhost:")) return false
        if (item?.localConfiguration?.tag == "live") return true
        if (item != null && item.liveConfiguration.targetOffsetMs != C.TIME_UNSET) return true
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme == "rtmp" || scheme == "rtmps" || scheme == "rtsp") return true
        if (scheme != "http" && scheme != "https") return false
        val pathLower = uri.path?.lowercase().orEmpty()
        return item?.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8 ||
            pathLower.endsWith(".flv") ||
            pathLower.contains("/live/")
    }

    fun acquire(playableUrl: String, headers: Map<String, String>): RelaySession? {
        if (playableUrl.startsWith("http://127.0.0.1:") || playableUrl.startsWith("http://localhost:")) return null
        val ffmpeg = DesktopBinaryFinder.ensureFfmpeg() ?: return null
        ensureHttpServerPort()

        synchronized(this) {
            sessionsByUrl[playableUrl]?.let { existing ->
                if (!existing.isStopped) {
                    existing.retain()
                    return existing
                }
            }
            // 若已有超过上限且引用为 0 的闲置会话，立即清理最旧的
            if (sessionsByUrl.size >= MAX_SESSIONS) {
                sessionsByUrl.values
                    .filter { it.refCount <= 0 }
                    .minByOrNull { it.createdAtMs }
                    ?.stopNow()
            }
            val id = "s${nextSessionNum.getAndIncrement()}"
            val created = RelaySession(id, playableUrl, HashMap(headers), ffmpeg)
            created.retain()
            sessionsByUrl[playableUrl] = created
            sessionsById[id] = created
            created.startPulling()
            return created
        }
    }

    fun release(session: RelaySession?) {
        session?.releaseRef()
    }

    fun newConnId(): Long = nextConnNum.getAndIncrement()

    class RelaySession internal constructor(
        val id: String,
        val playableUrl: String,
        private val headers: Map<String, String>,
        private val ffmpegExe: String,
    ) {
        val createdAtMs: Long = System.currentTimeMillis()
        internal val ring = RelayRingBuffer()
        private val refs = AtomicInteger(0)
        private val stopped = AtomicBoolean(false)
        private val failed = AtomicBoolean(false)
        private val firstChunkLatch = CountDownLatch(1)
        private val connActualStartMs = ConcurrentHashMap<Long, Long>()

        @Volatile
        private var currentProc: Process? = null

        @Volatile
        private var pendingStopFuture: ScheduledFuture<*>? = null

        val refCount: Int get() = refs.get()
        val isStopped: Boolean get() = stopped.get()
        val isFailed: Boolean get() = failed.get()
        val oldestTimelineMs: Long get() = ring.oldestTimelineMs
        val latestTimelineMs: Long get() = ring.latestTimelineMs

        internal fun retain() {
            pendingStopFuture?.cancel(false)
            pendingStopFuture = null
            refs.incrementAndGet()
        }

        internal fun releaseRef() {
            val left = refs.decrementAndGet()
            if (left <= 0) {
                refs.set(0)
                pendingStopFuture?.cancel(false)
                pendingStopFuture = cleanupScheduler.schedule({
                    if (refs.get() <= 0) {
                        stopNow()
                    }
                }, IDLE_GRACE_MS, TimeUnit.MILLISECONDS)
            }
        }

        fun buildPlaybackUrl(desiredStartContentMs: Long = -1L, connId: Long = newConnId()): Pair<String, Long> {
            val port = ensureHttpServerPort()
            return "http://127.0.0.1:$port/live/$id.ts?startMs=$desiredStartContentMs&conn=$connId" to connId
        }

        internal fun recordConnectionStart(connId: Long, actualStartMs: Long) {
            if (connId > 0L) {
                connActualStartMs[connId] = actualStartMs
                while (connActualStartMs.size > 32) {
                    val firstKey = connActualStartMs.keys().let { if (it.hasMoreElements()) it.nextElement() else null } ?: break
                    connActualStartMs.remove(firstKey)
                }
            }
        }

        fun actualStartMsForConnection(connId: Long): Long? = connActualStartMs[connId]

        fun awaitFirstChunk(timeoutMs: Long): Boolean {
            firstChunkLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return !failed.get() && ring.chunkCount > 0
        }

        internal fun openAsrStream(leadBackMs: Long = 1_000L, waitMs: Long = 8_000L): RelayRingBuffer.RelayInputStream? {
            if (!awaitFirstChunk(waitMs)) return null
            val desired = (latestTimelineMs - leadBackMs).coerceAtLeast(oldestTimelineMs)
            return ring.openStream(desiredContentMs = desired, waitFirstChunkMs = waitMs)
        }

        internal fun startPulling() {
            pullerPool.execute {
                val chunker = MpegTsChunker(
                    onPat = { ring.updatePat(it) },
                    onPmt = { ring.updatePmt(it) },
                    onChunk = { chunk ->
                        ring.append(chunk)
                        firstChunkLatch.countDown()
                    },
                )
                var attempts = 0
                var everProduced = false
                val buf = ByteArray(TS_PACKET_SIZE * 64)

                while (!stopped.get()) {
                    attempts++
                    if (attempts > 1) {
                        chunker.onReconnect(System.currentTimeMillis())
                    }
                    val cmd = buildFfmpegPullCommand()
                    Log.i(TAG, "直播时移中继启动拉流[$id, 第${attempts}次]: $playableUrl")
                    val proc = runCatching { ProcessBuilder(cmd).start() }.getOrElse { e ->
                        Log.w(TAG, "启动 FFmpeg 中继失败[$id]: ${e.message}")
                        failed.set(true)
                        firstChunkLatch.countDown()
                        break
                    }
                    currentProc = proc
                    val errSummary = StringBuilder()
                    Thread({
                        runCatching {
                            proc.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                                synchronized(errSummary) {
                                    if (errSummary.length < 1500) errSummary.appendLine(line)
                                }
                            }
                        }
                    }, "Voxtitle-RelayErr-$id").apply { isDaemon = true; start() }

                    var readAnyBytes = false
                    try {
                        val ins = proc.inputStream
                        while (!stopped.get()) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                readAnyBytes = true
                                chunker.feed(buf, 0, n, System.currentTimeMillis())
                                if (ring.chunkCount > 0) everProduced = true
                            }
                        }
                        chunker.flushCurrentChunk()
                        if (ring.chunkCount > 0) {
                            everProduced = true
                            firstChunkLatch.countDown()
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { proc.destroyForcibly() }
                        currentProc = null
                    }

                    if (stopped.get()) break

                    if (!everProduced && !readAnyBytes && attempts >= 1) {
                        val reason = synchronized(errSummary) { errSummary.lines().lastOrNull { it.isNotBlank() }.orEmpty() }
                        Log.w(TAG, "直播中继首连无数据[$id]，回退直连模式: $reason")
                        failed.set(true)
                        firstChunkLatch.countDown()
                        break
                    }
                    if (!everProduced && attempts >= 2) {
                        failed.set(true)
                        firstChunkLatch.countDown()
                        break
                    }
                    try {
                        Thread.sleep(minOf(800L * attempts, 2_500L))
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                ring.close()
                firstChunkLatch.countDown()
            }
        }

        private fun buildFfmpegPullCommand(): List<String> {
            val cmd = ArrayList<String>()
            cmd += ffmpegExe
            cmd += listOf("-nostdin", "-hide_banner", "-loglevel", "error")
            val isHttp = playableUrl.startsWith("http://", ignoreCase = true) ||
                playableUrl.startsWith("https://", ignoreCase = true)
            if (isHttp) {
                cmd += listOf(
                    "-reconnect", "1",
                    "-reconnect_streamed", "1",
                    "-reconnect_at_eof", "1",
                    "-reconnect_delay_max", "2",
                    "-rw_timeout", "15000000",
                )
                if (headers.isNotEmpty()) {
                    val hdr = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
                    cmd += listOf("-headers", hdr)
                }
            }
            cmd += listOf(
                "-fflags", "+genpts+discardcorrupt",
                "-i", playableUrl,
                "-map", "0:v:0?",
                "-map", "0:a:0?",
                "-c", "copy",
                "-mpegts_copyts", "0",
                "-f", "mpegts",
                "pipe:1",
            )
            return cmd
        }

        internal fun stopNow() {
            if (!stopped.compareAndSet(false, true)) return
            pendingStopFuture?.cancel(false)
            sessionsByUrl.remove(playableUrl, this)
            sessionsById.remove(id, this)
            runCatching { currentProc?.destroyForcibly() }
            currentProc = null
            ring.clearAndClose()
            firstChunkLatch.countDown()
            Log.i(TAG, "直播时移中继已释放[$id]")
        }
    }

    private fun handleHttpClient(socket: Socket) {
        socket.use { sock ->
            sock.tcpNoDelay = true
            sock.soTimeout = 15_000
            val reader = sock.getInputStream().bufferedReader(Charsets.US_ASCII)
            val requestLine = runCatching { reader.readLine() }.getOrNull() ?: return
            // 读完请求头
            while (true) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break
                if (line.isEmpty()) break
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val fullTarget = parts[1]
            val path = fullTarget.substringBefore('?')
            val query = fullTarget.substringAfter('?', "")

            if (!path.startsWith("/live/") || !path.endsWith(".ts")) {
                writeHttpError(sock, 404, "Not Found")
                return
            }
            val sessionId = path.removePrefix("/live/").removeSuffix(".ts")
            val session = sessionsById[sessionId]
            if (session == null || session.isStopped) {
                writeHttpError(sock, 404, "Session Gone")
                return
            }

            val params = parseQuery(query)
            val startMs = params["startMs"]?.toLongOrNull() ?: -1L
            val connId = params["conn"]?.toLongOrNull() ?: 0L

            val stream = session.ring.openStream(
                desiredContentMs = startMs,
                waitFirstChunkMs = 10_000L,
                onSelectedStartMs = { actualMs ->
                    session.recordConnectionStart(connId, actualMs)
                    Log.i(TAG, "直播本地流连接[$sessionId, conn=$connId]: 请求起点=${startMs}ms -> 实际关键帧=${actualMs}ms (最新=${session.latestTimelineMs}ms)")
                },
            )
            if (stream == null) {
                writeHttpError(sock, 502, "Upstream Unavailable")
                return
            }

            sock.soTimeout = 0
            val out = sock.getOutputStream()
            val headerBytes = (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp2t\r\n" +
                    "Cache-Control: no-cache, no-store\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            runCatching {
                out.write(headerBytes)
                out.flush()
                stream.use { ins ->
                    val buf = ByteArray(32 * 1024)
                    while (!session.isStopped) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n > 0) {
                            out.write(buf, 0, n)
                            out.flush()
                        }
                    }
                }
            }
        }
    }

    private fun writeHttpError(sock: Socket, code: Int, msg: String) {
        runCatching {
            val resp = "HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            sock.getOutputStream().write(resp.toByteArray(Charsets.US_ASCII))
            sock.getOutputStream().flush()
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                out[k] = v
            }
        }
        return out
    }
}
