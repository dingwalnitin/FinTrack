package com.example.fintrack.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Stable opaque identifier. Never derived from user content. */
@JvmInline
value class EntityId(val value: String) {
    companion object {
        fun generate(): EntityId = EntityId(UUID.randomUUID().toString())
    }
}

/**
 * Currency-aware money. Amounts stored in minor units (long) to avoid float error.
 * Unknown currency is kept unknown — never defaulted silently.
 */
data class Money(val minorUnits: Long, val currencyCode: String) {
    init {
        require(currencyCode.length == 3) { "currencyCode must be ISO-4217, got '$currencyCode'" }
    }

    operator fun plus(other: Money): Money {
        require(currencyCode == other.currencyCode) { "Cannot add $currencyCode and ${other.currencyCode}" }
        return copy(minorUnits = minorUnits + other.minorUnits)
    }

    companion object {
        fun ofMajor(major: Double, currencyCode: String): Money =
            Money(Math.round(major * 100), currencyCode)
    }
}

/** Provenance: where an interpretation came from and with which source version. */
data class Provenance(
    val sourceKind: SourceKind,
    val sourceVersion: String,
    val capturedAt: Instant,
)

enum class SourceKind { SMS, MANUAL_ENTRY, IMPORT_FILE, LLM_INTERPRETATION, USER_CORRECTION }

/**
 * Lifecycle of a financial event interpretation.
 * Raw evidence (message) is immutable; interpretations are mutable through these states.
 */
enum class LifecycleState { RAW, PENDING_ENRICHMENT, INTERPRETED, REVIEW_REQUIRED, CONFIRMED, FAILED_ENRICHMENT }

/** Local date derived from Instant + zone; never persisted as wall-clock string alone. */
fun Instant.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    atZone(zone).toLocalDate()
