package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.AtmCashLink
import com.example.fintrack.domain.model.AtmWithdrawalCandidate
import com.example.fintrack.domain.model.Budget
import com.example.fintrack.domain.model.BudgetPeriod
import com.example.fintrack.domain.model.CashReconciliation
import com.example.fintrack.domain.model.RecurringObservation
import com.example.fintrack.domain.model.RecurringPattern
import com.example.fintrack.domain.model.RecurringStatus
import kotlinx.coroutines.flow.Flow

/**
 * Stage 8 persistence contracts. Domain services depend on these interfaces;
 * Room repositories implement them (dependency direction: domain <- data).
 */

/** Ledger-derived transaction view consumed by budget/recurring engines. */
data class LedgerTxnView(
    val id: String,
    val accountId: String,
    val categoryId: String?,
    val kind: String,
    val directionDebit: Boolean,
    val amountMinor: Long,
    val localDateEpochDay: Long,
    val counterpartyNormalized: String?,
    val merchant: String?,
    val currencyCode: String,
    val occurredAtEpochMs: Long,
    val subtype: String?,
    val userCorrected: Boolean = false,
    val statusDeleted: Boolean = false,
    /**
     * Stage 9 (P19): payment rail (UPI/IMPS/NEFT/RTGS/CARD_POS/CARD_ONLINE/
     * ATM/ACH or null when unknown). Additive with default so existing
     * constructors keep compiling.
     */
    val rail: String? = null,
    /** Normalized 4-digit card mask when the funding instrument is a card; null otherwise. */
    val cardMask: String? = null,
)

interface BudgetSink {
    suspend fun upsertBudget(budget: Budget): Boolean
    suspend fun findBudgetByScopeIdentity(identity: String): Budget?
    suspend fun activeBudgets(): List<Budget>
    fun observeBudgets(): Flow<List<Budget>>

    /** Persist a period-boundary decision; idempotent per (budget, period). */
    suspend fun applyBoundary(period: BudgetPeriod): Boolean

    /** Most recent boundary row strictly before [day], or null when none yet. */
    suspend fun latestBoundaryBefore(budgetId: String, day: Long): BudgetPeriod?
}

interface RecurringSink {
    suspend fun findPatternByIdentity(identity: String): RecurringPattern?
    suspend fun upsertPattern(pattern: RecurringPattern): Boolean
    suspend fun reviewablePatterns(): List<RecurringPattern>
    fun observePatterns(): Flow<List<RecurringPattern>>

    /** Durable user decision; survives automated re-detection. */
    suspend fun applyUserDecision(patternId: String, status: RecurringStatus): Boolean

    suspend fun insertObservations(observations: List<RecurringObservation>)
    suspend fun observationsForPattern(patternId: String): List<RecurringObservation>
}

interface CashSink {
    /**
     * Atomic reconciliation write: event row plus optional adjustment
     * transaction/posting in one @Transaction.
     */
    suspend fun applyCashReconciliation(
        rec: CashReconciliation,
        adjustmentTxn: LedgerTxnView?,
        adjustmentPosting: Pair<String, String>?,
    ): CashReconciliation

    suspend fun reconciliationsForAccount(accountId: String): List<CashReconciliation>

    suspend fun insertAtmCashLink(link: AtmCashLink): Boolean
    suspend fun atmLinkForWithdrawal(txnId: String): AtmCashLink?
    suspend fun atmLinksForCashAccount(cashAccountId: String): List<AtmCashLink>
    suspend fun confirmAtmLink(linkId: String): Boolean

    // ledger reads
    suspend fun activeTransactions(): List<LedgerTxnView>
    suspend fun atmWithdrawalCandidates(): List<AtmWithdrawalCandidate>
    /** (id, nickname) pairs of ACTIVE CASH accounts. */
    suspend fun cashAccounts(): List<Pair<String, String>>
}
