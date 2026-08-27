package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.AtmCashLink
import com.example.fintrack.domain.model.AtmMatchKind
import com.example.fintrack.domain.model.AtmWithdrawalCandidate
import com.example.fintrack.domain.model.CashReconciliation
import com.example.fintrack.domain.model.ReconciliationOutcome
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Stage 8 P18 — cash reconciliation and ATM linking.
 *
 * Pure domain logic:
 *  - Reconciliation compares counted cash against the ledger-derived balance.
 *    The difference is explicit (EXACT / UNDER / OVER); booking it is a
 *    separate user decision that produces an adjustment transaction with a
 *    mandatory reason. Opening balances are never silently altered.
 *  - ATM matching pairs an existing withdrawal transaction with a cash
 *    account by amount/date/account. Same-amount withdrawals within the
 *    window make the match ambiguous — ambiguity is exposed, never resolved
 *    by guessing. The link is a relationship row; no transaction duplication.
 */
class CashService(
    private val clock: () -> Instant = Instant::now,
) {

    // ---- reconciliation ----

    data class ReconciliationResult(
        val reconciliation: CashReconciliation,
        val outcome: ReconciliationOutcome,
    )

    /**
     * Evaluate counted vs derived. [reason] is mandatory when there is a
     * difference AND the caller intends to book it; evaluation itself only
     * requires a reason for non-zero differences when [booking] is true.
     */
    fun reconcile(
        accountId: String,
        countedMinor: Long,
        ledgerDerivedMinor: Long,
        reason: String?,
        booking: Boolean,
    ): Result<ReconciliationResult> {
        if (countedMinor < 0) {
            return Result.failure(IllegalArgumentException("countedMinor must be >= 0"))
        }
        val diff = countedMinor - ledgerDerivedMinor
        val outcome = when {
            diff == 0L -> ReconciliationOutcome.EXACT
            diff > 0 -> ReconciliationOutcome.OVER
            else -> ReconciliationOutcome.UNDER
        }
        if (diff != 0L && booking && reason.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("booking a difference requires a reason"))
        }
        val rec = CashReconciliation(
            id = java.util.UUID.randomUUID().toString(),
            accountId = accountId,
            countedMinor = countedMinor,
            ledgerDerivedMinor = ledgerDerivedMinor,
            differenceMinor = diff,
            outcome = outcome,
            adjustmentTransactionId = null,
            reason = reason,
            atEpochMs = clock().toEpochMilli(),
        )
        return Result.success(ReconciliationResult(rec, outcome))
    }

    /**
     * Small-difference logging policy: differences at or below this share of
     * the derived balance may be logged without an adjustment transaction;
     * larger ones should always be booked or investigated.
     */
    fun isSmallDifference(differenceMinor: Long, derivedMinor: Long): Boolean =
        derivedMinor > 0 && kotlin.math.abs(differenceMinor).toDouble() / derivedMinor <= SMALL_DIFF_RATIO

    // ---- ATM matching ----

    data class AtmMatchResult(
        val best: AtmWithdrawalCandidate?,
        val candidates: List<AtmWithdrawalCandidate>,
        val ambiguous: Boolean,
    )

    /**
     * Match a cash movement to withdrawal candidates by amount + date window.
     * Multiple equally-valid candidates => ambiguous=true and NO automatic
     * pick beyond exposing the first candidate as provisional.
     */
    fun matchAtmWithdrawal(
        candidates: List<AtmWithdrawalCandidate>,
        amountMinor: Long,
        around: Instant,
        maxDrift: Duration = Duration.ofHours(24),
    ): AtmMatchResult {
        val inWindow = candidates.filter { c ->
            c.amountMinor == amountMinor &&
                Duration.between(c.occurredAt, around).abs() <= maxDrift
        }.sortedBy { Duration.between(it.occurredAt, around).abs() }
        return AtmMatchResult(
            best = inWindow.firstOrNull(),
            candidates = inWindow,
            ambiguous = inWindow.size > 1,
        )
    }

    /** Build the durable link row for a chosen match. */
    fun buildLink(
        withdrawalTransactionId: String,
        cashAccountId: String,
        amountMinor: Long,
        currencyCode: String,
        withdrawalOccurredAtEpochMs: Long,
        matchedBy: AtmMatchKind,
        candidateCount: Int,
        ambiguous: Boolean,
        confirmedByUser: Boolean,
    ): AtmCashLink {
        require(amountMinor > 0)
        val link = AtmCashLink(
            id = java.util.UUID.randomUUID().toString(),
            withdrawalTransactionId = withdrawalTransactionId,
            cashAccountId = cashAccountId,
            amountMinor = amountMinor,
            currencyCode = currencyCode,
            withdrawalOccurredAtEpochMs = withdrawalOccurredAtEpochMs,
            matchedBy = matchedBy,
            candidateCount = candidateCount,
            ambiguous = ambiguous,
            confirmedByUser = confirmedByUser,
            createdAtEpochMs = clock().toEpochMilli(),
        )
        return link.copy(id = link.id)
    }

    fun linkIdentity(withdrawalTransactionId: String, cashAccountId: String): String =
        sha256("$withdrawalTransactionId|$cashAccountId")

    companion object {
        const val SMALL_DIFF_RATIO = 0.01

        fun sha256(raw: String): String =
            MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
