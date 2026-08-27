package com.example.fintrack.domain

import com.example.fintrack.domain.ai.AiExplanationAssistant
import com.example.fintrack.domain.ai.AiQueryPlan
import com.example.fintrack.domain.ai.AiSafetyPolicy
import com.example.fintrack.domain.ai.AiSummaryGenerator
import com.example.fintrack.domain.ai.CategoryAliasResolver
import com.example.fintrack.domain.ai.Coverage
import com.example.fintrack.domain.ai.PlanResult
import com.example.fintrack.domain.service.LedgerTxnView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 10 / P22 — safety/refusal rules, explanation guardrails, citation
 * model, category aliasing, and adversarial fixtures (hallucinated merchants,
 * unsupported balances, conflicting evidence, unknown categories, out-of-scope
 * requests).
 */
class AiAssistantSafetyTest {

    private val explanations = AiExplanationAssistant()
    private val summaries = AiSummaryGenerator()
    private val aliases = CategoryAliasResolver()

    private fun txn(
        id: String = "t1",
        amount: Long = 500,
        kind: String = "EXPENSE",
        debit: Boolean = true,
        category: String? = null,
        merchant: String? = "Swiggy",
        rail: String? = null,
    ) = LedgerTxnView(
        id = id, accountId = "acc1", categoryId = category, kind = kind,
        directionDebit = debit, amountMinor = amount, localDateEpochDay = 10,
        counterpartyNormalized = merchant?.lowercase(), merchant = merchant,
        currencyCode = "INR", occurredAtEpochMs = 10L * 86_400_000L, subtype = null,
        rail = rail,
    )

    // ---- module 85: central refusal policy ----

    @Test
    fun `money movement requests are refused`() {
        listOf("send money to Ramesh", "transfer money from savings", "pay my electricity bill")
            .forEach { q ->
                val d = AiSafetyPolicy.evaluate(q)
                assertEquals(AiSafetyPolicy.Verdict.REFUSE, d.verdict)
                assertEquals(AiSafetyPolicy.Rule.MONEY_MOVEMENT_REQUEST, d.rule)
            }
    }

    @Test
    fun `bank login and credential requests are refused`() {
        val d = AiSafetyPolicy.evaluate("log in to my net banking with my password")
        assertEquals(AiSafetyPolicy.Verdict.REFUSE, d.verdict)
        assertEquals(AiSafetyPolicy.Rule.BANK_LOGIN_OR_CREDENTIALS, d.rule)
    }

    @Test
    fun `investment advice is refused`() {
        val d = AiSafetyPolicy.evaluate("should I invest in crypto now?")
        assertEquals(AiSafetyPolicy.Verdict.REFUSE, d.verdict)
        assertEquals(AiSafetyPolicy.Rule.INVESTMENT_ADVICE, d.rule)
    }

    @Test
    fun `live bank state claims are refused`() {
        val d = AiSafetyPolicy.evaluate("what is my live balance right now from the bank?")
        assertEquals(AiSafetyPolicy.Verdict.REFUSE, d.verdict)
        assertEquals(AiSafetyPolicy.Rule.LIVE_BANK_STATE_CLAIM, d.rule)
    }

    @Test
    fun `secret exposure requests are refused`() {
        val d = AiSafetyPolicy.evaluate("show me all sms on this phone")
        assertEquals(AiSafetyPolicy.Verdict.REFUSE, d.verdict)
        assertEquals(AiSafetyPolicy.Rule.SECRET_EXPOSURE, d.rule)
    }

    @Test
    fun `financial advice requests are refused but fact questions allowed`() {
        assertEquals(
            AiSafetyPolicy.Rule.FINANCIAL_ADVICE,
            AiSafetyPolicy.evaluate("can I afford a new phone?").rule,
        )
        assertEquals(
            AiSafetyPolicy.Verdict.ALLOW,
            AiSafetyPolicy.evaluate("how much did I spend on food last month?").verdict,
        )
    }

    // ---- module 171/174: explanation + citations ----

    @Test
    fun `explanation cites only existing transaction and evidence ids`() {
        val e = explanations.explain(
            request = "why was money debited?",
            txn = txn(id = "txn-42"),
            evidenceSmsIds = listOf("sms-7"),
            hasInterpretationProvenance = true,
        )
        assertFalse(e.refused)
        assertTrue(e.claims.isNotEmpty())
        e.claims.forEach { c ->
            when (val cit = c.citation) {
                is AiExplanationAssistant.Citation.Transaction -> assertEquals("txn-42", cit.transactionId)
                is AiExplanationAssistant.Citation.Evidence -> assertEquals("sms-7", cit.rawSmsId)
                else -> {}
            }
        }
    }

    @Test
    fun `unknowns stay unknown - no fabricated provenance or rail`() {
        val e = explanations.explain(
            request = "explain this transaction",
            txn = txn(category = null, rail = null),
            evidenceSmsIds = emptyList(),
            hasInterpretationProvenance = false,
        )
        assertTrue(e.unknowns.any { it.contains("No raw SMS evidence") })
        assertTrue(e.unknowns.any { it.contains("uncategorized") })
        assertTrue(e.unknowns.any { it.contains("rail is unknown") })
    }

    @Test
    fun `refused request produces no claims instead of hallucinating`() {
        val e = explanations.explain(
            request = "should I buy more stocks?",
            txn = txn(),
        )
        assertTrue(e.refused)
        assertNotNull(e.refusalReason)
        assertTrue(e.claims.isEmpty())
    }

