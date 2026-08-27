package com.example.fintrack.domain

import com.example.fintrack.domain.model.Category
import com.example.fintrack.domain.model.CategoryKind
import com.example.fintrack.domain.model.CategoryRule
import com.example.fintrack.domain.model.CategoryStatus
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.LlmCategorySuggestion
import com.example.fintrack.domain.model.Merchant
import com.example.fintrack.domain.model.MerchantStatus
import com.example.fintrack.domain.model.MerchantVpaBinding
import com.example.fintrack.domain.model.RuleMatchKind
import com.example.fintrack.domain.model.RuleStatus
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.merchant.MerchantNormalization
import com.example.fintrack.domain.policy.CategorizationPolicy
import com.example.fintrack.domain.service.CategorizationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Stage 7 P14 — categorization engine tests.
 *
 * Acceptance gate: given the same evidence, deterministic rules produce
 * repeatable categories; user corrections persist; UPI merchant mappings
 * improve only after explicit confirmation.
 */
class CategorizationEngineTest {

    private val now = Instant.ofEpochMilli(1_700_000_000_000L)
    private val service = CategorizationService(clock = { now })
    private val uncategorizedId = EntityId("uncat-1")

    private fun rule(
        id: String,
        matchKind: RuleMatchKind,
        matchValue: String,
        categoryId: String = "cat-food",
        priority: Int = 10,
        createdBy: String = "SYSTEM",
        sourceKind: SourceKind = SourceKind.IMPORT_FILE,
        status: RuleStatus = RuleStatus.ACTIVE,
    ) = CategoryRule(
        id = id, name = "rule-$id", priority = priority, status = status,
        matchKind = matchKind, matchValue = matchValue, merchantId = null,
        categoryId = EntityId(categoryId),
        sourceKind = sourceKind, sourceVersion = "rules-v1",
        createdAt = now, createdBy = createdBy,
    )

    // ---- precedence ----

    @Test
    fun `user rule outranks heuristic rule on same merchant`() {
        val userRule = rule("u1", RuleMatchKind.MERCHANT_EXACT, "swiggy", "cat-user", createdBy = "USER")
        val heuristic = rule("h1", RuleMatchKind.MERCHANT_CONTAINS, "swiggy", "cat-heur")
        val decision = service.decide(
            uncategorizedId = uncategorizedId,
            rules = listOf(heuristic, userRule),
            rawMerchant = "Swiggy",
            rawVpa = null,
            llmSuggestion = null,
        )
        assertEquals(EntityId("cat-user"), decision.categoryId)
        assertEquals(CategorizationPolicy.DecisionSource.USER_CONFIRMED_RULE, decision.source)
    }

    @Test
    fun `heuristic rule outranks LLM suggestion`() {
        val heuristic = rule("h1", RuleMatchKind.MERCHANT_CONTAINS, "swiggy", "cat-heur")
        val suggestion = suggestionFor("cat-llm", 0.95)
        val decision = service.decide(
            uncategorizedId = uncategorizedId,
            rules = listOf(heuristic),
            rawMerchant = "Swiggy",
            rawVpa = null,
            llmSuggestion = suggestion,
        )
        assertEquals(CategorizationPolicy.DecisionSource.HIGH_CONFIDENCE_RULE, decision.source)
        assertEquals(EntityId("cat-heur"), decision.categoryId)
    }

    @Test
    fun `LLM suggestion wins when no rule matches`() {
        val suggestion = suggestionFor("cat-llm", 0.8)
        val decision = service.decide(
            uncategorizedId = uncategorizedId,
            rules = emptyList(),
            rawMerchant = "Unknown Vendor",
            rawVpa = null,
            llmSuggestion = suggestion,
        )
        assertEquals(CategorizationPolicy.DecisionSource.LLM_SUGGESTION, decision.source)
        assertEquals(EntityId("cat-llm"), decision.categoryId)
    }

    @Test
    fun `no match falls back to uncategorized`() {
        val decision = service.decide(
            uncategorizedId = uncategorizedId,
            rules = emptyList(),
            rawMerchant = "Totally Unknown",
            rawVpa = null,
            llmSuggestion = null,
        )
        assertEquals(uncategorizedId, decision.categoryId)
        assertEquals(CategorizationPolicy.DecisionSource.UNCATEGORIZED, decision.source)
    }

    @Test
    fun `disabled rules never fire`() {
        val disabled = rule("d1", RuleMatchKind.MERCHANT_EXACT, "swiggy", status = RuleStatus.DISABLED)
        val decision = service.decide(
            uncategorizedId = uncategorizedId,
            rules = listOf(disabled),
            rawMerchant = "Swiggy",
            rawVpa = null,
            llmSuggestion = null,
        )
        assertEquals(CategorizationPolicy.DecisionSource.UNCATEGORIZED, decision.source)
    }

    @Test
    fun `same evidence produces same decision (repeatable)`() {
        val rules = listOf(
            rule("h1", RuleMatchKind.MERCHANT_CONTAINS, "amazon"),
            rule("h2", RuleMatchKind.VPA, "netflix@paytm", "cat-streaming"),
        )
        val a = service.decide(uncategorizedId, rules, "Amazon Pay India", null, null)
        val b = service.decide(uncategorizedId, rules, "Amazon Pay India", null, null)
        assertEquals(a.categoryId, b.categoryId)
        assertEquals(a.source, b.source)
        assertEquals(a.ruleId, b.ruleId)
    }

    // ---- VPA matching ----

