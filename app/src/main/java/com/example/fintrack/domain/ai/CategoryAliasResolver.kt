package com.example.fintrack.domain.ai

import com.example.fintrack.domain.model.CategoryRule
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.RuleMatchKind
import com.example.fintrack.domain.model.RuleStatus

/**
 * Stage 10 / P22 — natural-language category mapping (module 173).
 *
 * Maps user category language ("food", "eating out", "groceries") to stable
 * category IDs using DETERMINISTIC aliasing first. AI is only consulted for
 * unresolved ambiguity — and even then its output is advisory until the user
 * confirms.
 *
 * Aliasing order:
 *  1. exact normalized-name match on the taxonomy,
 *  2. alias table match (user-confirmed aliases),
 *  3. unique prefix/contains match when exactly one candidate exists,
 *  4. otherwise unresolved → caller may escalate to AI or ask the user.
 */
class CategoryAliasResolver(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class AliasCandidate(
        val categoryId: String,
        val normalizedName: String,
        val displayName: String,
    )

    sealed interface Resolution {
        /** Deterministic hit — safe to use without confirmation. */
        data class Resolved(val categoryId: String, val via: String) : Resolution

        /** Multiple candidates — the user must pick; never guessed. */
        data class Ambiguous(val candidates: List<AliasCandidate>) : Resolution

        /** Nothing matched. */
        data object Unresolved : Resolution
    }

    /**
     * Resolve a surface form against the active taxonomy plus confirmed
     * aliases. Deterministic for a given snapshot.
     */
    fun resolve(
        surface: String,
        taxonomy: List<AliasCandidate>,
        confirmedAliases: Map<String, String> = emptyMap(),
    ): Resolution {
        val q = surface.trim().lowercase()
        if (q.isEmpty()) return Resolution.Unresolved

        // 1. Exact normalized name.
        taxonomy.firstOrNull { it.normalizedName == q }?.let {
            return Resolution.Resolved(it.categoryId, "exact")
        }

        // 2. Confirmed alias table.
        confirmedAliases[q]?.let { return Resolution.Resolved(it, "alias") }

        // 3. Unique contains-match.
        val contains = taxonomy.filter {
            it.normalizedName.contains(q) || it.displayName.lowercase().contains(q)
        }
        return when {
            contains.size == 1 -> Resolution.Resolved(contains[0].categoryId, "unique-match")
            contains.size > 1 -> Resolution.Ambiguous(contains.sortedBy { it.normalizedName })
            else -> Resolution.Unresolved
        }
    }

    /**
     * Build a deterministic [CategoryRule] from an AI-proposed mapping.
     * The rule is advisory (createdBy=SYSTEM, sourceKind=LLM_VALIDATED) so it
     * can NEVER outrank a user rule in the existing CategorizationPolicy
     * ladder. Persisting requires explicit user acceptance upstream.
     */
    fun buildAdvisoryRule(
        id: String,
        merchantSurface: String,
        categoryId: EntityId,
        confidence: Double,
    ): CategoryRule? {
        if (confidence < MIN_ADVISORY_CONFIDENCE) return null
        require(categoryId.value.isNotBlank()) { "categoryId required" }
        return CategoryRule(
            id = id,
            name = "AI: $merchantSurface",
            priority = ADVISORY_RULE_PRIORITY,
            status = RuleStatus.ACTIVE,
            matchKind = RuleMatchKind.MERCHANT_CONTAINS,
            matchValue = merchantSurface.trim(),
            merchantId = null,
            categoryId = categoryId,
            sourceKind = com.example.fintrack.domain.model.SourceKind.LLM_INTERPRETATION,
            sourceVersion = ADVISORY_SOURCE_VERSION,
            createdAt = java.time.Instant.ofEpochMilli(clock()),
            createdBy = "SYSTEM",
        )
    }

    companion object {
        /** Below this confidence an AI mapping is not even proposed. */
        const val MIN_ADVISORY_CONFIDENCE = 0.6

        /** Advisory rules sort after every user/heuristic rule. */
        const val ADVISORY_RULE_PRIORITY = Int.MAX_VALUE / 2

        const val ADVISORY_SOURCE_VERSION = "ai-category-v1"
    }
}
