package com.aisub.player.download

import com.aisub.player.subtitle.Cue
import java.io.File

/** 下载过程中的阶段和进度。percent 为 0..1，null 表示进度未知。 */
data class LinkProgress(val stage: LinkStage, val percent: Float?, val detail: String = "")

enum class LinkStage(val label: String) {
    INFO("正在解析链接"),
    DOWNLOAD("正在下载"),
    MERGE("正在合并音视频"),
    SAVE("正在保存到相册"),
    SUBTITLE("正在准备字幕"),
}

/** 下载结果：视频文件和平台提供的字幕（都在临时目录里）。 */
data class DownloadedLink(
    val video: File,
    val title: String,
    /** 原文字幕（SRT），平台没有字幕时为 null */
    val subtitle: File?,
    /** 原文字幕是平台自动生成的（YouTube 自动字幕会逐词滚动，需要整理） */
    val autoCaption: Boolean,
    /** 平台提供的目标语言字幕（人工字幕），可以直接当译文 */
    val translation: File?,
)

/**
 * 从视频网站链接下载视频和字幕。下载版用 yt-dlp 实现；标准版没有这个功能（[Downloaders.create] 返回 null）。
 */
interface LinkDownloader {
    /**
     * 下载到 dir。quality 是最高分辨率（0 表示不限）；targetLocale 是翻译目标语言（如 zh-CN），
     * 平台有这种语言的人工字幕时一并下载。阻塞较久，可以用 [cancel] 中止。
     */
    suspend fun download(
        url: String,
        dir: File,
        quality: Int,
        targetLocale: String?,
        onProgress: (LinkProgress) -> Unit,
    ): DownloadedLink

    fun cancel()

    /** 更新 yt-dlp 到最新正式版，返回结果说明。 */
    suspend fun update(): String

    /** 当前 yt-dlp 版本；还没初始化时为 null。 */
    suspend fun version(): String?
}

/** 从分享过来的文字里找出第一个网址（很多 App 分享的是「标题 + 链接」）。 */
fun extractUrl(text: String): String? =
    Regex("""https?://[^\s<>"'，。、！？）】」]+""").find(text)?.value?.trimEnd('.', ',', ')', ']', '}')

/** 整理平台字幕：去掉自动字幕的逐词滚动重复，合并目标语言字幕作为译文。 */
object PlatformSubtitles {

    /**
     * YouTube 自动字幕转成 SRT 后，每条会重复上一条的末尾（滚动显示），还夹着几十毫秒的过渡条目。
     * 去掉过短的条目，再把每条开头和上一条结尾重复的词去掉。
     */
    fun cleanRolling(cues: List<Cue>): List<Cue> {
        val out = ArrayList<Cue>()
        var previous = emptyList<String>()
        for (c in cues) {
            if (c.endMs - c.startMs < 150) continue
            val words = c.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            var overlap = 0
            for (n in minOf(previous.size, words.size) downTo 1) {
                if (previous.takeLast(n) == words.take(n)) {
                    overlap = n
                    break
                }
            }
            val rest = words.drop(overlap)
            if (rest.isEmpty()) continue
            previous = words
            out += c.copy(id = out.size + 1, text = rest.joinToString(" "))
        }
        return out
    }

    /** 给每条原文字幕配上时间重叠的目标语言字幕作为译文。 */
    fun mergeTranslation(source: List<Cue>, target: List<Cue>): List<Cue> = source.map { s ->
        val matched = target.filter { t ->
            val overlap = minOf(s.endMs, t.endMs) - maxOf(s.startMs, t.startMs)
            // 重叠超过任一方的一半就算同一句
            overlap > 0 && (overlap * 2 >= s.endMs - s.startMs || overlap * 2 >= t.endMs - t.startMs)
        }
        if (matched.isEmpty() || s.translation != null) s
        else s.copy(translation = matched.joinToString(" ") { it.translation ?: it.text })
    }
}
