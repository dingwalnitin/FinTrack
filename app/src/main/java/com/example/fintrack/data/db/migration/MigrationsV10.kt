package com.example.fintrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v9 -> v10 (Stage 8, P16 budgets, P17 recurring/subscriptions,
 * P18 cash reconciliation + ATM links).
 *
 * Additive only. All new tables are independent of existing rows; existing
 * v9 data is untouched. Every table carries a stable identity hash backed by
 * a unique index so re-running detection / budget recomputation is idempotent.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---- P16: budgets ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `budgets` (
               `id` TEXT NOT NULL,
               `name` TEXT NOT NULL,
               `scopeKind` TEXT NOT NULL,
               `categoryId` TEXT,
               `accountId` TEXT,
               `periodType` TEXT NOT NULL,
               `startDayOfMonth` INTEGER NOT NULL,
               `targetAmountMinor` INTEGER NOT NULL,
               `currencyCode` TEXT NOT NULL,
               `rolloverEnabled` INTEGER NOT NULL,
               `rolloverCapMinor` INTEGER,
               `exclusionsJson` TEXT NOT NULL,
               `scopeIdentity` TEXT NOT NULL,
               `status` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_budgets_categoryId` ON `budgets` (`categoryId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_budgets_accountId` ON `budgets` (`accountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_budgets_status` ON `budgets` (`status`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_budgets_scopeIdentity` ON `budgets` (`scopeIdentity`)")

        // ---- P16: budget_periods ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `budget_periods` (
               `id` TEXT NOT NULL,
               `budgetId` TEXT NOT NULL,
               `periodStartEpochDay` INTEGER NOT NULL,
               `periodEndEpochDay` INTEGER NOT NULL,
               `rolloverInMinor` INTEGER NOT NULL,
               `boundaryAction` TEXT NOT NULL,
               `computedAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_budget_periods_budgetId_periodStartEpochDay` ON `budget_periods` (`budgetId`, `periodStartEpochDay`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_budget_periods_budgetId` ON `budget_periods` (`budgetId`)")

        // ---- P17: recurring_patterns ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `recurring_patterns` (
               `id` TEXT NOT NULL,
               `patternIdentity` TEXT NOT NULL,
               `accountId` TEXT NOT NULL,
               `counterpartyNormalized` TEXT,
               `merchant` TEXT,
               `categoryId` TEXT,
               `periodicity` TEXT NOT NULL,
               `intervalDays` INTEGER NOT NULL,
               `canonicalAmountMinor` INTEGER NOT NULL,
               `minObservedAmountMinor` INTEGER NOT NULL,
               `maxObservedAmountMinor` INTEGER NOT NULL,
               `currencyCode` TEXT NOT NULL,
               `confidence` REAL NOT NULL,
               `firstSeenEpochMs` INTEGER NOT NULL,
               `lastSeenEpochMs` INTEGER NOT NULL,
               `nextExpectedEpochMs` INTEGER,
               `status` TEXT NOT NULL,
               `isSubscription` INTEGER,
               `decidedBy` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               `updatedAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recurring_patterns_patternIdentity` ON `recurring_patterns` (`patternIdentity`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_patterns_accountId` ON `recurring_patterns` (`accountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_patterns_categoryId` ON `recurring_patterns` (`categoryId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_patterns_status` ON `recurring_patterns` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_patterns_nextExpectedEpochMs` ON `recurring_patterns` (`nextExpectedEpochMs`)")

        // ---- P17: recurring_observations ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `recurring_observations` (
               `id` TEXT NOT NULL,
               `patternId` TEXT NOT NULL,
               `transactionId` TEXT NOT NULL,
               `amountMinor` INTEGER NOT NULL,
               `occurredAtEpochMs` INTEGER NOT NULL,
               `observationIdentity` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recurring_observations_observationIdentity` ON `recurring_observations` (`observationIdentity`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_observations_patternId` ON `recurring_observations` (`patternId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_observations_transactionId` ON `recurring_observations` (`transactionId`)")

        // ---- P18: cash_reconciliations ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `cash_reconciliations` (
               `id` TEXT NOT NULL,
               `accountId` TEXT NOT NULL,
               `countedMinor` INTEGER NOT NULL,
               `ledgerDerivedMinor` INTEGER NOT NULL,
               `differenceMinor` INTEGER NOT NULL,
               `outcome` TEXT NOT NULL,
               `adjustmentTransactionId` TEXT,
               `reason` TEXT,
               `reconciliationIdentity` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `atEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cash_reconciliations_accountId` ON `cash_reconciliations` (`accountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cash_reconciliations_atEpochMs` ON `cash_reconciliations` (`atEpochMs`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cash_reconciliations_reconciliationIdentity` ON `cash_reconciliations` (`reconciliationIdentity`)")

        // ---- P18: atm_cash_links ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `atm_cash_links` (
               `id` TEXT NOT NULL,
               `withdrawalTransactionId` TEXT NOT NULL,
               `cashAccountId` TEXT NOT NULL,
               `amountMinor` INTEGER NOT NULL,
               `currencyCode` TEXT NOT NULL,
               `withdrawalOccurredAtEpochMs` INTEGER NOT NULL,
               `matchedBy` TEXT NOT NULL,
               `candidateCount` INTEGER NOT NULL,
               `ambiguous` INTEGER NOT NULL,
               `confirmedByUser` INTEGER NOT NULL,
               `linkIdentity` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_atm_cash_links_linkIdentity` ON `atm_cash_links` (`linkIdentity`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_atm_cash_links_withdrawalTransactionId` ON `atm_cash_links` (`withdrawalTransactionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_atm_cash_links_cashAccountId` ON `atm_cash_links` (`cashAccountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_atm_cash_links_confirmedByUser` ON `atm_cash_links` (`confirmedByUser`)")
    }
}
