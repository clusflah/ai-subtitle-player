package com.aisub.player.live

import android.content.Context
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 一个直播源：M3U / TXT 播放列表的网址，或用户选的本地文件（content://）；epgUrl 为可选的自定义节目单地址。 */
data class LiveSource(val id: String, val name: String, val url: String, val epgUrl: String = "")

/**
 * 直播源列表和频道缓存。列表存 SharedPreferences；每个源下载下来的原文存一份文件，
 * 打开时先用缓存显示，联网失败也能看上次的频道。
 */
class LiveSources(private val context: Context) {
    private val prefs = context.getSharedPreferences("live", Context.MODE_PRIVATE)
    private val dir get() = File(context.filesDir, "live").apply { mkdirs() }

    fun list(): List<LiveSource> = try {
        val arr = JSONArray(prefs.getString("sources", "[]"))
        List(arr.length()) { i ->
            arr.getJSONObject(i).let {
                LiveSource(it.getString("id"), it.getString("name"), it.getString("url"), it.optString("epgUrl"))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<LiveSource>) = prefs.edit().putString(
        "sources",
        JSONArray(list.map {
            JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("epgUrl", it.epgUrl)
        }).toString(),
    ).apply()

    fun add(name: String, url: String, epgUrl: String = ""): LiveSource =
        LiveSource(UUID.randomUUID().toString(), name, url, epgUrl.trim()).also { save(list() + it) }

    /** 改名称、地址、节目单地址；地址变了旧的频道缓存不要了 */
    fun update(id: String, name: String, url: String, epgUrl: String = "") {
        val old = list().firstOrNull { it.id == id } ?: return
        save(list().map { if (it.id == id) it.copy(name = name, url = url, epgUrl = epgUrl.trim()) else it })
        if (old.url != url) File(dir, "$id.txt").delete()
    }

    fun remove(id: String) {
        save(list().filter { it.id != id })
        File(dir, "$id.txt").delete()
        File(File(context.filesDir, "live/epg"), "$id.json").delete()
    }

    // ---- 收藏的频道：存完整信息，换了直播源也还在 ----

    fun favorites(): List<LiveChannel> = try {
        val arr = JSONArray(prefs.getString("favorites", "[]"))
        List(arr.length()) { i ->
            arr.getJSONObject(i).let {
                LiveChannel(
                    it.getString("name"), it.getString("url"), it.optString("group"), it.optString("logo"),
                    it.optString("tvgId"), it.optString("ua"), it.optString("ref"),
                    it.optString("tvgName"), it.optString("catchup"), it.optString("catchupSource"),
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun setFavorites(list: List<LiveChannel>) = prefs.edit().putString(
        "favorites",
        JSONArray(list.map {
            JSONObject().put("name", it.name).put("url", it.url).put("group", it.group).put("logo", it.logo)
                .put("tvgId", it.tvgId).put("ua", it.userAgent).put("ref", it.referrer)
                .put("tvgName", it.tvgName).put("catchup", it.catchup).put("catchupSource", it.catchupSource)
        }).toString(),
    ).apply()

    var lastSourceId: String?
        get() = prefs.getString("last", null)
        set(v) = prefs.edit().putString("last", v).apply()

    fun cached(source: LiveSource): List<LiveChannel>? = cachedPlaylist(source)?.channels

    fun cachedPlaylist(source: LiveSource): ParsedPlaylist? =
        File(dir, "${source.id}.txt").takeIf { it.exists() }?.let { LivePlaylist.parseWithHeader(it.readText()) }

    /** 联网（或读本地文件）取最新的频道并更新缓存。在 IO 线程调用。 */
    fun fetch(source: LiveSource): List<LiveChannel> = fetchPlaylist(source).channels

    fun fetchPlaylist(source: LiveSource): ParsedPlaylist {
        val text = if (source.url.startsWith("content://") || source.url.startsWith("file://")) {
            context.contentResolver.openInputStream(Uri.parse(source.url))!!.use { it.readBytes() }.toString(Charsets.UTF_8)
        } else {
            val req = Request.Builder().url(source.url).header("User-Agent", "Mozilla/5.0 (Linux; Android) Voxtitle").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("服务器返回 HTTP ${resp.code}")
                resp.body!!.string()
            }
        }
        val parsed = LivePlaylist.parseWithHeader(text)
        if (parsed.channels.isEmpty()) throw java.io.IOException("没有解析到频道（支持 M3U 和「频道名,地址」TXT 格式）")
        File(dir, "${source.id}.txt").writeText(text)
        return parsed
    }

    companion object {
        private val client = com.aisub.player.net.AppProxy.builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }
}

/**
 * 频道要求的请求头（User-Agent、Referer），按地址的主机登记；播放器取流（含 m3u8 里的分片）时带上。
 */
object LiveHeaders {
    private val byHost = ConcurrentHashMap<String, Map<String, String>>()

    fun register(channel: LiveChannel) {
        val headers = buildMap {
            if (channel.userAgent.isNotBlank()) put("User-Agent", channel.userAgent)
            if (channel.referrer.isNotBlank()) put("Referer", channel.referrer)
        }
        val host = Uri.parse(channel.url).host ?: return
        if (headers.isEmpty()) byHost.remove(host) else byHost[host] = headers
    }

    /** 网页里找到的视频地址：带上网页的 Referer、User-Agent、Cookie，不然很多网站会拒绝 */
    fun put(url: String, headers: Map<String, String>) {
        val host = Uri.parse(url).host ?: return
        byHost[host] = headers
    }

    fun headersFor(url: String): Map<String, String> = Uri.parse(url).host?.let { byHost[it] }.orEmpty()
}
