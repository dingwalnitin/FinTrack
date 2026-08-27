package com.example.fintrack.domain

import com.example.fintrack.domain.model.BoundaryAction
import com.example.fintrack.domain.model.Budget
import com.example.fintrack.domain.model.BudgetExclusions
import com.example.fintrack.domain.model.BudgetScopeKind
import com.example.fintrack.domain.model.ProgressStatus
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.service.BudgetService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Stage 8 P16 — budget engine tests.
 *
 * Covers: month boundaries, refund treatment, transfers, exclusions,
 * rollover, reset and missing historical data (coverage flag).
 */
class BudgetServiceTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val svc = BudgetService(zone = zone)

    private fun budget(
        scope: BudgetScopeKind = BudgetScopeKind.CATEGORY,
        categoryId: String? = "cat-food",
        accountId: String? = null,
        target: Long = 10_000L,
        rollover: Boolean = false,
        cap: Long? = null,
        exclusions: BudgetExclusions = BudgetExclusions.NONE,
        startDay: Int = 1,
    ) = Budget(
        id = "b1",
        name = "Food",
        scopeKind = scope,
        categoryId = categoryId,
        accountId = accountId,
        periodType = "MONTHLY",
        startDayOfMonth = startDay,
        targetAmountMinor = target,
        currencyCode = "INR",
        rolloverEnabled = rollover,
        rolloverCapMinor = cap,
        exclusions = exclusions,
        status = com.example.fintrack.domain.model.BudgetStatus.ACTIVE,
        createdAtEpochMs = 0L,
    )

    private fun txn(
        id: String = "t1",
        kind: TxKind = TxKind.EXPENSE,
        amount: Long = 1_000L,
        day: Long,
        categoryId: String? = "cat-food",
        accountId: String = "acc-a",
        debit: Boolean = true,
        tags: Set<String> = emptySet(),
        deleted: Boolean = false,
    ) = BudgetService.TxnView(
        id = id, accountId = accountId, categoryId = categoryId, kind = kind,
        directionDebit = debit, amountMinor = amount, localDateEpochDay = day,
        tags = tags, statusDeleted = deleted,
    )

    // ---- eligibility / exclusions ----

    @Test
    fun `transfers and cash moves are never eligible spend`() {
        val b = budget()
        listOf(TxKind.TRANSFER, TxKind.CASH_MOVE).forEach { kind ->
            val e = svc.eligibility(b, txn(kind = kind, day = 5))
            assertTrue("kind $kind must be excluded", e is BudgetService.Eligibility.Excluded)
        }
    }

    @Test
    fun `income is excluded from spend`() {
        val e = svc.eligibility(budget(), txn(kind = TxKind.INCOME, debit = false, day = 5))
        assertTrue(e is BudgetService.Eligibility.Excluded)
    }

    @Test
    fun `category scope excludes other categories`() {
        val e = svc.eligibility(budget(), txn(categoryId = "cat-travel", day = 5))
        assertTrue(e is BudgetService.Eligibility.Excluded)
    }

    @Test
    fun `account filter excludes scoped-out accounts`() {
        val b = budget(scope = BudgetScopeKind.OVERALL, categoryId = null, exclusions = BudgetExclusions(excludedAccountIds = setOf("acc-b")))
        val e = svc.eligibility(b, txn(accountId = "acc-b", day = 5))
        assertEquals("ACCOUNT_FILTER", (e as BudgetService.Eligibility.Excluded).reason)
    }

    @Test
    fun `tag filter excludes tagged transactions`() {
        val b = budget(scope = BudgetScopeKind.OVERALL, categoryId = null, exclusions = BudgetExclusions(excludedTags = setOf("reimbursable")))
        val e = svc.eligibility(b, txn(tags = setOf("reimbursable"), day = 5))
        assertEquals("TAG_FILTER", (e as BudgetService.Eligibility.Excluded).reason)
    }

    @Test
    fun `preview classifies included vs excluded with reasons`() {
        val b = budget(scope = BudgetScopeKind.OVERALL, categoryId = null)
        val txns = listOf(
            txn(id = "in", kind = TxKind.EXPENSE, day = 3),
            txn(id = "tr", kind = TxKind.TRANSFER, day = 4),
        )
        val p = svc.preview(b, txns)
        assertEquals(1, p.included.size)
        assertEquals(1, p.excluded.size)
        assertEquals("NON_SPENDING_KIND", p.excluded.single().second)
    }

    // ---- progress ----

    @Test
    fun `progress sums eligible spend only`() {
        val b = budget(target = 10_000)
        val progress = svc.progress(
            b,
            listOf(
                txn(amount = 3_000, day = 2),
                txn(amount = 2_000, kind = TxKind.TRANSFER, day = 3),
                txn(amount = 500, kind = TxKind.FEE, day = 4),
            ),
            rolloverInMinor = 0,
            coverageIncomplete = false,
        )
        assertEquals(3_500, progress.spentMinor)
        assertEquals(ProgressStatus.UNDER, progress.status)
    }

    @Test
    fun `refund within period reduces usage`() {
        val b = budget(target = 10_000)
        val progress = svc.progress(
            b,
            listOf(
                txn(amount = 5_000, day = 2),
                txn(amount = 2_000, kind = TxKind.REFUND, debit = false, day = 6),
            ),
            rolloverInMinor = 0,
            coverageIncomplete = false,
        )
        assertEquals(2_000, progress.refundedMinor)
        assertEquals(3_000, progress.effectiveUsageMinor)
    }

    @Test
    fun `over budget status when usage exceeds target`() {
        val b = budget(target = 1_000)
        val progress = svc.progress(b, listOf(txn(amount = 1_500, day = 2)), 0, false)
        assertEquals(ProgressStatus.OVER, progress.status)
        assertTrue(progress.usageRatio > 1.0)
    }

    @Test
    fun `near limit status at 85 percent`() {
        val b = budget(target = 1_000)
        val progress = svc.progress(b, listOf(txn(amount = 900, day = 2)), 0, false)
        assertEquals(ProgressStatus.NEAR_LIMIT, progress.status)
    }

    @Test
    fun `coverage incomplete flag propagates for partial SMS history`() {
        val b = budget(target = 10_000)
        val progress = svc.progress(b, emptyList(), 0, coverageIncomplete = true)
        assertTrue(progress.coverageIncomplete)
    }

    // ---- month boundaries ----

    @Test
    fun `period containing mid-month day starts at anchor`() {
        val b = budget(startDay = 5)
        val (start, end) = svc.periodContaining(b, LocalDate.of(2026, 8, 20))
        assertEquals(LocalDate.of(2026, 8, 5), start)
        assertEquals(LocalDate.of(2026, 9, 4), end)
    }

    @Test
    fun `period before anchor rolls to previous month`() {
        val b = budget(startDay = 5)
        val (start, _) = svc.periodContaining(b, LocalDate.of(2026, 8, 2))
        assertEquals(LocalDate.of(2026, 7, 5), start)
    }

    @Test
    fun `anchor beyond month length clamps deterministically`() {
        val b = budget(startDay = 28)
        val (start, end) = svc.periodContaining(b, LocalDate.of(2026, 2, 28))
        // Feb has 28 days in 2026; the period starts on the clamped anchor
        // and ends the day before the next month's anchor.
        assertEquals(LocalDate.of(2026, 2, 28), start)
        assertEquals(LocalDate.of(2026, 3, 27), end)
    }

    // ---- rollover / reset ----

    @Test
    fun `rollover disabled resets at boundary`() {
        val p = svc.resolveBoundary(budget(rollover = false), 1, 30, previousRemaining = 4_000)
        assertEquals(BoundaryAction.RESET, p.boundaryAction)
        assertEquals(0, p.rolloverInMinor)
    }

    @Test
    fun `negative remaining always resets`() {
        val p = svc.resolveBoundary(budget(rollover = true), 1, 30, previousRemaining = -500)
        assertEquals(BoundaryAction.RESET, p.boundaryAction)
    }

    @Test
    fun `positive remaining rolls over fully`() {
        val p = svc.resolveBoundary(budget(rollover = true), 1, 30, previousRemaining = 4_000)
        assertEquals(BoundaryAction.ROLLOVER_APPLIED, p.boundaryAction)
        assertEquals(4_000, p.rolloverInMinor)
    }

    @Test
    fun `rollover capped at configured maximum`() {
        val p = svc.resolveBoundary(budget(rollover = true, cap = 2_000), 1, 30, previousRemaining = 4_000)
        assertEquals(BoundaryAction.ROLLOVER_CAPPED, p.boundaryAction)
        assertEquals(2_000, p.rolloverInMinor)
    }

    @Test
    fun `boundary resolution is deterministic`() {
        val b = budget(rollover = true, cap = 1_000)
        val a = svc.resolveBoundary(b, 1, 30, 5_000)
        val c = svc.resolveBoundary(b, 1, 30, 5_000)
        assertEquals(a.rolloverInMinor, c.rolloverInMinor)
        assertEquals(a.boundaryAction, c.boundaryAction)
    }
}
