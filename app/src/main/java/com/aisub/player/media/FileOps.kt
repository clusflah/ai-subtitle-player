package com.aisub.player.media

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 本地媒体文件管理（通过 MediaStore，适配 Android 10+ 分区存储）。
 * 修改别的应用创建的文件时，系统要求用户确认：这类方法返回一个 [PendingIntent]，
 * 界面层用它弹出系统确认框，用户同意后再调用一次即可。
 */
object FileOps {

    /** 需要用户在系统弹窗里确认后才能继续。 */
    class NeedsPermission(val intent: PendingIntent) : Exception("需要授权")

    data class Details(
        val name: String,
        val folder: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val mime: String,
        val dateAdded: Long,
    )

    fun isLocal(entry: MediaEntry) = entry.uri.startsWith("content://media/")

    /** 删除。Android 11+ 一次性弹出系统确认框（会移到回收站或直接删除，由系统决定）。 */
    fun delete(context: Context, entries: List<MediaEntry>) {
        val uris = entries.filter(::isLocal).map { Uri.parse(it.uri) }
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            throw NeedsPermission(MediaStore.createDeleteRequest(context.contentResolver, uris))
        }
        for (uri in uris) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                    throw NeedsPermission(e.userAction.actionIntent)
                }
                throw e
            }
        }
    }

    /** Android 11+ 修改前先申请写权限（重命名、移动）。已经授权过时返回 null。 */
    fun writeRequest(context: Context, entries: List<MediaEntry>): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uris = entries.filter(::isLocal).map { Uri.parse(it.uri) }
        if (uris.isEmpty()) return null
        val needs = uris.any {
            context.checkUriPermission(it, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return if (needs) MediaStore.createWriteRequest(context.contentResolver, uris) else null
    }

    /** 重命名（保留原扩展名，用户只改主文件名）。 */
    fun rename(context: Context, entry: MediaEntry, newBaseName: String) {
        val ext = entry.title.substringAfterLast('.', "")
        val name = newBaseName.trim().let { if (ext.isNotEmpty() && !it.endsWith(".$ext", ignoreCase = true)) "$it.$ext" else it }
        require(name.isNotBlank() && '/' !in name) { "文件名不合法" }
        update(context, entry, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }) {
            File(it.parentFile, name)
        }
    }

    /** 移动到另一个目录（相对存储根目录，例如 "Movies/动画"）。 */
    fun move(context: Context, entry: MediaEntry, relativeDir: String) {
        val dir = relativeDir.trim('/').ifEmpty { throw IllegalArgumentException("目标目录为空") }
        update(context, entry, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/") }) {
            File(Environment.getExternalStorageDirectory(), "$dir/${it.name}").also { f -> f.parentFile?.mkdirs() }
        }
    }

    /**
     * Android 10+ 直接更新 MediaStore；Android 8/9 没有 RELATIVE_PATH，改为重命名实际文件后再通知媒体库。
     */
    @Suppress("DEPRECATION")
    private fun update(context: Context, entry: MediaEntry, values: ContentValues, legacyTarget: (File) -> File) {
        val uri = Uri.parse(entry.uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val n = context.contentResolver.update(uri, values, null, null)
                if (n == 0) throw IllegalStateException("系统拒绝了修改")
            } catch (e: SecurityException) {
                if (e is RecoverableSecurityException) throw NeedsPermission(e.userAction.actionIntent)
                throw e
            }
            return
        }
        val path = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: throw IllegalStateException("找不到文件路径")
        val src = File(path)
        val dst = legacyTarget(src)
        if (dst.exists()) throw IllegalStateException("目标位置已有同名文件")
        if (!src.renameTo(dst)) throw IllegalStateException("文件移动失败（可能没有存储写入权限）")
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, dst.absolutePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, dst.name)
        }, null, null)
    }

    fun shareIntent(entries: List<MediaEntry>): Intent {
        val uris = ArrayList(entries.map { Uri.parse(it.uri) })
        val type = when {
            entries.all { it.isVideo } -> "video/*"
            entries.none { it.isVideo } -> "audio/*"
            else -> "*/*"
        }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = type
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "分享").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun details(context: Context, entry: MediaEntry): Details {
        val uri = Uri.parse(entry.uri)
        val cols = arrayOf(
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
        )
        var size = 0L
        var mime = ""
        var added = 0L
        var w = 0
        var h = 0
        runCatching {
            context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    size = c.getLong(0); mime = c.getString(1).orEmpty(); added = c.getLong(2) * 1000
                    w = c.getInt(3); h = c.getInt(4)
                }
            }
        }
        return Details(entry.title, entry.folder, size, entry.durationMs, w, h, mime, added)
    }

    /** 可以移动到的目录：系统规定视频只能放在 Movies/DCIM/Pictures 下，音频只能放在 Music 等目录下。 */
    fun moveTargets(entries: List<MediaEntry>, existingFolders: List<String>): List<String> {
        val roots = if (entries.all { it.isVideo }) listOf("Movies", "DCIM", "Pictures")
        else if (entries.none { it.isVideo }) listOf("Music", "Podcasts", "Audiobooks", "Recordings")
        else listOf("Movies")
        val existing = existingFolders.filter { f -> roots.any { f == it || f.startsWith("$it/") } }
        return (roots + existing).distinct().sorted()
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
