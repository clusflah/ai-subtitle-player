package com.aisub.player.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns

/** 从系统媒体库（MediaStore）读取手机里的视频和音频。 */
object MediaLibrary {

    fun videos(context: Context): List<MediaEntry> =
        query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true)

    fun audios(context: Context): List<MediaEntry> =
        query(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, isVideo = false)

    @Suppress("DEPRECATION")
    private fun query(context: Context, collection: Uri, isVideo: Boolean): List<MediaEntry> {
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val pathColumn = if (useRelativePath) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.Video.VideoColumns.DURATION,
            pathColumn,
        )
        val storageRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
        val result = ArrayList<MediaEntry>()
        context.contentResolver.query(
            collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)
            val pathCol = c.getColumnIndexOrThrow(pathColumn)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                val rawPath = c.getString(pathCol).orEmpty()
                val folder = if (useRelativePath) rawPath.trim('/')
                else rawPath.removePrefix(storageRoot).substringBeforeLast('/', "")
                result.add(
                    MediaEntry(
                        uri = uri.toString(),
                        title = c.getString(nameCol) ?: "未命名",
                        isVideo = isVideo,
                        durationMs = c.getLong(durCol),
                        folder = folder,
                    )
                )
            }
        }
        return result
    }

    /** 外部打开的文件（文件管理器、选择器）取文件名作为标题。 */
    fun entryFor(context: Context, uri: Uri): MediaEntry {
        var name: String? = null
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) name = it.getString(0)
                }
            }
        }
        val title = name ?: Uri.decode(uri.lastPathSegment ?: uri.toString()).substringAfterLast('/')
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val isVideo = type?.startsWith("audio/") != true && !MediaEntry.isAudioName(title)
        return MediaEntry(uri.toString(), title, isVideo)
    }
}

/** 按目录浏览本地媒体：目录结构从媒体文件的路径推出来，不需要"所有文件访问"权限。 */
object LocalBrowser {

    data class Folder(val name: String, val path: String, val folderCount: Int, val fileCount: Int)

    /** 列出 path 下的子目录和直接位于该目录的媒体文件。path 为空表示存储根目录。 */
    fun children(all: List<MediaEntry>, path: String): Pair<List<Folder>, List<MediaEntry>> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val files = all.filter { it.folder == path }.sortedBy { it.title.lowercase() }
        val sub = all.filter { it.folder.startsWith(prefix) && it.folder != path }
            .groupBy { it.folder.removePrefix(prefix).substringBefore('/') }
        val folders = sub.map { (name, entries) ->
            val childPath = prefix + name
            val nested = entries.filter { it.folder.startsWith("$childPath/") }
                .map { it.folder.removePrefix("$childPath/").substringBefore('/') }
                .toSet()
            Folder(name, childPath, nested.size, entries.size)
        }.sortedBy { it.name.lowercase() }
        return folders to files
    }

    /** 某目录（含子目录）的统计信息，用于"收藏"卡片。 */
    fun summary(all: List<MediaEntry>, path: String): Folder {
        val (folders, files) = children(all, path)
        return Folder(path.substringAfterLast('/'), path, folders.size, files.size + folders.sumOf { it.fileCount })
    }
}
