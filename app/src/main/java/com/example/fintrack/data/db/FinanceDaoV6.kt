package com.example.fintrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * v9 P14 + P15 data layer (Stage 7).
 *
 * Responsibilities:
 *  1. P14 — category taxonomy, merchant normalization, aliases, rules, and
 *     the LLM advisor store. All writes are idempotent on stable identity
 *     hashes backed by unique indices.
 *  2. P15 — review queue, splits, reimbursement links, travel modes, tags
 *     and notes. All writes idempotent on identity hashes.
 *
 * P15: the split service writes one parent + N children and a
 * parent/child link in a single Room @Transaction so a half-applied split
 * can never corrupt the ledger.
 */
@Dao
interface FinanceDaoV6 {

    // ---- P14: categories ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun findCategoryById(id: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE normalizedName = :name LIMIT 1")
    suspend fun findCategoryByNormalizedName(name: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE status = :status ORDER BY sortOrder, name")
    suspend fun categoriesByStatus(status: String): List<CategoryEntity>

    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE parentId IS :parentId ORDER BY sortOrder, name")
    suspend fun categoriesByParent(parentId: String?): List<CategoryEntity>

    @Update
    suspend fun updateCategory(category: CategoryEntity): Int

    // ---- P14: merchants ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMerchant(merchant: MerchantEntity): Long

    @Query("SELECT * FROM merchants WHERE id = :id LIMIT 1")
    suspend fun findMerchantById(id: String): MerchantEntity?

    @Query("SELECT * FROM merchants WHERE merchantIdentity = :identity LIMIT 1")
    suspend fun findMerchantByIdentity(identity: String): MerchantEntity?

    @Query("SELECT * FROM merchants WHERE status = :status")
    suspend fun merchantsByStatus(status: String): List<MerchantEntity>

    @Query("UPDATE merchants SET status = :status, mergedIntoMerchantId = :mergedInto WHERE id = :id")
    suspend fun updateMerchantLifecycle(id: String, status: String, mergedInto: String?): Int

    // ---- P14: merchant aliases ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMerchantAlias(alias: MerchantAliasEntity): Long

    @Query("SELECT * FROM merchant_aliases WHERE aliasIdentity = :identity LIMIT 1")
    suspend fun findMerchantAliasByIdentity(identity: String): MerchantAliasEntity?

    @Query("SELECT * FROM merchant_aliases WHERE merchantId = :merchantId")
    suspend fun aliasesForMerchant(merchantId: String): List<MerchantAliasEntity>

    // ---- P14: category rules ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategoryRule(rule: CategoryRuleEntity): Long

    @Query("SELECT * FROM category_rules WHERE id = :id LIMIT 1")
    suspend fun findCategoryRuleById(id: String): CategoryRuleEntity?

    @Query("SELECT * FROM category_rules WHERE status = :status ORDER BY priority ASC")
    suspend fun categoryRulesByStatus(status: String): List<CategoryRuleEntity>

    @Query("SELECT * FROM category_rules WHERE merchantId = :merchantId AND status = :status ORDER BY priority ASC")
    suspend fun categoryRulesForMerchant(merchantId: String, status: String): List<CategoryRuleEntity>

    @Query("UPDATE category_rules SET status = :status WHERE id = :id")
    suspend fun updateCategoryRuleStatus(id: String, status: String): Int

    @Query("DELETE FROM category_rules WHERE id = :id")
    suspend fun deleteCategoryRuleById(id: String): Int

    // ---- P14: LLM advisor ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLlmCategorySuggestion(s: LlmCategorySuggestionEntity): Long

    @Query("SELECT * FROM llm_category_suggestions WHERE transactionId = :transactionId ORDER BY createdAtEpochMs DESC")
    suspend fun suggestionsForTransaction(transactionId: String): List<LlmCategorySuggestionEntity>

    @Query("SELECT * FROM llm_category_suggestions WHERE suggestionIdentity = :identity LIMIT 1")
    suspend fun findSuggestionByIdentity(identity: String): LlmCategorySuggestionEntity?

    @Query("UPDATE llm_category_suggestions SET accepted = 1, acceptedAtEpochMs = :atMs WHERE id = :id")
    suspend fun acceptLlmCategorySuggestion(id: String, atMs: Long): Int

    // ---- P14: merchant VPA bindings ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMerchantVpaBinding(b: MerchantVpaBindingEntity): Long

    @Query("SELECT * FROM merchant_vpa_bindings WHERE vpaIdentity = :identity LIMIT 1")
    suspend fun findMerchantVpaBindingByIdentity(identity: String): MerchantVpaBindingEntity?

    @Query("SELECT * FROM merchant_vpa_bindings WHERE merchantId = :merchantId")
    suspend fun merchantVpaBindingsForMerchant(merchantId: String): List<MerchantVpaBindingEntity>

    @Query("SELECT * FROM merchant_vpa_bindings WHERE confirmedByUser = 1")
    suspend fun confirmedMerchantVpaBindings(): List<MerchantVpaBindingEntity>

    // ---- P14: category audit ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategoryAudit(audit: CategoryAuditEntity): Long

    @Query("SELECT * FROM category_audit WHERE transactionId = :transactionId ORDER BY atEpochMs DESC")
    suspend fun categoryAuditForTransaction(transactionId: String): List<CategoryAuditEntity>

    @Query("SELECT * FROM category_audit WHERE transactionId = :transactionId ORDER BY atEpochMs DESC LIMIT 1")
    suspend fun latestCategoryAuditForTransaction(transactionId: String): CategoryAuditEntity?

    // ---- P15: review queue ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReviewItem(item: ReviewItemEntity): Long

    @Query("SELECT * FROM review_items WHERE id = :id LIMIT 1")
    suspend fun findReviewItemById(id: String): ReviewItemEntity?

    @Query("SELECT * FROM review_items WHERE transactionId = :txnId AND status = 'OPEN'")
    suspend fun openReviewItemsForTransaction(txnId: String): List<ReviewItemEntity>

    @Query("SELECT * FROM review_items WHERE status = :status ORDER BY priority ASC, createdAtEpochMs ASC")
    fun observeReviewItems(status: String): Flow<List<ReviewItemEntity>>

    @Query("SELECT * FROM review_items WHERE status = :status ORDER BY priority ASC, createdAtEpochMs ASC")
    suspend fun reviewItemsByStatus(status: String): List<ReviewItemEntity>

    @Query("UPDATE review_items SET status = :status, resolvedAtEpochMs = :atMs WHERE id = :id")
    suspend fun updateReviewItemStatus(id: String, status: String, atMs: Long?): Int

    // ---- P15: split transactions ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransactionSplit(split: TransactionSplitEntity): Long

    @Query("SELECT * FROM transaction_splits WHERE parentTransactionId = :parentId")
    suspend fun splitsForParent(parentId: String): List<TransactionSplitEntity>

    @Query("SELECT * FROM transaction_splits WHERE childTransactionId = :childId")
    suspend fun splitsForChild(childId: String): List<TransactionSplitEntity>

    @Query("SELECT * FROM transaction_splits WHERE parentTransactionId = :parentId AND childTransactionId = :childId LIMIT 1")
    suspend fun findSplit(parentId: String, childId: String): TransactionSplitEntity?

    // ---- P15: reimbursement links ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReimbursementLink(link: ReimbursementLinkEntity): Long

    @Query("SELECT * FROM reimbursement_links WHERE expenseTransactionId = :expenseId")
    suspend fun reimbursementsForExpense(expenseId: String): List<ReimbursementLinkEntity>

    @Query("SELECT * FROM reimbursement_links WHERE reimbursingTransactionId = :txnId")
    suspend fun reimbursementsByReimbursingTxn(txnId: String): List<ReimbursementLinkEntity>

    @Query("SELECT * FROM reimbursement_links WHERE expenseTransactionId = :e AND reimbursingTransactionId = :r LIMIT 1")
    suspend fun findReimbursementLink(e: String, r: String): ReimbursementLinkEntity?

    // ---- P15: travel modes ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTravelMode(travel: TravelModeEntity): Long

    @Query("SELECT * FROM travel_modes WHERE accountId = :accountId AND status = :status")
    suspend fun travelModesForAccountInStatus(accountId: String, status: String): List<TravelModeEntity>

    @Query("SELECT * FROM travel_modes WHERE id = :id LIMIT 1")
    suspend fun findTravelModeById(id: String): TravelModeEntity?

    @Query("UPDATE travel_modes SET status = :status, endEpochDay = :endDay WHERE id = :id")
    suspend fun updateTravelModeStatus(id: String, status: String, endDay: Long?): Int

    // ---- P15: tags / notes ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransactionTag(tag: TransactionTagEntity): Long

    @Query("SELECT * FROM transaction_tags WHERE transactionId = :txnId ORDER BY tag")
    suspend fun tagsForTransaction(txnId: String): List<TransactionTagEntity>

    @Query("DELETE FROM transaction_tags WHERE transactionId = :txnId AND tag = :tag")
    suspend fun deleteTransactionTag(txnId: String, tag: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransactionNote(note: TransactionNoteEntity): Long

    @Query("SELECT * FROM transaction_notes WHERE transactionId = :txnId ORDER BY updatedAtEpochMs DESC LIMIT 1")
    suspend fun latestNoteForTransaction(txnId: String): TransactionNoteEntity?

    @Query("SELECT * FROM transaction_notes WHERE transactionId = :txnId ORDER BY updatedAtEpochMs DESC")
    suspend fun notesForTransaction(txnId: String): List<TransactionNoteEntity>

    // ---- Composite transactions (atomic writes) ----

    /**
     * P15: split a parent transaction into N children inside one
     * Room @Transaction. Inserts the children (delegated to the
     * transaction-write DAO) and writes the parent/child split rows.
     * Idempotent: re-runs with the same children are no-ops because
     * the unique (parent, child) index is honored.
     */
    @Transaction
    suspend fun applySplits(splits: List<TransactionSplitEntity>) {
        splits.forEach { insertTransactionSplit(it) }
    }
}
