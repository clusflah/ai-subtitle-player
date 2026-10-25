package com.aisub.player.subtitle

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.aisub.player.media.FileOps
import com.aisub.player.media.MediaEntry
import java.io.File

/**
 * 在本地视频旁边写一个同名的 .srt（双语），别的播放器打开视频时会自动加载。
 * 只覆盖本应用写过的文件，旁边已有别人的同名字幕时不动它。
 */
object SidecarSrt {
    private const val MIME = "application/x-subrip"

    /** 写好返回文件名；网络视频、不知道所在目录时返回 null；写不了抛异常。 */
    fun write(context: Context, entry: MediaEntry, cues: List<Cue>): String? {
        if (cues.isEmpty() || !FileOps.isLocal(entry) || entry.folder.isBlank()) return null
        val name = entry.title.substringBeforeLast('.') + ".srt"
        val dir = entry.folder.trim('/')
        val bytes = java.io.ByteArrayOutputStream().also { writeSrt(cues, SrtMode.BILINGUAL, it) }.toByteArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) writeMediaStore(context, Uri.parse(entry.uri), dir, name, bytes)
        else writeFile(context, dir, name, bytes)
        return name
    }

    private fun writeMediaStore(context: Context, video: Uri, dir: String, name: String, bytes: ByteArray) {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.getVolumeName(video))
        // 查得到的同名文件只有本应用自己写的（别的应用的非媒体文件查不到），直接覆盖
        val existing = resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf("$dir/", name), null,
        )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(collection, c.getLong(0)) else null }
        if (existing != null) {
            resolver.openOutputStream(existing, "wt")!!.use { it.write(bytes) }
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("系统不允许在 $dir 里新建字幕文件")
        try {
            // 旁边已有别人的同名字幕时，系统会自动改名成「xx (1).srt」，这种不留
            val actual = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (actual != name) throw IllegalStateException("视频旁边已经有同名字幕 $name，没有覆盖")
            resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** Android 8/9：直接写文件；记下自己写过的路径，只覆盖这些。 */
    @Suppress("DEPRECATION")
    private fun writeFile(context: Context, dir: String, name: String, bytes: ByteArray) {
        val file = File(File(Environment.getExternalStorageDirectory(), dir), name)
        val prefs = context.getSharedPreferences("sidecar_srt", Context.MODE_PRIVATE)
        val mine = prefs.getStringSet("paths", emptySet())!!
        if (file.exists() && file.path !in mine) throw IllegalStateException("视频旁边已经有同名字幕 $name，没有覆盖")
        file.writeBytes(bytes)
        prefs.edit().putStringSet("paths", mine + file.path).apply()
    }
}
