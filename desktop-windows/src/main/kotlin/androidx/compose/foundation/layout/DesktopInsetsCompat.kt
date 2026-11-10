package androidx.compose.foundation.layout

import androidx.compose.runtime.Composable

@OptIn(ExperimentalLayoutApi::class)
val WindowInsets.Companion.isImeVisible: Boolean
    @Composable
    get() = false
