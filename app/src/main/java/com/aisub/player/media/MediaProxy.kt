package com.aisub.player.media

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.aisub.player.remote.cloud.CloudRegistry
import java.io.BufferedOutputStream
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 本机 HTTP 中转：把任意地址（content://、WebDAV、网盘 cloud://）转成 http://127.0.0.1 地址给 VLC 内核用。
 * 读取走应用自己的 DataSource，所以网盘直链解析、鉴权头都和 ExoPlayer 播放时一致；支持 Range，VLC 可以拖动。
 * 只监听本机回环地址，地址里带随机 token，其他应用猜不到。
 *
 * 针对 115 等严格单连接限制的网盘：
 * - 同一文件在同一时刻仅保留一个活跃上游连接，新的拖动/Range 请求会先立即抢占关闭旧上游，再发起新请求；
 * - 内置轻量 64KB 分块内存缓存（优先保留文件头/尾元数据），重复读取 MKV SeekHead / Cues 时零网络请求、不断开当前播放流；
 * - 对瞬态 401 / 403 / 429 / 502 / 503 在发包前自动退避重试并在必要时刷新直链。
 */
@UnstableApi
class MediaProxy(private val factory: DataSource.Factory) {

    private val routes = ConcurrentHashMap<String, Uri>()
    private val uriStates = ConcurrentHashMap<String, UriStreamState>()
    private var server: ServerSocket? = null

    /** 登记一个地址，返回可给 VLC 打开的本机地址。文件名附在末尾，方便 VLC 按扩展名识别格式。 */
    @Synchronized
    fun register(uri: Uri, name: String): String {
        val socket = server ?: ServerSocket(0, 64, InetAddress.getByName("127.0.0.1")).also {
            server = it
            thread(name = "media-proxy", isDaemon = true) { acceptLoop(it) }
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        val uriKey = uri.toString()
        routes[token] = uri
        uriStates.computeIfAbsent(uriKey) { UriStreamState(uri) }
        if (uriStates.size > MAX_CACHED_URIS) {
            val activeUris = routes.values.mapTo(HashSet()) { it.toString() }
            uriStates.entries.toList().forEach { (k, st) ->
                if (uriStates.size > MAX_CACHED_URIS && k !in activeUris) {
                    if (uriStates.remove(k, st)) st.close()
                }
            }
        }
        return "http://127.0.0.1:${socket.localPort}/$token/${Uri.encode(name)}"
    }

    fun clear() {
        routes.clear()
        val snapshot = uriStates.values.toList()
        uriStates.clear()
        snapshot.forEach { it.close() }
    }

    @Synchronized
    fun close() {
        clear()
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: continue
            thread(name = "media-proxy-conn", isDaemon = true) { client.use(::serve) }
        }
    }

    private fun serve(client: Socket) {
        runCatching { client.tcpNoDelay = true }
        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return
        val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val out = BufferedOutputStream(client.getOutputStream(), 1 shl 16)
        val uri = routes[path.trimStart('/').substringBefore('/')]
        if (uri == null || (method != "GET" && method != "HEAD")) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }

        val state = uriStates.computeIfAbsent(uri.toString()) { UriStreamState(uri) }
        val pacer = LoopbackPacer()
        val rawRange = headers["range"]?.trim()?.lowercase()?.removePrefix("bytes=")?.substringBefore(',')?.trim()
        val parsedRange = parseRange(rawRange, state.totalLength)
        val hasRange = rawRange != null
        val start = parsedRange.first
        val end = parsedRange.second

