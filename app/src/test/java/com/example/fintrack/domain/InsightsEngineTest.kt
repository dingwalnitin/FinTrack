package com.example.fintrack.domain

import com.example.fintrack.domain.service.InsightsEngine
import com.example.fintrack.domain.service.LedgerTxnView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 9 P19 — InsightsEngine tests on synthetic ledgers.
 *
 * Covers: dashboard aggregates, cash-flow separation of external vs internal,
 * category/merchant breakdown with refunds (gross vs net), rail analytics
 * with card-funded share NOT double counted, balance history with observed
 * snapshots never interpolated, savings-rate zero-income state, aligned
 * period comparison across a leap-year February, and Pareto.
 */
class InsightsEngineTest {

    private val engine = InsightsEngine()

    private fun txn(
        id: String,
        kind: String,
        debit: Boolean,
        amount: Long,
        day: Long,
        account: String = "acc1",
        category: String? = "cat-food",
        merchant: String? = null,
        counterparty: String? = null,
        rail: String? = null,
        cardMask: String? = null,
        currency: String = "INR",
        atMs: Long = day * 86_400_000L,
        deleted: Boolean = false,
    ) = LedgerTxnView(
        id = id,
        accountId = account,
        categoryId = category,
        kind = kind,
        directionDebit = debit,
        amountMinor = amount,
        localDateEpochDay = day,
        counterpartyNormalized = counterparty,
        merchant = merchant,
        currencyCode = currency,
        occurredAtEpochMs = atMs,
        subtype = null,
        statusDeleted = deleted,
        rail = rail,
        cardMask = cardMask,
    )

    // ---- dashboard ----

    @Test
    fun `dashboard separates income spend refunds and excludes transfers`() {
        val txns = listOf(
            txn("1", "INCOME", false, 500_000L, 10),
            txn("2", "EXPENSE", true, 100_000L, 11, merchant = "Swiggy"),
            txn("3", "REFUND", false, 20_000L, 12),
            txn("4", "TRANSFER", true, 300_000L, 12),   // owned-account movement
            txn("5", "CASH_MOVE", true, 50_000L, 13),
        )
        val s = engine.dashboardSummary(txns, fromDay = 1, toDay = 28, currencyCode = "INR")
        assertEquals(500_000L, s.incomeNetMinor)
        assertEquals(100_000L, s.spendGrossMinor)
        assertEquals(20_000L, s.spendRefundedMinor)
        assertEquals(80_000L, s.spendNetMinor)
    }

    @Test
    fun `dashboard recent list is bounded and skips tombstones`() {
        val txns = (1..10).map { txn("$it", "EXPENSE", true, 1_000L, it.toLong()) } +
            txn("dead", "EXPENSE", true, 9_999L, 11, deleted = true)
        val s = engine.dashboardSummary(txns, 1, 30, "INR", recentLimit = 3)
        assertEquals(3, s.recentTransactions.size)
        assertTrue(s.recentTransactions.none { it.id == "dead" })
    }

    // ---- cash flow ----

    @Test
    fun `cash flow keeps internal transfers out of external flows`() {
        val txns = listOf(
            txn("in", "INCOME", false, 1_000_000L, 5),
            txn("out", "EXPENSE", true, 250_000L, 6),
            txn("xfer-out", "TRANSFER", true, 200_000L, 7),
            txn("xfer-in", "TRANSFER", false, 200_000L, 7, account = "acc2"),
        )
        val cf = engine.cashFlow(txns, 1, 30, "INR")
        assertEquals(1_000_000L, cf.inflowExternalMinor)
        assertEquals(250_000L, cf.outflowExternalMinor)
        // Both sides of the transfer counted once each as internal volume.
        assertEquals(400_000L, cf.internalTransfersMinor)
        assertEquals(750_000L, cf.netExternalMinor)
    }

    @Test
    fun `monthly series produces one bucket per month with correct bounds`() {
        val today = java.time.LocalDate.of(2026, 8, 26)
        val txns = listOf(
            txn("aug", "EXPENSE", true, 10_000L, today.withDayOfMonth(2).toEpochDay()),
            txn(
                "jul",
                "EXPENSE", true, 20_000L,
                today.minusMonths(1).withDayOfMonth(3).toEpochDay(),
            ),
        )
        val series = engine.monthlyCashFlow(txns, monthsBack = 3, today = today, currencyCode = "INR")
        assertEquals(3, series.size)
        assertEquals(0L, series[0].outflowExternalMinor)          // June: empty
        assertEquals(20_000L, series[1].outflowExternalMinor)     // July
        assertEquals(10_000L, series[2].outflowExternalMinor)     // August
    }

