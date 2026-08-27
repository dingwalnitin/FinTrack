package com.example.fintrack.domain.model

import java.time.Instant

/**
 * Stage 5 / P10 transaction taxonomy and signed-money semantics.
 *
 * P10 #2: explicit kind + subtype. Refund stays a financial event linked to
 * the original expense rather than mutating history.
 *
 * P10 #3: signed money semantics.
 *  - BANK / CREDIT_CARD: amountMinor is the absolute value; the [direction]
 *    axis tells whether money is leaving (DEBIT) or entering (CREDIT) the
 *    account. Display formats prepend a "−" / "+" accordingly.
 *  - CASH: same as BANK; direction is DEBIT when cash is paid out, CREDIT
 *    when cash is received (e.g. a refund or top-up).
 *  - OTHER_LIABILITY (e.g. credit-card bill): DEBIT increases the owed
 *    amount, CREDIT decreases it.
 *
 * P10 #2 enum + subtype. The two axes are independent so we can introduce
 * subtypes without churning the kind enum.
 */
enum class TxKind {
    EXPENSE, INCOME, TRANSFER, REFUND, FEE, CASH_MOVE, UNKNOWN
}

/** Optional sub-axis for richer UX (e.g. card on UPI, partial refund). */
enum class TxSubtype {
    /** EXPENSE: card-not-present (online / ecom). */
    CARD_ONLINE,
    /** EXPENSE: card-present POS. */
    CARD_POS,
    /** EXPENSE/INCOME: UPI rail. */
    UPI,
    /** EXPENSE/INCOME: IMPS / NEFT / RTGS bank transfer. */
    BANK_TRANSFER,
    /** EXPENSE: ATM cash withdrawal. */
    ATM_WITHDRAWAL,
    /** INCOME: salary credit. */
    SALARY,
    /** INCOME/EXPENSE: interest credit. */
    INTEREST,
    /** INCOME: cashback. */
    CASHBACK,
    /** REFUND: full refund. */
    FULL,
    /** REFUND: partial refund. */
    PARTIAL,
    /** CASH_MOVE: ATM cash withdrawal. */
    CASH_OUT,
    /** CASH_MOVE: cash deposit. */
    CASH_IN,
    /** Unclassified subtype; unknown stays unknown. */
    UNKNOWN,
}

/**
 * P10 #1 / module 142: explicit lifecycle.
 * Distinct from the older LifecycleState enum in [Core.kt] which is centred
 * on the parser pipeline. TxStatus is the authoritative status the UI reads.
 *
 * Transitions:
 *   PENDING           -> POSTED               (parser finalized)
 *   PENDING           -> REVIEW_REQUIRED      (ambiguous / low confidence)
 *   PENDING | POSTED  -> FAILED               (terminal failure)
 *   PENDING | POSTED | REVIEW_REQUIRED | FAILED -> DELETED   (soft delete)
 *
 * Re-processing never resurrects a DELETED event.
 */
enum class TxStatus {
    PENDING, POSTED, REVIEW_REQUIRED, FAILED, DELETED
}

/**
 * P10 #1: normalized transaction. Mirrors the new v6 TransactionEntity
 * columns. Construction is centralised in [buildTransaction] so all
 * call sites apply the same defaulting + invariants.
 */
data class TransactionV6(
    val id: EntityId,
    val messageId: EntityId?,         // null = manual / import (no SMS)
    val accountId: EntityId,
    val categoryId: EntityId?,
    /** Absolute value in minor units; sign is encoded in [direction]. */
    val amountMinor: Long,
    val currencyCode: String,
    val occurredAt: Instant,
    val localDate: java.time.LocalDate,
    val counterparty: String?,
    val counterpartyNormalized: String?,
    val merchant: String?,            // display name; distinct from counterparty
    val description: String?,         // free-form; unknown stays null
    val referenceId: String?,         // bank ref / UTR / RRN
    val cardMask: String?,            // 4-digit normalized or null
    val rail: String?,                // matches parser Rail names
    val kind: TxKind,
    val subtype: TxSubtype?,
    val direction: PostingDirection,
    val status: TxStatus,
    val provenance: Provenance,
    val correctionOrigin: Provenance? = null,
    val dedupeKey: String,
    val postingGroupId: String? = null,      // shared with all child LedgerEntry rows
    /**
     * P11 transfer-group identity. Two [TransactionV6] rows representing
     * the two sides of a transfer (one per account) share the same value.
     * null for non-transfer events.
     */
    val transferGroupId: String? = null,
    val deletedAt: Instant? = null,
    val deletedReason: String? = null,
) {
    init {
        require(currencyCode.length == 3) { "currencyCode must be ISO-4217" }
        require(amountMinor >= 0) {
            "amountMinor must be the absolute value; sign is encoded in direction"
        }
        require(rail == null || rail.uppercase() in RAIL_NAMES) {
            "unknown rail '$rail'"
        }
        require(deletedAt == null || status == TxStatus.DELETED) {
            "deletedAt set but status is $status, not DELETED"
        }
        // transferGroupId is meaningful only for TRANSFER / CASH_MOVE siblings.
        if (transferGroupId != null) {
            require(kind == TxKind.TRANSFER || kind == TxKind.CASH_MOVE) {
                "transferGroupId set but kind is $kind, not TRANSFER/CASH_MOVE"
            }
        }
    }

    companion object {
        /** Rail-name whitelist; mirrors parser/Rail minus UNKNOWN. */
        val RAIL_NAMES: Set<String> = setOf(
            "UPI", "IMPS", "NEFT", "RTGS", "CARD_POS", "CARD_ONLINE", "ATM", "ACH", "UNKNOWN",
        )
    }
}

/** P10 #3 signed-money view used by the UI. */
data class SignedMoney(val amountMinor: Long, val currencyCode: String, val direction: PostingDirection) {
    /** Signed amount for display; positive on CREDIT, negative on DEBIT. */
    fun signed(): Long = if (direction == PostingDirection.CREDIT) amountMinor else -amountMinor
}

/** P11: refund kind. PARTIAL refunds carry an amountMinor on the link that
 *  may be less than the refund transaction's amountMinor. */
enum class RefundKind { FULL, PARTIAL }

/** P11: role for a [TransactionLinkEntity] row. */
enum class TransactionLinkRole { REFUND, FEE }
