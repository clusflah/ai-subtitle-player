package com.aisub.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aisub.player.AppBackground
import com.aisub.player.AppDarkMode

/**
 * 设计基线：品牌橙只用于主要操作、选中状态和分组标题前的短条；
 * 分区靠背景层次（页面浅底/轻渐变、卡片通透玻璃）区分，不叠加粗边框和重阴影。
 */
val Orange = Color(0xFFF28C00)

val LocalAppBackground = compositionLocalOf { AppBackground.AURORA_GRAY_WHITE_GRADIENT }
val LocalAppDarkMode = compositionLocalOf { AppDarkMode.SYSTEM }
val LocalLiquidGlassEnabled = compositionLocalOf { false }

/** 桌面端可注入实时监听的 Windows 系统深色状态；Android 端为 null 时直接使用系统 isSystemInDarkTheme() */
val LocalSystemDarkOverride = compositionLocalOf<Boolean?> { null }

@Composable
fun isAppSystemInDarkTheme(): Boolean = LocalSystemDarkOverride.current ?: isSystemInDarkTheme()

fun AppDarkMode.isDarkResolved(systemDark: Boolean): Boolean = when (this) {
    AppDarkMode.SYSTEM -> systemDark
    AppDarkMode.LIGHT -> false
    AppDarkMode.DARK -> true
}

@Composable
fun isAppDarkTheme(): Boolean = LocalAppDarkMode.current.isDarkResolved(isAppSystemInDarkTheme())

/**
 * 与每套 [AppBackground] 浅色 / 深色双态美学配套的液态玻璃、导航水滴、卡片通透度与边缘折射色板。
 */
@Immutable
data class AppVisualPalette(
    val isDark: Boolean,
    /** 液态玻璃默认基底色 */
    val glassBase: Color,
    /** 列表卡片、分组卡片（SurfaceCard / CollapsibleCard / SourceCard）通透染色 */
    val cardTint: Color,
    /** 搜索框、输入框通透容器底色 */
    val fieldContainerTint: Color,
    /** 顶栏右侧圆形液态玻璃按钮与功能胶囊底色 */
    val iconButtonTint: Color,
    /** 悬浮底栏、侧边导航栏、迷你播放条、网页工具栏基底色 */
    val dockTint: Color,
    /** 导航栏滑动水滴指示器色 */
    val dropletTint: Color,
    /** 导航栏与分段控制器选中态强调色（随背景色温美学调校） */
    val navActiveColor: Color,
    /** 导航栏未选中图标与文字色 */
    val navInactiveColor: Color,
    /** 分段控制器凹槽轨道底色、滑块底色与未选中文字色 */
    val segmentedTrackTint: Color,
    val segmentedThumbTint: Color,
    val segmentedInactiveText: Color,
    /** 液态玻璃边缘 Fresnel 折射中部双微光色 */
    val rimMidPrimary: Color,
    val rimMidSecondary: Color,
    /** 详情页横幅大图底部渐隐过渡色（无缝融入当前页面底色） */
    val backdropFadeColor: Color,
    /** Telegram 聊天气泡通透玻璃色 */
    val chatBubbleTint: Color,
)

// ---------------- 浅色视觉色板（8 套） ----------------

private val VeryLightVisualPalette = AppVisualPalette(
    isDark = false,
    glassBase = Color(0xFFFAFAFC),
    cardTint = Color(0xE0FFFFFF),
    fieldContainerTint = Color(0xC8FFFFFF),
    iconButtonTint = Color(0xEEFFFFFF),
    dockTint = Color(0xE2F9FAFC),
    dropletTint = Color(0xF0E6EEF8),
    navActiveColor = Color(0xFF007AFF),
    navInactiveColor = Color(0xFF1C1C1E),
    segmentedTrackTint = Color(0xFFE8EAEE),
    segmentedThumbTint = Color(0xFAFFFFFF),
    segmentedInactiveText = Color(0xFF3C3C43),
    rimMidPrimary = Color(0xFFA8D8FF),
    rimMidSecondary = Color(0xFFDCC4FF),
    backdropFadeColor = Color(0xFFF7F7F7),
    chatBubbleTint = Color(0xE6FFFFFF),
)

private val NeutralGrayVisualPalette = AppVisualPalette(
    isDark = false,
    glassBase = Color(0xFFF9FAFC),
    cardTint = Color(0xD8FFFFFF),
    fieldContainerTint = Color(0xBDFFFFFF),
    iconButtonTint = Color(0xEBFFFFFF),
    dockTint = Color(0xE0F7F9FC),
    dropletTint = Color(0xF2E5ECF6),
    navActiveColor = Color(0xFF1A6EE8),
    navInactiveColor = Color(0xFF282D35),
    segmentedTrackTint = Color(0xFFE4E8EF),
    segmentedThumbTint = Color(0xFAFFFFFF),
    segmentedInactiveText = Color(0xFF434954),
    rimMidPrimary = Color(0xFFB0D6FF),
    rimMidSecondary = Color(0xFFD9C8FF),
    backdropFadeColor = Color(0xFFF2F3F5),
    chatBubbleTint = Color(0xE3FFFFFF),
)

