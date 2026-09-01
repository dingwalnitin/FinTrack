package com.example.fintrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Stage 13 (v12) data layer.
 *
 * A: payee_category_rules — durable per-payee category rules. Upserts are
 *    idempotent via the unique payeeIdentityHash index.
 * D: transaction_evidence — durable link from a transaction to its source
 *    SMS and raw LLM JSON. rawLlmJson is NEVER overwritten by a cache-hit.
 */
@Dao
interface FinanceDaoV10 {

    // ---- A: payee category rules ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPayeeRule(rule: PayeeCategoryRuleEntity)

    @Query("UPDATE payee_category_rules SET categoryId = :categoryId, sourceKind = :sourceKind, sourceVersion = :sourceVersion, vpa = :vpa, updatedAtEpochMs = :updatedAtEpochMs WHERE payeeIdentityHash = :payeeIdentityHash")
    suspend fun updatePayeeRuleCategory(payeeIdentityHash: String, categoryId: String, sourceKind: String, sourceVersion: String, vpa: String?, updatedAtEpochMs: Long)

    @Query("SELECT * FROM payee_category_rules WHERE payeeIdentityHash = :payeeIdentityHash LIMIT 1")
    suspend fun payeeRuleByHash(payeeIdentityHash: String): PayeeCategoryRuleEntity?

    @Query("SELECT * FROM payee_category_rules")
    suspend fun allPayeeRules(): List<PayeeCategoryRuleEntity>

    // ---- D: transaction evidence ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransactionEvidence(evidence: TransactionEvidenceEntity)

    @Query("SELECT * FROM transaction_evidence WHERE transactionId = :transactionId ORDER BY createdAtEpochMs ASC")
    suspend fun evidenceForTransaction(transactionId: String): List<TransactionEvidenceEntity>

    @Query("SELECT * FROM transaction_evidence WHERE sourceMessageId = :sourceMessageId LIMIT 1")
    suspend fun evidenceByMessage(sourceMessageId: String): TransactionEvidenceEntity?
}
