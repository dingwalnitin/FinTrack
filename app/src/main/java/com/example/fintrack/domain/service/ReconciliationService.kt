package com.example.fintrack.domain.service

import java.security.MessageDigest

/**
 * Stage 9 P20 #5 — ledger reconciliation workbench.
 *
 * READ-ONLY by default: compares observed snapshots against the ledger-derived
 * balance and explains the difference. It NEVER mutates raw evidence and never
 * auto-adjusts; booking a difference remains an explicit user action through
 * the existing CashService / ReconcileViewModel paths.
 */
class ReconciliationService {

    data class AccountReconciliation(
        val accountId: String,
        val accountLabel: String?,
        val currencyCode: String,
        /** Latest observed snapshot value, or null when none exists. */
        val observedMinor: Long?,
        val observedAtEpochMs: Long?,
        val derivedMinor: Long,
        val differenceMinor: Long,
        val reconciled: Boolean,
        /**
         * True when postings exist AFTER the snapshot time — the comparison
         * is then stale and must be labelled as such rather than as an error.
         */
        val snapshotStale: Boolean,
        /** Postings newer than the latest snapshot (evidence for staleness). */
        val postingsAfterSnapshot: Int,
    )

    sealed interface Verdict {
        /** Observed == derived at snapshot time. */
        data object Matched : Verdict
        /** Difference fully explained by postings after the snapshot. */
        data class ExplainedByLaterPostings(val laterNetMinor: Long) : Verdict
        /** Real unexplained difference — user must investigate/book. */
        data class Unexplained(val differenceMinor: Long) : Verdict
        /** No snapshot to compare against. */
        data object NoObservation : Verdict
    }

    fun reconcile(
        accountId: String,
        accountLabel: String?,
        currencyCode: String,
        openingBalanceMinor: Long?,
        postings: List<LedgerTxnView>,
        latestSnapshot: Pair<Long, Long>?, // (capturedAtEpochMs, amountMinor)
    ): AccountReconciliation {
        var running = openingBalanceMinor ?: 0L
        var derivedAtSnapshot = running
        var latestAt = Long.MIN_VALUE
        var laterCount = 0
        var laterNet = 0L
        postings.filter { !it.statusDeleted }.sortedBy { it.occurredAtEpochMs }.forEach { t ->
            running = if (t.directionDebit) running - t.amountMinor else running + t.amountMinor
            if (latestSnapshot != null && t.occurredAtEpochMs <= latestSnapshot.first) {
                derivedAtSnapshot = running
                latestAt = t.occurredAtEpochMs
            } else if (latestSnapshot != null) {
                laterCount++
                laterNet += if (t.directionDebit) -t.amountMinor else t.amountMinor
            }
        }
        val derivedNow = running
        return AccountReconciliation(
            accountId = accountId,
            accountLabel = accountLabel,
            currencyCode = currencyCode,
            observedMinor = latestSnapshot?.second,
            observedAtEpochMs = latestSnapshot?.first,
            derivedMinor = derivedNow,
            differenceMinor = (latestSnapshot?.second ?: 0L) - derivedAtSnapshot,
            reconciled = latestSnapshot != null && latestSnapshot.second == derivedAtSnapshot,
            snapshotStale = latestSnapshot != null && (laterCount > 0 || derivedNow != derivedAtSnapshot),
            postingsAfterSnapshot = laterCount,
        )
    }

    fun verdict(rec: AccountReconciliation): Verdict = when {
        rec.observedMinor == null -> Verdict.NoObservation
        rec.differenceMinor == 0L && !rec.snapshotStale -> Verdict.Matched
        rec.differenceMinor == 0L && rec.snapshotStale -> Verdict.ExplainedByLaterPostings(0L)
        else -> Verdict.Unexplained(rec.differenceMinor)
    }

    /**
     * True when the observed-vs-derived difference at snapshot time is exactly
     * offset by the net of postings recorded after that snapshot — i.e. the
     * "mismatch" is just timing, not missing money.
     */
    fun explainedByTiming(
        differenceAtSnapshotMinor: Long,
        netPostingsAfterSnapshotMinor: Long,
    ): Boolean = differenceAtSnapshotMinor + netPostingsAfterSnapshotMinor == 0L
}

/**
 * Stage 9 P20 #6 — unresolved-data report.
 *
 * Aggregates counts of every kind of unresolved fact so nothing silently
 * disappears. Read-only over sink queries; no interpretation is fabricated.
 */
class UnresolvedDataReportService {

    data class Report(
        val transactionsWithoutAccountMapping: Int,
        val unknownEconomicMeaning: Int,       // kind == UNKNOWN
        val lowConfidenceFields: Int,          // LLM interpretations below threshold
        val parserFailures: Int,               // financial-classified evidence with no candidate
        val llmFailures: Int,                  // terminal-failed LLM jobs
        val staleProcessingJobs: Int,          // processing_jobs stuck PENDING/RUNNING past due
        val openReviewItems: Int,
        val uncategorizedTransactions: Int,
    ) {
        val totalUnresolved: Int
            get() = transactionsWithoutAccountMapping + unknownEconomicMeaning +
                lowConfidenceFields + parserFailures + llmFailures +
                staleProcessingJobs + openReviewItems + uncategorizedTransactions
    }
}

/**
 * Stage 9 P20 #7 — sensitive-data redaction engine.
 *
 * Applied whenever evidence text is exported or handed to an LLM. Deterministic:
 * the same input always yields the same redaction so tests and audits agree.
 * Raw evidence in Room is untouched — this operates on copies only.
 */
object RedactionEngine {

    const val VERSION = "redact-v1"

    private val AMOUNT_REGEX = Regex("""(?i)\b(?:rs\.?|inr|₹)\s?[0-9][0-9,]*(?:\.[0-9]{1,2})?""")
    private val ACCOUNT_REGEX = Regex("""\b(?:A/c|a\/c|account|acct|card|XX|x+)\s*[xX*]*([0-9]{2,8})\b""")
    private val VPA_REGEX = Regex("""\b[a-zA-Z0-9._-]+@[a-zA-Z]{2,}\b""")
    private val PHONE_REGEX = Regex("""\b(?:\+91[- ]?)?[6-9][0-9]{9}\b""")
    private val OTP_REGEX = Regex("""(?i)\bOTP\b[^0-9]{0,12}[0-9]{4,8}""")

    data class Result(val redactedText: String, val redactionCount: Int)

    fun redact(text: String): Result {
        var count = 0
        var out = text
        fun replaceAll(regex: Regex, token: String) {
            out = regex.replace(out) { m -> count++; token }
        }
        replaceAll(OTP_REGEX, "[OTP]")
        replaceAll(VPA_REGEX, "[VPA]")
        replaceAll(PHONE_REGEX, "[PHONE]")
        replaceAll(ACCOUNT_REGEX, "[ACCT]")
        replaceAll(AMOUNT_REGEX, "[AMOUNT]")
        return Result(out, count)
    }

    /** Stable hash of raw content for identity columns without storing the content. */
    fun sha256(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
