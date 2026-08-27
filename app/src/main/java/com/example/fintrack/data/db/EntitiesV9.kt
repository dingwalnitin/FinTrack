package com.example.fintrack.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v9 P14 + P15 additive entities (Stage 7).
 *
 * P14 — merchant normalization, rules and LLM advisor persistence.
 * The `categories` table already exists (v2); v9 adds the status/kind/
 * sortOrder/createdAt columns via MIGRATION_8_9, so the taxonomy entity
 * lives in EntitiesV2.kt.
 *
 *  - `merchants`: canonical merchant identity per (account, normalized name).
 *  - `merchant_aliases`: raw alias -> canonical merchant (idempotent).
 *  - `category_rules`: precedence-ordered deterministic rules.
 *  - `llm_category_suggestions`: advisory, never authoritative.
 *  - `merchant_vpa_bindings`: VPA -> merchant learned from confirmed edits.
 *  - `category_audit`: append-only history of every category change.
 *
 * P15 — review queue, splits, bulk corrections, tags/notes, audit.
 *  - `review_items`: rows pointing at transactions that need attention.
 *  - `transaction_splits`: parent/child split rows (amount conservation).
 *  - `reimbursement_links`: original expense linked to the reimbursing event.
 *  - `travel_modes`: per-account travel-mode tag window.
 *  - `transaction_tags`: free-form tags on transactions.
 *  - `transaction_notes`: free-form notes on transactions.
 *
 * All writes are idempotent via stable identity hashes / unique indices so
 * parser / LLM / categorization re-runs cannot duplicate history.
 */

@Entity(
    tableName = "merchants",
    indices = [
        Index(value = ["merchantIdentity"], unique = true), // sha-256(scope|normalized) for idempotency
        Index("accountId"),
        Index("status"),
    ],
)
data class MerchantEntity(
    @PrimaryKey val id: String,
    val displayName: String,                     // human label
    val normalizedName: String,                  // canonical normalization
    val accountId: String?,                      // optional scope (null = global)
    val status: String,                          // ACTIVE | MERGED | ARCHIVED
    val merchantIdentity: String,                // sha-256(accountId? | normalizedName)
    val sourceKind: String,                      // origin of the merchant row
    val sourceVersion: String,
    val createdAtEpochMs: Long,
    val mergedIntoMerchantId: String?,           // when MERGED, points at the surviving merchant
)

@Entity(
    tableName = "merchant_aliases",
    indices = [
        Index(value = ["aliasIdentity"], unique = true),
        Index("merchantId"),
    ],
)
data class MerchantAliasEntity(
    @PrimaryKey val id: String,
    val merchantId: String,                      // -> MerchantEntity.id
    val aliasRaw: String,                        // original surface form
    val aliasNormalized: String,
    val aliasIdentity: String,                   // sha-256(merchantId | aliasNormalized)
    val sourceKind: String,                      // SMS | USER | IMPORT
    val sourceVersion: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "category_rules",
    indices = [
        Index("priority"),
        Index("status"),
        Index("merchantId"),
        Index("categoryId"),
    ],
)
data class CategoryRuleEntity(
    @PrimaryKey val id: String,
    val name: String,                            // short human label
    val priority: Int,                           // lower = earlier; engine runs in order
    val status: String,                          // ACTIVE | DISABLED
    val matchKind: String,                       // MERCHANT_EXACT | MERCHANT_CONTAINS | VPA | USER_RULE
    val matchValue: String,                      // exact/contains/vpa pattern (case insensitive)
    val merchantId: String?,                     // set when a Merchant row was learned first
    val categoryId: String,                      // -> CategoryEntity.id
    val sourceKind: String,                      // USER_RULE | HEURISTIC | LLM_VALIDATED
    val sourceVersion: String,
    val createdAtEpochMs: Long,
    val createdBy: String,                       // USER | SYSTEM
)

@Entity(
    tableName = "llm_category_suggestions",
    indices = [
        Index("transactionId"),
        Index("categoryId"),
        Index(value = ["suggestionIdentity"], unique = true),
    ],
)
data class LlmCategorySuggestionEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val categoryId: String?,                     // null = model says "uncategorized"
    val merchantId: String?,                     // optional merchant confidence
    val confidence: Double,                      // 0..1
    val reason: String?,                         // short explanation
    val modelId: String,                         // provider model id
    val promptVersion: String,
    val schemaVersion: String,
    /** sha-256(transactionId | categoryId | merchantId?) — durable identity. */
    val suggestionIdentity: String,
    val createdAtEpochMs: Long,
    /** True if the user accepted this suggestion; subsequent writes are no-ops. */
    val accepted: Boolean,
    val acceptedAtEpochMs: Long?,
)

