package com.example.fintrack.domain

import com.example.fintrack.domain.model.Periodicity
import com.example.fintrack.domain.model.RecurringStatus
import com.example.fintrack.domain.service.RecurringService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Stage 8 P17 — recurring detection / forecast tests.
 *
 * Covers: monthly/quarterly/annual recurrence, variable amounts (module 148),
 * annual normalization (module 149), missing observations / skipped months,
 * cancellation and durable user decisions.
 */
class RecurringServiceTest {

    private var nowMs = Instant.parse("2026-08-20T00:00:00Z").toEpochMilli()
    private val svc = RecurringService(clock = { Instant.ofEpochMilli(nowMs) })

    private fun obs(
        id: String,
        dayOfMonth: Int,
        month: Int,
        year: Int = 2026,
        amount: Long = 4_9900L,
        counterparty: String? = "netflix",
    ) = RecurringService.ObservedTxn(
        transactionId = id,
        accountId = "acc-1",
        counterpartyNormalized = counterparty,
        merchant = "Netflix",
        categoryId = "cat-sub",
        amountMinor = amount,
        currencyCode = "INR",
        occurredAtEpochMs = Instant.parse("%04d-%02d-%02dT10:00:00Z".format(year, month, dayOfMonth)).toEpochMilli(),
    )

    @Test
    fun `monthly recurrence detected from three observations`() {
        val r = svc.detect(
            "acc-1",
            listOf(
                obs("t1", 5, 5),
                obs("t2", 5, 6),
                obs("t3", 5, 7),
            ),
        )
        assertNotNull(r)
        assertEquals(Periodicity.MONTHLY, r!!.pattern.periodicity)
        assertEquals(3, r.observations.size)
        assertTrue(r.pattern.confidence > 0.0)
        assertNotNull(r.pattern.nextExpectedEpochMs)
    }

    @Test
    fun `quarterly recurrence detected`() {
        val r = svc.detect(
            "acc-1",
            listOf(
                obs("t1", 10, 1),
                obs("t2", 10, 4),
                obs("t3", 10, 7),
            ),
        )
        assertNotNull(r)
        assertEquals(Periodicity.QUARTERLY, r!!.pattern.periodicity)
    }

    @Test
    fun `annual recurrence detected and normalizes to monthly equivalent`() {
        val r = svc.detect(
            "acc-1",
            listOf(
                obs("t1", 15, 1, 2024, amount = 12_000_00L),
                obs("t2", 15, 1, 2025, amount = 12_000_00L),
                obs("t3", 15, 1, 2026, amount = 12_000_00L),
            ),
        )
        assertNotNull(r)
        assertEquals(Periodicity.ANNUAL, r!!.pattern.periodicity)
        // Module 149: annual charge participates as 1/12 monthly without duplication.
        assertEquals(1_000_00L, r.pattern.monthlyEquivalentMinor())
        assertEquals(12_000_00L, r.pattern.annualEquivalentMinor())
    }

    @Test
    fun `variable amounts within tolerance keep the pattern with range preserved`() {
        val r = svc.detect(
            "acc-1",
            listOf(
                obs("t1", 5, 5, amount = 4_500L),
                obs("t2", 5, 6, amount = 5_000L),
                obs("t3", 5, 7, amount = 5_400L),
            ),
        )
        assertNotNull(r)
        assertEquals(4_500L, r!!.pattern.minObservedAmountMinor)
        assertEquals(5_400L, r.pattern.maxObservedAmountMinor)
        assertEquals(5_000L, r.pattern.canonicalAmountMinor)
    }

    @Test
    fun `wildly varying amounts do not produce a pattern`() {
        val r = svc.detect(
            "acc-1",
            listOf(
                obs("t1", 5, 5, amount = 1_000L),
                obs("t2", 5, 6, amount = 50_000L),
                obs("t3", 5, 7, amount = 900L),
            ),
        )
        assertNull(r)
    }

    @Test
    fun `too few observations yield no pattern`() {
        assertNull(svc.detect("acc-1", listOf(obs("t1", 5, 5), obs("t2", 5, 6))))
    }

