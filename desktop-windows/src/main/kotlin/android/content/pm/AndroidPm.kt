package android.content.pm

import android.content.Intent
import com.aisub.player.BuildConfig

class ApplicationInfo {
    var uid: Int = 1000
}

class PackageInfo {
    var packageName: String = BuildConfig.APPLICATION_ID
    var versionName: String = BuildConfig.VERSION_NAME
    var longVersionCode: Long = BuildConfig.VERSION_CODE.toLong()
    var firstInstallTime: Long = 0L
    var lastUpdateTime: Long = 0L
}

object ActivityInfo {
    const val SCREEN_ORIENTATION_UNSPECIFIED = -1
    const val SCREEN_ORIENTATION_LANDSCAPE = 0
    const val SCREEN_ORIENTATION_PORTRAIT = 1
    const val SCREEN_ORIENTATION_SENSOR_LANDSCAPE = 6
    const val SCREEN_ORIENTATION_SENSOR_PORTRAIT = 7
}

object ServiceInfo {
    const val FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK = 2
    const val FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION = 32
    const val FOREGROUND_SERVICE_TYPE_DATA_SYNC = 1
}

class PackageManager {
    fun getPackageInfo(packageName: String, flags: Int): PackageInfo {
        if (packageName != BuildConfig.APPLICATION_ID) {
            throw NameNotFoundException(packageName)
        }
        return PackageInfo()
    }

    fun getPackageArchiveInfo(archiveFilePath: String, flags: Int): PackageInfo? = PackageInfo()
    fun canRequestPackageInstalls(): Boolean = true
    fun hasSystemFeature(name: String): Boolean = false
    fun getLaunchIntentForPackage(packageName: String): Intent? =
        if (packageName == BuildConfig.APPLICATION_ID) Intent(Intent.ACTION_MAIN).putExtra("desktop_restart", true) else null
    fun getLeanbackLaunchIntentForPackage(packageName: String): Intent? = null

    class NameNotFoundException(msg: String = "") : Exception(msg)

    companion object {
        const val PERMISSION_GRANTED = 0
        const val PERMISSION_DENIED = -1
        const val FEATURE_LEANBACK = "android.software.leanback"
    }
}
