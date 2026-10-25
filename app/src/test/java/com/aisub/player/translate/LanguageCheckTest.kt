package com.aisub.player.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageCheckTest {

    /** 测试里没有系统 ICU：用两个字模拟简繁判断 */
    private val variant: (String) -> String? = { t ->
        when {
            "这" in t -> "hans"
            "這" in t -> "hant"
            else -> "neutral"
        }
    }

    private fun same(text: String, target: String?, asr: String = "auto", v: (String) -> String? = variant) =
        LanguageCheck.alreadyTarget(text, target, asr, v)

    @Test
    fun scripts() {
        assertEquals("zh", LanguageCheck.script("你好，世界"))
        assertEquals("ja", LanguageCheck.script("今日はいい天気ですね"))
        assertEquals("ko", LanguageCheck.script("안녕하세요"))
        assertEquals("latin", LanguageCheck.script("Where are you going?"))
        assertEquals("other", LanguageCheck.script("сначала русская"))
        assertEquals("other", LanguageCheck.script("مرحبا"))
        assertNull(LanguageCheck.script("♪ ♪ 123 ..."))
    }

    @Test
    fun chineseToChinese() {
        assertTrue(same("这是我的", "zh-CN"))
        assertTrue(same("我们走吧", "zh-CN"))
        // 繁体原文、简体目标：需要翻译（转换）
        assertFalse(same("這是我的", "zh-CN"))
        assertTrue(same("這是我的", "zh-TW"))
        assertFalse(same("这是我的", "zh-TW"))
        // 系统不支持简繁判断时，目标简体按相同处理，目标繁体照常翻译
        assertTrue(same("这是我的", "zh-CN", v = { null }))
        assertFalse(same("这是我的", "zh-TW", v = { null }))
        // 粤语书面语和普通话不同
        assertFalse(same("我们走吧", "zh-HK"))
    }

    @Test
    fun otherLanguages() {
        assertTrue(same("Where are you going?", "en"))
        assertFalse(same("Where are you going?", "zh-CN"))
        assertFalse(same("你好", "en"))
        assertTrue(same("こんにちは", "ja"))
        assertTrue(same("안녕하세요", "ko"))
        // 认不出是不是英语的拉丁字母句子照常翻译，除非视频语言设成英语
        assertFalse(same("Bonjour Paris", "en"))
        assertTrue(same("Bonjour Paris", "en", asr = "en"))
        // 法语目标：拉丁字母分不清是哪种语言，照常翻译
        assertFalse(same("Bonjour", "fr"))
        // 俄语等其他文字照常翻译
        assertFalse(same("сначала русская", "zh-CN"))
        assertFalse(same("и теперь американская", "en"))
    }

    @Test
    fun noLettersAndUnknownTarget() {
        assertTrue(same("♪ ♪", "zh-CN"))
        assertFalse(same("Hello", null))
        assertFalse(same("♪", null))
    }
}
