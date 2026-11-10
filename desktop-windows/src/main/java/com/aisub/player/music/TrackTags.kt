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
                durationMs, mm.artworkData, lyrics?.let(::fixGbk),
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

        /** 标签里没有歌名（或是坏的）时，从「歌手 - 歌名.mp3」这样的文件名里拆。 */
        private fun withFallback(
            fileName: String, title: String?, artist: String?, album: String?, durationMs: Long, cover: ByteArray?, lyrics: String?,
        ): TrackTags {
            val (nameTitle, nameArtist) = parseFileName(fileName)
            return TrackTags(
                title = valid(title) ?: nameTitle,
                artist = valid(artist) ?: nameArtist,
                album = valid(album),
                durationMs = durationMs,
                cover = cover,
                lyrics = lyrics,
            )
        }

        /** 括号里的音质 / 格式说明：[FLAC]、(320K)、【无损】（参考 MediaOrganizer） */
        private val QUALITY = Regex(
            """(?i)[\[(【{（][^\])】}）]*(flac|wav|ape|mp3|aac|alac|dsd|hi-?res|\d+\s*bit|\d+(\.\d+)?\s*khz|\d{3}\s*k(bps)?|lossless|无损|web)[^\])】}）]*[\])】}）]""",
        )
        private val TRACK_NO = Regex("""^(?:track\s*)?\d{1,3}(?:\s*[-._、)\]]\s*|\s+)""", RegexOption.IGNORE_CASE)

        /** 文件名拆成（歌名，歌手）：去掉音轨号和音质说明，「歌手 - 歌名」拆开。 */
        internal fun parseFileName(fileName: String): Pair<String, String?> {
            val base = fileName.substringBeforeLast('.').replace(QUALITY, " ").replace(Regex("""\s+"""), " ").trim()
                .replace(TRACK_NO, "").trim().ifEmpty { fileName.substringBeforeLast('.') }
            val parts = base.split(Regex("""\s+[-–—]\s+"""), limit = 2)
            return if (parts.size == 2) parts[1].trim() to parts[0].trim() else base to null
        }

        /** 未知艺术家、Track 01 这类占位值（参考 MediaOrganizer） */
        private val BAD_VALUE = Regex("""(?i)^(unknown( artist| album)?|<unknown>|未知(艺术家|歌手|专辑)?|track\s*\d+|音轨\s*\d+|various( artists)?)$""")

        /**
         * 标签值能不能用：空的、占位的、全是问号（不支持中文的工具写坏了）的不要；
         * 按 Latin-1 读出来的 GBK 乱码（「¹âÁÁ」）转回中文。
         */
        internal fun valid(s: String?): String? {
            val t = s?.trim()?.trim('\u0000')?.takeIf { it.isNotEmpty() } ?: return null
            if (BAD_VALUE.matches(t)) return null
            if (t.all { it == '?' || it == '？' || it == '�' || it.isWhitespace() }) return null
            return fixGbk(t)
        }

        /** 国内老 MP3 常把 GBK 当 Latin-1 写进去：全是 Latin-1 字符、有高位字符，按 GBK 能解出汉字就用 GBK */
        internal fun fixGbk(s: String): String {
            if (s.any { it.code > 0xFF } || s.none { it.code >= 0x80 }) return s
            val gbk = runCatching { String(s.toByteArray(Charsets.ISO_8859_1), Charset.forName("GBK")) }.getOrNull() ?: return s
            return if (gbk.contains('�') || gbk.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) s else gbk
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
