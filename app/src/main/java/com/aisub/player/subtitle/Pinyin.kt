package com.aisub.player.subtitle

import android.icu.text.Transliterator
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * 汉字转带声调的拼音，用系统自带的 ICU（Android 10 起提供 Han-Latin），不额外占安装包体积。
 * 连续汉字整体转换，少数词能读对多音字；大多数情况取最常用读音，多音字可能不准。
 */
object Pinyin {

    val available: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** 一段字幕拆成的片段：汉字带拼音，其余（标点、字母、数字）原样，pinyin 为 null。 */
    data class Segment(val text: String, val pinyin: String?)

    private val cache = ConcurrentHashMap<Int, String>()

    private val transliterator: Transliterator? by lazy {
        if (available) runCatching { Transliterator.getInstance("Han-Latin") }.getOrNull() else null
    }

    fun isHan(codePoint: Int) = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN

    fun segments(text: String): List<Segment> {
        val out = ArrayList<Segment>()
        val plain = StringBuilder()
        val han = ArrayList<String>()
        fun flushPlain() {
            if (plain.isNotEmpty()) out += Segment(plain.toString(), null).also { plain.clear() }
        }
        fun flushHan() {
            if (han.isEmpty()) return
            val readings = readings(han)
            han.forEachIndexed { k, ch -> out += Segment(ch, readings[k]) }
            han.clear()
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp))
            if (isHan(cp) && transliterator != null) {
                flushPlain()
                han += ch
            } else {
                flushHan()
                plain.append(ch)
            }
            i += ch.length
        }
        flushHan()
        flushPlain()
        return out
    }

    /**
     * 连续的一串汉字整体转换：ICU 对少数词有专门读音（如「重庆」读 chóng），
     * 结果按空格拆开后和字数对得上就用整体结果，否则逐字转换。
     */
    private fun readings(chars: List<String>): List<String?> {
        val t = transliterator ?: return chars.map { null }
        if (chars.size > 1) {
            val whole = synchronized(t) { t.transliterate(chars.joinToString("")) }.trim().split(Regex("\\s+"))
            if (whole.size == chars.size && whole.none { w -> w.codePoints().anyMatch(::isHan) }) return whole
        }
        return chars.map { of(it.codePointAt(0)) }
    }

    private fun of(codePoint: Int): String? {
        cache[codePoint]?.let { return it }
        val t = transliterator ?: return null
        val py = synchronized(t) { t.transliterate(String(Character.toChars(codePoint))) }.trim()
        // 没有对应读音时 ICU 会原样返回汉字
        if (py.isEmpty() || py.codePoints().anyMatch(::isHan)) return null
        cache[codePoint] = py
        return py
    }
}
