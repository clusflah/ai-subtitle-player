package com.aisub.player.media

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 老设备默认内核（ExoPlayer）零拷贝硬解杜比视界与 HDR→SDR 色彩还原桥接层：
 * 1. **严格隔离新设备**：凡支持原生杜比视界直出、支持任意 HDR 显示或运行现代系统的 64 位 GLES 3.0+ 新设备，
 *    常规播放时保持 `OFF`，完全沿用系统原生 `SurfaceView` HWC 硬件叠加层与既有策略，不影响新设备任何画质与亮度；
 * 2. **拯救老设备（如 Android 6.0 32 位小米电视 4A / Mali-450 GLES 2.0 / 无 HDR 老电视）及手动锁死 Exo 场景**：
 *    - **Dolby Vision Profile 5（`IPTPQc2`，如《密探》）**：在解封装层将无兼容底流的 `dvh1`/`dvhe` 重写为标准 `video/hevc` 交给芯片纯硬解，
 *      旁路提取每帧末尾百余字节的 `UNSPEC62` 杜比 RPU 动态参数，再由 [com.aisub.player.ui.LegacyDolbyGlSurfaceView]
 *      在 GPU 显存内单趟完成「逆 OES YUV 矩阵 → RPU 多项式重塑 → IPTPQc2 转 LMS → PQ 转线性 → BT.2390 色调映射」，零拷贝告别紫绿偏色与软解卡顿；
 *    - **Dolby Vision Profile 7 / 8 与 HDR10 / HLG**：在不支持 HDR 的老 SDR 屏幕上，硬解兼容底流后由 GLES 2.0 着色器直接完成
 *      BT.2020 PQ / HLG → BT.709 SDR 色调映射，解决老电视画面发灰偏暗问题。
 */
enum class LegacyExoShaderMode(val code: Int, val badgeSuffix: String, val summaryLabel: String) {
    OFF(0, "", ""),
    HDR_PQ_TO_SDR(1, "HDR映射", "MediaCodec 硬解 + GLES 2.0 HDR10→SDR 色调映射"),
    PROFILE_5_IPTPQC2(2, "杜比硬解", "MediaCodec 硬解 + GLES 2.0 杜比 Profile 5 (IPTPQc2) 色彩还原"),
    HDR_HLG_TO_SDR(3, "HLG映射", "MediaCodec 硬解 + GLES 2.0 HLG→SDR 色调映射"),
}

/**
 * 供 GLES 2.0 渲染线程无锁读取的杜比视界 Profile 5 矩阵与 RPU 多项式快照。
 * 所有 `3x3` 矩阵字段已预先转置为 OpenGL 列主序（column-major），可直接传入 `glUniformMatrix3fv(..., false, ...)`。
 */
