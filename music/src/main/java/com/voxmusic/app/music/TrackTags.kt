package com.voxmusic.app.music

/** 一首歌的标签信息。 */
data class TrackTags(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
) {
    companion object {
        private val QUALITY = Regex(
            """(?i)[\[(【{（][^\])】}）]*(flac|wav|ape|mp3|aac|alac|dsd|hi-?res|\d+\s*bit|\d+(\.\d+)?\s*khz|\d{3}\s*k(bps)?|lossless|无损|web)[^\])】}）]*[\])】}）]""",
        )
        private val TRACK_NO = Regex("""^(?:track\s*)?\d{1,3}(?:\s*[-._、)\]]\s*|\s+)""", RegexOption.IGNORE_CASE)

        fun fromFileName(fileName: String, durationMs: Long = 0L): TrackTags {
            val base = fileName.substringBeforeLast('.').replace(QUALITY, " ").replace(Regex("""\s+"""), " ").trim()
                .replace(TRACK_NO, "").trim().ifEmpty { fileName.substringBeforeLast('.') }
            val parts = base.split(Regex("""\s+[-–—]\s+"""), limit = 2)
            return if (parts.size == 2) {
                TrackTags(title = parts[1].trim(), artist = parts[0].trim(), durationMs = durationMs)
            } else {
                TrackTags(title = base, durationMs = durationMs)
            }
        }
    }
}
