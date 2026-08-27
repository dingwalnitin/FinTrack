package com.example.fintrack.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v7 P11 additive entities (Stage 5).
 *
 * Design invariants (App Bible):
 *  - Refunds are events that REFERENCE the original expense; they never
 *    mutate it. The link is therefore a separate table with a unique
 *    (refundedEventId, refundEventId) index so re-running the refund
 *    service is idempotent.
 *  - Transaction links carry a role enum (REFUND | FEE | PARENT_FEE) so
 *    the same table services the linked-events section in the detail
 *    screen for both refunds and fees.
 *  - Both tables are append-only: status changes (e.g. deletion of the
 *    parent event) preserve the link row for audit.
 *  - Idempotency on the link is also reinforced by the unique
 *    (refundedEventId, refundEventId) index in [RefundLinkEntity] and the
 *    unique (parentEventId, childEventId, role) index in
 *    [TransactionLinkEntity].
 */
@Entity(
    tableName = "refund_links",
    indices = [
        Index(value = ["refundedEventId", "refundEventId"], unique = true),
        Index("refundedEventId"),
        Index("refundEventId"),
        Index("refundIdentity", unique = true),
    ],
)
data class RefundLinkEntity(
    @PrimaryKey val id: String,
    /** TransactionEntity.id of the original (refunded) event. */
    val refundedEventId: String,
    /** TransactionEntity.id of the new refund event. */
    val refundEventId: String,
    /** FULL | PARTIAL — see [com.example.fintrack.domain.model.RefundKind]. */
    val kind: String,
    /** Refund amount in minor units (== refund txn amountMinor for FULL; < for PARTIAL). */
    val amountMinor: Long,
    val currencyCode: String,
    val sourceKind: String,
    val sourceVersion: String,
    /** Human-readable reason ("refund processed by Amazon"). */
    val sourceReason: String?,
    /**
     * sha-256(refundedEventId | refundEventId | kind | amountMinor) — durable
     * identity so a parser / reprocess pass re-asserting the same refund
     * is a no-op.
     */
    val refundIdentity: String,
    val createdAtEpochMs: Long,
)

/**
 * Parent/child links: a fee can be linked to its parent transaction; the
 * same table is used to surface both "linked fees" and "linked refunds" in
 * the transaction detail screen.
 */
@Entity(
    tableName = "transaction_links",
    indices = [
        Index(value = ["parentEventId", "childEventId", "role"], unique = true),
        Index("parentEventId"),
        Index("childEventId"),
        Index("linkIdentity", unique = true),
    ],
)
data class TransactionLinkEntity(
    @PrimaryKey val id: String,
    val parentEventId: String,
    val childEventId: String,
    /** REFUND | FEE — see [com.example.fintrack.domain.model.TransactionLinkRole]. */
    val role: String,
    val sourceKind: String,
    val sourceVersion: String,
    val sourceReason: String?,
    val linkIdentity: String,
    val createdAtEpochMs: Long,
)
