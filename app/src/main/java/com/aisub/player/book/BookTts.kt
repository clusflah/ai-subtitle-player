package com.aisub.player.book

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.aisub.player.SettingsRepository
import com.aisub.player.tts.AzureTts
import com.aisub.player.tts.SystemTts
import com.aisub.player.tts.TtsEngine
import com.aisub.player.tts.TtsEngineType
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 听书：从某一段开始一句一句朗读（用设置里的朗读引擎合成，提前合成后面几句），读到哪句阅读界面就高亮哪句。
 * 播放交给 [BookTtsPlayer]（Media3 播放器），由 [BookReadService] 交给系统：锁屏、切到别的 App 继续读，
 * 通知栏、耳机按键能暂停、上一句、下一句。整个应用一份，只在主线程上用。
 */
@UnstableApi
object BookTts {
    data class Status(
        /** 正在朗读的书（打开它的地址）；null 是没在听书 */
        val key: String? = null,
        val title: String = "",
        val chapter: String = "",
        /** 正在读的段落和句子在段里的起止；block = -1 是还没开始 */
        val block: Int = -1,
        val start: Int = 0,
        val end: Int = 0,
        val playing: Boolean = false,
        /** 在等合成 */
        val buffering: Boolean = false,
        val error: String? = null,
        /** 定时停止：到这个时间（毫秒）停；0 是没设 */
        val sleepAt: Long = 0,
        /** 读完这一章停 */
        val sleepChapterEnd: Boolean = false,
        val speed: Float = 1f,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private data class Cursor(val block: Int, val sentence: Int)

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var blocks: List<BookBlock> = emptyList()
    private var chapterOfBlock: IntArray = IntArray(0)
    private var chapterTitles: List<String> = emptyList()
    private var skippedBlocks = BooleanArray(0)
    private val sentenceCache = HashMap<Int, List<IntRange>>()
    private var cursor: Cursor? = null
    private var engine: TtsEngine? = null
    private var engineType = TtsEngineType.SYSTEM
    private var voice: String? = null
    private var locale: String = "zh-CN"
    /** 换一本书就加一，合成的文件名带上它，旧书的不会拿错 */
    private var generation = 0
    private val pending = HashMap<String, Deferred<File>>()
    private var speakJob: Job? = null
    private var sleepJob: Job? = null
    private var player: BookTtsPlayer? = null
    private var controller: ListenableFuture<MediaController>? = null

    fun player(context: Context): BookTtsPlayer {
        if (!::app.isInitialized) app = context.applicationContext
        return player ?: BookTtsPlayer(app).also { player = it }
    }

    private fun cacheDir() = File(app.cacheDir, "booktts").apply { mkdirs() }

    /**
     * 从第 [block] 段（之后第一段有字的）开始读这本书。换了书就重新准备；同一本书只是跳过去。
     * [chapters] 和阅读界面的一样，块的序号按章节顺序连起来数
     */
    fun start(context: Context, key: String, title: String, chapters: List<BookChapter>, block: Int) {
        app = context.applicationContext
        if (_status.value.key != key) load(key, title, chapters)
        val c = firstFrom(block) ?: run {
            _status.update { it.copy(error = "后面没有可以朗读的文字了") }
            return
        }
        cursor = c
        play(restart = true)
    }

    private fun load(key: String, title: String, chapters: List<BookChapter>) {
        stopSpeaking()
        generation++
        pending.clear()
        cacheDir().listFiles()?.forEach { it.delete() }
        blocks = chapters.flatMap { it.blocks }
        chapterTitles = chapters.map { it.title }
        chapterOfBlock = IntArray(blocks.size).also { arr ->
            var i = 0
            chapters.forEachIndexed { ci, ch -> repeat(ch.blocks.size) { arr[i++] = ci } }
        }
        val prefs = BookStore.settings.value
        skippedBlocks = BooleanArray(blocks.size)
        if (prefs.skipFrontMatter) chapters.forEachIndexed { ci, chapter ->
            if (BookNavigation.isFrontMatter(chapter)) chapterOfBlock.forEachIndexed { i, owner -> if (owner == ci) skippedBlocks[i] = true }
        }
        sentenceCache.clear()
        cursor = null
        val sample = blocks.asSequence().filter { it.text.isNotEmpty() }.take(80).joinToString("") { it.text }.take(4000)
        locale = Sentences.localeOf(sample)
        setupEngine()
        _status.value = Status(key = key, title = title, speed = prefs.ttsSpeed)
    }

    /** 用设置里选的朗读引擎；Azure 没填 Key 时用系统 TTS（装好就能用） */
    private fun setupEngine() {
        val s = SettingsRepository(app).load()
        val (key, region, endpoint) = s.ttsAzure()
        engineType = if (s.ttsEngine == TtsEngineType.AZURE && key.isNotBlank()) TtsEngineType.AZURE else TtsEngineType.SYSTEM
        engine?.close()
        engine = if (engineType == TtsEngineType.AZURE) AzureTts(key, region, endpoint) else SystemTts(app)
        // 设置里选的语音是这本书的语言才用（例如中文书不用英文语音），否则用这个语言的默认语音
        val lang = locale.substringBefore('-').lowercase()
        voice = (BookStore.settings.value.ttsVoice ?: s.ttsVoices[engineType])
            ?.takeIf { v -> v.lowercase().startsWith("$lang-") || v.lowercase().startsWith("${lang}_") }
    }

    private fun sentences(block: Int): List<IntRange> = sentenceCache.getOrPut(block) {
        blocks.getOrNull(block)?.text?.let(Sentences::split).orEmpty()
    }

    private fun firstFrom(block: Int): Cursor? {
        var b = block.coerceAtLeast(0)
        while (b < blocks.size) {
            if (!skippedBlocks.getOrElse(b) { false } && sentences(b).isNotEmpty()) return Cursor(b, 0)
            b++
        }
        return null
    }

    private fun nextOf(c: Cursor): Cursor? =
        if (c.sentence + 1 < sentences(c.block).size) Cursor(c.block, c.sentence + 1) else firstFrom(c.block + 1)

    private fun prevOf(c: Cursor): Cursor? {
        if (c.sentence > 0) return Cursor(c.block, c.sentence - 1)
        var b = c.block - 1
        while (b >= 0) {
            val n = sentences(b).size
            if (n > 0) return Cursor(b, n - 1)
            b--
        }
        return null
    }

    private fun keyOf(c: Cursor) = "$generation-${c.block}-${c.sentence}"

    private fun synth(c: Cursor): Deferred<File> {
        val key = keyOf(c)
        pending[key]?.let { return it }
        val range = sentences(c.block)[c.sentence]
        val text = blocks[c.block].text.substring(range.first, range.last + 1)
        val e = engine ?: throw IllegalStateException("没有朗读引擎")
        val out = File(cacheDir(), key + if (engineType == TtsEngineType.AZURE) ".mp3" else ".wav")
        val job = scope.async(Dispatchers.IO) {
            if (!out.exists() || out.length() == 0L) e.synthesize(text, voice, locale, out)
            out
        }
        pending[key] = job
        job.invokeOnCompletion { t -> if (t != null) scope.launch { if (pending[key] === job) pending.remove(key) } }
        return job
    }

    /** 提前合成后面几句；读过的删掉 */
    private fun prefetch(from: Cursor) {
        var c: Cursor? = from
        repeat(3) {
            c = c?.let(::nextOf) ?: return@repeat
            c?.let { runCatching { synth(it) } }
        }
        val keep = generateSequence(from) { prevOf(it) }.take(2).map(::keyOf).toSet() + generateSequence(from) { nextOf(it) }.take(5).map(::keyOf).toSet()
        val stale = pending.keys.filter { it !in keep }
        for (k in stale) {
            pending.remove(k)?.cancel()
            File(cacheDir(), "$k.mp3").delete()
            File(cacheDir(), "$k.wav").delete()
        }
    }

    private fun speak() {
        speakJob?.cancel()
        val c = cursor ?: return finish()
        val range = sentences(c.block).getOrNull(c.sentence) ?: return finish()
        _status.update {
            it.copy(block = c.block, start = range.first, end = range.last + 1, buffering = true, error = null,
                chapter = chapterTitles.getOrNull(chapterOfBlock.getOrElse(c.block) { 0 }).orEmpty())
        }
        player?.update()
        speakJob = scope.launch {
            try {
                val file = synth(c).await()
                if (!_status.value.playing || cursor != c) return@launch
                _status.update { it.copy(buffering = false) }
                player?.playFile(keyOf(c), file, _status.value.speed)
                player?.update()
                prefetch(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.update { it.copy(error = e.message ?: e.toString(), playing = false, buffering = false) }
                player?.pauseAudio()
                player?.update()
            }
        }
    }

    /** 一句读完了（播放器在主线程上调） */
    internal fun onSentenceDone() {
        val c = cursor ?: return
        if (!_status.value.playing) return
        val n = nextOf(c)
        // 读完这一章停
        if (_status.value.sleepChapterEnd && (n == null || chapterOfBlock.getOrElse(n.block) { -1 } != chapterOfBlock.getOrElse(c.block) { -1 })) {
            cursor = n ?: c
            _status.update { it.copy(sleepChapterEnd = false) }
            pause()
            n?.let { nc -> sentences(nc.block).getOrNull(nc.sentence)?.let { r -> _status.update { s -> s.copy(block = nc.block, start = r.first, end = r.last + 1) } } }
            return
        }
        cursor = n
        speak()
    }

    internal fun onAudioError(e: PlaybackException) {
        _status.update { it.copy(error = "播放出错：${e.errorCodeName}", playing = false, buffering = false) }
        player?.update()
    }

    private fun finish() {
        _status.update { it.copy(playing = false, buffering = false, error = "已经读到书的最后了") }
        player?.pauseAudio()
        player?.update()
    }

    fun play(restart: Boolean = false) {
        if (_status.value.key == null || cursor == null) return
        ensureService()
        _status.update { it.copy(playing = true, error = null) }
        val c = cursor!!
        if (!restart && player?.resume(keyOf(c)) == true) {
            player?.update()
            return
        }
        speak()
    }

    fun pause() {
        _status.update { it.copy(playing = false, buffering = false) }
        player?.pauseAudio()
        player?.update()
    }

    fun toggle() = if (_status.value.playing) pause() else play()

    fun next() {
        val n = cursor?.let(::nextOf) ?: return
        cursor = n
        if (_status.value.playing) speak() else showCursor()
    }

    fun prev() {
        val p = cursor?.let(::prevOf) ?: return
        cursor = p
        if (_status.value.playing) speak() else showCursor()
    }

    private fun showCursor() {
        val c = cursor ?: return
        val r = sentences(c.block).getOrNull(c.sentence) ?: return
        _status.update { it.copy(block = c.block, start = r.first, end = r.last + 1) }
        player?.update()
    }

    fun setSpeed(speed: Float) {
        _status.update { it.copy(speed = speed) }
        BookStore.update { it.copy(ttsSpeed = speed) }
        player?.setSpeed(speed)
    }

    /** 定时停止：minutes 分钟后停；0 取消；-1 读完这一章停 */
    fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        when {
            minutes > 0 -> {
                val at = System.currentTimeMillis() + minutes * 60_000L
                _status.update { it.copy(sleepAt = at, sleepChapterEnd = false) }
                sleepJob = scope.launch {
                    delay(minutes * 60_000L)
                    _status.update { it.copy(sleepAt = 0) }
                    pause()
                }
            }
            minutes < 0 -> _status.update { it.copy(sleepAt = 0, sleepChapterEnd = true) }
            else -> _status.update { it.copy(sleepAt = 0, sleepChapterEnd = false) }
        }
    }

    /** 不听了：停下、清掉通知和后台服务 */
    fun stop() {
        stopSpeaking()
        sleepJob?.cancel()
        _status.value = Status()
        player?.update()
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        if (::app.isInitialized) runCatching { app.stopService(Intent(app, BookReadService::class.java)) }
    }

    private fun stopSpeaking() {
        speakJob?.cancel()
        player?.pauseAudio()
        for (d in pending.values) d.cancel()
        pending.clear()
    }

    /** 连上后台服务：开始播放时 Media3 把它转到前台、显示通知 */
    private fun ensureService() {
        if (controller != null) return
        player(app)
        controller = MediaController.Builder(app, SessionToken(app, ComponentName(app, BookReadService::class.java))).buildAsync()
    }
}

/**
 * 听书的播放器：对系统（通知栏、锁屏、耳机）是一个普通的 Media3 播放器，播放 / 暂停 / 上一句 / 下一句交给 [BookTts]；
 * 每句的声音用里面的 ExoPlayer 放（它管音频焦点：来电、别的 App 放声音时暂停；拔耳机暂停；变速不变调）。
 */
@UnstableApi
class BookTtsPlayer(context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val audio: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()
    private var loaded: String? = null

    init {
        audio.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) BookTts.onSentenceDone()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // 音频焦点没了、拔了耳机：听书也暂停
                if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST &&
                    reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && BookTts.status.value.playing
                ) BookTts.pause()
            }

            override fun onPlayerError(error: PlaybackException) = BookTts.onAudioError(error)
        })
    }

    fun playFile(key: String, file: File, speed: Float) {
        loaded = key
        audio.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        audio.playbackParameters = PlaybackParameters(speed)
        audio.prepare()
        audio.play()
    }

    /** 暂停后接着读同一句（从停下的地方） */
    fun resume(key: String): Boolean {
        if (loaded != key || audio.playbackState != Player.STATE_READY) return false
        audio.play()
        return true
    }

    fun pauseAudio() = audio.pause()

    fun setSpeed(speed: Float) {
        audio.playbackParameters = PlaybackParameters(speed)
    }

    fun update() = invalidateState()

    override fun getState(): State {
        val s = BookTts.status.value
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE, Player.COMMAND_RELEASE,
        ).build()
        val b = State.Builder().setAvailableCommands(commands).setPlayWhenReady(s.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        if (s.key == null || s.block < 0) return b.setPlaybackState(Player.STATE_IDLE).build()
        val meta = MediaMetadata.Builder().setTitle(s.chapter.ifEmpty { s.title }).setArtist(s.title).setDisplayTitle(s.chapter.ifEmpty { s.title }).build()
        val item = MediaItemData.Builder("book")
            .setMediaItem(MediaItem.Builder().setMediaId("book").setMediaMetadata(meta).build())
            .setMediaMetadata(meta)
            .build()
        return b.setPlaylist(listOf(item)).setCurrentMediaItemIndex(0)
            .setPlaybackState(if (s.playing && s.buffering) Player.STATE_BUFFERING else Player.STATE_READY)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) BookTts.play() else BookTts.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> BookTts.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> BookTts.prev()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        BookTts.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}

/** 后台听书的媒体会话服务：朗读时 Media3 把它转到前台，显示通知栏控制；锁屏、耳机按键也能控制 */
@UnstableApi
class BookReadService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val builder = MediaSession.Builder(this, BookTts.player(this)).setId("book")
        if (launch != null) builder.setSessionActivity(
            PendingIntent.getActivity(this, 71, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 从最近任务里划掉：没在读就结束，在读就接着读 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!BookTts.status.value.playing) {
            BookTts.stop()
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
