package com.aisub.player.media

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「本机」页面的视频、音乐、照片可选过滤条件。
 * 持久化在 SharedPreferences("view") 中，对本机列表、目录统计和搜索实时生效。
 */
data class LocalMediaFilter(
    // ---- 视频过滤 ----
    val minVideoDurationSec: Int = 0,
    val minVideoSizeKb: Long = 0L,
    val maxVideoSizeKb: Long = 0L,
    val videoExtensions: Set<String> = emptySet(),
    val excludeVideoJunk: Boolean = false,
    val videoFolders: Set<String> = emptySet(),
    val videoExcludeKeyword: String = "",

    // ---- 音乐过滤 ----
    val minAudioDurationSec: Int = 0,
    val minAudioSizeKb: Long = 0L,
    val audioExtensions: Set<String> = emptySet(),
    val excludeAudioRingtones: Boolean = false,
    val audioFolders: Set<String> = emptySet(),
    val audioExcludeKeyword: String = "",

    // ---- 图片/照片过滤 ----
    val minImageSizeKb: Long = 0L,
    val maxImageSizeKb: Long = 0L,
    val imageExtensions: Set<String> = emptySet(),
    val excludeScreenshots: Boolean = false,
    val excludeCacheImages: Boolean = false,
    val imageFolders: Set<String> = emptySet(),
    val imageExcludeKeyword: String = "",
) {
    val videoActiveCount: Int
        get() = listOf(
            minVideoDurationSec > 0,
            minVideoSizeKb > 0L,
            maxVideoSizeKb > 0L,
            videoExtensions.isNotEmpty(),
            excludeVideoJunk,
            videoFolders.isNotEmpty(),
            videoExcludeKeyword.isNotBlank(),
        ).count { it }

    val audioActiveCount: Int
        get() = listOf(
            minAudioDurationSec > 0,
            minAudioSizeKb > 0L,
            audioExtensions.isNotEmpty(),
            excludeAudioRingtones,
            audioFolders.isNotEmpty(),
            audioExcludeKeyword.isNotBlank(),
        ).count { it }

    val imageActiveCount: Int
        get() = listOf(
            minImageSizeKb > 0L,
            maxImageSizeKb > 0L,
            imageExtensions.isNotEmpty(),
            excludeScreenshots,
            excludeCacheImages,
            imageFolders.isNotEmpty(),
            imageExcludeKeyword.isNotBlank(),
        ).count { it }

    val totalActiveCount: Int
        get() = videoActiveCount + audioActiveCount + imageActiveCount

    fun activeCountForTab(tabIndex: Int): Int = when (tabIndex) {
        0 -> videoActiveCount
        1 -> audioActiveCount
        2 -> imageActiveCount
        else -> totalActiveCount
    }

    fun matchesVideo(e: MediaEntry): Boolean {
        if (minVideoDurationSec > 0 && e.durationMs > 0L && e.durationMs < minVideoDurationSec * 1000L) return false
        if (minVideoSizeKb > 0L && e.sizeBytes > 0L && e.sizeBytes < minVideoSizeKb * 1024L) return false
        if (maxVideoSizeKb > 0L && e.sizeBytes > maxVideoSizeKb * 1024L) return false
        if (videoExtensions.isNotEmpty()) {
            val ext = e.title.substringAfterLast('.', "").lowercase()
            if (ext !in videoExtensions) return false
        }
        if (excludeVideoJunk && isVideoJunkEntry(e)) return false
        if (videoFolders.isNotEmpty() && !matchesSelectedFolders(e.folder, videoFolders)) return false
        if (videoExcludeKeyword.isNotBlank() && matchesExcludedKeyword(e, videoExcludeKeyword)) return false
        return true
    }

    fun matchesAudio(e: MediaEntry): Boolean {
        if (minAudioDurationSec > 0 && e.durationMs > 0L && e.durationMs < minAudioDurationSec * 1000L) return false
        if (minAudioSizeKb > 0L && e.sizeBytes > 0L && e.sizeBytes < minAudioSizeKb * 1024L) return false
        if (audioExtensions.isNotEmpty()) {
            val ext = e.title.substringAfterLast('.', "").lowercase()
            if (ext !in audioExtensions) return false
        }
        if (excludeAudioRingtones && isAudioRingtoneOrRecordEntry(e)) return false
        if (audioFolders.isNotEmpty() && !matchesSelectedFolders(e.folder, audioFolders)) return false
        if (audioExcludeKeyword.isNotBlank() && matchesExcludedKeyword(e, audioExcludeKeyword)) return false
        return true
    }

    fun matchesImage(e: MediaEntry): Boolean {
        if (minImageSizeKb > 0L && e.sizeBytes > 0L && e.sizeBytes < minImageSizeKb * 1024L) return false
        if (maxImageSizeKb > 0L && e.sizeBytes > maxImageSizeKb * 1024L) return false
        if (imageExtensions.isNotEmpty()) {
            val ext = normalizeImageExt(e.title.substringAfterLast('.', "").lowercase())
            if (ext !in imageExtensions) return false
        }
        if (excludeScreenshots && isScreenshotEntry(e)) return false
        if (excludeCacheImages && isCacheOrStickerImageEntry(e)) return false
        if (imageFolders.isNotEmpty() && !matchesSelectedFolders(e.folder, imageFolders)) return false
        if (imageExcludeKeyword.isNotBlank() && matchesExcludedKeyword(e, imageExcludeKeyword)) return false
        return true
    }

    fun clearTab(tabIndex: Int): LocalMediaFilter = when (tabIndex) {
        0 -> copy(
            minVideoDurationSec = 0,
            minVideoSizeKb = 0L,
            maxVideoSizeKb = 0L,
            videoExtensions = emptySet(),
            excludeVideoJunk = false,
            videoFolders = emptySet(),
            videoExcludeKeyword = "",
        )
        1 -> copy(
            minAudioDurationSec = 0,
            minAudioSizeKb = 0L,
            audioExtensions = emptySet(),
            excludeAudioRingtones = false,
            audioFolders = emptySet(),
            audioExcludeKeyword = "",
        )
        2 -> copy(
            minImageSizeKb = 0L,
            maxImageSizeKb = 0L,
            imageExtensions = emptySet(),
            excludeScreenshots = false,
            excludeCacheImages = false,
            imageFolders = emptySet(),
            imageExcludeKeyword = "",
        )
        else -> LocalMediaFilter()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("minVideoDurationSec", minVideoDurationSec)
        .put("minVideoSizeKb", minVideoSizeKb)
        .put("maxVideoSizeKb", maxVideoSizeKb)
        .put("videoExtensions", JSONArray(videoExtensions.sorted()))
        .put("excludeVideoJunk", excludeVideoJunk)
        .put("videoFolders", JSONArray(videoFolders.sorted()))
        .put("videoExcludeKeyword", videoExcludeKeyword)
        .put("minAudioDurationSec", minAudioDurationSec)
        .put("minAudioSizeKb", minAudioSizeKb)
        .put("audioExtensions", JSONArray(audioExtensions.sorted()))
        .put("excludeAudioRingtones", excludeAudioRingtones)
        .put("audioFolders", JSONArray(audioFolders.sorted()))
        .put("audioExcludeKeyword", audioExcludeKeyword)
        .put("minImageSizeKb", minImageSizeKb)
        .put("maxImageSizeKb", maxImageSizeKb)
        .put("imageExtensions", JSONArray(imageExtensions.sorted()))
        .put("excludeScreenshots", excludeScreenshots)
        .put("excludeCacheImages", excludeCacheImages)
        .put("imageFolders", JSONArray(imageFolders.sorted()))
        .put("imageExcludeKeyword", imageExcludeKeyword)

    companion object {
        fun fromJson(o: JSONObject): LocalMediaFilter {
            fun readSet(key: String): Set<String> {
                val arr = o.optJSONArray(key) ?: return emptySet()
                val out = LinkedHashSet<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotEmpty()) out += s
                }
                return out
            }
            return LocalMediaFilter(
                minVideoDurationSec = o.optInt("minVideoDurationSec", 0).coerceAtLeast(0),
                minVideoSizeKb = o.optLong("minVideoSizeKb", 0L).coerceAtLeast(0L),
                maxVideoSizeKb = o.optLong("maxVideoSizeKb", 0L).coerceAtLeast(0L),
                videoExtensions = readSet("videoExtensions").map { it.lowercase() }.toSet(),
                excludeVideoJunk = o.optBoolean("excludeVideoJunk", false),
                videoFolders = readSet("videoFolders"),
                videoExcludeKeyword = o.optString("videoExcludeKeyword", ""),
                minAudioDurationSec = o.optInt("minAudioDurationSec", 0).coerceAtLeast(0),
                minAudioSizeKb = o.optLong("minAudioSizeKb", 0L).coerceAtLeast(0L),
                audioExtensions = readSet("audioExtensions").map { it.lowercase() }.toSet(),
                excludeAudioRingtones = o.optBoolean("excludeAudioRingtones", false),
                audioFolders = readSet("audioFolders"),
                audioExcludeKeyword = o.optString("audioExcludeKeyword", ""),
                minImageSizeKb = o.optLong("minImageSizeKb", 0L).coerceAtLeast(0L),
                maxImageSizeKb = o.optLong("maxImageSizeKb", 0L).coerceAtLeast(0L),
                imageExtensions = readSet("imageExtensions").map { normalizeImageExt(it.lowercase()) }.toSet(),
                excludeScreenshots = o.optBoolean("excludeScreenshots", false),
                excludeCacheImages = o.optBoolean("excludeCacheImages", false),
                imageFolders = readSet("imageFolders"),
                imageExcludeKeyword = o.optString("imageExcludeKeyword", ""),
            )
        }

        internal fun normalizeImageExt(ext: String): String = when (ext) {
            "jpeg" -> "jpg"
            "heif" -> "heic"
            else -> ext
        }

        private fun matchesSelectedFolders(entryFolder: String, allowed: Set<String>): Boolean {
            if (allowed.isEmpty()) return true
            return allowed.any { sel ->
                entryFolder == sel || (sel.isNotEmpty() && entryFolder.startsWith(sel.trimEnd('/') + "/"))
            }
        }

        private fun matchesExcludedKeyword(e: MediaEntry, rawKeywords: String): Boolean {
            val tokens = rawKeywords.split(',', '，', ';', '；', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return false
            return tokens.any { kw ->
                e.title.contains(kw, ignoreCase = true) || e.folder.contains(kw, ignoreCase = true)
            }
        }

        private fun isVideoJunkEntry(e: MediaEntry): Boolean {
            val f = e.folder.lowercase()
            val n = e.title.lowercase()
            return f.contains("screenshot") || f.contains("screenrecord") || f.contains("screen_capture") ||
                f.contains("录屏") || f.contains("截屏") ||
                f.contains("/cache") || f.startsWith("cache") || f.contains(".thumbnails") ||
                f.contains("/temp") || f.startsWith("temp") || f.startsWith("android/data") ||
                n.startsWith("screenrecord") || n.startsWith("vid_cache")
        }

        private fun isAudioRingtoneOrRecordEntry(e: MediaEntry): Boolean {
            val f = e.folder.lowercase()
            return f.contains("ringtone") || f.contains("notification") || f.contains("alarm") ||
                f.contains("recordings") || f.contains("callrecord") || f.contains("soundrecorder") ||
                f.contains("铃声") || f.contains("录音") || f.contains("通话录音") ||
                f.startsWith("android/data")
        }

        private fun isScreenshotEntry(e: MediaEntry): Boolean {
            val f = e.folder.lowercase()
            val n = e.title.lowercase()
            return f.contains("screenshot") || f.contains("screencapture") ||
                f.contains("截屏") || f.contains("截图") ||
                n.startsWith("screenshot") || n.startsWith("截屏") || n.startsWith("屏幕截图")
        }

        private fun isCacheOrStickerImageEntry(e: MediaEntry): Boolean {
            val f = e.folder.lowercase()
            return f.contains(".thumbnails") || f.contains("/cache") || f.startsWith("cache") ||
                f.contains("/temp") || f.startsWith("temp") || f.contains("sticker") ||
                f.contains("emoji") || f.contains("表情") || f.startsWith("android/data")
        }
    }
}

