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
