package com.voxmusic.app.media

import org.json.JSONObject

/** 一首可播放的流媒体曲目条目（Apple Music / Spotify）。 */
data class MediaEntry(
    val uri: String,
    val title: String,
    val durationMs: Long = 0L,
    /** 副标题摘要（歌手 - 专辑 或来源名称） */
    val folder: String = "",
    val coverUrl: String? = null,
) {
    /** 显示用的歌名：去掉末尾可能附带的音频扩展名 */
    val displayTitle: String
        get() = if (isAudioName(title)) title.substringBeforeLast('.') else title

    fun toJson(): JSONObject = JSONObject()
        .put("uri", uri)
        .put("title", title)
        .put("duration", durationMs)
        .put("folder", folder)
        .apply { if (!coverUrl.isNullOrBlank()) put("coverUrl", coverUrl) }

    companion object {
        private val AUDIO_EXT = setOf("mp3", "flac", "aac", "m4a", "wav", "ogg", "opus", "alac")

        fun isAudioName(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in AUDIO_EXT

        fun fromJson(o: JSONObject): MediaEntry = MediaEntry(
            uri = o.getString("uri"),
            title = o.optString("title"),
            durationMs = o.optLong("duration"),
            folder = o.optString("folder"),
            coverUrl = o.optString("coverUrl").takeIf { it.isNotBlank() },
        )
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0L) return ""
    val total = ms / 1000L
    return if (total >= 3600L) "%d:%02d:%02d".format(total / 3600L, total / 60L % 60L, total % 60L)
    else "%d:%02d".format(total / 60L, total % 60L)
}