object LocalMediaFilterStore {
    private const val PREFS_NAME = "view"
    private const val KEY_FILTER = "local_media_filter"

    @Volatile
    private var initialized = false
    private val _filter = MutableStateFlow(LocalMediaFilter())
    val filter: StateFlow<LocalMediaFilter> = _filter.asStateFlow()

    fun ensureInit(context: Context): LocalMediaFilter {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    _filter.value = readFromPrefs(context)
                    initialized = true
                }
            }
        }
        return _filter.value
    }

    fun reload(context: Context): LocalMediaFilter {
        val loaded = readFromPrefs(context)
        _filter.value = loaded
        initialized = true
        return loaded
    }

    fun save(context: Context, next: LocalMediaFilter) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FILTER, next.toJson().toString())
            .apply()
        _filter.value = next
        initialized = true
    }

    fun update(context: Context, transform: (LocalMediaFilter) -> LocalMediaFilter) {
        val cur = ensureInit(context)
        save(context, transform(cur))
    }

    fun clearTab(context: Context, tabIndex: Int) {
        update(context) { it.clearTab(tabIndex) }
    }

    fun resetAll(context: Context) {
        save(context, LocalMediaFilter())
    }

    private fun readFromPrefs(context: Context): LocalMediaFilter = runCatching {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FILTER, null)
        if (raw.isNullOrBlank()) LocalMediaFilter() else LocalMediaFilter.fromJson(JSONObject(raw))
    }.getOrDefault(LocalMediaFilter())
}
