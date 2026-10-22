package com.aisub.player

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import com.aisub.player.media.AppStore
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.cloud.CloudRegistry

class AiSubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 网盘客户端刷新 token 后写回存储；播放器按 ID 找账号
        val store = AppStore(this)
        CloudRegistry.serverLookup = { id -> store.servers().firstOrNull { it.id == id } }
        CloudRegistry.serverSaver = { server -> store.updateServerCredentials(server) }
        // 连上 / 断开 WiFi 后重新判断走内网还是外网
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = Endpoints.invalidate()
            override fun onLost(network: Network) = Endpoints.invalidate()
        })
    }
}
