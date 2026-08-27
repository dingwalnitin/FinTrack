package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.BackupDataset
import com.example.fintrack.domain.model.BackupManifest
import com.example.fintrack.domain.model.ImportCommitResult
import com.example.fintrack.domain.model.ImportConflict
import com.example.fintrack.domain.model.ImportPreview
import com.example.fintrack.domain.model.ImportValidation
import com.example.fintrack.domain.model.MergePolicy

/**
 * Stage 11 P23 — persistence contract for backup/restore.
 *
 * The domain layer speaks in canonical ROW STRINGS: each row is a stable,
 * deterministic key=value serialization produced by [BackupCodec]. This keeps
 * the service pure (no Room, no JSON library) and makes checksums/identity
 * computable without any storage dependency.
 */
interface BackupSink {

    // ---- export reads ----
    suspend fun exportRows(dataset: BackupDataset): List<String>

    // ---- staging (temporary tables; never live data) ----
    /** Create a fresh staging batch; clears any previous one. */
    suspend fun beginBatch(formatVersion: Int, schemaVersion: Int, totalRows: Int)
    suspend fun stageRows(dataset: BackupDataset, rows: List<String>)
    suspend fun stagedDatasets(): List<BackupDataset>
    suspend fun stagedRowCount(dataset: BackupDataset): Int
    suspend fun stagedIds(dataset: BackupDataset): List<String>
    /** Canonical staged row for a stable id, or null. */
    suspend fun stagedRowById(dataset: BackupDataset, stableId: String): String?
    suspend fun clearStaging()

    /**
     * Commit staged rows into live tables inside ONE Room @Transaction.
     *
     * @param replaceIds per dataset, the staged stable ids the user chose to
     *        REPLACE (only honored under REPLACE_WITH_IMPORTED).
     * @return per-dataset inserted / replaced counts.
     */
    suspend fun commitStaged(
        policy: MergePolicy,
        replaceIds: Map<BackupDataset, Set<String>>,
    ): Pair<Map<BackupDataset, Int>, Map<BackupDataset, Int>>

    /** Live row for a stable id, canonical form — used for conflict detection. */
    suspend fun liveRowById(dataset: BackupDataset, stableId: String): String?

    /** All live stable ids for a dataset. */
    suspend fun liveIds(dataset: BackupDataset): List<String>
}

/**
 * Stage 11 P23 #1/#3/#4 — export/import orchestration.
 *
 * Guarantees:
 *  - Export: deterministic serialization + manifest with sha-256 per dataset;
 *    no secrets dataset exists in the vocabulary at all.
 *  - Import: validate → stage → preview → explicit user merge → commit.
 *    Live tables are only ever written inside [BackupSink.commitStaged],
 *    which is a single Room transaction. Re-importing the same export is an
 *    idempotent no-op (identical rows are skipped).
 *  - Conflicts require an explicit user choice via [MergePolicy]; there is
 *    no silent overwrite path.
 */
