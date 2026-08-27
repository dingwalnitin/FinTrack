package com.example.fintrack.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v10 Stage 8 additive entities (P16 budgets, P17 recurring/subscriptions,
 * P18 cash reconciliation + ATM linking).
 *
 * Design invariants (App Bible + stage contract):
 *  - Budgets are fully DERIVED from ledger data. A budget row stores only
 *    policy (scope, target, rollover, exclusions); actual-vs-budget numbers
 *    are always computed from transactions/postings — never stored as a
 *    second balance truth.
 *  - Rollover/reset is explicit and deterministic: every period boundary
 *    that applies a rollover writes a [BudgetPeriodEntity] row recording
 *    exactly how much was carried in. No silent carry-over.
 *  - Recurring patterns are interpretations over transactions, not facts.
 *    They carry confidence, last-seen/next-expected metadata and a durable
 *    user decision (CONFIRMED / REJECTED / CANCELLED) that survives
 *    re-detection. Observed amounts are kept per-observation so amount
 *    variance never breaks a recurrence.
 *  - Cash reconciliations are explicit events. They never mutate opening
 *    balances; differences become optional adjustment transactions with a
 *    mandatory reason.
 *  - ATM links are relationship rows between an existing withdrawal
 *    transaction and a cash account — transactions are never duplicated.
 *
 * Idempotency: stable identity hashes backed by unique indices throughout,
 * matching the v6–v9 convention.
 */

// ---- P16: budgets ----

@Entity(
    tableName = "budgets",
    indices = [
        Index("categoryId"),
        Index("accountId"),
        Index("status"),
        Index(value = ["scopeIdentity"], unique = true),
    ],
)
data class BudgetEntity(
    @PrimaryKey val id: String,
    /** Human label ("Eating out", "Monthly total"). */
    val name: String,
    /** CATEGORY | ACCOUNT | OVERALL. */
    val scopeKind: String,
    /** Stable category id when scopeKind == CATEGORY; null otherwise. */
    val categoryId: String?,
    /** Account filter when scopeKind == ACCOUNT or a scoped overall; null = all accounts. */
    val accountId: String?,
    /** Period length: MONTHLY (only supported value today). */
    val periodType: String,
    /** Period start anchor day-of-month (1..28) so boundaries are deterministic. */
    val startDayOfMonth: Int,
    val targetAmountMinor: Long,
    val currencyCode: String,
    /** When true, unspent remainder carries into the next period (capped). */
    val rolloverEnabled: Boolean,
    /** Upper bound on carried-in amount; null = uncapped. */
    val rolloverCapMinor: Long?,
    /**
     * Exclusion policy JSON (MiniJson-encoded): excluded account ids,
     * excluded tags, excluded tx kinds. Deterministic and previewable.
     */
    val exclusionsJson: String,
    /** sha-256(scopeKind | categoryId? | accountId? | periodType) — one budget per scope. */
    val scopeIdentity: String,
    val status: String, // ACTIVE | ARCHIVED
    val sourceKind: String,
    val sourceVersion: String,
    val createdAtEpochMs: Long,
)

/**
 * One row per (budget, period) where a rollover/reset decision was applied.
 * Written inside the same transaction that computes the new period so the
 * carry-in amount is auditable and reproducible — no silent carry-over.
 */
@Entity(
    tableName = "budget_periods",
    indices = [
        Index(value = ["budgetId", "periodStartEpochDay"], unique = true),
        Index("budgetId"),
    ],
)
data class BudgetPeriodEntity(
    @PrimaryKey val id: String,
    val budgetId: String,
    val periodStartEpochDay: Long,
    val periodEndEpochDay: Long,
    /** Amount carried in from the previous period (0 when rollover disabled). */
    val rolloverInMinor: Long,
    /** How the boundary was resolved: RESET | ROLLOVER_APPLIED | ROLLOVER_CAPPED. */
    val boundaryAction: String,
    val computedAtEpochMs: Long,
)

// ---- P17: recurring patterns ----

