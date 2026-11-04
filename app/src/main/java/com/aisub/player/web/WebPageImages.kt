package com.aisub.player.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 只有网页自己拿得到的漫画图片：有的网站把加密的图片下载下来、在网页里解密成 blob: 地址再显示，
 * App 自己下载不了（拿到的是加密数据），只能请网页把解好的图片交出来（web_manga.js 的 exportImage）。
 * 这种页的地址是「voxblob:序号#网页地址」，序号是网页上漫画图片的位置（还没加载出来的也占一个位置，
 * 要的时候网页滚过去让它加载）。
 */
object WebPageImages {
    const val SCHEME = "voxblob:"

    fun url(frame: String, index: Int) = "$SCHEME$index#$frame"

    fun isPage(url: String) = url.startsWith(SCHEME)

    /**
     * 请网页交出第 index 张图（网页地址 frame 用来核对网页没换）；结果用 [complete] 送回来。
     * 返回 false 是网页已经没了。阅读模式所在的网页界面设置
     */
    @Volatile
    var exporter: ((frame: String, index: Int, id: String) -> Boolean)? = null

    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val ids = AtomicLong()

    suspend fun bytes(url: String): ByteArray {
        val index = url.removePrefix(SCHEME).substringBefore('#').toIntOrNull() ?: throw IOException("网页图片地址不对")
        val frame = url.substringAfter('#', "")
        val id = "p" + ids.incrementAndGet()
        val result = CompletableDeferred<String>()
        pending[id] = result
        try {
            val ex = exporter ?: throw IOException("网页已经关了，请回到网页重新打开")
            if (!ex(frame, index, id)) throw IOException("网页已经关了，请回到网页重新打开")
            // 网页要滚过去等它下载、解密，一张一张来，前面排着几张时会久一点
            val base64 = withTimeoutOrNull(60_000) { result.await() } ?: throw IOException("网页没有交出这张图")
            if (base64.isEmpty()) throw IOException("网页里这张图还没加载出来")
            return android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        } finally {
            pending.remove(id)
        }
    }

    /** 网页送回图片（base64，失败是空串）；在 WebView 的后台线程上调 */
    fun complete(id: String, base64: String) {
        pending[id]?.complete(base64)
    }
}
