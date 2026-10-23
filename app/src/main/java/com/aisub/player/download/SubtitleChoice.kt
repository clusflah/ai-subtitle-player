package com.aisub.player.download

import org.json.JSONObject

/**
 * 根据 yt-dlp 的视频信息（-J 输出）挑字幕：
 * 原文字幕优先用视频原语言的人工字幕，其次平台自动字幕；
 * 平台有目标语言的人工字幕时一并下载，直接当译文用（省掉大模型翻译）。
 */
object SubtitleChoice {

    data class Choice(
        /** 原文字幕的语言键（yt-dlp 的 --sub-langs 用），没有为 null */
        val source: String?,
        val sourceIsAuto: Boolean,
        /** 目标语言人工字幕的语言键，没有为 null */
        val target: String?,
    ) {
        val languages: List<String> get() = listOfNotNull(source, target).distinct()
        val manual: Boolean get() = (source != null && !sourceIsAuto) || target != null
        val auto: Boolean get() = source != null && sourceIsAuto
    }

    /** 弹幕、聊天记录之类不是字幕 */
    private val NOT_SUBTITLES = setOf("live_chat", "danmaku", "comments", "rechat")

    fun pick(info: JSONObject, targetLocale: String?): Choice {
        val manual = keys(info.optJSONObject("subtitles"))
        val auto = keys(info.optJSONObject("automatic_captions"))
        val lang = info.optString("language").takeIf { it.isNotBlank() && it != "null" }

        val target = targetLocale?.let { t -> manual.firstOrNull { matchesTarget(it, t) } }
        var source: String? = null
        var sourceIsAuto = false
        if (lang != null) {
            source = manual.firstOrNull { sameLanguage(it, lang) }
            if (source == null) {
                source = auto.firstOrNull { it.equals("$lang-orig", ignoreCase = true) }
                    ?: auto.firstOrNull { it.equals(lang, ignoreCase = true) }
                    ?: auto.firstOrNull { sameLanguage(it, lang) }
                sourceIsAuto = source != null
            }
        } else {
            // 不知道原语言：取第一个不是目标语言的人工字幕，否则取「原始语言」自动字幕
            source = manual.firstOrNull { targetLocale == null || !matchesTarget(it, targetLocale) }
            if (source == null) {
                source = auto.firstOrNull { it.endsWith("-orig") }
                sourceIsAuto = source != null
            }
        }
        // 只有目标语言字幕，或视频本来就是目标语言：用它当原文，翻译时会直接跳过
        if (source == null && target != null) return Choice(target, false, null)
        if (source == target) return Choice(source, sourceIsAuto, null)
        return Choice(source, sourceIsAuto, target)
    }

    private fun keys(obj: JSONObject?): List<String> =
        obj?.keys()?.asSequence()?.filter { k -> k !in NOT_SUBTITLES && obj.optJSONArray(k)?.length() != 0 }?.toList().orEmpty()

    private fun sameLanguage(key: String, lang: String) =
        key.substringBefore('-').equals(lang.substringBefore('-'), ignoreCase = true)

    /** 字幕语言键是否是目标语言：简体 / 繁体要分开 */
    fun matchesTarget(key: String, target: String): Boolean {
        val k = key.lowercase()
        return when (target.lowercase()) {
            "zh-cn" -> k == "zh" || k == "zh-cn" || k == "zh-sg" || k.startsWith("zh-hans")
            "zh-tw" -> k == "zh-tw" || k.startsWith("zh-hant")
            "zh-hk" -> k == "zh-hk" || k == "yue" || k.startsWith("yue-")
            else -> sameLanguage(k, target)
        }
    }
}
