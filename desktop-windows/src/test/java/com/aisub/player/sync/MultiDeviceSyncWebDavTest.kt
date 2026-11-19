package com.aisub.player.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class MultiDeviceSyncWebDavTest {

    @Test
    fun parseWebDavChildDirsExtractsSharedFoldersAndFiltersSystemEntries() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
              <D:response>
                <D:href>/home/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
              <D:response>
                <D:href>/video/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
              <D:response>
                <D:href>/%E5%BD%B1%E8%A7%86/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
              <D:response>
                <D:href>/@eaDir/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
              <D:response>
                <D:href>/#recycle/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
              <D:response>
                <D:href>/VoxtitleSync/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
              <D:response>
                <D:href>/readme.txt</D:href>
                <D:propstat><D:prop><D:resourcetype/></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val dirs = MultiDeviceSync.parseWebDavChildDirs(xml, "https://www.lwxyw.site:5006")
        assertEquals(listOf("home", "video", "影视"), dirs)
    }

    @Test
    fun explainWebDavErrorSuggestsSharedFolderForRoot405() {
        val msg = MultiDeviceSync.explainWebDavError(
            baseUrl = "https://www.lwxyw.site:5006",
            code = 405,
            stage = "上传同步文件",
            suggestedDirs = listOf("video", "home"),
        )
        assertTrue(msg.contains("根目录"))
        assertTrue(msg.contains("HTTP 405"))
        assertTrue(msg.contains("https://www.lwxyw.site:5006/home"))
    }

    @Test
    fun testWebDavConnectionRejectsReadOnlyRootAndVerifiesWritableSubdir() = runBlocking {
        val seenMethods = CopyOnWriteArrayList<String>()
        var checkFilePresent = false
        val rootXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
              <D:response><D:href>/home/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
              <D:response><D:href>/video/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
            </D:multistatus>
        """.trimIndent()
        val homeXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/home/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
            </D:multistatus>
        """.trimIndent()

        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { server ->
            val acceptThread = thread(isDaemon = true) {
                while (!server.isClosed) {
                    val sock = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        handleWebDavMock(sock) { method, path ->
                            seenMethods += "$method $path"
                            when {
                                method == "PROPFIND" && path == "/" -> 207 to rootXml
                                method == "PROPFIND" && path == "/VoxtitleSync/" -> 404 to ""
                                method == "MKCOL" && path == "/VoxtitleSync/" -> 405 to ""
                                method == "PUT" && path.startsWith("/VoxtitleSync/") -> 405 to ""
                                method == "PROPFIND" && path == "/home/" -> 207 to homeXml
                                method == "PROPFIND" && path == "/home/VoxtitleSync/" -> if (checkFilePresent) 207 to homeXml else 404 to ""
                                method == "MKCOL" && path == "/home/VoxtitleSync/" -> {
                                    checkFilePresent = true
                                    201 to ""
                                }
                                method == "PUT" && path == "/home/VoxtitleSync/.voxtitle_write_check" -> 201 to ""
                                method == "DELETE" && path == "/home/VoxtitleSync/.voxtitle_write_check" -> 204 to ""
                                else -> 404 to ""
                            }
                        }
                    }
                }
            }

            val base = "http://127.0.0.1:${server.localPort}"
            val rootRes = MultiDeviceSync.testWebDavConnection(base, "www", "secret")
            assertTrue("Root path must report failure instead of false positive: $rootRes", rootRes.startsWith("连接失败"))
            assertTrue("Should mention root directory 405 reason: $rootRes", rootRes.contains("根目录") && rootRes.contains("405"))
            assertTrue("Should suggest /home path: $rootRes", rootRes.contains("$base/home"))

            val subRes = MultiDeviceSync.testWebDavConnection("$base/home", "www", "secret")
            assertTrue("Writable subdir must succeed: $subRes", subRes.startsWith("连接成功"))
            assertFalse(seenMethods.none { it == "PUT /home/VoxtitleSync/.voxtitle_write_check" })
            assertFalse(seenMethods.none { it == "DELETE /home/VoxtitleSync/.voxtitle_write_check" })

            server.close()
            acceptThread.join(1000)
        }
    }

    private fun handleWebDavMock(sock: Socket, handler: (String, String) -> Pair<Int, String>) {
        sock.use { s ->
            val ins = BufferedInputStream(s.getInputStream())
            val reqLine = readHeaderLine(ins) ?: return
            var contentLength = 0
            while (true) {
                val line = readHeaderLine(ins) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            if (contentLength > 0) {
                var rem = contentLength
                val skipBuf = ByteArray(1024)
                while (rem > 0) {
                    val n = ins.read(skipBuf, 0, minOf(rem, skipBuf.size))
                    if (n <= 0) break
                    rem -= n
                }
            }
            val parts = reqLine.split(' ')
            val method = parts.getOrElse(0) { "" }
            val path = parts.getOrElse(1) { "/" }
            val (code, body) = handler(method, path)
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val statusText = when (code) {
                201 -> "Created"
                204 -> "No Content"
                207 -> "Multi-Status"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                else -> "OK"
            }
            val head = "HTTP/1.1 $code $statusText\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
            val out = s.getOutputStream()
            out.write(head.toByteArray(Charsets.UTF_8))
            if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
            out.flush()
            runCatching { s.shutdownOutput() }
            runCatching {
                s.soTimeout = 500
                val drain = ByteArray(256)
                while (ins.read(drain) >= 0) {}
            }
        }
    }

    private fun readHeaderLine(ins: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }
}