class RpuShaderSnapshot(
    val version: Int,
    val iptToLmsColMajor: FloatArray,
    val iptOffset: FloatArray,
    val lmsToRgb709ColMajor: FloatArray,
    val lumaPivots: FloatArray,
    val lumaPoly0: FloatArray,
    val lumaPoly1: FloatArray,
    val lumaPoly2: FloatArray,
    val lumaPoly3: FloatArray,
    val chromaPolyU: FloatArray,
    val chromaPolyV: FloatArray,
) {
    companion object {
        // ETSI GS CCM 001 标准杜比 IPTPQc2 -> 非线性 LMS' 行主序矩阵（Ebner & Fairchild IPT 逆矩阵）
        internal val DEFAULT_IPT_TO_LMS_ROW = floatArrayOf(
            1.0000000f,  0.0975689f,  0.2052264f,
            1.0000000f, -0.1138765f,  0.1332172f,
            1.0000000f,  0.0326151f, -0.6768872f,
        )

        // 线性 LMS_c2（含 c=0.02 串扰）-> 线性 BT.709 RGB 行主序矩阵（精确保持 D65 白点 [1,1,1] -> [1,1,1]）
        internal val DEFAULT_LMSC2_TO_RGB709_ROW = floatArrayOf(
             5.7571983f, -4.9982419f,  0.2406656f,
            -1.1970246f,  2.4374985f, -0.2403038f,
             0.0086284f, -0.2284130f,  1.2197922f,
        )

        // BT.2020 线性 RGB -> BT.709 线性 RGB 行主序矩阵（当 RPU rgb_to_lms 指向 BT.2020 原色时用于转换至 BT.709）
        internal val BT2020_TO_BT709_ROW = floatArrayOf(
             1.6604910f, -0.5876411f, -0.0728499f,
            -0.1245505f,  1.1328999f, -0.0083494f,
            -0.0181508f, -0.1005789f,  1.1187297f,
        )

        internal val IDENTITY_POLY = floatArrayOf(0.0f, 1.0f, 0.0f)

        val DEFAULT_PROFILE_5: RpuShaderSnapshot = RpuShaderSnapshot(
            version = 0,
            iptToLmsColMajor = rowToColMajor3x3(DEFAULT_IPT_TO_LMS_ROW),
            iptOffset = floatArrayOf(0.0f, 0.5f, 0.5f),
            lmsToRgb709ColMajor = rowToColMajor3x3(DEFAULT_LMSC2_TO_RGB709_ROW),
            lumaPivots = floatArrayOf(1.0f, 1.0f, 1.0f),
            lumaPoly0 = IDENTITY_POLY.copyOf(),
            lumaPoly1 = IDENTITY_POLY.copyOf(),
            lumaPoly2 = IDENTITY_POLY.copyOf(),
            lumaPoly3 = IDENTITY_POLY.copyOf(),
            chromaPolyU = IDENTITY_POLY.copyOf(),
            chromaPolyV = IDENTITY_POLY.copyOf(),
        )

        internal fun rowToColMajor3x3(m: FloatArray): FloatArray = floatArrayOf(
            m[0], m[3], m[6],
            m[1], m[4], m[7],
            m[2], m[5], m[8],
        )

        internal fun mul3x3Row(a: FloatArray, b: FloatArray): FloatArray {
            val out = FloatArray(9)
            for (r in 0..2) {
                for (c in 0..2) {
                    out[r * 3 + c] =
                        a[r * 3 + 0] * b[0 * 3 + c] +
                        a[r * 3 + 1] * b[1 * 3 + c] +
                        a[r * 3 + 2] * b[2 * 3 + c]
                }
            }
            return out
        }

        internal fun invert3x3Row(m: FloatArray): FloatArray? {
            val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble()
            val d = m[3].toDouble(); val e = m[4].toDouble(); val f = m[5].toDouble()
            val g = m[6].toDouble(); val h = m[7].toDouble(); val i = m[8].toDouble()

            val c00 = e * i - f * h
            val c01 = -(d * i - f * g)
            val c02 = d * h - e * g
            val det = a * c00 + b * c01 + c * c02
            if (!det.isFinite() || abs(det) < 1e-5) return null
            val invDet = 1.0 / det
            return floatArrayOf(
                (c00 * invDet).toFloat(),
                (-(b * i - c * h) * invDet).toFloat(),
                ((b * f - c * e) * invDet).toFloat(),
                (c01 * invDet).toFloat(),
                ((a * i - c * g) * invDet).toFloat(),
                (-(a * f - c * d) * invDet).toFloat(),
                (c02 * invDet).toFloat(),
                (-(a * h - b * g) * invDet).toFloat(),
                ((a * e - b * d) * invDet).toFloat(),
            )
        }
    }
}

/**
 * 轻量零分配杜比视界 HEVC `UNSPEC62` RPU 元数据解析器：
 * 解析 `vdr_rpu_data_payload` 分段多项式与 `vdr_dm_data_payload` 精确色彩矩阵，耗时 < 0.05ms/帧。
 */
internal object DolbyRpuParser {

    internal class CachedRpuCurve(
        val lumaPivots: FloatArray,
        val lumaPolys: Array<FloatArray>,
        val chromaPolyU: FloatArray,
        val chromaPolyV: FloatArray,
    )

    private val versionCounter = AtomicInteger(1)
    private val cachedCurves = arrayOfNulls<CachedRpuCurve>(16)
    private var lastIptToLmsRow: FloatArray = RpuShaderSnapshot.DEFAULT_IPT_TO_LMS_ROW
    private var lastIptOffset: FloatArray = floatArrayOf(0.0f, 0.5f, 0.5f)
    private var lastLmsToRgb709Row: FloatArray = RpuShaderSnapshot.DEFAULT_LMSC2_TO_RGB709_ROW

    @Synchronized
    fun reset() {
        cachedCurves.fill(null)
        lastIptToLmsRow = RpuShaderSnapshot.DEFAULT_IPT_TO_LMS_ROW
        lastIptOffset = floatArrayOf(0.0f, 0.5f, 0.5f)
        lastLmsToRgb709Row = RpuShaderSnapshot.DEFAULT_LMSC2_TO_RGB709_ROW
    }

    @Synchronized
    fun parseNaluToSnapshot(nalu: ByteArray, offset: Int, length: Int, rbspScratch: ByteArray): RpuShaderSnapshot? {
        if (length < 6 || offset < 0 || offset + length > nalu.size) return null
        val nalType = (nalu[offset].toInt() and 0x7E) ushr 1
        if (nalType != 62) return null

        // 跳过 2 字节 HEVC NAL 头，去除 0x00 0x00 0x03 防竞争字节得到 RBSP
        var rbspLen = 0
        var zeros = 0
        val end = offset + length
        var p = offset + 2
        while (p < end && rbspLen < rbspScratch.size) {
            val b = nalu[p].toInt() and 0xFF
            if (zeros >= 2 && b == 0x03) {
                zeros = 0
                p++
                continue
            }
            rbspScratch[rbspLen++] = b.toByte()
            zeros = if (b == 0) zeros + 1 else 0
            p++
        }
        if (rbspLen < 4) return null
        return parseRbsp(rbspScratch, rbspLen)
    }

