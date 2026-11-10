package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.util.concurrent.CopyOnWriteArrayList

object DesktopBackDispatcher {
    internal class Entry(var enabled: Boolean, val onBack: () -> Unit)
    private val entries = CopyOnWriteArrayList<Entry>()

    internal fun register(entry: Entry) {
        entries.add(entry)
    }

    internal fun unregister(entry: Entry) {
        entries.remove(entry)
    }

    fun dispatchBack(): Boolean {
        val target = entries.lastOrNull { it.enabled } ?: return false
        target.onBack()
        return true
    }

    fun hasEnabledCallback(): Boolean = entries.any { it.enabled }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val currentOnBack = rememberUpdatedState(onBack)
    val entry = remember { DesktopBackDispatcher.Entry(enabled) { currentOnBack.value.invoke() } }
    entry.enabled = enabled
    DisposableEffect(Unit) {
        DesktopBackDispatcher.register(entry)
        onDispose { DesktopBackDispatcher.unregister(entry) }
    }
}

class ManagedActivityResultLauncher<I, O>(
    private val contract: ActivityResultContract<I, O>,
    private val onResult: (O) -> Unit,
) {
    fun launch(input: I) {
        contract.execute(input, onResult)
    }
}

@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ManagedActivityResultLauncher<I, O> {
    val currentOnResult = rememberUpdatedState(onResult)
    return remember(contract) {
        ManagedActivityResultLauncher(contract) { res -> currentOnResult.value(res) }
    }
}

fun ComponentActivity.setContent(content: @Composable () -> Unit) {
    this.desktopContent = content
}