private val WarmBeigeVisualPalette = AppVisualPalette(
    isDark = false,
    glassBase = Color(0xFFFFFEFB),
    cardTint = Color(0xDCFFFEFC),
    fieldContainerTint = Color(0xC4FFFEFC),
    iconButtonTint = Color(0xEDFFFDF9),
    dockTint = Color(0xE3FAF7F1),
    dropletTint = Color(0xF2EFE7DA),
    navActiveColor = Color(0xFFB86214),
    navInactiveColor = Color(0xFF363029),
    segmentedTrackTint = Color(0xFFEAE4DB),
    segmentedThumbTint = Color(0xFAFFFEFC),
    segmentedInactiveText = Color(0xFF524A40),
    rimMidPrimary = Color(0xFFFFDFB8),
    rimMidSecondary = Color(0xFFF5CFC4),
    backdropFadeColor = Color(0xFFF5F3EF),
    chatBubbleTint = Color(0xE6FFFEFB),
)

private val PaleCyanVisualPalette = AppVisualPalette(
    isDark = false,
    glassBase = Color(0xFFF9FCFB),
    cardTint = Color(0xD8FCFEFD),
    fieldContainerTint = Color(0xBEFCFEFD),
    iconButtonTint = Color(0xEBFAFEFC),
    dockTint = Color(0xE0F4F9F7),
    dropletTint = Color(0xF2DCECE5),
    navActiveColor = Color(0xFF157A6C),
    navInactiveColor = Color(0xFF25322E),
    segmentedTrackTint = Color(0xFFDFE8E4),
    segmentedThumbTint = Color(0xFAFCFEFD),
    segmentedInactiveText = Color(0xFF42524C),
    rimMidPrimary = Color(0xFFA6E8D5),
    rimMidSecondary = Color(0xFFBBDDF8),
    backdropFadeColor = Color(0xFFEEF2F0),
    chatBubbleTint = Color(0xE3FCFEFD),
)

private val MintGradientVisualPalette = AppVisualPalette(
    isDark = false,
    glassBase = Color(0xFFF7FCFA),
    cardTint = Color(0xC9FFFFFF),
    fieldContainerTint = Color(0xB5FFFFFF),
    iconButtonTint = Color(0xE6FAFFFD),
    dockTint = Color(0xDCF1F8F5),
    dropletTint = Color(0xF0D7EFE4),
    navActiveColor = Color(0xFF117D67),
    navInactiveColor = Color(0xFF22312C),
    segmentedTrackTint = Color(0xFFD5E6DF),
    segmentedThumbTint = Color(0xFAFCFFFD),
    segmentedInactiveText = Color(0xFF3E524B),
    rimMidPrimary = Color(0xFF9BE8CE),
    rimMidSecondary = Color(0xFFAED8FA),
    backdropFadeColor = Color(0xFFEDF4F0),
    chatBubbleTint = Color(0xDEFFFFFF),
)

private val GlacierGradientVisualPalette = AppVisualPalette(
    isDark = false,
    glassBase = Color(0xFFFAF9FE),
    cardTint = Color(0xC9FFFFFF),
    fieldContainerTint = Color(0xB5FFFFFF),
    iconButtonTint = Color(0xE6FCFBFF),
    dockTint = Color(0xDCF4F5FC),
    dropletTint = Color(0xF0E2E6FA),
    navActiveColor = Color(0xFF4A58E2),
    navInactiveColor = Color(0xFF272938),
    segmentedTrackTint = Color(0xFFDDE0F0),
    segmentedThumbTint = Color(0xFAFDFAFF),
    segmentedInactiveText = Color(0xFF46495E),
    rimMidPrimary = Color(0xFFADCFFF),
    rimMidSecondary = Color(0xFFE2C2FF),
    backdropFadeColor = Color(0xFFF0EFF7),
    chatBubbleTint = Color(0xDEFFFFFF),
)

private val AuroraGradientVisualPalette = AppVisualPalette(
    isDark = false,
    glassBase = Color(0xFFFAFBFD),
    cardTint = Color(0xCCFFFFFF),
    fieldContainerTint = Color(0xB8FFFFFF),
    iconButtonTint = Color(0xE8FAFCFD),
    dockTint = Color(0xDEF4F6F9),
    dropletTint = Color(0xF0E2EAF2),
    navActiveColor = Color(0xFF2468C4),
    navInactiveColor = Color(0xFF252A32),
    segmentedTrackTint = Color(0xFFE1E6EC),
    segmentedThumbTint = Color(0xFAFFFFFF),
    segmentedInactiveText = Color(0xFF464D58),
    rimMidPrimary = Color(0xFFB4E4EA),
    rimMidSecondary = Color(0xFFD9CEF0),
    backdropFadeColor = Color(0xFFF2F2F5),
    chatBubbleTint = Color(0xE0FFFFFF),
)

