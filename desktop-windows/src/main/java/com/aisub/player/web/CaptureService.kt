package com.aisub.player.web

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.aisub.player.desktop.WasapiLoopbackCapture

/**
 * Windows 桌面版系统声音捕获服务（基于 WASAPI Loopback）：
 * 读取系统正在播放的声音，交给 [WebCaptions] 边播边识别。
 */
class CaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startWasapi()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopWasapi()
        super.onDestroy()
    }

    companion object {
        private var listener: ((FloatArray) -> Unit)? = null

        private fun startWasapi() {
            if (listener != null) return
            val cb: (FloatArray) -> Unit = { samples ->
                WebCaptions.current?.onCaptured(samples)
            }
            listener = cb
            try {
                WasapiLoopbackCapture.addListener(cb)
                WebCaptions.current?.onCaptureStarted()
            } catch (e: Exception) {
                listener = null
                WebCaptions.current?.onCaptureStopped("读取系统声音失败：${e.message}")
            }
        }

        private fun stopWasapi() {
            listener?.let { WasapiLoopbackCapture.removeListener(it) }
            listener = null
            WebCaptions.current?.onCaptureStopped()
        }

        /** 用户点击「读取系统声音」后直接启动 WASAPI Loopback 采集 */
        fun start(context: Context, resultCode: Int, data: Intent) {
            startWasapi()
        }
    }
}
