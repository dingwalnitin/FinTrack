package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.ReviewItem
import com.example.fintrack.domain.model.ReviewReason
import com.example.fintrack.domain.model.ReviewStatus
import java.time.Instant

/**
 * Stage 7 P15 — review queue.
 *
 * The queue is a prioritized list of transactions that need attention.
 * Items are created by the parser / LLM / dedupe pipelines when they
 * cannot resolve something deterministically, and by the categorization
 * engine when the LLM suggestion is below the confidence threshold.
 *
 * Each item carries a short user-visible explanation of WHY it needs
 * review — never fabricated evidence. When evidence is missing, the
 * explanation says so explicitly ("no reference number in the SMS").
 *
 * Resolving an item is a normal write; dismissing is also recorded so
 * the audit trail shows the item was seen and deliberately skipped.
 */
class ReviewQueueService(
    private val sink: ReviewSink,
    private val clock: () -> Instant = Instant::now,
) {

    /**
     * Enqueue (or refresh) a review item for a transaction. Idempotent:
     * re-running with the same (transactionId, reason) while the item is
     * OPEN updates nothing; once resolved, a new row may be created if
     * the same reason recurs later.
     */
    suspend fun enqueue(
        transactionId: String,
        reason: ReviewReason,
        priority: Int,
        explanation: String,
        sourceKind: String,
        sourceVersion: String,
    ): Boolean {
        val existing = sink.openReviewItemsForTransaction(transactionId)
            .firstOrNull { it.reason == reason }
        if (existing != null) return false // already open; no-op
        val now = clock().toEpochMilli()
        return sink.insertReviewItem(
            ReviewItem(
                id = java.util.UUID.randomUUID().toString(),
                transactionId = transactionId,
                reason = reason,
                priority = priority,
                status = ReviewStatus.OPEN,
                createdAtEpochMs = now,
                resolvedAtEpochMs = null,
                explanation = explanation,
                sourceKind = sourceKind,
                sourceVersion = sourceVersion,
            )
        )
    }

    /** Resolve an item after the user has acted on it. */
    suspend fun resolve(itemId: String): Boolean =
        sink.updateReviewItemStatus(itemId, ReviewStatus.RESOLVED.name, clock().toEpochMilli())

    /** Dismiss an item without acting on it (recorded for audit). */
    suspend fun dismiss(itemId: String): Boolean =
        sink.updateReviewItemStatus(itemId, ReviewStatus.DISMISSED.name, clock().toEpochMilli())

    /** Open items in priority order. */
    suspend fun openItems(): List<ReviewItem> = sink.openReviewItems()

    /** Open items filtered to one transaction (detail screen). */
    suspend fun openItemsFor(transactionId: String): List<ReviewItem> =
        sink.openReviewItemsForTransaction(transactionId)
}

/** Persistence interface for [ReviewQueueService]. */
interface ReviewSink {
    suspend fun insertReviewItem(item: ReviewItem): Boolean
    suspend fun updateReviewItemStatus(id: String, status: String, atMs: Long?): Boolean
    suspend fun openReviewItems(): List<ReviewItem>
    suspend fun openReviewItemsForTransaction(transactionId: String): List<ReviewItem>
}
