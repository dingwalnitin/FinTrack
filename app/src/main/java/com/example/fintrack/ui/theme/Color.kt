package com.example.fintrack.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Brand palette for the dark, premium fintech visual language used across
 * every screen (see design_screens/ reference mockups). Single source of
 * truth so screens never hardcode ad-hoc hex values.
 */
object Palette {
    val Background = Color(0xFF0D0C1A)
    val BackgroundAlt = Color(0xFF13111F)
    val Surface = Color(0xFF17162A)
    val SurfaceElevated = Color(0xFF1F1E33)
    val SurfaceHigh = Color(0xFF262541)
    val Outline = Color(0xFF2E2C46)

    val Violet = Color(0xFF7C6FFF)
    val VioletDeep = Color(0xFF5B4CE0)
    val Blue = Color(0xFF5B8DFF)
    val Pink = Color(0xFFFF6FD8)

    val Income = Color(0xFF34D399)
    val IncomeDeep = Color(0xFF10B981)
    val Warn = Color(0xFFFFC24B)
    val Danger = Color(0xFFFF6B81)

    val TextPrimary = Color(0xFFF5F3FF)
    val TextSecondary = Color(0xFFA6A1C1)
    val TextMuted = Color(0xFF716C8E)

    /** Distinct, vivid colors used for category/merchant iconography and charts. */
    val CategoryPalette = listOf(
        Color(0xFFFF9F43), // food / dining
        Color(0xFF4FC3F7), // transport
        Color(0xFFC77DFF), // shopping
        Color(0xFF4ADE80), // groceries
        Color(0xFFFFD166), // bills / utilities
        Color(0xFFFF6FB5), // entertainment
        Color(0xFF2DD4BF), // health
        Color(0xFF34D399), // income / salary
        Color(0xFF667EEA), // transfer
        Color(0xFF7C6FFF), // education / other
        Color(0xFFFF8C6B), // travel
        Color(0xFF60A5FA), // bank / misc
    )
}

val BrandGradient = Brush.linearGradient(listOf(Palette.Violet, Palette.Blue))
val BrandGradientVertical = Brush.verticalGradient(listOf(Palette.Violet, Palette.VioletDeep))
val AccentGradient = Brush.linearGradient(listOf(Palette.Pink, Palette.Violet))
val IncomeGradient = Brush.linearGradient(listOf(Palette.Income, Palette.IncomeDeep))

/** Stable hash so the same name always renders with the same color/icon. */
private fun stableIndex(name: String, buckets: Int): Int {
    var h = 0
    for (c in name) h = (h * 31 + c.code) and 0x7FFFFFFF
    return h % buckets
}

private val categoryKeywordColors: List<Pair<List<String>, Color>> = listOf(
    listOf("food", "dining", "restaurant", "cafe", "coffee") to Color(0xFFFF9F43),
    listOf("transport", "travel", "uber", "ola", "cab", "fuel", "petrol") to Color(0xFF4FC3F7),
    listOf("shopping", "amazon", "flipkart", "retail") to Color(0xFFC77DFF),
    listOf("grocery", "groceries", "supermarket", "mart") to Color(0xFF4ADE80),
    listOf("bill", "utility", "electricity", "recharge", "dth", "broadband") to Color(0xFFFFD166),
    listOf("entertainment", "movie", "netflix", "spotify", "game") to Color(0xFFFF6FB5),
    listOf("health", "medical", "pharmacy", "hospital", "doctor") to Color(0xFF2DD4BF),
    listOf("income", "salary", "payroll", "credit") to Color(0xFF34D399),
    listOf("transfer", "upi", "neft", "imps", "rtgs") to Color(0xFF667EEA),
    listOf("education", "school", "college", "course") to Color(0xFF7C6FFF),
    listOf("flight", "airline", "hotel", "trip") to Color(0xFFFF8C6B),
    listOf("bank", "atm", "emi", "loan", "card") to Color(0xFF60A5FA),
)

/** Deterministic color for a category/merchant label — same input, same color. */
fun categoryColor(label: String?): Color {
    val key = label?.trim()?.lowercase().orEmpty()
    if (key.isEmpty()) return Palette.TextMuted
    categoryKeywordColors.firstOrNull { (keywords, _) -> keywords.any { key.contains(it) } }
        ?.let { return it.second }
    return Palette.CategoryPalette[stableIndex(key, Palette.CategoryPalette.size)]
}

private val categoryKeywordIcons: List<Pair<List<String>, ImageVector>> = listOf(
    listOf("food", "dining", "restaurant", "cafe", "coffee") to Icons.Filled.Fastfood,
    listOf("transport", "travel", "uber", "ola", "cab", "fuel", "petrol") to Icons.Filled.DirectionsCar,
    listOf("shopping", "amazon", "flipkart", "retail") to Icons.Filled.ShoppingBag,
    listOf("grocery", "groceries", "supermarket", "mart") to Icons.Filled.LocalGroceryStore,
    listOf("bill", "utility", "electricity", "recharge", "dth", "broadband") to Icons.Filled.ElectricBolt,
    listOf("entertainment", "movie", "netflix", "spotify", "game") to Icons.Filled.MovieFilter,
    listOf("health", "medical", "pharmacy", "hospital", "doctor") to Icons.Filled.LocalHospital,
    listOf("income", "salary", "payroll") to Icons.Filled.Work,
    listOf("transfer", "upi", "neft", "imps", "rtgs") to Icons.Filled.SwapHoriz,
    listOf("education", "school", "college", "course") to Icons.Filled.School,
    listOf("flight", "airline", "hotel", "trip") to Icons.Filled.Flight,
    listOf("bank", "atm", "loan") to Icons.Filled.AccountBalance,
    listOf("emi", "card") to Icons.Filled.CreditCard,
    listOf("game", "esports") to Icons.Filled.SportsEsports,
)

/** Deterministic icon glyph for a category/merchant label. */
fun categoryIcon(label: String?): ImageVector {
    val key = label?.trim()?.lowercase().orEmpty()
    if (key.isEmpty()) return Icons.Filled.Receipt
    categoryKeywordIcons.firstOrNull { (keywords, _) -> keywords.any { key.contains(it) } }
        ?.let { return it.second }
    val fallback = listOf(Icons.Filled.Wallet, Icons.Filled.Receipt, Icons.Filled.AttachMoney)
    return fallback[stableIndex(key, fallback.size)]
}
