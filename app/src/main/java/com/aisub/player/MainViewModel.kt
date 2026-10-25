package com.aisub.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import com.aisub.player.media.AppStore
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.MediaProxy
import com.aisub.player.media.VlcPlayer
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.cloud.CloudRegistry
import com.aisub.player.asr.AudioDecoder
import com.aisub.player.asr.LocalModel
import com.aisub.player.asr.ModelCatalog
import com.aisub.player.asr.ModelManager
import com.aisub.player.asr.SpeechEngine
import com.aisub.player.asr.Transcriber
import com.aisub.player.subtitle.Cue
import com.aisub.player.download.Downloaders
import com.aisub.player.download.LinkDownloader
import com.aisub.player.download.LinkProgress
import com.aisub.player.download.LinkStage
import com.aisub.player.download.PlatformSubtitles
import com.aisub.player.media.MediaLibrary
import com.aisub.player.subtitle.EmbeddedSubtitles
import com.aisub.player.subtitle.SidecarSrt
import com.aisub.player.subtitle.SrtMode
import com.aisub.player.subtitle.SubtitleParser
import com.aisub.player.subtitle.SubtitleStore
import com.aisub.player.subtitle.OnlineSubtitle
import com.aisub.player.subtitle.OnlineSubtitleSource
import com.aisub.player.subtitle.OnlineSubtitles
import com.aisub.player.subtitle.decodeText
import com.aisub.player.subtitle.writeSrt
import com.aisub.player.translate.ChineseScript
import com.aisub.player.translate.LanguageCheck
import com.aisub.player.translate.Translator
import com.aisub.player.subtitle.Role
import com.aisub.player.tts.AzureTts
import com.aisub.player.tts.RoleDetector
import com.aisub.player.tts.SystemTts
import com.aisub.player.tts.TtsEngine
import com.aisub.player.tts.TtsEngineType
import com.aisub.player.tts.TtsReader
import com.aisub.player.tts.TtsSnapshot
import com.aisub.player.tts.TtsVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** 最近失败的任务，用于在播放页显示"失败，点击重试" */
    val failedTask: Task? = null,
    /** 字幕延迟（毫秒，正数表示字幕推后出现） */
    val subtitleDelayMs: Long = 0,
    /** 睡眠计时器结束时间（System.currentTimeMillis），-1 表示本集结束，null 表示未开启 */
    val sleepAt: Long? = null,
    /** A-B 循环的 A、B 点（毫秒） */
    val loopA: Long? = null,
    val loopB: Long? = null,
    /** 播放失败的原因；null 表示正常 */
    val playbackError: String? = null,
    val settings: AppSettings = AppSettings(),
    /** 当前视频识别出的角色（朗读时按角色分配语音） */
    val roles: List<Role> = emptyList(),
    /** 正在识别角色时的进度说明；null 表示没在识别 */
    val roleStatus: String? = null,
    /** 正在读取视频自带的字幕轨 */
    val extractingEmbedded: Boolean = false,
    /** 读出的字幕轨（多条时让用户选），null 表示还没读 */
    val embeddedTracks: List<EmbeddedSubtitles.Track>? = null,
    /** 有多条字幕轨，等用户选择 */
    val choosingEmbedded: Boolean = false,
    /** 下载版：正在从分享的链接下载的任务 */
    val linkTask: LinkTask? = null,
    /** 在播放页给当前视频单独选的语音语言；null 表示用设置里的默认语言 */
    val videoAsrLanguage: String? = null,
) {
    /** 当前视频识别时用的设置：语音语言换成这个视频单独选的 */
    val asrSettings: AppSettings get() = videoAsrLanguage?.let { settings.copy(asrLanguage = it) } ?: settings
}

/** 链接下载任务的状态。done 时界面跳到播放页。 */
data class LinkTask(
    val url: String,
    val title: String? = null,
    val progress: LinkProgress = LinkProgress(LinkStage.INFO, null),
    val error: String? = null,
    val done: Boolean = false,
)

enum class Task { TRANSCRIBE, TRANSLATE }

