package androidx.media3.common

import android.net.Uri
import android.os.Looper
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet

object C {
    const val TIME_UNSET = Long.MIN_VALUE + 1
    const val INDEX_UNSET = -1
    const val LENGTH_UNSET = -1
    const val RESULT_END_OF_INPUT = -1
    const val MICROS_PER_SECOND = 1_000_000L

    const val TRACK_TYPE_UNKNOWN = -1
    const val TRACK_TYPE_DEFAULT = 0
    const val TRACK_TYPE_AUDIO = 1
    const val TRACK_TYPE_VIDEO = 2
    const val TRACK_TYPE_TEXT = 3

    const val FORMAT_HANDLED = 4
    const val FORMAT_UNSUPPORTED_TYPE = 0

    const val ENCODING_INVALID = 0
    const val ENCODING_PCM_8BIT = 3
    const val ENCODING_PCM_16BIT = 2
    const val ENCODING_PCM_24BIT = 21
    const val ENCODING_PCM_32BIT = 22
    const val ENCODING_PCM_FLOAT = 4

    const val BUFFER_FLAG_KEY_FRAME = 1
    const val BUFFER_FLAG_END_OF_STREAM = 4

    const val USAGE_MEDIA = 1
    const val AUDIO_CONTENT_TYPE_MOVIE = 3
    const val AUDIO_CONTENT_TYPE_SPEECH = 1
    const val WAKE_MODE_NETWORK = 2

    const val CONTENT_TYPE_DASH = 0
    const val CONTENT_TYPE_HLS = 2
    const val CONTENT_TYPE_OTHER = 4

    const val SELECTION_FLAG_DEFAULT = 1
    const val SELECTION_FLAG_FORCED = 2

    const val DATA_TYPE_MEDIA = 1
    const val DATA_TYPE_MEDIA_INITIALIZATION = 2
    const val DATA_TYPE_MANIFEST = 4

    const val ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND = 0x00000400
    const val ROLE_FLAG_TRANSCRIBES_DIALOG = 0x00001000
    const val ROLE_FLAG_EASY_TO_READ = 0x00002000
    const val ROLE_FLAG_DESCRIBES_VIDEO = 0x00000200
    const val ROLE_FLAG_MAIN = 0x00000001
    const val ROLE_FLAG_ALTERNATE = 0x00000002
    const val ROLE_FLAG_SUPPLEMENTARY = 0x00000004
    const val ROLE_FLAG_COMMENTARY = 0x00000008
    const val ROLE_FLAG_DUB = 0x00000010
    const val ROLE_FLAG_EMERGENCY = 0x00000020
    const val ROLE_FLAG_CAPTION = 0x00000040
    const val ROLE_FLAG_SUBTITLE = 0x00000080
    const val ROLE_FLAG_SIGN = 0x00000100
}

object MimeTypes {
    const val APPLICATION_M3U8 = "application/x-mpegURL"
    const val APPLICATION_MPD = "application/dash+xml"
    const val APPLICATION_SUBRIP = "application/x-subrip"
    const val APPLICATION_TTML = "application/ttml+xml"
    const val APPLICATION_TX3G = "application/x-quicktime-tx3g"
    const val APPLICATION_PGS = "application/pgs"
    const val APPLICATION_VOBSUB = "application/vobsub"
    const val APPLICATION_DVBSUBS = "application/dvbsubs"
    const val APPLICATION_MEDIA3_CUES = "application/x-media3-cues"
    const val TEXT_VTT = "text/vtt"
    const val TEXT_SSA = "text/x-ssa"
    const val TEXT_UNKNOWN = "text/x-unknown"
    const val AUDIO_RAW = "audio/raw"
    const val AUDIO_UNKNOWN = "audio/x-unknown"
    const val VIDEO_UNKNOWN = "video/x-unknown"

    @JvmStatic
    fun isAudio(mimeType: String?): Boolean = mimeType?.startsWith("audio/") == true

    @JvmStatic
    fun isVideo(mimeType: String?): Boolean = mimeType?.startsWith("video/") == true

    @JvmStatic
    fun isText(mimeType: String?): Boolean =
        mimeType?.startsWith("text/") == true ||
            mimeType in setOf(
                APPLICATION_SUBRIP,
                APPLICATION_TTML,
                APPLICATION_TX3G,
                APPLICATION_PGS,
                APPLICATION_VOBSUB,
                APPLICATION_DVBSUBS,
                APPLICATION_MEDIA3_CUES,
            )
}

