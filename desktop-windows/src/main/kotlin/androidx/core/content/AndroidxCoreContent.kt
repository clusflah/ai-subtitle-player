package androidx.core.content

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File

object ContextCompat {
    const val RECEIVER_NOT_EXPORTED = 4

    @JvmStatic
    fun checkSelfPermission(context: Context, permission: String): Int {
        return PackageManager.PERMISSION_GRANTED
    }

    @JvmStatic
    fun startForegroundService(context: Context, intent: Intent) {
        // Desktop no-op
    }

    @JvmStatic
    fun registerReceiver(
        context: Context,
        receiver: BroadcastReceiver?,
        filter: IntentFilter,
        flags: Int,
    ): Intent? = null
}

open class FileProvider {
    companion object {
        @JvmStatic
        fun getUriForFile(context: Context, authority: String, file: File): Uri {
            return Uri.fromFile(file)
        }
    }
}

object IntentCompat {
    @JvmStatic
    fun <T> getParcelableExtra(inIntent: Intent, name: String, clazz: Class<T>): T? {
        return inIntent.getParcelableExtra(name)
    }
}
