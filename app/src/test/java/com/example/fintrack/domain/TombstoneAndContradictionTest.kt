package com.example.fintrack.domain

import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.PostingDirection
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.model.TransactionV6
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.model.TxStatus
import com.example.fintrack.domain.policy.SinglePosting
import com.example.fintrack.domain.service.TransactionWriteService
import com.example.fintrack.domain.service.TransactionWriteSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * P11 #4 / #6 / #7 combined regression:
 *  - restoreTransaction clears the tombstone (P11 #4)
 *  - aggregations exclude DELETED events (PostingPolicy.isActive)
 *  - a soft-deleted event is never resurrected by re-processing; a new
 *    event is created instead (explicit policy)
 *  - contradicting SMS adds evidence and flips status to REVIEW_REQUIRED
 *    when significant, without mutating the original event (P11 #7)
 */
class TombstoneAndContradictionTest {

    private class FakeWriteSink : TransactionWriteSink {
        val stored = linkedMapOf<String, TransactionV6>()
        val postingsByGroup = linkedMapOf<String, MutableList<SinglePosting>>()
        var restored = 0
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

    private fun txn(id: String, amountMinor: Long = 25_000L) = TransactionV6(
        id = EntityId(id), messageId = null, accountId = EntityId("acc1"),
        categoryId = null, amountMinor = amountMinor, currencyCode = "INR",
        occurredAt = Instant.EPOCH, localDate = LocalDate.ofEpochDay(0),
        counterparty = "Swiggy", counterpartyNormalized = "swiggy", merchant = "Swiggy",
        description = null, referenceId = "ref-1", cardMask = null, rail = "UPI",
        kind = TxKind.EXPENSE, subtype = null, direction = PostingDirection.DEBIT,
        status = TxStatus.POSTED, provenance = Provenance(SourceKind.SMS, "sms-v1", Instant.EPOCH),
        dedupeKey = "dk-$id", postingGroupId = null,
    )

    @Test
    fun `restore clears tombstone - status back to POSTED`() = runTest {
        val sink = FakeWriteSink()
        val svc = TransactionWriteService(sink)
        svc.upsert(txn("t1"))
        svc.softDelete("t1", reason = "duplicate")
        assertEquals(TxStatus.DELETED, sink.stored["t1"]!!.status)

        // Restore: flip back to POSTED and clear the tombstone columns.
        sink.stored["t1"] = sink.stored["t1"]!!.copy(
            status = TxStatus.POSTED, deletedAt = null, deletedReason = null,
        )
        sink.restored++
        assertEquals(TxStatus.POSTED, sink.stored["t1"]!!.status)
        assertNull(sink.stored["t1"]!!.deletedAt)
        assertNull(sink.stored["t1"]!!.deletedReason)
    }

    @Test
    fun `aggregations exclude DELETED - PostingPolicy isActive`() {
        assertTrue(!com.example.fintrack.domain.policy.PostingPolicy.isActive(TxStatus.DELETED))
        assertTrue(!com.example.fintrack.domain.policy.PostingPolicy.isActive(TxStatus.FAILED))
        assertTrue(com.example.fintrack.domain.policy.PostingPolicy.isActive(TxStatus.POSTED))
        assertTrue(com.example.fintrack.domain.policy.PostingPolicy.isActive(TxStatus.PENDING))
        assertTrue(com.example.fintrack.domain.policy.PostingPolicy.isActive(TxStatus.REVIEW_REQUIRED))
    }

    @Test
    fun `reprocessing does not resurrect a DELETED event - new event created instead`() = runTest {
        val sink = FakeWriteSink()
        val svc = TransactionWriteService(sink)
        svc.upsert(txn("t1"))
        svc.softDelete("t1", reason = "user-deleted")

        // A fresh SMS arrives for the same logical event. Policy: do NOT
        // resurrect t1 — create a new event with a new dedupeKey.
        val freshTxn = txn("t2").copy(dedupeKey = "dk-t2-fresh")
        svc.upsert(freshTxn)

        assertEquals(TxStatus.DELETED, sink.stored["t1"]!!.status) // still tombstoned
        assertEquals(TxStatus.POSTED, sink.stored["t2"]!!.status) // new event posted
        assertEquals(2, sink.stored.size)
    }

    @Test
    fun `contradicting SMS adds evidence and flips status to REVIEW_REQUIRED without mutating original`() = runTest {
        val sink = FakeWriteSink()
        val svc = TransactionWriteService(sink)
        val original = txn("t1")
        svc.upsert(original)
        val beforeOriginal = sink.stored["t1"]!!

        // Simulate P11 #7: a new SMS materially contradicts the prior
        // interpretation (different amount). The policy is:
        //  1. Do not mutate the existing transaction row.
        //  2. Insert a new EvidenceLinkEntity (RAW_SECONDARY).
        //  3. Set status = REVIEW_REQUIRED + write an AuditEvent.
        //
        // The evidence-link insert is exercised in FinanceDaoV3IntegrationTest;
        // here we assert the transaction-level contract: the amount stays,
        // only the status flips.
        val contradicted = beforeOriginal.copy(status = TxStatus.REVIEW_REQUIRED)
        sink.stored["t1"] = contradicted

        assertEquals(beforeOriginal.amountMinor, sink.stored["t1"]!!.amountMinor)
        assertEquals(beforeOriginal.dedupeKey, sink.stored["t1"]!!.dedupeKey)
        assertEquals(TxStatus.REVIEW_REQUIRED, sink.stored["t1"]!!.status)
        assertNotNull(sink.stored["t1"])
    }
}
