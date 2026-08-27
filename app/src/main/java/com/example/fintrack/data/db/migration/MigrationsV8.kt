package com.example.fintrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v7 -> v8 (Stage 6, P12 — credit cards; P13 — EMI plans).
 *
 * Additive only — no existing data is mutated. All new tables are
 * append-only history; the `accounts` table is the authoritative balance
 * container and the new tables reference it by id.
 *
 * Indexing strategy (mirrors the P11 `*Identity` discipline):
 *  - every link / dedupe row carries a stable identity hash with a unique
 *    index so re-running parsers / LLM re-prompts / backfill is idempotent
 *  - lookups by account, card and statement all have dedicated indices
 *  - the `emi_installments` unique `(planId, installmentNumber)` index
 *    enforces the (plan, month) cardinality.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {

        // ---- P12: credit_cards ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `credit_cards` (
               `id` TEXT NOT NULL,
               `accountId` TEXT NOT NULL,
               `nickname` TEXT NOT NULL,
               `cardIdentity` TEXT NOT NULL,
               `issuer` TEXT,
               `cardMask` TEXT,
               `currencyCode` TEXT NOT NULL,
               `lifecycle` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               `creditLimitMinor` INTEGER,
               `statementDayOfMonth` INTEGER,
               `statementCycleDays` INTEGER,
               `dueDayOfMonth` INTEGER,
               `dueDaysAfterStatement` INTEGER,
               `rewardPointsBalance` INTEGER,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_credit_cards_accountId` ON `credit_cards` (`accountId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_credit_cards_cardIdentity` ON `credit_cards` (`cardIdentity`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_credit_cards_lifecycle` ON `credit_cards` (`lifecycle`)")

        // ---- P12: card_statements ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `card_statements` (
               `id` TEXT NOT NULL,
               `cardId` TEXT NOT NULL,
               `accountId` TEXT NOT NULL,
               `periodStartEpochDay` INTEGER NOT NULL,
               `periodEndEpochDay` INTEGER NOT NULL,
               `dueDateEpochDay` INTEGER,
               `totalDueMinor` INTEGER NOT NULL,
               `minDueMinor` INTEGER,
               `currencyCode` TEXT NOT NULL,
               `status` TEXT NOT NULL,
               `statementIdentity` TEXT NOT NULL,
               `capturedAtEpochMs` INTEGER NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_statements_cardId_periodStart` ON `card_statements` (`cardId`, `periodStartEpochDay`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_statements_accountId` ON `card_statements` (`accountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_statements_status` ON `card_statements` (`status`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_card_statements_statementIdentity` ON `card_statements` (`statementIdentity`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_statements_dueDate` ON `card_statements` (`dueDateEpochDay`)")

        // ---- P12: card_statement_lines ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `card_statement_lines` (
               `id` TEXT NOT NULL,
               `statementId` TEXT NOT NULL,
               `cardId` TEXT NOT NULL,
               `lineIdentity` TEXT NOT NULL,
               `transactionId` TEXT,
               `occurredAtEpochMs` INTEGER NOT NULL,
               `localDateEpochDay` INTEGER NOT NULL,
               `amountMinor` INTEGER NOT NULL,
               `currencyCode` TEXT NOT NULL,
               `direction` TEXT NOT NULL,
               `status` TEXT NOT NULL,
               `merchant` TEXT,
               `rail` TEXT,
               `cardMask` TEXT,
               `referenceId` TEXT,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_statement_lines_statementId` ON `card_statement_lines` (`statementId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_statement_lines_transactionId` ON `card_statement_lines` (`transactionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_statement_lines_status` ON `card_statement_lines` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_statement_lines_cardId_occurredAtEpochMs` ON `card_statement_lines` (`cardId`, `occurredAtEpochMs`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_card_statement_lines_lineIdentity` ON `card_statement_lines` (`lineIdentity`)")

        // ---- P12: card_payments ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `card_payments` (
               `id` TEXT NOT NULL,
               `cardId` TEXT NOT NULL,
               `statementId` TEXT,
               `fundingAccountId` TEXT NOT NULL,
               `amountMinor` INTEGER NOT NULL,
               `currencyCode` TEXT NOT NULL,
               `occurredAtEpochMs` INTEGER NOT NULL,
               `localDateEpochDay` INTEGER NOT NULL,
               `paymentStatus` TEXT NOT NULL,
               `referenceId` TEXT,
               `paymentIdentity` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_payments_cardId` ON `card_payments` (`cardId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_payments_statementId` ON `card_payments` (`statementId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_payments_fundingAccountId` ON `card_payments` (`fundingAccountId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_card_payments_paymentIdentity` ON `card_payments` (`paymentIdentity`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_payments_occurredAtEpochMs` ON `card_payments` (`occurredAtEpochMs`)")

        // ---- P12: reward_events ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `reward_events` (
               `id` TEXT NOT NULL,
               `cardId` TEXT NOT NULL,
               `accountId` TEXT NOT NULL,
               `statementId` TEXT,
               `transactionId` TEXT,
               `kind` TEXT NOT NULL,
               `classification` TEXT NOT NULL,
               `cashbackAmountMinor` INTEGER,
               `pointsDelta` INTEGER,
               `currencyCode` TEXT NOT NULL,
               `occurredAtEpochMs` INTEGER NOT NULL,
               `localDateEpochDay` INTEGER NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `sourceReason` TEXT,
               `rewardIdentity` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reward_events_cardId` ON `reward_events` (`cardId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reward_events_accountId` ON `reward_events` (`accountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reward_events_statementId` ON `reward_events` (`statementId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reward_events_transactionId` ON `reward_events` (`transactionId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_reward_events_rewardIdentity` ON `reward_events` (`rewardIdentity`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reward_events_classification` ON `reward_events` (`classification`)")

        // ---- P12: card_statement_adjustments ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `card_statement_adjustments` (
               `id` TEXT NOT NULL,
               `statementId` TEXT NOT NULL,
               `cardId` TEXT NOT NULL,
               `accountId` TEXT NOT NULL,
               `kind` TEXT NOT NULL,
               `amountMinor` INTEGER NOT NULL,
               `currencyCode` TEXT NOT NULL,
               `direction` TEXT NOT NULL,
               `occurredAtEpochMs` INTEGER NOT NULL,
               `localDateEpochDay` INTEGER NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `reason` TEXT,
               `adjustmentIdentity` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_statement_adjustments_statementId` ON `card_statement_adjustments` (`statementId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_statement_adjustments_cardId` ON `card_statement_adjustments` (`cardId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_statement_adjustments_kind` ON `card_statement_adjustments` (`kind`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_card_statement_adjustments_adjustmentIdentity` ON `card_statement_adjustments` (`adjustmentIdentity`)")

        // ---- P13: emi_plans ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `emi_plans` (
               `id` TEXT NOT NULL,
               `emiAccountId` TEXT NOT NULL,
               `merchantOrBiller` TEXT,
               `referenceId` TEXT,
               `principalMinor` INTEGER,
               `interestRateAnnualBps` INTEGER,
               `installmentAmountMinor` INTEGER,
               `totalInstallments` INTEGER,
               `startDateEpochDay` INTEGER,
               `endDateEpochDay` INTEGER,
               `currencyCode` TEXT NOT NULL,
               `status` TEXT NOT NULL,
               `planIdentity` TEXT NOT NULL,
               `refinancedFromPlanId` TEXT,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `capturedAtEpochMs` INTEGER NOT NULL,
               `closedAtEpochMs` INTEGER,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_emi_plans_emiAccountId` ON `emi_plans` (`emiAccountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_emi_plans_status` ON `emi_plans` (`status`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_emi_plans_planIdentity` ON `emi_plans` (`planIdentity`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_emi_plans_refinancedFromPlanId` ON `emi_plans` (`refinancedFromPlanId`)")

        // ---- P13: emi_installments ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `emi_installments` (
               `id` TEXT NOT NULL,
               `planId` TEXT NOT NULL,
               `installmentNumber` INTEGER NOT NULL,
               `dueDateEpochDay` INTEGER NOT NULL,
               `amountDueMinor` INTEGER,
               `amountPaidMinor` INTEGER,
               `currencyCode` TEXT NOT NULL,
               `status` TEXT NOT NULL,
               `transactionId` TEXT,
               `installmentIdentity` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_emi_installments_planId` ON `emi_installments` (`planId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_emi_installments_status` ON `emi_installments` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_emi_installments_transactionId` ON `emi_installments` (`transactionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_emi_installments_dueDateEpochDay` ON `emi_installments` (`dueDateEpochDay`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_emi_installments_plan_installmentNumber` ON `emi_installments` (`planId`, `installmentNumber`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_emi_installments_installmentIdentity` ON `emi_installments` (`installmentIdentity`)")

        // ---- P13: emi_preclosures ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `emi_preclosures` (
               `id` TEXT NOT NULL,
               `planId` TEXT NOT NULL,
               `occurredAtEpochMs` INTEGER NOT NULL,
               `localDateEpochDay` INTEGER NOT NULL,
               `principalOutstandingMinor` INTEGER,
               `feeMinor` INTEGER,
               `adjustmentMinor` INTEGER,
               `currencyCode` TEXT NOT NULL,
               `kind` TEXT NOT NULL,
               `transactionId` TEXT,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `preclosureIdentity` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_emi_preclosures_planId` ON `emi_preclosures` (`planId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_emi_preclosures_kind` ON `emi_preclosures` (`kind`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_emi_preclosures_transactionId` ON `emi_preclosures` (`transactionId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_emi_preclosures_preclosureIdentity` ON `emi_preclosures` (`preclosureIdentity`)")
    }
}
