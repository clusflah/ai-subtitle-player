package android.content

import android.app.Application
import android.app.DownloadManager
import android.app.NotificationManager
import android.app.UiModeManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import android.content.res.Resources
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import com.aisub.player.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object ContentUris {
    @JvmStatic
    fun withAppendedId(contentUri: Uri, id: Long): Uri {
        return contentUri.buildUpon().appendPath(id.toString()).build()
    }

    @JvmStatic
    fun parseId(contentUri: Uri): Long {
        return contentUri.lastPathSegment?.toLongOrNull() ?: -1L
    }
}

class ContentValues {
    internal val values = HashMap<String, Any?>()
    fun put(key: String, value: String?) { values[key] = value }
    fun put(key: String, value: Int?) { values[key] = value }
    fun put(key: String, value: Long?) { values[key] = value }
    fun put(key: String, value: Boolean?) { values[key] = value }
    fun getAsString(key: String): String? = values[key]?.toString()
    fun getAsInteger(key: String): Int? = (values[key] as? Number)?.toInt()
}

class ContentResolver(private val context: Context) {
    fun openInputStream(uri: Uri): InputStream? {
        val file = resolveFile(uri)
        if (file != null && file.exists() && file.isFile) {
            return FileInputStream(file)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            return runCatching { URI(uri.toString()).toURL().openStream() }.getOrNull()
        }
        return null
    }

    fun openOutputStream(uri: Uri, mode: String = "w"): OutputStream? {
        val file = resolveFile(uri) ?: return null
        file.parentFile?.mkdirs()
        return FileOutputStream(file, mode.contains('a'))
    }

    fun openFileDescriptor(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = resolveFile(uri) ?: return null
        if (!file.exists() && !mode.contains('w')) return null
        return ParcelFileDescriptor(file)
    }

    fun openAssetFileDescriptor(uri: Uri, mode: String): AssetFileDescriptor? {
        val file = resolveFile(uri) ?: return null
        if (!file.exists()) return null
        return AssetFileDescriptor(file)
    }

    fun getType(uri: Uri): String? {
        val file = resolveFile(uri)
        val name = file?.name ?: uri.lastPathSegment ?: uri.toString()
        if (file?.isDirectory == true) return DocumentsContract.Document.MIME_TYPE_DIR
        return guessMimeType(name, file)
    }

    fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {}

    fun loadThumbnail(uri: Uri, size: Size, signal: Any?): Bitmap? {
        val file = resolveFile(uri) ?: return null
        if (!file.exists()) return null
        val ext = file.extension.lowercase()
        if (ext in VIDEO_EXTS && isValidMediaFile(file, ext, MediaScanKind.VIDEO)) {
            return android.media.MediaMetadataRetriever().use { r ->
                r.setDataSource(file.absolutePath)
                r.getScaledFrameAtTime(
                    1_000_000L,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    size.width.coerceAtLeast(160),
                    size.height.coerceAtLeast(90),
                )
            }
        }
        if (ext in AUDIO_EXTS) {
            val embedded = android.media.MediaMetadataRetriever().use { r ->
                r.setDataSource(file.absolutePath)
                r.embeddedPicture
            }
            if (embedded != null && embedded.isNotEmpty()) {
                BitmapFactory.decodeByteArray(embedded, 0, embedded.size)?.let { return it }
            }
            val dir = file.parentFile
            if (dir != null) {
                for (coverName in listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png")) {
                    val coverFile = File(dir, coverName)
                    if (coverFile.isFile) {
                        BitmapFactory.decodeFile(coverFile.absolutePath)?.let { return it }
                    }
                }
            }
            return null
        }
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        val cols = projection ?: arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            MediaStore.MediaColumns.DATA,
        )
        val uriStr = uri.toString()
        val lastSeg = uri.lastPathSegment
        val isSingleMediaItem = uriStr.startsWith("content://media/") && lastSeg?.toLongOrNull() != null

