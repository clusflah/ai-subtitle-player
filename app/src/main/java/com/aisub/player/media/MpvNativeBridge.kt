package com.aisub.player.media

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.annotation.Keep

/**
 * `libmpv_player_bridge.so` 的 Kotlin JNI 桥接层：
 * - 按拓扑依赖顺序预加载 `c++_shared`、FFmpeg 共享库与 `mpv`，确保 Android 6.0 (API 23) 老电视的 Bionic 动态链接器也能可靠解析符号；
 * - 管理独立的 `mpv_handle*` 实例句柄，避免全局单例竞争。
 */
@Keep
class MpvNativeBridge(
    private val listener: Listener,
) {
    interface Listener {
        fun onEvent(eventId: Int, reason: Int, error: Int)
        fun onPropertyLong(name: String, value: Long)
        fun onPropertyDouble(name: String, value: Double)
        fun onPropertyBoolean(name: String, value: Boolean)
        fun onPropertyString(name: String, value: String?)
    }

    private var handle: Long = 0L

    val isCreated: Boolean get() = handle != 0L

    fun create(): Boolean {
        if (!isAvailable()) return false
        if (handle != 0L) destroy()
        handle = runCatching { nativeCreate() }.getOrDefault(0L)
        return handle != 0L
    }

    fun initialize(): Boolean {
        val h = handle
        if (h == 0L) return false
        return runCatching { nativeInitialize(h) }.getOrDefault(false)
    }

    fun setOptionString(name: String, value: String): Int {
        val h = handle
        if (h == 0L) return -1
        return nativeSetOptionString(h, name, value)
    }

    fun setPropertyString(name: String, value: String): Int {
        val h = handle
        if (h == 0L) return -1
        return nativeSetPropertyString(h, name, value)
    }

    fun setPropertyDouble(name: String, value: Double) {
        val h = handle
        if (h != 0L) nativeSetPropertyDouble(h, name, value)
    }

    fun setPropertyLong(name: String, value: Long) {
        val h = handle
        if (h != 0L) nativeSetPropertyLong(h, name, value)
    }

    fun setPropertyBoolean(name: String, value: Boolean) {
        val h = handle
        if (h != 0L) nativeSetPropertyBoolean(h, name, value)
    }

    fun getPropertyString(name: String): String? {
        val h = handle
        if (h == 0L) return null
        return nativeGetPropertyString(h, name)
    }

    fun getPropertyLong(name: String, fallback: Long = -1L): Long {
        val h = handle
        if (h == 0L) return fallback
        return nativeGetPropertyLong(h, name, fallback)
    }

    fun getPropertyDouble(name: String, fallback: Double = Double.NaN): Double {
        val h = handle
        if (h == 0L) return fallback
        return nativeGetPropertyDouble(h, name, fallback)
    }

    fun command(vararg args: String) {
        val h = handle
        if (h == 0L || args.isEmpty()) return
        nativeCommand(h, arrayOf(*args))
    }

    /** 同步执行 mpv 命令（用于切换播放内核时立即停止解复用与解码管线，避免与新内核抢占资源） */
    fun commandSync(vararg args: String) {
        val h = handle
        if (h == 0L || args.isEmpty()) return
        nativeCommandSync(h, arrayOf(*args))
    }

    fun attachSurface(surface: Surface) {
        val h = handle
        if (h != 0L && surface.isValid) {
            nativeAttachSurface(h, surface)
        }
    }

    fun detachSurface() {
        val h = handle
        if (h != 0L) {
            nativeDetachSurface(h)
        }
    }

    fun destroy() {
        val h = handle
        handle = 0L
        if (h != 0L) {
            runCatching { nativeDestroy(h) }
        }
    }

    // ---------------- 以下回调由 C++ 事件线程通过 JNI 调用（签名请勿改动） ----------------

    @Keep
    private fun onNativeEvent(eventId: Int, reason: Int, error: Int) {
        listener.onEvent(eventId, reason, error)
    }

    @Keep
    private fun onNativePropertyLong(name: String, value: Long) {
        listener.onPropertyLong(name, value)
    }

    @Keep
    private fun onNativePropertyDouble(name: String, value: Double) {
        listener.onPropertyDouble(name, value)
    }

    @Keep
    private fun onNativePropertyBoolean(name: String, value: Boolean) {
        listener.onPropertyBoolean(name, value)
    }

    @Keep
    private fun onNativePropertyString(name: String, value: String?) {
        listener.onPropertyString(name, value)
    }

    private external fun nativeCreate(): Long
    private external fun nativeInitialize(handle: Long): Boolean
    private external fun nativeSetOptionString(handle: Long, name: String, value: String): Int
    private external fun nativeSetPropertyString(handle: Long, name: String, value: String): Int
    private external fun nativeSetPropertyDouble(handle: Long, name: String, value: Double)
    private external fun nativeSetPropertyLong(handle: Long, name: String, value: Long)
    private external fun nativeSetPropertyBoolean(handle: Long, name: String, value: Boolean)
    private external fun nativeGetPropertyString(handle: Long, name: String): String?
    private external fun nativeGetPropertyLong(handle: Long, name: String, fallback: Long): Long
    private external fun nativeGetPropertyDouble(handle: Long, name: String, fallback: Double): Double
    private external fun nativeCommand(handle: Long, args: Array<String>)
    private external fun nativeCommandSync(handle: Long, args: Array<String>)
    private external fun nativeAttachSurface(handle: Long, surface: Surface)
    private external fun nativeDetachSurface(handle: Long)
    private external fun nativeDestroy(handle: Long)

    companion object {
        private const val TAG = "Voxtitle-MpvBridge"

        const val EVENT_SHUTDOWN = 1
        const val EVENT_START_FILE = 6
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_VIDEO_RECONFIG = 17
        const val EVENT_AUDIO_RECONFIG = 18
        const val EVENT_SEEK = 20
        const val EVENT_PLAYBACK_RESTART = 21

        const val END_REASON_EOF = 0
        const val END_REASON_STOP = 2
        const val END_REASON_QUIT = 3
        const val END_REASON_ERROR = 4
        const val END_REASON_REDIRECT = 5

        @Volatile
        private var checked = false

        @Volatile
        private var available = false

        @Volatile
        var lastLoadError: String? = null
            private set

        fun bindAppContext(context: Context) {
            if (!isAvailable()) return
            runCatching { nativeSetAppContext(context.applicationContext) }
        }

        /** 探测当前设备是否成功加载 `libmpv.so` 与 `libmpv_player_bridge.so`。 */
        fun isAvailable(): Boolean {
            if (checked) return available
            synchronized(this) {
                if (checked) return available
                // 按依赖拓扑顺序加载，兼容 Android 6.0 Bionic 链接器无递归同目录搜索的行为
                val deps = listOf(
                    "c++_shared",
                    "avutil",
                    "swresample",
                    "swscale",
                    "postproc",
                    "avcodec",
                    "avformat",
                    "avfilter",
                    "avdevice",
                    "mpv",
                )
                var mpvDepError: Throwable? = null
                for (dep in deps) {
                    runCatching { System.loadLibrary(dep) }.onFailure { err ->
                        Log.w(TAG, "预加载 lib$dep.so 失败: ${err.message}", err)
                        if (dep == "mpv" || dep == "c++_shared") {
                            mpvDepError = err
                        }
                    }
                }
                available = runCatching {
                    System.loadLibrary("mpv_player_bridge")
                    val ok = nativeIsAvailable()
                    if (ok) {
                        lastLoadError = null
                    } else {
                        lastLoadError = mpvDepError?.message ?: "libmpv.so 未能完成初始化"
                    }
                    ok
                }.onFailure { err ->
                    lastLoadError = (mpvDepError?.message ?: err.message)?.take(180)
                    Log.w(TAG, "加载 MPV 原生引擎失败", err)
                }.getOrDefault(false)
                checked = true
                return available
            }
        }

        @JvmStatic
        private external fun nativeIsAvailable(): Boolean

        @JvmStatic
        private external fun nativeSetAppContext(appContext: Context)
    }
}
