package androidx.core.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context

object NotificationCompat {
    class Builder(context: Context, channelId: String) {
        fun setSmallIcon(icon: Int): Builder = this
        fun setContentTitle(title: CharSequence?): Builder = this
        fun setContentText(text: CharSequence?): Builder = this
        fun setSubText(text: CharSequence?): Builder = this
        fun setProgress(max: Int, progress: Int, indeterminate: Boolean): Builder = this
        fun setOngoing(ongoing: Boolean): Builder = this
        fun setAutoCancel(autoCancel: Boolean): Builder = this
        fun setOnlyAlertOnce(onlyAlertOnce: Boolean): Builder = this
        fun setSilent(silent: Boolean): Builder = this
        fun setContentIntent(intent: PendingIntent?): Builder = this
        fun addAction(icon: Int, title: CharSequence?, intent: PendingIntent?): Builder = this
        fun build(): Notification = Notification()
    }
}

object ServiceCompat {
    @JvmStatic
    fun startForeground(service: Service, id: Int, notification: Notification, foregroundServiceType: Int) {}
}
