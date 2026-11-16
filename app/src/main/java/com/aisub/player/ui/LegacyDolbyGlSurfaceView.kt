package com.aisub.player.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.aisub.player.media.LegacyDolbyExoBridge
import com.aisub.player.media.LegacyExoShaderMode
import com.aisub.player.media.RpuShaderSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 老设备 ExoPlayer 专用的零拷贝 OpenGL ES 2.0 硬件色彩还原渲染视图：
 * - 兼容全系 Android 6.0+（含 Mali-450 等仅支持 GLES 2.0 `mediump` 半精度浮点的 32 位老电视）；
 * - 采用 `RENDERMODE_WHEN_DIRTY` 按帧触发渲染，播放 24fps 电影时每秒仅渲染 24 次，暂停时 GPU 零占用；
 * - 硬件解码器直接解码至 `GL_TEXTURE_EXTERNAL_OES` 显存纹理，无任何 CPU 软解或显存→内存帧读回开销。
 */
class LegacyDolbyGlSurfaceView(
    context: Context,
    private val onDecoderSurfaceChanged: (Surface?) -> Unit,
) : GLSurfaceView(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rendererImpl = DolbyOesRenderer()

    @Volatile
    private var decoderSurface: Surface? = null

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        setRenderer(rendererImpl)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setShaderMode(mode: LegacyExoShaderMode) {
        if (rendererImpl.shaderMode != mode) {
            rendererImpl.shaderMode = mode
            requestRender()
        }
    }

    fun currentDecoderSurface(): Surface? = decoderSurface

    fun releaseSurfaceResources() {
        val oldSurface = decoderSurface
        decoderSurface = null
        mainHandler.post { onDecoderSurfaceChanged(null) }
        queueEvent {
            rendererImpl.releaseGlResources()
        }
        oldSurface?.release()
    }

    override fun onDetachedFromWindow() {
        releaseSurfaceResources()
        super.onDetachedFromWindow()
    }

    private inner class DolbyOesRenderer : Renderer, SurfaceTexture.OnFrameAvailableListener {

        @Volatile
        var shaderMode: LegacyExoShaderMode = LegacyExoShaderMode.OFF

        private val hasNewFrame = AtomicBoolean(false)
        private val stMatrix = FloatArray(16)
        private var oesTexId: Int = 0
        private var surfaceTexture: SurfaceTexture? = null

        private var programId: Int = 0
        private var isFallbackPassthrough: Boolean = false
        private var aPositionLoc: Int = -1
        private var aTexCoordLoc: Int = -1
        private var uTexMatrixLoc: Int = -1
        private var uModeLoc: Int = -1
        private var uIptToLmsLoc: Int = -1
        private var uIptOffsetLoc: Int = -1
        private var uLmsToRgb709Loc: Int = -1
        private var uLumaPivotsLoc: Int = -1
        private var uLumaPoly0Loc: Int = -1
        private var uLumaPoly1Loc: Int = -1
        private var uLumaPoly2Loc: Int = -1
        private var uLumaPoly3Loc: Int = -1
        private var uChromaPolyULoc: Int = -1
        private var uChromaPolyVLoc: Int = -1
        private var uploadedRpuVersion: Int = -1

        private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(
                    floatArrayOf(
                        // X,   Y,    U,   V
                        -1.0f, -1.0f, 0.0f, 0.0f,
                         1.0f, -1.0f, 1.0f, 0.0f,
                        -1.0f,  1.0f, 0.0f, 1.0f,
                         1.0f,  1.0f, 1.0f, 1.0f,
                    ),
                )
                position(0)
            }

        init {
            Matrix.setIdentityM(stMatrix, 0)
        }

        override fun onFrameAvailable(st: SurfaceTexture?) {
            hasNewFrame.set(true)
            requestRender()
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

            programId = buildProgram(VERTEX_SHADER, DOLBY_FRAGMENT_SHADER)
            if (programId == 0) {
                isFallbackPassthrough = true
                programId = buildProgram(VERTEX_SHADER, PASSTHROUGH_FRAGMENT_SHADER)
            } else {
                isFallbackPassthrough = false
            }

            aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
            uTexMatrixLoc = GLES20.glGetUniformLocation(programId, "uTexMatrix")
            val uTexLoc = GLES20.glGetUniformLocation(programId, "uTex")
            if (!isFallbackPassthrough) {
                uModeLoc = GLES20.glGetUniformLocation(programId, "uMode")
                uIptToLmsLoc = GLES20.glGetUniformLocation(programId, "uIptToLms")
                uIptOffsetLoc = GLES20.glGetUniformLocation(programId, "uIptOffset")
                uLmsToRgb709Loc = GLES20.glGetUniformLocation(programId, "uLmsToRgb709")
                uLumaPivotsLoc = GLES20.glGetUniformLocation(programId, "uLumaPivots")
                uLumaPoly0Loc = GLES20.glGetUniformLocation(programId, "uLumaPoly0")
                uLumaPoly1Loc = GLES20.glGetUniformLocation(programId, "uLumaPoly1")
                uLumaPoly2Loc = GLES20.glGetUniformLocation(programId, "uLumaPoly2")
                uLumaPoly3Loc = GLES20.glGetUniformLocation(programId, "uLumaPoly3")
                uChromaPolyULoc = GLES20.glGetUniformLocation(programId, "uChromaPolyU")
                uChromaPolyVLoc = GLES20.glGetUniformLocation(programId, "uChromaPolyV")
            }
            uploadedRpuVersion = -1

            GLES20.glUseProgram(programId)
            if (uTexLoc >= 0) {
                GLES20.glUniform1i(uTexLoc, 0)
            }

            releaseGlTextureOnly()
            val texArr = IntArray(1)
            GLES20.glGenTextures(1, texArr, 0)
            oesTexId = texArr[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val st = SurfaceTexture(oesTexId)
            st.setOnFrameAvailableListener(this)
            surfaceTexture = st
            val prevSurface = decoderSurface
            val newSurface = Surface(st)
            decoderSurface = newSurface
            prevSurface?.release()
            mainHandler.post {
                onDecoderSurfaceChanged(newSurface)
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
        }

        override fun onDrawFrame(gl: GL10?) {
            val st = surfaceTexture ?: return
            if (hasNewFrame.compareAndSet(true, false)) {
                runCatching {
                    st.updateTexImage()
                    st.getTransformMatrix(stMatrix)
                }
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (programId == 0 || oesTexId == 0) return

            GLES20.glUseProgram(programId)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)

            if (uTexMatrixLoc >= 0) {
                GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, stMatrix, 0)
            }

            if (!isFallbackPassthrough) {
                val mode = shaderMode
                if (uModeLoc >= 0) {
                    GLES20.glUniform1i(uModeLoc, mode.code)
                }
                if (mode == LegacyExoShaderMode.PROFILE_5_IPTPQC2) {
                    val snap: RpuShaderSnapshot = LegacyDolbyExoBridge.rpuSnapshot
                    if (snap.version != uploadedRpuVersion) {
                        uploadedRpuVersion = snap.version
                        if (uIptToLmsLoc >= 0) GLES20.glUniformMatrix3fv(uIptToLmsLoc, 1, false, snap.iptToLmsColMajor, 0)
                        if (uIptOffsetLoc >= 0) GLES20.glUniform3fv(uIptOffsetLoc, 1, snap.iptOffset, 0)
                        if (uLmsToRgb709Loc >= 0) GLES20.glUniformMatrix3fv(uLmsToRgb709Loc, 1, false, snap.lmsToRgb709ColMajor, 0)
                        if (uLumaPivotsLoc >= 0) GLES20.glUniform3fv(uLumaPivotsLoc, 1, snap.lumaPivots, 0)
                        if (uLumaPoly0Loc >= 0) GLES20.glUniform3fv(uLumaPoly0Loc, 1, snap.lumaPoly0, 0)
                        if (uLumaPoly1Loc >= 0) GLES20.glUniform3fv(uLumaPoly1Loc, 1, snap.lumaPoly1, 0)
                        if (uLumaPoly2Loc >= 0) GLES20.glUniform3fv(uLumaPoly2Loc, 1, snap.lumaPoly2, 0)
                        if (uLumaPoly3Loc >= 0) GLES20.glUniform3fv(uLumaPoly3Loc, 1, snap.lumaPoly3, 0)
                        if (uChromaPolyULoc >= 0) GLES20.glUniform3fv(uChromaPolyULoc, 1, snap.chromaPolyU, 0)
                        if (uChromaPolyVLoc >= 0) GLES20.glUniform3fv(uChromaPolyVLoc, 1, snap.chromaPolyV, 0)
                    }
                }
            }

            quadVertices.position(0)
            if (aPositionLoc >= 0) {
                GLES20.glEnableVertexAttribArray(aPositionLoc)
                GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
            }
            quadVertices.position(2)
            if (aTexCoordLoc >= 0) {
                GLES20.glEnableVertexAttribArray(aTexCoordLoc)
                GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
            }

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun releaseGlTextureOnly() {
            surfaceTexture?.setOnFrameAvailableListener(null)
            runCatching { surfaceTexture?.release() }
            surfaceTexture = null
            if (oesTexId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(oesTexId), 0)
                oesTexId = 0
            }
        }

        fun releaseGlResources() {
            releaseGlTextureOnly()
            if (programId != 0) {
                GLES20.glDeleteProgram(programId)
                programId = 0
            }
        }

        private fun buildProgram(vsSrc: String, fsSrc: String): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
            if (vs == 0) return 0
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
            if (fs == 0) {
                GLES20.glDeleteShader(vs)
                return 0
            }
            val prog = GLES20.glCreateProgram()
            if (prog == 0) return 0
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                android.util.Log.w("Voxtitle", "GLES20 着色器链接失败: ${GLES20.glGetProgramInfoLog(prog)}")
                GLES20.glDeleteProgram(prog)
                return 0
            }
            return prog
        }

        private fun compileShader(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            if (sh == 0) return 0
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val status = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                android.util.Log.w("Voxtitle", "GLES20 着色器编译失败: ${GLES20.glGetShaderInfoLog(sh)}")
                GLES20.glDeleteShader(sh)
                return 0
            }
            return sh
        }
    }

    companion object {
        internal const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec4 aTexCoord;
uniform mat4 uTexMatrix;
varying vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = (uTexMatrix * aTexCoord).xy;
}
"""

        internal const val PASSTHROUGH_FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTexCoord;
uniform samplerExternalOES uTex;
void main() {
    gl_FragColor = texture2D(uTex, vTexCoord);
}
"""

        /**
         * 严格遵循 GLSL ES 1.00 (OpenGL ES 2.0) 语法规范：
         * - 支持 `highp` 的 GPU 使用精确 SMPTE ST 2084 PQ EOTF；
         * - 仅支持 `mediump` (FP16) 的老电视 GPU（如 Mali-450）使用全正系数 Horner 多项式计算归一化 PQ，
         *   彻底规避 FP16 下 `18.85156 - 18.6875 * p` 灾难性相消与 `pow(0.0, y)` NaN 黑点问题。
         */
        internal const val DOLBY_FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

