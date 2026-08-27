package com.example.fintrack.domain.policy

import com.example.fintrack.domain.model.EntityId

/**
 * Stage 7 P14 — categorization precedence & decision policy.
 *
 * The engine runs the rule ladder top-to-bottom; the first hit wins.
 * Each rung has a single, well-defined authority:
 *
 *   1. USER_CONFIRMED_RULE  — the user previously marked this exact
 *      (merchant|vpa|rule) -> category. Outranks everything.
 *   2. HIGH_CONFIDENCE_RULE — a deterministic, evidence-derived rule
 *      (e.g. "if the merchant row's displayName contains the canonical
 *      tax key word, use Taxes"). These never outrank the user.
 *   3. LEARNED_MAPPING      — a prior, user-confirmed categorization for
 *      the SAME merchant. Per-merchant; never generalized.
 *   4. LLM_SUGGESTION       — the LLM advisor's current suggestion for
 *      the transaction. Advisory only; validated against the taxonomy.
 *   5. UNCATEGORIZED        — fallback; the "unknown stays unknown" sink.
 *
 * Important: a single noisy merchant must NEVER become a global rule.
 * Per-merchant mappings are scoped to that merchant until the user
 * explicitly marks the rule as global.
 */
object CategorizationPolicy {

    enum class DecisionSource {
        USER_CONFIRMED_RULE,
        HIGH_CONFIDENCE_RULE,
        LEARNED_MAPPING,
        LLM_SUGGESTION,
        UNCATEGORIZED,
    }

    /** Source priority — lower value = higher authority. */
    fun rank(source: DecisionSource): Int = when (source) {
        DecisionSource.USER_CONFIRMED_RULE -> 0
        DecisionSource.HIGH_CONFIDENCE_RULE -> 1
        DecisionSource.LEARNED_MAPPING -> 2
        DecisionSource.LLM_SUGGESTION -> 3
        DecisionSource.UNCATEGORIZED -> 4
    }

    fun isMoreAuthoritative(a: DecisionSource, b: DecisionSource): Boolean =
        rank(a) < rank(b)

    /**
     * Resolve a candidate decision (typically from the rule ladder) into a
     * final categorization decision. Returns the more-authoritative of the
     * two: a stored correction always wins; the new candidate is dropped.
     */
    fun resolve(
        existing: CategorizationDecision?,
        incoming: CategorizationDecision,
    ): CategorizationDecision {
        if (existing == null) return incoming
        return if (isMoreAuthoritative(incoming.source, existing.source)) incoming else existing
    }
}

/** A categorization decision with provenance so audit is straightforward. */
data class CategorizationDecision(
    val categoryId: EntityId?,                  // null = uncategorized
    val merchantId: EntityId?,                  // resolved canonical merchant (optional)
    val source: CategorizationPolicy.DecisionSource,
    val reason: String,                         // human-readable explanation
    val ruleId: String?,                        // set when a rule fired
    val confidence: Double?,                    // [0,1] for LLM suggestions
    val atEpochMs: Long,
)
