package com.aisub.player.subtitle

/**
 * 播放页顶部「一键字幕」按钮：按视频现有的字幕决定要不要显示、点了做什么。
 * - 已有目标语言的字幕（自带字幕轨或已载入的字幕）：不显示
 * - 只有外语字幕：翻译（自带的文字字幕轨先读出来再翻译）
 * - 没有字幕，或只有外语图形字幕（PGS / VobSub 没有文字，翻译不了）：语音识别后翻译
 */
enum class QuickSubtitle { GENERATE, TRANSLATE_LOADED, TRANSLATE_EMBEDDED }

/** 播放器报告的一条自带字幕轨 */
data class EmbeddedTrackInfo(val language: String?, val label: String?, val imageBased: Boolean)

object QuickSubtitles {

    /**
     * @param target 目标语言的 locale（见 ttsLocaleFor），null 表示认不出目标语言
     * @param alreadyTarget 一句话是否已经是目标语言（[com.aisub.player.translate.LanguageCheck.alreadyTarget]）
     * @return 按钮要做的事，null 表示不用显示
     */
    fun decide(
        cues: List<Cue>,
        tracks: List<EmbeddedTrackInfo>,
        target: String?,
        alreadyTarget: (String) -> Boolean,
    ): QuickSubtitle? {
        if (tracks.any { isTargetTrack(it, target) }) return null
        if (cues.isNotEmpty()) {
            val pending = cues.filter { it.translation == null }
            if (pending.isEmpty()) return null
            // 原文大多已经是目标语言（例如载入的就是中文字幕）就不用翻译
            val sample = pending.take(SAMPLE_SIZE)
            return if (sample.count { alreadyTarget(it.text) } * 2 >= sample.size) null else QuickSubtitle.TRANSLATE_LOADED
        }
        return if (tracks.any { !it.imageBased }) QuickSubtitle.TRANSLATE_EMBEDDED else QuickSubtitle.GENERATE
    }

    /** 字幕轨是不是目标语言：先看语言代码，没有时看名字（VLC 只给名字）。中文不分简繁。 */
    fun isTargetTrack(track: EmbeddedTrackInfo, target: String?): Boolean {
        val want = target?.substringBefore('-')?.lowercase() ?: return false
        val lang = track.language?.lowercase()?.substringBefore('-')?.takeIf { it != "und" }
        if (lang != null) return lang == want || ISO_639_2[lang] == want
        val label = track.label?.lowercase() ?: return false
        return LABEL_WORDS[want].orEmpty().any { it in label }
    }

    private const val SAMPLE_SIZE = 30

    /** 容器里常见的三字母语言代码（Media3 一般已经转成两字母，VLC 等不一定） */
    private val ISO_639_2 = mapOf(
        "chi" to "zh", "zho" to "zh", "chs" to "zh", "cht" to "zh",
        "eng" to "en", "jpn" to "ja", "kor" to "ko",
    )

    private val LABEL_WORDS = mapOf(
        "zh" to listOf("中文", "简", "繁", "国语", "國語", "粤语", "粵語", "汉语", "中英", "chinese", "chs", "cht", "mandarin", "cantonese"),
        "en" to listOf("english", "英"),
        "ja" to listOf("japanese", "日本語", "日语", "日文"),
        "ko" to listOf("korean", "한국어", "韩语", "韓語"),
    )
}
