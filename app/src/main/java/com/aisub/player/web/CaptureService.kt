package com.aisub.player.web

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.aisub.player.asr.Transcriber

/**
 * 网页里读不到声音的视频（有版权保护等）：用系统的播放捕获（Android 10 起）读取译幕自己放出来的声音，
 * 交给 [WebCaptions] 边播边识别。只捕获译幕自己的声音，不录麦克风、不录别的 App；声音只用来识别，不保存。
 *
 * 系统要求先显示前台通知、用户在系统对话框里同意后才能捕获。
 */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || intent == null || running) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        try {
            start(code, data ?: error("没有授权数据"))
            WebCaptions.current?.onCaptureStarted()
        } catch (e: Exception) {
            android.util.Log.w("Voxtitle", "读取系统声音失败", e)
            WebCaptions.current?.onCaptureStopped("读取系统声音失败：${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // 启动前界面已经申请了录音权限
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun start(code: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(code, data) ?: error("系统没有给出授权")
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))
        // 只要译幕自己放出来的声音（网页的声音由 App 进程播放）
        val config = AudioPlaybackCaptureConfiguration.Builder(mp).addMatchingUid(applicationInfo.uid).build()
        val rate = Transcriber.SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(min, rate * 2))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("系统不允许捕获")
        }
        record = rec
        rec.startRecording()
        running = true
        thread = Thread({
            val buf = ShortArray(rate / 10) // 100 毫秒
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                WebCaptions.current?.onCaptured(FloatArray(n) { buf[it] / 32768f })
            }
        }, "web-capture").apply { start() }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "网页字幕", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("正在读取网页视频的声音")
            .setContentText("用来生成 AI 字幕，关闭 AI 字幕后停止")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        thread?.join(500)
        record?.run {
            runCatching { stop() }
            release()
        }
        record = null
        projection?.stop()
        projection = null
        WebCaptions.current?.onCaptureStopped()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "web_capture"
        private const val NOTIFICATION_ID = 7302
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        /** 用户在系统对话框里同意后启动 */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
