package com.aisub.player.telegram

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 边下边播 Telegram 的视频（tg://msg/<会话>/<消息>）：让 TDLib 从播放器要读的位置开始下载，
 * 下好的部分从本地文件读。拖动进度时播放器会关掉再从新位置打开，这里就从新位置接着下。
 */
@UnstableApi
class TgDataSource : BaseDataSource(true) {
    private var uri: Uri? = null
    private var fileId = 0
    private var size = 0L
    private var position = 0L
    private var remaining = 0L
    /** 从 [position] 起已经下好、能直接读的字节数 */
    private var ready = 0L
    private var raf: RandomAccessFile? = null
    private var rafPath = ""

    @Volatile
    private var latest: TdApi.File? = null
    private val lock = Object()
    private val listener: (TdApi.File) -> Unit = { f ->
        if (f.id == fileId) {
            latest = f
            synchronized(lock) { lock.notifyAll() }
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val (chatId, messageId) = Telegram.parse(dataSpec.uri.toString()) ?: throw IOException("不是 Telegram 视频地址")
        val file = runBlocking {
            if (!Telegram.awaitReady()) throw IOException("Telegram 还没登录")
            Telegram.messageFile(chatId, messageId)
        }
        fileId = file.id
        latest = file
        size = if (file.size > 0) file.size else file.expectedSize
        position = dataSpec.position
        if (size > 0 && position > size) throw IOException("位置超出文件大小")
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else if (size > 0) size - position else C.LENGTH_UNSET.toLong()
        ready = 0
        Telegram.fileListeners.add(listener)
        download(position)
        transferStarted(dataSpec)
        return remaining
    }

    /** 让 TDLib 从这个位置开始下（一直下到结尾） */
    private fun download(from: Long) {
        runBlocking { runCatching { Telegram.send(TdApi.DownloadFile(fileId, 32, from, 0, false)) }.getOrNull() }?.let { latest = it }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var n: Int
        while (true) {
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            if (ready <= 0) waitForData()
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val want = minOf(length.toLong(), ready, if (remaining > 0) remaining else Long.MAX_VALUE).toInt()
            val f = openFile()
            f.seek(position)
            n = f.read(buffer, offset, want)
            if (n > 0) break
            // TDLib 说下好了但文件里还没写进去：等一下再读
            ready = 0
            synchronized(lock) { lock.wait(200) }
        }
        position += n
        ready -= n
        if (remaining > 0) remaining -= n
        bytesTransferred(n)
        return n
    }

    /** 等到 [position] 起有数据可读；很久没进展就报错 */
    private fun waitForData() {
        var lastProgress = System.currentTimeMillis()
        var lastDownloaded = -1L
        while (true) {
            if (size > 0 && position >= size) {
                remaining = 0
                return
            }
            val available = runBlocking {
                runCatching { Telegram.send(TdApi.GetFileDownloadedPrefixSize(fileId, position)).size }.getOrDefault(0L)
            }
            if (available > 0) {
                ready = available
                return
            }
            val f = latest
            // 下载停了，或者正在下别的位置（另一个读的人把位置拉走了）：从这里重新开始
            if (f != null && (!f.local.isDownloadingActive || f.local.downloadOffset > position) && !f.local.isDownloadingCompleted) download(position)
            val downloaded = latest?.local?.downloadedSize ?: 0
            if (downloaded != lastDownloaded) {
                lastDownloaded = downloaded
                lastProgress = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastProgress > 60_000) {
                throw IOException("Telegram 视频一分钟没下载到数据")
            }
            synchronized(lock) { lock.wait(500) }
        }
    }

    private fun openFile(): RandomAccessFile {
        val path = latest?.local?.path.orEmpty()
        if (path.isEmpty()) throw IOException("Telegram 视频还没开始下载")
        raf?.let { if (rafPath == path) return it else it.close() }
        return RandomAccessFile(path, "r").also { raf = it; rafPath = path }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        Telegram.fileListeners.remove(listener)
        raf?.close()
        raf = null
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = TgDataSource()
    }
}
