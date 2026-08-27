package com.example.fintrack.domain.policy

import com.example.fintrack.domain.policy.SourceRank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class MoneyPolicyTest {

    @Test
    fun `INR and USD use 2 minor digits`() {
        assertEquals(2, MoneyPolicy.minorUnits("INR"))
        assertEquals(2, MoneyPolicy.minorUnits("USD"))
    }

    @Test
    fun `BigDecimal to minor units is exact`() {
        assertEquals(12345L, MoneyPolicy.toMinorUnits(BigDecimal("123.45"), "INR"))
        assertEquals(1L, MoneyPolicy.toMinorUnits(BigDecimal("0.01"), "USD"))
    }

    @Test
    fun `rounding uses HALF_EVEN`() {
        // 0.125 -> 0.12 (HALF_EVEN), 0.135 -> 0.14
        assertEquals(12L, MoneyPolicy.toMinorUnits(BigDecimal("0.125"), "USD"))
        assertEquals(14L, MoneyPolicy.toMinorUnits(BigDecimal("0.135"), "USD"))
    }

    @Test
    fun `split always sums to total`() {
        for (total in listOf(100L, 101L, 999L, 1L)) {
            for (parts in 1..7) {
                val split = MoneyPolicy.splitAmount(total, parts)
                assertEquals(total, split.sum())
            }
        }
    }

    @Test
    fun `float money is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MoneyPolicy.validateNoFloat(10.5)
        }
    }

    @Test
    fun `addition overflow is detected`() {
        assertThrows(ArithmeticException::class.java) {
            MoneyPolicy.add(Long.MAX_VALUE, 1)
        }
    }
}

class ProvenancePolicyTest {

    @Test
    fun `hierarchy ordering holds`() {
        assertTrue(SourceRank.USER_CONFIRMED.rank > SourceRank.RAW_EVIDENCE.rank)
        assertTrue(SourceRank.RAW_EVIDENCE.rank > SourceRank.IMPORT_FILE.rank)
        assertTrue(SourceRank.IMPORT_FILE.rank > SourceRank.HEURISTIC.rank)
        assertTrue(SourceRank.HEURISTIC.rank > SourceRank.MODEL_SUGGESTION.rank)
    }

    @Test
    fun `user correction blocks automated overwrite`() {
        assertFalse(
            ProvenancePolicy.mayAutomatedOverwrite(
                storedSourceKind = "SMS",
                hasUserCorrection = true,
                incomingSourceKind = "LLM_INTERPRETATION",
            )
        )
        // Even raw evidence cannot overwrite a user correction.
        assertFalse(
            ProvenancePolicy.mayAutomatedOverwrite(
                storedSourceKind = "HEURISTIC",
                hasUserCorrection = true,
                incomingSourceKind = "SMS",
            )
        )
    }

    @Test
    fun `higher-ranked evidence may overwrite when no correction exists`() {
        assertTrue(
            ProvenancePolicy.mayAutomatedOverwrite(
                storedSourceKind = "MODEL_SUGGESTION",
                hasUserCorrection = false,
                incomingSourceKind = "SMS",
            )
        )
        assertFalse(
            ProvenancePolicy.mayAutomatedOverwrite(
                storedSourceKind = "SMS",
                hasUserCorrection = false,
                incomingSourceKind = "HEURISTIC",
            )
        )
    }
}

class DateTimePolicyTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun `local date derivation respects zone`() {
        val instant = Instant.parse("2026-08-25T20:00:00Z")
        assertEquals(LocalDate.parse("2026-08-25").toEpochDay(), DateTimePolicy.localDateEpochDay(instant, ZoneId.of("UTC")))
        assertEquals(LocalDate.parse("2026-08-26").toEpochDay(), DateTimePolicy.localDateEpochDay(instant, kolkata))
    }

    @Test
    fun `month boundary across DST - March in New York`() {
        // 2026-03-15 in NY; DST began 2026-03-08.
        val instant = Instant.parse("2026-03-15T12:00:00Z")
        val (start, end) = DateTimePolicy.monthBounds(instant, newYork)
        assertEquals(Instant.parse("2026-03-01T05:00:00Z"), start) // EST offset -5
        assertEquals(Instant.parse("2026-04-01T03:59:59.999999999Z"), end) // EDT offset -4
    }

    @Test
    fun `imported historical date with no time defaults to noon`() {
        val instant = DateTimePolicy.fromImportedDateTime(
            LocalDate.parse("2019-06-10"), null, kolkata
        )
        assertEquals(Instant.parse("2019-06-10T06:30:00Z"), instant)
    }

    @Test
    fun `imported date on DST gap day resolves deterministically`() {
        // 2026-03-08 02:30 does not exist in New York (spring forward).
        val a = DateTimePolicy.fromImportedDateTime(LocalDate.parse("2026-03-08"), LocalTime.of(2, 30), newYork)
        val b = DateTimePolicy.fromImportedDateTime(LocalDate.parse("2026-03-08"), LocalTime.of(2, 30), newYork)
        assertEquals(a, b) // deterministic: same input -> same instant
    }

    @Test
    fun `epoch day round trip`() {
        val zone = kolkata
        val day = LocalDate.parse("2026-02-28").toEpochDay()
        val instant = DateTimePolicy.toInstant(day, zone)
        assertEquals(day, DateTimePolicy.localDateEpochDay(instant, zone))
    }
}
