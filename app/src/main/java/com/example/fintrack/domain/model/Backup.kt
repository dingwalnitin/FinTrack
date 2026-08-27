package com.example.fintrack.domain.model

import java.time.Instant

/**
 * Stage 11 P23 — portable backup / restore domain model.
 *
 * Design invariants (App Bible + stage contract):
 *  - An export is a VERSIONED MANIFEST plus per-entity datasets. The manifest
 *    carries the app schema version, dataset names, row counts and content
 *    checksums so an import can validate BEFORE any live data is touched.
 *  - Serialization is deterministic: the same database state always produces
 *    byte-identical export payloads (sorted keys, stable ordering, canonical
 *    JSON). This makes checksums meaningful and repeated exports diffable.
 *  - Raw SMS evidence is NEVER exported by default: it is immutable evidence,
 *    not truth, and it is the most sensitive content in the database. The
 *    user may explicitly opt in to including evidence bodies.
 *  - Provider API keys, tokens and other secrets are structurally excluded:
 *    no dataset for them exists in the export vocabulary at all.
 *  - Restore is a two-phase flow: validate/preview against staging, then an
 *    explicit user-confirmed merge. Nothing writes to live tables before the
 *    user confirms.
 */

/** Export format version — independent of the Room schema version. */
const val BACKUP_FORMAT_VERSION = 1

/** App schema version this build exports/restores (mirrors FinTrackDatabaseV2). */
const val BACKUP_SCHEMA_VERSION = 10

/**
 * Datasets that participate in backup/restore. Each maps to one or more
 * Room tables. Secrets-bearing stores (LLM provider config) are deliberately
 * absent from this enum so they cannot leak into an export by accident.
 */
enum class BackupDataset(val entityName: String) {
    ACCOUNTS("accounts"),
    CATEGORIES("categories"),
    TRANSACTIONS("transactions"),
    LEDGER_ENTRIES("ledger_entries"),
    TRANSFERS("transfers"),
    OPENING_BALANCES("account_opening_balances"),
    BALANCE_SNAPSHOTS("balance_snapshots"),
    SENDER_MAPPINGS("sender_account_mappings"),
    INSTITUTION_ALIASES("institution_aliases"),
    REFUND_LINKS("refund_links"),
    TRANSACTION_LINKS("transaction_links"),
    MERCHANTS("merchants"),
    MERCHANT_ALIASES("merchant_aliases"),
    CATEGORY_RULES("category_rules"),
    VPA_BINDINGS("merchant_vpa_bindings"),
    REVIEW_ITEMS("review_items"),
    SPLITS("transaction_splits"),
    REIMBURSEMENT_LINKS("reimbursement_links"),
    TRAVEL_MODES("travel_modes"),
    TAGS("transaction_tags"),
    NOTES("transaction_notes"),
    BUDGETS("budgets"),
    BUDGET_PERIODS("budget_periods"),
    RECURRING_PATTERNS("recurring_patterns"),
    RECURRING_OBSERVATIONS("recurring_observations"),
    CASH_RECONCILIATIONS("cash_reconciliations"),
    ATM_CASH_LINKS("atm_cash_links"),
}

/** Per-dataset integrity block inside the manifest. */
data class BackupDatasetManifest(
    val dataset: BackupDataset,
    val rowCount: Int,
    /** sha-256 over the dataset's canonical serialized rows. */
    val sha256: String,
)

/**
 * Versioned export manifest. Written as the first record of every export;
 * validated first on import.
 */
data class BackupManifest(
    val formatVersion: Int,
    val schemaVersion: Int,
    val createdAtEpochMs: Long,
    val createdByVersion: String,
    val datasets: List<BackupDatasetManifest>,
    /** True when the payload was written with evidence bodies included. */
    val includesRawEvidence: Boolean,
    /** True when the payload is encrypted (password-derived key). */
    val encrypted: Boolean,
    /** Redaction engine version applied to any free-text fields. */
    val redactionVersion: String,
) {
    init {
        require(formatVersion > 0)
        require(schemaVersion > 0)
        require(createdByVersion.isNotBlank())
    }

    fun manifestFor(dataset: BackupDataset): BackupDatasetManifest? =
        datasets.firstOrNull { it.dataset == dataset }
}

// ---------------------------------------------------------------------------
// Import preview / validation results
// ---------------------------------------------------------------------------

sealed interface ImportValidation {
    /** File parsed, schema/format versions understood, checksums verified. */
    data class Valid(
        val manifest: BackupManifest,
        val counts: Map<BackupDataset, Int>,
    ) : ImportValidation

    /** Structural problem — file cannot be imported at all. */
    data class Invalid(val reasons: List<String>) : ImportValidation {
        constructor(reason: String) : this(listOf(reason))
    }
}

/** One conflict between an imported row and a live row with the same stable id. */
data class ImportConflict(
    val dataset: BackupDataset,
    val stableId: String,
    /** Human-readable summary of which fields differ. */
    val differenceSummary: String,
    /**
     * True when the imported row is byte-identical to the live row after
     * normalization — i.e. re-importing the same export. These are NOT
     * conflicts; they are idempotent no-ops.
     */
    val identical: Boolean,
)

/**
 * Result of previewing an import against live data. Computed entirely from
 * staged rows — nothing has been committed yet.
 */
data class ImportPreview(
    val manifest: BackupManifest,
    val newRows: Map<BackupDataset, Int>,
    val identicalRows: Map<BackupDataset, Int>,
    val conflicts: List<ImportConflict>,
    val missingReferences: List<String>,
) {
    fun totalNew(): Int = newRows.values.sum()
    fun totalIdentical(): Int = identicalRows.values.sum()

    /** Conflicts that genuinely need a user decision. */
    fun realConflicts(): List<ImportConflict> = conflicts.filterNot { it.identical }
}

/** User's merge decision for conflicting rows. */
enum class MergePolicy {
    /** Keep the live row; only insert rows that don't exist locally. */
    KEEP_LIVE,
    /** Overwrite the live row with the imported row (explicit user choice). */
    REPLACE_WITH_IMPORTED,
    /** Abort the whole import when any real conflict exists. */
    ABORT_ON_CONFLICT,
}

/** Outcome of a committed import. */
sealed interface ImportCommitResult {
    data class Committed(
        val insertedByDataset: Map<BackupDataset, Int>,
        val replacedByDataset: Map<BackupDataset, Int>,
        val skippedConflicts: Int,
    ) : ImportCommitResult

    /** Aborted before any write (e.g. ABORT_ON_CONFLICT policy hit). */
    data class Aborted(val reason: String) : ImportCommitResult

    data class Failed(val reason: String) : ImportCommitResult
}
