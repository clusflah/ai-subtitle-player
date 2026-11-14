package com.aisub.player.util

import android.os.Build
import androidx.annotation.RequiresApi

/**
 * 兼容 Android 6.0 (API 23) 的文字种类判断与码点遍历工具：
 * `Character.UnicodeScript` 和 `String.codePoints()` 均从 API 24 起才提供，
 * 低版本通过 Unicode 标准码点区间实现等价分类，高版本委派系统 `UnicodeScript`。
 */
object UnicodeCompat {

    enum class ScriptKind { HAN, KANA, HANGUL, LATIN, OTHER_LETTER, NON_LETTER }

    fun scriptOf(cp: Int): ScriptKind =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Api24.scriptOf(cp) else scriptOfCompat(cp)

    internal fun scriptOfCompat(cp: Int): ScriptKind = when {
        cp in 0x4E00..0x9FFF ||
            cp in 0x3400..0x4DBF ||
            cp in 0xF900..0xFAFF ||
            cp in 0x20000..0x323AF ||
            cp in 0x2E80..0x2EFF ||
            cp == 0x3007 -> ScriptKind.HAN

        cp in 0x3040..0x309F ||
            cp in 0x30A0..0x30FF ||
            cp in 0x31F0..0x31FF ||
            cp in 0xFF66..0xFF9F ||
            cp in 0x1B000..0x1B0FF -> ScriptKind.KANA

        cp in 0xAC00..0xD7AF ||
            cp in 0x1100..0x11FF ||
            cp in 0x3130..0x318F ||
            cp in 0xA960..0xA97F ||
            cp in 0xD7B0..0xD7FF -> ScriptKind.HANGUL

        (cp in 0x0041..0x005A) ||
            (cp in 0x0061..0x007A) ||
            (cp in 0x00C0..0x024F && Character.isLetter(cp)) ||
            (cp in 0x1E00..0x1EFF) ||
            (cp in 0x2C60..0x2C7F) ||
            (cp in 0xA720..0xA7FF) ||
            (cp in 0xFF21..0xFF3A) ||
            (cp in 0xFF41..0xFF5A) -> ScriptKind.LATIN

        Character.isLetter(cp) -> ScriptKind.OTHER_LETTER
        else -> ScriptKind.NON_LETTER
    }

    inline fun forEachCodePoint(s: String, action: (Int) -> Unit) {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            action(cp)
            i += Character.charCount(cp)
        }
    }

    fun isHan(cp: Int): Boolean = scriptOf(cp) == ScriptKind.HAN

    fun isCjk(cp: Int): Boolean = when (scriptOf(cp)) {
        ScriptKind.HAN, ScriptKind.KANA, ScriptKind.HANGUL -> true
        else -> false
    }

    fun containsHan(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (isHan(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private object Api24 {
        fun scriptOf(cp: Int): ScriptKind = when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.HAN -> ScriptKind.HAN
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> ScriptKind.KANA
            Character.UnicodeScript.HANGUL -> ScriptKind.HANGUL
            Character.UnicodeScript.LATIN -> ScriptKind.LATIN
            else -> if (Character.isLetter(cp)) ScriptKind.OTHER_LETTER else ScriptKind.NON_LETTER
        }
    }
}
