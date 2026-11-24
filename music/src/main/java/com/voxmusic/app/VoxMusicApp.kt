package com.voxmusic.app

import android.app.Application
import com.voxmusic.app.net.AppProxy

class VoxMusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        AppProxy.install(this)
        val settings = SettingsRepository(this).load()
        AppProxy.apply(this, settings.proxyType, settings.proxyHost, settings.proxyPort, settings.proxyBypassCn)
    }

    companion object {
        lateinit var instance: VoxMusicApp
            private set
    }
}
