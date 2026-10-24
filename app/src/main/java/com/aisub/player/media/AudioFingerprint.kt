package com.aisub.player.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * 简单的声音指纹（Haitsma–Kalker 方法）：每 64ms 一帧，用相邻频带能量差的变化得到 32 位。
 * 两集里同一段片头/片尾的指纹几乎一样，用来找出两段音频共有的最长片段。
 */
object AudioFingerprint {
    const val SAMPLE_RATE = 16_000
    private const val FRAME = 2048
    private const val HOP = 1024
    private const val BANDS = 33
    /** 一帧里不同位数不超过这个值算"相同" */
    private const val MAX_BIT_ERRORS = 9
    /** 相同帧之间允许的最大空隙（帧），约 1.5 秒 */
    private const val MAX_GAP = 24

    class Print(val bits: IntArray, val loud: BooleanArray) {
        val size get() = bits.size
    }

    /** 两段音频共有的片段，单位毫秒，分别是在 a、b 里的位置。 */
    data class Match(val aStartMs: Long, val aEndMs: Long, val bStartMs: Long, val bEndMs: Long) {
        val lengthMs get() = aEndMs - aStartMs
    }

    fun frameMs(frames: Int): Long = frames.toLong() * HOP * 1000 / SAMPLE_RATE

    private fun msToFrames(ms: Long): Int = (ms * SAMPLE_RATE / 1000 / HOP).toInt()

    fun compute(samples: FloatArray): Print {
        val count = if (samples.size < FRAME) 0 else (samples.size - FRAME) / HOP + 1
        val window = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1)) }
        // 300–2000Hz 按对数分 33 个频带
        val edges = IntArray(BANDS + 1) { i ->
            val f = exp(ln(300.0) + (ln(2000.0) - ln(300.0)) * i / BANDS)
            (f * FRAME / SAMPLE_RATE).toInt()
        }
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        var prev = DoubleArray(BANDS)
        val bits = IntArray(count)
        val loud = BooleanArray(count)
        val energies = DoubleArray(count)
        for (n in 0 until count) {
            val off = n * HOP
            for (i in 0 until FRAME) {
                re[i] = samples[off + i] * window[i]
                im[i] = 0.0
            }
            fft(re, im)
            val e = DoubleArray(BANDS)
            var total = 0.0
            for (b in 0 until BANDS) {
                var s = 0.0
                for (k in edges[b] until maxOf(edges[b + 1], edges[b] + 1)) s += re[k] * re[k] + im[k] * im[k]
                e[b] = s
                total += s
            }
            energies[n] = total
            var v = 0
            for (m in 0 until BANDS - 1) {
                if (e[m] - e[m + 1] - (prev[m] - prev[m + 1]) > 0) v = v or (1 shl m)
            }
            bits[n] = v
            prev = e
        }
        // 太安静的帧（静音、黑场）指纹不可靠，不参与比对
        val median = energies.sorted().getOrElse(count / 2) { 0.0 }
        for (n in 0 until count) loud[n] = energies[n] > median * 0.02 && energies[n] > 1e-6
        return Print(bits, loud)
    }

    /**
     * 找出 a、b 共有的最长片段。只接受 [minMs, maxMs] 长度的结果。
     */
    fun longestCommon(a: Print, b: Print, minMs: Long = 15_000, maxMs: Long = 200_000): Match? {
        val minFrames = msToFrames(minMs)
        val maxFrames = msToFrames(maxMs)
        var best: IntArray? = null // aStart, aEnd, shift
        var bestLen = 0
        for (shift in -(b.size - 1) until a.size) {
            // a[i] 对齐 b[i - shift]
            val from = maxOf(0, shift)
            val to = minOf(a.size, b.size + shift)
            if (to - from < minFrames) continue
            var start = -1
            var last = -1
            var hits = 0
            var i = from
            while (i <= to) {
                val match = i < to && a.loud[i] && b.loud[i - shift] &&
                    Integer.bitCount(a.bits[i] xor b.bits[i - shift]) <= MAX_BIT_ERRORS
                if (match) {
                    if (start < 0) { start = i; hits = 0 }
                    last = i
                    hits++
                } else if (start >= 0 && (i == to || i - last > MAX_GAP)) {
                    val len = last - start + 1
                    // 片段里至少四成帧对得上，排除偶然相似
                    if (len > bestLen && len <= maxFrames && hits * 10 >= len * 4) {
                        bestLen = len
                        best = intArrayOf(start, last + 1, shift)
                    }
                    start = -1
                }
                i++
            }
        }
        val r = best ?: return null
        if (bestLen < minFrames) return null
        return Match(frameMs(r[0]), frameMs(r[1]), frameMs(r[0] - r[2]), frameMs(r[1] - r[2]))
    }

    /** 原地基 2 FFT */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
