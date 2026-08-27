package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.AccountType
import com.example.fintrack.domain.model.AdjustmentKind
import com.example.fintrack.domain.model.CardLifecycle
import com.example.fintrack.domain.model.CardLineStatus
import com.example.fintrack.domain.model.CardPayment
import com.example.fintrack.domain.model.CardPaymentStatus
import com.example.fintrack.domain.model.CardStatement
import com.example.fintrack.domain.model.CardStatementAdjustment
import com.example.fintrack.domain.model.CardStatementLine
import com.example.fintrack.domain.model.CreditCard
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.PostingDirection
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.RewardClassification
import com.example.fintrack.domain.model.RewardEvent
import com.example.fintrack.domain.model.RewardKind
import com.example.fintrack.domain.model.StatementStatus
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Stage 6 P12: credit-card transaction + statement + payment + rewards
 * services.
 *
 * Design:
 *  - All services are pure domain logic. Persistence goes through
 *    [CardSink] / [StatementSink] / [RewardSink] / [AdjustmentSink]
 *    so the data layer can be swapped (Room, fake, instrumentation).
 *  - Every write is idempotent: identity hashes are computed from
 *    stable inputs and a unique index in the data layer catches
 *    duplicates from a parser re-run.
 *  - The funding account on a card payment is REQUIRED. The card
 *    payment is modelled as a single logical event that touches two
 *    accounts (settlement); the rail analytics layer reads `kind !=
 *    TRANSFER` for spend metrics so a card payment is NEVER counted
 *    as a second expense.
 *  - Reward events are distinct from refunds. A cashback row is
 *    classified BENEFIT and reduces the amount owed; a refund row
 *    is classified REFUND and links to the original expense.
 *  - Statement adjustments (late fee, interest, fee reversal, goodwill
 *    credit) are explicit events linked to a statement. The original
 *    transactions are never mutated.
 *  - Pending vs posted distinction is preserved: a swipe at the
 *    start of the cycle lands as a PENDING line and is promoted to
 *    POSTED only when the same charge appears as a posted event.
 */
