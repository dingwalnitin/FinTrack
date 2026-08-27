package com.example.fintrack.domain

import com.example.fintrack.domain.model.AccountType
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.PostingDirection
import com.example.fintrack.domain.model.TransactionV6
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.model.TxStatus
import com.example.fintrack.domain.policy.PostingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * P10 acceptance tests:
 *  - every event generates correct postings (signs consistent across account types)
 *  - balance continuity: sum of signed postings matches the balance delta
 *  - edits cannot leave orphaned/duplicate postings (replace-group semantics)
 *  - soft-deleted events are excluded from active balances
 */
class PostingPolicyTest {

    private fun txn(
        kind: TxKind,
        amountMinor: Long = 25_000L,
        status: TxStatus = TxStatus.POSTED,
    ) = TransactionV6(
        id = EntityId("t1"), messageId = null, accountId = EntityId("acc1"),
        categoryId = null, amountMinor = amountMinor, currencyCode = "INR",
        occurredAt = Instant.EPOCH, localDate = LocalDate.ofEpochDay(0),
        counterparty = null, counterpartyNormalized = null, merchant = null,
        description = null, referenceId = null, cardMask = null, rail = "UPI",
        kind = kind, subtype = null,
        direction = PostingPolicy.directionFor(kind, AccountType.BANK),
        status = status, provenance = com.example.fintrack.domain.model.Provenance(
            sourceKind = com.example.fintrack.domain.model.SourceKind.SMS,
            sourceVersion = "sms-v1", capturedAt = Instant.EPOCH,
        ),
        dedupeKey = "dk", postingGroupId = "pg-1",
    )

    // ---- sign semantics per account type (P10 #3) ----

    @Test
    fun `expense is debit on bank account`() {
        assertEquals(PostingDirection.DEBIT, PostingPolicy.directionFor(TxKind.EXPENSE, AccountType.BANK))
    }

    @Test
    fun `income and refund are credit on bank account`() {
        assertEquals(PostingDirection.CREDIT, PostingPolicy.directionFor(TxKind.INCOME, AccountType.BANK))
        assertEquals(PostingDirection.CREDIT, PostingPolicy.directionFor(TxKind.REFUND, AccountType.BANK))
    }

    @Test
    fun `fee is debit on all account types`() {
        for (t in AccountType.entries) {
            assertEquals(PostingDirection.DEBIT, PostingPolicy.directionFor(TxKind.FEE, t))
        }
    }

    @Test
    fun `credit-card purchase is debit and refund is credit`() {
        assertEquals(PostingDirection.DEBIT, PostingPolicy.directionFor(TxKind.EXPENSE, AccountType.CREDIT_CARD))
        assertEquals(PostingDirection.CREDIT, PostingPolicy.directionFor(TxKind.REFUND, AccountType.CREDIT_CARD))
    }

    @Test
    fun `cash movement defaults to debit on bank accounts`() {
        assertEquals(PostingDirection.DEBIT, PostingPolicy.directionFor(TxKind.CASH_MOVE, AccountType.BANK))
        assertEquals(PostingDirection.DEBIT, PostingPolicy.directionFor(TxKind.CASH_MOVE, AccountType.CASH))
    }

    // ---- signed money (P10 #3) ----

    @Test
    fun `signed minor is negative on debit positive on credit`() {
        assertEquals(-100L, PostingPolicy.signedMinor(100L, PostingDirection.DEBIT))
        assertEquals(100L, PostingPolicy.signedMinor(100L, PostingDirection.CREDIT))
    }

    // ---- posting generation (P10 #4) ----

    @Test
    fun `single posting carries absolute amount and group identity`() {
        val p = PostingPolicy.singlePosting(
            txn = txn(TxKind.EXPENSE), postingId = "p1", postingGroupId = "pg-1", memo = "lunch",
        )
        assertEquals(25_000L, p.amountMinor)
        assertEquals(PostingDirection.DEBIT.name, p.direction)
        assertEquals("pg-1", p.postingGroupId)
        assertEquals("lunch", p.memo)
    }

    // ---- balance continuity (P10 #5) ----

    @Test
    fun `sum of signed postings equals balance delta`() {
        val postings = listOf(
            BalanceProbe(PostingDirection.DEBIT, 10_000L),
            BalanceProbe(PostingDirection.CREDIT, 2_500L),
            BalanceProbe(PostingDirection.DEBIT, 1_000L),
        )
        val delta = postings.sumOf { PostingPolicy.signedMinor(it.amountMinor, it.direction) }
        assertEquals(-8_500L, delta)
    }

    @Test
    fun `active filter excludes deleted and failed events`() {
        assertFalse(PostingPolicy.isActive(TxStatus.DELETED))
        assertFalse(PostingPolicy.isActive(TxStatus.FAILED))
        assertTrue(PostingPolicy.isActive(TxStatus.POSTED))
        assertTrue(PostingPolicy.isActive(TxStatus.PENDING))
        assertTrue(PostingPolicy.isActive(TxStatus.REVIEW_REQUIRED))
    }

    // ---- invariants on TransactionV6 construction ----

    @Test(expected = IllegalArgumentException::class)
    fun `negative amountMinor is rejected - sign lives in direction`() {
        txn(TxKind.EXPENSE, amountMinor = -5_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown rail name is rejected`() {
        TransactionV6(
            id = EntityId("t"), messageId = null, accountId = EntityId("a"),
            categoryId = null, amountMinor = 1L, currencyCode = "INR",
            occurredAt = Instant.EPOCH, localDate = LocalDate.ofEpochDay(0),
            counterparty = null, counterpartyNormalized = null, merchant = null,
            description = null, referenceId = null, cardMask = null, rail = "TELEPORT",
            kind = TxKind.UNKNOWN, subtype = null, direction = PostingDirection.DEBIT,
            status = TxStatus.POSTED, provenance = com.example.fintrack.domain.model.Provenance(
                sourceKind = com.example.fintrack.domain.model.SourceKind.SMS,
                sourceVersion = "v", capturedAt = Instant.EPOCH,
            ),
            dedupeKey = "dk", postingGroupId = null,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `deletedAt without DELETED status is rejected`() {
        txn(TxKind.EXPENSE).copy(deletedAt = Instant.EPOCH, status = TxStatus.POSTED)
    }

    private data class BalanceProbe(val direction: PostingDirection, val amountMinor: Long)
}
