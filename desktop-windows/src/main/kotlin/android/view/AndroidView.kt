package android.view

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Handler
import java.awt.event.KeyEvent as AwtKeyEvent

open class View(val context: Context = android.content.GlobalDesktopApp) {
    var visibility: Int = VISIBLE
    open val width: Int = 1280
    open val height: Int = 720
    val resources: Resources get() = context.resources
    open var keepScreenOn: Boolean = false
    var isFocusable: Boolean = true
    var tag: Any? = null
    var parent: ViewGroup? = null
    open var layoutParams: ViewGroup.LayoutParams? = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    open fun <T : View> findViewById(id: Int): T? = null

    fun interface OnTouchListener {
        fun onTouch(v: View, event: MotionEvent): Boolean
    }

    fun interface OnKeyListener {
        fun onKey(v: View, keyCode: Int, event: KeyEvent): Boolean
    }

    fun interface OnFocusChangeListener {
        fun onFocusChange(v: View, hasFocus: Boolean)
    }

    fun interface OnLayoutChangeListener {
        fun onLayoutChange(
            v: View,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int,
        )
    }

    open fun addOnLayoutChangeListener(listener: OnLayoutChangeListener) {}
    open fun removeOnLayoutChangeListener(listener: OnLayoutChangeListener) {}

    open fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {}
    open fun setBackgroundColor(color: Int) {}
    open fun requestLayout() {}
    open fun invalidate() {}
    open fun requestFocus(): Boolean = true
    open fun canScrollVertically(direction: Int): Boolean = false
    open fun setOnTouchListener(l: OnTouchListener?) {}
    open fun setOnKeyListener(l: OnKeyListener?) {}
    open fun setOnFocusChangeListener(l: OnFocusChangeListener?) {}
    open fun post(action: Runnable): Boolean {
        Handler().post(action)
        return true
    }
    open fun postDelayed(action: Runnable, delayMillis: Long): Boolean {
        Handler().postDelayed(action, delayMillis)
        return true
    }
    open fun dispatchKeyEventPreIme(event: KeyEvent): Boolean = false
    open fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
    }
}

open class ViewGroup(context: Context = android.content.GlobalDesktopApp) : View(context) {
    private val children = mutableListOf<View>()
    val childCount: Int get() = children.size

    open fun addView(child: View) {
        children.add(child)
    }

    open fun addView(child: View, params: LayoutParams) {
        child.layoutParams = params
        children.add(child)
    }

    open fun removeView(view: View) {
        children.remove(view)
    }

    open fun removeAllViews() {
        children.clear()
    }

    fun getChildAt(index: Int): View? = children.getOrNull(index)

    open class LayoutParams(var width: Int, var height: Int) {
        companion object {
            const val MATCH_PARENT = -1
            const val WRAP_CONTENT = -2
        }
    }
}

class Surface(val isValid: Boolean = true)

class SurfaceHolder {
    val surface: Surface = Surface()
}

open class SurfaceView(context: Context = android.content.GlobalDesktopApp) : View(context) {
    val holder: SurfaceHolder = SurfaceHolder()
}

open class TextureView(context: Context = android.content.GlobalDesktopApp) : View(context) {
    var bitmap: Bitmap? = null
}

object PixelCopy {
    const val SUCCESS = 0

    fun interface OnPixelCopyFinishedListener {
        fun onPixelCopyFinished(copyResult: Int)
    }

    @JvmStatic
    fun request(
        source: SurfaceView,
        dest: Bitmap,
        listener: OnPixelCopyFinishedListener,
        listenerThread: Handler,
    ) {
        val currentFrame = com.aisub.player.desktop.DesktopPlayerEngine.latestFrameBitmap
        if (currentFrame != null) {
            val scaled = Bitmap.createScaledBitmap(currentFrame, dest.width, dest.height, true)
            val g = dest.bufferedImage.createGraphics()
            g.drawImage(scaled.bufferedImage, 0, 0, null)
            g.dispose()
            dest.cachedComposeBitmap = null
            listenerThread.post { listener.onPixelCopyFinished(SUCCESS) }
        } else {
            listenerThread.post { listener.onPixelCopyFinished(1) }
        }
    }
}

class Window {
    val decorView: View = View()
    var attributes: WindowManager.LayoutParams = WindowManager.LayoutParams()
    fun addFlags(flags: Int) {}
    fun clearFlags(flags: Int) {}
    fun setFlags(flags: Int, mask: Int) {}
    fun setLayout(width: Int, height: Int) {}
    fun setBackgroundDrawableResource(resId: Int) {}
    fun setDimAmount(amount: Float) {}
    fun setWindowAnimations(anim: Int) {}
}

