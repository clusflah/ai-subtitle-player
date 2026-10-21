package com.aisub.player.remote

import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 最小 WebDAV 客户端：PROPFIND Depth:1 列目录。 */
class WebDavClient(private val server: Server) : RemoteClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val base = server.normalizedBase
    private val origin = Uri.parse(base).let { "${it.scheme}://${it.encodedAuthority}" }
    private val basePath = Uri.parse(base).path.orEmpty().trimEnd('/')

    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val dirPath = if (path.endsWith('/')) path else "$path/"
        val request = Request.Builder()
            .url(base + encodePath(dirPath))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .apply { server.basicAuthHeader()?.let { header("Authorization", it) } }
            .build()
        val xml = http.newCall(request).execute().use { resp ->
            when {
                resp.code == 401 -> throw IOException("用户名或密码错误")
                !resp.isSuccessful -> throw IOException("WebDAV 请求失败 HTTP ${resp.code}")
            }
            resp.body?.string().orEmpty()
        }
        parse(xml).filter { it.name.isNotEmpty() && it.path.trimEnd('/') != dirPath.trimEnd('/') }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    private fun parse(xml: String): List<RemoteItem> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.reader())

        val items = ArrayList<RemoteItem>()
        var href = ""
        var isDir = false
        var size = 0L
        var inResponse = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "response" -> {
                        inResponse = true; href = ""; isDir = false; size = 0
                    }
                    "href" -> if (inResponse) href = parser.nextText().trim()
                    "collection" -> isDir = true
                    "getcontentlength" -> size = parser.nextText().trim().toLongOrNull() ?: 0
                }
                XmlPullParser.END_TAG -> if (parser.name == "response" && inResponse) {
                    inResponse = false
                    toItem(href, isDir, size)?.let(items::add)
                }
            }
            event = parser.next()
        }
        return items
    }

    private fun toItem(href: String, isDir: Boolean, size: Long): RemoteItem? {
        if (href.isEmpty()) return null
        // href 可能是完整 URL，也可能只是路径
        val rawPath = if (href.startsWith("http")) Uri.parse(href).encodedPath.orEmpty() else href
        val decoded = Uri.decode(rawPath)
        val path = decoded.removePrefix(basePath).ifEmpty { "/" }
        val name = path.trimEnd('/').substringAfterLast('/')
        if (name.isEmpty()) return RemoteItem("", path, true)
        val playUrl = if (isDir) null else origin + rawPath
        return RemoteItem(name, path, isDir, size, playUrl)
    }

    companion object {
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getcontenttype/></d:prop></d:propfind>"""
    }
}
