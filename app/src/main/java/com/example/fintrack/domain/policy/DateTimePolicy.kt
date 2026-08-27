package com.example.fintrack.domain.policy

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Bible date/time policy:
 *  - Instants persisted as epoch millis (UTC, unambiguous)
 *  - local date derived ONLY via explicit ZoneId
 *  - DST/month boundaries handled by java.time zone rules, never manual offsets
 *  - imported historical dates parsed deterministically with a fixed fallback time
 */
object DateTimePolicy {

    /** Deterministic local-date derivation. */
    fun localDateEpochDay(instant: Instant, zone: ZoneId): Long =
        instant.atZone(zone).toLocalDate().toEpochDay()

    fun toInstant(epochDay: Long, zone: ZoneId): Instant =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant()

    /**
     * Imported historical dates: parse wall-clock date + optional time in the
     * given zone. Missing time defaults to noon — safe across DST shifts
     * (midnight can be nonexistent/ambiguous on DST transition days).
     */
    fun fromImportedDateTime(
        date: LocalDate,
        time: LocalTime? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Instant {
        val ldt = LocalDateTime.of(date, time ?: LocalTime.NOON)
        // ZoneRules resolves gaps/overlaps deterministically: gap -> shifted forward,
        // overlap -> earlier offset.
        return ldt.atZone(zone).toInstant()
    }

    /**
     * Month boundary helper: returns [startOfDayFirst, endOfDayLast] instants for the
     * month containing `instant` in `zone`. Uses zone rules so DST is correct.
     */
    fun monthBounds(instant: Instant, zone: ZoneId): Pair<Instant, Instant> {
        val date = instant.atZone(zone).toLocalDate()
        val first = date.withDayOfMonth(1).atStartOfDay(zone).toInstant()
        val last = date.withDayOfMonth(date.lengthOfMonth())
            .atTime(LocalTime.MAX).atZone(zone).toInstant()
        return first to last
    }

    fun utc(): ZoneId = ZoneOffset.UTC
}