varying vec2 vTexCoord;
uniform samplerExternalOES uTex;
uniform int uMode;

uniform mat3 uIptToLms;
uniform vec3 uIptOffset;
uniform mat3 uLmsToRgb709;
uniform vec3 uLumaPivots;
uniform vec3 uLumaPoly0;
uniform vec3 uLumaPoly1;
uniform vec3 uLumaPoly2;
uniform vec3 uLumaPoly3;
uniform vec3 uChromaPolyU;
uniform vec3 uChromaPolyV;

// 将非线性 PQ [0..1] 解码为以 203 nits SDR 参考白点为 1.0 的线性光强 [0..49.26]
vec3 pqToLinearNorm203(vec3 e) {
    vec3 x = clamp(e, 0.0, 1.0);
#ifdef GL_FRAGMENT_PRECISION_HIGH
    vec3 xs = max(x, vec3(0.00001));
    vec3 p = pow(xs, vec3(0.0126833135));
    vec3 num = max(p - vec3(0.8359375), vec3(0.0));
    vec3 den = max(vec3(18.8515625) - vec3(18.6875) * p, vec3(0.001));
    return pow(num / den, vec3(6.27739464)) * 49.2610837;
#else
    vec3 x2 = x * x;
    vec3 x5 = x2 * x2 * x;
    vec3 sq = x2 * (vec3(2.597985) + x5 * (vec3(5.043466) + vec3(0.389317) * x));
    return sq * sq;
#endif
}

