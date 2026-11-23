package com.aisub.player.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import androidx.annotation.Keep
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer

/**
 * libVLC 原生音频分流与双路径输出桥（JNI 对接 `libvlc_audio_bridge.so`）：
 * 1. 新设备（Android 8.1+，API >= 27，`dlopen("libaaudio.so")` 成功）：在 C++ 层直接通过 AAudio 低延迟模式输出声音，绕过 Java AudioTrack；
 * 2. 老设备（Android 6.0 32 位电视等，`dlopen` 返回空或流打开失败）：自动回退到本类的 Java [AudioTrack] 播放，
 *    配合 `startVlcKernel` 中的 `--audio-desync=-160` 抵消环形缓冲延迟，嘴型对齐；
 * 3. 开启 AI 字幕时，C++ 层同时将解码后的原始音频混音并重采样为 16kHz 单声道 float PCM 分批送入识别引擎，
 *    无需另开第二路网络连接，单连接直播源也能实时生成字幕。
 */
@Keep
class VlcAudioBridge {

    private var nativeHandle: Long = 0L
    private val trackLock = Any()
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var asrConsumer: ((FloatArray) -> Unit)? = null

    @Volatile
    private var discontinuityListener: (() -> Unit)? = null

    val isAttached: Boolean
        get() = nativeHandle != 0L

    /** 绑定到指定的 [LibVLC] 与 [MediaPlayer] 实例，接管原生音频回调。 */
    fun attach(libVlc: LibVLC, mediaPlayer: MediaPlayer): Boolean {
        if (!ensureLibraryLoaded()) return false
        if (nativeHandle != 0L) detach()
        // 固定 Java 层 MediaPlayer 音频输出模块为 amem，防止 stop()/play() 重置回 android_audiotrack 覆盖原生回调
        runCatching { mediaPlayer.setAudioOutput("amem") }
        nativeHandle = runCatching { nativeAttach(libVlc, mediaPlayer) }.getOrDefault(0L)
        if (nativeHandle == 0L) {
            Log.w(TAG, "绑定 VLC 原生音频回调失败，回退为 libVLC 默认音频输出")
            return false
        }
        nativeSetAsrEnabled(nativeHandle, asrConsumer != null)
        return true
    }

    /** 每次切换媒体调用 `mp.play()` 前重申一次回调绑定，确保换台后回调依然生效。 */
    fun rebindCallbacks(mediaPlayer: MediaPlayer) {
        val h = nativeHandle
        if (h == 0L) return
        runCatching { mediaPlayer.setAudioOutput("amem") }
        nativeRebindCallbacks(h)
    }

    /** 注册或清除 16kHz 单声道语音识别音频消费者（传 null 时关闭 C++ 层重采样分流，零额外算力开销）。 */
    fun setAsrConsumer(
        onChunk: ((FloatArray) -> Unit)?,
        onDiscontinuity: (() -> Unit)? = null,
    ) {
        asrConsumer = onChunk
        discontinuityListener = onDiscontinuity
        val h = nativeHandle
        if (h != 0L) {
            nativeSetAsrEnabled(h, onChunk != null)
        }
    }

    /** 调整扬声器输出音量（0.0f..1.0f，例如 TTS 朗读译文时压低原声），不影响送给识别引擎的音频幅度。 */
    fun setVolume(volume: Float) {
        val h = nativeHandle
        if (h != 0L) {
            nativeSetVolume(h, volume)
        }
    }

    /** 当前实际生效的音频输出后端说明（供视频信息面板展示与排查）。 */
    fun activeBackendLabel(): String {
        val h = nativeHandle
        if (h == 0L) return "libVLC 默认"
        return when (nativeActiveBackend(h)) {
            1 -> "AAudio C++ 32-bit 浮点直出"
            2 -> "AudioTrack（-180ms 唇形同步补偿）"
            else -> if (isAAudioSupported()) "AAudio 待命" else "AudioTrack 待命（-180ms 补偿）"
        }
    }

    /** 释放原生桥接上下文与 Java AudioTrack。 */
    fun detach() {
        asrConsumer = null
        discontinuityListener = null
        val h = nativeHandle
        nativeHandle = 0L
        if (h != 0L) {
            runCatching { nativeDetach(h) }
        }
        onNativeAudioCleanup()
    }

    // ---------------- 以下方法由 C++ JNI 线程直接回调（方法名与签名请勿改动） ----------------

