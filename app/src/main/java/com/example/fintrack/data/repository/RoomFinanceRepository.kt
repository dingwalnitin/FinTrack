package com.example.fintrack.data.repository

import com.example.fintrack.data.db.FinanceDaoV2
import com.example.fintrack.data.db.TransactionEntity
import com.example.fintrack.domain.FinanceRepository
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.LifecycleState
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.model.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Legacy v1 facade retained for compatibility with earlier-stage code/tests.
 * Delegates to the v2 DAO; new features should use FinanceRepositoryV2.
 */
class RoomFinanceRepository(private val dao: FinanceDaoV2) : FinanceRepository {

    override fun observeTransactions(): Flow<List<Transaction>> =
        dao.observeTransactions().map { list -> list.map { it.toDomain() } }
}

fun TransactionEntity.toDomain(): Transaction = Transaction(
    id = EntityId(id),
    messageId = messageId?.let { EntityId(it) } ?: EntityId("unknown"),
    amount = com.example.fintrack.domain.model.Money(amountMinor, currencyCode),
    occurredAt = Instant.ofEpochMilli(occurredAtEpochMs),
    counterparty = counterparty,
    state = LifecycleState.valueOf(state),
    provenance = com.example.fintrack.domain.model.Provenance(
        sourceKind = SourceKind.valueOf(sourceKind),
        sourceVersion = sourceVersion,
        capturedAt = Instant.ofEpochMilli(occurredAtEpochMs),
    ),
    correctionOrigin = correctionSourceKind?.let {
        com.example.fintrack.domain.model.Provenance(
            sourceKind = SourceKind.valueOf(it),
            sourceVersion = correctionSourceVersion ?: "unknown",
            capturedAt = Instant.ofEpochMilli(correctionCapturedAtEpochMs ?: 0L),
        )
    },
    // Sign is encoded in the semantic kind (amountMinor is always absolute);
    // EXPENSE/FEE/TRANSFER/CASH_MOVE are outflows (debit).
    directionDebit = kind == "EXPENSE" || kind == "FEE" || kind == "TRANSFER" || kind == "CASH_MOVE",
)

private typealias Instant = java.time.Instant