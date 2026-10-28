package com.aisub.player

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
 * 后台播放用的媒体会话服务。会话和播放器由 [MainViewModel] 创建，这里只负责把它交给系统：
 * 播放时 Media3 自动把服务转到前台并显示通知栏控制，锁屏、耳机和蓝牙按键也能控制播放。
 * 应用没开着时桌面小组件点播放，由 [WidgetPlayback] 在这里建一个后台播放器。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        session?.let(::addSession)
    }

    /** 服务还在、会话换成了新界面建的（旧界面刚退出又马上打开）：把新会话也交给系统 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_WIDGET_PLAY && !WidgetPlayback.start(this)) {
            // 用 startForegroundService 启动的，必须先转到前台，否则系统会让应用闪退；没有能放的歌就马上结束
            showBriefForeground()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        session?.takeIf { !isSessionAdded(it) }?.let(::addSession)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 从最近任务里划掉应用：播放器跟着界面一起释放，服务也结束。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
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
            .setContentTitle("没有最近听过的歌")
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type) }
    }

    companion object {
        /** 桌面小组件点播放，应用没开着：后台接着放最近听的歌 */
        const val ACTION_WIDGET_PLAY = "com.aisub.player.WIDGET_PLAY"
        private const val CHANNEL = "playback_start"
        private const val NOTIFICATION_ID = 3

        /** 当前的媒体会话；ViewModel 或 [WidgetPlayback] 创建、释放 */
        @Volatile
        var session: MediaSession? = null
    }
}
