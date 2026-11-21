package com.aisub.player.media

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.aisub.player.remote.cloud.CloudRegistry
import com.aisub.player.telegram.Telegram
import java.io.BufferedOutputStream
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 本机 HTTP 中转：把任意地址（content://、WebDAV、网盘 cloud://）转成 http://127.0.0.1 地址给 VLC / FFmpeg 内核用。
 * 读取走应用自己的 DataSource，所以网盘直链解析、鉴权头都和 ExoPlayer 播放时一致；支持 Range，VLC / FFmpeg 可以自由拖动。
 * 只监听本机回环地址，地址里带随机 token，其他应用猜不到。
 *
 * 高码率局域网（WebDAV / SMB / 4K 120fps Remux）与 115 单连接网盘双重优化：
 * - 零人为限速（移除旧版每 16KB sleep 2ms 导致的 ~7.9MB/s 回环瓶颈），采用 256KB 流缓冲 + 1MB 套接字发送缓冲 +
 *   标准半关闭排空（shutdownOutput + 短超时等待对端 EOF），既消除 Windows Winsock 提前 closesocket 触发 RST 截断，又跑满千兆/万兆内网吞吐；
 * - 同一文件在同一时刻仅保留一个活跃上游连接，新的拖动/Range 请求会先立即抢占关闭旧上游，再发起新请求；
 * - 内置 64KB 分块首尾元数据内存缓存（大文件仅缓存前 8MB 与后 8MB，避免 70GB+ 蓝光原盘播放中途频繁分配拷贝并冲刷掉 SeekHead/moov/Cues）；
 * - 对瞬态 401 / 403 / 429 / 502 / 503 在发包前自动退避重试并在必要时刷新直链。
 */
@UnstableApi
class MediaProxy(private val factory: DataSource.Factory) {

    private val routes = ConcurrentHashMap<String, Uri>()
    private val uriStates = ConcurrentHashMap<String, UriStreamState>()
    private var server: ServerSocket? = null

    /** 登记一个地址，返回可给 VLC / FFmpeg 打开的本机地址。文件名附在末尾，方便播放器按扩展名识别格式。 */
    @Synchronized
    fun register(uri: Uri, name: String): String {
        val socket = server ?: ServerSocket(0, 64, InetAddress.getByName("127.0.0.1")).also {
            server = it
            thread(name = "media-proxy", isDaemon = true) { acceptLoop(it) }
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        val uriKey = uri.toString()
        clearUpstreamError(null, uriKey)
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

    fun clearRoutesOnly() {
        routes.clear()
        uriStates.values.forEach { it.preemptActive() }
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
        runCatching {
            client.tcpNoDelay = true
            client.sendBufferSize = SOCKET_SEND_BUFFER_BYTES
        }
        val rawIn = client.getInputStream()
        val requestLine = readAsciiHeaderLine(rawIn) ?: return
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readAsciiHeaderLine(rawIn) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val out = BufferedOutputStream(client.getOutputStream(), IO_BUFFER_BYTES)
        val token = path.trimStart('/').substringBefore('/')
        val uri = routes[token]
        if (uri == null || (method != "GET" && method != "HEAD")) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }

        val state = uriStates.computeIfAbsent(uri.toString()) { UriStreamState(uri) }
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
        // 1. 纯内存缓存命中（如 HEAD 或重复读取 MKV 文件头 / Cues / MP4 moov 小块），直接高速返回且不打断正在播放的上游连接
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
                val hasLen = writeHeaders(out, hasRange, start, knownEndExclusive, knownTotal)
                out.flush()
                finishGracefully(client, hasLen)
                return
            }
            val reqSpan = knownEndExclusive - start
            if (reqSpan in 1L..MAX_PURE_CACHE_READ_BYTES && state.contiguousCachedEnd(start, knownEndExclusive) >= knownEndExclusive) {
                val hasLen = writeHeaders(out, hasRange, start, knownEndExclusive, knownTotal)
                streamCachedRange(state, start, knownEndExclusive, out) { !client.isClosed }
                out.flush()
                finishGracefully(client, hasLen)
                return
            }
        }

