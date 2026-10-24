package com.aisub.player.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/** 视频里的一个章节。endMs 未知时为 null（到下一章开始为止）。 */
data class Chapter(val startMs: Long, val endMs: Long?, val title: String)

/**
 * 读取 MKV / WebM 文件里的章节（ExoPlayer 不解析章节）。
 * 只读文件头部和章节所在的那一小段，不用把整个文件读完，网络文件也很快。
 */
object MkvChapters {
    /** 从文件 pos 处读 len 字节；文件末尾不足时返回较短的数组。 */
    fun interface RangeReader {
        fun read(pos: Long, len: Int): ByteArray
    }

    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val CLUSTER = 0x1F43B675L
    private const val CHAPTERS = 0x1043A770L
    private const val EDITION = 0x45B9L
    private const val EDITION_HIDDEN = 0x45BDL
    private const val EDITION_DEFAULT = 0x45DBL
    private const val ATOM = 0xB6L
    private const val TIME_START = 0x91L
    private const val TIME_END = 0x92L
    private const val HIDDEN = 0x98L
    private const val DISPLAY = 0x80L
    private const val STRING = 0x85L

    private const val MAX_CHAPTERS_BYTES = 4 shl 20

    fun isMatroska(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("mkv", "mka", "webm", "mk3d")

    @UnstableApi
    fun read(dataSourceFactory: DataSource.Factory, uri: Uri): List<Chapter> {
        val ds = dataSourceFactory.createDataSource()
        return read { pos, len ->
            try {
                ds.open(DataSpec.Builder().setUri(uri).setPosition(pos).setLength(len.toLong()).build())
                val out = java.io.ByteArrayOutputStream(len)
                val buf = ByteArray(minOf(len, 64 * 1024).coerceAtLeast(1))
                while (out.size() < len) {
                    val n = ds.read(buf, 0, minOf(buf.size, len - out.size()))
                    if (n == C.RESULT_END_OF_INPUT) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } finally {
                runCatching { ds.close() }
            }
        }
    }

    fun read(reader: RangeReader): List<Chapter> {
        val ebml = header(reader, 0) ?: return emptyList()
        if (ebml.id != EBML || ebml.size < 0) return emptyList()
        val segment = header(reader, ebml.dataStart + ebml.size) ?: return emptyList()
        if (segment.id != SEGMENT) return emptyList()
        val segmentStart = segment.dataStart
        val segmentEnd = if (segment.size < 0) Long.MAX_VALUE else segmentStart + segment.size

        // 依次看 Segment 下的顶层元素，碰到第一个 Cluster（音视频数据）就停；
        // 章节可能就在前面，也可能在文件末尾，由 SeekHead 指出位置
        var chaptersAt: Long? = null
        var pos = segmentStart
        var guard = 0
        while (pos < segmentEnd && guard++ < 64) {
            val e = header(reader, pos) ?: break
            when (e.id) {
                CHAPTERS -> return readChapters(reader, e)
                SEEK_HEAD -> if (e.size in 1..MAX_CHAPTERS_BYTES) {
                    seekTarget(reader.read(e.dataStart, e.size.toInt()), CHAPTERS)?.let { chaptersAt = segmentStart + it }
                }
                CLUSTER -> break
            }
            if (e.size < 0) break
            pos = e.dataStart + e.size
        }
        val at = chaptersAt ?: return emptyList()
        val e = header(reader, at) ?: return emptyList()
        return if (e.id == CHAPTERS) readChapters(reader, e) else emptyList()
    }

    private fun readChapters(reader: RangeReader, e: Header): List<Chapter> {
        if (e.size !in 1..MAX_CHAPTERS_BYTES) return emptyList()
        return parseChapters(reader.read(e.dataStart, e.size.toInt()))
    }

    /** SeekHead 里找到某个元素相对 Segment 数据起点的位置。 */
    private fun seekTarget(data: ByteArray, target: Long): Long? {
        for (seek in children(data, 0, data.size)) {
            if (seek.id != SEEK) continue
            var id: Long? = null
            var position: Long? = null
            for (c in children(data, seek.start, seek.end)) {
                when (c.id) {
                    SEEK_ID -> id = uint(data, c.start, c.end)
                    SEEK_POSITION -> position = uint(data, c.start, c.end)
                }
            }
            if (id == target && position != null) return position
        }
        return null
    }

    internal fun parseChapters(data: ByteArray): List<Chapter> {
        val editions = children(data, 0, data.size).filter { it.id == EDITION }
        // 选默认版本；没有标默认就选第一个不隐藏的
        val edition = editions.firstOrNull { ed ->
            children(data, ed.start, ed.end).any { it.id == EDITION_DEFAULT && uint(data, it.start, it.end) == 1L }
        } ?: editions.firstOrNull { ed ->
            children(data, ed.start, ed.end).none { it.id == EDITION_HIDDEN && uint(data, it.start, it.end) == 1L }
        } ?: editions.firstOrNull() ?: return emptyList()

        val out = mutableListOf<Chapter>()
        fun atom(a: Element) {
            var start = 0L
            var end: Long? = null
            var hidden = false
            var title: String? = null
            for (c in children(data, a.start, a.end)) {
                when (c.id) {
                    TIME_START -> start = uint(data, c.start, c.end) / 1_000_000
                    TIME_END -> end = uint(data, c.start, c.end) / 1_000_000
                    HIDDEN -> hidden = uint(data, c.start, c.end) == 1L
                    DISPLAY -> if (title == null) {
                        children(data, c.start, c.end).firstOrNull { it.id == STRING }?.let {
                            title = String(data, it.start, it.end - it.start, Charsets.UTF_8).trimEnd('\u0000').trim()
                        }
                    }
                    ATOM -> atom(c) // 子章节
                }
            }
            if (!hidden) out += Chapter(start, end, title.orEmpty())
        }
        children(data, edition.start, edition.end).filter { it.id == ATOM }.forEach(::atom)
        return out.sortedBy { it.startMs }.distinctBy { it.startMs }
    }

    // ---------------- EBML 基础 ----------------

    private class Header(val id: Long, val size: Long, val dataStart: Long)

    /** 元素内容在数组里的范围 [start, end) */
    private class Element(val id: Long, val start: Int, val end: Int)

    private fun header(reader: RangeReader, pos: Long): Header? {
        val b = reader.read(pos, 12)
        val (id, idLen) = vint(b, 0, keepMarker = true) ?: return null
        val (size, sizeLen) = vint(b, idLen, keepMarker = false) ?: return null
        return Header(id, size, pos + idLen + sizeLen)
    }

    private fun children(data: ByteArray, from: Int, to: Int): List<Element> {
        val out = mutableListOf<Element>()
        var p = from
        while (p < to) {
            val (id, idLen) = vint(data, p, keepMarker = true) ?: break
            val (size, sizeLen) = vint(data, p + idLen, keepMarker = false) ?: break
            val start = p + idLen + sizeLen
            if (size < 0 || start + size > to) break
            out += Element(id, start, (start + size).toInt())
            p = (start + size).toInt()
        }
        return out
    }

    /**
     * EBML 变长整数。返回 (值, 字节数)；元素 ID 保留长度标记位，大小去掉标记位、全 1 表示未知（返回 -1）。
     */
    private fun vint(b: ByteArray, pos: Int, keepMarker: Boolean): Pair<Long, Int>? {
        if (pos >= b.size) return null
        val first = b[pos].toInt() and 0xFF
        if (first == 0) return null
        val len = Integer.numberOfLeadingZeros(first) - 23
        if (len > (if (keepMarker) 4 else 8) || pos + len > b.size) return null
        var value = (if (keepMarker) first else first and (0xFF shr len)).toLong()
        var allOnes = value == (0xFF shr len).toLong()
        for (i in 1 until len) {
            val v = b[pos + i].toInt() and 0xFF
            if (v != 0xFF) allOnes = false
            value = (value shl 8) or v.toLong()
        }
        if (!keepMarker && allOnes) return -1L to len
        return value to len
    }

    private fun uint(b: ByteArray, from: Int, to: Int): Long {
        var v = 0L
        for (i in from until minOf(to, from + 8)) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }
}
