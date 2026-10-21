package com.aisub.player.media

import org.json.JSONObject

/** 一个可播放的媒体：本地 content:// 或远程 http(s):// 地址。 */
data class MediaEntry(
    val uri: String,
    val title: String,
    val isVideo: Boolean = true,
    val durationMs: Long = 0,
    /** 本地文件所在目录（相对存储根目录，例如 "Movies/动画"）；远程文件为空 */
    val folder: String = "",
    /** 文件大小（字节）；未知为 0 */
    val sizeBytes: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("uri", uri)
        .put("title", title)
        .put("isVideo", isVideo)
        .put("duration", durationMs)
        .put("folder", folder)
        .put("size", sizeBytes)

    companion object {
        fun fromJson(o: JSONObject) = MediaEntry(
            uri = o.getString("uri"),
            title = o.optString("title"),
            isVideo = o.optBoolean("isVideo", true),
            durationMs = o.optLong("duration"),
            folder = o.optString("folder"),
            sizeBytes = o.optLong("size"),
        )

        private val VIDEO_EXT = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "mts",
            "rmvb", "rm", "3gp", "mpg", "mpeg", "vob", "iso",
        )
        private val AUDIO_EXT = setOf("mp3", "flac", "aac", "m4a", "wav", "ogg", "opus", "wma", "ape", "alac", "dsf")

        fun isVideoName(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO_EXT
        fun isAudioName(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO_EXT
        fun isMediaName(name: String) = isVideoName(name) || isAudioName(name)
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val total = ms / 1000
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
}
