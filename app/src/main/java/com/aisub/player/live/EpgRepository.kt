package com.aisub.player.live

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.aisub.player.media.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 直播节目单（EPG）仓库：
 * 1. 管理「频道地址 → LiveChannel」登记，播放页拿到 MediaEntry 也能反查频道的 tvg-id、分组和回看模板；
 * 2. 下载并流式解析 XMLTV（支持 .xml 和 .xml.gz），按直播源缓存到本地；
 * 3. 对没有 XMLTV 节目单的频道（例如 TXT 直播源），按频道名向 DIYP 接口按需查询当天节目单。
 */
object EpgRepository {
    private const val CACHE_TTL_MS = 6 * 3600_000L
    private const val WINDOW_PAST_MS = 36 * 3600_000L
    private const val WINDOW_FUTURE_MS = 48 * 3600_000L

    /** 内置公开 DIYP 节目单接口（当直播源没带 x-tvg-url 或 XMLTV 里没有该频道时按需查询） */
    private val DEFAULT_DIYP_APIS = listOf(
        "https://epg.112114.xyz/",
        "https://diyp.112114.xyz/",
    )

    private val client = com.aisub.player.net.AppProxy.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val byUrl = ConcurrentHashMap<String, LiveChannel>()
    private val programsByKey = ConcurrentHashMap<String, List<EpgProgram>>()
    private val diypTriedAt = ConcurrentHashMap<String, Long>()
    private val diypGate = Semaphore(3)

    /** 节目单数据更新计数：Compose 界面读取它以在节目单加载完成后自动刷新 */
    var version by mutableIntStateOf(0)
        private set

