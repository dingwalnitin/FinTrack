package com.example.fintrack.ui.navigation

import androidx.annotation.StringRes
import com.example.fintrack.R

/**
 * Typed routes for the stable UX shell. Routes are stable identifiers — never
 * derived from user content. Deep links use the fintrack:// scheme plus
 * https://app.fintrack.example for defined back-stack behavior.
 */
object Routes {
    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val ACCOUNTS = "accounts"
    const val BUDGETS = "budgets"
    const val INSIGHTS = "insights"
    const val REVIEW = "review"
    const val SETTINGS = "settings"
    const val SMS_CONSENT = "sms-consent"

    /** Parameterized deep link: account detail from a notification-free path. */
    const val ACCOUNT_DETAIL = "accounts/{accountId}"

    /** Stage 5 (P10) — parameterized transaction detail route. */
    const val TRANSACTION_DETAIL = "transactions/{transactionId}"
    fun transactionDetail(transactionId: String) = "transactions/$transactionId"

    /** Stage 5 (P11) — manual entry / edit and transfer review. */
    const val MANUAL_ENTRY = "transactions/new"
    const val TRANSFER_REVIEW = "review/transfers"

    /** Stage 10 (P21) — AI natural-language query surface. */
    const val AI_QUERY = "ai-query"

    /** Stage 11 (P23/P24) — backup & restore + app lock. */
    const val BACKUP_RESTORE = "settings/backup"

    /** Stage 12 (P25) — developer diagnostics. */
    const val DIAGNOSTICS = "settings/diagnostics"

    fun accountDetail(accountId: String) = "accounts/$accountId"

    /** Deep-link base — offline app works fully without any link. */
    const val DEEP_LINK_SCHEME = "fintrack"
    const val DEEP_LINK_HOST = "app"

    val topLevel: List<TopLevelDestination> = listOf(
        TopLevelDestination(HOME, R.string.nav_home),
        TopLevelDestination(TRANSACTIONS, R.string.nav_transactions),
        TopLevelDestination(ACCOUNTS, R.string.nav_accounts),
        TopLevelDestination(BUDGETS, R.string.nav_budgets),
        TopLevelDestination(INSIGHTS, R.string.nav_insights),
        TopLevelDestination(REVIEW, R.string.nav_review),
        TopLevelDestination(SETTINGS, R.string.nav_settings),
    )
}

data class TopLevelDestination(val route: String, @StringRes val labelRes: Int)