    @Test
    fun `irregular intervals yield no pattern`() {
        assertNull(
            svc.detect(
                "acc-1",
                listOf(obs("t1", 2, 1), obs("t2", 17, 1), obs("t3", 21, 2)),
            )
        )
    }

    @Test
    fun `user confirmed status survives re-detection`() {
        val first = svc.detect("acc-1", listOf(obs("t1", 5, 5), obs("t2", 5, 6), obs("t3", 5, 7)))!!
        val reDetected = svc.detect(
            "acc-1",
            listOf(obs("t1", 5, 5), obs("t2", 5, 6), obs("t3", 5, 7), obs("t4", 5, 8)),
            existingStatus = RecurringStatus.CONFIRMED,
            existingDecidedBy = "USER",
        )!!
        assertEquals(RecurringStatus.CONFIRMED, reDetected.pattern.status)
        assertEquals("USER", reDetected.pattern.decidedBy)
        assertFalse(first.pattern.id == reDetected.pattern.id || true == false) // sanity
    }

    @Test
    fun `cancelled subscription stays cancelled on re-detection`() {
        val r = svc.detect(
            "acc-1",
            listOf(obs("t1", 5, 5), obs("t2", 5, 6), obs("t3", 5, 7)),
            existingStatus = RecurringStatus.CANCELLED,
            existingDecidedBy = "USER",
        )!!
        assertEquals(RecurringStatus.CANCELLED, r.pattern.status)
    }

    @Test
    fun `skipped month rolls the next-expected forward instead of stacking`() {
        val base = svc.detect("acc-1", listOf(obs("t1", 5, 5), obs("t2", 5, 6), obs("t3", 5, 7)))!!
        val pattern = base.pattern
        val afterSkip = Instant.parse("2026-09-10T00:00:00Z")
        val rolled = svc.rollForwardOnSkip(pattern, afterSkip)
        assertNotNull(rolled)
        // Rolled forward past the missed charge but still within one interval
        // of "now" — no stacking of multiple missed periods.
        assertTrue("rolled forward", rolled!!.isAfter(Instant.parse("2026-08-05T00:00:00Z")))
        assertFalse("not stacked beyond now + interval", rolled.isAfter(afterSkip.plusSeconds(31L * 86_400)))
    }

    @Test
    fun `forecast sums upcoming charges in window and flags unconfirmed`() {
        val detected = svc.detect("acc-1", listOf(obs("t1", 5, 5), obs("t2", 5, 6), obs("t3", 5, 7)))!!
        val confirmedPattern = detected.pattern.copy(status = RecurringStatus.CONFIRMED, decidedBy = "USER")
        val lowConfidence = detected.pattern.copy(id = "p2", confidence = 0.2)

        // Window covering Aug-Sep 2026 in real epoch days.
        val startDay = java.time.LocalDate.of(2026, 8, 1).toEpochDay()
        val endDay = java.time.LocalDate.of(2026, 9, 30).toEpochDay()
        val f = svc.forecast(listOf(confirmedPattern, lowConfidence), startDay, endDay)
        assertTrue(f.upcoming.isNotEmpty())
        assertTrue(f.upcoming.all { it.confirmed })
        assertFalse(f.includesUnconfirmed)
        assertEquals(f.expectedTotalMinor, f.upcoming.sumOf { it.expectedAmountMinor })

        val f2 = svc.forecast(listOf(detected.pattern.copy(confidence = 0.9)), startDay, endDay)
        assertTrue(f2.includesUnconfirmed)
    }

    @Test
    fun `subscription flag requires merchant evidence`() {
        val withMerchant = svc.detect(
            "acc-1",
            listOf(obs("t1", 5, 5), obs("t2", 5, 6), obs("t3", 5, 7)),
        )!!
        assertTrue(withMerchant.pattern.isSubscription == true)

        val noMerchant = svc.detect(
            "acc-1",
            listOf(
                obs("t1", 5, 5).copy(merchant = null),
                obs("t2", 5, 6).copy(merchant = null),
                obs("t3", 5, 7).copy(merchant = null),
            ),
        )!!
        assertTrue(noMerchant.pattern.isSubscription == false)
    }
}
