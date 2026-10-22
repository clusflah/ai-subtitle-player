package com.aisub.player.media

import android.content.Context
import android.net.Uri
import com.aisub.player.remote.AuthRegistry
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
            url to AuthRegistry.headersFor(url)
        }
        val req = Request.Builder().url(realUrl).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (truncate) header("Range", "bytes=0-${maxBytes - 1}")
        }.build()
        return cloudHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("没有内容")
            body.byteStream().use { readLimited(it, maxBytes, truncate) }
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