class CardTransactionService(
    private val cardSink: CardSink,
    private val statementSink: StatementSink,
    private val lineSink: StatementLineSink,
    private val paymentSink: CardPaymentSink,
    private val rewardSink: RewardSink,
    private val adjustmentSink: AdjustmentSink,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    // ---- Cards ----

    /**
     * Register a credit card. The cardId returned is the durable UUID;
     * the [CardIdentity] hash is a stable, dedupe-friendly second key.
     * Idempotent: re-running with the same inputs returns the existing
     * row.
     */
    suspend fun registerCard(
        accountId: EntityId,
        accountType: AccountType,
        nickname: String,
        currencyCode: String,
        issuer: String?,
        cardMask: String?,
        creditLimitMinor: Long?,
        statementDayOfMonth: Int?,
        statementCycleDays: Int?,
        dueDayOfMonth: Int?,
        dueDaysAfterStatement: Int?,
        rewardPointsBalance: Long?,
        provenance: Provenance,
    ): Result<CreditCard> {
        if (accountType != AccountType.CREDIT_CARD && accountType != AccountType.OTHER_LIABILITY) {
            return Result.failure(IllegalArgumentException("accountType must be CREDIT_CARD or OTHER_LIABILITY"))
        }
        if (currencyCode.length != 3) {
            return Result.failure(IllegalArgumentException("currencyCode must be ISO-4217"))
        }
        if (cardMask != null && (cardMask.length != 4 || !cardMask.all { it.isDigit() })) {
            return Result.failure(IllegalArgumentException("cardMask must be exactly 4 digits or null"))
        }
        if (creditLimitMinor != null && creditLimitMinor < 0) {
            return Result.failure(IllegalArgumentException("creditLimitMinor must be >= 0"))
        }

        val now = clock()
        val id = EntityId.generate()
        val identity = cardIdentityFor(accountId, nickname, issuer, cardMask)
        val card = CreditCard(
            id = id,
            accountId = accountId,
            nickname = nickname,
            issuer = issuer,
            cardMask = cardMask,
            currencyCode = currencyCode,
            lifecycle = CardLifecycle.ACTIVE,
            createdAt = now,
            creditLimitMinor = creditLimitMinor,
            statementDayOfMonth = statementDayOfMonth,
            statementCycleDays = statementCycleDays,
            dueDayOfMonth = dueDayOfMonth,
            dueDaysAfterStatement = dueDaysAfterStatement,
            rewardPointsBalance = rewardPointsBalance,
        )
        cardSink.insertCreditCard(card, identity)
        return Result.success(card)
    }

    // ---- Statement lines (pending / posted) ----

    /**
     * Record a card statement line. The same swipe can arrive twice
     * (pending SMS then posted SMS); the matcher is responsible for
     * collapsing, but the [lineIdentity] hash is what makes the
     * insert idempotent.
     */
    suspend fun recordLine(
        statementId: EntityId,
        cardId: EntityId,
        transactionId: String?,
        occurredAt: Instant,
        amountMinor: Long,
        currencyCode: String,
        direction: PostingDirection,
        status: CardLineStatus,
        merchant: String?,
        rail: String?,
        cardMask: String?,
        referenceId: String?,
        provenance: Provenance,
    ): Result<CardStatementLine> {
        if (amountMinor <= 0) {
            return Result.failure(IllegalArgumentException("amountMinor must be > 0"))
        }
        if (currencyCode.length != 3) {
            return Result.failure(IllegalArgumentException("currencyCode must be ISO-4217"))
        }
        if (status == CardLineStatus.PENDING && transactionId != null) {
            return Result.failure(IllegalArgumentException("PENDING lines must not link a transactionId"))
        }
        val localDate = occurredAt.atZone(zone).toLocalDate()
        val identity = lineIdentityFor(
            statementId, transactionId, amountMinor, direction, occurredAt,
        )
        val line = CardStatementLine(
            id = EntityId.generate(),
            statementId = statementId,
            transactionId = transactionId,
            occurredAt = occurredAt,
            localDate = localDate,
            amountMinor = amountMinor,
            currencyCode = currencyCode,
            direction = direction,
            status = status,
            merchant = merchant,
            rail = rail,
            cardMask = cardMask,
            referenceId = referenceId,
            provenance = provenance,
        )
        lineSink.insertLine(line, cardId, identity)
        return Result.success(line)
    }

    // ---- Statements ----

    /**
     * Open a new statement. Idempotent on
     * sha-256(cardId | periodStart | periodEnd): the same period on
     * the same card never creates a second statement.
     */
    suspend fun openStatement(
        cardId: EntityId,
        accountId: EntityId,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        dueDate: LocalDate?,
        totalDueMinor: Long,
        minDueMinor: Long?,
        currencyCode: String,
        provenance: Provenance,
    ): Result<CardStatement> {
        if (totalDueMinor < 0) {
            return Result.failure(IllegalArgumentException("totalDueMinor must be >= 0"))
        }
        if (minDueMinor != null && minDueMinor > totalDueMinor) {
            return Result.failure(IllegalArgumentException("minDueMinor > totalDueMinor"))
        }
        if (periodEnd.isBefore(periodStart)) {
            return Result.failure(IllegalArgumentException("periodEnd < periodStart"))
        }
        val identity = statementIdentityFor(cardId, periodStart, periodEnd)
        val now = clock()
        val stmt = CardStatement(
            id = EntityId.generate(),
            cardId = cardId,
            accountId = accountId,
            periodStart = periodStart,
            periodEnd = periodEnd,
            dueDate = dueDate,
            totalDueMinor = totalDueMinor,
            minDueMinor = minDueMinor,
            currencyCode = currencyCode,
            status = StatementStatus.OPEN,
            statementIdentity = identity,
            capturedAt = now,
            provenance = provenance,
        )
        statementSink.insertStatement(stmt)
        return Result.success(stmt)
    }

    // ---- Card payments (liability settlement) ----

    /**
     * Record a card payment. The payment is a single logical event
     * that touches two accounts (card and funding); the data-layer
     * sink is responsible for emitting the two postings. We never
     * create an expense event here.
     */
    suspend fun recordPayment(
        cardId: EntityId,
        statementId: EntityId?,
        fundingAccountId: EntityId,
        amountMinor: Long,
        currencyCode: String,
        occurredAt: Instant,
        referenceId: String?,
        provenance: Provenance,
    ): Result<CardPayment> {
        if (amountMinor <= 0) {
            return Result.failure(IllegalArgumentException("amountMinor must be > 0"))
        }
        if (currencyCode.length != 3) {
            return Result.failure(IllegalArgumentException("currencyCode must be ISO-4217"))
        }
        if (cardId == fundingAccountId) {
            return Result.failure(IllegalArgumentException("fundingAccountId cannot equal cardId"))
        }
        val identity = paymentIdentityFor(cardId, statementId, fundingAccountId, amountMinor, occurredAt)
        val payment = CardPayment(
            id = EntityId.generate(),
            cardId = cardId,
            statementId = statementId,
            fundingAccountId = fundingAccountId,
            amountMinor = amountMinor,
            currencyCode = currencyCode,
            occurredAt = occurredAt,
            localDate = occurredAt.atZone(zone).toLocalDate(),
            paymentStatus = CardPaymentStatus.POSTED,
            referenceId = referenceId,
            provenance = provenance,
        )
        paymentSink.insertPayment(payment, identity)
        return Result.success(payment)
    }

    // ---- Rewards ----

    /**
     * Record a reward event. [kind]=CASHBACK/VOUCHER require a positive
     * [cashbackAmountMinor]; [kind]=REWARD_POINTS require a (possibly
     * negative on reversal) [pointsDelta]. Cashback on a credit card
     * is BENEFIT, not a refund, and never downgrades the original
     * expense.
     */
    suspend fun recordReward(
        cardId: EntityId,
        accountId: EntityId,
        statementId: EntityId?,
        transactionId: String?,
        kind: RewardKind,
        cashbackAmountMinor: Long?,
        pointsDelta: Long?,
        currencyCode: String,
        occurredAt: Instant,
        reason: String?,
        provenance: Provenance,
    ): Result<RewardEvent> {
        if (currencyCode.length != 3) {
            return Result.failure(IllegalArgumentException("currencyCode must be ISO-4217"))
        }
        when (kind) {
            RewardKind.CASHBACK, RewardKind.VOUCHER -> {
                if (cashbackAmountMinor == null || cashbackAmountMinor <= 0) {
                    return Result.failure(IllegalArgumentException("cashback/voucher requires cashbackAmountMinor > 0"))
                }
            }
            RewardKind.REWARD_POINTS -> {
                if (pointsDelta == null) {
                    return Result.failure(IllegalArgumentException("reward-points requires pointsDelta"))
                }
            }
            RewardKind.OTHER -> Unit
        }
        val identity = rewardIdentityFor(cardId, kind, cashbackAmountMinor, pointsDelta, occurredAt, statementId)
        val event = RewardEvent(
            id = EntityId.generate(),
            cardId = cardId,
            accountId = accountId,
            statementId = statementId,
            transactionId = transactionId,
            kind = kind,
            classification = RewardClassification.BENEFIT,
            cashbackAmountMinor = cashbackAmountMinor,
            pointsDelta = pointsDelta,
            currencyCode = currencyCode,
            occurredAt = occurredAt,
            localDate = occurredAt.atZone(zone).toLocalDate(),
            provenance = provenance,
            // `reason` is kept on the event via the sink; the domain
            // RewardEvent model intentionally does not carry a free-form
            // reason field. Provenance already records the SMS / LLM trail.
        )
        rewardSink.insertReward(event, identity, reason)
        return Result.success(event)
    }

    // ---- Statement adjustments (late fee / interest / reversal / goodwill) ----

    /**
     * Record an explicit adjustment. LATE_FEE / INTEREST typically carry
     * DEBIT (added to bill); FEE_REVERSAL / GOODWILL_CREDIT typically
     * carry CREDIT. The original transactions are never mutated.
     */
    suspend fun recordAdjustment(
        statementId: EntityId,
        cardId: EntityId,
        accountId: EntityId,
        kind: AdjustmentKind,
        amountMinor: Long,
        currencyCode: String,
        direction: PostingDirection,
        occurredAt: Instant,
        reason: String?,
        provenance: Provenance,
    ): Result<CardStatementAdjustment> {
        if (amountMinor <= 0) {
            return Result.failure(IllegalArgumentException("amountMinor must be > 0"))
        }
        if (currencyCode.length != 3) {
            return Result.failure(IllegalArgumentException("currencyCode must be ISO-4217"))
        }
        val identity = adjustmentIdentityFor(statementId, kind, amountMinor, direction, occurredAt)
        val adj = CardStatementAdjustment(
            id = EntityId.generate(),
            statementId = statementId,
            cardId = cardId,
            accountId = accountId,
            kind = kind,
            amountMinor = amountMinor,
            currencyCode = currencyCode,
            direction = direction,
            occurredAt = occurredAt,
            localDate = occurredAt.atZone(zone).toLocalDate(),
            provenance = provenance,
            reason = reason,
        )
        adjustmentSink.insertAdjustment(adj, identity)
        return Result.success(adj)
    }
}

