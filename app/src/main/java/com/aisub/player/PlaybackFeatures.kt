package com.aisub.player

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.aisub.player.asr.AudioDecoder
import com.aisub.player.media.AudioFingerprint
import com.aisub.player.media.Bookmark
import com.aisub.player.media.Chapter
import com.aisub.player.media.FrameGrabber
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.MkvChapters
import com.aisub.player.media.PlaybackStore
import com.aisub.player.media.SkipKind
import com.aisub.player.media.SkipRange
import com.aisub.player.media.SkipSource
import com.aisub.player.media.TrackChoice
import com.aisub.player.media.TrackMemory
import com.aisub.player.media.VlcPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** 播放页的进度条缩略图、章节、片头片尾、轨道记忆、逐帧与书签的状态。 */
data class PlaybackExtras(
    val chapters: List<Chapter> = emptyList(),
    /** 当前视频要跳过的区间（已换算成绝对时间，含章节推断出的片头片尾） */
    val skipRanges: List<SkipRange> = emptyList(),
    /** 正处于某个区间里且没有自动跳过：播放页显示"跳过"按钮 */
    val activeSkip: SkipRange? = null,
    /** 正在比对片头片尾时的说明；null 表示没在比对 */
    val detecting: String? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    /** 拖动进度条时的预览：(请求的位置, 画面) */
    val thumbnail: Pair<Long, Bitmap>? = null,
    /** 片段浏览：位置 → 画面 */
    val segmentThumbs: Map<Long, Bitmap> = emptyMap(),
)

/**
 * 从 MainViewModel 拆出来的播放辅助功能，播放器和设置由 ViewModel 提供。
 * 事件（"已跳过片头"、"截图已保存"）通过 [events] 发给播放页显示。
 */
