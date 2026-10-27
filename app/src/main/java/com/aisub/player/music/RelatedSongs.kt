package com.aisub.player.music

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.aisub.player.media.MediaEntry

/** 播放页「推荐」里的一首歌：手机媒体库里读到的歌名、歌手、专辑。 */
data class RelatedSong(val entry: MediaEntry, val title: String, val artist: String, val album: String)

/**
 * 播放页的「推荐」：手机里同一张专辑、同一位歌手的其他歌（从系统媒体库查，不联网）。
 * 没有读取音频的权限、或者媒体库里没有这位歌手时返回空列表。
 */
object RelatedSongs {

    fun sameAlbum(context: Context, album: String?, artist: String?, excludeUri: String): List<RelatedSong> {
        if (album.isNullOrBlank()) return emptyList()
        // 合辑、原声带常常每首歌手不同，专辑名一样就算；歌手也一样的排前面
        return query(context, "${MediaStore.Audio.AudioColumns.ALBUM} = ?", arrayOf(album), MediaStore.Audio.AudioColumns.TRACK)
            .filter { it.entry.uri != excludeUri }
            .sortedByDescending { artist != null && it.artist.equals(artist, ignoreCase = true) }
    }

    fun sameArtist(context: Context, artist: String?, excludeUri: String, excludeAlbum: String?, limit: Int = 30): List<RelatedSong> {
        if (artist.isNullOrBlank()) return emptyList()
        // 「A / B」「A feat. B」这类合作曲也算
        return query(context, "${MediaStore.Audio.AudioColumns.ARTIST} LIKE ?", arrayOf("%$artist%"), "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            .filter { it.entry.uri != excludeUri && (excludeAlbum.isNullOrBlank() || !it.album.equals(excludeAlbum, ignoreCase = true)) }
            .distinctBy { it.title.lowercase() }
            .take(limit)
    }

    private fun query(context: Context, selection: String, args: Array<String>, order: String): List<RelatedSong> = runCatching {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.Audio.AudioColumns.TITLE,
            MediaStore.Audio.AudioColumns.ARTIST,
            MediaStore.Audio.AudioColumns.ALBUM,
            MediaStore.Audio.AudioColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
        )
        val out = ArrayList<RelatedSong>()
        context.contentResolver.query(collection, projection, "${MediaStore.Audio.AudioColumns.IS_MUSIC} != 0 AND $selection", args, order)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val artist = c.getString(3).orEmpty().takeIf { it != MediaStore.UNKNOWN_STRING }.orEmpty()
                out += RelatedSong(
                    MediaEntry(
                        uri = ContentUris.withAppendedId(collection, c.getLong(0)).toString(),
                        title = name, isVideo = false, durationMs = c.getLong(5), sizeBytes = c.getLong(6),
                    ),
                    title = c.getString(2)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                    artist = artist,
                    album = c.getString(4).orEmpty(),
                )
            }
        }
        out
    }.getOrDefault(emptyList())
}
