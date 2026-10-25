package com.aisub.player

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 识别、翻译这类长任务在通知栏显示进度，离开播放页也看得到；完成后提示一下，点通知回到播放页。
 * 没有通知权限（Android 13 起要用户允许）时什么都不做。
 */
class TaskNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var lastUpdate = 0L
    private var running = false

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "字幕任务", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun allowed() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openPlayer() = PendingIntent.getActivity(
        context, 1, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 任务进行中：title 是视频名，status 是当前步骤，progress 0..1（null 或负数为不确定） */
    fun progress(title: String, status: String, progress: Float?) {
        running = true
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 1000 || !allowed()) return
        lastUpdate = now
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(status)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(openPlayer())
            .apply {
                if (progress == null || progress < 0) setProgress(0, 0, true) else setProgress(100, (progress * 100).toInt(), false)
            }.build()
        runCatching { manager.notify(ID, n) }
    }

    /** 任务结束：message 为 null 表示取消或失败，只收起通知 */
    fun done(title: String, message: String?) {
        if (!running) return
        running = false
        lastUpdate = 0
        manager.cancel(ID)
        if (message == null || !allowed()) return
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(message)
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(openPlayer())
            .build()
        runCatching { manager.notify(ID, n) }
    }

    companion object {
        private const val CHANNEL = "tasks"
        private const val ID = 2001
    }
}