private val ChampagneGradientVisualPalette = AppVisualPalette(
    isDark = false,
    glassBase = Color(0xFFFFFEFC),
    cardTint = Color(0xCCFFFEFD),
    fieldContainerTint = Color(0xB8FFFEFD),
    iconButtonTint = Color(0xEAFFFDFA),
    dockTint = Color(0xDEF8F5F0),
    dropletTint = Color(0xF0EDE6DC),
    navActiveColor = Color(0xFFA66321),
    navInactiveColor = Color(0xFF332E29),
    segmentedTrackTint = Color(0xFFE7E2DA),
    segmentedThumbTint = Color(0xFAFFFEFC),
    segmentedInactiveText = Color(0xFF524B43),
    rimMidPrimary = Color(0xFFF7DEC0),
    rimMidSecondary = Color(0xFFDCE2EE),
    backdropFadeColor = Color(0xFFF5F3F0),
    chatBubbleTint = Color(0xE0FFFEFC),
)

// ---------------- 深色视觉色板（8 套对应夜间色温） ----------------

private val DarkVeryLightVisualPalette = AppVisualPalette(
    isDark = true,
    glassBase = Color(0xFF1A2029),
    cardTint = Color(0xC71B2029),
    fieldContainerTint = Color(0xA61E242E),
    iconButtonTint = Color(0xCC222936),
    dockTint = Color(0xBF181E28),
    dropletTint = Color(0xE02D394E),
    navActiveColor = Color(0xFF4DA3FF),
    navInactiveColor = Color(0xFFD5DCE6),
    segmentedTrackTint = Color(0xCC1A202B),
    segmentedThumbTint = Color(0xEC303D54),
    segmentedInactiveText = Color(0xFFB0BAC8),
    rimMidPrimary = Color(0xFFA8D8FF),
    rimMidSecondary = Color(0xFFDCC4FF),
    backdropFadeColor = Color(0xFF121212),
    chatBubbleTint = Color(0xCC212734),
)

private val DarkNeutralGrayVisualPalette = DarkVeryLightVisualPalette.copy(
    glassBase = Color(0xFF1B212C),
    cardTint = Color(0xC91D222C),
    fieldContainerTint = Color(0xAB202631),
    iconButtonTint = Color(0xCF242B39),
    dockTint = Color(0xC219202B),
    dropletTint = Color(0xE22D3B52),
    navActiveColor = Color(0xFF5C9DFF),
    segmentedTrackTint = Color(0xCC1C222E),
    segmentedThumbTint = Color(0xEE313F57),
    backdropFadeColor = Color(0xFF14161A),
    chatBubbleTint = Color(0xCE222936),
)

private val DarkWarmBeigeVisualPalette = DarkVeryLightVisualPalette.copy(
    glassBase = Color(0xFF24201C),
    cardTint = Color(0xC924201C),
    fieldContainerTint = Color(0xAB28241F),
    iconButtonTint = Color(0xCF2C2722),
    dockTint = Color(0xC4201C18),
    dropletTint = Color(0xE33B332A),
    navActiveColor = Color(0xFFE89A48),
    navInactiveColor = Color(0xFFE4DDD4),
    segmentedTrackTint = Color(0xCC24201B),
    segmentedThumbTint = Color(0xEE3D342B),
    segmentedInactiveText = Color(0xFFBEB5AA),
    rimMidPrimary = Color(0xFFFFD8AB),
    rimMidSecondary = Color(0xFFF0C4B8),
    backdropFadeColor = Color(0xFF181613),
    chatBubbleTint = Color(0xCE28231E),
)

private val DarkPaleCyanVisualPalette = DarkVeryLightVisualPalette.copy(
    glassBase = Color(0xFF192320),
    cardTint = Color(0xC91A2421),
    fieldContainerTint = Color(0xAB1E2926),
    iconButtonTint = Color(0xCF212E2A),
    dockTint = Color(0xC217201E),
    dropletTint = Color(0xE2283C37),
    navActiveColor = Color(0xFF45C2AF),
    navInactiveColor = Color(0xFFD3E2DD),
    segmentedTrackTint = Color(0xCC192320),
    segmentedThumbTint = Color(0xEE2B403A),
    segmentedInactiveText = Color(0xFFAABDB7),
    rimMidPrimary = Color(0xFF98E5D0),
    rimMidSecondary = Color(0xFFAAD6F5),
    backdropFadeColor = Color(0xFF131816),
    chatBubbleTint = Color(0xCE1E2926),
)

private val DarkMintGradientVisualPalette = DarkPaleCyanVisualPalette.copy(
    cardTint = Color(0xBD1B2622),
    fieldContainerTint = Color(0xA31F2B27),
    dockTint = Color(0xBD16211E),
    dropletTint = Color(0xE2273F38),
    navActiveColor = Color(0xFF42C9AE),
    rimMidPrimary = Color(0xFF8EE6C8),
    rimMidSecondary = Color(0xFFA2D2F8),
    backdropFadeColor = Color(0xFF131919),
    chatBubbleTint = Color(0xC41F2B27),
)

