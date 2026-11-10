package androidx.lifecycle.viewmodel.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.DesktopViewModelStore
import androidx.lifecycle.ViewModel

@Composable
inline fun <reified T : ViewModel> viewModel(): T {
    return remember { DesktopViewModelStore.getOrCreate(T::class.java) }
}
