package com.example.fintrack.data.repository

import com.example.fintrack.data.db.AuditEventEntity
import com.example.fintrack.data.db.FinanceDaoV3
import com.example.fintrack.data.db.FinanceDaoV4
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.Money
import com.example.fintrack.domain.model.PostingDirection
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.model.TransactionV6
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.model.TxStatus
import com.example.fintrack.domain.model.TxSubtype
import com.example.fintrack.domain.service.ManualEntrySink
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Room-backed [ManualEntrySink] for the P11 manual-entry service. Reads
 * via [FinanceDaoV3] (same path as the existing P10 detail screen);
 * writes via [FinanceDaoV4] (P11 added `restoreFromTombstone`).
 */
class RoomManualEntryRepository(
    private val daoV3: FinanceDaoV3,
    private val daoV4: FinanceDaoV4,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ManualEntrySink {

    override suspend fun findTransaction(id: String): TransactionV6? =
        daoV3.getTransactionV6(id)?.toDomain()

    override suspend fun restoreFromTombstone(id: String) {
        daoV4.restoreFromTombstone(id)
    }

    override suspend fun appendAudit(
        entityId: String,
        entityType: String,
        action: String,
        actor: String,
        reason: String?,
        atEpochMs: Long,
    ) {
        daoV4.insertAuditEvent(
            AuditEventEntity(
                id = UUID.randomUUID().toString(),
                entityId = entityId,
                entityType = entityType,
                action = action,
                actor = actor,
                detailReason = reason,
                atEpochMs = atEpochMs,
            )
        )
    }

    // ---- mapper (mirrors RoomTransactionWriteRepository.toDomain) ----

    private fun com.example.fintrack.data.db.TransactionEntity.toDomain(): TransactionV6 = TransactionV6(
        id = EntityId(id),
        messageId = messageId?.let { EntityId(it) },
        accountId = EntityId(accountId),
        categoryId = categoryId?.let { EntityId(it) },
        amountMinor = amountMinor,
        currencyCode = currencyCode,
        occurredAt = Instant.ofEpochMilli(occurredAtEpochMs),
        localDate = Instant.ofEpochMilli(occurredAtEpochMs).atZone(zone).toLocalDate(),
        counterparty = counterparty,
        counterpartyNormalized = counterpartyNormalized,
        merchant = merchant,
        description = description,
        referenceId = referenceId,
        cardMask = cardMask,
        rail = rail,
        kind = runCatching { TxKind.valueOf(kind) }.getOrDefault(TxKind.UNKNOWN),
        subtype = subtype?.let { runCatching { TxSubtype.valueOf(it) }.getOrNull() },
        direction = runCatching { PostingDirection.valueOf(state) }.getOrDefault(PostingDirection.DEBIT)
            .let {
                if (amountMinor < 0L) PostingDirection.CREDIT else PostingDirection.DEBIT
            },
        status = runCatching { TxStatus.valueOf(status) }.getOrDefault(TxStatus.POSTED),
        provenance = Provenance(
            sourceKind = runCatching { SourceKind.valueOf(sourceKind) }.getOrDefault(SourceKind.SMS),
            sourceVersion = sourceVersion,
            capturedAt = Instant.ofEpochMilli(occurredAtEpochMs),
        ),
        correctionOrigin = correctionSourceKind?.let {
            Provenance(
                sourceKind = runCatching { SourceKind.valueOf(it) }.getOrDefault(SourceKind.USER_CORRECTION),
                sourceVersion = correctionSourceVersion ?: "user-v1",
                capturedAt = Instant.ofEpochMilli(correctionCapturedAtEpochMs ?: 0L),
            )
        },
        dedupeKey = dedupeKey,
        postingGroupId = postingGroupId,
        transferGroupId = transferGroupId,
        deletedAt = deletedAtEpochMs?.let { Instant.ofEpochMilli(it) },
        deletedReason = deletedReason,
    )
}