@UnstableApi
class PlaybackFeatures(
    private val app: Application,
    private val scope: CoroutineScope,
    private val exoPlayer: ExoPlayer,
    private val player: () -> Player,
    private val settings: () -> AppSettings,
    private val queue: () -> List<MediaEntry>,
    private val hasLoadedSubtitles: () -> Boolean,
) {
    private val store = PlaybackStore(app)
    private val _state = MutableStateFlow(PlaybackExtras())
    val state: StateFlow<PlaybackExtras> = _state.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    private var entry: MediaEntry? = null
    private var chapterJob: Job? = null
    private var detectJob: Job? = null
    private var segmentJob: Job? = null

    // ---- 画面读取：同一时间只在一个线程里用 ----
    private val grabDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var grabber: FrameGrabber? = null
    /** 按字节算，最多 16MB */
    private val thumbCache = object : LruCache<Long, Bitmap>(16 shl 20) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val thumbTarget = MutableStateFlow<Long?>(null)

    init {
        scope.launch {
            thumbTarget.filterNotNull().collectLatest { ms ->
                val key = thumbKey(ms)
                val bmp = thumbCache.get(key) ?: withContext(grabDispatcher) { grabber()?.frameAt(key) }?.also { thumbCache.put(key, it) }
                if (bmp != null) _state.update { it.copy(thumbnail = ms to bmp) }
            }
        }
    }

    // ---------------- 切换视频 ----------------

    fun onEntryChanged(e: MediaEntry) {
        entry = e
        chapterJob?.cancel()
        detectJob?.cancel()
        segmentJob?.cancel()
        skipped.clear()
        pendingTrackMemory = store.trackMemory(e.uri)
        thumbTarget.value = null
        scope.launch(grabDispatcher) {
            grabber?.close()
            grabber = null
            thumbCache.evictAll()
        }
        _state.value = PlaybackExtras(bookmarks = store.bookmarks(e.uri))
        applyTrackDefaults()
        refreshSkipRanges()
        if (!VlcPlayer.needsVlc(e.title) && MkvChapters.isMatroska(e.title)) {
            // 在 IO 线程读文件，回到主线程再更新（播放器只能在主线程访问）
            chapterJob = scope.launch {
                val list = withContext(Dispatchers.IO) {
                    runCatching { MkvChapters.read(authDataSourceFactory(app), Uri.parse(e.uri)) }
                        .onFailure { android.util.Log.w("Voxtitle", "读取章节失败", it) }.getOrDefault(emptyList())
                }
                if (entry == e) setChapters(list)
            }
        }
        maybeAutoDetect(e)
        scope.launch {
            delay(15_000)
            if (entry == e) saveHistoryThumbnail(onlyIfMissing = true)
        }
    }

    /** 播放器报告轨道变化：VLC 的章节这时才拿得到；记住的轨道这时才能应用。 */
    fun onTracksChanged(tracks: Tracks) {
        // 时长这时一般已经知道了，按离结尾的距离记的片尾才能换算
        refreshSkipRanges()
        val p = player()
        if (p is VlcPlayer && _state.value.chapters.isEmpty()) {
            p.chapters().takeIf { it.isNotEmpty() }?.let(::setChapters)
        }
        if (tracks.groups.isNotEmpty()) pendingTrackMemory?.let { m ->
            pendingTrackMemory = null
            applyTrackMemory(m, tracks)
        }
    }

    /** ViewModel 清理时调用（这时协程作用域已经取消） */
    fun release() {
        runCatching { grabber?.close() }
        grabber = null
        grabDispatcher.close()
    }

    // ---------------- 播放历史的缩略图 ----------------

    private var historyThumbJob: Job? = null

    /**
     * 给网络视频截一张缩略图（播放历史、继续观看显示用）。离开播放页时用当前位置截，
     * 让卡片显示看到的地方；开播一会儿后没有缩略图的也先截一张，防止应用被直接关掉。
     */
    fun saveHistoryThumbnail(onlyIfMissing: Boolean = false) {
        val e = entry ?: return
        // 直播用台标，不截图（直播流取帧要连上直播源，又慢又不一定成功）
        if (!e.isVideo || e.isLive || player().isCurrentMediaItemLive) return
        if (!com.aisub.player.ui.HistoryThumbs.needed(e.uri) || VlcPlayer.needsVlc(e.title)) return
        if (onlyIfMissing && com.aisub.player.ui.HistoryThumbs.file(app, e.uri).exists()) return
        // 开头几秒常是黑场，太靠前时改取全片约 10% 处
        val d = player().duration
        val pos = player().currentPosition.let { p -> if (p < 5_000 && d > 0) d / 10 else p }
        historyThumbJob?.cancel()
        historyThumbJob = scope.launch {
            val bmp = withContext(grabDispatcher) { if (entry == e) grabber()?.frameAt(pos, maxWidth = 480) else null } ?: return@launch
            // 黑场（或者网盘视频取帧失败给的全黑图）不存，留着以前的或者显示图标，别让卡片一片黑
            if (isMostlyBlack(bmp)) return@launch
            withContext(Dispatchers.IO) { runCatching { com.aisub.player.ui.HistoryThumbs.save(app, e.uri, bmp) } }
        }
    }

    /** 抽一些点看平均亮度：几乎全黑就算黑画面 */
    private fun isMostlyBlack(bmp: android.graphics.Bitmap): Boolean {
        var sum = 0L
        var n = 0
        for (yi in 1..9) for (xi in 1..16) {
            val c = bmp.getPixel(bmp.width * xi / 17, bmp.height * yi / 10)
            sum += (android.graphics.Color.red(c) * 3 + android.graphics.Color.green(c) * 6 + android.graphics.Color.blue(c)) / 10
            n++
        }
        return sum / n < 16
    }

    // ---------------- 1. 缩略图与片段 ----------------

    private fun grabber(): FrameGrabber? {
        val e = entry ?: return null
        if (VlcPlayer.needsVlc(e.title)) return null
        return grabber ?: FrameGrabber(app, e.uri).also { grabber = it }
    }

    /** 缩略图按时间分格缓存：长视频分得粗一些，拖动时不会每个像素都去取一次 */
    private fun thumbKey(ms: Long): Long {
        val d = player().duration.takeIf { it > 0 } ?: 3_600_000L
        val step = (d / 300).coerceIn(2_000, 20_000)
        return ms / step * step
    }

    fun requestThumbnail(positionMs: Long) {
        thumbTarget.value = positionMs
    }

    fun clearThumbnail() {
        thumbTarget.value = null
        _state.update { it.copy(thumbnail = null) }
    }

    /** 片段浏览：把整个视频均分成 count 段，逐张生成预览图。 */
    fun loadSegments(count: Int = 12) {
        val d = player().duration.takeIf { it > 0 } ?: return
        if (segmentJob?.isActive == true) return
        segmentJob = scope.launch {
            for (i in 0 until count) {
                val pos = d * (2 * i + 1) / (2 * count)
                if (_state.value.segmentThumbs.containsKey(pos)) continue
                val bmp = withContext(grabDispatcher) { grabber()?.frameAt(pos, maxWidth = 360) } ?: continue
                _state.update { it.copy(segmentThumbs = it.segmentThumbs + (pos to bmp)) }
            }
        }
    }

    fun segmentPositions(count: Int = 12): List<Long> {
        val d = player().duration.takeIf { it > 0 } ?: return emptyList()
        return List(count) { i -> d * (2 * i + 1) / (2 * count) }
    }

    private fun setChapters(list: List<Chapter>) {
        _state.update { it.copy(chapters = list) }
        refreshSkipRanges()
    }

    // ---------------- 3. 片头片尾与跳过区间 ----------------

    /** 这一集已经自动跳过的区间：用户往回拖进去时不再跳 */
    private val skipped = HashSet<SkipRange>()

    private fun refreshSkipRanges() {
        val e = entry ?: return
        val d = player().duration.takeIf { it > 0 } ?: e.durationMs
        val stored = store.skipRanges(e).mapNotNull { it.resolve(d) }
        val fromChapters = chapterSkips(_state.value.chapters, d).filter { c -> stored.none { it.kind == c.kind } }
        _state.update { it.copy(skipRanges = (stored + fromChapters).filter { r -> r.endMs > r.startMs }.sortedBy { r -> r.startMs }) }
    }

    /** 章节名像 "Opening"、"ED"、"片头" 的当作片头片尾 */
    private fun chapterSkips(chapters: List<Chapter>, durationMs: Long): List<SkipRange> = chapters.mapIndexedNotNull { i, c ->
        val kind = when {
            INTRO_NAME.containsMatchIn(c.title) -> SkipKind.INTRO
            OUTRO_NAME.containsMatchIn(c.title) -> SkipKind.OUTRO
            else -> return@mapIndexedNotNull null
        }
        val end = c.endMs?.takeIf { it > c.startMs } ?: chapters.getOrNull(i + 1)?.startMs ?: durationMs.takeIf { it > 0 } ?: return@mapIndexedNotNull null
        SkipRange(c.startMs, end, kind, SkipSource.CHAPTER)
    }.distinctBy { it.kind }

    /** 播放页每 100ms 调用一次。 */
    fun checkSkip(positionMs: Long) {
        val ranges = _state.value.skipRanges
        if (ranges.isEmpty()) {
            if (_state.value.activeSkip != null) _state.update { it.copy(activeSkip = null) }
            return
        }
        // 离区间结尾不到 1 秒就不算在区间里了，免得刚跳完又显示按钮
        val inside = ranges.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - 1000 }
        if (inside != null && settings().autoSkip && inside !in skipped) {
            skipped += inside
            player().seekTo(inside.endMs)
            _events.tryEmit("已跳过${inside.kind.short}")
            _state.update { it.copy(activeSkip = null) }
            return
        }
        if (_state.value.activeSkip != inside) _state.update { it.copy(activeSkip = inside) }
    }

    fun skipActive() {
        val r = _state.value.activeSkip ?: return
        skipped += r
        player().seekTo(r.endMs)
        _state.update { it.copy(activeSkip = null) }
    }

    fun canMarkSeries() = entry?.let { store.hasSeries(it) } ?: false

    /**
     * 用户标记一个区间。片头按整部剧的绝对时间记；片尾按离结尾的距离记（各集时长不同）；
     * 跳过区间只对这一集。解析不出剧名集数时都只记这一集。
     */
    fun markRange(kind: SkipKind, startMs: Long, endMs: Long) {
        val e = entry ?: return
        val (s, t) = if (startMs <= endMs) startMs to endMs else endMs to startMs
        if (t - s < 1000) {
            _events.tryEmit("区间太短")
            return
        }
        val series = kind != SkipKind.CUSTOM && store.hasSeries(e)
        val d = player().duration
        val range = if (kind == SkipKind.OUTRO && series && d > 0) {
            SkipRange(d - s, d - t, kind, SkipSource.USER, fromEnd = true, series = true)
        } else {
            SkipRange(s, t, kind, SkipSource.USER, series = series)
        }
        store.addSkipRange(e, range)
        refreshSkipRanges()
        // 刚标记完，当前位置就在区间里：这次先不跳，下次播到再跳
        val pos = player().currentPosition
        _state.value.skipRanges.filter { pos >= it.startMs && pos < it.endMs }.forEach { skipped += it }
        _events.tryEmit("已记录${kind.label}" + if (series) "（整部剧）" else "")
    }

    /** 删除一个区间（章节推断出的不能删，只能关掉自动跳过）。 */
    fun removeRange(resolved: SkipRange) {
        val e = entry ?: return
        val d = player().duration.takeIf { it > 0 } ?: e.durationMs
        store.skipRanges(e).firstOrNull { it.resolve(d) == resolved }?.let { store.removeSkipRange(e, it) }
        skipped.remove(resolved)
        refreshSkipRanges()
    }

    // ---- 自动比对片头片尾 ----

    /** 声音指纹缓存：上一集当"另一集"时不用再解码 */
    private val printCache = LruCache<String, AudioFingerprint.Print>(8)
    private val autoTried = HashSet<String>()

    private fun maybeAutoDetect(e: MediaEntry) {
        if (!settings().autoDetectIntro || !store.hasSeries(e) || e.uri in autoTried) return
        // 解码时音视频数据要一起读，网络文件每集要下载几百 MB，只对本地文件自动比对
        if (!e.uri.startsWith("content://") && !e.uri.startsWith("file://")) return
        if (store.detectTried(e.uri)) return
        detectJob = scope.launch {
            // 等章节读完、播放稳定后再开始，避免和起播抢带宽
            delay(8_000)
            chapterJob?.join()
            val kinds = _state.value.skipRanges.map { it.kind }.toSet()
            if (SkipKind.INTRO in kinds && SkipKind.OUTRO in kinds) return@launch
            autoTried += e.uri
            detect(e, manual = false)
        }
    }

    /** 播放页"自动识别片头片尾"按钮。 */
    fun detectNow() {
        val e = entry ?: return
        if (detectJob?.isActive == true && _state.value.detecting != null) return
        detectJob?.cancel()
        detectJob = scope.launch { detect(e, manual = true) }
    }

    private suspend fun detect(e: MediaEntry, manual: Boolean) {
        val other = partnerOf(e)
        if (other == null) {
            if (manual) _events.tryEmit("需要同一部剧的另一集在播放列表里（从文件夹点开视频即可）")
            return
        }
        try {
            _state.update { it.copy(detecting = "正在比对片头（${other.title}）…") }
            val found = mutableListOf<String>()
            // 时长要在主线程读播放器，先取好再进后台
            val da = durationOf(e)
            val db = durationOf(other)
            withContext(Dispatchers.Default) {
                val a = fingerprint(e, 0)
                val b = fingerprint(other, 0)
                AudioFingerprint.longestCommon(a, b)?.takeIf { it.aStartMs < SCAN_MS - 15_000 }?.let { m ->
                    store.addSkipRange(e, SkipRange(m.aStartMs, m.aEndMs, SkipKind.INTRO, SkipSource.AUTO))
                    store.addSkipRange(other, SkipRange(m.bStartMs, m.bEndMs, SkipKind.INTRO, SkipSource.AUTO))
                    found += "片头 ${formatMs(m.aStartMs)}–${formatMs(m.aEndMs)}"
                }
                _state.update { it.copy(detecting = "正在比对片尾…") }
                if (da > SCAN_MS * 2 && db > SCAN_MS * 2) {
                    val ta = da - SCAN_MS
                    val tb = db - SCAN_MS
                    val a2 = fingerprint(e, ta)
                    val b2 = fingerprint(other, tb)
                    AudioFingerprint.longestCommon(a2, b2)?.let { m ->
                        store.addSkipRange(e, SkipRange(ta + m.aStartMs, ta + m.aEndMs, SkipKind.OUTRO, SkipSource.AUTO))
                        store.addSkipRange(other, SkipRange(tb + m.bStartMs, tb + m.bEndMs, SkipKind.OUTRO, SkipSource.AUTO))
                        found += "片尾 ${formatMs(ta + m.aStartMs)}–${formatMs(ta + m.aEndMs)}"
                    }
                }
            }
            store.markDetectTried(e.uri)
            store.markDetectTried(other.uri)
            if (entry == e) refreshSkipRanges()
            if (found.isNotEmpty()) _events.tryEmit("找到" + found.joinToString("，"))
            else if (manual) _events.tryEmit("没找到两集共有的片头片尾")
        } catch (ex: kotlinx.coroutines.CancellationException) {
            throw ex
        } catch (ex: Exception) {
            android.util.Log.w("Voxtitle", "比对片头片尾失败", ex)
            if (manual) _events.tryEmit("比对失败：${ex.message}")
        } finally {
            _state.update { it.copy(detecting = null) }
        }
    }

    /** 播放列表里同一部剧（同剧名、同季）的另一集，优先下一集 */
    private fun partnerOf(e: MediaEntry): MediaEntry? {
        val key = PlaybackStore.seriesKey(e) ?: return null
        val q = queue()
        val i = q.indexOfFirst { it.uri == e.uri }
        val candidates = (q.drop(i + 1) + q.take(maxOf(i, 0)).reversed())
        return candidates.firstOrNull { it.uri != e.uri && PlaybackStore.seriesKey(it) == key }
    }

    /** 在主线程调用（会读播放器） */
    private suspend fun durationOf(e: MediaEntry): Long {
        if (e.uri == entry?.uri) player().duration.takeIf { it > 0 }?.let { return it }
        if (e.durationMs > 0) return e.durationMs
        return withContext(Dispatchers.IO) { FrameGrabber(app, e.uri).use { it.durationMs() } }
    }

    /** 解码从 startMs 开始的 [SCAN_MS] 音频算指纹 */
    private suspend fun fingerprint(e: MediaEntry, startMs: Long): AudioFingerprint.Print {
        val key = "${e.uri}@$startMs"
        printCache.get(key)?.let { return it }
        val need = (SCAN_MS / 1000 * AudioFingerprint.SAMPLE_RATE).toInt()
        val buf = FloatArray(need)
        var filled = 0
        val job = kotlin.coroutines.coroutineContext[Job]
        withContext(Dispatchers.IO) {
            AudioDecoder(app, Uri.parse(e.uri), authDataSourceFactory(app)).decode(
                targetRate = AudioFingerprint.SAMPLE_RATE,
                startMs = startMs,
                isActive = { filled < need && job?.isActive != false },
                onProgress = {},
                onChunk = { chunk ->
                    val n = minOf(chunk.size, need - filled)
                    System.arraycopy(chunk, 0, buf, filled, n)
                    filled += n
                },
            )
        }
        return AudioFingerprint.compute(buf.copyOf(filled)).also { printCache.put(key, it) }
    }

    // ---------------- 4. 音轨与字幕选择 ----------------

    private var pendingTrackMemory: TrackMemory? = null

    /** 换视频时：清掉上一个视频的选轨，按设置里的首选语言选；已有 AI / 外挂字幕时不开视频自带字幕。 */
    fun applyTrackDefaults() {
        val s = settings()
        val audioLangs = s.preferredAudioLanguages.toLanguageList()
        val textLangs = s.preferredSubtitleLanguages.toLanguageList()
        val textOff = textLangs.isEmpty() || (s.preferLoadedSubtitles && hasLoadedSubtitles())
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredAudioLanguages(*audioLangs.toTypedArray())
            .setPreferredTextLanguages(*textLangs.toTypedArray())
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, textOff)
            .build()
    }

    private fun applyTrackMemory(m: TrackMemory, tracks: Tracks) {
        val p = player()
        var b = p.trackSelectionParameters.buildUpon()
        m.audio?.let { choice -> find(tracks, C.TRACK_TYPE_AUDIO, choice)?.let { (g, i) -> b = b.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)) } }
        when {
            m.textOff -> b = b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            m.text != null -> find(tracks, C.TRACK_TYPE_TEXT, m.text)?.let { (g, i) ->
                b = b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
            }
        }
        p.trackSelectionParameters = b.build()
    }

    /** 按语言+名称、语言、序号的顺序找回记住的那条轨 */
    private fun find(tracks: Tracks, type: Int, c: TrackChoice): Pair<Tracks.Group, Int>? {
        val all = tracks.groups.filter { it.type == type }.flatMap { g -> (0 until g.length).map { g to it } }
        fun fmt(x: Pair<Tracks.Group, Int>): Format = x.first.getTrackFormat(x.second)
        return all.firstOrNull { fmt(it).language == c.language && fmt(it).label == c.label }
            ?: all.firstOrNull { c.language != null && fmt(it).language == c.language }
            ?: all.getOrNull(c.index)
    }

    /** 用户在面板里选了一条轨：应用并按视频记住。 */
    fun selectTrack(type: Int, group: Tracks.Group, index: Int) {
        val p = player()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
        val f = group.getTrackFormat(index)
        val ordinal = p.currentTracks.groups.filter { it.type == type }.flatMap { g -> (0 until g.length).map { g to it } }
            .indexOfFirst { it.first.mediaTrackGroup == group.mediaTrackGroup && it.second == index }
        val choice = TrackChoice(f.language, f.label, ordinal.coerceAtLeast(0))
        remember { if (type == C.TRACK_TYPE_AUDIO) it.copy(audio = choice) else it.copy(text = choice, textOff = false) }
    }

    fun disableTrackType(type: Int) {
        val p = player()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build()
        if (type == C.TRACK_TYPE_TEXT) remember { it.copy(text = null, textOff = true) }
    }

    private fun remember(change: (TrackMemory) -> TrackMemory) {
        val e = entry ?: return
        store.saveTrackMemory(e.uri, change(store.trackMemory(e.uri) ?: TrackMemory()))
    }

    // ---------------- 5. 逐帧、截图、书签 ----------------

    /** 暂停并前进/后退一帧。 */
    fun stepFrame(forward: Boolean): Long {
        val p = player()
        p.pause()
        val fps = (p as? ExoPlayer)?.videoFormat?.frameRate?.takeIf { it > 1f } ?: 30f
        val frameMs = (1000f / fps).toLong().coerceAtLeast(1)
        (p as? ExoPlayer)?.setSeekParameters(SeekParameters.EXACT)
        val target = (p.currentPosition + if (forward) frameMs else -frameMs).coerceAtLeast(0)
        p.seekTo(target)
        return target
    }

    /**
     * 取当前画面：优先从播放画面直接复制（快、和看到的一样），不行再从文件里解码那一帧。
     * @param videoView PlayerView 里的 SurfaceView / TextureView
     */
    suspend fun captureFrame(videoView: View?, maxWidth: Int = 0): Bitmap? {
        val pos = player().currentPosition
        val fromView = when (videoView) {
            is TextureView -> videoView.bitmap
            is SurfaceView -> copySurface(videoView)
            else -> null
        }
        val bmp = fromView ?: withContext(grabDispatcher) { grabber()?.frameAt(pos, exact = true, maxWidth = 0) }
        return bmp?.let { if (maxWidth > 0 && it.width > maxWidth) Bitmap.createScaledBitmap(it, maxWidth, it.height * maxWidth / it.width, true) else it }
    }

    private suspend fun copySurface(view: SurfaceView): Bitmap? {
        if (view.width <= 0 || view.height <= 0 || !view.holder.surface.isValid) return null
        val vs = player().videoSize
        val (w, h) = if (vs.width > 0 && vs.height > 0) {
            val scale = minOf(1f, 1920f / vs.width)
            (vs.width * vs.pixelWidthHeightRatio * scale).toInt() to (vs.height * scale).toInt()
        } else view.width to view.height
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(view, bmp, { result -> cont.resume(if (result == PixelCopy.SUCCESS) bmp else null) }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                cont.resume(null)
            }
        }
    }

    /** 截图存到相册的 Pictures/译幕 */
    suspend fun saveToGallery(bmp: Bitmap, positionMs: Long): Boolean = withContext(Dispatchers.IO) {
        val base = entry?.title?.substringBeforeLast('.')?.replace(Regex("""[\\/:*?"<>|]"""), "_")?.take(60) ?: "截图"
        val name = "${base}_${formatMs(positionMs).replace(':', '-')}_${System.currentTimeMillis() % 100000}.jpg"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/译幕")
                }
                val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
                app.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: return@withContext false
            } else {
                val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "译幕").apply { mkdirs() }
                val f = java.io.File(dir, name)
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                android.media.MediaScannerConnection.scanFile(app, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null)
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("Voxtitle", "保存截图失败", e)
            false
        }
    }

    suspend fun addBookmark(videoView: View?, note: String = "") {
        val e = entry ?: return
        val pos = player().currentPosition
        val bmp = captureFrame(videoView, maxWidth = 1280)
        withContext(Dispatchers.IO) { store.addBookmark(e.uri, pos, note, bmp) }
        _state.update { it.copy(bookmarks = store.bookmarks(e.uri)) }
        _events.tryEmit("已添加书签 ${formatMs(pos)}")
    }

    fun renameBookmark(b: Bookmark, note: String) {
        val e = entry ?: return
        store.updateBookmark(e.uri, b.copy(note = note))
        _state.update { it.copy(bookmarks = store.bookmarks(e.uri)) }
    }

    fun removeBookmark(b: Bookmark) {
        val e = entry ?: return
        store.removeBookmark(e.uri, b)
        _state.update { it.copy(bookmarks = store.bookmarks(e.uri)) }
    }

    /** 把书签图（关键帧集合）全部存到相册 */
    fun exportBookmarks() {
        val list = _state.value.bookmarks.filter { it.image != null }
        if (list.isEmpty()) {
            _events.tryEmit("还没有带画面的书签")
            return
        }
        scope.launch {
            var ok = 0
            for (b in list) {
                val bmp = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(b.image) } ?: continue
                if (saveToGallery(bmp, b.positionMs)) ok++
            }
            _events.tryEmit("已保存 $ok 张到相册 Pictures/译幕")
        }
    }

    fun emit(message: String) {
        _events.tryEmit(message)
    }

    companion object {
        /** 比对片头、片尾各取多长的音频 */
        private const val SCAN_MS = 360_000L

        // \b 对中日文不起作用，英文词用前后不是字母数字来判断
        private val INTRO_NAME = Regex("""(?i)(?<![a-z0-9])(op\d*|opening|intro|introduction)(?![a-z0-9])|オープニング|片头|片頭|主题曲""")
        private val OUTRO_NAME = Regex("""(?i)(?<![a-z0-9])(ed\d*|ending|credits|outro)(?![a-z0-9])|エンディング|片尾""")

        fun formatMs(ms: Long): String {
            val t = ms / 1000
            return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
        }

        private fun String.toLanguageList() = split(',', '，', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
