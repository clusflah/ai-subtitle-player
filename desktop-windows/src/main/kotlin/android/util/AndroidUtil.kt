package android.util

import org.xmlpull.v1.StaxXmlPullParser
import org.xmlpull.v1.XmlPullParser
import java.util.LinkedHashMap

object Log {
    @JvmStatic
    fun d(tag: String?, msg: String): Int {
        println("[D/$tag] $msg")
        return 0
    }

    @JvmStatic
    fun d(tag: String?, msg: String, tr: Throwable?): Int {
        println("[D/$tag] $msg")
        tr?.printStackTrace()
        return 0
    }

    @JvmStatic
    fun i(tag: String?, msg: String): Int {
        println("[I/$tag] $msg")
        return 0
    }

    @JvmStatic
    fun i(tag: String?, msg: String, tr: Throwable?): Int {
        println("[I/$tag] $msg")
        tr?.printStackTrace()
        return 0
    }

    @JvmStatic
    fun w(tag: String?, msg: String): Int {
        println("[W/$tag] $msg")
        return 0
    }

    @JvmStatic
    fun w(tag: String?, msg: String, tr: Throwable?): Int {
        println("[W/$tag] $msg: ${tr?.message}")
        return 0
    }

    @JvmStatic
    fun w(tag: String?, tr: Throwable?): Int {
        println("[W/$tag] ${tr?.message}")
        return 0
    }

    @JvmStatic
    fun e(tag: String?, msg: String): Int {
        System.err.println("[E/$tag] $msg")
        return 0
    }

    @JvmStatic
    fun e(tag: String?, msg: String, tr: Throwable?): Int {
        System.err.println("[E/$tag] $msg: ${tr?.message}")
        tr?.printStackTrace()
        return 0
    }
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