fun interface DataReader {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

class AudioAttributes private constructor() {
    class Builder {
        fun setUsage(usage: Int): Builder = this
        fun setContentType(contentType: Int): Builder = this
        fun build(): AudioAttributes = DEFAULT
    }

    companion object {
        val DEFAULT = AudioAttributes()
    }
}

class AuxEffectInfo(val effectId: Int, val sendLevel: Float)

open class ParserException(message: String?, cause: Throwable?) : IOException(message, cause) {
    companion object {
        @JvmStatic
        fun createForMalformedContainer(message: String?, cause: Throwable?): ParserException =
            ParserException(message, cause)

        @JvmStatic
        fun createForUnsupportedContainerFeature(message: String?): ParserException =
            ParserException(message, null)
    }
}

class Metadata(private val entries: List<Entry> = emptyList()) {
    interface Entry
    fun length(): Int = entries.size
    fun get(index: Int): Entry = entries[index]
}

data class Label(val language: String?, val value: String)

class Format private constructor(
    val id: String?,
    val label: String?,
    val labels: List<Label>,
    val language: String?,
    val selectionFlags: Int,
    val roleFlags: Int,
    val averageBitrate: Int,
    val peakBitrate: Int,
    val bitrate: Int,
    val codecs: String?,
    val metadata: Metadata?,
    val containerMimeType: String?,
    val sampleMimeType: String?,
    val maxInputSize: Int,
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val channelCount: Int,
    val sampleRate: Int,
    val pcmEncoding: Int,
    val initializationData: List<ByteArray>,
) {
    fun buildUpon(): Builder = Builder(this)

    class Builder() {
        private var id: String? = null
        private var label: String? = null
        private var language: String? = null
        private var selectionFlags: Int = 0
        private var roleFlags: Int = 0
        private var averageBitrate: Int = Format.NO_VALUE
        private var peakBitrate: Int = Format.NO_VALUE
        private var codecs: String? = null
        private var metadata: Metadata? = null
        private var containerMimeType: String? = null
        private var sampleMimeType: String? = null
        private var maxInputSize: Int = Format.NO_VALUE
        private var width: Int = Format.NO_VALUE
        private var height: Int = Format.NO_VALUE
        private var frameRate: Float = Format.NO_VALUE.toFloat()
        private var channelCount: Int = Format.NO_VALUE
        private var sampleRate: Int = Format.NO_VALUE
        private var pcmEncoding: Int = Format.NO_VALUE
        private var initializationData: List<ByteArray> = emptyList()

        constructor(f: Format) : this() {
            id = f.id
            label = f.label
            language = f.language
            selectionFlags = f.selectionFlags
            roleFlags = f.roleFlags
            averageBitrate = f.averageBitrate
            peakBitrate = f.peakBitrate
            codecs = f.codecs
            metadata = f.metadata
            containerMimeType = f.containerMimeType
            sampleMimeType = f.sampleMimeType
            maxInputSize = f.maxInputSize
            width = f.width
            height = f.height
            frameRate = f.frameRate
            channelCount = f.channelCount
            sampleRate = f.sampleRate
            pcmEncoding = f.pcmEncoding
            initializationData = f.initializationData
        }

        fun setId(v: String?): Builder = apply { id = v }
        fun setId(v: Int): Builder = apply { id = v.toString() }
        fun setLabel(v: String?): Builder = apply { label = v }
        fun setLanguage(v: String?): Builder = apply { language = v }
        fun setSelectionFlags(v: Int): Builder = apply { selectionFlags = v }
        fun setRoleFlags(v: Int): Builder = apply { roleFlags = v }
        fun setAverageBitrate(v: Int): Builder = apply { averageBitrate = v }
        fun setPeakBitrate(v: Int): Builder = apply { peakBitrate = v }
        fun setCodecs(v: String?): Builder = apply { codecs = v }
        fun setMetadata(v: Metadata?): Builder = apply { metadata = v }
        fun setContainerMimeType(v: String?): Builder = apply { containerMimeType = v }
        fun setSampleMimeType(v: String?): Builder = apply { sampleMimeType = v }
        fun setMaxInputSize(v: Int): Builder = apply { maxInputSize = v }
        fun setWidth(v: Int): Builder = apply { width = v }
        fun setHeight(v: Int): Builder = apply { height = v }
        fun setFrameRate(v: Float): Builder = apply { frameRate = v }
        fun setChannelCount(v: Int): Builder = apply { channelCount = v }
        fun setSampleRate(v: Int): Builder = apply { sampleRate = v }
        fun setPcmEncoding(v: Int): Builder = apply { pcmEncoding = v }
        fun setInitializationData(v: List<ByteArray>?): Builder = apply { initializationData = v.orEmpty() }

        fun build(): Format = Format(
            id = id,
            label = label,
            labels = if (label != null) listOf(Label(language, label!!)) else emptyList(),
            language = language,
            selectionFlags = selectionFlags,
            roleFlags = roleFlags,
            averageBitrate = averageBitrate,
            peakBitrate = peakBitrate,
            bitrate = if (peakBitrate != NO_VALUE) peakBitrate else averageBitrate,
            codecs = codecs,
            metadata = metadata,
            containerMimeType = containerMimeType,
            sampleMimeType = sampleMimeType,
            maxInputSize = maxInputSize,
            width = width,
            height = height,
            frameRate = frameRate,
            channelCount = channelCount,
            sampleRate = sampleRate,
            pcmEncoding = pcmEncoding,
            initializationData = initializationData,
        )
    }

    companion object {
        const val NO_VALUE = -1
    }
}

class MediaMetadata private constructor(
    val title: CharSequence?,
    val artist: CharSequence?,
    val albumTitle: CharSequence?,
    val albumArtist: CharSequence?,
    val displayTitle: CharSequence?,
    val artworkData: ByteArray?,
    val artworkDataType: Int?,
) {
    fun buildUpon(): Builder = Builder(this)

    class Builder() {
        private var title: CharSequence? = null
        private var artist: CharSequence? = null
        private var albumTitle: CharSequence? = null
        private var albumArtist: CharSequence? = null
        private var displayTitle: CharSequence? = null
        private var artworkData: ByteArray? = null
        private var artworkDataType: Int? = null

        constructor(m: MediaMetadata) : this() {
            title = m.title
            artist = m.artist
            albumTitle = m.albumTitle
            albumArtist = m.albumArtist
            displayTitle = m.displayTitle
            artworkData = m.artworkData
            artworkDataType = m.artworkDataType
        }

        fun setTitle(v: CharSequence?): Builder = apply { title = v }
        fun setArtist(v: CharSequence?): Builder = apply { artist = v }
        fun setAlbumTitle(v: CharSequence?): Builder = apply { albumTitle = v }
        fun setAlbumArtist(v: CharSequence?): Builder = apply { albumArtist = v }
        fun setDisplayTitle(v: CharSequence?): Builder = apply { displayTitle = v }
        fun setArtworkData(data: ByteArray?, pictureType: Int?): Builder = apply {
            artworkData = data
            artworkDataType = pictureType
        }

        fun populate(other: MediaMetadata?): Builder = apply {
            if (other == null) return@apply
            other.title?.let { title = it }
            other.artist?.let { artist = it }
            other.albumTitle?.let { albumTitle = it }
            other.albumArtist?.let { albumArtist = it }
            other.displayTitle?.let { displayTitle = it }
            other.artworkData?.let { artworkData = it; artworkDataType = other.artworkDataType }
        }

        fun populateFromMetadata(metadata: Metadata): Builder = this

        fun build(): MediaMetadata = MediaMetadata(
            title = title,
            artist = artist,
            albumTitle = albumTitle,
            albumArtist = albumArtist,
            displayTitle = displayTitle,
            artworkData = artworkData,
            artworkDataType = artworkDataType,
        )
    }

    companion object {
        val EMPTY = Builder().build()
        const val PICTURE_TYPE_FRONT_COVER = 3
    }
}

class MediaItem private constructor(
    val mediaId: String,
    val localConfiguration: LocalConfiguration?,
    val liveConfiguration: LiveConfiguration,
    val mediaMetadata: MediaMetadata,
) {
    class LocalConfiguration(
        val uri: Uri,
        val mimeType: String?,
        val tag: Any?,
    )

    class LiveConfiguration(
        val targetOffsetMs: Long = C.TIME_UNSET,
        val minOffsetMs: Long = C.TIME_UNSET,
        val maxOffsetMs: Long = C.TIME_UNSET,
        val minPlaybackSpeed: Float = C.TIME_UNSET.toFloat(),
        val maxPlaybackSpeed: Float = C.TIME_UNSET.toFloat(),
    ) {
        fun buildUpon(): Builder = Builder(this)

        class Builder(c: LiveConfiguration = LiveConfiguration()) {
            private var targetOffsetMs: Long = c.targetOffsetMs
            private var minOffsetMs: Long = c.minOffsetMs
            private var maxOffsetMs: Long = c.maxOffsetMs
            private var minPlaybackSpeed: Float = c.minPlaybackSpeed
            private var maxPlaybackSpeed: Float = c.maxPlaybackSpeed

            fun setTargetOffsetMs(v: Long): Builder = apply { targetOffsetMs = v }
            fun setMinOffsetMs(v: Long): Builder = apply { minOffsetMs = v }
            fun setMaxOffsetMs(v: Long): Builder = apply { maxOffsetMs = v }
            fun setMinPlaybackSpeed(v: Float): Builder = apply { minPlaybackSpeed = v }
            fun setMaxPlaybackSpeed(v: Float): Builder = apply { maxPlaybackSpeed = v }
            fun build(): LiveConfiguration = LiveConfiguration(
                targetOffsetMs, minOffsetMs, maxOffsetMs, minPlaybackSpeed, maxPlaybackSpeed,
            )
        }
    }

    fun buildUpon(): Builder = Builder(this)

    class Builder() {
        private var mediaId: String = ""
        private var uri: Uri? = null
        private var mimeType: String? = null
        private var tag: Any? = null
        private var liveConfiguration: LiveConfiguration = LiveConfiguration()
        private var mediaMetadata: MediaMetadata = MediaMetadata.EMPTY

        constructor(item: MediaItem) : this() {
            mediaId = item.mediaId
            uri = item.localConfiguration?.uri
            mimeType = item.localConfiguration?.mimeType
            tag = item.localConfiguration?.tag
            liveConfiguration = item.liveConfiguration
            mediaMetadata = item.mediaMetadata
        }

        fun setMediaId(v: String): Builder = apply { mediaId = v }
        fun setUri(v: Uri?): Builder = apply { uri = v }
        fun setUri(v: String?): Builder = apply { uri = v?.let(Uri::parse) }
        fun setMimeType(v: String?): Builder = apply { mimeType = v }
        fun setTag(v: Any?): Builder = apply { tag = v }
        fun setLiveConfiguration(v: LiveConfiguration): Builder = apply { liveConfiguration = v }
        fun setLiveConfiguration(b: LiveConfiguration.Builder): Builder = apply { liveConfiguration = b.build() }
        fun setMediaMetadata(v: MediaMetadata): Builder = apply { mediaMetadata = v }

        fun build(): MediaItem {
            val u = uri ?: if (mediaId.isNotEmpty()) Uri.parse(mediaId) else null
            return MediaItem(
                mediaId = mediaId.ifEmpty { u?.toString().orEmpty() },
                localConfiguration = u?.let { LocalConfiguration(it, mimeType, tag) },
                liveConfiguration = liveConfiguration,
                mediaMetadata = mediaMetadata,
            )
        }
    }

    companion object {
        val EMPTY = Builder().build()

        @JvmStatic
        fun fromUri(uri: Uri): MediaItem = Builder().setUri(uri).build()

        @JvmStatic
        fun fromUri(uriString: String): MediaItem = Builder().setUri(uriString).build()
    }
}

class TrackGroup(val id: String, vararg val formats: Format) {
    constructor(vararg formats: Format) : this("", *formats)
    val length: Int get() = formats.size
    val type: Int
        get() {
            val mime = formats.firstOrNull()?.sampleMimeType
            return when {
                MimeTypes.isAudio(mime) || id.startsWith("a") -> C.TRACK_TYPE_AUDIO
                MimeTypes.isVideo(mime) || id.startsWith("v") -> C.TRACK_TYPE_VIDEO
                MimeTypes.isText(mime) || id.startsWith("s") -> C.TRACK_TYPE_TEXT
                else -> C.TRACK_TYPE_UNKNOWN
            }
        }

    fun getFormat(index: Int): Format = formats[index]
    fun indexOf(format: Format): Int = formats.indexOf(format)
}

class Tracks(val groups: List<Group> = emptyList()) {
    val isEmpty: Boolean get() = groups.isEmpty()

    class Group(
        val mediaTrackGroup: TrackGroup,
        val adaptiveSupported: Boolean = false,
        private val trackSupport: IntArray = IntArray(mediaTrackGroup.length) { C.FORMAT_HANDLED },
        private val trackSelected: BooleanArray = BooleanArray(mediaTrackGroup.length) { false },
    ) {
        val length: Int get() = mediaTrackGroup.length
        val type: Int get() = mediaTrackGroup.type
        val isSelected: Boolean get() = trackSelected.any { it }
        val isSupported: Boolean get() = trackSupport.any { it == C.FORMAT_HANDLED }

        fun getTrackFormat(trackIndex: Int): Format = mediaTrackGroup.getFormat(trackIndex)
        fun isTrackSelected(trackIndex: Int): Boolean = trackSelected.getOrElse(trackIndex) { false }
        fun isTrackSupported(trackIndex: Int): Boolean = trackSupport.getOrElse(trackIndex) { C.FORMAT_HANDLED } == C.FORMAT_HANDLED
    }

    companion object {
        val EMPTY = Tracks(emptyList())
    }
}

class TrackSelectionOverride(val mediaTrackGroup: TrackGroup, val trackIndices: List<Int>) {
    constructor(mediaTrackGroup: TrackGroup, trackIndex: Int) : this(mediaTrackGroup, listOf(trackIndex))
    val type: Int get() = mediaTrackGroup.type
}

open class TrackSelectionParameters(
    val overrides: Map<TrackGroup, TrackSelectionOverride> = emptyMap(),
    val disabledTrackTypes: Set<Int> = emptySet(),
) {
    open fun buildUpon(): Builder = Builder(this)

    open class Builder(params: TrackSelectionParameters = DEFAULT) {
        protected val overrides = LinkedHashMap<TrackGroup, TrackSelectionOverride>(params.overrides)
        protected val disabledTrackTypes = LinkedHashSet<Int>(params.disabledTrackTypes)

        open fun setTrackTypeDisabled(trackType: Int, disabled: Boolean): Builder = apply {
            if (disabled) disabledTrackTypes.add(trackType) else disabledTrackTypes.remove(trackType)
        }

        open fun clearOverridesOfType(trackType: Int): Builder = apply {
            overrides.entries.removeIf { it.value.type == trackType }
        }

        open fun setOverrideForType(override: TrackSelectionOverride): Builder = apply {
            overrides.entries.removeIf { it.value.type == override.type }
            overrides[override.mediaTrackGroup] = override
        }

        open fun setPreferredAudioLanguages(vararg languages: String): Builder = this
        open fun setPreferredTextLanguages(vararg languages: String): Builder = this

        open fun setForceLowestBitrate(forceLowestBitrate: Boolean): Builder = this

        open fun build(): TrackSelectionParameters = TrackSelectionParameters(
            overrides = HashMap(overrides),
            disabledTrackTypes = HashSet(disabledTrackTypes),
        )
    }

    companion object {
        val DEFAULT = TrackSelectionParameters()
    }
}

data class VideoSize(
    val width: Int,
    val height: Int,
    val unappliedRotationDegrees: Int = 0,
    val pixelWidthHeightRatio: Float = 1f,
) {
    constructor(width: Int, height: Int, pixelWidthHeightRatio: Float) : this(width, height, 0, pixelWidthHeightRatio)

    companion object {
        val UNKNOWN = VideoSize(0, 0, 0, 1f)
    }
}

data class PlaybackParameters(val speed: Float = 1f, val pitch: Float = 1f) {
    fun withSpeed(speed: Float): PlaybackParameters = copy(speed = speed)
    companion object {
        val DEFAULT = PlaybackParameters(1f, 1f)
    }
}

open class PlaybackException(
    message: String?,
    cause: Throwable?,
    val errorCode: Int,
) : Exception(message, cause) {
    val errorCodeName: String
        get() = "ERROR_$errorCode"

    companion object {
        const val ERROR_CODE_UNSPECIFIED = 1000
        const val ERROR_CODE_BEHIND_LIVE_WINDOW = 1002
        const val ERROR_CODE_IO_UNSPECIFIED = 2000
        const val ERROR_CODE_IO_NETWORK_CONNECTION_FAILED = 2001
        const val ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT = 2002
        const val ERROR_CODE_IO_BAD_HTTP_STATUS = 2004
        const val ERROR_CODE_IO_FILE_NOT_FOUND = 2005
        const val ERROR_CODE_IO_NO_PERMISSION = 2006
        const val ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE = 2008
        const val ERROR_CODE_PARSING_CONTAINER_MALFORMED = 3001
        const val ERROR_CODE_PARSING_MANIFEST_MALFORMED = 3002
        const val ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED = 3003
        const val ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED = 3004
        const val ERROR_CODE_DECODER_INIT_FAILED = 4001
        const val ERROR_CODE_DECODER_QUERY_FAILED = 4002
        const val ERROR_CODE_DECODING_FAILED = 4003
        const val ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES = 4004
        const val ERROR_CODE_DECODING_FORMAT_UNSUPPORTED = 4005
        const val ERROR_CODE_AUDIO_TRACK_INIT_FAILED = 5001
        const val ERROR_CODE_AUDIO_TRACK_WRITE_FAILED = 5002
    }
}

open class Timeline {
    open val isEmpty: Boolean get() = windowCount == 0
    open val windowCount: Int get() = 0
    open val periodCount: Int get() = windowCount

    open fun getWindow(windowIndex: Int, window: Window): Window = window

    class Window {
        var durationUs: Long = C.TIME_UNSET
        var positionInFirstPeriodUs: Long = 0L
        var windowStartTimeMs: Long = C.TIME_UNSET
        var isSeekable: Boolean = true
        var isDynamic: Boolean = false
        val durationMs: Long get() = if (durationUs == C.TIME_UNSET) C.TIME_UNSET else durationUs / 1000L

        fun set(
            uid: Any?,
            mediaItem: MediaItem?,
            manifest: Any?,
            presentationStartTimeMs: Long,
            windowStartTimeMs: Long,
            elapsedRealtimeEpochOffsetMs: Long,
            isSeekable: Boolean,
            isDynamic: Boolean,
            liveConfiguration: MediaItem.LiveConfiguration?,
            defaultPositionUs: Long,
            durationUs: Long,
            firstPeriodIndex: Int,
            lastPeriodIndex: Int,
            positionInFirstPeriodUs: Long,
        ): Window = apply {
            this.windowStartTimeMs = windowStartTimeMs
            this.isSeekable = isSeekable
            this.isDynamic = isDynamic
            this.durationUs = durationUs
            this.positionInFirstPeriodUs = positionInFirstPeriodUs
        }
    }

    companion object {
        val EMPTY = Timeline()
    }
}

interface Player {
    interface Listener {
        fun onPlaybackStateChanged(playbackState: Int) {}
        fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {}
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {}
        fun onTracksChanged(tracks: Tracks) {}
        fun onPlayerError(error: PlaybackException) {}
        fun onVideoSizeChanged(videoSize: VideoSize) {}
        fun onRenderedFirstFrame() {}
        fun onAudioSessionIdChanged(audioSessionId: Int) {}
        fun onPositionDiscontinuity(oldPosition: PositionInfo, newPosition: PositionInfo, reason: Int) {}
        fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {}
        fun onTimelineChanged(timeline: Timeline, reason: Int) {}
        fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {}
    }

    class PositionInfo(
        val mediaItemIndex: Int = 0,
        val positionMs: Long = 0L,
    )

    class Commands private constructor(val flags: Set<Int>) {
        class Builder {
            private val set = HashSet<Int>()
            fun add(cmd: Int): Builder = apply { set.add(cmd) }
            fun addAll(vararg cmds: Int): Builder = apply { cmds.forEach { set.add(it) } }
            fun build(): Commands = Commands(set)
        }
        companion object {
            val EMPTY = Commands(emptySet())
        }
    }

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    fun setMediaItem(mediaItem: MediaItem)
    fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long)
    fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long)
    fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean)
    fun addMediaItem(mediaItem: MediaItem)
    fun addMediaItems(mediaItems: List<MediaItem>)
    fun replaceMediaItem(index: Int, mediaItem: MediaItem)
    fun clearMediaItems()

    val mediaItemCount: Int
    val currentMediaItemIndex: Int
    val currentMediaItem: MediaItem?
    fun getMediaItemAt(index: Int): MediaItem

    fun prepare()
    fun play()
    fun pause()
    fun stop()
    fun release()

    var playWhenReady: Boolean
    val playbackState: Int
    val isPlaying: Boolean
    val playerError: PlaybackException?

    val duration: Long
    val currentPosition: Long
    val bufferedPosition: Long
    val currentLiveOffset: Long
    val isCurrentMediaItemLive: Boolean
    val isCurrentMediaItemSeekable: Boolean

    fun seekTo(positionMs: Long)
    fun seekTo(mediaItemIndex: Int, positionMs: Long)
    fun seekToDefaultPosition()
    fun seekToDefaultPosition(mediaItemIndex: Int)
    fun seekToNext()
    fun seekToPrevious()
    fun seekToNextMediaItem()
    fun seekToPreviousMediaItem()
    fun hasNextMediaItem(): Boolean
    fun hasPreviousMediaItem(): Boolean

    var pauseAtEndOfMediaItems: Boolean
        get() = false
        set(value) {}
    val nextMediaItemIndex: Int get() = if (hasNextMediaItem()) currentMediaItemIndex + 1 else -1
    val previousMediaItemIndex: Int get() = if (hasPreviousMediaItem()) currentMediaItemIndex - 1 else -1
    fun removeMediaItem(index: Int) {}

    var repeatMode: Int
    var shuffleModeEnabled: Boolean
    var playbackParameters: PlaybackParameters
    fun setPlaybackSpeed(speed: Float) { playbackParameters = playbackParameters.withSpeed(speed) }
    var volume: Float
    var trackSelectionParameters: TrackSelectionParameters
    val currentTracks: Tracks
    val videoSize: VideoSize
    val currentTimeline: Timeline
    val currentManifest: Any?

    fun getMediaMetadata(): MediaMetadata

    fun setVideoSurfaceView(surfaceView: android.view.SurfaceView?) {}
    fun clearVideoSurfaceView(surfaceView: android.view.SurfaceView?) {}
    fun setVideoTextureView(textureView: android.view.TextureView?) {}
    fun clearVideoTextureView(textureView: android.view.TextureView?) {}

    companion object {
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4

        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ONE = 1
        const val REPEAT_MODE_ALL = 2

        const val PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST = 1
        const val PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS = 2
        const val PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY = 3
        const val PLAY_WHEN_READY_CHANGE_REASON_REMOTE = 4
        const val PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM = 5

        const val DISCONTINUITY_REASON_AUTO_TRANSITION = 0
        const val DISCONTINUITY_REASON_SEEK = 1

        const val MEDIA_ITEM_TRANSITION_REASON_AUTO = 1
        const val MEDIA_ITEM_TRANSITION_REASON_SEEK = 2
        const val MEDIA_ITEM_TRANSITION_REASON_REPEAT = 3
        const val MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED = 4

        const val COMMAND_PLAY_PAUSE = 1
        const val COMMAND_PREPARE = 2
        const val COMMAND_STOP = 3
        const val COMMAND_SEEK_TO_DEFAULT_POSITION = 4
        const val COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM = 5
        const val COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM = 6
        const val COMMAND_SEEK_TO_PREVIOUS = 7
        const val COMMAND_SEEK_TO_NEXT_MEDIA_ITEM = 8
        const val COMMAND_SEEK_TO_NEXT = 9
        const val COMMAND_SEEK_TO_MEDIA_ITEM = 10
        const val COMMAND_SET_SPEED_AND_PITCH = 13
        const val COMMAND_SET_VOLUME = 15
        const val COMMAND_GET_VOLUME = 22
        const val COMMAND_GET_CURRENT_MEDIA_ITEM = 16
        const val COMMAND_GET_TIMELINE = 17
        const val COMMAND_GET_METADATA = 18
        const val COMMAND_SET_MEDIA_ITEM = 31
        const val COMMAND_CHANGE_MEDIA_ITEMS = 20
        const val COMMAND_SET_VIDEO_SURFACE = 27
        const val COMMAND_GET_TRACKS = 30
        const val COMMAND_SET_TRACK_SELECTION_PARAMETERS = 29
        const val COMMAND_RELEASE = 32
    }
}

