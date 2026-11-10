package androidx.activity

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.lifecycle.DesktopViewModelStore
import androidx.lifecycle.ViewModel

open class ComponentActivity : Activity() {
    var desktopContent: (@Composable () -> Unit)? = null
}

fun ComponentActivity.enableEdgeToEdge() {}

inline fun <reified VM : ViewModel> ComponentActivity.viewModels(): Lazy<VM> = lazy {
    DesktopViewModelStore.getOrCreate(VM::class.java)
}
