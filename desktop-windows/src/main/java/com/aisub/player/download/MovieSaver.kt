package com.aisub.player.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * 把下载好的视频放进手机的 Movies/Voxtitle：媒体库里能看到，卸载应用也不会丢。
 * Android 10 起通过 MediaStore 写入；更老的系统直接复制到公共目录，失败时留在应用目录。
 */
object MovieSaver {

    const val FOLDER = "Voxtitle"

    /** 返回可以播放的地址；成功后删除临时文件。阻塞调用。 */
    fun save(context: Context, file: File, title: String, onProgress: (Float) -> Unit): Uri {
        val name = safeName(title) + "." + file.extension.ifEmpty { "mp4" }
        val mime = when (file.extension.lowercase()) {
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            else -> "video/mp4"
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (mime.startsWith("audio/")) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val dir = if (mime.startsWith("audio/")) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: throw IOException("无法写入相册")
            try {
                resolver.openOutputStream(uri)!!.use { out -> copy(file, out, onProgress) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw IOException("保存到相册失败：${e.message}", e)
            }
            file.delete()
            uri
        } else {
            // Android 8、9：有存储权限时复制到公共目录，否则留在应用目录也能播放
            runCatching {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER).apply { mkdirs() }
                val target = uniqueFile(dir, name)
                target.outputStream().use { out -> copy(file, out, onProgress) }
                file.delete()
                Uri.fromFile(target)
            }.getOrElse { Uri.fromFile(file) }
        }
    }

    private fun copy(file: File, out: java.io.OutputStream, onProgress: (Float) -> Unit) {
        val total = file.length().coerceAtLeast(1)
        var done = 0L
        val buf = ByteArray(1 shl 20)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                onProgress(done.toFloat() / total)
            }
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 2
        while (f.exists()) f = File(dir, name.substringBeforeLast('.') + " ($i)." + name.substringAfterLast('.')).also { i++ }
        return f
    }

    /** 文件名里不能有路径分隔符等特殊字符，太长的截断 */
    fun safeName(title: String) = title.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim().take(120).ifEmpty { "video" }
}
