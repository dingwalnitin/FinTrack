package com.example.fintrack.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schema version history (forward-only, no destructive loss):
 *  v1 — messages, transactions (foundation)
 *  v2 — full Bible blueprint: accounts, categories, ledger_entries, transfers,
 *       processing_jobs, audit_events; transactions gain account/category/reference/
 *       dedupe/local-date/provenance-reason columns and the indexing strategy.
 *  v3 — accounts authoritative: nickname/last4/institutionName columns,
 *       account_opening_balances, balance_snapshots, sender_account_mappings,
 *       institution_aliases.
 *  v4 — SMS evidence acquisition: raw_sms (immutable provider-id-keyed evidence),
 *       sms_backfill_cursor (durable resumption), sms_ingestion_progress
 *       (aggregate progress). Existing rows are preserved.
 *  v5 — LLM enrichment pipeline: llm_jobs (durable four-worker state machine),
 *       llm_interpretations (advisory typed output + per-field confidence),
 *       llm_response_cache, llm_usage_counters, llm_metrics. Advisory-only;
 *       no existing data mutated.
 *  v6 — Stage 5 (P09 + P10): evidence_links / dedupe_clusters /
 *       dedupe_cluster_members / dedupe_decisions for durable dedup artifacts
 *       and audit trail; transactions gain kind/subtype/status/merchant/
 *       description/rail/cardMask/postingGroupId/deletedAtEpochMs; ledger_entries
 *       gain postingGroupId + memo. Additive only; legacy v5 rows are valid
 *       with kind=UNKNOWN, status=POSTED.
 *  v7 — Stage 5 (P11): refund_links + transaction_links (parent/child for
 *       fees & refunds) and a transferGroupId on transactions so two-sided
 *       transfer siblings can be surfaced as a single logical event. Additive
 *       only; existing v6 rows remain valid with transferGroupId=null.
 *  v8 — Stage 6 (P12 + P13): credit_cards, card_statements,
 *       card_statement_lines, card_payments, reward_events,
 *       card_statement_adjustments, emi_plans, emi_installments,
 *       emi_preclosures. Additive only; existing v7 rows are unchanged.
 *  v9 — Stage 7 (P14 + P15): categories, merchants, merchant_aliases,
 *       category_rules, llm_category_suggestions, merchant_vpa_bindings,
 *       category_audit, review_items, transaction_splits,
 *       reimbursement_links, travel_modes, transaction_tags,
 *       transaction_notes. Additive only; existing v8 rows remain
 *       valid with categoryId unchanged.
 *  v10 — Stage 8 (P16 + P17 + P18): budgets, budget_periods,
 *       recurring_patterns, recurring_observations, cash_reconciliations,
 *       atm_cash_links. Additive only; budgets stay fully derived from
 *       ledger data and recurring patterns carry durable user decisions.
 *  v11 — Stage 11 (P23 + P24): import_staging_rows / import_batches
 *       (temporary-by-contract import staging), settings_profiles (module
 *       175), audit_log (P24 retention-bounded sensitive-action log) and
 *       app_lock_state (singleton; secret lives in Keystore, not Room).
 *       Additive only; existing v10 rows are unchanged.
 *  v12 — Stage 13 (A + D): payee_category_rules (per-payee persistent
 *       category rules), transaction_evidence (durable transaction ->
 *       source SMS + raw LLM JSON link) and llm_interpretations.rawLlmJson
 *       (raw LLM output for audit). Additive only; raw SMS bodies stay in
 *       raw_sms.
 */
