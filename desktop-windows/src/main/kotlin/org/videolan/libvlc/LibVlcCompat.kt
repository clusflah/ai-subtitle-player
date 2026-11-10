package org.videolan.libvlc

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.aisub.player.desktop.DesktopExoPlayer
import org.videolan.libvlc.interfaces.IVLCVout

class LibVLC(val context: Context, val options: List<String> = emptyList()) {
    fun release() {}
}

class Media(libVLC: LibVLC, val uri: Uri) {
    internal val options = mutableListOf<String>()
    fun setHWDecoderEnabled(enabled: Boolean, force: Boolean) {}
    fun addOption(option: String) {
        options.add(option)
    }
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

    private val engine = DesktopExoPlayer(libVLC.context, null)
    private var eventListener: EventListener? = null

    val vlcVout: IVLCVout = object : IVLCVout {}
    var media: Media? = null
    val isPlaying: Boolean get() = engine.isPlaying
    val length: Long get() = engine.duration
    val time: Long get() = engine.currentPosition
    var rate: Float = 1f
        set(value) {
            field = value
            engine.playbackParameters = PlaybackParameters(value)
        }
    var volume: Int = 100
        set(value) {
            field = value
            engine.volume = (value / 100f).coerceIn(0f, 1f)
        }
    var currentVideoTrack: VideoTrack? = VideoTrack()
        private set
    val audioTracks: Array<TrackDescription>? = emptyArray()
    val audioTrack: Int = -1
    val spuTracks: Array<TrackDescription>? = emptyArray()
    val spuTrack: Int = -1

    init {
        engine.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> eventListener?.onEvent(Event(Event.Opening))
                    Player.STATE_READY -> {
                        val d = engine.duration
                        if (d > 0) eventListener?.onEvent(Event(Event.LengthChanged, lengthChanged = d))
                        if (engine.playWhenReady) {
                            eventListener?.onEvent(Event(Event.Playing))
                        } else {
                            eventListener?.onEvent(Event(Event.Paused))
                        }
                    }
                    Player.STATE_ENDED -> eventListener?.onEvent(Event(Event.EndReached))
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    eventListener?.onEvent(Event(Event.Playing))
                } else if (engine.playbackState == Player.STATE_READY) {
                    eventListener?.onEvent(Event(Event.Paused))
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    currentVideoTrack = VideoTrack(videoSize.width, videoSize.height)
                    eventListener?.onEvent(Event(Event.Vout, voutCount = 1))
                }
            }

            override fun onRenderedFirstFrame() {
                eventListener?.onEvent(Event(Event.Vout, voutCount = 1))
            }

            override fun onPlayerError(error: PlaybackException) {
                eventListener?.onEvent(Event(Event.EncounteredError))
            }
        })
    }

    fun setEventListener(listener: EventListener?) {
        this.eventListener = listener
    }

    fun play() {
        val m = media ?: return
        if (engine.playbackState == Player.STATE_IDLE || engine.playbackState == Player.STATE_ENDED) {
            var startMs = 0L
            for (opt in m.options) {
                if (opt.startsWith(":start-time=")) {
                    val sec = opt.removePrefix(":start-time=").toDoubleOrNull() ?: 0.0
                    startMs = (sec * 1000.0).toLong()
                }
            }
            engine.setMediaItem(MediaItem.fromUri(m.uri), startMs)
            engine.playWhenReady = true
            engine.prepare()
        } else {
            engine.play()
        }
    }

    fun pause() {
        engine.pause()
    }

    fun stop() {
        engine.stop()
    }

    fun release() {
        engine.release()
    }

    fun setTime(time: Long): Long {
        engine.seekTo(time)
        return time
    }

    fun setAudioTrack(id: Int): Boolean = true
    fun setSpuTrack(id: Int): Boolean = true
    fun setVideoTrackEnabled(enabled: Boolean) {}
    fun getChapters(title: Int): Array<Chapter>? = emptyArray()
}
