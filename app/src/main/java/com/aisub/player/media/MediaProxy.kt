package com.aisub.player.media

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * 本机 HTTP 中转：把任意地址（content://、WebDAV、网盘 cloud://）转成 http://127.0.0.1 地址给 VLC 内核用。
 * 读取走应用自己的 DataSource，所以网盘直链解析、鉴权头都和 ExoPlayer 播放时一致；支持 Range，VLC 可以拖动。
 * 只监听本机回环地址，地址里带随机 token，其他应用猜不到。
 */
@UnstableApi
class MediaProxy(private val factory: DataSource.Factory) {

    private val routes = ConcurrentHashMap<String, Uri>()
    private var server: ServerSocket? = null

    /** 登记一个地址，返回可给 VLC 打开的本机地址。文件名附在末尾，方便 VLC 按扩展名识别格式。 */
    @Synchronized
    fun register(uri: Uri, name: String): String {
        val socket = server ?: ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).also {
            server = it
            thread(name = "media-proxy", isDaemon = true) { acceptLoop(it) }
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        routes[token] = uri
        return "http://127.0.0.1:${socket.localPort}/$token/${Uri.encode(name)}"
    }

    fun clear() = routes.clear()

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: continue
            thread(name = "media-proxy-conn", isDaemon = true) { client.use(::serve) }
        }
    }

    private fun serve(client: Socket) {
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
        // 只支持 bytes=start- 或 bytes=start-end
        val range = headers["range"]?.removePrefix("bytes=")?.split('-')
        val start = range?.getOrNull(0)?.toLongOrNull() ?: 0L
        val end = range?.getOrNull(1)?.toLongOrNull()
        val source = factory.createDataSource()
        try {
            val requested = if (end != null) end - start + 1 else C.LENGTH_UNSET.toLong()
            val opened = source.open(DataSpec.Builder().setUri(uri).setPosition(start).setLength(requested).build())
            val total = if (opened == C.LENGTH_UNSET.toLong()) null else start + opened
            val head = StringBuilder()
            if (range != null && total != null) {
                head.append("HTTP/1.1 206 Partial Content\r\n")
                head.append("Content-Range: bytes $start-${total - 1}/${if (end == null) total else "*"}\r\n")
            } else {
                head.append("HTTP/1.1 200 OK\r\n")
            }
            if (opened != C.LENGTH_UNSET.toLong()) head.append("Content-Length: $opened\r\n")
            head.append("Accept-Ranges: bytes\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n")
            out.write(head.toString().toByteArray())
            if (method == "GET") {
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = source.read(buf, 0, buf.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    out.write(buf, 0, n)
                }
            }
            out.flush()
        } catch (e: Exception) {
            // VLC 拖动时会主动断开旧连接，属于正常情况
            Log.d("AiSub", "中转连接结束：${e.message}")
            runCatching {
                out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
            }
        } finally {
            runCatching { source.close() }
        }
    }
}
