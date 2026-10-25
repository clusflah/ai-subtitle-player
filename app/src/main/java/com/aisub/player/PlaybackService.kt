package com.aisub.player

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 后台播放用的媒体会话服务。会话和播放器由 [MainViewModel] 创建，这里只负责把它交给系统：
 * 播放时 Media3 自动把服务转到前台并显示通知栏控制，锁屏、耳机和蓝牙按键也能控制播放。
 */
class PlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        session?.let(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 从最近任务里划掉应用：播放器跟着界面一起释放，服务也结束。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.pause()
        stopSelf()
    }

    companion object {
        /** 当前的媒体会话；ViewModel 创建、释放 */
        @Volatile
        var session: MediaSession? = null
    }
}