private val DarkGlacierGradientVisualPalette = DarkVeryLightVisualPalette.copy(
    glassBase = Color(0xFF1D2030),
    cardTint = Color(0xBD1E2130),
    fieldContainerTint = Color(0xA3222536),
    iconButtonTint = Color(0xCC26293D),
    dockTint = Color(0xBD1A1C2B),
    dropletTint = Color(0xE22E3350),
    navActiveColor = Color(0xFF8792FA),
    navInactiveColor = Color(0xFFD9DCF0),
    segmentedTrackTint = Color(0xCC1D1F30),
    segmentedThumbTint = Color(0xEE323757),
    segmentedInactiveText = Color(0xFFB3B7D0),
    rimMidPrimary = Color(0xFFA8CAFF),
    rimMidSecondary = Color(0xFFE0BEFF),
    backdropFadeColor = Color(0xFF171620),
    chatBubbleTint = Color(0xC4232638),
)

private val DarkAuroraGradientVisualPalette = DarkVeryLightVisualPalette.copy(
    glassBase = Color(0xFF1C2128),
    cardTint = Color(0xBD1D2229),
    fieldContainerTint = Color(0xA321272F),
    iconButtonTint = Color(0xCC252B35),
    dockTint = Color(0xBD181D24),
    dropletTint = Color(0xE22B3747),
    navActiveColor = Color(0xFF5CA6F8),
    navInactiveColor = Color(0xFFD7DEE8),
    segmentedTrackTint = Color(0xCC1B2028),
    segmentedThumbTint = Color(0xEE2F3B4D),
    segmentedInactiveText = Color(0xFFB2BAC7),
    rimMidPrimary = Color(0xFFA6E0E8),
    rimMidSecondary = Color(0xFFD4C6EE),
    backdropFadeColor = Color(0xFF16171A),
    chatBubbleTint = Color(0xC4222730),
)

private val DarkChampagneGradientVisualPalette = DarkWarmBeigeVisualPalette.copy(
    glassBase = Color(0xFF231F1C),
    cardTint = Color(0xBD231F1C),
    fieldContainerTint = Color(0xA3272320),
    iconButtonTint = Color(0xCC2B2724),
    dockTint = Color(0xBD1E1B18),
    dropletTint = Color(0xE238312A),
    navActiveColor = Color(0xFFE0A45C),
    rimMidPrimary = Color(0xFFF5D8B5),
    rimMidSecondary = Color(0xFFCBD4E6),
    backdropFadeColor = Color(0xFF171616),
    chatBubbleTint = Color(0xC4272320),
)

fun AppBackground.visualPalette(dark: Boolean): AppVisualPalette = if (dark) {
    when (this) {
        AppBackground.NEUTRAL_GRAY -> DarkNeutralGrayVisualPalette
        AppBackground.WARM_BEIGE_GRAY -> DarkWarmBeigeVisualPalette
        AppBackground.PALE_CYAN_GRAY -> DarkPaleCyanVisualPalette
        AppBackground.MINT_GREEN_GRADIENT -> DarkMintGradientVisualPalette
        AppBackground.GLACIER_BLUE_PURPLE_GRADIENT -> DarkGlacierGradientVisualPalette
        AppBackground.AURORA_GRAY_WHITE_GRADIENT -> DarkAuroraGradientVisualPalette
        AppBackground.WARM_CHAMPAGNE_GRADIENT -> DarkChampagneGradientVisualPalette
        AppBackground.VERY_LIGHT_GRAY -> DarkVeryLightVisualPalette
    }
} else {
    when (this) {
        AppBackground.NEUTRAL_GRAY -> NeutralGrayVisualPalette
        AppBackground.WARM_BEIGE_GRAY -> WarmBeigeVisualPalette
        AppBackground.PALE_CYAN_GRAY -> PaleCyanVisualPalette
        AppBackground.MINT_GREEN_GRADIENT -> MintGradientVisualPalette
        AppBackground.GLACIER_BLUE_PURPLE_GRADIENT -> GlacierGradientVisualPalette
        AppBackground.AURORA_GRAY_WHITE_GRADIENT -> AuroraGradientVisualPalette
        AppBackground.WARM_CHAMPAGNE_GRADIENT -> ChampagneGradientVisualPalette
        AppBackground.VERY_LIGHT_GRAY -> VeryLightVisualPalette
    }
}

/**
 * 读取当前上下文的视觉色板：
 * - 常规界面按当前配色方案与深色模式状态（跟随系统 / 浅色 / 深色）返回对应色板；
 * - 若处于播放器等局部强制深色画布（`background.luminance() < 0.5f` 且全局并非深色），返回中性暗色播放玻璃色板。
 */
@Composable
fun currentVisualPalette(): AppVisualPalette {
    val bg = LocalAppBackground.current
    val appDark = isAppDarkTheme()
    val schemeDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    if (schemeDark && !appDark) return DarkVeryLightVisualPalette
    return bg.visualPalette(dark = appDark || schemeDark)
}

