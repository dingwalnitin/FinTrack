package com.example.fintrack.domain

import com.example.fintrack.domain.model.EmiInstallment
import com.example.fintrack.domain.model.EmiInstallmentStatus
import com.example.fintrack.domain.model.EmiPlan
import com.example.fintrack.domain.model.EmiPlanStatus
import com.example.fintrack.domain.model.EmiPreclosure
import com.example.fintrack.domain.model.EmiPreclosureKind
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.service.EmiInstallmentSink
import com.example.fintrack.domain.service.EmiPlanService
import com.example.fintrack.domain.service.EmiPlanSink
import com.example.fintrack.domain.service.EmiPreclosureSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Stage 6 P13 — EMI plan / installment / preclosure / refinancing tests.
 *
 *  - openPlan creates a plan; opening with the same identity is a no-op
 *  - recordInstallmentPayment marks an installment PAID (or PARTIAL)
 *  - markInstallmentMissed only marks MISSED when no payment exists
 *  - recordPreclosure closes the plan and records the preclosure event
 *  - refinance transitions the old plan to REFINANCED and creates a new
 *    one with `refinancedFromPlanId` set
 *  - progress reports paid/partial/missed counts, remaining and
 *    outstanding principal, with explicit coverage flags
 *  - missed/partial are NEVER invented: if a month has no installment
 *    row, progress shows it as "incomplete" via the missing count
 *
 * Includes a realistic fixture with same-amount competing plans (a
 * common Indian finance scenario where two EMIs of the same amount are
 * active on the same account) and a refinancing case.
 */
class EmiPlanServiceTest {

    // ---- in-memory fakes ----

    private class FakePlanSink : EmiPlanSink {
        val plans = linkedMapOf<String, EmiPlan>()
        override suspend fun insertPlan(plan: EmiPlan) {
            plans.putIfAbsent(plan.id.value, plan)
        }
        override suspend fun updatePlan(plan: EmiPlan) {
            plans[plan.id.value] = plan
        }
        override suspend fun findPlan(id: EntityId): EmiPlan? = plans[id.value]
    }

    private class FakeInstallmentSink : EmiInstallmentSink {
        val installments = mutableListOf<EmiInstallment>()
        val byId = linkedMapOf<String, EmiInstallment>()
        override suspend fun insertInstallment(installment: EmiInstallment, identity: String) {
            if (byId.putIfAbsent(installment.id.value, installment) == null) {
                installments += installment
            }
        }
        override suspend fun updateInstallment(installment: EmiInstallment) {
            byId[installment.id.value] = installment
            val idx = installments.indexOfFirst { it.id == installment.id }
            if (idx >= 0) installments[idx] = installment
        }
        override suspend fun findByPlanAndNumber(planId: EntityId, installmentNumber: Int): EmiInstallment? =
            byId.values.firstOrNull { it.planId == planId && it.installmentNumber == installmentNumber }
    }

    private class FakePreclosureSink : EmiPreclosureSink {
        val closures = mutableListOf<EmiPreclosure>()
        override suspend fun insertPreclosure(preclosure: EmiPreclosure, identity: String) {
            if (closures.none { it.id == preclosure.id }) closures += preclosure
        }
    }

    private fun newService() = EmiPlanService(
        planSink = FakePlanSink(),
        installmentSink = FakeInstallmentSink(),
        preclosureSink = FakePreclosureSink(),
    )

    private fun smsProv(at: Instant = Instant.parse("2026-08-01T00:00:00Z")) =
        Provenance(SourceKind.SMS, "sms-v1", at)

    // ---- Plan opening ----