@Database(
    entities = [
        com.example.fintrack.data.db.MessageEntity::class,
        com.example.fintrack.data.db.TransactionEntity::class,
        com.example.fintrack.data.db.AccountEntity::class,
        com.example.fintrack.data.db.CategoryEntity::class,
        com.example.fintrack.data.db.LedgerEntryEntity::class,
        com.example.fintrack.data.db.TransferEntity::class,
        com.example.fintrack.data.db.ProcessingJobEntity::class,
        com.example.fintrack.data.db.AuditEventEntity::class,
        com.example.fintrack.data.db.AccountOpeningBalanceEntity::class,
        com.example.fintrack.data.db.BalanceSnapshotEntity::class,
        com.example.fintrack.data.db.SenderAccountMappingEntity::class,
        com.example.fintrack.data.db.InstitutionAliasEntity::class,
        com.example.fintrack.data.db.RawSmsEntity::class,
        com.example.fintrack.data.db.SmsBackfillCursorEntity::class,
        com.example.fintrack.data.db.SmsIngestionProgressEntity::class,
        com.example.fintrack.data.db.LlmJobEntity::class,
        com.example.fintrack.data.db.LlmInterpretationEntity::class,
        com.example.fintrack.data.db.LlmResponseCacheEntity::class,
        com.example.fintrack.data.db.LlmUsageCounterEntity::class,
        com.example.fintrack.data.db.LlmMetricEntity::class,
        com.example.fintrack.data.db.EvidenceLinkEntity::class,
        com.example.fintrack.data.db.DedupeClusterEntity::class,
        com.example.fintrack.data.db.DedupeClusterMemberEntity::class,
        com.example.fintrack.data.db.DedupeDecisionEntity::class,
        com.example.fintrack.data.db.RefundLinkEntity::class,
        com.example.fintrack.data.db.TransactionLinkEntity::class,
        com.example.fintrack.data.db.CreditCardEntity::class,
        com.example.fintrack.data.db.CardStatementEntity::class,
        com.example.fintrack.data.db.CardStatementLineEntity::class,
        com.example.fintrack.data.db.CardPaymentEntity::class,
        com.example.fintrack.data.db.RewardEventEntity::class,
        com.example.fintrack.data.db.CardStatementAdjustmentEntity::class,
        com.example.fintrack.data.db.EmiPlanEntity::class,
        com.example.fintrack.data.db.EmiInstallmentEntity::class,
        com.example.fintrack.data.db.EmiPreclosureEntity::class,
        com.example.fintrack.data.db.MerchantEntity::class,
        com.example.fintrack.data.db.MerchantAliasEntity::class,
        com.example.fintrack.data.db.CategoryRuleEntity::class,
        com.example.fintrack.data.db.LlmCategorySuggestionEntity::class,
        com.example.fintrack.data.db.MerchantVpaBindingEntity::class,
        com.example.fintrack.data.db.CategoryAuditEntity::class,
        com.example.fintrack.data.db.ReviewItemEntity::class,
        com.example.fintrack.data.db.TransactionSplitEntity::class,
        com.example.fintrack.data.db.ReimbursementLinkEntity::class,
        com.example.fintrack.data.db.TravelModeEntity::class,
        com.example.fintrack.data.db.TransactionTagEntity::class,
        com.example.fintrack.data.db.TransactionNoteEntity::class,
        com.example.fintrack.data.db.BudgetEntity::class,
        com.example.fintrack.data.db.BudgetPeriodEntity::class,
        com.example.fintrack.data.db.RecurringPatternEntity::class,
        com.example.fintrack.data.db.RecurringObservationEntity::class,
        com.example.fintrack.data.db.CashReconciliationEntity::class,
        com.example.fintrack.data.db.AtmCashLinkEntity::class,
        com.example.fintrack.data.db.ImportStagingRowEntity::class,
        com.example.fintrack.data.db.ImportBatchEntity::class,
        com.example.fintrack.data.db.SettingsProfileEntity::class,
        com.example.fintrack.data.db.AuditLogEntryEntity::class,
        com.example.fintrack.data.db.AppLockStateEntity::class,
        com.example.fintrack.data.db.PayeeCategoryRuleEntity::class,
        com.example.fintrack.data.db.TransactionEvidenceEntity::class,
    ],
    version = 12,
    exportSchema = true,
)
abstract class FinTrackDatabaseV2 : RoomDatabase() {
    abstract fun financeDaoV2(): FinanceDaoV2
    abstract fun financeDaoV3(): FinanceDaoV3
    abstract fun financeDaoV4(): FinanceDaoV4
    abstract fun financeDaoV5(): FinanceDaoV5
    abstract fun financeDaoV6(): FinanceDaoV6
    abstract fun financeDaoV7(): FinanceDaoV7

    /** Stage 9 (P19 + P20): read-only dashboard / search / diagnostics queries. */
    abstract fun financeDaoV8(): FinanceDaoV8

    /** Stage 11 (P23 + P24): backup staging/commit, profiles, audit, app lock. */
    abstract fun financeDaoV9(): FinanceDaoV9

    /** Stage 13 (A + D): payee category rules + transaction evidence. */
    abstract fun financeDaoV10(): FinanceDaoV10

    abstract fun smsDao(): SmsDao
    abstract fun llmDao(): LlmDao

    val llmSchedulerDao: LlmSchedulerDao get() = llmDao()

    companion object {
        const val SCHEMA_VERSION = 12
    }
}
