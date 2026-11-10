package android.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.util.Rational
import android.view.View
import android.view.Window
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

open class Application : Context() {
    open fun onCreate() {}
}

open class Activity : Context() {
    open var intent: Intent = Intent()
    var requestedOrientation: Int = -1
    var isFinishing: Boolean = false
    var isDestroyed: Boolean = false
    var isInPictureInPictureMode: Boolean = false
    val window: Window = Window()

    protected open fun onCreate(savedInstanceState: Bundle?) {}
    protected open fun onStart() {}
    protected open fun onResume() {}
    protected open fun onPause() {}
    protected open fun onStop() {}
    protected open fun onDestroy() {}
    protected open fun onNewIntent(intent: Intent) {}
    protected open fun onUserLeaveHint() {}
    open fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {}

    fun dispatchOnCreate(savedInstanceState: Bundle? = null) {
        onCreate(savedInstanceState)
    }

    fun dispatchOnNewIntent(newIntent: Intent) {
        this.intent = newIntent
        onNewIntent(newIntent)
    }

    fun dispatchOnStop() {
        onStop()
    }

    fun enterPictureInPictureMode(params: PictureInPictureParams): Boolean {
        isInPictureInPictureMode = true
        return true
    }

    fun finish() {
        isFinishing = true
    }

    companion object {
        const val RESULT_OK = -1
        const val RESULT_CANCELED = 0
    }
}

abstract class Service : Context() {
    open fun onCreate() {}
    open fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    open fun onDestroy() {}
    abstract fun onBind(intent: Intent?): IBinder?
    fun stopForeground(removeNotification: Int) {}
    fun stopSelf() {}

    companion object {
        const val START_STICKY = 1
        const val START_NOT_STICKY = 2
        const val STOP_FOREGROUND_REMOVE = 1
    }
}

class PendingIntent {
    val intentSender: Any = Any()
    companion object {
        const val FLAG_IMMUTABLE = 0x04000000
        const val FLAG_UPDATE_CURRENT = 0x08000000

        @JvmStatic
        fun getActivity(context: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent = PendingIntent()
    }
}

class RemoteAction(val actionIntent: PendingIntent = PendingIntent())

class RecoverableSecurityException(
    cause: Throwable = SecurityException(),
    val userMessage: CharSequence = "",
    val userAction: RemoteAction = RemoteAction(),
) : SecurityException(userMessage.toString(), cause)

class PictureInPictureParams {
    class Builder {
        fun setAspectRatio(aspectRatio: Rational?): Builder = this
        fun build(): PictureInPictureParams = PictureInPictureParams()
    }
}

class UiModeManager {
    val currentModeType: Int = Configuration.UI_MODE_TYPE_NORMAL
}

class Notification

class NotificationChannel(val id: String, val name: CharSequence, val importance: Int) {
    var description: String? = null
}

class NotificationManager {
    fun createNotificationChannel(channel: NotificationChannel) {}
    fun notify(id: Int, notification: Notification) {}
    fun cancel(id: Int) {}

    companion object {
        const val IMPORTANCE_DEFAULT = 3
        const val IMPORTANCE_LOW = 2
        const val IMPORTANCE_HIGH = 4
    }
}

class DownloadManager {
    class Request(val uri: Uri) {
        internal var title: CharSequence? = null
        internal var description: CharSequence? = null
        internal var destDirType: String = Environment.DIRECTORY_DOWNLOADS
        internal var destSubPath: String = "Voxtitle/download"
        internal val headers = LinkedHashMap<String, String>()

        fun setTitle(title: CharSequence?): Request = apply { this.title = title }
        fun setDescription(description: CharSequence?): Request = apply { this.description = description }
        fun setNotificationVisibility(visibility: Int): Request = this
        fun setDestinationInExternalPublicDir(dirType: String, subPath: String): Request = apply {
            destDirType = dirType
            destSubPath = subPath
        }
        fun addRequestHeader(header: String, value: String): Request = apply {
            headers[header] = value
        }

        companion object {
            const val VISIBILITY_VISIBLE_NOTIFY_COMPLETED = 1
        }
    }

    fun enqueue(request: Request): Long {
        val id = System.currentTimeMillis()
        executor.submit {
            runCatching {
                val pubDir = Environment.getExternalStoragePublicDirectory(request.destDirType)
                val target = File(pubDir, request.destSubPath)
                target.parentFile?.mkdirs()
                val conn = URL(request.uri.toString()).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.inputStream.use { ins ->
                    target.outputStream().use { out ->
                        ins.copyTo(out)
                    }
                }
                conn.disconnect()
            }
        }
        return id
    }

    companion object {
        const val ACTION_VIEW_DOWNLOADS = "android.intent.action.VIEW_DOWNLOADS"
        private val executor = Executors.newFixedThreadPool(3) { r ->
            Thread(r, "Voxtitle-DownloadManager").apply { isDaemon = true }
        }
    }
}

class AlertDialog {
    fun dismiss() {}

    fun interface OnClickListener {
        fun onClick(dialog: AlertDialog, which: Int)
    }

    class Builder(private val context: Context) {
        private var title: CharSequence = "提示"
        private var message: CharSequence? = null
        private var positiveText: CharSequence? = null
        private var positiveListener: OnClickListener? = null
        private var negativeText: CharSequence? = null
        private var negativeListener: OnClickListener? = null
        private var neutralText: CharSequence? = null
        private var neutralListener: OnClickListener? = null
        private var customView: View? = null

        fun setTitle(t: CharSequence?): Builder = apply { if (t != null) title = t }
        fun setMessage(m: CharSequence?): Builder = apply { message = m }
        fun setView(v: View?): Builder = apply { customView = v }
        fun setCancelable(c: Boolean): Builder = this

        fun setPositiveButton(text: CharSequence, listener: OnClickListener?): Builder = apply {
            positiveText = text
            positiveListener = listener
        }

        fun setNegativeButton(text: CharSequence, listener: OnClickListener?): Builder = apply {
            negativeText = text
            negativeListener = listener
        }

        fun setNeutralButton(text: CharSequence, listener: OnClickListener?): Builder = apply {
            neutralText = text
            neutralListener = listener
        }

        fun show(): AlertDialog {
            val dlg = AlertDialog()
            if (customView != null && message == null) {
                // 进度条弹窗直接后台进行，不阻塞主界面
                return dlg
            }
            SwingUtilities.invokeLater {
                val options = mutableListOf<String>()
                positiveText?.let { options.add(it.toString()) }
                neutralText?.let { options.add(it.toString()) }
                negativeText?.let { options.add(it.toString()) }
                if (options.isEmpty()) options.add("确定")
                val res = JOptionPane.showOptionDialog(
                    null,
                    message?.toString() ?: "",
                    title.toString(),
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.INFORMATION_MESSAGE,
                    null,
                    options.toTypedArray(),
                    options.firstOrNull(),
                )
                val chosen = options.getOrNull(res)
                when (chosen) {
                    positiveText?.toString() -> positiveListener?.onClick(dlg, -1)
                    neutralText?.toString() -> neutralListener?.onClick(dlg, -3)
                    negativeText?.toString() -> negativeListener?.onClick(dlg, -2)
                }
            }
            return dlg
        }
    }
}
