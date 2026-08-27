package com.example.fintrack.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v8 P12 + P13 additive entities (Stage 6).
 *
 * P12: credit cards. The `credit_cards` table is the source of truth
 * for card-specific facts (limit, statement cycle, due-date rule,
 * reward balance). Statements, statement lines, payments, reward
 * events and adjustments live in their own tables. The `accountId`
 * column on every card-related table is denormalized for fast lookups
 * but the authoritative balance container is still `accounts`.
 *
 * P13: EMI plans. One plan = many installments. A preclosure / refinance
 * is a separate event; historical installments are never rewritten.
 *
 * Idempotency: every link in this set has a stable `*Identity` column
 * backed by a unique index so re-running parsers / reprocessing is
 * a no-op.
 *
 * The new tables are append-only history. Status changes (e.g. plan
 * closed, statement settled) preserve the original rows for audit.
 */

@Entity(
    tableName = "credit_cards",
    indices = [
        Index(value = ["accountId"], unique = true), // 1:1 with the [Account] row
        Index("cardIdentity", unique = true),
        Index("lifecycle"),
    ],
)
data class CreditCardEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val nickname: String,
    /** sha-256(accountId | nickname | issuer | cardMask) for idempotent inserts. */
    val cardIdentity: String,
    val issuer: String?,
    val cardMask: String?,
    val currencyCode: String,
    val lifecycle: String, // ACTIVE | ARCHIVED
    val createdAtEpochMs: Long,
    val creditLimitMinor: Long?,
    val statementDayOfMonth: Int?,
    val statementCycleDays: Int?,
    val dueDayOfMonth: Int?,
    val dueDaysAfterStatement: Int?,
    val rewardPointsBalance: Long?,
)

@Entity(
    tableName = "card_statements",
    indices = [
        Index("cardId", "periodStartEpochDay"),
        Index("accountId"),
        Index("status"),
        Index("statementIdentity", unique = true),
        Index("dueDateEpochDay"),
    ],
)
data class CardStatementEntity(
    @PrimaryKey val id: String,
    val cardId: String,
    val accountId: String,
    val periodStartEpochDay: Long,
    val periodEndEpochDay: Long,
    val dueDateEpochDay: Long?,
    val totalDueMinor: Long,
    val minDueMinor: Long?,
    val currencyCode: String,
    val status: String, // OPEN | CLOSED | SETTLED | OVERDUE
    val statementIdentity: String,
    val capturedAtEpochMs: Long,
    val sourceKind: String,
    val sourceVersion: String,
)

@Entity(
    tableName = "card_statement_lines",
    indices = [
        Index("statementId"),
        Index("transactionId"),
        Index("status"),
        Index("cardId", "occurredAtEpochMs"),
        Index("lineIdentity", unique = true),
    ],
)
data class CardStatementLineEntity(
    @PrimaryKey val id: String,
    val statementId: String,
    val cardId: String,
    /** sha-256(statementId | transactionId? | amount | direction | occurredAt) for idempotency. */
    val lineIdentity: String,
    val transactionId: String?,
    val occurredAtEpochMs: Long,
    val localDateEpochDay: Long,
    val amountMinor: Long,
    val currencyCode: String,
    val direction: String, // DEBIT | CREDIT
    val status: String, // PENDING | POSTED | REVERSED | ADJUSTED
    val merchant: String?,
    val rail: String?,
    val cardMask: String?,
    val referenceId: String?,
    val sourceKind: String,
    val sourceVersion: String,
)

@Entity(
    tableName = "card_payments",
    indices = [
        Index("cardId"),
        Index("statementId"),
        Index("fundingAccountId"),
        Index("paymentIdentity", unique = true),
        Index("occurredAtEpochMs"),
    ],
)
data class CardPaymentEntity(
    @PrimaryKey val id: String,
    val cardId: String,
    /** Optional: when the payment targets a specific statement. */
    val statementId: String?,
    val fundingAccountId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val occurredAtEpochMs: Long,
    val localDateEpochDay: Long,
    val paymentStatus: String, // PENDING | POSTED | FAILED | REVERSED
    val referenceId: String?,
    /** sha-256(cardId | statementId? | fundingAccountId | amount | occurredAt). */
    val paymentIdentity: String,
    val sourceKind: String,
    val sourceVersion: String,
)

