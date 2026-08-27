package com.example.fintrack.data.repository

import com.example.fintrack.data.db.AccountEntity
import com.example.fintrack.data.db.AccountOpeningBalanceEntity
import com.example.fintrack.data.db.AuditEventEntity
import com.example.fintrack.data.db.BalanceSnapshotEntity
import com.example.fintrack.data.db.CategoryEntity
import com.example.fintrack.data.db.FinanceDaoV2
import com.example.fintrack.data.db.InstitutionAliasEntity
import com.example.fintrack.data.db.LedgerEntryEntity
import com.example.fintrack.data.db.MessageEntity
import com.example.fintrack.data.db.ProcessingJobEntity
import com.example.fintrack.data.db.SenderAccountMappingEntity
import com.example.fintrack.data.db.TransactionEntity
import com.example.fintrack.data.db.TransferEntity
import com.example.fintrack.domain.repository.FinanceRepositoryV2
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Room-backed implementation of the v2 persistence contract.
 * All writes delegate to DAO-level idempotent operations; audit events are
 * written alongside state changes. Maps domain row contracts <-> Room entities.
 */
class RoomFinanceRepositoryV2(private val dao: FinanceDaoV2) : FinanceRepositoryV2 {

    override suspend fun recordEvidence(evidence: FinanceRepositoryV2.EvidenceRecord): Boolean =
        dao.insertMessage(
            MessageEntity(
                id = evidence.id, body = evidence.body, sender = evidence.sender,
                receivedAtEpochMs = evidence.receivedAtEpochMs, sourceHash = evidence.sourceHash,
                sourceKind = evidence.sourceKind, sourceVersion = evidence.sourceVersion,
                capturedAtEpochMs = evidence.capturedAtEpochMs,
            )
        ) != -1L

    override fun observeTransactions(): Flow<List<FinanceRepositoryV2.TransactionRow>> =
        dao.observeTransactions().map { list -> list.map { it.toRow() } }

    override suspend fun postTransaction(
        txn: FinanceRepositoryV2.TransactionRow,
        entry: FinanceRepositoryV2.LedgerEntryRow,
    ): Boolean {
        val inserted = dao.insertTransaction(txn.toEntity()) != -1L
        if (inserted) {
            dao.insertLedgerEntry(
                LedgerEntryEntity(
                    id = entry.id, transactionId = entry.transactionId, accountId = entry.accountId,
                    direction = entry.direction, amountMinor = entry.amountMinor, currencyCode = entry.currencyCode,
                )
            )
        }
        return inserted
    }

    override suspend fun addAccount(account: FinanceRepositoryV2.AccountRow): Boolean =
        dao.insertAccount(
            AccountEntity(
                id = account.id, name = account.name, normalizedName = account.normalizedName,
                currencyCode = account.currencyCode, accountType = account.accountType,
                createdAtEpochMs = account.createdAtEpochMs, lifecycle = account.lifecycle,
                nickname = account.nickname, last4 = account.last4,
                institutionName = account.institutionName,
            )
        ) != -1L

    override suspend fun addCategory(category: FinanceRepositoryV2.CategoryRow): Boolean =
        dao.insertCategory(
            CategoryEntity(
                id = category.id, name = category.name,
                normalizedName = category.normalizedName, parentId = category.parentId,
            )
        ) != -1L

    override suspend fun linkTransfer(transfer: FinanceRepositoryV2.TransferRow): Boolean =
        dao.insertTransfer(
            TransferEntity(
                id = transfer.id, fromEntryId = transfer.fromEntryId, toEntryId = transfer.toEntryId,
                kind = transfer.kind, sourceKind = transfer.sourceKind, sourceVersion = transfer.sourceVersion,
            )
        ) != -1L

    override suspend fun applyUserCorrection(
        txnId: String, amountMinor: Long, currencyCode: String,
        counterparty: String?, categoryId: String?, reason: String?, atEpochMs: Long,
    ) {
        val existing = dao.getTransaction(txnId) ?: return
        dao.applyUserCorrection(
            id = txnId,
            amountMinor = amountMinor,
            currencyCode = currencyCode,
            counterparty = counterparty,
            counterpartyNormalized = counterparty?.lowercase()?.trim(),
            categoryId = categoryId ?: existing.categoryId,
            state = existing.state,
            correctionKind = "USER_CORRECTION",
            correctionVersion = "user-v1",
            correctionReason = reason,
            correctionAt = atEpochMs,
        )
        audit(
            FinanceRepositoryV2.AuditEventRow(
                id = UUID.randomUUID().toString(), entityId = txnId, entityType = "transaction",
                action = "CORRECTED", actor = "USER", detailReason = reason, atEpochMs = atEpochMs,
            )
        )
    }

    override suspend fun enqueueJob(job: FinanceRepositoryV2.ProcessingJobRow): Boolean =
        dao.insertJob(
            ProcessingJobEntity(
                id = job.id, jobIdentity = job.jobIdentity, jobType = job.jobType,
                payloadRef = job.payloadRef, status = job.status, attempts = job.attempts,
                maxAttempts = job.maxAttempts, lastError = job.lastError,
                nextAttemptAtEpochMs = job.nextAttemptAtEpochMs,
            )
        ) != -1L

