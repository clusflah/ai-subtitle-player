package com.aisub.player.web

import android.app.Application
import android.os.SystemClock
import android.util.Base64
import com.aisub.player.AppSettings
import com.aisub.player.AsrEngine
import com.aisub.player.asr.AsrLanguages
import com.aisub.player.asr.ModelCatalog
import com.aisub.player.asr.ModelManager
import com.aisub.player.asr.RangeSet
import com.aisub.player.asr.SpeechEngine
import com.aisub.player.asr.Transcriber
import com.aisub.player.asrProblem
import com.aisub.player.createCloudEngine
import com.aisub.player.createStreamingEngine
import com.aisub.player.streamingAvailable
import com.aisub.player.subtitle.Cue
import com.aisub.player.translate.ChineseScript
import com.aisub.player.translate.LanguageCheck
import com.aisub.player.translate.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

data class WebCaptionState(
    /** 正在给网页里的视频生成字幕 */
    val running: Boolean = false,
    /** 显示在字幕位置的说明（连接中、出错、读不到声音等）；null 表示不显示 */
    val status: String? = null,
    /** 字幕，时间是网页视频的播放位置（毫秒），按开始时间排序 */
    val cues: List<Cue> = emptyList(),
    /** 读不到声音、但能拿到地址的视频：可以改用播放器打开 */
    val directUrl: String? = null,
)

/**
 * 网页视频的 AI 字幕：注入网页的脚本（assets/web_captions.js）读出视频的声音、报告播放位置，
 * 这里把声音交给识别引擎，识别出的句子换算到视频的播放位置上，再按播放位置就近翻译。
 *
 * 和直播字幕一样是边播边识别，字幕会比声音晚一两秒出来；同一个视频拖回已经识别过的地方不再重复识别。
 */
class WebCaptions(private val app: Application, private val scope: CoroutineScope) {
    private val models = ModelManager(app)
    private val _state = MutableStateFlow(WebCaptionState())
    val state: StateFlow<WebCaptionState> = _state.asStateFlow()

    /** 网页脚本每 250 毫秒问一次要不要送声音 */
    @Volatile
    var enabled = false
        private set

    // ---- 视频的播放位置（网页脚本报告） ----

    private class Clock(val frame: String, val videoMs: Double, val paused: Boolean, val rate: Double, val at: Long)

    @Volatile
    private var clock: Clock? = null

    /** 当前视频所在页面的地址：换了视频（页面）就清掉字幕重新来 */
    private var pageKey: String? = null

    /** 现在视频播放到哪了（毫秒）；还不知道时返回 null */
    fun videoTimeNow(): Long? {
        val c = clock ?: return null
        val elapsed = if (c.paused) 0.0 else (SystemClock.elapsedRealtime() - c.at) * c.rate
        return (c.videoMs + minOf(elapsed, 1_000.0)).toLong()
    }

    // ---- 识别 ----

    private class Chunk(val samples: FloatArray, val videoMs: Double, val rate: Double)
    private class Anchor(val audioMs: Long, val videoMs: Double, val rate: Double)

    private val chunks = LinkedBlockingQueue<Chunk>()
    private val anchors = ArrayList<Anchor>()

    /** 已经送去识别的视频时间段：拖回来重看时不再重复识别 */
    private val covered = RangeSet()
    private var job: Job? = null
    private var translateJob: Job? = null
    private var nextId = 1

