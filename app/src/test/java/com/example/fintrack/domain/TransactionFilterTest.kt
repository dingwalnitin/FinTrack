package com.example.fintrack.domain

import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.Money
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.model.Transaction
import com.example.fintrack.domain.service.TransactionFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Stage 13 (B) — pure JVM tests for [TransactionFilter].
 */
class TransactionFilterTest {

    private val filters = TransactionFilter.Filters()
    private val sort = TransactionFilter.SortSpec()

    private fun txn(
        id: String,
        debit: Boolean = true,
        amount: Long = 1000L,
        counterparty: String? = "Merchant",
        at: Instant = Instant.ofEpochMilli(1000),
    ) = Transaction(
        id = EntityId(id),
        messageId = EntityId("m1"),
        amount = Money(amount, "INR"),
        occurredAt = at,
        counterparty = counterparty,
        state = com.example.fintrack.domain.model.LifecycleState.INTERPRETED,
        provenance = Provenance(SourceKind.SMS, "v1", Instant.EPOCH),
        directionDebit = debit,
    )

    // ---- Kind filter ----

    @Test
    fun `kindFilter ALL shows everything`() {
        val all = listOf(txn("1", debit = true), txn("2", debit = false))
        val result = TransactionFilter.apply(all, TransactionFilter.Filters(kind = TransactionFilter.KindFilter.ALL), sort)
        assertEquals(2, result.size)
    }

    @Test
    fun `kindFilter EXPENSE hides income`() {
        val all = listOf(txn("e1", debit = true), txn("i1", debit = false))
        val result = TransactionFilter.apply(all, TransactionFilter.Filters(kind = TransactionFilter.KindFilter.EXPENSE), sort)
        assertEquals(1, result.size)
        assertEquals("e1", result[0].id.value)
    }

    @Test
    fun `kindFilter INCOME hides expense`() {
        val all = listOf(txn("e1", debit = true), txn("i1", debit = false))
        val result = TransactionFilter.apply(all, TransactionFilter.Filters(kind = TransactionFilter.KindFilter.INCOME), sort)
        assertEquals(1, result.size)
        assertEquals("i1", result[0].id.value)
    }

    // ---- Text search ----

    @Test
    fun `text search matches counterparty case-insensitive`() {
        val txns = listOf(txn("1", counterparty = "Swiggy"), txn("2", counterparty = "Zomato"))
        val result = TransactionFilter.apply(txns, TransactionFilter.Filters(query = "swiggy"), sort)
        assertEquals(1, result.size)
        assertEquals("1", result[0].id.value)
    }

    @Test
    fun `empty query returns all`() {
        val txns = listOf(txn("1"), txn("2"))
        val result = TransactionFilter.apply(txns, TransactionFilter.Filters(), sort)
        assertEquals(2, result.size)
    }

    // ---- Amount filter ----

    @Test
    fun `minAmount filters below threshold`() {
        val txns = listOf(txn("1", amount = 500L), txn("2", amount = 1500L))
        val result = TransactionFilter.apply(txns, TransactionFilter.Filters(minAmountMinor = 1000L), sort)
        assertEquals(1, result.size)
        assertEquals("2", result[0].id.value)
    }

    @Test
    fun `maxAmount filters above threshold`() {
        val txns = listOf(txn("1", amount = 500L), txn("2", amount = 1500L))
        val result = TransactionFilter.apply(txns, TransactionFilter.Filters(maxAmountMinor = 1000L), sort)
        assertEquals(1, result.size)
        assertEquals("1", result[0].id.value)
    }

    // ---- Sort ----

    @Test
    fun `sort by date desc is default`() {
        val txns = listOf(
            txn("old", at = Instant.ofEpochMilli(100)),
            txn("new", at = Instant.ofEpochMilli(200)),
        )
        val result = TransactionFilter.apply(txns, TransactionFilter.Filters(), TransactionFilter.SortSpec())
        assertEquals("new", result[0].id.value)
        assertEquals("old", result[1].id.value)
    }

    @Test
    fun `sort by amount asc`() {
        val txns = listOf(
            txn("big", amount = 2000L),
            txn("small", amount = 500L),
        )
        val result = TransactionFilter.apply(txns, TransactionFilter.Filters(),
            TransactionFilter.SortSpec(TransactionFilter.SortField.AMOUNT, TransactionFilter.SortOrder.ASC))
        assertEquals("small", result[0].id.value)
        assertEquals("big", result[1].id.value)
    }

    @Test
    fun `sort by merchant asc`() {
        val txns = listOf(
            txn("z", counterparty = "Zomato"),
            txn("a", counterparty = "Amazon"),
        )
        val result = TransactionFilter.apply(txns, TransactionFilter.Filters(),
            TransactionFilter.SortSpec(TransactionFilter.SortField.MERCHANT, TransactionFilter.SortOrder.ASC))
        assertEquals("a", result[0].id.value)
        assertEquals("z", result[1].id.value)
    }

    // ---- Combined filter ----

    @Test
    fun `combines text and kind filter`() {
        val txns = listOf(
            txn("1", debit = true, counterparty = "Swiggy"),
            txn("2", debit = false, counterparty = "Swiggy"),  // income from Swiggy (refund)
            txn("3", debit = true, counterparty = "Amazon"),
        )
        val result = TransactionFilter.apply(txns,
            TransactionFilter.Filters(query = "swiggy", kind = TransactionFilter.KindFilter.EXPENSE), sort)
        assertEquals(1, result.size)
        assertEquals("1", result[0].id.value)
    }
}