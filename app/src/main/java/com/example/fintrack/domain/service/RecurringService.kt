package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.Periodicity
import com.example.fintrack.domain.model.RecurringForecast
import com.example.fintrack.domain.model.RecurringObservation
import com.example.fintrack.domain.model.RecurringPattern
import com.example.fintrack.domain.model.RecurringStatus
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/**
 * Stage 8 P17 — recurring payment / subscription detection and forecasting.
 *
 * Pure domain logic. Detection groups observed expense transactions by
 * (account, counterparty) and infers periodicity from the median interval.
 * Module 148: amount variance within a tolerance never breaks a recurrence —
 * the canonical amount is the median and the observed min/max are preserved.
 * Module 149: annual charges normalize to monthly equivalents for reporting.
 *
 * User decisions (CONFIRMED / REJECTED / CANCELLED) are durable: re-running
 * detection updates metadata but never flips a user-set status back to
 * DETECTED.
 */
class RecurringService(
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /** Input observation derived from an existing transaction row. */
    data class ObservedTxn(
        val transactionId: String,
        val accountId: String,
        val counterpartyNormalized: String?,
        val merchant: String?,
        val categoryId: String?,
        val amountMinor: Long,
        val currencyCode: String,
        val occurredAtEpochMs: Long,
        /** True when the user has corrected this row (corrections outrank detection). */
        val userCorrected: Boolean = false,
    )

    data class DetectionResult(
        val pattern: RecurringPattern,
        val observations: List<RecurringObservation>,
    )

    /**
     * Detect one candidate pattern from a group of same-counterparty txns on
     * one account, sorted ascending by time. Returns null when the group is
     * too small or intervals are inconsistent with any periodicity.
     */
    fun detect(
        accountId: String,
        txns: List<ObservedTxn>,
        existingStatus: RecurringStatus? = null,
        existingDecidedBy: String? = null,
    ): DetectionResult? {
        if (txns.size < MIN_OBSERVATIONS) return null
        val sorted = txns.sortedBy { it.occurredAtEpochMs }
        if (sorted.map { it.currencyCode }.distinct().size != 1) return null
        val currency = sorted.first().currencyCode

        // Interval in days between consecutive observations.
        val gapsDays = sorted.zipWithNext { a, b ->
            ((b.occurredAtEpochMs - a.occurredAtEpochMs) / 86_400_000.0)
        }
        val medianGap = median(gapsDays) ?: return null

        // Reject irregular cadence up front: observed gaps must be mutually
        // consistent (within 30% of the mean, min slack 3 days) or there is
        // no credible recurrence.
        val gapMeanPre = gapsDays.average()
        val gapSpreadPre = gapsDays.max() - gapsDays.min()
        if (gapSpreadPre > maxOf(gapMeanPre * 0.3, 3.0)) return null

        val periodicity = classifyPeriodicity(medianGap) ?: return null

        // Module 148: amounts may vary; keep median + range. Drop the pattern
        // only when variance is unexplainable (> VARIANCE_TOLERANCE of median).
        val amounts = sorted.map { it.amountMinor }.sorted()
        val medianAmount = median(amounts.map { it.toDouble() })!!.toLong()
        val minA = amounts.first()
        val maxA = amounts.last()
        if (medianAmount > 0 && (maxA - minA).toDouble() / medianAmount > VARIANCE_TOLERANCE) {
            return null
        }

        // Confidence grows with observation count and interval regularity.
        val gapMean = gapsDays.average()
        val spread = if (gapsDays.size > 1) {
            kotlin.math.abs(gapsDays.max() - gapsDays.min()) / maxOf(gapMean, 1.0)
        } else 1.0
        val regularity = (1.0 - spread).coerceIn(0.0, 1.0)
        val countFactor = (sorted.size - MIN_OBSERVATIONS + 1).toDouble() / MIN_OBSERVATIONS
        val confidence = (0.4 * regularity + 0.6 * countFactor.coerceAtMost(1.0)).coerceIn(0.0, 1.0)

        val lastSeen = sorted.last().occurredAtEpochMs
        val nextExpected = lastSeen + Math.round(medianGap * 86_400_000.0)

        val counterparty = sorted.mapNotNull { it.counterpartyNormalized }.firstOrNull()
        val identity = sha256(listOf(accountId, counterparty ?: "-", periodicity.name).joinToString("|"))

        // Durable user decision: never regress CONFIRMED/REJECTED/CANCELLED.
        val status = when {
            existingStatus == RecurringStatus.CONFIRMED ||
                existingStatus == RecurringStatus.REJECTED ||
                existingStatus == RecurringStatus.CANCELLED -> existingStatus
            else -> RecurringStatus.DETECTED
        }
        val decidedBy = if (status != RecurringStatus.DETECTED && existingDecidedBy != null) {
            existingDecidedBy
        } else "SYSTEM"

        val nowMs = clock().toEpochMilli()
        val pattern = RecurringPattern(
            id = java.util.UUID.randomUUID().toString(),
            patternIdentity = identity,
            accountId = accountId,
            counterpartyNormalized = counterparty,
            merchant = sorted.lastOrNull { it.merchant != null }?.merchant,
            categoryId = sorted.mapNotNull { it.categoryId }.groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key,
            periodicity = periodicity,
            intervalDays = Math.round(medianGap).toInt().coerceAtLeast(1),
            canonicalAmountMinor = medianAmount,
            minObservedAmountMinor = minA,
            maxObservedAmountMinor = maxA,
            currencyCode = currency,
            confidence = confidence,
            firstSeenEpochMs = sorted.first().occurredAtEpochMs,
            lastSeenEpochMs = lastSeen,
            nextExpectedEpochMs = Instant.ofEpochMilli(nextExpected),
            status = status,
            // Subscription evidence: a stable merchant name is required;
            // without it we do not claim subscription (unknown stays unknown).
            isSubscription = sorted.all { !it.merchant.isNullOrBlank() },
            decidedBy = decidedBy,
            createdAtEpochMs = nowMs,
            updatedAtEpochMs = nowMs,
        )
        val observations = sorted.map { t ->
            RecurringObservation(
                id = java.util.UUID.randomUUID().toString(),
                patternId = pattern.id,
                transactionId = t.transactionId,
                amountMinor = t.amountMinor,
                occurredAtEpochMs = t.occurredAtEpochMs,
                createdAtEpochMs = nowMs,
            )
        }
        return DetectionResult(pattern, observations)
    }

    /**
     * Handle a skipped occurrence: if [now] is more than one full interval
     * past next-expected with no new observation, the forecast shifts rather
     * than piling up missed charges.
     */
    fun rollForwardOnSkip(pattern: RecurringPattern, now: Instant): Instant? {
        var next = pattern.nextExpectedEpochMs ?: return null
        val stepMs = pattern.intervalDays * 86_400_000L
        while (next.isBefore(now.minusSeconds(stepMs / 1000))) {
            next = Instant.ofEpochMilli(next.toEpochMilli() + stepMs)
        }
        return next
    }

    /**
     * Forecast upcoming charges in a window. Only CONFIRMED patterns and
     * DETECTED patterns above [minConfidence] contribute; unconfirmed
     * contributions are flagged so the UI can show estimate quality.
     */
    fun forecast(
        patterns: List<RecurringPattern>,
        windowStartEpochDay: Long,
        windowEndEpochDay: Long,
        minConfidence: Double = 0.5,
    ): RecurringForecast {
        val now = clock()
        val windowStartMs = windowStartEpochDay * 86_400_000L
        val windowEndMs = (windowEndEpochDay + 1) * 86_400_000L
        val contributing = patterns.filter {
            it.status == RecurringStatus.CONFIRMED ||
                (it.status == RecurringStatus.DETECTED && it.confidence >= minConfidence)
        }
        val upcoming = mutableListOf<RecurringForecast.UpcomingCharge>()
        var includesUnconfirmed = false
        contributing.forEach { p ->
            val next = rollForwardOnSkip(p, now) ?: p.nextExpectedEpochMs ?: return@forEach
            if (next.toEpochMilli() in windowStartMs until windowEndMs) {
                if (p.status != RecurringStatus.CONFIRMED) includesUnconfirmed = true
                upcoming += RecurringForecast.UpcomingCharge(
                    patternId = p.id,
                    merchant = p.merchant,
                    counterpartyNormalized = p.counterpartyNormalized,
                    expectedEpochMs = next,
                    expectedAmountMinor = p.canonicalAmountMinor,
                    confidence = p.confidence,
                    confirmed = p.status == RecurringStatus.CONFIRMED,
                )
            }
        }
        val currency = contributing.groupingBy { it.currencyCode }.eachCount()
            .maxByOrNull { it.value }?.key ?: "INR"
        return RecurringForecast(
            periodStartEpochDay = windowStartEpochDay,
            periodEndEpochDay = windowEndEpochDay,
            currencyCode = currency,
            expectedTotalMinor = upcoming.sumOf { it.expectedAmountMinor },
            upcoming = upcoming.sortedBy { it.expectedEpochMs },
            includesUnconfirmed = includesUnconfirmed,
        )
    }

    private fun classifyPeriodicity(medianGapDays: Double): Periodicity? = when {
        medianGapDays in 25.0..35.0 -> Periodicity.MONTHLY
        medianGapDays in 85.0..95.0 -> Periodicity.QUARTERLY
        medianGapDays in 350.0..380.0 -> Periodicity.ANNUAL
        else -> null
    }

    private fun <T : Comparable<T>> median(values: List<T>): T? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return s[s.size / 2]
    }

    companion object {
        const val MIN_OBSERVATIONS = 3
        const val VARIANCE_TOLERANCE = 0.5 // ±50% around the median keeps the recurrence alive

        fun sha256(raw: String): String =
            MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
