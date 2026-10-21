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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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

    @Suppress("DEPRECATION")
    fun load(context: Context, uriString: String): ImageBitmap? {
        cache.get(uriString)?.let { return it }
        val uri = Uri.parse(uriString)
        if (uri.scheme != "content") return null
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

/** 视频缩略图；没有缩略图（远程文件、音频）时显示类型图标。 */
@Composable
fun MediaThumbnail(entry: MediaEntry, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, entry.uri) {
        value = if (entry.isVideo) withContext(Dispatchers.IO) { ThumbCache.load(context, entry.uri) } else null
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
