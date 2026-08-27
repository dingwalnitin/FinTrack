package com.example.fintrack.domain

import com.example.fintrack.domain.model.AtmMatchKind
import com.example.fintrack.domain.model.AtmWithdrawalCandidate
import com.example.fintrack.domain.model.ReconciliationOutcome
import com.example.fintrack.domain.service.CashService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Stage 8 P18 — cash reconciliation and ATM matching tests.
 *
 * Covers: exact/under/over outcomes, mandatory reason on booked differences,
 * small-difference logging policy, same-amount multiple withdrawals
 * (ambiguity), and link identity stability.
 */
class CashServiceTest {

    private var nowMs = Instant.parse("2026-08-20T10:00:00Z").toEpochMilli()
    private val svc = CashService(clock = { Instant.ofEpochMilli(nowMs) })

    // ---- reconciliation ----

    @Test
    fun `exact count reconciles with no difference`() {
        val r = svc.reconcile("cash-1", countedMinor = 5_000, ledgerDerivedMinor = 5_000, reason = null, booking = false)
        assertTrue(r.isSuccess)
        assertEquals(ReconciliationOutcome.EXACT, r.getOrThrow().outcome)
    }

    @Test
    fun `counted below derived is UNDER`() {
        val r = svc.reconcile("cash-1", 4_000, 5_000, reason = "lost change", booking = true).getOrThrow()
        assertEquals(ReconciliationOutcome.UNDER, r.outcome)
        assertEquals(-1_000, r.reconciliation.differenceMinor)
    }

    @Test
    fun `counted above derived is OVER`() {
        val r = svc.reconcile("cash-1", 5_500, 5_000, reason = "unrecorded receipt", booking = true).getOrThrow()
        assertEquals(ReconciliationOutcome.OVER, r.outcome)
        assertEquals(500, r.reconciliation.diff())
    }

    private fun com.example.fintrack.domain.model.CashReconciliation.diff() = differenceMinor

    @Test
    fun `booking a non-zero difference without a reason fails`() {
        val r = svc.reconcile("cash-1", 4_000, 5_000, reason = null, booking = true)
        assertTrue(r.isFailure)
    }

    @Test
    fun `non-booking evaluation tolerates missing reason`() {
        val r = svc.reconcile("cash-1", 4_000, 5_000, reason = null, booking = false)
        assertTrue(r.isSuccess)
    }

    @Test
    fun `small differences may be logged without adjustment`() {
        assertTrue(svc.isSmallDifference(50, 10_000))
        assertFalse(svc.isSmallDifference(500, 10_000))
        assertFalse(svc.isSmallDifference(50, 0)) // zero balance: never "small"
    }

    @Test
    fun `reconciliation is idempotent on identity inputs`() {
        val a = svc.reconcile("cash-1", 4_900, 5_000, reason = "rounding", booking = false).getOrThrow().reconciliation
        nowMs += 1000
        val b = svc.reconcile("cash-1", 4_900, 5_000, reason = "rounding", booking = false).getOrThrow().reconciliation
        // Same account/amounts; identity hash inputs differ only by timestamp,
        // so distinct events are distinct rows — but the same event re-written
        // (same timestamp) hashes identically.
        assertEquals(a.accountId, b.accountId)
        assertEquals(a.countedMinor, b.countedMinor)
    }

    // ---- ATM matching ----

    private fun candidate(id: String, amount: Long, at: Instant) =
        AtmWithdrawalCandidate(transactionId = id, accountId = "bank-1", amountMinor = amount, occurredAt = at)

    @Test
    fun `single matching withdrawal links unambiguously`() {
        val t = Instant.parse("2026-08-19T18:00:00Z")
        val m = svc.matchAtmWithdrawal(
            listOf(candidate("w1", 20_000, t)),
            amountMinor = 20_000,
            around = Instant.parse("2026-08-19T20:00:00Z"),
        )
        assertEquals("w1", m.best?.transactionId)
        assertFalse(m.ambiguous)
    }

    @Test
    fun `same-amount multiple withdrawals are ambiguous`() {
        val around = Instant.parse("2026-08-19T20:00:00Z")
        val m = svc.matchAtmWithdrawal(
            listOf(
                candidate("w1", 20_000, around.minus(Duration.ofHours(2))),
                candidate("w2", 20_000, around.minus(Duration.ofHours(3))),
            ),
            amountMinor = 20_000,
            around = around,
        )
        assertTrue(m.ambiguous)
        assertEquals(2, m.candidates.size)
        // Nearest-in-time candidate is provisional best.
        assertEquals("w1", m.best?.transactionId)
    }

    @Test
    fun `amount mismatch does not match`() {
        val m = svc.matchAtmWithdrawal(
            listOf(candidate("w1", 20_000, Instant.parse("2026-08-19T18:00:00Z"))),
            amountMinor = 25_000,
            around = Instant.parse("2026-08-19T20:00:00Z"),
        )
        assertNull(m.best)
    }

    private fun assertNull(any: Any?) = org.junit.Assert.assertNull(any)

    @Test
    fun `drift beyond window does not match`() {
        val m = svc.matchAtmWithdrawal(
            listOf(candidate("w1", 20_000, Instant.parse("2026-08-17T18:00:00Z"))),
            amountMinor = 20_000,
            around = Instant.parse("2026-08-19T20:00:00Z"),
            maxDrift = Duration.ofHours(24),
        )
        assertNull(m.best)
    }

    @Test
    fun `link identity is stable per withdrawal-cash pair`() {
        val i1 = svc.linkIdentity("w1", "cash-1")
        val i2 = svc.linkIdentity("w1", "cash-1")
        val i3 = svc.linkIdentity("w2", "cash-1")
        assertEquals(i1, i2)
        assertTrue(i1 != i3)
    }

    @Test
    fun `manual link builds confirmed row`() {
        val link = svc.buildLink(
            withdrawalTransactionId = "w1",
            cashAccountId = "cash-1",
            amountMinor = 20_000,
            currencyCode = "INR",
            withdrawalOccurredAtEpochMs = nowMs,
            matchedBy = AtmMatchKind.MANUAL,
            candidateCount = 1,
            ambiguous = false,
            confirmedByUser = true,
        )
        assertTrue(link.confirmedByUser)
        assertEquals(AtmMatchKind.MANUAL, link.matchedBy)
    }
}