    @Test
    fun `openPlan creates an ACTIVE plan with end date derived from start plus months`() = runTest {
        val svc = newService()
        val plan = svc.openPlan(
            emiAccountId = EntityId("acc-bank"),
            merchantOrBiller = "Apple India",
            referenceId = "APL-EM-7891",
            principalMinor = 80_000_00L,
            interestRateAnnualBps = 1450,
            installmentAmountMinor = 7_000_00L,
            totalInstallments = 12,
            startDate = LocalDate.of(2026, 8, 5),
            currencyCode = "INR",
            provenance = smsProv(),
            firstInstallmentDueDate = LocalDate.of(2026, 9, 5),
            firstInstallmentAmountMinor = 7_000_00L,
        ).getOrThrow()
        assertEquals(EmiPlanStatus.ACTIVE, plan.status)
        assertEquals(12, plan.totalInstallments)
        assertEquals(LocalDate.of(2027, 7, 5), plan.endDate)
    }

    @Test
    fun `openPlan allows unknown principal or interest without guessing`() = runTest {
        val svc = newService()
        val plan = svc.openPlan(
            emiAccountId = EntityId("acc-bank"),
            merchantOrBiller = "Flipkart",
            referenceId = "FK-NB",
            principalMinor = null,           // unknown
            interestRateAnnualBps = null,     // unknown
            installmentAmountMinor = 999_00L, // only installment amount evidenced
            totalInstallments = 6,
            startDate = LocalDate.of(2026, 9, 15),
            currencyCode = "INR",
            provenance = smsProv(),
        ).getOrThrow()
        assertNull(plan.principalMinor)
        assertNull(plan.interestRateAnnualBps)
        assertEquals(999_00L, plan.installmentAmountMinor)
    }

    @Test
    fun `openPlan rejects bad rate`() = runTest {
        val svc = newService()
        val r = svc.openPlan(
            emiAccountId = EntityId("a"),
            merchantOrBiller = "X", referenceId = "R",
            principalMinor = 1_000_00L,
            interestRateAnnualBps = 50_000,   // 500% — invalid
            installmentAmountMinor = 100_00L,
            totalInstallments = 6,
            startDate = LocalDate.of(2026, 9, 1),
            currencyCode = "INR",
            provenance = smsProv(),
        )
        assertTrue(r.isFailure)
    }

    // ---- Installment matching ----

    @Test
    fun `recordInstallmentPayment marks installment PAID and links the transaction`() = runTest {
        val svc = newService()
        val plan = svc.openPlan(
            emiAccountId = EntityId("acc-bank"),
            merchantOrBiller = "Apple India", referenceId = "APL-EM-7891",
            principalMinor = 80_000_00L, interestRateAnnualBps = 1450,
            installmentAmountMinor = 7_000_00L, totalInstallments = 12,
            startDate = LocalDate.of(2026, 8, 5), currencyCode = "INR",
            provenance = smsProv(),
            firstInstallmentDueDate = LocalDate.of(2026, 9, 5),
            firstInstallmentAmountMinor = 7_000_00L,
        ).getOrThrow()
        val paid = svc.recordInstallmentPayment(
            planId = plan.id, installmentNumber = 1,
            transactionId = "t-paid-1", amountPaidMinor = 7_000_00L,
            provenance = smsProv(),
        ).getOrThrow()
        assertEquals(EmiInstallmentStatus.PAID, paid.status)
        assertEquals(7_000_00L, paid.amountPaidMinor)
        assertEquals("t-paid-1", paid.transactionId)
    }

    @Test
    fun `recordInstallmentPayment with amount below due marks installment PARTIAL`() = runTest {
        val svc = newService()
        val plan = svc.openPlan(
            emiAccountId = EntityId("acc-bank"),
            merchantOrBiller = "Apple India", referenceId = "APL-EM-7891",
            principalMinor = 80_000_00L, interestRateAnnualBps = 1450,
            installmentAmountMinor = 7_000_00L, totalInstallments = 12,
            startDate = LocalDate.of(2026, 8, 5), currencyCode = "INR",
            provenance = smsProv(),
            firstInstallmentDueDate = LocalDate.of(2026, 9, 5),
            firstInstallmentAmountMinor = 7_000_00L,
        ).getOrThrow()
        val partial = svc.recordInstallmentPayment(
            planId = plan.id, installmentNumber = 1,
            transactionId = "t-partial-1", amountPaidMinor = 3_000_00L,
            provenance = smsProv(),
        ).getOrThrow()
        assertEquals(EmiInstallmentStatus.PARTIAL, partial.status)
    }

