package androidx.media3.ui

import android.content.Context
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player

object R {
    object id {
        const val exo_content_frame = 1001
    }
}

object AspectRatioFrameLayout {
    const val RESIZE_MODE_FIT = 0
    const val RESIZE_MODE_FIXED_WIDTH = 1
    const val RESIZE_MODE_FIXED_HEIGHT = 2
    const val RESIZE_MODE_FILL = 3
    const val RESIZE_MODE_ZOOM = 4
}

class SubtitleView(context: Context = android.content.GlobalDesktopApp) : ViewGroup(context) {
    val bottomPaddingFractionState = androidx.compose.runtime.mutableFloatStateOf(DEFAULT_BOTTOM_PADDING_FRACTION)
    val visibleState = androidx.compose.runtime.mutableStateOf(true)

    fun syncVisible(visible: Boolean) {
        if (visibleState.value != visible) {
            visibleState.value = visible
        }
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setBottomPaddingFraction(fraction: Float) {
        val safe = fraction.coerceIn(0f, 0.7f)
        if (bottomPaddingFractionState.floatValue != safe) {
            bottomPaddingFractionState.floatValue = safe
        }
    }

    companion object {
        const val DEFAULT_BOTTOM_PADDING_FRACTION = 0.08f
    }
}

class PlayerView(context: Context = android.content.GlobalDesktopApp) : ViewGroup(context) {
    var player: Player? = null
    var useController: Boolean = false
    var resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT
    val subtitleView: SubtitleView? = SubtitleView(context)
    val videoSurfaceView: View? = SurfaceView(context)

    fun setShutterBackgroundColor(color: Int) {}
}
