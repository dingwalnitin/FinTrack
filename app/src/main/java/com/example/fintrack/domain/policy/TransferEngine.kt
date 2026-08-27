package com.example.fintrack.domain.policy

import com.example.fintrack.domain.dedupe.Candidate

/** Mirrors [com.example.fintrack.domain.dedupe.ScoreResult] for the transfer engine. */
data class ScoreResult(
    val score: Double,
    val verdict: TransferEngine.Verdict,
    val signals: Map<String, Double>,
)

/**
 * P11 #1 transfer-candidate scoring engine.
 *
 * Pure, side-effect-free scoring of (DEBIT, CREDIT) candidate pairs as
 * potential two-sided transfers. Modeled on the dedup engine so the
 * threshold vocabulary and per-signal breakdown are familiar.
 *
 * The two main consumers are:
 *  - [com.example.fintrack.domain.service.TransferCandidateMatcher]:
 *    scans recent posted events for likely-debit/credit pairs and emits
 *    [TransferEngine.Verdict.AUTO_LINK] / [TransferEngine.Verdict.REVIEW].
 *  - The detail screen: explains *why* a transfer was proposed.
 */
object TransferEngine {

    /** Tunables. Kept as a single object so reviewers can audit thresholds together. */
    object Thresholds {
        /** Top score at or above this is AUTO_LINK (paired automatically). */
        const val AUTO_LINK: Double = 0.90
        /** Below AUTO_LINK but at or above this routes to REVIEW. */
        const val REVIEW: Double = 0.60
    }

    /** Time window in minutes inside which a DEBIT and CREDIT are considered
     *  contemporaneous. Outside this window the score is 0. */
    const val WINDOW_MINUTES: Long = 10L

    private val W_AMOUNT = 0.30
    private val W_REF = 0.25
    private val W_RAIL = 0.10
    private val W_TIME = 0.15
    private val W_ACCOUNT_DISTINCT = 0.10
    private val W_CURRENCY = 0.10
    // weight total = 1.00

    private fun amountSignal(a: Candidate, b: Candidate): Double {
        if (a.amountMinor == null || b.amountMinor == null) return 0.0
        if (a.currencyCode != b.currencyCode) return 0.0
        if (a.amountMinor != b.amountMinor) return 0.0
        return 1.0
    }

    private fun refSignal(a: Candidate, b: Candidate): Double {
        if (a.refId == null || b.refId == null) return 0.0
        if (a.refId.isBlank() || b.refId.isBlank()) return 0.0
        return if (a.refId == b.refId) 1.0 else 0.0
    }

    private fun railSignal(a: Candidate, b: Candidate): Double =
        if (a.rail != null && b.rail != null && a.rail == b.rail) 1.0 else 0.0

    private fun currencySignal(a: Candidate, b: Candidate): Double =
        if (a.currencyCode != null && b.currencyCode != null && a.currencyCode == b.currencyCode) 1.0 else 0.0

    private fun timeSignal(a: Candidate, b: Candidate): Double {
        if (a.occurredAtEpochMs == null || b.occurredAtEpochMs == null) return 0.0
        val deltaMin = kotlin.math.abs(a.occurredAtEpochMs - b.occurredAtEpochMs) / 60_000.0
        if (deltaMin >= WINDOW_MINUTES.toDouble()) return 0.0
        return ((WINDOW_MINUTES.toDouble() - deltaMin) / WINDOW_MINUTES.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun accountDistinctSignal(a: Candidate, b: Candidate): Double =
        if (a.accountId != null && b.accountId != null && a.accountId != b.accountId) 1.0 else 0.0

    /** Direction match: a transfer requires DEBIT vs CREDIT. */
    fun directionMatches(a: Candidate, b: Candidate): Boolean {
        if (a.direction == null || b.direction == null) return false
        val (x, y) = if (a.occurredAtEpochMs ?: 0L <= b.occurredAtEpochMs ?: 0L) a to b else b to a
        return x.direction == "DEBIT" && y.direction == "CREDIT"
    }

    /**
     * Score a pair of candidates. Returns null if direction does not match
     * (i.e. the pair cannot be a transfer at all) or if either side is
     * missing a critical signal.
     */
    fun scorePair(a: Candidate, b: Candidate): ScoreResult? {
        if (!directionMatches(a, b)) return null
        if (a.accountId == null || b.accountId == null) return null
        if (a.accountId == b.accountId) return null // same-account "transfer" is not a transfer
        if (a.amountMinor == null || b.amountMinor == null) return null
        if (a.currencyCode == null || b.currencyCode == null || a.currencyCode != b.currencyCode) return null

        val signals = linkedMapOf(
            "amount" to amountSignal(a, b),
            "ref" to refSignal(a, b),
            "rail" to railSignal(a, b),
            "currency" to currencySignal(a, b),
            "time" to timeSignal(a, b),
            "accountDistinct" to accountDistinctSignal(a, b),
        )
        val score = (signals["amount"]!! * W_AMOUNT +
            signals["ref"]!! * W_REF +
            signals["rail"]!! * W_RAIL +
            signals["currency"]!! * W_CURRENCY +
            signals["time"]!! * W_TIME +
            signals["accountDistinct"]!! * W_ACCOUNT_DISTINCT)
            .coerceIn(0.0, 1.0)
        val verdict = when {
            score >= Thresholds.AUTO_LINK -> Verdict.AUTO_LINK
            score >= Thresholds.REVIEW -> Verdict.REVIEW
            else -> Verdict.REJECT
        }
        return ScoreResult(score = score, verdict = verdict, signals = signals)
    }

    enum class Verdict { AUTO_LINK, REVIEW, REJECT }
}
