package com.example.fintrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * v10 Stage 8 data layer (P16 budgets, P17 recurring, P18 cash/ATM).
 *
 * All writes are idempotent on stable identity hashes backed by unique
 * indices. Budget period boundary writes happen inside a @Transaction so a
 * rollover decision and its audit row land atomically.
 */
@Dao
interface FinanceDaoV7 {

    // ---- P16: budgets ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBudget(budget: BudgetEntity): Long

    @Query("SELECT * FROM budgets WHERE id = :id LIMIT 1")
    suspend fun findBudgetById(id: String): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE scopeIdentity = :identity LIMIT 1")
    suspend fun findBudgetByScopeIdentity(identity: String): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE status = :status ORDER BY createdAtEpochMs ASC")
    suspend fun budgetsByStatus(status: String): List<BudgetEntity>

    @Query("SELECT * FROM budgets ORDER BY createdAtEpochMs ASC")
    fun observeBudgets(): Flow<List<BudgetEntity>>

    @Update
    suspend fun updateBudget(budget: BudgetEntity): Int

    // ---- P16: budget periods ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBudgetPeriod(period: BudgetPeriodEntity): Long

    @Query("SELECT * FROM budget_periods WHERE budgetId = :budgetId AND periodStartEpochDay = :startDay LIMIT 1")
    suspend fun findBudgetPeriod(budgetId: String, startDay: Long): BudgetPeriodEntity?

    @Query("SELECT * FROM budget_periods WHERE budgetId = :budgetId ORDER BY periodStartEpochDay DESC")
    suspend fun budgetPeriodsFor(budgetId: String): List<BudgetPeriodEntity>

    /**
     * Atomic period-boundary write: persist the rollover/reset decision for
     * one (budget, period). Idempotent via the unique (budgetId, startDay)
     * index — re-running the same boundary is a no-op.
     */
    @Transaction
    suspend fun applyBudgetBoundary(period: BudgetPeriodEntity) {
        insertBudgetPeriod(period)
    }

    // ---- P17: recurring patterns ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecurringPattern(pattern: RecurringPatternEntity): Long

    @Query("SELECT * FROM recurring_patterns WHERE id = :id LIMIT 1")
    suspend fun findRecurringPatternById(id: String): RecurringPatternEntity?

    @Query("SELECT * FROM recurring_patterns WHERE patternIdentity = :identity LIMIT 1")
    suspend fun findRecurringPatternByIdentity(identity: String): RecurringPatternEntity?

    @Query("SELECT * FROM recurring_patterns WHERE status IN (:statuses) ORDER BY nextExpectedEpochMs ASC")
    suspend fun recurringPatternsInStatuses(statuses: List<String>): List<RecurringPatternEntity>

    @Query("SELECT * FROM recurring_patterns ORDER BY nextExpectedEpochMs ASC")
    fun observeRecurringPatterns(): Flow<List<RecurringPatternEntity>>

    /** Durable user decision. Never overwritten by automated re-detection. */
    @Query(
        "UPDATE recurring_patterns SET status = :status, decidedBy = 'USER', " +
            "updatedAtEpochMs = :atMs WHERE id = :id"
    )
    suspend fun applyUserRecurringDecision(id: String, status: String, atMs: Long): Int

    // ---- P17: recurring observations ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecurringObservation(observation: RecurringObservationEntity): Long

    @Query("SELECT * FROM recurring_observations WHERE patternId = :patternId ORDER BY occurredAtEpochMs ASC")
    suspend fun observationsForPattern(patternId: String): List<RecurringObservationEntity>

    @Query("SELECT * FROM recurring_observations WHERE observationIdentity = :identity LIMIT 1")
    suspend fun findObservationByIdentity(identity: String): RecurringObservationEntity?

    // ---- P18: cash reconciliations ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCashReconciliation(rec: CashReconciliationEntity): Long

    @Query("SELECT * FROM cash_reconciliations WHERE id = :id LIMIT 1")
    suspend fun findCashReconciliationById(id: String): CashReconciliationEntity?

    @Query("SELECT * FROM cash_reconciliations WHERE accountId = :accountId ORDER BY atEpochMs DESC")
    suspend fun reconciliationsForAccount(accountId: String): List<CashReconciliationEntity>

    @Query("SELECT * FROM cash_reconciliations WHERE reconciliationIdentity = :identity LIMIT 1")
    suspend fun findCashReconciliationByIdentity(identity: String): CashReconciliationEntity?

    /**
     * Atomic reconciliation write: the event row plus (optionally) the
     * adjustment transaction + posting produced by the caller.
     */
    @Transaction
    suspend fun applyCashReconciliation(
        rec: CashReconciliationEntity,
        adjustmentTxn: TransactionEntity?,
        adjustmentPosting: LedgerEntryEntity?,
    ) {
        insertCashReconciliation(rec)
        if (adjustmentTxn != null && adjustmentPosting != null) {
            upsertTransaction(adjustmentTxn)
            insertLedgerEntry(adjustmentPosting)
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransaction(txn: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgerEntry(entry: LedgerEntryEntity): Long

    // ---- P18: ATM cash links ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAtmCashLink(link: AtmCashLinkEntity): Long

    @Query("SELECT * FROM atm_cash_links WHERE id = :id LIMIT 1")
    suspend fun findAtmCashLinkById(id: String): AtmCashLinkEntity?

    @Query("SELECT * FROM atm_cash_links WHERE linkIdentity = :identity LIMIT 1")
    suspend fun findAtmCashLinkByIdentity(identity: String): AtmCashLinkEntity?

    @Query("SELECT * FROM atm_cash_links WHERE withdrawalTransactionId = :txnId LIMIT 1")
    suspend fun atmCashLinkForWithdrawal(txnId: String): AtmCashLinkEntity?

    @Query("SELECT * FROM atm_cash_links WHERE cashAccountId = :cashAccountId ORDER BY withdrawalOccurredAtEpochMs DESC")
    suspend fun atmCashLinksForCashAccount(cashAccountId: String): List<AtmCashLinkEntity>

    @Query("UPDATE atm_cash_links SET confirmedByUser = 1 WHERE id = :id")
    suspend fun confirmAtmCashLink(id: String): Int

    // ---- Ledger reads used by budget / forecast engines ----

    @Query(
        "SELECT t.* FROM transactions t WHERE t.status != 'DELETED' " +
            "AND t.localDateEpochDay BETWEEN :fromDay AND :toDay"
    )
    suspend fun transactionsBetween(fromDay: Long, toDay: Long): List<TransactionEntity>

    @Query(
        "SELECT t.* FROM transactions t WHERE t.status != 'DELETED' " +
            "AND t.localDateEpochDay BETWEEN :fromDay AND :toDay"
    )
    fun observeTransactionsBetween(fromDay: Long, toDay: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE status != 'DELETED'")
    suspend fun allActiveTransactions(): List<TransactionEntity>

    @Query("SELECT * FROM accounts WHERE lifecycle = 'ACTIVE'")
    suspend fun activeAccounts(): List<AccountEntity>
}
