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
 * 在本地视频旁边写一个旁挂 .srt（双语），别的播放器打开视频时会自动加载。
 * 绝不破坏/覆盖用户原有的同名字幕：如果同目录已存在并非本应用写入的原 .srt，则改写为 `<片名>.voxtitle.srt`。
 */
object SidecarSrt {
    private const val MIME = "application/x-subrip"
    private const val PREF_NAME = "sidecar_srt"
    private const val KEY_PATHS = "paths"
    private const val KEY_OWNED = "owned_targets"

    private fun ownedKey(dir: String, name: String): String = "$dir/$name"

    /** 写好返回实际写入的文件名；网络视频、不知道所在目录时返回 null；写不了抛异常。 */
    fun write(context: Context, entry: MediaEntry, cues: List<Cue>): String? {
        if (cues.isEmpty() || !FileOps.isLocal(entry) || entry.folder.isBlank()) return null
        val stem = entry.title.substringBeforeLast('.')
        val primaryName = "$stem.srt"
        val fallbackName = "$stem.voxtitle.srt"
        val dir = entry.folder.trim('/')
        val bytes = java.io.ByteArrayOutputStream().also { writeSrt(cues, SrtMode.BILINGUAL, it) }.toByteArray()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeMediaStore(context, Uri.parse(entry.uri), dir, primaryName, fallbackName, bytes)
        } else {
            writeFile(context, dir, primaryName, fallbackName, bytes)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun writeMediaStore(
        context: Context,
        video: Uri,
        dir: String,
        primaryName: String,
        fallbackName: String,
        bytes: ByteArray,
    ): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.getVolumeName(video))
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val owned = prefs.getStringSet(KEY_OWNED, emptySet()).orEmpty()

        fun queryByName(name: String): Uri? = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("$dir/", name),
            null,
        )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(collection, c.getLong(0)) else null }

        fun insertNew(name: String): Boolean {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("系统不允许在 $dir 里新建字幕文件")
            return try {
                // 系统在同目录已有别的应用/用户的同名字幕时，会自动改名成「xx (1).srt」
                val actual = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                if (actual != name) {
                    runCatching { resolver.delete(uri, null, null) }
                    false
                } else {
                    resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    true
                }
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }

        val primaryKey = ownedKey(dir, primaryName)
        val existingPrimary = queryByName(primaryName)
        if (existingPrimary != null) {
            if (primaryKey in owned) {
                resolver.openOutputStream(existingPrimary, "wt")!!.use { it.write(bytes) }
                return primaryName
            }
        } else {
            if (insertNew(primaryName)) {
                prefs.edit().putStringSet(KEY_OWNED, owned + primaryKey).apply()
                return primaryName
            }
        }

        // 原同名字幕并非本应用创建：绝不覆盖原文件，改用独立 .voxtitle.srt 文件保存
        val fallbackKey = ownedKey(dir, fallbackName)
        val existingFallback = queryByName(fallbackName)
        if (existingFallback != null) {
            resolver.openOutputStream(existingFallback, "wt")!!.use { it.write(bytes) }
        } else if (!insertNew(fallbackName)) {
            throw IllegalStateException("视频旁边已存在 $primaryName 与 $fallbackName，为保护原有字幕未覆盖")
        }
        prefs.edit().putStringSet(KEY_OWNED, owned + fallbackKey).apply()
        return fallbackName
    }

    /** Android 8/9：直接写文件；记下自己写过的路径，旁有别人的同名 .srt 时改写 .voxtitle.srt。 */
    @Suppress("DEPRECATION")
    internal fun writeFile(
        context: Context,
        dir: String,
        primaryName: String,
        fallbackName: String,
        bytes: ByteArray,
    ): String {
        val parent = File(Environment.getExternalStorageDirectory(), dir)
        val primaryFile = File(parent, primaryName)
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val mine = prefs.getStringSet(KEY_PATHS, emptySet()).orEmpty()
        val owned = prefs.getStringSet(KEY_OWNED, emptySet()).orEmpty()
        val primaryKey = ownedKey(dir, primaryName)

        if (!primaryFile.exists() || primaryFile.path in mine || primaryKey in owned) {
            primaryFile.writeBytes(bytes)
            prefs.edit()
                .putStringSet(KEY_PATHS, mine + primaryFile.path)
                .putStringSet(KEY_OWNED, owned + primaryKey)
                .apply()
            return primaryName
        }

        val fallbackFile = File(parent, fallbackName)
        val fallbackKey = ownedKey(dir, fallbackName)
        fallbackFile.writeBytes(bytes)
        prefs.edit()
            .putStringSet(KEY_PATHS, mine + fallbackFile.path)
            .putStringSet(KEY_OWNED, owned + fallbackKey)
            .apply()
        return fallbackName
    }
}