interface WindowManager {
    class LayoutParams : ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT) {
        var screenBrightness: Float = BRIGHTNESS_OVERRIDE_NONE
        var layoutInDisplayCutoutMode: Int = 0
        var blurBehindRadius: Int = 0

        companion object {
            const val BRIGHTNESS_OVERRIDE_NONE = -1.0f
            const val FLAG_BLUR_BEHIND = 4
            const val FLAG_KEEP_SCREEN_ON = 128
            const val FLAG_NOT_TOUCHABLE = 16
            const val FLAG_NOT_FOCUSABLE = 8
            const val FLAG_LAYOUT_NO_LIMITS = 512
            const val FLAG_DIM_BEHIND = 2
            const val LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES = 1
        }
    }
}

class MotionEvent private constructor(
    val action: Int,
    val x: Float,
    val y: Float,
    val downTime: Long = 0L,
    val eventTime: Long = 0L,
) {
    var source: Int = 0
    var flags: Int = 0
    val isCanceled: Boolean get() = (flags and FLAG_CANCELED) != 0
    val actionMasked: Int get() = action
    fun recycle() {}

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val FLAG_CANCELED = 32
        const val FLAG_LONG_PRESS = 128

        @JvmStatic
        fun obtain(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float, metaState: Int): MotionEvent {
            return MotionEvent(action, x, y, downTime, eventTime)
        }
    }
}

object InputDevice {
    const val SOURCE_TOUCHSCREEN = 4098
}

class KeyEvent(
    val action: Int = ACTION_DOWN,
    val keyCode: Int = 0,
    val repeatCount: Int = 0,
    val downTime: Long = 0L,
    val eventTime: Long = 0L,
) {
    var flags: Int = 0
    val isCanceled: Boolean get() = (flags and FLAG_CANCELED) != 0

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val FLAG_CANCELED = 32
        const val FLAG_LONG_PRESS = 128

        // 映射为 AWT VK_* 常量，直接兼容 Compose Desktop nativeKeyEvent
        const val KEYCODE_BACK = AwtKeyEvent.VK_ESCAPE
        const val KEYCODE_MENU = AwtKeyEvent.VK_CONTEXT_MENU
        const val KEYCODE_SETTINGS = AwtKeyEvent.VK_F10
        const val KEYCODE_DPAD_UP = AwtKeyEvent.VK_UP
        const val KEYCODE_DPAD_DOWN = AwtKeyEvent.VK_DOWN
        const val KEYCODE_DPAD_LEFT = AwtKeyEvent.VK_LEFT
        const val KEYCODE_DPAD_RIGHT = AwtKeyEvent.VK_RIGHT
        const val KEYCODE_DPAD_CENTER = AwtKeyEvent.VK_ENTER
        const val KEYCODE_ENTER = AwtKeyEvent.VK_ENTER
        const val KEYCODE_NUMPAD_ENTER = AwtKeyEvent.VK_ENTER
        const val KEYCODE_SPACE = AwtKeyEvent.VK_SPACE
        const val KEYCODE_PAGE_UP = AwtKeyEvent.VK_PAGE_UP
        const val KEYCODE_PAGE_DOWN = AwtKeyEvent.VK_PAGE_DOWN
        const val KEYCODE_CHANNEL_UP = AwtKeyEvent.VK_PAGE_UP
        const val KEYCODE_CHANNEL_DOWN = AwtKeyEvent.VK_PAGE_DOWN
        const val KEYCODE_MEDIA_PLAY_PAUSE = AwtKeyEvent.VK_SPACE
        const val KEYCODE_MEDIA_NEXT = AwtKeyEvent.VK_CLOSE_BRACKET
        const val KEYCODE_MEDIA_PREVIOUS = AwtKeyEvent.VK_OPEN_BRACKET
        const val KEYCODE_MEDIA_FAST_FORWARD = AwtKeyEvent.VK_PERIOD
        const val KEYCODE_MEDIA_REWIND = AwtKeyEvent.VK_COMMA

        fun fromNative(native: Any?): KeyEvent {
            if (native is KeyEvent) return native
            if (native is AwtKeyEvent) {
                val act = if (native.id == AwtKeyEvent.KEY_RELEASED) ACTION_UP else ACTION_DOWN
                val now = System.currentTimeMillis()
                return KeyEvent(action = act, keyCode = native.keyCode, repeatCount = 0, downTime = now, eventTime = now)
            }
            return KeyEvent()
        }
    }
}
