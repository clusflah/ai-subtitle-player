package com.voxmusic.app.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 全局共享的 OkHttpClient（走 AppProxy，兼容局域网与 Android 6/7 TLS 1.3）。 */
val musicHttp: OkHttpClient by lazy {
    AppProxy.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}