    // ---- breakdown ----

    @Test
    fun `category breakdown nets refunds per group and surfaces uncategorized`() {
        val txns = listOf(
            txn("e1", "EXPENSE", true, 50_000L, 5, category = "cat-food", merchant = "Swiggy"),
            txn("r1", "REFUND", false, 10_000L, 6, category = "cat-food", merchant = "Swiggy"),
            txn("e2", "EXPENSE", true, 30_000L, 7, category = null),
        )
        val bd = engine.spendBreakdown(txns, 1, 30, InsightsEngine.Grouping.CATEGORY, currencyCode = "INR")
        assertEquals(80_000L, bd.totalGrossMinor)
        assertEquals(10_000L, bd.totalRefundedMinor)
        assertEquals(70_000L, bd.totalNetMinor)
        assertEquals(30_000L, bd.uncategorizedNetMinor)
        val food = bd.rows.first { it.key == "cat-food" }
        assertEquals(40_000L, food.netMinor)
        assertTrue(bd.rows.any { it.isUncategorized })
    }

    @Test
    fun `merchant grouping falls back to normalized counterparty`() {
        val txns = listOf(
            txn("a", "EXPENSE", true, 10_000L, 5, merchant = null, counterparty = "swiggy"),
            txn("b", "EXPENSE", true, 15_000L, 6, merchant = "Swiggy"),
        )
        val bd = engine.spendBreakdown(txns, 1, 30, InsightsEngine.Grouping.MERCHANT, currencyCode = "INR")
        assertEquals(1, bd.rows.size)
        assertEquals(25_000L, bd.rows.single().netMinor)
    }

    @Test
    fun `account filter narrows breakdown`() {
        val txns = listOf(
            txn("a", "EXPENSE", true, 10_000L, 5, account = "acc1"),
            txn("b", "EXPENSE", true, 99_000L, 6, account = "acc2"),
        )
        val bd = engine.spendBreakdown(
            txns, 1, 30, InsightsEngine.Grouping.CATEGORY,
            accountIds = setOf("acc1"), currencyCode = "INR",
        )
        assertEquals(10_000L, bd.totalNetMinor)
    }

    // ---- rails ----

    @Test
    fun `rail analytics separates funding instrument without double counting`() {
        val txns = listOf(
            // UPI spend funded by credit card: counts once under UPI, card share surfaced separately.
            txn("upi-card", "EXPENSE", true, 40_000L, 5, rail = "UPI", cardMask = "4411"),
            txn("upi-bank", "EXPENSE", true, 60_000L, 6, rail = "UPI"),
            txn("pos", "EXPENSE", true, 25_000L, 7, rail = "CARD_POS", cardMask = "4411"),
        )
        val ra = engine.railAnalytics(txns, 1, 30, currencyCode = "INR")
        assertEquals(125_000L, ra.totalSpendMinor)
        val upi = ra.rows.first { it.rail == "UPI" }
        assertEquals(100_000L, upi.spendMinor)
        assertEquals(40_000L, upi.fundingInstrumentMinor)
        assertTrue(upi.fundingInstrumentLabel!!.contains("4411"))
        val pos = ra.rows.first { it.rail == "CARD_POS" }
        assertEquals(25_000L, pos.fundingInstrumentMinor)
    }

    // ---- balance history ----

    @Test
    fun `balance history merges observed snapshots and derived points without interpolation`() {
        val postings = listOf(
            txn("p1", "EXPENSE", true, 100_000L, 5),
            txn("p2", "INCOME", false, 300_000L, 10),
        )
        val h = engine.balanceHistory(
            accountId = "acc1",
            openingBalanceMinor = 500_000L,
            postings = postings,
            snapshots = listOf((6L * 86_400_000L) to 400_000L), // observed after p1
            currencyCode = "INR",
        )
        assertEquals(3, h.points.size)
        assertFalse(h.hasGaps)
        val observed = h.points.filter { it.source == InsightsEngine.BalancePoint.Source.OBSERVED_SNAPSHOT }
        assertEquals(1, observed.size)
        assertEquals(400_000L, observed.single().observedMinor)
        // Derived value AT the snapshot time equals the observed value here.
        assertEquals(400_000L, observed.single().derivedMinor)
        // Final derived point reflects all postings.
        val lastDerived = h.points.last { it.source == InsightsEngine.BalancePoint.Source.LEDGER_DERIVED }
        assertEquals(700_000L, lastDerived.derivedMinor)
    }

