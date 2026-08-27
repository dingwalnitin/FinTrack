package com.example.fintrack.domain.model

import java.time.Instant
import java.time.LocalDate

/**
 * Stage 6 P12 — credit-card domain model.
 *
 * Design invariants (App Bible + P12):
 *  - A credit card is modelled as a liability account. The credit_card row
 *    carries the card-specific facts (limit, statement cycle, due date,
 *    reward balance) that the bare [Account] model intentionally does not.
 *    The card row references its parent account by [accountId] so the
 *    authoritative balance view remains on [Account].
 *  - Outstanding balance on the card grows on a charge (DEBIT) and shrinks
 *    on a payment / refund / cashback posted to the card (CREDIT). The
 *    "amount owed" view is therefore the absolute sum of active postings.
 *  - Credit limit is informational — it is NEVER fabricated when absent.
 *    Downstream metrics (utilization) show "unknown" when the limit is null
 *    rather than guessing.
 *  - Statement cycles are durable: a statement is created from the SMS or
 *    from manual entry; subsequent matching is idempotent on the
 *    [StatementIdentity] hash (statement period + account).
 *  - Pending vs posted distinction is preserved: a [CardStatementLine]
 *    carries its own status so a pending swipe at the start of the cycle
 *    is not double-counted when the same charge appears as posted later.
 *  - Card payment is a liability settlement (kind=TRANSFER or a dedicated
 *    SETTLEMENT posting): it is never a second expense. The payment row
 *    carries the funding account and the statement it settles.
 *  - Reward points / cashback are first-class benefit events. Cashback
 *    posted to the card reduces the amount owed and is NOT a refund.
 *    Reward points are tracked separately on the card row.
 *  - Adjustments (late fee, interest, fee reversal, goodwill credit) are
 *    explicit events linked to the statement they adjust. The original
 *    transactions are never mutated.
 */
enum class CardLifecycle { ACTIVE, ARCHIVED }

/**
 * One credit card = one liability account + card-specific metadata.
 * The row is the source of truth for limit, reward balance, statement
 * cycle and next due date. [accountId] is the foreign key to [Account].
 */
data class CreditCard(
    val id: EntityId,
    val accountId: EntityId,                 // 1:1 with the [Account] row
    val nickname: String,
    val issuer: String?,                     // normalized bank/issuer; null = unknown
    val cardMask: String?,                   // exactly 4 digits or null
    val currencyCode: String,                // ISO-4217
    val lifecycle: CardLifecycle,
    val createdAt: Instant,

    /**
     * Total credit limit in minor units. null = unknown (NEVER fabricated).
     * Downstream "utilization" metrics must surface "unknown" instead of
     * defaulting to a guessed limit.
     */
    val creditLimitMinor: Long?,

    /**
     * Statement cycle anchor day of month. Some issuers charge on a
     * fixed calendar day (e.g. 5th); others use an anniversary cycle.
     * null = unknown. [statementCycleDays] is the alternative: the
     * number of days in the cycle (commonly 30).
     */
    val statementDayOfMonth: Int?,
    val statementCycleDays: Int?,            // typically 30; null = unknown

    /**
     * Due-date rule. If null, the engine cannot infer a due date and
     * surfaces "unknown" until a statement is recorded. The user
     * confirmation flow always wins over a derived value.
     */
    val dueDayOfMonth: Int?,
    val dueDaysAfterStatement: Int?,         // commonly 20; null = unknown

    /**
     * Accumulated reward points (NOT minor units). null = unknown. The
     * points are informational and never used as a balance against
     * the card's owed amount.
     */
    val rewardPointsBalance: Long?,
) {
    init {
        require(currencyCode.length == 3)
        if (cardMask != null) {
            require(cardMask.length == 4 && cardMask.all { it.isDigit() }) {
                "cardMask must be exactly 4 digits or null"
            }
        }
        if (creditLimitMinor != null) require(creditLimitMinor >= 0)
        if (statementDayOfMonth != null) {
            require(statementDayOfMonth in 1..28) { // 1..28 keeps every month happy
                "statementDayOfMonth must be 1..28"
            }
        }
        if (dueDayOfMonth != null) {
            require(dueDayOfMonth in 1..28) { "dueDayOfMonth must be 1..28" }
        }
        if (dueDaysAfterStatement != null) require(dueDaysAfterStatement >= 0)
        if (statementCycleDays != null) require(statementCycleDays in 1..120)
    }
}

/** Stable identity for a statement: sha-256(accountId | periodStart | periodEnd). */
typealias StatementIdentity = String

/**
 * P12 #2: statement snapshot.
 *
 * A statement is the issuer's view of the cycle. It carries:
 *  - the period (start/end, both inclusive)
 *  - the total due and the minimum due (the latter is informational; if
 *    null, the engine surfaces "unknown" instead of guessing a minimum)
 *  - the due date (also surfaced separately so the due-date banner can
 *    highlight approaching deadlines even before a statement is recorded)
 *  - the durable [statementIdentity] hash for idempotent writes
 */
enum class StatementStatus { OPEN, CLOSED, SETTLED, OVERDUE }

data class CardStatement(
    val id: EntityId,
    val cardId: EntityId,                    // -> CreditCard.id
    val accountId: EntityId,                 // -> Account.id (denormalized for fast lookups)
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val dueDate: LocalDate?,
    val totalDueMinor: Long,                 // total amount owed at statement close
    val minDueMinor: Long?,                  // min due (informational; null = unknown)
    val currencyCode: String,
    val status: StatementStatus,
    val statementIdentity: StatementIdentity,
    val capturedAt: Instant,
    val provenance: Provenance,
) {
    init {
        require(currencyCode.length == 3)
        require(totalDueMinor >= 0) { "totalDueMinor must be >= 0" }
        if (minDueMinor != null) {
            require(minDueMinor >= 0) { "minDueMinor must be >= 0" }
            require(minDueMinor <= totalDueMinor) {
                "minDueMinor ($minDueMinor) cannot exceed totalDueMinor ($totalDueMinor)"
            }
        }
        require(!periodEnd.isBefore(periodStart)) {
            "periodEnd must be on or after periodStart"
        }
    }
}

