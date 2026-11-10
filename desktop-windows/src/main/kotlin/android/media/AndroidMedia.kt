package android.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjection
import android.net.Uri
import android.view.Surface
import com.aisub.player.desktop.DesktopBinaryFinder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.InputStream
import java.nio.ByteBuffer
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

class AudioManager {
    private var currentVolume = 12

    fun getStreamMaxVolume(streamType: Int): Int = 15
    fun getStreamVolume(streamType: Int): Int = currentVolume

    fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
        currentVolume = index.coerceIn(0, 15)
        com.aisub.player.desktop.DesktopPlayerEngine.setMasterVolume(currentVolume / 15f)
    }

    fun adjustStreamVolume(streamType: Int, direction: Int, flags: Int) {}

    companion object {
        const val STREAM_MUSIC = 3
        const val ADJUST_SAME = 0
        const val FLAG_SHOW_UI = 1
    }
}

class PlaybackParams {
    var speed: Float = 1.0f
        private set

    fun setSpeed(speed: Float): PlaybackParams = apply {
        this.speed = speed.coerceIn(0.25f, 3.0f)
    }
}

class MediaPlayer {
    fun interface OnCompletionListener {
        fun onCompletion(mp: MediaPlayer)
    }

    fun interface OnErrorListener {
        fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean
    }

    private var sourcePath: String? = null
    private var clip: Clip? = null
    private var completionListener: OnCompletionListener? = null
    private var errorListener: OnErrorListener? = null
    var playbackParams: PlaybackParams = PlaybackParams()
    var duration: Int = 1000
        private set

    val isPlaying: Boolean
        get() = clip?.isRunning == true

    fun setDataSource(path: String) {
        sourcePath = path
    }

    fun prepare() {
        val path = sourcePath ?: return
        val file = File(path)
        if (!file.exists()) return
        val wavFile = if (file.extension.equals("wav", ignoreCase = true)) {
            file
        } else {
            convertToWav(file) ?: file
        }
        runCatching {
            AudioSystem.getAudioInputStream(wavFile).use { ais ->
                val c = AudioSystem.getClip()
                c.open(ais)
                duration = (c.microsecondLength / 1000L).toInt().coerceAtLeast(100)
                c.addLineListener { ev ->
                    if (ev.type == LineEvent.Type.STOP && c.microsecondPosition >= c.microsecondLength - 20_000L) {
                        completionListener?.onCompletion(this)
                    }
                }
                clip = c
            }
        }
    }

    fun start() {
        val c = clip
        if (c != null) {
            c.start()
        } else {
            completionListener?.onCompletion(this)
        }
    }

    fun pause() {
        clip?.stop()
    }

    fun stop() {
        clip?.stop()
    }

    fun release() {
        runCatching { clip?.close() }
        clip = null
    }

    fun setOnCompletionListener(listener: OnCompletionListener?) {
        completionListener = listener
    }

    fun setOnErrorListener(listener: OnErrorListener?) {
        errorListener = listener
    }

    private fun convertToWav(input: File): File? {
        val ffmpeg = DesktopBinaryFinder.findFfmpeg() ?: return null
        val out = File(input.parentFile, "${input.nameWithoutExtension}_pcm.wav")
        val ok = runCatching {
            val proc = ProcessBuilder(
                ffmpeg, "-y", "-v", "error",
                "-i", input.absolutePath,
                "-ac", "1", "-ar", "24000",
                out.absolutePath,
            ).redirectErrorStream(true).start()
            proc.inputStream.readBytes()
            proc.waitFor() == 0 && out.exists()
        }.getOrDefault(false)
        return if (ok) out else null
    }
}

class MediaMetadataRetriever : AutoCloseable {
    private var path: String? = null
    private var headers: Map<String, String> = emptyMap()

    fun setDataSource(path: String?) {
        this.path = path
    }

    fun setDataSource(uri: String, headers: Map<String, String>) {
        this.path = uri
        this.headers = headers
    }

    fun setDataSource(context: Context, uri: Uri) {
        this.path = uri.path ?: uri.toString()
    }

    fun setDataSource(fd: FileDescriptor) {}

