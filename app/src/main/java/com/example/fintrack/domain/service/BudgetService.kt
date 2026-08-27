package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.BoundaryAction
import com.example.fintrack.domain.model.Budget
import com.example.fintrack.domain.model.BudgetExclusions
import com.example.fintrack.domain.model.BudgetPeriod
import com.example.fintrack.domain.model.BudgetProgress
import com.example.fintrack.domain.model.BudgetScopeKind
import com.example.fintrack.domain.model.BudgetStatus
import com.example.fintrack.domain.model.ProgressStatus
import com.example.fintrack.domain.model.TxKind
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Stage 8 P16 — budget engine.
 *
 * Pure domain logic over ledger-derived transaction views; no storage.
 *
 * Rules:
 *  - Eligible spend = EXPENSE + FEE debits in scope, minus REFUND credits
 *    linked to the same category/account, minus explicitly excluded
 *    dimensions. TRANSFER and CASH_MOVE are NEVER eligible (they are not
 *    consumption).
 *  - Rollover is explicit: at a period boundary the engine computes the
 *    carry-in deterministically from the previous period's remaining amount
 *    and records a [BudgetPeriod] row (RESET / ROLLOVER_APPLIED /
 *    ROLLOVER_CAPPED). No silent carry-over.
 *  - Coverage incompleteness is surfaced, never guessed away.
 */
