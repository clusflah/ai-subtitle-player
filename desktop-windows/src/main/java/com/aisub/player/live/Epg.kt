package com.aisub.player.live

import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.SAXParserFactory

enum class EpgStatus(val label: String) {
    PAST("已播放"),
    LIVE("正在直播"),
    UPCOMING("未播放"),
}

/** 一档电视节目。startMs / endMs 为毫秒时间戳。 */
data class EpgProgram(
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val desc: String = "",
) {
    fun statusAt(nowMs: Long): EpgStatus = when {
        nowMs < startMs -> EpgStatus.UPCOMING
        nowMs < endMs -> EpgStatus.LIVE
        else -> EpgStatus.PAST
    }

    fun progressAt(nowMs: Long): Float {
        val total = endMs - startMs
        if (total <= 0L) return 0f
        return ((nowMs - startMs).toFloat() / total).coerceIn(0f, 1f)
    }

    /** 时长说明，例如「时长: 1小时30分钟」「时长: 35分钟」 */
    fun durationLabel(): String {
        val mins = ((endMs - startMs + 30_000L) / 60_000L).toInt().coerceAtLeast(1)
        val h = mins / 60
        val m = mins % 60
        val text = when {
            h > 0 && m > 0 -> "${h}小时${m}分钟"
            h > 0 -> "${h}小时"
            else -> "${m}分钟"
        }
        return "时长: $text"
    }

    fun startClock(tz: TimeZone = TimeZone.getDefault()): String = format(startMs, "HH:mm", tz)

    fun endClock(tz: TimeZone = TimeZone.getDefault()): String = format(endMs, "HH:mm", tz)

    fun dateKey(tz: TimeZone = TimeZone.getDefault()): String = format(startMs, "yyyy-MM-dd", tz)

    fun timeRangeLabel(tz: TimeZone = TimeZone.getDefault()): String = "${startClock(tz)} - ${endClock(tz)}"

    fun dateTimeRangeLabel(tz: TimeZone = TimeZone.getDefault()): String =
        "${dateKey(tz)} ${startClock(tz)} - ${endClock(tz)}"

    fun toJson(): JSONObject = JSONObject()
        .put("s", startMs)
        .put("e", endMs)
        .put("t", title)
        .apply { if (desc.isNotEmpty()) put("d", desc) }

    companion object {
        fun fromJson(o: JSONObject) = EpgProgram(
            startMs = o.optLong("s"),
            endMs = o.optLong("e"),
            title = o.optString("t"),
            desc = o.optString("d"),
        )

        private fun format(ms: Long, pattern: String, tz: TimeZone): String =
            SimpleDateFormat(pattern, Locale.US).apply { timeZone = tz }.format(Date(ms))
    }
}

/**
 * 频道名规范化与匹配：
 * 把「CCTV-1 综合」「CCTV1高清」「CCTV-5+ 体育赛事」「CCTV5-4K」「湖南卫视 HD」统一成能和节目单对上的标准名。
 */
object EpgMatcher {
    private val BRACKETS = Regex("""\[[^\]]*\]|\([^)]*\)|【[^】]*】|（[^）]*）""")
    private val CCTV_NUM = Regex("""^cctv[-_\s]*(\d+\+?)(?:[-_\s]*(?:4k|8k|hd|fhd|uhd)|[^0-9+].*)?$""")
    private val CCTV_4K = Regex("""^cctv[-_\s]*(4k|8k).*$""")
    private val CETV_NUM = Regex("""^(?:cetv|中国教育\s*电视台|中国教育)[-_\s]*(\d+).*$""")
    private val NOISE = Regex(
        """(?i)(?:[-_\s]+(?:4k|8k|fhd|uhd|hd|sd|hevc|h265|h264|ipv4|ipv6|50fps|60fps)|(?:4k|8k|fhd|uhd|hd|hevc|ipv4|ipv6)$|超高清|高清|标清|蓝光|极速|频道|电视台|直播|备用|测试)""",
    )
    private val PUNCT = Regex("""[\s\-_./|·:：]+""")

    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        val half = buildString(raw.length) {
            for (c in raw.trim()) {
                append(
                    when (c) {
                        '\u3000' -> ' '
                        in '\uFF01'..'\uFF5E' -> (c.code - 0xFEE0).toChar()
                        else -> c
                    },
                )
            }
        }.lowercase(Locale.ROOT)
        val noBracket = BRACKETS.replace(half, " ").trim()
        CCTV_NUM.matchEntire(noBracket)?.let { return "cctv${it.groupValues[1]}" }
        CCTV_4K.matchEntire(noBracket)?.let { return "cctv${it.groupValues[1]}" }
        CETV_NUM.matchEntire(noBracket)?.let { return "cetv${it.groupValues[1]}" }

