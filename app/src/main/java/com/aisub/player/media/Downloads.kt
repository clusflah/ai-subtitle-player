package com.aisub.player.media

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.aisub.player.remote.AuthRegistry
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.RemoteClient
import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.cloud.CloudRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把网络位置、网盘里的文件或整个文件夹下载到手机的 Download/AiSubPlayer 目录。
 * 交给系统下载管理器：后台下载、通知栏显示进度、断网自动重试。
 * 网盘直链和需要的请求头（Cookie、UA、鉴权）在加入下载前解析好。
 */
object Downloads {

    const val ROOT = "AiSubPlayer"

    /** 一个待下载的文件：remotePath 用于展示，relativeDir 是下载目录下的子目录。 */
    data class Job(val item: RemoteItem, val relativeDir: String)

    /** 文件夹下载前的统计，用于确认对话框。 */
    data class Plan(val jobs: List<Job>, val totalBytes: Long, val truncated: Boolean)

    /** 递归列出文件夹里的所有文件（最多 [MAX_FILES] 个，避免误点下载整个网盘）。 */
    suspend fun planFolder(client: RemoteClient, folder: RemoteItem): Plan {
        val jobs = ArrayList<Job>()
        var truncated = false
        suspend fun walk(path: String, dir: String) {
            for (item in client.list(path)) {
                if (jobs.size >= MAX_FILES) { truncated = true; return }
                if (item.isDir) walk(item.path, "$dir/${safe(item.name)}")
                else if (item.playUrl != null) jobs += Job(item, dir)
            }
        }
        walk(folder.path, safe(folder.name))
        return Plan(jobs, jobs.sumOf { it.item.size }, truncated)
    }

    /** 加入系统下载队列，返回成功加入的数量。 */
    suspend fun enqueue(context: Context, jobs: List<Job>): Int = withContext(Dispatchers.IO) {
        val dm = context.getSystemService(DownloadManager::class.java)
        var n = 0
        for (job in jobs) {
            val url = job.item.playUrl ?: continue
            val (realUrl, headers) = if (CloudRegistry.isCloud(url)) {
                val link = CloudRegistry.resolve(url)
                link.url to link.headers
            } else {
                Endpoints.rewrite(url).let { it to AuthRegistry.headersFor(it) }
            }
            val sub = listOf(ROOT, job.relativeDir).filter { it.isNotEmpty() }.joinToString("/")
            val request = DownloadManager.Request(Uri.parse(realUrl))
                .setTitle(job.item.name)
                .setDescription("AI 字幕播放器")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$sub/${safe(job.item.name)}")
            headers.forEach { (k, v) -> request.addRequestHeader(k, v) }
            dm.enqueue(request)
            n++
        }
        n
    }

    /** 文件名里不能有路径分隔符等特殊字符。 */
    private fun safe(name: String) = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "未命名" }

    const val MAX_FILES = 500
}
