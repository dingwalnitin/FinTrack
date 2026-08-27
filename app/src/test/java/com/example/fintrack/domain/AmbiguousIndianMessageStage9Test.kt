package com.example.fintrack.domain

import com.example.fintrack.domain.service.InsightsEngine
import com.example.fintrack.domain.service.LedgerTxnView
import com.example.fintrack.parser.FinancialClass
import com.example.fintrack.parser.FinTrackParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 9 acceptance requirement: at least one realistic AMBIGUOUS /
 * CONFLICTING Indian financial-message fixture — not only happy-path data.
 *
 * These tests prove that:
 *  1. Ambiguous evidence is NOT deterministically interpreted (parser refuses;
 *     the message routes to review instead of becoming a guessed fact).
 *  2. When such evidence DOES land in the ledger as kind=UNKNOWN /
 *     uncategorized (via user manual entry or a later confirmed interpretation),
 *     the Stage 9 analytics surface it honestly: uncategorized visibility,
 *     unknown-kind counting, and no fabricated income/expense classification.
 */
class AmbiguousIndianMessageStage9Test {

    private val parser = FinTrackParser()
    private val engine = InsightsEngine()

    // ---- realistic ambiguous / conflicting fixtures ----

    /** No economic verb: amount + account present, direction unknowable. */
    private val ambiguousNoVerb =
        "Avail balance in A/c XX1234 is Rs.12,345.67 as on 09/08/26"

    /** Conflicting signals: "debited" verb but credit-style phrasing and no rail. */
    private val conflictingDirection =
        "Transaction of Rs.500 on A/c XX1234 dated 12/08/26"

    /** Malformed amount: financial-looking but not extractable. */
    private val malformedAmount =
        "Debited Rs.,,, from account on 10/08/26"

    @Test
    fun `ambiguous balance-message is classified borderline and never parsed as fact`() {
        val cls = parser.classify(ambiguousNoVerb)
        assertEquals(FinancialClass.BORDERLINE, cls.financialClass)
        assertNull("ambiguous evidence must not become a deterministic transaction", parser.parse(ambiguousNoVerb))
    }

    @Test
    fun `conflicting-direction message refuses deterministic extraction`() {
        val cls = parser.classify(conflictingDirection)
        assertEquals(FinancialClass.BORDERLINE, cls.financialClass)
        assertNull(parser.parse(conflictingDirection))
    }

    @Test
    fun `malformed-amount message does not fabricate an amount`() {
        val candidate = parser.parse(malformedAmount)
        // Either refused outright, or parsed with NO amount (unknown stays unknown).
        if (candidate != null) {
            assertNull(candidate.amountMinor)
            assertNull(candidate.direction)
        }
    }

    /**
     * If the user manually records the ambiguous INR 500 event without a
     * category/kind decision, analytics must show it as UNCATEGORIZED spend
     * of UNKNOWN kind — visible, but never silently counted as confirmed
     * expense in savings-rate math.
     */
    @Test
    fun `manually-recorded ambiguous event shows as uncategorized and unknown-kind`() {
        val day = java.time.LocalDate.of(2026, 8, 12).toEpochDay()
        val ambiguousTxn = LedgerTxnView(
            id = "amb-1",
            accountId = "acc-hdfc",
            categoryId = null,                       // user did not decide a category
            kind = "UNKNOWN",                        // economic meaning unresolved
            directionDebit = true,
            amountMinor = 50_000L,                   // Rs.500.00
            localDateEpochDay = day,
            counterpartyNormalized = null,           // payee unknown from the SMS
            merchant = null,
            currencyCode = "INR",
            occurredAtEpochMs = day * 86_400_000L,
            subtype = null,
            rail = null,
        )
        val clearFacts = listOf(
            LedgerTxnView(
                id = "sal", accountId = "acc-hdfc", categoryId = "cat-salary",
                kind = "INCOME", directionDebit = false, amountMinor = 1_000_000L,
                localDateEpochDay = day - 2, counterpartyNormalized = "abc corp",
                merchant = "ABC Corp", currencyCode = "INR",
                occurredAtEpochMs = (day - 2) * 86_400_000L, subtype = null,
            ),
        )

        // Uncategorized visibility: the breakdown must show the ambiguous row.
        val bd = engine.spendBreakdown(
            clearFacts + ambiguousTxn,
            fromDay = day - 10, toDay = day + 1,
            grouping = InsightsEngine.Grouping.CATEGORY,
            currencyCode = "INR",
        )
        assertTrue(bd.rows.any { it.isUncategorized })
        assertEquals(50_000L, bd.uncategorizedNetMinor)

        // Savings rate counts only INCOME/EXPENSE kinds; the UNKNOWN row is
        // neither income nor external spend, so it cannot distort the rate.
        val sr = engine.savingsRate(clearFacts + ambiguousTxn, day - 10, day + 1)
        assertEquals(1_000_000L, sr.incomeMinor)
        assertEquals(0L, sr.expensesMinor)

        // Cash flow: unknown-kind debit is surfaced conservatively as outflow
        // (money left the account) while remaining unclassified for meaning.
        val cf = engine.cashFlow(clearFacts + ambiguousTxn, day - 10, day + 1, "INR")
        assertEquals(50_000L, cf.outflowExternalMinor)
    }

    /**
     * CONFLICTING duplicate scenario: two same-amount UPI debits minutes apart
     * (the classic false-merge trap). Analytics must count BOTH events —
     * dedupe decisions belong to the review queue, never to silent merging.
     */
    @Test
    fun `conflicting same-amount duplicates are both counted until user merges them`() {
        val base = java.time.LocalDate.of(2026, 8, 15).toEpochDay() * 86_400_000L
        val coffeeA = LedgerTxnView(
            id = "coffee-a", accountId = "acc-sbi", categoryId = null, kind = "EXPENSE",
            directionDebit = true, amountMinor = 9_900L,
            localDateEpochDay = base / 86_400_000L,
            counterpartyNormalized = "blue tokai coffee", merchant = "Blue Tokai",
            currencyCode = "INR", occurredAtEpochMs = base, subtype = null, rail = "CARD_POS",
        )
        val coffeeB = coffeeA.copy(id = "coffee-b", occurredAtEpochMs = base + 4 * 60_000L)
        val bd = engine.spendBreakdown(
            listOf(coffeeA, coffeeB), base / 86_400_000L, base / 86_400_000L,
            InsightsEngine.Grouping.MERCHANT, currencyCode = "INR",
        )
        assertEquals(19_800L, bd.totalNetMinor)
        assertEquals(2, bd.rows.single().txnCount)
    }
}
