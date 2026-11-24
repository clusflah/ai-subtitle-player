package com.voxmusic.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 后台播放媒体会话服务：播放时自动转到前台显示通知栏控制，支持锁屏与耳机按键控制。
 * 应用未在前台时从桌面小组件点播放，由 [WidgetPlayback] 在此处托管后台播放器。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        session?.let(::addSession)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_WIDGET_PLAY) {
            showBriefForeground()
            if (!WidgetPlayback.start(this)) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        session?.takeIf { !isSessionAdded(it) }?.let(::addSession)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching { onBeforeTaskRemoved?.invoke() }
        session?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        WidgetPlayback.release()
        super.onDestroy()
    }

    private fun showBriefForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "播放", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("VoxMusic")
            .setContentText("正在恢复播放…")
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type) }
    }

    companion object {
        const val ACTION_WIDGET_PLAY = "com.voxmusic.app.WIDGET_PLAY"
        private const val CHANNEL = "voxmusic_playback_start"
        private const val NOTIFICATION_ID = 3

        @Volatile
        var session: MediaSession? = null

        @Volatile
        var onBeforeTaskRemoved: (() -> Unit)? = null
    }
}
