package com.example.fintrack.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    secondary = Color(0xFF80CBC4),
    error = Color(0xFFF2B8B5),
    surface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFF49454F),
)

private val LightMoney = MoneySemanticColors(
    debit = Color(0xFF8A1C1C),   // darker red — AA contrast on light surface
    credit = Color(0xFF1B5E20),  // darker green
    neutral = Color(0xFF37363B),
)

private val DarkMoney = MoneySemanticColors(
    debit = Color(0xFFF2B8B5),
    credit = Color(0xFFA5D6A7),
    neutral = Color(0xFFCAC4D0),
)

/** Dense typography tuned for compact financial rows. */
private fun financeTypography(): Typography = Typography().let { base ->
    base.copy(
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, fontFamily = FontFamily.Default),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp),
        titleMedium = base.titleMedium.copy(fontSize = 16.sp),
        headlineSmall = base.headlineSmall.copy(fontSize = 22.sp),
        // Tabular figures keep amount columns aligned.
        labelLarge = base.labelLarge.copy(fontFeatureSettings = "tnum"),
    )
}

@Composable
fun FinTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
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
            typography = financeTypography(),
            content = content,
        )
    }
}
