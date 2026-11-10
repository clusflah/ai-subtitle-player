package androidx.media3.common.util

import android.net.Uri
import androidx.media3.common.C

@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
@Retention(AnnotationRetention.BINARY)
annotation class UnstableApi

class ParsableByteArray(val data: ByteArray = ByteArray(0), var limit: Int = data.size) {
    var position: Int = 0

    fun readBytes(buffer: ByteArray, offset: Int, length: Int) {
        val n = length.coerceAtMost((limit - position).coerceAtLeast(0))
        System.arraycopy(data, position, buffer, offset, n)
        position += n
    }
}

object Util {
    @JvmStatic
    fun inferContentType(uri: Uri): Int {
        val path = uri.path?.lowercase() ?: uri.toString().lowercase()
        return when {
            path.endsWith(".m3u8") || path.contains(".m3u8?") -> C.CONTENT_TYPE_HLS
            path.endsWith(".mpd") || path.contains(".mpd?") -> C.CONTENT_TYPE_DASH
            else -> C.CONTENT_TYPE_OTHER
        }
    }
}
