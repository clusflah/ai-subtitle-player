package com.aisub.player.desktop

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
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
import com.aisub.player.asrProblem
import com.aisub.player.createCloudEngine
import com.aisub.player.createStreamingEngine
import com.aisub.player.streamingAvailable
import com.aisub.player.subtitle.Cue
import com.aisub.player.translate.ChineseScript
import com.aisub.player.translate.LanguageCheck
import com.aisub.player.ui.AppIcons
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
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class FloatingSubState(
    val running: Boolean = false,
    val status: String? = "点击开始捕获 Windows 系统声音并实时生成双语字幕",
    val currentCue: Cue? = null,
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
    private var nextId = 1

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
                status = "正在启动 Windows 系统声音捕获…",
            )
        }

        val listener: (FloatArray) -> Unit = { samples ->
            while (audioQueue.size >= 25) {
                audioQueue.poll()
            }
            audioQueue.offer(samples)
        }
        audioListener = listener
        WasapiLoopbackCapture.addListener(listener)

        recognizeJob = scope.launch {
            var engine: SpeechEngine? = null
            val emptySamples = FloatArray(0)
            val onCue: (Cue) -> Unit = { rawCue ->
                val text = rawCue.text.trim()
                if (text.isNotEmpty()) {
                    val cue = Cue(
                        id = nextId++,
                        startMs = rawCue.startMs.coerceAtLeast(0L),
                        endMs = maxOf(rawCue.endMs, rawCue.startMs + 1500L),
                        text = text,
                        translation = rawCue.translation,
                    )
                    _state.update { st ->
                        val last = st.history.lastOrNull()
                        val updatedHistory = if (last != null && last.text == cue.text) {
                            st.history
                        } else {
                            (st.history + cue).takeLast(500)
                        }
                        st.copy(
                            status = null,
                            currentCue = if (last != null && last.text == cue.text) last else cue,
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
                    try {
                        engine = createEngine(settings)
                        _state.update { st ->
                            st.copy(status = if (st.currentCue == null) "正在监听系统声音…（播放任意视频或音频即出字幕）" else null)
                        }

                        var hadSpeechSinceFlush = false
                        var silentSamples = 0L
                        val preSpeechBuffer = ArrayDeque<FloatArray>(4)

                        while (isActive) {
                            val chunk = audioQueue.poll(120, TimeUnit.MILLISECONDS)
                            if (chunk == null) {
                                engine.accept(emptySamples, onCue)
                                continue
                            }
                            var maxAbs = 0f
                            for (v in chunk) {
                                val a = kotlin.math.abs(v)
                                if (a > maxAbs) maxAbs = a
                            }
                            if (maxAbs >= 0.003f) {
                                if (!hadSpeechSinceFlush) {
                                    while (preSpeechBuffer.isNotEmpty()) {
                                        engine.accept(preSpeechBuffer.removeFirst(), onCue)
                                    }
                                }
                                hadSpeechSinceFlush = true
                                silentSamples = 0L
                                engine.accept(chunk, onCue)
                            } else {
                                if (hadSpeechSinceFlush) {
                                    silentSamples += chunk.size
                                    engine.accept(chunk, onCue)
                                    if (silentSamples >= WasapiLoopbackCapture.SAMPLE_RATE * 2L) {
                                        hadSpeechSinceFlush = false
                                        silentSamples = 0L
                                        engine.finish(onCue)
                                    }
                                } else {
                                    if (preSpeechBuffer.size >= 3) preSpeechBuffer.removeFirst()
                                    preSpeechBuffer.addLast(chunk)
                                    engine.accept(emptySamples, onCue)
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        runCatching { engine?.close() }
                        engine = null
                        val msg = e.message.orEmpty()
                        val isFatal = msg.contains("鉴权失败") || msg.contains("未填写") || msg.contains("API Key")
                        if (isFatal) {
                            _state.update { it.copy(status = "识别出错：$msg", running = false) }
                            break
                        }
                        android.util.Log.w("Voxtitle-LiveSub", "实时字幕识别中断，准备自动恢复: $msg")
                        _state.update { it.copy(status = "识别连接重连中…") }
                        delay(1200)
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
        val translator = settings.createTranslator() ?: return
        translateJob = scope.launch {
            val translatedIds = HashSet<Int>()
            while (isActive) {
                delay(200)
                val st = _state.value
                val targetLocale = settings.ttsLocale
                val pending = st.history.filter {
                    it.translation == null && it.id !in translatedIds
                }.takeLast(3)
                if (pending.isEmpty()) continue

                for (c in pending) {
                    if (LanguageCheck.alreadyTarget(c.text, targetLocale, settings.asrLanguage, ChineseScript::variant)) {
                        translatedIds += c.id
                        updateCueTranslation(c.id, c.text)
                    }
                }

                val toTranslate = pending.filter { it.id !in translatedIds }
                if (toTranslate.isEmpty()) continue
                toTranslate.forEach { translatedIds += it.id }

                val contextLines = st.history
                    .filter { it.id < toTranslate.first().id }
                    .takeLast(4)
                    .map { it.text }

                runCatching {
                    val res = translator.translate(toTranslate.map { it.text }, contextLines, settings.targetLanguage)
                    toTranslate.forEachIndexed { idx, cue ->
                        val trans = res.getOrNull(idx)?.takeIf { it.isNotBlank() }
                        if (trans != null) {
                            updateCueTranslation(cue.id, trans)
                        } else {
                            translatedIds.remove(cue.id)
                        }
                    }
                }.onFailure {
                    toTranslate.forEach { translatedIds.remove(it.id) }
                    delay(1500)
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
        _state.update { it.copy(running = false, status = if (it.currentCue == null) "已暂停系统声音识别" else null) }
    }

    fun clear() {
        _state.update { it.copy(currentCue = null, history = emptyList(), status = if (it.running) "正在监听系统声音…" else "点击开始捕获 Windows 系统声音并实时生成双语字幕") }
    }

    private fun createEngine(s: AppSettings): SpeechEngine {
        if (s.streamingAvailable) {
            s.createStreamingEngine()?.let { return it }
        }
        return if (s.asrEngine == AsrEngine.LOCAL) {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            Transcriber(
                model = ModelCatalog.byId(s.localModel),
                models = models,
                language = AsrLanguages.primary(s.asrLanguage),
                numThreads = threads,
                maxSpeechSeconds = 4.5f,
            )
        } else {
            s.createCloudEngine().also {
                (it as? com.aisub.player.asr.cloud.ChunkedCloudEngine)?.useChunks(2, 3)
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
        }

        val textShadow = Shadow(
            color = Color.Black.copy(alpha = 0.92f),
            blurRadius = 8f,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
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
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xCC161822),
                            Color(0xE60E1017),
                        ),
                    ),
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (state.clickThrough) 0.12f else 0.32f),
                            Color.White.copy(alpha = 0.08f),
                        ),
                    ),
                    shape = RoundedCornerShape(22.dp),
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
                if (cue != null) {
                    val trans = cue.translation?.takeIf { it.isNotBlank() }
                    if (trans != null) {
                        Text(
                            text = trans,
                            color = Color(0xFFFFE082),
                            fontSize = fontSizeSp.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            style = TextStyle(shadow = textShadow),
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = cue.text,
                        color = Color.White,
                        fontSize = (if (trans != null) (fontSizeSp * 0.82f) else fontSizeSp).sp,
                        fontWeight = if (trans != null) FontWeight.Medium else FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
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
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 开始 / 暂停按钮
                        Surface(
                            shape = CircleShape,
                            color = if (state.running) Color(0xFF2E7D32) else Color(0xFF424242),
                            modifier = Modifier.clickable {
                                settings = settingsStore.load()
                                if (state.running) transcriber.stop() else transcriber.start(settings)
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
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
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.14f),
                                modifier = Modifier.clickable { langMenuOpen = true },
                            ) {
                                Text(
                                    text = "源语言: $currentLangLabel",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                            DropdownMenu(
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
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (settings.autoTranslate) Color(0xFF1565C0) else Color.White.copy(alpha = 0.14f),
                            modifier = Modifier.clickable {
                                val updated = settingsStore.load().let { it.copy(autoTranslate = !it.autoTranslate) }
                                settings = updated
                                settingsStore.save(updated)
                                transcriber.restart(updated)
                            },
                        ) {
                            Text(
                                text = if (settings.autoTranslate) "译: ${settings.targetLanguage}" else "仅原文",
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
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
