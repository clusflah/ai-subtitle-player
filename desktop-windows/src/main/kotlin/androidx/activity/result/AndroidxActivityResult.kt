package androidx.activity.result

import android.app.PendingIntent
import android.content.Intent

class ActivityResult(val resultCode: Int, val data: Intent? = null)

class IntentSenderRequest private constructor() {
    class Builder(pendingIntent: Any) {
        fun build(): IntentSenderRequest = IntentSenderRequest()
    }
}
