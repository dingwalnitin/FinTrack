package com.example.fintrack.domain.policy

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * Bible money policy: integer minor units only. Binary floating point is
 * prohibited for persisted money. Rounding is centralized here.
 *
 *  INR: minor unit = paise (2 digits). USD: minor unit = cents (2 digits).
 */
object MoneyPolicy {

    private val EXACT = RoundingMode.UNNECESSARY
    private val DISPLAY_ROUNDING = RoundingMode.HALF_EVEN // banker's rounding

    fun minorUnits(currencyCode: String): Int =
        Currency.getInstance(currencyCode).defaultFractionDigits.let { if (it < 0) 2 else it }

    /** BigDecimal -> minor units with explicit rounding policy. */
    fun toMinorUnits(value: BigDecimal, currencyCode: String): Long {
        val digits = minorUnits(currencyCode)
        return value.setScale(digits, DISPLAY_ROUNDING)
            .movePointRight(digits)
            .longValueExact()
    }

    /** Minor units -> BigDecimal for display only; persistence stays integer. */
    fun toMajor(minorUnits: Long, currencyCode: String): BigDecimal =
        BigDecimal.valueOf(minorUnits, minorUnits(currencyCode))

    /** Exact addition in minor units — no rounding possible. */
    fun add(a: Long, b: Long): Long =
        Math.addExact(a, b)

    /** Pro-rata split that always sums back to the original amount. */
    fun splitAmount(totalMinor: Long, parts: Int): List<Long> {
        require(parts > 0)
        val base = totalMinor / parts
        val remainder = totalMinor - base * parts
        return List(parts) { i -> base + if (i < remainder) 1 else 0 }
    }

    fun validateNoFloat(amount: Double): Nothing =
        throw IllegalArgumentException("Binary floating point is prohibited for persisted money")
}
