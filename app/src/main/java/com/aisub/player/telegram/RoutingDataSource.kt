package com.aisub.player.telegram

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** 按地址分流：Telegram 的视频（tg://）交给 [TgDataSource]，其余交给原来的数据源 */
@UnstableApi
class RoutingDataSource(private val default: DataSource, private val telegram: DataSource) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        default.addTransferListener(transferListener)
        telegram.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (Telegram.isTelegram(dataSpec.uri.toString())) telegram else default
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(private val default: DataSource.Factory, private val telegram: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(default.createDataSource(), telegram.createDataSource())
    }
}