    internal fun parseRbsp(rbsp: ByteArray, rbspLen: Int): RpuShaderSnapshot? {
        val br = BitReader(rbsp, rbspLen)
        val prefix = br.readBits(8)
        if (prefix != 25) return null
        val rpuType = br.readBits(6)
        if (rpuType != 2) return null

        val rpuFormat = br.readBits(11)
        val vdrRpuProfile = br.readBits(4)
        br.readBits(4) // vdr_rpu_level

        var coefDataType = 0
        var coefLog2Denom = 23
        var blBitDepth = 10
        var disableResidualFlag = true

        val vdrSeqInfoPresent = br.readBit() == 1
        if (vdrSeqInfoPresent) {
            br.readBit() // chroma_resampling_explicit_filter_flag
            coefDataType = br.readBits(2)
            if (coefDataType == 0) {
                coefLog2Denom = br.readUe()
                if (coefLog2Denom !in 13..32) return null
            } else if (coefDataType == 1) {
                coefLog2Denom = 32
            } else {
                return null
            }
            br.readBits(2) // vdr_rpu_normalized_idc
            br.readBit() // bl_video_full_range_flag
            if ((rpuFormat and 0x700) == 0) {
                val blMinus8 = br.readUe()
                val elMinus8 = br.readUe()
                val vdrMinus8 = br.readUe()
                if (blMinus8 > 8 || elMinus8 > 8 || vdrMinus8 > 8) return null
                blBitDepth = 8 + blMinus8
                br.readBit() // spatial_resampling_filter_flag
                br.readBits(3) // reserved_zero_3bits
                br.readBit() // el_spatial_resampling_filter_flag
                disableResidualFlag = br.readBit() == 1
            }
        }

        val dmMetadataPresent = br.readBit() == 1
        val usePrevVdrRpu = br.readBit() == 1
        val rpuId = br.readUe()
        if (rpuId !in 0..15) return null

        var activeCurve: CachedRpuCurve? = if (usePrevVdrRpu) cachedCurves[rpuId] else null
        var polynomialParsedCleanly = usePrevVdrRpu

        if (!usePrevVdrRpu) {
            polynomialParsedCleanly = false
            val parsedCurve = parseReshapePayload(br, rpuFormat, blBitDepth, coefDataType, coefLog2Denom, disableResidualFlag)
            if (parsedCurve != null) {
                cachedCurves[rpuId] = parsedCurve
                activeCurve = parsedCurve
                polynomialParsedCleanly = true
            }
        }

        var updatedMatrices = false
        if (dmMetadataPresent && polynomialParsedCleanly && br.bitsLeft() >= 64) {
            val affectedDmId = br.readUe()
            val currentDmId = br.readUe()
            val sceneRefresh = br.readUe()
            if (affectedDmId in 0..15 && currentDmId in 0..15 && sceneRefresh >= 0 && br.bitsLeft() >= (9 * 16 + 3 * 32 + 9 * 16)) {
                val yccToRgb = FloatArray(9)
                for (i in 0 until 9) {
                    yccToRgb[i] = br.readSignedBits(16) / 8192.0f
                }
                val offsetDenom = if (vdrRpuProfile == 4) (1L shl 30).toFloat() else (1L shl 28).toFloat()
                val offsets = FloatArray(3)
                for (i in 0 until 3) {
                    val rawOff = br.readLongBits(32)
                    offsets[i] = (rawOff.toDouble() / offsetDenom.toDouble()).toFloat().coerceIn(-1.0f, 1.0f)
                }
                val rgbToLms = FloatArray(9)
                for (i in 0 until 9) {
                    rgbToLms[i] = br.readSignedBits(16) / 16384.0f
                }
                // 校验矩阵合理性并转换为 LMS_c2 -> 线性 BT.709 RGB
                if (abs(yccToRgb[0] - 1.0f) < 0.35f && abs(yccToRgb[3] - 1.0f) < 0.35f) {
                    lastIptToLmsRow = yccToRgb
                    lastIptOffset = offsets
                }
                RpuShaderSnapshot.invert3x3Row(rgbToLms)?.let { lmsToSrcRgb ->
                    // 判断源原色是 BT.2020 (rgbToLms[0] ~ 0.41..0.43) 还是 BT.709 (rgbToLms[0] ~ 0.30..0.32)
                    val lmsTo709 = if (rgbToLms[0] > 0.36f) {
                        RpuShaderSnapshot.mul3x3Row(RpuShaderSnapshot.BT2020_TO_BT709_ROW, lmsToSrcRgb)
                    } else {
                        lmsToSrcRgb
                    }
                    // 白点守恒校验：确保 [1,1,1] 映射在合理区间，杜绝损坏码流引起色彩爆炸
                    val wR = lmsTo709[0] + lmsTo709[1] + lmsTo709[2]
                    val wG = lmsTo709[3] + lmsTo709[4] + lmsTo709[5]
                    val wB = lmsTo709[6] + lmsTo709[7] + lmsTo709[8]
                    if (wR in 0.75f..1.25f && wG in 0.75f..1.25f && wB in 0.75f..1.25f) {
                        lastLmsToRgb709Row = lmsTo709
                    }
                }
                updatedMatrices = true
            }
        }

        if (usePrevVdrRpu && !updatedMatrices && activeCurve == null) {
            return null
        }

        val curve = activeCurve
        val idPoly = RpuShaderSnapshot.IDENTITY_POLY
        return RpuShaderSnapshot(
            version = versionCounter.incrementAndGet(),
            iptToLmsColMajor = RpuShaderSnapshot.rowToColMajor3x3(lastIptToLmsRow),
            iptOffset = lastIptOffset.copyOf(),
            lmsToRgb709ColMajor = RpuShaderSnapshot.rowToColMajor3x3(lastLmsToRgb709Row),
            lumaPivots = curve?.lumaPivots ?: floatArrayOf(1.0f, 1.0f, 1.0f),
            lumaPoly0 = curve?.lumaPolys?.getOrNull(0) ?: idPoly,
            lumaPoly1 = curve?.lumaPolys?.getOrNull(1) ?: idPoly,
            lumaPoly2 = curve?.lumaPolys?.getOrNull(2) ?: idPoly,
            lumaPoly3 = curve?.lumaPolys?.getOrNull(3) ?: idPoly,
            chromaPolyU = curve?.chromaPolyU ?: idPoly,
            chromaPolyV = curve?.chromaPolyV ?: idPoly,
        )
    }

