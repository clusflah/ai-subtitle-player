package androidx.media3.session

import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.media3.common.Player
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Future

class SessionToken(context: Context, serviceComponent: ComponentName)

class MediaSession(var player: Player) {
    class ControllerInfo

    fun release() {}

    class Builder(private val context: Context, private val player: Player) {
        fun setId(id: String): Builder = this
        fun setSessionActivity(pendingIntent: PendingIntent): Builder = this
        fun build(): MediaSession = MediaSession(player)
    }
}

abstract class MediaSessionService : Service() {
    abstract fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession?
    open fun onTaskRemoved(rootIntent: Intent?) {}
    override fun onBind(intent: Intent?): IBinder? = null
}

class MediaController {
    class Builder(context: Context, token: SessionToken) {
        @Suppress("UNCHECKED_CAST")
        fun buildAsync(): ListenableFuture<MediaController> =
            Futures.immediateVoidFuture() as ListenableFuture<MediaController>
    }

    companion object {
        @JvmStatic
        fun releaseFuture(controllerFuture: Future<MediaController>) {}
    }
}