class BudgetService(
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /** Ledger-derived view of one transaction, reduced for budget math. */
    data class TxnView(
        val id: String,
        val accountId: String,
        val categoryId: String?,
        val kind: TxKind,
        val directionDebit: Boolean,
        val amountMinor: Long,
        val localDateEpochDay: Long,
        val tags: Set<String> = emptySet(),
        val statusDeleted: Boolean = false,
    )

    // ---- scope / eligibility ----

    fun scopeIdentity(budget: Budget): String =
        sha256(listOf(budget.scopeKind.name, budget.categoryId ?: "-", budget.accountId ?: "-", budget.periodType).joinToString("|"))

    /**
     * Deterministic eligibility test. Returns null when excluded.
     */
    fun eligibility(budget: Budget, txn: TxnView): Eligibility {
        if (txn.statusDeleted) return Eligibility.Excluded("DELETED")
        when (txn.kind) {
            TxKind.TRANSFER, TxKind.CASH_MOVE -> return Eligibility.Excluded("NON_SPENDING_KIND")
            TxKind.INCOME -> return Eligibility.Excluded("INCOME")
            else -> {}
        }
        if ((txn.kind == TxKind.EXPENSE || txn.kind == TxKind.FEE) && !txn.directionDebit) {
            return Eligibility.Excluded("NOT_DEBIT")
        }
        val ex = budget.exclusions
        if (txn.kind in ex.excludedKinds) return Eligibility.Excluded("KIND_FILTER")
        if (txn.accountId in ex.excludedAccountIds) return Eligibility.Excluded("ACCOUNT_FILTER")
        val taggedOut = ex.excludedTags.any { it in txn.tags }
        if (taggedOut) return Eligibility.Excluded("TAG_FILTER")

        when (budget.scopeKind) {
            BudgetScopeKind.CATEGORY ->
                if (txn.categoryId != budget.categoryId) return Eligibility.Excluded("OUT_OF_SCOPE")
            BudgetScopeKind.ACCOUNT ->
                if (txn.accountId != budget.accountId) return Eligibility.Excluded("OUT_OF_SCOPE")
            BudgetScopeKind.OVERALL -> Unit
        }
        return Eligibility.Eligible
    }

    sealed interface Eligibility {
        data object Eligible : Eligibility
        data class Excluded(val reason: String) : Eligibility
    }

    /**
     * Module 151 preview: classify every transaction as included/excluded
     * with reasons so the user can see exactly what a budget covers before
     * trusting its numbers.
     */
    data class ScopePreview(
        val included: List<TxnView>,
        val excluded: List<Pair<TxnView, String>>,
    )

    fun preview(budget: Budget, txns: List<TxnView>): ScopePreview {
        val inc = mutableListOf<TxnView>()
        val exc = mutableListOf<Pair<TxnView, String>>()
        txns.forEach { txn ->
            when (val e = eligibility(budget, txn)) {
                is Eligibility.Eligible -> inc += txn
                is Eligibility.Excluded -> exc += txn to e.reason
            }
        }
        return ScopePreview(inc, exc)
    }

    // ---- period boundaries ----

    /** Period containing [day], anchored on [startDayOfMonth]. */
    fun periodContaining(budget: Budget, day: LocalDate): Pair<LocalDate, LocalDate> {
        require(budget.periodType == "MONTHLY") { "only MONTHLY budgets supported" }
        var start = if (day.dayOfMonth >= minOf(budget.startDayOfMonth, day.lengthOfMonth())) {
            day.withDayOfMonth(minOf(budget.startDayOfMonth, day.lengthOfMonth()))
        } else {
            val prev = day.minusMonths(1)
            prev.withDayOfMonth(minOf(budget.startDayOfMonth, prev.lengthOfMonth()))
        }
        val end = start.plusMonths(1).minusDays(1)
        return start to end
    }

    /**
     * Compute the rollover decision at a boundary. Deterministic given the
     * previous period's effective usage.
     *
     * @param previousRemaining previous period (target + rolloverIn - usage); may be negative.
     */
    fun resolveBoundary(
        budget: Budget,
        periodStartEpochDay: Long,
        periodEndEpochDay: Long,
        previousRemaining: Long,
    ): BudgetPeriod {
        val action: BoundaryAction
        val rolloverIn: Long
        if (!budget.rolloverEnabled || previousRemaining <= 0) {
            action = BoundaryAction.RESET
            rolloverIn = 0L
        } else if (budget.rolloverCapMinor != null && previousRemaining > budget.rolloverCapMinor) {
            action = BoundaryAction.ROLLOVER_CAPPED
            rolloverIn = budget.rolloverCapMinor
        } else {
            action = BoundaryAction.ROLLOVER_APPLIED
            rolloverIn = previousRemaining
        }
        return BudgetPeriod(
            id = java.util.UUID.randomUUID().toString(),
            budgetId = budget.id,
            periodStartEpochDay = periodStartEpochDay,
            periodEndEpochDay = periodEndEpochDay,
            rolloverInMinor = rolloverIn,
            boundaryAction = action,
            computedAtEpochMs = clock().toEpochMilli(),
        )
    }

    // ---- progress ----

    /**
     * Actual-vs-budget for one period. Refunds within the period reduce
     * usage; transfers/cash moves/excluded rows never count.
     */
    fun progress(
        budget: Budget,
        txnsInPeriod: List<TxnView>,
        rolloverInMinor: Long,
        coverageIncomplete: Boolean,
    ): BudgetProgress {
        var spent = 0L
        var refunded = 0L
        txnsInPeriod.forEach { txn ->
            // Refund credits against the scoped category reduce usage even
            // though refunds are not "spend".
            if (txn.kind == TxKind.REFUND && !txn.statusDeleted && !txn.directionDebit) {
                if (inScopeForRefund(budget, txn)) refunded += txn.amountMinor
                return@forEach
            }
            when (eligibility(budget, txn)) {
                is Eligibility.Excluded -> Unit
                Eligibility.Eligible -> spent += txn.amountMinor
            }
        }
        val capacity = budget.targetAmountMinor + rolloverInMinor
        val effective = spent - refunded + rolloverInMinor
        val remaining = capacity - (spent - refunded)
        val ratio = if (capacity <= 0) 1.0 else effective.toDouble() / capacity.toDouble()
        val status = when {
            ratio > 1.0 -> ProgressStatus.OVER
            ratio >= 0.85 -> ProgressStatus.NEAR_LIMIT
            else -> ProgressStatus.UNDER
        }
        return BudgetProgress(
            budgetId = budget.id,
            periodStartEpochDay = txnsInPeriod.minOfOrNull { it.localDateEpochDay } ?: 0L,
            periodEndEpochDay = 0L,
            targetMinor = budget.targetAmountMinor,
            rolloverInMinor = rolloverInMinor,
            spentMinor = spent,
            refundedMinor = refunded,
            effectiveUsageMinor = effective,
            remainingMinor = remaining,
            usageRatio = ratio,
            status = status,
            coverageIncomplete = coverageIncomplete,
        )
    }

    private fun inScopeForRefund(budget: Budget, txn: TxnView): Boolean = when (budget.scopeKind) {
        BudgetScopeKind.CATEGORY -> txn.categoryId == budget.categoryId
        BudgetScopeKind.ACCOUNT -> txn.accountId == budget.accountId
        BudgetScopeKind.OVERALL -> true
    }

    companion object {
        fun sha256(raw: String): String =
            MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
