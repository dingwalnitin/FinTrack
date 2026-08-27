package com.example.fintrack.domain.model

import java.time.Instant

/**
 * Stage 7 P15 — review queue, splits, reimbursement, travel modes,
 * tags and notes.
 *
 * Design invariants (App Bible + P15):
 *  - The review queue is a VIEW over transactions that need attention.
 *    It is not a second source of truth: the authoritative transaction
 *    row is unchanged; review items only point at it and carry a
 *    short user-visible explanation of why attention is needed.
 *  - Splits are parent/child. Amount conservation is enforced by the
 *    service: sum(children.amountMinor) == parent.amountMinor exactly
 *    (no rounding drift). Children are real [TransactionV6] rows so
 *    they participate in balances normally; the split link is a
 *    separate durable row.
 *  - Reimbursement links are explicit (expense -> reimbursing credit)
 *    pairs. They never mutate either side.
 *  - Travel mode is a per-account window with its own reporting
 *    currency; transactions inside the window can be filtered as
 *    "travel" without changing their stored currency or category.
 *  - Tags and notes are free-form local data. Attachments remain
 *    placeholder-only per the Bible (no binary storage).
 */
enum class ReviewReason {
    AMBIGUOUS,
    CONFLICTING,
    UNRESOLVED,
    LOW_CONFIDENCE,
    CATEGORY_NEEDS_REVIEW,
}

enum class ReviewStatus { OPEN, RESOLVED, DISMISSED }

data class ReviewItem(
    val id: String,
    val transactionId: String,
    val reason: ReviewReason,
    val priority: Int,
    val status: ReviewStatus,
    val createdAtEpochMs: Long,
    val resolvedAtEpochMs: Long?,
    val explanation: String,
    val sourceKind: String,
    val sourceVersion: String,
) {
    init {
        require(explanation.isNotBlank()) { "review explanation must not be blank" }
        require(priority >= 0)
    }
}

/** A single proposed child in a split preview. */
data class SplitLineDraft(
    val categoryId: EntityId?,
    val amountMinor: Long,
    val memo: String?,
) {
    init {
        require(amountMinor > 0) { "split line amount must be > 0" }
    }
}

/** Result of validating a split draft before commit. */
sealed interface SplitValidation {
    /** Draft sums to the parent amount exactly — safe to commit. */
    data object Valid : SplitValidation

    /** Draft does not conserve the parent amount. */
    data class Invalid(val differenceMinor: Long, val message: String) : SplitValidation
}

data class TransactionSplit(
    val id: String,
    val parentTransactionId: String,
    val childTransactionId: String,
    val splitIdentity: String,
    val sourceKind: SourceKind,
    val sourceVersion: String,
    val createdAtEpochMs: Long,
)

data class ReimbursementLink(
    val id: String,
    val expenseTransactionId: String,
    val reimbursingTransactionId: String,
    val linkIdentity: String,
    val sourceKind: SourceKind,
    val sourceVersion: String,
    val createdAtEpochMs: Long,
)

enum class TravelModeStatus { ACTIVE, ENDED }

data class TravelMode(
    val id: String,
    val accountId: EntityId,
    val label: String,
    val currencyCode: String,
    val startEpochDay: Long,
    val endEpochDay: Long?,
    val status: TravelModeStatus,
    val sourceKind: SourceKind,
    val sourceVersion: String,
    val createdAtEpochMs: Long,
) {
    init {
        require(label.isNotBlank())
        require(currencyCode.length == 3)
        if (endEpochDay != null) {
            require(endEpochDay >= startEpochDay) { "end must be on/after start" }
        }
    }
}

data class TransactionTag(
    val id: String,
    val transactionId: String,
    val tag: String,
    val sourceKind: SourceKind,
    val sourceVersion: String,
    val createdAtEpochMs: Long,
)

data class TransactionNote(
    val id: String,
    val transactionId: String,
    val note: String,
    val sourceKind: SourceKind,
    val sourceVersion: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
