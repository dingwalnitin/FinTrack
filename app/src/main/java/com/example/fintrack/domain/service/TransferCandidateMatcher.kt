package com.example.fintrack.domain.service

import com.example.fintrack.domain.dedupe.Candidate
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.policy.ScoreResult
import com.example.fintrack.domain.policy.TransferEngine
import java.time.Instant

/**
 * P11 #1: scans recent posted events for likely (DEBIT, CREDIT) pairs and
 * surfaces transfer proposals via Review.
 *
 * The matcher is read-only: it never mutates state. The [TransferService]
 * is what actually writes the link; the matcher just supplies the inputs.
 *
 * Scoring lives in [TransferEngine] so the thresholds are auditable.
 */
class TransferCandidateMatcher(
    private val source: TransferCandidateSource,
) {

    /**
     * Find transfer candidates. The window is a symmetric [Instant] range;
     * callers typically pass "now() - 1 day" .. "now()" or similar.
     */
    suspend fun findCandidates(
        accountIds: List<String>,
        from: Instant,
        to: Instant,
    ): List<TransferProposal> {
        val rows = source.candidatesInWindow(
            accountIds = accountIds,
            fromEpochMs = from.toEpochMilli(),
            toEpochMs = to.toEpochMilli(),
        )
        val candidates = rows.map { it.toCandidate() }
        val out = mutableListOf<TransferProposal>()
        // j > i so each unordered pair is considered exactly once. The debit /
        // credit orientation is normalized below, so iterating both (i, j) and
        // (j, i) would emit the same proposal twice.
        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                if (candidates[i].accountId == candidates[j].accountId) continue
                val debit = candidates[i]
                val credit = candidates[j]
                val ordered = if (debit.direction == "DEBIT") debit to credit else credit to debit
                val result = TransferEngine.scorePair(ordered.first, ordered.second) ?: continue
                if (result.verdict == TransferEngine.Verdict.REJECT) continue
                out += TransferProposal(
                    debitEventId = ordered.first.eventId,
                    creditEventId = ordered.second.eventId,
                    score = result.score,
                    verdict = result.verdict,
                    signals = result.signals,
                )
            }
        }
        return out
    }
}

/** Read-only source for the matcher. */
interface TransferCandidateSource {
    suspend fun candidatesInWindow(
        accountIds: List<String>,
        fromEpochMs: Long,
        toEpochMs: Long,
    ): List<com.example.fintrack.data.db.TransactionEntity>
}

private fun com.example.fintrack.data.db.TransactionEntity.toCandidate(): Candidate = Candidate(
    eventId = id,
    amountMinor = amountMinor,
    currencyCode = currencyCode,
    direction = directionForCandidate(kind, subtype),
    rail = rail,
    accountId = accountId,
    refId = referenceId,
    counterpartyNormalized = counterpartyNormalized,
    cardMask = cardMask,
    occurredAtEpochMs = occurredAtEpochMs,
)

/**
 * Best-effort: P10 transactions carry kind + amountMinor (absolute).
 * For the candidate matcher we need a direction signal. The
 * [com.example.fintrack.domain.policy.PostingPolicy.defaultDirection] rules
 * are mirrored here without importing data-layer types.
 */
private fun directionForCandidate(kind: String?, subtype: String?): String? = when (kind) {
    "EXPENSE", "FEE" -> "DEBIT"
    "INCOME", "REFUND" -> "CREDIT"
    "CASH_MOVE" -> when (subtype) {
        "CASH_OUT" -> "DEBIT"
        "CASH_IN" -> "CREDIT"
        else -> "UNKNOWN"
    }
    "TRANSFER" -> null // transfers are themselves transfers; skip
    else -> "UNKNOWN"
}

/** A single transfer proposal surfaced to the Review queue. */
data class TransferProposal(
    val debitEventId: String,
    val creditEventId: String,
    val score: Double,
    val verdict: TransferEngine.Verdict,
    val signals: Map<String, Double>,
)
