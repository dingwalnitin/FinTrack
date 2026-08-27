package com.example.fintrack.domain

import com.example.fintrack.domain.dedupe.Candidate
import com.example.fintrack.domain.policy.TransferEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P11 #1 transfer-engine tests.
 *  - directionMatches requires DEBIT vs CREDIT
 *  - same-account "transfer" is not a transfer
 *  - strong (amount + ref + time) signal scores AUTO_LINK
 *  - ambiguous pairs land in REVIEW
 *  - non-matching currency returns null
 */
class TransferEngineTest {

    private fun candidate(
        eventId: String,
        direction: String,
        amountMinor: Long? = 50_000L,
        currency: String? = "INR",
        refId: String? = "UTR1",
        rail: String? = "UPI",
        accountId: String? = "acc-a",
        atMs: Long = 1_700_000_000_000L,
    ) = Candidate(
        eventId = eventId,
        amountMinor = amountMinor,
        currencyCode = currency,
        direction = direction,
        rail = rail,
        accountId = accountId,
        refId = refId,
        counterpartyNormalized = null,
        cardMask = null,
        occurredAtEpochMs = atMs,
    )

    @Test
    fun `directionMatches requires DEBIT and CREDIT`() {
        assertTrue(TransferEngine.directionMatches(candidate("a", "DEBIT"), candidate("b", "CREDIT")))
        assertTrue(!TransferEngine.directionMatches(candidate("a", "CREDIT"), candidate("b", "DEBIT")))
    }

    @Test
    fun `scorePair returns null when direction does not match`() {
        val a = candidate("a", "DEBIT")
        val b = candidate("b", "DEBIT")
        assertNull(TransferEngine.scorePair(a, b))
    }

    @Test
    fun `scorePair returns null when accounts are the same`() {
        val a = candidate("a", "DEBIT", accountId = "acc-same")
        val b = candidate("b", "CREDIT", accountId = "acc-same")
        assertNull(TransferEngine.scorePair(a, b))
    }

    @Test
    fun `strong match - same amount ref rail and time - auto links`() {
        val a = candidate("a", "DEBIT", accountId = "acc-from")
        val b = candidate("b", "CREDIT", accountId = "acc-to")
        val r = TransferEngine.scorePair(a, b)
        assertNotNull(r)
        assertEquals(TransferEngine.Verdict.AUTO_LINK, r!!.verdict)
        assertTrue("score >= AUTO_LINK threshold: ${r.score}", r.score >= TransferEngine.Thresholds.AUTO_LINK)
    }

    @Test
    fun `same amount same currency and accounts but no ref and outside window - reject`() {
        val a = candidate("a", "DEBIT", refId = null, accountId = "acc-from",
            atMs = 1_700_000_000_000L)
        val b = candidate("b", "CREDIT", refId = null, accountId = "acc-to",
            atMs = 1_700_000_000_000L + 60L * 60_000L) // 1 hour later
        val r = TransferEngine.scorePair(a, b)
        assertNotNull(r)
        // time = 0, ref = 0, rail = 1, amount = 1, currency = 1, accountDistinct = 1
        // score = 0.30 + 0.10 + 0.10 + 0.15 + 0.10 = 0.75 → REVIEW
        assertEquals(TransferEngine.Verdict.REVIEW, r!!.verdict)
    }

    @Test
    fun `currency mismatch returns null`() {
        val a = candidate("a", "DEBIT", currency = "INR", accountId = "acc-from")
        val b = candidate("b", "CREDIT", currency = "USD", accountId = "acc-to")
        assertNull(TransferEngine.scorePair(a, b))
    }
}
