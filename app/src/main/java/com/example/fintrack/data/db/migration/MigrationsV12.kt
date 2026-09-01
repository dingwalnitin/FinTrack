package com.example.fintrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v11 -> v12 (Stage 13, A: payee tagging + D: raw LLM evidence).
 *
 * Additive only:
 *  - new `payee_category_rules` table (feature A),
 *  - new `transaction_evidence` table (feature D),
 *  - `llm_interpretations.rawLlmJson` nullable TEXT column (feature D).
 * No data rewrite; existing rows remain valid (rawLlmJson defaults to null).
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---- Stage 13 (A): per-payee persistent category rule ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `payee_category_rules` (
               `id` TEXT NOT NULL,
               `payeeIdentityHash` TEXT NOT NULL,
               `payeeName` TEXT NOT NULL,
               `vpa` TEXT,
               `categoryId` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               `updatedAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_payee_category_rules_payeeIdentityHash` ON `payee_category_rules` (`payeeIdentityHash`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_payee_category_rules_categoryId` ON `payee_category_rules` (`categoryId`)")

        // ---- Stage 13 (D): durable transaction -> evidence link ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `transaction_evidence` (
               `id` TEXT NOT NULL,
               `transactionId` TEXT NOT NULL,
               `sourceMessageId` TEXT NOT NULL,
               `rawLlmJson` TEXT,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_evidence_transactionId` ON `transaction_evidence` (`transactionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_evidence_sourceMessageId` ON `transaction_evidence` (`sourceMessageId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_transaction_evidence_transactionId_sourceMessageId` ON `transaction_evidence` (`transactionId`, `sourceMessageId`)")

        // ---- Stage 13 (D): raw LLM JSON column on interpretations (nullable) ----
        db.execSQL("ALTER TABLE `llm_interpretations` ADD COLUMN `rawLlmJson` TEXT")
    }
}
