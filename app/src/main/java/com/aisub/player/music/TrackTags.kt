package com.aisub.player.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.nio.charset.Charset

/** 一首歌的标签。title 一定有（没有标签时用文件名）。 */
data class TrackTags(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
    /** 内嵌封面（原始图片字节） */
    val cover: ByteArray? = null,
    /** 内嵌歌词（LRC 或纯文本） */
    val lyrics: String? = null,
) {
    override fun equals(other: Any?) = other is TrackTags && title == other.title && artist == other.artist &&
        album == other.album && durationMs == other.durationMs && lyrics == other.lyrics && (cover == null) == (other.cover == null)

    override fun hashCode() = listOf(title, artist, album, durationMs, lyrics, cover == null).hashCode()

    companion object {

        /**
         * 从播放器读到的音轨元数据里取标签、封面、歌词（本地和网络文件都适用）：
         * MP3 的 ID3（USLT 歌词、APIC 封面）、FLAC / OGG 的 Vorbis 注释（LYRICS）、M4A 的 ©lyr。
         */
        fun fromMetadata(fileName: String, durationMs: Long, metadata: List<Metadata>): TrackTags {
            val b = MediaMetadata.Builder()
            var lyrics: String? = null
            for (m in metadata) {
                b.populateFromMetadata(m)
                for (i in 0 until m.length()) {
                    lyrics = lyrics ?: when (val e = m.get(i)) {
                        is VorbisComment -> e.value.takeIf { e.key.uppercase() in LYRIC_KEYS }
                        is TextInformationFrame -> e.values.firstOrNull().takeIf { e.id == "USLT" }
                        is BinaryFrame -> if (e.id == "USLT") parseUslt(e.data) else null
                        else -> null
                    }?.takeIf { it.isNotBlank() }
                }
            }
            val mm = b.build()
            return withFallback(
                fileName, mm.title?.toString(), (mm.artist ?: mm.albumArtist)?.toString(), mm.albumTitle?.toString(),
                durationMs, mm.artworkData, lyrics,
            )
        }

        /** VLC 内核等拿不到音轨元数据时，用系统的 MediaMetadataRetriever 读本地文件。 */
        fun fromRetriever(context: Context, uri: Uri, fileName: String): TrackTags? = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(context, uri)
                    withFallback(
                        fileName,
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                        embeddedPicture, null,
                    )
                } finally {
                    release()
                }
            }
        }.getOrNull()

        private val LYRIC_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS")

        /** 标签里没有歌名时，从「歌手 - 歌名.mp3」这样的文件名里拆。 */
        private fun withFallback(
            fileName: String, title: String?, artist: String?, album: String?, durationMs: Long, cover: ByteArray?, lyrics: String?,
        ): TrackTags {
            val base = fileName.substringBeforeLast('.').trim()
            val parts = base.split(Regex("""\s+-\s+|\s*–\s*"""), limit = 2)
            val fromName = if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            return TrackTags(
                title = title?.trim()?.takeIf { it.isNotEmpty() } ?: fromName?.second ?: base.replace(Regex("""^\d{1,3}[.\s_-]+"""), ""),
                artist = artist?.trim()?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", true) } ?: fromName?.first,
                album = album?.trim()?.takeIf { it.isNotEmpty() },
                durationMs = durationMs,
                cover = cover,
                lyrics = lyrics,
            )
        }

        /** ID3 USLT：编码(1) + 语言(3) + 描述（以 0 结尾）+ 歌词正文。 */
        internal fun parseUslt(data: ByteArray): String? {
            if (data.size < 5) return null
            val (charset, wide) = when (data[0].toInt()) {
                1 -> Charsets.UTF_16 to true
                2 -> Charsets.UTF_16BE to true
                3 -> Charsets.UTF_8 to false
                else -> Charset.forName("ISO-8859-1") to false
            }
            var i = 4
            // 跳过描述
            if (wide) {
                while (i + 1 < data.size && !(data[i].toInt() == 0 && data[i + 1].toInt() == 0)) i += 2
                i += 2
            } else {
                while (i < data.size && data[i].toInt() != 0) i++
                i += 1
            }
            if (i >= data.size) return null
            return String(data, i, data.size - i, charset).trimEnd('\u0000').trim().takeIf { it.isNotEmpty() }
        }
    }
}