/**
 * One statement line = one transaction counted in the statement, with
 * its own pending vs posted status so a pending swipe is not double-counted
 * with the same charge as posted later in the same cycle.
 */
enum class CardLineStatus { PENDING, POSTED, REVERSED, ADJUSTED }

data class CardStatementLine(
    val id: EntityId,
    val statementId: EntityId,               // -> CardStatement.id
    val transactionId: String?,              // -> TransactionEntity.id (when matched)
    val occurredAt: Instant,
    val localDate: LocalDate,
    val amountMinor: Long,                   // absolute; sign is in [direction]
    val currencyCode: String,
    val direction: PostingDirection,         // DEBIT = charge; CREDIT = refund/credit adjustment
    val status: CardLineStatus,
    val merchant: String?,                   // null = unknown
    val rail: String?,                       // CARD_POS, CARD_ONLINE, UPI_ON_CARD, ...
    val cardMask: String?,
    val referenceId: String?,
    val provenance: Provenance,
) {
    init {
        require(currencyCode.length == 3)
        require(amountMinor >= 0)
    }
}

/**
 * P12 #3: card payment = liability settlement. Distinct from an expense:
 * the card's owed amount shrinks by the payment amount and the funding
 * account's balance shrinks by the same amount. The payment is a single
 * logical event that touches TWO accounts; modelled as a transfer-like
 * double-posting so rail analytics do not double-count it as an expense.
 */
enum class CardPaymentStatus { PENDING, POSTED, FAILED, REVERSED }

data class CardPayment(
    val id: EntityId,
    val cardId: EntityId,                    // -> CreditCard.id (the account being settled)
    val statementId: EntityId?,              // -> CardStatement.id (when a specific statement is settled)
    val fundingAccountId: EntityId,          // -> Account.id (bank account the money came from)
    val amountMinor: Long,                   // absolute
    val currencyCode: String,
    val occurredAt: Instant,
    val localDate: LocalDate,
    val paymentStatus: CardPaymentStatus,
    val referenceId: String?,
    val provenance: Provenance,
) {
    init {
        require(currencyCode.length == 3)
        require(amountMinor > 0) { "card payment amount must be > 0" }
    }
}

/**
 * P12 #5: reward event. Captures cashback / reward-points postings
 * separately from refunds so the cashback-vs-refund classification is
 * explicit and net/gross expense treatment is correct.
 *
 * Cashback on a credit card is a CREDIT posting to the card (reduces
 * the owed amount) and is NOT a refund. Reward points are tracked
 * separately and do not affect the owed amount.
 */
enum class RewardKind { CASHBACK, REWARD_POINTS, VOUCHER, OTHER }

enum class RewardClassification { BENEFIT, REFUND }   // BENEFIT = not a refund

data class RewardEvent(
    val id: EntityId,
    val cardId: EntityId,
    val accountId: EntityId,                 // -> Account.id (denormalized for fast lookups)
    val statementId: EntityId?,              // optional: the statement this reward was credited in
    val transactionId: String?,              // optional: matched posted transaction
    val kind: RewardKind,
    val classification: RewardClassification, // BENEFIT vs REFUND explicit
    val cashbackAmountMinor: Long?,          // populated for CASHBACK / VOUCHER
    val pointsDelta: Long?,                  // populated for REWARD_POINTS (signed)
    val currencyCode: String,
    val occurredAt: Instant,
    val localDate: LocalDate,
    val provenance: Provenance,
) {
    init {
        require(currencyCode.length == 3)
        require(cashbackAmountMinor == null || cashbackAmountMinor >= 0) {
            "cashbackAmountMinor must be >= 0 when present"
        }
        when (kind) {
            RewardKind.CASHBACK, RewardKind.VOUCHER ->
                require(cashbackAmountMinor != null) {
                    "cashback/voucher rewards must carry cashbackAmountMinor"
                }
            RewardKind.REWARD_POINTS ->
                require(pointsDelta != null) {
                    "reward-points events must carry pointsDelta"
                }
            RewardKind.OTHER -> Unit // no specific amount required
        }
        // BENEFIT events are never a refund; the cashback-vs-refund line is
        // enforced at construction.
    }
}

/**
 * P12 #6: statement adjustment (late fee, interest, fee reversal,
 * goodwill credit). An adjustment is an explicit financial event linked
 * to a statement; the original transactions are never mutated.
 */
enum class AdjustmentKind { LATE_FEE, INTEREST, FEE_REVERSAL, GOODWILL_CREDIT, OTHER }

data class CardStatementAdjustment(
    val id: EntityId,
    val statementId: EntityId,
    val cardId: EntityId,
    val accountId: EntityId,
    val kind: AdjustmentKind,
    val amountMinor: Long,                   // absolute; sign in [direction]
    val currencyCode: String,
    val direction: PostingDirection,         // DEBIT = added to bill; CREDIT = reduces bill
    val occurredAt: Instant,
    val localDate: LocalDate,
    val provenance: Provenance,
    val reason: String?,                     // human-readable evidence
) {
    init {
        require(currencyCode.length == 3)
        require(amountMinor >= 0)
    }
}
