package com.example.fintrack.domain.model

import java.time.Instant

/**
 * Stage 8 P18 — cash reconciliation and ATM linking.
 *
 * Cash is a first-class account (AccountType.CASH already exists). This model
 * covers the explicit reconciliation event and the ATM withdrawal -> cash
 * movement relationship. Transactions are never duplicated: the link is a
 * relationship row.
 */
enum class ReconciliationOutcome { EXACT, UNDER, OVER }

data class CashReconciliation(
    val id: String,
    val accountId: String,
    val countedMinor: Long,
    val ledgerDerivedMinor: Long,
    /** counted - derived. */
    val differenceMinor: Long,
    val outcome: ReconciliationOutcome,
    /** Adjustment transaction id when the user chose to book the difference. */
    val adjustmentTransactionId: String?,
    /** Mandatory whenever difference != 0. */
    val reason: String?,
    val atEpochMs: Long,
) {
    init {
        require(countedMinor >= 0)
    }
}

/** How an ATM withdrawal was matched to a cash movement. */
enum class AtmMatchKind { AMOUNT_DATE_ACCOUNT, MANUAL }

data class AtmCashLink(
    val id: String,
    val withdrawalTransactionId: String,
    val cashAccountId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val withdrawalOccurredAtEpochMs: Long,
    val matchedBy: AtmMatchKind,
    /** Number of equally-plausible candidates when matched automatically. */
    val candidateCount: Int,
    val ambiguous: Boolean,
    val confirmedByUser: Boolean,
    val createdAtEpochMs: Long,
)

/**
 * Candidate withdrawal for ATM matching. Exposed so ambiguity can be shown
 * to the user instead of silently picking one.
 */
data class AtmWithdrawalCandidate(
    val transactionId: String,
    val accountId: String,
    val amountMinor: Long,
    val occurredAt: Instant,
)
