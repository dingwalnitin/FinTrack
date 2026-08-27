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
 * P10 #4 / P11-preview: transactional write service tests.
 *  - upsert replaces the prior posting group (no duplicate postings on edit)
 *  - soft delete is idempotent and tombstones without deleting postings
 */
class TransactionWriteServiceTest {

    private class FakeSink : TransactionWriteSink {
        var stored: TransactionV6? = null
        val postingsByGroup = linkedMapOf<String, MutableList<SinglePosting>>()
        var tombstone: Triple<String, Long, String?>? = null
        private var counter = 0

        override suspend fun findTransaction(id: String): TransactionV6? = stored?.takeIf { it.id.value == id }

        override suspend fun findPostingGroupId(id: String): String? = stored?.takeIf { it.id.value == id }?.postingGroupId

        override suspend fun replacePostingGroupAndUpsertTxn(
            txn: TransactionV6,
            previousPostingGroupId: String?,
            newPostings: List<SinglePosting>,
        ): Pair<TransactionV6, List<SinglePosting>> {
            // Simulate the Room @Transaction: delete prior group, insert new.
            previousPostingGroupId?.let { postingsByGroup.remove(it) }
            val group = txn.postingGroupId!!
            postingsByGroup.getOrPut(group) { mutableListOf() }.clear()
            postingsByGroup[group]!!.addAll(newPostings)
            stored = txn
            return txn to newPostings
        }

        override suspend fun updateStatusAndTombstone(
            txnId: String,
            status: String,
            deletedAtEpochMs: Long,
            deletedReason: String?,
        ) {
            tombstone = Triple(txnId, deletedAtEpochMs, deletedReason)
            stored = stored?.copy(
                status = TxStatus.valueOf(status),
                deletedAt = Instant.ofEpochMilli(deletedAtEpochMs),
                deletedReason = deletedReason,
            )
        }
    }

    private fun txn(amountMinor: Long = 25_000L, postingGroup: String? = null) = TransactionV6(
        id = EntityId("t1"), messageId = null, accountId = EntityId("acc1"),
        categoryId = null, amountMinor = amountMinor, currencyCode = "INR",
        occurredAt = Instant.EPOCH, localDate = LocalDate.ofEpochDay(0),
        counterparty = "Swiggy", counterpartyNormalized = "swiggy", merchant = "Swiggy",
        description = null, referenceId = "ref-1", cardMask = null, rail = "UPI",
        kind = TxKind.EXPENSE, subtype = null, direction = PostingDirection.DEBIT,
        status = TxStatus.POSTED, provenance = Provenance(SourceKind.SMS, "sms-v1", Instant.EPOCH),
        dedupeKey = "dk-1", postingGroupId = postingGroup,
    )

    @Test
    fun `first upsert creates one posting group with one posting`() = runTest {
        val sink = FakeSink()
        val svc = TransactionWriteService(sink)
        val result = svc.upsert(txn())
        assertEquals(1, sink.postingsByGroup.size)
        assertEquals(1, sink.postingsByGroup.values.single().size)
        assertEquals(result.transaction.postingGroupId, sink.postingsByGroup.keys.single())
    }

    @Test
    fun `editing amount regenerates the same group - no duplicate postings`() = runTest {
        val sink = FakeSink()
        val svc = TransactionWriteService(sink)

        val first = svc.upsert(txn(amountMinor = 25_000L))
        val groupId = first.transaction.postingGroupId!!

        // Edit: same event id + same posting group, new amount.
        val edited = svc.upsert(txn(amountMinor = 30_000L, postingGroup = groupId))

        assertEquals(groupId, edited.transaction.postingGroupId)
        assertEquals(1, sink.postingsByGroup.size)
        assertEquals(1, sink.postingsByGroup[groupId]!!.size)
        assertEquals(30_000L, sink.postingsByGroup[groupId]!!.single().amountMinor)
    }

    @Test
    fun `upsert is idempotent for identical input`() = runTest {
        val sink = FakeSink()
        val svc = TransactionWriteService(sink)
        val first = svc.upsert(txn())
        val second = svc.upsert(first.transaction)
        assertEquals(1, sink.postingsByGroup.size)
        assertEquals(1, sink.postingsByGroup.values.single().size)
        assertEquals(first.transaction.id, second.transaction.id)
    }

    @Test
    fun `soft delete tombstones and is idempotent`() = runTest {
        val sink = FakeSink()
        val svc = TransactionWriteService(sink)
        svc.upsert(txn())

        val deleted = svc.softDelete("t1", reason = "duplicate")
        assertNotNull(deleted)
        assertEquals(TxStatus.DELETED, deleted!!.status)
        assertNotNull(sink.tombstone)

        // Postings are preserved for audit (not deleted).
        assertEquals(1, sink.postingsByGroup.values.single().size)

        // Re-delete is a no-op returning the already-deleted row.
        val again = svc.softDelete("t1", reason = "duplicate")
        assertEquals(TxStatus.DELETED, again!!.status)
    }

    @Test
    fun `soft delete of unknown id returns null`() = runTest {
        val svc = TransactionWriteService(FakeSink())
        assertNull(svc.softDelete("missing", reason = null))
    }
}