    fun extractMetadata(keyCode: Int): String? {
        val p = path ?: return null
        val ffprobe = DesktopBinaryFinder.findFfprobe() ?: return null
        return runCatching {
            when (keyCode) {
                METADATA_KEY_DURATION -> {
                    val proc = ProcessBuilder(
                        ffprobe, "-v", "error",
                        "-show_entries", "format=duration",
                        "-of", "default=noprint_wrappers=1:nokey=1",
                        p,
                    ).redirectErrorStream(true).start()
                    val text = proc.inputStream.bufferedReader().readText().trim()
                    proc.waitFor()
                    val sec = text.toDoubleOrNull() ?: return null
                    (sec * 1000.0).toLong().toString()
                }
                else -> null
            }
        }.getOrNull()
    }

    val embeddedPicture: ByteArray?
        get() {
            val p = path ?: return null
            val ffmpeg = DesktopBinaryFinder.findFfmpeg() ?: return null
            return runCatching {
                val proc = ProcessBuilder(
                    ffmpeg, "-v", "error",
                    "-i", p,
                    "-an", "-vcodec", "mjpeg", "-vframes", "1",
                    "-f", "image2pipe", "-",
                ).start()
                val bytes = proc.inputStream.readBytes()
                proc.waitFor()
                bytes.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }

    fun getFrameAtTime(timeUs: Long, option: Int): Bitmap? {
        return getScaledFrameAtTime(timeUs, option, 320, 180)
    }

    fun getScaledFrameAtTime(timeUs: Long, option: Int, dstWidth: Int, dstHeight: Int): Bitmap? {
        val p = path ?: return null
        val ffmpeg = DesktopBinaryFinder.findFfmpeg() ?: return null
        val sec = (timeUs / 1_000_000.0).coerceAtLeast(0.0)
        return runCatching {
            val cmd = mutableListOf(
                ffmpeg, "-v", "error",
                "-ss", "%.3f".format(java.util.Locale.US, sec),
            )
            if (headers.isNotEmpty() && (p.startsWith("http://") || p.startsWith("https://"))) {
                val hdrStr = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
                cmd.addAll(listOf("-headers", hdrStr))
            }
            cmd.addAll(
                listOf(
                    "-i", p,
                    "-vframes", "1",
                    "-vf", "scale=${dstWidth.coerceAtLeast(64)}:-1",
                    "-f", "image2pipe", "-vcodec", "png", "-",
                ),
            )
            val proc = ProcessBuilder(cmd).start()
            val bytes = proc.inputStream.readBytes()
            proc.waitFor()
            if (bytes.isNotEmpty()) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
        }.getOrNull()
    }

    fun release() {}
    override fun close() = release()

    companion object {
        const val OPTION_PREVIOUS_SYNC = 0
        const val OPTION_NEXT_SYNC = 1
        const val OPTION_CLOSEST_SYNC = 2
        const val OPTION_CLOSEST = 3

        const val METADATA_KEY_ALBUM = 1
        const val METADATA_KEY_ARTIST = 2
        const val METADATA_KEY_TITLE = 7
        const val METADATA_KEY_DURATION = 9
        const val METADATA_KEY_ALBUMARTIST = 13
    }
}

class ExifInterface(inputStream: InputStream) {
    fun getAttributeInt(tag: String, defaultValue: Int): Int = defaultValue

    companion object {
        const val TAG_ORIENTATION = "Orientation"
        const val ORIENTATION_NORMAL = 1
        const val ORIENTATION_ROTATE_90 = 6
        const val ORIENTATION_ROTATE_180 = 3
        const val ORIENTATION_ROTATE_270 = 8
    }
}

class AudioFormat {
    class Builder {
        fun setEncoding(encoding: Int): Builder = this
        fun setSampleRate(sampleRate: Int): Builder = this
        fun setChannelMask(channelMask: Int): Builder = this
        fun build(): AudioFormat = AudioFormat()
    }

