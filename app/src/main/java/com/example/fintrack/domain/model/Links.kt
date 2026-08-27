package com.example.fintrack.domain.model

import java.time.Instant

/**
 * P11 durable refund link (Stage 5).
 *
 * The refund is a separate FinancialEvent; this link tells the detail screen
 * that the refund is the answer to the original expense. The original
 * postings are never mutated.
 */
data class RefundLink(
    val id: String,
    val refundedEventId: String,
    val refundEventId: String,
    val kind: RefundKind,
    val amountMinor: Long,
    val currencyCode: String,
    val provenance: Provenance,
    val reason: String?,
    val createdAt: Instant,
)

/** Domain view of a parent/child transaction link (fee or refund). */
data class TransactionLink(
    val id: String,
    val parentEventId: String,
    val childEventId: String,
    val role: TransactionLinkRole,
    val provenance: Provenance,
    val reason: String?,
    val createdAt: Instant,
)
