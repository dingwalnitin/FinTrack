package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.Category
import com.example.fintrack.domain.model.CategoryAudit
import com.example.fintrack.domain.model.CategoryKind
import com.example.fintrack.domain.model.CategoryRule
import com.example.fintrack.domain.model.CategoryStatus
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.Merchant
import com.example.fintrack.domain.model.MerchantAlias
import com.example.fintrack.domain.model.MerchantStatus
import com.example.fintrack.domain.model.MerchantVpaBinding
import com.example.fintrack.domain.model.RuleMatchKind
import com.example.fintrack.domain.model.RuleStatus
import com.example.fintrack.domain.merchant.MerchantNormalization
import com.example.fintrack.domain.merchant.MerchantRegistry
import com.example.fintrack.domain.policy.CategorizationDecision
import com.example.fintrack.domain.policy.CategorizationPolicy
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Stage 7 P14 — categorization engine.
 *
 * Wraps the rule ladder and merchant learning layer. The engine is
 * deterministic for a given (input + dataset) snapshot so tests can
 * assert the resulting decision.
 *
 * Order of operations:
 *   1. resolveMerchant()  — given a raw surface form + (optional) VPA,
 *      return the canonical [Merchant] (existing or freshly learned
 *      via the registry). UPI VPAs consult the binding table first;
 *      a binding is only used when confirmedByUser is true.
 *   2. decide() — given a transaction (with optional [rawMerchant] and
 *      [rawVpa]), run the rule ladder. The first hit wins; ties
 *      resolve to the higher-authority rung.
 *
 * The engine never mutates the database directly. It returns decisions
 * and lets the call site persist them transactionally through
 * [CategorizationSink]. This keeps the engine pure & testable.
 *
 * Singleton uncategorized root: callers pass the active uncategorized
 * [EntityId] via [uncategorizedId]. The engine NEVER fabricates a
 * category id of its own.
 */
