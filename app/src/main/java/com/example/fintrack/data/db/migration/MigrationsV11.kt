package com.example.fintrack.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v10 -> v11 (Stage 11, P23 import/export staging + P24 privacy/security).
 *
 * Additive only: five new tables, zero ALTERs on existing tables, no data
 * rewrite. Existing v10 rows are untouched and remain valid.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---- P23 #3: import staging ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `import_staging_rows` (
               `id` TEXT NOT NULL,
               `batchId` TEXT NOT NULL,
               `dataset` TEXT NOT NULL,
               `stableId` TEXT NOT NULL,
               `canonicalRow` TEXT NOT NULL,
               `stagedAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_import_staging_rows_dataset` ON `import_staging_rows` (`dataset`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_import_staging_rows_dataset_stableId` ON `import_staging_rows` (`dataset`, `stableId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_import_staging_rows_batchId` ON `import_staging_rows` (`batchId`)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `import_batches` (
               `id` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               `status` TEXT NOT NULL,
               `formatVersion` INTEGER NOT NULL,
               `schemaVersion` INTEGER NOT NULL,
               `totalStagedRows` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )

        // ---- P24 #6 / module 175: settings profiles ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `settings_profiles` (
               `id` TEXT NOT NULL,
               `name` TEXT NOT NULL,
               `version` INTEGER NOT NULL,
               `aiInterpretationEnabled` INTEGER NOT NULL,
               `autoCategorizationEnabled` INTEGER NOT NULL,
               `exportIncludeRawEvidence` INTEGER NOT NULL,
               `appLockEnabled` INTEGER NOT NULL,
               `featureFlagsJson` TEXT NOT NULL,
               `createdAtEpochMs` INTEGER NOT NULL,
               `updatedAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_settings_profiles_name` ON `settings_profiles` (`name`)")

        // ---- P24 #4: audit log ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `audit_log` (
               `id` TEXT NOT NULL,
               `actionClass` TEXT NOT NULL,
               `entityId` TEXT,
               `actor` TEXT NOT NULL,
               `detail` TEXT,
               `atEpochMs` INTEGER NOT NULL,
               `retention` TEXT NOT NULL,
               PRIMARY KEY(`id`))"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_log_atEpochMs` ON `audit_log` (`atEpochMs`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_log_actionClass` ON `audit_log` (`actionClass`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_log_entityId` ON `audit_log` (`entityId`)")

        // ---- P24 #5: app lock state ----
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `app_lock_state` (
               `id` INTEGER NOT NULL,
               `enabled` INTEGER NOT NULL,
               `lastUnlockedAtEpochMs` INTEGER NOT NULL,
               `state` TEXT NOT NULL,
               `updatedAtEpochMs` INTEGER NOT NULL,
               PRIMARY KEY(`id`))"""
        )
    }
}