// ---- Sinks (domain-side persistence contracts) ----

interface CardSink {
    suspend fun insertCreditCard(card: CreditCard, identity: String)
}

interface StatementSink {
    suspend fun insertStatement(stmt: CardStatement)
}

interface StatementLineSink {
    suspend fun insertLine(line: CardStatementLine, cardId: EntityId, identity: String)
}

interface CardPaymentSink {
    suspend fun insertPayment(payment: CardPayment, identity: String)
}

interface RewardSink {
    suspend fun insertReward(event: RewardEvent, identity: String, reason: String?)
}

interface AdjustmentSink {
    suspend fun insertAdjustment(adj: CardStatementAdjustment, identity: String)
}

// ---- Identity helpers (deterministic; mirror data-layer unique indices) ----

internal fun cardIdentityFor(
    accountId: EntityId, nickname: String, issuer: String?, cardMask: String?,
): String {
    val raw = "${accountId.value}|${nickname.lowercase()}|${issuer?.lowercase() ?: ""}|${cardMask ?: ""}"
    return sha256Hex(raw)
}

internal fun statementIdentityFor(cardId: EntityId, start: LocalDate, end: LocalDate): String {
    val raw = "${cardId.value}|${start.toEpochDay()}|${end.toEpochDay()}"
    return sha256Hex(raw)
}