        val singleFlight = requiresSingleFlight(uri)
        val myEpoch = state.clearEpoch.get()
        val mySeq = if (singleFlight) state.requestSeq.incrementAndGet() else state.requestSeq.get()
        val hadPrev = singleFlight && state.active != null
        fun stillValid(): Boolean = !client.isClosed && myEpoch == state.clearEpoch.get() && (!singleFlight || mySeq == state.requestSeq.get())
        // 仅对单连接网盘/Telegram 抢占关闭旧上游，破解 VLC/FFmpeg「先开新 Socket 再关旧 Socket」导致的 403 死锁；
        // WebDAV / OpenList / Plex / Emby 允许并发 Range 请求，避免 ffprobe/ffmpeg 拖动时互相掐断预热连接
        if (singleFlight) {
            state.active?.preempt()
        }

        var headersSent = false
        var hasContentLength = false
        var completedCleanly = false
        var activeHandle: ActiveUpstream? = null
        try {
            val freshTotal = state.totalLength
            var streamStart = start
            val knownEndExclusive: Long? = if (freshTotal > 0L) {
                if (end != null) minOf(end + 1L, freshTotal) else freshTotal
            } else {
                null
            }

            if (freshTotal > 0L && knownEndExclusive != null && method == "GET") {
                val cachedEnd = state.contiguousCachedEnd(start, knownEndExclusive)
                if (cachedEnd > start) {
                    hasContentLength = writeHeaders(out, hasRange, start, knownEndExclusive, freshTotal)
                    headersSent = true
                    if (cachedEnd >= knownEndExclusive) {
                        streamCachedRange(state, start, cachedEnd, out) { !client.isClosed && myEpoch == state.clearEpoch.get() }
                        out.flush()
                        completedCleanly = !client.isClosed && myEpoch == state.clearEpoch.get()
                        return
                    }
                    val writtenEnd = streamCachedRange(state, start, cachedEnd, out) {
                        stillValid()
                    }
                    out.flush()
                    if (writtenEnd < cachedEnd || !stillValid()) {
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
                    hasContentLength = writeHeaders(out, hasRange, start, start, knownTotal.takeIf { it > 0L })
                    headersSent = true
                }
                out.flush()
                completedCleanly = true
                return
            }

            val openedResult = if (singleFlight) {
                synchronized(state.openLock) {
                    state.active?.preempt()
                    state.active = null
                    openUpstreamImpl(
                        state = state,
                        token = token,
                        uri = uri,
                        singleFlight = true,
                        mySeq = mySeq,
                        myEpoch = myEpoch,
                        hadPrev = hadPrev,
                        client = client,
                        start = streamStart,
                        end = end,
                        length = upstreamLen,
                    )
                }
            } else {
                openUpstreamImpl(
                    state = state,
                    token = token,
                    uri = uri,
                    singleFlight = false,
                    mySeq = mySeq,
                    myEpoch = myEpoch,
                    hadPrev = false,
                    client = client,
                    start = streamStart,
                    end = end,
                    length = upstreamLen,
                )
            }
            activeHandle = openedResult.handle

            if (!headersSent) {
                val respEndExclusive = knownEndExclusive ?: openedResult.endExclusive
                val respTotal = state.totalLength.takeIf { it > 0L } ?: openedResult.resourceTotal
                hasContentLength = writeHeaders(out, hasRange && respEndExclusive != null, start, respEndExclusive, respTotal)
                headersSent = true
            }

            if (method == "GET") {
                val staging = BlockStaging(streamStart, state)
                val buf = ByteArray(IO_BUFFER_BYTES)
                try {
                    while (stillValid() && !activeHandle.preempted) {
                        val n = activeHandle.source.read(buf, 0, buf.size)
                        if (n == C.RESULT_END_OF_INPUT) break
                        if (n > 0) {
                            staging.feed(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                } finally {
                    staging.flushPartial()
                }
            }
            out.flush()
            completedCleanly = stillValid() && !activeHandle.preempted
        } catch (e: Exception) {
            val isTimeout = e is java.net.SocketTimeoutException
            val isSuperseded = !stillValid() || activeHandle?.preempted == true ||
                (e is InterruptedIOException && !isTimeout)
            if (!isSuperseded) {
                Log.d("Voxtitle", "中转连接结束：${e.message}")
                if (!headersSent) {
                    val httpCode = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                        ?: HTTP_STATUS_IN_MSG.find(e.message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val ioCause = e as? java.io.IOException
                        ?: java.io.IOException(e.message ?: e.javaClass.simpleName, e)
                    recordUpstreamError(
                        token = token,
                        targetUri = uri.toString(),
                        error = UpstreamError(
                            targetUri = uri.toString(),
                            httpStatusCode = httpCode,
                            causeClass = e.javaClass.name,
                            message = e.message ?: e.javaClass.simpleName,
                            cause = ioCause,
                        ),
                    )
                }
            }
            if (!headersSent) {
                runCatching {
                    out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    finishGracefully(client, true)
                }
            }
        } finally {
            activeHandle?.preempt()
            if (activeHandle != null) {
                if (state.active === activeHandle) {
                    state.active = null
                }
                state.concurrentActive.remove(activeHandle)
            }
            if (completedCleanly) {
                finishGracefully(client, hasContentLength)
            }
        }
    }

    /** 逐字节读取请求行与请求头，避免 BufferedReader 预读吞掉后续对端关闭信号 */
    private fun readAsciiHeaderLine(ins: java.io.InputStream): String? {
        val sb = StringBuilder(128)
        while (sb.length < 16_384) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun requiresSingleFlight(uri: Uri): Boolean {
        val raw = uri.toString()
        if (Telegram.isTelegram(raw)) return true
        if (CloudRegistry.isCloud(raw)) {
            val srv = uri.host?.let(CloudRegistry.serverLookup)
            return srv == null || !srv.type.isMediaServer
        }
        return false
    }

    private fun openUpstreamImpl(
        state: UriStreamState,
        token: String,
        uri: Uri,
        singleFlight: Boolean,
        mySeq: Long,
        myEpoch: Long,
        hadPrev: Boolean,
        client: Socket,
        start: Long,
        end: Long?,
        length: Long,
    ): UpstreamOpenResult {
        fun superseded(): Boolean = myEpoch != state.clearEpoch.get() || client.isClosed || (singleFlight && mySeq != state.requestSeq.get())

        val isCloud = CloudRegistry.isCloud(uri.toString())
        if (singleFlight && hadPrev && isCloud) {
            sleepChecking(PREEMPT_SETTLE_MS, state, singleFlight, mySeq, myEpoch, client)
        }

        val maxAttempts = 4
        var lastError: Exception? = null
        for (attempt in 0 until maxAttempts) {
            if (superseded()) {
                throw InterruptedIOException("Superseded by newer proxy request")
            }
            val src = factory.createDataSource()
            val handle = ActiveUpstream(if (singleFlight) mySeq else myEpoch, src)
            if (singleFlight) {
                state.active = handle
            } else {
                state.concurrentActive.add(handle)
            }
            fun detachHandle() {
                handle.preempt()
                if (singleFlight) {
                    if (state.active === handle) state.active = null
                } else {
                    state.concurrentActive.remove(handle)
                }
            }
            try {
                val spec = DataSpec.Builder().setUri(uri).setPosition(start).setLength(length).build()
                val opened = src.open(spec)
                if (handle.preempted || superseded()) {
                    detachHandle()
                    throw InterruptedIOException("Superseded while opening upstream")
                }
                clearUpstreamError(token, uri.toString())
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
                detachHandle()
                lastError = e
                if (wasPreempted || superseded() || e is InterruptedIOException) {
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
                sleepChecking(backoffMs, state, singleFlight, mySeq, myEpoch, client)
            }
        }
        throw lastError ?: InterruptedIOException("Upstream open failed")
    }

    private fun isRetryableOpenFailure(e: Exception): Boolean {
        val code = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (code != null) {
            return code == 401 || code == 403 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504
        }
        if (e is java.net.SocketException) return true
        val msg = e.message.orEmpty()
        return msg.contains("HTTP 401") || msg.contains("HTTP 403") || msg.contains("HTTP 429") || msg.contains("HTTP 502") || msg.contains("HTTP 503")
    }

    private fun sleepChecking(
        totalMs: Long,
        state: UriStreamState,
        singleFlight: Boolean,
        mySeq: Long,
        myEpoch: Long,
        client: Socket,
    ) {
        var rem = totalMs
        while (rem > 0L) {
            if (myEpoch != state.clearEpoch.get() || client.isClosed || (singleFlight && mySeq != state.requestSeq.get())) {
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
    ): Boolean {
        val head = StringBuilder()
        if (isPartial && endExclusive != null && endExclusive > start) {
            head.append("HTTP/1.1 206 Partial Content\r\n")
            head.append("Content-Range: bytes $start-${endExclusive - 1}/${resourceTotal ?: "*"}\r\n")
        } else {
            head.append("HTTP/1.1 200 OK\r\n")
        }
        val hasLength = endExclusive != null && endExclusive >= start
        if (hasLength) {
            head.append("Content-Length: ${endExclusive - start}\r\n")
        }
        head.append("Accept-Ranges: bytes\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
        out.flush()
        return hasLength
    }

    /**
     * 安全收尾：
     * - 已知 Content-Length 时不提前发送服务端 FIN（避免 Windows WFP/TUN 过滤驱动在在途数据未排空时因半关闭触发 65535 截断或 RST），
     *   而是等待客户端按 Content-Length 读完并因 Connection: close 主动关闭对端（< 1ms 返回 -1）；
     * - 未知长度流则先 shutdownOutput 发送 FIN 通知客户端流结束，再排空对端关闭信号。
     */
    private fun finishGracefully(client: Socket, hasContentLength: Boolean) {
        if (client.isClosed) return
        if (!hasContentLength) {
            runCatching { client.shutdownOutput() }
        }
        runCatching {
            client.soTimeout = if (hasContentLength) 1_500 else 500
            val drain = ByteArray(512)
            val ins = client.getInputStream()
            while (ins.read(drain) >= 0) {
                // 正常本机回环客户端读完即关（< 1ms 返回 -1）
            }
        }
        if (hasContentLength && !client.isClosed) {
            runCatching { client.shutdownOutput() }
        }
    }

    private fun streamCachedRange(
        state: UriStreamState,
        start: Long,
        endExclusive: Long,
        out: BufferedOutputStream,
        shouldContinue: () -> Boolean,
    ): Long {
        var pos = start
        val buf = ByteArray(UriStreamState.BLOCK_SIZE)
        while (pos < endExclusive && shouldContinue()) {
            val want = minOf(buf.size.toLong(), endExclusive - pos).toInt()
            val n = state.readCachedAt(pos, buf, want)
            if (n <= 0) break
            out.write(buf, 0, n)
            pos += n
        }
        return pos
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
        val clearEpoch = AtomicLong(0L)
        val openLock = Any()

        @Volatile
        var active: ActiveUpstream? = null

        val concurrentActive = CopyOnWriteArraySet<ActiveUpstream>()

        @Volatile
        var totalLength: Long = C.LENGTH_UNSET.toLong()

        private val cacheLock = Any()
        private val blocks = object : LinkedHashMap<Long, ByteArray>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > MAX_BLOCKS
        }

        /**
         * 大文件（>16MB，如 77GB 4K Remux）仅缓存文件头 8MB 与文件尾 8MB 元数据区间（ftyp/moov/SeekHead/Cues），
         * 正片中间数据零拷贝直通，既不产生百万次数组分配，也不会冲刷掉已缓存的首尾索引块。
         */
        fun shouldCacheBlock(blockIndex: Long): Boolean {
            if (blockIndex < EDGE_CACHE_BLOCKS) return true
            val total = totalLength
            if (total <= 0L) return blockIndex < MAX_BLOCKS
            if (total <= FULL_CACHE_FILE_THRESHOLD_BYTES) return true
            val lastBlock = (total - 1L) / BLOCK_SIZE
            return blockIndex >= (lastBlock - EDGE_CACHE_BLOCKS + 1L)
        }

        fun putPrefixBlock(blockIndex: Long, src: ByteArray, len: Int) {
            if (len <= 0 || !shouldCacheBlock(blockIndex)) return
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

        fun preemptActive() {
            clearEpoch.incrementAndGet()
            requestSeq.incrementAndGet()
            active?.preempt()
            active = null
            concurrentActive.forEach { it.preempt() }
            concurrentActive.clear()
        }

        fun close() {
            preemptActive()
            synchronized(cacheLock) { blocks.clear() }
        }

        companion object {
            const val BLOCK_SIZE = 65_536
            private const val EDGE_CACHE_BLOCKS = 128L // 首尾各 8MB
            private const val MAX_BLOCKS = 256         // 单文件最多 16MB 块缓存
            private const val FULL_CACHE_FILE_THRESHOLD_BYTES = 16L * 1024 * 1024
        }
    }

    private class BlockStaging(startPos: Long, private val state: UriStreamState) {
        private val buf = ByteArray(UriStreamState.BLOCK_SIZE)
        private var blockIndex = startPos / UriStreamState.BLOCK_SIZE
        private var offsetInBlock = (startPos % UriStreamState.BLOCK_SIZE).toInt()
        private var cacheThisBlock = state.shouldCacheBlock(blockIndex)
        private var validFromZero = if (!cacheThisBlock) {
            false
        } else if (offsetInBlock == 0) {
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
                if (cacheThisBlock && validFromZero) {
                    System.arraycopy(data, srcPos, buf, offsetInBlock, take)
                }
                srcPos += take
                rem -= take
                offsetInBlock += take
                if (offsetInBlock == UriStreamState.BLOCK_SIZE) {
                    if (cacheThisBlock && validFromZero) {
                        state.putPrefixBlock(blockIndex, buf, UriStreamState.BLOCK_SIZE)
                    }
                    blockIndex++
                    offsetInBlock = 0
                    cacheThisBlock = state.shouldCacheBlock(blockIndex)
                    validFromZero = cacheThisBlock
                }
            }
        }

        fun flushPartial() {
            if (cacheThisBlock && validFromZero && offsetInBlock > 0) {
                state.putPrefixBlock(blockIndex, buf, offsetInBlock)
            }
        }
    }

    data class UpstreamError(
        val targetUri: String,
        val httpStatusCode: Int?,
        val causeClass: String,
        val message: String,
        val cause: java.io.IOException,
        val timestampMs: Long = System.currentTimeMillis(),
    )

    companion object {
        private const val IO_BUFFER_BYTES = 256 * 1024
        private const val SOCKET_SEND_BUFFER_BYTES = 1024 * 1024
        private const val PREEMPT_SETTLE_MS = 45L
        private const val MAX_PURE_CACHE_READ_BYTES = 4L * 1024 * 1024
        private const val MAX_CACHED_URIS = 3
        private const val UPSTREAM_ERROR_TTL_MS = 60_000L
        private val HTTP_STATUS_IN_MSG = Regex("""HTTP\s+(\d{3})""")
        private val recentUpstreamErrors = ConcurrentHashMap<String, UpstreamError>()

        private fun extractTokenFromProxyUrl(url: String?): String? {
            if (url.isNullOrBlank()) return null
            if (!url.startsWith("http://127.0.0.1:", ignoreCase = true) &&
                !url.startsWith("http://localhost:", ignoreCase = true)
            ) return null
            return runCatching {
                java.net.URI(url).rawPath?.trimStart('/')?.substringBefore('/')?.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }

        internal fun recordUpstreamError(token: String?, targetUri: String, error: UpstreamError) {
            if (recentUpstreamErrors.size > 64) {
                val cutoff = System.currentTimeMillis() - UPSTREAM_ERROR_TTL_MS
                recentUpstreamErrors.entries.removeIf { it.value.timestampMs < cutoff }
            }
            recentUpstreamErrors[targetUri] = error
            if (!token.isNullOrEmpty()) recentUpstreamErrors[token] = error
        }

        internal fun clearUpstreamError(token: String?, targetUri: String) {
            if (!token.isNullOrEmpty()) recentUpstreamErrors.remove(token)
            recentUpstreamErrors.remove(targetUri)
        }

        fun findRecentUpstreamError(urlOrUri: String?, maxAgeMs: Long = UPSTREAM_ERROR_TTL_MS): UpstreamError? {
            if (urlOrUri.isNullOrBlank()) return null
            val hit = extractTokenFromProxyUrl(urlOrUri)?.let { recentUpstreamErrors[it] }
                ?: recentUpstreamErrors[urlOrUri]
                ?: return null
            return hit.takeIf { System.currentTimeMillis() - it.timestampMs <= maxAgeMs }
        }

        internal fun clearRecentErrorsForTest() = recentUpstreamErrors.clear()
    }
}