val Player.mediaMetadata: MediaMetadata get() = getMediaMetadata()

open class ForwardingPlayer(val wrappedPlayer: Player) : Player by wrappedPlayer {
    override fun getMediaMetadata(): MediaMetadata = wrappedPlayer.getMediaMetadata()
}

abstract class SimpleBasePlayer(looper: Looper) : Player {
    fun interface PositionSupplier {
        fun get(): Long
    }

    class MediaItemData private constructor(
        val uid: Any,
        val mediaItem: MediaItem,
        val mediaMetadata: MediaMetadata,
        val durationUs: Long,
        val isSeekable: Boolean,
        val tracks: Tracks,
    ) {
        class Builder(private val uid: Any) {
            private var mediaItem: MediaItem = MediaItem.Builder().build()
            private var mediaMetadata: MediaMetadata = MediaMetadata.EMPTY
            private var durationUs: Long = C.TIME_UNSET
            private var isSeekable: Boolean = true
            private var tracks: Tracks = Tracks.EMPTY

            fun setMediaItem(v: MediaItem): Builder = apply { mediaItem = v }
            fun setMediaMetadata(v: MediaMetadata): Builder = apply { mediaMetadata = v }
            fun setDurationUs(v: Long): Builder = apply { durationUs = v }
            fun setIsSeekable(v: Boolean): Builder = apply { isSeekable = v }
            fun setTracks(v: Tracks): Builder = apply { tracks = v }
            fun build(): MediaItemData = MediaItemData(uid, mediaItem, mediaMetadata, durationUs, isSeekable, tracks)
        }
    }