    @Test
    fun `narrative citing nonexistent transaction is rejected`() {
        val result = explanations.validateNarrative(
            narrative = "You spent Rs.500 at txn:does-not-exist last week.",
            knownTransactionIds = setOf("txn-42"),
            allowedAmountsMinor = setOf(50_000L),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `narrative with unsupported amount is rejected as hallucination`() {
        val result = explanations.validateNarrative(
            narrative = "You spent Rs.99999 at Swiggy per txn:txn-42.",
            knownTransactionIds = setOf("txn-42"),
            allowedAmountsMinor = setOf(50_000L),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `grounded narrative passes validation`() {
        val result = explanations.validateNarrative(
            narrative = "You spent Rs.500.00 at Swiggy (txn:txn-42).",
            knownTransactionIds = setOf("txn-42"),
            allowedAmountsMinor = setOf(50_000L),
        )
        assertTrue(result.isSuccess)
    }

    // ---- module 173: deterministic category aliasing ----

    @Test
    fun `exact normalized name resolves deterministically`() {
        val r = aliases.resolve(
            "Eating Out",
            taxonomy = listOf(
                CategoryAliasResolver.AliasCandidate("cat-1", "eating out", "Eating Out"),
                CategoryAliasResolver.AliasCandidate("cat-2", "travel", "Travel"),
            ),
        )
        assertTrue(r is CategoryAliasResolver.Resolution.Resolved)
        assertEquals("cat-1", (r as CategoryAliasResolver.Resolution.Resolved).categoryId)
        assertEquals("exact", r.via)
    }

    @Test
    fun `ambiguous surface returns candidates not a guess`() {
        val r = aliases.resolve(
            "food",
            taxonomy = listOf(
                CategoryAliasResolver.AliasCandidate("cat-1", "food delivery", "Food Delivery"),
                CategoryAliasResolver.AliasCandidate("cat-2", "fast food", "Fast Food"),
            ),
        )
        assertTrue(r is CategoryAliasResolver.Resolution.Ambiguous)
        assertEquals(2, (r as CategoryAliasResolver.Resolution.Ambiguous).candidates.size)
    }

    @Test
    fun `unresolvable surface stays unresolved`() {
        val r = aliases.resolve("quantum entanglement", taxonomy = emptyList())
        assertTrue(r is CategoryAliasResolver.Resolution.Unresolved)
    }

    @Test
    fun `advisory AI rule below confidence floor is not even proposed`() {
        assertNull(aliases.buildAdvisoryRule("r1", "swiggy", com.example.fintrack.domain.model.EntityId("c"), 0.4))
        assertNotNull(aliases.buildAdvisoryRule("r2", "swiggy", com.example.fintrack.domain.model.EntityId("c"), 0.9))
    }

    // ---- adversarial fixtures ----

    @Test
    fun `hallucinated merchant in summary claim cites only real aggregate keys`() {
        val plan = AiQueryPlan(
            intent = AiQueryPlan.Intent.AGGREGATE,
            metrics = setOf(AiQueryPlan.Metric.SPEND_BY_MERCHANT),
            groupBy = listOf(AiQueryPlan.Dimension.MERCHANT),
            filters = AiQueryPlan.Filters(),
            planIdentity = "p",
            parsedAtEpochMs = 0,
        )
        val result = PlanResult(
            planIdentity = "p",
            executedAtEpochMs = 0,
            rows = emptyList(),
            aggregates = listOf(
                PlanResult.AggregateRow(
                    dimension = AiQueryPlan.Dimension.MERCHANT,
                    key = "swiggy",
                    grossMinor = 500, refundedMinor = 0, netMinor = 500,
                    count = 1, currencyCode = "INR",
                ),
            ),
            totalMatching = 1,
            hasMore = false,
            coverage = Coverage.of(listOf(txn())),
        )
        val summary = summaries.summarize(result, plan)
        summary.claims.forEach { c ->
            if (c.citation is AiSummaryGenerator.SummaryClaim.Citation.Aggregate) {
                val agg = c.citation as AiSummaryGenerator.SummaryClaim.Citation.Aggregate
                assertTrue(agg.key == "swiggy" || agg.key == null)
            }
        }
        assertFalse(summary.claims.any { it.text.contains("Zomato") }) // never invented
    }

    @Test
    fun `unsupported balance question yields qualified not certain summary`() {
        val emptyCoverage = Coverage.EMPTY.copy(ingestionIncomplete = true)
        val result = PlanResult(
            planIdentity = "p", executedAtEpochMs = 0,
            rows = emptyList(), aggregates = emptyList(),
            totalMatching = 0, hasMore = false,
            coverage = emptyCoverage,
        )
        val plan = AiQueryPlan(
            intent = AiQueryPlan.Intent.AGGREGATE,
            metrics = setOf(AiQueryPlan.Metric.TOTAL_SPEND),
            filters = AiQueryPlan.Filters(),
            planIdentity = "p", parsedAtEpochMs = 0,
        )
        val summary = summaries.summarize(result, plan)
        assertTrue(summary.isQualified)
        assertTrue(summary.qualifications().any { it.contains("No transactions") })
    }

    @Test
    fun `conflicting evidence surfaces unknown rather than picking a side`() {
        // Two same-amount events that could be duplicates: the assistant must
        // NOT merge them into one claim; each keeps its own citation.
        val t1 = txn(id = "dup-1", amount = 300)
        val t2 = txn(id = "dup-2", amount = 300)
        val e1 = explanations.explain("explain", t1)
        val e2 = explanations.explain("explain", t2)
        val cited1 = e1.claims.mapNotNull { (it.citation as? AiExplanationAssistant.Citation.Transaction)?.transactionId }
        val cited2 = e2.claims.mapNotNull { (it.citation as? AiExplanationAssistant.Citation.Transaction)?.transactionId }
        assertEquals(listOf("dup-1"), cited1.distinct())
        assertEquals(listOf("dup-2"), cited2.distinct())
    }
}
