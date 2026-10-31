package com.aisub.player

import android.app.Application
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import com.aisub.player.live.LiveAnchors
import com.aisub.player.live.LiveCaptions
import com.aisub.player.live.LiveClock
import com.aisub.player.live.LiveKey
import com.aisub.player.live.LiveTimeline
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
import com.aisub.player.media.withAiff
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
import com.aisub.player.remote.cloud.MediaBrowser
import com.aisub.player.remote.cloud.ServerProgress
import com.aisub.player.asr.AudioDecoder
import com.aisub.player.asr.LocalModel
import com.aisub.player.asr.ModelCatalog
import com.aisub.player.asr.ModelManager
import com.aisub.player.asr.RangeSet
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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
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
    /** 有声音但画面出不来时的说明（多半是手机解不了这种格式）；null 表示正常 */
    val videoProblem: String? = null,
    /** 正在播放服务器转码后的流 */
    val transcoding: Boolean = false,
    /** 直播实时字幕用的时钟（见 [LiveClock]）；null 表示没开，字幕按播放位置显示 */
    val liveClock: LiveClock? = null,
    /** 这次识别用的是流式识别（直播、远程视频，见设置里的开关） */
    val streamingAsr: Boolean = false,
    /** 这个视频的字幕用的识别服务和模型（显示在播放页顶部），如「识别：阿里 Fun-ASR · fun-asr-flash」；null 表示没识别过 */
    val asrInfo: String? = null,
    /** 用的翻译服务和模型，如「翻译：DeepSeek · deepseek-chat」 */
    val translateInfo: String? = null,
    /** 直播实时字幕一直出不来时的说明（显示在字幕的位置）；正常时为 null */
    val liveHint: String? = null,
    /** 正在边播边翻译直播自带的隐藏式字幕（见 [MainViewModel.startLiveCaptions]）；这时画面上不再原样显示自带字幕 */
    val liveCaptions: Boolean = false,
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
        val sources = DefaultMediaSourceFactory(authDataSourceFactory(app), DefaultExtractorsFactory().withAssMkvSupport(assParsers, assHandler).withAiff())
        sources.setSubtitleParserFactory(assParsers)
        ExoPlayer.Builder(app)
            .setRenderersFactory(renderersFactory(app).withAssSupport(assHandler))
            .setMediaSourceFactory(sources)
            // 后台播放需要：来电、别的应用放声音时暂停，拔耳机暂停，锁屏后播放网络文件不断
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
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

    /** 应用没开着时从桌面小组件开始的后台播放：先停掉它，界面打开后接着放（见 [resumeWidgetPlayback]） */
    private var widgetHandover = WidgetPlayback.takeOver()

    /** 媒体会话：通知栏、锁屏、耳机按键控制播放；切换内核时跟着换播放器 */
    private val session: MediaSession = MediaSession.Builder(app, exoPlayer)
        // 同一进程里会话 ID 不能重复：旧界面还没销毁时新界面就建好了（刚退出又马上打开），用默认 ID 会闪退
        .setId(java.util.UUID.randomUUID().toString())
        .setSessionActivity(
            PendingIntent.getActivity(
                // 点通知栏、锁屏的播放控件回到播放页
                app, 0, Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()
        .also { PlaybackService.session = it }

    /** 界面打开前小组件在后台放着歌：从同一首、同一位置接着放（进度已记进历史） */
    fun resumeWidgetPlayback() {
        val h = widgetHandover ?: return
        widgetHandover = null
        play(h.queue, h.index)
        if (!h.playing) player.pause()
    }

    /** 开始播放时启动后台播放服务（应用在前台时才能启动），之后由 Media3 管理前台通知 */
    private fun startPlaybackService() {
        val app = getApplication<Application>()
        runCatching { app.startService(Intent(app, PlaybackService::class.java)) }
            .onFailure { android.util.Log.w("Voxtitle", "无法启动后台播放服务", it) }
    }

    /** 切到后台时是否继续播放：音乐看设置，视频选了「继续播放声音」时继续 */
    fun keepPlayingInBackground(): Boolean {
        val e = _state.value.current ?: return false
        if (!player.playWhenReady) return false
        val s = _state.value.settings
        return if (e.isVideo || e.isLive) s.videoLeaveAction == VideoLeaveAction.AUDIO else s.musicBackground
    }

    // ---- 画面出不来：手机解不了这种格式时提示，并可请服务器转码 ----

    private var sawFirstFrame = false
    private var frameWatch: Job? = null

    /** 视频开始播放后 6 秒还没出第一帧（或解码器明说不支持），就提示 */
    private fun watchFirstFrame() {
        if (usingVlc || sawFirstFrame || frameWatch?.isActive == true) return
        val e = _state.value.current?.takeIf { it.isVideo && !it.isLive } ?: return
        frameWatch = viewModelScope.launch {
            delay(6000)
            // 解码器说不支持时，ExoPlayer 干脆不选视频轨、只放声音，所以看「有视频轨」而不是「选中了视频轨」
            val video = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            if (sawFirstFrame || video.isEmpty() || _state.value.current?.uri != e.uri || !exoPlayer.isPlaying) return@launch
            // 用户自己选了「作为音频播放」（关掉视频轨）时不提示
            if (C.TRACK_TYPE_VIDEO in exoPlayer.trackSelectionParameters.disabledTrackTypes) return@launch
            val f = exoPlayer.videoFormat ?: video.first().getTrackFormat(0)
            val codec = when (f.sampleMimeType) {
                "video/hevc" -> "HEVC"
                "video/avc" -> "H.264"
                "video/av01" -> "AV1"
                "video/x-vnd.on2.vp9" -> "VP9"
                "video/dolby-vision" -> "杜比视界"
                else -> f.sampleMimeType?.substringAfter('/')?.uppercase()
            }
            // 宽银幕的 4K 是 3840×1606 这样，按宽度算
            val size = if (f.width >= 3800 || f.height >= 2000) "4K" else if (f.height > 0) "${f.height}p" else null
            val desc = listOfNotNull(size, codec).joinToString(" ").ifEmpty { "这个视频" }
            _state.update { it.copy(videoProblem = "这台手机放不了 $desc 的画面，声音照常播放") }
        }
    }

    /** 当前视频来自 Plex / Emby / Jellyfin，可以请服务器转码 */
    val canTranscode: Boolean
        get() = !_state.value.transcoding && _state.value.current?.let { it.uri != transcodeFailedUri && ServerProgress.find(it.uri) != null } == true

    /** 服务器说转不了的视频，不再显示「服务器转码播放」 */
    private var transcodeFailedUri: String? = null

    /** 换成服务器转码的 1080p H.264 流，从当前位置接着放 */
    fun playTranscoded() {
        val e = _state.value.current ?: return
        val p = ServerProgress.find(e.uri) ?: return
        val server = CloudRegistry.serverLookup(p.serverId) ?: return
        val browser = CloudRegistry.client(server) as? MediaBrowser ?: return
        val position = exoPlayer.currentPosition
        val index = exoPlayer.currentMediaItemIndex
        viewModelScope.launch {
            val url = runCatching { withContext(Dispatchers.IO) { browser.transcodeUrl(p.ratingKey) } }.getOrElse {
                transcodeFailedUri = e.uri
                _state.update { s -> s.copy(videoProblem = "画面放不了，而且${it.message}") }
                return@launch
            }
            sawFirstFrame = false
            _state.update { it.copy(videoProblem = null, transcoding = true, playbackError = null) }
            exoPlayer.replaceMediaItem(
                index,
                itemOf(e).buildUpon().setUri(url).setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8).build(),
            )
            exoPlayer.seekTo(index, position)
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    fun dismissVideoProblem() = _state.update { it.copy(videoProblem = null) }

    /** 生成字幕前要先下载的本地识别模型（已经下好、或用云端识别时为 null） */
    fun modelToDownload(): LocalModel? {
        val s = _state.value.asrSettings
        if (s.asrEngine != AsrEngine.LOCAL) return null
        return ModelCatalog.byId(s.localModel).takeIf { !models.isReady(it) }
    }

    /** 正在放的是音乐（不是视频、直播） */
    val playingMusic: Boolean get() = _state.value.current?.let { !it.isVideo && !it.isLive } == true

    /** 从播放页返回时音乐是否继续放（跟随「音乐后台播放」设置） */
    fun keepMusicPlayingOutsidePlayer(): Boolean = playingMusic && _state.value.settings.musicBackground

    /** 离开应用时是否自动进入画中画（悬浮窗）：正在播放视频且设置里选了悬浮窗 */
    fun wantsAutoPip(): Boolean {
        val e = _state.value.current ?: return false
        return (e.isVideo || e.isLive) && player.playWhenReady && _state.value.settings.videoLeaveAction == VideoLeaveAction.PIP
    }

    private val settingsRepo = SettingsRepository(app)
    private val store = SubtitleStore(app)
    private val models = ModelManager(app)
    private val library = AppStore(app)

    private val _state = MutableStateFlow(UiState(settings = settingsRepo.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var transcribeJob: Job? = null
    private var translateJob: Job? = null

    /** 按播放位置边看边识别：已经识别过的时间段，以及这个视频是否还只识别了一部分 */
    private val coverage = RangeSet()
    private var coverageActive = false
    private var coverageLimit = UNKNOWN_END
    private var nextCueId = 1

    /** 已识别范围是用哪个引擎、哪种语言识别的（"引擎/语言"）；换了就从头识别。null 表示不知道（重新打开的视频） */
    private var coverageKey: String? = null

    /** 上次点生成字幕时是否要求同时翻译；自动接着识别时沿用 */
    private var lastAlsoTranslate = false

    /** 网盘视频识别到结尾后，盯着播放位置：拖回没识别过的地方就接着识别 */
    private var gapWatch: Job? = null

    /** 这次翻译用的服务和模型；翻出第一批后才显示到播放页 */
    private var pendingTranslateInfo: String? = null

    /** 这次识别同时要翻译：Gemini 识别时顺带翻译，省掉单独翻译的一轮 */
    private var inlineTranslate = false
    private var roleJob: Job? = null
    private var embeddedJob: Job? = null
    private var captionJob: Job? = null
    /** 读出的自带字幕轨载入后一定翻译（播放页的「翻译成…字幕」按钮），不看自动翻译设置 */
    private var translateEmbedded = false
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
        clock = { subtitleTime() },
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
            // 自动连播下一集：上一集算看完，告诉 Plex
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) _state.value.current?.let { prev ->
                ServerProgress.find(prev.uri)?.let { p -> reportPlex(prev.uri, "stopped", p.durationMs, p.durationMs) }
            }
            if (!usingVlc) queue.getOrNull(exoPlayer.currentMediaItemIndex)?.let(::onEntryChanged)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            reportPlexNow(if (isPlaying) "playing" else "paused")
            if (isPlaying) watchFirstFrame()
        }

        override fun onRenderedFirstFrame() {
            sawFirstFrame = true
            frameWatch?.cancel()
            _state.update { it.copy(videoProblem = null) }
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            extras.onTracksChanged(tracks)
            music.onTracksChanged(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.e("Voxtitle", "播放失败", error)
            if (recoverLive(error)) return
            if (retryLiveAsOtherType(error)) return
            if (autoSwitchLiveToVlc(error)) return
            // 记住要用兼容模式的频道，兼容模式也打不开：忘掉，下次先用默认内核试
            _state.value.current?.takeIf { usingVlc && it.isLive }?.let { forgetVlcChannel(it.uri) }
            // 识别另开了一路连接：直播源、网盘限制同时连接数时，可能就是它把画面挤断了
            val extra = _state.value.takeIf { s -> s.transcribing && s.current?.let { it.isLive || isRemote(it) } == true }
                ?.let { s -> "\n\n$CONCURRENCY_HINT。可以先停止识别再播放" + if (s.current?.isLive == true) "，有自带字幕时改用「翻译自带字幕」" else "" }
            _state.update { it.copy(playbackError = describePlaybackError(error) + extra.orEmpty()) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                _state.update { it.copy(playbackError = null) }
                _state.value.current?.takeIf { it.isLive && !usingVlc }?.let { liveWorked += it.uri }
            }
        }
    }

    /** 这次打开后默认内核放出过画面的直播频道：中途出错是直播流的问题，不是内核打不开 */
    private val liveWorked = HashSet<String>()

    /** 直播中途出错后原地重连的时间（elapsedRealtime），用来限制次数 */
    private val liveRecoveries = ArrayDeque<Long>()

    /**
     * 默认内核放得好好的直播中途出错（落后直播窗口、分片暂时下载失败等）：回到直播最新处重新加载，
     * 不换兼容模式、也不记住（不然这个频道以后都用兼容模式，有的直播在兼容模式下根本放不出来）。
     * 一分钟内最多重连 3 次，还不行就照常报错。
     */
    private fun recoverLive(error: PlaybackException): Boolean {
        val entry = _state.value.current?.takeIf { it.isLive } ?: return false
        if (usingVlc) return false
        val behind = error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
        if (!behind && entry.uri !in liveWorked) return false
        val now = android.os.SystemClock.elapsedRealtime()
        while (liveRecoveries.isNotEmpty() && now - liveRecoveries.first() > 60_000) liveRecoveries.removeFirst()
        if (liveRecoveries.size >= 3) return false
        liveRecoveries.addLast(now)
        android.util.Log.w("Voxtitle", "直播中途出错（${error.errorCodeName}），回到最新处重连：${entry.uri}")
        exoPlayer.seekToDefaultPosition()
        exoPlayer.prepare()
        exoPlayer.play()
        return true
    }

    init {
        if (_state.value.settings.ttsEnabled) ttsReader.start()
        exoPlayer.addListener(listener)
        // 内嵌字幕默认不显示（避免和 AI 字幕重叠），除非设置了首选字幕语言
        extras.applyTrackDefaults()
        // 识别、翻译的进度放到通知栏，离开播放页也看得到
        viewModelScope.launch {
            val notifier = TaskNotifier(app)
            var wasBusy = false
            _state.collect { s ->
                val busy = s.transcribing || s.translating || s.extractingEmbedded
                val title = s.current?.displayTitle.orEmpty()
                if (busy) {
                    val step = s.status.ifEmpty { if (s.translating) "正在翻译字幕…" else "正在识别字幕…" }
                    notifier.progress(title, step, s.progress)
                } else if (wasBusy) {
                    notifier.done(title, if (s.error == null && s.cues.isNotEmpty()) "字幕准备好了" else null)
                }
                wasBusy = busy
            }
        }
        // 媒体服务器：播放时每 10 秒报一次进度
        viewModelScope.launch {
            while (true) {
                delay(10_000)
                if (player.isPlaying) reportPlexNow("playing")
            }
        }
        // 换了播放内核（ExoPlayer ↔ VLC）或读到歌曲信息时，更新媒体会话：通知栏、锁屏显示歌名、歌手和封面；桌面小组件也跟着刷新
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(_player, music.state) { p, m -> p to m }
                .distinctUntilChanged { a, b -> a.first === b.first && a.second.tags == b.second.tags && a.second.cover === b.second.cover }
                .collect { (p, m) ->
                    session.player = SessionPlayer(p, musicMetadata(m))
                    if (PlaybackService.session === session) NowPlayingWidget.attach(getApplication(), session.player)
                }
        }
        // 桌面小组件显示正在唱的那句歌词
        viewModelScope.launch {
            music.state.map { it.lyrics }.distinctUntilChanged().collect { l ->
                if (PlaybackService.session === session) NowPlayingWidget.setLyrics(getApplication(), l)
            }
        }
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

    // ---- 直播：默认内核打不开的频道自动换兼容模式（VLC 内核），并按频道记住 ----

    private val playbackPrefs = getApplication<Application>().getSharedPreferences("playback", Context.MODE_PRIVATE)

    /** 要用兼容模式播放的直播频道地址 */
    private val vlcChannels: MutableSet<String> = HashSet(playbackPrefs.getStringSet("vlcChannels", emptySet()).orEmpty())

    /** 这次打开后已经自动换过兼容模式的频道，兼容模式也不行时不再来回换 */
    private val vlcAutoTried = HashSet<String>()

    private fun rememberVlcChannel(uri: String) {
        if (vlcChannels.add(uri)) playbackPrefs.edit().putStringSet("vlcChannels", HashSet(vlcChannels)).apply()
    }

    private fun forgetVlcChannel(uri: String) {
        if (vlcChannels.remove(uri)) playbackPrefs.edit().putStringSet("vlcChannels", HashSet(vlcChannels)).apply()
    }

    /**
     * 直播用默认内核打不开：自动改用兼容模式。网络不通、源失效（HTTP 4xx/5xx）换内核也没用，照常报错。
     * 返回是否已切换。
     */
    private fun autoSwitchLiveToVlc(error: PlaybackException): Boolean {
        val entry = _state.value.current?.takeIf { it.isLive } ?: return false
        if (usingVlc || !vlcAutoTried.add(entry.uri)) return false
        if (error.errorCode in setOf(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            )
        ) return false
        android.util.Log.i("Voxtitle", "直播默认内核打不开，自动换兼容模式：${entry.uri}")
        switchToVlc()
        return true
    }

    /** 用 VLC 内核播放一个文件。直播保留频道列表，换台时每个频道再按记住的内核打开。 */
    private fun playWithVlc(entry: MediaEntry, positionMs: Long, channels: List<MediaEntry>? = null) {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        queue = channels ?: listOf(entry)
        val v = vlc()
        v.setMediaItem(itemOf(entry), positionMs)
        _state.update { it.copy(playbackError = null) }
        v.prepare()
        v.play()
        startPlaybackService()
        _player.value = v
        onEntryChanged(entry)
        if (entry.isLive) watchVlcLive(entry)
    }

    private var vlcLiveWatch: Job? = null

    /**
     * 记成要用兼容模式的直播频道，兼容模式一直放不出来（不报错，只是黑屏不动）：忘掉，改回默认内核。
     * 每个频道只回退一次，免得两个内核来回换。
     */
    private fun watchVlcLive(entry: MediaEntry) {
        vlcLiveWatch?.cancel()
        vlcLiveWatch = viewModelScope.launch {
            delay(VLC_LIVE_START_MS)
            val v = vlcPlayer ?: return@launch
            if (!usingVlc || _state.value.current?.uri != entry.uri || v.playbackState == Player.STATE_READY) return@launch
            if (!vlcFallbackTried.add(entry.uri)) return@launch
            android.util.Log.w("Voxtitle", "兼容模式 ${VLC_LIVE_START_MS / 1000} 秒还没放出直播，改回默认内核：${entry.uri}")
            forgetVlcChannel(entry.uri)
            vlcAutoTried += entry.uri
            val channels = queue
            play(channels, channels.indexOf(entry).coerceAtLeast(0))
        }
    }

    /** 这次打开后已经从兼容模式退回过默认内核的频道 */
    private val vlcFallbackTried = HashSet<String>()

    /** ExoPlayer 播放失败时，从当前位置改用 VLC 内核重试。 */
    fun switchToVlc() {
        val entry = _state.value.current ?: return
        if (entry.isLive) {
            rememberVlcChannel(entry.uri)
            // 换内核前先停掉实时字幕：兼容模式下字幕无法工作
            cancelAll()
            playWithVlc(entry, 0, queue.takeIf { entry in it })
        } else {
            playWithVlc(entry, exoPlayer.currentPosition.coerceAtLeast(0))
        }
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
        val resume = resumePosition(start)
        // 光盘镜像只能用 VLC 内核播放
        if (VlcPlayer.needsVlc(start.title)) {
            playWithVlc(start, resume)
            return
        }
        // 上次默认内核打不开、换了兼容模式的直播频道：直接用兼容模式，不用再先报错一次
        if (start.isLive && start.uri in vlcChannels) {
            playWithVlc(start, 0, entries)
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
        startPlaybackService()
        onEntryChanged(start)
    }

    /** 上次看到的位置；快看完的从头开始。直播没有"上次看到哪"，总是从最新画面开始。 */
    private fun resumePosition(e: MediaEntry): Long {
        if (e.isLive) return 0L
        // 媒体服务器的条目以服务器记的进度为准（在别的设备上看过也能接着看）
        ServerProgress.find(e.uri)?.let { p ->
            return p.viewOffsetMs.takeIf { it > 0 && (p.durationMs <= 0 || it < p.durationMs - 10_000) } ?: 0L
        }
        // 媒体库没给时长（例如 AIFF）时用上次播放时记下的，不然听完的歌会从结尾续播、马上结束
        val saved = library.history().firstOrNull { it.entry.uri == e.uri } ?: return 0L
        val duration = e.durationMs.takeIf { it > 0 } ?: saved.entry.durationMs
        return saved.positionMs.takeIf { duration <= 0 || it < duration - 10_000 } ?: 0L
    }

    // ---- 媒体服务器（Plex、Emby / Jellyfin）：把播放进度报给服务器，官方客户端里的「继续观看」「已看」跟着更新 ----

    private fun reportPlex(uri: String, state: String, positionMs: Long, durationMs: Long) {
        val p = ServerProgress.find(uri) ?: return
        val duration = durationMs.takeIf { it > 0 } ?: p.durationMs
        ServerProgress.updateOffset(uri, if (state == "stopped" && duration > 0 && positionMs >= duration * 0.9) 0 else positionMs)
        val server = CloudRegistry.serverLookup(p.serverId) ?: return
        val client = CloudRegistry.client(server) as? MediaBrowser ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { client.timeline(p.ratingKey, state, positionMs, duration) }
                .onFailure { android.util.Log.w("Voxtitle", "进度上报失败", it) }
        }
    }

    private fun reportPlexNow(state: String) {
        val e = _state.value.current ?: return
        if (player.mediaItemCount == 0) return
        reportPlex(e.uri, state, player.currentPosition.coerceAtLeast(0), player.duration)
    }

    private fun onEntryChanged(entry: MediaEntry) {
        if (_state.value.current?.uri == entry.uri) return
        cancelAll()
        sawFirstFrame = false
        frameWatch?.cancel()
        _state.update { it.copy(videoProblem = null, transcoding = false, liveClock = null, liveHint = null, asrInfo = null, translateInfo = null) }
        val uri = Uri.parse(entry.uri)
        coverage.clear()
        coverageLimit = UNKNOWN_END
        coverageKey = null
        coverageActive = false
        store.loadCoverage(uri)?.let { coverage.load(it); coverageActive = true }
        val videoLanguage = store.loadAsrLanguage(uri)
        // 直播字幕的时间是那一次直播的时间轴，下次打开对不上（会一遍遍冒出旧字幕）：不读缓存，旧版存下的也清掉
        if (entry.isLive) store.deleteCues(uri)
        // 旧版把俄语等非拉丁文字误判成「不用翻译」、原文直接当了译文，这里清掉让它重新翻译
        val cached = if (entry.isLive) emptyList() else store.load(uri).let { cues ->
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
        // 上次只识别了一部分：打开后接着识别
        if (coverageActive && entry.isVideo && !entry.isLive) viewModelScope.launch { transcribeJob?.join(); resumePartialSubtitles() }
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
        reportPlexNow(if (player.isPlaying) "playing" else "stopped")
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
                persistCues(uri)
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

    /**
     * 生成字幕：从当前播放位置开始识别，识别一段、翻译一段，播放到哪里字幕就出现在哪里；
     * 拖动进度条到还没识别的地方，马上改从那里开始；识别到结尾后再回头补前面的。
     * 直播流只识别正在播出的内容（见 [runLive]）。
     * @param alsoTranslate 不看自动翻译设置，识别的同时一定翻译（播放页的一键字幕按钮）
     * @param resume 接着上次没识别完的继续，不清空已有字幕
     */
    fun generateSubtitles(alsoTranslate: Boolean = false, resume: Boolean = false) {
        // 已经识别了一部分（中途退出、失败、网盘视频只识别了播放位置之后）：再点一次是接着识别没识别的部分，
        // 不清空重来；换了识别引擎或视频语言才从头识别
        val key = _state.value.asrSettings.let { "${it.asrEngine}/${it.asrLanguage}" }
        val partial = _state.value.current?.isLive != true && coverageActive && _state.value.cues.isNotEmpty() &&
            (coverageKey == null || coverageKey == key)
        val keep = resume || partial
        val uri = _state.value.videoUri ?: return
        if (transcribeJob?.isActive == true) return
        gapWatch?.cancel()
        if (!resume) lastAlsoTranslate = alsoTranslate
        coverageKey = key
        val live = _state.value.current?.isLive == true
        if (live && usingVlc) {
            _state.update { it.copy(error = VLC_NO_SUBTITLES, failedTask = Task.TRANSCRIBE) }
            return
        }
        translateJob?.cancel()
        // 手动改用语音识别：停掉直播自带字幕的翻译
        captionJob?.cancel()
        if (!keep || live) {
            coverage.clear()
            coverageActive = !live
            store.clearCoverage(uri)
            _state.update { it.copy(cues = emptyList(), liveClock = null, asrInfo = null, translateInfo = null) }
        }
        _state.update { it.copy(transcribing = true, error = null, failedTask = null) }
        nextCueId = (_state.value.cues.maxOfOrNull { it.id } ?: 0) + 1
        updateAutoAdvance()

        transcribeJob = viewModelScope.launch {
            // 识别出第一句之后才在播放页顶部显示用的识别服务（没识别出东西就不显示）
            var asrInfoWatch: Job? = null
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
                val streaming = useStreaming(settings)
                val engineName = (if (settings.asrEngine == AsrEngine.LOCAL) localModel.label else settings.asrEngine.label) +
                    if (streaming) " 流式" else ""
                // 播放页顶部显示用的是哪家、哪个模型（流式识别的模型由引擎自己定，不写）
                val asrModel = settings.asrValue(settings.asrEngine, "model").takeIf { !streaming && settings.asrEngine != AsrEngine.LOCAL && it.isNotEmpty() }
                val asrInfo = "识别：$engineName" + (asrModel?.let { " · $it" } ?: "")
                // Gemini 流式：先说清字幕时间是估的
                val note = if (streaming && settings.asrEngine == AsrEngine.GEMINI) "；$GEMINI_STREAMING_NOTE" else ""
                _state.update { it.copy(status = "正在识别语音（$engineName）…$note", progress = 0f, streamingAsr = streaming) }
                val cuesBefore = _state.value.cues.size
                asrInfoWatch = launch {
                    _state.map { it.cues.size }.first { it > cuesBefore }
                    _state.update { it.copy(asrInfo = asrInfo) }
                }
                val translate = (settings.autoTranslate || alsoTranslate) && settings.createTranslator() != null
                if ((settings.autoTranslate || alsoTranslate) && !translate) {
                    // 要翻译但翻译服务没设置好：说出来，不要只出原文让人以为翻译坏了
                    _state.update { it.copy(error = "字幕不会翻译：${settings.translatorProblem() ?: "翻译服务没有设置好"}。到「设置 → 字幕翻译」改好后，点「翻译」补上", failedTask = Task.TRANSLATE) }
                }
                inlineTranslate = translate
                if (translate) startTranslation()
                if (live) runLive(settings) else runOnDemand(settings, uri)
                ensureActive()
                if (!live) {
                    val count = _state.value.cues.size
                    _state.update { it.copy(status = "识别完成，共 $count 条字幕", progress = null) }
                    persistCues(uri)
                    saveSidecar()
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(status = "已取消", progress = null) }
                _state.value.videoUri?.let { if (!live) persistCues(it) }
                throw e
            } catch (e: Exception) {
                // 取消时被中断的网络请求会抛 IOException，不算失败
                if (!isActive) {
                    _state.update { it.copy(status = "已取消", progress = null) }
                    _state.value.videoUri?.let { if (!live) persistCues(it) }
                    throw CancellationException("cancelled")
                }
                android.util.Log.e("Voxtitle", "识别失败", e)
                _state.update { it.copy(status = "识别失败", progress = null, error = e.message ?: e.toString(), failedTask = Task.TRANSCRIBE) }
                _state.value.videoUri?.let { if (!live) persistCues(it) }
            } finally {
                asrInfoWatch?.cancel()
                _state.update { it.copy(transcribing = false, streamingAsr = false) }
                updateAutoAdvance()
            }
        }
    }

    /**
     * 按已识别的范围往前推进：从播放位置开始，到结尾后回头补前面没识别的。
     *
     * 网盘、NAS 等远程视频：识别要另外下载一遍视频，所以只领先播放 [REMOTE_AHEAD_MS]，
     * 领先够了就停下断开连接，播放追到只剩 [REMOTE_RESUME_MS] 时再接着识别；也不回头补播放位置之前的。
     * 这样流量和只看视频差不多，也不和播放抢带宽。
     */
    private suspend fun runOnDemand(settings: AppSettings, uri: Uri) {
        val dispatcher = if (settings.asrEngine == AsrEngine.LOCAL) Dispatchers.Default else Dispatchers.IO
        val remote = _state.value.current?.let { isRemote(it) } == true
        var duration = player.duration
        var tries = 0
        while (duration <= 0 && tries++ < 20) { delay(200); duration = player.duration }
        if (duration <= 0) duration = UNKNOWN_END
        coverageLimit = duration
        var cursor = player.currentPosition - LEAD_IN_MS
        var waiting = false
        var readFailures = 0
        while (true) {
            coroutineContext.ensureActive()
            if (coverage.isFull(duration)) break
            val gap = if (remote) {
                val position = player.currentPosition
                // 从播放位置到结尾都识别过了：结束（不然一直算"识别中"，播完不会自动放下一集）；
                // 前面没识别的部分：拖回去时接着识别（[startGapWatch]），下次打开这个视频时也会接着识别
                val ahead = coverage.nextGap((position - LEAD_IN_MS).coerceAtLeast(0), duration) ?: run {
                    startGapWatch(uri)
                    break
                }
                ahead.takeIf { it.first < position + REMOTE_RESUME_MS }
            } else {
                coverage.nextGap(cursor.coerceAtLeast(0), duration) ?: coverage.nextGap(0, duration) ?: break
            }
            if (gap == null) {
                // 远程视频已经领先播放够多了：等播放追上来（或拖到没识别的地方）
                if (!waiting) {
                    waiting = true
                    _state.update { it.copy(status = "已识别到播放位置之后几分钟，跟着播放继续识别（省流量）") }
                }
                delay(1000)
                continue
            }
            if (waiting) {
                waiting = false
                _state.update { it.copy(status = "正在识别语音…") }
            }
            val coveredBefore = coverage.coveredMs(duration)
            val restartAt = try {
                withContext(dispatcher) { recognizeRange(settings, uri, gap.first, gap.second, duration, remote) }
            } catch (e: com.aisub.player.asr.SourceReadException) {
                // 网盘、NAS 读到一半断了（网络抖动、直链过期）：换个新直链，从断开的地方接着识别
                persistCues(uri)
                // 断开前识别了不少：不算连续失败
                if (coverage.coveredMs(duration) > coveredBefore + 30_000) readFailures = 0
                val cloud = CloudRegistry.isCloud(uri.toString())
                // 播放正常、识别这一路被拒（115 等网盘限制同时连接时是 403，换新直链也一样）：多等一会儿再试，别一直连
                val refused = e.httpCode in REFUSED_CODES
                if (++readFailures > REMOTE_READ_RETRIES) {
                    val why = if (refused) "网盘拒绝了识别另开的连接（HTTP ${e.httpCode}），播放那一路不受影响。" else "${e.message}\n"
                    throw com.aisub.player.asr.SourceReadException("$why$CONCURRENCY_HINT。可以先下载到手机再识别", e)
                }
                android.util.Log.w("Voxtitle", "识别时读取视频中断（第 $readFailures 次），重连：${e.message}")
                if (cloud) CloudRegistry.invalidate(uri.toString())
                val wait = if (refused) 10_000L * readFailures else 2_000L * readFailures
                val reason = e.httpCode?.let { "HTTP $it" + if (refused) "，服务器拒绝了识别另开的连接" else "" } ?: "网络断了或太慢"
                _state.update { it.copy(status = "读取视频中断（$reason），${wait / 1000} 秒后重连…") }
                delay(wait)
                continue
            }
            readFailures = 0
            persistCues(uri)
            cursor = restartAt ?: gap.second
        }
    }

    /**
     * 识别 [from, to) 这一段。播放位置跳到了这一段够不到、也没识别过的地方时提前结束，返回新的位置；
     * 正常识别完（或到视频结尾）返回 null。识别过的范围记入 [coverage]。
     */
    private suspend fun recognizeRange(
        settings: AppSettings, uri: Uri, from: Long, to: Long, duration: Long, remote: Boolean = false,
    ): Long? {
        val ctx = coroutineContext
        val consumed = java.util.concurrent.atomic.AtomicLong(0)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val seekTo = java.util.concurrent.atomic.AtomicLong(-1)
        val sampleRate = Transcriber.SAMPLE_RATE
        val frontier = { from + consumed.get() * 1000 / sampleRate }
        var readError: com.aisub.player.asr.SourceReadException? = null

        val watcher = viewModelScope.launch(Dispatchers.Main) {
            var ticks = 0
            while (true) {
                delay(400)
                val p = player.currentPosition
                val away = p in 0 until duration && !coverage.contains(p) && (p < from - 1500 || p > frontier() + AHEAD_MS)
                if (away) {
                    seekTo.set(p)
                    stop.set(true)
                    break
                }
                // 远程视频领先播放够多了：先停下，断开这一路下载
                if (remote && p >= 0 && frontier() > p + REMOTE_AHEAD_MS) {
                    stop.set(true)
                    break
                }
                // 识别很长时每隔一会儿存一次，中途退出也不会白识别
                if (++ticks % 50 == 0) persistCues(uri)
            }
        }
        try {
            createEngine(settings).use { engine ->
                val cancelHandle = ctx.job.invokeOnCompletion { engine.cancel() }
                try {
                    val onCue: (Cue) -> Unit = { c ->
                        val start = c.startMs + from
                        if (start < to) addCues(listOf(c.copy(startMs = start, endMs = c.endMs + from)))
                    }
                    var lastProgress = 0L
                    try {
                        AudioDecoder(getApplication(), uri, authDataSourceFactory(getApplication())).decode(
                            startMs = from,
                            isActive = { ctx.isActive && !stop.get() },
                            onProgress = {},
                            onChunk = { chunk ->
                                engine.accept(chunk, onCue)
                                val n = consumed.addAndGet(chunk.size.toLong())
                                if (from + n * 1000 / sampleRate >= to) stop.set(true)
                                val now = System.currentTimeMillis()
                                if (duration < UNKNOWN_END && now - lastProgress > 500) {
                                    lastProgress = now
                                    val done = coverage.coveredMs(duration) + n * 1000 / sampleRate
                                    _state.update { it.copy(progress = (done.toFloat() / duration).coerceIn(0f, 1f)) }
                                }
                            },
                        )
                    } catch (e: com.aisub.player.asr.SourceReadException) {
                        // 远程视频读到一半断了：已经解码的部分照样识别完、记下来，由 [runOnDemand] 从断开处重连
                        if (!remote) throw e
                        readError = e
                    }
                    if (ctx.isActive) engine.finish(onCue)
                } finally {
                    cancelHandle.dispose()
                }
            }
            if (!ctx.isActive) return null
            readError?.let {
                if (frontier() > from) coverage.add(from, minOf(frontier(), to))
                throw it
            }
            // 没被打断就是一直解码到了音轨结尾；被打断或到了这一段的终点，就只算解码到的部分
            val reachedEnd = !stop.get()
            coverage.add(from, if (reachedEnd) maxOf(duration, frontier()) else minOf(frontier(), to))
            return seekTo.get().takeIf { it >= 0 }
        } finally {
            watcher.cancel()
        }
    }

    /**
     * 直播实时字幕，所有时间都放在同一条时间轴上（屏幕播放器的位置，见 [LiveTimeline]）：
     * 1. 另开一个不出声的播放器，用和屏幕播放器完全一样的条目（类型、请求头）在直播最新处解码声音，
     *    它天然比屏幕上的画面早十几秒；
     * 2. 解码时定期记下"音频第几毫秒 ↔ 直播内容位置"（[LiveAnchors]），识别出的每句都换算成屏幕播放器上的开始、结束时间；
     * 3. 字幕按屏幕播放器的位置显示，和画面用同一个时钟。字幕赶不上画面时把画面再往回拖一点（只拖一两次）；
     * 4. 原文先显示，译文由实时翻译（[translateLoop]）按播放位置就近补上。
     * 换算不了时间的直播流（普通 HTTP 流、VLC 内核）退回按到达先后显示。
     */
    private suspend fun runLive(settings: AppSettings) {
        val entry = _state.value.current ?: return
        val item = itemOf(entry)
        val anchors = LiveAnchors()
        liveDelayAdded = 0
        liveDelayChangedAt = 0
        _state.update {
            it.copy(
                cues = emptyList(), progress = null, status = "正在连接直播的声音…",
                liveClock = if (usingVlc) LiveClock.Arrival(android.os.SystemClock.elapsedRealtime()) else LiveClock.Stream,
            )
        }
        val incoming = java.util.concurrent.ConcurrentLinkedQueue<Pair<Cue, Long>>()
        val dispatcher = if (settings.asrEngine == AsrEngine.LOCAL) Dispatchers.Default else Dispatchers.IO
        // 识别引擎收到的音频总长（样本数）；重连后接着往后数，字幕的时间都相对它
        val samples = java.util.concurrent.atomic.AtomicLong(0)
        val heard = java.util.concurrent.atomic.AtomicBoolean(false)
        var lastError: String? = null

        val watchdog = com.aisub.player.live.LiveWatchdog(
            android.os.SystemClock.elapsedRealtime(),
            language = settings.asrLanguage.takeIf { it != "auto" }?.let { com.aisub.player.asr.AsrLanguages.label(it) },
            streaming = _state.value.streamingAsr,
            onDevice = settings.asrEngine == AsrEngine.LOCAL,
        )
        val shower = viewModelScope.launch {
            val waiting = ArrayList<Pair<Cue, Long>>()
            val state = LivePublishState()
            var tick = 0
            while (true) {
                delay(200)
                val now = android.os.SystemClock.elapsedRealtime()
                if (heard.getAndSet(false)) {
                    if (_state.value.cues.isEmpty() && _state.value.liveHint == null) {
                        _state.update { it.copy(status = "已连上直播，正在识别…（第一句要等几秒）") }
                    }
                    watchdog.audio(now)
                }
                val before = waiting.size
                while (true) waiting.add(incoming.poll() ?: break)
                if (waiting.size > before) watchdog.cue(now)
                if (waiting.isNotEmpty()) publishLive(waiting, anchors, state)
                // 每秒看一次：一直没有字的时候在画面上说明原因
                if (++tick % 5 == 0) {
                    val hint = watchdog.hint(now)
                    if (hint != _state.value.liveHint) {
                        _state.update { it.copy(liveHint = hint, status = hint ?: if (it.cues.isEmpty()) "正在识别…" else "实时字幕") }
                    }
                }
            }
        }
        try {
            withContext(dispatcher) {
                createEngine(settings).use { engine ->
                    (engine as? com.aisub.player.asr.cloud.ChunkedCloudEngine)?.useChunks(LIVE_FIRST_CHUNK_SEC, LIVE_CHUNK_SEC)
                    val cancelHandle = coroutineContext.job.invokeOnCompletion { engine.cancel() }
                    val onCue: (Cue) -> Unit = { incoming.add(it to android.os.SystemClock.elapsedRealtime()) }
                    // 解码和识别分开两条线程：识别引擎会等（流式识别控制发送速度、断线重连，
                    // 分段识别在途太多时等结果），不能卡住解码线程，不然收不到新的声音，
                    // 画面上会误报「声音断了」，卡久了解码器还会掉出直播窗口、真的断开重连
                    val pending = java.util.concurrent.LinkedBlockingQueue<FloatArray>()
                    val decodingDone = java.util.concurrent.atomic.AtomicBoolean(false)
                    val feeder = launch(Dispatchers.IO) {
                        while (true) {
                            val chunk = pending.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                            if (chunk == null) {
                                if (decodingDone.get()) break else continue
                            }
                            engine.accept(chunk, onCue)
                        }
                    }
                    try {
                        // 直播流断了或一连上就结束时，重连几次
                        var quickEnds = 0
                        var connects = 0
                        while (coroutineContext.isActive && quickEnds < 3) {
                            val startedAt = System.currentTimeMillis()
                            // 收不到新声音太久就断开重连：解码器可能卡住了（不报错也不结束）
                            val lastAudioAt = java.util.concurrent.atomic.AtomicLong(startedAt)
                            val gotAudio = java.util.concurrent.atomic.AtomicBoolean(false)
                            val stalled = java.util.concurrent.atomic.AtomicBoolean(false)
                            android.util.Log.i("Voxtitle", "直播声音：第 ${++connects} 次连接 ${entry.uri}")
                            val baseMs = samples.get() * 1000 / Transcriber.SAMPLE_RATE
                            anchors.newSegment(baseMs)
                            var lastWall = -1L
                            var lastMs = -1L
                            var anchorCount = 0
                            try {
                                com.aisub.player.asr.ExoAudioDecoder(
                                    getApplication(), Uri.parse(entry.uri), authDataSourceFactory(getApplication()),
                                    mediaItem = item, lowestBitrate = true, diagnostics = true,
                                    onTick = { p, ms ->
                                        // 解码器在追缓冲时一下子解出很多，这时读到的位置不准；
                                        // 只在它停下来等新数据、或按正常速度解码时记锚点
                                        val wall = android.os.SystemClock.elapsedRealtime()
                                        val steady = lastWall >= 0 && (ms == lastMs || kotlin.math.abs((ms - lastMs) - (wall - lastWall)) <= 200)
                                        lastWall = wall
                                        lastMs = ms
                                        if (steady && ms > 0) LiveTimeline.keyOf(p)?.let {
                                            anchors.add(baseMs + ms, it)
                                            // 诊断：对照点（识别用的音频时间 ↔ 直播内容位置）
                                            if (anchorCount++ % 10 == 0) {
                                                android.util.Log.i("Voxtitle-map", "对照点 音频 ${baseMs + ms} → 分片 ${it.seq} +${it.ms}ms；解码器进度 ${p.currentPosition} 离直播最新处 ${p.currentLiveOffset}ms")
                                            }
                                        }
                                    },
                                ).decode(
                                    isActive = {
                                        val limit = if (gotAudio.get()) LIVE_AUDIO_STALL_MS else LIVE_AUDIO_FIRST_MS
                                        val quiet = System.currentTimeMillis() - lastAudioAt.get()
                                        if (quiet > limit && stalled.compareAndSet(false, true)) {
                                            android.util.Log.w("Voxtitle", "直播声音：${quiet / 1000} 秒没收到新声音，断开重连")
                                        }
                                        coroutineContext.isActive && !stalled.get()
                                    },
                                    onProgress = {},
                                    onChunk = { chunk ->
                                        if (!gotAudio.getAndSet(true)) {
                                            android.util.Log.i("Voxtitle", "直播声音：连上了，${System.currentTimeMillis() - startedAt} ms 收到第一段")
                                        }
                                        lastAudioAt.set(System.currentTimeMillis())
                                        samples.addAndGet(chunk.size.toLong())
                                        heard.set(true)
                                        pending.add(chunk)
                                    },
                                )
                                if (stalled.get()) lastError = "直播声音卡住了"
                                else if (coroutineContext.isActive) android.util.Log.w("Voxtitle", "直播声音：直播流结束了，重连")
                            } catch (e: java.io.IOException) {
                                // 直播流断开、解不了：记下原因，重连
                                if (!coroutineContext.isActive) throw e
                                android.util.Log.w("Voxtitle", "直播声音解码中断：${e.message}")
                                lastError = e.message
                            }
                            if (System.currentTimeMillis() - startedAt < 10_000) quickEnds++ else quickEnds = 0
                            delay(500)
                        }
                        // 等识别把已经收到的声音处理完
                        decodingDone.set(true)
                        feeder.join()
                        if (coroutineContext.isActive) engine.finish(onCue)
                    } finally {
                        decodingDone.set(true)
                        feeder.cancel()
                        cancelHandle.dispose()
                    }
                }
            }
            delay(2000)
            if (_state.value.cues.isEmpty() && lastError != null) throw java.io.IOException("直播的声音连不上：$lastError。$CONCURRENCY_HINT；直播有自带字幕时可改用「翻译自带字幕」，连不上也能读屏幕上的字幕")
            _state.update { it.copy(status = "直播字幕已停止：直播的声音断了" + (lastError?.let { "（$it）" } ?: "")) }
        } finally {
            shower.cancel()
            // 时钟留着：已有字幕的时间是按它算的，清掉后会按播放器的窗口位置比，旧字幕反复冒出来。
            // 换台、重新识别、改看自带字幕时各自会重设
            _state.update { it.copy(liveHint = null) }
            if (liveDelayAdded > 0) restoreLivePlayback(entry)
        }
    }

    /** 直播字幕上屏过程中要记住的东西（只在主线程用） */
    private class LivePublishState {
        /** 按到达先后显示时，上一句的结束时间 */
        var lastEnd = 0L

        /** 换算出的时间明显不对的次数，多了就不再换算 */
        var badMappings = 0
    }

    /** 已经识别出的直播字幕：换算到屏幕播放器的时间上，加进字幕列表。换算不了的先留在 [waiting] 里等锚点。 */
    private fun publishLive(waiting: MutableList<Pair<Cue, Long>>, anchors: LiveAnchors, ps: LivePublishState) {
        val clock = _state.value.liveClock ?: return
        val wall = android.os.SystemClock.elapsedRealtime()
        val shown = ArrayList<Cue>()
        if (clock == LiveClock.Stream) {
            val now = LiveTimeline.periodPosition(exoPlayer)
            var latest = Long.MIN_VALUE
            val mapped = ArrayList<Pair<Cue, Long>>()
            val it = waiting.iterator()
            while (it.hasNext()) {
                val (c, arrivedAt) = it.next()
                val start = anchors.keyAt(c.startMs)?.let { k -> LiveTimeline.positionOf(k, exoPlayer) }
                if (start == null) {
                    // 等了十几秒还换算不了：这个直播流对不上时间，改成按到达先后显示
                    if (wall - arrivedAt > LIVE_MAP_TIMEOUT_MS) return switchToArrival(waiting, ps, "这个直播流对不上时间")
                    continue
                }
                it.remove()
                if (start !in now - 60_000..now + 180_000) {
                    if (++ps.badMappings >= 3) return switchToArrival(waiting, ps, "换算出的时间不对")
                    continue
                }
                ps.badMappings = 0
                // 诊断：这句字幕换算到屏幕上的时间，和它到达时屏幕播到哪了（差为负 = 程序认为字幕晚了）
                android.util.Log.i("Voxtitle-map", "字幕 音频 ${c.startMs} → 屏幕 $start，屏幕现在 $now（差 ${start - now}ms）；屏幕离直播最新处 ${exoPlayer.currentLiveOffset}ms「${c.text.take(12)}」")
                mapped += c to start
                latest = maxOf(latest, now + LIVE_SAFETY_MS - start)
            }
            // 字幕赶不上画面：画面再往回拖一点，这几句和之后的字幕就都能赶在画面之前准备好
            val after = if (latest > 0 && delayLive(latest)) LiveTimeline.periodPosition(exoPlayer) else now
            for ((c, start) in mapped) {
                val length = (c.endMs - c.startMs).coerceIn(LIVE_MIN_CUE_MS, 8_000)
                // 还是晚了（画面已经播过这句）也显示出来，从现在开始
                val from = maxOf(start, after)
                shown += Cue(nextCueId++, from, from + length, c.text, c.translation)
            }
        } else {
            val now = wall - (clock as LiveClock.Arrival).baseMs
            // 积压太多（翻译或识别变慢）时不再排队，直接显示最新的
            var cursor = if (ps.lastEnd - now > LIVE_MAX_BACKLOG_MS) now else maxOf(now, ps.lastEnd)
            val first = waiting.first().first.startMs
            for ((c, _) in waiting) {
                val length = (c.endMs - c.startMs).coerceIn(LIVE_MIN_CUE_MS, 6_000)
                // 保持这一批里各句原来的间隔
                val start = maxOf(cursor, now + (c.startMs - first))
                cursor = start + length
                shown += Cue(nextCueId++, start, cursor, c.text, c.translation)
            }
            ps.lastEnd = cursor
            waiting.clear()
        }
        if (shown.isEmpty()) return
        _state.update { st ->
            st.copy(
                cues = (st.cues + shown).sortedBy { it.startMs }.takeLast(LIVE_KEEP_CUES),
                status = if (liveDelayAdded > 0) "实时字幕 · 画面延后了 ${liveDelayAdded / 1000} 秒，和说话对齐" else "实时字幕",
            )
        }
    }

    private fun switchToArrival(waiting: MutableList<Pair<Cue, Long>>, ps: LivePublishState, why: String) {
        android.util.Log.w("Voxtitle", "直播字幕改为按到达顺序显示：$why")
        ps.lastEnd = 0
        _state.update { it.copy(cues = emptyList(), liveClock = LiveClock.Arrival(android.os.SystemClock.elapsedRealtime())) }
        if (waiting.isNotEmpty()) publishLive(waiting, LiveAnchors(), ps)
    }

    /** 实时字幕让画面额外延后了多久（毫秒） */
    private var liveDelayAdded = 0L
    private var liveDelayChangedAt = 0L

    /**
     * 字幕比画面晚了 [lateMs]：把画面往回拖这么多（再多留一秒余量）。隔一会儿才调一次，总共最多 [LIVE_MAX_DELAY_MS]。
     * 带绝对时间的直播流播放器会自动加速追回直播位置，所以同时把目标延迟加上、固定 1 倍速。
     */
    private fun delayLive(lateMs: Long): Boolean {
        val wall = android.os.SystemClock.elapsedRealtime()
        if (usingVlc || liveDelayAdded >= LIVE_MAX_DELAY_MS) return false
        if (liveDelayChangedAt > 0 && wall - liveDelayChangedAt < 15_000) return false
        val p = exoPlayer
        if (!p.isCurrentMediaItemLive) return false
        val step = minOf(lateMs + 1_000, LIVE_MAX_DELAY_MS - liveDelayAdded, p.currentPosition - 1_000)
        if (step < 500) return false
        val offset = p.currentLiveOffset
        if (offset != C.TIME_UNSET) {
            val item = p.currentMediaItem ?: return false
            val config = item.liveConfiguration.buildUpon()
                .setTargetOffsetMs(offset + step)
                .setMinPlaybackSpeed(1f)
                .setMaxPlaybackSpeed(1f)
                .build()
            p.replaceMediaItem(p.currentMediaItemIndex, item.buildUpon().setLiveConfiguration(config).build())
        }
        p.seekTo(p.currentPosition - step)
        liveDelayAdded += step
        liveDelayChangedAt = wall
        android.util.Log.i("Voxtitle", "直播画面延后 $step ms，共 $liveDelayAdded ms")
        return true
    }

    /** 实时字幕停了：恢复正常的直播延迟，回到直播最新位置。 */
    private fun restoreLivePlayback(entry: MediaEntry) {
        liveDelayAdded = 0
        if (usingVlc) return
        val p = exoPlayer
        if (p.currentMediaItem?.mediaId != entry.uri) return
        p.replaceMediaItem(p.currentMediaItemIndex, itemOf(entry))
        p.seekToDefaultPosition()
    }

    /** 跳到字幕时间轴上的某个时间（点字幕列表里的一句）；直播按到达先后显示时跳不了。 */
    fun seekToSubtitleTime(ms: Long) {
        when (_state.value.liveClock) {
            null -> player.seekTo(ms)
            LiveClock.Stream -> if (!usingVlc) exoPlayer.seekTo(ms - LiveTimeline.periodPosition(exoPlayer, 0))
            is LiveClock.Arrival -> {}
        }
    }

    /** 字幕时间轴上的"现在"：普通视频就是播放位置；直播实时字幕见 [LiveClock]。 */
    fun subtitleTime(positionMs: Long = player.currentPosition): Long = when (val c = _state.value.liveClock) {
        null -> positionMs
        LiveClock.Stream -> if (usingVlc) positionMs else LiveTimeline.periodPosition(exoPlayer, positionMs)
        is LiveClock.Arrival -> android.os.SystemClock.elapsedRealtime() - c.baseMs
    }

    /** 新识别出的字幕按开始时间排进列表；编号用不重复的新号，翻译、朗读靠编号找字幕。 */
    private fun addCues(new: List<Cue>) {
        val numbered = new.map { it.copy(id = nextCueId++) }
        _state.update { s -> s.copy(cues = (s.cues + numbered).sortedBy { it.startMs }) }
    }

    /** 存字幕；只识别了一部分时连同已识别的范围一起存，下次打开接着识别。 */
    private fun persistCues(uri: Uri) {
        if (_state.value.videoUri != uri) return
        // 直播字幕只在这次观看时有用，不存
        if (_state.value.current?.isLive == true) return
        store.save(uri, _state.value.cues)
        if (!coverageActive) return
        if (coverage.isFull(coverageLimit)) {
            store.clearCoverage(uri)
            coverageActive = false
        } else {
            store.saveCoverage(uri, coverage.snapshot())
        }
    }

    /** 字幕换成了导入的、视频自带的等别的来源：不再按识别范围续识别。 */
    private fun dropCoverage(uri: Uri) {
        coverageActive = false
        coverage.clear()
        store.clearCoverage(uri)
    }

    /** 打开上次没识别完的视频：接着识别（识别设置不完整或要下载模型时不自动做）。 */
    private fun resumePartialSubtitles() {
        val s = _state.value.asrSettings
        if (s.asrProblem() != null) return
        if (s.asrEngine == AsrEngine.LOCAL && !models.isReady(ModelCatalog.byId(s.localModel))) return
        generateSubtitles(alsoTranslate = false, resume = true)
    }

    /** 这次识别用流式：打开了流式识别开关、引擎支持、正在放直播或远程视频（本地视频照用分段识别，跑得比播放快） */
    private fun useStreaming(settings: AppSettings): Boolean {
        val current = _state.value.current ?: return false
        return settings.streamingAsr && settings.streamingAvailable && (current.isLive || isRemote(current))
    }

    private fun createEngine(settings: AppSettings): SpeechEngine {
        if (useStreaming(settings)) settings.createStreamingEngine()?.let { return it }
        return createChunkedEngine(settings)
    }

    private fun createChunkedEngine(settings: AppSettings): SpeechEngine = when (settings.asrEngine) {
        AsrEngine.LOCAL -> {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            Transcriber(ModelCatalog.byId(settings.localModel), models, com.aisub.player.asr.AsrLanguages.primary(settings.asrLanguage), threads)
        }
        else -> settings.createCloudEngine().also { e ->
            if (e is com.aisub.player.asr.cloud.GeminiTranscriber && inlineTranslate) e.translateTo = settings.targetLanguage
        }
    }

    // ---- 本地模型管理（设置页用） ----

    fun isModelInstalled(model: LocalModel) = models.isInstalled(model)

    fun deleteModel(model: LocalModel) = models.delete(model)

    /** 翻译所有还没有译文的字幕。识别进行中时会持续等待新字幕。 */
    /** 播放页顶部显示的翻译服务和模型；Azure 翻译的「模型」一栏填的是区域，不显示 */
    private fun translateInfoOf(settings: AppSettings): String {
        val provider = settings.provider
        val model = settings.configOf(provider).model.takeIf { provider != Provider.AZURE_TRANSLATOR && it.isNotEmpty() }
        return "翻译：${provider.label}" + (model?.let { " · $it" } ?: "")
    }

    fun startTranslation() {
        if (translateJob?.isActive == true) return
        val settings = _state.value.settings
        val translator = settings.createTranslator()
        if (translator == null) {
            _state.update { it.copy(error = settings.translatorProblem() ?: "翻译服务配置不完整", failedTask = Task.TRANSLATE) }
            return
        }
        // 真的有句子翻出来了才在播放页顶部显示（例如中文直播一句都不用翻，就不显示翻译服务）
        pendingTranslateInfo = translateInfoOf(settings)
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
                _state.value.videoUri?.let { persistCues(it) }
                saveSidecar()
            }
        }
    }

    /**
     * 同时翻译几批：先翻播放位置之后的，再回头翻前面的。
     *
     * 两种方案：
     * - 本地视频（普通翻译）：识别还在进行时，没有在途的请求就马上翻手上有的，有在途的请求就攒够 [BATCH_SIZE] 句再发，
     *   上下文多、请求少，译文更连贯；
     * - 直播和网盘、NAS 等远程文件（实时翻译）：
     *   - 本地翻译（本地模型、Offline Translator）没有限流，识别出来就翻（快速翻译），一次最多 [REALTIME_BATCH] 句；
     *   - 云端翻译：播放位置之后 [REALTIME_HORIZON_MS] 内的句子识别出来就翻，一次最多 [REALTIME_BATCH] 句、不攒批，
     *     更远的照普通方案攒批翻，不然识别跑在播放前面很远时会一下子发出一大串小请求、被限流。
     *   直播已经播过去的句子不再翻。
     *   实时翻译失败了不停下来：原文照常显示，等一会儿再试。
     */
    private suspend fun translateLoop(translator: Translator) = coroutineScope {
        val current = _state.value.current
        val live = current?.isLive == true
        val realtime = live || current?.let { isRemote(it) } == true
        val batchSize = if (realtime) REALTIME_BATCH else BATCH_SIZE
        // 本地翻译不怕请求多：实时翻译时所有句子都马上翻
        val localTranslator = _state.value.settings.provider.isLocal
        val horizon = if (localTranslator) Long.MAX_VALUE / 4 else REALTIME_HORIZON_MS
        val slots = Semaphore(TRANSLATE_PARALLEL)
        val inFlight = HashSet<Int>()
        var running = 0
        var skipped = 0
        var failures = 0
        var retryAt = 0L
        while (true) {
            // 实时翻译刚失败过：先等一会儿再发
            val wait = retryAt - android.os.SystemClock.elapsedRealtime()
            if (wait > 0) {
                delay(minOf(wait, 250))
                continue
            }
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
            val position = subtitleTime()
            // 直播：画面已经播过去的不翻了
            val pending = snapshot.cues.filter { it.translation == null && it.id !in inFlight && (!live || it.endMs >= position - 2_000) }
            if (pending.isEmpty()) {
                if (running > 0 || snapshot.transcribing || snapshot.liveCaptions) {
                    delay(250)
                    continue
                }
                if (skipped > 0) {
                    _state.update { it.copy(status = "翻译完成，其中 $skipped 句原文已是${it.settings.targetLanguage}，没有翻译") }
                }
                return@coroutineScope
            }
            val ahead = pending.filter { it.endMs >= position - 5_000 }
            // 实时翻译只对快播到的句子不攒批
            // 云端翻译时，播放位置之前的句子（拖动进度条跳过的）不着急，照普通方案攒批
            val quick = if (localTranslator) ahead.ifEmpty { pending } else ahead
            val urgent = realtime && quick.firstOrNull()?.let { it.startMs < position + horizon } == true
            val size = if (urgent || live) batchSize else BATCH_SIZE
            val batch = (if (urgent) quick.filter { it.startMs < position + horizon } else ahead.ifEmpty { pending }).take(size)
            if (!urgent && !live && snapshot.transcribing && batch.size < size && running > 0) {
                delay(250)
                continue
            }
            slots.acquire()
            val ids = batch.map { it.id }
            inFlight += ids
            running++
            val first = snapshot.cues.indexOfFirst { it.id == batch.first().id }
            val context = snapshot.cues.subList(maxOf(0, first - CONTEXT_SIZE), first).map { it.text }
            launch {
                try {
                    val results = translator.translate(batch.map { it.text }, context, snapshot.settings.targetLanguage)
                    if (results.any { it != null }) _state.update { it.copy(translateInfo = pendingTranslateInfo) }
                    // 模型漏掉的条目用原文占位，避免死循环反复请求
                    val translated = batch.indices.associate { i -> batch[i].id to (results[i] ?: batch[i].text) }
                    _state.update { s ->
                        s.copy(cues = s.cues.map { c -> translated[c.id]?.let { c.copy(translation = it) } ?: c })
                    }
                    failures = 0
                    if (!_state.value.transcribing && !live) {
                        val done = _state.value.cues.count { it.translation != null }
                        _state.update { it.copy(status = "翻译中 $done/${it.cues.size}") }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 实时翻译失败不停下来（限流、网络抖动常见）：原文照常显示，越失败等得越久；
                    // 远程文件连续失败多次多半是配置问题，停下来报错
                    failures++
                    if (!realtime || (!live && failures >= REALTIME_MAX_FAILURES)) throw e
                    android.util.Log.w("Voxtitle", "实时翻译失败（第 $failures 次）：${e.message}")
                    retryAt = android.os.SystemClock.elapsedRealtime() + minOf(3_000L shl minOf(failures - 1, 4), 30_000L)
                    _state.update { it.copy(status = "翻译暂时失败，稍后重试：${e.message?.take(60)}") }
                } finally {
                    inFlight -= ids.toSet()
                    running--
                    slots.release()
                }
            }
        }
    }

    /** 网盘、NAS、网络地址等不在手机上的文件：边下边识别，翻译走实时方案。 */
    private fun isRemote(e: MediaEntry) = Uri.parse(e.uri).scheme?.lowercase().let { it != null && it !in LOCAL_SCHEMES }

    /** 识别结束后盯着播放位置：拖到没识别过的地方，就接着识别那里（等这次识别真正结束后才开始） */
    private fun startGapWatch(uri: Uri) {
        gapWatch?.cancel()
        gapWatch = viewModelScope.launch {
            while (true) {
                delay(1000)
                if (_state.value.videoUri != uri || !coverageActive) return@launch
                if (transcribeJob?.isActive == true) continue
                val p = player.currentPosition
                if (p in 0 until coverageLimit && !coverage.contains(p)) {
                    generateSubtitles(alsoTranslate = lastAlsoTranslate, resume = true)
                    return@launch
                }
            }
        }
    }

    fun cancelAll() {
        gapWatch?.cancel()
        transcribeJob?.cancel()
        translateJob?.cancel()
        embeddedJob?.cancel()
        captionJob?.cancel()
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
                    _state.update { s ->
                        s.copy(cues = s.cues.map { if (it.id == cueId) it.copy(translation = result) else it }, translateInfo = translateInfoOf(settings))
                    }
                    _state.value.videoUri?.let { persistCues(it) }
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
        _state.value.videoUri?.let { persistCues(it) }
        saveSidecar()
        ttsReader.reset()
    }

    fun deleteCue(cueId: Int) {
        _state.update { s -> s.copy(cues = s.cues.filter { it.id != cueId }) }
        _state.value.videoUri?.let { persistCues(it) }
        saveSidecar()
        ttsReader.reset()
    }

    private val sidecarLock = Mutex()

    /** 开了「在视频旁保存字幕文件」时，把当前字幕写到本地视频旁边的同名 .srt */
    private fun saveSidecar() {
        val s = _state.value
        val entry = s.current ?: return
        if (!s.settings.autoSaveSrt || s.cues.isEmpty() || entry.isLive) return
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
     * @param translate 载入后一定翻译，不看自动翻译设置
     */
    fun extractEmbeddedSubtitles(translate: Boolean = false) {
        // 直播读不完整个文件：改成边播边翻译
        if (_state.value.current?.isLive == true) return startLiveCaptions()
        val uri = _state.value.videoUri ?: return
        if (embeddedJob?.isActive == true) return
        translateEmbedded = translate
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

    /**
     * 直播自带的隐藏式字幕（CEA-608 等）翻译成目标语言，和画面对齐：
     * 1. 另开一路不出声的解码（[ExoAudioDecoder]，和语音识别的实时字幕一样），在直播最新处读字幕，
     *    比屏幕上的画面早十几秒；字幕拼成整句（[LiveCaptions]），记下这句在直播内容里的位置（[LiveKey]）；
     * 2. 每句换算到屏幕播放器的时间上（[LiveClock.Stream]），提前交给实时翻译，到时候原文、译文一起出现；
     *    赶不上画面时把画面往回拖一点（[delayLive]）；
     * 3. 那一路连不上、读不到字幕或者换算不了时间（不是 HLS、也没有绝对时间）时，退回读屏幕播放器自己的字幕：
     *    一句说完才出现，比画面晚一点。
     * 这期间画面上不再原样显示自带字幕，免得同一句出现两遍。想改用语音识别时点「语音识别生成字幕」，会先停掉这里。
     */
    fun startLiveCaptions() {
        val entry = _state.value.current?.takeIf { it.isLive } ?: return
        if (usingVlc) {
            _state.update { it.copy(error = VLC_NO_SUBTITLES) }
            return
        }
        val p = exoPlayer
        val texts = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        if (texts.isEmpty()) {
            _state.update { it.copy(error = "这个直播没有自带字幕，可以用语音识别生成字幕") }
            return
        }
        // 正在进行的语音识别、翻译：停掉，等它们真正收尾（收尾时会改状态）后再接手
        val previous = listOfNotNull(transcribeJob, translateJob, embeddedJob, captionJob)
        cancelAll()
        // 自带字幕关着（选了「不显示」）时打开第一条，不然收不到字幕
        val chosen = texts.firstOrNull { it.isSelected }?.takeIf { C.TRACK_TYPE_TEXT !in p.trackSelectionParameters.disabledTrackTypes }
            ?: texts[0].also { extras.selectTrack(C.TRACK_TYPE_TEXT, it, 0) }
        val language = chosen.getTrackFormat(0).language
        // 编号接着往后排：还没回来的旧翻译按编号找字幕，不能和新字幕撞号
        fun takeOver() = _state.update {
            it.copy(
                cues = emptyList(), liveClock = LiveClock.Stream, liveHint = null, liveCaptions = true,
                asrInfo = "字幕：直播自带", translateInfo = null, status = "正在读取直播自带的字幕…",
                error = null, failedTask = null,
            )
        }
        takeOver()

        // 提前读的那一路：拼好的句子和它在直播内容里的起止位置
        val ahead = java.util.concurrent.ConcurrentLinkedQueue<AheadCaption>()
        // 那一路读到了字幕，而且时间换算得了：屏幕播放器的字幕就不再用
        val useAhead = java.util.concurrent.atomic.AtomicBoolean(false)
        val aheadUsable = java.util.concurrent.atomic.AtomicBoolean(true)
        val screen = LiveCaptions()
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                if (!useAhead.get()) publishCaptions(screen.update(captionLines(cueGroup.cues), android.os.SystemClock.elapsedRealtime()))
            }
        }
        captionJob = viewModelScope.launch {
            try {
                previous.joinAll()
                takeOver()
                liveDelayAdded = 0
                liveDelayChangedAt = 0
                p.addListener(listener)
                // 翻译没设置好时也调用，好提示出原因；原文照样显示
                startTranslation()
                launch(Dispatchers.IO) {
                    try {
                        readCaptionsAhead(entry, language, ahead) { isActive && aheadUsable.get() }
                    } catch (e: Exception) {
                        if (!isActive) throw e
                        android.util.Log.w("Voxtitle", "提前读直播字幕失败，改读屏幕上的字幕：${e.message}")
                    } finally {
                        aheadUsable.set(false)
                    }
                }
                val ps = AheadPublishState()
                while (true) {
                    delay(300)
                    val now = android.os.SystemClock.elapsedRealtime()
                    val batch = generateSequence { ahead.poll() }.toList()
                    if (batch.isNotEmpty() && aheadUsable.get()) {
                        if (publishAhead(batch, ps)) {
                            if (!useAhead.getAndSet(true)) android.util.Log.i("Voxtitle", "直播字幕：改用提前读的字幕，和画面对齐")
                        } else if (++ps.unmapped >= 3) {
                            android.util.Log.w("Voxtitle", "直播字幕：提前读的字幕换算不了时间，改读屏幕上的字幕")
                            aheadUsable.set(false)
                        }
                    }
                    if (!aheadUsable.get()) useAhead.set(false)
                    if (!useAhead.get()) publishCaptions(screen.tick(now))
                }
            } finally {
                p.removeListener(listener)
                // 被新开的一次顶掉时不要把它的状态清掉；时钟留着，已有字幕按它算时间（见 runLive 的收尾）
                if (captionJob?.isActive != true) {
                    _state.update { it.copy(liveCaptions = false) }
                    if (liveDelayAdded > 0 && !_state.value.transcribing) restoreLivePlayback(entry)
                }
            }
        }
    }

    /** 提前读到的一句直播字幕：起止是直播内容位置 */
    private data class AheadCaption(val text: String, val start: LiveKey, val end: LiveKey)

    /** 提前读的字幕上屏时要记住的东西（只在主线程用） */
    private class AheadPublishState {
        /** 连续几批都换算不了时间 */
        var unmapped = 0
    }

    /**
     * 另开一路在直播最新处读自带字幕，拼成整句放进 [out]。阻塞调用，断了重连，[isActive] 返回 false 时结束。
     * 解码线程上：字幕变化时、每 300 毫秒各拼一次，句子的起点记成这句第一次出现字时的直播内容位置。
     */
    private fun readCaptionsAhead(entry: MediaEntry, language: String?, out: java.util.Queue<AheadCaption>, isActive: () -> Boolean) {
        var quickEnds = 0
        while (isActive() && quickEnds < 3) {
            val assembler = LiveCaptions()
            var startKey: LiveKey? = null
            fun emit(sentences: List<String>, key: LiveKey?) {
                if (key == null || sentences.isEmpty()) return
                // 一次凑成好几句时，把这段时间平分给它们
                val from = startKey ?: key
                val span = if (from.seq == key.seq) key.ms - from.ms else 0L
                sentences.forEachIndexed { i, s ->
                    val a = from + span * i / sentences.size
                    out.add(AheadCaption(s, a, from + span * (i + 1) / sentences.size))
                }
                startKey = null
            }
            val startedAt = System.currentTimeMillis()
            try {
                com.aisub.player.asr.ExoAudioDecoder(
                    getApplication(), Uri.parse(entry.uri), authDataSourceFactory(getApplication()),
                    mediaItem = itemOf(entry), lowestBitrate = true,
                    onTick = { player, _ -> emit(assembler.tick(android.os.SystemClock.elapsedRealtime()), LiveTimeline.keyOf(player)) },
                    onCues = { player, cues ->
                        val key = LiveTimeline.keyOf(player)
                        val lines = captionLines(cues)
                        if (startKey == null && lines.isNotEmpty()) startKey = key
                        emit(assembler.update(lines, android.os.SystemClock.elapsedRealtime()), key)
                    },
                    captionLanguage = language,
                ).decode(isActive = isActive, onProgress = {}, onChunk = {})
            } catch (e: java.io.IOException) {
                android.util.Log.w("Voxtitle", "提前读直播字幕中断：${e.message}")
            }
            if (System.currentTimeMillis() - startedAt < 10_000) quickEnds++ else quickEnds = 0
            if (isActive()) Thread.sleep(500)
        }
    }

    /**
     * 提前读到的字幕换算到屏幕播放器的时间上，加进字幕列表；赶不上画面时把画面往回拖。
     * @return 是否换算成功（一句都换算不了说明这个直播流对不上时间）
     */
    private fun publishAhead(batch: List<AheadCaption>, ps: AheadPublishState): Boolean {
        if (!_state.value.liveCaptions) return true
        val now = LiveTimeline.periodPosition(exoPlayer)
        val mapped = batch.mapNotNull { c ->
            val start = LiveTimeline.positionOf(c.start, exoPlayer) ?: return@mapNotNull null
            val end = LiveTimeline.positionOf(c.end, exoPlayer) ?: start
            Triple(c.text, start, end).takeIf { start in now - 60_000..now + 180_000 }
        }
        if (mapped.isEmpty()) return false
        ps.unmapped = 0
        // 字幕得比画面早一点到（留出翻译的时间），赶不上就把画面往回拖
        val late = mapped.maxOf { now + LIVE_SAFETY_MS - it.second }
        val after = if (late > 0 && delayLive(late)) LiveTimeline.periodPosition(exoPlayer) else now
        val cues = mapped.map { (text, start, end) ->
            val from = maxOf(start, after)
            Cue(nextCueId++, from, (end + LIVE_CAPTION_LINGER_MS).coerceIn(from + LIVE_MIN_CUE_MS, from + LIVE_CAPTION_MAX_MS), text)
        }
        addCaptionCues(cues, if (liveDelayAdded > 0) "实时翻译直播自带字幕 · 画面延后了 ${liveDelayAdded / 1000} 秒，和字幕对齐" else "实时翻译直播自带字幕")
        return true
    }

    /** 拼好的整句上屏（读屏幕播放器字幕时）：从现在开始显示，上一句显示到这一句出现为止 */
    private fun publishCaptions(sentences: List<String>) {
        if (sentences.isEmpty() || !_state.value.liveCaptions) return
        val start = subtitleTime()
        addCaptionCues(listOf(Cue(nextCueId++, start, start + LIVE_CAPTION_MAX_MS, sentences.joinToString(" "))), "实时翻译直播自带字幕")
    }

    /** 直播字幕加进列表：按开始时间排好，和后一句重叠的前一句提前结束（同一时间只显示一句） */
    private fun addCaptionCues(new: List<Cue>, status: String) {
        _state.update { s ->
            val all = (s.cues + new).sortedBy { it.startMs }
            val trimmed = all.mapIndexed { i, c ->
                val next = all.getOrNull(i + 1)
                if (next != null && c.endMs > next.startMs) c.copy(endMs = maxOf(c.startMs, next.startMs)) else c
            }
            s.copy(cues = trimmed.takeLast(LIVE_KEEP_CUES), status = status)
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
        dropCoverage(video)
        store.save(video, track.cues)
        val settings = _state.value.settings
        val translate = translateEmbedded
        translateEmbedded = false
        if (track.cues.none { it.translation == null }) return
        // 点「翻译」进来的：翻译没配置好时也调用，好提示出原因
        if (translate || (settings.autoTranslate && settings.createTranslator() != null)) startTranslation()
    }

    fun dismissEmbeddedChoice() {
        translateEmbedded = false
        _state.update { it.copy(choosingEmbedded = false) }
    }

    private fun applySubtitleText(video: Uri, text: String): Int {
        val cues = SubtitleParser.parse(text)
        if (cues.isEmpty()) throw IllegalArgumentException("文件里没有识别到字幕")
        cancelAll()
        _state.update { it.copy(cues = cues, status = "已载入 ${cues.size} 句字幕", error = null, failedTask = null) }
        dropCoverage(video)
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

    /** 切到队列里的第 index 个。直播从最新画面开始，其他的从上次看到的地方继续。 */
    fun playQueueIndex(index: Int) {
        if (index !in queue.indices) return
        // 兼容模式下没有播放队列：直播换台时重新打开，按这个频道记住的内核播放
        if (usingVlc) {
            if (queue[index].isLive) play(queue, index)
            return
        }
        val e = queue[index]
        savePosition()
        if (e.isLive) exoPlayer.seekToDefaultPosition(index) else exoPlayer.seekTo(index, resumePosition(e))
        exoPlayer.play()
    }

    /** 从播放队列里去掉第 index 个；去掉的是正在放的就接着放下一个。 */
    fun removeQueueIndex(index: Int) {
        if (usingVlc || index !in queue.indices || queue.size <= 1) return
        queue = queue.toMutableList().also { it.removeAt(index) }
        exoPlayer.removeMediaItem(index)
    }

    // ---- 「我喜欢」：存成一个普通的播放列表，在首页的播放列表里也能看到 ----

    fun isLiked(uri: String): Boolean = library.isLiked(uri)

    /** 喜欢 / 取消喜欢正在播放的歌，返回现在是否喜欢 */
    fun toggleLiked(): Boolean {
        val e = _state.value.current ?: return false
        return library.toggleLiked(e).also { NowPlayingWidget.refresh(getApplication()) }
    }

    fun playlists() = library.playlists()

    /** 把正在播放的加进播放列表；id 为 null 时新建一个叫 newName 的列表 */
    fun addCurrentToPlaylist(id: String?, newName: String = "") {
        val e = _state.value.current ?: return
        val target = id ?: library.createPlaylist(newName.ifBlank { "新建播放列表" }).id
        library.savePlaylists(library.playlists().map { p ->
            if (p.id != target || p.items.any { it.uri == e.uri }) p
            else p.copy(items = p.items + e, updatedAt = System.currentTimeMillis())
        })
    }

    /**
     * 上一个 / 下一个，按队列顺序（跟随循环、随机播放设置）。
     * restartFirst 时「上一个」在播放超过 3 秒时先回到开头，和常见音乐播放器的按钮一样。
     */
    fun skipInQueue(next: Boolean, restartFirst: Boolean = false) {
        if (usingVlc) return
        if (!next && restartFirst && exoPlayer.currentPosition > 3000 && _state.value.current?.isLive != true) {
            exoPlayer.seekTo(0)
            return
        }
        val target = if (next) exoPlayer.nextMediaItemIndex else exoPlayer.previousMediaItemIndex
        if (target != C.INDEX_UNSET) playQueueIndex(target)
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
        _state.value.videoUri?.let { persistCues(it) }
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
        // 新界面已经接管了会话（见 session 的注释）时，不动它的会话和后台服务
        val current = PlaybackService.session === session
        if (current) PlaybackService.session = null
        if (current) NowPlayingWidget.detach(getApplication())
        session.release()
        if (current) getApplication<Application>().let { it.stopService(Intent(it, PlaybackService::class.java)) }
        exoPlayer.release()
        assHandler.release()
        vlcPlayer?.release()
    }

    companion object {
        private const val BATCH_SIZE = 20
        private val LOCAL_SCHEMES = setOf("content", "file", "android.resource")

        /** 实时翻译（直播、远程文件）一次最多翻几句 */
        private const val REALTIME_BATCH = 6

        /** 实时翻译：播放位置之后这么久以内的句子马上翻，更远的攒批 */
        private const val REALTIME_HORIZON_MS = 90_000L

        /** 远程文件实时翻译连续失败这么多次就停下报错 */
        private const val REALTIME_MAX_FAILURES = 5
        private const val CONTEXT_SIZE = 6
        private const val TRANSLATE_PARALLEL = 3

        /** 时长未知时的"结尾" */
        private const val UNKNOWN_END = Long.MAX_VALUE / 4

        /** 从播放位置往前多识别一点，字幕不会晚于画面 */
        private const val LEAD_IN_MS = 3_000L

        /** 播放位置超出已解码位置这么多，就当作拖动了进度条，改从新位置开始 */
        private const val AHEAD_MS = 45_000L

        /** 远程视频的识别最多领先播放这么久；领先够了就停，播放追到只剩 [REMOTE_RESUME_MS] 时再继续 */
        private const val REMOTE_AHEAD_MS = 5 * 60_000L
        private const val REMOTE_RESUME_MS = 3 * 60_000L

        /** 远程视频识别时读取中断，连续重连几次 */
        private const val REMOTE_READ_RETRIES = 3
        /** 直播云端识别的分段（秒）：段越短字幕越早出来，太短识别不准 */
        private const val LIVE_FIRST_CHUNK_SEC = 4
        private const val LIVE_CHUNK_SEC = 6
        private const val LIVE_KEEP_CUES = 200

        /** 兼容模式这么久还没放出直播，就退回默认内核 */
        private const val VLC_LIVE_START_MS = 20_000L

        /** 识别另开连接被服务器拒绝（并发限制、风控）时的状态码 */
        private val REFUSED_CODES = setOf(403, 429, 503)

        /** 直播自带字幕拼成的一句，下一句没来时最多显示多久 */
        private const val LIVE_CAPTION_MAX_MS = 8_000L

        /** 提前读的直播字幕：一句的字在屏幕上消失后再留多久（自带字幕本来就比说话晚一点） */
        private const val LIVE_CAPTION_LINGER_MS = 1_500L
        private const val LIVE_MAX_BACKLOG_MS = 8_000L

        /** 直播声音这么久没有新的就断开重连；刚连上时等第一段声音放宽一些 */
        private const val LIVE_AUDIO_STALL_MS = 12_000L
        private const val LIVE_AUDIO_FIRST_MS = 25_000L

        /** 兼容模式下直播不能出字幕时给的提示（播放页也用） */
        /** 识别要另开一路连接读视频（直播、网盘、NAS）：源有并发限制时会出问题 */
        const val CONCURRENCY_HINT = "语音识别要另开一路连接读取视频，直播源、网盘等限制同时连接数时，可能连不上，或把正在播放的画面挤断"

        const val VLC_NO_SUBTITLES = "兼容模式（VLC 内核）下字幕无法工作"
        private const val LIVE_MIN_CUE_MS = 1_200L

        /** 字幕至少要比画面早这么久换算好，留给翻译 */
        private const val LIVE_SAFETY_MS = 2_500L

        /** 为了对齐字幕，画面最多额外往回拖多久 */
        private const val LIVE_MAX_DELAY_MS = 30_000L

        /** 字幕识别出来这么久还换算不了时间，就改成按到达先后显示 */
        private const val LIVE_MAP_TIMEOUT_MS = 15_000L
        /** 播放页点「♡」的歌放在这个播放列表里 */
        const val LIKED_PLAYLIST = com.aisub.player.media.LIKED_PLAYLIST
    }
}

/** 隐藏式字幕的各行：按在屏幕上的位置从上到下排（每行一个 Cue） */
private fun captionLines(cues: List<androidx.media3.common.text.Cue>): List<String> = cues
    .sortedBy { if (it.line == androidx.media3.common.text.Cue.DIMEN_UNSET) 0f else it.line }
    .mapNotNull { it.text?.toString() }
    .flatMap { it.split('\n') }

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

/** 出错提示里「人话」和技术细节的分隔，播放页只默认显示前半部分 */
internal const val PLAYBACK_ERROR_DETAIL = "\n\u0001"

/** 播放出错的说明：原因 + 怎么办；错误码和异常信息放在分隔符后面（点「详细信息」才显示）。 */
internal fun describePlaybackError(e: PlaybackException): String {
    val reason = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "连不上，网络断了或者太慢。检查网络后点「重试」；NAS 在家里的话，出门时要用外网地址"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            when ((e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode) {
                401, 403 -> "服务器不让访问，多半是登录过期了。到「浏览」里对这个位置选「重新登录」"
                404 -> "服务器上已经没有这个文件了，可能被删除或改名"
                else -> "服务器出错了，稍后再试；一直这样的话检查服务器或链接是否还有效"
            }
        }
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "找不到这个文件，可能已经被删除或移动。从列表里重新打开试试"
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "文件不完整或已损坏，可能没下载完"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "没有读取这个文件的权限。到系统设置里给译幕「音乐和视频」权限"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "这种文件格式默认内核打不开，或者文件已损坏。点「兼容模式播放」试试"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "这台手机解不了这个视频的画面或声音格式。点「兼容模式播放」试试"
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "声音输出失败，可能被别的应用占用了。点「重试」"
        else -> "播放出错了。点「重试」，不行的话用兼容模式播放"
    }
    val detail = generateSequence(e.cause) { it.cause }.mapNotNull { it.message }.firstOrNull()?.take(200)
    return buildString {
        append(reason)
        append(PLAYBACK_ERROR_DETAIL).append("错误码：").append(e.errorCodeName)
        if (!detail.isNullOrBlank()) append("\n").append(detail)
    }
}