@OptIn(UnstableApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    /** 视频自带 ASS / SSA 字幕的 libass 渲染；播放页创建画面时把它的图层加到字幕视图上 */
    val assHandler = AssHandler(AssRenderType.OVERLAY_OPEN_GL)

    /** 默认播放内核 */
    val exoPlayer: ExoPlayer = run {
        val assParsers = AssSubtitleParserFactory(assHandler)
        val sources = DefaultMediaSourceFactory(authDataSourceFactory(app), DefaultExtractorsFactory().withAssMkvSupport(assParsers, assHandler))
        sources.setSubtitleParserFactory(assParsers)
        ExoPlayer.Builder(app)
            .setRenderersFactory(renderersFactory(app).withAssSupport(assHandler))
            .setMediaSourceFactory(sources)
            .build()
            .also(assHandler::init)
    }

    /** VLC 内核：ISO 光盘镜像，或用户在 ExoPlayer 播放失败后手动切换。用到时才创建。 */
    private var vlcPlayer: VlcPlayer? = null
    private val proxy by lazy { MediaProxy(authDataSourceFactory(app)) }

    private val _player = MutableStateFlow<Player>(exoPlayer)
    /** 当前使用的播放内核，切换时播放页会重新绑定画面 */
    val playerFlow: StateFlow<Player> = _player.asStateFlow()
    val player: Player get() = _player.value
    val usingVlc: Boolean get() = player !== exoPlayer

    private val settingsRepo = SettingsRepository(app)
    private val store = SubtitleStore(app)
    private val models = ModelManager(app)
    private val library = AppStore(app)

    private val _state = MutableStateFlow(UiState(settings = settingsRepo.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var transcribeJob: Job? = null
    private var translateJob: Job? = null
    private var roleJob: Job? = null
    private var embeddedJob: Job? = null
    private var linkJob: Job? = null

    /** 下载版才有；标准版为 null */
    val linkDownloader: LinkDownloader? = Downloaders.create(app)

    /** 下载版：yt-dlp 用的登录 cookies */
    val cookieJar = com.aisub.player.download.CookieJar(app)

    // ---- 朗读 ----
    private val ttsEngines = HashMap<String, TtsEngine>()
    private val voiceCache = HashMap<String, List<TtsVoice>>()
    private var previewPlayer: android.media.MediaPlayer? = null
    private val ttsReader = TtsReader(
        app, viewModelScope,
        player = { player },
        snapshot = {
            val s = _state.value
            TtsSnapshot(
                s.cues, s.roles, s.subtitleDelayMs, s.settings.ttsEngine, s.settings.ttsVoices[s.settings.ttsEngine],
                s.settings.ttsLocale, s.settings.ttsDuckVolume,
            )
        },
        engineFor = ::ttsEngine,
        onError = { msg -> _state.update { it.copy(status = "朗读失败：$msg") } },
    )
    private var queue: List<MediaEntry> = emptyList()

    /** 进度条缩略图、章节、片头片尾、轨道记忆、逐帧与书签 */
    val extras = PlaybackFeatures(
        app, viewModelScope, exoPlayer,
        player = { player },
        settings = { _state.value.settings },
        queue = { queue },
        hasLoadedSubtitles = { _state.value.cues.isNotEmpty() },
    )

    /** 音乐的歌词与封面 */
    val music = com.aisub.player.music.MusicFeatures(app, viewModelScope, player = { player }, settings = { _state.value.settings })

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!usingVlc) queue.getOrNull(exoPlayer.currentMediaItemIndex)?.let(::onEntryChanged)
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            extras.onTracksChanged(tracks)
            music.onTracksChanged(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.e("Voxtitle", "播放失败", error)
            if (retryLiveAsOtherType(error)) return
            _state.update { it.copy(playbackError = describePlaybackError(error)) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) _state.update { it.copy(playbackError = null) }
        }
    }

    init {
        if (_state.value.settings.ttsEnabled) ttsReader.start()
        exoPlayer.addListener(listener)
        // 内嵌字幕默认不显示（避免和 AI 字幕重叠），除非设置了首选字幕语言
        extras.applyTrackDefaults()
    }

    private fun vlc(): VlcPlayer = vlcPlayer ?: VlcPlayer(getApplication(), proxy).also {
        it.addListener(listener)
        vlcPlayer = it
    }

    private fun itemOf(e: MediaEntry) = MediaItem.Builder()
        .setUri(e.uri)
        .setMediaId(e.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(e.title).build())
        .apply { if (e.isLive) setMimeType(liveMimeFor(e.uri)) }
        .build()

    /**
     * 直播流的类型。很多直播源的地址是 live.php?id=xxx 这种，看不出是 HLS，
     * 播放器会当成普通视频文件解析失败。地址看不出类型时先当 HLS；打不开再换成普通流重试一次（见 [retryLiveAsOtherType]）。
     */
    private val liveMime = HashMap<String, String?>()
    private val liveRetried = HashSet<String>()

    private fun liveMimeFor(uri: String): String? = liveMime.getOrPut(uri) {
        val u = Uri.parse(uri)
        val ext = u.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        when {
            u.scheme == "rtsp" || u.scheme == "rtmp" -> null
            androidx.media3.common.util.Util.inferContentType(u) != C.CONTENT_TYPE_OTHER -> null // m3u8 / mpd 交给播放器自己判断
            ext in setOf("flv", "ts", "mp4", "mkv", "mp3", "aac", "m4a", "webm", "mov") -> null
            else -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
        }
    }

    /** 直播打不开且是解析错误：换一种类型（HLS ↔ 普通流）重试一次。返回是否已重试。 */
    private fun retryLiveAsOtherType(error: PlaybackException): Boolean {
        val entry = _state.value.current?.takeIf { it.isLive } ?: return false
        if (usingVlc || entry.uri in liveRetried) return false
        val parsing = error.errorCode in setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
        if (!parsing) return false
        liveRetried += entry.uri
        liveMime[entry.uri] = if (liveMimeFor(entry.uri) == null) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null
        val index = exoPlayer.currentMediaItemIndex
        android.util.Log.i("Voxtitle", "直播换类型重试：${entry.uri} → ${liveMime[entry.uri] ?: "普通流"}")
        exoPlayer.replaceMediaItem(index, itemOf(entry))
        exoPlayer.prepare()
        exoPlayer.play()
        return true
    }

    /** 用 VLC 内核播放一个文件（不带播放队列）。 */
    private fun playWithVlc(entry: MediaEntry, positionMs: Long) {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        queue = listOf(entry)
        val v = vlc()
        v.setMediaItem(itemOf(entry), positionMs)
        _state.update { it.copy(playbackError = null) }
        v.prepare()
        v.play()
        _player.value = v
        onEntryChanged(entry)
    }

    /** ExoPlayer 播放失败时，从当前位置改用 VLC 内核重试。 */
    fun switchToVlc() {
        val entry = _state.value.current ?: return
        playWithVlc(entry, exoPlayer.currentPosition.coerceAtLeast(0))
    }

    /**
     * 播放/暂停。出错后（播放器处于空闲状态）重新加载并从当前位置继续；播完后从头播放。
     * 以 playWhenReady 判断而不是 isPlaying：缓冲中 isPlaying 为 false，按"播放"不会有任何效果。
     */
    fun togglePlay() {
        when (player.playbackState) {
            Player.STATE_IDLE -> {
                _state.update { it.copy(playbackError = null) }
                player.prepare()
                player.play()
            }
            Player.STATE_ENDED -> {
                player.seekTo(player.currentMediaItemIndex, 0)
                player.play()
            }
            else -> if (player.playWhenReady) player.pause() else player.play()
        }
    }

    /** 播放一组媒体，从 index 开始；上次看到一半的会自动续播。 */
    fun play(entries: List<MediaEntry>, index: Int) {
        if (entries.isEmpty()) return
        savePosition()
        val start = entries[index.coerceIn(entries.indices)]
        // 直播没有"上次看到哪"，总是从最新画面开始
        val resume = if (start.isLive) 0L else library.positionOf(start.uri)
            .takeIf { start.durationMs <= 0 || it < start.durationMs - 10_000 } ?: 0L
        // 光盘镜像只能用 VLC 内核播放
        if (VlcPlayer.needsVlc(start.title)) {
            playWithVlc(start, resume)
            return
        }
        vlcPlayer?.stop()
        _player.value = exoPlayer
        // 连播队列里跳过光盘镜像，ExoPlayer 打不开
        queue = entries.filter { it == start || !VlcPlayer.needsVlc(it.title) }
        exoPlayer.setMediaItems(queue.map(::itemOf), queue.indexOf(start), resume)
        _state.update { it.copy(playbackError = null) }
        exoPlayer.prepare()
        exoPlayer.play()
        onEntryChanged(start)
    }

    private fun onEntryChanged(entry: MediaEntry) {
        if (_state.value.current?.uri == entry.uri) return
        cancelAll()
        val uri = Uri.parse(entry.uri)
        val videoLanguage = store.loadAsrLanguage(uri)
        // 旧版把俄语等非拉丁文字误判成「不用翻译」、原文直接当了译文，这里清掉让它重新翻译
        val cached = store.load(uri).let { cues ->
            val s = _state.value.settings
            val asrLanguage = videoLanguage ?: s.asrLanguage
            cues.map {
                if (it.translation == it.text && !LanguageCheck.alreadyTarget(it.text, s.ttsLocale, asrLanguage, ChineseScript::variant))
                    it.copy(translation = null) else it
            }
        }
        ttsReader.reset()
        _state.update {
            it.copy(
                current = entry,
                videoUri = uri,
                cues = cached,
                roles = store.loadRoles(uri),
                videoAsrLanguage = videoLanguage,
                roleStatus = null,
                extractingEmbedded = false,
                embeddedTracks = null,
                choosingEmbedded = false,
                status = "",
                progress = null,
                error = null,
                failedTask = null,
            )
        }
        extras.onEntryChanged(entry)
        music.onEntryChanged(entry)
        library.recordHistory(entry, 0)
    }

    /**
     * 识别或翻译进行中时，当前视频播完就暂停，不自动切到队列里的下一个，
     * 否则切换会取消正在进行的任务。任务结束后恢复连播。
     */
    private fun updateAutoAdvance() {
        val busy = _state.value.transcribing || _state.value.translating
        exoPlayer.pauseAtEndOfMediaItems = busy
    }

    /** 记录当前播放进度，离开播放页或切换视频时调用。 */
    fun savePosition() {
        val entry = _state.value.current ?: return
        if (player.mediaItemCount == 0) return
        val duration = player.duration.takeIf { it > 0 } ?: entry.durationMs
        library.recordHistory(entry.copy(durationMs = if (entry.isLive) 0 else duration), if (entry.isLive) 0 else player.currentPosition)
        extras.saveHistoryThumbnail()
    }

    /** 只给当前视频选语音语言，不改设置里的默认语言 */
    fun setVideoAsrLanguage(code: String) {
        val uri = _state.value.videoUri ?: return
        store.saveAsrLanguage(uri, code)
        _state.update { it.copy(videoAsrLanguage = code) }
    }

    fun updateSettings(s: AppSettings) {
        val old = _state.value.settings
        settingsRepo.save(s)
        _state.update { it.copy(settings = s) }
        if (old.ttsEngine != s.ttsEngine || old.ttsVoices != s.ttsVoices || old.ttsAzure() != s.ttsAzure() || old.targetLanguage != s.targetLanguage) {
            ttsReader.reset()
        }
        if (s.ttsEnabled) ttsReader.start() else ttsReader.stop()
        if (old.preferredAudioLanguages != s.preferredAudioLanguages || old.preferredSubtitleLanguages != s.preferredSubtitleLanguages) {
            extras.applyTrackDefaults()
        }
    }

    // ---------------- 朗读与角色 ----------------

    /** 按引擎和配置缓存引擎实例，配置变了就新建。 */
    @Synchronized
    private fun ttsEngine(type: TtsEngineType): TtsEngine {
        val settings = _state.value.settings
        val (key, region, endpoint) = settings.ttsAzure()
        val id = if (type == TtsEngineType.AZURE) "AZURE|$key|$region|$endpoint" else "SYSTEM"
        return ttsEngines.getOrPut(id) {
            when (type) {
                TtsEngineType.AZURE -> AzureTts(key, region, endpoint)
                TtsEngineType.SYSTEM -> SystemTts(getApplication())
            }
        }
    }

    /** 在线拉取（系统引擎为本机）当前目标语言可用的语音。 */
    suspend fun ttsVoices(settings: AppSettings = _state.value.settings, refresh: Boolean = false): List<TtsVoice> {
        val id = "${settings.ttsEngine}|${settings.ttsAzure()}|${settings.ttsLocale}"
        if (!refresh) voiceCache[id]?.let { return it }
        val voices = withContext(Dispatchers.IO) {
            // 设置页里还没保存的改动也要生效：用传入的配置建一个临时引擎
            val engine = if (settings == _state.value.settings) ttsEngine(settings.ttsEngine) else when (settings.ttsEngine) {
                TtsEngineType.AZURE -> settings.ttsAzure().let { (k, r, e) -> AzureTts(k, r, e) }
                TtsEngineType.SYSTEM -> ttsEngine(TtsEngineType.SYSTEM)
            }
            engine.voices(settings.ttsLocale)
        }
        voiceCache[id] = voices
        return voices
    }

    /** 试听某个语音。 */
    suspend fun previewVoice(voice: String?, settings: AppSettings = _state.value.settings) {
        val sample = _state.value.cues.firstOrNull { !it.translation.isNullOrBlank() }?.translation
            ?: sampleText(settings.ttsLocale)
        val file = java.io.File(getApplication<Application>().cacheDir, "tts-preview." + if (settings.ttsEngine == TtsEngineType.AZURE) "mp3" else "wav")
        withContext(Dispatchers.IO) {
            val engine = if (settings.ttsEngine == TtsEngineType.AZURE) settings.ttsAzure().let { (k, r, e) -> AzureTts(k, r, e) }
            else ttsEngine(TtsEngineType.SYSTEM)
            engine.synthesize(sample, voice, settings.ttsLocale, file)
        }
        previewPlayer?.release()
        previewPlayer = android.media.MediaPlayer().apply {
            setDataSource(file.path)
            prepare()
            setOnCompletionListener { it.release(); if (previewPlayer === it) previewPlayer = null }
            start()
        }
    }

    private fun sampleText(locale: String?) = when {
        locale == null -> "Hello, this is a voice preview."
        locale.startsWith("zh") -> "你好，这是一段试听。"
        locale.startsWith("ja") -> "こんにちは、これは試聴です。"
        locale.startsWith("ko") -> "안녕하세요, 미리 듣기입니다."
        else -> "Hello, this is a voice preview."
    }

    /** 用翻译服务的大模型识别每句台词的角色，并按性别自动分配语音。 */
    fun detectRoles() {
        val uri = _state.value.videoUri ?: return
        if (roleJob?.isActive == true) return
        val settings = _state.value.settings
        val translator = settings.createTranslator() ?: run {
            _state.update { it.copy(error = settings.translatorProblem() ?: "翻译服务配置不完整") }
            return
        }
        val cues = _state.value.cues
        if (cues.isEmpty()) return
        roleJob = viewModelScope.launch {
            try {
                _state.update { it.copy(roleStatus = "正在识别角色…") }
                val (roles, assigned) = RoleDetector.detect(translator, cues, _state.value.roles) { done, total ->
                    _state.update { it.copy(roleStatus = "正在识别角色 $done/$total…") }
                }
                // 自动配音：拿不到语音列表时先不分配，用默认语音朗读
                val voices = runCatching { ttsVoices() }.getOrDefault(emptyList())
                val withVoices = RoleDetector.autoAssign(roles, voices, settings.ttsEngine.name)
                _state.update { s ->
                    s.copy(
                        cues = s.cues.map { c -> c.copy(speaker = assigned[c.id]) },
                        roles = withVoices,
                        roleStatus = null,
                        status = "识别出 ${withVoices.size} 个角色",
                    )
                }
                store.save(uri, _state.value.cues)
                store.saveRoles(uri, withVoices)
                ttsReader.reset()
            } catch (e: CancellationException) {
                _state.update { it.copy(roleStatus = null) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(roleStatus = null, error = "识别角色失败：${e.message}") }
            }
        }
    }

    fun setRoleVoice(roleId: String, voice: String) {
        val uri = _state.value.videoUri ?: return
        val engine = _state.value.settings.ttsEngine.name
        _state.update { s -> s.copy(roles = s.roles.map { if (it.id == roleId) it.copy(voice = voice, voiceEngine = engine) else it }) }
        store.saveRoles(uri, _state.value.roles)
        ttsReader.reset()
    }

    /** 当前引擎下按性别重新给所有角色分配语音。 */
    fun reassignVoices() {
        val uri = _state.value.videoUri ?: return
        viewModelScope.launch {
            try {
                val voices = ttsVoices()
                val engine = _state.value.settings.ttsEngine.name
                val cleared = _state.value.roles.map { it.copy(voice = null, voiceEngine = null) }
                _state.update { it.copy(roles = RoleDetector.autoAssign(cleared, voices, engine)) }
                store.saveRoles(uri, _state.value.roles)
                ttsReader.reset()
            } catch (e: Exception) {
                _state.update { it.copy(error = "获取语音列表失败：${e.message}") }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null, failedTask = null) }

    /** 识别整段音轨。边识别边出字幕，开启自动翻译时同时翻译。 */
    fun generateSubtitles() {
        val uri = _state.value.videoUri ?: return
        if (transcribeJob?.isActive == true) return
        translateJob?.cancel()
        _state.update { it.copy(cues = emptyList(), transcribing = true, error = null, failedTask = null) }
        updateAutoAdvance()

        transcribeJob = viewModelScope.launch {
            try {
                _state.value.current?.takeIf { VlcPlayer.needsVlc(it.title) }?.let {
                    throw IllegalStateException("光盘镜像（ISO）暂不支持 AI 识别字幕，可以用「在线下载字幕」或选择字幕文件")
                }
                val settings = _state.value.asrSettings
                settings.asrProblem()?.let { throw IllegalStateException(it) }
                val localModel = ModelCatalog.byId(settings.localModel)
                if (settings.asrEngine == AsrEngine.LOCAL && !models.isReady(localModel)) {
                    models.ensure(localModel, settings.modelBaseUrl) { msg, p ->
                        _state.update { it.copy(status = msg, progress = p) }
                    }
                }
                val engineName = if (settings.asrEngine == AsrEngine.LOCAL) localModel.label else settings.asrEngine.label
                _state.update { it.copy(status = "正在识别语音（$engineName）…", progress = 0f) }
                if (settings.autoTranslate && settings.createTranslator() != null) startTranslation()

                // 云端引擎大部分时间在等网络，放到 IO 线程池
                val dispatcher = if (settings.asrEngine == AsrEngine.LOCAL) Dispatchers.Default else Dispatchers.IO
                withContext(dispatcher) {
                    createEngine(settings).use { engine ->
                        // 协程取消时中断引擎里正在进行的网络请求
                        val cancelHandle = coroutineContext.job.invokeOnCompletion { engine.cancel() }
                        try {
                            val onCue: (Cue) -> Unit = { cue -> _state.update { it.copy(cues = it.cues + cue) } }
                            AudioDecoder(getApplication(), uri, authDataSourceFactory(getApplication())).decode(
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
                saveSidecar()
            } catch (e: CancellationException) {
                _state.update { it.copy(status = "已取消", progress = null) }
                throw e
            } catch (e: Exception) {
                // 取消时被中断的网络请求会抛 IOException，不算失败
                if (!isActive) {
                    _state.update { it.copy(status = "已取消", progress = null) }
                    throw CancellationException("cancelled")
                }
                android.util.Log.e("Voxtitle", "识别失败", e)
                _state.update { it.copy(status = "识别失败", progress = null, error = e.message ?: e.toString(), failedTask = Task.TRANSCRIBE) }
            } finally {
                _state.update { it.copy(transcribing = false) }
                updateAutoAdvance()
            }
        }
    }

    private fun createEngine(settings: AppSettings): SpeechEngine = when (settings.asrEngine) {
        AsrEngine.LOCAL -> {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            Transcriber(ModelCatalog.byId(settings.localModel), models, com.aisub.player.asr.AsrLanguages.primary(settings.asrLanguage), threads)
        }
        else -> settings.createCloudEngine()
    }

    // ---- 本地模型管理（设置页用） ----

    fun isModelInstalled(model: LocalModel) = models.isInstalled(model)

    fun deleteModel(model: LocalModel) = models.delete(model)

    /** 翻译所有还没有译文的字幕。识别进行中时会持续等待新字幕。 */
    fun startTranslation() {
        if (translateJob?.isActive == true) return
        val settings = _state.value.settings
        val translator = settings.createTranslator()
        if (translator == null) {
            _state.update { it.copy(error = settings.translatorProblem() ?: "翻译服务配置不完整", failedTask = Task.TRANSLATE) }
            return
        }
        _state.update { it.copy(translating = true, error = null, failedTask = null) }
        updateAutoAdvance()
        translateJob = viewModelScope.launch {
            try {
                translateLoop(translator)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "翻译失败：${e.message}", failedTask = Task.TRANSLATE) }
            } finally {
                _state.update { it.copy(translating = false) }
                updateAutoAdvance()
                _state.value.videoUri?.let { store.save(it, _state.value.cues) }
                saveSidecar()
            }
        }
    }

    private suspend fun translateLoop(translator: Translator) {
        var skipped = 0
        while (true) {
            // 原文已经是目标语言（或没有文字）的句子不发给大模型，原文直接当译文
            val target = _state.value.settings.ttsLocale
            val asrLanguage = _state.value.asrSettings.asrLanguage
            val same = _state.value.cues
                .filter { it.translation == null && LanguageCheck.alreadyTarget(it.text, target, asrLanguage, ChineseScript::variant) }
                .map { it.id }.toSet()
            if (same.isNotEmpty()) {
                skipped += same.size
                _state.update { s -> s.copy(cues = s.cues.map { c -> if (c.id in same) c.copy(translation = c.text) else c }) }
            }

            val snapshot = _state.value
            val firstPending = snapshot.cues.indexOfFirst { it.translation == null }
            if (firstPending < 0) {
                if (snapshot.transcribing) {
                    delay(1000)
                    continue
                }
                if (skipped > 0 && !snapshot.transcribing) {
                    _state.update { it.copy(status = "翻译完成，其中 $skipped 句原文已是${it.settings.targetLanguage}，没有翻译") }
                }
                return
            }
            // 跳过中间不用翻译的句子，凑够一批再发
            val batch = snapshot.cues.drop(firstPending).filter { it.translation == null }.take(BATCH_SIZE)
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
        embeddedJob?.cancel()
    }

    /** 重新翻译某一句（带前后文）。 */
    fun retranslate(cueId: Int) {
        val settings = _state.value.settings
        val translator = settings.createTranslator() ?: run {
            _state.update { it.copy(error = settings.translatorProblem(), failedTask = Task.TRANSLATE) }
            return
        }
        viewModelScope.launch {
            val cues = _state.value.cues
            val i = cues.indexOfFirst { it.id == cueId }.takeIf { it >= 0 } ?: return@launch
            try {
                val context = cues.subList(maxOf(0, i - CONTEXT_SIZE), i).map { it.text }
                val result = translator.translate(listOf(cues[i].text), context, settings.targetLanguage).firstOrNull()
                if (result != null) {
                    _state.update { s -> s.copy(cues = s.cues.map { if (it.id == cueId) it.copy(translation = result) else it }) }
                    _state.value.videoUri?.let { store.save(it, _state.value.cues) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = "翻译失败：${e.message}", failedTask = Task.TRANSLATE) }
            }
        }
    }

    /**
     * 手动修改一句：原文、译文、起止时间。译文为 null 表示清除（之后可以重新翻译）。
     * 改了时间后按开始时间重新排序，字幕显示用二分查找依赖这个顺序。
     */
    fun editCue(cueId: Int, text: String, translation: String?, startMs: Long, endMs: Long) {
        _state.update { s ->
            s.copy(
                cues = s.cues.map { if (it.id == cueId) it.copy(text = text, translation = translation, startMs = startMs, endMs = endMs) else it }
                    .sortedBy { it.startMs },
            )
        }
        _state.value.videoUri?.let { store.save(it, _state.value.cues) }
        saveSidecar()
        ttsReader.reset()
    }

    fun deleteCue(cueId: Int) {
        _state.update { s -> s.copy(cues = s.cues.filter { it.id != cueId }) }
        _state.value.videoUri?.let { store.save(it, _state.value.cues) }
        saveSidecar()
        ttsReader.reset()
    }

    private val sidecarLock = Mutex()

    /** 开了「在视频旁保存字幕文件」时，把当前字幕写到本地视频旁边的同名 .srt */
    private fun saveSidecar() {
        val s = _state.value
        val entry = s.current ?: return
        if (!s.settings.autoSaveSrt || s.cues.isEmpty()) return
        val cues = s.cues
        viewModelScope.launch(Dispatchers.IO) {
            sidecarLock.withLock {
                try {
                    SidecarSrt.write(getApplication(), entry, cues)
                } catch (e: Exception) {
                    android.util.Log.w("Voxtitle", "保存字幕文件失败", e)
                    _state.update { it.copy(status = "没能在视频旁保存字幕：${e.message}") }
                }
            }
        }
    }

    // ---------------- 播放器"更多"菜单 ----------------

    fun setSubtitleDelay(ms: Long) = _state.update { it.copy(subtitleDelayMs = ms) }

    private var sleepJob: Job? = null

    /** 睡眠计时：minutes > 0 为倒计时；0 为本集结束后暂停；null 为取消。 */
    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        exoPlayer.pauseAtEndOfMediaItems = false
        when {
            minutes == null -> _state.update { it.copy(sleepAt = null) }
            minutes == 0 -> {
                exoPlayer.pauseAtEndOfMediaItems = true
                _state.update { it.copy(sleepAt = -1) }
            }
            else -> {
                val at = System.currentTimeMillis() + minutes * 60_000L
                _state.update { it.copy(sleepAt = at) }
                sleepJob = viewModelScope.launch {
                    delay(minutes * 60_000L)
                    player.pause()
                    _state.update { it.copy(sleepAt = null) }
                }
            }
        }
    }

    /** A-B 循环：依次设置 A、B，第三次清除。 */
    fun toggleAbLoop(): String {
        val s = _state.value
        val pos = player.currentPosition
        return when {
            s.loopA == null -> { _state.update { it.copy(loopA = pos, loopB = null) }; "已设置 A 点，再点一次设置 B 点" }
            s.loopB == null && pos > s.loopA -> { _state.update { it.copy(loopB = pos) }; "A-B 循环已开启" }
            else -> { _state.update { it.copy(loopA = null, loopB = null) }; "A-B 循环已关闭" }
        }
    }

    /** 由播放页的位置轮询调用：到达 B 点跳回 A 点。 */
    fun checkAbLoop(positionMs: Long) {
        val a = _state.value.loopA ?: return
        val b = _state.value.loopB ?: return
        if (positionMs >= b) player.seekTo(a)
    }

    /** 载入外挂字幕文件（SRT / VTT），替换当前字幕。 */
    fun importSubtitles(file: Uri) {
        val video = _state.value.videoUri ?: return
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(file)!!.use { it.readBytes() }
                        .let { bytes -> decodeText(bytes) }
                }
                applySubtitleText(video, text)
            } catch (e: Exception) {
                _state.update { it.copy(error = "载入字幕失败：${e.message}") }
            }
        }
    }

    /**
     * 读取视频自带的文字字幕轨。只有一条可用时直接用；有多条时放进 embeddedTracks 让用户选，
     * 选中后调用 [useEmbeddedTrack]。
     */
    fun extractEmbeddedSubtitles() {
        val uri = _state.value.videoUri ?: return
        if (embeddedJob?.isActive == true) return
        _state.value.embeddedTracks?.let { tracks -> offerEmbedded(tracks); return }
        transcribeJob?.cancel()
        _state.update { it.copy(extractingEmbedded = true, status = "正在读取视频自带的字幕…", progress = 0f, error = null) }
        embeddedJob = viewModelScope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) {
                    EmbeddedSubtitles.extract(uri, authDataSourceFactory(getApplication()), isActive = { isActive }) { p ->
                        _state.update { it.copy(progress = p) }
                    }
                }
                _state.update { it.copy(embeddedTracks = tracks, progress = null) }
                offerEmbedded(tracks)
            } catch (e: CancellationException) {
                _state.update { it.copy(status = "已取消", progress = null) }
                throw e
            } catch (e: InterruptedException) {
                _state.update { it.copy(status = "已取消", progress = null) }
            } catch (e: Exception) {
                android.util.Log.e("Voxtitle", "读取内嵌字幕失败", e)
                _state.update { it.copy(status = "", progress = null, error = "读取视频自带字幕失败：${e.message ?: e}") }
            } finally {
                _state.update { it.copy(extractingEmbedded = false) }
            }
        }
    }

    private fun offerEmbedded(tracks: List<EmbeddedSubtitles.Track>) {
        val usable = tracks.filter { it.cues.isNotEmpty() }
        when {
            usable.size == 1 -> useEmbeddedTrack(usable[0])
            usable.isEmpty() -> _state.update {
                it.copy(
                    status = "",
                    error = if (tracks.any { t -> t.imageBased }) "视频自带的是图形字幕（PGS / VobSub），没有文字，无法翻译。可以用 AI 识别字幕"
                    else "视频里没有找到文字字幕轨。画面里烧录的字幕无法提取，可以用 AI 识别字幕",
                )
            }
            // 多条：界面弹出选择
            else -> _state.update { it.copy(status = "找到 ${usable.size} 条字幕轨，请选择一条", choosingEmbedded = true) }
        }
    }

    // ---------------- 下载版：分享链接 → 下载 → 字幕 → 播放 ----------------

    /**
     * 用 yt-dlp 下载链接里的视频和平台字幕，保存到 Movies/Voxtitle，
     * 载入字幕（没有就按设置自动 AI 识别），需要时开始翻译，然后播放。
     */
    fun downloadLink(url: String) {
        val downloader = linkDownloader ?: return
        if (linkJob?.isActive == true) {
            downloader.cancel()
            linkJob?.cancel()
        }
        _state.update { it.copy(linkTask = LinkTask(url)) }
        fun progress(p: LinkProgress, title: String? = null) =
            _state.update { s -> s.copy(linkTask = s.linkTask?.copy(progress = p, title = title ?: s.linkTask.title)) }

        linkJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val settings = _state.value.settings
            try {
                // 临时目录放在应用的外部存储里，视频可能很大
                val dir = java.io.File(app.getExternalFilesDir(null) ?: app.filesDir, "link-downloads")
                val cookies = cookieJar.file.takeIf { cookieJar.exists }
                val result = downloader.download(url, dir, settings.dlQuality, settings.ttsLocale, cookies) { p ->
                    progress(p, p.detail.takeIf { p.stage == LinkStage.DOWNLOAD && it.isNotEmpty() && '%' !in it })
                }
                progress(LinkProgress(LinkStage.SAVE, 0f), result.title)
                val uri = withContext(Dispatchers.IO) {
                    com.aisub.player.download.MovieSaver.save(app, result.video, result.title) { f ->
                        progress(LinkProgress(LinkStage.SAVE, f))
                    }
                }

                progress(LinkProgress(LinkStage.SUBTITLE, null))
                val cues = withContext(Dispatchers.IO) {
                    fun read(f: java.io.File?) = f?.let { SubtitleParser.parse(decodeText(it.readBytes())) }.orEmpty()
                    var source = read(result.subtitle)
                    if (result.autoCaption) source = PlatformSubtitles.cleanRolling(source)
                    val target = read(result.translation)
                    listOfNotNull(result.subtitle, result.translation).forEach { it.delete() }
                    if (source.isEmpty()) target else if (target.isEmpty()) source else PlatformSubtitles.mergeTranslation(source, target)
                }
                if (cues.isNotEmpty()) store.save(uri, cues)

                play(listOf(MediaLibrary.entryFor(app, uri)), 0)
                val s = _state.value.settings
                when {
                    _state.value.cues.isEmpty() && s.dlAutoAsr -> generateSubtitles()
                    s.autoTranslate && cues.any { it.translation == null } && s.createTranslator() != null -> startTranslation()
                }
                _state.update { st -> st.copy(linkTask = st.linkTask?.copy(done = true)) }
            } catch (e: CancellationException) {
                _state.update { it.copy(linkTask = null) }
                throw e
            } catch (e: Exception) {
                android.util.Log.e("Voxtitle", "链接下载失败", e)
                _state.update { s -> s.copy(linkTask = s.linkTask?.copy(error = e.message ?: e.toString())) }
            }
        }
    }

    fun cancelLink() {
        linkDownloader?.cancel()
        linkJob?.cancel()
        _state.update { it.copy(linkTask = null) }
    }

    /** 界面已经跳到播放页（或关闭了出错提示）。 */
    fun clearLinkTask() = _state.update { it.copy(linkTask = null) }

    /** 用某条自带字幕轨作为当前字幕（之后可以翻译、朗读、导出）。 */
    fun useEmbeddedTrack(track: EmbeddedSubtitles.Track) {
        val video = _state.value.videoUri ?: return
        if (track.cues.isEmpty()) return
        cancelAll()
        _state.update {
            it.copy(
                cues = track.cues, status = "已载入视频自带字幕「${track.title}」${track.cues.size} 句",
                error = null, failedTask = null, choosingEmbedded = false,
            )
        }
        store.save(video, track.cues)
        val settings = _state.value.settings
        if (settings.autoTranslate && settings.createTranslator() != null && track.cues.any { it.translation == null }) startTranslation()
    }

    fun dismissEmbeddedChoice() = _state.update { it.copy(choosingEmbedded = false) }

    private fun applySubtitleText(video: Uri, text: String): Int {
        val cues = SubtitleParser.parse(text)
        if (cues.isEmpty()) throw IllegalArgumentException("文件里没有识别到字幕")
        cancelAll()
        _state.update { it.copy(cues = cues, status = "已载入 ${cues.size} 句字幕", error = null, failedTask = null) }
        store.save(video, cues)
        return cues.size
    }

    // ---------------- 在线字幕 ----------------

    private var online: Pair<AppSettings, OnlineSubtitles>? = null

    /** 按当前设置创建（设置没变就复用，保留 OpenSubtitles 的登录状态）。 */
    fun onlineSubtitles(): OnlineSubtitles {
        val s = _state.value.settings
        online?.takeIf { it.first == s }?.let { return it.second }
        return OnlineSubtitles(s.osApiKey, s.osUsername, s.osPassword, s.assrtToken).also { online = s to it }
    }

    /** 搜索框的默认内容：去掉扩展名的文件名。 */
    fun defaultSubtitleQuery(): String = _state.value.current?.title?.substringBeforeLast('.').orEmpty()

    suspend fun searchOnlineSubtitles(source: OnlineSubtitleSource, query: String): List<OnlineSubtitle> = withContext(Dispatchers.IO) {
        val video = _state.value.videoUri
        // 本地文件附带哈希，能精确匹配到同一版本的字幕
        val hash = if (source == OnlineSubtitleSource.OPENSUBTITLES && video != null) {
            OnlineSubtitles.movieHash(getApplication(), video)
        } else {
            null
        }
        onlineSubtitles().search(source, query, _state.value.settings.subtitleLanguages, hash)
    }

    /** 下载并替换当前字幕，返回句数。 */
    suspend fun downloadOnlineSubtitle(sub: OnlineSubtitle): Int {
        val video = _state.value.videoUri ?: throw IllegalStateException("没有正在播放的视频")
        val text = withContext(Dispatchers.IO) { onlineSubtitles().download(sub) }
        return applySubtitleText(video, text)
    }

    /** 当前播放队列存为播放列表。 */
    fun saveQueueAsPlaylist(name: String) {
        val p = library.createPlaylist(name)
        library.savePlaylists(library.playlists().map { if (it.id == p.id) it.copy(items = queue, updatedAt = System.currentTimeMillis()) else it })
    }

    val queueSize get() = queue.size

    /** 当前播放队列（直播时就是频道列表） */
    val queueEntries: List<MediaEntry> get() = queue

    /** 切到队列里的第 index 个（直播切台）。直播从最新画面开始。 */
    fun playQueueIndex(index: Int) {
        if (usingVlc || index !in queue.indices) return
        exoPlayer.seekToDefaultPosition(index)
        exoPlayer.play()
    }

    /** 设置页的"测试连接"：用 1 秒静音请求一次识别服务。返回给用户看的结果。 */
    suspend fun testAsr(settings: AppSettings): String = withContext(Dispatchers.IO) {
        settings.asrProblem()?.let { return@withContext it }
        if (settings.asrEngine == AsrEngine.LOCAL) {
            val model = ModelCatalog.byId(settings.localModel)
            return@withContext if (models.isReady(model)) "${model.label} 已就绪" else "${model.label} 尚未下载，首次识别时会自动下载"
        }
        try {
            settings.createCloudEngine().use { engine ->
                engine.accept(FloatArray(Transcriber.SAMPLE_RATE)) {}
                engine.finish {}
            }
            "连接成功"
        } catch (e: Exception) {
            "连接失败：${e.message}"
        }
    }

    /** 设置页的"测试连接"：翻译一句英文。 */
    suspend fun testTranslator(settings: AppSettings): String {
        val translator = settings.createTranslator() ?: return settings.translatorProblem() ?: "配置不完整"
        return try {
            val r = translator.translate(listOf("Hello, nice to meet you."), emptyList(), settings.targetLanguage).firstOrNull()
            if (r != null) "连接成功：$r" else "连接成功，但没有返回译文"
        } catch (e: Exception) {
            "连接失败：${e.message}"
        }
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
        ttsReader.stop()
        previewPlayer?.release()
        ttsEngines.values.forEach { it.close() }
        savePosition()
        extras.release()
        exoPlayer.release()
        assHandler.release()
        vlcPlayer?.release()
    }

    companion object {
        private const val BATCH_SIZE = 20
        private const val CONTEXT_SIZE = 6
    }
}

