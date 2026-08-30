package com.example.fintrack.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Finance-oriented theme tokens. Centralized so every feature renders
 * consistently in light/dark and dense list layouts.
 */

/** Centralized spacing/size tokens (dense, compact rows). */
data class SpacingTokens(
    val xs: Int = 4,
    val sm: Int = 8,
    val md: Int = 12,
    val lg: Int = 16,
    val xl: Int = 24,
    /** Minimum touch target per Material a11y guidance. */
    val minTouchTarget: Int = 48,
)

val LocalSpacing = staticCompositionLocalOf { SpacingTokens() }

/**
 * Semantic money styling. Debit/credit is NEVER red/green-only: direction is
 * carried by sign glyph + label + color, so color-blind users are covered.
 */
data class MoneySemanticColors(
    val debit: Color,
    val credit: Color,
    val neutral: Color,
)

val LocalMoneySemantics = staticCompositionLocalOf {
    MoneySemanticColors(Color.Unspecified, Color.Unspecified, Color.Unspecified)
}

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    secondary = Color(0xFF00695C),
    error = Color(0xFFB3261E),
    surface = Color(0xFFFFFBFE),
    surfaceVariant = Color(0xFFE7E2EC),
)

/** Dark, premium fintech palette matching the reference design mockups. */
private val DarkColors: ColorScheme = darkColorScheme(
    primary = Palette.Violet,
    onPrimary = Color.White,
    primaryContainer = Palette.SurfaceHigh,
    onPrimaryContainer = Palette.TextPrimary,
    secondary = Palette.Blue,
    onSecondary = Color.White,
    tertiary = Palette.Pink,
    onTertiary = Color.White,
    error = Palette.Danger,
    onError = Color.White,
    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceElevated,
    onSurfaceVariant = Palette.TextSecondary,
    surfaceContainer = Palette.Surface,
    surfaceContainerLow = Palette.BackgroundAlt,
    surfaceContainerHigh = Palette.SurfaceElevated,
    surfaceContainerHighest = Palette.SurfaceHigh,
    outline = Palette.Outline,
    outlineVariant = Palette.Outline,
)

private val LightMoney = MoneySemanticColors(
    debit = Color(0xFF8A1C1C),   // darker red — AA contrast on light surface
    credit = Color(0xFF1B5E20),  // darker green
    neutral = Color(0xFF37363B),
)

private val DarkMoney = MoneySemanticColors(
    debit = Palette.TextPrimary,
    credit = Palette.Income,
    neutral = Palette.TextSecondary,
)

/** Rounded, card-forward shapes matching the reference mockups. */
private val FinTrackShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Dense typography tuned for compact financial rows, bold display numbers. */
private fun financeTypography(): Typography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum"),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp),
        // Tabular figures keep amount columns aligned.
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        labelMedium = base.labelMedium.copy(fontFeatureSettings = "tnum"),
    )
}

@Composable
fun FinTrackTheme(
    // The brand is dark-first by design (see design_screens/ reference mockups);
    // system light mode is still honored if the caller explicitly asks for it.
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val money = if (darkTheme) DarkMoney else LightMoney
    CompositionLocalProvider(
        LocalSpacing provides SpacingTokens(),
        LocalMoneySemantics provides money,
    ) {
        MaterialTheme(
            colorScheme = colors,
            shapes = FinTrackShapes,
            typography = financeTypography(),
            content = content,
        )
    }
}
