package androidx.lifecycle.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.DesktopLifecycleOwner
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

val LocalLifecycleOwner = staticCompositionLocalOf<LifecycleOwner> {
    DesktopLifecycleOwner
}

@Composable
fun <T> StateFlow<T>.collectAsStateWithLifecycle(): State<T> = collectAsState()

@Composable
fun <T> Flow<T>.collectAsStateWithLifecycle(initialValue: T): State<T> = collectAsState(initial = initialValue)
