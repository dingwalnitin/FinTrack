package com.example.fintrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * v8 P12 + P13 data layer (Stage 6).
 *
 * P12 — credit cards. Idempotency on every table via a unique `*Identity`
 * index. The card row is the durable identity for limit / cycle / due-date
 * rules; statements, statement lines, payments, rewards and adjustments
 * reference the card and (optionally) the statement.
 *
 * P13 — EMI plans. One plan with many installments; preclosure is its
 * own event. Re-running parsers / LLM re-prompts / backfill is a no-op
 * thanks to the unique (planId, installmentNumber) and `*Identity` indices.
 */
@Dao
interface FinanceDaoV5 {

    // ---- P12: credit_cards ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCreditCard(card: CreditCardEntity): Long

    @Query("SELECT * FROM credit_cards WHERE id = :id LIMIT 1")
    suspend fun findCreditCardById(id: String): CreditCardEntity?

    @Query("SELECT * FROM credit_cards WHERE accountId = :accountId LIMIT 1")
    suspend fun findCreditCardByAccountId(accountId: String): CreditCardEntity?

    @Query("SELECT * FROM credit_cards WHERE cardIdentity = :identity LIMIT 1")
    suspend fun findCreditCardByIdentity(identity: String): CreditCardEntity?

    @Query("SELECT * FROM credit_cards WHERE lifecycle = :lifecycle")
    suspend fun creditCardsByLifecycle(lifecycle: String): List<CreditCardEntity>

    // ---- P12: card_statements ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCardStatement(stmt: CardStatementEntity): Long

    @Query("SELECT * FROM card_statements WHERE id = :id LIMIT 1")
    suspend fun findCardStatementById(id: String): CardStatementEntity?

    @Query("SELECT * FROM card_statements WHERE statementIdentity = :identity LIMIT 1")
    suspend fun findCardStatementByIdentity(identity: String): CardStatementEntity?

    @Query("SELECT * FROM card_statements WHERE cardId = :cardId ORDER BY periodStartEpochDay DESC")
    suspend fun statementsForCard(cardId: String): List<CardStatementEntity>

    @Query("SELECT * FROM card_statements WHERE cardId = :cardId AND status = :status ORDER BY periodStartEpochDay DESC")
    suspend fun statementsForCardInStatus(cardId: String, status: String): List<CardStatementEntity>

    // ---- P12: card_statement_lines ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCardStatementLine(line: CardStatementLineEntity): Long

    @Query("SELECT * FROM card_statement_lines WHERE statementId = :statementId")
    suspend fun linesForStatement(statementId: String): List<CardStatementLineEntity>

    @Query("SELECT * FROM card_statement_lines WHERE lineIdentity = :identity LIMIT 1")
    suspend fun findCardStatementLineByIdentity(identity: String): CardStatementLineEntity?

    @Query("SELECT * FROM card_statement_lines WHERE transactionId = :transactionId")
    suspend fun linesForTransaction(transactionId: String): List<CardStatementLineEntity>

    @Query("SELECT * FROM card_statement_lines WHERE cardId = :cardId AND status = :status")
    suspend fun linesForCardInStatus(cardId: String, status: String): List<CardStatementLineEntity>

    // ---- P12: card_payments ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCardPayment(payment: CardPaymentEntity): Long

    @Query("SELECT * FROM card_payments WHERE paymentIdentity = :identity LIMIT 1")
    suspend fun findCardPaymentByIdentity(identity: String): CardPaymentEntity?

    @Query("SELECT * FROM card_payments WHERE cardId = :cardId ORDER BY occurredAtEpochMs DESC")
    suspend fun paymentsForCard(cardId: String): List<CardPaymentEntity>

    @Query("SELECT * FROM card_payments WHERE statementId = :statementId")
    suspend fun paymentsForStatement(statementId: String): List<CardPaymentEntity>

    // ---- P12: reward_events ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRewardEvent(reward: RewardEventEntity): Long

    @Query("SELECT * FROM reward_events WHERE rewardIdentity = :identity LIMIT 1")
    suspend fun findRewardEventByIdentity(identity: String): RewardEventEntity?