    private fun parseReshapePayload(
        br: BitReader,
        rpuFormat: Int,
        blBitDepth: Int,
        coefDataType: Int,
        coefLog2Denom: Int,
        disableResidualFlag: Boolean,
    ): CachedRpuCurve? {
        br.readUe() // mapping_color_space
        br.readUe() // mapping_chroma_format_idc
        val numPivots = IntArray(3)
        val pivotsNorm = Array(3) { FloatArray(0) }
        val maxVal = ((1 shl blBitDepth) - 1).coerceAtLeast(1).toFloat()

        for (c in 0..2) {
            val npMinus2 = br.readUe()
            if (npMinus2 !in 0..7) return null
            val np = npMinus2 + 2
            numPivots[c] = np
            val pv = FloatArray(np)
            var acc = 0
            for (i in 0 until np) {
                acc += br.readBits(blBitDepth)
                pv[i] = (acc.toFloat() / maxVal).coerceIn(0.0f, 1.0f)
            }
            pivotsNorm[c] = pv
        }

        if ((rpuFormat and 0x700) == 0 && !disableResidualFlag) {
            // Profile 5 不使用 NLQ；若混入其它 profile 字段则安全放弃多项式解析但保留默认恒等重塑
            return null
        }

        val numXPart = br.readUe()
        val numYPart = br.readUe()
        if (numXPart < 0 || numYPart < 0) return null

        val denom = if (coefDataType == 0) (1L shl coefLog2Denom).toDouble() else 1.0
        val chanPolys = Array(3) { ArrayList<FloatArray>() }

        for (c in 0..2) {
            val pieces = numPivots[c] - 1
            for (i in 0 until pieces) {
                val mappingIdc = br.readUe()
                if (mappingIdc == 0) {
                    val linearInterp = br.readBit() == 1
                    if (linearInterp) {
                        val x0 = pivotsNorm[c][i]
                        val x1 = pivotsNorm[c][i + 1]
                        val y0 = readCoef(br, coefDataType, coefLog2Denom, denom)
                        val y1 = readCoef(br, coefDataType, coefLog2Denom, denom)
                        val slope = if (x1 > x0 + 1e-5f) (y1 - y0) / (x1 - x0) else 1.0f
                        chanPolys[c] += floatArrayOf(y0 - slope * x0, slope, 0.0f)
                    } else {
                        val orderMinus1 = br.readUe()
                        if (orderMinus1 !in 0..1) return null
                        val order = orderMinus1 + 1
                        val poly = FloatArray(3)
                        for (k in 0..order) {
                            poly[k] = readCoef(br, coefDataType, coefLog2Denom, denom)
                        }
                        chanPolys[c] += poly
                    }
                } else if (mappingIdc == 1) {
                    // MMR 多维重塑：跳过 MMR 系数位，对色度通道采用恒等回退（避免跨通道误差）
                    val mmrOrderMinus1 = br.readBits(2)
                    if (mmrOrderMinus1 !in 0..2) return null
                    val mmrOrder = mmrOrderMinus1 + 1
                    readCoef(br, coefDataType, coefLog2Denom, denom) // mmr_constant
                    for (j in 0 until mmrOrder) {
                        for (k in 0 until 7) {
                            readCoef(br, coefDataType, coefLog2Denom, denom)
                        }
                    }
                    chanPolys[c] += RpuShaderSnapshot.IDENTITY_POLY.copyOf()
                } else {
                    return null
                }
            }
        }

        // 将亮度最多 8 段压缩映射至 4 段 GLES 2.0 友好分段点
        val lPieces = chanPolys[0]
        val lPiv = pivotsNorm[0]
        val outPiv = floatArrayOf(1.0f, 1.0f, 1.0f)
        val outPolys = Array(4) { RpuShaderSnapshot.IDENTITY_POLY.copyOf() }
        if (lPieces.isNotEmpty()) {
            if (lPieces.size <= 4) {
                for (i in 0 until 4) {
                    outPolys[i] = lPieces[minOf(i, lPieces.lastIndex)]
                    if (i < 3 && i + 1 < lPiv.size - 1) {
                        outPiv[i] = lPiv[i + 1]
                    }
                }
            } else {
                val n = lPieces.size
                val idx0 = 0
                val idx1 = n / 4
                val idx2 = n / 2
                val idx3 = (3 * n) / 4
                outPolys[0] = lPieces[idx0]
                outPolys[1] = lPieces[idx1]
                outPolys[2] = lPieces[idx2]
                outPolys[3] = lPieces[idx3]
                outPiv[0] = lPiv[idx1]
                outPiv[1] = lPiv[idx2]
                outPiv[2] = lPiv[idx3]
            }
        }

        val uPoly = pickMidPiece(chanPolys[1])
        val vPoly = pickMidPiece(chanPolys[2])
        return CachedRpuCurve(outPiv, outPolys, uPoly, vPoly)
    }

