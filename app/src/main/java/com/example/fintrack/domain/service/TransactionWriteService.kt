package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.PostingDirection
import com.example.fintrack.domain.model.TransactionV6
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.model.TxStatus
import com.example.fintrack.domain.policy.PostingPolicy
import com.example.fintrack.domain.policy.SinglePosting
import java.time.Instant
import java.util.UUID

/**
 * P10 #1 / #4 / #5: authoritative normalized-transaction and ledger-posting
 * write service.
 *
 * Contract:
 *  - Every money-changing event persists [TransactionV6] + one or more
 *    [SinglePosting] rows inside a single Room @Transaction.
 *  - Editing a transaction re-generates postings transactionally: the
 *    previous posting group is deleted and the new group is inserted in
 *    the same Room transaction. Duplicate postings can never accumulate.
 *  - Balance continuity: sum of signed postings on an account matches
 *    the change in derived balance. The engine only ever writes one side
 *    here; two-sided transfers (P11) are handled by a sibling service.
 *  - Soft-deleted transactions are tombstoned (status=DELETED,
 *    deletedAt=now). Their postings are kept (immutable history) but
 *    excluded from balance continuity via [isActive].
 */
class TransactionWriteService(
    private val sink: TransactionWriteSink,
    private val clock: () -> Instant = Instant::now,
) {

    /**
     * Create-or-update a transaction and replace its posting group. Returns
     * the persisted [TransactionV6] and the resulting [SinglePosting] list.
     * Idempotent: a stable [TransactionV6.dedupeKey] is the upsert key —
     * re-submitting the same logical event returns the same id.
     */
    suspend fun upsert(txn: TransactionV6, memos: List<String?> = listOf(null)): WriteResult {
        val now = clock()
        val postingGroupId = txn.postingGroupId ?: UUID.randomUUID().toString()
        val effectiveTxn = if (txn.postingGroupId == null) txn.copy(postingGroupId = postingGroupId) else txn
        val postingIds = effectiveTxn.amountMinor.let { _ ->
            List(memos.size.coerceAtLeast(1)) { UUID.randomUUID().toString() }
        }
        val postings = listOf(
            PostingPolicy.singlePosting(
                txn = effectiveTxn,
                postingId = postingIds[0],
                postingGroupId = postingGroupId,
                memo = memos.getOrNull(0),
            )
        )
        val replacedGroup = sink.replacePostingGroupAndUpsertTxn(
            txn = effectiveTxn,
            previousPostingGroupId = sink.findPostingGroupId(effectiveTxn.id.value),
            newPostings = postings,
        )
        return WriteResult(
            transaction = replacedGroup.first,
            postings = replacedGroup.second,
        )
    }

    /**
     * Soft-delete: marks the transaction DELETED, keeps postings for
     * audit, and records the reason. Idempotent — re-deleting is a no-op.
     * Posting generation does NOT run after deletion (P10 / P11 #4).
     */
    suspend fun softDelete(txnId: String, reason: String?): TransactionV6? {
        val existing = sink.findTransaction(txnId) ?: return null
        if (existing.status == TxStatus.DELETED) return existing
        val now = clock()
        val deleted = existing.copy(
            status = TxStatus.DELETED,
            deletedAt = now,
            deletedReason = reason,
        )
        sink.updateStatusAndTombstone(
            txnId = txnId,
            status = TxStatus.DELETED.name,
            deletedAtEpochMs = now.toEpochMilli(),
            deletedReason = reason,
        )
        return deleted
    }
}

/** Result of a transactional upsert: the persisted entity + the new postings. */
data class WriteResult(
    val transaction: TransactionV6,
    val postings: List<SinglePosting>,
)

/** Persistence interface for [TransactionWriteService]. */
interface TransactionWriteSink {
    suspend fun findTransaction(id: String): TransactionV6?
    suspend fun findPostingGroupId(id: String): String?
    suspend fun replacePostingGroupAndUpsertTxn(
        txn: TransactionV6,
        previousPostingGroupId: String?,
        newPostings: List<SinglePosting>,
    ): Pair<TransactionV6, List<SinglePosting>>
    suspend fun updateStatusAndTombstone(
        txnId: String,
        status: String,
        deletedAtEpochMs: Long,
        deletedReason: String?,
    )
}
