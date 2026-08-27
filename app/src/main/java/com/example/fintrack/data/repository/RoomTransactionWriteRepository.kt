package com.example.fintrack.data.repository

import com.example.fintrack.data.db.FinanceDaoV3
import com.example.fintrack.data.db.LedgerEntryEntity
import com.example.fintrack.data.db.TransactionEntity
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.Money
import com.example.fintrack.domain.model.PostingDirection
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.model.TransactionV6
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.model.TxStatus
import com.example.fintrack.domain.model.TxSubtype
import com.example.fintrack.domain.policy.SinglePosting
import com.example.fintrack.domain.service.TransactionWriteSink
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Room-backed [TransactionWriteSink] for the P10 transaction/posting
 * write service. All writes go through [FinanceDaoV3.replacePostingGroup]
 * which is a single Room @Transaction so duplicate postings can never
 * accumulate when an event is edited.
 */
class RoomTransactionWriteRepository(
    private val dao: FinanceDaoV3,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : TransactionWriteSink {

    override suspend fun findTransaction(id: String): TransactionV6? =
        dao.getTransactionV6(id)?.toDomain()

    override suspend fun findPostingGroupId(id: String): String? =
        dao.getTransactionV6(id)?.postingGroupId

    override suspend fun replacePostingGroupAndUpsertTxn(
        txn: TransactionV6,
        previousPostingGroupId: String?,
        newPostings: List<SinglePosting>,
    ): Pair<TransactionV6, List<SinglePosting>> {
        val entity = txn.toEntity()
        val postingEntities = newPostings.map { it.toEntity() }
        dao.replacePostingGroup(entity, postingEntities)
        return txn to newPostings
    }

    override suspend fun updateStatusAndTombstone(
        txnId: String,
        status: String,
        deletedAtEpochMs: Long,
        deletedReason: String?,
    ) {
        dao.tombstoneTransaction(
            id = txnId,
            status = status,
            deletedAt = deletedAtEpochMs,
            reason = deletedReason,
        )
    }

    // ---- mappers ----

    private fun TransactionEntity.toDomain(): TransactionV6 = TransactionV6(
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
                // state column is legacy; direction is encoded in amount sign
                // for v5 rows. v6 uses the explicit Postings only. We keep
                // DEBIT as the safe default for legacy rows.
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

    private fun TransactionV6.toEntity(): TransactionEntity = TransactionEntity(
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
        state = status.name, // legacy column; v6 status is authoritative
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

    private fun SinglePosting.toEntity(): LedgerEntryEntity = LedgerEntryEntity(
        id = id,
        transactionId = transactionId,
        accountId = accountId,
        direction = direction,
        amountMinor = amountMinor,
        currencyCode = currencyCode,
        postingGroupId = postingGroupId,
        memo = memo,
    )
}
