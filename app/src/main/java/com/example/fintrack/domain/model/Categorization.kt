package com.example.fintrack.domain.model

import java.time.Instant

/**
 * Stage 7 P14 — category taxonomy, merchant normalization and rules.
 *
 * Design invariants (App Bible + P14):
 *  - Categories form a parent/child tree with stable ids so renames do
 *    not break history. The root "Uncategorized" row has
 *    kind = UNCATEGORIZED and is a singleton: it represents the
 *    "unknown" terminal state. Anything the engine cannot place lands
 *    there rather than being guessed.
 *  - Merchants are canonicalised per (accountId?, normalizedName). The
 *    canonicalization is idempotent so re-runs never duplicate a row.
 *  - Aliases map a raw surface form to a canonical merchant. They are
 *    the durable learning layer the engine consults before suggesting
 *    a new merchant.
 *  - Category rules carry a priority and a kind. The engine runs rules
 *    in priority order; the first match wins. User rules always
 *    outrank heuristic rules; LLM_VALIDATED rules are advisory only
 *    and must never outrank user corrections.
 *  - LLM suggestions land in their own table and never become a write
 *    source of truth. They are surfaced in the Review queue until the
 *    user accepts them; the accept is a normal user correction.
 *  - User corrections create / update safe local mappings. The engine
 *    NEVER promotes a single noisy merchant into a global rule: the
 *    mapping is per-merchant until the user explicitly marks it global.
 */
enum class CategoryStatus { ACTIVE, ARCHIVED }
enum class CategoryKind { TAXONOMY, UNCATEGORIZED }

data class Category(
    val id: EntityId,
    val name: String,
    val normalizedName: String,
    val parentId: EntityId?,
    val status: CategoryStatus,
    val kind: CategoryKind,
    val sortOrder: Int,
    val createdAt: Instant,
) {
    init {
        require(name.isNotBlank()) { "category name must not be blank" }
        if (kind == CategoryKind.UNCATEGORIZED) {
            // The uncategorized root is a singleton; it may not be parented
            // and is always at the top of the list.
            require(parentId == null) { "UNCATEGORIZED must be a root category" }
        }
        if (kind == CategoryKind.TAXONOMY && parentId != null) {
            require(parentId != id) { "category cannot be its own parent" }
        }
    }

    /** True when this is the singleton uncategorized root. */
    val isUncategorizedRoot: Boolean get() = kind == CategoryKind.UNCATEGORIZED
}

enum class MerchantStatus { ACTIVE, MERGED, ARCHIVED }

data class Merchant(
    val id: EntityId,
    val displayName: String,
    val normalizedName: String,
    val accountId: EntityId?,                    // null = global
    val status: MerchantStatus,
    val merchantIdentity: String,                // sha-256(accountId? | normalized)
    val sourceKind: SourceKind,
    val sourceVersion: String,
    val createdAt: Instant,
    val mergedIntoMerchantId: EntityId?,
) {
    init {
        require(displayName.isNotBlank()) { "merchant displayName must not be blank" }
        require(normalizedName.isNotBlank()) { "merchant normalizedName must not be blank" }
        require(merchantIdentity.isNotBlank())
        if (status == MerchantStatus.MERGED) {
            require(mergedIntoMerchantId != null && mergedIntoMerchantId != id) {
                "MERGED merchant must point at a different merchant"
            }
        } else {
            require(mergedIntoMerchantId == null) {
                "non-MERGED merchant must not have a merge target"
            }
        }
    }
}

data class MerchantAlias(
    val id: String,
    val merchantId: EntityId,
    val aliasRaw: String,
    val aliasNormalized: String,
    val aliasIdentity: String,                   // sha-256(merchantId | aliasNormalized)
    val sourceKind: SourceKind,
    val sourceVersion: String,
    val createdAt: Instant,
)

/** Rule match kinds. */
enum class RuleMatchKind {
    /** Exact (case-insensitive) equality on the merchant display name. */
    MERCHANT_EXACT,
    /** Substring match (case-insensitive) on the merchant display name. */
    MERCHANT_CONTAINS,
    /** Exact (case-insensitive) equality on a UPI VPA. */
    VPA,
    /** A free-form user rule stored as opaque match text (e.g. "Swiggy Instamart"). */
    USER_RULE,
}

enum class RuleStatus { ACTIVE, DISABLED }

data class CategoryRule(
    val id: String,
    val name: String,
    val priority: Int,
    val status: RuleStatus,
    val matchKind: RuleMatchKind,
    val matchValue: String,
    val merchantId: EntityId?,                   // set when a merchant row anchors the rule
    val categoryId: EntityId,
    val sourceKind: SourceKind,
    val sourceVersion: String,
    val createdAt: Instant,
    val createdBy: String,                       // USER | SYSTEM
) {
    init {
        require(name.isNotBlank())
        require(matchValue.isNotBlank()) { "matchValue must not be blank" }
        require(categoryId.value.isNotBlank())
    }
}

/** LLM advisor persistence shape. */
data class LlmCategorySuggestion(
    val id: String,
    val transactionId: String,
    val categoryId: EntityId?,                   // null = model says uncategorized
    val merchantId: EntityId?,
    val confidence: Double,
    val reason: String?,
    val modelId: String,
    val promptVersion: String,
    val schemaVersion: String,
    val suggestionIdentity: String,
    val createdAt: Instant,
    val accepted: Boolean,
    val acceptedAt: Instant?,
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be in [0,1]" }
        require(suggestionIdentity.isNotBlank())
    }
}

/** VPA <-> merchant binding (only confirmedByUser rows drive categorization). */
data class MerchantVpaBinding(
    val id: String,
    val merchantId: EntityId,
    val vpa: String,
    val vpaIdentity: String,
    val confirmedByUser: Boolean,
    val sourceKind: SourceKind,
    val sourceVersion: String,
    val createdAt: Instant,
)

/** Append-only audit row recording every category/merchant change. */
data class CategoryAudit(
    val id: String,
    val transactionId: String,
    val previousCategoryId: EntityId?,
    val newCategoryId: EntityId?,
    val previousMerchantId: EntityId?,
    val newMerchantId: EntityId?,
    val actor: String,                            // USER | SYSTEM | LLM_VALIDATED
    val sourceKind: String,                       // USER_CORRECTION | RULE | LLM_SUGGESTION_ACCEPTED
    val sourceVersion: String,
    val reason: String?,
    val ruleId: String?,
    val atEpochMs: Long,
)