private val Light = lightColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFF1DE),
    onPrimaryContainer = Color(0xFF5A3300),
    secondary = Color(0xFFB86A00),
    // 分组标题前的短条
    tertiary = Orange,
    secondaryContainer = Color(0xFFFFF1DE),
    onSecondaryContainer = Color(0xFF5A3300),
    background = Color(0xFFF7F7F7),
    onBackground = Color(0xFF242426),
    surface = Color(0xFFF7F7F7),
    onSurface = Color(0xFF242426),
    surfaceVariant = Color(0xFFECECEE),
    onSurfaceVariant = Color(0xFF5E5E63),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFFCFCFC),
    surfaceContainerHigh = Color(0xFFF0F0F2),
    surfaceContainerHighest = Color(0xFFE8E8EB),
    outline = Color(0xFF8A8A8F),
    outlineVariant = Color(0xFFE2E2E6),
    error = Color(0xFFD93025),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFA733),
    onPrimary = Color(0xFF3A2000),
    primaryContainer = Color(0xFF3D2A12),
    onPrimaryContainer = Color(0xFFFFDDB3),
    secondary = Color(0xFFFFB866),
    tertiary = Color(0xFFFFA733),
    secondaryContainer = Color(0xFF3D2A12),
    onSecondaryContainer = Color(0xFFFFDDB3),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF262B35),
    onSurfaceVariant = Color(0xFFB2BAC6),
    surfaceContainerLowest = Color(0xFF1B1F27),
    surfaceContainerLow = Color(0xFF1E222B),
    surfaceContainer = Color(0xFF212630),
    surfaceContainerHigh = Color(0xFF272D38),
    surfaceContainerHighest = Color(0xFF303744),
    outline = Color(0xFF858E9C),
    outlineVariant = Color(0xFF2F3642),
)

/** 每套方案在浅色 / 深色模式下的实际背景色（单元素为纯色，三元素为左上至右下斜向渐变） */
fun AppBackground.resolveColors(dark: Boolean): List<Color> = if (dark) {
    when (this) {
        AppBackground.NEUTRAL_GRAY -> listOf(Color(0xFF14161A))
        AppBackground.WARM_BEIGE_GRAY -> listOf(Color(0xFF181613))
        AppBackground.PALE_CYAN_GRAY -> listOf(Color(0xFF131816))
        AppBackground.VERY_LIGHT_GRAY -> listOf(Color(0xFF121212))
        AppBackground.MINT_GREEN_GRADIENT -> listOf(Color(0xFF111B17), Color(0xFF141918), Color(0xFF13191D))
        AppBackground.GLACIER_BLUE_PURPLE_GRADIENT -> listOf(Color(0xFF131722), Color(0xFF171620), Color(0xFF1B1521))
        AppBackground.AURORA_GRAY_WHITE_GRADIENT -> listOf(Color(0xFF13191B), Color(0xFF16171A), Color(0xFF19161F))
        AppBackground.WARM_CHAMPAGNE_GRADIENT -> listOf(Color(0xFF1B1713), Color(0xFF171615), Color(0xFF15161A))
    }
} else {
    when (this) {
        AppBackground.NEUTRAL_GRAY -> listOf(Color(0xFFF2F3F5))
        AppBackground.WARM_BEIGE_GRAY -> listOf(Color(0xFFF5F3EF))
        AppBackground.PALE_CYAN_GRAY -> listOf(Color(0xFFEEF2F0))
        AppBackground.VERY_LIGHT_GRAY -> listOf(Color(0xFFF7F7F7))
        AppBackground.MINT_GREEN_GRADIENT -> listOf(Color(0xFFE4F1EA), Color(0xFFF3F6F3), Color(0xFFE8EFF3))
        AppBackground.GLACIER_BLUE_PURPLE_GRADIENT -> listOf(Color(0xFFE8EDF8), Color(0xFFF4F3F8), Color(0xFFF1EBF4))
        AppBackground.AURORA_GRAY_WHITE_GRADIENT -> listOf(Color(0xFFE9F1F2), Color(0xFFF5F5F6), Color(0xFFF0EDF5))
        AppBackground.WARM_CHAMPAGNE_GRADIENT -> listOf(Color(0xFFF4EEE7), Color(0xFFFAF8F5), Color(0xFFEEEFF2))
    }
}