        var cleaned = NOISE.replace(noBracket, "")
        cleaned = PUNCT.replace(cleaned, "")
        if (cleaned.isEmpty()) cleaned = PUNCT.replace(noBracket, "")
        return cleaned
    }

    /** 一个频道可能匹配的所有键（原样小写 + 规范化名称）。 */
    fun keysOf(channel: LiveChannel): Set<String> = buildSet {
        for (raw in listOf(channel.tvgId, channel.tvgName, channel.name)) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            add(trimmed.lowercase(Locale.ROOT))
            val norm = normalize(trimmed)
            if (norm.isNotEmpty()) add(norm)
        }
    }

    /** 给 DIYP JSON 接口查询用的标准频道名（例如 "CCTV1"、"CCTV5+"、"湖南卫视"）。 */
    fun queryName(channel: LiveChannel): String {
        for (raw in listOf(channel.tvgName, channel.name, channel.tvgId)) {
            val norm = normalize(raw)
            if (norm.startsWith("cctv")) return norm.uppercase(Locale.ROOT)
            if (norm.startsWith("cetv")) return norm.uppercase(Locale.ROOT)
        }
        val base = channel.tvgName.ifBlank { channel.name }.trim()
        val noBracket = BRACKETS.replace(base, "").trim()
        val cleaned = NOISE.replace(noBracket, "").trim(' ', '-', '_', '·')
        return cleaned.ifEmpty { base }
    }
}

/**
 * 流式解析 XMLTV 节目单：
 * 解压后的 XML 可能有几十 MB，用 SAX 一边读一边按当前源的频道和最近几天的时间窗口过滤，不占内存。
 */
object XmlTvParser {
    private val TIME_RE = Regex("""^(\d{12,14})(?:\s*([+-]\d{2}:?\d{2}|Z|UTC|GMT))?""", RegexOption.IGNORE_CASE)

