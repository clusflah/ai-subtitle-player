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
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

data class WebCaptionState(
    /** 正在给网页里的视频生成字幕 */
    val running: Boolean = false,
    /** 显示在字幕位置的说明（连接中、出错、读不到声音等）；null 表示不显示 */
    val status: String? = null,
    /** 字幕，时间是网页视频的播放位置（毫秒），按开始时间排序 */
    val cues: List<Cue> = emptyList(),
    /** 每句字幕来自哪一路声音（MediaSource 编号或视频地址），见 [source] */
    val sources: Map<Int, String> = emptyMap(),
    /** 视频现在放的是哪一路：同一个 video 里先放广告再放正片时，只显示正在放的那一路的字幕 */
    val source: String? = null,
    /** 读不到声音、但能拿到地址的视频：可以改用播放器打开 */
    val directUrl: String? = null,
    /** 网页里读不到声音：可以改用系统的播放捕获读取（Android 10 起，要用户授权） */
    val canCapture: Boolean = false,
    /** 正在用系统的播放捕获读取声音 */
    val capturing: Boolean = false,
)

/**
 * 网页视频的 AI 字幕：注入网页的脚本（assets/web_captions.js）报告视频的播放位置，声音按能拿到的方式取：
 * 1. 网页用 MSE 播放：脚本截下网页缓冲好的音频（比画面提前十几到几十秒）解码后送来，字幕能赶在声音前面；
 * 2. 跨域的视频文件、播放列表（网页读不到声音）：App 带上网页的 Cookie、Referer 直接解码这个地址，同样提前识别；
 * 3. 普通视频：脚本用 Web Audio 读出正在播放的声音，字幕晚一两秒；
 * 4. 都不行（有版权保护等）：用户授权后用系统的播放捕获读取 App 放出来的声音（见 [CaptureService]）。
 * 识别出的句子换算到视频的播放位置上，再按播放位置就近翻译；同一个视频拖回已经识别过的地方不再重复识别。
 */
class WebCaptions(private val app: Application, private val scope: CoroutineScope) {
    init {
        current = this
    }

    /** 网页的 User-Agent，App 直接读取视频地址时带上 */
    @Volatile
    var userAgent: String? = null
    private val models = ModelManager(app)
    private val _state = MutableStateFlow(WebCaptionState())
    val state: StateFlow<WebCaptionState> = _state.asStateFlow()

    /** 网页脚本每 250 毫秒问一次要不要送声音 */
    @Volatile
    var enabled = false
        private set

    // ---- 视频的播放位置（网页脚本报告） ----

    private class Clock(val frame: String, val videoMs: Double, val paused: Boolean, val rate: Double, val at: Long, val source: String)

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

    @Volatile
    private var shownAt = -1L

    /**
     * 显示字幕用的时间：网页报告的位置有几十毫秒的抖动，按经过的时间推算时会稍微往回跳，
     * 正好落在两句交界处就会一闪；播放中小幅往回跳时不回退（拖动进度那种大跳照常跟上）。
     */
    fun displayTime(): Long? {
        val t = videoTimeNow() ?: return null
        val last = shownAt
        val c = clock
        val shown = if (c != null && !c.paused && t < last && last - t < 500) last else t
        shownAt = shown
        return shown
    }

    // ---- 识别 ----

    /** ahead：比播放提前送来的声音（网页缓冲、直接读地址）；否则是边播边读的 */
    private class Chunk(val samples: FloatArray, val videoMs: Double, val rate: Double, val ahead: Boolean, val source: String)
    private class Anchor(val audioMs: Long, val videoMs: Double, val rate: Double, val source: String)

    /**
     * 一次识别用到的数据：换语言重新识别、换了网页时整个换掉。
     * 旧的识别循环被取消后还可能收个尾，它手里拿的是旧的这份，碰不到新的（不然两次识别的时间对应混在一起，字幕错位）
     */
    private class Session {
        val chunks = LinkedBlockingDeque<Chunk>()
        val anchors = ArrayList<Anchor>()

        /** 每一路已经送去识别的视频时间段：拖回来重看时不再重复识别 */
        val covered = HashMap<String, RangeSet>()
    }