    class State private constructor(
        val availableCommands: Player.Commands,
        val playWhenReady: Boolean,
        val playbackState: Int,
        val playerError: PlaybackException?,
        val playlist: List<MediaItemData>,
        val currentMediaItemIndex: Int,
        val contentPositionSupplier: PositionSupplier,
        val playbackParameters: PlaybackParameters,
        val trackSelectionParameters: TrackSelectionParameters,
        val volume: Float,
        val videoSize: VideoSize,
        val newlyRenderedFirstFrame: Boolean,
    ) {
        class Builder {
            private var availableCommands = Player.Commands.EMPTY
            private var playWhenReady = false
            private var playbackState = Player.STATE_IDLE
            private var playerError: PlaybackException? = null
            private var playlist: List<MediaItemData> = emptyList()
            private var currentMediaItemIndex = 0
            private var contentPositionSupplier = PositionSupplier { 0L }
            private var playbackParameters = PlaybackParameters.DEFAULT
            private var trackSelectionParameters = TrackSelectionParameters.DEFAULT
            private var volume = 1f
            private var videoSize = VideoSize.UNKNOWN
            private var newlyRenderedFirstFrame = false

            fun setNewlyRenderedFirstFrame(v: Boolean): Builder = apply { newlyRenderedFirstFrame = v }
            fun setAvailableCommands(v: Player.Commands): Builder = apply { availableCommands = v }
            fun setPlayWhenReady(v: Boolean, reason: Int): Builder = apply { playWhenReady = v }
            fun setPlaybackState(v: Int): Builder = apply { playbackState = v }
            fun setPlayerError(v: PlaybackException?): Builder = apply { playerError = v }
            fun setPlaylist(v: List<MediaItemData>): Builder = apply { playlist = v }
            fun setCurrentMediaItemIndex(v: Int): Builder = apply { currentMediaItemIndex = v }
            fun setContentPositionMs(v: PositionSupplier): Builder = apply { contentPositionSupplier = v }
            fun setPlaybackParameters(v: PlaybackParameters): Builder = apply { playbackParameters = v }
            fun setTrackSelectionParameters(v: TrackSelectionParameters): Builder = apply { trackSelectionParameters = v }
            fun setVolume(v: Float): Builder = apply { volume = v }
            fun setVideoSize(v: VideoSize): Builder = apply { videoSize = v }

            fun build(): State = State(
                availableCommands, playWhenReady, playbackState, playerError, playlist,
                currentMediaItemIndex, contentPositionSupplier, playbackParameters,
                trackSelectionParameters, volume, videoSize, newlyRenderedFirstFrame,
            )
        }
    }

