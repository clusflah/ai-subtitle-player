package android.media.projection

import android.content.Intent
import android.os.Handler

class MediaProjection {
    abstract class Callback {
        open fun onStop() {}
    }

    fun registerCallback(callback: Callback, handler: Handler?) {}
    fun stop() {}
}

class MediaProjectionManager {
    fun createScreenCaptureIntent(): Intent = Intent("android.media.projection.CAPTURE")
    fun getMediaProjection(resultCode: Int, resultData: Intent): MediaProjection? = MediaProjection()
}
