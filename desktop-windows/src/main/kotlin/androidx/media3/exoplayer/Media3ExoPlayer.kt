package androidx.media3.exoplayer

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.aisub.player.desktop.DesktopExoPlayer

class DecoderReuseEvaluation

class SeekParameters(val toleranceBeforeUs: Long, val toleranceAfterUs: Long) {
    companion object {
        val EXACT = SeekParameters(0L, 0L)
        val CLOSEST_SYNC = SeekParameters(Long.MAX_VALUE, Long.MAX_VALUE)
        val PREVIOUS_SYNC = SeekParameters(Long.MAX_VALUE, 0L)
        val NEXT_SYNC = SeekParameters(0L, Long.MAX_VALUE)
        val DEFAULT = EXACT
    }
}

open class DefaultRenderersFactory(private val context: Context) {
    fun setExtensionRendererMode(mode: Int): DefaultRenderersFactory = this
    fun setEnableDecoderFallback(enable: Boolean): DefaultRenderersFactory = this
    protected open fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? = null

    companion object {
        const val EXTENSION_RENDERER_MODE_OFF = 0
        const val EXTENSION_RENDERER_MODE_ON = 1
        const val EXTENSION_RENDERER_MODE_PREFER = 2
    }
}

interface ExoPlayer : Player {
    val audioSessionId: Int
    val videoFormat: Format?
    val audioFormat: Format?
    val isLoading: Boolean
    val totalBufferedDuration: Long

    fun setSeekParameters(seekParameters: SeekParameters?)
    fun addAnalyticsListener(listener: AnalyticsListener)

    class Builder(
        private val context: Context,
        private var renderersFactory: DefaultRenderersFactory = DefaultRenderersFactory(context),
    ) {
        private var mediaSourceFactory: DefaultMediaSourceFactory? = null

        fun setRenderersFactory(factory: DefaultRenderersFactory): Builder = apply { renderersFactory = factory }
        fun setMediaSourceFactory(factory: DefaultMediaSourceFactory): Builder = apply { mediaSourceFactory = factory }
        fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean): Builder = this
        fun setHandleAudioBecomingNoisy(handleAudioBecomingNoisy: Boolean): Builder = this
        fun setWakeMode(wakeMode: Int): Builder = this
        fun setLooper(looper: Looper): Builder = this

        fun build(): ExoPlayer = DesktopExoPlayer(context, mediaSourceFactory?.dataSourceFactory)
    }
}