private fun colorSchemeFor(bg: AppBackground, dark: Boolean): ColorScheme {
    if (dark) {
        return when (bg) {
            AppBackground.NEUTRAL_GRAY -> Dark.copy(
                background = Color(0xFF14161A),
                surface = Color(0xFF14161A),
                surfaceContainerLowest = Color(0xFF1C2027),
                surfaceContainerLow = Color(0xFF1F242C),
                surfaceContainer = Color(0xFF232831),
                surfaceVariant = Color(0xFF282E38),
                surfaceContainerHigh = Color(0xFF2A303B),
                surfaceContainerHighest = Color(0xFF323946),
                outlineVariant = Color(0xFF303744),
            )
            AppBackground.WARM_BEIGE_GRAY -> Dark.copy(
                background = Color(0xFF181613),
                surface = Color(0xFF181613),
                onBackground = Color(0xFFEDE7DF),
                onSurface = Color(0xFFEDE7DF),
                surfaceContainerLowest = Color(0xFF221E1A),
                surfaceContainerLow = Color(0xFF26221D),
                surfaceContainer = Color(0xFF2A2520),
                surfaceVariant = Color(0xFF322C26),
                onSurfaceVariant = Color(0xFFC2B9AE),
                surfaceContainerHigh = Color(0xFF342E28),
                surfaceContainerHighest = Color(0xFF3E3730),
                outline = Color(0xFF968D82),
                outlineVariant = Color(0xFF3B342D),
            )
            AppBackground.PALE_CYAN_GRAY,
            AppBackground.MINT_GREEN_GRADIENT,
            -> Dark.copy(
                background = Color(0xFF131816),
                surface = Color(0xFF131816),
                onBackground = Color(0xFFE4EEEA),
                onSurface = Color(0xFFE4EEEA),
                surfaceContainerLowest = Color(0xFF1A2320),
                surfaceContainerLow = Color(0xFF1D2724),
                surfaceContainer = Color(0xFF212C28),
                surfaceVariant = Color(0xFF26332F),
                onSurfaceVariant = Color(0xFFADC0BA),
                surfaceContainerHigh = Color(0xFF283631),
                surfaceContainerHighest = Color(0xFF31413C),
                outline = Color(0xFF80968F),
                outlineVariant = Color(0xFF2E3D38),
            )
            AppBackground.GLACIER_BLUE_PURPLE_GRADIENT -> Dark.copy(
                background = Color(0xFF161620),
                surface = Color(0xFF161620),
                onBackground = Color(0xFFE8E9F4),
                onSurface = Color(0xFFE8E9F4),
                surfaceContainerLowest = Color(0xFF1E202E),
                surfaceContainerLow = Color(0xFF222433),
                surfaceContainer = Color(0xFF262839),
                surfaceVariant = Color(0xFF2C2F42),
                onSurfaceVariant = Color(0xFFB5B8CE),
                surfaceContainerHigh = Color(0xFF2E3145),
                surfaceContainerHighest = Color(0xFF373B52),
                outline = Color(0xFF888CA4),
                outlineVariant = Color(0xFF34384E),
            )
            AppBackground.AURORA_GRAY_WHITE_GRADIENT -> Dark.copy(
                background = Color(0xFF16171A),
                surface = Color(0xFF16171A),
                onBackground = Color(0xFFE6ECF2),
                onSurface = Color(0xFFE6ECF2),
                surfaceContainerLowest = Color(0xFF1D2228),
                surfaceContainerLow = Color(0xFF20262D),
                surfaceContainer = Color(0xFF242A32),
                surfaceVariant = Color(0xFF2A313A),
                onSurfaceVariant = Color(0xFFB4BDC9),
                surfaceContainerHigh = Color(0xFF2C333D),
                surfaceContainerHighest = Color(0xFF343C48),
                outline = Color(0xFF86909D),
                outlineVariant = Color(0xFF323945),
            )
            AppBackground.WARM_CHAMPAGNE_GRADIENT -> Dark.copy(
                background = Color(0xFF171615),
                surface = Color(0xFF171615),
                onBackground = Color(0xFFECE7E1),
                onSurface = Color(0xFFECE7E1),
                surfaceContainerLowest = Color(0xFF221F1C),
                surfaceContainerLow = Color(0xFF26221F),
                surfaceContainer = Color(0xFF2A2623),
                surfaceVariant = Color(0xFF312D29),
                onSurfaceVariant = Color(0xFFC0B8B0),
                surfaceContainerHigh = Color(0xFF332F2B),
                surfaceContainerHighest = Color(0xFF3D3833),
                outline = Color(0xFF948C84),
                outlineVariant = Color(0xFF3A3530),
            )
            AppBackground.VERY_LIGHT_GRAY -> Dark
        }
    }
    return when (bg) {
        AppBackground.NEUTRAL_GRAY -> Light.copy(
            background = Color(0xFFF2F3F5),
            surface = Color(0xFFF2F3F5),
            onBackground = Color(0xFF1F2329),
            onSurface = Color(0xFF1F2329),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFFCFDFF),
            surfaceContainer = Color(0xFFF9FAFC),
            surfaceVariant = Color(0xFFE6E9EF),
            onSurfaceVariant = Color(0xFF5A606B),
            surfaceContainerHigh = Color(0xFFECEEF2),
            surfaceContainerHighest = Color(0xFFE2E5EC),
            outline = Color(0xFF858C99),
            outlineVariant = Color(0xFFDCE0E8),
        )
        AppBackground.WARM_BEIGE_GRAY -> Light.copy(
            primaryContainer = Color(0xFFFCEDD8),
            onPrimaryContainer = Color(0xFF5E3504),
            secondaryContainer = Color(0xFFFCEDD8),
            onSecondaryContainer = Color(0xFF5E3504),
            background = Color(0xFFF5F3EF),
            surface = Color(0xFFF5F3EF),
            onBackground = Color(0xFF28231E),
            onSurface = Color(0xFF28231E),
            surfaceContainerLowest = Color(0xFFFFFEFC),
            surfaceContainerLow = Color(0xFFFCFBF8),
            surfaceContainer = Color(0xFFF9F7F3),
            surfaceVariant = Color(0xFFEBE6DE),
            onSurfaceVariant = Color(0xFF665E54),
            surfaceContainerHigh = Color(0xFFEFECE6),
            surfaceContainerHighest = Color(0xFFE6E1D8),
            outline = Color(0xFF8F867B),
            outlineVariant = Color(0xFFE2DDD3),
        )
        AppBackground.PALE_CYAN_GRAY -> Light.copy(
            primaryContainer = Color(0xFFFAEFDC),
            onPrimaryContainer = Color(0xFF583400),
            secondaryContainer = Color(0xFFFAEFDC),
            onSecondaryContainer = Color(0xFF583400),
            background = Color(0xFFEEF2F0),
            surface = Color(0xFFEEF2F0),
            onBackground = Color(0xFF1E2623),
            onSurface = Color(0xFF1E2623),
            surfaceContainerLowest = Color(0xFFFCFEFD),
            surfaceContainerLow = Color(0xFFF9FCFA),
            surfaceContainer = Color(0xFFF5F9F7),
            surfaceVariant = Color(0xFFE2EAE6),
            onSurfaceVariant = Color(0xFF566660),
            surfaceContainerHigh = Color(0xFFE6ECE9),
            surfaceContainerHighest = Color(0xFFDCE5E1),
            outline = Color(0xFF7F9089),
            outlineVariant = Color(0xFFD6E0DC),
        )
        AppBackground.MINT_GREEN_GRADIENT -> Light.copy(
            primaryContainer = Color(0xFFFAEFDC),
            onPrimaryContainer = Color(0xFF583400),
            secondaryContainer = Color(0xFFFAEFDC),
            onSecondaryContainer = Color(0xFF583400),
            background = Color(0xFFECF4F0),
            surface = Color(0xFFECF4F0),
            onBackground = Color(0xFF1C2723),
            onSurface = Color(0xFF1C2723),
            surfaceContainerLowest = Color(0xFFFCFFFD),
            surfaceContainerLow = Color(0xFFF8FCFA),
            surfaceContainer = Color(0xFFF3F9F6),
            surfaceVariant = Color(0xFFDFECE6),
            onSurfaceVariant = Color(0xFF53665E),
            surfaceContainerHigh = Color(0xFFE3EFEA),
            surfaceContainerHighest = Color(0xFFD8E8E1),
            outline = Color(0xFF7B9087),
            outlineVariant = Color(0xFFD3E3DC),
        )
        AppBackground.GLACIER_BLUE_PURPLE_GRADIENT -> Light.copy(
            primaryContainer = Color(0xFFFCEDDF),
            onPrimaryContainer = Color(0xFF5C3408),
            secondaryContainer = Color(0xFFFCEDDF),
            onSecondaryContainer = Color(0xFF5C3408),
            background = Color(0xFFEEEFF7),
            surface = Color(0xFFEEEFF7),
            onBackground = Color(0xFF20222E),
            onSurface = Color(0xFF20222E),
            surfaceContainerLowest = Color(0xFFFDFAFF),
            surfaceContainerLow = Color(0xFFFAF8FE),
            surfaceContainer = Color(0xFFF5F4FB),
            surfaceVariant = Color(0xFFE3E5F2),
            onSurfaceVariant = Color(0xFF5B5E72),
            surfaceContainerHigh = Color(0xFFE6E7F4),
            surfaceContainerHighest = Color(0xFFDCE0F0),
            outline = Color(0xFF83879C),
            outlineVariant = Color(0xFFD8DBEC),
        )
        AppBackground.AURORA_GRAY_WHITE_GRADIENT -> Light.copy(
            primaryContainer = Color(0xFFFCEDDD),
            onPrimaryContainer = Color(0xFF5A3404),
            secondaryContainer = Color(0xFFFCEDDD),
            onSecondaryContainer = Color(0xFF5A3404),
            background = Color(0xFFF2F3F5),
            surface = Color(0xFFF2F3F5),
            onBackground = Color(0xFF20242B),
            onSurface = Color(0xFF20242B),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFFCFDFF),
            surfaceContainer = Color(0xFFF7F8FA),
            surfaceVariant = Color(0xFFE5E9EF),
            onSurfaceVariant = Color(0xFF5B626E),
            surfaceContainerHigh = Color(0xFFEAEEF3),
            surfaceContainerHighest = Color(0xFFE0E5EC),
            outline = Color(0xFF848C98),
            outlineVariant = Color(0xFFDCE1E8),
        )
        AppBackground.WARM_CHAMPAGNE_GRADIENT -> Light.copy(
            primaryContainer = Color(0xFFFCEDD9),
            onPrimaryContainer = Color(0xFF5D3505),
            secondaryContainer = Color(0xFFFCEDD9),
            onSecondaryContainer = Color(0xFF5D3505),
            background = Color(0xFFF5F3F0),
            surface = Color(0xFFF5F3F0),
            onBackground = Color(0xFF27231F),
            onSurface = Color(0xFF27231F),
            surfaceContainerLowest = Color(0xFFFFFEFC),
            surfaceContainerLow = Color(0xFFFCFAF7),
            surfaceContainer = Color(0xFFF8F6F2),
            surfaceVariant = Color(0xFFEAE5DE),
            onSurfaceVariant = Color(0xFF645D55),
            surfaceContainerHigh = Color(0xFFEEEAE4),
            surfaceContainerHighest = Color(0xFFE4DFD7),
            outline = Color(0xFF8C857C),
            outlineVariant = Color(0xFFE0DCD4),
        )
        AppBackground.VERY_LIGHT_GRAY -> Light
    }
}

