package com.example.fintrack.domain.model

import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.LifecycleState
import com.example.fintrack.domain.model.Money
import com.example.fintrack.domain.model.Provenance
import java.time.Instant

/**
 * Canonical vocabulary:
 *  - Message      = raw evidence (immutable)
 *  - Transaction  = normalized interpretation of evidence (mutable via corrections)
 *  - Ledger entry = posting against an account
 *  - Account      = balance-bearing container
 *  - Transfer     = explicit relationship between two postings (never implied)
 */

/** Raw evidence. Immutable once written. */
data class Message(
    val id: EntityId,
    val body: String,
    val receivedAt: Instant,
    val provenance: Provenance,
)

/** Normalized interpretation. User corrections are first-class: correctionOrigin != null survives reprocessing. */
data class Transaction(
    val id: EntityId,
    val messageId: EntityId,
    val amount: Money,
    val occurredAt: Instant,
    val counterparty: String?,
    val state: LifecycleState,
    val provenance: Provenance,
    val correctionOrigin: Provenance? = null,
    /** True when money leaves the account (EXPENSE/FEE/TRANSFER/CASH_MOVE); false for INCOME/REFUND. */
    val directionDebit: Boolean = true,
    /** Stage 13 (B): kind string for richer filter (EXPENSE/INCOME/TRANSFER/etc). Null when unavailable. */
    val kind: String? = null,
    /** Stage 13 (B): category id for filter-by-category. Null when unavailable. */
    val categoryId: String? = null,
    /** Stage 13 (B): account id for filter-by-account. Null when unavailable. */
    val accountId: String? = null,
    /** Stage 13 (B): payment rail (UPI/IMPS/NEFT/etc). Null when unavailable. */
    val rail: String? = null,
)

enum class PostingDirection { DEBIT, CREDIT }

/** Ledger entry = posting. */
data class LedgerEntry(
    val id: EntityId,
    val transactionId: EntityId,
    val accountId: EntityId,
    val direction: PostingDirection,
    val amount: Money,
)

// Account (balance-bearing container) is defined in Accounts.kt — v3 increment.

/** Explicit transfer/settlement relationship between two ledger entries. */
data class TransferLink(
    val id: EntityId,
    val fromEntryId: EntityId,
    val toEntryId: EntityId,
)
