package com.example.fintrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration discipline:
 *  - every version bump gets a MIGRATION_x_y object registered in MIGRATIONS
 *  - forward-only; destructive fallback is forbidden for versions > 1
 *  - each migration has a test (MigrationTest) using exported schemas
 */
object Migrations {

    /**
     * Migration discipline:
     *  - every version bump gets a MIGRATION_x_y object registered in MIGRATIONS
     *  - forward-only; destructive fallback is forbidden for versions > 1
     *  - each migration has a test (MigrationTest) using exported schemas
     *
     * Schema history:
     *  v1 — messages, transactions (foundation)
     *  v2 — Bible blueprint (accounts, categories, ledger, transfers, jobs, audit)
     *  v3 — accounts authoritative (openings, snapshots, sender mappings, aliases)
     *  v4 — SMS evidence (raw_sms, cursor, progress)
     *  v5 — LLM enrichment (jobs, interpretations, cache, usage, metrics)
     *  v6 — P09 dedup artifacts + P10 normalized transaction / postings
     *  v7 — P11 refund_links + transaction_links + transferGroupId
     *  v8 — P12 credit cards + P13 EMI plans
     *  v9 — P14 categorization/merchants/rules/audit + P15 review/splits/links
     *  v10 — P16 budgets + P17 recurring/subscriptions + P18 cash/ATM links
     *  v11 — Stage 11: import staging + settings profiles + audit log + app lock
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `accounts` (
                   `id` TEXT NOT NULL, `name` TEXT NOT NULL, `normalizedName` TEXT NOT NULL,
                   `currencyCode` TEXT NOT NULL, `accountType` TEXT NOT NULL,
                   `createdAtEpochMs` INTEGER NOT NULL, `lifecycle` TEXT NOT NULL,
                   PRIMARY KEY(`id`))"""
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_accounts_normalized_name` ON `accounts` (`normalizedName`)")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `categories` (
                   `id` TEXT NOT NULL, `name` TEXT NOT NULL, `normalizedName` TEXT NOT NULL,
                   `parentId` TEXT, PRIMARY KEY(`id`))"""
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_normalizedName` ON `categories` (`normalizedName`)")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `ledger_entries` (
                   `id` TEXT NOT NULL, `transactionId` TEXT NOT NULL, `accountId` TEXT NOT NULL,
                   `direction` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `currencyCode` TEXT NOT NULL,
                   PRIMARY KEY(`id`))"""
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_entries_transactionId` ON `ledger_entries` (`transactionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_entries_accountId` ON `ledger_entries` (`accountId`)")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `transfers` (
                   `id` TEXT NOT NULL, `fromEntryId` TEXT NOT NULL, `toEntryId` TEXT NOT NULL,
                   `kind` TEXT NOT NULL, `sourceKind` TEXT NOT NULL, `sourceVersion` TEXT NOT NULL,
                   PRIMARY KEY(`id`))"""
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfers_fromEntryId` ON `transfers` (`fromEntryId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_transfers_toEntryId` ON `transfers` (`toEntryId`)")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `processing_jobs` (
                   `id` TEXT NOT NULL, `jobIdentity` TEXT NOT NULL, `jobType` TEXT NOT NULL,
                   `payloadRef` TEXT NOT NULL, `status` TEXT NOT NULL, `attempts` INTEGER NOT NULL,
                   `maxAttempts` INTEGER NOT NULL, `lastError` TEXT, `nextAttemptAtEpochMs` INTEGER NOT NULL,
                   PRIMARY KEY(`id`))"""
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_processing_jobs_status` ON `processing_jobs` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_processing_jobs_nextAttemptAtEpochMs` ON `processing_jobs` (`nextAttemptAtEpochMs`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_processing_jobs_jobIdentity` ON `processing_jobs` (`jobIdentity`)")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `audit_events` (
                   `id` TEXT NOT NULL, `entityId` TEXT NOT NULL, `entityType` TEXT NOT NULL,
                   `action` TEXT NOT NULL, `actor` TEXT NOT NULL, `detailReason` TEXT,
                   `atEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))"""
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_entityId` ON `audit_events` (`entityId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_atEpochMs` ON `audit_events` (`atEpochMs`)")

            // Enrich existing transactions table (v1 rows keep their data).
            val cols = listOf(
                "accountId TEXT NOT NULL DEFAULT ''",
                "categoryId TEXT",
                "localDateEpochDay INTEGER NOT NULL DEFAULT 0",
                "counterpartyNormalized TEXT",
                "referenceId TEXT",
                "sourceReason TEXT",
                "correctionSourceReason TEXT",
                "dedupeKey TEXT NOT NULL DEFAULT ''"
            )
            cols.forEach { db.execSQL("ALTER TABLE `transactions` ADD COLUMN $it") }

            // Backfill derived columns deterministically from existing data.
            db.execSQL("UPDATE `transactions` SET localDateEpochDay = occurredAtEpochMs / 86400000")
            db.execSQL(
                """UPDATE `transactions` SET dedupeKey =
                   hex(quote(id)) -- stable fallback: id-based dedupe key for legacy rows"""
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_dedupeKey` ON `transactions` (`dedupeKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_messageId` ON `transactions` (`messageId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_occurredAtEpochMs` ON `transactions` (`occurredAtEpochMs`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_accountId_occurredAtEpochMs` ON `transactions` (`accountId`, `occurredAtEpochMs`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_counterpartyNormalized` ON `transactions` (`counterpartyNormalized`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_state` ON `transactions` (`state`)")

            // Evidence dedupe index on messages.
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_sourceHash` ON `messages` (`sourceHash`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_receivedAtEpochMs` ON `messages` (`receivedAtEpochMs`)")
        }
    }

    /** v2 -> v3: accounts become authoritative balance containers. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Enrich accounts with identity hints (nullable — unknown stays unknown).
            db.execSQL("ALTER TABLE `accounts` ADD COLUMN `nickname` TEXT")
            db.execSQL("ALTER TABLE `accounts` ADD COLUMN `last4` TEXT")
            db.execSQL("ALTER TABLE `accounts` ADD COLUMN `institutionName` TEXT")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `account_opening_balances` (
                   `id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL,
                   `currencyCode` TEXT NOT NULL, `asOfEpochMs` INTEGER NOT NULL,
                   PRIMARY KEY(`id`))"""
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_account_opening_balances_accountId` ON `account_opening_balances` (`accountId`)")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `balance_snapshots` (
                   `id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL,
                   `currencyCode` TEXT NOT NULL, `kind` TEXT NOT NULL, `messageId` TEXT,
                   `capturedAtEpochMs` INTEGER NOT NULL, `sourceKind` TEXT NOT NULL,
                   `sourceVersion` TEXT NOT NULL, `snapshotIdentity` TEXT NOT NULL,
                   PRIMARY KEY(`id`))"""
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_balance_snapshots_accountId_capturedAtEpochMs` ON `balance_snapshots` (`accountId`, `capturedAtEpochMs`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_balance_snapshots_snapshotIdentity` ON `balance_snapshots` (`snapshotIdentity`)")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `sender_account_mappings` (
                   `id` TEXT NOT NULL, `senderId` TEXT NOT NULL, `accountId` TEXT NOT NULL,
                   `confirmedByUser` INTEGER NOT NULL, `sourceKind` TEXT NOT NULL,
                   `sourceVersion` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL,
                   PRIMARY KEY(`id`))"""
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sender_account_mappings_senderId_accountId` ON `sender_account_mappings` (`senderId`, `accountId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sender_account_mappings_accountId` ON `sender_account_mappings` (`accountId`)")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `institution_aliases` (
                   `id` TEXT NOT NULL, `aliasRaw` TEXT NOT NULL, `aliasNormalized` TEXT NOT NULL,
                   `canonicalInstitution` TEXT NOT NULL, `confirmedByUser` INTEGER NOT NULL,
                   PRIMARY KEY(`id`))"""
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_institution_aliases_aliasNormalized` ON `institution_aliases` (`aliasNormalized`)")
        }
    }

    /**
     * v3 -> v4: SMS evidence acquisition. Adds raw_sms, sms_backfill_cursor and
     * sms_ingestion_progress. No existing data is mutated.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `raw_sms` (
                   `id` TEXT NOT NULL,
                   `providerId` INTEGER NOT NULL,
                   `sender` TEXT,
                   `receivedAtEpochMs` INTEGER NOT NULL,
                   `body` TEXT NOT NULL,
                   `contentHash` TEXT NOT NULL,
                   `sourceKind` TEXT NOT NULL,
                   `sourceVersion` TEXT NOT NULL,
                   `capturedAtEpochMs` INTEGER NOT NULL,
                   PRIMARY KEY(`id`))"""
            )
            // Idempotency: the user SMS database's row id is the durable key.
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_raw_sms_providerId` ON `raw_sms` (`providerId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_sms_receivedAtEpochMs` ON `raw_sms` (`receivedAtEpochMs`)")
            // Cross-provider dedupe in case the system SMS database rebuilds ids.
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_raw_sms_contentHash` ON `raw_sms` (`contentHash`)")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `sms_backfill_cursor` (
                   `id` INTEGER NOT NULL,
                   `lastProviderId` INTEGER,
                   `startedAtEpochMs` INTEGER NOT NULL,
                   `lastUpdatedAtEpochMs` INTEGER NOT NULL,
                   `status` TEXT NOT NULL,
                   `totalSeen` INTEGER NOT NULL,
                   `totalPersisted` INTEGER NOT NULL,
                   `totalDuplicate` INTEGER NOT NULL,
                   PRIMARY KEY(`id`))"""
            )

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `sms_ingestion_progress` (
                   `id` INTEGER NOT NULL,
                   `totalPersisted` INTEGER NOT NULL,
                   `lastUpdatedAtEpochMs` INTEGER NOT NULL,
                   `status` TEXT NOT NULL,
                   `lastError` TEXT,
                   PRIMARY KEY(`id`))"""
            )
        }
    }

    /**
     * v4 -> v5: LLM enrichment pipeline (Stage 4). Adds llm_jobs,
     * llm_interpretations, llm_response_cache, llm_usage_counters and
     * llm_metrics. No existing data is mutated; advisory-only tables.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `llm_jobs` (
                   `id` TEXT NOT NULL,
                   `jobIdentity` TEXT NOT NULL,
                   `sourceMessageId` TEXT NOT NULL,
                   `senderHash` TEXT,
                   `priority` INTEGER NOT NULL,
                   `status` TEXT NOT NULL,
                   `attempts` INTEGER NOT NULL,
                   `maxAttempts` INTEGER NOT NULL,
                   `nextRetryAtEpochMs` INTEGER NOT NULL,
                   `claimedAtEpochMs` INTEGER,
                   `claimedByWorker` TEXT,
                   `promptVersion` TEXT NOT NULL,
                   `schemaVersion` TEXT NOT NULL,
                   `providerId` TEXT NOT NULL,
                   `lastErrorClass` TEXT,
                   `createdAtEpochMs` INTEGER NOT NULL,
                   `updatedAtEpochMs` INTEGER NOT NULL,
                   PRIMARY KEY(`id`))"""
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_llm_jobs_jobIdentity` ON `llm_jobs` (`jobIdentity`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llm_jobs_status_nextRetryAtEpochMs` ON `llm_jobs` (`status`, `nextRetryAtEpochMs`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llm_jobs_priority` ON `llm_jobs` (`priority`)")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `llm_interpretations` (
                   `id` TEXT NOT NULL,
                   `sourceMessageId` TEXT NOT NULL,
                   `responseHash` TEXT NOT NULL,
                   `promptVersion` TEXT NOT NULL,
                   `schemaVersion` TEXT NOT NULL,
                   `providerId` TEXT NOT NULL,
                   `modelId` TEXT NOT NULL,
                   `amountMinor` INTEGER,
                   `currencyCode` TEXT,
                   `direction` TEXT,
                   `accountToken` TEXT,
                   `rail` TEXT,
                   `counterpartyRaw` TEXT,
                   `counterpartyNormalized` TEXT,
                   `categorySuggestion` TEXT,
                   `transferTargetToken` TEXT,
                   `recurring` INTEGER,
                   `emiDetail` TEXT,
                   `occurredAtEpochMs` INTEGER,
                   `confidenceAmount` REAL,
                   `confidenceDirection` REAL,
                   `confidenceAccount` REAL,
                   `confidenceRail` REAL,
                   `confidenceCounterparty` REAL,
                   `confidenceCategory` REAL,
                   `confidenceTransferTarget` REAL,
                   `confidenceRecurring` REAL,
                   `confidenceEmi` REAL,
                   `evidenceExplanationsJson` TEXT NOT NULL,
                   `overallConfidence` REAL,
                   `latencyMs` INTEGER NOT NULL,
                   `tokensPrompt` INTEGER NOT NULL,
                   `tokensCompletion` INTEGER NOT NULL,
                   `fromCache` INTEGER NOT NULL,
                   `createdAtEpochMs` INTEGER NOT NULL,
                   PRIMARY KEY(`id`))"""
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_llm_interpretations_responseHash` ON `llm_interpretations` (`responseHash`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llm_interpretations_sourceMessageId` ON `llm_interpretations` (`sourceMessageId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_llm_interpretations_promptVersion` ON `llm_interpretations` (`promptVersion`)")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `llm_response_cache` (
                   `id` TEXT NOT NULL,
                   `cacheKey` TEXT NOT NULL,
                   `validatedResponseJson` TEXT NOT NULL,
                   `promptVersion` TEXT NOT NULL,
                   `schemaVersion` TEXT NOT NULL,
                   `providerId` TEXT NOT NULL,
                   `modelId` TEXT NOT NULL,
                   `createdAtEpochMs` INTEGER NOT NULL,
                   PRIMARY KEY(`id`))"""
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_llm_response_cache_cacheKey` ON `llm_response_cache` (`cacheKey`)")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `llm_usage_counters` (
                   `id` TEXT NOT NULL,
                   `bucketDayUtc` INTEGER NOT NULL,
                   `requests` INTEGER NOT NULL,
                   `cacheHits` INTEGER NOT NULL,
                   `tokensPrompt` INTEGER NOT NULL,
                   `tokensCompletion` INTEGER NOT NULL,
                   `validationFailures` INTEGER NOT NULL,
                   `retries` INTEGER NOT NULL,
                   `updatedAtEpochMs` INTEGER NOT NULL,
                   PRIMARY KEY(`id`))"""
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_llm_usage_counters_bucketDayUtc` ON `llm_usage_counters` (`bucketDayUtc`)")

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `llm_metrics` (
                   `id` TEXT NOT NULL,
                   `metricName` TEXT NOT NULL,
                   `value` INTEGER NOT NULL,
                   `updatedAtEpochMs` INTEGER NOT NULL,
                   PRIMARY KEY(`id`))"""
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_llm_metrics_metricName` ON `llm_metrics` (`metricName`)")
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        com.example.fintrack.data.db.migration.MIGRATION_5_6,
        com.example.fintrack.data.db.migration.MIGRATION_6_7,
        com.example.fintrack.data.db.migration.MIGRATION_7_8,
        com.example.fintrack.data.db.migration.MIGRATION_8_9,
        com.example.fintrack.data.db.migration.MIGRATION_9_10,
        com.example.fintrack.data.db.migration.MIGRATION_10_11,
        com.example.fintrack.data.db.migration.MIGRATION_11_12,
    )
}