        if (start < 0L || (end != null && end < start)) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }

        val knownTotal = state.totalLength
        // 1. 纯内存缓存命中（如 HEAD 或重复读取 MKV 文件头 / Cues 小块），直接返回且不打断正在播放的上游连接
        if (knownTotal > 0L) {
            if (start >= knownTotal) {
                out.write(
                    "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$knownTotal\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(),
                )
                out.flush()
                return
            }
            val knownEndExclusive = if (end != null) minOf(end + 1L, knownTotal) else knownTotal
            if (method == "HEAD") {
                writeHeaders(out, hasRange, start, knownEndExclusive, knownTotal)
                out.flush()
                finishGracefully(client)
                return
            }
            val reqSpan = knownEndExclusive - start
            if (reqSpan in 1L..MAX_PURE_CACHE_READ_BYTES && state.contiguousCachedEnd(start, knownEndExclusive) >= knownEndExclusive) {
                writeHeaders(out, hasRange, start, knownEndExclusive, knownTotal)
                streamCachedRange(state, start, knownEndExclusive, out, pacer) { !client.isClosed }
                out.flush()
                finishGracefully(client)
                return
            }
        }

        val mySeq = state.requestSeq.incrementAndGet()
        val hadPrev = state.active != null
        // 在同步等待 openLock 之前先立即抢占关闭旧上游，破解 VLC「先开新 Socket 再关旧 Socket」导致的 403 死锁
        state.active?.preempt()

        var headersSent = false
        var completedCleanly = false
        var activeHandle: ActiveUpstream? = null
        try {
            val freshTotal = state.totalLength
            var streamStart = start
            var knownEndExclusive: Long? = if (freshTotal > 0L) {
                if (end != null) minOf(end + 1L, freshTotal) else freshTotal
            } else {
                null
            }

            if (freshTotal > 0L && knownEndExclusive != null && method == "GET") {
                val cachedEnd = state.contiguousCachedEnd(start, knownEndExclusive)
                if (cachedEnd > start) {
                    writeHeaders(out, hasRange, start, knownEndExclusive, freshTotal)
                    headersSent = true
                    if (cachedEnd >= knownEndExclusive) {
                        streamCachedRange(state, start, cachedEnd, out, pacer) { !client.isClosed }
                        out.flush()
                        completedCleanly = !client.isClosed
                        return
                    }
                    val writtenEnd = streamCachedRange(state, start, cachedEnd, out, pacer) {
                        mySeq == state.requestSeq.get() && !client.isClosed
                    }
                    out.flush()
                    if (writtenEnd < cachedEnd || mySeq != state.requestSeq.get() || client.isClosed) {
                        throw InterruptedIOException("Superseded while streaming cached prefix")
                    }
                    streamStart = cachedEnd
                }
            }

            val upstreamLen = if (knownEndExclusive != null) {
                knownEndExclusive - streamStart
            } else if (end != null) {
                end - streamStart + 1L
            } else {
                C.LENGTH_UNSET.toLong()
            }
            if (upstreamLen == 0L) {
                if (!headersSent) {
                    writeHeaders(out, hasRange, start, start, knownTotal.takeIf { it > 0L })
                }
                out.flush()
                completedCleanly = true
                return
            }

            val openedResult = openUpstreamSingleFlight(
                state = state,
                uri = uri,
                mySeq = mySeq,
                hadPrev = hadPrev,
                client = client,
                start = streamStart,
                end = end,
                length = upstreamLen,
            )
            activeHandle = openedResult.handle

            if (!headersSent) {
                val respEndExclusive = knownEndExclusive ?: openedResult.endExclusive
                val respTotal = state.totalLength.takeIf { it > 0L } ?: openedResult.resourceTotal
                writeHeaders(out, hasRange && respEndExclusive != null, start, respEndExclusive, respTotal)
                headersSent = true
            }

            if (method == "GET") {
                val staging = BlockStaging(streamStart, state)
                val buf = ByteArray(1 shl 16)
                try {
                    while (mySeq == state.requestSeq.get() && !activeHandle.preempted) {
                        val n = activeHandle.source.read(buf, 0, buf.size)
                        if (n == C.RESULT_END_OF_INPUT) break
                        if (n > 0) {
                            staging.feed(buf, 0, n)
                            pacer.writeChunked(out, buf, 0, n) {
                                mySeq == state.requestSeq.get() && activeHandle?.preempted != true && !client.isClosed
                            }
                        }
                    }
                } finally {
                    staging.flushPartial()
                }
            }
            out.flush()
            completedCleanly = mySeq == state.requestSeq.get() && !activeHandle.preempted
        } catch (e: Exception) {
            if (mySeq == state.requestSeq.get() && e !is InterruptedIOException) {
                Log.d("Voxtitle", "中转连接结束：${e.message}")
            }
            if (!headersSent) {
                runCatching {
                    out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                }
            }
        } finally {
            activeHandle?.preempt()
            if (activeHandle != null && state.active === activeHandle) {
                state.active = null
            }
            if (completedCleanly) {
                finishGracefully(client)
            }
        }
    }

    private fun openUpstreamSingleFlight(
        state: UriStreamState,
        uri: Uri,
        mySeq: Long,
        hadPrev: Boolean,
        client: Socket,
        start: Long,
        end: Long?,
        length: Long,
    ): UpstreamOpenResult = synchronized(state.openLock) {
        state.active?.preempt()
        state.active = null

        val isCloud = CloudRegistry.isCloud(uri.toString())
        if (hadPrev && isCloud) {
            sleepChecking(PREEMPT_SETTLE_MS, state, mySeq, client)
        }

        val maxAttempts = 4
        var lastError: Exception? = null
        for (attempt in 0 until maxAttempts) {
            if (mySeq != state.requestSeq.get() || client.isClosed) {
                throw InterruptedIOException("Superseded by newer proxy request")
            }
            val src = factory.createDataSource()
            val handle = ActiveUpstream(mySeq, src)
            state.active = handle
            try {
                val spec = DataSpec.Builder().setUri(uri).setPosition(start).setLength(length).build()
                val opened = src.open(spec)
                if (handle.preempted || mySeq != state.requestSeq.get()) {
                    handle.preempt()
                    if (state.active === handle) state.active = null
                    throw InterruptedIOException("Superseded while opening upstream")
                }
                val endExclusive = if (opened == C.LENGTH_UNSET.toLong()) null else start + opened
                val upstreamTotal = src.getResponseHeaders().entries
                    .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                    ?.value?.firstOrNull()
                    ?.substringAfterLast('/', "")
                    ?.trim()
                    ?.toLongOrNull()
                val resourceTotal = upstreamTotal ?: if (end == null) endExclusive else null
                if (resourceTotal != null && resourceTotal > 0L) {
                    state.totalLength = resourceTotal
                }
                return UpstreamOpenResult(handle, opened, endExclusive, resourceTotal)
            } catch (e: Exception) {
                val wasPreempted = handle.preempted
                handle.preempt()
                if (state.active === handle) state.active = null
                lastError = e
                if (wasPreempted || mySeq != state.requestSeq.get() || e is InterruptedIOException) {
                    throw e
                }
                val retryable = isRetryableOpenFailure(e)
                if (!retryable || attempt >= maxAttempts - 1) {
                    throw e
                }
                val code = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (isCloud && (code == 401 || attempt >= 2 || e.message.orEmpty().contains("HTTP 401"))) {
                    CloudRegistry.invalidate(uri.toString())
                }
                val backoffMs = 150L * (1 shl attempt)
                Log.d("Voxtitle", "中转上游打开受限（${e.message}），${backoffMs}ms 后重试(${attempt + 1}/$maxAttempts)")
                sleepChecking(backoffMs, state, mySeq, client)
            }
        }
        throw lastError ?: InterruptedIOException("Upstream open failed")
    }

    private fun isRetryableOpenFailure(e: Exception): Boolean {
        val code = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (code != null) {
            return code == 401 || code == 403 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504
        }
        val msg = e.message.orEmpty()
        return msg.contains("HTTP 401") || msg.contains("HTTP 403") || msg.contains("HTTP 429") || msg.contains("HTTP 502") || msg.contains("HTTP 503")
    }

    private fun sleepChecking(totalMs: Long, state: UriStreamState, mySeq: Long, client: Socket) {
        var rem = totalMs
        while (rem > 0L) {
            if (mySeq != state.requestSeq.get() || client.isClosed) {
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

    private fun parseRange(rawRange: String?, knownTotal: Long): Pair<Long, Long?> {
        if (rawRange.isNullOrEmpty()) return 0L to null
        val dash = rawRange.indexOf('-')
        if (dash < 0) return (rawRange.toLongOrNull() ?: 0L) to null
        val left = rawRange.substring(0, dash).trim()
        val right = rawRange.substring(dash + 1).trim()
        if (left.isEmpty()) {
            val suffix = right.toLongOrNull()
            if (suffix != null && suffix > 0L && knownTotal > 0L) {
                return (knownTotal - suffix).coerceAtLeast(0L) to (knownTotal - 1L)
            }
            return 0L to null
        }
        val start = left.toLongOrNull() ?: 0L
        val end = right.toLongOrNull()
        return start to end
    }

    private fun writeHeaders(
        out: BufferedOutputStream,
        isPartial: Boolean,
        start: Long,
        endExclusive: Long?,
        resourceTotal: Long?,
    ) {
        val head = StringBuilder()
        if (isPartial && endExclusive != null && endExclusive > start) {
            head.append("HTTP/1.1 206 Partial Content\r\n")
            head.append("Content-Range: bytes $start-${endExclusive - 1}/${resourceTotal ?: "*"}\r\n")
        } else {
            head.append("HTTP/1.1 200 OK\r\n")
        }
        if (endExclusive != null && endExclusive >= start) {
            head.append("Content-Length: ${endExclusive - start}\r\n")
        }
        head.append("Accept-Ranges: bytes\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
        out.flush()
    }

    private fun finishGracefully(client: Socket) {
        if (client.isClosed) return
        runCatching { client.shutdownOutput() }
    }

    private fun streamCachedRange(
        state: UriStreamState,
        start: Long,
        endExclusive: Long,
        out: BufferedOutputStream,
        pacer: LoopbackPacer,
        shouldContinue: () -> Boolean,
    ): Long {
        var pos = start
        val buf = ByteArray(1 shl 16)
        while (pos < endExclusive && shouldContinue()) {
            val want = minOf(buf.size.toLong(), endExclusive - pos).toInt()
            val n = state.readCachedAt(pos, buf, want)
            if (n <= 0) break
            pacer.writeChunked(out, buf, 0, n, shouldContinue)
            if (!shouldContinue()) break
            pos += n
        }
        return pos
    }

    /**
     * 本机回环流控节流器：以 16KB 为步长 flush，避免零延迟写入大量内存块后立刻半关闭造成对端截断。
     */
    private class LoopbackPacer {
        private var windowStartNs = System.nanoTime()
        private var windowBytes = 0

        fun writeChunked(
            out: BufferedOutputStream,
            data: ByteArray,
            offset: Int,
            len: Int,
            shouldContinue: () -> Boolean,
        ) {
            var pos = offset
            val end = offset + len
            while (pos < end && shouldContinue()) {
                val step = minOf(16_384, end - pos)
                out.write(data, pos, step)
                out.flush()
                pos += step
                windowBytes += step
                if (windowBytes >= 16_384) {
                    val elapsedNs = System.nanoTime() - windowStartNs
                    if (elapsedNs < 1_500_000L) {
                        try {
                            Thread.sleep(2L)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return
                        }
                    }
                    windowStartNs = System.nanoTime()
                    windowBytes = 0
                }
            }
        }
    }

    private data class UpstreamOpenResult(
        val handle: ActiveUpstream,
        val opened: Long,
        val endExclusive: Long?,
        val resourceTotal: Long?,
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

    private class UriStreamState(val uri: Uri) {
        val requestSeq = AtomicLong(0L)
        val openLock = Any()

        @Volatile
        var active: ActiveUpstream? = null

        @Volatile
        var totalLength: Long = C.LENGTH_UNSET.toLong()

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

        fun readCachedAt(pos: Long, dest: ByteArray, maxLen: Int): Int {
            synchronized(cacheLock) {
                val idx = pos / BLOCK_SIZE
                val off = (pos % BLOCK_SIZE).toInt()
                val b = blocks[idx] ?: return 0
                if (off >= b.size) return 0
                val n = minOf(maxLen, b.size - off)
                System.arraycopy(b, off, dest, 0, n)
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

    private class BlockStaging(startPos: Long, private val state: UriStreamState) {
        private val buf = ByteArray(UriStreamState.BLOCK_SIZE)
        private var blockIndex = startPos / UriStreamState.BLOCK_SIZE
        private var offsetInBlock = (startPos % UriStreamState.BLOCK_SIZE).toInt()
        private var validFromZero = if (offsetInBlock == 0) {
            true
        } else {
            state.readCachedAt(blockIndex * UriStreamState.BLOCK_SIZE, buf, offsetInBlock) == offsetInBlock
        }

        fun feed(data: ByteArray, offset: Int, len: Int) {
            var srcPos = offset
            var rem = len
            while (rem > 0) {
                val space = UriStreamState.BLOCK_SIZE - offsetInBlock
                val take = minOf(rem, space)
                if (validFromZero) {
                    System.arraycopy(data, srcPos, buf, offsetInBlock, take)
                }
                srcPos += take
                rem -= take
                offsetInBlock += take
                if (offsetInBlock == UriStreamState.BLOCK_SIZE) {
                    if (validFromZero) {
                        state.putPrefixBlock(blockIndex, buf, UriStreamState.BLOCK_SIZE)
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
        private const val MAX_PURE_CACHE_READ_BYTES = 4L * 1024 * 1024
        private const val MAX_CACHED_URIS = 3
    }
}
