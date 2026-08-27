package com.example.fintrack.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v11 Stage 11 additive entities (P23 import/export + P24 privacy/security).
 *
 * Design invariants (App Bible + stage contract):
 *  - `import_staging_*` tables are TEMPORARY BY CONTRACT: rows exist only
 *    between "stage validated file" and "commit / clear". They are never
 *    read by any finance feature and never counted as live data. Staging
 *    before commit is what makes import preview safe.
 *  - `settings_profiles` (module 175) is deliberately separate from all
 *    financial tables. A profile export contains preferences only.
 *  - `audit_log` (P24 #4) records money-changing and sensitive actions with
 *    a retention boundary; it stores ids and action classes, never secrets
 *    and never raw SMS bodies.
 *  - `app_lock_state` (P24 #5) is a singleton row; the lock secret itself
 *    lives in Android Keystore-backed storage, NOT in Room.
 */

// ---- P23 #3: import staging ----

@Entity(
    tableName = "import_staging_rows",
    indices = [
        Index("dataset"),
        Index(value = ["dataset", "stableId"], unique = true),
        Index("batchId"),
    ],
)
data class ImportStagingRowEntity(
    @PrimaryKey val id: String,
    /** Groups one staged import session so concurrent previews can't mix. */
    val batchId: String,
    /** BackupDataset.name */
    val dataset: String,
    /** Stable id of the row inside its dataset (e.g. transactions.id). */
    val stableId: String,
    /** Canonical key=value serialization of the staged row. */
    val canonicalRow: String,
    val stagedAtEpochMs: Long,
)

/** One staged import session (so preview/commit operate on the right batch). */
@Entity(
    tableName = "import_batches",
)
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    val createdAtEpochMs: Long,
    /** STAGED | PREVIEWED | COMMITTED | CLEARED | FAILED */
    val status: String,
    val formatVersion: Int,
    val schemaVersion: Int,
    val totalStagedRows: Int,
)

// ---- P24 #6 / module 175: settings profiles ----

@Entity(
    tableName = "settings_profiles",
    indices = [Index(value = ["name"], unique = true)],
)
data class SettingsProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: Int,
    val aiInterpretationEnabled: Boolean,
    val autoCategorizationEnabled: Boolean,
    val exportIncludeRawEvidence: Boolean,
    val appLockEnabled: Boolean,
    /** MiniJson-encoded map of safe local feature flags. */
    val featureFlagsJson: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

// ---- P24 #4: audit log for money-changing / sensitive actions ----

@Entity(
    tableName = "audit_log",
    indices = [
        Index("atEpochMs"),
        Index("actionClass"),
        Index("entityId"),
    ],
)
data class AuditLogEntryEntity(
    @PrimaryKey val id: String,
    /** e.g. TRANSACTION_WRITE, IMPORT_COMMIT, EXPORT, APP_UNLOCK, SETTINGS_CHANGE */
    val actionClass: String,
    val entityId: String?,
    val actor: String,                 // USER | SYSTEM
    /** Short sanitized reason; never raw evidence, never secrets. */
    val detail: String?,
    val atEpochMs: Long,
    /** Retention bucket the row was written under (AuditRetention name). */
    val retention: String,
)

// ---- P24 #5: app lock lifecycle state (secret stays in Keystore) ----

@Entity(
    tableName = "app_lock_state",
)
data class AppLockStateEntity(
    @PrimaryKey val id: Int,           // always 1 — singleton
    val enabled: Boolean,
    /** Epoch ms of last successful unlock; used for grace-period relock. */
    val lastUnlockedAtEpochMs: Long,
    /** LOCKED | UNLOCKED | DISABLED */
    val state: String,
    val updatedAtEpochMs: Long,
)