internal fun lineIdentityFor(
    statementId: EntityId, transactionId: String?, amountMinor: Long,
    direction: PostingDirection, occurredAt: Instant,
): String {
    val raw = "${statementId.value}|${transactionId ?: ""}|$amountMinor|${direction.name}|${occurredAt.toEpochMilli()}"
    return sha256Hex(raw)
}

internal fun paymentIdentityFor(
    cardId: EntityId, statementId: EntityId?, fundingAccountId: EntityId,
    amountMinor: Long, occurredAt: Instant,
): String {
    val raw = "${cardId.value}|${statementId?.value ?: ""}|${fundingAccountId.value}|$amountMinor|${occurredAt.toEpochMilli()}"
    return sha256Hex(raw)
}

internal fun rewardIdentityFor(
    cardId: EntityId, kind: RewardKind, cashbackAmountMinor: Long?, pointsDelta: Long?,
    occurredAt: Instant, statementId: EntityId?,
): String {
    val raw = "${cardId.value}|${kind.name}|${cashbackAmountMinor ?: -1L}|${pointsDelta ?: Long.MIN_VALUE}|${occurredAt.toEpochMilli()}|${statementId?.value ?: ""}"
    return sha256Hex(raw)
}

internal fun adjustmentIdentityFor(
    statementId: EntityId, kind: AdjustmentKind,
    amountMinor: Long, direction: PostingDirection, occurredAt: Instant,
): String {
    val raw = "${statementId.value}|${kind.name}|$amountMinor|${direction.name}|${occurredAt.toEpochMilli()}"
    return sha256Hex(raw)
}

internal fun sha256Hex(raw: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
