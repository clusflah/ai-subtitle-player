package android.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Toast(private val text: String, private val duration: Int) {
    fun show() {
        println("[Toast] $text")
        _latestToast.value = ToastMessage(text, System.currentTimeMillis(), if (duration == LENGTH_LONG) 3500L else 2000L)
    }

    data class ToastMessage(val text: String, val timestamp: Long, val durationMs: Long)

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        private val _latestToast = MutableStateFlow<ToastMessage?>(null)
        val latestToast: StateFlow<ToastMessage?> = _latestToast.asStateFlow()

        val currentMessage: String?
            get() {
                val msg = _latestToast.value ?: return null
                if (System.currentTimeMillis() - msg.timestamp > msg.durationMs) return null
                return msg.text
            }

        @JvmStatic
        fun makeText(context: Context?, text: CharSequence?, duration: Int): Toast {
            return Toast(text?.toString().orEmpty(), duration)
        }
    }
}

open class LinearLayout(context: Context = android.content.GlobalDesktopApp) : ViewGroup(context) {
    var orientation: Int = HORIZONTAL

    companion object {
        const val HORIZONTAL = 0
        const val VERTICAL = 1
    }
}

open class FrameLayout(context: Context = android.content.GlobalDesktopApp) : ViewGroup(context)


open class TextView(context: Context = android.content.GlobalDesktopApp) : View(context) {
    var text: CharSequence = ""
}

open class ProgressBar(
    context: Context = android.content.GlobalDesktopApp,
    attrs: Any? = null,
    defStyleAttr: Int = 0,
) : View(context) {
    var max: Int = 100
    var progress: Int = 0
    var isIndeterminate: Boolean = false
}
