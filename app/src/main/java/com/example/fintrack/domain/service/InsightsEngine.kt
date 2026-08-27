package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.TxKind
import java.time.LocalDate

/**
 * Stage 9 P19 — derived dashboard & analytics engine.
 *
 * Pure domain logic over [LedgerTxnView] rows (the shared ledger read model
 * from Stage 8). No storage, no Room. Every metric is DERIVED from normalized
 * ledger facts; nothing here is a second source of truth.
 *
 * Core rules (App Bible + stage contract):
 *  - Owned-account TRANSFER / CASH_MOVE rows are excluded from income,
 *    expense and savings-rate math so internal movements and card-settlement
 *    double-counting never inflate external cash flow.
 *  - Refunds reduce spend (gross vs net treatment is explicit on every
 *    aggregate: grossMinor + refundedMinor = netMinor).
 *  - Uncategorized visibility: transactions with a null categoryId (or one
 *    pointing at the uncategorized sink) are counted, never hidden.
 *  - Coverage incompleteness is surfaced as data, never guessed away.
 *  - Period comparison aligns ranges by exact epoch-day spans; leap-year /
 *    month-length differences are handled by comparing equal-length windows
 *    anchored at period starts (see [alignedComparison]).
 */
class InsightsEngine {

    // ---- classification helpers ----

    /** External economic activity: EXPENSE/FEE debits, INCOME/REFUND credits. */
    private fun isExternalSpend(t: LedgerTxnView): Boolean =
        !t.statusDeleted && t.directionDebit &&
            (t.kind == TxKind.EXPENSE.name || t.kind == TxKind.FEE.name)

    private fun isExternalIncome(t: LedgerTxnView): Boolean =
        !t.statusDeleted && !t.directionDebit && t.kind == TxKind.INCOME.name

    /** Refund credit: external inflow that reduces spend, never counted as earned income. */
    private fun isRefundCredit(t: LedgerTxnView): Boolean =
        !t.statusDeleted && !t.directionDebit && t.kind == TxKind.REFUND.name

    private fun isInternalMovement(t: LedgerTxnView): Boolean =
        t.kind == TxKind.TRANSFER.name || t.kind == TxKind.CASH_MOVE.name

    private fun inWindow(t: LedgerTxnView, fromDay: Long, toDay: Long): Boolean =
        t.localDateEpochDay in fromDay..toDay

    private fun matchesAccount(t: LedgerTxnView, accountIds: Set<String>?): Boolean =
        accountIds == null || t.accountId in accountIds

    // ---- P19 #1: dashboard summary ----

    /**
     * Home-dashboard aggregates for one window. All values are local
     * aggregates over the ledger; review/pending counts are supplied by the
     * caller (they come from the review queue, not the ledger).
     */
    data class DashboardSummary(
        val currencyCode: String,
        val windowStartEpochDay: Long,
        val windowEndEpochDay: Long,
        /** Net across ALL active accounts incl. opening balances (caller-supplied). */
        val totalBalanceMinor: Long?,
        val incomeNetMinor: Long,
        val spendGrossMinor: Long,
        val spendRefundedMinor: Long,
        val spendNetMinor: Long,
        /** Distinct active accounts with any activity in window. */
        val activeAccounts: Int,
        val recentTransactions: List<LedgerTxnView>,
        val openReviewCount: Int,
        val pendingStatusCount: Int,
        val coverageIncomplete: Boolean,
    )