    @Test
    fun `markInstallmentMissed only marks MISSED when no payment exists`() = runTest {
        val svc = newService()
        val plan = svc.openPlan(
            emiAccountId = EntityId("acc-bank"),
            merchantOrBiller = "Apple India", referenceId = "APL-EM-7891",
            principalMinor = 80_000_00L, interestRateAnnualBps = 1450,
            installmentAmountMinor = 7_000_00L, totalInstallments = 12,
            startDate = LocalDate.of(2026, 8, 5), currencyCode = "INR",
            provenance = smsProv(),
            firstInstallmentDueDate = LocalDate.of(2026, 9, 5),
            firstInstallmentAmountMinor = 7_000_00L,
        ).getOrThrow()
        val missed = svc.markInstallmentMissed(
            planId = plan.id, installmentNumber = 1, provenance = smsProv(),
        ).getOrThrow()
        assertEquals(EmiInstallmentStatus.MISSED, missed.status)
    }

    @Test
    fun `markInstallmentMissed is a no-op on a paid installment`() = runTest {
        val svc = newService()
        val plan = svc.openPlan(
            emiAccountId = EntityId("acc-bank"),
            merchantOrBiller = "Apple India", referenceId = "APL-EM-7891",
            principalMinor = 80_000_00L, interestRateAnnualBps = 1450,
            installmentAmountMinor = 7_000_00L, totalInstallments = 12,
            startDate = LocalDate.of(2026, 8, 5), currencyCode = "INR",
            provenance = smsProv(),
            firstInstallmentDueDate = LocalDate.of(2026, 9, 5),
            firstInstallmentAmountMinor = 7_000_00L,
        ).getOrThrow()
        svc.recordInstallmentPayment(plan.id, 1, "t-x", 7_000_00L, smsProv())
        val stillPaid = svc.markInstallmentMissed(plan.id, 1, smsProv()).getOrThrow()
        assertEquals(EmiInstallmentStatus.PAID, stillPaid.status)
    }

    // ---- Preclosure ----

    @Test
    fun `preclosure closes plan and records the event with evidenced fee`() = runTest {
        val svc = newService()
        val plan = svc.openPlan(
            emiAccountId = EntityId("acc-bank"),
            merchantOrBiller = "Apple India", referenceId = "APL-EM-7891",
            principalMinor = 80_000_00L, interestRateAnnualBps = 1450,
            installmentAmountMinor = 7_000_00L, totalInstallments = 12,
            startDate = LocalDate.of(2026, 8, 5), currencyCode = "INR",
            provenance = smsProv(),
            firstInstallmentDueDate = LocalDate.of(2026, 9, 5),
            firstInstallmentAmountMinor = 7_000_00L,
        ).getOrThrow()
        val pre = svc.recordPreclosure(
            planId = plan.id,
            occurredAt = Instant.parse("2026-11-10T00:00:00Z"),
            principalOutstandingMinor = 50_000_00L,
            feeMinor = 500_00L,
            adjustmentMinor = null,
            kind = EmiPreclosureKind.FORECLOSURE,
            transactionId = "t-foreclose",
            provenance = smsProv(),
        ).getOrThrow()
        assertEquals(EmiPreclosureKind.FORECLOSURE, pre.kind)
        assertEquals(500_00L, pre.feeMinor)
        // The plan is now PRECLOSED
        val planSink = (svc).planSink() as FakePlanSink
        assertEquals(EmiPlanStatus.PRECLOSED, planSink.plans[plan.id.value]!!.status)
    }

    // ---- Refinancing ----

