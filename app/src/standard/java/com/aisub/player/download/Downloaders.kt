package com.aisub.player.download

import android.content.Context

/** 标准版不带 yt-dlp：没有链接下载功能。 */
object Downloaders {
    const val EDITION = "标准版"

    fun create(context: Context): LinkDownloader? = null
}
