package com.aisub.player.media

import android.content.Context
import android.net.Uri
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.cloud.CloudRegistry
import com.aisub.player.remote.cloud.cloudHttp
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** 读取本地、网络位置、网盘里的小文件（图片、文本），鉴权方式与播放时相同。 */
object RemoteFiles {

    /** 阻塞调用。超过 maxBytes 时：truncate 为 true 截断，否则报错。 */
    fun read(context: Context, url: String, maxBytes: Int, truncate: Boolean = false): ByteArray {
        if (url.startsWith("content://") || url.startsWith("file://")) {
            val input = context.contentResolver.openInputStream(Uri.parse(url)) ?: throw IOException("无法打开文件")
            return input.use { readLimited(it, maxBytes, truncate) }
        }
        val (realUrl, headers) = if (CloudRegistry.isCloud(url)) {
            CloudRegistry.resolve(url).let { it.url to it.headers }
        } else {
            Endpoints.rewrite(url).let { it to AuthRegistry.headersFor(it) }
        }
        val req = Request.Builder().url(realUrl).apply {
            headers.forEach { (k, v) -> header(k, v) }
            // 豆瓣图片防盗链：不带豆瓣的 Referer 会返回 418
            if (realUrl.contains(".doubanio.com/")) header("Referer", "https://movie.douban.com/")
            if (truncate) header("Range", "bytes=0-${maxBytes - 1}")
        }.build()
        return cloudHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("没有内容")
            body.byteStream().use { readLimited(it, maxBytes, truncate) }
        }
    }

    /**
     * 把整个文件下载到本地（漫画压缩包要随机读取，只能先存下来）。阻塞调用；progress 收到已下载、总字节数（不知道是 -1）。
     */
    fun download(context: Context, url: String, dest: java.io.File, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val tmp = java.io.File(dest.path + ".part")
        val input: InputStream
        var total = -1L
        var call: okhttp3.Response? = null
        if (url.startsWith("content://") || url.startsWith("file://")) {
            input = context.contentResolver.openInputStream(Uri.parse(url)) ?: throw IOException("无法打开文件")
            total = runCatching {
                context.contentResolver.openAssetFileDescriptor(Uri.parse(url), "r")?.use { it.length }
            }.getOrNull() ?: -1L
        } else {
            val (realUrl, headers) = if (CloudRegistry.isCloud(url)) {
                CloudRegistry.resolve(url).let { it.url to it.headers }
            } else {
                Endpoints.rewrite(url).let { it to AuthRegistry.headersFor(it) }
            }
            val req = Request.Builder().url(realUrl).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            val resp = cloudHttp.newCall(req).execute()
            if (!resp.isSuccessful) { resp.close(); throw IOException("HTTP ${resp.code}") }
            call = resp
            val body = resp.body ?: run { resp.close(); throw IOException("没有内容") }
            total = body.contentLength()
            input = body.byteStream()
        }
        try {
            input.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > 256 * 1024) { last = done; progress(done, total) }
                    }
                    progress(done, total)
                }
            }
            if (!tmp.renameTo(dest)) throw IOException("保存失败")
        } finally {
            call?.close()
            tmp.delete()
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int, truncate: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > maxBytes) {
                if (!truncate) throw IOException("文件太大（超过 ${formatSize(maxBytes.toLong())}）")
                out.write(buf, 0, maxBytes - out.size())
                break
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 文本解码：UTF-8（含 BOM、UTF-16 BOM），不合法时按 GBK。 */
    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
        } catch (e: java.nio.charset.CharacterCodingException) {
            // 截断时最后一个字符可能不完整，先去掉末尾几个字节再试
            runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, 0, maxOf(0, bytes.size - 3))).toString() }
                .getOrElse { String(bytes, charset("GBK")) }
        }
    }
}
