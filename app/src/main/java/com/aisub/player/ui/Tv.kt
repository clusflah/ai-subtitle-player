package com.aisub.player.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** 运行在电视上（Android TV / Google TV）：用遥控器操作，按 10 英尺观看距离排版。 */
val LocalTv = staticCompositionLocalOf { false }

/** 大屏横向布局：电视，或横屏的平板（最短边 ≥ 600dp）。手机始终是 false，布局不变。 */
val LocalWideLayout = staticCompositionLocalOf { false }

fun isTvDevice(context: Context): Boolean =
    context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

/**
 * 提供 LocalTv / LocalWideLayout。电视上字号整体放大一点，
 * 获得焦点的按钮、列表项用品牌橙色的明显底色（手机上焦点基本用不到，保持原样）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvideScreenClass(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val tv = remember { isTvDevice(context) }
    val config = LocalConfiguration.current
    val wide = tv || (config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.smallestScreenWidthDp >= 600)
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalTv provides tv,
        LocalWideLayout provides wide,
        LocalDensity provides if (tv) Density(density.density, density.fontScale * 1.1f) else density,
        content = content,
    )
}

/** 电视上让所有带水波纹的控件（按钮、菜单项、Surface 卡片）获得焦点时有明显的橙色底色。在 MaterialTheme 里面调用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TvRipple(content: @Composable () -> Unit) {
    if (!LocalTv.current) return content()
    CompositionLocalProvider(
        LocalRippleConfiguration provides RippleConfiguration(
            color = MaterialTheme.colorScheme.primary,
            rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.30f, hoveredAlpha = 0.08f, pressedAlpha = 0.24f),
        ),
        content = content,
    )
}

/**
 * 电视上获得焦点时画一圈橙色描边（可选放大一点），放在 clickable / Surface 的 modifier 前面。
 * 手机上什么都不做。
 */
fun Modifier.tvFocusRing(
    shape: Shape = RoundedCornerShape(12.dp),
    color: Color = Color.Unspecified,
    scale: Boolean = false,
): Modifier = composed {
    if (!LocalTv.current) return@composed Modifier
    var focused by remember { mutableStateOf(false) }
    val c = if (color.isSpecified) color else MaterialTheme.colorScheme.primary
    Modifier
        .onFocusChanged { focused = it.isFocused }
        .then(if (scale && focused) Modifier.graphicsLayer { scaleX = 1.05f; scaleY = 1.05f } else Modifier)
        .then(if (focused) Modifier.border(3.dp, c, shape) else Modifier)
}