    @Volatile
    private var session = Session()
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
        aheadNoted = false
        session = Session()
        _state.update { it.copy(running = true, status = "播放网页里的视频后开始识别…", directUrl = null) }
        job = scope.launch {
            try {
                run(settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = "识别失败：${e.message}") }
            } finally {
                // 换语言时会马上开始新的一次：旧的收尾不能把新的关掉
                if (job === coroutineContext.job) {
                    enabled = false
                    translateJob?.cancel()
                    _state.update { it.copy(running = false) }
                }
            }
        }
        startTranslation(settings)
    }

    private fun startTranslation(settings: AppSettings) {
        translateJob?.cancel()
        translateJob = null
        val t = settings.createTranslator() ?: return
        if (settings.autoTranslate) translateJob = scope.launch { translateLoop(t, settings) }
    }

    /** 换了视频语言：已有字幕作废，用新的设置从头识别（正在识别时才重新开始） */
    fun restart(settings: AppSettings) {
        val running = job?.isActive == true
        log("换视频语言，重新识别（${settings.asrLanguage}），正在识别：$running")
        val direct = directSource
        if (running) {
            job?.cancel()
            translateJob?.cancel()
        }
        stopDirect()
        // 网页脚本看到它变了，会把缓冲好的声音重新送一遍
        generation++
        session = Session()
        _state.update { it.copy(cues = emptyList(), sources = emptyMap()) }
        if (running) {
            start(settings)
            direct?.let { startDirect(it) }
        }
    }

    /** 换语言后要从头识别的次数（网页脚本读） */
    @Volatile
    var generation = 0
        private set

    /** 只换了翻译语言：识别出的原文留着，译文重新翻 */
    fun retranslate(settings: AppSettings) {
        _state.update { s -> s.copy(cues = s.cues.map { it.copy(translation = null) }) }
        if (job?.isActive == true) startTranslation(settings)
    }

    fun stop() {
        enabled = false
        job?.cancel()
        translateJob?.cancel()
        stopDirect()
        stopCapture()
        _state.update { it.copy(running = false, status = null, canCapture = false) }
    }

    /** 在字幕的位置显示一句说明 */
    fun note(text: String) = _state.update { it.copy(status = text) }

    /** 换了一个网页：旧字幕不再对得上 */
    fun reset() {
        session = Session()
        clock = null
        pageKey = null
        stopDirect()
        _state.update {
            it.copy(cues = emptyList(), sources = emptyMap(), directUrl = null, canCapture = false, status = if (it.running && !it.capturing) "播放网页里的视频后开始识别…" else null)
        }
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
        // 引擎等第一段声音来了再建：提前送来的声音（网页缓冲、直接读地址）用分段识别，能比播放快很多；
        // 边播边读的声音用流式识别（引擎支持时），一两秒出字。流式识别按实时速度发送，提前送来的声音会被它拖回和播放同步
        var engine: SpeechEngine? = null
        var engineAhead = false
        val job = coroutineContext.job
        val cancelHandle = job.invokeOnCompletion { engine?.cancel() }
        try {
            // 送进识别的音频总长（毫秒），字幕时间都相对它；换引擎时新引擎从 engineBase 开始数
            var audioMs = 0L
            var engineBase = 0L
            var heard = false
            // 上一段送进引擎的声音是哪一路、到视频的哪儿结束
            var fedSource: String? = null
            var fedEnd = 0L
            var current: Session? = null
            while (isActive) {
                    val s = session
                    val chunk = s.chunks.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    // 刚被取消（换语言了）：这段是新一次识别的，放回去
                    if (!isActive) {
                        s.chunks.offerFirst(chunk)
                        break
                    }
                    // 换了网页：引擎里没识别完的是旧网页的声音，不要了
                    if (s !== current) {
                        engine?.close()
                        engine = null
                        current = s
                        audioMs = 0L
                        fedSource = null
                    }
                    // 识别跟不上、或者拖动过进度：已经播过去的声音不用识别了
                    val playing = videoTimeNow()
                    val chunkEnd = chunk.videoMs + chunk.samples.size * 1000.0 / Transcriber.SAMPLE_RATE * chunk.rate
                    if (playing != null && chunk.source == clock?.source && chunkEnd < playing - STALE_MS) { log("跳过过时的声音 ${chunk.videoMs.toLong()}（播放到 $playing）"); continue }
                    if (!heard) {
                        heard = true
                        _state.update { it.copy(status = if (it.cues.isEmpty()) "正在识别…（第一句要等几秒）" else null) }
                    }
                    val lenMs = chunk.samples.size * 1000L / Transcriber.SAMPLE_RATE
                    val start = chunk.videoMs.toLong()
                    val end = start + (lenMs * chunk.rate).toLong()
                    val skip = synchronized(s.anchors) {
                        val done = s.covered.getOrPut(chunk.source) { RangeSet() }
                        if (done.contains(start) && done.contains(end - 1)) true else {
                            s.anchors.add(Anchor(audioMs, chunk.videoMs, chunk.rate, chunk.source))
                            if (s.anchors.size > 2000) s.anchors.subList(0, 500).clear()
                            done.add(start, end)
                            false
                        }
                    }
                    if (skip) { log("跳过已识别 $start-$end"); continue }
                    // 声音接不上（拖了进度、换了一路）：先把引擎里还没出结果的那段识别完，再从新位置开始，
                    // 不然拖动前后的声音会被当成一句话，出来一条横跨几分钟的字幕
                    if (engine != null && (chunk.source != fedSource || kotlin.math.abs(start - fedEnd) > GAP_MS)) {
                        log("声音接不上（$fedSource $fedEnd → ${chunk.source} $start），重新开始识别")
                        val base = engineBase
                        engine?.finish { c -> publish(c.copy(startMs = c.startMs + base, endMs = c.endMs + base), s) }
                        engine?.close()
                        engine = null
                    }
                    fedSource = chunk.source
                    fedEnd = end
                    if (engine == null || engineAhead != chunk.ahead) {
                        engine?.close()
                        engine = createEngine(settings, chunk.ahead)
                        engineAhead = chunk.ahead
                        engineBase = audioMs
                    }
                    val base = engineBase
                    audioMs += lenMs
                    engine!!.accept(chunk.samples) { c -> publish(c.copy(startMs = c.startMs + base, endMs = c.endMs + base), s) }
                }
            } finally {
                cancelHandle.dispose()
                engine?.close()
            }
    }

    private fun createEngine(s: AppSettings, ahead: Boolean): SpeechEngine {
        if (!ahead && s.streamingAvailable) s.createStreamingEngine()?.let { return it }
        return if (s.asrEngine == AsrEngine.LOCAL) {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            // 本地模型要等一段话说完才出字：边播边读时一段最长 4 秒，不然字幕会晚十几秒
            Transcriber(ModelCatalog.byId(s.localModel), models, AsrLanguages.primary(s.asrLanguage), threads, if (ahead) 12f else 4f)
        } else s.createCloudEngine().also {
            // 提前送来的声音不急，按直播的分段；边播边读时分段再短一点，字幕出得快
            (it as? com.aisub.player.asr.cloud.ChunkedCloudEngine)?.useChunks(if (ahead) 4 else 2, if (ahead) 6 else 3)
        }
    }

    /** 引擎时间（送进去的音频的第几毫秒）→ 那一路和视频播放位置 */
    private fun videoMsAt(s: Session, audioMs: Long): Pair<String, Long>? = synchronized(s.anchors) {
        val a = s.anchors.lastOrNull { it.audioMs <= audioMs } ?: s.anchors.firstOrNull() ?: return null
        a.source to (a.videoMs + (audioMs - a.audioMs) * a.rate).toLong()
    }

    private fun publish(c: Cue, s: Session) {
        // 已经换过一次识别（换语言、换网页）：旧的结果不要
        if (s !== session) return
        val (source, start) = videoMsAt(s, c.startMs) ?: return
        var end = maxOf(videoMsAt(s, c.endMs)?.takeIf { it.first == source }?.second ?: start, start + 800)
        // 字幕出来时这句已经说完了：至少再显示一会儿，不然一闪而过
        val now = videoTimeNow()?.takeIf { clock?.source == source }
        if (now != null && end < now + MIN_SHOW_MS) end = now + minOf(maxOf(end - start, MIN_SHOW_MS), 4_000)
        val cue = Cue(nextId++, start, end, c.text)
        log("字幕 $start-$end（播放到 $now）：${c.text}")
        _state.update { s ->
            s.copy(cues = (s.cues + cue).sortedBy { it.startMs }, sources = s.sources + (cue.id to source), status = if (source == s.source) null else s.status)
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
                val st = _state.value
                val batch = cues.filter { it.translation == null && it.id !in inFlight && it.endMs >= now - 3_000 && st.sources[it.id] == st.source }.take(BATCH)
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

    fun onTick(frame: String, href: String, videoMs: Double, paused: Boolean, rate: Double, source: String) {
        val c = clock
        // 页面上有好几个框架都在放视频（例如角落里静音的广告）：一直跟着原来那个，
        // 它停了或者一秒多没报告了才换，不然时间来回跳，字幕一闪一闪
        if (c != null && c.frame != frame && !c.paused && SystemClock.elapsedRealtime() - c.at < 1_200) return
        if (pageKey != href) {
            if (pageKey != null) reset()
            pageKey = href
        }
        clock = Clock(frame, videoMs, paused, rate, SystemClock.elapsedRealtime(), source)
        if (_state.value.source != source) _state.update { it.copy(source = source) }
    }

    /** 边播边读的声音（Web Audio） */
    fun onAudio(frame: String, base64: String, startMs: Double, rate: Double, source: String) {
        if (!enabled || capturing || clock?.frame?.let { it != frame } == true) return
        // 正在（或准备）直接读取视频地址、提前识别：边播边读的声音不要，免得两路混着来回换识别引擎
        // 直接读取 8 秒还没读出声音（卡住了）：先边播边读
        if (directJob?.isActive == true && directSource == source && (directProduced || SystemClock.elapsedRealtime() - directSince < 8_000)) return
        decodePcm(base64)?.let { session.chunks.add(Chunk(it, startMs, rate, ahead = false, source)) }
    }

    /** 网页缓冲好、提前解码的声音（MSE），startMs 是这段声音在视频里的位置 */
    fun onAhead(frame: String, base64: String, startMs: Double, source: String) {
        if (!enabled || clock?.frame?.let { it != frame } == true) return
        decodePcm(base64)?.let { session.chunks.add(Chunk(it, startMs, 1.0, ahead = true, source)) }
        if (!aheadNoted) {
            aheadNoted = true
            _state.update { it.copy(status = if (it.cues.isEmpty()) "正在提前识别网页缓冲好的声音…" else it.status, canCapture = false) }
        }
    }

    @Volatile
    private var aheadNoted = false

    private fun decodePcm(base64: String): FloatArray? {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val samples = FloatArray(bytes.size / 2) { i ->
            ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f
        }
        return samples.takeIf { it.isNotEmpty() }
    }

    fun onProblem(kind: String, detail: String) {
        log("网页报告：$kind $detail")
        if (capturing) return
        val capture = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        when {
            // 视频是普通地址（不是 MSE、没加密）：先试试直接读取、提前识别，比边播边读快得多；读不了再边播边读
            kind == "src" -> if (detail.startsWith("http")) {
                _state.update { it.copy(directUrl = detail) }
                startDirect(detail, quiet = true)
            }
            kind == "cors" && detail.startsWith("http") -> {
                _state.update { it.copy(status = "网页读不到这个视频的声音，改由译幕直接读取视频地址…", directUrl = detail) }
                startDirect(detail)
            }
            kind == "drm" -> _state.update {
                it.copy(
                    status = "这个视频有版权保护，网页里读不到声音" + if (capture) "。可以改为读取系统声音（边播边识别）" else "，无法生成字幕",
                    canCapture = capture,
                )
            }
            else -> _state.update {
                it.copy(status = "读取视频声音失败：$detail" + if (capture) "。可以改为读取系统声音" else "", canCapture = capture)
            }
        }
    }

    // ---- 跨域视频：App 直接解码视频地址，提前识别 ----

    private var directJob: Job? = null
    private var directSource: String? = null

    @Volatile
    private var directQuiet = false

    /** 这次直接读取开始的时间、有没有读出过声音 */
    @Volatile
    private var directSince = 0L

    @Volatile
    private var directProduced = false

    private fun stopDirect() {
        directJob?.cancel()
        directJob = null
        directSource = null
    }

    /** quiet：网页自己也能读到声音，读不了时不提示，回到边播边读 */
    private fun startDirect(url: String, quiet: Boolean = false) {
        if (directJob?.isActive == true && directSource == url) {
            if (!quiet) directQuiet = false
            return
        }
        directJob?.cancel()
        directSource = url
        directQuiet = quiet
        directSince = SystemClock.elapsedRealtime()
        directProduced = false
        directJob = scope.launch(Dispatchers.IO) {
            val headers = buildMap {
                pageKey?.let { put("Referer", it) }
                userAgent?.let { put("User-Agent", it) }
                android.webkit.CookieManager.getInstance().getCookie(url)?.let { put("Cookie", it) }
            }
            val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
            val factory = androidx.media3.datasource.DefaultDataSource.Factory(app, http)
            val job = coroutineContext.job
            var from = videoTimeNow() ?: 0L
            var ended = false
            while (isActive) {
                val start = from
                var produced = 0L
                var seekTo = -1L
                val frontier = { start + produced * 1000 / Transcriber.SAMPLE_RATE }
                if (!ended) try {
                    com.aisub.player.asr.ExoAudioDecoder(app, android.net.Uri.parse(url), factory).decode(
                        Transcriber.SAMPLE_RATE, start,
                        isActive = {
                            val now = videoTimeNow() ?: start
                            if (now < start - 3_000 || now > frontier() + 5_000) seekTo = now
                            job.isActive && seekTo < 0 && frontier() < now + DIRECT_AHEAD_MS
                        },
                        onProgress = {},
                    ) { chunk ->
                        if (produced == 0L) log("直接读取从 $start 开始")
                        directProduced = true
                        session.chunks.add(Chunk(chunk, frontier().toDouble(), 1.0, ahead = true, url))
                        produced += chunk.size
                    }
                    if (produced == 0L) ended = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("Voxtitle", "网页视频直接读取失败：$url", e)
                    if (directQuiet) return@launch
                    val capture = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                    _state.update {
                        it.copy(
                            status = "译幕也读不到这个视频的声音（${e.message?.take(60)}）。可以点「用播放器打开」" + if (capture) "，或改为读取系统声音" else "",
                            canCapture = capture,
                        )
                    }
                    return@launch
                }
                if (seekTo >= 0) {
                    from = seekTo
                    ended = false
                    continue
                }
                // 已经提前读够了（或读到了结尾）：等播放追上来，或者拖动了进度
                val end = frontier()
                while (isActive) {
                    delay(500)
                    val now = videoTimeNow() ?: continue
                    if (now < start - 3_000 || now > end + 5_000) {
                        from = now
                        ended = false
                        break
                    }
                    if (!ended && end < now + DIRECT_AHEAD_MS / 2) {
                        from = end
                        break
                    }
                }
            }
        }
    }

    // ---- 系统的播放捕获（见 CaptureService） ----

    @Volatile
    var capturing = false
        private set

    fun onCaptureStarted() {
        capturing = true
        _state.update { it.copy(capturing = true, canCapture = false, status = if (it.cues.isEmpty()) "正在读取系统声音，边播边识别…" else null) }
    }

    fun onCaptureStopped(error: String? = null) {
        capturing = false
        _state.update { it.copy(capturing = false, status = error ?: it.status) }
    }

    /** 捕获到的一小段声音：刚刚播出来的，按现在的播放位置往前推它的长度 */
    fun onCaptured(samples: FloatArray) {
        if (!enabled) return
        val c = clock ?: return
        if (c.paused) return
        val now = videoTimeNow() ?: return
        val startMs = now - samples.size * 1000.0 / Transcriber.SAMPLE_RATE * c.rate
        session.chunks.add(Chunk(samples, startMs, c.rate, ahead = false, c.source))
    }

    private fun stopCapture() {
        if (capturing) app.stopService(android.content.Intent(app, CaptureService::class.java))
        capturing = false
        _state.update { it.copy(capturing = false) }
    }

    /** 调试版的诊断日志（adb logcat -s VoxWeb）：每段声音、每句字幕怎么处理的 */
    private fun log(msg: String) {
        if (com.aisub.player.BuildConfig.DEBUG) android.util.Log.d("VoxWeb", msg)
    }

    companion object {
        private const val MIN_SHOW_MS = 1_800L
        private const val BATCH = 4
        private const val CONTEXT = 6

        /** 早于播放位置这么多的声音不再识别 */
        private const val STALE_MS = 2_000L

        /** 前后两段声音在视频里隔了这么多就算接不上 */
        private const val GAP_MS = 1_000L

        /** 直接读取视频地址时最多比播放位置提前多少 */
        private const val DIRECT_AHEAD_MS = 90_000L

        /** 捕获服务把声音交给正在用的那一个 */
        @Volatile
        var current: WebCaptions? = null
            private set
    }
}
