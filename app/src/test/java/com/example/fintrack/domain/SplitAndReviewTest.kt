package com.example.fintrack.domain

import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.PostingDirection
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.ReviewItem
import com.example.fintrack.domain.model.ReviewReason
import com.example.fintrack.domain.model.ReviewStatus
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.model.SplitLineDraft
import com.example.fintrack.domain.model.SplitValidation
import com.example.fintrack.domain.model.TransactionV6
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.model.TxStatus
import com.example.fintrack.domain.policy.SinglePosting
import com.example.fintrack.domain.service.ReviewQueueService
import com.example.fintrack.domain.service.ReviewSink
import com.example.fintrack.domain.service.SplitService
import com.example.fintrack.domain.service.SplitSink
import com.example.fintrack.domain.service.TransactionWriteService
import com.example.fintrack.domain.service.TransactionWriteSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Stage 7 P15 — split transactions + review queue.
 *
 * Acceptance gate: amount conservation tests pass; review can clear
 * ambiguities without data loss; operations are reversible/auditable.
 */
class SplitAndReviewTest {

    private val now = Instant.ofEpochMilli(1_700_000_000_000L)

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

    private class FakeSplitSink : SplitSink {
        val links = mutableListOf<com.example.fintrack.domain.model.TransactionSplit>()
        override suspend fun applySplits(links: List<com.example.fintrack.domain.model.TransactionSplit>) {
            this.links += links
        }
    }

    private class FakeReviewSink : ReviewSink {
        val items = linkedMapOf<String, ReviewItem>()
        override suspend fun insertReviewItem(item: ReviewItem): Boolean {
            items[item.id] = item; return true
        }
        override suspend fun updateReviewItemStatus(id: String, status: String, atMs: Long?): Boolean {
            val it = items[id] ?: return false
            items[id] = it.copy(status = ReviewStatus.valueOf(status), resolvedAtEpochMs = atMs)
            return true
        }
        fun openItems(): List<ReviewItem> =
            items.values.filter { it.status == ReviewStatus.OPEN }.sortedBy { it.priority }
        override suspend fun openReviewItems(): List<ReviewItem> = openItems()
        override suspend fun openReviewItemsForTransaction(transactionId: String): List<ReviewItem> =
            items.values.filter { it.transactionId == transactionId && it.status == ReviewStatus.OPEN }
    }

    private fun parent(amountMinor: Long = 100_000L) = TransactionV6(
        id = EntityId("parent-1"), messageId = null, accountId = EntityId("acc1"),
        categoryId = EntityId("cat-old"), amountMinor = amountMinor, currencyCode = "INR",
        occurredAt = Instant.EPOCH, localDate = LocalDate.ofEpochDay(0),
        counterparty = "Swiggy", counterpartyNormalized = "swiggy", merchant = "Swiggy",
        description = null, referenceId = "ref-1", cardMask = null, rail = "UPI",
        kind = TxKind.EXPENSE, subtype = null, direction = PostingDirection.DEBIT,
        status = TxStatus.POSTED,
        provenance = Provenance(SourceKind.SMS, "sms-v1", Instant.EPOCH),
        dedupeKey = "dk-parent", postingGroupId = null,
    )

    // ---- split validation ----

    @Test
    fun `valid split conserves the parent amount exactly`() {
        val svc = SplitService(TransactionWriteService(FakeWriteSink()), FakeSplitSink(), clock = { now })
        val draft = listOf(
            SplitLineDraft(EntityId("cat-food"), 60_000L, "dinner"),
            SplitLineDraft(EntityId("cat-taxi"), 40_000L, "cab"),
        )
        assertEquals(SplitValidation.Valid, svc.validate(100_000L, draft))
    }

    @Test
    fun `split that over-sums is rejected`() {
        val svc = SplitService(TransactionWriteService(FakeWriteSink()), FakeSplitSink(), clock = { now })
        val draft = listOf(
            SplitLineDraft(null, 60_000L, null),
            SplitLineDraft(null, 50_000L, null), // over by 10k
        )
        val v = svc.validate(100_000L, draft)
        assertTrue(v is SplitValidation.Invalid)
        assertEquals(10_000L, (v as SplitValidation.Invalid).differenceMinor)
    }

    @Test
    fun `split that under-sums is rejected`() {
        val svc = SplitService(TransactionWriteService(FakeWriteSink()), FakeSplitSink(), clock = { now })
        val draft = listOf(SplitLineDraft(null, 30_000L, null))
        val v = svc.validate(100_000L, draft)
        assertTrue(v is SplitValidation.Invalid)
        assertEquals(-70_000L, (v as SplitValidation.Invalid).differenceMinor)
    }

    @Test
    fun `empty split draft is rejected`() {
        val svc = SplitService(TransactionWriteService(FakeWriteSink()), FakeSplitSink(), clock = { now })
        val v = svc.validate(100_000L, emptyList())
        assertTrue(v is SplitValidation.Invalid)
    }

