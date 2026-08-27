package com.example.fintrack.domain

import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.PostingDirection
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.RefundKind
import com.example.fintrack.domain.model.RefundLink
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.model.TransactionLink
import com.example.fintrack.domain.model.TransactionLinkRole
import com.example.fintrack.domain.model.TransactionV6
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.model.TxStatus
import com.example.fintrack.domain.policy.SinglePosting
import com.example.fintrack.domain.service.RefundService
import com.example.fintrack.domain.service.RefundSink
import com.example.fintrack.domain.service.TransactionWriteService
import com.example.fintrack.domain.service.TransactionWriteSink
import com.example.fintrack.domain.service.WriteResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * P11 #2 refund tests.
 *  - recordRefund creates a new event with its own postings (the original
 *    expense is unchanged).
 *  - The link is stored with FULL or PARTIAL kind.
 *  - Original event is never mutated.
 */
class RefundServiceTest {

    private class FakeWriteSink : TransactionWriteSink {
        val stored = linkedMapOf<String, TransactionV6>()
        val postingsByGroup = linkedMapOf<String, MutableList<SinglePosting>>()

        override suspend fun findTransaction(id: String): TransactionV6? = stored[id]
        override suspend fun findPostingGroupId(id: String): String? = stored[id]?.postingGroupId

        override suspend fun replacePostingGroupAndUpsertTxn(
            txn: TransactionV6,
            previousPostingGroupId: String?,
            newPostings: List<SinglePosting>,
        ): Pair<TransactionV6, List<SinglePosting>> {
            previousPostingGroupId?.let { postingsByGroup.remove(it) }
            stored[txn.id.value] = txn
            val g = txn.postingGroupId!!
            postingsByGroup.getOrPut(g) { mutableListOf() }.clear()
            postingsByGroup[g]!!.addAll(newPostings)
            return txn to newPostings
        }

        override suspend fun updateStatusAndTombstone(
            txnId: String, status: String, deletedAtEpochMs: Long, deletedReason: String?,
        ) {
            stored[txnId] = stored[txnId]!!.copy(
                status = TxStatus.valueOf(status),
                deletedAt = Instant.ofEpochMilli(deletedAtEpochMs),
                deletedReason = deletedReason,
            )
        }
    }

    private class FakeRefundSink : RefundSink {
        val refundLinks = mutableListOf<RefundLink>()
        val txLinks = mutableListOf<TransactionLink>()
        override suspend fun insertRefundLink(link: RefundLink, refundIdentity: String) {
            refundLinks += link
        }
        override suspend fun insertTransactionLink(link: TransactionLink, linkIdentity: String) {
            txLinks += link
        }
    }

    private fun makeTxn(
        id: String = "t-orig",
        amountMinor: Long = 25_000L,
        kind: TxKind = TxKind.EXPENSE,
    ) = TransactionV6(
        id = EntityId(id),
        messageId = null,
        accountId = EntityId("acc1"),
        categoryId = null,
        amountMinor = amountMinor,
        currencyCode = "INR",
        occurredAt = Instant.EPOCH,
        localDate = LocalDate.ofEpochDay(0),
        counterparty = "Amazon",
        counterpartyNormalized = "amazon",
        merchant = "Amazon",
        description = null,
        referenceId = "R-1",
        cardMask = null,
        rail = "UPI",
        kind = kind,
        subtype = null,
        direction = PostingDirection.DEBIT,
        status = TxStatus.POSTED,
        provenance = Provenance(SourceKind.SMS, "sms-v1", Instant.EPOCH),
        dedupeKey = "dk-$id",
    )

    @Test
    fun `recordRefund creates new event and link - original unchanged`() = runTest {
        val writeSink = FakeWriteSink()
        val refundSink = FakeRefundSink()
        val writeService = TransactionWriteService(writeSink)
        val svc = RefundService(writeService, refundSink)

        val original = makeTxn()
        writeService.upsert(original)
        val beforeOriginal = writeSink.stored["t-orig"]!!
        val originalPostingCount = writeSink.postingsByGroup.values.sumOf { it.size }

        val refundTxn = makeTxn(id = "t-refund", amountMinor = 25_000L, kind = TxKind.REFUND)
        val result = svc.recordRefund(
            originalEventId = "t-orig",
            refundTxn = refundTxn,
            kind = RefundKind.FULL,
        )

        assertTrue(result.isSuccess)
        val r = result.getOrThrow()
        assertEquals("t-refund", r.refundEventId)
        // Two rows now: original + refund
        assertEquals(2, writeSink.stored.size)
        // Original postings are unchanged
        assertEquals(beforeOriginal, writeSink.stored["t-orig"])
        // The refund adds exactly ONE new posting (its own); the original
        // group still holds its single posting.
        assertEquals(originalPostingCount + 1, writeSink.postingsByGroup.values.sumOf { it.size })
        // Refund is its own event with its own posting
        assertEquals(TxKind.REFUND, writeSink.stored["t-refund"]!!.kind)
        // Refund link stored
        assertEquals(1, refundSink.refundLinks.size)
        assertEquals("t-orig", refundSink.refundLinks.single().refundedEventId)
        assertEquals("t-refund", refundSink.refundLinks.single().refundEventId)
        assertEquals(RefundKind.FULL, refundSink.refundLinks.single().kind)
        // Transaction link (role=REFUND) stored
        assertEquals(1, refundSink.txLinks.size)
        assertEquals(TransactionLinkRole.REFUND, refundSink.txLinks.single().role)
    }

    @Test
    fun `partial refund stores the partial amount on the link`() = runTest {
        val writeSink = FakeWriteSink()
        val refundSink = FakeRefundSink()
        val writeService = TransactionWriteService(writeSink)
        val svc = RefundService(writeService, refundSink)

        writeService.upsert(makeTxn())
        val refundTxn = makeTxn(id = "t-refund-partial", amountMinor = 5_000L, kind = TxKind.REFUND)
        val result = svc.recordRefund(
            originalEventId = "t-orig",
            refundTxn = refundTxn,
            kind = RefundKind.PARTIAL,
        )
        assertTrue(result.isSuccess)
        val link = refundSink.refundLinks.single()
        assertEquals(RefundKind.PARTIAL, link.kind)
        assertEquals(5_000L, link.amountMinor)
    }

    @Test
    fun `non-positive refund amount is rejected`() = runTest {
        val writeSink = FakeWriteSink()
        val refundSink = FakeRefundSink()
        val writeService = TransactionWriteService(writeSink)
        val svc = RefundService(writeService, refundSink)

        writeService.upsert(makeTxn())
        val badRefund = makeTxn(id = "t-refund-bad", amountMinor = 0L, kind = TxKind.REFUND)
        val result = svc.recordRefund(
            originalEventId = "t-orig",
            refundTxn = badRefund,
            kind = RefundKind.FULL,
        )
        assertTrue(result.isFailure)
    }
}
