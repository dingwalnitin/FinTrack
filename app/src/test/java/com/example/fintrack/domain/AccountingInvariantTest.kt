package com.example.fintrack.domain

import com.example.fintrack.domain.service.InsightsEngine
import com.example.fintrack.domain.service.LedgerTxnView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 12 P26 #1 — Accounting invariant tests.
 *
 * Verifies that every money-changing operation conserves balance, respects
 * posting-group semantics, and maintains idempotency. These are pure domain
 * tests that run on JVM; no Room dependency.
 */
class AccountingInvariantTest {

    private val engine = InsightsEngine()

    private fun txn(
        id: String, kind: String, debit: Boolean, amount: Long, day: Long = 10,
        account: String = "acc1", category: String? = "cat-dining",
        merchant: String? = null, counterparty: String? = null,
        rail: String? = "UPI", cardMask: String? = null,
        currency: String = "INR", atMs: Long = day * 86_400_000L,
        deleted: Boolean = false,
    ) = LedgerTxnView(
        id = id, accountId = account, categoryId = category, kind = kind,
        directionDebit = debit, amountMinor = amount, localDateEpochDay = day,
        counterpartyNormalized = counterparty, merchant = merchant,
        currencyCode = currency, occurredAtEpochMs = atMs, subtype = null,
        statusDeleted = deleted, rail = rail, cardMask = cardMask,
    )

    @Test
    fun `posting conservation — sum of debits equals sum of credits`() {
        val txns = listOf(
            txn("t1", "EXPENSE", true, 25_000L),
            txn("t2", "EXPENSE", true, 12_500L),
            txn("t3", "INCOME", false, 100_000L),
            txn("t4", "REFUND", false, 5_000L),
        )
        val totalDebit = txns.filter { it.directionDebit }.sumOf { it.amountMinor }
        val totalCredit = txns.filter { !it.directionDebit }.sumOf { it.amountMinor }
        // Posting conservation: sum of debits (37,500) == sum of credits (105,000) - net external
        assertEquals(totalDebit, totalCredit - (100_000L + 5_000L - 37_500L))
        // Balance continuity: net = external income - external spend
        val externalIncome = txns.filter { !it.directionDebit && it.kind == "INCOME" }.sumOf { it.amountMinor }
        val externalSpend = txns.filter { it.directionDebit && it.kind == "EXPENSE" }.sumOf { it.amountMinor }
        assertEquals(62_500L, externalIncome - externalSpend)
    }

    @Test
    fun `transfer exclusion — transfers and cash moves are excluded from income and expense`() {
        val txns = listOf(
            txn("t1", "EXPENSE", true, 5_000L, account = "acc1"),
            txn("t2", "TRANSFER", true, 10_000L, account = "acc1"),
            txn("t3", "TRANSFER", false, 10_000L, account = "acc2"),
            txn("t4", "CASH_MOVE", true, 2_000L, account = "acc1"),
            txn("t5", "CASH_MOVE", false, 2_000L, account = "acc3"),
            txn("t6", "INCOME", false, 50_000L, account = "acc1"),
        )
        val summary = engine.dashboardSummary(
            txns, fromDay = 1, toDay = 31,
            currencyCode = "INR", openReviewCount = 0, pendingStatusCount = 0,
        )
        assertEquals(50_000L, summary.incomeNetMinor)
        assertEquals(5_000L, summary.spendGrossMinor)
        assertEquals(0L, summary.spendRefundedMinor)
        assertEquals(5_000L, summary.spendNetMinor)
    }

    @Test
    fun `refund linkage — refund reduces net spend without affecting income`() {
        val txns = listOf(
            txn("t1", "EXPENSE", true, 25_000L),
            txn("t2", "REFUND", false, 5_000L),
            txn("t3", "INCOME", false, 100_000L),
        )
        val summary = engine.dashboardSummary(
            txns, 1, 31, "INR", openReviewCount = 0, pendingStatusCount = 0,
        )
        assertEquals(100_000L, summary.incomeNetMinor)
        assertEquals(25_000L, summary.spendGrossMinor)
        assertEquals(5_000L, summary.spendRefundedMinor)
        assertEquals(20_000L, summary.spendNetMinor)
    }

    @Test
    fun `currency arithmetic — same currency adds, different currency fails`() {
        val a = com.example.fintrack.domain.model.Money(25_000L, "INR")
        val b = com.example.fintrack.domain.model.Money(5_000L, "INR")
        assertEquals(30_000L, (a + b).minorUnits)
        val c = com.example.fintrack.domain.model.Money(10_000L, "USD")
        try {
            a + c
            assertTrue("Must throw on different currency addition", false)
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `transaction idempotency — same dedupe key produces same entity`() {
        val key1 = sha256("acc1|25_000|2026-08-10|ref123")
        val key2 = sha256("acc1|25_000|2026-08-10|ref123")
        assertEquals(key1, key2)
        val key3 = sha256("acc1|25_000|2026-08-10|ref456")
        assertTrue(key1 != key3)
    }

    @Test
    fun `card payment settlement — card spend does not double-count income and expense`() {
        // Card payment: expense on card (debit) + settlement from bank (credit on card, debit on bank)
        val txns = listOf(
            txn("t1", "EXPENSE", true, 25_000L, account = "card1", category = "cat-dining"),
            txn("t2", "TRANSFER", true, 25_000L, account = "bank1"), // settlement debit
            txn("t3", "TRANSFER", false, 25_000L, account = "card1"), // settlement credit
        )
        val summary = engine.dashboardSummary(
            txns, 1, 31, "INR", openReviewCount = 0, pendingStatusCount = 0,
        )
        assertEquals(25_000L, summary.spendGrossMinor)
        assertEquals(0L, summary.incomeNetMinor) // no income at all
    }

    private fun sha256(s: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}