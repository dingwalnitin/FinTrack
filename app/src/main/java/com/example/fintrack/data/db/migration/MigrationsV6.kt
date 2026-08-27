package com.example.fintrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v5 -> v6 (Stage 5, P09 + P10).
 *
 * Additive only — no existing data is mutated. Two new tables for P09 dedup
 * artifacts and additive columns on `transactions` and `ledger_entries` for
 * P10 normalized-transaction / postings model. Unknown values stay unknown
 * (new columns are NULLable, with safe defaults for string-state columns).
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---- P09 dedup tables ----

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `evidence_links` (
               `id` TEXT NOT NULL,
               `eventId` TEXT NOT NULL,
               `rawSmsId` TEXT NOT NULL,
               `linkIdentity` TEXT NOT NULL,
               `linkKind` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `sourceReason` TEXT,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_evidence_links_eventId_rawSmsId` ON `evidence_links` (`eventId`, `rawSmsId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_evidence_links_eventId` ON `evidence_links` (`eventId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_evidence_links_rawSmsId` ON `evidence_links` (`rawSmsId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_evidence_links_linkIdentity` ON `evidence_links` (`linkIdentity`)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `dedupe_clusters` (
               `id` TEXT NOT NULL,
               `clusterIdentity` TEXT NOT NULL,
               `status` TEXT NOT NULL,
               `topScore` REAL NOT NULL,
               `verdict` TEXT NOT NULL,
               `reasonsJson` TEXT NOT NULL,
               `canonicalEventId` TEXT,
               `createdAtEpochMs` INTEGER NOT NULL,
               `updatedAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_dedupe_clusters_clusterIdentity` ON `dedupe_clusters` (`clusterIdentity`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dedupe_clusters_status` ON `dedupe_clusters` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dedupe_clusters_createdAtEpochMs` ON `dedupe_clusters` (`createdAtEpochMs`)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `dedupe_cluster_members` (
               `id` TEXT NOT NULL,
               `clusterId` TEXT NOT NULL,
               `eventId` TEXT NOT NULL,
               `score` REAL NOT NULL,
               `signalBreakdownJson` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_dedupe_cluster_members_clusterId_eventId` ON `dedupe_cluster_members` (`clusterId`, `eventId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dedupe_cluster_members_eventId` ON `dedupe_cluster_members` (`eventId`)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `dedupe_decisions` (
               `id` TEXT NOT NULL,
               `decisionEventId` TEXT NOT NULL,
               `clusterId` TEXT,
               `decisionKind` TEXT NOT NULL,
               `actor` TEXT NOT NULL,
               `sourceKind` TEXT NOT NULL,
               `sourceVersion` TEXT NOT NULL,
               `reason` TEXT,
               `appliedAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_dedupe_decisions_eventId_kind_appliedAt` ON `dedupe_decisions` (`decisionEventId`, `decisionKind`, `appliedAtEpochMs`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dedupe_decisions_clusterId` ON `dedupe_decisions` (`clusterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dedupe_decisions_eventId` ON `dedupe_decisions` (`decisionEventId`)")

        // ---- P10 normalized transaction / postings columns ----

        // Transaction taxonomy (P10 #1, #2): kind + subtype explicit, status,
        // merchant, description, rail. Each is nullable / has a safe default
        // so legacy rows remain valid and unknown values stay unknown.
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'UNKNOWN'")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `subtype` TEXT")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'POSTED'")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `merchant` TEXT")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `description` TEXT")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `rail` TEXT")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `cardMask` TEXT")
        // P10 #4 posting identity: explicit posting-group id so edits can
        // replace the entire posting set without losing history.
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `postingGroupId` TEXT")
        // P10 #5 soft-delete tombstone (P11 reuses it but we add the column
        // now to keep schema linear and edits forward-only).
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `deletedAtEpochMs` INTEGER")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `deletedReason` TEXT")
        // P10 #7 absorbing module 142: explicit lifecycle status separate
        // from the older `state` column. New column supersedes; old kept for
        // back-compat with v5 writers.
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_status` ON `transactions` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_kind` ON `transactions` (`kind`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_postingGroupId` ON `transactions` (`postingGroupId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_deletedAtEpochMs` ON `transactions` (`deletedAtEpochMs`)")

        // LedgerEntry enrichment (P10 #4): postingGroupId lets us
        // transactionally replace a posting set on edit. Account-typed post
        // axis is unchanged.
        db.execSQL("ALTER TABLE `ledger_entries` ADD COLUMN `postingGroupId` TEXT")
        db.execSQL("ALTER TABLE `ledger_entries` ADD COLUMN `memo` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_entries_postingGroupId` ON `ledger_entries` (`postingGroupId`)")
    }
}