@Entity(
    tableName = "reward_events",
    indices = [
        Index("cardId"),
        Index("accountId"),
        Index("statementId"),
        Index("transactionId"),
        Index("rewardIdentity", unique = true),
        Index("classification"),
    ],
)
data class RewardEventEntity(
    @PrimaryKey val id: String,
    val cardId: String,
    val accountId: String,
    val statementId: String?,
    val transactionId: String?,
    val kind: String, // CASHBACK | REWARD_POINTS | VOUCHER | OTHER
    val classification: String, // BENEFIT | REFUND
    val cashbackAmountMinor: Long?,
    val pointsDelta: Long?,
    val currencyCode: String,
    val occurredAtEpochMs: Long,
    val localDateEpochDay: Long,
    val sourceKind: String,
    val sourceVersion: String,
    val sourceReason: String?,
    /** sha-256(cardId | kind | amount | points | occurredAt | statementId?). */
    val rewardIdentity: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "card_statement_adjustments",
    indices = [
        Index("statementId"),
        Index("cardId"),
        Index("kind"),
        Index("adjustmentIdentity", unique = true),
    ],
)
data class CardStatementAdjustmentEntity(
    @PrimaryKey val id: String,
    val statementId: String,
    val cardId: String,
    val accountId: String,
    val kind: String, // LATE_FEE | INTEREST | FEE_REVERSAL | GOODWILL_CREDIT | OTHER
    val amountMinor: Long,
    val currencyCode: String,
    val direction: String, // DEBIT | CREDIT
    val occurredAtEpochMs: Long,
    val localDateEpochDay: Long,
    val sourceKind: String,
    val sourceVersion: String,
    val reason: String?,
    /** sha-256(statementId | kind | amount | direction | occurredAt). */
    val adjustmentIdentity: String,
    val createdAtEpochMs: Long,
)

// ---- P13: EMI ----

@Entity(
    tableName = "emi_plans",
    indices = [
        Index("emiAccountId"),
        Index("status"),
        Index("planIdentity", unique = true),
        Index("refinancedFromPlanId"),
    ],
)
data class EmiPlanEntity(
    @PrimaryKey val id: String,
    val emiAccountId: String,
    val merchantOrBiller: String?,
    val referenceId: String?,
    val principalMinor: Long?,
    val interestRateAnnualBps: Int?,
    val installmentAmountMinor: Long?,
    val totalInstallments: Int?,
    val startDateEpochDay: Long?,
    val endDateEpochDay: Long?,
    val currencyCode: String,
    val status: String, // ACTIVE | CLOSED | PRECLOSED | REFINANCED | PAUSED
    val planIdentity: String,
    val refinancedFromPlanId: String?,
    val sourceKind: String,
    val sourceVersion: String,
    val capturedAtEpochMs: Long,
    val closedAtEpochMs: Long?,
)

@Entity(
    tableName = "emi_installments",
    indices = [
        Index("planId"),
        Index("status"),
        Index("transactionId"),
        Index("dueDateEpochDay"),
        Index(value = ["planId", "installmentNumber"], unique = true),
        Index("installmentIdentity", unique = true),
    ],
)
data class EmiInstallmentEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val installmentNumber: Int,
    val dueDateEpochDay: Long,
    val amountDueMinor: Long?,
    val amountPaidMinor: Long?,
    val currencyCode: String,
    val status: String, // DUE | PAID | MISSED | PARTIAL | SKIPPED
    val transactionId: String?,
    val installmentIdentity: String,
    val sourceKind: String,
    val sourceVersion: String,
)

@Entity(
    tableName = "emi_preclosures",
    indices = [
        Index("planId"),
        Index("kind"),
        Index("transactionId"),
        Index("preclosureIdentity", unique = true),
    ],
)
data class EmiPreclosureEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val occurredAtEpochMs: Long,
    val localDateEpochDay: Long,
    val principalOutstandingMinor: Long?,
    val feeMinor: Long?,
    val adjustmentMinor: Long?,
    val currencyCode: String,
    val kind: String, // FORECLOSURE | SETTLEMENT | BUYOUT
    val transactionId: String?,
    val sourceKind: String,
    val sourceVersion: String,
    /** sha-256(planId | occurredAt | kind). */
    val preclosureIdentity: String,
    val createdAtEpochMs: Long,
)
