package com.example.fintrack.data.repository

import com.example.fintrack.data.db.CardPaymentEntity
import com.example.fintrack.data.db.CardStatementAdjustmentEntity
import com.example.fintrack.data.db.CardStatementEntity
import com.example.fintrack.data.db.CardStatementLineEntity
import com.example.fintrack.data.db.CreditCardEntity
import com.example.fintrack.data.db.FinanceDaoV5
import com.example.fintrack.data.db.RewardEventEntity
import com.example.fintrack.domain.model.CardPayment
import com.example.fintrack.domain.model.CardStatement
import com.example.fintrack.domain.model.CardStatementAdjustment
import com.example.fintrack.domain.model.CardStatementLine
import com.example.fintrack.domain.model.CreditCard
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.RewardEvent
import com.example.fintrack.domain.service.AdjustmentSink
import com.example.fintrack.domain.service.CardPaymentSink
import com.example.fintrack.domain.service.CardSink
import com.example.fintrack.domain.service.RewardSink
import com.example.fintrack.domain.service.StatementLineSink
import com.example.fintrack.domain.service.StatementSink
import java.time.Instant
import java.time.ZoneId

/**
 * Room-backed persistence for the P12 credit-card services. Every
 * insert goes through the v8 FinanceDaoV5 which enforces unique
 * `*Identity` indices so re-running the services is idempotent.
 */
class RoomCardRepository(
    private val dao: FinanceDaoV5,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : CardSink, StatementSink, StatementLineSink, CardPaymentSink, RewardSink, AdjustmentSink {

    // ---- CardSink ----

    override suspend fun insertCreditCard(card: CreditCard, identity: String) {
        dao.insertCreditCard(card.toEntity(identity))
    }

    // ---- StatementSink ----

    override suspend fun insertStatement(stmt: CardStatement) {
        dao.insertCardStatement(stmt.toEntity())
    }

    // ---- StatementLineSink ----

    override suspend fun insertLine(line: CardStatementLine, cardId: EntityId, identity: String) {
        dao.insertCardStatementLine(line.toEntity(cardId, identity))
    }

    // ---- CardPaymentSink ----

    override suspend fun insertPayment(payment: CardPayment, identity: String) {
        dao.insertCardPayment(payment.toEntity(identity))
    }

    // ---- RewardSink ----

    override suspend fun insertReward(event: RewardEvent, identity: String, reason: String?) {
        dao.insertRewardEvent(event.toEntity(identity, reason))
    }

    // ---- AdjustmentSink ----

    override suspend fun insertAdjustment(adj: CardStatementAdjustment, identity: String) {
        dao.insertCardStatementAdjustment(adj.toEntity(identity))
    }

    // ---- mappers ----

    private fun CreditCard.toEntity(identity: String) = CreditCardEntity(
        id = id.value,
        accountId = accountId.value,
        nickname = nickname,
        cardIdentity = identity,
        issuer = issuer,
        cardMask = cardMask,
        currencyCode = currencyCode,
        lifecycle = lifecycle.name,
        createdAtEpochMs = createdAt.toEpochMilli(),
        creditLimitMinor = creditLimitMinor,
        statementDayOfMonth = statementDayOfMonth,
        statementCycleDays = statementCycleDays,
        dueDayOfMonth = dueDayOfMonth,
        dueDaysAfterStatement = dueDaysAfterStatement,
        rewardPointsBalance = rewardPointsBalance,
    )

    private fun CardStatement.toEntity() = CardStatementEntity(
        id = id.value,
        cardId = cardId.value,
        accountId = accountId.value,
        periodStartEpochDay = periodStart.toEpochDay(),
        periodEndEpochDay = periodEnd.toEpochDay(),
        dueDateEpochDay = dueDate?.toEpochDay(),
        totalDueMinor = totalDueMinor,
        minDueMinor = minDueMinor,
        currencyCode = currencyCode,
        status = status.name,
        statementIdentity = statementIdentity,
        capturedAtEpochMs = capturedAt.toEpochMilli(),
        sourceKind = provenance.sourceKind.name,
        sourceVersion = provenance.sourceVersion,
    )

    private fun CardStatementLine.toEntity(cardId: EntityId, identity: String) = CardStatementLineEntity(
        id = id.value,
        statementId = statementId.value,
        // The line carries the parent statement's cardId so lookups
        // by cardId do not need to JOIN through `card_statements`.
        cardId = cardId.value,
        lineIdentity = identity,
        transactionId = transactionId,
        occurredAtEpochMs = occurredAt.toEpochMilli(),
        localDateEpochDay = localDate.toEpochDay(),
        amountMinor = amountMinor,
        currencyCode = currencyCode,
        direction = direction.name,
        status = status.name,
        merchant = merchant,
        rail = rail,
        cardMask = cardMask,
        referenceId = referenceId,
        sourceKind = provenance.sourceKind.name,
        sourceVersion = provenance.sourceVersion,
    )

    private fun CardPayment.toEntity(identity: String) = CardPaymentEntity(
        id = id.value,
        cardId = cardId.value,
        statementId = statementId?.value,
        fundingAccountId = fundingAccountId.value,
        amountMinor = amountMinor,
        currencyCode = currencyCode,
        occurredAtEpochMs = occurredAt.toEpochMilli(),
        localDateEpochDay = localDate.toEpochDay(),
        paymentStatus = paymentStatus.name,
        referenceId = referenceId,
        paymentIdentity = identity,
        sourceKind = provenance.sourceKind.name,
        sourceVersion = provenance.sourceVersion,
    )

    private fun RewardEvent.toEntity(identity: String, reason: String?) = RewardEventEntity(
        id = id.value,
        cardId = cardId.value,
        accountId = accountId.value,
        statementId = statementId?.value,
        transactionId = transactionId,
        kind = kind.name,
        classification = classification.name,
        cashbackAmountMinor = cashbackAmountMinor,
        pointsDelta = pointsDelta,
        currencyCode = currencyCode,
        occurredAtEpochMs = occurredAt.toEpochMilli(),
        localDateEpochDay = localDate.toEpochDay(),
        sourceKind = provenance.sourceKind.name,
        sourceVersion = provenance.sourceVersion,
        sourceReason = reason,
        rewardIdentity = identity,
        createdAtEpochMs = createdAtOrNow(provenance).toEpochMilli(),
    )

    private fun CardStatementAdjustment.toEntity(identity: String) = CardStatementAdjustmentEntity(
        id = id.value,
        statementId = statementId.value,
        cardId = cardId.value,
        accountId = accountId.value,
        kind = kind.name,
        amountMinor = amountMinor,
        currencyCode = currencyCode,
        direction = direction.name,
        occurredAtEpochMs = occurredAt.toEpochMilli(),
        localDateEpochDay = localDate.toEpochDay(),
        sourceKind = provenance.sourceKind.name,
        sourceVersion = provenance.sourceVersion,
        reason = reason,
        adjustmentIdentity = identity,
        createdAtEpochMs = createdAtOrNow(provenance).toEpochMilli(),
    )

    private fun createdAtOrNow(p: Provenance): Instant = p.capturedAt
}
