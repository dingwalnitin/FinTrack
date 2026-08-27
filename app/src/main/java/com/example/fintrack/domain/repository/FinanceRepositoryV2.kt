package com.example.fintrack.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Stable persistence contract for all later feature prompts.
 * Features build against this interface only — never Room/DAO directly.
 *
 * Deliberately free of data-layer types: rows are passed as domain-side row
 * contracts so the domain layer never depends on Room entities.
 */
interface FinanceRepositoryV2 {

    /** Raw evidence. Immutable; duplicate sourceHash returns false (idempotent). */
    suspend fun recordEvidence(evidence: EvidenceRecord): Boolean

    fun observeTransactions(): Flow<List<TransactionRow>>

    /** Money-changing write: interpretation + posting, idempotent via dedupeKey. */
    suspend fun postTransaction(txn: TransactionRow, entry: LedgerEntryRow): Boolean

    suspend fun addAccount(account: AccountRow): Boolean
    suspend fun addCategory(category: CategoryRow): Boolean

    /** Explicit transfer/settlement relationship between two postings. */
    suspend fun linkTransfer(transfer: TransferRow): Boolean

    /**
     * User correction: first-class; overwrites interpretation and is recorded in
     * audit. Survives automated reprocessing by policy (ProvenancePolicy).
     */
    suspend fun applyUserCorrection(
        txnId: String,
        amountMinor: Long,
        currencyCode: String,
        counterparty: String?,
        categoryId: String?,
        reason: String?,
        atEpochMs: Long,
    )

    // Resumable background work with persisted identity.
    suspend fun enqueueJob(job: ProcessingJobRow): Boolean
    suspend fun dueJobs(nowEpochMs: Long, limit: Int): List<ProcessingJobRow>
    suspend fun reportJobProgress(jobIdentity: String, status: String, error: String?, nextAttemptAt: Long)

    suspend fun audit(event: AuditEventRow)

    suspend fun transactionsForAccountBetween(accountId: String, fromDay: Long, toDay: Long): List<TransactionRow>

    // ---- v3: account authority ----

    fun observeAccounts(): Flow<List<AccountRow>>

    suspend fun getAccount(accountId: String): AccountRow?

    /** Manual CRUD. Duplicate last4 allowed; identity is the UUID. */
    suspend fun updateAccount(account: AccountRow)

    suspend fun archiveAccount(accountId: String)
    suspend fun restoreAccount(accountId: String)

    /** Opening balance seed; one per account, idempotent. */
    suspend fun setOpeningBalance(ob: OpeningBalanceRow): Boolean

    /** Immutable, timestamped snapshot; idempotent via snapshotIdentity. */
    suspend fun recordBalanceSnapshot(snapshot: BalanceSnapshotRow): Boolean

    suspend fun snapshotsForAccount(accountId: String): List<BalanceSnapshotRow>
    suspend fun latestSnapshot(accountId: String): BalanceSnapshotRow?

    suspend fun ledgerEntriesForAccount(accountId: String): List<LedgerEntryRow>

    /**
     * Sender mapping proposal (unconfirmed). Confirmation is a separate,
     * user-driven call — ownership is never auto-confirmed.
     */
    suspend fun proposeSenderMapping(mapping: SenderMappingRow): Boolean
    suspend fun confirmSenderMapping(senderId: String, accountId: String)
    suspend fun confirmedAccountsForSender(senderId: String): List<String>

    /** Alias learning: unconfirmed until user confirms. */
    suspend fun learnInstitutionAlias(aliasRaw: String, canonicalInstitution: String, confirmedByUser: Boolean): Boolean
    suspend fun confirmedAliases(): List<AliasRow>

    // ---- Domain-side row contracts (structurally identical to storage schema) ----

    data class EvidenceRecord(
        val id: String, val body: String, val sender: String?, val receivedAtEpochMs: Long,
        val sourceHash: String, val sourceKind: String, val sourceVersion: String, val capturedAtEpochMs: Long,
    )

    data class TransactionRow(
        val id: String, val messageId: String?, val accountId: String, val categoryId: String?,
        val amountMinor: Long, val currencyCode: String,
        val occurredAtEpochMs: Long, val localDateEpochDay: Long,
        val counterparty: String?, val counterpartyNormalized: String?, val referenceId: String?,
        val state: String,
        val sourceKind: String, val sourceVersion: String, val sourceReason: String?,
        val correctionSourceKind: String?, val correctionSourceVersion: String?,
        val correctionSourceReason: String?, val correctionCapturedAtEpochMs: Long?,
        val dedupeKey: String,
    )

    data class LedgerEntryRow(
        val id: String, val transactionId: String, val accountId: String,
        val direction: String, val amountMinor: Long, val currencyCode: String,
    )

    data class AccountRow(
        val id: String, val name: String, val normalizedName: String, val currencyCode: String,
        val accountType: String, val createdAtEpochMs: Long, val lifecycle: String,
        val nickname: String? = null, val last4: String? = null,
        val institutionName: String? = null,
    )

    data class CategoryRow(
        val id: String, val name: String, val normalizedName: String, val parentId: String?,
    )

    data class TransferRow(
        val id: String, val fromEntryId: String, val toEntryId: String,
        val kind: String, val sourceKind: String, val sourceVersion: String,
    )

    data class ProcessingJobRow(
        val id: String, val jobIdentity: String, val jobType: String, val payloadRef: String,
        val status: String, val attempts: Int, val maxAttempts: Int,
        val lastError: String?, val nextAttemptAtEpochMs: Long,
    )

    data class AuditEventRow(
        val id: String, val entityId: String, val entityType: String, val action: String,
        val actor: String, val detailReason: String?, val atEpochMs: Long,
    )

    data class OpeningBalanceRow(
        val id: String, val accountId: String, val amountMinor: Long,
        val currencyCode: String, val asOfEpochMs: Long,
    )

    data class BalanceSnapshotRow(
        val id: String, val accountId: String, val amountMinor: Long, val currencyCode: String,
        val kind: String, val messageId: String?, val capturedAtEpochMs: Long,
        val sourceKind: String, val sourceVersion: String, val snapshotIdentity: String,
    )

    data class SenderMappingRow(
        val id: String, val senderId: String, val accountId: String,
        val confirmedByUser: Boolean, val sourceKind: String, val sourceVersion: String,
        val createdAtEpochMs: Long,
    )

    data class AliasRow(
        val aliasRaw: String, val canonicalInstitution: String, val confirmedByUser: Boolean,
    )
}
