package com.example.fintrack.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v12 Stage 13 additive entities (A: payee tagging, D: raw evidence).
 *
 * Design invariants:
 *  - `payee_category_rules` keys on a stable normalized payee identity hash
 *    (sha-256) so a re-run can never duplicate a rule (unique index). When an
 *    inbound transaction's payee has a rule, the rule's category is applied
 *    as a post-categorization override, persisted until the user changes it.
 *  - Raw SMS bodies ALWAYS stay in `raw_sms`; never copied into llm_* tables.
 *    `rawLlmJson` on `llm_interpretations` stores the raw LLM output JSON for
 *    audit/debug (nullable; absent when the row predates v12). It is never
 *    overwritten by a cache-hit.
 */
@Entity(
    tableName = "payee_category_rules",
    indices = [
        Index(value = ["payeeIdentityHash"], unique = true),
        Index("categoryId"),
    ],
)
data class PayeeCategoryRuleEntity(
    @PrimaryKey val id: String,
    /** sha-256 of the normalized payee identity (name and/or UPI VPA). */
    val payeeIdentityHash: String,
    /** Normalized payee name (display). */
    val payeeName: String,
    /** UPI VPA like name@bank when known; null for non-UPI payees. */
    val vpa: String?,
    /** Category to auto-apply for this payee. */
    val categoryId: String,
    /** USER_SET | LLM_VALIDATED (a manually-set rule is never auto-overwritten). */
    val sourceKind: String,
    val sourceVersion: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/**
 * Durable link from a transaction to its reconstruction evidence:
 * the source SMS id (raw_sms) and the raw LLM output JSON. This guarantees
 * a transaction row can be rebuilt as (raw_sms.body) + (rawLlmJson) even if
 * interpretations change later. Idempotent per (transactionId, sourceMessageId).
 */
@Entity(
    tableName = "transaction_evidence",
    indices = [
        Index("transactionId"),
        Index("sourceMessageId"),
        Index(value = ["transactionId", "sourceMessageId"], unique = true),
    ],
)
data class TransactionEvidenceEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val sourceMessageId: String,
    /** Raw LLM output JSON for this transaction; never overwritten on reprocess. */
    val rawLlmJson: String?,
    val createdAtEpochMs: Long,
)