    private fun pickMidPiece(pieces: List<FloatArray>): FloatArray {
        if (pieces.isEmpty()) return RpuShaderSnapshot.IDENTITY_POLY.copyOf()
        return pieces[pieces.size / 2]
    }

    private fun readCoef(br: BitReader, coefDataType: Int, coefLog2Denom: Int, denom: Double): Float {
        return if (coefDataType == 0) {
            val intPart = br.readSe()
            val fracPart = br.readLongBits(coefLog2Denom)
            (intPart.toDouble() + fracPart.toDouble() / denom).toFloat()
        } else {
            java.lang.Float.intBitsToFloat(br.readLongBits(32).toInt())
        }
    }

    private class BitReader(private val data: ByteArray, private val byteLen: Int) {
        private var bitPos: Int = 0
        private val totalBits: Int = byteLen * 8

        fun bitsLeft(): Int = (totalBits - bitPos).coerceAtLeast(0)

        fun readBit(): Int {
            if (bitPos >= totalBits) return 0
            val b = data[bitPos ushr 3].toInt() and 0xFF
            val shift = 7 - (bitPos and 7)
            bitPos++
            return (b ushr shift) and 1
        }

        fun readBits(n: Int): Int = readLongBits(n).toInt()

        fun readLongBits(n: Int): Long {
            if (n <= 0) return 0L
            var res = 0L
            for (i in 0 until n) {
                res = (res shl 1) or readBit().toLong()
            }
            return res
        }

        fun readSignedBits(n: Int): Int {
            val u = readBits(n)
            val signBit = 1 shl (n - 1)
            return if ((u and signBit) != 0) u - (1 shl n) else u
        }

        fun readUe(): Int {
            var leadingZeros = 0
            while (bitPos < totalBits && readBit() == 0 && leadingZeros < 31) {
                leadingZeros++
            }
            if (leadingZeros == 0) return 0
            val suffix = readLongBits(leadingZeros)
            return ((1L shl leadingZeros) - 1L + suffix).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        fun readSe(): Int {
            val codeNum = readUe()
            val absVal = (codeNum + 1) ushr 1
            return if ((codeNum and 1) != 0) absVal else -absVal
        }
    }
}

@OptIn(UnstableApi::class)
object LegacyDolbyExoBridge {

    const val TAG_DV_PROFILE_5 = "#aisub_dv5"
    const val TAG_HDR_PQ = "#aisub_hdr_pq"
    const val TAG_HDR_HLG = "#aisub_hdr_hlg"

    private val _shaderModeFlow = MutableStateFlow(LegacyExoShaderMode.OFF)
    val shaderModeFlow: StateFlow<LegacyExoShaderMode> = _shaderModeFlow.asStateFlow()

    @Volatile
    var rpuSnapshot: RpuShaderSnapshot = RpuShaderSnapshot.DEFAULT_PROFILE_5
        private set

    @Volatile
    private var activeTrackLocked: Boolean = false

    fun reset() {
        activeTrackLocked = false
        _shaderModeFlow.value = LegacyExoShaderMode.OFF
        rpuSnapshot = RpuShaderSnapshot.DEFAULT_PROFILE_5
        DolbyRpuParser.reset()
    }

    /**
     * 是否应在 ExoPlayer 内直接硬解并通过 GLES 2.0 着色器还原杜比视界 Profile 5：
     * - 原生杜比视界设备返回 false（走硬件原生直出）；
     * - 老设备（[DolbyVisionHelper.isLegacyDolbyDevice]）返回 true；
     * - 用户在非杜比屏幕上手动指定锁死 Exo 内核（或关闭了自动切 MPV）时也返回 true，避免 Exo 出现紫绿画面；
     * - 其它现代非杜比新手机默认返回 false（保持自动分流至 MPV `libplacebo`）。
     */
    fun shouldUseExoForProfile5(
        context: Context,
        format: Format? = null,
        decodeMode: com.aisub.player.DecodeMode = com.aisub.player.DecodeMode.AUTO,
        manualExoLocked: Boolean = false,
    ): Boolean = DolbyVisionHelper.shouldUseExoHardwareShaderForProfile5(
        context = context,
        format = format,
        decodeMode = decodeMode,
        manualExoLocked = manualExoLocked,
    )