    fun dashboardSummary(
        txns: List<LedgerTxnView>,
        fromDay: Long,
        toDay: Long,
        currencyCode: String,
        totalBalanceMinor: Long? = null,
        openReviewCount: Int = 0,
        pendingStatusCount: Int = 0,
        recentLimit: Int = 5,
        coverageIncomplete: Boolean = false,
    ): DashboardSummary {
        val windowed = txns.filter { inWindow(it, fromDay, toDay) && matchesAccount(it, null) }
        var income = 0L
        var gross = 0L
        var refunded = 0L
        windowed.forEach { t ->
            when {
                isRefundCredit(t) -> refunded += t.amountMinor
                isExternalIncome(t) -> income += t.amountMinor
                isExternalSpend(t) -> gross += t.amountMinor
            }
        }
        val recent = txns.filter { !it.statusDeleted }
            .sortedByDescending { it.occurredAtEpochMs }
            .take(recentLimit)
        return DashboardSummary(
            currencyCode = currencyCode,
            windowStartEpochDay = fromDay,
            windowEndEpochDay = toDay,
            totalBalanceMinor = totalBalanceMinor,
            incomeNetMinor = income,
            spendGrossMinor = gross,
            spendRefundedMinor = refunded,
            spendNetMinor = gross - refunded,
            activeAccounts = windowed.filter { !it.statusDeleted }.map { it.accountId }.distinct().size,
            recentTransactions = recent,
            openReviewCount = openReviewCount,
            pendingStatusCount = pendingStatusCount,
            coverageIncomplete = coverageIncomplete,
        )
    }

    // ---- P19 #2: monthly cash flow ----

    /**
     * Cash-flow split separating EXTERNAL flows (income/expense/refund)
     * from INTERNAL movements (owned-account transfers + card settlements).
     * Internal volume is reported separately so it can never be mistaken
     * for consumption or earnings.
     */
    data class CashFlow(
        val fromDay: Long,
        val toDay: Long,
        val currencyCode: String,
        val inflowExternalMinor: Long,
        val outflowExternalMinor: Long,
        /** REFUND credits inside the window (already part of external inflow). */
        val refundsMinor: Long,
        /** TRANSFER + CASH_MOVE volume between owned accounts — NOT income/expense. */
        val internalTransfersMinor: Long,
        /** Net external = inflow - outflow. */
        val netExternalMinor: Long,
        val coverageIncomplete: Boolean,
    )

    fun cashFlow(
        txns: List<LedgerTxnView>,
        fromDay: Long,
        toDay: Long,
        currencyCode: String,
        coverageIncomplete: Boolean = false,
    ): CashFlow {
        var inflow = 0L
        var outflow = 0L
        var refunds = 0L
        var internal = 0L
        txns.filter { inWindow(it, fromDay, toDay) && !it.statusDeleted }.forEach { t ->
            when {
                isInternalMovement(t) -> internal += t.amountMinor
                isRefundCredit(t) -> { inflow += t.amountMinor; refunds += t.amountMinor }
                isExternalIncome(t) -> inflow += t.amountMinor
                isExternalSpend(t) -> outflow += t.amountMinor
                t.directionDebit && t.kind == TxKind.UNKNOWN.name -> outflow += t.amountMinor
            }
        }
        return CashFlow(
            fromDay = fromDay,
            toDay = toDay,
            currencyCode = currencyCode,
            inflowExternalMinor = inflow,
            outflowExternalMinor = outflow,
            refundsMinor = refunds,
            internalTransfersMinor = internal,
            netExternalMinor = inflow - outflow,
            coverageIncomplete = coverageIncomplete,
        )
    }

