package androidx.compose.ui.platform

import android.content.Context
import android.content.GlobalDesktopApp
import android.content.res.Configuration
import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf

val LocalContext = staticCompositionLocalOf<Context> { GlobalDesktopApp }

val LocalConfiguration = staticCompositionLocalOf { Configuration() }

val LocalView = staticCompositionLocalOf { View(GlobalDesktopApp) }

val LocalLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner
