package com.example.fintrack.domain.model

import java.time.Instant

/**
 * Stage 8 P17 — recurring payments and subscriptions.
 *
 * A recurring pattern is an INTERPRETATION over observed transactions, not a
 * fact. It carries confidence, observed amount range (module 148: variance
 * within an explainable range never breaks the recurrence) and durable user
 * decisions (CONFIRMED / REJECTED / CANCELLED) that survive re-detection.
 * Annual billing (module 149) participates in monthly-equivalent reporting
 * via [monthlyEquivalentMinor] without duplicating spend.
 */
enum class RecurringStatus { DETECTED, CONFIRMED, REJECTED, CANCELLED }

enum class Periodicity { MONTHLY, QUARTERLY, ANNUAL, CUSTOM }

data class RecurringPattern(
    val id: String,
    val patternIdentity: String,
    val accountId: String,
    val counterpartyNormalized: String?,
    val merchant: String?,
    val categoryId: String?,
    val periodicity: Periodicity,
    /** Median observed interval in days. */
    val intervalDays: Int,
    /** Canonical (median) amount; variance lives in observations. */
    val canonicalAmountMinor: Long,
    val minObservedAmountMinor: Long,
    val maxObservedAmountMinor: Long,
    val currencyCode: String,
    val confidence: Double,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    /** Next expected charge — always an estimate, labelled as such. */
    val nextExpectedEpochMs: Instant?,
    val status: RecurringStatus,
    /**
     * Subscription vs generic recurrence. null = evidence insufficient;
     * unknown stays unknown per the Bible.
     */
    val isSubscription: Boolean?,
    val decidedBy: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    init {
        require(confidence in 0.0..1.0)
        require(canonicalAmountMinor > 0)
        require(minObservedAmountMinor <= canonicalAmountMinor)
        require(maxObservedAmountMinor >= canonicalAmountMinor)
        require(intervalDays > 0)
    }

    /**
     * Module 149: monthly-equivalent value so annual/quarterly charges
     * participate correctly in monthly reporting without duplication.
     */
    fun monthlyEquivalentMinor(): Long = when (periodicity) {
        Periodicity.MONTHLY -> canonicalAmountMinor
        Periodicity.QUARTERLY -> canonicalAmountMinor / 3
        Periodicity.ANNUAL -> canonicalAmountMinor / 12
        Periodicity.CUSTOM -> (canonicalAmountMinor * 30) / intervalDays
    }

    /** Annualized view of the same canonical amount. */
    fun annualEquivalentMinor(): Long = monthlyEquivalentMinor() * 12
}

/** One observed occurrence backing a pattern. */
data class RecurringObservation(
    val id: String,
    val patternId: String,
    val transactionId: String,
    val amountMinor: Long,
    val occurredAtEpochMs: Long,
    val createdAtEpochMs: Long,
)

/**
 * Forecast for one upcoming period. Every value is an estimate derived from
 * CONFIRMED (or high-confidence DETECTED) patterns only.
 */
data class RecurringForecast(
    val periodStartEpochDay: Long,
    val periodEndEpochDay: Long,
    val currencyCode: String,
    /** Sum of monthly equivalents of active patterns expected in-period. */
    val expectedTotalMinor: Long,
    /** Patterns with nextExpected inside the window. */
    val upcoming: List<UpcomingCharge>,
    /** True when any contributing pattern is unconfirmed (estimate quality cue). */
    val includesUnconfirmed: Boolean,
) {
    data class UpcomingCharge(
        val patternId: String,
        val merchant: String?,
        val counterpartyNormalized: String?,
        val expectedEpochMs: Instant?,
        val expectedAmountMinor: Long,
        val confidence: Double,
        val confirmed: Boolean,
    )
}
