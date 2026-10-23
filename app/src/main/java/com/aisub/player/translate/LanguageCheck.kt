package com.aisub.player.translate

import android.icu.text.Transliterator
import android.os.Build

/**
 * 判断一句字幕是否已经是目标语言：是的话不用发给大模型翻译，原文直接当译文。
 * 按文字种类判断（汉字 / 假名 / 谚文 / 拉丁字母），英语再看常见词；判断不了的一律照常翻译。
 */
object LanguageCheck {

    /** 一句话主要用的文字：zh / ja / ko / latin；没有任何文字（♪、数字、标点）返回 null。 */
    fun script(text: String): String? {
        var han = 0
        var kana = 0
        var hangul = 0
        var latin = 0
        text.codePoints().forEach { cp ->
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.LATIN -> latin++
                else -> {}
            }
        }
        return when {
            han + kana + hangul + latin == 0 -> null
            kana > 0 -> "ja"
            hangul > 0 && hangul >= han -> "ko"
            han > 0 && han * 2 >= latin -> "zh"
            latin > 0 -> "latin"
            else -> null
        }
    }

    /**
     * 这句是否不需要翻译。
     * @param target 目标语言的 locale（见 ttsLocaleFor），null 表示认不出目标语言，全部照常翻译
     * @param asrLanguage 设置里的「视频语言」，auto 表示自动
     * @param chineseVariant 判断中文是简体（hans）、繁体（hant）还是两者通用（neutral），不支持时返回 null
     */
    fun alreadyTarget(text: String, target: String?, asrLanguage: String, chineseVariant: (String) -> String?): Boolean {
        if (target == null) return false
        val script = script(text) ?: return true
        return when (script) {
            "zh" -> when (target) {
                // 系统不支持简繁判断时：目标简体按相同处理（识别结果基本都是简体），目标繁体照常翻译（转成繁体）
                "zh-CN" -> chineseVariant(text).let { it == null || it == "hans" || it == "neutral" }
                "zh-TW" -> chineseVariant(text).let { it == "hant" || it == "neutral" }
                else -> false
            }
            "ja" -> target == "ja"
            "ko" -> target == "ko"
            else -> target == "en" && looksEnglish(text, asrLanguage)
        }
    }

    /** 拉丁字母的句子是不是英语：含常见英语词，或者设置里指明了视频是英语。 */
    private fun looksEnglish(text: String, asrLanguage: String): Boolean {
        val words = text.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        if (words.any { it in ENGLISH_WORDS }) return true
        return asrLanguage == "en"
    }

    private val ENGLISH_WORDS = setOf(
        "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "be", "been", "am",
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them", "my", "your", "his", "our", "their",
        "this", "that", "what", "who", "where", "when", "why", "how", "to", "of", "in", "on", "at", "for", "with", "from",
        "do", "does", "did", "don't", "can", "can't", "will", "won't", "not", "no", "yes", "yeah", "okay", "ok", "oh",
        "i'm", "it's", "you're", "that's", "there", "here", "have", "has", "had", "get", "go", "come", "know", "just", "all",
        "please", "thank", "thanks", "sorry", "hello", "hi", "hey", "well", "so", "now", "right", "let's", "if",
    )
}

/** 用系统 ICU 的简繁转换判断中文是简体还是繁体（Android 10 起提供 Transliterator）。 */
object ChineseScript {
    private val toHans: Transliterator? by lazy { create("Traditional-Simplified") }
    private val toHant: Transliterator? by lazy { create("Simplified-Traditional") }

    private fun create(id: String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching { Transliterator.getInstance(id) }.getOrNull() else null

    /** hans / hant / neutral（简繁写法相同）/ mixed；系统不支持时返回 null。 */
    fun variant(text: String): String? {
        val s = toHans ?: return null
        val t = toHant ?: return null
        val asHans = synchronized(s) { s.transliterate(text) }
        val asHant = synchronized(t) { t.transliterate(text) }
        return when {
            asHans == text && asHant == text -> "neutral"
            asHans == text -> "hans"
            asHant == text -> "hant"
            else -> "mixed"
        }
    }
}