/**
 * 绘制应用背景：根据当前配色方案与深色模式状态自动绘制对应纯色或左上至右下三色渐变。
 */
fun Modifier.appMainBackground(): Modifier = composed {
    val bg = LocalAppBackground.current
    val dark = isAppDarkTheme()
    this.drawWithCache {
        val colors = bg.resolveColors(dark)
        val brush = if (colors.size > 1) {
            Brush.linearGradient(
                colors = colors,
                start = Offset.Zero,
                end = Offset(size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f)),
            )
        } else {
            null
        }
        val solidColor = colors.first()
        onDrawBehind {
            if (brush != null) {
                drawRect(brush = brush)
            } else {
                drawRect(color = solidColor)
            }
        }
    }
}

/** 圆角：输入框、按钮 12dp，卡片 20dp，比默认的更圆润 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 页面左右边距 */
val PageGutter = 20.dp

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.2.sp),
    )
}

@Composable
fun AppTheme(
    background: AppBackground = LocalAppBackground.current,
    darkMode: AppDarkMode = LocalAppDarkMode.current,
    liquidGlass: Boolean = LocalLiquidGlassEnabled.current,
    content: @Composable () -> Unit,
) {
    val systemDark = isAppSystemInDarkTheme()
    val dark = darkMode.isDarkResolved(systemDark)
    CompositionLocalProvider(
        LocalAppBackground provides background,
        LocalAppDarkMode provides darkMode,
        LocalLiquidGlassEnabled provides liquidGlass,
    ) {
        MaterialTheme(
            colorScheme = colorSchemeFor(background, dark),
            shapes = AppShapes,
            typography = AppTypography,
        ) { TvRipple(content) }
    }
}