    @Query("SELECT * FROM reward_events WHERE cardId = :cardId ORDER BY occurredAtEpochMs DESC")
    suspend fun rewardEventsForCard(cardId: String): List<RewardEventEntity>

    // ---- P12: card_statement_adjustments ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCardStatementAdjustment(adj: CardStatementAdjustmentEntity): Long

    @Query("SELECT * FROM card_statement_adjustments WHERE adjustmentIdentity = :identity LIMIT 1")
    suspend fun findCardStatementAdjustmentByIdentity(identity: String): CardStatementAdjustmentEntity?

    @Query("SELECT * FROM card_statement_adjustments WHERE statementId = :statementId")
    suspend fun adjustmentsForStatement(statementId: String): List<CardStatementAdjustmentEntity>

    @Query("SELECT * FROM card_statement_adjustments WHERE cardId = :cardId")
    suspend fun adjustmentsForCard(cardId: String): List<CardStatementAdjustmentEntity>

    // ---- P13: emi_plans ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEmiPlan(plan: EmiPlanEntity): Long

    @Query("SELECT * FROM emi_plans WHERE id = :id LIMIT 1")
    suspend fun findEmiPlanById(id: String): EmiPlanEntity?

    @Query("SELECT * FROM emi_plans WHERE planIdentity = :identity LIMIT 1")
    suspend fun findEmiPlanByIdentity(identity: String): EmiPlanEntity?

    @Query("SELECT * FROM emi_plans WHERE emiAccountId = :accountId")
    suspend fun emiPlansForAccount(accountId: String): List<EmiPlanEntity>

    @Query("SELECT * FROM emi_plans WHERE status = :status")
    suspend fun emiPlansByStatus(status: String): List<EmiPlanEntity>

    // ---- P13: emi_installments ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEmiInstallment(installment: EmiInstallmentEntity): Long

    @Query("SELECT * FROM emi_installments WHERE planId = :planId ORDER BY installmentNumber ASC")
    suspend fun installmentsForPlan(planId: String): List<EmiInstallmentEntity>

    @Query("SELECT * FROM emi_installments WHERE installmentIdentity = :identity LIMIT 1")
    suspend fun findEmiInstallmentByIdentity(identity: String): EmiInstallmentEntity?

    @Query("SELECT * FROM emi_installments WHERE transactionId = :transactionId")
    suspend fun installmentsForTransaction(transactionId: String): List<EmiInstallmentEntity>

    // ---- P13: emi_preclosures ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEmiPreclosure(preclosure: EmiPreclosureEntity): Long

    @Query("SELECT * FROM emi_preclosures WHERE preclosureIdentity = :identity LIMIT 1")
    suspend fun findEmiPreclosureByIdentity(identity: String): EmiPreclosureEntity?

    @Query("SELECT * FROM emi_preclosures WHERE planId = :planId")
    suspend fun preclosuresForPlan(planId: String): List<EmiPreclosureEntity>

    // ---- in-place transitions (status / closedAt / paid-amount) ----
    @Query(
        """UPDATE emi_plans SET status = :status, closedAtEpochMs = :closedAtEpochMs
           WHERE id = :id"""
    )
    suspend fun updateEmiPlanStatus(id: String, status: String, closedAtEpochMs: Long?): Int

    @Query(
        """UPDATE emi_installments SET status = :status, amountPaidMinor = :amountPaidMinor,
           transactionId = :transactionId WHERE id = :id"""
    )
    suspend fun updateEmiInstallmentPaid(
        id: String, status: String, amountPaidMinor: Long?, transactionId: String?,
    ): Int

    // ---- v8 atomic plan creation: plan + first installment in one @Transaction ----
    @Transaction
    suspend fun createEmiPlanWithFirstInstallment(
        plan: EmiPlanEntity,
        firstInstallment: EmiInstallmentEntity?,
    ) {
        insertEmiPlan(plan)
        firstInstallment?.let { insertEmiInstallment(it) }
    }
}
