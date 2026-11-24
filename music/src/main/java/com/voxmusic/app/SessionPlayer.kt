package com.voxmusic.app

import android.graphics.Bitmap
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.voxmusic.app.music.MusicState
import java.io.ByteArrayOutputStream

/**
 * 交给媒体会话的播放器包装器：注入当前歌曲的歌名、歌手、专辑与封面，
 * 使通知栏、锁屏、蓝牙和桌面小组件实时显示完整元数据。
 */
class SessionPlayer(player: Player, private val music: MediaMetadata?) : ForwardingPlayer(player) {
    override fun getMediaMetadata(): MediaMetadata =
        music?.let { super.getMediaMetadata().buildUpon().populate(it).build() } ?: super.getMediaMetadata()
}

internal fun musicMetadata(m: MusicState): MediaMetadata? {
    val tags = m.tags ?: return null
    val artwork = m.cover?.takeIf { !it.isRecycled }?.let { bmp ->
        runCatching {
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        }.getOrNull()
    }
    return MediaMetadata.Builder()
        .setTitle(tags.title)
        .setArtist(tags.artist)
        .setAlbumTitle(tags.album)
        .apply { artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
        .build()
}