    companion object {
        const val ENCODING_PCM_16BIT = 2
        const val ENCODING_PCM_FLOAT = 4
        const val CHANNEL_IN_MONO = 16
        const val CHANNEL_OUT_MONO = 4
        const val CHANNEL_OUT_STEREO = 12
    }
}

class AudioPlaybackCaptureConfiguration private constructor() {
    class Builder(projection: MediaProjection) {
        fun addMatchingUid(uid: Int): Builder = this
        fun excludeUid(uid: Int): Builder = this
        fun build(): AudioPlaybackCaptureConfiguration = AudioPlaybackCaptureConfiguration()
    }
}

class AudioRecord {
    val state: Int = STATE_INITIALIZED

    fun startRecording() {}
    fun stop() {}
    fun release() {}
    fun read(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int): Int = -1

    class Builder {
        fun setAudioFormat(format: AudioFormat): Builder = this
        fun setBufferSizeInBytes(bufferSizeInBytes: Int): Builder = this
        fun setAudioPlaybackCaptureConfig(config: AudioPlaybackCaptureConfiguration): Builder = this
        fun build(): AudioRecord = AudioRecord()
    }

    companion object {
        const val STATE_INITIALIZED = 1
        @JvmStatic
        fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int = 4096
    }
}

class MediaFormat {
    private val map = HashMap<String, Any>()
    fun getString(name: String): String? = map[name] as? String
    fun getInteger(name: String): Int = (map[name] as? Number)?.toInt() ?: 0
    fun getLong(name: String): Long = (map[name] as? Number)?.toLong() ?: 0L
    fun containsKey(name: String): Boolean = map.containsKey(name)

    companion object {
        const val KEY_MIME = "mime"
        const val KEY_SAMPLE_RATE = "sample-rate"
        const val KEY_CHANNEL_COUNT = "channel-count"
        const val KEY_DURATION = "durationUs"
        const val KEY_PCM_ENCODING = "pcm-encoding"
    }
}

class MediaExtractor {
    val trackCount: Int = 0
    val sampleTime: Long = -1L
    fun setDataSource(path: String, headers: Map<String, String>?) {}
    fun setDataSource(context: Context, uri: Uri, headers: Map<String, String>?) {}
    fun getTrackFormat(index: Int): MediaFormat = MediaFormat()
    fun selectTrack(index: Int) {}
    fun seekTo(timeUs: Long, mode: Int) {}
    fun readSampleData(byteBuf: ByteBuffer, offset: Int): Int = -1
    fun advance(): Boolean = false
    fun release() {}

    companion object {
        const val SEEK_TO_PREVIOUS_SYNC = 0
    }
}

class MediaCodecList(kind: Int) {
    fun findDecoderForFormat(format: MediaFormat): String? = null
    companion object {
        const val REGULAR_CODECS = 0
    }
}

class MediaCodec {
    class BufferInfo {
        var offset: Int = 0
        var size: Int = 0
        var presentationTimeUs: Long = 0L
        var flags: Int = 0
    }

    val outputFormat: MediaFormat = MediaFormat()
    fun configure(format: MediaFormat?, surface: Surface?, crypto: Any?, flags: Int) {}
    fun start() {}
    fun stop() {}
    fun release() {}
    fun dequeueInputBuffer(timeoutUs: Long): Int = -1
    fun getInputBuffer(index: Int): ByteBuffer? = null
    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {}
    fun dequeueOutputBuffer(info: BufferInfo, timeoutUs: Long): Int = -1
    fun getOutputBuffer(index: Int): ByteBuffer? = null
    fun releaseOutputBuffer(index: Int, render: Boolean) {}

    companion object {
        const val BUFFER_FLAG_END_OF_STREAM = 4
        const val INFO_OUTPUT_FORMAT_CHANGED = -2
        @JvmStatic
        fun createDecoderByType(type: String): MediaCodec = MediaCodec()
        @JvmStatic
        fun createByCodecName(name: String): MediaCodec = MediaCodec()
    }
}

class MediaScannerConnection {
    fun interface OnScanCompletedListener {
        fun onScanCompleted(path: String, uri: Uri?)
    }

    companion object {
        @JvmStatic
        fun scanFile(context: Context, paths: Array<String>, mimeTypes: Array<String>?, callback: OnScanCompletedListener?) {
            paths.forEach { path ->
                callback?.onScanCompleted(path, Uri.fromFile(java.io.File(path)))
            }
        }
    }
}