    private val listeners = CopyOnWriteArraySet<Player.Listener>()
    private var cachedState: State? = null

    protected abstract fun getState(): State

    protected fun invalidateState() {
        val old = cachedState
        val cur = getState()
        cachedState = cur
        if (old?.playbackState != cur.playbackState) {
            listeners.forEach { it.onPlaybackStateChanged(cur.playbackState) }
        }
        if (old?.playWhenReady != cur.playWhenReady) {
            listeners.forEach { it.onPlayWhenReadyChanged(cur.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        }
        val oldPlaying = old?.playWhenReady == true && old.playbackState == Player.STATE_READY
        val curPlaying = cur.playWhenReady && cur.playbackState == Player.STATE_READY
        if (oldPlaying != curPlaying) {
            listeners.forEach { it.onIsPlayingChanged(curPlaying) }
        }
        if (cur.playerError != null && old?.playerError != cur.playerError) {
            listeners.forEach { it.onPlayerError(cur.playerError) }
        }
        if (cur.newlyRenderedFirstFrame) {
            listeners.forEach { it.onRenderedFirstFrame() }
        }
        if (old?.videoSize != cur.videoSize) {
            listeners.forEach { it.onVideoSizeChanged(cur.videoSize) }
        }
        val curTracks = cur.playlist.getOrNull(cur.currentMediaItemIndex)?.tracks ?: Tracks.EMPTY
        val oldTracks = old?.playlist?.getOrNull(old.currentMediaItemIndex)?.tracks ?: Tracks.EMPTY
        if (oldTracks != curTracks) {
            listeners.forEach { it.onTracksChanged(curTracks) }
        }
    }

    protected open fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> =
        Futures.immediateVoidFuture()
    protected open fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> =
        Futures.immediateVoidFuture()
    protected open fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleStop(): ListenableFuture<*> = Futures.immediateVoidFuture()
    protected open fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun s(): State = cachedState ?: getState().also { cachedState = it }

    override fun addListener(listener: Player.Listener) { listeners.add(listener) }
    override fun removeListener(listener: Player.Listener) { listeners.remove(listener) }

    override fun setMediaItem(mediaItem: MediaItem) = setMediaItems(listOf(mediaItem), 0, C.TIME_UNSET)
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = setMediaItems(listOf(mediaItem), 0, startPositionMs)
    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        handleSetMediaItems(mediaItems, startIndex, startPositionMs)
        invalidateState()
    }
    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) =
        setMediaItems(mediaItems, 0, C.TIME_UNSET)
    override fun addMediaItem(mediaItem: MediaItem) {}
    override fun addMediaItems(mediaItems: List<MediaItem>) {}
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {}
    override fun clearMediaItems() { setMediaItems(emptyList(), 0, C.TIME_UNSET) }

