package com.aisub.player.asr.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ChunkedCloudEngineTest {

    /** 每段识别耗时不同（前面的段更慢），结果仍然要按原来的顺序交出；同时在途的段数不超过并发数。 */
    private class FakeEngine : ChunkedCloudEngine(firstChunkSec = 1, chunkSec = 1, parallel = 3) {
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)

        override fun recognize(wav: ByteArray, chunkStartMs: Long, chunkEndMs: Long): List<Recognized> {
            peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            try {
                Thread.sleep(if (chunkStartMs == 0L) 150 else 20)
                return listOf(Recognized.Sentence("段$chunkStartMs", chunkStartMs, chunkEndMs))
            } finally {
                running.decrementAndGet()
            }
        }
    }

    @Test
    fun emitsInOrderAndRespectsParallelLimit() {
        val engine = FakeEngine()
        val texts = ArrayList<String>()
        val starts = ArrayList<Long>()
        val onCue: (com.aisub.player.subtitle.Cue) -> Unit = { texts.add(it.text); starts.add(it.startMs) }
        // 6 秒音频，每秒一段
        repeat(6) { engine.accept(FloatArray(16_000) { 0.1f }, onCue) }
        engine.finish(onCue)
        engine.close()
        assertTrue("段数 ${texts.size}", texts.size >= 5)
        assertEquals(0L, starts.first())
        assertEquals(starts.distinct().sorted(), starts)
        assertEquals(starts.sorted(), starts)
        assertTrue("并发数 ${engine.peak.get()}", engine.peak.get() in 2..3)
    }

    @Test
    fun useParallelOneRunsChunksOneAtATime() {
        val engine = FakeEngine().apply { useParallel(1) }
        val texts = ArrayList<String>()
        repeat(4) { engine.accept(FloatArray(16_000) { 0.1f }) { texts.add(it.text) } }
        engine.finish { texts.add(it.text) }
        engine.close()
        assertTrue("段数 ${texts.size}", texts.size >= 3)
        assertEquals(1, engine.peak.get())
    }
}