        // 1. 按相对路径 + 文件名精确查询（用于 SidecarSrt / MusicFeatures 同目录文件查找）
        if (uriStr.startsWith("content://media/") && !isSingleMediaItem &&
            selection != null && selection.contains(MediaStore.MediaColumns.RELATIVE_PATH) &&
            selection.contains(MediaStore.MediaColumns.DISPLAY_NAME) &&
            selectionArgs != null && selectionArgs.size >= 2
        ) {
            val relDir = selectionArgs[0].trim('/', '\\')
            val displayName = selectionArgs[1]
            val target = File(File(Environment.getExternalStorageDirectory(), relDir), displayName)
            if (target.exists() && target.isFile) {
                val id = registerFileId(target)
                val rel = relativePathFromHome(target)
                val row = cols.map { col ->
                    when (col) {
                        MediaStore.MediaColumns._ID -> id
                        MediaStore.MediaColumns.DISPLAY_NAME, OpenableColumns.DISPLAY_NAME -> target.name
                        MediaStore.MediaColumns.SIZE, OpenableColumns.SIZE -> target.length()
                        MediaStore.MediaColumns.DATA -> target.absolutePath
                        MediaStore.MediaColumns.RELATIVE_PATH -> rel
                        MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED -> target.lastModified() / 1000L
                        MediaStore.MediaColumns.MIME_TYPE -> guessMimeType(target.name, target)
                        else -> null
                    }
                }.toTypedArray<Any?>()
                return MatrixCursor(cols, listOf(row))
            }
            return MatrixCursor(cols, emptyList())
        }

        // 2. 系统媒体库集合扫描（视频 / 音频 / 图片）
        if (uriStr.startsWith("content://media/") && !isSingleMediaItem) {
            val files = when {
                uriStr.contains("/video/") -> scanMediaFiles(VIDEO_EXTS, listOf("Videos", "Movies", "Downloads"), MediaScanKind.VIDEO)
                uriStr.contains("/audio/") -> scanMediaFiles(AUDIO_EXTS, listOf("Music", "Downloads"), MediaScanKind.AUDIO)
                // 桌面端「照片」仅扫描 Pictures（排除 Downloads 下载杂项及自动截屏文件夹）
                uriStr.contains("/images/") -> scanMediaFiles(IMAGE_EXTS, listOf("Pictures"), MediaScanKind.IMAGE)
                else -> emptyList()
            }
            val rows = files.map { f ->
                val id = registerFileId(f)
                val rel = relativePathFromHome(f)
                cols.map { col ->
                    when (col) {
                        MediaStore.MediaColumns._ID -> id
                        MediaStore.MediaColumns.DISPLAY_NAME, OpenableColumns.DISPLAY_NAME -> f.name
                        MediaStore.MediaColumns.SIZE, OpenableColumns.SIZE -> f.length()
                        MediaStore.MediaColumns.DATA -> f.absolutePath
                        MediaStore.MediaColumns.RELATIVE_PATH -> rel
                        MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED -> f.lastModified() / 1000L
                        MediaStore.Video.VideoColumns.DURATION -> 0L
                        MediaStore.MediaColumns.MIME_TYPE -> guessMimeType(f.name, f)
                        else -> null
                    }
                }.toTypedArray<Any?>()
            }
            return MatrixCursor(cols, rows)
        }

        val file = resolveFile(uri) ?: return MatrixCursor(cols, emptyList())

        // 3. 文件夹子项查询（用于漫画文件夹 DocumentsContract）
        if (file.isDirectory && cols.any { it == DocumentsContract.Document.COLUMN_DOCUMENT_ID }) {
            val children = file.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
            val rows = children.map { child ->
                cols.map { col ->
                    when (col) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> child.absolutePath
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME, OpenableColumns.DISPLAY_NAME -> child.name
                        DocumentsContract.Document.COLUMN_MIME_TYPE ->
                            if (child.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else (guessMimeType(child.name, child) ?: "application/octet-stream")
                        else -> null
                    }
                }.toTypedArray<Any?>()
            }
            return MatrixCursor(cols, rows)
        }