    private fun bumpVersion() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { version++ }
    }

    fun register(channel: LiveChannel) {
        byUrl[channel.url] = channel
    }

    fun registerAll(channels: List<LiveChannel>) {
        channels.forEach { byUrl[it.url] = it }
    }

    fun channelOf(entry: MediaEntry): LiveChannel =
        byUrl[entry.uri] ?: LiveChannel(name = entry.title, url = entry.uri, group = entry.folder, logo = entry.logo)

    /** 查询某个频道的完整节目单（按开始时间升序）。 */
    fun programsFor(channel: LiveChannel): List<EpgProgram> {
        for (k in EpgMatcher.keysOf(channel)) {
            val list = programsByKey[k]
            if (!list.isNullOrEmpty()) return list
        }
        return emptyList()
    }

    fun programsFor(entry: MediaEntry): List<EpgProgram> = programsFor(channelOf(entry))

    /** 当前正在播出的节目。 */
    fun currentProgram(channel: LiveChannel, nowMs: Long = System.currentTimeMillis()): EpgProgram? =
        programsFor(channel).firstOrNull { nowMs in it.startMs until it.endMs }

    fun currentProgram(entry: MediaEntry, nowMs: Long = System.currentTimeMillis()): EpgProgram? =
        currentProgram(channelOf(entry), nowMs)

    /** 下一档即将播出的节目。 */
    fun nextProgram(channel: LiveChannel, nowMs: Long = System.currentTimeMillis()): EpgProgram? =
        programsFor(channel).firstOrNull { it.startMs > nowMs }

    /**
     * 加载某个直播源的节目单：
     * 先读本地缓存立即显示，缓存过期或为空时后台下载 XMLTV。
     */
    suspend fun loadSourceEpg(
        context: Context,
        source: LiveSource,
        channels: List<LiveChannel>,
        headerEpgUrls: List<String> = emptyList(),
        defaultEpgUrl: String = "",
        force: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        registerAll(channels)
        val cacheFile = cacheFile(context, source.id)
        val cachedValid = !force && loadDiskCache(cacheFile)
        val urls = buildList {
            source.epgUrl.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
            headerEpgUrls.forEach { if (it !in this) add(it) }
            defaultEpgUrl.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { if (it !in this) add(it) }
        }
        if (cachedValid || urls.isEmpty()) return@withContext

        val now = System.currentTimeMillis()
        for (url in urls) {
            if (!looksLikeXmlTv(url)) continue
            val parsed = runCatching { fetchXmlTv(url, channels, now - WINDOW_PAST_MS, now + WINDOW_FUTURE_MS) }.getOrNull()
            if (!parsed.isNullOrEmpty()) {
                for ((k, v) in parsed) {
                    val existing = programsByKey[k]
                    if (existing == null || v.size > existing.size) programsByKey[k] = v
                }
                saveDiskCache(cacheFile, parsed)
                bumpVersion()
                break
            }
        }
    }

    /**
     * 确保单个频道有当天节目单：如果 XMLTV 里没有这个频道，按频道名去 DIYP JSON 接口查当天节目。
     */
    suspend fun ensureChannel(
        channel: LiveChannel,
        customEpgUrl: String = "",
        defaultEpgUrl: String = "",
    ): List<EpgProgram> = withContext(Dispatchers.IO) {
        register(channel)
        val existing = programsFor(channel)
        val now = System.currentTimeMillis()
        if (existing.any { it.endMs > now }) return@withContext existing

        val qName = EpgMatcher.queryName(channel)
        val normKey = EpgMatcher.normalize(qName).ifEmpty { qName.lowercase(Locale.ROOT) }
        val lastTried = diypTriedAt[normKey] ?: 0L
        if (now - lastTried < 15 * 60_000L) return@withContext existing
        diypTriedAt[normKey] = now

        val apis = buildList {
            for (u in listOf(customEpgUrl, defaultEpgUrl)) {
                u.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() && !looksLikeXmlTv(it) }.forEach { add(it) }
            }
            DEFAULT_DIYP_APIS.forEach { if (it !in this) add(it) }
        }
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.format(Date(now))
        diypGate.withPermit {
            for (api in apis) {
                val list = runCatching { fetchDiyp(api, qName, today, tz) }.getOrNull()
                if (!list.isNullOrEmpty()) {
                    for (k in EpgMatcher.keysOf(channel) + normKey) {
                        programsByKey[k] = list
                    }
                    bumpVersion()
                    return@withPermit list
                }
            }
            existing
        }
    }

    private fun looksLikeXmlTv(url: String): Boolean {
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        return path.endsWith(".xml") || path.endsWith(".xml.gz") || path.endsWith(".gz") || path.contains("xmltv") || path.contains("/epg.xml")
    }

    private fun fetchXmlTv(
        url: String,
        channels: List<LiveChannel>,
        windowStartMs: Long,
        windowEndMs: Long,
    ): Map<String, List<EpgProgram>> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) Voxtitle")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val rawStream = resp.body?.byteStream() ?: return emptyMap()
            val buffered = BufferedInputStream(rawStream, 64 * 1024)
            buffered.mark(4)
            val b0 = buffered.read()
            val b1 = buffered.read()
            buffered.reset()
            val isGzip = (b0 == 0x1f && b1 == 0x8b) ||
                resp.header("Content-Encoding")?.contains("gzip", ignoreCase = true) == true
            val stream = if (isGzip) GZIPInputStream(buffered, 32 * 1024) else buffered
            stream.use { return XmlTvParser.parse(it, channels, windowStartMs, windowEndMs) }
        }
    }

    private fun fetchDiyp(baseUrl: String, channelName: String, date: String, tz: TimeZone): List<EpgProgram> {
        val httpUrl = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("ch", channelName)
            ?.addQueryParameter("date", date)
            ?.build() ?: return emptyList()
        val req = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) Voxtitle")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string().orEmpty()
            return DiypEpg.parseJson(body, date, tz)
        }
    }

    private fun cacheFile(context: Context, sourceId: String): File {
        val dir = File(context.filesDir, "live/epg").apply { mkdirs() }
        return File(dir, "$sourceId.json")
    }

    private fun loadDiskCache(file: File): Boolean = runCatching {
        if (!file.exists()) return false
        val root = JSONObject(file.readText())
        val savedAt = root.optLong("savedAt")
        val data = root.optJSONObject("channels") ?: return false
        val now = System.currentTimeMillis()
        var loaded = 0
        for (key in data.keys()) {
            val arr = data.optJSONArray(key) ?: continue
            val list = ArrayList<EpgProgram>(arr.length())
            for (i in 0 until arr.length()) {
                val p = EpgProgram.fromJson(arr.getJSONObject(i))
                if (p.endMs > now - WINDOW_PAST_MS) list += p
            }
            if (list.isNotEmpty()) {
                programsByKey[key] = list
                loaded++
            }
        }
        if (loaded > 0) bumpVersion()
        loaded > 0 && (now - savedAt) < CACHE_TTL_MS
    }.getOrDefault(false)

    private fun saveDiskCache(file: File, map: Map<String, List<EpgProgram>>) = runCatching {
        val channelsObj = JSONObject()
        // 同一个 List 实例可能挂在多个别名键下，都写进去方便离线直接按任意键查
        for ((k, list) in map) {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            channelsObj.put(k, arr)
        }
        val root = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("channels", channelsObj)
        file.writeText(root.toString())
    }
}
