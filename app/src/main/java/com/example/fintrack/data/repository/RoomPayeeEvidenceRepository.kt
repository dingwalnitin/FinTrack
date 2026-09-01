package com.example.fintrack.data.repository

import com.example.fintrack.data.db.FinTrackDatabaseV2
import com.example.fintrack.data.db.PayeeCategoryRuleEntity
import com.example.fintrack.data.db.TransactionEvidenceEntity
import com.example.fintrack.domain.service.PayeeEvidenceSink
import com.example.fintrack.domain.service.PayeeIdentity
import com.example.fintrack.domain.service.PayeeRule
import com.example.fintrack.domain.service.TransactionEvidence
import java.util.UUID

/**
 * Room-backed implementation of [PayeeEvidenceSink] (Stage 13 A + D).
 * All writes are idempotent via unique indices.
 */
class RoomPayeeEvidenceRepository(
    private val db: FinTrackDatabaseV2,
) : PayeeEvidenceSink {

    private val dao = db.financeDaoV10()
    private val now: Long get() = System.currentTimeMillis()

    override suspend fun upsertPayeeRule(
        payeeName: String,
        vpa: String?,
        categoryId: String,
        sourceKind: String,
        sourceVersion: String,
    ): Boolean {
        val hash = PayeeIdentity.identityHash(vpa, payeeName)
        val existing = dao.payeeRuleByHash(hash)
        val nowMs = now
        return if (existing == null) {
            dao.insertPayeeRule(
                PayeeCategoryRuleEntity(
                    id = UUID.randomUUID().toString(),
                    payeeIdentityHash = hash,
                    payeeName = payeeName,
                    vpa = vpa,
                    categoryId = categoryId,
                    sourceKind = sourceKind,
                    sourceVersion = sourceVersion,
                    createdAtEpochMs = nowMs,
                    updatedAtEpochMs = nowMs,
                )
            )
            true
        } else {
            dao.updatePayeeRuleCategory(
                payeeIdentityHash = hash,
                categoryId = categoryId,
                sourceKind = sourceKind,
                sourceVersion = sourceVersion,
                vpa = vpa,
                updatedAtEpochMs = nowMs,
            )
            true
        }
    }

    override suspend fun payeeRuleFor(vpa: String?, name: String?): PayeeRule? {
        val hash = PayeeIdentity.identityHash(vpa, name)
        return dao.payeeRuleByHash(hash)?.toDomain()
    }

    override suspend fun allPayeeRules(): List<PayeeRule> =
        dao.allPayeeRules().map { it.toDomain() }

    override suspend fun storeTransactionEvidence(
        transactionId: String,
        sourceMessageId: String,
        rawLlmJson: String?,
    ): Boolean {
        val existing = dao.evidenceByMessage(sourceMessageId)
        if (existing != null) {
            // rawLlmJson is NEVER overwritten by a cache-hit
            if (existing.rawLlmJson != null) return false
        }
        dao.insertTransactionEvidence(
            TransactionEvidenceEntity(
                id = UUID.randomUUID().toString(),
                transactionId = transactionId,
                sourceMessageId = sourceMessageId,
                rawLlmJson = rawLlmJson,
                createdAtEpochMs = now,
            )
        )
        return true
    }

    override suspend fun evidenceFor(transactionId: String): List<TransactionEvidence> =
        dao.evidenceForTransaction(transactionId).map { it.toDomain() }

    private fun PayeeCategoryRuleEntity.toDomain() = PayeeRule(
        payeeIdentityHash = payeeIdentityHash,
        payeeName = payeeName,
        vpa = vpa,
        categoryId = categoryId,
        sourceKind = sourceKind,
    )

    private fun TransactionEvidenceEntity.toDomain() = TransactionEvidence(
        transactionId = transactionId,
        sourceMessageId = sourceMessageId,
        rawLlmJson = rawLlmJson,
    )
}