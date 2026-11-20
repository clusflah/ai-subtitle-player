package org.videolan.libvlc

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import com.aisub.player.desktop.DesktopBackendMode
import com.aisub.player.desktop.DesktopExoPlayer
import org.videolan.libvlc.interfaces.IVLCVout

class LibVLC(val context: Context, val options: List<String> = emptyList()) {
    fun release() {}
}

class Media(libVLC: LibVLC, val uri: Uri) {
    internal val options = mutableListOf<String>()
    internal var hwdecPref: Boolean? = null
    internal var title: String? = null
    internal var isLive: Boolean = false
    fun setHWDecoderEnabled(enabled: Boolean, force: Boolean) {
        hwdecPref = when {
            !enabled -> false
            force -> true
            else -> null
        }
    }
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

    private val engine = DesktopExoPlayer(
        context = libVLC.context,
        dataSourceFactory = (libVLC.context.applicationContext as? android.app.Application)?.let { app ->
            runCatching { com.aisub.player.authDataSourceFactory(app) }.getOrNull()
        },
        backendMode = DesktopBackendMode.VLC_ONLY,
    )
    private var eventListener: EventListener? = null

    @Volatile
    var lastError: PlaybackException? = null
        private set

    val vlcVout: IVLCVout = object : IVLCVout {}
    private var mediaDirty = false
    var media: Media? = null
        set(value) {
            field = value
            if (value != null) mediaDirty = true
        }
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

    val audioTracks: Array<TrackDescription>?
        get() = engine.vlcAudioTrackDescriptions().map { TrackDescription(it.first, it.second) }.toTypedArray()

    val audioTrack: Int
        get() = engine.vlcAudioTrackId()

    val spuTracks: Array<TrackDescription>?
        get() = engine.vlcSpuTrackDescriptions().map { TrackDescription(it.first, it.second) }.toTypedArray()

    val spuTrack: Int
        get() = engine.vlcSpuTrackId()

    init {
        engine.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        eventListener?.onEvent(Event(Event.Opening))
                        eventListener?.onEvent(Event(Event.Buffering, buffering = 0f))
                    }
                    Player.STATE_READY -> {
                        val d = engine.duration
                        if (d > 0) eventListener?.onEvent(Event(Event.LengthChanged, lengthChanged = d))
                        eventListener?.onEvent(Event(Event.Buffering, buffering = 100f))
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

            override fun onTracksChanged(tracks: Tracks) {
                eventListener?.onEvent(Event(Event.ESAdded))
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    currentVideoTrack = VideoTrack(videoSize.width, videoSize.height)
                    eventListener?.onEvent(Event(Event.Vout, voutCount = 1))
                }
            }

            override fun onRenderedFirstFrame() {
                val d = engine.duration
                if (d > 0) eventListener?.onEvent(Event(Event.LengthChanged, lengthChanged = d))
                eventListener?.onEvent(Event(Event.Vout, voutCount = 1))
            }

            override fun onPlayerError(error: PlaybackException) {
                lastError = error
                eventListener?.onEvent(Event(Event.EncounteredError))
            }
        })
    }

    fun setEventListener(listener: EventListener?) {
        this.eventListener = listener
    }

    val activePipelineDescription: String
        get() = engine.activePipelineDescription

    fun setHwdecPreference(forceHwdec: Boolean?) {
        engine.updateHwdecPreference(forceHwdec)
    }

    fun setEmbeddedSubtitlesSuppressed(suppressed: Boolean) {
        engine.setEmbeddedSubtitlesSuppressed(suppressed)
    }

    fun play() {
        val m = media ?: return
        lastError = null
        if (mediaDirty || engine.playbackState == Player.STATE_IDLE || engine.playbackState == Player.STATE_ENDED) {
            mediaDirty = false
            var startMs = 0L
            for (opt in m.options) {
                if (opt.startsWith(":start-time=")) {
                    val sec = opt.removePrefix(":start-time=").toDoubleOrNull() ?: 0.0
                    startMs = (sec * 1000.0).toLong()
                }
            }
            engine.hwdecPreference = m.hwdecPref
            val item = MediaItem.Builder()
                .setUri(m.uri)
                .setMediaId(m.uri.toString())
                .apply {
                    m.title?.takeIf { it.isNotBlank() }?.let { t ->
                        setMediaMetadata(MediaMetadata.Builder().setTitle(t).build())
                    }
                    if (m.isLive) {
                        setTag("live")
                        if (m.uri.toString().contains(".m3u8", ignoreCase = true)) {
                            setMimeType(MimeTypes.APPLICATION_M3U8)
                        }
                    }
                }
                .build()
            engine.setMediaItem(item, startMs)
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

    fun setAudioTrack(id: Int): Boolean = engine.setVlcAudioTrackId(id)
    fun setSpuTrack(id: Int): Boolean = engine.setVlcSpuTrackId(id)
    fun setVideoTrackEnabled(enabled: Boolean) {
        engine.setVlcVideoTrackEnabled(enabled)
    }
    fun getChapters(title: Int): Array<Chapter>? =
        engine.chapters().map { Chapter(it.startMs, (it.endMs ?: it.startMs) - it.startMs, it.title) }.toTypedArray()
}