    override suspend fun dueJobs(nowEpochMs: Long, limit: Int): List<FinanceRepositoryV2.ProcessingJobRow> =
        dao.dueJobs("PENDING", nowEpochMs, limit).map {
            FinanceRepositoryV2.ProcessingJobRow(
                id = it.id, jobIdentity = it.jobIdentity, jobType = it.jobType, payloadRef = it.payloadRef,
                status = it.status, attempts = it.attempts, maxAttempts = it.maxAttempts,
                lastError = it.lastError, nextAttemptAtEpochMs = it.nextAttemptAtEpochMs,
            )
        }

    override suspend fun reportJobProgress(jobIdentity: String, status: String, error: String?, nextAttemptAt: Long) =
        dao.updateJobProgress(jobIdentity, status, error, nextAttemptAt)

    override suspend fun audit(event: FinanceRepositoryV2.AuditEventRow) =
        dao.insertAuditEvent(
            AuditEventEntity(
                id = event.id, entityId = event.entityId, entityType = event.entityType,
                action = event.action, actor = event.actor, detailReason = event.detailReason,
                atEpochMs = event.atEpochMs,
            )
        )

    override suspend fun transactionsForAccountBetween(accountId: String, fromDay: Long, toDay: Long): List<FinanceRepositoryV2.TransactionRow> =
        dao.transactionsForAccountBetween(accountId, fromDay, toDay).map { it.toRow() }

    // ---- v3: account authority ----

    override fun observeAccounts(): Flow<List<FinanceRepositoryV2.AccountRow>> =
        dao.observeAccounts().map { list -> list.map { it.toRow() } }

    override suspend fun getAccount(accountId: String): FinanceRepositoryV2.AccountRow? =
        dao.getAccount(accountId)?.toRow()

