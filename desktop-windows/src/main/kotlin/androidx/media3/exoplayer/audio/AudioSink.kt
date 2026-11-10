package androidx.media3.exoplayer.audio

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import java.nio.ByteBuffer

interface AudioSink {
    interface Listener

    fun setListener(listener: Listener)
    fun supportsFormat(format: Format): Boolean
    fun getFormatSupport(format: Format): Int
    fun getCurrentPositionUs(sourceEnded: Boolean): Long
    fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?)
    fun play()
    fun handleDiscontinuity()
    fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean
    fun playToEndOfStream()
    fun isEnded(): Boolean
    fun hasPendingData(): Boolean
    fun setPlaybackParameters(playbackParameters: PlaybackParameters)
    fun getPlaybackParameters(): PlaybackParameters
    fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean)
    fun getSkipSilenceEnabled(): Boolean
    fun setAudioAttributes(audioAttributes: AudioAttributes)
    fun getAudioAttributes(): AudioAttributes
    fun setAudioSessionId(audioSessionId: Int)
    fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo)
    fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET
    fun enableTunnelingV21() {}
    fun disableTunneling() {}
    fun setVolume(volume: Float)
    fun pause()
    fun flush()
    fun reset()

    companion object {
        const val SINK_FORMAT_SUPPORTED_DIRECTLY = 2
        const val SINK_FORMAT_SUPPORTED_WITH_TRANSCODING = 1
        const val SINK_FORMAT_UNSUPPORTED = 0
        const val CURRENT_POSITION_NOT_SET = Long.MIN_VALUE
    }
}
