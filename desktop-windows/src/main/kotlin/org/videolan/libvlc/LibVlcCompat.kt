package org.videolan.libvlc

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.interfaces.IVLCVout

class LibVLC(context: Context, options: List<String> = emptyList()) {
    fun release() {}
}

class Media(libVLC: LibVLC, val uri: Uri) {
    fun setHWDecoderEnabled(enabled: Boolean, force: Boolean) {}
    fun addOption(option: String) {}
    fun release() {}
}

class MediaPlayer(libVLC: LibVLC) {
    class Event(
        val type: Int,
        val buffering: Float = 100f,
        val lengthChanged: Long = 0L,
        val voutCount: Int = 1,
    ) {
        companion object {
            const val Opening = 0x102
            const val Buffering = 0x103
            const val Playing = 0x104
            const val Paused = 0x105
            const val EndReached = 0x109
            const val EncounteredError = 0x10a
            const val LengthChanged = 0x111
            const val Vout = 0x112
            const val ESAdded = 0x114
            const val ESDeleted = 0x115
            const val ESSelected = 0x116
        }
    }

    fun interface EventListener {
        fun onEvent(event: Event)
    }

    class TrackDescription(val id: Int, val name: String)
    class Chapter(val timeOffset: Long, val duration: Long, val name: String?)
    class VideoTrack(
        val width: Int = 1920,
        val height: Int = 1080,
        val sarNum: Int = 1,
        val sarDen: Int = 1,
        val orientation: Int = 0,
    )

    val vlcVout: IVLCVout = object : IVLCVout {}
    var media: Media? = null
    var time: Long = 0L
    var rate: Float = 1f
    var volume: Int = 100
    val currentVideoTrack: VideoTrack? = VideoTrack()
    val audioTracks: Array<TrackDescription>? = emptyArray()
    val audioTrack: Int = -1
    val spuTracks: Array<TrackDescription>? = emptyArray()
    val spuTrack: Int = -1

    fun setEventListener(listener: EventListener?) {}
    fun play() {}
    fun pause() {}
    fun stop() {}
    fun release() {}
    fun setTime(time: Long): Long {
        this.time = time
        return time
    }
    fun setAudioTrack(id: Int): Boolean = true
    fun setSpuTrack(id: Int): Boolean = true
    fun setVideoTrackEnabled(enabled: Boolean) {}
    fun getChapters(title: Int): Array<Chapter>? = emptyArray()
}
