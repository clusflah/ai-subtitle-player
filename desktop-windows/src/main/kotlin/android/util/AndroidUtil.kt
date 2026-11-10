package android.util

import org.xmlpull.v1.StaxXmlPullParser
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.concurrent.LinkedBlockingQueue

object Log {
    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val logQueue = LinkedBlockingQueue<String>(4096)

    val logFile: File by lazy {
        val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home", "."), "AppData/Local")
        val dir = File(base, "Voxtitle/logs").apply { mkdirs() }
        File(dir, "voxtitle.log")
    }

    init {
        Thread({
            var writer: PrintWriter? = null
            var bytesWritten = runCatching { if (logFile.exists()) logFile.length() else 0L }.getOrDefault(0L)
            val maxBytes = 4L * 1024L * 1024L
            while (true) {
                try {
                    val line = logQueue.take()
                    if (writer == null || bytesWritten > maxBytes) {
                        runCatching { writer?.close() }
                        if (bytesWritten > maxBytes && logFile.exists()) {
                            val backup = File(logFile.parentFile, "voxtitle.log.1")
                            runCatching {
                                if (backup.exists()) backup.delete()
                                logFile.renameTo(backup)
                            }
                            bytesWritten = 0L
                        }
                        writer = runCatching {
                            PrintWriter(FileOutputStream(logFile, true).bufferedWriter(Charsets.UTF_8), false)
                        }.getOrNull()
                    }
                    writer?.println(line)
                    bytesWritten += line.length + 2
                    if (logQueue.isEmpty()) {
                        writer?.flush()
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                }
            }
            runCatching { writer?.flush(); writer?.close() }
        }, "Voxtitle-LogWriter").apply {
            isDaemon = true
            start()
        }
    }

    private fun record(level: Char, tag: String?, msg: String, tr: Throwable? = null, toErr: Boolean = false): Int {
        val now = try { timeFmt.format(LocalDateTime.now()) } catch (_: Throwable) { "" }
        val tName = Thread.currentThread().name
        val fullMsg = if (tr != null) {
            val sw = StringWriter()
            tr.printStackTrace(PrintWriter(sw))
            "$msg\n$sw".trimEnd()
        } else {
            msg
        }
        val formatted = "$now [$level/${tag ?: "App"}] ($tName) $fullMsg"
        if (toErr) {
            System.err.println(formatted)
        } else {
            println(formatted)
        }
        logQueue.offer(formatted)
        return 0
    }

    @JvmStatic
    fun d(tag: String?, msg: String): Int = record('D', tag, msg)

    @JvmStatic
    fun d(tag: String?, msg: String, tr: Throwable?): Int = record('D', tag, msg, tr)

    @JvmStatic
    fun i(tag: String?, msg: String): Int = record('I', tag, msg)

    @JvmStatic
    fun i(tag: String?, msg: String, tr: Throwable?): Int = record('I', tag, msg, tr)

    @JvmStatic
    fun w(tag: String?, msg: String): Int = record('W', tag, msg)

    @JvmStatic
    fun w(tag: String?, msg: String, tr: Throwable?): Int = record('W', tag, "$msg: ${tr?.message}", tr)

    @JvmStatic
    fun w(tag: String?, tr: Throwable?): Int = record('W', tag, tr?.message ?: "", tr)

    @JvmStatic
    fun e(tag: String?, msg: String): Int = record('E', tag, msg, toErr = true)

    @JvmStatic
    fun e(tag: String?, msg: String, tr: Throwable?): Int = record('E', tag, "$msg: ${tr?.message}", tr, toErr = true)
}

object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        return String(encode(input, flags), Charsets.ISO_8859_1)
    }

    @JvmStatic
    fun encodeToString(input: ByteArray, offset: Int, len: Int, flags: Int): String {
        return encodeToString(input.copyOfRange(offset, offset + len), flags)
    }

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray {
        val urlSafe = (flags and URL_SAFE) != 0
        val noPadding = (flags and NO_PADDING) != 0
        val noWrap = (flags and NO_WRAP) != 0
        var enc = when {
            urlSafe && noWrap -> java.util.Base64.getUrlEncoder()
            urlSafe -> java.util.Base64.getUrlEncoder()
            noWrap -> java.util.Base64.getEncoder()
            else -> java.util.Base64.getMimeEncoder(76, "\n".toByteArray())
        }
        if (noPadding) enc = enc.withoutPadding()
        val raw = enc.encode(input)
        return if (!noWrap && !urlSafe) raw + "\n".toByteArray() else raw
    }

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray {
        return decode(str.toByteArray(Charsets.ISO_8859_1), flags)
    }

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray {
        val cleaned = String(input, Charsets.ISO_8859_1).trim()
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")
        if (cleaned.isEmpty()) return ByteArray(0)
        val isUrl = (flags and URL_SAFE) != 0 || cleaned.contains('-') || cleaned.contains('_')
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        return if (isUrl) {
            java.util.Base64.getUrlDecoder().decode(padded.replace('+', '-').replace('/', '_'))
        } else {
            java.util.Base64.getDecoder().decode(padded.replace('-', '+').replace('_', '/'))
        }
    }
}

