package com.example.fintrack.domain.model

import java.time.Instant

/**
 * Stage 8 P16 — budgets.
 *
 * A budget is pure POLICY: scope, target, rollover and exclusions. Actual
 * spending is always derived from the ledger at read time; nothing here is a
 * second balance truth. Rollover is explicit: every period boundary that
 * applies one records the carried-in amount in a [BudgetPeriod] row.
 */
enum class BudgetScopeKind { CATEGORY, ACCOUNT, OVERALL }

enum class BudgetStatus { ACTIVE, ARCHIVED }

/**
 * Deterministic exclusion policy. Each dimension is an allow/deny list;
 * evaluation order: kind exclusions first, then account, then tag.
 * The full effective filter is previewable (see BudgetService.preview).
 */
data class BudgetExclusions(
    /** Excluded transaction kinds (e.g. TRANSFER, CASH_MOVE are always excluded implicitly). */
    val excludedKinds: Set<TxKind> = emptySet(),
    /** Excluded account ids. */
    val excludedAccountIds: Set<String> = emptySet(),
    /** Excluded tags (normalized lowercase). */
    val excludedTags: Set<String> = emptySet(),
) {
    fun encode(): String {
        val parts = mutableListOf<String>()
        if (excludedKinds.isNotEmpty()) parts += "kinds=" + excludedKinds.joinToString(",") { it.name }
        if (excludedAccountIds.isNotEmpty()) parts += "accounts=" + excludedAccountIds.sorted().joinToString(",")
        if (excludedTags.isNotEmpty()) parts += "tags=" + excludedTags.sorted().joinToString(",")
        return parts.joinToString(";")
    }

    companion object {
        val NONE = BudgetExclusions()

        fun decode(raw: String): BudgetExclusions {
            if (raw.isBlank()) return NONE
            var kinds = emptySet<TxKind>()
            var accounts = emptySet<String>()
            var tags = emptySet<String>()
            raw.split(";").forEach { part ->
                val kv = part.split("=", limit = 2)
                if (kv.size != 2) return@forEach
                when (kv[0]) {
                    "kinds" -> kinds = kv[1].split(",").filter { it.isNotBlank() }
                        .mapNotNull { runCatching { TxKind.valueOf(it.trim()) }.getOrNull() }.toSet()
                    "accounts" -> accounts = kv[1].split(",").filter { it.isNotBlank() }.toSet()
                    "tags" -> tags = kv[1].split(",").filter { it.isNotBlank() }.toSet()
                }
            }
            return BudgetExclusions(kinds, accounts, tags)
        }
    }
}

data class Budget(
    val id: String,
    val name: String,
    val scopeKind: BudgetScopeKind,
    val categoryId: String?,
    val accountId: String?,
    /** Only MONTHLY supported today; enum keeps room for later periods. */
    val periodType: String,
    val startDayOfMonth: Int,
    val targetAmountMinor: Long,
    val currencyCode: String,
    val rolloverEnabled: Boolean,
    val rolloverCapMinor: Long?,
    val exclusions: BudgetExclusions,
    val status: BudgetStatus,
    val createdAtEpochMs: Long,
) {
    init {
        require(name.isNotBlank())
        require(targetAmountMinor > 0) { "budget target must be > 0" }
        require(currencyCode.length == 3)
        require(startDayOfMonth in 1..28) { "startDayOfMonth must be 1..28 for determinism" }
        if (scopeKind == BudgetScopeKind.CATEGORY) {
            require(categoryId != null) { "CATEGORY budget requires categoryId" }
        }
    }
}

/** Durable record of how one period boundary was resolved. No silent carry-over. */
data class BudgetPeriod(
    val id: String,
    val budgetId: String,
    val periodStartEpochDay: Long,
    val periodEndEpochDay: Long,
    val rolloverInMinor: Long,
    val boundaryAction: BoundaryAction,
    val computedAtEpochMs: Long,
)

enum class BoundaryAction { RESET, ROLLOVER_APPLIED, ROLLOVER_CAPPED }

/** Actual-vs-budget result for one period. Derived, never stored as truth. */
data class BudgetProgress(
    val budgetId: String,
    val periodStartEpochDay: Long,
    val periodEndEpochDay: Long,
    val targetMinor: Long,
    /** Rollover carried into this period (0 unless applied). Shown as source/adjustment in UI. */
    val rolloverInMinor: Long,
    /** Ledger-derived eligible spend for this period only. */
    val spentMinor: Long,
    /** Refund credits applied against this category within the period. */
    val refundedMinor: Long,
    /** Effective usage = spent - refunded + rolloverIn. */
    val effectiveUsageMinor: Long,
    val remainingMinor: Long,
    /** 0..1+ ratio of effectiveUsage to (target + rolloverIn). */
    val usageRatio: Double,
    val status: ProgressStatus,
    /**
     * True when source history for the period is known to be partial
     * (e.g. SMS backfill incomplete). Drives the "incomplete data" cue.
     */
    val coverageIncomplete: Boolean,
)

/**
 * Accessible status cues: never color-only. Every state has a text label +
 * symbol contract used by the UI.
 */
enum class ProgressStatus(val label: String, val symbol: String) {
    UNDER("Under budget", "OK"),
    NEAR_LIMIT("Near limit", "!"),
    OVER("Over budget", "X"),
}
