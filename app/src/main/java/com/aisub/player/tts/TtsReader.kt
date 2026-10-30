package com.aisub.player.tts

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.media3.common.Player
import com.aisub.player.subtitle.Cue
import com.aisub.player.subtitle.Role
import com.aisub.player.subtitle.cueAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** 朗读需要的当前状态，每次轮询时从界面状态里取。 */
data class TtsSnapshot(
    val cues: List<Cue>,
    val roles: List<Role>,
    val delayMs: Long,
    val engine: TtsEngineType,
    val defaultVoice: String?,
    val locale: String?,
    /** 朗读时原声的音量（0..1） */
    val duckVolume: Float,
)

/**
 * 跟随字幕时间朗读译文：每句字幕开始时播放它的语音，提前在后台合成后面几句。
 * 语音比字幕时长长时加快语速（最多 [MAX_SPEED] 倍），下一句开始时还没读完就切到下一句；
 * 视频暂停、拖动进度时同步暂停、停止。朗读期间把原声调小。
 */
class TtsReader(
    context: Context,
    private val scope: CoroutineScope,
    private val player: () -> Player,
    private val snapshot: () -> TtsSnapshot,
    private val engineFor: (TtsEngineType) -> TtsEngine,
    private val onError: (String) -> Unit,
    /** 字幕时间轴上的当前时间；直播实时字幕和播放位置不是一回事 */
    private val clock: () -> Long = { player().currentPosition },
) {
    private val cacheDir = File(context.cacheDir, "tts").apply { mkdirs() }
    private val ready = ConcurrentHashMap<String, File>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private var loop: Job? = null
    private var media: MediaPlayer? = null
    private var mediaPaused = false
    private var lastCueId = -1
    private var lastPos = -1L
    private var waitingCue: Cue? = null
    private var ducked = false
    private var reportedError = false

    val running get() = loop?.isActive == true

    fun start() {
        if (running) return
        trimCache()
        reportedError = false
        loop = scope.launch(Dispatchers.Main) {
            var synth: Job? = null
            while (isActive) {
                tick()
                if (synth?.isActive != true) synth = scope.launch(Dispatchers.IO) { prefetch() }
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        stopSpeaking()
        lastCueId = -1
        lastPos = -1
        waitingCue = null
    }

    /** 语音或角色变了：已合成的语音作废（文件留在缓存里，换回来时还能直接用）。 */
    fun reset() {
        ready.clear()
        failed.clear()
        stopSpeaking()
        lastCueId = -1
    }

    private fun tick() {
        val p = player()
        val s = snapshot()
        val pos = clock() - s.delayMs
        val playing = p.isPlaying

        // 拖动进度：位置突变时停掉正在读的句子
        if (lastPos >= 0 && abs(pos - lastPos) > SEEK_THRESHOLD_MS) {
            stopSpeaking()
            lastCueId = -1
            waitingCue = null
        }
        lastPos = pos

        media?.let { m ->
            if (!playing && m.isPlaying) { m.pause(); mediaPaused = true }
            else if (playing && mediaPaused) { m.start(); mediaPaused = false }
        }

        if (!playing) return
        val cue = s.cues.cueAt(pos)
        if (cue != null && cue.id != lastCueId) {
            lastCueId = cue.id
            // 从句子中间开始（拖动到这里）的不读
            waitingCue = if (pos - cue.startMs < LATE_START_MS && cue.translation != null) cue else null
        }
        val w = waitingCue ?: return
        if (pos > w.endMs - 200) { waitingCue = null; return }
        val file = ready[keyFor(w, s)] ?: return
        waitingCue = null
        speak(w, file, s, p)
    }

    private fun speak(cue: Cue, file: File, s: TtsSnapshot, p: Player) {
        stopSpeaking()
        try {
            val m = MediaPlayer()
            m.setDataSource(file.path)
            m.prepare()
            // 可用时长：到下一句开始为止（至少是本句的显示时长）
            val next = s.cues.firstOrNull { it.startMs > cue.startMs }
            val slot = maxOf(cue.endMs - cue.startMs, (next?.startMs ?: cue.endMs) - cue.startMs - 150).coerceAtLeast(300)
            val fit = (m.duration.toFloat() / slot).coerceIn(1f, MAX_SPEED)
            val speed = fit * p.playbackParameters.speed
            if (abs(speed - 1f) > 0.01f) m.playbackParams = m.playbackParams.setSpeed(speed)
            m.setOnCompletionListener { done -> if (media === done) stopSpeaking() else done.release() }
            m.start()
            media = m
            mediaPaused = false
            if (!ducked) {
                p.volume = s.duckVolume
                ducked = true
            }
        } catch (e: Exception) {
            Log.w("Voxtitle", "朗读播放失败", e)
            stopSpeaking()
        }
    }

    private fun stopSpeaking() {
        media?.let { runCatching { it.stop() }; it.release() }
        media = null
        mediaPaused = false
        if (ducked) {
            runCatching { player().volume = 1f }
            ducked = false
        }
    }

    /** 合成接下来一段时间内的句子，一次一句，避免触发接口限流。 */
    private suspend fun prefetch() {
        val s = snapshot()
        val pos = lastPos.coerceAtLeast(0)
        val upcoming = s.cues.asSequence()
            .filter { it.endMs >= pos && it.startMs <= pos + PREFETCH_MS && !it.translation.isNullOrBlank() }
            .take(PREFETCH_COUNT)
            .toList()
        val engine = runCatching { engineFor(s.engine) }.getOrElse { report(it); return }
        for (cue in upcoming) {
            val key = keyFor(cue, s)
            if (ready.containsKey(key) || key in failed) continue
            val ext = if (s.engine == TtsEngineType.AZURE) "mp3" else "wav"
            val file = File(cacheDir, "$key.$ext")
            if (file.length() > 0) {
                ready[key] = file
                continue
            }
            try {
                engine.synthesize(spokenText(cue.translation!!), voiceFor(cue, s), s.locale, file)
                ready[key] = file
            } catch (e: Exception) {
                failed += key
                report(e)
                return
            }
        }
    }

    private fun report(e: Throwable) {
        Log.w("Voxtitle", "朗读合成失败", e)
        if (!reportedError) {
            reportedError = true
            onError(e.message ?: e.toString())
        }
    }

    private fun voiceFor(cue: Cue, s: TtsSnapshot): String? {
        val role = cue.speaker?.let { id -> s.roles.firstOrNull { it.id == id } }
        return role?.voice?.takeIf { role.voiceEngine == s.engine.name } ?: s.defaultVoice
    }

    private fun keyFor(cue: Cue, s: TtsSnapshot): String {
        val raw = "${s.engine.name}|${voiceFor(cue, s)}|${s.locale}|${cue.translation}"
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** 去掉双语字幕里的换行、ASS 标签等不该读出来的东西 */
    private fun spokenText(text: String) = text.replace(Regex("\\{[^}]*\\}|<[^>]*>"), "").replace('\n', ' ').trim()

    /** 只保留最近一周的朗读缓存 */
    private fun trimCache() {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 3600_000L
        cacheDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    companion object {
        private const val TICK_MS = 80L
        private const val SEEK_THRESHOLD_MS = 2500L
        private const val LATE_START_MS = 1200L
        private const val PREFETCH_MS = 60_000L
        private const val PREFETCH_COUNT = 8
        const val MAX_SPEED = 1.6f
    }
}
