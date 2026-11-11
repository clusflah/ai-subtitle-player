package android.net

import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.net.URLEncoder

class Uri private constructor(private val raw: String) : Comparable<Uri> {
    val scheme: String?
        get() {
            // 兼容 Windows 盘符路径 (如 C:\...)
            if (raw.length >= 2 && raw[0].isLetter() && raw[1] == ':') return "file"
            val idx = raw.indexOf(':')
            if (idx <= 0) return null
            val prefix = raw.substring(0, idx)
            return if (prefix.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) prefix.lowercase() else null
        }

    private val afterScheme: String
        get() {
            if (raw.length >= 2 && raw[0].isLetter() && raw[1] == ':') return "///" + raw.replace('\\', '/')
            val idx = raw.indexOf(':')
            return if (idx >= 0 && scheme != null) raw.substring(idx + 1) else raw
        }

    val authority: String?
        get() {
            val s = afterScheme
            if (!s.startsWith("//")) return null
            val rest = s.substring(2)
            val end = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
            return rest.substring(0, end).takeIf { it.isNotEmpty() }
        }

    val encodedAuthority: String? get() = authority

    fun toJavaFile(): File? {
        if (scheme == "file") {
            val p = path ?: return null
            return File(p)
        }
        if (raw.length >= 2 && raw[0].isLetter() && raw[1] == ':') {
            return File(raw)
        }
        return null
    }