        // 4. 单个文件查询
        if (!file.exists()) return MatrixCursor(cols, emptyList())
        val id = registerFileId(file)
        val rel = relativePathFromHome(file)
        val row = cols.map { col ->
            when (col) {
                MediaStore.MediaColumns._ID -> id
                OpenableColumns.DISPLAY_NAME, MediaStore.MediaColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE, MediaStore.MediaColumns.SIZE -> file.length()
                MediaStore.MediaColumns.DATA -> file.absolutePath
                MediaStore.MediaColumns.RELATIVE_PATH -> rel
                MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED -> file.lastModified() / 1000L
                MediaStore.MediaColumns.MIME_TYPE -> guessMimeType(file.name, file)
                MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT -> 0
                else -> null
            }
        }.toTypedArray<Any?>()
        return MatrixCursor(cols, listOf(row))
    }

    fun insert(url: Uri, values: ContentValues?): Uri? {
        val name = values?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) ?: "file_${System.currentTimeMillis()}"
        val rel = values?.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)?.trim('/', '\\').orEmpty()
        val baseDir = Environment.getExternalStorageDirectory()
        val targetDir = if (rel.isNotEmpty()) File(baseDir, rel) else File(baseDir, "Downloads/Voxtitle")
        targetDir.mkdirs()
        var target = File(targetDir, name)
        var counter = 1
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        while (target.exists() && target.length() > 0L) {
            target = File(targetDir, "$stem ($counter)$ext")
            counter++
        }
        runCatching { target.createNewFile() }
        val id = registerFileId(target)
        if (url.toString().startsWith("content://media/")) {
            return ContentUris.withAppendedId(url, id)
        }
        return Uri.fromFile(target)
    }

    fun update(uri: Uri, values: ContentValues?, where: String?, selectionArgs: Array<String>?): Int {
        val file = resolveFile(uri) ?: return 0
        if (!file.exists()) return 0
        val newRel = values?.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)?.trim('/', '\\')
        val newName = values?.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)?.takeIf { it.isNotBlank() } ?: file.name
        val targetDir = if (!newRel.isNullOrBlank()) {
            File(Environment.getExternalStorageDirectory(), newRel).apply { mkdirs() }
        } else {
            file.parentFile
        }
        val dst = File(targetDir, newName)
        if (dst.absolutePath != file.absolutePath) {
            if (dst.exists()) return 0
            val moved = file.renameTo(dst) || runCatching {
                file.copyTo(dst, overwrite = false)
                file.delete()
                true
            }.getOrDefault(false)
            if (!moved) return 0
            val id = fileToId.remove(file.absolutePath) ?: uri.lastPathSegment?.toLongOrNull()
            if (id != null) {
                fileToId[dst.absolutePath] = id
                idToFile[id] = dst
            }
        }
        return 1
    }

    fun delete(url: Uri, where: String?, selectionArgs: Array<String>?): Int {
        val file = resolveFile(url) ?: return 0
        return if (file.exists() && file.delete()) 1 else 0
    }

    companion object {
        private enum class MediaScanKind { VIDEO, AUDIO, IMAGE }

        private val idToFile = ConcurrentHashMap<Long, File>()
        private val fileToId = ConcurrentHashMap<String, Long>()
        private var nextId = 1000L

        private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "ts", "m2ts", "mts", "wmv", "m4v", "mpg", "mpeg", "3gp", "rmvb")
        private val AUDIO_EXTS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma", "ape", "alac", "aiff", "dsf", "dff")
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")

        /** 软件工程、依赖包、构建缓存、系统与应用资源目录（本地媒体扫描时一律跳过） */
        private val IGNORED_SCAN_DIRS = setOf(
            "node_modules", "oh_modules", "bower_components", "vendor", "packages",
            "__pycache__", "venv", ".venv", "env", "target", "build", "dist", "out",
            "bin", "obj", "src", "lib", "libs", "include", "cmakefiles",
            "assets", "res", "resources", "static", "public",
            "test", "tests", "fixtures", "testdata", "docs",
            "cache", "caches", "temp", "tmp", "logs", "crashreports",
            "shadercache", "webcache", "code cache", "gpucache",
            "appdata", "programdata", "windows", "program files", "program files (x86)",
            "steamapps", "commonredist",
        )

        /** 屏幕截图与表情缓存文件夹（「本机 → 照片」不混入系统自动保存的桌面截图与聊天表情） */
        private val IGNORED_IMAGE_DIRS = setOf(
            "screenshots", "屏幕截图", "截屏", "截图",
            "snipaste", "sharex", "screenpresso", "pixpin", "faststone",
            "bandicam", "obs", "captures",
            "stickers", "emojis", "emoji", "thumbnails", "thumbs", "icons",
        )

        /** 截屏工具默认命名前缀（即使直接保存在 Pictures 根目录也不计入「照片」） */
        private val SCREENSHOT_NAME_PREFIXES = listOf(
            "screenshot", "屏幕截图", "截屏", "截图",
            "snipaste_", "pixpin_", "sharex_", "qq截图", "微信截图", "bandicam ",
        )

        /** 代码仓库或解压版软件目录的特征文件（子目录命中时整体跳过，避免把项目内的 .ts 源码或 UI 贴图当成媒体） */
        private val PROJECT_MARKER_NAMES = setOf(
            "package.json", "tsconfig.json", "cargo.toml", "pom.xml",
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "pyproject.toml", "requirements.txt", "go.mod", "cmakelists.txt",
            "makefile", "composer.json", ".git", ".svn",
        )

        private val SOFTWARE_BINARY_EXTS = setOf("dll", "asar", "pak", "pyd", "pdb", "vcxproj", "sln")

        @Synchronized
        internal fun registerFileId(file: File): Long {
            val key = file.absolutePath
            fileToId[key]?.let { return it }
            val id = nextId++
            fileToId[key] = id
            idToFile[id] = file
            return id
        }

        internal fun fileForId(id: Long): File? = idToFile[id]

        internal fun relativePathFromHome(file: File): String {
            val parent = file.parentFile ?: return ""
            val homePath = Environment.getExternalStorageDirectory().absolutePath.replace('\\', '/').trimEnd('/')
            val parentPath = parent.absolutePath.replace('\\', '/').trimEnd('/')
            val rel = if (parentPath.startsWith("$homePath/", ignoreCase = true)) {
                parentPath.substring(homePath.length + 1)
            } else {
                parent.name
            }
            return if (rel.isEmpty()) "" else "$rel/"
        }

        internal fun resolveFile(uri: Uri): File? {
            val str = uri.toString()
            if (str.isBlank()) return null
            if (str.startsWith("content://media/")) {
                val id = uri.lastPathSegment?.toLongOrNull()
                if (id != null) return fileForId(id)
            }
            val p = uri.path
            if (!p.isNullOrBlank()) {
                val f = File(p)
                if (f.exists() || uri.scheme == "file" || uri.scheme == null) return f
            }
            val direct = File(str)
            if (direct.exists()) return direct
            return null
        }

        private fun scanMediaFiles(exts: Set<String>, dirNames: List<String>, kind: MediaScanKind): List<File> {
            val home = Environment.getExternalStorageDirectory()
            val result = mutableListOf<File>()
            val visited = HashSet<String>()
            for (dirName in dirNames) {
                val root = File(home, dirName)
                if (!root.isDirectory) continue
                scanDirRecursive(root, exts, kind, result, visited, depthFromRoot = 0, maxDepth = 4)
            }
            return result.sortedByDescending { it.lastModified() }
        }

        private fun shouldSkipSubdir(dir: File, kind: MediaScanKind): Boolean {
            val name = dir.name
            if (name.isEmpty() || name.startsWith(".") || name.startsWith("$") || dir.isHidden) return true
            val lower = name.lowercase()
            if (lower in IGNORED_SCAN_DIRS) return true
            if (lower.endsWith("_files") || lower.endsWith(".files")) return true
            if (kind == MediaScanKind.IMAGE && lower in IGNORED_IMAGE_DIRS) return true
            return false
        }

        private fun hasProjectOrSoftwareMarkers(children: Array<File>): Boolean {
            for (c in children) {
                val lower = c.name.lowercase()
                if (lower in PROJECT_MARKER_NAMES) return true
                if (c.isFile && lower.substringAfterLast('.', "") in SOFTWARE_BINARY_EXTS) return true
            }
            return false
        }

        /**
         * 校验 .ts / .mts / .m2ts 是否为真正的 MPEG-2 Transport Stream 视频流（而非 TypeScript 源码文本）：
         * 必须包含连续 3 个包间隔（188 / 192 / 204 字节）的 0x47 同步字，且头部含二进制控制字节。
         */
        internal fun isMpegTransportStream(file: File): Boolean {
            if (!file.isFile || file.length() < 4096L) return false
            val lower = file.name.lowercase()
            if (lower.endsWith(".d.ts") || lower.endsWith(".d.mts") || lower.endsWith(".d.cts")) return false
            return runCatching {
                val buf = ByteArray(1152)
                val n = FileInputStream(file).use { it.read(buf) }
                if (n < 564) return@runCatching false
                var hasBinary = false
                for (i in 0 until n) {
                    val b = buf[i].toInt() and 0xFF
                    if (b == 0 || b < 9) {
                        hasBinary = true
                        break
                    }
                }
                if (!hasBinary) return@runCatching false
                for (stride in intArrayOf(188, 192, 204)) {
                    val maxOff = minOf(stride, n - stride * 2)
                    for (off in 0 until maxOff) {
                        if (buf[off] == 0x47.toByte() &&
                            buf[off + stride] == 0x47.toByte() &&
                            buf[off + stride * 2] == 0x47.toByte()
                        ) {
                            return@runCatching true
                        }
                    }
                }
                false
            }.getOrDefault(false)
        }

        private fun isValidMediaFile(file: File, ext: String, kind: MediaScanKind): Boolean {
            val lowerName = file.name.lowercase()
            if (lowerName.endsWith(".d.ts") || lowerName.endsWith(".d.mts") || lowerName.endsWith(".d.cts")) return false
            if (kind == MediaScanKind.IMAGE && SCREENSHOT_NAME_PREFIXES.any { lowerName.startsWith(it) }) return false
            val len = file.length()
            val minBytes = when (kind) {
                MediaScanKind.VIDEO -> 4 * 1024L
                MediaScanKind.AUDIO -> 4 * 1024L
                MediaScanKind.IMAGE -> 8 * 1024L
            }
            if (len < minBytes) return false
            if (kind == MediaScanKind.VIDEO && (ext == "ts" || ext == "mts" || ext == "m2ts")) {
                return isMpegTransportStream(file)
            }
            return true
        }

        private fun scanDirRecursive(
            dir: File,
            exts: Set<String>,
            kind: MediaScanKind,
            out: MutableList<File>,
            visited: MutableSet<String>,
            depthFromRoot: Int,
            maxDepth: Int,
        ) {
            if (depthFromRoot > maxDepth || out.size >= 2000) return
            val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
            if (!visited.add(canonical)) return
            val children = dir.listFiles() ?: return
            if (depthFromRoot >= 1 && hasProjectOrSoftwareMarkers(children)) return
            for (f in children) {
                if (f.isDirectory) {
                    if (!shouldSkipSubdir(f, kind)) {
                        scanDirRecursive(f, exts, kind, out, visited, depthFromRoot + 1, maxDepth)
                    }
                } else if (f.isFile && !f.name.startsWith(".") && !f.isHidden) {
                    val ext = f.extension.lowercase()
                    if (ext in exts && isValidMediaFile(f, ext, kind)) {
                        out.add(f)
                    }
                }
            }
        }

        fun guessMimeType(name: String, file: File? = null): String? {
            val lower = name.lowercase()
            if (lower.endsWith(".d.ts") || lower.endsWith(".d.mts") || lower.endsWith(".d.cts")) return "text/plain"
            return when (lower.substringAfterLast('.', "")) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                "mov" -> "video/quicktime"
                "ts", "m2ts", "mts" -> if (file != null && file.isFile && !isMpegTransportStream(file)) "text/plain" else "video/mp2t"
                "flv" -> "video/x-flv"
                "wmv" -> "video/x-ms-wmv"
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                "m4a", "aac" -> "audio/mp4"
                "ogg", "opus" -> "audio/ogg"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "avif" -> "image/avif"
                "pdf" -> "application/pdf"
                "epub" -> "application/epub+zip"
                "cbz", "zip" -> "application/zip"
                "srt" -> "application/x-subrip"
                "ass", "ssa" -> "text/x-ssa"
                "vtt" -> "text/vtt"
                else -> runCatching { Files.probeContentType(File(name).toPath()) }.getOrNull()
            }
        }
    }
}

