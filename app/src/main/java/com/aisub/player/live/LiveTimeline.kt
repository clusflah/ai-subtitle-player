package com.aisub.player.live

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist

/**
 * 直播字幕的统一时间轴。
 *
 * 识别用的音频由另一个不出声的播放器在直播最新处解码（见 [com.aisub.player.asr.ExoAudioDecoder]），
 * 和屏幕上的播放器不是同一条时间线。两边都能换算成同一个"直播内容位置" [LiveKey]：
 * - 直播流带绝对时间（HLS 的 EXT-X-PROGRAM-DATE-TIME、DASH）：用内容的 UTC 时间；
 * - 普通 HLS：用分片序号 + 分片内偏移（两个播放器拿到的是同一份播放列表，序号一致）。
 *
 * 字幕的开始、结束时间都换算成屏幕播放器的"周期内位置"（[periodPosition]），显示时和画面用同一个时钟比较，
 * 不再按字幕到达的时刻排队。
 */
data class LiveKey(
    /** HLS 分片序号；[UTC] 表示 ms 是内容的 UTC 时间 */
    val seq: Long,
    val ms: Long,
) {
    operator fun plus(deltaMs: Long) = copy(ms = ms + deltaMs)

    companion object {
        const val UTC = -1L
    }
}

/** 直播字幕的时钟。 */
sealed interface LiveClock {
    /** 字幕时间 = 屏幕播放器的周期内位置，和画面严格对齐 */
    data object Stream : LiveClock

    /** 换算不了时间（普通 HTTP 流、VLC 内核）：字幕时间 = 开启后经过的时间，按到达先后显示 */
    data class Arrival(val baseMs: Long) : LiveClock
}

@UnstableApi
object LiveTimeline {

    private fun window(player: Player): Timeline.Window? {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        val index = player.currentMediaItemIndex
        if (index !in 0 until timeline.windowCount) return null
        return timeline.getWindow(index, Timeline.Window())
    }

    /**
     * 播放器当前位置在直播周期里的位置（毫秒）。直播窗口会往前滑动，[Player.getCurrentPosition] 是相对窗口的，
     * 会跟着跳；加上窗口在周期里的起点后才是一直往前走的时间。
     */
    fun periodPosition(player: Player, positionMs: Long = player.currentPosition): Long =
        (window(player)?.positionInFirstPeriodUs ?: 0L) / 1000 + positionMs

    /** 播放器当前位置对应的直播内容位置；看不出来（不是 HLS，也没有绝对时间）时返回 null。 */
    fun keyOf(player: Player): LiveKey? {
        val window = window(player) ?: return null
        return keyOf(window, hlsPlaylist(player), player.currentPosition)
    }

    /** 直播内容位置在这个播放器上的周期内位置；换算不了时返回 null。 */
    fun positionOf(key: LiveKey, player: Player): Long? {
        val window = window(player) ?: return null
        return positionOf(key, window, hlsPlaylist(player))
    }

    private fun hlsPlaylist(player: Player) = (player.currentManifest as? HlsManifest)?.mediaPlaylist

    internal fun keyOf(window: Timeline.Window, playlist: HlsMediaPlaylist?, positionMs: Long): LiveKey? {
        if (window.windowStartTimeMs != C.TIME_UNSET) return LiveKey(LiveKey.UTC, window.windowStartTimeMs + positionMs)
        val segments = playlist?.segments
        if (segments.isNullOrEmpty()) return null
        // 窗口的 0 点就是播放列表第一个分片的开头
        val posUs = positionMs * 1000
        var i = segments.indexOfLast { it.relativeStartTimeUs <= posUs }
        if (i < 0) i = 0
        return LiveKey(playlist.mediaSequence + i, positionMs - segments[i].relativeStartTimeUs / 1000)
    }

    internal fun positionOf(key: LiveKey, window: Timeline.Window, playlist: HlsMediaPlaylist?): Long? {
        val windowInPeriod = window.positionInFirstPeriodUs / 1000
        if (key.seq == LiveKey.UTC) {
            if (window.windowStartTimeMs == C.TIME_UNSET) return null
            return windowInPeriod + key.ms - window.windowStartTimeMs
        }
        val segments = playlist?.segments
        if (segments.isNullOrEmpty() || window.windowStartTimeMs != C.TIME_UNSET) return null
        val i = key.seq - playlist.mediaSequence
        val target = playlist.targetDurationUs
        val startUs = when {
            // 这个分片已经滑出了窗口，或者还没出现在这边的播放列表里：按目标分片时长往前、往后推
            i < 0 -> segments.first().relativeStartTimeUs + i * target
            i < segments.size -> segments[i.toInt()].relativeStartTimeUs
            else -> segments.last().let { it.relativeStartTimeUs + it.durationUs } + (i - segments.size) * target
        }
        return windowInPeriod + startUs / 1000 + key.ms
    }
}

/**
 * 解码出的音频（按样本计的毫秒）和直播内容位置的对应关系。解码线程定期记录一个锚点，
 * 识别出的字幕按自己在音频里的时间找最近的锚点换算。重连后音频接着往后数，锚点另起一段。
 */
class LiveAnchors {
    private data class Anchor(val audioMs: Long, val key: LiveKey)

    private val anchors = ArrayList<Anchor>()
    private var segmentStartMs = 0L

    /** 重新连接直播：之后的音频和之前的不连续 */
    @Synchronized
    fun newSegment(audioMs: Long) {
        segmentStartMs = audioMs
    }

    @Synchronized
    fun add(audioMs: Long, key: LiveKey) {
        anchors.add(Anchor(audioMs, key))
        // 只留最近一段时间的锚点就够了
        if (anchors.size > 400) anchors.subList(0, 100).clear()
    }

    /**
     * 音频里某个时间对应的直播内容位置。同一段连续音频里内容是线性往前走的，取离得最近的锚点推算；
     * 这段音频还没有锚点时返回 null（稍后再换算）。
     */
    @Synchronized
    fun keyAt(audioMs: Long): LiveKey? {
        val sameSegment = if (audioMs >= segmentStartMs) anchors.filter { it.audioMs >= segmentStartMs }
        else anchors.filter { it.audioMs < segmentStartMs }
        val nearest = sameSegment.minByOrNull { kotlin.math.abs(it.audioMs - audioMs) } ?: return null
        return nearest.key + (audioMs - nearest.audioMs)
    }
}