    val host: String?
        get() {
            val auth = authority ?: return null
            val at = auth.lastIndexOf('@')
            val hostPort = if (at >= 0) auth.substring(at + 1) else auth
            if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                return if (close > 0) hostPort.substring(0, close + 1) else hostPort
            }
            val colon = hostPort.indexOf(':')
            return (if (colon >= 0) hostPort.substring(0, colon) else hostPort).takeIf { it.isNotEmpty() }
        }

    val port: Int
        get() {
            val auth = authority ?: return -1
            val at = auth.lastIndexOf('@')
            val hostPort = if (at >= 0) auth.substring(at + 1) else auth
            val colon = hostPort.lastIndexOf(':')
            if (colon < 0) return -1
            return hostPort.substring(colon + 1).toIntOrNull() ?: -1
        }

    val encodedPath: String?
        get() {
            val s = afterScheme
            val start = if (s.startsWith("//")) {
                val rest = s.substring(2)
                val slash = rest.indexOf('/')
                if (slash < 0) return ""
                2 + slash
            } else 0
            val q = s.indexOfAny(charArrayOf('?', '#'), start)
            val rawPath = if (q < 0) s.substring(start) else s.substring(start, q)
            return rawPath
        }

    val path: String?
        get() {
            val ep = encodedPath ?: return null
            val decoded = decode(ep) ?: ep
            // Windows file:///C:/... -> C:/...
            if (scheme == "file" && decoded.length >= 3 && decoded[0] == '/' && decoded[1].isLetter() && decoded[2] == ':') {
                return decoded.substring(1)
            }
            return decoded
        }

    val lastPathSegment: String?
        get() {
            val p = path?.trimEnd('/', '\\') ?: return null
            val idx = p.lastIndexOfAny(charArrayOf('/', '\\'))
            val seg = if (idx >= 0) p.substring(idx + 1) else p
            return seg.takeIf { it.isNotEmpty() }
        }

    val pathSegments: List<String>
        get() = path?.split('/', '\\')?.filter { it.isNotEmpty() } ?: emptyList()

    val encodedQuery: String?
        get() {
            val q = raw.indexOf('?')
            if (q < 0) return null
            val h = raw.indexOf('#', q + 1)
            return if (h < 0) raw.substring(q + 1) else raw.substring(q + 1, h)
        }

    val query: String?
        get() = encodedQuery?.let { decode(it) }

    val fragment: String?
        get() {
            val h = raw.indexOf('#')
            return if (h < 0) null else decode(raw.substring(h + 1))
        }

    val schemeSpecificPart: String
        get() = decode(afterScheme) ?: afterScheme

    fun getQueryParameter(key: String): String? = getQueryParameters(key).firstOrNull()

    fun getQueryParameters(key: String): List<String> {
        val eq = encodedQuery ?: return emptyList()
        if (eq.isEmpty()) return emptyList()
        val res = mutableListOf<String>()
        for (pair in eq.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val k = if (idx < 0) decode(pair) else decode(pair.substring(0, idx))
            if (k == key) {
                val v = if (idx < 0) "" else decode(pair.substring(idx + 1)).orEmpty()
                res.add(v)
            }
        }
        return res
    }

    val queryParameterNames: Set<String>
        get() {
            val eq = encodedQuery ?: return emptySet()
            if (eq.isEmpty()) return emptySet()
            val set = LinkedHashSet<String>()
            for (pair in eq.split('&')) {
                if (pair.isEmpty()) continue
                val idx = pair.indexOf('=')
                val k = if (idx < 0) decode(pair) else decode(pair.substring(0, idx))
                if (!k.isNullOrEmpty()) set.add(k)
            }
            return set
        }

    fun buildUpon(): Builder = Builder().also { b ->
        b.scheme = scheme
        b.authority = authority
        b.encodedPath = encodedPath
        b.encodedQuery = encodedQuery
        b.fragment = fragment
    }

    class Builder {
        internal var scheme: String? = null
        internal var authority: String? = null
        internal var encodedPath: String? = null
        internal var encodedQuery: String? = null
        internal var fragment: String? = null

        fun scheme(s: String?): Builder = apply { scheme = s }
        fun authority(a: String?): Builder = apply { authority = a }
        fun encodedAuthority(a: String?): Builder = apply { authority = a }
        fun path(p: String?): Builder = apply {
            encodedPath = p?.split('/')?.joinToString("/") { encode(it) ?: it }
        }
        fun encodedPath(p: String?): Builder = apply { encodedPath = p }
        fun appendPath(segment: String): Builder = apply {
            val enc = encode(segment) ?: segment
            val cur = encodedPath.orEmpty().trimEnd('/')
            encodedPath = if (cur.isEmpty()) "/$enc" else "$cur/$enc"
        }
        fun appendEncodedPath(segment: String): Builder = apply {
            val cur = encodedPath.orEmpty().trimEnd('/')
            encodedPath = if (cur.isEmpty()) "/$segment" else "$cur/$segment"
        }
        fun query(q: String?): Builder = apply { encodedQuery = q?.let { encode(it, "&=") } }
        fun encodedQuery(q: String?): Builder = apply { encodedQuery = q }
        fun clearQuery(): Builder = apply { encodedQuery = null }
        fun appendQueryParameter(key: String, value: String?): Builder = apply {
            val pair = "${encode(key)}=${encode(value.orEmpty())}"
            val cur = encodedQuery.orEmpty()
            encodedQuery = if (cur.isEmpty()) pair else "$cur&$pair"
        }
        fun fragment(f: String?): Builder = apply { fragment = f }

        fun build(): Uri {
            val sb = StringBuilder()
            if (!scheme.isNullOrEmpty()) {
                sb.append(scheme).append(':')
            }
            if (authority != null || scheme == "file" || scheme == "http" || scheme == "https") {
                sb.append("//")
                if (authority != null) sb.append(authority)
            }
            val p = encodedPath.orEmpty()
            if (p.isNotEmpty()) {
                if (sb.isNotEmpty() && !p.startsWith('/') && sb.endsWith('/')) {
                    sb.append(p)
                } else if (authority != null && !p.startsWith('/')) {
                    sb.append('/').append(p)
                } else {
                    sb.append(p)
                }
            }
            if (!encodedQuery.isNullOrEmpty()) {
                sb.append('?').append(encodedQuery)
            }
            if (!fragment.isNullOrEmpty()) {
                sb.append('#').append(encode(fragment))
            }
            return Uri(sb.toString())
        }
    }

    override fun toString(): String = raw
    override fun equals(other: Any?): Boolean = other is Uri && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()
    override fun compareTo(other: Uri): Int = raw.compareTo(other.raw)

    companion object {
        val EMPTY: Uri = Uri("")

        @JvmStatic
        fun parse(uriString: String?): Uri = Uri(uriString.orEmpty())

        @JvmStatic
        fun fromFile(file: File): Uri {
            val abs = file.absolutePath.replace('\\', '/')
            val p = if (abs.startsWith("/")) abs else "/$abs"
            return Uri("file://${encode(p, "/:")}")
        }

        private fun isUnreservedAscii(ch: Char, allow: String?): Boolean =
            (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch in "-_.~" || (allow != null && ch in allow)

        @JvmStatic
        fun encode(s: String?, allow: String? = null): String {
            if (s.isNullOrEmpty()) return ""
            val sb = StringBuilder(s.length + 16)
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (isUnreservedAscii(ch, allow)) {
                    sb.append(ch)
                    i++
                } else {
                    val start = i
                    while (i < s.length && !isUnreservedAscii(s[i], allow)) {
                        i++
                    }
                    val bytes = s.substring(start, i).toByteArray(Charsets.UTF_8)
                    for (b in bytes) {
                        sb.append('%')
                        val v = b.toInt() and 0xFF
                        sb.append("0123456789ABCDEF"[v ushr 4])
                        sb.append("0123456789ABCDEF"[v and 0x0F])
                    }
                }
            }
            return sb.toString()
        }

        @JvmStatic
        fun decode(s: String?): String {
            if (s == null) return ""
            if (!s.contains('%')) return s
            return runCatching {
                URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
            }.getOrDefault(s)
        }
    }
}

class Network
data class LinkAddress(val address: InetAddress)

class LinkProperties(val linkAddresses: List<LinkAddress>)

class NetworkCapabilities {
    fun hasCapability(capability: Int): Boolean = true

    companion object {
        const val NET_CAPABILITY_INTERNET = 12
    }
}

class ConnectivityManager {
    val activeNetwork: Network? = Network()

    fun getLinkProperties(network: Network?): LinkProperties? {
        val addrs = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { LinkAddress(it) }
        }.getOrDefault(emptyList())
        return LinkProperties(addrs)
    }

    fun getNetworkCapabilities(network: Network?): NetworkCapabilities? = NetworkCapabilities()

    open class NetworkCallback {
        open fun onAvailable(network: Network) {}
        open fun onLost(network: Network) {}
        open fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {}
        open fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {}
    }

    fun registerDefaultNetworkCallback(callback: NetworkCallback) {}
    fun unregisterNetworkCallback(callback: NetworkCallback) {}
}

object Proxy {
    const val PROXY_CHANGE_ACTION = "android.intent.action.PROXY_CHANGE"
}