@Entity(
    tableName = "recurring_patterns",
    indices = [
        Index(value = ["patternIdentity"], unique = true),
        Index("accountId"),
        Index("categoryId"),
        Index("status"),
        Index("nextExpectedEpochMs"),
    ],
)
data class RecurringPatternEntity(
    @PrimaryKey val id: String,
    /**
     * sha-256(accountId | counterpartyNormalized | periodicity) — durable
     * identity so re-running detection updates rather than duplicates.
     */
    val patternIdentity: String,
    val accountId: String,
    val counterpartyNormalized: String?,
    val merchant: String?,
    val categoryId: String?,
    /** MONTHLY | QUARTERLY | ANNUAL | CUSTOM. */
    val periodicity: String,
    /** Median observed interval in days (drift-tolerant). */
    val intervalDays: Int,
    /** Canonical (median) observed amount. Variance lives in observations. */
    val canonicalAmountMinor: Long,
    val minObservedAmountMinor: Long,
    val maxObservedAmountMinor: Long,
    val currencyCode: String,
    /** Detection confidence in [0,1]. Advisory until CONFIRMED by the user. */
    val confidence: Double,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    /** Next expected charge (estimate — clearly labelled as such in UI). */
    val nextExpectedEpochMs: Long?,
    /** DETECTED | CONFIRMED | REJECTED | CANCELLED. User decisions are durable. */
    val status: String,
    /** True when evidence supports a subscription (vs generic recurrence). Unknown stays false-with-null-evidence. */
    val isSubscription: Boolean?,
    /** Actor of the current status: SYSTEM | USER. */
    val decidedBy: String,
    val sourceKind: String,
    val sourceVersion: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/** One observed occurrence backing a pattern. Append-only; idempotent. */
@Entity(
    tableName = "recurring_observations",
    indices = [
        Index(value = ["observationIdentity"], unique = true),
        Index("patternId"),
        Index("transactionId"),
    ],
)
data class RecurringObservationEntity(
    @PrimaryKey val id: String,
    val patternId: String,
    val transactionId: String,
    val amountMinor: Long,
    val occurredAtEpochMs: Long,
    /** sha-256(patternId | transactionId). */
    val observationIdentity: String,
    val createdAtEpochMs: Long,
)

// ---- P18: cash reconciliation + ATM links ----

/**
 * Explicit reconciliation event for a CASH account. Never mutates the
 * opening balance; a non-zero difference may optionally produce an
 * adjustment transaction carrying a mandatory reason.
 */
@Entity(
    tableName = "cash_reconciliations",
    indices = [
        Index("accountId"),
        Index("atEpochMs"),
        Index(value = ["reconciliationIdentity"], unique = true),
    ],
)
data class CashReconciliationEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val countedMinor: Long,
    val ledgerDerivedMinor: Long,
    val differenceMinor: Long, // counted - derived
    /** EXACT | UNDER (counted < derived) | OVER (counted > derived). */
    val outcome: String,
    /** Adjustment transaction id when the user chose to book the difference. */
    val adjustmentTransactionId: String?,
    /** Mandatory human reason whenever differenceMinor != 0. */
    val reason: String?,
    /** sha-256(accountId | counted | derived | atEpochMs). */
    val reconciliationIdentity: String,
    val sourceKind: String,
    val sourceVersion: String,
    val atEpochMs: Long,
)

/**
 * Relationship row linking an existing ATM withdrawal transaction to the
 * cash movement it produced. The withdrawal transaction is NEVER duplicated;
 * this table only records the provenance of the link.
 */
@Entity(
    tableName = "atm_cash_links",
    indices = [
        Index(value = ["linkIdentity"], unique = true),
        Index("withdrawalTransactionId"),
        Index("cashAccountId"),
        Index("confirmedByUser"),
    ],
)
data class AtmCashLinkEntity(
    @PrimaryKey val id: String,
    val withdrawalTransactionId: String,
    val cashAccountId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val withdrawalOccurredAtEpochMs: Long,
    /** How the match was made: AMOUNT_DATE_ACCOUNT | MANUAL. */
    val matchedBy: String,
    /** Number of equally-plausible candidate withdrawals at match time (>1 = ambiguous). */
    val candidateCount: Int,
    val ambiguous: Boolean,
    val confirmedByUser: Boolean,
    /** sha-256(withdrawalTransactionId | cashAccountId). */
    val linkIdentity: String,
    val sourceKind: String,
    val sourceVersion: String,
    val createdAtEpochMs: Long,
)
