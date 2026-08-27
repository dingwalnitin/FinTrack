package com.example.fintrack.data.repository

import com.example.fintrack.data.db.FinanceDaoV3
import com.example.fintrack.data.db.FinanceDaoV4
import com.example.fintrack.data.db.LedgerEntryEntity
import com.example.fintrack.data.db.TransferEntity
import com.example.fintrack.domain.model.TransactionV6
import com.example.fintrack.domain.policy.SinglePosting
import com.example.fintrack.domain.service.PersistedTransfer
import com.example.fintrack.domain.service.TransferSink
import java.time.ZoneId

/**
 * Room-backed [TransferSink] for the P11 transfer service. The atomic
 * write path lives in [FinanceDaoV4.recordTransfer] (a single Room
 * @Transaction) so two-sided transfers are persisted as one logical
 * write — duplicate postings on either side cannot accumulate.
 */
class RoomTransferRepository(
    private val daoV4: FinanceDaoV4,
    private val daoV3: FinanceDaoV3,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : TransferSink {

    override suspend fun recordTransfer(
        source: TransactionV6,
        destination: TransactionV6,
        sourcePosting: SinglePosting,
        destinationPosting: SinglePosting,
        transferId: String,
        transferKind: String,
    ): Result<PersistedTransfer> = runCatching {
        val sourceEntity = source.toEntity()
        val destEntity = destination.toEntity()
        val sourcePostingEntity = LedgerEntryEntity(
            id = sourcePosting.id,
            transactionId = sourcePosting.transactionId,
            accountId = sourcePosting.accountId,
            direction = sourcePosting.direction,
            amountMinor = sourcePosting.amountMinor,
            currencyCode = sourcePosting.currencyCode,
            postingGroupId = sourcePosting.postingGroupId,
            memo = sourcePosting.memo,
        )
        val destPostingEntity = LedgerEntryEntity(
            id = destinationPosting.id,
            transactionId = destinationPosting.transactionId,
            accountId = destinationPosting.accountId,
            direction = destinationPosting.direction,
            amountMinor = destinationPosting.amountMinor,
            currencyCode = destinationPosting.currencyCode,
            postingGroupId = destinationPosting.postingGroupId,
            memo = destinationPosting.memo,
        )
        val transferEntity = TransferEntity(
            id = transferId,
            fromEntryId = sourcePosting.id,
            toEntryId = destinationPosting.id,
            kind = transferKind,
            sourceKind = source.provenance.sourceKind.name,
            sourceVersion = source.provenance.sourceVersion,
        )
        daoV4.recordTransfer(
            txnSource = sourceEntity,
            txnDestination = destEntity,
            entrySource = sourcePostingEntity,
            entryDestination = destPostingEntity,
            transfer = transferEntity,
        )
        PersistedTransfer(
            source = source,
            destination = destination,
            transferEntityId = transferId,
        )
    }

    // ---- mappers ----

    private fun TransactionV6.toEntity() = com.example.fintrack.data.db.TransactionEntity(
        id = id.value,
        messageId = messageId?.value,
        accountId = accountId.value,
        categoryId = categoryId?.value,
        amountMinor = amountMinor,
        currencyCode = currencyCode,
        occurredAtEpochMs = occurredAt.toEpochMilli(),
        localDateEpochDay = localDate.toEpochDay(),
        counterparty = counterparty,
        counterpartyNormalized = counterpartyNormalized,
        referenceId = referenceId,
        state = status.name,
        sourceKind = provenance.sourceKind.name,
        sourceVersion = provenance.sourceVersion,
        sourceReason = null,
        correctionSourceKind = correctionOrigin?.sourceKind?.name,
        correctionSourceVersion = correctionOrigin?.sourceVersion,
        correctionSourceReason = null,
        correctionCapturedAtEpochMs = correctionOrigin?.capturedAt?.toEpochMilli(),
        dedupeKey = dedupeKey,
        kind = kind.name,
        subtype = subtype?.name,
        status = status.name,
        merchant = merchant,
        description = description,
        rail = rail,
        cardMask = cardMask,
        postingGroupId = postingGroupId,
        transferGroupId = transferGroupId,
        deletedAtEpochMs = deletedAt?.toEpochMilli(),
        deletedReason = deletedReason,
    )
}