/**
 * 主界面背景方案选择器：
 * 顶部提供深色模式切换（跟随系统 / 浅色 / 深色），下方列出全部配色方案并实时联动预览当前浅色或深色形态。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppBackgroundPicker(
    selected: AppBackground,
    darkMode: AppDarkMode,
    onSelect: (AppBackground) -> Unit,
    onDarkModeChange: (AppDarkMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val systemDark = isAppSystemInDarkTheme()
    val resolvedDark = darkMode.isDarkResolved(systemDark)
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "深色模式",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (darkMode == AppDarkMode.SYSTEM) {
                        "当前系统：${if (systemDark) "深色" else "浅色"}"
                    } else {
                        "已固定为${darkMode.label}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            LiquidGlassSegmentedControl(
                options = AppDarkMode.entries.map { it.label },
                selectedIndex = darkMode.ordinal,
                onSelect = { idx -> onDarkModeChange(AppDarkMode.entries[idx]) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        CardDivider(inset = 16.dp)
        AppBackground.entries.forEachIndexed { index, bg ->
            if (index > 0) CardDivider(inset = 82.dp)
            val isSelected = bg == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusRing(MaterialTheme.shapes.small)
                    .clickable { onSelect(bg) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackgroundSwatch(bg = bg, dark = resolvedDark, selected = isSelected)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = bg.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = bg.colorCode(resolvedDark),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                                    },
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = bg.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                RadioButton(selected = isSelected, onClick = null)
            }
        }
    }
}

@Composable
private fun BackgroundSwatch(bg: AppBackground, dark: Boolean, selected: Boolean) {
    val swatchShape = RoundedCornerShape(10.dp)
    val colors = bg.resolveColors(dark)
    val swatchPalette = bg.visualPalette(dark = dark)
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (selected) 2.dp else 1.dp
    Box(
        modifier = Modifier
            .size(width = 54.dp, height = 40.dp)
            .clip(swatchShape)
            .drawWithCache {
                val brush = if (colors.size > 1) {
                    Brush.linearGradient(
                        colors = colors,
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    )
                } else {
                    null
                }
                val single = colors.first()
                onDrawBehind {
                    if (brush != null) drawRect(brush = brush) else drawRect(color = single)
                }
            }
            .border(borderWidth, borderColor, swatchShape)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier
                    .size(width = 34.dp, height = 14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(swatchPalette.cardTint)
                    .border(
                        0.5.dp,
                        if (swatchPalette.isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.07f),
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 2.5.dp, height = 7.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Orange),
                )
                Box(
                    Modifier
                        .height(3.5.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (swatchPalette.isDark) Color.White.copy(alpha = 0.50f)
                            else Color.Black.copy(alpha = 0.22f),
                        ),
                )
            }
            Row(
                modifier = Modifier
                    .size(width = 28.dp, height = 8.dp)
                    .clip(CircleShape)
                    .background(swatchPalette.dockTint)
                    .padding(horizontal = 2.dp, vertical = 1.5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(width = 10.dp, height = 5.dp)
                        .clip(CircleShape)
                        .background(swatchPalette.navActiveColor.copy(alpha = 0.88f)),
                )
            }
        }
    }
}
