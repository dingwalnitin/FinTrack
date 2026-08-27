package com.example.fintrack.domain

import com.example.fintrack.domain.ai.AiQueryEngine
import com.example.fintrack.domain.ai.AiQueryParser
import com.example.fintrack.domain.ai.AiQueryPlan
import com.example.fintrack.domain.ai.NaturalDateParser
import com.example.fintrack.domain.service.LedgerTxnView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Stage 10 / P21 — acceptance gate: queries such as "spending by
 * category/date/account" convert to a validated plan and produce a
 * DETERMINISTIC result set before any narrative generation.
 */
class AiQueryPlanTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 8, 26)
    private val parser = AiQueryParser()
    private val engine = AiQueryEngine()

    private fun txn(
        id: String,
        day: Long,
        amount: Long,
        kind: String = "EXPENSE",
        debit: Boolean = true,
        category: String? = "cat-food",
        merchant: String? = "Swiggy",
        account: String = "acc1",
        rail: String? = "UPI",
        deleted: Boolean = false,
    ) = LedgerTxnView(
        id = id, accountId = account, categoryId = category, kind = kind,
        directionDebit = debit, amountMinor = amount, localDateEpochDay = day,
        counterpartyNormalized = merchant?.lowercase(), merchant = merchant,
        currencyCode = "INR", occurredAtEpochMs = day * 86_400_000L, subtype = null,
        statusDeleted = deleted, rail = rail,
    )

    // ---- natural-language date parsing (module 172) ----

    @Test
    fun `last month resolves to explicit previous calendar month`() {
        val r = NaturalDateParser.parse("last month", today, zone)
        assertNotNull(r)
        assertEquals(LocalDate.of(2026, 7, 1).toEpochDay(), r!!.fromDay)
        assertEquals(LocalDate.of(2026, 7, 31).toEpochDay(), r.toDay)
    }

    @Test
    fun `this quarter resolves to explicit quarter bounds`() {
        val r = NaturalDateParser.parse("this quarter", today, zone)
        assertNotNull(r)
        // Aug is in Q3: Jul 1 .. Sep 30, capped at today.
        assertEquals(LocalDate.of(2026, 7, 1).toEpochDay(), r!!.fromDay)
        assertEquals(today.toEpochDay(), r.toDay)
    }

    @Test
    fun `past 3 months resolves to rolling window`() {
        val r = NaturalDateParser.parse("past 3 months", today, zone)
        assertNotNull(r)
        assertEquals(LocalDate.of(2026, 6, 1).toEpochDay(), r!!.fromDay)
        assertEquals(today.toEpochDay(), r.toDay)
    }

    @Test
    fun `unknown phrase refuses rather than guessing`() {
        assertNull(NaturalDateParser.parse("sometime last monsoon", today, zone))
    }

    // ---- NL → plan ----

    @Test
    fun `spending by category last month parses to aggregate plan`() {
        val outcome = parser.parse("how much did I spend by category last month", today, zone)
        assertTrue(outcome is AiQueryParser.ParseOutcome.Parsed)
        val plan = (outcome as AiQueryParser.ParseOutcome.Parsed).plan
        assertEquals(AiQueryPlan.Intent.AGGREGATE, plan.intent)
        assertTrue(AiQueryPlan.Metric.TOTAL_SPEND in plan.metrics)
        assertTrue(AiQueryPlan.Dimension.CATEGORY in plan.groupBy)
        assertEquals(LocalDate.of(2026, 7, 1).toEpochDay(), plan.filters.fromDay)
        assertEquals(LocalDate.of(2026, 7, 31).toEpochDay(), plan.filters.toDay)
    }

    @Test
    fun `same input produces identical plan identity - deterministic`() {
        val a = parser.parse("show transactions over Rs.500 last month", today, zone)
        val b = parser.parse("show transactions over Rs.500 last month", today, zone)
        assertTrue(a is AiQueryParser.ParseOutcome.Parsed && b is AiQueryParser.ParseOutcome.Parsed)
        assertEquals(
            (a as AiQueryParser.ParseOutcome.Parsed).plan.planIdentity,
            (b as AiQueryParser.ParseOutcome.Parsed).plan.planIdentity,
        )
    }

    @Test
    fun `unrecognized query returns Unparsed not a guessed plan`() {
        val outcome = parser.parse("tell me a joke about money", today, zone)
        assertTrue(outcome is AiQueryParser.ParseOutcome.Unparsed)
    }

    @Test
    fun `merchant filter extracts payee from from-at-to phrasing`() {
        val outcome = parser.parse("list transactions from Swiggy last month", today, zone)
        assertTrue(outcome is AiQueryParser.ParseOutcome.Parsed)
        val plan = (outcome as AiQueryParser.ParseOutcome.Parsed).plan
        assertEquals("swiggy", plan.filters.merchantNormalized?.lowercase())
    }

    @Test
    fun `amount filters parse over and under thresholds`() {
        val outcome = parser.parse("show purchases over Rs.1000 under Rs.5000 this month", today, zone)
        assertTrue(outcome is AiQueryParser.ParseOutcome.Parsed)
        val plan = (outcome as AiQueryParser.ParseOutcome.Parsed).plan
        assertEquals(100_001L, plan.filters.minAmountMinor)
        assertEquals(499_999L, plan.filters.maxAmountMinor)
    }

    @Test
    fun `top N sets bounded limit`() {
        val outcome = parser.parse("show top 5 transactions this month", today, zone)
        assertTrue(outcome is AiQueryParser.ParseOutcome.Parsed)
        assertEquals(5, (outcome as AiQueryParser.ParseOutcome.Parsed).plan.limit)
    }

    // ---- LLM-proposed plan decoding (module 170) ----

    @Test
    fun `valid plan json decodes to validated plan`() {
        val json = """{"intent":"AGGREGATE","metrics":["TOTAL_SPEND"],
            "groupBy":["CATEGORY"],"limit":20}"""
        val outcome = parser.decodePlanJson(json, today, zone)
        assertTrue(outcome is AiQueryParser.ParseOutcome.Parsed)
    }

    @Test
    fun `unknown metric in plan json is rejected`() {
        val json = """{"intent":"AGGREGATE","metrics":["TOTAL_BITCOIN"],"limit":20}"""
        val outcome = parser.decodePlanJson(json, today, zone)
        assertTrue(outcome is AiQueryParser.ParseOutcome.Unparsed)
    }

    @Test
    fun `unsupported field in plan json is rejected`() {
        val json = """{"intent":"AGGREGATE","sql":"SELECT * FROM transactions"}"""
        val outcome = parser.decodePlanJson(json, today, zone)
        assertTrue(outcome is AiQueryParser.ParseOutcome.Unparsed)
    }

    @Test
    fun `limit outside bounds is rejected`() {
        val json = """{"intent":"LIST_TRANSACTIONS","limit":100000}"""
        val outcome = parser.decodePlanJson(json, today, zone)
        assertTrue(outcome is AiQueryParser.ParseOutcome.Unparsed)
    }

    // ---- deterministic execution ----

    @Test
    fun `aggregate by category groups spend with refunds netted`() {
        val txns = listOf(
            txn("a", day = 10, amount = 500),
            txn("b", day = 11, amount = 300, category = "cat-travel"),
            txn("c", day = 12, amount = 200, kind = "REFUND", debit = false, category = "cat-food"),
            txn("d", day = 13, amount = 900, kind = "TRANSFER"), // internal — never spend
        )
        val plan = AiQueryPlan(
            intent = AiQueryPlan.Intent.AGGREGATE,
            metrics = setOf(AiQueryPlan.Metric.SPEND_BY_CATEGORY),
            groupBy = listOf(AiQueryPlan.Dimension.CATEGORY),
            filters = AiQueryPlan.Filters(fromDay = 1, toDay = 30),
            planIdentity = "test",
            parsedAtEpochMs = 0,
        )
        val result = engine.execute(plan, txns)
        val food = result.aggregates.first { it.key == "cat-food" }
        assertEquals(500L, food.grossMinor)
        assertEquals(200L, food.refundedMinor)
        assertEquals(300L, food.netMinor)
        // Transfer row must not appear in any category bucket.
        assertFalse(result.aggregates.any { it.key == null && it.grossMinor == 900L })
    }

    @Test
    fun `execution is deterministic for same snapshot`() {
        val txns = listOf(txn("a", 5, 100), txn("b", 6, 200), txn("c", 7, 150))
        val plan = AiQueryPlan(
            intent = AiQueryPlan.Intent.LIST_TRANSACTIONS,
            filters = AiQueryPlan.Filters(fromDay = 1, toDay = 30),
            planIdentity = "p",
            parsedAtEpochMs = 0,
        )
        val r1 = engine.execute(plan, txns)
        val r2 = engine.execute(plan, txns)
        assertEquals(r1.rows.map { it.transactionId }, r2.rows.map { it.transactionId })
        assertEquals(r1.aggregates, r2.aggregates)
    }

    @Test
    fun `pagination respects limit and reports hasMore`() {
        val txns = (1..30).map { txn(it.toString().padStart(3, '0'), it.toLong(), it * 100L) }
        val plan = AiQueryPlan(
            intent = AiQueryPlan.Intent.LIST_TRANSACTIONS,
            filters = AiQueryPlan.Filters(),
            limit = 10,
            planIdentity = "p",
            parsedAtEpochMs = 0,
        )
        val result = engine.execute(plan, txns)
        assertEquals(10, result.rows.size)
        assertTrue(result.hasMore)
        assertEquals(30, result.totalMatching)
    }

    @Test
    fun `deleted rows are excluded from results`() {
        val txns = listOf(txn("live", 5, 100), txn("dead", 6, 200, deleted = true))
        val plan = AiQueryPlan(
            intent = AiQueryPlan.Intent.LIST_TRANSACTIONS,
            filters = AiQueryPlan.Filters(),
            planIdentity = "p",
            parsedAtEpochMs = 0,
        )
        val result = engine.execute(plan, txns)
        assertEquals(listOf("live"), result.rows.map { it.transactionId })
    }

    @Test
    fun `sort by amount desc puts largest first with id tiebreak`() {
        val txns = listOf(
            txn("t2", 5, 500), txn("big", 6, 900), txn("t1", 7, 500),
        )
        val plan = AiQueryPlan(
            intent = AiQueryPlan.Intent.LIST_TRANSACTIONS,
            sort = AiQueryPlan.Sort(AiQueryPlan.Sort.SortField.AMOUNT, AiQueryPlan.Sort.SortDirection.DESC),
            filters = AiQueryPlan.Filters(),
            planIdentity = "p",
            parsedAtEpochMs = 0,
        )
        val result = engine.execute(plan, txns)
        assertEquals("big", result.rows.first().transactionId)
        val i1 = result.rows.indexOfFirst { it.transactionId == "t1" }
        val i2 = result.rows.indexOfFirst { it.transactionId == "t2" }
        assertTrue(i1 < i2) // ascending id tiebreak
    }
}
