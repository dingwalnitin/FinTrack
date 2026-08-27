package com.example.fintrack.data.repository

import com.example.fintrack.data.db.FinanceDaoV6
import com.example.fintrack.data.db.ReimbursementLinkEntity
import com.example.fintrack.data.db.ReviewItemEntity
import com.example.fintrack.data.db.TransactionNoteEntity
import com.example.fintrack.data.db.TransactionSplitEntity
import com.example.fintrack.data.db.TransactionTagEntity
import com.example.fintrack.data.db.TravelModeEntity
import com.example.fintrack.domain.model.ReimbursementLink
import com.example.fintrack.domain.model.ReviewItem
import com.example.fintrack.domain.model.ReviewReason
import com.example.fintrack.domain.model.ReviewStatus
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.model.TransactionNote
import com.example.fintrack.domain.model.TransactionSplit
import com.example.fintrack.domain.model.TransactionTag
import com.example.fintrack.domain.model.TravelMode
import com.example.fintrack.domain.model.TravelModeStatus
import com.example.fintrack.domain.service.ReviewSink
import com.example.fintrack.domain.service.SplitSink
import com.example.fintrack.domain.service.TagsNotesSink
import java.time.Instant

/**
 * Room-backed persistence for Stage 7 P15 (review queue, splits,
 * reimbursement links, travel modes, tags, notes). Every insert is
 * idempotent on a stable identity hash / unique index so re-running
 * the services cannot duplicate history.
 */
