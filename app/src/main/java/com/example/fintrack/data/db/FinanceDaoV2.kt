package com.example.fintrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DAOs for the v2 blueprint. Money-changing writes are @Transaction + idempotent
 * (IGNORE on dedupeKey / stable ids). UI never references this package.
 */
@Dao
interface FinanceDaoV2 {

    // ---- Raw evidence (immutable) ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE sourceHash = :hash LIMIT 1")
    suspend fun findBySourceHash(hash: String): MessageEntity?

    // ---- Transactions ----
    @Insert(onConflict = OnConflictStrategy.IGNORE) // idempotent via unique dedupeKey
    suspend fun insertTransaction(txn: TransactionEntity): Long

    /**
     * Correction write. User corrections outrank everything (SourceRank.USER_CONFIRMED);
     * automated reprocessing must call this only when its rank exceeds the stored one.
     */
    @Query(
        """UPDATE transactions SET
           amountMinor = :amountMinor, currencyCode = :currencyCode,
           counterparty = :counterparty, counterpartyNormalized = :counterpartyNormalized,
           categoryId = :categoryId, state = :state,
           correctionSourceKind = :correctionKind, correctionSourceVersion = :correctionVersion,
           correctionSourceReason = :correctionReason, correctionCapturedAtEpochMs = :correctionAt
           WHERE id = :id"""
    )
    suspend fun applyUserCorrection(
        id: String, amountMinor: Long, currencyCode: String,
        counterparty: String?, counterpartyNormalized: String?,
        categoryId: String?, state: String,
        correctionKind: String, correctionVersion: String,
        correctionReason: String?, correctionAt: Long,
    )

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransaction(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions ORDER BY occurredAtEpochMs DESC")
    fun observeTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE accountId = :accountId AND localDateEpochDay BETWEEN :fromDay AND :toDay ORDER BY occurredAtEpochMs")
    suspend fun transactionsForAccountBetween(accountId: String, fromDay: Long, toDay: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE state IN (:states)")
    suspend fun transactionsInStates(states: List<String>): List<TransactionEntity>

    // ---- Accounts / categories / ledger / transfers ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccount(account: AccountEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLedgerEntry(entry: LedgerEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTransfer(transfer: TransferEntity): Long

    /** Transactional money-changing write: transaction + its posting atomically. */
    @Transaction
    suspend fun postTransaction(txn: TransactionEntity, entry: LedgerEntryEntity) {
        if (insertTransaction(txn) != -1L) {
            insertLedgerEntry(entry)
        }
    }

    // ---- Processing jobs (resumable background work) ----
    @Insert(onConflict = OnConflictStrategy.IGNORE) // idempotent via jobIdentity
    suspend fun insertJob(job: ProcessingJobEntity): Long

    @Query("UPDATE processing_jobs SET status = :status, attempts = attempts + 1, lastError = :error, nextAttemptAtEpochMs = :nextAttemptAt WHERE jobIdentity = :jobIdentity")
    suspend fun updateJobProgress(jobIdentity: String, status: String, error: String?, nextAttemptAt: Long)

    @Query("SELECT * FROM processing_jobs WHERE status = :status AND nextAttemptAtEpochMs <= :now ORDER BY nextAttemptAtEpochMs LIMIT :limit")
    suspend fun dueJobs(status: String, now: Long, limit: Int): List<ProcessingJobEntity>

    // ---- Audit ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAuditEvent(event: AuditEventEntity)

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun transactionCount(): Int

    // ---- v3: account authority (balances, snapshots, mappings, aliases) ----

    @Query("SELECT * FROM accounts ORDER BY lifecycle, normalizedName")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getAccount(id: String): AccountEntity?

    /** Update identity/lifecycle fields; id and createdAt are immutable. */
    @Query(
        """UPDATE accounts SET name = :name, normalizedName = :normalizedName,
           currencyCode = :currencyCode, accountType = :accountType,
           lifecycle = :lifecycle, nickname = :nickname, last4 = :last4,
           institutionName = :institutionName WHERE id = :id"""
    )
    suspend fun updateAccount(
        id: String, name: String, normalizedName: String, currencyCode: String,
        accountType: String, lifecycle: String, nickname: String?, last4: String?,
        institutionName: String?,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE) // one opening balance per account
    suspend fun insertOpeningBalance(ob: AccountOpeningBalanceEntity): Long

    @Query("SELECT * FROM account_opening_balances WHERE accountId = :accountId LIMIT 1")
    suspend fun getOpeningBalance(accountId: String): AccountOpeningBalanceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE) // idempotent via snapshotIdentity
    suspend fun insertBalanceSnapshot(s: BalanceSnapshotEntity): Long

    @Query("SELECT * FROM balance_snapshots WHERE accountId = :accountId ORDER BY capturedAtEpochMs DESC")
    suspend fun snapshotsForAccount(accountId: String): List<BalanceSnapshotEntity>

    @Query("SELECT * FROM balance_snapshots WHERE accountId = :accountId ORDER BY capturedAtEpochMs DESC LIMIT 1")
    suspend fun latestSnapshot(accountId: String): BalanceSnapshotEntity?

    @Query("SELECT * FROM ledger_entries WHERE accountId = :accountId")
    suspend fun ledgerEntriesForAccount(accountId: String): List<LedgerEntryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSenderMapping(m: SenderAccountMappingEntity): Long

    /** User confirmation outranks any automated proposal for the same pair. */
    @Query("UPDATE sender_account_mappings SET confirmedByUser = 1 WHERE senderId = :senderId AND accountId = :accountId")
    suspend fun confirmSenderMapping(senderId: String, accountId: String)

    @Query("SELECT * FROM sender_account_mappings WHERE senderId = :senderId AND confirmedByUser = 1")
    suspend fun confirmedMappingsForSender(senderId: String): List<SenderAccountMappingEntity>

    @Query("SELECT * FROM sender_account_mappings WHERE senderId = :senderId")
    suspend fun mappingsForSender(senderId: String): List<SenderAccountMappingEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) // unique aliasNormalized
    suspend fun insertInstitutionAlias(a: InstitutionAliasEntity): Long

    @Query("SELECT * FROM institution_aliases WHERE confirmedByUser = 1")
    suspend fun confirmedAliases(): List<InstitutionAliasEntity>

    /** Transactional money write: snapshot + audit atomically. Returns whether the snapshot was newly inserted. */
    @Transaction
    suspend fun recordSnapshotWithAudit(snapshot: BalanceSnapshotEntity, event: AuditEventEntity): Boolean =
        insertBalanceSnapshot(snapshot) != -1L && run { insertAuditEvent(event); true }
}
