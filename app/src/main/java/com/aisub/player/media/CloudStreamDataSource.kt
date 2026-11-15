package com.aisub.player.media

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.aisub.player.remote.cloud.CloudRegistry
import java.io.InterruptedIOException
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 为 ExoPlayer 播放 `cloud://` 网盘地址提供的单连接抢占与轻量内存热缓存数据源。
 *
 * 解决 115 等严格单连接限流网盘在 ExoPlayer 起播、Seek 或读取 MKV 索引（SeekHead / Cues）时
 * 新旧请求并发重叠触发 HTTP 403 / 卡顿退化问题：
 * - 新 Range 请求在打开前立即抢占关闭同一 URI 上的旧上游连接，并在必要时做短退避与直链刷新；
 * - 缓存最近读取的 64KB 数据块（单文件上限 8MB，最多保留 3 个 URI），Seek 跳回文件头时直接内存命中。
 */
@UnstableApi
class CloudStreamDataSource(private val upstreamFactory: DataSource.Factory) : DataSource {

    private val listeners = CopyOnWriteArrayList<TransferListener>()

    @Volatile
    private var passthrough: DataSource? = null
    @Volatile
    private var currentSpec: DataSpec? = null
    @Volatile
    private var uriKey: String = ""
    @Volatile
    private var state: CloudUriState? = null
    @Volatile
    private var mySeq: Long = 0L
    @Volatile
    private var activeHandle: ActiveUpstream? = null
    private var staging: BlockStaging? = null

    private var pos: Long = 0L
    private var limitExclusive: Long = C.LENGTH_UNSET.toLong()
    private var cachedServeUntil: Long = 0L
    @Volatile
    private var responseUri: Uri? = null
    @Volatile
    private var responseHeaders: Map<String, List<String>> = emptyMap()
    @Volatile
    private var opened: Boolean = false
    @Volatile
    private var closed: Boolean = false