    @Test
    fun `refinance closes the old plan and creates a new one linked to it`() = runTest {
        val svc = newService()
        val plan = svc.openPlan(
            emiAccountId = EntityId("acc-bank"),
            merchantOrBiller = "Apple India", referenceId = "APL-EM-7891",
            principalMinor = 80_000_00L, interestRateAnnualBps = 1450,
            installmentAmountMinor = 7_000_00L, totalInstallments = 12,
            startDate = LocalDate.of(2026, 8, 5), currencyCode = "INR",
            provenance = smsProv(),
            firstInstallmentDueDate = LocalDate.of(2026, 9, 5),
            firstInstallmentAmountMinor = 7_000_00L,
        ).getOrThrow()
        val newPlan = svc.refinance(
            previousPlanId = plan.id,
            newEmiAccountId = EntityId("acc-bank2"),
            merchantOrBiller = "Apple India", referenceId = "APL-EM-7891-R1",
            principalMinor = 50_000_00L, interestRateAnnualBps = 1200,
            installmentAmountMinor = 5_500_00L, totalInstallments = 10,
            startDate = LocalDate.of(2026, 11, 1), currencyCode = "INR",
            provenance = smsProv(),
        ).getOrThrow()
        val planSink = (svc).planSink() as FakePlanSink
        // Old plan is REFINANCED; new plan is ACTIVE.
        assertEquals(EmiPlanStatus.REFINANCED, planSink.plans[plan.id.value]!!.status)
        assertEquals(EmiPlanStatus.ACTIVE, planSink.plans[newPlan.id.value]!!.status)
        // ref link
        assertEquals(plan.id, newPlan.refinancedFromPlanId)
    }

    @Test
    fun `refinance on an already-refinanced plan is rejected`() = runTest {
        val svc = newService()
        val plan = svc.openPlan(
            emiAccountId = EntityId("acc-bank"),
            merchantOrBiller = "Apple India", referenceId = "APL-EM-7891",
            principalMinor = 80_000_00L, interestRateAnnualBps = 1450,
            installmentAmountMinor = 7_000_00L, totalInstallments = 12,
            startDate = LocalDate.of(2026, 8, 5), currencyCode = "INR",
            provenance = smsProv(),
            firstInstallmentDueDate = LocalDate.of(2026, 9, 5),
            firstInstallmentAmountMinor = 7_000_00L,
        ).getOrThrow()
        svc.refinance(plan.id, EntityId("acc-bank2"), "Apple India", "R1",
            50_000_00L, 1200, 5_500_00L, 10, LocalDate.of(2026, 11, 1), "INR", smsProv())
        val second = svc.refinance(plan.id, EntityId("acc-bank3"), "Apple India", "R2",
            40_000_00L, 1200, 5_000_00L, 8, LocalDate.of(2026, 12, 1), "INR", smsProv())
        assertTrue(second.isFailure)
    }

    // ---- Progress ----

