package com.example.fintrack.domain.dedupe

import com.example.fintrack.domain.model.DedupeClusterMember
import com.example.fintrack.domain.model.DedupeVerdict
import java.security.MessageDigest

/**
 * P09 #2: scored candidate/cluster problem.
 *
 * The engine takes a pair of "candidate" events and returns a verdict +
 * per-signal breakdown. The candidate representation is intentionally
 * minimal so the engine can be re-used for any pair (parser-raw event vs
 * stored event, stored event vs stored event, LLM interpretation vs
 * stored event, etc.).
 *
 * Reference IDs dominate: if two events share a non-null [refId] and the
 * rails agree, that's a strong merge signal. Same-amount purchases close
 * in time are NOT auto-merged; they go to REVIEW (P09 #4).
 */
object DedupeEngine {

    /** Tunables. Kept as a single object so reviewers can audit thresholds together. */
    object Thresholds {
        /** Top score at or above this is AUTO_MERGE. */
        const val MERGE: Double = 0.85
        /** Below MERGE but at or above this routes to REVIEW. */
        const val REVIEW: Double = 0.35
    }

    /**
     * Per-signal weights. Sum = 1.0. The score is a weighted sum bounded to
     * [0,1]; individual signals are 1.0 when fully matching, 0.0 when not
     * applicable or contradictory.
     */
    private val W_REF = 0.45
    private val W_AMOUNT = 0.15
    private val W_RAIL = 0.10
    private val W_ACCOUNT = 0.10
    private val W_MERCHANT = 0.10
    private val W_CARD = 0.05
    private val W_TS = 0.05
    // weight total = 1.00

    /** Strongest single signal: same non-null refId on a non-broad rail. */
    private fun refSignal(a: Candidate, b: Candidate): Double {
        if (a.refId == null || b.refId == null) return 0.0
        if (a.refId.isBlank() || b.refId.isBlank()) return 0.0
        if (a.refId != b.refId) return 0.0
        // refId match only counts when rails agree (otherwise could be a
        // different rail re-using the same ref, e.g. NEFT and UPI on same day)
        if (a.rail != null && b.rail != null && a.rail != b.rail) return 0.5
        return 1.0
    }

    /** Same amount and same currency. */
    private fun amountSignal(a: Candidate, b: Candidate): Double {
        if (a.amountMinor == null || b.amountMinor == null) return 0.0
        if (a.currencyCode != b.currencyCode) return 0.0
        if (a.amountMinor != b.amountMinor) return 0.0
        return 1.0
    }

    private fun railSignal(a: Candidate, b: Candidate): Double =
        if (a.rail != null && b.rail != null && a.rail == b.rail) 1.0 else 0.0

    private fun accountSignal(a: Candidate, b: Candidate): Double =
        if (a.accountId != null && b.accountId != null && a.accountId == b.accountId) 1.0 else 0.0

    private fun merchantSignal(a: Candidate, b: Candidate): Double {
        val na = a.counterpartyNormalized ?: return 0.0
        val nb = b.counterpartyNormalized ?: return 0.0
        if (na.isBlank() || nb.isBlank()) return 0.0
        return if (na == nb) 1.0 else 0.0
    }

    private fun cardSignal(a: Candidate, b: Candidate): Double =
        if (a.cardMask != null && b.cardMask != null && a.cardMask == b.cardMask) 1.0 else 0.0

    /**
     * Time proximity: 1.0 at 0 minutes, 0.0 at >= 30 minutes, linear in between.
     * P09 #2: timestamp proximity is a soft signal — same-value purchases
     * within 5 minutes are deliberately NOT auto-merged, so we keep ts low.
     */
    private fun tsSignal(a: Candidate, b: Candidate): Double {
        if (a.occurredAtEpochMs == null || b.occurredAtEpochMs == null) return 0.0
        val deltaMin = kotlin.math.abs(a.occurredAtEpochMs - b.occurredAtEpochMs) / 60_000.0
        if (deltaMin >= 30.0) return 0.0
        return ((30.0 - deltaMin) / 30.0).coerceIn(0.0, 1.0)
    }

