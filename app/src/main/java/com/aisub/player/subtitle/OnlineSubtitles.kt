package com.aisub.player.subtitle

import android.content.Context
import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** 一条在线字幕搜索结果。 */
data class OnlineSubtitle(
    val source: OnlineSubtitleSource,
    /** 来源内的 ID：OpenSubtitles 为 file_id，射手网为字幕 ID */
    val id: String,
    val title: String,
    val language: String,
    /** 补充信息：下载次数、发布组、上传时间等 */
    val info: String,
    /** 和视频文件哈希完全匹配（时间轴基本不用调） */
    val exactMatch: Boolean = false,
)

enum class OnlineSubtitleSource(val label: String) { OPENSUBTITLES("OpenSubtitles"), ASSRT("射手网") }

/**
 * 在线字幕搜索与下载：OpenSubtitles.com（外语片多）和射手网 assrt.net（中文字幕多）。
 * 两者都需要用户自己申请的免费 Key / Token。下载结果统一转成纯文本，交给 [SubtitleParser]。
 */
class OnlineSubtitles(
    private val osApiKey: String,
    private val osUsername: String,
    private val osPassword: String,
    private val assrtToken: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 已配置的来源。 */
    val sources: List<OnlineSubtitleSource>
        get() = listOfNotNull(
            OnlineSubtitleSource.ASSRT.takeIf { assrtToken.isNotBlank() },
            OnlineSubtitleSource.OPENSUBTITLES.takeIf { osApiKey.isNotBlank() },
        )

    fun search(source: OnlineSubtitleSource, query: String, languages: String, movieHash: String?): List<OnlineSubtitle> =
        when (source) {
            OnlineSubtitleSource.OPENSUBTITLES -> searchOpenSubtitles(query, languages, movieHash)
            OnlineSubtitleSource.ASSRT -> searchAssrt(query)
        }

    /** 下载并返回字幕文件文本（SRT / VTT / ASS）。 */
    fun download(sub: OnlineSubtitle): String = when (sub.source) {
        OnlineSubtitleSource.OPENSUBTITLES -> downloadOpenSubtitles(sub.id)
        OnlineSubtitleSource.ASSRT -> downloadAssrt(sub.id)
    }

    // ---------------- OpenSubtitles.com ----------------

    private var osToken: String? = null

    private fun osRequest(path: String) = Request.Builder()
        .url(OS_BASE + path)
        .header("Api-Key", osApiKey.trim())
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")
        .apply { osToken?.let { header("Authorization", "Bearer $it") } }

    private fun searchOpenSubtitles(query: String, languages: String, movieHash: String?): List<OnlineSubtitle> {
        // 官方建议：参数按字母序、全部小写，否则会被重定向
        val params = sortedMapOf<String, String>()
        languages.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.sorted().joinToString(",")
            .takeIf { it.isNotEmpty() }?.let { params["languages"] = it }
        movieHash?.let { params["moviehash"] = it }
        if (query.isNotBlank()) params["query"] = query.trim().lowercase()
        val url = (OS_BASE + "/subtitles").toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val json = call(osRequest("/subtitles").url(url).get().build())
        val data = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<OnlineSubtitle>()
        for (i in 0 until data.length()) {
            val a = data.getJSONObject(i).getJSONObject("attributes")
            val files = a.optJSONArray("files") ?: continue
            val feature = a.optJSONObject("feature_details")
            for (j in 0 until files.length()) {
                val f = files.getJSONObject(j)
                val name = f.optString("file_name").ifEmpty { a.optString("release") }
                out += OnlineSubtitle(
                    source = OnlineSubtitleSource.OPENSUBTITLES,
                    id = f.getLong("file_id").toString(),
                    title = name.ifEmpty { feature?.optString("title").orEmpty() },
                    language = languageName(a.optString("language")),
                    info = listOfNotNull(
                        feature?.optString("title")?.takeIf { it.isNotEmpty() && it != name },
                        "下载 ${a.optInt("download_count")} 次",
                        "AI 翻译".takeIf { a.optBoolean("ai_translated") || a.optBoolean("machine_translated") },
                        "听障".takeIf { a.optBoolean("hearing_impaired") },
                        a.optString("upload_date").take(10).takeIf { it.isNotEmpty() },
                    ).joinToString(" · "),
                    exactMatch = a.optBoolean("moviehash_match"),
                )
            }
        }
        return out
    }

    private fun downloadOpenSubtitles(fileId: String): String {
        if (osToken == null && osUsername.isNotBlank() && osPassword.isNotBlank()) login()
        val body = JSONObject().put("file_id", fileId.toLong()).put("sub_format", "srt").toString()
            .toRequestBody(JSON)
        val json = call(osRequest("/download").post(body).build())
        val link = json.optString("link").ifEmpty {
            throw IllegalStateException(json.optString("message").ifEmpty { "没有拿到下载地址" })
        }
        return decodeSubtitleBytes(fetch(link), link)
    }

    private fun login() {
        val body = JSONObject().put("username", osUsername.trim()).put("password", osPassword).toString().toRequestBody(JSON)
        val json = call(osRequest("/login").post(body).build())
        osToken = json.optString("token").ifEmpty { null }
    }

    // ---------------- 射手网 assrt.net ----------------

    private fun searchAssrt(query: String): List<OnlineSubtitle> {
        val q = query.trim()
        if (q.length < 3) throw IllegalArgumentException("射手网要求关键词至少 3 个字")
        val url = "$ASSRT_BASE/sub/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("cnt", "15")
            .addQueryParameter("is_file", "1")
            .build()
        val json = assrt(url.toString())
        val subs = json.optJSONObject("sub")?.optJSONArray("subs") ?: return emptyList()
        return (0 until subs.length()).map { i ->
            val s = subs.getJSONObject(i)
            OnlineSubtitle(
                source = OnlineSubtitleSource.ASSRT,
                id = s.get("id").toString(),
                title = s.optString("native_name").ifEmpty { s.optString("videoname") },
                language = s.optJSONObject("lang")?.optString("desc")?.trim()?.replace(Regex("\\s+"), " ").orEmpty(),
                info = listOfNotNull(
                    s.optString("videoname").takeIf { it.isNotEmpty() && it != s.optString("native_name") },
                    s.optString("subtype").takeIf { it.isNotEmpty() },
                    s.optString("release_site").takeIf { it.isNotEmpty() },
                    s.optString("upload_time").take(10).takeIf { it.isNotEmpty() },
                ).joinToString(" · "),
            )
        }
    }

    private fun downloadAssrt(id: String): String {
        val url = "$ASSRT_BASE/sub/detail".toHttpUrl().newBuilder().addQueryParameter("id", id).build()
        val sub = assrt(url.toString()).optJSONObject("sub")?.optJSONArray("subs")?.optJSONObject(0)
            ?: throw IllegalStateException("找不到这条字幕")
        // 压缩包会列出里面每个文件的单独下载地址，挑一个最合适的字幕文件
        val files = sub.optJSONArray("filelist")
        if (files != null && files.length() > 0) {
            val candidates = (0 until files.length()).map { files.getJSONObject(it) }
                .filter { SUB_EXT.containsMatchIn(it.optString("f")) }
            val best = candidates.maxByOrNull { scoreName(it.optString("f")) }
                ?: throw IllegalStateException("压缩包里没有可用的字幕文件")
            val link = best.getString("url")
            return decodeSubtitleBytes(fetch(link), best.optString("f"))
        }
        val link = sub.optString("url").ifEmpty { throw IllegalStateException("没有下载地址") }
        return decodeSubtitleBytes(fetch(link), sub.optString("filename").ifEmpty { link })
    }

    private fun assrt(url: String): JSONObject {
        val json = call(Request.Builder().url(url).header("Authorization", "Bearer ${assrtToken.trim()}").get().build())
        val status = json.optInt("status", 0)
        if (status != 0) {
            throw IllegalStateException(
                when (status) {
                    20001 -> "射手网 Token 无效，请到设置里检查"
                    20900 -> "射手网上找不到这条字幕"
                    101 -> "关键词太短（至少 3 个字）"
                    30900 -> "射手网请求太频繁（每分钟 20 次），请稍后再试"
                    else -> "射手网返回错误 $status ${json.optString("errmsg")}"
                },
            )
        }
        return json
    }

    // ---------------- 通用 ----------------

    private fun call(request: Request): JSONObject = http.newCall(request).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        // 射手网出错时返回 4xx，但正文里有具体错误码，交给调用方解析
        val assrtError = runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.has("errmsg") && it.optInt("status", 0) != 0 }
        if (assrtError != null) return@use assrtError
        if (!resp.isSuccessful) {
            val msg = runCatching { JSONObject(text).let { it.optString("message").ifEmpty { it.optString("errors") } } }.getOrNull()
            throw IllegalStateException(
                when (resp.code) {
                    401, 403 -> "Key 或账号无效（HTTP ${resp.code}）" + (msg?.let { "：$it" } ?: "")
                    406 -> "今天的免费下载次数用完了" + (msg?.let { "：$it" } ?: "")
                    429 -> "请求太频繁，请稍后再试"
                    else -> "服务器返回 HTTP ${resp.code}" + (msg?.takeIf { it.isNotEmpty() }?.let { "：$it" } ?: "")
                },
            )
        }
        JSONObject(text)
    }

    private fun fetch(url: String): ByteArray =
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("下载失败（HTTP ${resp.code}）")
            resp.body!!.bytes()
        }

    companion object {
        private const val OS_BASE = "https://api.opensubtitles.com/api/v1"
        private const val ASSRT_BASE = "https://api.assrt.net/v1"
        private const val USER_AGENT = "AiSubPlayer v0.8"
        private val JSON = "application/json".toMediaType()
        private val SUB_EXT = Regex("\\.(srt|ass|ssa|vtt)$", RegexOption.IGNORE_CASE)

        /** 压缩包内多个字幕时的偏好：简体/双语 > 繁体 > 其他；SRT 和 ASS 都能解析。 */
        internal fun scoreName(name: String): Int {
            val n = name.lowercase()
            var score = 0
            if (Regex("chs|简|sc\\b|gb|zh-?cn|简体").containsMatchIn(n)) score += 4
            if (Regex("双语|chs&eng|chs_eng|中英|bilingual").containsMatchIn(n)) score += 3
            if (Regex("cht|繁|tc\\b|big5").containsMatchIn(n)) score += 1
            if (n.endsWith(".srt")) score += 1
            return score
        }

        private fun languageName(code: String) = when (code.lowercase()) {
            "zh-cn" -> "简体中文"
            "zh-tw" -> "繁体中文"
            "en" -> "英语"
            "ja" -> "日语"
            "ko" -> "韩语"
            "fr" -> "法语"
            "de" -> "德语"
            "es" -> "西班牙语"
            "ru" -> "俄语"
            else -> code
        }

        /**
         * OpenSubtitles 文件哈希：文件大小 + 开头和结尾各 64KB 按 64 位小端整数求和。
         * 只对本地文件计算；网络文件返回 null（要额外下载，不划算）。
         */
        fun movieHash(context: Context, uri: Uri): String? = runCatching {
            if (uri.scheme != "content" && uri.scheme != "file") return null
            context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val size = ch.size()
                    if (size < CHUNK * 2) return null
                    var hash = size
                    for (offset in longArrayOf(0, size - CHUNK)) {
                        val buf = ByteBuffer.allocate(CHUNK).order(ByteOrder.LITTLE_ENDIAN)
                        ch.position(offset)
                        while (buf.hasRemaining() && ch.read(buf) > 0) Unit
                        buf.flip()
                        val longs = buf.asLongBuffer()
                        while (longs.hasRemaining()) hash += longs.get()
                    }
                    "%016x".format(hash)
                }
            }
        }.getOrNull()

        private const val CHUNK = 65536

        /** 下载结果可能是压缩包（zip）或各种编码的文本。 */
        internal fun decodeSubtitleBytes(bytes: ByteArray, name: String): String {
            if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    val entries = generateSequence { zip.nextEntry }
                        .filter { !it.isDirectory && SUB_EXT.containsMatchIn(it.name) }
                        .map { it.name to zip.readBytes() }
                        .toList()
                    val best = entries.maxByOrNull { scoreName(it.first) } ?: throw IllegalStateException("压缩包里没有字幕文件")
                    return decodeText(best.second)
                }
            }
            if (bytes.size > 4 && (String(bytes, 0, 4, Charsets.ISO_8859_1) == "Rar!" || bytes[0] == '7'.code.toByte() && bytes[1] == 'z'.code.toByte())) {
                throw IllegalStateException("这条字幕是 RAR/7z 压缩包，暂不支持，请换一条")
            }
            return decodeText(bytes)
        }
    }
}

/** 字幕文本解码：识别 BOM（UTF-8 / UTF-16），否则先试 UTF-8，乱码再按 GBK。 */
fun decodeText(bytes: ByteArray): String {
    if (bytes.size >= 2) {
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }
    val utf8 = String(bytes, Charsets.UTF_8).removePrefix("﻿")
    return if ('�' in utf8) runCatching { String(bytes, charset("GBK")) }.getOrDefault(utf8) else utf8
}