// HLG [0..1] 解码为以 SDR 参考白点为 1.0 的场景线性光强并施加 1.2 系统伽马
vec3 hlgToLinearNorm(vec3 e) {
    vec3 x = clamp(e, 0.0, 1.0);
    vec3 lo = (x * x) * 0.3333333;
    vec3 hi = (exp((max(x, vec3(0.5)) - vec3(0.5599107)) * 5.5918163) + vec3(0.2846689)) * 0.0833333;
    vec3 scene = mix(lo, hi, step(vec3(0.5), x));
    float y = max(dot(scene, vec3(0.2627, 0.6780, 0.0593)), 0.0005);
    return scene * pow(y, 0.2) * 3.2;
}

// C1 连续有理拐点色调映射（0..0.72 保持 1:1 线性肤色与对比度，0.72..5.0 平滑滚降至 1.0 并轻微高光消色防溢色）
vec3 toneMapAndEncodeSrgb(vec3 lin709) {
    vec3 c = max(lin709, vec3(0.0));
    float peak = max(c.r, max(c.g, c.b));
    if (peak > 0.72) {
        float d = peak - 0.72;
        float mapped = 0.72 + (0.2996 * d) / (d + 0.2996);
        c = c * (mapped / peak);
        float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
        float desat = clamp((mapped - 0.78) * 1.1, 0.0, 0.25);
        c = mix(c, vec3(luma), desat);
    }
    vec3 safeC = clamp(c, 0.0002, 1.0);
    return clamp(pow(safeC, vec3(0.454545)), 0.0, 1.0);
}