class RoomReviewRepository(
    private val dao: FinanceDaoV6,
) : ReviewSink, SplitSink, TagsNotesSink {

    // ---- ReviewSink ----

    override suspend fun insertReviewItem(item: ReviewItem): Boolean =
        dao.insertReviewItem(
            ReviewItemEntity(
                id = item.id,
                transactionId = item.transactionId,
                reason = item.reason.name,
                priority = item.priority,
                status = item.status.name,
                createdAtEpochMs = item.createdAtEpochMs,
                resolvedAtEpochMs = item.resolvedAtEpochMs,
                explanation = item.explanation,
                sourceKind = item.sourceKind,
                sourceVersion = item.sourceVersion,
            )
        ) != -1L

    override suspend fun updateReviewItemStatus(id: String, status: String, atMs: Long?): Boolean =
        dao.updateReviewItemStatus(id, status, atMs) > 0

    override suspend fun openReviewItems(): List<ReviewItem> =
        dao.reviewItemsByStatus(ReviewStatus.OPEN.name).map { it.toDomain() }

    override suspend fun openReviewItemsForTransaction(transactionId: String): List<ReviewItem> =
        dao.openReviewItemsForTransaction(transactionId).map { it.toDomain() }

    private fun ReviewItemEntity.toDomain() = ReviewItem(
        id = id,
        transactionId = transactionId,
        reason = runCatching { ReviewReason.valueOf(reason) }.getOrDefault(ReviewReason.UNRESOLVED),
        priority = priority,
        status = runCatching { ReviewStatus.valueOf(status) }.getOrDefault(ReviewStatus.OPEN),
        createdAtEpochMs = createdAtEpochMs,
        resolvedAtEpochMs = resolvedAtEpochMs,
        explanation = explanation,
        sourceKind = sourceKind,
        sourceVersion = sourceVersion,
    )

    // ---- SplitSink ----

    override suspend fun applySplits(links: List<TransactionSplit>) {
        val rows = links.map {
            TransactionSplitEntity(
                id = it.id,
                parentTransactionId = it.parentTransactionId,
                childTransactionId = it.childTransactionId,
                splitIdentity = it.splitIdentity,
                sourceKind = it.sourceKind.name,
                sourceVersion = it.sourceVersion,
                createdAtEpochMs = it.createdAtEpochMs,
            )
        }
        dao.applySplits(rows)
    }

    // ---- TagsNotesSink ----

    override suspend fun insertTransactionTag(tag: TransactionTag): Boolean =
        dao.insertTransactionTag(
            TransactionTagEntity(
                id = tag.id,
                transactionId = tag.transactionId,
                tag = tag.tag,
                sourceKind = tag.sourceKind.name,
                sourceVersion = tag.sourceVersion,
                createdAtEpochMs = tag.createdAtEpochMs,
            )
        ) != -1L

    override suspend fun deleteTransactionTag(transactionId: String, tag: String): Boolean =
        dao.deleteTransactionTag(transactionId, tag) > 0

    override suspend fun tagsForTransaction(transactionId: String): List<TransactionTag> =
        dao.tagsForTransaction(transactionId).map {
            TransactionTag(
                id = it.id,
                transactionId = it.transactionId,
                tag = it.tag,
                sourceKind = runCatching { SourceKind.valueOf(it.sourceKind) }.getOrDefault(SourceKind.USER_CORRECTION),
                sourceVersion = it.sourceVersion,
                createdAtEpochMs = it.createdAtEpochMs,
            )
        }

    override suspend fun upsertTransactionNote(note: TransactionNote): Boolean =
        dao.insertTransactionNote(
            TransactionNoteEntity(
                id = note.id,
                transactionId = note.transactionId,
                note = note.note,
                sourceKind = note.sourceKind.name,
                sourceVersion = note.sourceVersion,
                createdAtEpochMs = note.createdAtEpochMs,
                updatedAtEpochMs = note.updatedAtEpochMs,
            )
        ) != -1L

    override suspend fun latestNoteForTransaction(transactionId: String): TransactionNote? =
        dao.latestNoteForTransaction(transactionId)?.let {
            TransactionNote(
                id = it.id,
                transactionId = it.transactionId,
                note = it.note,
                sourceKind = runCatching { SourceKind.valueOf(it.sourceKind) }.getOrDefault(SourceKind.USER_CORRECTION),
                sourceVersion = it.sourceVersion,
                createdAtEpochMs = it.createdAtEpochMs,
                updatedAtEpochMs = it.updatedAtEpochMs,
            )
        }

    override suspend fun insertReimbursementLink(link: ReimbursementLink): Boolean =
        dao.insertReimbursementLink(
            ReimbursementLinkEntity(
                id = link.id,
                expenseTransactionId = link.expenseTransactionId,
                reimbursingTransactionId = link.reimbursingTransactionId,
                linkIdentity = link.linkIdentity,
                sourceKind = link.sourceKind.name,
                sourceVersion = link.sourceVersion,
                createdAtEpochMs = link.createdAtEpochMs,
            )
        ) != -1L

    override suspend fun reimbursementsForExpense(expenseTransactionId: String): List<ReimbursementLink> =
        dao.reimbursementsForExpense(expenseTransactionId).map {
            ReimbursementLink(
                id = it.id,
                expenseTransactionId = it.expenseTransactionId,
                reimbursingTransactionId = it.reimbursingTransactionId,
                linkIdentity = it.linkIdentity,
                sourceKind = runCatching { SourceKind.valueOf(it.sourceKind) }.getOrDefault(SourceKind.USER_CORRECTION),
                sourceVersion = it.sourceVersion,
                createdAtEpochMs = it.createdAtEpochMs,
            )
        }

    override suspend fun insertTravelMode(mode: TravelMode): Boolean =
        dao.insertTravelMode(
            TravelModeEntity(
                id = mode.id,
                accountId = mode.accountId.value,
                label = mode.label,
                currencyCode = mode.currencyCode,
                startEpochDay = mode.startEpochDay,
                endEpochDay = mode.endEpochDay,
                status = mode.status.name,
                sourceKind = mode.sourceKind.name,
                sourceVersion = mode.sourceVersion,
                createdAtEpochMs = mode.createdAtEpochMs,
            )
        ) != -1L

    override suspend fun updateTravelModeStatus(id: String, status: String, endDay: Long?): Boolean =
        dao.updateTravelModeStatus(id, status, endDay) > 0

    override suspend fun activeTravelModesFor(accountId: String): List<TravelMode> =
        dao.travelModesForAccountInStatus(accountId, TravelModeStatus.ACTIVE.name).map {
            TravelMode(
                id = it.id,
                accountId = com.example.fintrack.domain.model.EntityId(it.accountId),
                label = it.label,
                currencyCode = it.currencyCode,
                startEpochDay = it.startEpochDay,
                endEpochDay = it.endEpochDay,
                status = runCatching { TravelModeStatus.valueOf(it.status) }.getOrDefault(TravelModeStatus.ACTIVE),
                sourceKind = runCatching { SourceKind.valueOf(it.sourceKind) }.getOrDefault(SourceKind.MANUAL_ENTRY),
                sourceVersion = it.sourceVersion,
                createdAtEpochMs = it.createdAtEpochMs,
            )
        }
}
