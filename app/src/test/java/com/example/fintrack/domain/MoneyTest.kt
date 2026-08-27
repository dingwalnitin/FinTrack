package com.example.fintrack.domain

import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.Money
import com.example.fintrack.domain.model.toLocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MoneyTest {
    @Test
    fun `adds same currency in minor units`() {
        assertEquals(Money(300, "USD"), Money.ofMajor(1.0, "USD") + Money.ofMajor(2.0, "USD"))
    }

    @Test
    fun `rejects mixed currency addition`() {
        assertThrows(IllegalArgumentException::class.java) {
            Money(100, "USD") + Money(100, "EUR")
        }
    }

    @Test
    fun `rejects invalid currency code`() {
        assertThrows(IllegalArgumentException::class.java) { Money(1, "US") }
    }
}

class EntityIdTest {
    @Test
    fun `generated ids are unique`() {
        val a = EntityId.generate()
        val b = EntityId.generate()
        org.junit.Assert.assertNotEquals(a, b)
    }
}

class LocalDateDerivationTest {
    @Test
    fun `derives local date from instant and zone`() {
        val instant = Instant.parse("2026-08-25T23:30:00Z")
        assertEquals(java.time.LocalDate.parse("2026-08-25"), instant.toLocalDate(ZoneId.of("UTC")))
        assertEquals(java.time.LocalDate.parse("2026-08-26"), instant.toLocalDate(ZoneId.of("Asia/Kolkata")))
    }
}
