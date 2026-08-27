package com.example.fintrack.data.repository

import com.example.fintrack.data.db.FinanceDaoV4
import com.example.fintrack.data.db.RefundLinkEntity
import com.example.fintrack.data.db.TransactionLinkEntity
import com.example.fintrack.domain.model.RefundLink
import com.example.fintrack.domain.model.TransactionLink
import com.example.fintrack.domain.service.RefundSink

/**
 * Room-backed [RefundSink] for the P11 refund service. Inserts are
 * idempotent on the unique (refundedEventId, refundEventId) and
 * (parentEventId, childEventId, role) indexes; the linkIdentity unique
 * index catches accidental re-inserts.
 */
class RoomRefundRepository(private val dao: FinanceDaoV4) : RefundSink {

    override suspend fun insertRefundLink(link: RefundLink, refundIdentity: String) {
        dao.insertRefundLink(
            RefundLinkEntity(
                id = link.id,
                refundedEventId = link.refundedEventId,
                refundEventId = link.refundEventId,
                kind = link.kind.name,
                amountMinor = link.amountMinor,
                currencyCode = link.currencyCode,
                sourceKind = link.provenance.sourceKind.name,
                sourceVersion = link.provenance.sourceVersion,
                sourceReason = link.reason,
                refundIdentity = refundIdentity,
                createdAtEpochMs = link.createdAt.toEpochMilli(),
            )
        )
    }

    override suspend fun insertTransactionLink(link: TransactionLink, linkIdentity: String) {
        dao.insertTransactionLink(
            TransactionLinkEntity(
                id = link.id,
                parentEventId = link.parentEventId,
                childEventId = link.childEventId,
                role = link.role.name,
                sourceKind = link.provenance.sourceKind.name,
                sourceVersion = link.provenance.sourceVersion,
                sourceReason = link.reason,
                linkIdentity = linkIdentity,
                createdAtEpochMs = link.createdAt.toEpochMilli(),
            )
        )
    }
}