    override suspend fun updateAccount(account: FinanceRepositoryV2.AccountRow) {
        dao.updateAccount(
            id = account.id, name = account.name, normalizedName = account.normalizedName,
            currencyCode = account.currencyCode, accountType = account.accountType,
            lifecycle = account.lifecycle, nickname = account.nickname,
            last4 = account.last4, institutionName = account.institutionName,
        )
        audit(
            FinanceRepositoryV2.AuditEventRow(
                id = UUID.randomUUID().toString(), entityId = account.id, entityType = "account",
                action = "CORRECTED", actor = "USER", detailReason = null,
                atEpochMs = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun archiveAccount(accountId: String) {
        dao.getAccount(accountId)?.let { dao.updateAccount(
            id = it.id, name = it.name, normalizedName = it.normalizedName,
            currencyCode = it.currencyCode, accountType = it.accountType,
            lifecycle = "ARCHIVED", nickname = it.nickname, last4 = it.last4,
            institutionName = it.institutionName,
        ) }
    }

    override suspend fun restoreAccount(accountId: String) {
        dao.getAccount(accountId)?.let { dao.updateAccount(
            id = it.id, name = it.name, normalizedName = it.normalizedName,
            currencyCode = it.currencyCode, accountType = it.accountType,
            lifecycle = "ACTIVE", nickname = it.nickname, last4 = it.last4,
            institutionName = it.institutionName,
        ) }
    }

    override suspend fun setOpeningBalance(ob: FinanceRepositoryV2.OpeningBalanceRow): Boolean =
        dao.insertOpeningBalance(
            AccountOpeningBalanceEntity(
                id = ob.id, accountId = ob.accountId, amountMinor = ob.amountMinor,
                currencyCode = ob.currencyCode, asOfEpochMs = ob.asOfEpochMs,
            )
        ) != -1L

    override suspend fun recordBalanceSnapshot(snapshot: FinanceRepositoryV2.BalanceSnapshotRow): Boolean =
        dao.recordSnapshotWithAudit(
            BalanceSnapshotEntity(
                id = snapshot.id, accountId = snapshot.accountId,
                amountMinor = snapshot.amountMinor, currencyCode = snapshot.currencyCode,
                kind = snapshot.kind, messageId = snapshot.messageId,
                capturedAtEpochMs = snapshot.capturedAtEpochMs,
                sourceKind = snapshot.sourceKind, sourceVersion = snapshot.sourceVersion,
                snapshotIdentity = snapshot.snapshotIdentity,
            ),
            AuditEventEntity(
                id = UUID.randomUUID().toString(), entityId = snapshot.accountId,
                entityType = "account", action = "SNAPSHOT_RECORDED", actor = "SYSTEM",
                detailReason = snapshot.kind, atEpochMs = snapshot.capturedAtEpochMs,
            ),
        )

    override suspend fun snapshotsForAccount(accountId: String): List<FinanceRepositoryV2.BalanceSnapshotRow> =
        dao.snapshotsForAccount(accountId).map {
            FinanceRepositoryV2.BalanceSnapshotRow(
                id = it.id, accountId = it.accountId, amountMinor = it.amountMinor,
                currencyCode = it.currencyCode, kind = it.kind, messageId = it.messageId,
                capturedAtEpochMs = it.capturedAtEpochMs, sourceKind = it.sourceKind,
                sourceVersion = it.sourceVersion, snapshotIdentity = it.snapshotIdentity,
            )
        }

    override suspend fun latestSnapshot(accountId: String): FinanceRepositoryV2.BalanceSnapshotRow? =
        dao.latestSnapshot(accountId)?.let {
            FinanceRepositoryV2.BalanceSnapshotRow(
                id = it.id, accountId = it.accountId, amountMinor = it.amountMinor,
                currencyCode = it.currencyCode, kind = it.kind, messageId = it.messageId,
                capturedAtEpochMs = it.capturedAtEpochMs, sourceKind = it.sourceKind,
                sourceVersion = it.sourceVersion, snapshotIdentity = it.snapshotIdentity,
            )
        }

    override suspend fun ledgerEntriesForAccount(accountId: String): List<FinanceRepositoryV2.LedgerEntryRow> =
        dao.ledgerEntriesForAccount(accountId).map {
            FinanceRepositoryV2.LedgerEntryRow(
                id = it.id, transactionId = it.transactionId, accountId = it.accountId,
                direction = it.direction, amountMinor = it.amountMinor, currencyCode = it.currencyCode,
            )
        }

    override suspend fun proposeSenderMapping(mapping: FinanceRepositoryV2.SenderMappingRow): Boolean =
        dao.insertSenderMapping(
            SenderAccountMappingEntity(
                id = mapping.id, senderId = mapping.senderId, accountId = mapping.accountId,
                confirmedByUser = mapping.confirmedByUser, sourceKind = mapping.sourceKind,
                sourceVersion = mapping.sourceVersion, createdAtEpochMs = mapping.createdAtEpochMs,
            )
        ) != -1L

    override suspend fun confirmSenderMapping(senderId: String, accountId: String) {
        dao.confirmSenderMapping(senderId, accountId)
        audit(
            FinanceRepositoryV2.AuditEventRow(
                id = UUID.randomUUID().toString(), entityId = accountId, entityType = "account",
                action = "SENDER_MAPPING_CONFIRMED", actor = "USER",
                detailReason = null, atEpochMs = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun confirmedAccountsForSender(senderId: String): List<String> =
        dao.confirmedMappingsForSender(senderId).map { it.accountId }

    override suspend fun learnInstitutionAlias(
        aliasRaw: String, canonicalInstitution: String, confirmedByUser: Boolean,
    ): Boolean = dao.insertInstitutionAlias(
        InstitutionAliasEntity(
            id = UUID.randomUUID().toString(), aliasRaw = aliasRaw,
            aliasNormalized = aliasRaw.trim().lowercase().replace(Regex("\\s+"), " "),
            canonicalInstitution = canonicalInstitution, confirmedByUser = confirmedByUser,
        )
    ) != -1L

    override suspend fun confirmedAliases(): List<FinanceRepositoryV2.AliasRow> =
        dao.confirmedAliases().map { FinanceRepositoryV2.AliasRow(it.aliasRaw, it.canonicalInstitution, true) }

    private fun AccountEntity.toRow() = FinanceRepositoryV2.AccountRow(
        id = id, name = name, normalizedName = normalizedName, currencyCode = currencyCode,
        accountType = accountType, createdAtEpochMs = createdAtEpochMs, lifecycle = lifecycle,
        nickname = nickname, last4 = last4, institutionName = institutionName,
    )

    private fun TransactionEntity.toRow() = FinanceRepositoryV2.TransactionRow(
        id = id, messageId = messageId, accountId = accountId, categoryId = categoryId,
        amountMinor = amountMinor, currencyCode = currencyCode,
        occurredAtEpochMs = occurredAtEpochMs, localDateEpochDay = localDateEpochDay,
        counterparty = counterparty, counterpartyNormalized = counterpartyNormalized, referenceId = referenceId,
        state = state, sourceKind = sourceKind, sourceVersion = sourceVersion, sourceReason = sourceReason,
        correctionSourceKind = correctionSourceKind, correctionSourceVersion = correctionSourceVersion,
        correctionSourceReason = correctionSourceReason, correctionCapturedAtEpochMs = correctionCapturedAtEpochMs,
        dedupeKey = dedupeKey,
    )

    private fun FinanceRepositoryV2.TransactionRow.toEntity() = TransactionEntity(
        id = id, messageId = messageId, accountId = accountId, categoryId = categoryId,
        amountMinor = amountMinor, currencyCode = currencyCode,
        occurredAtEpochMs = occurredAtEpochMs, localDateEpochDay = localDateEpochDay,
        counterparty = counterparty, counterpartyNormalized = counterpartyNormalized, referenceId = referenceId,
        state = state, sourceKind = sourceKind, sourceVersion = sourceVersion, sourceReason = sourceReason,
        correctionSourceKind = correctionSourceKind, correctionSourceVersion = correctionSourceVersion,
        correctionSourceReason = correctionSourceReason, correctionCapturedAtEpochMs = correctionCapturedAtEpochMs,
        dedupeKey = dedupeKey,
    )
}
