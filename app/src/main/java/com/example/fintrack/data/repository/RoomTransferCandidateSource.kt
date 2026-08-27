package com.example.fintrack.data.repository

import com.example.fintrack.data.db.TransactionEntity
import com.example.fintrack.data.db.FinanceDaoV8
import com.example.fintrack.domain.service.TransferCandidateSource

/**
 * Room-backed [TransferCandidateSource] for the candidate matcher.
 *
 * Reads non-deleted transactions within a time window, matching the
 * matcher's requirement for [TransactionEntity] rows. The entity-to-candidate
 * conversion lives in [TransferCandidateMatcher].
 */
class RoomTransferCandidateSource(
    private val dao: FinanceDaoV8,
) : TransferCandidateSource {

    override suspend fun candidatesInWindow(
        accountIds: List<String>,
        fromEpochMs: Long,
        toEpochMs: Long,
    ): List<TransactionEntity> {
        // Query by epoch-day boundaries (deterministic, indexed).
        val fromDay = fromEpochMs / 86_400_000L
        val toDay = toEpochMs / 86_400_000L
        return dao.transactionsBetween(fromDay, toDay)
            .filter { it.status != "DELETED" && it.accountId in accountIds }
    }
}