    override val mediaItemCount: Int get() = s().playlist.size
    override val currentMediaItemIndex: Int get() = s().currentMediaItemIndex
    override val currentMediaItem: MediaItem? get() = s().playlist.getOrNull(currentMediaItemIndex)?.mediaItem
    override fun getMediaItemAt(index: Int): MediaItem = s().playlist[index].mediaItem

    override fun prepare() { handlePrepare(); invalidateState() }
    override fun play() { playWhenReady = true }
    override fun pause() { playWhenReady = false }
    override fun stop() { handleStop(); invalidateState() }
    override fun release() { handleRelease(); listeners.clear() }

    override var playWhenReady: Boolean
        get() = s().playWhenReady
        set(value) { handleSetPlayWhenReady(value); invalidateState() }

    override val playbackState: Int get() = s().playbackState
    override val isPlaying: Boolean get() = playWhenReady && playbackState == Player.STATE_READY
    override val playerError: PlaybackException? get() = s().playerError

    override val duration: Long
        get() {
            val dUs = s().playlist.getOrNull(currentMediaItemIndex)?.durationUs ?: C.TIME_UNSET
            return if (dUs == C.TIME_UNSET) C.TIME_UNSET else dUs / 1000L
        }
    override val currentPosition: Long get() = s().contentPositionSupplier.get()
    override val bufferedPosition: Long get() = currentPosition
    override val currentLiveOffset: Long get() = 0L
    override val isCurrentMediaItemLive: Boolean get() = false
    override val isCurrentMediaItemSeekable: Boolean
        get() = s().playlist.getOrNull(currentMediaItemIndex)?.isSeekable ?: true