    @Test
    fun `balance history flags gaps instead of interpolating`() {
        val farApart = listOf(
            txn("p1", "EXPENSE", true, 100_000L, 1),
            txn("p2", "EXPENSE", true, 100_000L, 200), // >45 days later, no snapshot between
        )
        val h = engine.balanceHistory("acc1", 0L, farApart, emptyList(), "INR")
        assertTrue(h.hasGaps)
    }

    @Test
    fun `empty history is flagged as gap`() {
        val h = engine.balanceHistory("acc1", null, emptyList(), emptyList(), "INR")
        assertTrue(h.hasGaps)
        assertTrue(h.points.isEmpty())
    }

    // ---- savings rate ----

    @Test
    fun `savings rate uses external flows only`() {
        val txns = listOf(
            txn("sal", "INCOME", false, 1_000_000L, 5),
            txn("spend", "EXPENSE", true, 400_000L, 6),
            txn("xfer", "TRANSFER", true, 900_000L, 7),
        )
        val sr = engine.savingsRate(txns, 1, 30)
        assertEquals(1_000_000L, sr.incomeMinor)
        assertEquals(400_000L, sr.expensesMinor)
        assertEquals(0.6, sr.rate!!, 1e-9)
        assertFalse(sr.zeroIncome)
    }

    @Test
    fun `zero income yields explicit non-computable state`() {
        val sr = engine.savingsRate(listOf(txn("e", "EXPENSE", true, 100L, 5)), 1, 30)
        assertNull(sr.rate)
        assertTrue(sr.zeroIncome)
    }

    // ---- period comparison / leap year ----

    @Test
    fun `aligned ranges compare equal-length windows across february leap year`() {
        // Current window: Feb 1 2024 .. Feb 29 2024 (leap year, 29 days).
        val start = java.time.LocalDate.of(2024, 2, 1).toEpochDay()
        val end = java.time.LocalDate.of(2024, 2, 29).toEpochDay()
        val (cur, prev) = engine.alignedRanges(start, end)
        assertEquals(29, cur.second - cur.first + 1)
        assertEquals(29, prev.second - prev.first + 1)
        assertEquals(cur.first - 1, prev.second)
        // Previous window ends Jan 31 2024 and spans back 29 days.
        assertEquals(java.time.LocalDate.of(2024, 1, 31).toEpochDay(), prev.second)
        assertEquals(java.time.LocalDate.of(2024, 1, 3).toEpochDay(), prev.first)
    }

    @Test
    fun `compareCashFlow aligns previous window by length not calendar month`() {
        val txns = listOf(
            txn("cur", "EXPENSE", true, 10_000L, java.time.LocalDate.of(2024, 2, 15).toEpochDay()),
            txn("prev", "EXPENSE", true, 20_000L, java.time.LocalDate.of(2024, 1, 20).toEpochDay()),
        )
        val cmp = engine.compareCashFlow(
            txns,
            java.time.LocalDate.of(2024, 2, 1).toEpochDay(),
            java.time.LocalDate.of(2024, 2, 29).toEpochDay(),
            "INR",
        )
        assertEquals(10_000L, cmp.current.outflowExternalMinor)
        assertEquals(20_000L, cmp.previous.outflowExternalMinor)
        assertEquals(cmp.currentRange.second - cmp.currentRange.first, cmp.previousRange.second - cmp.previousRange.first)
    }

    // ---- Pareto & income sources ----

    @Test
    fun `pareto finds vital few covering threshold`() {
        val txns = listOf(
            txn("big", "EXPENSE", true, 70_000L, 5, category = "rent"),
            txn("mid", "EXPENSE", true, 20_000L, 6, category = "food"),
            txn("small", "EXPENSE", true, 10_000L, 7, category = "fun"),
        )
        val bd = engine.spendBreakdown(txns, 1, 30, InsightsEngine.Grouping.CATEGORY, currencyCode = "INR")
        val p = engine.pareto(bd, threshold = 0.80)
        assertEquals(2, p.vitalFewCount) // rent (70%) + food (20%) = 90% >= 80%
    }

    @Test
    fun `income sources group by counterparty`() {
        val txns = listOf(
            txn("s1", "INCOME", false, 800_000L, 5, merchant = "ABC Corp"),
            txn("s2", "INCOME", false, 200_000L, 6, merchant = "Freelance"),
            txn("not-income", "EXPENSE", true, 50_000L, 7, merchant = "Swiggy"),
        )
        val inc = engine.incomeSources(txns, 1, 30, "INR")
        assertEquals(1_000_000L, inc.totalNetMinor)
        assertEquals(2, inc.rows.size)
        assertEquals("ABC Corp", inc.rows.first().label)
    }

