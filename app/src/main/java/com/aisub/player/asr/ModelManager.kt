package com.aisub.player.asr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 管理识别模型：首次使用时从 sherpa-onnx 的 GitHub Release 下载，
 * 只解压出需要的两个文件，不把测试音频等内容留在手机上。
 */
class ModelManager(context: Context) {

    data class Paths(val senseVoiceModel: File, val tokens: File, val vad: File)

    private val root = File(context.filesDir, "models")
    private val senseVoiceDir = File(root, "sense-voice")
    val paths = Paths(
        senseVoiceModel = File(senseVoiceDir, "model.int8.onnx"),
        tokens = File(senseVoiceDir, "tokens.txt"),
        vad = File(root, "silero_vad.onnx"),
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isReady(): Boolean = paths.senseVoiceModel.exists() && paths.tokens.exists() && paths.vad.exists()

    /**
     * @param baseUrl 模型下载地址前缀，默认 GitHub；国内网络可以换成镜像
     * @param onProgress (说明文字, 0..1 进度；-1 表示不确定)
     */
    suspend fun ensureModels(baseUrl: String, onProgress: (String, Float) -> Unit) = withContext(Dispatchers.IO) {
        root.mkdirs()
        val base = baseUrl.trimEnd('/') + "/"
        if (!paths.vad.exists()) {
            download(base + VAD_FILE, paths.vad) { onProgress("下载 VAD 模型", it) }
        }
        if (!paths.senseVoiceModel.exists() || !paths.tokens.exists()) {
            val archive = File(root, SENSE_VOICE_ARCHIVE)
            if (!archive.exists()) {
                download(base + SENSE_VOICE_ARCHIVE, archive) { onProgress("下载 SenseVoice 识别模型（约 160MB）", it) }
            }
            onProgress("解压识别模型", -1f)
            extractSenseVoice(archive)
            archive.delete()
        }
    }

    private suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        val part = File(dest.path + ".part")
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败 HTTP ${resp.code}: $url")
            val body = resp.body ?: throw IOException("下载失败：空响应 $url")
            val total = body.contentLength()
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
        }
        if (!part.renameTo(dest)) throw IOException("无法保存 ${dest.name}")
    }

    private suspend fun extractSenseVoice(archive: File) {
        senseVoiceDir.mkdirs()
        var gotModel = false
        var gotTokens = false
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = tar.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                // 包里只有一个 onnx（int8 版），文件名在不同版本间可能变化，所以按扩展名匹配
                val target = when {
                    name.endsWith(".onnx") -> paths.senseVoiceModel.also { gotModel = true }
                    name == "tokens.txt" -> paths.tokens.also { gotTokens = true }
                    else -> null
                } ?: continue
                val tmp = File(target.path + ".tmp")
                tmp.outputStream().use { tar.copyTo(it) }
                tmp.renameTo(target)
            }
        }
        if (!gotModel || !gotTokens) throw IOException("模型包内容不完整")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
        private const val VAD_FILE = "silero_vad.onnx"
        private const val SENSE_VOICE_ARCHIVE = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2"
    }
}
