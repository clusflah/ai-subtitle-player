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

/**
 * 设计基线：品牌橙只用于主要操作、选中状态和分组标题前的短条；
 * 分区靠背景层次（页面浅底/轻渐变、卡片通透玻璃）区分，不叠加粗边框和重阴影。
 */
val Orange = Color(0xFFF28C00)

val LocalAppBackground = compositionLocalOf { AppBackground.SYSTEM }

/**
 * 与每套 [AppBackground] 美学配套的液态玻璃、导航水滴、卡片通透度与边缘折射色板。
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

private val DarkVisualPalette = AppVisualPalette(
    isDark = true,
    glassBase = Color(0xFF1A222D),
    cardTint = Color(0xC71B2029),
    fieldContainerTint = Color(0xA61E2430),
    iconButtonTint = Color(0xCC222A38),
    dockTint = Color(0xBF181F2C),
    dropletTint = Color(0xE02E3B52),
    navActiveColor = Color(0xFF4DA3FF),
    navInactiveColor = Color(0xFFD5DCE6),
    segmentedTrackTint = Color(0xCC1A212E),
    segmentedThumbTint = Color(0xEC32405A),
    segmentedInactiveText = Color(0xFFB0BAC9),
    rimMidPrimary = Color(0xFFA8D8FF),
    rimMidSecondary = Color(0xFFDCC4FF),
    backdropFadeColor = Color(0xFF121212),
    chatBubbleTint = Color(0xCC222938),
)

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

fun AppBackground.visualPalette(systemDark: Boolean, forceDark: Boolean = false): AppVisualPalette {
    if (forceDark || isDarkResolved(systemDark)) return DarkVisualPalette
    return when (this) {
        AppBackground.NEUTRAL_GRAY -> NeutralGrayVisualPalette
        AppBackground.WARM_BEIGE_GRAY -> WarmBeigeVisualPalette
        AppBackground.PALE_CYAN_GRAY -> PaleCyanVisualPalette
        AppBackground.MINT_GREEN_GRADIENT -> MintGradientVisualPalette
        AppBackground.GLACIER_BLUE_PURPLE_GRADIENT -> GlacierGradientVisualPalette
        AppBackground.VERY_LIGHT_GRAY,
        AppBackground.SYSTEM,
        AppBackground.DARK,
        -> VeryLightVisualPalette
    }
}

/**
 * 读取当前上下文的视觉色板：若处于播放器等深色局部主题（`background.luminance() < 0.5f`），自动切换深色玻璃色板。
 */
@Composable
fun currentVisualPalette(): AppVisualPalette {
    val bg = LocalAppBackground.current
    val systemDark = isSystemInDarkTheme()
    val schemeDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return bg.visualPalette(systemDark = systemDark, forceDark = schemeDark)
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

fun AppBackground.isDarkResolved(systemDark: Boolean): Boolean = when (this) {
    AppBackground.SYSTEM -> systemDark
    AppBackground.DARK -> true
    else -> false
}

val AppBackground.previewColors: List<Color>
    get() = when (this) {
        AppBackground.SYSTEM -> listOf(Color(0xFFF7F7F7), Color(0xFF1B1F27))
        AppBackground.NEUTRAL_GRAY -> listOf(Color(0xFFF2F3F5))
        AppBackground.WARM_BEIGE_GRAY -> listOf(Color(0xFFF5F3EF))
        AppBackground.PALE_CYAN_GRAY -> listOf(Color(0xFFEEF2F0))
        AppBackground.VERY_LIGHT_GRAY -> listOf(Color(0xFFF7F7F7))
        AppBackground.MINT_GREEN_GRADIENT -> listOf(Color(0xFFE4F1EA), Color(0xFFF3F6F3), Color(0xFFE8EFF3))
        AppBackground.GLACIER_BLUE_PURPLE_GRADIENT -> listOf(Color(0xFFE8EDF8), Color(0xFFF4F3F8), Color(0xFFF1EBF4))
        AppBackground.DARK -> listOf(Color(0xFF121212))
    }

private fun colorSchemeFor(bg: AppBackground, systemDark: Boolean): ColorScheme {
    if (bg.isDarkResolved(systemDark)) return Dark
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
        AppBackground.VERY_LIGHT_GRAY,
        AppBackground.SYSTEM,
        AppBackground.DARK,
        -> Light
    }
}

/**
 * 绘制应用背景（支持柔和中性灰、暖米灰、淡青灰、极浅灰等纯色方案，以及薄荷青绿、冰川蓝紫斜向三色渐变方案）。
 */
fun Modifier.appMainBackground(): Modifier = composed {
    val bg = LocalAppBackground.current
    val systemDark = isSystemInDarkTheme()
    val fallbackSolid = MaterialTheme.colorScheme.background
    this.drawWithCache {
        val gradientColors = when (bg) {
            AppBackground.MINT_GREEN_GRADIENT,
            AppBackground.GLACIER_BLUE_PURPLE_GRADIENT,
            -> bg.previewColors
            else -> null
        }
        val brush = if (gradientColors != null) {
            Brush.linearGradient(
                colors = gradientColors,
                start = Offset.Zero,
                end = Offset(size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f)),
            )
        } else {
            null
        }
        val solidColor = when (bg) {
            AppBackground.SYSTEM -> if (systemDark) Color(0xFF121212) else Color(0xFFF7F7F7)
            AppBackground.DARK -> Color(0xFF121212)
            AppBackground.NEUTRAL_GRAY -> Color(0xFFF2F3F5)
            AppBackground.WARM_BEIGE_GRAY -> Color(0xFFF5F3EF)
            AppBackground.PALE_CYAN_GRAY -> Color(0xFFEEF2F0)
            AppBackground.VERY_LIGHT_GRAY -> Color(0xFFF7F7F7)
            else -> fallbackSolid
        }
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
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalAppBackground provides background) {
        MaterialTheme(
            colorScheme = colorSchemeFor(background, systemDark),
            shapes = AppShapes,
            typography = AppTypography,
        ) { TvRipple(content) }
    }
}

/**
 * 主界面背景方案选择器：带真实色彩/三色斜向渐变缩略样张、迷你卡片与导航水滴层次预览、色值标注。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppBackgroundPicker(
    selected: AppBackground,
    onSelect: (AppBackground) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
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
                BackgroundSwatch(bg = bg, selected = isSelected)
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
                            text = bg.colorCode,
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
private fun BackgroundSwatch(bg: AppBackground, selected: Boolean) {
    val swatchShape = RoundedCornerShape(10.dp)
    val colors = bg.previewColors
    val systemDark = isSystemInDarkTheme()
    val swatchPalette = bg.visualPalette(systemDark = systemDark)
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
                    .border(0.5.dp, Color.Black.copy(alpha = if (swatchPalette.isDark) 0.22f else 0.07f), RoundedCornerShape(4.dp))
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
                            if (swatchPalette.isDark) Color.White.copy(alpha = 0.45f)
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
                        .background(swatchPalette.navActiveColor.copy(alpha = 0.85f)),
                )
            }
        }
    }
}
