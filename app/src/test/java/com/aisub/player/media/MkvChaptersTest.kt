package com.aisub.player.media

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MkvChaptersTest {

    // ---- 拼一个最小的 MKV：EBML 头 + Segment（SeekHead、Cluster、文件末尾的 Chapters） ----

    private fun id(v: Long): ByteArray {
        val len = when {
            v <= 0xFF -> 1
            v <= 0xFFFF -> 2
            v <= 0xFFFFFF -> 3
            else -> 4
        }
        return ByteArray(len) { i -> (v shr (8 * (len - 1 - i))).toByte() }
    }

    /** 统一用 8 字节的大小，方便算位置 */
    private fun size(n: Long) = ByteArray(8) { i -> if (i == 0) 0x01 else (n shr (8 * (7 - i))).toByte() }

    private fun el(idValue: Long, vararg payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { payload.forEach { write(it) } }.toByteArray()
        return id(idValue) + size(body.size.toLong()) + body
    }

    private fun uint(v: Long) = ByteArray(8) { i -> (v shr (8 * (7 - i))).toByte() }

    private fun atom(startMs: Long, title: String, hidden: Boolean = false) = el(
        0xB6,
        el(0x91, uint(startMs * 1_000_000)),
        el(0x98, uint(if (hidden) 1 else 0)),
        el(0x80, el(0x85, title.toByteArray()), el(0x437C, "eng".toByteArray())),
    )

    private fun sample(): ByteArray {
        val chapters = el(
            0x1043A770,
            el(0x45B9, atom(0, "Opening"), atom(90_000, "Part A"), atom(100_000, "secret", hidden = true), atom(1_300_000, "Ending")),
        )
        val cluster = el(0x1F43B675, ByteArray(5000) { 7 })
        // SeekHead 自身大小固定，先算出 Chapters 相对 Segment 数据起点的位置
        fun seekHead(pos: Long) = el(0x114D9B74, el(0x4DBB, el(0x53AB, id(0x1043A770)), el(0x53AC, uint(pos))))
        val headLen = seekHead(0).size
        val head = seekHead((headLen + cluster.size).toLong())
        val segmentBody = head + cluster + chapters
        return el(0x1A45DFA3, el(0x4282, "matroska".toByteArray())) + el(0x18538067, segmentBody)
    }

    @Test
    fun readsChaptersPointedToBySeekHead() {
        val data = sample()
        val chapters = MkvChapters.read { pos, len -> data.copyOfRange(pos.toInt().coerceAtMost(data.size), (pos + len).toInt().coerceAtMost(data.size)) }
        assertEquals(listOf("Opening", "Part A", "Ending"), chapters.map { it.title })
        assertEquals(listOf(0L, 90_000L, 1_300_000L), chapters.map { it.startMs })
    }

    @Test
    fun garbageGivesNoChapters() {
        assertTrue(MkvChapters.read { _, len -> ByteArray(len) { 0x55 } }.isEmpty())
    }

    @Test
    fun realFileWithoutChapters() {
        val file = File(javaClass.classLoader!!.getResource("embedded/test.mkv")!!.toURI())
        assertTrue(MkvChapters.read(FileDataSource.Factory(), Uri.fromFile(file)).isEmpty())
    }

    @Test
    fun matroskaNames() {
        assertTrue(MkvChapters.isMatroska("a.MKV"))
        assertTrue(!MkvChapters.isMatroska("a.mp4"))
    }
}
