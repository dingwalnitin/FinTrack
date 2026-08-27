package com.example.fintrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * P09 + P10 data layer.
 *
 * Two DAO responsibilities:
 *  1. P09: durable dedup artifacts (evidence links, clusters, decisions)
 *     with idempotent writes and append-only history.
 *  2. P10: transaction + posting upserts that replace the prior posting
 *     group inside a single Room @Transaction, so duplicate postings
 *     never accumulate when an event is edited.
 *
 * This DAO is layered on top of [FinanceDaoV2]: callers can use both.
 */
@Dao
interface FinanceDaoV3 {

    // ---- P09: evidence links ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvidenceLink(link: EvidenceLinkEntity): Long

    @Query("SELECT * FROM evidence_links WHERE linkIdentity = :identity LIMIT 1")
    suspend fun findEvidenceLinkByIdentity(identity: String): EvidenceLinkEntity?

    @Query("SELECT * FROM evidence_links WHERE eventId = :eventId")
    suspend fun evidenceLinksForEvent(eventId: String): List<EvidenceLinkEntity>

    @Query("SELECT * FROM evidence_links WHERE rawSmsId = :rawSmsId")
    suspend fun evidenceLinksForRawSms(rawSmsId: String): List<EvidenceLinkEntity>

    // ---- P09: clusters ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCluster(cluster: DedupeClusterEntity): Long

    @Query("SELECT * FROM dedupe_clusters WHERE clusterIdentity = :identity LIMIT 1")
    suspend fun findClusterByIdentity(identity: String): DedupeClusterEntity?

    @Query("SELECT * FROM dedupe_clusters WHERE id = :id LIMIT 1")
    suspend fun findClusterById(id: String): DedupeClusterEntity?

    @Query("SELECT * FROM dedupe_clusters WHERE status = :status")
    suspend fun clustersInStatus(status: String): List<DedupeClusterEntity>

    // ---- P09: cluster members ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClusterMember(member: DedupeClusterMemberEntity): Long

    @Query("SELECT * FROM dedupe_cluster_members WHERE clusterId = :clusterId")
    suspend fun clusterMembers(clusterId: String): List<DedupeClusterMemberEntity>

    @Query("DELETE FROM dedupe_cluster_members WHERE id = :id")
    suspend fun deleteClusterMemberById(id: String)

    // ---- P09: decisions (append-only) ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDecision(decision: DedupeDecisionEntity): Long

    @Query("SELECT * FROM dedupe_decisions WHERE decisionEventId = :eventId ORDER BY appliedAtEpochMs DESC")
    suspend fun decisionsForEvent(eventId: String): List<DedupeDecisionEntity>

    @Query("SELECT * FROM dedupe_decisions WHERE clusterId = :clusterId ORDER BY appliedAtEpochMs DESC")
    suspend fun decisionsForCluster(clusterId: String): List<DedupeDecisionEntity>

    // ---- P10: transactions + postings (replace-group upsert) ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransaction(txn: TransactionEntity): Long

    @Update
    suspend fun updateTransaction(txn: TransactionEntity): Int

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getTransactionV6(id: String): TransactionEntity?

    /**
     * P10 #4: replace the prior posting group for a transaction inside a
     * single Room @Transaction. Any prior [LedgerEntryEntity] rows with the
     * same postingGroupId are deleted and the new postings are inserted
     * atomically. The transaction row is upserted in the same transaction.
     * Editing an event therefore never accumulates duplicate postings.
     */
    @Transaction
    suspend fun replacePostingGroup(
        txn: TransactionEntity,
        newPostings: List<LedgerEntryEntity>,
    ) {
        val priorGroup = txn.postingGroupId
        if (priorGroup != null) {
            deleteLedgerEntriesForGroup(priorGroup)
        }
        upsertTransaction(txn)
        newPostings.forEach { insertLedgerEntryInternal(it) }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgerEntryInternal(entry: LedgerEntryEntity): Long

    @Query("DELETE FROM ledger_entries WHERE postingGroupId = :groupId")
    suspend fun deleteLedgerEntriesForGroup(groupId: String)

    @Query("SELECT * FROM ledger_entries WHERE postingGroupId = :groupId")
    suspend fun ledgerEntriesForGroup(groupId: String): List<LedgerEntryEntity>

    @Query("SELECT * FROM ledger_entries WHERE transactionId = :txnId")
    suspend fun ledgerEntriesForTxn(txnId: String): List<LedgerEntryEntity>

    /** Soft-delete tombstone: keep history, flip status, leave postings. */
    @Query(
        """UPDATE transactions SET status = :status, deletedAtEpochMs = :deletedAt,
           deletedReason = :reason WHERE id = :id"""
    )
    suspend fun tombstoneTransaction(
        id: String,
        status: String,
        deletedAt: Long?,
        reason: String?,
    ): Int

    @Query(
        """SELECT t.*, le.amountMinor AS postingAmountMinor, le.direction AS postingDirection,
                  le.accountId AS postingAccountId, le.currencyCode AS postingCurrency
           FROM transactions t LEFT JOIN ledger_entries le ON le.postingGroupId = t.postingGroupId
           WHERE t.id = :id"""
    )
    suspend fun getTransactionWithPostings(id: String): List<TransactionWithPostingRow>

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observeTransaction(id: String): Flow<TransactionEntity?>

    @Query("SELECT * FROM transactions WHERE status = :status ORDER BY occurredAtEpochMs DESC")
    fun observeTransactionsWithStatus(status: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE postingGroupId = :groupId")
    suspend fun transactionsForPostingGroup(groupId: String): List<TransactionEntity>
}

/**
 * Flat row used by the transaction-detail query (P10 #6). Posting columns
 * are nullable because some events (REFUND, TRANSFER siblings in P11) may
 * have multiple postings; some may have zero (e.g. dry-run preview).
 */
data class TransactionWithPostingRow(
    val id: String,
    val messageId: String?,
    val accountId: String,
    val categoryId: String?,
    val amountMinor: Long,
    val currencyCode: String,
    val occurredAtEpochMs: Long,
    val localDateEpochDay: Long,
    val counterparty: String?,
    val counterpartyNormalized: String?,
    val referenceId: String?,
    val state: String,
    val sourceKind: String,
    val sourceVersion: String,
    val sourceReason: String?,
    val correctionSourceKind: String?,
    val correctionSourceVersion: String?,
    val correctionSourceReason: String?,
    val correctionCapturedAtEpochMs: Long?,
    val dedupeKey: String,
    val kind: String,
    val subtype: String?,
    val status: String,
    val merchant: String?,
    val description: String?,
    val rail: String?,
    val cardMask: String?,
    val postingGroupId: String?,
    val transferGroupId: String?,
    val deletedAtEpochMs: Long?,
    val deletedReason: String?,
    val postingAmountMinor: Long?,
    val postingDirection: String?,
    val postingAccountId: String?,
    val postingCurrency: String?,
)