    override fun addTransferListener(transferListener: TransferListener) {
        if (!listeners.contains(transferListener)) {
            listeners.add(transferListener)
        }
        passthrough?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        val uri = dataSpec.uri
        val key = uri.toString()
        if (!CloudRegistry.isCloud(key)) {
            val direct = createUpstreamDataSource()
            passthrough = direct
            currentSpec = dataSpec
            opened = true
            return direct.open(dataSpec)
        }

        val uriState = stateFor(key, uri)
        val seq = uriState.requestSeq.incrementAndGet()
        val hadPrev = uriState.active != null
        // 立即抢占关闭旧连接，防止新旧 Loader 并存触发 115 单连接 HTTP 403
        uriState.active?.preempt()

        state = uriState
        uriKey = key
        mySeq = seq
        currentSpec = dataSpec
        pos = dataSpec.position.coerceAtLeast(0L)
        responseUri = uri
        responseHeaders = emptyMap()
        cachedServeUntil = 0L
        activeHandle = null
        staging = null

        val knownTotal = uriState.totalLength
        if (knownTotal > 0L) {
            if (pos > knownTotal || (pos == knownTotal && dataSpec.length != 0L)) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            val endExcl = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                minOf(pos + dataSpec.length, knownTotal)
            } else {
                knownTotal
            }
            limitExclusive = endExcl
            if (pos >= endExcl) {
                opened = true
                return 0L
            }
            val cachedEnd = uriState.contiguousCachedEnd(pos, endExcl)
            if (cachedEnd > pos) {
                cachedServeUntil = cachedEnd
                responseHeaders = uriState.lastHeaders
                opened = true
                return endExcl - pos
            }
        } else {
            limitExclusive = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                pos + dataSpec.length
            } else {
                C.LENGTH_UNSET.toLong()
            }
            if (dataSpec.length == 0L) {
                opened = true
                return 0L
            }
        }

        val res = openUpstreamSingleFlight(uriState, dataSpec, key, seq, hadPrev, pos, limitExclusive)
        activeHandle = res.handle
        staging = BlockStaging(pos, uriState)
        if (res.effectiveLimitExclusive > 0L) {
            limitExclusive = res.effectiveLimitExclusive
        }
        opened = true
        return if (limitExclusive > 0L) {
            (limitExclusive - pos).coerceAtLeast(0L)
        } else {
            res.openedLength
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        passthrough?.let { return it.read(buffer, offset, length) }
        if (length == 0) return 0
        if (closed) return C.RESULT_END_OF_INPUT
        if (limitExclusive >= 0L && pos >= limitExclusive) return C.RESULT_END_OF_INPUT

        val st = state ?: return C.RESULT_END_OF_INPUT
        if (pos < cachedServeUntil) {
            val want = minOf(length.toLong(), cachedServeUntil - pos).toInt()
            val n = st.readCachedAt(pos, buffer, offset, want)
            if (n > 0) {
                pos += n
                return n
            }
            cachedServeUntil = pos
        }

        if (closed) return C.RESULT_END_OF_INPUT
        var handle = activeHandle
        if (handle == null) {
            if (mySeq != st.requestSeq.get()) {
                throw InterruptedIOException("Superseded by newer stream request")
            }
            val spec = currentSpec ?: return C.RESULT_END_OF_INPUT
            val res = openUpstreamSingleFlight(st, spec, uriKey, mySeq, hadPrev = false, readPos = pos, readLimitExclusive = limitExclusive)
            handle = res.handle
            activeHandle = handle
            staging = BlockStaging(pos, st)
            if (res.effectiveLimitExclusive > 0L) {
                limitExclusive = res.effectiveLimitExclusive
            }
        }

        if (closed || handle.preempted || mySeq != st.requestSeq.get()) {
            return C.RESULT_END_OF_INPUT
        }
        val toRead = if (limitExclusive > 0L) {
            minOf(length.toLong(), limitExclusive - pos).toInt().coerceAtLeast(0)
        } else {
            length
        }
        if (toRead == 0) return C.RESULT_END_OF_INPUT

        val n = handle.source.read(buffer, offset, toRead)
        if (n > 0) {
            staging?.feed(buffer, offset, n)
            pos += n
        }
        return n
    }

    override fun getUri(): Uri? {
        passthrough?.let { return it.uri }
        if (!opened) return null
        return activeHandle?.source?.uri ?: responseUri
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        passthrough?.let { return it.responseHeaders }
        if (!opened) return emptyMap()
        return activeHandle?.source?.responseHeaders?.takeIf { it.isNotEmpty() } ?: responseHeaders
    }

    override fun close() {
        closed = true
        opened = false
        passthrough?.let { direct ->
            passthrough = null
            currentSpec = null
            responseUri = null
            responseHeaders = emptyMap()
            direct.close()
            return
        }
        runCatching { staging?.flushPartial() }
        staging = null
        val handle = activeHandle
        activeHandle = null
        val st = state
        state = null
        currentSpec = null
        responseUri = null
        responseHeaders = emptyMap()
        if (handle != null) {
            handle.preempt()
            if (st != null && st.active === handle) {
                st.active = null
                st.lastClosedAtMs = System.currentTimeMillis()
            }
        }
    }

    private fun createUpstreamDataSource(): DataSource = upstreamFactory.createDataSource().also { src ->
        for (l in listeners) src.addTransferListener(l)
    }

    private fun openUpstreamSingleFlight(
        st: CloudUriState,
        baseSpec: DataSpec,
        key: String,
        seq: Long,
        hadPrev: Boolean,
        readPos: Long,
        readLimitExclusive: Long,
    ): UpstreamOpenResult = synchronized(st.openLock) {
        val hadActiveNow = st.active != null
        st.active?.preempt()
        st.active = null
        if (hadActiveNow) {
            st.lastClosedAtMs = System.currentTimeMillis()
        }

        val elapsedSinceClose = System.currentTimeMillis() - st.lastClosedAtMs
        val needSettleMs = when {
            hadPrev || hadActiveNow -> PREEMPT_SETTLE_MS
            st.lastClosedAtMs > 0L && elapsedSinceClose in 0L until PREEMPT_SETTLE_MS -> PREEMPT_SETTLE_MS - elapsedSinceClose
            else -> 0L
        }
        if (needSettleMs > 0L) {
            sleepChecking(needSettleMs, st, seq)
        }

        val reqLen = if (readLimitExclusive > 0L) {
            (readLimitExclusive - readPos).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }
        val spec = baseSpec.buildUpon().setPosition(readPos).setLength(reqLen).build()
        val maxAttempts = 4
        var lastError: Exception? = null
        for (attempt in 0 until maxAttempts) {
            if (closed || seq != st.requestSeq.get()) {
                throw InterruptedIOException("Superseded by newer stream request")
            }
            val src = createUpstreamDataSource()
            val handle = ActiveUpstream(seq, src)
            st.active = handle
            try {
                val openedLen = src.open(spec)
                if (closed || handle.preempted || seq != st.requestSeq.get()) {
                    handle.preempt()
                    if (st.active === handle) {
                        st.active = null
                        st.lastClosedAtMs = System.currentTimeMillis()
                    }
                    throw InterruptedIOException("Superseded while opening upstream")
                }
                val hdrs = src.responseHeaders
                responseHeaders = hdrs
                st.lastHeaders = hdrs
                src.uri?.let { responseUri = it }

                val upstreamTotal = hdrs.entries
                    .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                    ?.value?.firstOrNull()
                    ?.substringAfterLast('/', "")
                    ?.trim()
                    ?.toLongOrNull()
                val inferredTotal = upstreamTotal ?: if (openedLen != C.LENGTH_UNSET.toLong() && reqLen == C.LENGTH_UNSET.toLong()) {
                    readPos + openedLen
                } else {
                    null
                }
                if (inferredTotal != null && inferredTotal > 0L) {
                    st.totalLength = inferredTotal
                }

                val effectiveLimit = when {
                    st.totalLength > 0L -> if (readLimitExclusive > 0L) minOf(readLimitExclusive, st.totalLength) else st.totalLength
                    openedLen != C.LENGTH_UNSET.toLong() -> readPos + openedLen
                    else -> readLimitExclusive
                }
                return UpstreamOpenResult(handle, openedLen, effectiveLimit)
            } catch (e: Exception) {
                val wasPreempted = handle.preempted
                handle.preempt()
                if (st.active === handle) {
                    st.active = null
                    st.lastClosedAtMs = System.currentTimeMillis()
                }
                lastError = e
                if (closed || wasPreempted || seq != st.requestSeq.get() || e is InterruptedIOException) {
                    throw e
                }
                if (!isRetryableOpenFailure(e) || attempt >= maxAttempts - 1) {
                    throw e
                }
                val code = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (code == 401 || attempt >= 2 || e.message.orEmpty().contains("HTTP 401")) {
                    CloudRegistry.invalidate(key)
                }
                val backoffMs = 150L * (1 shl attempt)
                Log.d("Voxtitle", "网盘直连打开受限（${e.message}），${backoffMs}ms 后重试(${attempt + 1}/$maxAttempts)")
                sleepChecking(backoffMs, st, seq)
            }
        }
        throw lastError ?: InterruptedIOException("Cloud upstream open failed")
    }

    private fun isRetryableOpenFailure(e: Exception): Boolean {
        val code = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (code != null) {
            return code == 401 || code == 403 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504
        }
        val msg = e.message.orEmpty()
        return msg.contains("HTTP 401") || msg.contains("HTTP 403") || msg.contains("HTTP 429") || msg.contains("HTTP 502") || msg.contains("HTTP 503")
    }

    private fun sleepChecking(totalMs: Long, st: CloudUriState, seq: Long) {
        var rem = totalMs
        while (rem > 0L) {
            if (closed || seq != st.requestSeq.get()) {
                throw InterruptedIOException("Superseded while waiting")
            }
            val step = minOf(25L, rem)
            try {
                Thread.sleep(step)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Interrupted while waiting")
            }
            rem -= step
        }
    }

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = CloudStreamDataSource(upstreamFactory)
    }

    private data class UpstreamOpenResult(
        val handle: ActiveUpstream,
        val openedLength: Long,
        val effectiveLimitExclusive: Long,
    )

    private class ActiveUpstream(val seq: Long, val source: DataSource) {
        @Volatile
        var preempted: Boolean = false
        private val closed = AtomicBoolean(false)

        fun preempt() {
            preempted = true
            if (closed.compareAndSet(false, true)) {
                runCatching { source.close() }
            }
        }
    }

    private class CloudUriState(val uri: Uri) {
        val requestSeq = AtomicLong(0L)
        val openLock = Any()

        @Volatile
        var active: ActiveUpstream? = null
        @Volatile
        var lastClosedAtMs: Long = 0L
        @Volatile
        var lastAccessOrder: Long = 0L
        @Volatile
        var totalLength: Long = C.LENGTH_UNSET.toLong()
        @Volatile
        var lastHeaders: Map<String, List<String>> = emptyMap()

        private val cacheLock = Any()
        private val blocks = object : LinkedHashMap<Long, ByteArray>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > MAX_BLOCKS
        }

        fun putPrefixBlock(blockIndex: Long, src: ByteArray, len: Int) {
            if (len <= 0) return
            synchronized(cacheLock) {
                val existing = blocks[blockIndex]
                if (existing == null || existing.size < len) {
                    blocks[blockIndex] = src.copyOf(len)
                }
            }
        }

        fun readCachedAt(pos: Long, dest: ByteArray, destOffset: Int, maxLen: Int): Int {
            synchronized(cacheLock) {
                val idx = pos / BLOCK_SIZE
                val off = (pos % BLOCK_SIZE).toInt()
                val b = blocks[idx] ?: return 0
                if (off >= b.size) return 0
                val n = minOf(maxLen, b.size - off)
                System.arraycopy(b, off, dest, destOffset, n)
                return n
            }
        }

        fun contiguousCachedEnd(startPos: Long, limitExclusive: Long): Long {
            if (startPos >= limitExclusive) return startPos
            synchronized(cacheLock) {
                var pos = startPos
                while (pos < limitExclusive) {
                    val idx = pos / BLOCK_SIZE
                    val off = (pos % BLOCK_SIZE).toInt()
                    val b = blocks[idx] ?: break
                    if (off >= b.size) break
                    val take = minOf((b.size - off).toLong(), limitExclusive - pos)
                    pos += take
                    if (b.size < BLOCK_SIZE && pos < limitExclusive) break
                }
                return pos
            }
        }

        fun close() {
            requestSeq.incrementAndGet()
            active?.preempt()
            active = null
            synchronized(cacheLock) { blocks.clear() }
        }

        companion object {
            const val BLOCK_SIZE = 65_536
            private const val MAX_BLOCKS = 128
        }
    }

    private class BlockStaging(startPos: Long, private val state: CloudUriState) {
        private val buf = ByteArray(CloudUriState.BLOCK_SIZE)
        private var blockIndex = startPos / CloudUriState.BLOCK_SIZE
        private var offsetInBlock = (startPos % CloudUriState.BLOCK_SIZE).toInt()
        private var validFromZero = if (offsetInBlock == 0) {
            true
        } else {
            state.readCachedAt(blockIndex * CloudUriState.BLOCK_SIZE, buf, 0, offsetInBlock) == offsetInBlock
        }

        fun feed(data: ByteArray, offset: Int, len: Int) {
            var srcPos = offset
            var rem = len
            while (rem > 0) {
                val space = CloudUriState.BLOCK_SIZE - offsetInBlock
                val take = minOf(rem, space)
                if (validFromZero) {
                    System.arraycopy(data, srcPos, buf, offsetInBlock, take)
                }
                srcPos += take
                rem -= take
                offsetInBlock += take
                if (offsetInBlock == CloudUriState.BLOCK_SIZE) {
                    if (validFromZero) {
                        state.putPrefixBlock(blockIndex, buf, CloudUriState.BLOCK_SIZE)
                    }
                    blockIndex++
                    offsetInBlock = 0
                    validFromZero = true
                }
            }
        }

        fun flushPartial() {
            if (validFromZero && offsetInBlock > 0) {
                state.putPrefixBlock(blockIndex, buf, offsetInBlock)
            }
        }
    }

    companion object {
        private const val PREEMPT_SETTLE_MS = 45L
        private const val MAX_CACHED_URIS = 3

        private val uriStates = ConcurrentHashMap<String, CloudUriState>()
        private val accessCounter = AtomicLong(0L)

        private fun stateFor(key: String, uri: Uri): CloudUriState {
            val st = uriStates.computeIfAbsent(key) { CloudUriState(uri) }
            st.lastAccessOrder = accessCounter.incrementAndGet()
            if (uriStates.size > MAX_CACHED_URIS) {
                uriStates.entries
                    .filter { it.key != key && it.value.active == null }
                    .sortedBy { it.value.lastAccessOrder }
                    .take((uriStates.size - MAX_CACHED_URIS).coerceAtLeast(0))
                    .forEach { (oldKey, oldState) ->
                        if (uriStates.remove(oldKey, oldState)) oldState.close()
                    }
            }
            return st
        }

        fun clearAll() {
            val snapshot = uriStates.values.toList()
            uriStates.clear()
            snapshot.forEach { it.close() }
        }
    }
}
