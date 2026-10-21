package com.aisub.player.asr

import com.aisub.player.subtitle.Cue
import java.io.Closeable

/**
 * 识别引擎：持续接收 16kHz 单声道音频，识别出字幕就回调 onCue。
 * accept/finish 都是阻塞调用，需要在后台线程执行。
 */
interface SpeechEngine : Closeable {
    fun accept(samples: FloatArray, onCue: (Cue) -> Unit)

    /** 音频结束，处理剩余部分。 */
    fun finish(onCue: (Cue) -> Unit)

    /** 从其他线程中断正在进行的阻塞操作（例如网络请求）。 */
    fun cancel() {}
}
