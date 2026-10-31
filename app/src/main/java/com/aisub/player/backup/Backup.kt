package com.aisub.player.backup

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** 备份里可以分别选择导出、导入的几类数据。 */
enum class BackupSection(val label: String, val hint: String) {
    SETTINGS("设置和密钥", "语音识别、翻译、朗读、字幕样式、影片信息等全部设置，包括各服务的 API Key"),
    LIVE("直播源和收藏", "直播源订阅、收藏的频道、各频道调过的字幕延迟"),
    SERVERS("服务器和网盘", "WebDAV、OpenList、Emby / Plex 和各网盘的登录"),
    OTHER("其他记录", "片头片尾标记、播放列表、影片信息修正、网页历史和字幕语言"),
}

/** 读进来的备份：来源设备、导出时间、各类数据（还没写入）。 */
class BackupFile(val device: String, val exportedAt: Long, val sections: Map<BackupSection, JSONObject>) {
    /** 预览：每类数据有哪些内容，给用户确认 */
    fun summary(section: BackupSection): String {
        val data = sections[section] ?: return ""
        fun prefs(name: String) = data.optJSONObject(name) ?: JSONObject()
        fun count(name: String, key: String) = PrefsCodec.arrayOf(prefs(name), key)?.length() ?: 0
        return when (section) {
            BackupSection.SETTINGS -> {
                val keys = PrefsCodec.decode(prefs("settings")).count { (k, v) -> v is String && v.isNotBlank() && Backup.isSecretKey(k) }
                if (keys > 0) "$keys 个密钥 / 账号" else "不含密钥"
            }
            BackupSection.LIVE -> {
                val delays = PrefsCodec.decode(prefs("playback")).keys.count { it.startsWith("delay.live.") }
                listOf("${count("live", "sources")} 个直播源", "${count("live", "favorites")} 个收藏频道", "$delays 个频道的字幕延迟")
                    .filterNot { it.startsWith("0 ") }.joinToString("、").ifEmpty { "空" }
            }
            BackupSection.SERVERS -> {
                val arr = PrefsCodec.arrayOf(prefs("library"), "servers") ?: JSONArray()
                val names = List(arr.length()) { arr.getJSONObject(it).optString("name") }
                if (names.isEmpty()) "空" else "${names.size} 个：" + names.joinToString("、")
            }
            BackupSection.OTHER -> {
                val skips = PrefsCodec.decode(prefs("playback")).keys.count { it.startsWith("skip.file.") || it.startsWith("skip.series.") }
                listOf("$skips 处片头片尾标记", "${count("library", "playlists")} 个播放列表", "${count("web", "history")} 条网页历史")
                    .filterNot { it.startsWith("0 ") }.joinToString("、").ifEmpty { "少量记录" }
            }
        }
    }
}

/**
 * 数据导出、导入：换手机、或在手机上设置好后一键搬到电视（电视上用遥控器填 Key、加直播源很麻烦）。
 *
 * 备份是一个 JSON 文件，按 [BackupSection] 分类保存相关的 SharedPreferences。
 * 导入时设置整体覆盖；直播源、收藏、服务器、播放列表等列表和本机已有的合并（同一个地址只留一份），不会删掉本机已有的。
 * 只在本机或手机直连的局域网里传递，不经过任何服务器；但文件里有明文的密钥，要自己保管好。
 */
object Backup {
    const val FORMAT = "voxtitle-backup"
    const val VERSION = 1
    private val LISTS = setOf("live/sources", "live/favorites", "library/servers", "library/playlists", "web/history")

