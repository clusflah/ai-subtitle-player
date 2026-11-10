package com.aisub.player.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aisub.player.media.MediaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object ThumbCache {
    private val cache = LruCache<String, ImageBitmap>(120)

    fun forget(uri: String) {
        cache.remove(uri)
    }

    @Suppress("DEPRECATION")
    fun load(context: Context, uriString: String): ImageBitmap? {
        cache.get(uriString)?.let { return it }
        val uri = Uri.parse(uriString)
        // 网络、网盘、媒体服务器的视频：用播放时截下的画面（见 HistoryThumbs）
        if (uri.scheme != "content") {
            val file = HistoryThumbs.file(context, uriString).takeIf { it.exists() } ?: return null
            return android.graphics.BitmapFactory.decodeFile(file.path)?.asImageBitmap()?.also { cache.put(uriString, it) }
        }
        val bitmap: Bitmap? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(320, 180), null)
            } else {
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver, ContentUris.parseId(uri), MediaStore.Video.Thumbnails.MINI_KIND, null,
                )
            }
        } catch (e: Exception) {
            null
        }
        return bitmap?.asImageBitmap()?.also { cache.put(uriString, it) }
    }
}

/**
 * 网络视频的缩略图：媒体库没有，播放时截一帧存在应用目录里，播放历史、继续观看用它。
 * 文件名是地址的哈希。
 */
object HistoryThumbs {
    fun file(context: Context, uri: String): java.io.File {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return java.io.File(java.io.File(context.filesDir, "thumbs").apply { mkdirs() }, "$name.jpg")
    }

    /** 媒体库里的本地视频系统自带缩略图，不用另存 */
    fun needed(uri: String) = !uri.startsWith("content://")

    /** 存了新图就加一，界面据此重新加载 */
    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun save(context: Context, uri: String, bmp: Bitmap) {
        val f = file(context, uri)
        val tmp = java.io.File(f.path + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        tmp.renameTo(f)
        ThumbCache.forget(uri)
        android.os.Handler(android.os.Looper.getMainLooper()).post { version++ }
    }
}

/** 视频缩略图；没有缩略图（音频、还没播过的网络视频）时显示类型图标。 */
@Composable
fun MediaThumbnail(entry: MediaEntry, modifier: Modifier = Modifier) {
    // 直播频道显示台标
    if (entry.isLive) {
        NetImage(entry.logo.ifEmpty { null }, modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), contentScale = ContentScale.Fit, fallback = AppIcons.Live)
        return
    }
    val context = LocalContext.current
    val thumbVersion = if (HistoryThumbs.needed(entry.uri)) HistoryThumbs.version else 0
    val image by produceState<ImageBitmap?>(null, entry.uri, thumbVersion) {
        value = withContext(Dispatchers.IO) {
            if (entry.isVideo) ThumbCache.load(context, entry.uri)
            else com.aisub.player.music.MusicFeatures.thumbnail(context, entry.uri)?.asImageBitmap()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                if (entry.isVideo) AppIcons.Movie else AppIcons.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}
