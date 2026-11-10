package androidx.core.view

import android.view.View
import android.view.Window

object WindowCompat {
    @JvmStatic
    fun getInsetsController(window: Window, view: View): WindowInsetsControllerCompat {
        return WindowInsetsControllerCompat()
    }
}

class WindowInsetsCompat {
    fun isVisible(typeMask: Int): Boolean = false

    object Type {
        @JvmStatic
        fun systemBars(): Int = 1
        @JvmStatic
        fun ime(): Int = 8
    }
}

class WindowInsetsControllerCompat {
    var systemBarsBehavior: Int = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    var isAppearanceLightStatusBars: Boolean = false
    var isAppearanceLightNavigationBars: Boolean = false
    fun hide(types: Int) {}
    fun show(types: Int) {}

    companion object {
        const val BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2
    }
}

object ViewCompat {
    @JvmStatic
    fun getRootWindowInsets(view: View): WindowInsetsCompat? = WindowInsetsCompat()
}
