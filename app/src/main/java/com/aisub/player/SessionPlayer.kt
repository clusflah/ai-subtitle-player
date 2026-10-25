package com.aisub.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player

/**
 * 交给媒体会话的播放器：音乐用读到的歌名、歌手和封面（含在线找到的）代替文件名，
 * 通知栏、锁屏和蓝牙设备上显示的就是这些。其他操作原样转给真正的播放器。
 */
class SessionPlayer(player: Player, private val music: MediaMetadata?) : ForwardingPlayer(player) {
    override fun getMediaMetadata(): MediaMetadata =
        music?.let { super.getMediaMetadata().buildUpon().populate(it).build() } ?: super.getMediaMetadata()
}