void main() {
    vec4 src = texture2D(uTex, vTexCoord);
    if (uMode == 0) {
        gl_FragColor = src;
        return;
    }

    if (uMode == 2) {
        // 1. 逆转 samplerExternalOES 的 BT.709 YUV->RGB 矩阵，恢复原始 10-bit 码字 [v0, v1, v2] in [0..1]
        float v0 = clamp(dot(src.rgb, vec3(0.212600, 0.715200, 0.072200)), 0.0, 1.0);
        float v1 = clamp(dot(src.rgb, vec3(-0.114572, -0.385428, 0.500000)) + 0.5, 0.0, 1.0);
        float v2 = clamp(dot(src.rgb, vec3(0.500000, -0.454153, -0.045847)) + 0.5, 0.0, 1.0);

        // 2. 应用杜比 RPU 动态分段二次多项式重塑
        vec3 pL = uLumaPoly0;
        if (v0 >= uLumaPivots.x) pL = uLumaPoly1;
        if (v0 >= uLumaPivots.y) pL = uLumaPoly2;
        if (v0 >= uLumaPivots.z) pL = uLumaPoly3;
        float iCh = clamp((pL.z * v0 + pL.y) * v0 + pL.x, 0.0, 1.0);
        float pCh = clamp((uChromaPolyU.z * v1 + uChromaPolyU.y) * v1 + uChromaPolyU.x, 0.0, 1.0);
        float tCh = clamp((uChromaPolyV.z * v2 + uChromaPolyV.y) * v2 + uChromaPolyV.x, 0.0, 1.0);

        // 3. IPTPQc2 -> 非线性 LMS' -> 线性 LMS_c2 -> 线性 BT.709 RGB -> 色调映射与伽马编码
        vec3 ipt = vec3(iCh, pCh, tCh) - uIptOffset;
        vec3 lmsPq = clamp(uIptToLms * ipt, 0.0, 1.0);
        vec3 lmsLin = pqToLinearNorm203(lmsPq);
        vec3 lin709 = uLmsToRgb709 * lmsLin;
        gl_FragColor = vec4(toneMapAndEncodeSrgb(lin709), src.a);
        return;
    }

    // Mode 1 (HDR10 / Profile 7 / Profile 8.1 PQ) 或 Mode 3 (Profile 8.4 / HLG) -> BT.709 SDR
    vec3 lin2020 = (uMode == 3) ? hlgToLinearNorm(src.rgb) : pqToLinearNorm203(src.rgb);
    mat3 bt2020ToBt709 = mat3(
        1.660491, -0.124550, -0.018151,
       -0.587641,  1.132900, -0.100579,
       -0.072850, -0.008349,  1.118730
    );
    vec3 lin709 = bt2020ToBt709 * lin2020;
    gl_FragColor = vec4(toneMapAndEncodeSrgb(lin709), src.a);
}
"""
    }
}