    @Test
    fun `non-positive line amount is rejected by the model`() {
        try {
            SplitLineDraft(null, 0L, null)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    // ---- split commit ----

    @Test
    fun `commit creates children tombstones parent and writes links`() = runTest {
        val writeSink = FakeWriteSink()
        val splitSink = FakeSplitSink()
        val svc = SplitService(TransactionWriteService(writeSink), splitSink, clock = { now })
        val p = parent()
        writeSink.stored[p.id.value] = p

        val result = svc.commit(
            parent = p,
            draft = listOf(
                SplitLineDraft(EntityId("cat-food"), 70_000L, "food"),
                SplitLineDraft(EntityId("cat-cab"), 30_000L, "cab"),
            ),
            provenance = Provenance(SourceKind.USER_CORRECTION, "split-v1", now),
        )

        assertTrue(result.isSuccess)
        val res = result.getOrThrow()
        assertEquals(2, res.children.size)
        assertEquals(2, res.links.size)
        // Amount conservation on persisted children.
        assertEquals(p.amountMinor, res.children.sumOf { it.amountMinor })
        // Parent is tombstoned with a reason.
        val tombstoned = writeSink.stored[p.id.value]!!
        assertEquals(TxStatus.DELETED, tombstoned.status)
        assertTrue(tombstoned.deletedReason!!.startsWith("split"))
        // Links point at the right children.
        assertEquals(
            res.children.map { it.id.value }.toSet(),
            res.links.map { it.childTransactionId }.toSet(),
        )
        assertTrue(res.links.all { it.parentTransactionId == p.id.value })
    }

    @Test
    fun `commit refuses an invalid split without writing anything`() = runTest {
        val writeSink = FakeWriteSink()
        val splitSink = FakeSplitSink()
        val svc = SplitService(TransactionWriteService(writeSink), splitSink, clock = { now })
        val p = parent()
        writeSink.stored[p.id.value] = p

        val result = svc.commit(
            parent = p,
            draft = listOf(SplitLineDraft(null, 1L, null)), // wrong total
            provenance = Provenance(SourceKind.USER_CORRECTION, "split-v1", now),
        )
        assertTrue(result.isFailure)
        // Nothing was written.
        assertEquals(1, writeSink.stored.size) // only the pre-seeded parent
        assertTrue(splitSink.links.isEmpty())
        assertEquals(TxStatus.POSTED, writeSink.stored[p.id.value]!!.status)
    }

    @Test
    fun `children carry distinct dedupe keys so re-runs are idempotent`() = runTest {
        val writeSink = FakeWriteSink()
        val svc = SplitService(TransactionWriteService(writeSink), FakeSplitSink(), clock = { now })
        val p = parent()
        writeSink.stored[p.id.value] = p
        val res = svc.commit(
            parent = p,
            draft = listOf(
                SplitLineDraft(EntityId("a"), 50_000L, null),
                SplitLineDraft(EntityId("b"), 50_000L, null),
            ),
            provenance = Provenance(SourceKind.USER_CORRECTION, "split-v1", now),
        ).getOrThrow()
        val keys = res.children.map { it.dedupeKey }
        assertEquals(keys.size, keys.toSet().size) // all distinct
    }

    // ---- review queue ----

    @Test
    fun `enqueue creates one open item per reason and is idempotent`() = runTest {
        val sink = FakeReviewSink()
        val svc = ReviewQueueService(sink, clock = { now })
        assertTrue(svc.enqueue("t1", ReviewReason.AMBIGUOUS, 5, "Two conflicting SMS for one charge.", "parser", "v1"))
        assertFalse(svc.enqueue("t1", ReviewReason.AMBIGUOUS, 5, "duplicate", "parser", "v1"))
        assertEquals(1, sink.items.size)
    }

    @Test
    fun `different reasons create separate items`() = runTest {
        val sink = FakeReviewSink()
        val svc = ReviewQueueService(sink, clock = { now })
        svc.enqueue("t1", ReviewReason.AMBIGUOUS, 5, "reason A", "parser", "v1")
        svc.enqueue("t1", ReviewReason.LOW_CONFIDENCE, 8, "reason B", "llm", "v1")
        assertEquals(2, sink.openItems().size)
    }

    @Test
    fun `resolve marks item resolved with timestamp`() = runTest {
        val sink = FakeReviewSink()
        val svc = ReviewQueueService(sink, clock = { now })
        svc.enqueue("t1", ReviewReason.UNRESOLVED, 3, "no reference in SMS", "parser", "v1")
        val id = sink.items.keys.first()
        assertTrue(svc.resolve(id))
        assertEquals(ReviewStatus.RESOLVED, sink.items[id]!!.status)
        assertTrue(sink.items[id]!!.resolvedAtEpochMs != null)
        assertTrue(svc.openItems().isEmpty())
    }

    @Test
    fun `dismiss records the dismissal`() = runTest {
        val sink = FakeReviewSink()
        val svc = ReviewQueueService(sink, clock = { now })
        svc.enqueue("t1", ReviewReason.CATEGORY_NEEDS_REVIEW, 9, "LLM below threshold", "llm", "v1")
        val id = sink.items.keys.first()
        assertTrue(svc.dismiss(id))
        assertEquals(ReviewStatus.DISMISSED, sink.items[id]!!.status)
    }

    @Test
    fun `open items are ordered by priority`() = runTest {
        val sink = FakeReviewSink()
        val svc = ReviewQueueService(sink, clock = { now })
        svc.enqueue("t-low", ReviewReason.AMBIGUOUS, 90, "low", "p", "v1")
        svc.enqueue("t-high", ReviewReason.CONFLICTING, 1, "high", "p", "v1")
        assertEquals(listOf("t-high", "t-low"), svc.openItems().map { it.transactionId })
    }

    @Test
    fun `explanation must not be blank`() {
        try {
            ReviewItem(
                id = "x", transactionId = "t1", reason = ReviewReason.AMBIGUOUS,
                priority = 1, status = ReviewStatus.OPEN, createdAtEpochMs = 0L,
                resolvedAtEpochMs = null, explanation = "   ",
                sourceKind = "p", sourceVersion = "v1",
            )
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
