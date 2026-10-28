package com.aisub.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.aisub.player.music.MusicState

/**
 * 交给媒体会话的播放器：音乐用读到的歌名、歌手和封面（含在线找到的）代替文件名，
 * 通知栏、锁屏和蓝牙设备上显示的就是这些。其他操作原样转给真正的播放器。
 */
class SessionPlayer(player: Player, private val music: MediaMetadata?) : ForwardingPlayer(player) {
    override fun getMediaMetadata(): MediaMetadata =
        music?.let { super.getMediaMetadata().buildUpon().populate(it).build() } ?: super.getMediaMetadata()
}

/** 音乐的歌名、歌手、专辑、封面，给通知栏、锁屏和桌面小组件用；不是音乐时返回 null */
internal fun musicMetadata(m: MusicState): MediaMetadata? {
    val tags = m.tags ?: return null
    val artwork = m.cover?.let { bmp ->
        java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }
    return MediaMetadata.Builder()
        .setTitle(tags.title)
        .setArtist(tags.artist)
        .setAlbumTitle(tags.album)
        .apply { artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
        .build()
}