class CategorizationService(
    private val registry: MerchantRegistry = MerchantRegistry.empty(),
    private val clock: () -> Instant = Instant::now,
) {

    // ---- merchant resolution ----

    /**
     * Resolve a raw counterparty to a canonical [Merchant]. Returns the
     * existing merchant when one matches the stable identity, otherwise
     * a freshly-built [Merchant] the caller can persist.
     *
     * @param rawCounterparty the user-visible merchant name (e.g. "SWIGGY")
     * @param upiVpa the normalized UPI VPA when known
     * @param accountId the account scope (null = global)
     * @param existing the sink's view of existing merchants + bindings
     * @param confirmedVpaBindings the sink's confirmed (VPA -> merchant) map
     */
    fun resolveMerchant(
        rawCounterparty: String?,
        upiVpa: String?,
        accountId: EntityId?,
        existing: List<Merchant>,
        confirmedVpaBindings: Map<String, EntityId>,
    ): Merchant {
        val identityInput = buildString {
            append(accountId?.value.orEmpty())
            append("|")
            if (!upiVpa.isNullOrBlank()) {
                append("VPA:")
                append(upiVpa.lowercase())
            } else {
                append("NAME:")
                append(MerchantNormalization.normalize(rawCounterparty.orEmpty()))
            }
        }
        val identity = sha256(identityInput)

        // 1. UPI VPA confirmed binding is the highest-confidence route.
        if (!upiVpa.isNullOrBlank()) {
            val vpaKey = upiVpa.lowercase()
            val bound = confirmedVpaBindings[vpaKey]
            if (bound != null) {
                existing.firstOrNull { it.id == bound }?.let { return it }
            }
        }

        // 2. Existing merchant by stable identity.
        existing.firstOrNull { it.merchantIdentity == identity }?.let { return it }

        // 3. Fallback: existing ACTIVE merchant with the same normalized name
        //    (alias-style reuse so we never duplicate a canonical merchant).
        val normalizedInput = MerchantNormalization.normalize(rawCounterparty)
        if (normalizedInput.isNotBlank()) {
            existing.firstOrNull {
                it.status == MerchantStatus.ACTIVE && it.normalizedName == normalizedInput
            }?.let { return it }
        }

        // 4. Build a fresh merchant. The engine NEVER infers a category.
        val display = rawCounterparty?.takeIf { it.isNotBlank() }
            ?: upiVpa?.substringBefore("@")
            ?: "Unknown"
        val normalized = MerchantNormalization.normalize(display)
        val now = clock()
        return Merchant(
            id = EntityId.generate(),
            displayName = display.trim(),
            normalizedName = normalized,
            accountId = accountId,
            status = MerchantStatus.ACTIVE,
            merchantIdentity = identity,
            sourceKind = com.example.fintrack.domain.model.SourceKind.SMS,
            sourceVersion = "merchants-v1",
            createdAt = now,
            mergedIntoMerchantId = null,
        )
    }

    /**
     * Confirm a UPI VPA binding: the merchant the user has just classified
     * becomes the canonical owner of this VPA. The binding is only
     * effective when the user has explicitly confirmed it.
     */
    fun confirmVpaBinding(
        merchantId: EntityId,
        vpa: String,
    ): MerchantVpaBinding {
        val v = vpa.lowercase()
        return MerchantVpaBinding(
            id = UUID.randomUUID().toString(),
            merchantId = merchantId,
            vpa = v,
            vpaIdentity = sha256("$v|${merchantId.value}"),
            confirmedByUser = true,
            sourceKind = com.example.fintrack.domain.model.SourceKind.USER_CORRECTION,
            sourceVersion = "merchants-v1",
            createdAt = clock(),
        )
    }

    // ---- decision ladder ----

    /**
     * Run the rule ladder. The first match wins; ties resolve to the
     * higher-authority rung. Returns a [CategorizationDecision] that
     * may have categoryId = null (uncategorized).
     *
     * Inputs are explicit so the engine does not have to fetch
     * anything from Room itself; the caller supplies the data snapshot.
     */
    fun decide(
        uncategorizedId: EntityId,
        rules: List<CategoryRule>,
        rawMerchant: String?,
        rawVpa: String?,
        llmSuggestion: com.example.fintrack.domain.model.LlmCategorySuggestion?,
    ): CategorizationDecision {
        val now = clock().toEpochMilli()
        val rawMerchantLc = rawMerchant?.lowercase()?.trim()
        val rawVpaLc = rawVpa?.lowercase()?.trim()

        // Active rules only, ordered by priority.
        val active = rules.asSequence()
            .filter { it.status == RuleStatus.ACTIVE }
            .sortedBy { it.priority }
            .toList()

        // ---- rung 1: user-confirmed rules ----
        for (rule in active) {
            if (rule.createdBy != "USER") continue
            if (matches(rule, rawMerchantLc, rawVpaLc)) {
                return CategorizationDecision(
                    categoryId = rule.categoryId,
                    merchantId = null,
                    source = CategorizationPolicy.DecisionSource.USER_CONFIRMED_RULE,
                    reason = "User rule '${rule.name}' matched.",
                    ruleId = rule.id,
                    confidence = 1.0,
                    atEpochMs = now,
                )
            }
        }

        // ---- rung 2: high-confidence heuristic rules ----
        for (rule in active) {
            if (rule.createdBy == "USER") continue
            if (rule.sourceKind == com.example.fintrack.domain.model.SourceKind.LLM_INTERPRETATION) continue
            if (matches(rule, rawMerchantLc, rawVpaLc)) {
                return CategorizationDecision(
                    categoryId = rule.categoryId,
                    merchantId = null,
                    source = CategorizationPolicy.DecisionSource.HIGH_CONFIDENCE_RULE,
                    reason = "Heuristic rule '${rule.name}' matched.",
                    ruleId = rule.id,
                    confidence = 0.9,
                    atEpochMs = now,
                )
            }
        }

        // ---- rung 4: LLM suggestion (advisory; validated by the caller) ----
        if (llmSuggestion != null && llmSuggestion.categoryId != null) {
            return CategorizationDecision(
                categoryId = llmSuggestion.categoryId,
                merchantId = null,
                source = CategorizationPolicy.DecisionSource.LLM_SUGGESTION,
                reason = llmSuggestion.reason
                    ?: "LLM suggested this category with confidence ${"%.2f".format(llmSuggestion.confidence)}.",
                ruleId = null,
                confidence = llmSuggestion.confidence,
                atEpochMs = now,
            )
        }

        // ---- rung 5: uncategorized fallback ----
        return CategorizationDecision(
            categoryId = uncategorizedId,
            merchantId = null,
            source = CategorizationPolicy.DecisionSource.UNCATEGORIZED,
            reason = "No rule or LLM suggestion matched. Defaulted to uncategorized.",
            ruleId = null,
            confidence = null,
            atEpochMs = now,
        )
    }

    private fun matches(
        rule: CategoryRule,
        rawMerchantLc: String?,
        rawVpaLc: String?,
    ): Boolean {
        return when (rule.matchKind) {
            RuleMatchKind.MERCHANT_EXACT -> rawMerchantLc != null &&
                rawMerchantLc == rule.matchValue.lowercase().trim()
            RuleMatchKind.MERCHANT_CONTAINS -> rawMerchantLc != null &&
                rawMerchantLc.contains(rule.matchValue.lowercase().trim())
            RuleMatchKind.VPA -> rawVpaLc != null &&
                rawVpaLc == rule.matchValue.lowercase().trim()
            RuleMatchKind.USER_RULE -> {
                val needle = rule.matchValue.lowercase().trim()
                if (rawMerchantLc != null && rawMerchantLc.contains(needle)) return true
                if (rawVpaLc != null && rawVpaLc.contains(needle)) return true
                false
            }
        }
    }

    // ---- helpers ----

    private fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** Persistence interface for [CategorizationService]. */
interface CategorizationSink {

    // Categories

    // Categories
    suspend fun findUncategorized(): Category?
    suspend fun findCategoryByNormalizedName(normalized: String): Category?
    suspend fun insertCategory(category: Category, normalizedKey: String): Boolean
    suspend fun archiveCategory(categoryId: EntityId)

    // Merchants
    suspend fun findMerchantByIdentity(identity: String): Merchant?
    suspend fun findMerchantById(id: EntityId): Merchant?
    suspend fun insertMerchant(merchant: Merchant, identity: String): Boolean
    suspend fun listActiveMerchants(): List<Merchant>
    suspend fun mergeMerchant(source: EntityId, target: EntityId)

    // Aliases
    suspend fun findAlias(merchantId: EntityId, aliasNormalized: String): MerchantAlias?
    suspend fun insertAlias(alias: MerchantAlias, aliasIdentity: String): Boolean
    suspend fun aliasesForMerchant(merchantId: EntityId): List<MerchantAlias>

    // VPA bindings
    suspend fun findVpaBinding(vpa: String): MerchantVpaBinding?
    suspend fun insertVpaBinding(binding: MerchantVpaBinding, vpaIdentity: String): Boolean
    suspend fun confirmedVpaBindings(): List<MerchantVpaBinding>

    // Rules
    suspend fun activeRules(): List<CategoryRule>
    suspend fun insertRule(rule: CategoryRule): Boolean
    suspend fun findRuleById(id: String): CategoryRule?
    suspend fun disableRule(id: String)

    // LLM advisor
    suspend fun insertLlmSuggestion(
        suggestion: com.example.fintrack.domain.model.LlmCategorySuggestion,
        identity: String,
    ): Boolean
    suspend fun acceptLlmSuggestion(id: String, atMs: Long)

    // Audit
    suspend fun appendCategoryAudit(
        transactionId: String,
        previousCategoryId: EntityId?,
        newCategoryId: EntityId?,
        previousMerchantId: EntityId?,
        newMerchantId: EntityId?,
        actor: String,
        sourceKind: String,
        sourceVersion: String,
        reason: String?,
        ruleId: String?,
        atEpochMs: Long,
    )

    /** Latest audit row for a transaction, or null when never categorized. */
    suspend fun latestAuditForTransaction(transactionId: String): CategoryAudit?

    // Transaction integration
    suspend fun applyCategorization(
        transactionId: String,
        categoryId: EntityId?,
        merchantId: EntityId?,
        sourceKind: String,
        sourceVersion: String,
        sourceReason: String?,
    )
}

/** Singleton uncategorized root helper. */
object Uncategorized {
    fun build(now: Instant = Instant.now()): Category = Category(
        id = EntityId.generate(),
        name = "Uncategorized",
        normalizedName = "uncategorized",
        parentId = null,
        status = CategoryStatus.ACTIVE,
        kind = CategoryKind.UNCATEGORIZED,
        sortOrder = Int.MAX_VALUE,
        createdAt = now,
    )

    val NORMALIZED_KEY = "uncategorized"
}