    fun start(settings: AppSettings) {
        if (job?.isActive == true) return
        settings.asrProblem()?.let { p ->
            _state.update { it.copy(status = p) }
            return
        }
        enabled = true
        chunks.clear()
        _state.update { it.copy(running = true, status = "播放网页里的视频后开始识别…", directUrl = null) }
        job = scope.launch {
            try {
                run(settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = "识别失败：${e.message}") }
            } finally {
                enabled = false
                translateJob?.cancel()
                _state.update { it.copy(running = false) }
            }
        }
        settings.createTranslator()?.let { t -> if (settings.autoTranslate) translateJob = scope.launch { translateLoop(t, settings) } }
    }

    fun stop() {
        enabled = false
        job?.cancel()
        translateJob?.cancel()
        _state.update { it.copy(running = false, status = null) }
    }

    /** 换了一个网页：旧字幕不再对得上 */
    fun reset() {
        synchronized(anchors) {
            anchors.clear()
            covered.clear()
        }
        chunks.clear()
        clock = null
        pageKey = null
        _state.update { it.copy(cues = emptyList(), directUrl = null, status = if (it.running) "播放网页里的视频后开始识别…" else null) }
    }

    private suspend fun run(settings: AppSettings) = withContext(Dispatchers.Default) {
        val local = settings.asrEngine == AsrEngine.LOCAL
        if (local) {
            val model = ModelCatalog.byId(settings.localModel)
            if (!models.isReady(model)) {
                models.ensure(model, settings.modelBaseUrl) { msg, p ->
                    _state.update { it.copy(status = if (p >= 0) "$msg ${(p * 100).toInt()}%" else msg) }
                }
            }
        }
        createEngine(settings).use { engine ->
            val cancelHandle = coroutineContext.job.invokeOnCompletion { engine.cancel() }
            try {
                // 识别引擎收到的音频总长（毫秒），字幕时间都相对它
                var audioMs = 0L
                var heard = false
                val onCue: (Cue) -> Unit = { c -> publish(c) }
                while (isActive) {
                    val chunk = chunks.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (!heard) {
                        heard = true
                        _state.update { it.copy(status = if (it.cues.isEmpty()) "正在识别…（第一句要等几秒）" else null) }
                    }
                    val lenMs = chunk.samples.size * 1000L / Transcriber.SAMPLE_RATE
                    val start = chunk.videoMs.toLong()
                    val end = start + (lenMs * chunk.rate).toLong()
                    val skip = synchronized(anchors) {
                        if (covered.contains(start) && covered.contains(end - 1)) true else {
                            anchors.add(Anchor(audioMs, chunk.videoMs, chunk.rate))
                            if (anchors.size > 2000) anchors.subList(0, 500).clear()
                            covered.add(start, end)
                            false
                        }
                    }
                    if (skip) continue
                    audioMs += lenMs
                    engine.accept(chunk.samples, onCue)
                }
            } finally {
                cancelHandle.dispose()
            }
        }
    }

    private fun createEngine(s: AppSettings): SpeechEngine {
        if (s.streamingAsr && s.streamingAvailable) s.createStreamingEngine()?.let { return it }
        return if (s.asrEngine == AsrEngine.LOCAL) {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            Transcriber(ModelCatalog.byId(s.localModel), models, AsrLanguages.primary(s.asrLanguage), threads)
        } else s.createCloudEngine().also {
            // 边播边识别：分段短一点，字幕出得快
            (it as? com.aisub.player.asr.cloud.ChunkedCloudEngine)?.useChunks(4, 6)
        }
    }

    /** 引擎时间（送进去的音频的第几毫秒）→ 视频播放位置 */
    private fun videoMsAt(audioMs: Long): Long? = synchronized(anchors) {
        val a = anchors.lastOrNull { it.audioMs <= audioMs } ?: anchors.firstOrNull() ?: return null
        (a.videoMs + (audioMs - a.audioMs) * a.rate).toLong()
    }

    private fun publish(c: Cue) {
        val start = videoMsAt(c.startMs) ?: return
        var end = maxOf(videoMsAt(c.endMs) ?: start, start + 800)
        // 字幕出来时这句已经说完了：至少再显示一会儿，不然一闪而过
        val now = videoTimeNow()
        if (now != null && end < now + MIN_SHOW_MS) end = now + minOf(maxOf(end - start, MIN_SHOW_MS), 4_000)
        val cue = Cue(nextId++, start, end, c.text)
        _state.update { s ->
            s.copy(cues = (s.cues + cue).sortedBy { it.startMs }, status = null)
        }
    }

    // ---- 翻译：播放位置附近还没译的句子，识别出来就翻 ----

    private suspend fun translateLoop(translator: Translator, settings: AppSettings) {
        val slots = Semaphore(2)
        val inFlight = HashSet<Int>()
        var failures = 0
        var retryAt = 0L
        kotlinx.coroutines.coroutineScope {
            while (isActive) {
                delay(250)
                if (SystemClock.elapsedRealtime() < retryAt) continue
                val target = settings.ttsLocale
                val same = _state.value.cues
                    .filter { it.translation == null && LanguageCheck.alreadyTarget(it.text, target, settings.asrLanguage, ChineseScript::variant) }
                    .map { it.id }.toSet()
                if (same.isNotEmpty()) {
                    _state.update { s -> s.copy(cues = s.cues.map { if (it.id in same) it.copy(translation = it.text) else it }) }
                }
                val now = videoTimeNow() ?: 0L
                val cues = _state.value.cues
                val batch = cues.filter { it.translation == null && it.id !in inFlight && it.endMs >= now - 3_000 }.take(BATCH)
                if (batch.isEmpty() || !slots.tryAcquire()) continue
                val ids = batch.map { it.id }
                inFlight += ids
                val first = cues.indexOfFirst { it.id == ids.first() }
                val context = cues.subList(maxOf(0, first - CONTEXT), maxOf(0, first)).map { it.text }
                launch {
                    try {
                        val results = translator.translate(batch.map { it.text }, context, settings.targetLanguage)
                        val done = batch.indices.associate { i -> batch[i].id to (results.getOrNull(i) ?: batch[i].text) }
                        _state.update { s -> s.copy(cues = s.cues.map { c -> done[c.id]?.let { c.copy(translation = it) } ?: c }) }
                        failures = 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 限流、网络抖动常见：原文照常显示，越失败等得越久
                        failures++
                        retryAt = SystemClock.elapsedRealtime() + minOf(3_000L shl minOf(failures - 1, 4), 30_000L)
                        android.util.Log.w("Voxtitle", "网页字幕翻译失败（第 $failures 次）：${e.message}")
                    } finally {
                        inFlight -= ids.toSet()
                        slots.release()
                    }
                }
            }
        }
    }

    // ---- 网页脚本的回调（在 WebView 的 JavaBridge 线程上） ----

    fun onTick(frame: String, href: String, videoMs: Double, paused: Boolean, rate: Double) {
        val c = clock
        // 另一个框架里的视频在放、而当前的停了：跟着正在放的那个
        if (c != null && c.frame != frame && !c.paused && paused) return
        if (pageKey != href) {
            if (pageKey != null) reset()
            pageKey = href
        }
        clock = Clock(frame, videoMs, paused, rate, SystemClock.elapsedRealtime())
    }

    fun onAudio(frame: String, base64: String, startMs: Double, rate: Double) {
        if (!enabled || clock?.frame?.let { it != frame } == true) return
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val samples = FloatArray(bytes.size / 2) { i ->
            ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f
        }
        if (samples.isNotEmpty()) chunks.add(Chunk(samples, startMs, rate))
    }

    fun onProblem(kind: String, detail: String) {
        val msg = when (kind) {
            "drm" -> "这个视频有版权保护，读不到声音，无法生成字幕"
            "cors" -> "这个网站不允许读取视频的声音。可以点「用播放器打开」，在译幕播放器里看并生成字幕"
            else -> "读取视频声音失败：$detail"
        }
        _state.update { it.copy(status = msg, directUrl = detail.takeIf { kind == "cors" && it.startsWith("http") }) }
    }

    companion object {
        private const val MIN_SHOW_MS = 1_800L
        private const val BATCH = 4
        private const val CONTEXT = 6
    }
}
