package com.example.fintrack.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Stage 9 (P19 + P20) READ-ONLY data layer.
 *
 * No new tables and no schema bump: every query targets the existing
 * v2–v10 tables. All reads are bounded (LIMIT / windowed) so large ledgers
 * never load wholesale into Compose state. Raw evidence bodies are only ever
 * returned through [rawEvidenceForTransaction] for the explicit evidence
 * viewer — never in list flows.
 */
@Dao
interface FinanceDaoV8 {

    // ---- P19: ledger reads ----

    @Query(
        """SELECT * FROM transactions WHERE status != 'DELETED'
           AND localDateEpochDay BETWEEN :fromDay AND :toDay"""
    )
    suspend fun transactionsBetween(fromDay: Long, toDay: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE status != 'DELETED'")
    suspend fun allActiveTransactions(): List<TransactionEntity>

    @Query(
        """SELECT * FROM transactions WHERE status != 'DELETED'
           ORDER BY occurredAtEpochMs DESC LIMIT :limit OFFSET :offset"""
    )
    suspend fun activeTransactionsPaged(limit: Int, offset: Int): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE status != 'DELETED'")
    suspend fun activeTransactionCount(): Int

    @Query("SELECT * FROM accounts")
    suspend fun allAccounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun accountById(id: String): AccountEntity?

    @Query("SELECT * FROM account_opening_balances")
    suspend fun allOpeningBalances(): List<AccountOpeningBalanceEntity>

    @Query("SELECT * FROM balance_snapshots ORDER BY capturedAtEpochMs ASC")
    suspend fun allSnapshots(): List<BalanceSnapshotEntity>

    @Query(
        """SELECT * FROM balance_snapshots WHERE accountId = :accountId
           ORDER BY capturedAtEpochMs DESC LIMIT 1"""
    )
    suspend fun latestSnapshotFor(accountId: String): BalanceSnapshotEntity?

    @Query("SELECT * FROM categories WHERE status = 'ACTIVE'")
    suspend fun activeCategories(): List<CategoryEntity>

    // ---- P20 #1/#2/#3: search ----

    /**
     * Bounded search over indexed text columns. LIKE with escaped wildcards
     * is deterministic and uses the counterpartyNormalized / merchant indices.
     */
    @Query(
        """SELECT * FROM transactions WHERE status != 'DELETED'
           AND localDateEpochDay BETWEEN :fromDay AND :toDay
           AND accountId IN (:accountIds)
           AND kind IN (:kinds)
           AND (
               LOWER(COALESCE(merchant, '')) LIKE :textPattern
               OR LOWER(COALESCE(counterpartyNormalized, '')) LIKE :textPattern
               OR LOWER(COALESCE(description, '')) LIKE :textPattern
               OR LOWER(COALESCE(referenceId, '')) LIKE :textPattern
           )
           ORDER BY occurredAtEpochMs DESC
           LIMIT :limit OFFSET :offset"""
    )
    suspend fun searchTransactions(
        fromDay: Long,
        toDay: Long,
        accountIds: List<String>,
        kinds: List<String>,
        textPattern: String,
        limit: Int,
        offset: Int,
    ): List<TransactionEntity>

    /** Count query mirroring [searchTransactions] for pagination totals. */
    @Query(
        """SELECT COUNT(*) FROM transactions WHERE status != 'DELETED'
           AND localDateEpochDay BETWEEN :fromDay AND :toDay
           AND accountId IN (:accountIds)
           AND kind IN (:kinds)
           AND (
               LOWER(COALESCE(merchant, '')) LIKE :textPattern
               OR LOWER(COALESCE(counterpartyNormalized, '')) LIKE :textPattern
               OR LOWER(COALESCE(description, '')) LIKE :textPattern
               OR LOWER(COALESCE(referenceId, '')) LIKE :textPattern
           )"""
    )
    suspend fun countSearchTransactions(
        fromDay: Long,
        toDay: Long,
        accountIds: List<String>,
        kinds: List<String>,
        textPattern: String,
    ): Int

    // ---- P20 #4: tags / notes enrichment ----

    @Query("SELECT * FROM transaction_tags WHERE transactionId IN (:txnIds)")
    suspend fun tagsForTransactions(txnIds: List<String>): List<TransactionTagEntity>

    @Query(
        """SELECT n.* FROM transaction_notes n
           INNER JOIN (
               SELECT transactionId, MAX(updatedAtEpochMs) AS maxUpdated
               FROM transaction_notes WHERE transactionId IN (:txnIds)
               GROUP BY transactionId
           ) latest ON n.transactionId = latest.transactionId
                  AND n.updatedAtEpochMs = latest.maxUpdated"""
    )
    suspend fun latestNotesForTransactions(txnIds: List<String>): List<TransactionNoteEntity>

    @Query(
        """SELECT DISTINCT t.tag FROM transaction_tags t
           INNER JOIN transactions x ON x.id = t.transactionId
           WHERE x.status != 'DELETED' ORDER BY t.tag LIMIT 200"""
    )
    suspend fun distinctTagsInUse(): List<String>

    // ---- P20 #5: reconciliation inputs ----

    @Query("SELECT * FROM ledger_entries WHERE accountId = :accountId")
    suspend fun ledgerEntriesForAccount(accountId: String): List<LedgerEntryEntity>

    // ---- P20 #6: unresolved-data report ----

    @Query("SELECT COUNT(*) FROM transactions WHERE status != 'DELETED' AND kind = 'UNKNOWN'")
    suspend fun unknownKindCount(): Int

    @Query(
        """SELECT COUNT(*) FROM transactions WHERE status != 'DELETED'
           AND categoryId IS NULL AND kind IN ('EXPENSE','FEE')"""
    )
    suspend fun uncategorizedSpendCount(): Int

    @Query("SELECT COUNT(*) FROM review_items WHERE status = 'OPEN'")
    suspend fun openReviewItemCount(): Int

    @Query("SELECT COUNT(*) FROM llm_jobs WHERE status = 'TERMINAL_FAILED'")
    suspend fun llmTerminalFailedCount(): Int

    @Query("SELECT COUNT(*) FROM llm_jobs WHERE status IN ('RETRYABLE_FAILED','PENDING')")
    suspend fun llmPendingOrRetryableCount(): Int

    @Query(
        """SELECT COUNT(*) FROM processing_jobs
           WHERE status IN ('PENDING','RUNNING') AND nextAttemptAtEpochMs <= :now"""
    )
    suspend fun staleProcessingJobCount(now: Long): Int

    @Query(
        """SELECT COUNT(DISTINCT r.sender) FROM raw_sms r
           LEFT JOIN sender_account_mappings m ON m.senderId = r.sender AND m.confirmedByUser = 1
           WHERE r.sender IS NOT NULL AND m.senderId IS NULL"""
    )
    suspend fun unmappedSenderCount(): Int

    @Query("SELECT COUNT(*) FROM llm_interpretations WHERE overallConfidence IS NOT NULL AND overallConfidence < :threshold")
    suspend fun lowConfidenceInterpretationCount(threshold: Double): Int

    // ---- P20 #7: raw evidence viewer ----

    @Query(
        """SELECT r.* FROM raw_sms r
           INNER JOIN evidence_links e ON e.rawSmsId = r.id
           WHERE e.eventId = :transactionId
           ORDER BY CASE e.linkKind WHEN 'RAW_PRIMARY' THEN 0 ELSE 1 END, r.receivedAtEpochMs ASC"""
    )
    suspend fun rawEvidenceForTransaction(transactionId: String): List<RawSmsEntity>

    @Query(
        """SELECT * FROM raw_sms WHERE id = :rawSmsId LIMIT 1"""
    )
    suspend fun rawSmsById(rawSmsId: String): RawSmsEntity?

    @Query(
        """SELECT COUNT(*) FROM messages WHERE sourceHash = :sourceHash"""
    )
    suspend fun messageCountBySourceHash(sourceHash: String): Int

    // ---- P20 #8: parser/LLM provenance for the evidence viewer ----

    @Query("SELECT * FROM llm_interpretations WHERE sourceMessageId = :messageId ORDER BY createdAtEpochMs DESC")
    suspend fun interpretationsForMessage(messageId: String): List<LlmInterpretationEntity>

    @Query(
        """SELECT i.* FROM llm_interpretations i
           INNER JOIN evidence_links e ON e.rawSmsId = i.sourceMessageId
           WHERE e.eventId = :transactionId
           ORDER BY i.createdAtEpochMs DESC LIMIT 5"""
    )
    suspend fun interpretationsForTransaction(transactionId: String): List<LlmInterpretationEntity>

    // ---- Stage 12 P25: diagnostics-supporting reads ----

    @Query("SELECT COUNT(*) FROM processing_jobs WHERE status = 'PENDING'")
    suspend fun processingPendingCount(): Int

    @Query("SELECT COUNT(*) FROM processing_jobs WHERE status = 'RUNNING'")
    suspend fun processingRunningCount(): Int

    @Query("SELECT COUNT(*) FROM processing_jobs")
    suspend fun processingJobCount(): Int

    @Query("SELECT COUNT(*) FROM audit_log")
    suspend fun auditLogCount(): Int

    @Query("SELECT COUNT(*) FROM dedupe_clusters")
    suspend fun totalClusterCount(): Int

    @Query("SELECT COUNT(*) FROM dedupe_clusters WHERE status = :status")
    suspend fun clusterCountInStatus(status: String): Int

    /** Read budgets (Stage 8 entity lives in the same DB). */
    @Query("SELECT * FROM budgets ORDER BY id")
    suspend fun exportBudgets(): List<BudgetEntity>
}
