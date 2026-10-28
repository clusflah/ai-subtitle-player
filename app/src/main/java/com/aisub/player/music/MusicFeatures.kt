package com.aisub.player.music

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Size
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.aisub.player.AppSettings
import com.aisub.player.media.FileOps
import com.aisub.player.media.MediaEntry
import com.aisub.player.translate.ChineseScript
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

/** 当前播放的音乐：标签、封面、歌词。tags 为 null 表示当前不是音乐。 */
data class MusicState(
    val tags: TrackTags? = null,
    val cover: Bitmap? = null,
    /** 封面来源：内嵌 / 文件夹 / 网易云音乐 / iTunes / 手动选择 */
    val coverSource: String? = null,
    val lyrics: Lyrics? = null,
    /** 正在查找或没找到时的提示；有歌词时为 null */
    val lyricsStatus: String? = null,
    /** 歌名、歌手、专辑是用户改过的（存在译幕里） */
    val edited: Boolean = false,
    /** 歌词的翻译，和 lyrics.lines 一一对应；没翻译过为 null */
    val translations: List<String?>? = null,
    val translating: Boolean = false,
)

/**
 * 音乐的歌词与封面。顺序：
 * - 歌词：手动选过或在线找到后存下的 → 内嵌歌词 → 旁边的同名 .lrc → 在线查找
 * - 封面：手动选过或在线找到后存下的 → 内嵌封面 → 系统媒体库（含文件夹里的 cover.jpg 等）→ 在线查找
 */
