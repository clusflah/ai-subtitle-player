package com.aisub.player.desktop

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.aisub.player.AppSettings
import com.aisub.player.AsrEngine
import com.aisub.player.SettingsStore
import com.aisub.player.asr.AsrLanguages
import com.aisub.player.asr.ModelCatalog
import com.aisub.player.asr.ModelManager
import com.aisub.player.asr.SpeechEngine
import com.aisub.player.asr.Transcriber
import com.aisub.player.asr.cloud.ChunkedCloudEngine
import com.aisub.player.asr.cloud.StreamingCloudEngine
import com.aisub.player.asrProblem
import com.aisub.player.createCloudEngine
import com.aisub.player.createStreamingEngine
import com.aisub.player.streamingAvailable
import com.aisub.player.subtitle.Cue
import com.aisub.player.translate.ChatCompletionsTranslator
import com.aisub.player.translate.ChineseScript
import com.aisub.player.translate.LanguageCheck
import com.aisub.player.ui.AppIcons
import com.aisub.player.ui.AppMenu
import com.aisub.player.ui.GlassStyle
import com.aisub.player.ui.GlassTokens
import com.aisub.player.ui.LocalLiquidGlassEnabled
import com.aisub.player.ui.glassShadow
import com.aisub.player.ui.liquidGlass
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class FloatingSubState(
    val running: Boolean = false,
    val status: String? = "点击开始捕获 Windows 系统声音并实时生成双语字幕",
    val currentCue: Cue? = null,
    val cueUpdatedAtMs: Long = 0L,
    val partialText: String? = null,
    val partialTranslation: String? = null,
    val partialUpdatedAtMs: Long = 0L,
    val latencyMs: Long? = null,
    val history: List<Cue> = emptyList(),
    val clickThrough: Boolean = false,
)

/**
 * Windows 桌面全局实时字幕引擎：
 * 通过 [WasapiLoopbackCapture] 采集系统扬声器声音，送入本地 sherpa-onnx 或云端流式/分段语音识别，
 * 并自动调用配置的 AI 翻译模型生成实时双语字幕。
 */