    /**
     * 是否应在 ExoPlayer 内启用 HDR10 / Profile 7 / 8 → SDR 色调映射着色器：
     * 仅对完全不支持 HDR 且系统低于 Android 12 (API 31) 的老设备启用，绝不干扰任何新设备的原生 HDR 显示。
     */
    fun shouldUseExoHdrToneMap(context: Context): Boolean {
        return !DolbyVisionHelper.displaySupportsAnyHdr(context) &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    }

    /** 由 `MainViewModel.onTracksChanged` 根据当前真正选中的播放轨道精确同步着色器模式（防止播放列表后台预缓冲下一集串台）。 */
    fun syncFromSelectedTracks(
        context: Context,
        formats: Sequence<Format>,
        decodeMode: com.aisub.player.DecodeMode = com.aisub.player.DecodeMode.AUTO,
        manualExoLocked: Boolean = false,
    ) {
        var resolved = LegacyExoShaderMode.OFF
        for (f in formats) {
            val id = f.id.orEmpty()
            when {
                id.endsWith(TAG_DV_PROFILE_5) -> {
                    resolved = LegacyExoShaderMode.PROFILE_5_IPTPQC2
                    break
                }
                id.endsWith(TAG_HDR_HLG) -> {
                    resolved = LegacyExoShaderMode.HDR_HLG_TO_SDR
                    break
                }
                id.endsWith(TAG_HDR_PQ) -> {
                    resolved = LegacyExoShaderMode.HDR_PQ_TO_SDR
                    break
                }
                shouldUseExoHdrToneMap(context) -> {
                    val transfer = f.colorInfo?.colorTransfer ?: C.INDEX_UNSET
                    if (transfer == C.COLOR_TRANSFER_HLG) {
                        resolved = LegacyExoShaderMode.HDR_HLG_TO_SDR
                        break
                    } else if (transfer == C.COLOR_TRANSFER_ST2084 || DolbyVisionHelper.hasHdrBaseLayerFallback(f)) {
                        resolved = LegacyExoShaderMode.HDR_PQ_TO_SDR
                        break
                    }
                }
            }
            if (DolbyVisionHelper.isProfile5(f) && shouldUseExoForProfile5(context, f, decodeMode, manualExoLocked)) {
                resolved = LegacyExoShaderMode.PROFILE_5_IPTPQC2
                break
            }
        }
        activeTrackLocked = resolved != LegacyExoShaderMode.OFF
        _shaderModeFlow.value = resolved
    }

    internal fun onExtractorDetectedInitialMode(mode: LegacyExoShaderMode) {
        if (!activeTrackLocked && mode != LegacyExoShaderMode.OFF) {
            _shaderModeFlow.value = mode
        }
    }

    internal fun updateRpuSnapshot(snapshot: RpuShaderSnapshot) {
        rpuSnapshot = snapshot
    }

    fun wrapExtractor(
        extractor: Extractor,
        context: Context,
        decodeModeProvider: () -> com.aisub.player.DecodeMode = { com.aisub.player.DecodeMode.AUTO },
        manualExoLockedProvider: () -> Boolean,
    ): Extractor {
        val appCtx = context.applicationContext
        return object : Extractor {
            override fun getUnderlyingImplementation(): Extractor = extractor.underlyingImplementation
            override fun sniff(input: ExtractorInput): Boolean = extractor.sniff(input)
            override fun init(output: ExtractorOutput) {
                extractor.init(DolbyVideoExtractorOutput(output, appCtx, decodeModeProvider, manualExoLockedProvider))
            }
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
                extractor.read(input, seekPosition)
            override fun seek(position: Long, timeUs: Long) = extractor.seek(position, timeUs)
            override fun release() = extractor.release()
        }
    }

    private class DolbyVideoExtractorOutput(
        private val downstream: ExtractorOutput,
        private val appContext: Context,
        private val decodeModeProvider: () -> com.aisub.player.DecodeMode,
        private val manualExoLockedProvider: () -> Boolean,
    ) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            val out = downstream.track(id, type)
            return if (type == C.TRACK_TYPE_VIDEO) {
                DolbyVideoTrackOutput(out, appContext, decodeModeProvider, manualExoLockedProvider)
            } else {
                out
            }
        }

