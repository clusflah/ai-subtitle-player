package com.aisub.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.aisub.player.media.AppStore
import com.aisub.player.media.MediaEntry
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.cloud.CloudRegistry
import com.aisub.player.asr.AudioDecoder
import com.aisub.player.asr.AzureTranscriber
import com.aisub.player.asr.ModelManager
import com.aisub.player.asr.SpeechEngine
import com.aisub.player.asr.Transcriber
import com.aisub.player.subtitle.Cue
import com.aisub.player.subtitle.SrtMode
import com.aisub.player.subtitle.SubtitleStore
import com.aisub.player.subtitle.writeSrt
import com.aisub.player.translate.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val current: MediaEntry? = null,
    val videoUri: Uri? = null,
    val cues: List<Cue> = emptyList(),
    val transcribing: Boolean = false,
    val translating: Boolean = false,
    val status: String = "",
    /** 0..1；-1 表示进度不确定；null 表示不显示进度条 */
    val progress: Float? = null,
    val error: String? = null,
    val settings: AppSettings = AppSettings(),
)

@OptIn(UnstableApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(DefaultMediaSourceFactory(authDataSourceFactory(app)))
        .build()

    private val settingsRepo = SettingsRepository(app)
    private val store = SubtitleStore(app)
    private val models = ModelManager(app)
    private val library = AppStore(app)

    private val _state = MutableStateFlow(UiState(settings = settingsRepo.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var transcribeJob: Job? = null
    private var translateJob: Job? = null
    private var queue: List<MediaEntry> = emptyList()

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                queue.getOrNull(player.currentMediaItemIndex)?.let(::onEntryChanged)
            }
        })
    }

    /** 播放一组媒体，从 index 开始；上次看到一半的会自动续播。 */
    fun play(entries: List<MediaEntry>, index: Int) {
        if (entries.isEmpty()) return
        savePosition()
        queue = entries
        val start = entries[index.coerceIn(entries.indices)]
        val items = entries.map {
            MediaItem.Builder()
                .setUri(it.uri)
                .setMediaId(it.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).build())
                .build()
        }
        val resume = library.positionOf(start.uri)
            .takeIf { start.durationMs <= 0 || it < start.durationMs - 10_000 } ?: 0L
        player.setMediaItems(items, index.coerceIn(entries.indices), resume)
        player.prepare()
        player.play()
        onEntryChanged(start)
    }

    private fun onEntryChanged(entry: MediaEntry) {
        if (_state.value.current?.uri == entry.uri) return
        cancelAll()
        val uri = Uri.parse(entry.uri)
        val cached = store.load(uri)
        _state.update {
            it.copy(
                current = entry,
                videoUri = uri,
                cues = cached,
                status = if (cached.isEmpty()) "点击「生成字幕」开始识别" else "已加载缓存字幕 ${cached.size} 条",
                progress = null,
                error = null,
            )
        }
        library.recordHistory(entry, 0)
    }

    /** 记录当前播放进度，离开播放页或切换视频时调用。 */
    fun savePosition() {
        val entry = _state.value.current ?: return
        if (player.mediaItemCount == 0) return
        val duration = player.duration.takeIf { it > 0 } ?: entry.durationMs
        library.recordHistory(entry.copy(durationMs = duration), player.currentPosition)
    }

    fun updateSettings(s: AppSettings) {
        settingsRepo.save(s)
        _state.update { it.copy(settings = s) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** 识别整段音轨。边识别边出字幕，开启自动翻译时同时翻译。 */
    fun generateSubtitles() {
        val uri = _state.value.videoUri ?: return
        if (transcribeJob?.isActive == true) return
        translateJob?.cancel()
        _state.update { it.copy(cues = emptyList(), transcribing = true, error = null) }

        transcribeJob = viewModelScope.launch {
            try {
                val settings = _state.value.settings
                settings.asrProblem()?.let { throw IllegalStateException(it) }
                if (settings.asrEngine == AsrEngine.LOCAL && !models.isReady()) {
                    models.ensureModels(settings.modelBaseUrl) { msg, p ->
                        _state.update { it.copy(status = msg, progress = p) }
                    }
                }
                val engineName = settings.asrEngine.label
                _state.update { it.copy(status = "正在识别语音（$engineName）…", progress = 0f) }
                if (settings.autoTranslate && settings.createTranslator() != null) startTranslation()

                // Azure 引擎大部分时间在等网络，放到 IO 线程池
                val dispatcher = if (settings.asrEngine == AsrEngine.AZURE) Dispatchers.IO else Dispatchers.Default
                withContext(dispatcher) {
                    createEngine(settings).use { engine ->
                        // 协程取消时中断引擎里正在进行的网络请求
                        val cancelHandle = coroutineContext.job.invokeOnCompletion { engine.cancel() }
                        try {
                            val onCue: (Cue) -> Unit = { cue -> _state.update { it.copy(cues = it.cues + cue) } }
                            AudioDecoder(getApplication(), uri).decode(
                                isActive = { isActive },
                                onProgress = { p -> _state.update { it.copy(progress = p) } },
                                onChunk = { chunk -> engine.accept(chunk, onCue) },
                            )
                            if (isActive) engine.finish(onCue)
                        } finally {
                            cancelHandle.dispose()
                        }
                    }
                }
                ensureActive()
                val count = _state.value.cues.size
                _state.update { it.copy(status = "识别完成，共 $count 条字幕", progress = null) }
                store.save(uri, _state.value.cues)
            } catch (e: CancellationException) {
                _state.update { it.copy(status = "已取消", progress = null) }
                throw e
            } catch (e: Exception) {
                // 取消时被中断的网络请求会抛 IOException，不算失败
                if (!isActive) {
                    _state.update { it.copy(status = "已取消", progress = null) }
                    throw CancellationException("cancelled")
                }
                _state.update { it.copy(status = "识别失败", progress = null, error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(transcribing = false) }
            }
        }
    }

    private fun createEngine(settings: AppSettings): SpeechEngine = when (settings.asrEngine) {
        AsrEngine.LOCAL -> {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            Transcriber(models.paths, settings.asrLanguage, threads)
        }
        AsrEngine.AZURE -> AzureTranscriber(
            endpoint = settings.azureEndpointOrDefault(),
            apiKey = settings.azureKey.trim(),
            locales = AzureTranscriber.localesFor(settings.asrLanguage),
        )
    }

    /** 翻译所有还没有译文的字幕。识别进行中时会持续等待新字幕。 */
    fun startTranslation() {
        if (translateJob?.isActive == true) return
        val settings = _state.value.settings
        val translator = settings.createTranslator()
        if (translator == null) {
            _state.update { it.copy(error = settings.translatorProblem() ?: "翻译服务配置不完整") }
            return
        }
        _state.update { it.copy(translating = true) }
        translateJob = viewModelScope.launch {
            try {
                translateLoop(translator)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "翻译失败：${e.message}") }
            } finally {
                _state.update { it.copy(translating = false) }
                _state.value.videoUri?.let { store.save(it, _state.value.cues) }
            }
        }
    }

    private suspend fun translateLoop(translator: Translator) {
        while (true) {
            val snapshot = _state.value
            val firstPending = snapshot.cues.indexOfFirst { it.translation == null }
            if (firstPending < 0) {
                if (snapshot.transcribing) {
                    delay(1000)
                    continue
                }
                return
            }
            val batch = snapshot.cues.drop(firstPending).takeWhile { it.translation == null }.take(BATCH_SIZE)
            // 识别还在进行时攒够一批再翻，翻译质量更好、请求也更少
            if (snapshot.transcribing && batch.size < BATCH_SIZE) {
                delay(1000)
                continue
            }
            val context = snapshot.cues.subList(maxOf(0, firstPending - CONTEXT_SIZE), firstPending).map { it.text }
            val results = translator.translate(batch.map { it.text }, context, snapshot.settings.targetLanguage)

            // 模型漏掉的条目用原文占位，避免死循环反复请求
            val translated = batch.indices.associate { i -> batch[i].id to (results[i] ?: batch[i].text) }
            _state.update { s ->
                s.copy(cues = s.cues.map { c -> translated[c.id]?.let { c.copy(translation = it) } ?: c })
            }
            val done = _state.value.cues.count { it.translation != null }
            if (!_state.value.transcribing) {
                _state.update { it.copy(status = "翻译中 $done/${it.cues.size}") }
            }
        }
    }

    fun cancelAll() {
        transcribeJob?.cancel()
        translateJob?.cancel()
    }

    fun clearTranslations() {
        translateJob?.cancel()
        _state.update { s -> s.copy(cues = s.cues.map { it.copy(translation = null) }) }
        _state.value.videoUri?.let { store.save(it, _state.value.cues) }
    }

    fun exportSrt(target: Uri, mode: SrtMode) {
        val cues = _state.value.cues
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openOutputStream(target)!!.use { writeSrt(cues, mode, it) }
                _state.update { it.copy(status = "已导出 ${cues.size} 条字幕") }
            } catch (e: Exception) {
                _state.update { it.copy(error = "导出失败：${e.message}") }
            }
        }
    }

    override fun onCleared() {
        savePosition()
        player.release()
    }

    companion object {
        private const val BATCH_SIZE = 20
        private const val CONTEXT_SIZE = 6
    }
}

/** 给已登记的 WebDAV 地址加上鉴权头，其他地址原样访问。 */
@UnstableApi
private fun authDataSourceFactory(app: Application): DataSource.Factory {
    val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
    return ResolvingDataSource.Factory(DefaultDataSource.Factory(app, http)) { spec ->
        val uri = spec.uri.toString()
        if (CloudRegistry.isCloud(uri)) {
            // 网盘：在加载线程里把 cloud:// 换成临时直链（会联网，结果有缓存）
            val link = CloudRegistry.resolve(uri)
            spec.withUri(Uri.parse(link.url)).withAdditionalHeaders(link.headers)
        } else {
            val headers = AuthRegistry.headersFor(uri)
            if (headers.isEmpty()) spec else spec.withAdditionalHeaders(headers)
        }
    }
}