@Entity(
    tableName = "merchant_vpa_bindings",
    indices = [
        Index(value = ["vpaIdentity"], unique = true),
        Index("merchantId"),
        Index("confirmedByUser"),
    ],
)
data class MerchantVpaBindingEntity(
    @PrimaryKey val id: String,
    val merchantId: String,
    val vpa: String,                             // normalized VPA
    /** sha-256(merchantId | vpa) — durable identity. */
    val vpaIdentity: String,
    val confirmedByUser: Boolean,                // only true after explicit user confirmation
    val sourceKind: String,
    val sourceVersion: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "category_audit",
    indices = [
        Index("transactionId"),
        Index("atEpochMs"),
        Index("actor"),
    ],
)
data class CategoryAuditEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val previousCategoryId: String?,             // null = was uncategorized
    val newCategoryId: String?,                  // null = now uncategorized
    val previousMerchantId: String?,
    val newMerchantId: String?,
    val actor: String,                           // USER | SYSTEM | LLM_VALIDATED
    val sourceKind: String,                      // USER_CORRECTION | RULE | LLM_SUGGESTION_ACCEPTED
    val sourceVersion: String,
    val reason: String?,                         // free-form; explains the change
    val ruleId: String?,                         // when a rule fired
    val atEpochMs: Long,
)

// ---- P15 ----

@Entity(
    tableName = "review_items",
    indices = [
        Index("transactionId"),
        Index("status"),
        Index("reason"),
        Index("priority"),
    ],
)
data class ReviewItemEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val reason: String,                          // AMBIGUOUS | CONFLICTING | UNRESOLVED | LOW_CONFIDENCE | CATEGORY_NEEDS_REVIEW
    val priority: Int,                           // lower = sooner
    val status: String,                          // OPEN | RESOLVED | DISMISSED
    val createdAtEpochMs: Long,
    val resolvedAtEpochMs: Long?,
    val explanation: String,                     // short user-visible reason
    val sourceKind: String,
    val sourceVersion: String,
)

@Entity(
    tableName = "transaction_splits",
    indices = [
        Index("parentTransactionId"),
        Index("childTransactionId"),
        Index(value = ["parentTransactionId", "childTransactionId"], unique = true),
    ],
)
data class TransactionSplitEntity(
    @PrimaryKey val id: String,
    val parentTransactionId: String,
    val childTransactionId: String,
    /** sha-256(parent | child) — durable identity, idempotent re-assert. */
    val splitIdentity: String,
    val sourceKind: String,
    val sourceVersion: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "reimbursement_links",
    indices = [
        Index("expenseTransactionId"),
        Index("reimbursingTransactionId"),
        Index(value = ["expenseTransactionId", "reimbursingTransactionId"], unique = true),
    ],
)
data class ReimbursementLinkEntity(
    @PrimaryKey val id: String,
    val expenseTransactionId: String,            // the original spend being reimbursed
    val reimbursingTransactionId: String,        // the credit/refund that pays it back
    /** sha-256(expense | reimbursing) — durable identity. */
    val linkIdentity: String,
    val sourceKind: String,
    val sourceVersion: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "travel_modes",
    indices = [
        Index("accountId"),
        Index("status"),
        Index(value = ["accountId", "startEpochDay"], unique = true),
    ],
)
data class TravelModeEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val label: String,                           // "Bali trip"
    val currencyCode: String,                    // the trip's reporting currency
    val startEpochDay: Long,
    val endEpochDay: Long?,
    val status: String,                          // ACTIVE | ENDED
    val sourceKind: String,
    val sourceVersion: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "transaction_tags",
    indices = [
        Index("transactionId"),
        Index("tag"),
        Index(value = ["transactionId", "tag"], unique = true),
    ],
)
data class TransactionTagEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val tag: String,                             // normalized, lowercase
    val sourceKind: String,
    val sourceVersion: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "transaction_notes",
    indices = [
        Index("transactionId"),
    ],
)
data class TransactionNoteEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val note: String,
    val sourceKind: String,
    val sourceVersion: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
