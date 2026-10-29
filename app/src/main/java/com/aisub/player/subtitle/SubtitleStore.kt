package com.aisub.player.subtitle

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/**
 * 字幕缓存：每个视频按 URI 的哈希存一份 JSON（原文 + 译文），
 * 下次打开同一个视频直接加载，不用重新识别。
 */
class SubtitleStore(context: Context) {
    private val dir = File(context.filesDir, "subtitles").apply { mkdirs() }

    private fun fileFor(video: Uri): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(video.toString().toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(dir, "$name.json")
    }

    fun load(video: Uri): List<Cue> {
        val file = fileFor(video)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONObject(file.readText()).getJSONArray("cues")
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Cue(
                    id = o.getInt("id"),
                    startMs = o.getLong("start"),
                    endMs = o.getLong("end"),
                    text = o.getString("text"),
                    translation = o.optString("translation").takeIf { o.has("translation") },
                    speaker = o.optString("speaker").takeIf { o.has("speaker") },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(video: Uri, cues: List<Cue>) {
        val arr = JSONArray()
        for (c in cues) {
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("start", c.startMs)
                put("end", c.endMs)
                put("text", c.text)
                c.translation?.let { put("translation", it) }
                c.speaker?.let { put("speaker", it) }
            })
        }
        val file = fileFor(video)
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("version", 1).put("cues", arr).toString())
        tmp.renameTo(file)
    }

    fun delete(video: Uri) {
        fileFor(video).delete()
        rolesFile(video).delete()
        languageFile(video).delete()
        clearCoverage(video)
    }

    private fun coverageFile(video: Uri) = File(fileFor(video).path.removeSuffix(".json") + ".cov")

    /**
     * 语音识别已经覆盖的时间段（毫秒）。按播放位置边看边识别时，字幕可能只覆盖视频的一部分；
     * null 表示没有记录，即整段都有字幕（整段识别、导入、视频自带字幕等），不需要再识别。
     */
    fun loadCoverage(video: Uri): List<Pair<Long, Long>>? {
        val file = coverageFile(video)
        if (!file.exists()) return null
        return try {
            file.readLines().mapNotNull { line ->
                val p = line.trim().split(' ')
                if (p.size == 2) p[0].toLong() to p[1].toLong() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun saveCoverage(video: Uri, ranges: List<Pair<Long, Long>>) =
        coverageFile(video).writeText(ranges.joinToString("\n") { "${it.first} ${it.second}" })

    fun clearCoverage(video: Uri) {
        coverageFile(video).delete()
    }

    private fun languageFile(video: Uri) = File(fileFor(video).path.removeSuffix(".json") + ".lang")

    /** 在播放页给这个视频单独选的语音语言；null 表示没选过，用设置里的默认语言。 */
    fun loadAsrLanguage(video: Uri): String? =
        languageFile(video).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    fun saveAsrLanguage(video: Uri, code: String) = languageFile(video).writeText(code)

    private fun rolesFile(video: Uri) = File(fileFor(video).path.removeSuffix(".json") + ".roles.json")

    /** 角色表和配音选择，与字幕分开存，重新识别字幕时不会丢。 */
    fun loadRoles(video: Uri): List<Role> {
        val file = rolesFile(video)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Role(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    gender = o.optString("gender", "unknown"),
                    voice = o.optString("voice").takeIf { o.has("voice") },
                    voiceEngine = o.optString("voiceEngine").takeIf { o.has("voiceEngine") },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveRoles(video: Uri, roles: List<Role>) {
        val arr = JSONArray(roles.map { r ->
            JSONObject().put("id", r.id).put("name", r.name).put("gender", r.gender).apply {
                r.voice?.let { put("voice", it) }
                r.voiceEngine?.let { put("voiceEngine", it) }
            }
        })
        rolesFile(video).writeText(arr.toString())
    }
}

enum class SrtMode { ORIGINAL, TRANSLATION, BILINGUAL }

fun writeSrt(cues: List<Cue>, mode: SrtMode, out: OutputStream) {
    val sb = StringBuilder()
    var index = 1
    for (c in cues) {
        val body = when (mode) {
            SrtMode.ORIGINAL -> c.text
            SrtMode.TRANSLATION -> c.translation ?: c.text
            SrtMode.BILINGUAL -> listOfNotNull(c.translation, c.text).distinct().joinToString("\n")
        }
        sb.append(index++).append('\n')
        sb.append(srtTime(c.startMs)).append(" --> ").append(srtTime(c.endMs)).append('\n')
        sb.append(body).append("\n\n")
    }
    out.write(sb.toString().toByteArray(Charsets.UTF_8))
}

private fun srtTime(ms: Long): String {
    val h = ms / 3_600_000
    val m = ms / 60_000 % 60
    val s = ms / 1000 % 60
    val milli = ms % 1000
    return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
}

/** 在已缓存的字幕里搜索：返回原文或译文包含关键词的视频地址。 */
fun SubtitleStore.searchCached(uris: List<String>, query: String): Set<String> {
    val q = query.trim()
    if (q.length < 2) return emptySet()
    return uris.filter { uri ->
        load(Uri.parse(uri)).any { it.text.contains(q, ignoreCase = true) || it.translation?.contains(q, ignoreCase = true) == true }
    }.toSet()
}
