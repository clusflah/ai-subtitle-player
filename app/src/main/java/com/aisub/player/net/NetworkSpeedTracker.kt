package com.aisub.player.net

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.Locale

/**
 * 实时下载网速统计器（100ms 分桶、最近 1.5 秒滑动窗口），
 * 统一汇集 ExoPlayer HTTP、Telegram 流式下载及 VLC 直连拉流的字节吞吐，
 * 并自动排除本机回环代理流量（127.0.0.1 / localhost / ::1）。
 */
object NetworkSpeedTracker {
    private const val SPEED_BUCKET_MS = 100L
    private const val SPEED_WINDOW_BUCKETS = 15
    private const val SPEED_WINDOW_MS = 1_500L
    private const val SPEED_STALE_MS = 2_000L

    private val speedLock = Any()
    private val speedBuckets = LongArray(SPEED_WINDOW_BUCKETS)
    private var speedHeadBucketIdx: Long = -1L
    private var speedFirstSampleMs: Long = 0L
    private var speedLastSampleMs: Long = 0L

    fun recordNetworkBytes(bytes: Long, nowMs: Long = System.currentTimeMillis()) {
        if (bytes <= 0L) return
        val bucket = nowMs / SPEED_BUCKET_MS
        synchronized(speedLock) {
            if (speedHeadBucketIdx < 0L || nowMs - speedLastSampleMs > SPEED_STALE_MS) {
                speedBuckets.fill(0L)
                speedHeadBucketIdx = bucket
                speedFirstSampleMs = nowMs
            } else if (bucket > speedHeadBucketIdx) {
                val deltaBuckets = bucket - speedHeadBucketIdx
                if (deltaBuckets >= SPEED_WINDOW_BUCKETS.toLong()) {
                    speedBuckets.fill(0L)
                    speedFirstSampleMs = nowMs
                } else {
                    for (i in 1..deltaBuckets.toInt()) {
                        val bIdx = ((speedHeadBucketIdx + i) % SPEED_WINDOW_BUCKETS).toInt()
                        speedBuckets[bIdx] = 0L
                    }
                    val oldestValid = nowMs - SPEED_WINDOW_MS
                    if (speedFirstSampleMs < oldestValid) {
                        speedFirstSampleMs = oldestValid
                    }
                }
                speedHeadBucketIdx = bucket
            }
            val slot = (bucket % SPEED_WINDOW_BUCKETS).toInt().let { if (it < 0) it + SPEED_WINDOW_BUCKETS else it }
            speedBuckets[slot] += bytes
            speedLastSampleMs = nowMs
        }
    }

    fun resetNetworkSpeed() {
        synchronized(speedLock) {
            speedBuckets.fill(0L)
            speedHeadBucketIdx = -1L
            speedFirstSampleMs = 0L
            speedLastSampleMs = 0L
        }
    }

    fun currentDownloadSpeedBytesPerSec(nowMs: Long = System.currentTimeMillis()): Long {
        synchronized(speedLock) {
            if (speedHeadBucketIdx < 0L || nowMs - speedLastSampleMs > SPEED_STALE_MS) {
                return 0L
            }
            val currentBucket = nowMs / SPEED_BUCKET_MS
            val scanStart = maxOf(
                currentBucket - SPEED_WINDOW_BUCKETS + 1L,
                speedHeadBucketIdx - SPEED_WINDOW_BUCKETS + 1L,
            )
            val scanEnd = minOf(currentBucket, speedHeadBucketIdx)
            var totalBytes = 0L
            for (b in scanStart..scanEnd) {
                val slot = (b % SPEED_WINDOW_BUCKETS).toInt().let { if (it < 0) it + SPEED_WINDOW_BUCKETS else it }
                totalBytes += speedBuckets[slot]
            }
            if (totalBytes <= 0L) return 0L
            val elapsedMs = (nowMs - speedFirstSampleMs).coerceIn(250L, SPEED_WINDOW_MS)
            return totalBytes * 1_000L / elapsedMs
        }
    }

    fun formatNetworkSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 KB/s"
        val kbPerSec = bytesPerSec / 1024.0
        if (kbPerSec < 100.0) {
            return "%.1f KB/s".format(Locale.US, kbPerSec)
        }
        if (bytesPerSec < 1024L * 1024L) {
            return "${bytesPerSec / 1024L} KB/s"
        }
        val mbPerSec = bytesPerSec / (1024.0 * 1024.0)
        if (mbPerSec < 100.0) {
            return "%.1f MB/s".format(Locale.US, mbPerSec)
        }
        return "%.0f MB/s".format(Locale.US, mbPerSec)
    }

    fun isLoopbackHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]").orEmpty()
        return h == "localhost" || h == "127.0.0.1" || h == "::1" || h.startsWith("127.")
    }

    @UnstableApi
    val transferListener: TransferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (!isNetwork || bytesTransferred <= 0) return
            if (isLoopbackHost(dataSpec.uri.host)) return
            recordNetworkBytes(bytesTransferred.toLong())
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    }
}
