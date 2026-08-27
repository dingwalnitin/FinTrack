package com.example.fintrack.data.repository

import com.example.fintrack.data.db.AccountEntity
import com.example.fintrack.data.db.AtmCashLinkEntity
import com.example.fintrack.data.db.BudgetEntity
import com.example.fintrack.data.db.BudgetPeriodEntity
import com.example.fintrack.data.db.CashReconciliationEntity
import com.example.fintrack.data.db.FinanceDaoV7
import com.example.fintrack.data.db.LedgerEntryEntity
import com.example.fintrack.data.db.RecurringObservationEntity
import com.example.fintrack.data.db.RecurringPatternEntity
import com.example.fintrack.data.db.TransactionEntity
import com.example.fintrack.domain.model.AtmCashLink
import com.example.fintrack.domain.model.AtmMatchKind
import com.example.fintrack.domain.model.AtmWithdrawalCandidate
import com.example.fintrack.domain.model.BoundaryAction
import com.example.fintrack.domain.model.Budget
import com.example.fintrack.domain.model.BudgetExclusions
import com.example.fintrack.domain.model.BudgetPeriod
import com.example.fintrack.domain.model.BudgetScopeKind
import com.example.fintrack.domain.model.BudgetStatus
import com.example.fintrack.domain.model.CashReconciliation
import com.example.fintrack.domain.model.Periodicity
import com.example.fintrack.domain.model.RecurringObservation
import com.example.fintrack.domain.model.RecurringPattern
import com.example.fintrack.domain.model.RecurringStatus
import com.example.fintrack.domain.model.ReconciliationOutcome
import com.example.fintrack.domain.service.BudgetSink
import com.example.fintrack.domain.service.CashService
import com.example.fintrack.domain.service.CashSink
import com.example.fintrack.domain.service.LedgerTxnView
import com.example.fintrack.domain.service.RecurringSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed persistence for Stage 8 (P16/P17/P18). Every insert is
 * idempotent on a stable identity hash / unique index, matching the v6–v9
 * repository conventions.
 */