    /**
     * 返回「匹配键 → 节目列表（按开始时间排序）」。
     * [channels] 非空时只保留这些频道能匹配上的节目；为空时保留全部频道。
     */
    fun parse(
        input: InputStream,
        channels: List<LiveChannel> = emptyList(),
        windowStartMs: Long = 0L,
        windowEndMs: Long = Long.MAX_VALUE,
    ): Map<String, List<EpgProgram>> {
        val wantedKeys: Set<String> = if (channels.isEmpty()) emptySet() else channels.flatMapTo(HashSet()) { EpgMatcher.keysOf(it) }
        // channel id -> 这个频道对应的所有匹配键（id 小写、id 规范化、各个 display-name 的小写和规范化）
        val channelKeys = HashMap<String, MutableSet<String>>()
        // 匹配键 -> 节目列表（同一个频道的多个键指向同一个列表）
        val programsById = HashMap<String, MutableList<EpgProgram>>()

        fun registerKeys(id: String, names: List<String>): MutableSet<String> {
            val all = LinkedHashSet<String>()
            for (s in listOf(id) + names) {
                val t = s.trim()
                if (t.isEmpty()) continue
                all += t.lowercase(Locale.ROOT)
                val n = EpgMatcher.normalize(t)
                if (n.isNotEmpty()) all += n
            }
            val matched = if (wantedKeys.isEmpty()) all else all.intersect(wantedKeys)
            return matched.toMutableSet()
        }

        val handler = object : DefaultHandler() {
            private var curChannelId: String? = null
            private val curDisplayNames = ArrayList<String>()

            private var progChannel: String? = null
            private var progStart: Long = 0L
            private var progEnd: Long = 0L
            private var progTitle = ""
            private var progDesc = ""

            private var textTag: String? = null
            private val textBuf = StringBuilder()

            override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                InputSource(StringReader(""))

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                val tag = (localName?.ifEmpty { qName } ?: qName).orEmpty().lowercase(Locale.ROOT)
                when (tag) {
                    "channel" -> {
                        curChannelId = attributes.getValue("id")?.trim()
                        curDisplayNames.clear()
                    }
                    "programme" -> {
                        val ch = attributes.getValue("channel")?.trim().orEmpty()
                        if (ch.isEmpty()) return
                        val keys = channelKeys.getOrPut(ch) { registerKeys(ch, emptyList()) }
                        if (keys.isEmpty()) return
                        val start = parseXmlTvTime(attributes.getValue("start").orEmpty()) ?: return
                        val stop = parseXmlTvTime(attributes.getValue("stop").orEmpty()) ?: (start + 3600_000L)
                        if (stop <= windowStartMs || start >= windowEndMs || stop <= start) return
                        progChannel = ch
                        progStart = start
                        progEnd = stop
                        progTitle = ""
                        progDesc = ""
                    }
                    "display-name", "title", "desc" -> {
                        if (curChannelId != null || progChannel != null) {
                            textTag = tag
                            textBuf.setLength(0)
                        }
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (textTag != null) textBuf.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val tag = (localName?.ifEmpty { qName } ?: qName).orEmpty().lowercase(Locale.ROOT)
                when (tag) {
                    "display-name" -> if (curChannelId != null) {
                        val name = textBuf.toString().trim()
                        if (name.isNotEmpty()) curDisplayNames += name
                        textTag = null
                    }
                    "title" -> if (progChannel != null) {
                        if (progTitle.isEmpty()) progTitle = textBuf.toString().trim()
                        textTag = null
                    }
                    "desc" -> if (progChannel != null) {
                        if (progDesc.isEmpty()) progDesc = textBuf.toString().trim()
                        textTag = null
                    }
                    "channel" -> {
                        val id = curChannelId
                        if (!id.isNullOrEmpty()) {
                            channelKeys[id] = registerKeys(id, curDisplayNames)
                        }
                        curChannelId = null
                    }
                    "programme" -> {
                        val ch = progChannel
                        if (ch != null && progTitle.isNotEmpty()) {
                            programsById.getOrPut(ch) { ArrayList() }
                                .add(EpgProgram(progStart, progEnd, progTitle, progDesc))
                        }
                        progChannel = null
                    }
                }
            }
        }

        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        factory.newSAXParser().parse(InputSource(input), handler)

        val result = HashMap<String, List<EpgProgram>>()
        for ((chId, rawList) in programsById) {
            val sorted = rawList.sortedBy { it.startMs }.distinctBy { it.startMs }
            val keys = channelKeys[chId] ?: continue
            for (k in keys) {
                val existing = result[k]
                if (existing == null || sorted.size > existing.size) result[k] = sorted
            }
        }
        return result
    }

    /** 解析 XMLTV 时间格式：`20240715200000 +0800` 或 `202407152000`。 */
    fun parseXmlTvTime(raw: String): Long? {
        val m = TIME_RE.find(raw.trim()) ?: return null
        val digits = m.groupValues[1].padEnd(14, '0')
        val tzPart = m.groupValues[2].uppercase(Locale.ROOT)
        val tz = when {
            tzPart.isEmpty() -> TimeZone.getDefault()
            tzPart == "Z" || tzPart == "UTC" || tzPart == "GMT" -> TimeZone.getTimeZone("GMT")
            else -> TimeZone.getTimeZone("GMT" + if (':' in tzPart) tzPart else tzPart.substring(0, 3) + ":" + tzPart.substring(3))
        }
        return runCatching {
            val cal = Calendar.getInstance(tz)
            cal.set(Calendar.YEAR, digits.substring(0, 4).toInt())
            cal.set(Calendar.MONTH, digits.substring(4, 6).toInt() - 1)
            cal.set(Calendar.DAY_OF_MONTH, digits.substring(6, 8).toInt())
            cal.set(Calendar.HOUR_OF_DAY, digits.substring(8, 10).toInt())
            cal.set(Calendar.MINUTE, digits.substring(10, 12).toInt())
            cal.set(Calendar.SECOND, digits.substring(12, 14).toInt())
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }.getOrNull()
    }
}

/**
 * DIYP / 百川 JSON 节目单格式：
 * `GET https://…/?ch=CCTV1&date=2024-07-15`
 * 返回 `{"date":"2024-07-15","epg_data":[{"title":"…","start":"20:00","end":"21:30","desc":"…"}]}`
 */
object DiypEpg {
    fun parseJson(json: String, fallbackDate: String, tz: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")): List<EpgProgram> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val date = root.optString("date").takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) } ?: fallbackDate
        val arr = root.optJSONArray("epg_data") ?: root.optJSONArray("programmes") ?: root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<EpgProgram>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            val startStr = o.optString("start").trim()
            val endStr = o.optString("end").trim()
            if (title.isEmpty() || startStr.isEmpty()) continue
            val startMs = parseDayClock(date, startStr, tz) ?: continue
            var endMs = parseDayClock(date, endStr, tz) ?: (startMs + 1800_000L)
            if (endMs <= startMs) endMs += 86_400_000L
            out += EpgProgram(startMs, endMs, title, o.optString("desc").trim())
        }
        return out.sortedBy { it.startMs }
    }