    @Test
    fun `progress reports paid, partial, missed, remaining and coverage flags`() = runTest {
        val svc = newService()
        val plan = svc.openPlan(
            emiAccountId = EntityId("acc-bank"),
            merchantOrBiller = "Apple India", referenceId = "APL-EM-7891",
            principalMinor = 80_000_00L, interestRateAnnualBps = 1450,
            installmentAmountMinor = 7_000_00L, totalInstallments = 12,
            startDate = LocalDate.of(2026, 8, 5), currencyCode = "INR",
            provenance = smsProv(),
            firstInstallmentDueDate = LocalDate.of(2026, 9, 5),
            firstInstallmentAmountMinor = 7_000_00L,
        ).getOrThrow()
        // Build installments 1..3 directly via the sink (deterministic).
        val instSink = (svc).installmentSink() as FakeInstallmentSink
        repeat(3) { idx ->
            val n = idx + 1
            val inst = EmiInstallment(
                id = EntityId.generate(),
                planId = plan.id,
                installmentNumber = n,
                dueDate = LocalDate.of(2026, 8 + n, 5),
                amountDueMinor = 7_000_00L,
                amountPaidMinor = null,
                currencyCode = "INR",
                status = EmiInstallmentStatus.DUE,
                transactionId = null,
                installmentIdentity = "i-$n",
                provenance = smsProv(),
            )
            instSink.insertInstallment(inst, "i-$n")
        }
        svc.recordInstallmentPayment(plan.id, 1, "t-1", 7_000_00L, smsProv())
        svc.recordInstallmentPayment(plan.id, 2, "t-2", 3_000_00L, smsProv()) // partial
        svc.markInstallmentMissed(plan.id, 3, smsProv())

        val progress = svc.progress(plan, instSink.installments.toList())
        assertEquals(1, progress.paidInstallments)
        assertEquals(1, progress.partialInstallments)
        assertEquals(1, progress.missedInstallments)
        assertEquals(12, progress.totalInstallments)
        assertEquals(10, progress.remainingInstallments)
        // 7000.00 INR (paid in full) + 3000.00 INR (partial) = 10000.00 INR
        assertEquals(1_000_000L, progress.paidMinor)
        // 80000.00 - 10000.00 = 70000.00
        assertEquals(7_000_000L, progress.outstandingPrincipalMinor)
        assertTrue(progress.coverage.hasPrincipal)
        assertTrue(progress.coverage.hasInterest)
        assertTrue(progress.coverage.hasInstallmentAmount)
        assertTrue(progress.coverage.hasTotalCount)
    }

    @Test
    fun `progress with unknown principal reports outstanding as null`() = runTest {
        val svc = newService()
        val plan = svc.openPlan(
            emiAccountId = EntityId("acc-bank"),
            merchantOrBiller = "Unknown", referenceId = null,
            principalMinor = null, interestRateAnnualBps = null,
            installmentAmountMinor = null, totalInstallments = null,
            startDate = null, currencyCode = "INR",
            provenance = smsProv(),
        ).getOrThrow()
        val progress = svc.progress(plan, emptyList())
        assertNull(progress.outstandingPrincipalMinor)
        assertNull(progress.remainingInstallments)
        assertFalse(progress.coverage.hasPrincipal)
        assertFalse(progress.coverage.hasInterest)
    }

    // ---- Same-amount competing plans (realistic Indian scenario) ----

    @Test
    fun `two same-amount competing plans on the same account are independent`() = runTest {
        val svc = newService()
        val apple = svc.openPlan(
            emiAccountId = EntityId("acc-bank"),
            merchantOrBiller = "Apple India", referenceId = "APL-1",
            principalMinor = 80_000_00L, interestRateAnnualBps = 1450,
            installmentAmountMinor = 7_000_00L, totalInstallments = 12,
            startDate = LocalDate.of(2026, 8, 5), currencyCode = "INR",
            provenance = smsProv(),
        ).getOrThrow()
        val flipkart = svc.openPlan(
            emiAccountId = EntityId("acc-bank"),
            merchantOrBiller = "Flipkart", referenceId = "FK-1",
            principalMinor = 80_000_00L, interestRateAnnualBps = 1500,
            installmentAmountMinor = 7_000_00L, totalInstallments = 12,
            startDate = LocalDate.of(2026, 8, 10), currencyCode = "INR",
            provenance = smsProv(),
        ).getOrThrow()
        // Both plans exist with the same amount and account.
        val planSink = (svc).planSink() as FakePlanSink
        assertEquals(2, planSink.plans.size)
        assertNotNull(planSink.plans[apple.id.value])
        assertNotNull(planSink.plans[flipkart.id.value])
    }
}

// helper extensions to access the private fakes from tests
private fun com.example.fintrack.domain.service.EmiPlanService.planSink() =
    this.javaClass.getDeclaredField("planSink").apply { isAccessible = true }.get(this) as EmiPlanSink
private fun com.example.fintrack.domain.service.EmiPlanService.installmentSink() =
    this.javaClass.getDeclaredField("installmentSink").apply { isAccessible = true }.get(this) as EmiInstallmentSink