    @Test
    fun `vpa rule matches exact vpa`() {
        val vpaRule = rule("v1", RuleMatchKind.VPA, "NETFLIX@PAYTM", "cat-streaming")
        val decision = service.decide(
            uncategorizedId = uncategorizedId,
            rules = listOf(vpaRule),
            rawMerchant = null,
            rawVpa = "netflix@paytm",
            llmSuggestion = null,
        )
        assertEquals(EntityId("cat-streaming"), decision.categoryId)
    }

    @Test
    fun `vpa rule does not match different vpa`() {
        val vpaRule = rule("v1", RuleMatchKind.VPA, "netflix@paytm", "cat-streaming")
        val decision = service.decide(
            uncategorizedId = uncategorizedId,
            rules = listOf(vpaRule),
            rawMerchant = null,
            rawVpa = "someone@ybl",
            llmSuggestion = null,
        )
        assertEquals(CategorizationPolicy.DecisionSource.UNCATEGORIZED, decision.source)
    }

    // ---- merchant resolution & VPA bindings ----

    @Test
    fun `confirmed vpa binding resolves to bound merchant`() {
        val boundMerchant = merchant("m-bound", "Swiggy")
        val bindings = mapOf("swiggy@ybl" to boundMerchant.id)
        val resolved = service.resolveMerchant(
            rawCounterparty = "SWIGGY PVT LTD",
            upiVpa = "swiggy@ybl",
            accountId = null,
            existing = listOf(boundMerchant),
            confirmedVpaBindings = bindings,
        )
        assertEquals(boundMerchant.id, resolved.id)
    }

    @Test
    fun `unconfirmed vpa never resolves to a merchant`() {
        val other = merchant("m-other", "OtherVendor")
        // No confirmed bindings — the engine must NOT use an unconfirmed one.
        val resolved = service.resolveMerchant(
            rawCounterparty = "Someone Else",
            upiVpa = "someone@ybl",
            accountId = null,
            existing = listOf(other),
            confirmedVpaBindings = emptyMap(),
        )
        assertNotEquals(other.id, resolved.id)
    }

    @Test
    fun `existing merchant identity is reused not duplicated`() {
        val existing = merchant("m-existing", "Swiggy")
        val resolved = service.resolveMerchant(
            rawCounterparty = "Swiggy",
            upiVpa = null,
            accountId = null,
            existing = listOf(existing),
            confirmedVpaBindings = emptyMap(),
        )
        assertEquals(existing.id, resolved.id)
    }

    @Test
    fun `confirmVpaBinding marks binding as user-confirmed`() {
        val m = merchant("m1", "Swiggy")
        val binding = service.confirmVpaBinding(m.id, "swiggy@ybl")
        assertTrue(binding.confirmedByUser)
        assertEquals("swiggy@ybl", binding.vpa)
    }

    // ---- merchant normalization ----

    @Test
    fun `normalization strips corporate suffixes and case`() {
        assertEquals("swiggy", MerchantNormalization.normalize("SWIGGY Pvt Ltd"))
        assertEquals("swiggy", MerchantNormalization.normalize("  swiggy  india "))
        assertEquals("amazon pay", MerchantNormalization.normalize("Amazon-Pay!"))
    }

    @Test
    fun `normalization is stable and non-empty for real names`() {
        val a = MerchantNormalization.normalize("HDFC Bank Ltd.")
        val b = MerchantNormalization.normalize("hdfc bank")
        assertEquals(a, b)
        assertFalse(a.isBlank())
    }

    @Test
    fun `normalization of blank input is empty string`() {
        assertEquals("", MerchantNormalization.normalize(null))
        assertEquals("", MerchantNormalization.normalize(""))
        assertEquals("", MerchantNormalization.normalize("   "))
    }

    // ---- policy rank helpers ----

    @Test
    fun `policy ranks are strictly ordered`() {
        val sources = CategorizationPolicy.DecisionSource.entries
        val ranks = sources.map { CategorizationPolicy.rank(it) }
        assertEquals(ranks.size, ranks.toSet().size) // all distinct
        assertEquals(ranks.sorted(), ranks)          // declaration order == authority order
    }

    @Test
    fun `resolve keeps more authoritative decision`() {
        val user = decision("cat-user", CategorizationPolicy.DecisionSource.USER_CONFIRMED_RULE)
        val llm = decision("cat-llm", CategorizationPolicy.DecisionSource.LLM_SUGGESTION)
        assertEquals(user, CategorizationPolicy.resolve(llm, user))
        assertEquals(user, CategorizationPolicy.resolve(user, llm))
    }

    // ---- helpers ----

    private fun merchant(id: String, name: String) = Merchant(
        id = EntityId(id), displayName = name, normalizedName = name.lowercase(),
        accountId = null, status = MerchantStatus.ACTIVE,
        merchantIdentity = "identity-$id",
        sourceKind = SourceKind.SMS, sourceVersion = "merchants-v1",
        createdAt = now, mergedIntoMerchantId = null,
    )

    private fun suggestionFor(categoryId: String, confidence: Double) =
        LlmCategorySuggestion(
            id = "sugg-1", transactionId = "t1", categoryId = EntityId(categoryId),
            merchantId = null, confidence = confidence, reason = "model guess",
            modelId = "test-model", promptVersion = "p1", schemaVersion = "s1",
            suggestionIdentity = "ident-1", createdAt = now,
            accepted = false, acceptedAt = null,
        )

    private fun decision(categoryId: String, source: CategorizationPolicy.DecisionSource) =
        com.example.fintrack.domain.policy.CategorizationDecision(
            categoryId = EntityId(categoryId), merchantId = null, source = source,
            reason = "test", ruleId = null, confidence = null, atEpochMs = now.toEpochMilli(),
        )
}