    @Keep
    private fun onNativeAudioSetup(sampleRate: Int, channels: Int): Boolean = synchronized(trackLock) {
        releaseTrackLocked()
        val channelConfig = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.e(TAG, "AudioTrack.getMinBufferSize 无效: rate=$sampleRate, ch=$channels, res=$minBuf")
            return false
        }
        val bytesPerFrame = (if (channels >= 2) 2 else 1) * 2
        // 目标环形缓冲约 220ms，吸收高码率 4K 直播解码抖动，配合 --audio-desync=-180 保持嘴型对齐
        val targetBytes = (sampleRate * bytesPerFrame * 220) / 1000
        val maxBytes = maxOf(minBuf * 6, (sampleRate * bytesPerFrame * 3) / 4)
        val bufferSize = maxOf(minBuf * 2, targetBytes).coerceAtMost(maxBytes)

        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            val track = AudioTrack(
                attrs,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack 初始化未就绪: state=${track.state}")
                track.release()
                return false
            }
            track.play()
            audioTrack = track
            true
        } catch (t: Throwable) {
            Log.e(TAG, "创建 AudioTrack 失败", t)
            false
        }
    }

    @Keep
    private fun onNativeAudioWrite(pcm: ShortArray, count: Int) {
        val track = synchronized(trackLock) { audioTrack } ?: return
        var offset = 0
        val limit = count.coerceAtMost(pcm.size)
        while (offset < limit) {
            val written = try {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
                track.write(pcm, offset, limit - offset)
            } catch (_: Throwable) {
                break
            }
            if (written <= 0) break
            offset += written
        }
    }

    @Keep
    private fun onNativeAudioPause() {
        synchronized(trackLock) {
            val track = audioTrack ?: return
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
            }
        }
    }

    @Keep
    private fun onNativeAudioResume() {
        synchronized(trackLock) {
            val track = audioTrack ?: return
            runCatching {
                if (track.state == AudioTrack.STATE_INITIALIZED && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
            }
        }
    }

    @Keep
    private fun onNativeAudioFlush() {
        synchronized(trackLock) {
            val track = audioTrack ?: return
            runCatching {
                val wasPlaying = track.playState == AudioTrack.PLAYSTATE_PLAYING
                if (wasPlaying) track.pause()
                track.flush()
                if (wasPlaying) track.play()
            }
        }
    }

    @Keep
    private fun onNativeAudioCleanup() {
        synchronized(trackLock) {
            releaseTrackLocked()
        }
    }

    @Keep
    private fun onNativeAsrPcm(samples: FloatArray) {
        asrConsumer?.invoke(samples)
    }

    @Keep
    private fun onNativeDiscontinuity() {
        discontinuityListener?.invoke()
    }

    private fun releaseTrackLocked() {
        val track = audioTrack ?: return
        audioTrack = null
        runCatching {
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.pause()
                track.flush()
                track.stop()
            }
        }
        runCatching { track.release() }
    }

    private external fun nativeAttach(libVlc: LibVLC, mediaPlayer: MediaPlayer): Long
    private external fun nativeRebindCallbacks(handle: Long)
    private external fun nativeSetAsrEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetVolume(handle: Long, volume: Float)
    private external fun nativeActiveBackend(handle: Long): Int
    private external fun nativeDetach(handle: Long)

    companion object {
        private const val TAG = "Voxtitle-VlcBridge"

        @Volatile
        private var loadAttempted = false

        @Volatile
        private var libraryLoaded = false

        fun ensureLibraryLoaded(): Boolean {
            if (loadAttempted) return libraryLoaded
            synchronized(this) {
                if (loadAttempted) return libraryLoaded
                libraryLoaded = runCatching {
                    System.loadLibrary("vlc_audio_bridge")
                    true
                }.onFailure {
                    Log.e(TAG, "加载 libvlc_audio_bridge.so 失败", it)
                }.getOrDefault(false)
                loadAttempted = true
                return libraryLoaded
            }
        }

        /** 运行时探测当前设备是否具备 Android 8.1+ `libaaudio.so` C++ 直出能力。 */
        fun isAAudioSupported(): Boolean {
            if (!ensureLibraryLoaded()) return false
            return runCatching { nativeIsAAudioSupported() }.getOrDefault(false)
        }

        @JvmStatic
        private external fun nativeIsAAudioSupported(): Boolean
    }
}