        override fun endTracks() = downstream.endTracks()
        override fun seekMap(seekMap: SeekMap) = downstream.seekMap(seekMap)
    }

    private class DolbyVideoTrackOutput(
        private val downstream: TrackOutput,
        private val appContext: Context,
        private val decodeModeProvider: () -> com.aisub.player.DecodeMode,
        private val manualExoLockedProvider: () -> Boolean,
    ) : TrackOutput {

        private var tapRpu: Boolean = false
        private var expectingNalHeader: Boolean = false
        private var capturingRpu: Boolean = false
        private var rpuLen: Int = 0
        private val rpuBuf = ByteArray(1024)
        private val rbspScratch = ByteArray(1024)
        private val ioScratch = ByteArray(1024)
        private val tinyHeader = ByteArray(2)

        override fun format(format: Format) {
            val manualLocked = manualExoLockedProvider()
            val isDv = DolbyVisionHelper.isDolbyVision(format)
            val isP5 = DolbyVisionHelper.isProfile5(format)

            val curDecodeMode = decodeModeProvider()
            if (isP5 && shouldUseExoForProfile5(appContext, format, curDecodeMode, manualLocked) && format.initializationData.isNotEmpty()) {
                tapRpu = true
                onExtractorDetectedInitialMode(LegacyExoShaderMode.PROFILE_5_IPTPQC2)
                val baseId = format.id.orEmpty().removeSuffix(TAG_DV_PROFILE_5)
                val fullRangeBt709 = ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT709)
                    .setColorRange(C.COLOR_RANGE_FULL)
                    .setColorTransfer(C.COLOR_TRANSFER_SDR)
                    .build()
                val cleanInitData = format.initializationData
                    .filterNot { buf -> isDolbyConfigRecord(buf) }
                    .ifEmpty { format.initializationData.take(1) }
                val rewritten = format.buildUpon()
                    .setId(baseId + TAG_DV_PROFILE_5)
                    .setSampleMimeType(MimeTypes.VIDEO_H265)
                    .setCodecs(null)
                    .setInitializationData(cleanInitData)
                    .setColorInfo(fullRangeBt709)
                    .build()
                downstream.format(rewritten)
                return
            }

            tapRpu = false
            if (shouldUseExoHdrToneMap(appContext)) {
                val transfer = format.colorInfo?.colorTransfer ?: C.INDEX_UNSET
                val isHlg = transfer == C.COLOR_TRANSFER_HLG
                val isPqOrDvFallback = transfer == C.COLOR_TRANSFER_ST2084 || (isDv && DolbyVisionHelper.hasHdrBaseLayerFallback(format))
                if (isHlg || isPqOrDvFallback) {
                    val mode = if (isHlg) LegacyExoShaderMode.HDR_HLG_TO_SDR else LegacyExoShaderMode.HDR_PQ_TO_SDR
                    val tag = if (isHlg) TAG_HDR_HLG else TAG_HDR_PQ
                    onExtractorDetectedInitialMode(mode)
                    val baseId = format.id.orEmpty().removeSuffix(TAG_HDR_PQ).removeSuffix(TAG_HDR_HLG)
                    val builder = format.buildUpon().setId(baseId + tag)
                    // Android 6.0 (API 23) 等老系统不认识 video/dolby-vision，直接剥离 dvcC 改标标准 video/hevc 硬解底流
                    if (isDv && format.initializationData.isNotEmpty()) {
                        val cleanInitData = format.initializationData
                            .filterNot { buf -> isDolbyConfigRecord(buf) }
                            .ifEmpty { format.initializationData.take(1) }
                        builder
                            .setSampleMimeType(MimeTypes.VIDEO_H265)
                            .setCodecs(null)
                            .setInitializationData(cleanInitData)
                    }
                    downstream.format(builder.build())
                    return
                }
            }

            downstream.format(format)
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            if (!tapRpu) {
                return downstream.sampleData(input, length, allowEndOfInput, sampleDataPart)
            }
            if (expectingNalHeader) {
                if (length <= ioScratch.size) {
                    val n = input.read(ioScratch, 0, length)
                    if (n == C.RESULT_END_OF_INPUT) {
                        if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                        throw java.io.EOFException()
                    }
                    if (n <= 0) return n
                    val nalType = (ioScratch[0].toInt() and 0x7E) ushr 1
                    expectingNalHeader = false
                    if (nalType == 62) {
                        capturingRpu = true
                        rpuLen = 0
                        appendRpuBytes(ioScratch, 0, n)
                    } else {
                        capturingRpu = false
                    }
                    downstream.sampleData(ParsableByteArray(ioScratch, n), n, sampleDataPart)
                    return n
                } else {
                    // 大体积视频帧 Slice：仅窥探首 2 字节 NAL 头，剩余几十 KB 数据后续 100% 零拷贝直通 SampleQueue
                    val n = input.read(tinyHeader, 0, minOf(2, length))
                    if (n == C.RESULT_END_OF_INPUT) {
                        if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                        throw java.io.EOFException()
                    }
                    if (n <= 0) return n
                    val nalType = (tinyHeader[0].toInt() and 0x7E) ushr 1
                    expectingNalHeader = false
                    capturingRpu = (nalType == 62)
                    if (capturingRpu) {
                        rpuLen = 0
                        appendRpuBytes(tinyHeader, 0, n)
                    }
                    downstream.sampleData(ParsableByteArray(tinyHeader, n), n, sampleDataPart)
                    return n
                }
            }
            if (capturingRpu && length <= ioScratch.size) {
                val n = input.read(ioScratch, 0, length)
                if (n == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                    throw java.io.EOFException()
                }
                if (n <= 0) return n
                appendRpuBytes(ioScratch, 0, n)
                downstream.sampleData(ParsableByteArray(ioScratch, n), n, sampleDataPart)
                return n
            }
            return downstream.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (tapRpu && length > 0) {
                val pos = data.position
                val arr = data.data
                if (length == 4 &&
                    pos + 4 <= data.limit() &&
                    arr[pos].toInt() == 0 &&
                    arr[pos + 1].toInt() == 0 &&
                    arr[pos + 2].toInt() == 0 &&
                    arr[pos + 3].toInt() == 1
                ) {
                    if (capturingRpu && rpuLen > 4) {
                        flushCapturedRpu()
                    }
                    expectingNalHeader = true
                    capturingRpu = false
                } else if (length == 3 &&
                    pos + 3 <= data.limit() &&
                    arr[pos].toInt() == 0 &&
                    arr[pos + 1].toInt() == 0 &&
                    arr[pos + 2].toInt() == 1
                ) {
                    if (capturingRpu && rpuLen > 4) {
                        flushCapturedRpu()
                    }
                    expectingNalHeader = true
                    capturingRpu = false
                } else if (expectingNalHeader && pos < data.limit()) {
                    expectingNalHeader = false
                    val nalType = (arr[pos].toInt() and 0x7E) ushr 1
                    if (nalType == 62) {
                        capturingRpu = true
                        rpuLen = 0
                        appendRpuBytes(arr, pos, length)
                    } else {
                        capturingRpu = false
                    }
                } else if (capturingRpu) {
                    appendRpuBytes(arr, pos, length)
                } else if (length in 24..512 && pos + 6 <= data.limit()) {
                    // 兼容一次性写入整段 Annex-B RPU 单元的解封装器
                    val startOff = when {
                        arr[pos].toInt() == 0 && arr[pos + 1].toInt() == 0 && arr[pos + 2].toInt() == 0 && arr[pos + 3].toInt() == 1 -> 4
                        arr[pos].toInt() == 0 && arr[pos + 1].toInt() == 0 && arr[pos + 2].toInt() == 1 -> 3
                        else -> 0
                    }
                    if (pos + startOff < data.limit()) {
                        val nalType = (arr[pos + startOff].toInt() and 0x7E) ushr 1
                        if (nalType == 62) {
                            rpuLen = 0
                            appendRpuBytes(arr, pos + startOff, length - startOff)
                        }
                    }
                }
            }
            downstream.sampleData(data, length, sampleDataPart)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            if (tapRpu && rpuLen > 4) {
                flushCapturedRpu()
            }
            expectingNalHeader = false
            capturingRpu = false
            downstream.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }

        private fun appendRpuBytes(src: ByteArray, offset: Int, count: Int) {
            val room = rpuBuf.size - rpuLen
            if (room <= 0 || count <= 0) return
            val n = minOf(room, count)
            System.arraycopy(src, offset, rpuBuf, rpuLen, n)
            rpuLen += n
        }

        private fun isDolbyConfigRecord(buf: ByteArray): Boolean {
            val looksAnnexB = buf.size >= 4 &&
                buf[0].toInt() == 0 &&
                buf[1].toInt() == 0 &&
                (buf[2].toInt() == 1 || (buf[2].toInt() == 0 && buf[3].toInt() == 1))
            if (looksAnnexB) return false
            return DolbyVisionHelper.parseDvccProfile(listOf(buf)) != null
        }

        private fun flushCapturedRpu() {
            val len = rpuLen
            rpuLen = 0
            capturingRpu = false
            runCatching {
                DolbyRpuParser.parseNaluToSnapshot(rpuBuf, 0, len, rbspScratch)?.let { snap ->
                    updateRpuSnapshot(snap)
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
fun ExtractorsFactory.withLegacyDolbyExoSupport(
    context: Context,
    decodeModeProvider: () -> com.aisub.player.DecodeMode = { com.aisub.player.DecodeMode.AUTO },
    manualExoLockedProvider: () -> Boolean,
): ExtractorsFactory {
    val base = this
    return object : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> =
            base.createExtractors()
                .map { LegacyDolbyExoBridge.wrapExtractor(it, context, decodeModeProvider, manualExoLockedProvider) }
                .toTypedArray()

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            base.createExtractors(uri, responseHeaders)
                .map { LegacyDolbyExoBridge.wrapExtractor(it, context, decodeModeProvider, manualExoLockedProvider) }
                .toTypedArray()

        override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory =
            base.experimentalSetTextTrackTranscodingEnabled(enabled)
                .withLegacyDolbyExoSupport(context, decodeModeProvider, manualExoLockedProvider)

        override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory =
            base.setSubtitleParserFactory(factory)
                .withLegacyDolbyExoSupport(context, decodeModeProvider, manualExoLockedProvider)

        override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecs: Int): ExtractorsFactory =
            base.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs)
                .withLegacyDolbyExoSupport(context, decodeModeProvider, manualExoLockedProvider)
    }
}
