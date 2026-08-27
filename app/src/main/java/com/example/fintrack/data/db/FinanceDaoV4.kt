package com.example.fintrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * P11 data layer.
 *
 * Three responsibilities:
 *  1. P11 #1: two-sided transfers. `recordTransfer` writes the source +
 *     destination transactions, the two posting rows, and the [TransferEntity]
 *     link inside a single Room @Transaction.
 *  2. P11 #2: refund + parent/child links.
 *  3. P11 #6: explicit lifecycle writes. `flipStatus` is the central
 *     transition primitive; the parser / LLM orchestrator / manual-entry
 *     service all use it.
 *
 * All writes are idempotent: posting rows keyed by deterministic ids,
 * refund_links on a unique (refundedEventId, refundEventId) index,
 * transaction_links on a unique (parentEventId, childEventId, role) index.
 */
@Dao
interface FinanceDaoV4 {

    // ---- P11 #1: transfers (two-sided) ----

    /**
     * Persist a complete two-sided transfer in one Room @Transaction:
     *  - upsert the source [txnSource] (DEBIT on fromAccountId)
     *  - upsert the destination [txnDestination] (CREDIT on toAccountId)
     *  - insert two [LedgerEntryEntity] rows sharing the same postingGroupId
     *  - insert a single [TransferEntity] linking the two sides
     *
     * Idempotency: the call site supplies a stable [transferGroupId] so
     * re-running the service for the same logical transfer is a no-op
     * (REPLACE on transactions + the unique toEntryId index on transfers
     * catches duplicates).
     */
    @Transaction
    suspend fun recordTransfer(
        txnSource: TransactionEntity,
        txnDestination: TransactionEntity,
        entrySource: LedgerEntryEntity,
        entryDestination: LedgerEntryEntity,
        transfer: TransferEntity,
    ) {
        // Make sure the two transactions share the transferGroupId.
        require(txnSource.transferGroupId == txnDestination.transferGroupId) {
            "transferGroupId mismatch between the two sides"
        }
        require(txnSource.postingGroupId == txnDestination.postingGroupId) {
            "postingGroupId mismatch between the two sides"
        }
        upsertTransaction(txnSource)
        upsertTransaction(txnDestination)
        insertLedgerEntryInternal(entrySource)
        insertLedgerEntryInternal(entryDestination)
        insertTransfer(transfer)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransaction(txn: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgerEntryInternal(entry: LedgerEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransfer(transfer: TransferEntity): Long

    // ---- P11 #1: transfer lookups ----

    @Query("SELECT * FROM transfers WHERE toEntryId = :toEntryId LIMIT 1")
    suspend fun findTransferByToEntry(toEntryId: String): TransferEntity?

    @Query("SELECT * FROM transfers WHERE fromEntryId = :fromEntryId LIMIT 1")
    suspend fun findTransferByFromEntry(fromEntryId: String): TransferEntity?

    @Query("SELECT * FROM transactions WHERE transferGroupId = :groupId")
    suspend fun transactionsForTransferGroup(groupId: String): List<TransactionEntity>

    // ---- P11 #2: refund links ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRefundLink(link: RefundLinkEntity): Long

    @Query("SELECT * FROM refund_links WHERE refundIdentity = :identity LIMIT 1")
    suspend fun findRefundLinkByIdentity(identity: String): RefundLinkEntity?

    @Query("SELECT * FROM refund_links WHERE refundEventId = :eventId")
    suspend fun refundLinksForEvent(eventId: String): List<RefundLinkEntity>

    @Query("SELECT * FROM refund_links WHERE refundedEventId = :eventId")
    suspend fun refundLinksForOriginalEvent(eventId: String): List<RefundLinkEntity>

    // ---- P11 #2: generic parent/child links (fees) ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransactionLink(link: TransactionLinkEntity): Long

    @Query("SELECT * FROM transaction_links WHERE linkIdentity = :identity LIMIT 1")
    suspend fun findTransactionLinkByIdentity(identity: String): TransactionLinkEntity?

    @Query("SELECT * FROM transaction_links WHERE parentEventId = :eventId")
    suspend fun childLinksForParent(eventId: String): List<TransactionLinkEntity>

    @Query("SELECT * FROM transaction_links WHERE childEventId = :eventId")
    suspend fun parentLinksForChild(eventId: String): List<TransactionLinkEntity>

    // ---- P11 #6: explicit lifecycle writes ----

    @Query(
        """UPDATE transactions SET status = :status WHERE id = :id"""
    )
    suspend fun flipStatus(id: String, status: String): Int

    @Query(
        """UPDATE transactions SET status = 'POSTED', deletedAtEpochMs = NULL, deletedReason = NULL
           WHERE id = :id AND status = 'DELETED'"""
    )
    suspend fun restoreFromTombstone(id: String): Int

    // ---- P11 #7: audit + tombstone reads ----

    @Query("SELECT * FROM audit_events WHERE entityId = :entityId ORDER BY atEpochMs DESC")
    suspend fun auditEventsForEntity(entityId: String): List<AuditEventEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAuditEvent(event: AuditEventEntity): Long

    // ---- P11: active-only read paths ----

    @Query(
        """SELECT * FROM transactions
           WHERE status != 'DELETED' AND kind != 'TRANSFER'
           ORDER BY occurredAtEpochMs DESC"""
    )
    suspend fun activeNonTransferTransactions(): List<TransactionEntity>

    @Query(
        """SELECT * FROM transactions
           WHERE accountId = :accountId AND status != 'DELETED' AND kind != 'TRANSFER'
           ORDER BY occurredAtEpochMs DESC"""
    )
    suspend fun activeNonTransferForAccount(accountId: String): List<TransactionEntity>

    /**
     * Lookup helper for the transfer-candidate matcher: returns posted,
     * non-deleted transactions on the given accounts in a recent window.
     * Used to find (DEBIT, CREDIT) pairs the matcher should score.
     */
    @Query(
        """SELECT * FROM transactions
           WHERE status = 'POSTED' AND kind IN ('EXPENSE','INCOME','CASH_MOVE')
             AND accountId IN (:accountIds)
             AND occurredAtEpochMs BETWEEN :fromEpochMs AND :toEpochMs
           ORDER BY occurredAtEpochMs DESC"""
    )
    suspend fun candidatesInWindow(
        accountIds: List<String>,
        fromEpochMs: Long,
        toEpochMs: Long,
    ): List<TransactionEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getAccount(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE lifecycle = 'ACTIVE'")
    suspend fun activeAccounts(): List<AccountEntity>

    @Query("SELECT * FROM ledger_entries WHERE transactionId = :txnId")
    suspend fun ledgerEntriesForTxn(txnId: String): List<LedgerEntryEntity>
}

/**
 * Result row used by the candidate matcher. We return the entity as-is;
 * the matcher converts to its own [com.example.fintrack.domain.dedupe.Candidate].
 */
typealias TransactionEntitySnapshot = TransactionEntity
