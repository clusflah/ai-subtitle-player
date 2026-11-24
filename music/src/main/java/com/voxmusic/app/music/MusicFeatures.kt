package com.voxmusic.app.music

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.Player
import com.voxmusic.app.AppSettings
import com.voxmusic.app.media.MediaEntry
import com.voxmusic.app.media.TrackMetadataRegistry
import com.voxmusic.app.net.musicHttp
import com.voxmusic.app.translate.ChineseScript
import com.voxmusic.app.util.decodeText
import kotlinx.coroutines.CancellationException
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
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 当前播放的音乐：标签、封面、歌词及译文。tags 为 null 表示当前没有播放歌曲。 */
data class MusicState(
    val tags: TrackTags? = null,
    val cover: Bitmap? = null,
    val coverSource: String? = null,
    val lyrics: Lyrics? = null,
    val lyricsStatus: String? = null,
    val edited: Boolean = false,
    val translations: List<String?>? = null,
    val translating: Boolean = false,
)

/**
 * 歌曲封面、同步歌词与 AI 歌词翻译管理：
 * - 封面：手动保存 → 流媒体元数据封面 → 在线查找（网易云 / iTunes）；
 * - 歌词：手动保存/导入 → 在线自动匹配（LRCLIB / 网易云）；
 * - 歌词翻译：使用已配置的 AI 模型逐批翻译并持久化保存。
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
    private val infoDir get() = File(app.filesDir, "music_info").apply { mkdirs() }

    private var entry: MediaEntry? = null
    private var rawTags: TrackTags? = null
    private var loadJob: Job? = null
    private var lyricsJob: Job? = null

    fun onEntryChanged(e: MediaEntry) {
        if (entry?.uri == e.uri) return
        entry = e
        loadJob?.cancel()
        lyricsJob?.cancel()
        val initialTags = TrackTags.fromFileName(e.title, e.durationMs)
        _state.value = MusicState(tags = initialTags)
        loadJob = scope.launch {
            val duration = player().duration.takeIf { it > 0L } ?: e.durationMs
            val meta = TrackMetadataRegistry.find(e.uri)
            val fallbackTitle = e.displayTitle
            val tags = if (meta == null) {
                initialTags.copy(durationMs = duration)
            } else {
                TrackTags(
                    title = meta.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                    artist = meta.artist?.takeIf { it.isNotBlank() } ?: initialTags.artist,
                    album = meta.album?.takeIf { it.isNotBlank() },
                    durationMs = if (duration > 0L) duration else meta.durationMs,
                )
            }
            if (entry?.uri != e.uri) return@launch
            rawTags = tags
            val shown = withContext(Dispatchers.IO) { applySavedInfo(e, tags) }
            if (entry?.uri != e.uri) return@launch
            _state.update { it.copy(tags = shown, edited = shown !== tags) }
            launch { loadCover(e, shown) }
            launchLyricsLoad(e, shown)
        }
    }

    private fun launchLyricsLoad(e: MediaEntry, tags: TrackTags) {
        lyricsJob?.cancel()
        lyricsJob = scope.launch { loadLyrics(e, tags) }
    }

    private fun isGenericPlaceholderTitle(t: String?): Boolean {
        val s = t?.trim().orEmpty()
        if (s.isEmpty() || s == "未知歌曲") return true
        return WebMusicService.entries.any { it.label.equals(s, ignoreCase = true) }
    }

    /** 流媒体后台播放器实时解析到更完整的歌名/歌手/专辑/时长/封面时动态补齐封面与同步歌词 */
    fun onStreamingMetadataUpdated(
        uri: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        coverUrl: String?,
    ) {
        val e = entry ?: return
        if (e.uri != uri) return
        val cur = _state.value
        val oldTags = cur.tags ?: TrackTags(title = title.ifBlank { e.displayTitle })
        val updatedTags = if (cur.edited) {
            oldTags.copy(durationMs = if (durationMs > 0L) durationMs else oldTags.durationMs)
        } else {
            val effectiveTitle = if (isGenericPlaceholderTitle(oldTags.title) && title.isNotBlank()) title else oldTags.title
            val effectiveArtist = if (oldTags.artist.isNullOrBlank() && artist.isNotBlank()) artist else oldTags.artist
            val effectiveAlbum = if (oldTags.album.isNullOrBlank() && album.isNotBlank()) album else oldTags.album
            oldTags.copy(
                title = effectiveTitle,
                artist = effectiveArtist,
                album = effectiveAlbum,
                durationMs = if (durationMs > 0L) durationMs else oldTags.durationMs,
            )
        }
        val titleOrArtistChanged = updatedTags.title != oldTags.title || updatedTags.artist != oldTags.artist
        rawTags = updatedTags
        _state.update { it.copy(tags = updatedTags) }
        if (cur.cover == null && !coverUrl.isNullOrBlank()) {
            scope.launch { loadCover(e, updatedTags) }
        }
        if ((titleOrArtistChanged || (cur.lyrics == null && lyricsJob?.isActive != true)) && settings().musicOnlineLookup && updatedTags.title.isNotBlank()) {
            launchLyricsLoad(e, updatedTags)
        }
    }

    // ---------------- 编辑歌曲信息 ----------------

    private fun infoFile(e: MediaEntry) = File(infoDir, key(e) + ".json")

    private fun applySavedInfo(e: MediaEntry, tags: TrackTags): TrackTags {
        val o = runCatching { JSONObject(infoFile(e).readText()) }.getOrNull() ?: return tags
        fun s(k: String) = o.optString(k).trim().takeIf { it.isNotEmpty() }
        return tags.copy(title = s("title") ?: tags.title, artist = s("artist"), album = s("album"))
    }

    suspend fun searchSongs(title: String, artist: String?): List<SongCandidate> = withContext(Dispatchers.IO) {
        MusicLookup.searchSongs(title, artist?.takeIf { it.isNotBlank() }, _state.value.tags?.durationMs ?: 0L)
    }

    suspend fun saveInfo(title: String, artist: String, album: String, coverFrom: SongCandidate?) {
        val e = entry ?: return
        val old = _state.value.tags ?: return
        val tags = old.copy(
            title = title.trim().ifEmpty { old.title },
            artist = artist.trim().ifEmpty { null },
            album = album.trim().ifEmpty { null },
        )
        withContext(Dispatchers.IO) {
            infoFile(e).writeText(
                JSONObject()
                    .put("title", tags.title)
                    .put("artist", tags.artist.orEmpty())
                    .put("album", tags.album.orEmpty())
                    .toString(),
            )
        }
        _state.update { it.copy(tags = tags, edited = true) }
        coverFrom?.takeIf { it.coverUrl != null }?.let { applyCover(it) }
        if (_state.value.lyrics == null && settings().musicOnlineLookup) loadLyrics(e, tags)
    }

    suspend fun resetInfo() {
        val e = entry ?: return
        val raw = rawTags ?: return
        withContext(Dispatchers.IO) { infoFile(e).delete() }
        _state.update { it.copy(tags = raw, edited = false) }
    }

    // ---------------- 歌词 ----------------

    private fun normTitleForLyricsCache(t: String): String =
        t.trim().lowercase().replace(Regex("[\\s\\p{Punct}（）()【】\\[\\]《》·•—\\-]+"), "")

    private fun isCachedLyricsValidForTrack(tags: TrackTags, rawCached: String): Boolean {
        val firstLine = rawCached.lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine == "[vox_manual:1]") return true
        val autoTagPrefix = "[vox_auto_ti:"
        if (firstLine.startsWith(autoTagPrefix) && firstLine.endsWith("]")) {
            val savedTitle = firstLine.removePrefix(autoTagPrefix).removeSuffix("]")
            val want = normTitleForLyricsCache(tags.title)
            val got = normTitleForLyricsCache(savedTitle)
            return want.isEmpty() || got.isEmpty() || want == got
        }
        return false
    }

    private suspend fun loadLyrics(e: MediaEntry, tags: TrackTags) {
        if (entry?.uri != e.uri) return
        val cached = withContext(Dispatchers.IO) {
            lyricsFile(e).takeIf { it.exists() }?.readText()?.takeIf { isCachedLyricsValidForTrack(tags, it) }
        }
        val local = cached?.let { Lyrics.parse(it, "已保存") }
        if (entry?.uri != e.uri) return
        if (local != null) {
            val tr = withContext(Dispatchers.IO) { savedTranslations(e, local) }
            if (entry?.uri != e.uri) return
            _state.update { it.copy(lyrics = local, lyricsStatus = null, translations = tr) }
            return
        }
        if (!settings().musicOnlineLookup) {
            _state.update { it.copy(lyrics = null, lyricsStatus = "没有歌词", translations = null) }
            return
        }
        _state.update { it.copy(lyrics = null, lyricsStatus = "正在在线查找歌词…", translations = null) }
        val found = withContext(Dispatchers.IO) { MusicLookup.bestLyrics(tags)?.let(::localize) }
        if (entry?.uri != e.uri) return
        if (found != null) {
            val header = "[vox_auto_ti:${tags.title.replace("\n", " ").replace("]", "")}]\n"
            withContext(Dispatchers.IO) { lyricsFile(e).writeText(header + found.toLrc()) }
            if (entry?.uri != e.uri) return
            _state.update { it.copy(lyrics = found, lyricsStatus = null, translations = null) }
        } else {
            withContext(Dispatchers.IO) { lyricsFile(e).delete() }
            if (entry?.uri != e.uri) return
            _state.update { it.copy(lyrics = null, lyricsStatus = "没有找到歌词", translations = null) }
        }
    }

    suspend fun searchLyrics(title: String, artist: String?): List<SongCandidate> = withContext(Dispatchers.IO) {
        MusicLookup.searchLyrics(title, artist?.takeIf { it.isNotBlank() }, _state.value.tags?.durationMs ?: 0L)
    }

    suspend fun applyLyrics(c: SongCandidate): Boolean {
        val e = entry ?: return false
        val lyrics = withContext(Dispatchers.IO) { MusicLookup.lyricsOf(c)?.let(::localize) } ?: return false
        saveLyrics(e, lyrics)
        return true
    }

    private fun localize(l: Lyrics): Lyrics =
        if (settings().targetLocale != "zh-CN") l
        else l.copy(lines = l.lines.map { it.copy(text = ChineseScript.toSimplified(it.text)) })

    suspend fun importLyrics(uri: Uri): Boolean {
        val e = entry ?: return false
        val text = withContext(Dispatchers.IO) {
            runCatching { app.contentResolver.openInputStream(uri)?.use { decodeText(it.readBytes()) } }.getOrNull()
        } ?: return false
        val lyrics = Lyrics.parse(text, "导入") ?: return false
        saveLyrics(e, lyrics)
        return true
    }

    fun shiftLyrics(deltaMs: Long) {
        val e = entry ?: return
        val l = _state.value.lyrics?.takeIf { it.synced } ?: return
        saveLyrics(e, l.copy(lines = l.lines.map { it.copy(timeMs = (it.timeMs!! + deltaMs).coerceAtLeast(0L)) }))
    }

    private fun saveLyrics(e: MediaEntry, lyrics: Lyrics) {
        val sameText = _state.value.lyrics?.lines?.map { it.text } == lyrics.lines.map { it.text }
        _state.update { it.copy(lyrics = lyrics, lyricsStatus = null, translations = it.translations.takeIf { sameText }) }
        scope.launch(Dispatchers.IO) {
            lyricsFile(e).writeText("[vox_manual:1]\n" + lyrics.toLrc())
            if (!sameText) translationFile(e).delete()
        }
    }

    // ---------------- 歌词 AI 翻译 ----------------

    private fun translationFile(e: MediaEntry) = File(lyricsDir, key(e) + ".tr.json")

    private fun savedTranslations(e: MediaEntry, lyrics: Lyrics): List<String?>? = runCatching {
        val arr = JSONArray(translationFile(e).readText())
        if (arr.length() != lyrics.lines.size) null
        else List(arr.length()) { i -> arr.optString(i).takeIf { it.isNotEmpty() } }
    }.getOrNull()

    suspend fun translateLyrics(): String? {
        val e = entry ?: return "没有正在播放的歌曲"
        val lyrics = _state.value.lyrics ?: return "这首歌还没有歌词"
        val s = settings()
        val translator = s.createTranslator() ?: return s.translatorProblem() ?: "翻译服务没有设置好"
        _state.update { it.copy(translating = true) }
        try {
            val texts = lyrics.lines.map { it.text }
            val out = _state.value.translations?.takeIf { it.size == texts.size }?.toTypedArray() ?: arrayOfNulls(texts.size)
            val todo = texts.indices.filter { out[it] == null && texts[it].any(Char::isLetter) }
            try {
                for (chunk in todo.chunked(20)) {
                    val ctx = texts.subList(maxOf(0, chunk.first() - 3), chunk.first())
                    val result = withContext(Dispatchers.IO) { translator.translate(chunk.map { texts[it] }, ctx, s.targetLanguage) }
                    chunk.forEachIndexed { k, i -> out[i] = result.getOrNull(k)?.trim()?.takeIf { it.isNotEmpty() && it != texts[i].trim() } }
                    if (entry != e) return null
                    _state.update { it.copy(translations = out.toList()) }
                }
            } finally {
                if (entry == e && out.any { it != null }) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        translationFile(e).writeText(JSONArray(out.map { it ?: "" }).toString())
                    }
                }
            }
            return null
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            val done = _state.value.translations?.count { it != null } ?: 0
            return "翻译失败：${ex.message}" + if (done > 0) "\n已翻译的部分已保存，再点「生成翻译」会接着翻剩下的" else ""
        } finally {
            _state.update { it.copy(translating = false) }
        }
    }

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
                remoteCover(e)?.let { it to "专辑封面" }
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
        if (entry == e) {
            _state.update { it.copy(cover = online, coverSource = "在线") }
            withContext(Dispatchers.IO) { saveThumb(app, e.uri, online) }
        }
    }

    private fun remoteCover(e: MediaEntry): Bitmap? {
        val coverUrl = TrackMetadataRegistry.find(e.uri)?.coverUrl ?: e.coverUrl ?: return null
        val bytes = fetchImageBytes(coverUrl) ?: return null
        return decode(bytes)?.also { runCatching { coverFile(e).writeBytes(bytes) } }
    }

    suspend fun searchCovers(title: String, artist: String?): List<SongCandidate> = withContext(Dispatchers.IO) {
        MusicLookup.searchCovers(title, artist?.takeIf { it.isNotBlank() })
    }

    suspend fun applyCover(c: SongCandidate): Boolean {
        val e = entry ?: return false
        val url = c.coverUrl ?: return false
        val (bitmap, bytes) = withContext(Dispatchers.IO) { MusicLookup.download(url)?.let { decode(it) to it } } ?: return false
        bitmap ?: return false
        withContext(Dispatchers.IO) {
            coverFile(e).writeBytes(bytes)
            saveThumb(app, e.uri, bitmap)
        }
        _state.update { it.copy(cover = bitmap, coverSource = c.source) }
        return true
    }

    private fun key(e: MediaEntry): String = keyOf(e.uri)
    private fun lyricsFile(e: MediaEntry) = File(lyricsDir, key(e) + ".lrc")
    private fun coverFile(e: MediaEntry) = File(coverDir, key(e) + ".img")

    companion object {
        private fun thumbFile(context: Context, key: String) = File(File(context.cacheDir, "song-thumbs"), "$key.jpg")

        private fun keyOf(uri: String) =
            MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun fetchImageBytes(url: String): ByteArray? = runCatching {
            val req = Request.Builder().url(url).build()
            musicHttp.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.getOrNull()

        private fun saveThumb(context: Context, uri: String, bitmap: Bitmap) = runCatching {
            val f = thumbFile(context, keyOf(uri)).apply { parentFile?.mkdirs() }
            val scale = 300f / maxOf(bitmap.width, bitmap.height)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
            f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }

        fun thumbnail(context: Context, uri: String, maxPx: Int = 300, fallbackCoverUrl: String? = null): Bitmap? {
            val key = keyOf(uri)
            val file = File(File(context.filesDir, "covers"), "$key.img")
            if (file.exists()) {
                val bytes = runCatching { file.readBytes() }.getOrNull()
                if (bytes != null) decodeScaled(bytes, maxPx)?.let { return it }
            }
            thumbFile(context, key).takeIf { it.exists() }?.let { f ->
                BitmapFactory.decodeFile(f.path)?.let { return it }
            }
            val remoteCover = TrackMetadataRegistry.find(uri)?.coverUrl ?: fallbackCoverUrl
            if (!remoteCover.isNullOrBlank()) {
                val bytes = fetchImageBytes(remoteCover)
                if (bytes != null) {
                    decodeScaled(bytes, maxPx)?.let { bmp ->
                        saveThumb(context, uri, bmp)
                        return bmp
                    }
                }
            }
            return null
        }

        private fun decodeScaled(bytes: ByteArray, maxPx: Int): Bitmap? {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0) return null
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxPx) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? = decodeScaled(bytes, 1024)
}
