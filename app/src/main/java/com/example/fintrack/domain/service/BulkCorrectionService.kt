package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.SourceKind
import java.time.Instant

/**
 * Stage 7 P15 — bulk correction with safe preview + explicit commit.
 *
 * A bulk correction applies one field change (category, merchant, tag)
 * to a set of transactions. The flow is always two-phase:
 *
 *   1. preview()  — returns the per-transaction before/after diff with
 *      provenance. Nothing is written.
 *   2. commit()   — writes each row through [CategorizationSink] inside
 *      its own transactional apply, appending an audit row per change.
 *
 * Per-field/user provenance is preserved: every committed row records
 * which user action produced it and what the previous value was, so
 * the audit trail is complete and reversible by inspection.
 */
class BulkCorrectionService(
    private val sink: CategorizationSink,
    private val clock: () -> Instant = { Instant.now() },
) {

    /** One proposed change for one transaction. */
    data class ProposedChange(
        val transactionId: String,
        val currentCategoryId: EntityId?,
        val newCategoryId: EntityId?,
        val currentMerchantId: EntityId?,
        val newMerchantId: EntityId?,
    )

    /**
     * Preview the effect of applying (newCategoryId, newMerchantId) to
     * every transaction in [transactionIds]. Returns only rows that
     * would actually change; unchanged rows are omitted so the UI can
     * show an accurate "N of M will change" summary.
     */
    suspend fun preview(
        transactionIds: List<String>,
        newCategoryId: EntityId?,
        newMerchantId: EntityId?,
    ): List<ProposedChange> {
        return transactionIds.mapNotNull { id ->
            val audit = sink.latestAuditForTransaction(id)
            val currentCategory = audit?.newCategoryId
            val currentMerchant = audit?.newMerchantId
            if (currentCategory == newCategoryId && currentMerchant == newMerchantId) {
                null // no-op; omit from preview
            } else {
                ProposedChange(
                    transactionId = id,
                    currentCategoryId = currentCategory,
                    newCategoryId = newCategoryId,
                    currentMerchantId = currentMerchant,
                    newMerchantId = newMerchantId,
                )
            }
        }
    }

    /**
     * Commit the bulk correction. Each row is applied independently;
     * a failure on one row does not roll back the others (the caller
     * sees the per-row result). Every successful write appends an
     * audit row recording actor=USER and sourceKind=USER_CORRECTION.
     */
    suspend fun commit(
        changes: List<ProposedChange>,
        reason: String?,
    ): BulkCommitResult {
        var applied = 0
        val failures = mutableListOf<Pair<String, String>>()
        val now = clock().toEpochMilli()
        for (change in changes) {
            try {
                sink.appendCategoryAudit(
                    transactionId = change.transactionId,
                    previousCategoryId = change.currentCategoryId,
                    newCategoryId = change.newCategoryId,
                    previousMerchantId = change.currentMerchantId,
                    newMerchantId = change.newMerchantId,
                    actor = "USER",
                    sourceKind = "USER_CORRECTION",
                    sourceVersion = "bulk-v1",
                    reason = reason ?: "Bulk correction",
                    ruleId = null,
                    atEpochMs = now,
                )
                sink.applyCategorization(
                    transactionId = change.transactionId,
                    categoryId = change.newCategoryId,
                    merchantId = change.newMerchantId,
                    sourceKind = SourceKind.USER_CORRECTION.name,
                    sourceVersion = "bulk-v1",
                    sourceReason = reason,
                )
                applied++
            } catch (t: Throwable) {
                failures += change.transactionId to (t.message ?: "unknown error")
            }
        }
        return BulkCommitResult(applied = applied, failures = failures)
    }
}

data class BulkCommitResult(
    val applied: Int,
    val failures: List<Pair<String, String>>,
) {
    val allSucceeded: Boolean get() = failures.isEmpty()
}