    private fun parseDayClock(date: String, clock: String, tz: TimeZone): Long? = runCatching {
        if (clock == "24:00") {
            val base = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }.parse("$date 00:00")!!.time
            base + 86_400_000L
        } else {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }.parse("$date $clock")!!.time
        }
    }.getOrNull()
}

/**
 * 直播回看地址生成：支持 M3U 的 `catchup` / `catchup-source` 时间占位符（`${(b)yyyyMMddHHmmss}`、`{utc}` 等）。
 */
object CatchupUrl {
    private val FMT_TOKEN = Regex("""\$\{\(([be])\)([^}]+)\}""")

    fun canCatchup(channel: LiveChannel): Boolean =
        channel.catchupSource.isNotBlank() || channel.catchup.lowercase(Locale.ROOT) in setOf("append", "default", "shift", "flussonic")

    fun build(channel: LiveChannel, program: EpgProgram, tz: TimeZone = TimeZone.getDefault()): String? {
        if (!canCatchup(channel)) return null
        val type = channel.catchup.lowercase(Locale.ROOT)
        val template = channel.catchupSource.ifBlank {
            when (type) {
                "shift" -> "?utc={utc}&lutc={lutc}"
                else -> return null
            }
        }
        val expanded = expand(template, program.startMs, program.endMs, tz)
        return when {
            expanded.startsWith("http://") || expanded.startsWith("https://") || expanded.startsWith("rtsp://") -> expanded
            expanded.startsWith("?") || expanded.startsWith("&") -> {
                val sep = if ('?' in channel.url) '&' else '?'
                channel.url + sep + expanded.trimStart('?', '&')
            }
            type == "append" -> channel.url + expanded
            else -> expanded
        }
    }

    internal fun expand(template: String, startMs: Long, endMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val nowSec = System.currentTimeMillis() / 1000L
        val startSec = startMs / 1000L
        val endSec = endMs / 1000L
        val durSec = (endSec - startSec).coerceAtLeast(1L)
        var s = FMT_TOKEN.replace(template) { m ->
            val targetMs = if (m.groupValues[1] == "b") startMs else endMs
            val pattern = m.groupValues[2]
            runCatching { SimpleDateFormat(pattern, Locale.US).apply { timeZone = tz }.format(Date(targetMs)) }
                .getOrDefault(m.value)
        }
        fun fmt(ms: Long, p: String) = SimpleDateFormat(p, Locale.US).apply { timeZone = tz }.format(Date(ms))
        s = s
            .replace("\${timestamp}", startSec.toString())
            .replace("{utc}", startSec.toString())
            .replace("{start}", startSec.toString())
            .replace("\${end}", endSec.toString())
            .replace("{utcend}", endSec.toString())
            .replace("{end}", endSec.toString())
            .replace("{lutc}", nowSec.toString())
            .replace("\${lutc}", nowSec.toString())
            .replace("\${duration}", durSec.toString())
            .replace("{duration}", durSec.toString())
            .replace("{offset}", (nowSec - startSec).coerceAtLeast(0L).toString())
            .replace("\${offset}", (nowSec - startSec).coerceAtLeast(0L).toString())
            .replace("{Y}", fmt(startMs, "yyyy"))
            .replace("{m}", fmt(startMs, "MM"))
            .replace("{d}", fmt(startMs, "dd"))
            .replace("{H}", fmt(startMs, "HH"))
            .replace("{M}", fmt(startMs, "mm"))
            .replace("{S}", fmt(startMs, "ss"))
        return s
    }
}