interface SharedPreferences {
    val all: Map<String, *>
    fun getString(key: String, defValue: String?): String?
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)
    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)

    fun interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?)
    }

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }
}

fun SharedPreferences.getAll(): Map<String, *> = all

internal class FileSharedPreferences(private val file: File) : SharedPreferences {
    private val map = ConcurrentHashMap<String, Any>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    init {
        loadFromDisk()
    }

    @Synchronized
    private fun loadFromDisk() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            for (k in root.keys()) {
                val entry = root.optJSONObject(k)
                if (entry != null && entry.has("t") && entry.has("v")) {
                    val v: Any? = when (entry.optString("t")) {
                        "s" -> entry.optString("v")
                        "i" -> entry.optInt("v")
                        "l" -> entry.optLong("v")
                        "f" -> entry.optDouble("v", 0.0).toFloat()
                        "b" -> entry.optBoolean("v")
                        "set" -> {
                            val arr = entry.optJSONArray("v") ?: JSONArray()
                            (0 until arr.length()).map { arr.optString(it) }.toSet()
                        }
                        else -> null
                    }
                    if (v != null) map[k] = v
                } else {
                    when (val raw = root.opt(k)) {
                        is String, is Int, is Long, is Boolean -> map[k] = raw
                        is Double -> map[k] = raw.toFloat()
                        is JSONArray -> map[k] = (0 until raw.length()).map { raw.optString(it) }.toSet()
                    }
                }
            }
        }
    }

    @Synchronized
    private fun saveToDisk() {
        runCatching {
            file.parentFile?.mkdirs()
            val root = JSONObject()
            for ((k, v) in map) {
                val entry = JSONObject()
                when (v) {
                    is String -> { entry.put("t", "s"); entry.put("v", v) }
                    is Int -> { entry.put("t", "i"); entry.put("v", v) }
                    is Long -> { entry.put("t", "l"); entry.put("v", v) }
                    is Float -> { entry.put("t", "f"); entry.put("v", v.toDouble()) }
                    is Double -> { entry.put("t", "f"); entry.put("v", v) }
                    is Boolean -> { entry.put("t", "b"); entry.put("v", v) }
                    is Set<*> -> {
                        entry.put("t", "set")
                        val arr = JSONArray()
                        v.forEach { arr.put(it?.toString().orEmpty()) }
                        entry.put("v", arr)
                    }
                }
                if (entry.has("t")) root.put(k, entry)
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(root.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(2), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    override val all: Map<String, *> get() = HashMap(map)
    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = (map[key] as? Set<String>) ?: defValues
    override fun getInt(key: String, defValue: Int): Int = (map[key] as? Number)?.toInt() ?: defValue
    override fun getLong(key: String, defValue: Long): Long = (map[key] as? Number)?.toLong() ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = (map[key] as? Number)?.toFloat() ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { pending[key] = null }
        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            val changedKeys = mutableListOf<String>()
            synchronized(this@FileSharedPreferences) {
                if (clearAll) {
                    changedKeys.addAll(map.keys)
                    map.clear()
                }
                for ((k, v) in pending) {
                    if (v == null) {
                        if (map.remove(k) != null) changedKeys.add(k)
                    } else {
                        val old = map.put(k, v)
                        if (old != v) changedKeys.add(k)
                    }
                }
                saveToDisk()
            }
            for (k in changedKeys) {
                for (l in listeners) {
                    runCatching { l.onSharedPreferenceChanged(this@FileSharedPreferences, k) }
                }
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}

class ComponentName(val packageName: String, val className: String) {
    constructor(context: Context, cls: Class<*>) : this(context.packageName, cls.name)
}

class Intent(
    var action: String? = null,
    var data: Uri? = null,
) {
    constructor(context: Context, cls: Class<*>) : this(action = cls.name)

    var type: String? = null
    var flags: Int = 0
    private val extras = HashMap<String, Any?>()

    fun setData(uri: Uri?): Intent = apply { data = uri }
    fun setType(t: String?): Intent = apply { type = t }
    fun setDataAndType(uri: Uri?, t: String?): Intent = apply { data = uri; type = t }
    fun setPackage(pkg: String?): Intent = this
    fun addFlags(f: Int): Intent = apply { flags = flags or f }
    fun setFlags(f: Int): Intent = apply { flags = f }
    fun putExtra(name: String, value: Any?): Intent = apply { extras[name] = value }
    fun getStringExtra(name: String): String? = extras[name] as? String
    fun getIntExtra(name: String, defaultValue: Int): Int = (extras[name] as? Number)?.toInt() ?: defaultValue
    fun getBooleanExtra(name: String, defaultValue: Boolean): Boolean = extras[name] as? Boolean ?: defaultValue
    fun <T> getParcelableExtra(name: String): T? = @Suppress("UNCHECKED_CAST") (extras[name] as? T)
    fun setAction(action: String): Intent = apply { this.action = action }
    fun putParcelableArrayListExtra(name: String, value: java.util.ArrayList<out Any>?): Intent = apply { extras[name] = value }
    fun resolveActivity(pm: PackageManager): ComponentName? = ComponentName(BuildConfig.APPLICATION_ID, "Desktop")

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
        const val EXTRA_STREAM = "android.intent.extra.STREAM"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val FLAG_ACTIVITY_SINGLE_TOP = 0x20000000
        const val FLAG_ACTIVITY_CLEAR_TASK = 0x00008000
        const val FLAG_GRANT_READ_URI_PERMISSION = 1
        const val FLAG_GRANT_WRITE_URI_PERMISSION = 2

        @JvmStatic
        fun createChooser(target: Intent, title: CharSequence?): Intent = target
    }
}

class IntentFilter(vararg actions: String) {
    fun addAction(action: String) {}
}

abstract class BroadcastReceiver {
    abstract fun onReceive(context: Context, intent: Intent)
}

interface ServiceConnection {
    fun onServiceConnected(name: ComponentName, service: IBinder)
    fun onServiceDisconnected(name: ComponentName)
}

class ClipData private constructor(val label: CharSequence?, val text: CharSequence?) {
    companion object {
        @JvmStatic
        fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData = ClipData(label, text)
    }
}

class ActivityNotFoundException(msg: String = "") : Exception(msg)

class ClipboardManager {
    fun setPrimaryClip(clip: ClipData) {
        val str = clip.text?.toString() ?: return
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(str), null)
        }
    }
}

open class Context {
    open val packageName: String get() = BuildConfig.APPLICATION_ID
    open val applicationContext: Context get() = GlobalDesktopApp
    open val applicationInfo: ApplicationInfo = ApplicationInfo()
    open val packageManager: PackageManager = PackageManager()
    open val resources: Resources = Resources()
    open val assets: AssetManager get() = resources.assets
    open val contentResolver: ContentResolver by lazy { ContentResolver(this) }
    open val mainExecutor: java.util.concurrent.Executor = java.util.concurrent.Executor { r -> r.run() }

    open val filesDir: File
        get() = File(appDataRoot(), "files").apply { mkdirs() }

    open val cacheDir: File
        get() = File(appDataRoot(), "cache").apply { mkdirs() }

    open val externalCacheDir: File?
        get() = cacheDir

    open fun getExternalFilesDir(type: String?): File? {
        val base = File(appDataRoot(), "external")
        return (if (type != null) File(base, type) else base).apply { mkdirs() }
    }

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        return prefsCache.getOrPut(name) {
            val prefsDir = File(appDataRoot(), "prefs").apply { mkdirs() }
            FileSharedPreferences(File(prefsDir, "$name.json"))
        }
    }

    @Suppress("UNCHECKED_CAST")
    open fun <T> getSystemService(serviceClass: Class<T>): T {
        return when (serviceClass) {
            NotificationManager::class.java -> notificationManager as T
            DownloadManager::class.java -> downloadManager as T
            ClipboardManager::class.java -> clipboardManager as T
            ConnectivityManager::class.java -> connectivityManager as T
            NsdManager::class.java -> nsdManager as T
            AudioManager::class.java -> audioManager as T
            UiModeManager::class.java -> uiModeManager as T
            MediaProjectionManager::class.java -> mediaProjectionManager as T
            else -> throw IllegalArgumentException("Unsupported service: $serviceClass")
        }
    }

    open fun getSystemService(name: String): Any {
        return when (name) {
            AUDIO_SERVICE -> audioManager
            CLIPBOARD_SERVICE -> clipboardManager
            NOTIFICATION_SERVICE -> notificationManager
            DOWNLOAD_SERVICE -> downloadManager
            CONNECTIVITY_SERVICE -> connectivityManager
            else -> audioManager
        }
    }

    open fun startActivity(intent: Intent) {
        if (intent.action == DownloadManager.ACTION_VIEW_DOWNLOADS) {
            val home = Environment.getExternalStorageDirectory()
            val voxDownloads = File(home, "Downloads/Voxtitle")
            val dir = if (voxDownloads.isDirectory) voxDownloads else File(home, "Downloads").apply { mkdirs() }
            runCatching {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(dir)
                } else {
                    ProcessBuilder("explorer.exe", dir.absolutePath).start()
                }
            }
            return
        }
        val uri = intent.data ?: intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (uri != null) {
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                runCatching {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(URI(uri.toString()))
                    } else {
                        ProcessBuilder("cmd", "/c", "start", "", uri.toString()).start()
                    }
                }
                return
            }
            val file = ContentResolver.resolveFile(uri)
            if (file != null && file.exists()) {
                runCatching {
                    if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
                        ProcessBuilder("explorer.exe", "/select,${file.absolutePath}").start()
                    } else if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(file)
                    }
                }
            }
        } else if (!text.isNullOrBlank()) {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("share", text))
        }
    }

    open fun startService(service: Intent): ComponentName? = null
    open fun stopService(name: Intent): Boolean = true
    open fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int): Int = PackageManager.PERMISSION_GRANTED
    open fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? = null
    open fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? = null
    open fun unregisterReceiver(receiver: BroadcastReceiver?) {}

    companion object {
        const val MODE_PRIVATE = 0
        const val BIND_AUTO_CREATE = 1
        const val AUDIO_SERVICE = "audio"
        const val CLIPBOARD_SERVICE = "clipboard"
        const val NOTIFICATION_SERVICE = "notification"
        const val DOWNLOAD_SERVICE = "download"
        const val CONNECTIVITY_SERVICE = "connectivity"
        const val RECEIVER_NOT_EXPORTED = 4

        private val prefsCache = ConcurrentHashMap<String, SharedPreferences>()
        private val notificationManager = NotificationManager()
        private val downloadManager = DownloadManager()
        private val clipboardManager = ClipboardManager()
        private val connectivityManager = ConnectivityManager()
        private val nsdManager = NsdManager()
        private val audioManager = AudioManager()
        private val uiModeManager = UiModeManager()
        private val mediaProjectionManager = MediaProjectionManager()

        @Volatile
        var customAppDataRoot: File? = null

        fun appDataRoot(): File {
            customAppDataRoot?.let { return it }
            val appData = System.getenv("APPDATA")
            val root = if (!appData.isNullOrBlank()) {
                File(appData, "Voxtitle")
            } else {
                File(System.getProperty("user.home", "."), ".voxtitle")
            }
            if (!root.exists()) root.mkdirs()
            return root
        }
    }
}

object GlobalDesktopApp : Application()

object DesktopContextHolder {
    @Volatile
    var appContext: Context = GlobalDesktopApp
}

