package com.example.fintrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v8 -> v9 (Stage 7, P14 — categorization/merchants/rules/audit; P15 —
 * review queue, splits, reimbursement, travel modes, tags, notes).
 *
 * Additive only. The new tables are append-only history; existing v8 rows
 * (and v6/v7 legacy rows they may point at) remain valid without any data
 * rewrite. Every new link / rule / binding carries a stable identity hash
 * backed by a unique index so re-running parsers / categorization /
 * user-correction flows is idempotent.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---- P14: categories (existing v2 table gains additive columns) ----
        db.execSQL("ALTER TABLE `categories` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'ACTIVE'")
        db.execSQL("ALTER TABLE `categories` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'TAXONOMY'")
        db.execSQL("ALTER TABLE `categories` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `categories` ADD COLUMN `createdAtEpochMs` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_parentId` ON `categories` (`parentId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_status` ON `categories` (`status`)")

        // ---- P14: merchants ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `merchants` (
               `id` TEXT NOT NULL,
               `displayName` TEXT NOT NULL,
               `normalizedName` TEXT NOT NULL,
               `accountId` TEXT,
               `status` TEXT NOT NULL,
               `merchantIdentity` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               `mergedIntoMerchantId` TEXT,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_merchants_merchantIdentity` ON `merchants` (`merchantIdentity`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_merchants_accountId` ON `merchants` (`accountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_merchants_status` ON `merchants` (`status`)")

        // ---- P14: merchant_aliases ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `merchant_aliases` (
               `id` TEXT NOT NULL,
               `merchantId` TEXT NOT NULL,
               `aliasRaw` TEXT NOT NULL,
               `aliasNormalized` TEXT NOT NULL,
               `aliasIdentity` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_merchant_aliases_aliasIdentity` ON `merchant_aliases` (`aliasIdentity`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_merchant_aliases_merchantId` ON `merchant_aliases` (`merchantId`)")

        // ---- P14: category_rules ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `category_rules` (
               `id` TEXT NOT NULL,
               `name` TEXT NOT NULL,
               `priority` INTEGER NOT NULL,
               `status` TEXT NOT NULL,
               `matchKind` TEXT NOT NULL,
               `matchValue` TEXT NOT NULL,
               `merchantId` TEXT,
               `categoryId` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               `createdBy` TEXT NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_category_rules_priority` ON `category_rules` (`priority`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_category_rules_status` ON `category_rules` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_category_rules_merchantId` ON `category_rules` (`merchantId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_category_rules_categoryId` ON `category_rules` (`categoryId`)")

        // ---- P14: llm_category_suggestions ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `llm_category_suggestions` (
               `id` TEXT NOT NULL,
               `transactionId` TEXT NOT NULL,
               `categoryId` TEXT,
               `merchantId` TEXT,
               `confidence` REAL NOT NULL,
               `reason` TEXT,
               `modelId` TEXT NOT NULL,
               `promptVersion` TEXT NOT NULL,
               `schemaVersion` TEXT NOT NULL,
               `suggestionIdentity` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               `accepted` INTEGER NOT NULL,
               `acceptedAtEpochMs` INTEGER,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_llm_category_suggestions_transactionId` ON `llm_category_suggestions` (`transactionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_llm_category_suggestions_categoryId` ON `llm_category_suggestions` (`categoryId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_llm_category_suggestions_suggestionIdentity` ON `llm_category_suggestions` (`suggestionIdentity`)")

        // ---- P14: merchant_vpa_bindings ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `merchant_vpa_bindings` (
               `id` TEXT NOT NULL,
               `merchantId` TEXT NOT NULL,
               `vpa` TEXT NOT NULL,
               `vpaIdentity` TEXT NOT NULL,
               `confirmedByUser` INTEGER NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_merchant_vpa_bindings_vpaIdentity` ON `merchant_vpa_bindings` (`vpaIdentity`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_merchant_vpa_bindings_merchantId` ON `merchant_vpa_bindings` (`merchantId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_merchant_vpa_bindings_confirmedByUser` ON `merchant_vpa_bindings` (`confirmedByUser`)")

        // ---- P14: category_audit ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `category_audit` (
               `id` TEXT NOT NULL,
               `transactionId` TEXT NOT NULL,
               `previousCategoryId` TEXT,
               `newCategoryId` TEXT,
               `previousMerchantId` TEXT,
               `newMerchantId` TEXT,
               `actor` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `reason` TEXT,
               `ruleId` TEXT,
               `atEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_category_audit_transactionId` ON `category_audit` (`transactionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_category_audit_atEpochMs` ON `category_audit` (`atEpochMs`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_category_audit_actor` ON `category_audit` (`actor`)")

        // ---- P15: review_items ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `review_items` (
               `id` TEXT NOT NULL,
               `transactionId` TEXT NOT NULL,
               `reason` TEXT NOT NULL,
               `priority` INTEGER NOT NULL,
               `status` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               `resolvedAtEpochMs` INTEGER,
               `explanation` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_items_transactionId` ON `review_items` (`transactionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_items_status` ON `review_items` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_items_reason` ON `review_items` (`reason`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_review_items_priority` ON `review_items` (`priority`)")

        // ---- P15: transaction_splits ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `transaction_splits` (
               `id` TEXT NOT NULL,
               `parentTransactionId` TEXT NOT NULL,
               `childTransactionId` TEXT NOT NULL,
               `splitIdentity` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_splits_parentTransactionId` ON `transaction_splits` (`parentTransactionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_splits_childTransactionId` ON `transaction_splits` (`childTransactionId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_transaction_splits_parent_child` ON `transaction_splits` (`parentTransactionId`, `childTransactionId`)")

        // ---- P15: reimbursement_links ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `reimbursement_links` (
               `id` TEXT NOT NULL,
               `expenseTransactionId` TEXT NOT NULL,
               `reimbursingTransactionId` TEXT NOT NULL,
               `linkIdentity` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reimbursement_links_expenseTransactionId` ON `reimbursement_links` (`expenseTransactionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reimbursement_links_reimbursingTransactionId` ON `reimbursement_links` (`reimbursingTransactionId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_reimbursement_links_pair` ON `reimbursement_links` (`expenseTransactionId`, `reimbursingTransactionId`)")

        // ---- P15: travel_modes ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `travel_modes` (
               `id` TEXT NOT NULL,
               `accountId` TEXT NOT NULL,
               `label` TEXT NOT NULL,
               `currencyCode` TEXT NOT NULL,
               `startEpochDay` INTEGER NOT NULL,
               `endEpochDay` INTEGER,
               `status` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_travel_modes_accountId` ON `travel_modes` (`accountId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_travel_modes_status` ON `travel_modes` (`status`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_travel_modes_account_start` ON `travel_modes` (`accountId`, `startEpochDay`)")

        // ---- P15: transaction_tags ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `transaction_tags` (
               `id` TEXT NOT NULL,
               `transactionId` TEXT NOT NULL,
               `tag` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_tags_transactionId` ON `transaction_tags` (`transactionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_tags_tag` ON `transaction_tags` (`tag`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_transaction_tags_txn_tag` ON `transaction_tags` (`transactionId`, `tag`)")

        // ---- P15: transaction_notes ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `transaction_notes` (
               `id` TEXT NOT NULL,
               `transactionId` TEXT NOT NULL,
               `note` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               `updatedAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_notes_transactionId` ON `transaction_notes` (`transactionId`)")
    }
}
