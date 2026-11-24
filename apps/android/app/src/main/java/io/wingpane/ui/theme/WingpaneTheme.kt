@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package io.wingpane.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.wingpane.R

/**
 * Approved Wingpane dark-mode-oled tokens from design-system/wingpane/tokens.json & MASTER.md.
 * No colors outside this table are permitted anywhere in the UI.
 */
@Immutable
data class WingpaneColors(
    val bg: Color = Color(0xFF000000),
    val surface1: Color = Color(0xFF0A0E27),
    val surface2: Color = Color(0xFF121212),
    val border: Color = Color(0xFF5A6380),
    val text: Color = Color(0xFFF2F4FF),
    val textMuted: Color = Color(0xFFA3AED0),
    val primary: Color = Color(0xFF7AA2FF),
    val onPrimary: Color = Color(0xFF000000),
    val success: Color = Color(0xFF3DDC97),
    val onSuccess: Color = Color(0xFF000000),
    val warning: Color = Color(0xFFFFB547),
    val onWarning: Color = Color(0xFF000000),
    val danger: Color = Color(0xFFFF8080),
    val onDanger: Color = Color(0xFF000000),
)

@Immutable
data class WingpaneSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
)

@Immutable
data class WingpaneRadius(
    val card: Dp = 12.dp,
    val pill: Dp = 999.dp,
)

@Immutable
data class WingpaneTouch(
    val minTarget: Dp = 48.dp,
    val minGap: Dp = 8.dp,
)

val OutfitFontFamily = FontFamily(
    Font(R.font.outfit_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.SemiBold.weight))),
    Font(R.font.outfit_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.Bold.weight))),
    Font(R.font.outfit_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.Medium.weight))),
)

val WorkSansFontFamily = FontFamily(
    Font(R.font.work_sans_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.Normal.weight))),
    Font(R.font.work_sans_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.Medium.weight))),
    Font(R.font.work_sans_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.SemiBold.weight))),
)

@Immutable
data class WingpaneTypography(
    // Outfit headings 28/22/18sp 600 per MASTER.md
    val headingLg: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    val headingMd: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    val headingSm: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    // Work Sans body 16sp/1.5, caption >=13sp per MASTER.md
    val body: TextStyle = TextStyle(
        fontFamily = WorkSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    val bodyStrong: TextStyle = TextStyle(
        fontFamily = WorkSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    val caption: TextStyle = TextStyle(
        fontFamily = WorkSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    val captionStrong: TextStyle = TextStyle(
        fontFamily = WorkSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    val metricValue: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        fontFeatureSettings = "tnum",
    ),
)

val LocalWingpaneColors = staticCompositionLocalOf { WingpaneColors() }
val LocalWingpaneSpacing = staticCompositionLocalOf { WingpaneSpacing() }
val LocalWingpaneRadius = staticCompositionLocalOf { WingpaneRadius() }
val LocalWingpaneTouch = staticCompositionLocalOf { WingpaneTouch() }
val LocalWingpaneTypography = staticCompositionLocalOf { WingpaneTypography() }

object WingpaneTheme {
    val colors: WingpaneColors
        @Composable
        @ReadOnlyComposable
        get() = LocalWingpaneColors.current

    val spacing: WingpaneSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalWingpaneSpacing.current

    val radius: WingpaneRadius
        @Composable
        @ReadOnlyComposable
        get() = LocalWingpaneRadius.current

    val touch: WingpaneTouch
        @Composable
        @ReadOnlyComposable
        get() = LocalWingpaneTouch.current

    val typography: WingpaneTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalWingpaneTypography.current
}

private fun wingpaneMaterialColorScheme(c: WingpaneColors): ColorScheme = darkColorScheme(
    primary = c.primary,
    onPrimary = c.onPrimary,
    primaryContainer = c.surface1,
    onPrimaryContainer = c.text,
    secondary = c.surface2,
    onSecondary = c.text,
    secondaryContainer = c.surface2,
    onSecondaryContainer = c.text,
    tertiary = c.success,
    onTertiary = c.onSuccess,
    background = c.bg,
    onBackground = c.text,
    surface = c.surface1,
    onSurface = c.text,
    surfaceVariant = c.surface2,
    onSurfaceVariant = c.textMuted,
    outline = c.border,
    outlineVariant = c.border,
    error = c.danger,
    onError = c.onDanger,
    errorContainer = c.surface1,
    onErrorContainer = c.danger,
    scrim = c.bg,
)

private fun wingpaneMaterialTypography(t: WingpaneTypography): Typography = Typography(
    headlineLarge = t.headingLg,
    headlineMedium = t.headingMd,
    headlineSmall = t.headingSm,
    titleLarge = t.headingMd,
    titleMedium = t.headingSm,
    titleSmall = t.bodyStrong,
    bodyLarge = t.body,
    bodyMedium = t.body,
    bodySmall = t.caption,
    labelLarge = t.bodyStrong,
    labelMedium = t.captionStrong,
    labelSmall = t.caption,
)

@Composable
fun WingpaneTheme(
    colors: WingpaneColors = WingpaneColors(),
    spacing: WingpaneSpacing = WingpaneSpacing(),
    radius: WingpaneRadius = WingpaneRadius(),
    touch: WingpaneTouch = WingpaneTouch(),
    typography: WingpaneTypography = WingpaneTypography(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalWingpaneColors provides colors,
        LocalWingpaneSpacing provides spacing,
        LocalWingpaneRadius provides radius,
        LocalWingpaneTouch provides touch,
        LocalWingpaneTypography provides typography,
    ) {
        MaterialTheme(
            colorScheme = wingpaneMaterialColorScheme(colors),
            typography = wingpaneMaterialTypography(typography),
            content = content,
        )
    }
}
