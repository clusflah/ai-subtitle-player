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
 * 管理本地识别模型：按需从 sherpa-onnx 的 GitHub Release 下载，解压到 models/<模型 ID>/。
 * 只保留模型文件，测试音频等内容不落盘。
 */
class ModelManager(context: Context) {

    private val root = File(context.filesDir, "models")
    val vadFile = File(root, "silero_vad.onnx")
    val punctuationDir = File(root, "punct")

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun dirOf(model: LocalModel) = File(root, model.id)

    fun isInstalled(model: LocalModel): Boolean {
        val dir = dirOf(model)
        if (File(dir, DONE_MARKER).exists()) return true
        // 0.4.0 以前下载的 SenseVoice 没有完成标记
        return model.id == "sense-voice" && File(dir, "tokens.txt").exists() &&
            dir.listFiles()?.any { it.name.endsWith(".onnx") } == true
    }

    private fun punctuationReady() = File(punctuationDir, DONE_MARKER).exists()

    fun isReady(model: LocalModel) =
        vadFile.exists() && isInstalled(model) && (!model.needsPunctuation || punctuationReady())

    /** 已下载模型占用的空间（MB）。 */
    fun installedSizeMb(model: LocalModel): Long =
        dirOf(model).walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)

    fun delete(model: LocalModel) {
        dirOf(model).deleteRecursively()
    }

    /**
     * 确保 VAD、模型（以及需要时的标点模型）都已下载。
     * @param baseUrl 模型下载地址前缀，默认 GitHub；国内网络可以换成镜像
     * @param onProgress (说明文字, 0..1 进度；-1 表示不确定)
     */
    suspend fun ensure(model: LocalModel, baseUrl: String, onProgress: (String, Float) -> Unit) = withContext(Dispatchers.IO) {
        root.mkdirs()
        val asrBase = baseUrl.trimEnd('/') + "/"
        if (!vadFile.exists()) {
            download(asrBase + "silero_vad.onnx", vadFile) { onProgress("下载 VAD 模型", it) }
        }
        if (!isInstalled(model)) {
            install(asrBase + model.archive, dirOf(model), "${model.label}（约 ${model.sizeMb}MB）", onProgress)
        }
        if (model.needsPunctuation && !punctuationReady()) {
            // 标点模型在另一个 release（punctuation-models），与 asr-models 同级
            val punctBase = asrBase.trimEnd('/').substringBeforeLast('/') + "/punctuation-models/"
            install(punctBase + ModelCatalog.PUNCTUATION_ARCHIVE, punctuationDir, "标点模型", onProgress)
        }
    }

    private suspend fun install(url: String, dir: File, label: String, onProgress: (String, Float) -> Unit) {
        val archive = File(root, url.substringAfterLast('/'))
        if (!archive.exists()) download(url, archive) { onProgress("下载 $label", it) }
        onProgress("解压 $label", -1f)
        dir.deleteRecursively()
        extract(archive, dir)
        File(dir, DONE_MARKER).writeText("ok")
        archive.delete()
    }

    private suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        val part = File(dest.path + ".part")
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
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

    /** 解压 tar.bz2：去掉包内最外层目录，跳过测试音频和说明文件，保留子目录（如 Qwen3 的 tokenizer/）。 */
    private suspend fun extract(archive: File, dir: File) {
        dir.mkdirs()
        val canonicalDir = dir.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = tar.nextEntry ?: break
                if (entry.isDirectory) continue
                val relative = entry.name.substringAfter('/', entry.name)
                if (relative.startsWith("test_wavs") || relative.endsWith(".md") || relative.endsWith(".wav")) continue
                val target = File(dir, relative)
                if (!target.canonicalPath.startsWith(canonicalDir)) continue // 防止路径穿越
                target.parentFile?.mkdirs()
                target.outputStream().use { tar.copyTo(it) }
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
        private const val DONE_MARKER = ".complete"
    }
}
