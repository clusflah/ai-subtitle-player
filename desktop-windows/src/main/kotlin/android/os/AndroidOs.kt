package android.os

import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

object Build {
    val MODEL: String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Windows PC")
    const val MANUFACTURER: String = "Microsoft"
    const val BRAND: String = "Windows"
    const val DEVICE: String = "Desktop"
    val SUPPORTED_ABIS: Array<String> = arrayOf("x86_64", "arm64-v8a")

    object VERSION {
        const val SDK_INT: Int = 34
        val RELEASE: String = System.getProperty("os.version") ?: "11"
    }

    object VERSION_CODES {
        const val M = 23
        const val N = 24
        const val O = 26
        const val O_MR1 = 27
        const val P = 28
        const val Q = 29
        const val R = 30
        const val S = 31
        const val S_V2 = 32
        const val TIRAMISU = 33
        const val UPSIDE_DOWN_CAKE = 34
    }
}

object SystemClock {
    private val startNano = System.nanoTime()

    @JvmStatic
    fun elapsedRealtime(): Long = (System.nanoTime() - startNano) / 1_000_000L

    @JvmStatic
    fun uptimeMillis(): Long = elapsedRealtime()

    @JvmStatic
    fun sleep(ms: Long) {
        if (ms > 0) runCatching { Thread.sleep(ms) }
    }
}

object Environment {
    const val DIRECTORY_DOWNLOADS = "Downloads"
    const val DIRECTORY_MOVIES = "Videos"
    const val DIRECTORY_MUSIC = "Music"
    const val DIRECTORY_PICTURES = "Pictures"
    const val DIRECTORY_DOCUMENTS = "Documents"

    @JvmStatic
    fun getExternalStorageDirectory(): File {
        return File(System.getProperty("user.home") ?: ".")
    }

    @JvmStatic
    fun getExternalStoragePublicDirectory(type: String): File {
        val home = getExternalStorageDirectory()
        val folder = when (type) {
            "Movies" -> File(home, "Videos")
            else -> File(home, type)
        }
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    @JvmStatic
    fun isExternalStorageManager(): Boolean = true
}

class Looper internal constructor(val isMain: Boolean) {
    val thread: Thread = Thread.currentThread()
    fun quit() {}
    fun quitSafely() {}

    companion object {
        private val MAIN = Looper(true)
        private val threadLocal = ThreadLocal<Looper>()

        @JvmStatic
        fun getMainLooper(): Looper = MAIN

        @JvmStatic
        fun myLooper(): Looper? = threadLocal.get() ?: MAIN

        @JvmStatic
        fun prepare() {
            if (threadLocal.get() == null) threadLocal.set(Looper(false))
        }

        @JvmStatic
        fun loop() {}
    }
}

open class Handler(val looper: Looper = Looper.getMainLooper()) {
    private val tasks = ConcurrentHashMap<Runnable, ScheduledFuture<*>>()

    fun post(r: Runnable): Boolean {
        if (looper.isMain) {
            if (SwingUtilities.isEventDispatchThread()) {
                runCatching { r.run() }
            } else {
                SwingUtilities.invokeLater { runCatching { r.run() } }
            }
        } else {
            scheduler.execute { runCatching { r.run() } }
        }
        return true
    }

    fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        val future = scheduler.schedule({
            tasks.remove(r)
            post(r)
        }, delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        tasks[r] = future
        return true
    }

    fun removeCallbacks(r: Runnable) {
        tasks.remove(r)?.cancel(false)
    }

    fun removeCallbacksAndMessages(token: Any?) {
        if (token == null) {
            tasks.values.forEach { it.cancel(false) }
            tasks.clear()
        }
    }

    companion object {
        private val scheduler = Executors.newScheduledThreadPool(2) { r ->
            Thread(r, "Voxtitle-Handler").apply { isDaemon = true }
        }
    }
}

open class HandlerThread(name: String) : Thread(name) {
    val looper: Looper = Looper(false)
    override fun run() {}
    fun quit(): Boolean = true
    fun quitSafely(): Boolean = true
}

interface IBinder
open class Binder : IBinder

class Bundle {
    private val map = HashMap<String, Any?>()
    fun putString(key: String, value: String?) { map[key] = value }
    fun getString(key: String, defaultValue: String? = null): String? = map[key] as? String ?: defaultValue
    fun putInt(key: String, value: Int) { map[key] = value }
    fun getInt(key: String, defaultValue: Int = 0): Int = map[key] as? Int ?: defaultValue
    fun putLong(key: String, value: Long) { map[key] = value }
    fun getLong(key: String, defaultValue: Long = 0L): Long = map[key] as? Long ?: defaultValue
    fun putBoolean(key: String, value: Boolean) { map[key] = value }
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean = map[key] as? Boolean ?: defaultValue
}

class Parcel {
    fun writeInt(v: Int) {}
    fun readInt(): Int = 0
}

class ParcelFileDescriptor(val file: File) : Closeable {
    private val fis by lazy { java.io.FileInputStream(file) }
    val fileDescriptor: java.io.FileDescriptor get() = fis.fd
    val fd: Int = 0
    fun detachFd(): Int = 0
    override fun close() {
        runCatching { fis.close() }
    }
}

class Message {
    var obj: Any? = null
    fun sendToTarget() {}
}

object Process {
    @JvmStatic
    fun myPid(): Int = runCatching { java.lang.management.ManagementFactory.getRuntimeMXBean().name.substringBefore('@').toInt() }.getOrDefault(0)

    @JvmStatic
    fun myUid(): Int = 1000
}

/**
 * 拦截并加载桌面版 JNI 动态库（如 sherpa-onnx-jni.dll 与 onnxruntime.dll）。
 */
object LibLd {
    private val loaded = ConcurrentHashMap.newKeySet<String>()

    @JvmStatic
    @Synchronized
    fun loadLibrary(name: String) {
        if (!loaded.add(name)) return
        if (name == "sherpa-onnx-jni") {
            val dir = File(android.content.Context.appDataRoot(), "bin/sherpa-1.13.8").apply { mkdirs() }
            val onnxDll = extractResource("sherpa-onnx/native/win-x64/onnxruntime.dll", File(dir, "onnxruntime.dll"))
            val jniDll = extractResource("sherpa-onnx/native/win-x64/sherpa-onnx-jni.dll", File(dir, "sherpa-onnx-jni.dll"))
            if (onnxDll != null && jniDll != null) {
                System.load(onnxDll.absolutePath)
                System.load(jniDll.absolutePath)
                return
            }
        }
        System.loadLibrary(name)
    }

    private fun extractResource(resourcePath: String, target: File): File? {
        if (target.exists() && target.length() > 100_000L) return target
        val stream = LibLd::class.java.classLoader.getResourceAsStream(resourcePath) ?: return null
        val tmp = File(target.parentFile, "${target.name}.tmp")
        stream.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }
}