    fun export(context: Context, sections: Set<BackupSection>): String {
        fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
        val out = JSONObject()
        for (section in sections) {
            val data = JSONObject()
            when (section) {
                BackupSection.SETTINGS -> data.put("settings", PrefsCodec.encode(prefs("settings")))
                BackupSection.LIVE -> {
                    val live = prefs("live")
                    data.put("live", PrefsCodec.encode(mapOf(
                        "sources" to filterArray(live["sources"]) { !isLocalUri(it.optString("url")) },
                        "favorites" to live["favorites"],
                    )))
                    data.put("playback", PrefsCodec.encode(prefs("playback").filterKeys { it.startsWith("delay.live.") }))
                }
                BackupSection.SERVERS -> data.put("library", PrefsCodec.encode(mapOf("servers" to prefs("library")["servers"])))
                BackupSection.OTHER -> {
                    data.put("playback", PrefsCodec.encode(prefs("playback").filterKeys { it.startsWith("skip.file.") || it.startsWith("skip.series.") }))
                    val playlists = (prefs("library")["playlists"] as? String)?.let { json ->
                        val arr = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
                        // 本机文件换了设备打不开，只带网络地址
                        JSONArray(List(arr.length()) { arr.getJSONObject(it) }.map { p ->
                            p.put("items", filterArray(p.optJSONArray("items")?.toString()) { !isLocalUri(it.optString("uri")) }?.let(::JSONArray) ?: JSONArray())
                        }).toString()
                    }
                    data.put("library", PrefsCodec.encode(mapOf("playlists" to playlists)))
                    data.put("meta_overrides", PrefsCodec.encode(prefs("meta_overrides")))
                    data.put("web", PrefsCodec.encode(prefs("web").filterKeys { it != "last" }))
                }
            }
            out.put(section.name, data)
        }
        return JSONObject()
            .put("format", FORMAT).put("version", VERSION)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("exportedAt", System.currentTimeMillis())
            .put("sections", out)
            .toString(2)
    }

    /** 读备份文件；不是译幕的备份时抛出异常 */
    fun parse(text: String): BackupFile {
        val o = runCatching { JSONObject(text.trim().removePrefix("﻿")) }.getOrNull()
        if (o == null || o.optString("format") != FORMAT) throw IllegalArgumentException("不是译幕的备份文件")
        if (o.optInt("version") > VERSION) throw IllegalArgumentException("备份来自更新版本的译幕，请先更新")
        val sections = o.optJSONObject("sections") ?: JSONObject()
        return BackupFile(
            o.optString("device"), o.optLong("exportedAt"),
            BackupSection.entries.mapNotNull { s -> sections.optJSONObject(s.name)?.let { s to it } }.toMap(),
        )
    }

    /** 写入选中的几类数据。写完要重启应用，各页面才会读到新的内容。 */
    fun import(context: Context, file: BackupFile, sections: Set<BackupSection>) {
        fun edit(name: String, block: SharedPreferences.(SharedPreferences.Editor) -> Unit) {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            prefs.block(editor)
            editor.commit()
        }
        for (section in sections) {
            val data = file.sections[section] ?: continue
            for (name in data.keys()) {
                val values = PrefsCodec.decode(data.getJSONObject(name))
                when {
                    // 设置整体换成备份里的（没出现的项回到默认）
                    section == BackupSection.SETTINGS && name == "settings" -> edit(name) { e ->
                        e.clear()
                        values.forEach { (k, v) -> PrefsCodec.put(e, k, v) }
                    }
                    else -> edit(name) { e ->
                        for ((k, v) in values) {
                            val merged = mergeList(name, k, getString(k, null), v as? String)
                            PrefsCodec.put(e, k, merged ?: v)
                        }
                    }
                }
            }
        }
    }

    /** 需要合并的列表：返回合并后的 JSON；不是列表的项返回 null（直接覆盖） */
    internal fun mergeList(prefs: String, key: String, existing: String?, incoming: String?): String? {
        if (incoming == null || "$prefs/$key" !in LISTS) return null
        val old = runCatching { JSONArray(existing ?: "[]") }.getOrDefault(JSONArray())
        val new = runCatching { JSONArray(incoming) }.getOrNull() ?: return null
        return when ("$prefs/$key") {
            // 同一个地址的直播源只留一份，保留本机的 id（频道缓存按 id 存）
            "live/sources" -> mergeBy(old, new, keepId = true) { it.optString("url").trim() }
            "live/favorites" -> mergeBy(old, new) { it.optString("url") }
            // 同一台服务器（类型 + 地址 + 账号）只留一份，保留本机的 id（收藏的文件夹按 id 记）
            "library/servers" -> mergeBy(old, new, keepId = true) {
                it.optString("type") + "|" + it.optString("baseUrl").trim().trimEnd('/') + "|" + it.optString("username") + "|" +
                    if (it.optString("baseUrl").isBlank()) it.optString("name") else ""
            }
            // 同名播放列表合并里面的曲目
            "library/playlists" -> mergeBy(old, new, keepId = true, combine = { a, b ->
                b.put("items", mergeArrays(a.optJSONArray("items") ?: JSONArray(), b.optJSONArray("items") ?: JSONArray()) { it.optString("uri") })
            }) { it.optString("name") }
            "web/history" -> mergeArrays(new, old) { it.optString("u") }.let { arr -> JSONArray(List(minOf(arr.length(), 30)) { arr.get(it) }) }
            else -> return null
        }.toString()
    }