class RoomStage8Repository(
    private val dao: FinanceDaoV7,
) : BudgetSink, RecurringSink, CashSink {

    // ---- BudgetSink ----

    override suspend fun upsertBudget(budget: Budget): Boolean =
        dao.insertBudget(budget.toEntity()) != -1L ||
            dao.updateBudget(budget.toEntity()) > 0

    override suspend fun findBudgetByScopeIdentity(identity: String): Budget? =
        dao.findBudgetByScopeIdentity(identity)?.toDomain()

    override suspend fun activeBudgets(): List<Budget> =
        dao.budgetsByStatus(BudgetStatus.ACTIVE.name).map { it.toDomain() }

    override fun observeBudgets(): Flow<List<Budget>> =
        dao.observeBudgets().map { list -> list.map { it.toDomain() } }

    override suspend fun applyBoundary(period: BudgetPeriod): Boolean {
        if (dao.findBudgetPeriod(period.budgetId, period.periodStartEpochDay) != null) {
            return false // idempotent no-op
        }
        dao.applyBudgetBoundary(period.toEntity())
        return true
    }

    override suspend fun latestBoundaryBefore(budgetId: String, day: Long): BudgetPeriod? =
        dao.budgetPeriodsFor(budgetId)
            .filter { it.periodStartEpochDay < day }
            .maxByOrNull { it.periodStartEpochDay }
            ?.toDomain()

    // ---- RecurringSink ----

    override suspend fun findPatternByIdentity(identity: String): RecurringPattern? =
        dao.findRecurringPatternByIdentity(identity)?.toDomain()

    override suspend fun upsertPattern(pattern: RecurringPattern): Boolean {
        // Preserve the stable id of an existing row so re-detection updates
        // rather than duplicates.
        val existing = dao.findRecurringPatternByIdentity(pattern.patternIdentity)
        val toWrite = if (existing != null) pattern.copy(id = existing.id) else pattern
        dao.upsertRecurringPattern(toWrite.toEntity())
        return true
    }

    override suspend fun reviewablePatterns(): List<RecurringPattern> =
        dao.recurringPatternsInStatuses(
            listOf(RecurringStatus.DETECTED.name, RecurringStatus.CONFIRMED.name)
        ).map { it.toDomain() }

    override fun observePatterns(): Flow<List<RecurringPattern>> =
        dao.observeRecurringPatterns().map { list -> list.map { it.toDomain() } }

    /** Durable user decision; automated re-detection cannot overwrite it. */
    override suspend fun applyUserDecision(patternId: String, status: RecurringStatus): Boolean =
        dao.applyUserRecurringDecision(patternId, status.name, System.currentTimeMillis()) > 0

    override suspend fun insertObservations(observations: List<RecurringObservation>) {
        observations.forEach { dao.insertRecurringObservation(it.toEntity()) }
    }

    override suspend fun observationsForPattern(patternId: String): List<RecurringObservation> =
        dao.observationsForPattern(patternId).map { it.toDomain() }

    // ---- CashSink ----

    override suspend fun applyCashReconciliation(
        rec: CashReconciliation,
        adjustmentTxn: LedgerTxnView?,
        adjustmentPosting: Pair<String, String>?, // (direction, memo) — txn built here
    ): CashReconciliation {
        val entity = rec.toEntity()
        val txn = adjustmentTxn?.let { view ->
            buildAdjustmentTransaction(rec, view)
        }
        val posting = txn?.let {
            LedgerEntryEntity(
                id = java.util.UUID.randomUUID().toString(),
                transactionId = it.id,
                accountId = rec.accountId,
                direction = adjustmentPosting?.first ?: "DEBIT",
                amountMinor = kotlin.math.abs(rec.differenceMinor),
                currencyCode = "INR",
                postingGroupId = null,
                memo = adjustmentPosting?.second ?: "Cash reconciliation adjustment",
            )
        }
        dao.applyCashReconciliation(entity, txn, posting)
        return rec
    }

    private fun buildAdjustmentTransaction(
        rec: CashReconciliation,
        view: LedgerTxnView,
    ): TransactionEntity {
        val nowMs = System.currentTimeMillis()
        val epochDay = nowMs / 86_400_000L
        return TransactionEntity(
            id = java.util.UUID.randomUUID().toString(),
            messageId = null,
            accountId = rec.accountId,
            categoryId = null,
            amountMinor = kotlin.math.abs(rec.differenceMinor),
            currencyCode = "INR",
            occurredAtEpochMs = nowMs,
            localDateEpochDay = epochDay,
            counterparty = null,
            counterpartyNormalized = null,
            referenceId = null,
            state = "POSTED",
            sourceKind = "MANUAL_ENTRY",
            sourceVersion = "cash-reconcile-v1",
            sourceReason = rec.reason,
            correctionSourceKind = null,
            correctionSourceVersion = null,
            correctionSourceReason = null,
            correctionCapturedAtEpochMs = null,
            dedupeKey = "recon-" + rec.id,
            kind = "FEE",
            subtype = null,
            status = "POSTED",
            merchant = null,
            description = "Cash reconciliation adjustment",
            rail = null,
            cardMask = null,
            postingGroupId = null,
            transferGroupId = null,
            deletedAtEpochMs = null,
            deletedReason = null,
        )
    }

    override suspend fun reconciliationsForAccount(accountId: String): List<CashReconciliation> =
        dao.reconciliationsForAccount(accountId).map { it.toDomain() }

    override suspend fun insertAtmCashLink(link: AtmCashLink): Boolean =
        dao.insertAtmCashLink(link.toEntity()) != -1L

    override suspend fun atmLinkForWithdrawal(txnId: String): AtmCashLink? =
        dao.atmCashLinkForWithdrawal(txnId)?.toDomain()

    override suspend fun atmLinksForCashAccount(cashAccountId: String): List<AtmCashLink> =
        dao.atmCashLinksForCashAccount(cashAccountId).map { it.toDomain() }

    override suspend fun confirmAtmLink(linkId: String): Boolean =
        dao.confirmAtmCashLink(linkId) > 0

    // ---- ledger reads ----

    override suspend fun activeTransactions(): List<LedgerTxnView> =
        dao.allActiveTransactions().map { it.toLedgerView() }

    override suspend fun atmWithdrawalCandidates(): List<AtmWithdrawalCandidate> =
        dao.allActiveTransactions()
            .filter { it.kind == "CASH_MOVE" && it.subtype == "CASH_OUT" || it.subtype == "ATM_WITHDRAWAL" }
            .map { t ->
                AtmWithdrawalCandidate(
                    transactionId = t.id,
                    accountId = t.accountId,
                    amountMinor = t.amountMinor,
                    occurredAt = java.time.Instant.ofEpochMilli(t.occurredAtEpochMs),
                )
            }

    override suspend fun cashAccounts(): List<Pair<String, String>> =
        dao.activeAccounts()
            .filter { it.accountType == "CASH" }
            .map { it.id to (it.nickname ?: "") }

    // ---- mappers ----

    private fun Budget.toEntity() = BudgetEntity(
        id = id,
        name = name,
        scopeKind = scopeKind.name,
        categoryId = categoryId,
        accountId = accountId,
        periodType = periodType,
        startDayOfMonth = startDayOfMonth,
        targetAmountMinor = targetAmountMinor,
        currencyCode = currencyCode,
        rolloverEnabled = rolloverEnabled,
        rolloverCapMinor = rolloverCapMinor,
        exclusionsJson = exclusions.encode(),
        scopeIdentity = com.example.fintrack.domain.service.BudgetService.sha256(
            listOf(scopeKind.name, categoryId ?: "-", accountId ?: "-", periodType).joinToString("|")
        ),
        status = status.name,
        sourceKind = "USER",
        sourceVersion = "budget-v1",
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun BudgetEntity.toDomain() = Budget(
        id = id,
        name = name,
        scopeKind = runCatching { BudgetScopeKind.valueOf(scopeKind) }
            .getOrDefault(BudgetScopeKind.OVERALL),
        categoryId = categoryId,
        accountId = accountId,
        periodType = periodType,
        startDayOfMonth = startDayOfMonth,
        targetAmountMinor = targetAmountMinor,
        currencyCode = currencyCode,
        rolloverEnabled = rolloverEnabled,
        rolloverCapMinor = rolloverCapMinor,
        exclusions = BudgetExclusions.decode(exclusionsJson),
        status = runCatching { BudgetStatus.valueOf(status) }.getOrDefault(BudgetStatus.ACTIVE),
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun BudgetPeriod.toEntity() = BudgetPeriodEntity(
        id = id,
        budgetId = budgetId,
        periodStartEpochDay = periodStartEpochDay,
        periodEndEpochDay = periodEndEpochDay,
        rolloverInMinor = rolloverInMinor,
        boundaryAction = boundaryAction.name,
        computedAtEpochMs = computedAtEpochMs,
    )

    private fun BudgetPeriodEntity.toDomain() = BudgetPeriod(
        id = id,
        budgetId = budgetId,
        periodStartEpochDay = periodStartEpochDay,
        periodEndEpochDay = periodEndEpochDay,
        rolloverInMinor = rolloverInMinor,
        boundaryAction = runCatching { BoundaryAction.valueOf(boundaryAction) }
            .getOrDefault(BoundaryAction.RESET),
        computedAtEpochMs = computedAtEpochMs,
    )

    private fun RecurringPattern.toEntity() = RecurringPatternEntity(
        id = id,
        patternIdentity = patternIdentity,
        accountId = accountId,
        counterpartyNormalized = counterpartyNormalized,
        merchant = merchant,
        categoryId = categoryId,
        periodicity = periodicity.name,
        intervalDays = intervalDays,
        canonicalAmountMinor = canonicalAmountMinor,
        minObservedAmountMinor = minObservedAmountMinor,
        maxObservedAmountMinor = maxObservedAmountMinor,
        currencyCode = currencyCode,
        confidence = confidence,
        firstSeenEpochMs = firstSeenEpochMs,
        lastSeenEpochMs = lastSeenEpochMs,
        nextExpectedEpochMs = nextExpectedEpochMs?.toEpochMilli(),
        status = status.name,
        isSubscription = isSubscription,
        decidedBy = decidedBy,
        sourceKind = "SYSTEM",
        sourceVersion = "recurring-v1",
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun RecurringPatternEntity.toDomain() = RecurringPattern(
        id = id,
        patternIdentity = patternIdentity,
        accountId = accountId,
        counterpartyNormalized = counterpartyNormalized,
        merchant = merchant,
        categoryId = categoryId,
        periodicity = runCatching { Periodicity.valueOf(periodicity) }
            .getOrDefault(Periodicity.CUSTOM),
        intervalDays = intervalDays,
        canonicalAmountMinor = canonicalAmountMinor,
        minObservedAmountMinor = minObservedAmountMinor,
        maxObservedAmountMinor = maxObservedAmountMinor,
        currencyCode = currencyCode,
        confidence = confidence,
        firstSeenEpochMs = firstSeenEpochMs,
        lastSeenEpochMs = lastSeenEpochMs,
        nextExpectedEpochMs = nextExpectedEpochMs?.let { java.time.Instant.ofEpochMilli(it) },
        status = runCatching { RecurringStatus.valueOf(status) }
            .getOrDefault(RecurringStatus.DETECTED),
        isSubscription = isSubscription,
        decidedBy = decidedBy,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun RecurringObservation.toEntity() = RecurringObservationEntity(
        id = id,
        patternId = patternId,
        transactionId = transactionId,
        amountMinor = amountMinor,
        occurredAtEpochMs = occurredAtEpochMs,
        observationIdentity = RecurringServiceSha.sha256("$patternId|$transactionId"),
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun RecurringObservationEntity.toDomain() = RecurringObservation(
        id = id,
        patternId = patternId,
        transactionId = transactionId,
        amountMinor = amountMinor,
        occurredAtEpochMs = occurredAtEpochMs,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun CashReconciliation.toEntity() = CashReconciliationEntity(
        id = id,
        accountId = accountId,
        countedMinor = countedMinor,
        ledgerDerivedMinor = ledgerDerivedMinor,
        differenceMinor = differenceMinor,
        outcome = outcome.name,
        adjustmentTransactionId = adjustmentTransactionId,
        reason = reason,
        reconciliationIdentity = CashService.sha256("$accountId|$countedMinor|$ledgerDerivedMinor|${atEpochMs}"),
        sourceKind = "MANUAL_ENTRY",
        sourceVersion = "cash-reconcile-v1",
        atEpochMs = atEpochMs,
    )

    private fun CashReconciliationEntity.toDomain() = CashReconciliation(
        id = id,
        accountId = accountId,
        countedMinor = countedMinor,
        ledgerDerivedMinor = ledgerDerivedMinor,
        differenceMinor = differenceMinor,
        outcome = runCatching { ReconciliationOutcome.valueOf(outcome) }
            .getOrDefault(ReconciliationOutcome.EXACT),
        adjustmentTransactionId = adjustmentTransactionId,
        reason = reason,
        atEpochMs = atEpochMs,
    )

    private fun AtmCashLink.toEntity() = AtmCashLinkEntity(
        id = id,
        withdrawalTransactionId = withdrawalTransactionId,
        cashAccountId = cashAccountId,
        amountMinor = amountMinor,
        currencyCode = currencyCode,
        withdrawalOccurredAtEpochMs = withdrawalOccurredAtEpochMs,
        matchedBy = matchedBy.name,
        candidateCount = candidateCount,
        ambiguous = ambiguous,
        confirmedByUser = confirmedByUser,
        linkIdentity = linkIdentity(withdrawalTransactionId, cashAccountId),
        sourceKind = if (matchedBy == AtmMatchKind.MANUAL) "USER" else "SYSTEM",
        sourceVersion = "atm-link-v1",
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun AtmCashLinkEntity.toDomain() = AtmCashLink(
        id = id,
        withdrawalTransactionId = withdrawalTransactionId,
        cashAccountId = cashAccountId,
        amountMinor = amountMinor,
        currencyCode = currencyCode,
        withdrawalOccurredAtEpochMs = withdrawalOccurredAtEpochMs,
        matchedBy = runCatching { AtmMatchKind.valueOf(matchedBy) }
            .getOrDefault(AtmMatchKind.AMOUNT_DATE_ACCOUNT),
        candidateCount = candidateCount,
        ambiguous = ambiguous,
        confirmedByUser = confirmedByUser,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun TransactionEntity.toLedgerView() = LedgerTxnView(
        id = id,
        accountId = accountId,
        categoryId = categoryId,
        kind = kind,
        directionDebit = directionDebitFromEntity(),
        amountMinor = amountMinor,
        localDateEpochDay = localDateEpochDay,
        counterpartyNormalized = counterpartyNormalized,
        merchant = merchant,
        currencyCode = currencyCode,
        occurredAtEpochMs = occurredAtEpochMs,
        subtype = subtype,
        userCorrected = correctionSourceKind != null,
        statusDeleted = status == "DELETED",
    )

    private fun TransactionEntity.directionDebitFromEntity(): Boolean {
        // Direction lives on the ledger entries; transactions store the
        // semantic kind. For budget purposes EXPENSE/FEE are outflows.
        return when (kind) {
            "EXPENSE", "FEE", "TRANSFER", "CASH_MOVE" -> true
            else -> false
        }
    }

    private fun linkIdentity(withdrawalTxnId: String, cashAccountId: String): String =
        CashService.sha256("$withdrawalTxnId|$cashAccountId")
}

/** Small helper so observation identity hashing stays consistent. */
private object RecurringServiceSha {
    fun sha256(raw: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
