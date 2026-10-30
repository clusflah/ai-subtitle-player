package com.aisub.player.live

/**
 * 直播实时字幕"为什么还没有字"的判断：记下什么时候开始、什么时候收到声音、什么时候识别出句子，
 * 隔一会儿看一次，给出画面上的提示；一切正常时返回 null。时间都是 SystemClock.elapsedRealtime。
 *
 * @param language 当前的视频语言（显示用，如「韩语」）；自动时为 null
 * @param streaming 正在用流式识别
 * @param onDevice 在手机上识别（本地模型）
 */
class LiveWatchdog(
    private val startedAt: Long,
    private val language: String?,
    private val streaming: Boolean,
    private val onDevice: Boolean,
) {
    private var firstAudio = -1L
    private var lastAudio = -1L
    private var lastCue = -1L

    fun audio(now: Long) {
        if (firstAudio < 0) firstAudio = now
        lastAudio = now
    }

    fun cue(now: Long) {
        lastCue = now
    }

    fun hint(now: Long): String? = when {
        firstAudio < 0 && now - startedAt > NO_AUDIO_MS ->
            "${(now - startedAt) / 1000} 秒还没收到直播的声音。直播源可能不允许同时连两路，或者网络太慢；可以换个频道，或者关掉实时字幕再打开试试"
        firstAudio >= 0 && now - lastAudio > AUDIO_GAP_MS -> "直播的声音断了，正在重连…"
        lastCue < 0 && firstAudio >= 0 && now - firstAudio > NO_CUE_MS -> buildString {
            append("已经听了 ${(now - firstAudio) / 1000} 秒，还没识别出一句话。可能这段没人说话（音乐、广告、比赛画面）")
            if (language != null) append("；或者「视频语言」选错了（现在是$language）")
            if (streaming) append("；流式识别要先在控制台开通实时语音识别")
            if (onDevice) append("；手机上识别比较慢，第一句会更久")
        }
        lastCue >= 0 && now - lastCue > QUIET_MS -> "最近 ${(now - lastCue) / 1000} 秒没有识别出字幕，可能这段没人说话"
        else -> null
    }

    companion object {
        const val NO_AUDIO_MS = 20_000L
        const val AUDIO_GAP_MS = 15_000L
        const val NO_CUE_MS = 45_000L
        const val QUIET_MS = 90_000L
    }
}