    // ---- calendar ----

    @Test
    fun `calendar emits only days with activity`() {
        val txns = listOf(
            txn("a", "EXPENSE", true, 100L, 5),
            txn("b", "INCOME", false, 200L, 5),
            txn("c", "EXPENSE", true, 300L, 9),
            txn("d", "TRANSFER", true, 400L, 10), // excluded
        )
        val cal = engine.cashFlowCalendar(txns, 1, 30)
        assertEquals(2, cal.size)
        val day5 = cal.first { it.epochDay == 5L }
        assertEquals(200L, day5.inflowMinor)
        assertEquals(100L, day5.outflowMinor)
    }

    // ---- Stage 13 (E): regression guards for the §2 class of bug ----
    // The income/expense sign must ALWAYS come from directionDebit (semantic
    // kind), never from the sign of amountMinor (which is always absolute).

    @Test
    fun `regression_incomeNeverCountedAsExpenseEvenWithSignAmbiguity`() {
        // amountMinor is positive for BOTH rows; only directionDebit differs.
        // If the engine ever keyed off amount sign, the income would be miscounted.
        val txns = listOf(
            txn("in", "INCOME", debit = false, amount = 500_000L, 10),
            txn("out", "EXPENSE", debit = true, amount = 200_000L, 11),
        )
        val cf = engine.cashFlow(txns, 1, 30, "INR")
        assertEquals(500_000L, cf.inflowExternalMinor)
        assertEquals(200_000L, cf.outflowExternalMinor)
        assertEquals(300_000L, cf.netExternalMinor)
    }

    @Test
    fun `regression_incomeSourcesOnlyCountsCredits`() {
        val txns = listOf(
            txn("sal", "INCOME", debit = false, amount = 900_000L, 5, merchant = "Employer"),
            txn("spend", "EXPENSE", debit = true, amount = 900_000L, 6, merchant = "Employer"),
        )
        val inc = engine.incomeSources(txns, 1, 30, "INR")
        // Only the credit contributes; the expense with the same counterparty must not inflate income.
        assertEquals(900_000L, inc.totalNetMinor)
        assertEquals(1, inc.rows.size)
    }

    @Test
    fun `regression_savingsRateIgnoresRefundsAsIncome`() {
        val txns = listOf(
            txn("sal", "INCOME", debit = false, amount = 1_000_000L, 5),
            txn("spend", "EXPENSE", debit = true, amount = 300_000L, 6),
            txn("refund", "REFUND", debit = false, amount = 50_000L, 7),
        )
        val sr = engine.savingsRate(txns, 1, 30)
        assertEquals(1_000_000L, sr.incomeMinor)     // refund is NOT income
        assertEquals(300_000L, sr.expensesMinor)
        assertEquals(0.7, sr.rate!!, 1e-9)
    }

    @Test
    fun `regression_perAccountBalanceUsesDirectionNotSign`() {
        val postings = listOf(
            txn("spend", "EXPENSE", debit = true, amount = 40_000L, 5),   // money OUT
            txn("salary", "INCOME", debit = false, amount = 40_000L, 6),   // money IN
        )
        // Opening + out + in => balance returns to opening (net 0 movement).
        val h = engine.balanceHistory("acc1", 100_000L, postings, emptyList(), "INR")
        val lastDerived = h.points.last { it.source == InsightsEngine.BalancePoint.Source.LEDGER_DERIVED }
        assertEquals(100_000L, lastDerived.derivedMinor)
    }

    @Test
    fun `regression_emptyLedgerDoesNotCrashAnyAggregate`() {
        val empty = emptyList<LedgerTxnView>()
        val cf = engine.cashFlow(empty, 1, 30, "INR")
        assertEquals(0L, cf.netExternalMinor)
        val bd = engine.spendBreakdown(empty, 1, 30, InsightsEngine.Grouping.CATEGORY, currencyCode = "INR")
        assertEquals(0L, bd.totalNetMinor)
        assertTrue(bd.rows.isEmpty())
        val sr = engine.savingsRate(empty, 1, 30)
        assertTrue(sr.zeroIncome)
        assertNull(sr.rate)
        val cal = engine.cashFlowCalendar(empty, 1, 30)
        assertTrue(cal.isEmpty())
        val p = engine.pareto(bd)
        assertEquals(0, p.vitalFewCount)
    }
}
