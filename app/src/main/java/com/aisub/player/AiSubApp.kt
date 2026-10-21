package com.aisub.player

import android.app.Application
import com.aisub.player.media.AppStore
import com.aisub.player.remote.cloud.CloudRegistry

class AiSubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 网盘客户端刷新 token 后写回存储；播放器按 ID 找账号
        val store = AppStore(this)
        CloudRegistry.serverLookup = { id -> store.servers().firstOrNull { it.id == id } }
        CloudRegistry.serverSaver = { server -> store.updateServerCredentials(server) }
    }
}