    override fun seekTo(positionMs: Long) { handleSeek(currentMediaItemIndex, positionMs, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM); invalidateState() }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) { handleSeek(mediaItemIndex, positionMs, Player.COMMAND_SEEK_TO_MEDIA_ITEM); invalidateState() }
    override fun seekToDefaultPosition() { handleSeek(currentMediaItemIndex, 0L, Player.COMMAND_SEEK_TO_DEFAULT_POSITION); invalidateState() }
    override fun seekToDefaultPosition(mediaItemIndex: Int) { handleSeek(mediaItemIndex, 0L, Player.COMMAND_SEEK_TO_MEDIA_ITEM); invalidateState() }
    override fun seekToNext() { handleSeek(currentMediaItemIndex, C.TIME_UNSET, Player.COMMAND_SEEK_TO_NEXT); invalidateState() }
    override fun seekToPrevious() { handleSeek(currentMediaItemIndex, C.TIME_UNSET, Player.COMMAND_SEEK_TO_PREVIOUS); invalidateState() }
    override fun seekToNextMediaItem() { handleSeek(currentMediaItemIndex + 1, C.TIME_UNSET, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM); invalidateState() }
    override fun seekToPreviousMediaItem() { handleSeek(currentMediaItemIndex - 1, C.TIME_UNSET, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM); invalidateState() }
    override fun hasNextMediaItem(): Boolean = currentMediaItemIndex + 1 < mediaItemCount
    override fun hasPreviousMediaItem(): Boolean = currentMediaItemIndex > 0

    override var repeatMode: Int = Player.REPEAT_MODE_OFF
    override var shuffleModeEnabled: Boolean = false

    override var playbackParameters: PlaybackParameters
        get() = s().playbackParameters
        set(value) { handleSetPlaybackParameters(value); invalidateState() }

    override var volume: Float
        get() = s().volume
        set(value) { handleSetVolume(value, 0); invalidateState() }

    override var trackSelectionParameters: TrackSelectionParameters
        get() = s().trackSelectionParameters
        set(value) { handleSetTrackSelectionParameters(value); invalidateState() }

    override val currentTracks: Tracks
        get() = s().playlist.getOrNull(currentMediaItemIndex)?.tracks ?: Tracks.EMPTY

    override val videoSize: VideoSize get() = s().videoSize
    override val currentTimeline: Timeline get() = Timeline.EMPTY
    override val currentManifest: Any? get() = null

    override fun getMediaMetadata(): MediaMetadata =
        s().playlist.getOrNull(currentMediaItemIndex)?.mediaMetadata ?: MediaMetadata.EMPTY

    override fun setVideoSurfaceView(surfaceView: android.view.SurfaceView?) {
        if (surfaceView != null) handleSetVideoOutput(surfaceView) else handleClearVideoOutput(null)
    }

    override fun clearVideoSurfaceView(surfaceView: android.view.SurfaceView?) {
        handleClearVideoOutput(surfaceView)
    }

    override fun setVideoTextureView(textureView: android.view.TextureView?) {
        if (textureView != null) handleSetVideoOutput(textureView) else handleClearVideoOutput(null)
    }

    override fun clearVideoTextureView(textureView: android.view.TextureView?) {
        handleClearVideoOutput(textureView)
    }
}