class MusicFeatures(
    private val app: Application,
    private val scope: CoroutineScope,
    private val player: () -> Player,
    private val settings: () -> AppSettings,
) {
    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()

    private val lyricsDir get() = File(app.filesDir, "lyrics").apply { mkdirs() }
    private val coverDir get() = File(app.filesDir, "covers").apply { mkdirs() }

    private var entry: MediaEntry? = null
    private var loadJob: Job? = null
    private var metadataReady = CompletableDeferred<List<Metadata>>()

    fun onEntryChanged(e: MediaEntry) {
        if (entry?.uri == e.uri) return
        entry = e
        loadJob?.cancel()
        metadataReady = CompletableDeferred()
        if (e.isVideo || e.isLive) {
            _state.value = MusicState()
            return
        }
        _state.value = MusicState(tags = TrackTags.fromMetadata(e.title, 0, emptyList()))
        val ready = metadataReady
        loadJob = scope.launch {
            // 等播放器读出音轨的元数据；VLC 内核或读得慢时改用系统接口
            val metadata = withTimeoutOrNull(2500) { ready.await() }
            val duration = player().duration.takeIf { it > 0 } ?: 0L
            val fileTags = withContext(Dispatchers.IO) {
                if (!metadata.isNullOrEmpty()) TrackTags.fromMetadata(e.title, duration, metadata)
                else (if (FileOps.isLocal(e)) TrackTags.fromRetriever(app, Uri.parse(e.uri), e.title) else null)
                    ?: TrackTags.fromMetadata(e.title, duration, emptyList())
            }
            // Plex、Emby 的歌：文件里没写歌手、专辑时用服务器整理好的
            val server = com.aisub.player.remote.cloud.ServerProgress.find(e.uri)
            val tags = if (server == null) fileTags else fileTags.copy(
                artist = fileTags.artist ?: server.artist,
                album = fileTags.album ?: server.album,
            )
            rawTags = tags
            val shown = withContext(Dispatchers.IO) { applySavedInfo(e, tags) }
            _state.update { it.copy(tags = shown, edited = shown !== tags) }
            launch { loadCover(e, shown) }
            loadLyrics(e, shown)
        }
    }

    // ---------------- 编辑歌曲信息 ----------------

    /** 文件里读到的标签，「恢复」时用 */
    private var rawTags: TrackTags? = null

    private fun infoFile(e: MediaEntry) = File(infoDir, key(e) + ".json")
    private val infoDir get() = File(app.filesDir, "music_info").apply { mkdirs() }

    /** 用户改过的歌名、歌手、专辑盖在文件标签上；没改过原样返回 */
    private fun applySavedInfo(e: MediaEntry, tags: TrackTags): TrackTags {
        val o = runCatching { org.json.JSONObject(infoFile(e).readText()) }.getOrNull() ?: return tags
        fun s(k: String) = o.optString(k).trim().takeIf { it.isNotEmpty() }
        return tags.copy(title = s("title") ?: tags.title, artist = s("artist"), album = s("album"))
    }

    suspend fun searchSongs(title: String, artist: String?): List<SongCandidate> = withContext(Dispatchers.IO) {
        MusicLookup.searchSongs(title, artist?.takeIf { it.isNotBlank() }, _state.value.tags?.durationMs ?: 0)
    }

    /**
     * 保存改好的歌曲信息（只存在译幕里，不改音乐文件）。
     * coverFrom 不为空时顺便用它的封面；还没有歌词时按新信息重新在线找。
     */
    suspend fun saveInfo(title: String, artist: String, album: String, coverFrom: SongCandidate?) {
        val e = entry ?: return
        val old = _state.value.tags ?: return
        val tags = old.copy(title = title.trim().ifEmpty { old.title }, artist = artist.trim().ifEmpty { null }, album = album.trim().ifEmpty { null })
        withContext(Dispatchers.IO) {
            infoFile(e).writeText(
                org.json.JSONObject().put("title", tags.title).put("artist", tags.artist.orEmpty()).put("album", tags.album.orEmpty()).toString(),
            )
        }
        _state.update { it.copy(tags = tags, edited = true) }
        coverFrom?.takeIf { it.coverUrl != null }?.let { applyCover(it) }
        if (_state.value.lyrics == null && settings().musicOnlineLookup) loadLyrics(e, tags)
    }

    /** 不用改过的信息，恢复成文件里的标签 */
    suspend fun resetInfo() {
        val e = entry ?: return
        val raw = rawTags ?: return
        withContext(Dispatchers.IO) { infoFile(e).delete() }
        _state.update { it.copy(tags = raw.copy(cover = it.tags?.cover), edited = false) }
    }

    fun onTracksChanged(tracks: Tracks) {
        val e = entry ?: return
        if (e.isVideo || metadataReady.isCompleted) return
        val metadata = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { g ->
            (0 until g.length).mapNotNull { g.getTrackFormat(it).metadata }
        }
        if (tracks.groups.isNotEmpty()) metadataReady.complete(metadata)
    }

    // ---------------- 歌词 ----------------

    private suspend fun loadLyrics(e: MediaEntry, tags: TrackTags) {
        val cached = withContext(Dispatchers.IO) { lyricsFile(e).takeIf { it.exists() }?.readText() }
        val local = cached?.let { Lyrics.parse(it, "已保存") }
            ?: tags.lyrics?.let { Lyrics.parse(it, "内嵌") }
            ?: withContext(Dispatchers.IO) { sidecarLrc(e) }?.let { Lyrics.parse(it, "LRC 文件") }
        if (local != null) {
            val tr = withContext(Dispatchers.IO) { savedTranslations(e, local) }
            _state.update { it.copy(lyrics = local, lyricsStatus = null, translations = tr) }
            return
        }
        if (!settings().musicOnlineLookup) {
            _state.update { it.copy(lyricsStatus = "没有歌词") }
            return
        }
        _state.update { it.copy(lyricsStatus = "正在在线查找歌词…") }
        val found = withContext(Dispatchers.IO) { MusicLookup.bestLyrics(tags)?.let(::localize) }
        if (entry != e) return
        if (found != null) {
            withContext(Dispatchers.IO) { lyricsFile(e).writeText(found.toLrc()) }
            _state.update { it.copy(lyrics = found, lyricsStatus = null) }
        } else {
            _state.update { it.copy(lyricsStatus = "没有找到歌词") }
        }
    }

    /** 本地歌曲旁边的同名 .lrc。Android 11 起读不到别的应用放的文件，读不到就算了。 */
    @Suppress("DEPRECATION")
    private fun sidecarLrc(e: MediaEntry): String? {
        if (!FileOps.isLocal(e) || e.folder.isBlank()) return null
        val base = e.title.substringBeforeLast('.')
        val dir = File(Environment.getExternalStorageDirectory(), e.folder.trim('/'))
        return listOf("$base.lrc", "$base.LRC").firstNotNullOfOrNull { name ->
            runCatching { File(dir, name).takeIf { it.canRead() }?.readText() }.getOrNull()
        }
    }

    suspend fun searchLyrics(title: String, artist: String?): List<SongCandidate> = withContext(Dispatchers.IO) {
        MusicLookup.searchLyrics(title, artist?.takeIf { it.isNotBlank() }, _state.value.tags?.durationMs ?: 0)
    }

    /** 用选中的搜索结果作为这首歌的歌词。返回是否成功。 */
    suspend fun applyLyrics(c: SongCandidate): Boolean {
        val e = entry ?: return false
        val lyrics = withContext(Dispatchers.IO) { MusicLookup.lyricsOf(c)?.let(::localize) } ?: return false
        saveLyrics(e, lyrics)
        return true
    }

    /** 在线找到的繁体歌词，翻译目标是简体中文时转成简体（LRCLIB 的华语歌词很多是繁体）。 */
    private fun localize(l: Lyrics): Lyrics =
        if (settings().ttsLocale != "zh-CN") l
        else l.copy(lines = l.lines.map { it.copy(text = ChineseScript.toSimplified(it.text)) })

    /** 导入 LRC / TXT 歌词文件。返回是否成功。 */
    suspend fun importLyrics(uri: Uri): Boolean {
        val e = entry ?: return false
        val text = withContext(Dispatchers.IO) {
            runCatching { app.contentResolver.openInputStream(uri)?.use { com.aisub.player.subtitle.decodeText(it.readBytes()) } }.getOrNull()
        } ?: return false
        val lyrics = Lyrics.parse(text, "导入") ?: return false
        saveLyrics(e, lyrics)
        return true
    }

    /** 整体调早（负数）或调晚歌词时间，保存。 */
    fun shiftLyrics(deltaMs: Long) {
        val e = entry ?: return
        val l = _state.value.lyrics?.takeIf { it.synced } ?: return
        saveLyrics(e, l.copy(lines = l.lines.map { it.copy(timeMs = (it.timeMs!! + deltaMs).coerceAtLeast(0)) }))
    }

    private fun saveLyrics(e: MediaEntry, lyrics: Lyrics) {
        // 只是整体调了时间时，每行的字没变，翻译照用
        val sameText = _state.value.lyrics?.lines?.map { it.text } == lyrics.lines.map { it.text }
        _state.update { it.copy(lyrics = lyrics, lyricsStatus = null, translations = it.translations.takeIf { sameText }) }
        scope.launch(Dispatchers.IO) {
            lyricsFile(e).writeText(lyrics.toLrc())
            if (!sameText) translationFile(e).delete()
        }
    }

    // ---------------- 歌词翻译 ----------------

    private fun translationFile(e: MediaEntry) = File(lyricsDir, key(e) + ".tr.json")

    /** 存下的翻译；歌词换过（行数对不上）就不用 */
    private fun savedTranslations(e: MediaEntry, lyrics: Lyrics): List<String?>? = runCatching {
        val arr = org.json.JSONArray(translationFile(e).readText())
        if (arr.length() != lyrics.lines.size) null
        else List(arr.length()) { i -> arr.optString(i).takeIf { it.isNotEmpty() } }
    }.getOrNull()

    /** 用设置里的翻译服务把歌词翻成目标语言。返回出错时给用户看的提示，成功返回 null。 */
    suspend fun translateLyrics(): String? {
        val e = entry ?: return "没有正在播放的歌曲"
        val lyrics = _state.value.lyrics ?: return "这首歌还没有歌词"
        val s = settings()
        val translator = s.createTranslator() ?: return s.translatorProblem() ?: "翻译服务没有设置好"
        _state.update { it.copy(translating = true) }
        try {
            val texts = lyrics.lines.map { it.text }
            // 上次翻到一半失败时接着翻，已经翻好的行不再请求
            val out = _state.value.translations?.takeIf { it.size == texts.size }?.toTypedArray() ?: arrayOfNulls(texts.size)
            // 空行、纯符号的行不用翻；一次送 20 行（和字幕一样，太多免费服务容易超时），前面几行作上下文
            val todo = texts.indices.filter { out[it] == null && texts[it].any(Char::isLetter) }
            try {
                for (chunk in todo.chunked(20)) {
                    val context = texts.subList(maxOf(0, chunk.first() - 3), chunk.first())
                    val result = withContext(Dispatchers.IO) { translator.translate(chunk.map { texts[it] }, context, s.targetLanguage) }
                    chunk.forEachIndexed { k, i -> out[i] = result.getOrNull(k)?.trim()?.takeIf { it.isNotEmpty() && it != texts[i].trim() } }
                    if (entry != e) return null
                    _state.update { it.copy(translations = out.toList()) }
                }
            } finally {
                // 失败时也把翻好的部分存下
                if (entry == e && out.any { it != null }) {
                    withContext(NonCancellable + Dispatchers.IO) { translationFile(e).writeText(org.json.JSONArray(out.map { it ?: "" }).toString()) }
                }
            }
            return null
        } catch (ex: Exception) {
            if (ex is kotlinx.coroutines.CancellationException) throw ex
            val done = _state.value.translations?.count { it != null } ?: 0
            return "翻译失败：${ex.message}" + if (done > 0) "\n已翻译的部分已保存，再点「生成翻译」会接着翻剩下的" else ""
        } finally {
            _state.update { it.copy(translating = false) }
        }
    }

    /** 不显示翻译（存下的也删掉） */
    fun clearTranslations() {
        val e = entry ?: return
        _state.update { it.copy(translations = null) }
        scope.launch(Dispatchers.IO) { translationFile(e).delete() }
    }

    // ---------------- 封面 ----------------

    private suspend fun loadCover(e: MediaEntry, tags: TrackTags) {
        val cached = withContext(Dispatchers.IO) { coverFile(e).takeIf { it.exists() }?.readBytes()?.let(::decode) }
        val (bitmap, source) = when {
            cached != null -> cached to "已保存"
            else -> withContext(Dispatchers.IO) {
                tags.cover?.let(::decode)?.let { it to "内嵌" } ?: mediaStoreCover(e)?.let { it to "文件夹" }
            } ?: (null to null)
        }
        if (bitmap != null) {
            _state.update { it.copy(cover = bitmap, coverSource = source) }
            withContext(Dispatchers.IO) { saveThumb(app, e.uri, bitmap) }
            return
        }
        if (!settings().musicOnlineLookup) return
        val bytes = withContext(Dispatchers.IO) { MusicLookup.bestCover(tags) } ?: return
        val online = withContext(Dispatchers.IO) { decode(bytes)?.also { coverFile(e).writeBytes(bytes) } } ?: return
        if (entry == e) _state.update { it.copy(cover = online, coverSource = "在线") }
    }

    /** 系统媒体库给的封面：内嵌图片，或歌曲文件夹里的 AlbumArt.jpg、cover.jpg、folder.jpg 等。 */
    private fun mediaStoreCover(e: MediaEntry): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !FileOps.isLocal(e)) return null
        return runCatching { app.contentResolver.loadThumbnail(Uri.parse(e.uri), Size(800, 800), null) }.getOrNull()
    }

    suspend fun searchCovers(title: String, artist: String?): List<SongCandidate> = withContext(Dispatchers.IO) {
        MusicLookup.searchCovers(title, artist?.takeIf { it.isNotBlank() })
    }

    /** 用选中的搜索结果作为封面。返回是否成功。 */
    suspend fun applyCover(c: SongCandidate): Boolean {
        val e = entry ?: return false
        val url = c.coverUrl ?: return false
        val (bitmap, bytes) = withContext(Dispatchers.IO) { MusicLookup.download(url)?.let { decode(it) to it } } ?: return false
        bitmap ?: return false
        withContext(Dispatchers.IO) { coverFile(e).writeBytes(bytes) }
        _state.update { it.copy(cover = bitmap, coverSource = c.source) }
        return true
    }

    // ---------------- 工具 ----------------

    private fun key(e: MediaEntry): String =
        MessageDigest.getInstance("SHA-1").digest(e.uri.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun lyricsFile(e: MediaEntry) = File(lyricsDir, key(e) + ".lrc")
    private fun coverFile(e: MediaEntry) = File(coverDir, key(e) + ".img")

    companion object {
        private fun thumbFile(context: android.content.Context, key: String) = File(File(context.cacheDir, "song-thumbs"), "$key.jpg")

        private fun keyOf(uri: String) = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }

        /** 播放时找到的封面（内嵌、文件夹）存一张小图，最近播放等列表里直接用 */
        private fun saveThumb(context: android.content.Context, uri: String, bitmap: Bitmap) = runCatching {
            val f = thumbFile(context, keyOf(uri)).apply { parentFile?.mkdirs() }
            val scale = 300f / maxOf(bitmap.width, bitmap.height)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
            f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }

        /**
         * 列表、最近播放里的歌曲封面：在线找到或手动选过的 → 播放时存下的小图 → 系统媒体库的（内嵌或文件夹封面）
         * → 直接读文件里内嵌的封面（不在媒体库里的文件，例如从文件管理器打开的）。都没有返回 null，界面显示音乐图标。
         */
        fun thumbnail(context: android.content.Context, uri: String, maxPx: Int = 300): Bitmap? {
            val key = keyOf(uri)
            val file = File(File(context.filesDir, "covers"), "$key.img")
            if (file.exists()) {
                val bytes = runCatching { file.readBytes() }.getOrNull()
                if (bytes != null) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    var sample = 1
                    while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxPx) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.let { return it }
                }
            }
            thumbFile(context, key).takeIf { it.exists() }?.let { f -> BitmapFactory.decodeFile(f.path)?.let { return it } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.startsWith("content://media/")) {
                runCatching { context.contentResolver.loadThumbnail(Uri.parse(uri), Size(maxPx, maxPx), null) }.getOrNull()?.let { return it }
            }
            if (!uri.startsWith("content://") && !uri.startsWith("file://")) return null
            val art = runCatching {
                android.media.MediaMetadataRetriever().run {
                    try {
                        setDataSource(context, Uri.parse(uri))
                        embeddedPicture
                    } finally {
                        release()
                    }
                }
            }.getOrNull() ?: return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(art, 0, art.size, opts)
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxPx) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(art, 0, art.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            saveThumb(context, uri, bitmap)
            return bitmap
        }
    }

    /** 解码图片，长边缩到 1024 以内。 */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
