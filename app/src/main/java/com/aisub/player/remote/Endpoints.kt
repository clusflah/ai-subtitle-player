package com.aisub.player.remote

import com.aisub.player.net.AppProxy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 内网 / 外网双地址：服务器填了内网地址时，先试内网能不能连上，能连就走内网，否则走外网地址。
 * 结果按服务器缓存，网络切换（连上 / 断开 WiFi）时由 [invalidate] 清空，下次访问重新探测。
 * 历史、播放列表里存的是完整地址，播放前用 [rewrite] 换成当前该用的那个。
 */
object Endpoints {
    private val servers = ConcurrentHashMap<String, Server>()
    private val chosen = ConcurrentHashMap<String, String>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** 探测超时要短：在外面时内网地址连不上，不能让用户等太久 */
    private val probeHttp: OkHttpClient by lazy {
        AppProxy.builder()
            .connectTimeout(1200, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .callTimeout(1800, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun register(server: Server) {
        val old = servers.put(server.id, server)
        if (old == null || old.normalizedBase != server.normalizedBase || old.normalizedLan != server.normalizedLan) {
            chosen.remove(server.id)
        }
    }

    fun unregister(server: Server) {
        servers.remove(server.id)
        chosen.remove(server.id)
        locks.remove(server.id)
        com.aisub.player.net.LanTlsSupport.invalidateSessions()
    }

    /** 网络变了，之前的选择作废。 */
    fun invalidate() = chosen.clear()

    internal fun clearForTest() {
        servers.clear()
        chosen.clear()
        locks.clear()
        com.aisub.player.net.LanTlsSupport.invalidateSessions()
    }

    /**
     * 若 [host] 属于某台已登记服务器的 `lanUrl` 或 `baseUrl`，返回与其配对的另一侧主机名集合；
     * 用于双地址 HTTPS（如内网 IP 使用了外网域名的 Let's Encrypt 证书）的主机名校验配对。
     */
    fun pairedHostsFor(host: String?): Set<String> {
        val target = normalizeHost(host) ?: return emptySet()
        var out: MutableSet<String>? = null
        for (s in servers.values) {
            val baseHost = extractHost(s.normalizedBase)
            val lanHost = extractHost(s.normalizedLan)
            if (lanHost != null && lanHost == target && baseHost != null && baseHost != target) {
                (out ?: LinkedHashSet<String>().also { out = it }).add(baseHost)
            }
            if (baseHost != null && baseHost == target && lanHost != null && lanHost != target) {
                (out ?: LinkedHashSet<String>().also { out = it }).add(lanHost)
            }
        }
        return out ?: emptySet()
    }

    /** 仅当 [host] 为内网/本机私网地址且匹配已登记服务器时返回 true，绝不放行公网域名。 */
    fun isRegisteredPrivateServerHost(host: String?): Boolean {
        val target = normalizeHost(host) ?: return false
        if (!AppProxy.isPrivateOrLocalHost(target)) return false
        for (s in servers.values) {
            if (extractHost(s.normalizedLan) == target || extractHost(s.normalizedBase) == target) {
                return true
            }
        }
        return false
    }

    /** 当前应该用的地址（不带末尾 /）。第一次调用会联网探测，只能在后台线程用。 */
    fun base(server: Server): String {
        // 测试连接等尚未持久化登记的场景，也先记录以便 LAN TLS 校验证书与主机名能识别该服务器
        servers.putIfAbsent(server.id, server)
        val lan = server.normalizedLan
        if (lan.isEmpty()) return server.normalizedBase
        chosen[server.id]?.let { return it }
        val lock = locks.computeIfAbsent(server.id) { Any() }
        synchronized(lock) {
            chosen[server.id]?.let { return it }
            val pick = if (server.normalizedBase.isEmpty() || reachable(lan)) lan else server.normalizedBase
            chosen[server.id] = pick
            return pick
        }
    }

    /** 属于某台双地址服务器的完整地址，换成当前该用的地址；其他地址原样返回。可能联网探测。 */
    fun rewrite(url: String): String {
        for (s in servers.values) {
            if (s.normalizedLan.isEmpty()) continue
            // 长的前缀优先，避免一个地址是另一个的前缀时匹配错
            val from = listOf(s.normalizedLan, s.normalizedBase).filter { it.isNotEmpty() }
                .sortedByDescending { it.length }
                .firstOrNull { url.startsWith("$it/") || url.startsWith("$it?") || url == it } ?: continue
            val to = base(s)
            return if (from == to) url else to + url.substring(from.length)
        }
        return url
    }

    /** 能收到任何 HTTP 响应（包括 401、404）就算连得上。 */
    private fun reachable(base: String): Boolean = runCatching {
        probeHttp.newCall(Request.Builder().url("$base/").head().build()).execute().close()
        true
    }.getOrDefault(false)

    private fun extractHost(url: String): String? {
        if (url.isBlank()) return null
        val h = url.toHttpUrlOrNull()?.host ?: runCatching { URI(url).host }.getOrNull()
        return normalizeHost(h)
    }

    private fun normalizeHost(host: String?): String? =
        host?.trim()?.trim('[', ']')?.substringBefore('%')?.lowercase()?.takeIf { it.isNotEmpty() }
}