open class LruCache<K : Any, V : Any>(private var maxSize: Int) {
    private val map = LinkedHashMap<K, V>(0, 0.75f, true)
    private var currentSize = 0

    protected open fun sizeOf(key: K, value: V): Int = 1
    protected open fun entryRemoved(evicted: Boolean, key: K, oldValue: V, newValue: V?) {}
    protected open fun create(key: K): V? = null

    fun get(key: K): V? {
        var mapValue: V?
        synchronized(this) {
            mapValue = map[key]
            if (mapValue != null) return mapValue
        }
        val createdValue = create(key) ?: return null
        synchronized(this) {
            mapValue = map.put(key, createdValue)
            if (mapValue != null) {
                map[key] = mapValue!!
            } else {
                currentSize += safeSizeOf(key, createdValue)
            }
        }
        if (mapValue != null) {
            entryRemoved(false, key, createdValue, mapValue)
            return mapValue
        }
        trimToSize(maxSize)
        return createdValue
    }

    fun put(key: K, value: V): V? {
        val previous: V?
        synchronized(this) {
            currentSize += safeSizeOf(key, value)
            previous = map.put(key, value)
            if (previous != null) {
                currentSize -= safeSizeOf(key, previous)
            }
        }
        if (previous != null) {
            entryRemoved(false, key, previous, value)
        }
        trimToSize(maxSize)
        return previous
    }

    open fun trimToSize(maxSize: Int) {
        while (true) {
            val key: K
            val value: V
            synchronized(this) {
                if (currentSize <= maxSize || map.isEmpty()) break
                val toEvict = map.entries.firstOrNull() ?: break
                key = toEvict.key
                value = toEvict.value
                map.remove(key)
                currentSize -= safeSizeOf(key, value)
            }
            entryRemoved(true, key, value, null)
        }
    }

    fun remove(key: K): V? {
        val previous: V?
        synchronized(this) {
            previous = map.remove(key)
            if (previous != null) {
                currentSize -= safeSizeOf(key, previous)
            }
        }
        if (previous != null) {
            entryRemoved(false, key, previous, null)
        }
        return previous
    }

    fun evictAll() {
        trimToSize(-1)
    }

    @Synchronized
    fun size(): Int = currentSize

    @Synchronized
    fun maxSize(): Int = maxSize

    @Synchronized
    fun snapshot(): Map<K, V> = LinkedHashMap(map)

    private fun safeSizeOf(key: K, value: V): Int = sizeOf(key, value).coerceAtLeast(0)
}

data class Rational(val numerator: Int, val denominator: Int) {
    fun toFloat(): Float = if (denominator == 0) 0f else numerator.toFloat() / denominator.toFloat()
}

data class Size(val width: Int, val height: Int)

object Xml {
    @JvmStatic
    fun newPullParser(): XmlPullParser = StaxXmlPullParser()
}

class DisplayMetrics {
    var density: Float = 1.0f
    var densityDpi: Int = 160
    var scaledDensity: Float = 1.0f
    var widthPixels: Int = 1920
    var heightPixels: Int = 1080

    companion object {
        const val DENSITY_DEFAULT = 160
    }
}