    /**
     * Score one pair. Returns a [ScoreResult] containing the per-signal
     * breakdown (used by the Review UI to explain *why* a cluster was
     * proposed), the aggregate score, and the verdict.
     */
    fun scorePair(a: Candidate, b: Candidate): ScoreResult {
        val signals = linkedMapOf(
            "ref" to refSignal(a, b),
            "amount" to amountSignal(a, b),
            "rail" to railSignal(a, b),
            "account" to accountSignal(a, b),
            "merchant" to merchantSignal(a, b),
            "card" to cardSignal(a, b),
            "ts" to tsSignal(a, b),
        )
        // Hard contradiction guards (P09 #4): these facts can never be true
        // of the same financial event, so the pair is rejected outright
        // regardless of how well other signals score.
        val contradicted =
            (a.accountId != null && b.accountId != null && a.accountId != b.accountId) ||
                (a.currencyCode != null && b.currencyCode != null && a.currencyCode != b.currencyCode) ||
                (a.direction != null && b.direction != null && a.direction != b.direction) ||
                (a.refId != null && b.refId != null && a.refId != b.refId)
        if (contradicted) {
            return ScoreResult(score = 0.0, verdict = DedupeVerdict.REJECT, signals = signals)
        }
        val score = (signals["ref"]!! * W_REF +
            signals["amount"]!! * W_AMOUNT +
            signals["rail"]!! * W_RAIL +
            signals["account"]!! * W_ACCOUNT +
            signals["merchant"]!! * W_MERCHANT +
            signals["card"]!! * W_CARD +
            signals["ts"]!! * W_TS)
            .coerceIn(0.0, 1.0)
        val verdict = when {
            score >= Thresholds.MERGE -> DedupeVerdict.AUTO_MERGE
            score >= Thresholds.REVIEW -> DedupeVerdict.REVIEW
            else -> DedupeVerdict.REJECT
        }
        return ScoreResult(score = score, verdict = verdict, signals = signals)
    }

    /**
     * Build a stable cluster identity. Two runs with the same ordered pair
     * inputs produce the same identity, so re-clustering is idempotent
     * (P09 #5).
     *
     * Inputs are sorted by [Candidate.eventId] to make identity
     * order-independent within a pair.
     */
    fun clusterIdentity(a: Candidate, b: Candidate): String {
        val (x, y) = if (a.eventId <= b.eventId) a to b else b to a
        val raw = listOf(
            x.eventId, y.eventId,
            (x.amountMinor ?: "?").toString(),
            x.currencyCode ?: "?",
            x.direction ?: "?",
            x.rail ?: "?",
            x.accountId ?: "?",
            x.refId ?: "?",
            x.counterpartyNormalized ?: "?",
            x.cardMask ?: "?",
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Convert a [ScoreResult] to a [DedupeClusterMember] for persistence. */
    fun toMember(eventId: String, score: ScoreResult): DedupeClusterMember =
        DedupeClusterMember(eventId = eventId, score = score.score, signals = score.signals)
}

/**
 * Minimal pair-representation passed to the engine. Fields are nullable
 * deliberately: "unknown stays unknown" — a missing refId is a different
 * situation from a blank refId or a non-matching one.
 */
data class Candidate(
    val eventId: String,
    val amountMinor: Long?,
    val currencyCode: String?,
    val direction: String?,             // DEBIT | CREDIT
    val rail: String?,                  // UPI | IMPS | NEFT | ...
    val accountId: String?,
    val refId: String?,
    val counterpartyNormalized: String?,
    val cardMask: String?,
    val occurredAtEpochMs: Long?,
)

data class ScoreResult(
    val score: Double,
    val verdict: DedupeVerdict,
    val signals: Map<String, Double>,
)