class DesktopLiveTranscriber(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val models = ModelManager(app)
    private val _state = MutableStateFlow(FloatingSubState())
    val state: StateFlow<FloatingSubState> = _state.asStateFlow()

    private var recognizeJob: Job? = null
    private var translateJob: Job? = null
    private val audioQueue = LinkedBlockingQueue<FloatArray>(400)
    private var audioListener: ((FloatArray) -> Unit)? = null
    private val nextId = AtomicInteger(1)

    fun setClickThrough(enabled: Boolean) {
        _state.update { it.copy(clickThrough = enabled) }
    }

    fun start(settings: AppSettings) {
        if (recognizeJob?.isActive == true) return
        settings.asrProblem()?.let { problem ->
            _state.update { it.copy(status = problem, running = false) }
            return
        }
        audioQueue.clear()
        _state.update {
            it.copy(
                running = true,
                partialText = null,
                status = "正在启动 Windows 系统声音捕获…",
            )
        }

        val listener: (FloatArray) -> Unit = { samples ->
            // 保留约 6 秒缓冲容量，防止本地大模型（如 Qwen3-ASR）单句解码耗时波动时丢字
            while (audioQueue.size >= 120) {
                audioQueue.poll()
            }
            audioQueue.offer(samples)
        }
        audioListener = listener
        WasapiLoopbackCapture.addListener(listener)

        recognizeJob = scope.launch {
            var engine: SpeechEngine? = null
            val emptySamples = FloatArray(0)
            val lastSignalOutMs = AtomicLong(System.currentTimeMillis())
            val sessionProducedOutput = AtomicBoolean(false)
            val speechOnsetWallMs = AtomicLong(0L)

            fun recordLatencySample(now: Long): Long? {
                val onset = speechOnsetWallMs.getAndSet(0L)
                if (onset <= 0L) return null
                val raw = (now - onset).coerceIn(120L, 6_500L)
                val prev = _state.value.latencyMs
                return if (prev == null) raw else ((prev * 3L + raw * 2L) / 5L)
            }

            val onPartial: (String) -> Unit = { rawPartial ->
                val text = rawPartial.trim()
                if (text.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    lastSignalOutMs.set(now)
                    sessionProducedOutput.set(true)
                    val lat = recordLatencySample(now)
                    _state.update { st ->
                        if (st.partialText == text) st
                        else st.copy(
                            status = null,
                            partialText = text,
                            partialTranslation = if (st.partialText != null && text.startsWith(st.partialText)) st.partialTranslation else null,
                            partialUpdatedAtMs = now,
                            latencyMs = lat ?: st.latencyMs,
                        )
                    }
                }
            }

            val onCue: (Cue) -> Unit = { rawCue ->
                val text = rawCue.text.trim()
                if (text.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    lastSignalOutMs.set(now)
                    sessionProducedOutput.set(true)
                    val lat = recordLatencySample(now)
                    val cue = Cue(
                        id = nextId.getAndIncrement(),
                        startMs = rawCue.startMs.coerceAtLeast(0L),
                        endMs = maxOf(rawCue.endMs, rawCue.startMs + 1500L),
                        text = text,
                        translation = rawCue.translation,
                    )
                    _state.update { st ->
                        val last = st.history.lastOrNull()
                        val duplicate = last != null && last.text == cue.text && (now - st.cueUpdatedAtMs) < 4_000L
                        val updatedHistory = if (duplicate) {
                            st.history
                        } else {
                            (st.history + cue).takeLast(500)
                        }
                        st.copy(
                            status = null,
                            currentCue = if (duplicate) last else cue,
                            cueUpdatedAtMs = now,
                            partialText = null,
                            partialTranslation = null,
                            latencyMs = lat ?: st.latencyMs,
                            history = updatedHistory,
                        )
                    }
                }
            }

            try {
                if (settings.asrEngine == AsrEngine.LOCAL) {
                    val model = ModelCatalog.byId(settings.localModel)
                    if (!models.isReady(model)) {
                        models.ensure(model, settings.modelBaseUrl) { msg, p ->
                            _state.update {
                                it.copy(status = if (p >= 0f) "$msg ${(p * 100).toInt()}%" else msg)
                            }
                        }
                    }
                }

                while (isActive) {
                    sessionProducedOutput.set(false)
                    lastSignalOutMs.set(System.currentTimeMillis())
                    try {
                        val currentEngine = createEngine(settings, onPartial)
                        engine = currentEngine
                        _state.update { st ->
                            st.copy(status = if (st.currentCue == null && st.partialText == null) "正在监听系统声音…（播放任意视频或音频即出字幕）" else null)
                        }

                        var hadSpeechSinceFlush = false
                        var silentSamples = 0L
                        val preSpeechBuffer = ArrayDeque<FloatArray>(6)
                        var lastObservedSignalMs = lastSignalOutMs.get()
                        var activeAudioWithoutOutputMs = 0L

                        while (isActive) {
                            val chunk = audioQueue.poll(100, TimeUnit.MILLISECONDS)
                            if (chunk == null) {
                                currentEngine.accept(emptySamples, onCue)
                                continue
                            }

                            // 若主窗口正在播放且已开启应用内字幕，自动避让避免画面出现两层重复字幕
                            if (DesktopPlayerEngine.inAppSubtitlesActive && DesktopPlayerEngine.activePlayer?.isPlaying == true) {
                                lastSignalOutMs.set(System.currentTimeMillis())
                                speechOnsetWallMs.set(0L)
                                _state.update { st ->
                                    val yieldMsg = "主窗口正在显示应用内字幕，全局字幕已自动避让"
                                    if (st.status == yieldMsg && st.partialText == null && st.currentCue == null) st
                                    else st.copy(status = yieldMsg, partialText = null, partialTranslation = null, currentCue = null)
                                }
                                currentEngine.accept(emptySamples, onCue)
                                continue
                            } else if (_state.value.status == "主窗口正在显示应用内字幕，全局字幕已自动避让") {
                                _state.update { it.copy(status = "正在监听系统声音…（播放任意视频或音频即出字幕）") }
                            }

                            var maxAbs = 0f
                            var sumSq = 0.0
                            for (v in chunk) {
                                val a = abs(v)
                                if (a > maxAbs) maxAbs = a
                                sumSq += v.toDouble() * v
                            }
                            val rms = if (chunk.isNotEmpty()) sqrt(sumSq / chunk.size) else 0.0
                            if (maxAbs >= 0.012f) {
                                speechOnsetWallMs.compareAndSet(0L, System.currentTimeMillis())
                            }

                            // 端到端活性看门狗：若连续 24 秒输入有效节目声音但引擎无任何文本/临时帧输出，自动重启识别实例
                            val latestSignal = lastSignalOutMs.get()
                            if (latestSignal != lastObservedSignalMs) {
                                lastObservedSignalMs = latestSignal
                                activeAudioWithoutOutputMs = 0L
                            } else if (rms >= 0.008) {
                                activeAudioWithoutOutputMs += chunk.size * 1000L / WasapiLoopbackCapture.SAMPLE_RATE
                                val now = System.currentTimeMillis()
                                if (activeAudioWithoutOutputMs >= 24_000L && now - latestSignal >= 25_000L) {
                                    android.util.Log.w("Voxtitle-LiveSub", "全局字幕看门狗触发：连续 24 秒有声音但无识别输出，正在自动重建引擎")
                                    runCatching { currentEngine.close() }
                                    engine = null
                                    break
                                }
                            }

                            when (currentEngine) {
                                // 1. 云端 WebSocket 实时流式识别（Azure / 火山 / 阿里 / Gemini）：
                                //    持续喂入完整 16kHz 音频流（包含静音心跳），由服务端自动断句；绝不在停顿时调用 finish() 关闭连接！
                                is StreamingCloudEngine -> {
                                    currentEngine.accept(chunk, onCue)
                                }

                                // 2. 本地 sherpa-onnx（内置 Silero VAD）：
                                //    持续送入音频由 VAD 0.25s 自动断句；长停顿时轻量 flush 尾部残余片段
                                is Transcriber -> {
                                    if (maxAbs >= 0.003f) {
                                        hadSpeechSinceFlush = true
                                        silentSamples = 0L
                                    } else if (hadSpeechSinceFlush) {
                                        silentSamples += chunk.size
                                    }
                                    currentEngine.accept(chunk, onCue)
                                    if (hadSpeechSinceFlush && silentSamples >= WasapiLoopbackCapture.SAMPLE_RATE) {
                                        hadSpeechSinceFlush = false
                                        silentSamples = 0L
                                        currentEngine.finish(onCue)
                                    }
                                }

                                // 3. 云端 HTTP 分段识别（Whisper / OpenAI / 非流式）：
                                //    跳过纯静音避免幻觉，停顿时非阻塞 flushPending，绝不阻塞主采集协程等待慢请求
                                is ChunkedCloudEngine -> {
                                    if (maxAbs >= 0.003f) {
                                        if (!hadSpeechSinceFlush) {
                                            while (preSpeechBuffer.isNotEmpty()) {
                                                currentEngine.accept(preSpeechBuffer.removeFirst(), onCue)
                                            }
                                        }
                                        hadSpeechSinceFlush = true
                                        silentSamples = 0L
                                        currentEngine.accept(chunk, onCue)
                                    } else if (hadSpeechSinceFlush) {
                                        silentSamples += chunk.size
                                        currentEngine.accept(chunk, onCue)
                                        if (silentSamples >= WasapiLoopbackCapture.SAMPLE_RATE * 9L / 10L) {
                                            hadSpeechSinceFlush = false
                                            silentSamples = 0L
                                            currentEngine.flushPending(onCue)
                                        }
                                    } else {
                                        if (preSpeechBuffer.size >= 4) preSpeechBuffer.removeFirst()
                                        preSpeechBuffer.addLast(chunk)
                                        currentEngine.accept(emptySamples, onCue)
                                    }
                                }

                                else -> {
                                    currentEngine.accept(chunk, onCue)
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        runCatching { engine?.close() }
                        engine = null
                        val msg = e.message.orEmpty()
                        val isFatal = !sessionProducedOutput.get() && (
                            msg.contains("鉴权失败") || msg.contains("未填写") || msg.contains("没开通")
                        )
                        if (isFatal) {
                            _state.update { it.copy(status = "识别出错：$msg", running = false) }
                            break
                        }
                        android.util.Log.w("Voxtitle-LiveSub", "实时字幕识别中断，准备自动恢复: $msg")
                        _state.update { it.copy(status = "识别连接重连中…") }
                        delay(1000)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = "识别出错：${e.message}", running = false) }
            } finally {
                audioListener?.let { WasapiLoopbackCapture.removeListener(it) }
                audioListener = null
                runCatching { engine?.close() }
            }
        }

        startTranslationLoop(settings)
    }

    private fun startTranslationLoop(settings: AppSettings) {
        translateJob?.cancel()
        translateJob = null
        if (!settings.autoTranslate) return
        val translator = settings.createTranslator()?.also {
            (it as? ChatCompletionsTranslator)?.useLiveTimeout(18)
        } ?: return

        translateJob = scope.launch {
            val doneIds = LinkedHashSet<Int>()
            val attempts = LinkedHashMap<Int, Int>()
            var lastPreviewSource = ""

            fun markDone(id: Int) {
                doneIds += id
                while (doneIds.size > 800) {
                    val first = doneIds.iterator().let { it.next().also { _ -> it.remove() } }
                    attempts.remove(first)
                }
            }

            while (isActive) {
                delay(150)
                val st = _state.value
                val targetLocale = settings.ttsLocale
                val pending = st.history.filter {
                    it.translation == null && it.id !in doneIds && (attempts[it.id] ?: 0) < 2
                }.takeLast(3)

                if (pending.isEmpty()) {
                    // 长句定稿前预翻译：当流式临时文本满 6 字且停顿 550ms（或增长满 18 字），提前给出译文预览
                    val part = st.partialText?.trim().orEmpty()
                    val now = System.currentTimeMillis()
                    val shouldPreview = part.length >= 6 &&
                        part != lastPreviewSource &&
                        !LanguageCheck.alreadyTarget(part, targetLocale, settings.asrLanguage, ChineseScript::variant) &&
                        ((now - st.partialUpdatedAtMs) >= 550L || part.length - lastPreviewSource.length >= 18)
                    if (shouldPreview) {
                        lastPreviewSource = part
                        val ctx = st.history.takeLast(3).map { it.text }
                        runCatching {
                            val previewRes = withTimeout(8_000L) {
                                translator.translate(listOf(clipLivePartial(part, 80)), ctx, settings.targetLanguage)
                            }.firstOrNull()?.trim().orEmpty()
                            if (previewRes.isNotEmpty()) {
                                _state.update { cur ->
                                    if (cur.partialText != null && cur.partialText.startsWith(part.take(minOf(part.length, 6)))) {
                                        cur.copy(partialTranslation = previewRes)
                                    } else cur
                                }
                            }
                        }
                    } else if (part.isEmpty()) {
                        lastPreviewSource = ""
                    }
                    continue
                }

                for (c in pending) {
                    if (LanguageCheck.alreadyTarget(c.text, targetLocale, settings.asrLanguage, ChineseScript::variant)) {
                        markDone(c.id)
                        updateCueTranslation(c.id, c.text)
                    }
                }

                val toTranslate = pending.filter { it.id !in doneIds }
                if (toTranslate.isEmpty()) continue

                val contextLines = st.history
                    .filter { it.id < toTranslate.first().id }
                    .takeLast(4)
                    .map { it.text }

                runCatching {
                    val res = withTimeout(20_000L) {
                        translator.translate(toTranslate.map { it.text }, contextLines, settings.targetLanguage)
                    }
                    toTranslate.forEachIndexed { idx, cue ->
                        val trans = res.getOrNull(idx)?.takeIf { it.isNotBlank() }
                        if (trans != null) {
                            markDone(cue.id)
                            updateCueTranslation(cue.id, trans)
                        } else {
                            attempts[cue.id] = (attempts[cue.id] ?: 0) + 1
                        }
                    }
                }.onFailure { err ->
                    if (err is CancellationException && !isActive) throw err
                    toTranslate.forEach { cue ->
                        attempts[cue.id] = (attempts[cue.id] ?: 0) + 1
                    }
                    while (attempts.size > 800) {
                        val iter = attempts.entries.iterator()
                        if (iter.hasNext()) {
                            iter.next()
                            iter.remove()
                        }
                    }
                    delay(1000)
                }
            }
        }
    }

    private fun updateCueTranslation(id: Int, translation: String) {
        _state.update { st ->
            val updatedHistory = st.history.map { if (it.id == id) it.copy(translation = translation) else it }
            val updatedCurrent = if (st.currentCue?.id == id) st.currentCue.copy(translation = translation) else st.currentCue
            st.copy(history = updatedHistory, currentCue = updatedCurrent)
        }
    }

    fun restart(settings: AppSettings) {
        val wasRunning = _state.value.running
        stop()
        if (wasRunning) {
            start(settings)
        }
    }

    fun stop() {
        recognizeJob?.cancel()
        recognizeJob = null
        translateJob?.cancel()
        translateJob = null
        audioListener?.let { WasapiLoopbackCapture.removeListener(it) }
        audioListener = null
        _state.update { it.copy(running = false, partialText = null, partialTranslation = null, status = if (it.currentCue == null) "已暂停系统声音识别" else null) }
    }

    fun clear() {
        _state.update {
            it.copy(
                currentCue = null,
                partialText = null,
                partialTranslation = null,
                history = emptyList(),
                status = if (it.running) "正在监听系统声音…" else "点击开始捕获 Windows 系统声音并实时生成双语字幕",
            )
        }
    }

    private fun createEngine(s: AppSettings, onPartial: (String) -> Unit): SpeechEngine {
        if (s.streamingAvailable) {
            s.createStreamingEngine()?.let { streaming ->
                (streaming as? StreamingCloudEngine)?.apply {
                    liveStream = true
                    this.onPartial = onPartial
                }
                return streaming
            }
        }
        return if (s.asrEngine == AsrEngine.LOCAL) {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            Transcriber(
                model = ModelCatalog.byId(s.localModel),
                models = models,
                language = AsrLanguages.primary(s.asrLanguage),
                numThreads = threads,
                maxSpeechSeconds = 3.8f,
                minSilenceSeconds = 0.25f,
            )
        } else {
            s.createCloudEngine().also {
                (it as? ChunkedCloudEngine)?.apply {
                    useChunks(2, 3)
                    useLiveTimeout(25)
                }
            }
        }
    }
}

/**
 * 设置 Windows 窗口的鼠标穿透样式（WS_EX_TRANSPARENT | WS_EX_LAYERED）。
 */
private fun setWindowClickThrough(window: java.awt.Window, enabled: Boolean) {
    runCatching {
        val ptr: Pointer = Native.getComponentPointer(window) ?: return
        val hwnd = WinDef.HWND(ptr)
        val user32 = User32.INSTANCE
        val exStyle = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        val newStyle = if (enabled) {
            exStyle or WinUser.WS_EX_LAYERED or WinUser.WS_EX_TRANSPARENT
        } else {
            exStyle and WinUser.WS_EX_TRANSPARENT.inv()
        }
        user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, newStyle)
    }
}

private fun AppSettings.sameLiveConfigAs(other: AppSettings): Boolean =
    asrEngine == other.asrEngine &&
        asrLanguage == other.asrLanguage &&
        localModel == other.localModel &&
        streamingAsr == other.streamingAsr &&
        autoTranslate == other.autoTranslate &&
        provider == other.provider &&
        targetLanguage == other.targetLanguage

private fun clipLivePartial(text: String, maxChars: Int = 62): String {
    val clean = text.replace('\n', ' ').trim()
    return if (clean.length <= maxChars) clean else "…" + clean.takeLast(maxChars - 1)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FloatingSubtitleWindow(
    transcriber: DesktopLiveTranscriber,
    settingsStore: SettingsStore,
    onClose: () -> Unit,
) {
    val state by transcriber.state.collectAsState()
    var settings by remember { mutableStateOf(settingsStore.load()) }
    var fontSizeSp by remember { mutableFloatStateOf(22f) }
    var hovered by remember { mutableStateOf(false) }
    var langMenuOpen by remember { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val screenSize = remember { Toolkit.getDefaultToolkit().screenSize }
    val winWidth = 860.dp
    val winHeight = 165.dp
    val windowState = rememberWindowState(
        size = DpSize(winWidth, winHeight),
        position = WindowPosition(
            x = ((screenSize.width - 860) / 2).coerceAtLeast(40).dp,
            y = (screenSize.height - 240).coerceAtLeast(60).dp,
        ),
    )

    Window(
        onCloseRequest = {
            transcriber.stop()
            onClose()
        },
        state = windowState,
        title = "译幕 - 桌面全局字幕",
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        resizable = true,
    ) {
        LaunchedEffect(state.clickThrough) {
            setWindowClickThrough(window, state.clickThrough)
        }

        LaunchedEffect(Unit) {
            if (!state.running) {
                settings = settingsStore.load()
                transcriber.start(settings)
            }
            while (true) {
                delay(500)
                nowMs = System.currentTimeMillis()
            }
        }

        // 同步主窗口「设置」里的引擎、语言、翻译服务及液态玻璃开关变更
        LaunchedEffect(Unit) {
            while (true) {
                delay(1500)
                val latest = settingsStore.load()
                if (latest != settings) {
                    val needRestart = !latest.sameLiveConfigAs(settings) && transcriber.state.value.running
                    settings = latest
                    if (needRestart) {
                        transcriber.restart(latest)
                    }
                }
            }
        }

        val textShadow = Shadow(
            color = Color.Black.copy(alpha = 0.92f),
            blurRadius = 8f,
        )

        CompositionLocalProvider(LocalLiquidGlassEnabled provides settings.liquidGlass) {
        MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
        val windowShape = RoundedCornerShape(24.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { if (!langMenuOpen) hovered = false }
                .pointerInput(state.clickThrough) {
                    if (!state.clickThrough) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val loc = window.location
                            window.setLocation(
                                loc.x + dragAmount.x.roundToInt(),
                                loc.y + dragAmount.y.roundToInt(),
                            )
                        }
                    }
                }
                .let { mod ->
                    if (state.clickThrough) mod else mod.glassShadow(windowShape, GlassStyle.Floating, 14.dp)
                }
                .liquidGlass(
                    shape = windowShape,
                    style = GlassStyle.Floating,
                    tint = if (state.clickThrough) Color(0x99121722) else GlassTokens.DarkDockTint,
                    borderColor = if (state.clickThrough) Color.White.copy(alpha = 0.14f) else Color.Unspecified,
                    enableBackdrop = false,
                ),
        ) {
            // 字幕主显示区域
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val cue = state.currentCue
                val activePartial = state.partialText
                    ?.takeIf { it.isNotBlank() && (nowMs - state.partialUpdatedAtMs) <= 4_500L && it != cue?.text }
                val primaryOriginal = activePartial?.let { clipLivePartial(it) } ?: cue?.text

                if (primaryOriginal != null) {
                    val partialPreviewTrans = if (activePartial != null) state.partialTranslation?.takeIf { it.isNotBlank() } else null
                    val ownTrans = cue?.translation?.takeIf { it.isNotBlank() }
                    val fallbackPrevTrans = if (partialPreviewTrans == null && ownTrans == null && settings.autoTranslate && (nowMs - state.cueUpdatedAtMs) <= 2_800L) {
                        state.history.asReversed().firstOrNull { !it.translation.isNullOrBlank() }?.translation
                    } else null
                    val displayTrans = partialPreviewTrans ?: ownTrans ?: fallbackPrevTrans
                    val transAlpha = when {
                        partialPreviewTrans != null -> 0.85f
                        ownTrans != null && activePartial == null -> 1f
                        else -> 0.65f
                    }
                    if (displayTrans != null) {
                        Text(
                            text = displayTrans,
                            color = Color(0xFFFFE082).copy(alpha = transAlpha),
                            fontSize = fontSizeSp.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(shadow = textShadow),
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = primaryOriginal,
                        color = if (activePartial != null) Color.White.copy(alpha = 0.92f) else Color.White,
                        fontSize = (if (displayTrans != null) (fontSizeSp * 0.82f) else fontSizeSp).sp,
                        fontWeight = if (displayTrans != null) FontWeight.Medium else FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(shadow = textShadow),
                    )
                    val statusHint = state.status?.takeIf { it.isNotBlank() && !it.startsWith("正在监听") }
                    if (statusHint != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = statusHint,
                            color = Color(0xFFFFB74D),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(shadow = textShadow),
                        )
                    }
                } else {
                    Text(
                        text = state.status ?: "正在监听 Windows 系统声音…",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        style = TextStyle(shadow = textShadow),
                    )
                }
            }

            // 顶部悬浮控制栏（鼠标移入且未锁定穿透时显示）
            AnimatedVisibility(
                visible = (hovered || !state.running) && !state.clickThrough,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0x990C1018), Color.Transparent)))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val chipShape = RoundedCornerShape(50)
                        // 开始 / 暂停按钮
                        Box(
                            modifier = Modifier
                                .liquidGlass(
                                    shape = chipShape,
                                    style = GlassStyle.Pill,
                                    tint = if (state.running) Color(0xCC2E7D32) else GlassTokens.DarkDropletTint,
                                    enableBackdrop = false,
                                )
                                .clickable {
                                    settings = settingsStore.load()
                                    if (state.running) transcriber.stop() else transcriber.start(settings)
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (state.running) AppIcons.Pause else AppIcons.PlayArrow,
                                    contentDescription = if (state.running) "暂停识别" else "开始识别",
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (state.running) "监听中" else "已暂停",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                )
                            }
                        }

                        // 识别语言快速切换
                        Box {
                            val currentLangLabel = AsrLanguages.COMMON.firstOrNull { it.first == settings.asrLanguage }?.second
                                ?: settings.asrLanguage
                            Box(
                                modifier = Modifier
                                    .liquidGlass(
                                        shape = chipShape,
                                        style = GlassStyle.Pill,
                                        tint = GlassTokens.DarkDropletTint,
                                        enableBackdrop = false,
                                    )
                                    .clickable { langMenuOpen = true }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = "源语言: $currentLangLabel",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                )
                            }
                            AppMenu(
                                expanded = langMenuOpen,
                                onDismissRequest = { langMenuOpen = false },
                            ) {
                                for ((code, name) in AsrLanguages.COMMON.take(10)) {
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            langMenuOpen = false
                                            val updated = settingsStore.load().copy(asrLanguage = code)
                                            settings = updated
                                            settingsStore.save(updated)
                                            transcriber.restart(updated)
                                        },
                                    )
                                }
                            }
                        }

                        // 自动翻译开关
                        Box(
                            modifier = Modifier
                                .liquidGlass(
                                    shape = chipShape,
                                    style = GlassStyle.Pill,
                                    tint = if (settings.autoTranslate) GlassTokens.DarkActiveBlue.copy(alpha = 0.72f) else GlassTokens.DarkDropletTint,
                                    enableBackdrop = false,
                                )
                                .clickable {
                                    val updated = settingsStore.load().let { it.copy(autoTranslate = !it.autoTranslate) }
                                    settings = updated
                                    settingsStore.save(updated)
                                    transcriber.restart(updated)
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = if (settings.autoTranslate) "译: ${settings.targetLanguage}" else "仅原文",
                                color = Color.White,
                                fontSize = 12.sp,
                            )
                        }

                        // 实时语音→上屏延迟指示器
                        state.latencyMs?.takeIf { state.running }?.let { lat ->
                            Text(
                                text = "延迟 ~%.1fs".format(lat / 1000f),
                                color = if (lat <= 1_200L) Color(0xFFA5D6A7) else Color(0xFFFFE082),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 2.dp),
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        // 字号缩小 / 放大
                        Text(
                            text = "A-",
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { fontSizeSp = (fontSizeSp - 2f).coerceAtLeast(14f) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                        Text(
                            text = "A+",
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { fontSizeSp = (fontSizeSp + 2f).coerceAtMost(42f) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )

                        // 鼠标穿透锁定按钮
                        Text(
                            text = "穿透",
                            color = Color(0xFF90CAF9),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { transcriber.setClickThrough(true) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )

                        // 导出 SRT 字幕
                        if (state.history.isNotEmpty()) {
                            Text(
                                text = "导出SRT",
                                color = Color(0xFFA5D6A7),
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        val dlg = FileDialog(null as Frame?, "导出全局字幕 (.srt)", FileDialog.SAVE)
                                        dlg.file = "桌面字幕.srt"
                                        dlg.isVisible = true
                                        val dir = dlg.directory
                                        val file = dlg.file
                                        if (dir != null && file != null) {
                                            runCatching {
                                                File(dir, file).outputStream().use { fos ->
                                                    com.aisub.player.subtitle.writeSrt(state.history, com.aisub.player.subtitle.SrtMode.BILINGUAL, fos)
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }

                        // 清空字幕
                        IconButton(
                            onClick = { transcriber.clear() },
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                imageVector = AppIcons.Delete,
                                contentDescription = "清空",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(15.dp),
                            )
                        }

                        // 关闭悬浮窗
                        IconButton(
                            onClick = {
                                transcriber.stop()
                                onClose()
                            },
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                imageVector = AppIcons.Close,
                                contentDescription = "关闭全局字幕",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
        }
        }
    }
}