    /** 按 key 合并：备份里的替换本机同 key 的那条（位置不变），本机没有的加在后面 */
    private fun mergeBy(
        old: JSONArray,
        new: JSONArray,
        keepId: Boolean = false,
        combine: (JSONObject, JSONObject) -> JSONObject = { _, b -> b },
        key: (JSONObject) -> String,
    ): JSONArray {
        val result = List(old.length()) { old.getJSONObject(it) }.toMutableList()
        for (i in 0 until new.length()) {
            val item = new.getJSONObject(i)
            val at = result.indexOfFirst { key(it) == key(item) || (it.has("id") && it.optString("id") == item.optString("id")) }
            if (at < 0) result += item
            else {
                if (keepId && result[at].has("id")) item.put("id", result[at].getString("id"))
                result[at] = combine(result[at], item)
            }
        }
        return JSONArray(result)
    }

    /** first 在前，second 里 first 没有的接在后面 */
    private fun mergeArrays(first: JSONArray, second: JSONArray, key: (JSONObject) -> String): JSONArray {
        val items = List(first.length()) { first.getJSONObject(it) } + List(second.length()) { second.getJSONObject(it) }
        return JSONArray(items.distinctBy(key))
    }

    private fun filterArray(json: Any?, keep: (JSONObject) -> Boolean): String? {
        val arr = runCatching { JSONArray(json as? String ?: return null) }.getOrNull() ?: return null
        return JSONArray(List(arr.length()) { arr.getJSONObject(it) }.filter(keep)).toString()
    }

    private fun isLocalUri(uri: String) = uri.startsWith("content://") || uri.startsWith("file://") || uri.startsWith("/")

    /** 设置里的密钥、账号类项（预览里数一数） */
    internal fun isSecretKey(key: String) =
        Regex("(?i)(apikey|key|token|secret|password|username)$").containsMatchIn(key.substringAfterLast('.'))
}

/** SharedPreferences 的值和 JSON 互转，带上类型（Int、Long、Float 在 JSON 里分不出来） */
internal object PrefsCodec {
    fun encode(values: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in values) {
            val tagged = when (v) {
                is String -> JSONArray().put("s").put(v)
                is Boolean -> JSONArray().put("b").put(v)
                is Int -> JSONArray().put("i").put(v)
                is Long -> JSONArray().put("l").put(v)
                is Float -> JSONArray().put("f").put(v.toDouble())
                is Set<*> -> JSONArray().put("ss").put(JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, tagged)
        }
        return o
    }

    fun decode(o: JSONObject): Map<String, Any> = o.keys().asSequence().mapNotNull { k ->
        val arr = o.optJSONArray(k) ?: return@mapNotNull null
        val v: Any = when (arr.optString(0)) {
            "s" -> arr.optString(1)
            "b" -> arr.optBoolean(1)
            "i" -> arr.optInt(1)
            "l" -> arr.optLong(1)
            "f" -> arr.optDouble(1).toFloat()
            "ss" -> arr.optJSONArray(1)?.let { s -> List(s.length()) { s.getString(it) }.toSet() } ?: return@mapNotNull null
            else -> return@mapNotNull null
        }
        k to v
    }.toMap()

    /** 编码后的 prefs 里某个存成 JSON 数组的字符串项 */
    fun arrayOf(o: JSONObject, key: String): JSONArray? = (decode(o)[key] as? String)?.let { runCatching { JSONArray(it) }.getOrNull() }

    @Suppress("UNCHECKED_CAST")
    fun put(e: SharedPreferences.Editor, key: String, v: Any) {
        when (v) {
            is String -> e.putString(key, v)
            is Boolean -> e.putBoolean(key, v)
            is Int -> e.putInt(key, v)
            is Long -> e.putLong(key, v)
            is Float -> e.putFloat(key, v)
            is Set<*> -> e.putStringSet(key, v as Set<String>)
        }
    }
}
