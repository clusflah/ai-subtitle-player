package com.aisub.player.asr

import java.util.Locale

/**
 * 语音识别的视频语言。设置里存用户选的代码：常用语言见 [COMMON]，也可以自己填
 * ISO 639-1 / 639-3 代码（如 uk、sv、fil），可带地区（如 pt-BR、es-MX）。
 * 各引擎拿到的是 [primary]（去掉地区、小写）；Azure 需要完整 locale，见 [azureLocale]。
 */
object AsrLanguages {
    val COMMON: List<Pair<String, String>> = listOf(
        "auto" to "自动", "zh" to "中文", "yue" to "粤语", "en" to "英语", "ja" to "日语", "ko" to "韩语",
        "ru" to "俄语", "fr" to "法语", "de" to "德语", "es" to "西班牙语", "pt" to "葡萄牙语", "it" to "意大利语",
        "th" to "泰语", "vi" to "越南语", "id" to "印尼语", "ar" to "阿拉伯语", "hi" to "印地语", "tr" to "土耳其语",
        "uk" to "乌克兰语", "pl" to "波兰语", "nl" to "荷兰语",
    )

    private val CODE = Regex("""^[a-z]{2,3}(-[a-z0-9]{2,8})?$""")

    fun isCommon(code: String) = COMMON.any { it.first == code }

    /** 用户输入的代码整理成存储格式（小写、下划线换成连字符）；不合法返回 null。 */
    fun normalize(input: String): String? {
        val c = input.trim().lowercase().replace('_', '-')
        return c.takeIf { it == "auto" || CODE.matches(it) }
    }

    /** 去掉地区的语言代码："pt-br" → "pt" */
    fun primary(code: String): String = code.substringBefore('-').lowercase()

    fun label(code: String): String {
        COMMON.firstOrNull { it.first == code }?.let { return it.second }
        val name = Locale.forLanguageTag(code).getDisplayName(Locale.SIMPLIFIED_CHINESE)
        return if (name.isBlank() || name.equals(code, ignoreCase = true)) code else "$name（$code）"
    }

    /** Azure 的 locale；常用语言用默认地区，自填了地区的照用；不认识时返回 null（交给服务端自动识别）。 */
    fun azureLocale(code: String): String? {
        if (code == "auto") return null
        if ('-' in code) {
            val (l, r) = code.split('-', limit = 2)
            return if (l == "yue") "zh-HK" else "$l-${r.uppercase()}"
        }
        return AZURE_DEFAULT[code]
    }

    private val AZURE_DEFAULT = mapOf(
        "zh" to "zh-CN", "yue" to "zh-HK", "en" to "en-US", "ja" to "ja-JP", "ko" to "ko-KR", "ru" to "ru-RU",
        "fr" to "fr-FR", "de" to "de-DE", "es" to "es-ES", "pt" to "pt-BR", "it" to "it-IT", "th" to "th-TH",
        "vi" to "vi-VN", "id" to "id-ID", "ar" to "ar-SA", "hi" to "hi-IN", "tr" to "tr-TR", "uk" to "uk-UA",
        "pl" to "pl-PL", "nl" to "nl-NL", "sv" to "sv-SE", "da" to "da-DK", "fi" to "fi-FI", "nb" to "nb-NO",
        "cs" to "cs-CZ", "el" to "el-GR", "he" to "he-IL", "hu" to "hu-HU", "ro" to "ro-RO", "ms" to "ms-MY",
        "fil" to "fil-PH", "bg" to "bg-BG", "hr" to "hr-HR", "sk" to "sk-SK", "sl" to "sl-SI", "fa" to "fa-IR",
    )
}
