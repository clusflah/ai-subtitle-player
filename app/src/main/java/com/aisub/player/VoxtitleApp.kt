package com.aisub.player

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import com.aisub.player.media.AppStore
import com.aisub.player.net.AppProxy
import com.aisub.player.remote.Endpoints
import com.aisub.player.remote.cloud.CloudRegistry

class VoxtitleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Android 6/7 上启用 Conscrypt 现代 TLS 1.3 引擎
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.N_MR1) {
            try {
                if (java.security.Security.getProvider(org.conscrypt.Conscrypt.newProvider().name) == null) {
                    java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
                }
            } catch (_: Throwable) {}
        }
        // 应用内代理：在任何网络客户端创建之前装好
        AppProxy.install(this)
        SettingsRepository(this).load().let { AppProxy.apply(this, it.proxyType, it.proxyHost, it.proxyPort) }
        // 网盘客户端刷新 token 后写回存储；播放器按 ID 找账号
        val store = AppStore(this)
        CloudRegistry.serverLookup = { id -> store.servers().firstOrNull { it.id == id } }
        CloudRegistry.serverSaver = { server -> store.updateServerCredentials(server) }
        // 连上 / 断开 WiFi 后重新判断走内网还是外网（API 24+ 用 registerDefaultNetworkCallback，API 23 用 registerNetworkCallback）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = Endpoints.invalidate()
                override fun onLost(network: Network) = Endpoints.invalidate()
            })
        } else {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val req = android.net.NetworkRequest.Builder().build()
            cm?.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = Endpoints.invalidate()
                override fun onLost(network: Network) = Endpoints.invalidate()
            })
        }
    }

    companion object {
        /** 设置里创建翻译服务时用（离线翻译要绑定另一个 App 的服务） */
        lateinit var instance: VoxtitleApp
            private set
    }
}