class BackupService(
    private val sink: BackupSink,
    private val codec: BackupCodec,
    private val clock: () -> Long,
) {

    // ------------------------------------------------------------------
    // EXPORT
    // ------------------------------------------------------------------

    /**
     * Build a full export payload. Returns the canonical payload string plus
     * its manifest. Deterministic: same DB state ⇒ same payload bytes.
     */
    suspend fun buildExport(
        appVersion: String,
        includeRawEvidence: Boolean = false,
        datasets: Set<BackupDataset> = BackupDataset.entries.toSet(),
    ): ExportPayload {
        val datasetBlocks = mutableListOf<BackupCodec.DatasetBlock>()
        val manifests = mutableListOf<com.example.fintrack.domain.model.BackupDatasetManifest>()

        for (dataset in BackupDataset.entries) {
            if (dataset !in datasets) continue
            val rows = sink.exportRows(dataset).sorted()
            val block = codec.encodeDataset(dataset, rows)
            datasetBlocks += block
            manifests += com.example.fintrack.domain.model.BackupDatasetManifest(
                dataset = dataset,
                rowCount = rows.size,
                sha256 = RedactionEngine.sha256(block.canonicalBody),
            )
        }

        val manifest = BackupManifest(
            formatVersion = com.example.fintrack.domain.model.BACKUP_FORMAT_VERSION,
            schemaVersion = com.example.fintrack.domain.model.BACKUP_SCHEMA_VERSION,
            createdAtEpochMs = clock(),
            createdByVersion = appVersion,
            datasets = manifests,
            includesRawEvidence = includeRawEvidence,
            encrypted = false, // set true by the encrypted wrapper, never here
            redactionVersion = RedactionEngine.VERSION,
        )

        return ExportPayload(
            manifest = manifest,
            body = codec.encodeEnvelope(manifest, datasetBlocks),
        )
    }

    // ------------------------------------------------------------------
    // IMPORT — validate → stage → preview → commit
    // ------------------------------------------------------------------

    /** Phase 1: structural validation. Nothing is written anywhere. */
    fun validate(payload: String): ImportValidation {
        val decoded = codec.decodeEnvelope(payload)
        val envelope = when (decoded) {
            is CodecResult.Ok -> decoded.envelope
            is CodecResult.Bad -> return ImportValidation.Invalid(decoded.reasons)
        }
        val reasons = mutableListOf<String>()
        if (envelope.manifest.formatVersion > com.example.fintrack.domain.model.BACKUP_FORMAT_VERSION) {
            reasons += "backup format v${envelope.manifest.formatVersion} is newer than supported v" +
                com.example.fintrack.domain.model.BACKUP_FORMAT_VERSION
        }
        if (envelope.manifest.schemaVersion > com.example.fintrack.domain.model.BACKUP_SCHEMA_VERSION) {
            reasons += "backup schema v${envelope.manifest.schemaVersion} is newer than supported v" +
                com.example.fintrack.domain.model.BACKUP_SCHEMA_VERSION
        }
        // Checksum verification BEFORE any staging.
        for (block in envelope.blocks) {
            val manifestEntry = envelope.manifest.manifestFor(block.dataset)
            if (manifestEntry == null) {
                reasons += "manifest missing entry for ${block.dataset}"
                continue
            }
            val expected = manifestEntry.sha256
            val actual = RedactionEngine.sha256(block.canonicalBody)
            if (expected != actual) {
                reasons += "checksum mismatch for ${block.dataset} (file corrupt or tampered)"
            }
        }
        if (reasons.isNotEmpty()) return ImportValidation.Invalid(reasons)
        return ImportValidation.Valid(
            manifest = envelope.manifest,
            counts = envelope.blocks.associate { it.dataset to it.rows.size },
        )
    }

    /** Phase 2: copy validated rows into staging tables. */
    suspend fun stageValidated(payload: String): ImportValidation {
        return when (val validation = validate(payload)) {
            is ImportValidation.Invalid -> validation
            is ImportValidation.Valid -> {
                // A staging batch must exist before any row can be staged —
                // the sink throws IllegalStateException otherwise.
                sink.beginBatch(
                    formatVersion = validation.manifest.formatVersion,
                    schemaVersion = validation.manifest.schemaVersion,
                    totalRows = validation.counts.values.sum(),
                )
                sink.clearStaging()
                val decoded = (codec.decodeEnvelope(payload) as CodecResult.Ok).envelope
                for (block in decoded.blocks) {
                    sink.stageRows(block.dataset, block.rows)
                }
                validation
            }
        }
    }

    /**
     * Phase 3: preview against live data using staged rows only.
     * Stable-ID match first; content-hash equality decides identical vs
     * conflicting. No writes occur.
     */
    suspend fun preview(): ImportPreview {
        val newRows = mutableMapOf<BackupDataset, Int>()
        val identical = mutableMapOf<BackupDataset, Int>()
        val conflicts = mutableListOf<ImportConflict>()
        var manifest: BackupManifest? = null

        for (dataset in sink.stagedDatasets()) {
            var n = 0
            var same = 0
            for (stagedId in sink.stagedIds(dataset)) {
                val staged = sink.stagedRowById(dataset, stagedId) ?: continue
                val live = sink.liveRowById(dataset, stagedId)
                if (live == null) {
                    n++
                    continue
                }
                if (normalize(staged) == normalize(live)) same++ else {
                    conflicts += ImportConflict(
                        dataset = dataset,
                        stableId = stagedId,
                        differenceSummary = diffSummary(live, staged),
                        identical = false,
                    )
                }
            }
            newRows[dataset] = n
            identical[dataset] = same
        }

        return ImportPreview(
            manifest = manifest ?: currentStagedManifest(),
            newRows = newRows,
            identicalRows = identical,
            conflicts = conflicts.sortedWith(
                compareBy({ it.dataset.name }, { it.stableId })
            ),
            missingReferences = emptyList(),
        )
    }

    /**
     * Phase 4: commit. Single transaction inside the sink. Under
     * ABORT_ON_CONFLICT any real conflict aborts before writing.
     */
    suspend fun commit(
        policy: MergePolicy,
        userSelectedReplaceIds: Set<String> = emptySet(),
    ): ImportCommitResult {
        val p = preview()
        val realConflicts = p.realConflicts()
        if (policy == MergePolicy.ABORT_ON_CONFLICT && realConflicts.isNotEmpty()) {
            return ImportCommitResult.Aborted(
                "${realConflicts.size} conflict(s); policy=ABORT_ON_CONFLICT",
            )
        }
        val replaceIds = if (policy == MergePolicy.REPLACE_WITH_IMPORTED) {
            // Only the rows the user explicitly checked may be replaced.
            // An empty selection under REPLACE falls back to KEEP_LIVE
            // semantics (nothing is overwritten).
            realConflicts.groupBy({ it.dataset }, { it.stableId })
                .mapValues { (_, ids) -> ids.filter { it in userSelectedReplaceIds }.toSet() }
                .filterValues { it.isNotEmpty() }
        } else {
            emptyMap()
        }
        return try {
            val (inserted, replaced) = sink.commitStaged(policy, replaceIds)
            sink.clearStaging()
            ImportCommitResult.Committed(
                insertedByDataset = inserted,
                replacedByDataset = replaced,
                skippedConflicts = if (policy == MergePolicy.KEEP_LIVE) realConflicts.size else 0,
            )
        } catch (e: Exception) {
            ImportCommitResult.Failed(e.message ?: "commit failed")
        }
    }

    private suspend fun currentStagedManifest(): BackupManifest =
        BackupManifest(
            formatVersion = com.example.fintrack.domain.model.BACKUP_FORMAT_VERSION,
            schemaVersion = com.example.fintrack.domain.model.BACKUP_SCHEMA_VERSION,
            createdAtEpochMs = clock(),
            createdByVersion = "staging",
            datasets = sink.stagedDatasets().map {
                com.example.fintrack.domain.model.BackupDatasetManifest(
                    dataset = it,
                    rowCount = sink.stagedRowCount(it),
                    sha256 = "",
                )
            },
            includesRawEvidence = false,
            encrypted = false,
            redactionVersion = RedactionEngine.VERSION,
        )

    private fun normalize(row: String): String =
        row.split(ROW_SEP).filter { it.isNotBlank() }.sorted().joinToString(ROW_SEP)

    private fun diffSummary(live: String, imported: String): String {
        val liveFields = live.split(ROW_SEP).mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val impFields = imported.split(ROW_SEP).mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val differing = liveFields.keys.filter { k -> impFields.containsKey(k) && liveFields[k] != impFields[k] }
        val added = impFields.keys.filter { it !in liveFields }
        return buildString {
            if (differing.isNotEmpty()) append("changed: ${differing.sorted().joinToString()}")
            if (added.isNotEmpty()) {
                if (isNotEmpty()) append("; ")
                append("new fields: ${added.sorted().joinToString()}")
            }
            if (isEmpty()) append("content differs")
        }
    }

    companion object {
        const val ROW_SEP = ";"
    }
}

/** Canonical export output. */
data class ExportPayload(
    val manifest: BackupManifest,
    val body: String,
)

/** Result of decoding an envelope. */
sealed interface CodecResult {
    data class Ok(val envelope: BackupEnvelope) : CodecResult
    data class Bad(val reasons: List<String>) : CodecResult
}

/** Decoded but unvalidated envelope. */
data class BackupEnvelope(
    val manifest: BackupManifest,
    val blocks: List<BackupCodec.DatasetBlock>,
)