/** 给已登记的 WebDAV 地址加上鉴权头，其他地址原样访问。 */
@UnstableApi
internal fun authDataSourceFactory(app: Application): DataSource.Factory {
    val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
    return ResolvingDataSource.Factory(DefaultDataSource.Factory(app, http)) { spec ->
        val uri = spec.uri.toString()
        if (CloudRegistry.isCloud(uri)) {
            // 网盘：在加载线程里把 cloud:// 换成临时直链（会联网，结果有缓存）
            val link = CloudRegistry.resolve(uri)
            spec.withUri(Uri.parse(link.url)).withAdditionalHeaders(link.headers)
        } else {
            // 双地址服务器：换成当前能连上的内网 / 外网地址
            val routed = Endpoints.rewrite(uri)
            // 直播频道要求的 User-Agent / Referer 也在这里带上
            val headers = AuthRegistry.headersFor(routed) + com.aisub.player.live.LiveHeaders.headersFor(routed)
            val s = if (routed == uri) spec else spec.withUri(Uri.parse(routed))
            if (headers.isEmpty()) s else s.withAdditionalHeaders(headers)
        }
    }
}

/**
 * 系统解码器优先；AC3/E-AC3/DTS/TrueHD 等系统不支持的音频格式交给 FFmpeg 扩展软解，
 * 否则这类视频（常见于 MKV 电影）会只有画面没有声音。
 */
@UnstableApi
internal fun renderersFactory(app: Application) = DefaultRenderersFactory(app)
    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    .setEnableDecoderFallback(true)

/** 把播放器错误翻译成用户能看懂的话，附上错误码方便反馈。 */
internal fun describePlaybackError(e: PlaybackException): String {
    val reason = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接失败或超时"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val code = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
            "服务器拒绝了请求" + (code?.let { "（HTTP $it）" } ?: "") + "，可能是登录过期或链接失效"
        }
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "找不到文件"
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "文件不完整或已损坏（可能没下载完）"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "没有读取这个文件的权限"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "不支持或已损坏的视频封装格式"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "手机无法解码这个视频的画面或声音格式"
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "音频输出失败"
        else -> "播放出错"
    }
    val detail = generateSequence(e.cause) { it.cause }.mapNotNull { it.message }.firstOrNull()?.take(200)
    return buildString {
        append(reason).append("\n错误码：").append(e.errorCodeName)
        if (!detail.isNullOrBlank()) append("\n").append(detail)
    }
}
