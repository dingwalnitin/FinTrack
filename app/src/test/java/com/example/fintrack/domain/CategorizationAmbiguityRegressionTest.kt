package com.example.fintrack.domain

import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.LlmCategorySuggestion
import com.example.fintrack.domain.model.RuleMatchKind
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.merchant.MerchantNormalization
import com.example.fintrack.domain.policy.CategorizationPolicy
import com.example.fintrack.domain.service.CategorizationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Stage 7 acceptance-gate regression: realistic ambiguous / conflicting
 * Indian financial-message scenarios must resolve deterministically and
 * never fabricate a category.
 *
 * Fixtures model real-world ambiguity:
 *  - a merchant whose name collides with two different categories
 *    ("Amazon" can be Shopping or Bill Pay for an Amazon Pay bill);
 *  - a UPI VPA that is shared between a personal handle and a business;
 *  - an LLM suggestion that contradicts a user rule.
 */
class CategorizationAmbiguityRegressionTest {

    private val now = Instant.ofEpochMilli(1_700_000_000_000L)
    private val service = CategorizationService(clock = { now })
    private val uncategorizedId = EntityId("uncat")

    @Test
    fun `ambiguous merchant name without rules falls back to uncategorized`() {
        // "Amazon" alone is genuinely ambiguous: shopping vs bill pay.
        val decision = service.decide(
            uncategorizedId = uncategorizedId,
            rules = emptyList(),
            rawMerchant = "AMAZON PAY INDIA",
            rawVpa = null,
            llmSuggestion = null,
        )
        assertEquals(uncategorizedId, decision.categoryId)
        assertEquals(CategorizationPolicy.DecisionSource.UNCATEGORIZED, decision.source)
    }

    @Test
    fun `conflicting user rule wins over contradicting LLM suggestion`() {
        val userRule = com.example.fintrack.domain.model.CategoryRule(
            id = "u-amazon", name = "user says amazon = shopping",
            priority = 1, status = com.example.fintrack.domain.model.RuleStatus.ACTIVE,
            matchKind = RuleMatchKind.MERCHANT_CONTAINS, matchValue = "amazon",
            merchantId = null, categoryId = EntityId("cat-shopping"),
            sourceKind = SourceKind.USER_CORRECTION, sourceVersion = "v1",
            createdAt = now, createdBy = "USER",
        )
        val llmSaysBills = LlmCategorySuggestion(
            id = "s1", transactionId = "t1", categoryId = EntityId("cat-bills"),
            merchantId = null, confidence = 0.97, reason = "model insists bills",
            modelId = "m", promptVersion = "p", schemaVersion = "s",
            suggestionIdentity = "i", createdAt = now, accepted = false, acceptedAt = null,
        )
        val decision = service.decide(
            uncategorizedId = uncategorizedId,
            rules = listOf(userRule),
            rawMerchant = "Amazon Pay Bill",
            rawVpa = null,
            llmSuggestion = llmSaysBills,
        )
        assertEquals(EntityId("cat-shopping"), decision.categoryId)
        assertEquals(CategorizationPolicy.DecisionSource.USER_CONFIRMED_RULE, decision.source)
    }

    @Test
    fun `shared vpa between personal and business resolves only after confirmation`() {
        // Same VPA surface form; the engine must not guess which merchant it
        // belongs to until the user confirms one binding.
        val resolvedUnconfirmed = service.resolveMerchant(
            rawCounterparty = "Ramesh Kumar",
            upiVpa = "ramesh@ybl",
            accountId = null,
            existing = emptyList(),
            confirmedVpaBindings = emptyMap(),
        )
        assertEquals("", MerchantNormalization.normalize(null))
        // No binding -> fresh merchant built from the raw name, not from the VPA.
        assertEquals("ramesh kumar", resolvedUnconfirmed.normalizedName)

        // After explicit confirmation, the same VPA resolves to that merchant.
        val confirmedMerchant = com.example.fintrack.domain.model.Merchant(
            id = EntityId("m-business"), displayName = "Ramesh Store",
            normalizedName = "ramesh store", accountId = null,
            status = com.example.fintrack.domain.model.MerchantStatus.ACTIVE,
            merchantIdentity = "ident-1",
            sourceKind = SourceKind.SMS, sourceVersion = "v1",
            createdAt = now, mergedIntoMerchantId = null,
        )
        val binding = service.confirmVpaBinding(confirmedMerchant.id, "ramesh@ybl")
        assertTrue(binding.confirmedByUser)
        val resolvedConfirmed = service.resolveMerchant(
            rawCounterparty = "RAMESH KUMAR",
            upiVpa = "ramesh@ybl",
            accountId = null,
            existing = listOf(confirmedMerchant),
            confirmedVpaBindings = mapOf(binding.vpa to binding.merchantId),
        )
        assertEquals(confirmedMerchant.id, resolvedConfirmed.id)
        assertNotEquals(resolvedUnconfirmed.id, resolvedConfirmed.id)
    }

    @Test
    fun `low-confidence LLM suggestion still surfaces as advisory not authoritative`() {
        val lowConfidence = LlmCategorySuggestion(
            id = "s2", transactionId = "t2", categoryId = EntityId("cat-guess"),
            merchantId = null, confidence = 0.2, reason = "unsure",
            modelId = "m", promptVersion = "p", schemaVersion = "s",
            suggestionIdentity = "i2", createdAt = now, accepted = false, acceptedAt = null,
        )
        val decision = service.decide(
            uncategorizedId = uncategorizedId,
            rules = emptyList(),
            rawMerchant = "Unknown Vendor LLP",
            rawVpa = null,
            llmSuggestion = lowConfidence,
        )
        // The engine surfaces it as LLM_SUGGESTION (advisory); the UI layer
        // routes sub-threshold suggestions into the review queue.
        assertEquals(CategorizationPolicy.DecisionSource.LLM_SUGGESTION, decision.source)
        assertTrue(decision.confidence != null && decision.confidence!! < 0.5)
    }
}