    /** Month-by-month cash-flow series for charts/calendars (bounded by input). */
    fun monthlyCashFlow(
        txns: List<LedgerTxnView>,
        monthsBack: Int,
        today: LocalDate,
        currencyCode: String,
        coverageIncomplete: Boolean = false,
    ): List<CashFlow> {
        require(monthsBack > 0)
        return (monthsBack - 1 downTo 0).map { back ->
            val monthStart = today.minusMonths(back.toLong()).withDayOfMonth(1)
            val monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth())
            cashFlow(
                txns, monthStart.toEpochDay(), monthEnd.toEpochDay(), currencyCode,
                coverageIncomplete = coverageIncomplete,
            )
        }
    }

    // ---- P19 #3: category / merchant breakdown ----

    data class BreakdownRow(
        val key: String?,                 // categoryId or normalized merchant; null = uncategorized/unknown
        val label: String?,               // display label resolved by the caller
        val grossMinor: Long,
        val refundedMinor: Long,
        val netMinor: Long,
        val txnCount: Int,
        /** Share of net spend across all rows, 0..1. */
        val shareOfNet: Double,
    ) {
        val isUncategorized: Boolean get() = key == null
    }

    data class Breakdown(
        val rows: List<BreakdownRow>,
        val totalGrossMinor: Long,
        val totalRefundedMinor: Long,
        val totalNetMinor: Long,
        val uncategorizedNetMinor: Long,
        val currencyCode: String,
    )

    enum class Grouping { CATEGORY, MERCHANT }

    /**
     * Spend grouped by category or merchant. Refunds credited against the
     * same group reduce that group's net (gross vs net shown explicitly).
     * Uncategorized rows (null key) are always visible.
     */
    fun spendBreakdown(
        txns: List<LedgerTxnView>,
        fromDay: Long,
        toDay: Long,
        grouping: Grouping,
        accountIds: Set<String>? = null,
        currencyCode: String,
    ): Breakdown {
        data class Acc(var gross: Long = 0, var refunded: Long = 0, var count: Int = 0)

        val byKey = LinkedHashMap<String?, Acc>()
        txns.filter { inWindow(it, fromDay, toDay) && !it.statusDeleted && matchesAccount(it, accountIds) }
            .forEach { t ->
                val key: String? = when (grouping) {
                    Grouping.CATEGORY -> t.categoryId?.ifBlank { null }
                    Grouping.MERCHANT -> {
                        val m = t.merchant?.lowercase()?.takeIf { it.isNotBlank() }
                            ?: t.counterpartyNormalized?.lowercase()?.takeIf { it.isNotBlank() }
                        m
                    }
                }
                val acc = byKey.getOrPut(key) { Acc() }
                when {
                    isExternalSpend(t) -> { acc.gross += t.amountMinor; acc.count++ }
                    isRefundCredit(t) -> acc.refunded += t.amountMinor
                    // UNKNOWN-kind debits are visible money-out whose meaning is
                    // unresolved; they surface under Uncategorized without being
                    // promoted to a confirmed expense classification.
                    t.directionDebit && t.kind == TxKind.UNKNOWN.name -> { acc.gross += t.amountMinor; acc.count++ }
                }
            }
        val rows = byKey.map { (key, acc) ->
            BreakdownRow(
                key = key,
                label = key,
                grossMinor = acc.gross,
                refundedMinor = acc.refunded,
                netMinor = acc.gross - acc.refunded,
                txnCount = acc.count,
                shareOfNet = 0.0,
            )
        }
        val totalGross = rows.sumOf { it.grossMinor }
        val totalRefunded = rows.sumOf { it.refundedMinor }
        val totalNet = totalGross - totalRefunded
        val withShares = rows
            .map { it.copy(shareOfNet = if (totalNet <= 0) 0.0 else it.netMinor.toDouble() / totalNet) }
            .sortedByDescending { it.netMinor }
        return Breakdown(
            rows = withShares,
            totalGrossMinor = totalGross,
            totalRefundedMinor = totalRefunded,
            totalNetMinor = totalNet,
            uncategorizedNetMinor = withShares.firstOrNull { it.isUncategorized }?.netMinor ?: 0L,
            currencyCode = currencyCode,
        )
    }

    // ---- P19 #4: payment-rail analytics ----

    data class RailRow(
        val rail: String,
        /** Spend AUTHORIZED on this rail (external expense/fee debits). */
        val spendMinor: Long,
        val txnCount: Int,
        /**
         * Portion of this rail's spend funded by a card (cardMask != null),
         * e.g. UPI-on-credit-card. Shown separately so rail × funding-instrument
         * totals never double count: fundingInstrumentMinor ⊆ spendMinor.
         */
        val fundingInstrumentMinor: Long,
        val fundingInstrumentLabel: String?,
    )

    data class RailAnalytics(
        val rows: List<RailRow>,
        val totalSpendMinor: Long,
        val currencyCode: String,
    )

    /**
     * Rail separated from funding instrument. A UPI transaction funded by a
     * credit card contributes its amount ONCE under rail=UPI, with the
     * card-funded share surfaced separately — never added again as CARD spend.
     */
    fun railAnalytics(
        txns: List<LedgerTxnView>,
        fromDay: Long,
        toDay: Long,
        accountIds: Set<String>? = null,
        currencyCode: String,
    ): RailAnalytics {
        data class Acc(var spend: Long = 0, var count: Int = 0, var cardFunded: Long = 0, var mask: String? = null)

        val byRail = LinkedHashMap<String, Acc>()
        txns.filter { inWindow(it, fromDay, toDay) && !it.statusDeleted && matchesAccount(it, accountIds) }
            .forEach { t ->
                if (!isExternalSpend(t)) return@forEach
                val rail = t.rail?.uppercase()?.takeIf { it != "UNKNOWN" && it.isNotBlank() } ?: "UNKNOWN"
                val acc = byRail.getOrPut(rail) { Acc() }
                acc.spend += t.amountMinor
                acc.count++
                t.cardMask?.takeIf { it.isNotBlank() }?.let { mask ->
                    acc.cardFunded += t.amountMinor
                    acc.mask = mask
                }
            }
        val rows = byRail.map { (rail, acc) ->
            RailRow(
                rail = rail,
                spendMinor = acc.spend,
                txnCount = acc.count,
                fundingInstrumentMinor = acc.cardFunded,
                fundingInstrumentLabel = if (acc.cardFunded > 0) "card ••••${acc.mask ?: "????"}" else null,
            )
        }.sortedByDescending { it.spendMinor }
        return RailAnalytics(rows, rows.sumOf { it.spendMinor }, currencyCode)
    }

    // ---- P19 #5: balance history ----

    data class BalancePoint(
        val atEpochMs: Long,
        /** Observed bank-reported value (snapshot). Never interpolated. */
        val observedMinor: Long?,
        /** Ledger-derived running balance at this point (opening + postings). */
        val derivedMinor: Long,
        val source: Source,
    ) {
        enum class Source { OBSERVED_SNAPSHOT, LEDGER_DERIVED }
    }

    data class BalanceHistory(
        val accountId: String,
        val points: List<BalancePoint>,
        /** True when there are periods with no postings AND no snapshot. */
        val hasGaps: Boolean,
        val currencyCode: String,
    )

    /**
     * Balance history = observed snapshots (fact) + ledger-derived points
     * (interpretation), merged chronologically. Observed values are NEVER
     * interpolated across gaps; gaps stay visible via [BalanceHistory.hasGaps].
     */
    fun balanceHistory(
        accountId: String,
        openingBalanceMinor: Long?,
        postings: List<LedgerTxnView>,   // directionDebit => money leaves the account
        snapshots: List<Pair<Long, Long>>, // (capturedAtEpochMs, amountMinor)
        currencyCode: String,
    ): BalanceHistory {
        var running = openingBalanceMinor ?: 0L
        val derivedPoints = postings
            .filter { !it.statusDeleted }
            .sortedBy { it.occurredAtEpochMs }
            .map { t ->
                running = if (t.directionDebit) running - t.amountMinor else running + t.amountMinor
                BalancePoint(
                    atEpochMs = t.occurredAtEpochMs,
                    observedMinor = null,
                    derivedMinor = running,
                    source = BalancePoint.Source.LEDGER_DERIVED,
                )
            }
        val observedPoints = snapshots
            .sortedBy { it.first }
            .map { (at, amount) ->
                BalancePoint(at, observedMinor = amount, derivedMinor = running, source = BalancePoint.Source.OBSERVED_SNAPSHOT)
            }
        val merged = (derivedPoints + observedPoints).sortedBy { it.atEpochMs }
        // Re-walk once more so each point carries the derived balance AT its time.
        var replay = openingBalanceMinor ?: 0L
        val sortedPostings = postings.filter { !it.statusDeleted }.sortedBy { it.occurredAtEpochMs }
        val finalPoints = mutableListOf<BalancePoint>()
        var pIdx = 0
        merged.forEach { point ->
            while (pIdx < sortedPostings.size && sortedPostings[pIdx].occurredAtEpochMs <= point.atEpochMs) {
                val t = sortedPostings[pIdx]
                replay = if (t.directionDebit) replay - t.amountMinor else replay + t.amountMinor
                pIdx++
            }
            finalPoints += point.copy(derivedMinor = replay)
        }
        // Gap detection: any consecutive pair of events far apart with no
        // observed snapshot in between means we cannot vouch for continuity.
        val GAP_MS = 45L * 24 * 60 * 60 * 1000
        var hasGaps = false
        for (i in 1 until finalPoints.size) {
            val prevObserved = finalPoints[i - 1].observedMinor != null
            val currObserved = finalPoints[i].observedMinor != null
            if (!prevObserved && !currObserved &&
                finalPoints[i].atEpochMs - finalPoints[i - 1].atEpochMs > GAP_MS
            ) {
                hasGaps = true
                break
            }
        }
        if (finalPoints.isEmpty()) hasGaps = true
        return BalanceHistory(accountId, finalPoints, hasGaps, currencyCode)
    }

    // ---- P19 #6: savings rate ----

    data class SavingsRate(
        val incomeMinor: Long,
        val expensesMinor: Long,
        /** (income - expenses) / income. Null when income == 0 (zero-income state). */
        val rate: Double?,
        val zeroIncome: Boolean,
        val incompleteHistory: Boolean,
    )

    /**
     * Savings rate over EXTERNAL flows only — transfers/CASH_MOVE excluded.
     * Zero income yields rate=null with zeroIncome=true (shown clearly, never
     * fabricated as 0% or ∞).
     */
    fun savingsRate(
        txns: List<LedgerTxnView>,
        fromDay: Long,
        toDay: Long,
        incompleteHistory: Boolean = false,
    ): SavingsRate {
        var income = 0L
        var expenses = 0L
        txns.filter { inWindow(it, fromDay, toDay) && !it.statusDeleted }.forEach { t ->
            when {
                isInternalMovement(t) -> Unit
                isExternalIncome(t) && t.kind == TxKind.INCOME.name -> income += t.amountMinor
                isExternalSpend(t) -> expenses += t.amountMinor
            }
        }
        val rate = if (income == 0L) null else (income - expenses).toDouble() / income.toDouble()
        return SavingsRate(income, expenses, rate, zeroIncome = income == 0L, incompleteHistory = incompleteHistory)
    }

    // ---- P19 #7: aligned period comparison ----

    data class Comparison<T>(
        val current: T,
        val previous: T,
        val currentRange: Pair<Long, Long>,
        val previousRange: Pair<Long, Long>,
    )

    /**
     * Explicit date-range alignment: the previous window is the immediately
     * preceding span of the SAME LENGTH as the current window (so a 29-day
     * February compares against an equal-length window, never a naive
     * calendar-month shift). Leap years handled by LocalDate arithmetic.
     */
    fun alignedRanges(currentStart: Long, currentEnd: Long): Pair<Pair<Long, Long>, Pair<Long, Long>> {
        require(currentEnd >= currentStart)
        val len = currentEnd - currentStart + 1
        val prevEnd = currentStart - 1
        val prevStart = prevEnd - len + 1
        return (currentStart to currentEnd) to (prevStart to prevEnd)
    }

    fun compareCashFlow(
        txns: List<LedgerTxnView>,
        currentStart: Long,
        currentEnd: Long,
        currencyCode: String,
        coverageIncomplete: Boolean = false,
    ): Comparison<CashFlow> {
        val (cur, prev) = alignedRanges(currentStart, currentEnd)
        return Comparison(
            current = cashFlow(txns, cur.first, cur.second, currencyCode, coverageIncomplete),
            previous = cashFlow(txns, prev.first, prev.second, currencyCode, coverageIncomplete),
            currentRange = cur,
            previousRange = prev,
        )
    }

    // ---- P19 #8: Pareto / top categories + income sources ----

    /**
     * Pareto analysis: returns rows plus the smallest prefix covering
     * [threshold] (e.g. 0.8) of net spend. Marks coverage explicitly.
     */
    data class Pareto(
        val breakdown: Breakdown,
        /** Number of top rows covering >= threshold of net spend. */
        val vitalFewCount: Int,
        val threshold: Double,
    )

    fun pareto(breakdown: Breakdown, threshold: Double = 0.80): Pareto {
        require(threshold in 0.0..1.0)
        if (breakdown.totalNetMinor <= 0) return Pareto(breakdown, 0, threshold)
        var cumulative = 0.0
        var count = 0
        for (row in breakdown.rows) {
            cumulative += row.shareOfNet
            count++
            if (cumulative >= threshold) break
        }
        return Pareto(breakdown, count, threshold)
    }

    /** Income-source analytics: INCOME credits grouped by counterparty/merchant. */
    fun incomeSources(
        txns: List<LedgerTxnView>,
        fromDay: Long,
        toDay: Long,
        currencyCode: String,
    ): Breakdown {
        data class Acc(var total: Long = 0, var count: Int = 0)
        val byKey = LinkedHashMap<String?, Acc>()
        txns.filter { inWindow(it, fromDay, toDay) && !it.statusDeleted }
            .filter { it.kind == TxKind.INCOME.name && !it.directionDebit }
            .forEach { t ->
                val key = t.merchant ?: t.counterpartyNormalized
                val acc = byKey.getOrPut(key) { Acc() }
                acc.total += t.amountMinor
                acc.count++
            }
        val total = byKey.values.sumOf { it.total }
        val rows = byKey.map { (key, acc) ->
            BreakdownRow(
                key = key,
                label = key,
                grossMinor = acc.total,
                refundedMinor = 0,
                netMinor = acc.total,
                txnCount = acc.count,
                shareOfNet = if (total <= 0) 0.0 else acc.total.toDouble() / total,
            )
        }.sortedByDescending { it.netMinor }
        return Breakdown(
            rows = rows,
            totalGrossMinor = total,
            totalRefundedMinor = 0,
            totalNetMinor = total,
            uncategorizedNetMinor = rows.firstOrNull { it.isUncategorized }?.netMinor ?: 0L,
            currencyCode = currencyCode,
        )
    }

    /**
     * Cash-flow calendar: per-day net external flow for one month window.
     * Days with no activity are absent (not zero-filled) so sparse history
     * stays honest.
     */
    data class CalendarDay(val epochDay: Long, val inflowMinor: Long, val outflowMinor: Long)

    fun cashFlowCalendar(
        txns: List<LedgerTxnView>,
        fromDay: Long,
        toDay: Long,
    ): List<CalendarDay> {
        data class Acc(var inflow: Long = 0, var outflow: Long = 0)
        val byDay = LinkedHashMap<Long, Acc>()
        txns.filter { inWindow(it, fromDay, toDay) && !it.statusDeleted }.forEach { t ->
            when {
                isInternalMovement(t) -> Unit
                isExternalIncome(t) -> byDay.getOrPut(t.localDateEpochDay) { Acc() }.inflow += t.amountMinor
                isExternalSpend(t) -> byDay.getOrPut(t.localDateEpochDay) { Acc() }.outflow += t.amountMinor
            }
        }
        return byDay.map { (day, acc) -> CalendarDay(day, acc.inflow, acc.outflow) }.sortedBy { it.epochDay }
    }
}
