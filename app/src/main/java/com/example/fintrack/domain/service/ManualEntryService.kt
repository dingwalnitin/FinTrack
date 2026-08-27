package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.PostingDirection
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.model.TransactionV6
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.model.TxStatus
import com.example.fintrack.domain.model.TxSubtype
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * P11 #3: manual transaction quick entry / edit.
 *
 * Manual entries are first-class data and survive automated reprocessing.
 * The service:
 *  - Validates the input ([Result.failure] on bad input, never throws).
 *  - Forces `messageId = null`, `provenance.sourceKind = MANUAL_ENTRY`,
 *    `provenance.sourceVersion = "manual-v1"`, `correctionOrigin = null`.
 *  - On edit, decides whether the user changed a non-display-only field
 *    (amount, account, currency, kind, date). If so, the resulting row
 *    gets `correctionOrigin = USER_CORRECTION` so the
 *    [com.example.fintrack.domain.policy.ProvenancePolicy] outranks any
 *    later model-suggested overwrite.
 *  - Persists via [TransactionWriteService] so the posting-group
 *    invariant (replace prior group inside one @Transaction) holds.
 *
 * Save / Cancel: ViewModel keeps an in-memory draft; this service is the
 * only path that writes to Room.
 */
class ManualEntryService(
    private val writeService: TransactionWriteService,
    private val sink: ManualEntrySink,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /** Create a manual transaction. */
    suspend fun createManual(input: ManualEntryInput): Result<TransactionV6> {
        val validation = validate(input)
        if (validation is ValidationResult.Invalid) {
            return Result.failure(IllegalArgumentException(validation.reason))
        }
        val now = clock()
        val provenance = Provenance(
            sourceKind = SourceKind.MANUAL_ENTRY,
            sourceVersion = MANUAL_VERSION,
            capturedAt = now,
        )
        val txn = buildTxn(input, now, provenance, correctionOrigin = null)
        val result = writeService.upsert(txn, listOf(input.note))
        return Result.success(result.transaction)
    }

    /**
     * Edit a manual (or any) transaction. Decides whether the change
     * was substantive (USER_CORRECTION) or display-only (no correction).
     */
    suspend fun editManual(txnId: String, input: ManualEntryInput): Result<TransactionV6> {
        val existing = sink.findTransaction(txnId)
            ?: return Result.failure(IllegalStateException("transaction $txnId not found"))
        val validation = validate(input)
        if (validation is ValidationResult.Invalid) {
            return Result.failure(IllegalArgumentException(validation.reason))
        }
        val now = clock()
        val substantive = isSubstantiveChange(existing, input)
        val provenance = if (existing.provenance.sourceKind == SourceKind.MANUAL_ENTRY) {
            existing.provenance
        } else {
            // Editing an automated transaction: keep its original source but
            // layer a USER_CORRECTION on top.
            existing.provenance
        }
        val correctionOrigin = if (substantive) {
            Provenance(
                sourceKind = SourceKind.USER_CORRECTION,
                sourceVersion = MANUAL_VERSION,
                capturedAt = now,
            )
        } else {
            existing.correctionOrigin
        }
        val txn = buildTxn(
            input = input,
            now = now,
            provenance = provenance,
            correctionOrigin = correctionOrigin,
            existingId = EntityId(txnId),
            existingPostingGroupId = existing.postingGroupId,
        )
        val result = writeService.upsert(txn, listOf(input.note))
        return Result.success(result.transaction)
    }

    /**
     * Soft-delete a manual transaction. Routed through the same
     * [com.example.fintrack.domain.service.TransactionWriteService.softDelete]
     * as automated transactions; we additionally record an AuditEvent for
     * P11 #4 traceability.
     */
    suspend fun deleteManual(txnId: String, reason: String? = "user-deleted"): Result<TransactionV6> {
        val existing = sink.findTransaction(txnId)
            ?: return Result.failure(IllegalStateException("transaction $txnId not found"))
        val deleted = writeService.softDelete(txnId, reason = reason)
            ?: return Result.failure(IllegalStateException("soft-delete failed for $txnId"))
        sink.appendAudit(
            entityId = txnId,
            entityType = "transaction",
            action = "DELETED",
            actor = "USER",
            reason = reason,
            atEpochMs = clock().toEpochMilli(),
        )
        return Result.success(deleted)
    }

    /**
     * Restore a soft-deleted transaction. P11 #4: only USER-initiated;
     * records an audit event. Re-processing must never resurrect a
     * DELETED event from a fresh SMS — this path is the only
     * resurrection allowed.
     */
    suspend fun restoreTransaction(txnId: String): Result<TransactionV6> {
        val existing = sink.findTransaction(txnId)
            ?: return Result.failure(IllegalStateException("transaction $txnId not found"))
        if (existing.status != TxStatus.DELETED) {
            return Result.success(existing) // idempotent
        }
        sink.restoreFromTombstone(txnId)
        sink.appendAudit(
            entityId = txnId,
            entityType = "transaction",
            action = "RESTORED",
            actor = "USER",
            reason = null,
            atEpochMs = clock().toEpochMilli(),
        )
        // Re-emit the restored event so callers observe the cleared tombstone.
        val restored = sink.findTransaction(txnId)
            ?: return Result.failure(IllegalStateException("restore vanished"))
        return Result.success(restored)
    }

    // ---- validation ----

    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    fun validate(input: ManualEntryInput): ValidationResult {
        if (input.amountMinor <= 0) return ValidationResult.Invalid("amountMinor must be > 0")
        if (input.currencyCode.length != 3) return ValidationResult.Invalid("currencyCode must be ISO-4217")
        if (input.occurredAt.isAfter(clock().plusSeconds(3600))) {
            return ValidationResult.Invalid("occurredAt is more than 1h in the future")
        }
        if (input.accountId.value.isBlank()) return ValidationResult.Invalid("accountId is required")
        // account existence + ACTIVE lifecycle is checked at the sink boundary (DB read).
        try {
            TxKind.valueOf(input.kind.name)
        } catch (e: IllegalArgumentException) {
            return ValidationResult.Invalid("unknown TxKind '${input.kind}'")
        }
        return ValidationResult.Valid
    }

    private fun buildTxn(
        input: ManualEntryInput,
        now: Instant,
        provenance: Provenance,
        correctionOrigin: Provenance?,
        existingId: EntityId = EntityId(UUID.randomUUID().toString()),
        existingPostingGroupId: String? = null,
    ): TransactionV6 {
        val direction = when (input.kind) {
            TxKind.EXPENSE, TxKind.FEE, TxKind.CASH_MOVE -> PostingDirection.DEBIT
            TxKind.INCOME, TxKind.REFUND -> PostingDirection.CREDIT
            TxKind.TRANSFER, TxKind.UNKNOWN -> PostingDirection.DEBIT // TRANSFER has its own service
        }
        return TransactionV6(
            id = existingId,
            messageId = null,
            accountId = input.accountId,
            categoryId = input.categoryId,
            amountMinor = input.amountMinor,
            currencyCode = input.currencyCode,
            occurredAt = input.occurredAt,
            localDate = input.occurredAt.atZone(zone).toLocalDate(),
            counterparty = input.counterparty,
            counterpartyNormalized = input.counterparty?.lowercase()?.trim(),
            merchant = input.merchant,
            description = input.note,
            referenceId = input.referenceId,
            cardMask = input.cardMask,
            rail = input.rail,
            kind = input.kind,
            subtype = input.subtype,
            direction = direction,
            status = TxStatus.POSTED,
            provenance = provenance,
            correctionOrigin = correctionOrigin,
            dedupeKey = input.dedupeKey ?: dedupeKeyForManual(existingId.value, input),
            postingGroupId = existingPostingGroupId, // preserve on edit; null on create
        )
    }

    private fun isSubstantiveChange(existing: TransactionV6, input: ManualEntryInput): Boolean {
        return existing.amountMinor != input.amountMinor ||
            existing.currencyCode != input.currencyCode ||
            existing.accountId != input.accountId ||
            existing.kind != input.kind ||
            existing.occurredAt != input.occurredAt
    }

    private fun dedupeKeyForManual(id: String, input: ManualEntryInput): String {
        val raw = "manual|$id|${input.accountId.value}|${input.amountMinor}|${input.occurredAt.toEpochMilli()}|${input.referenceId ?: ""}"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MANUAL_VERSION = "manual-v1"
    }
}

/**
 * Manual entry / edit input. Constructed by the ViewModel from the
 * in-memory draft; validated by [ManualEntryService].
 */
data class ManualEntryInput(
    val accountId: EntityId,
    val amountMinor: Long,
    val currencyCode: String,
    val occurredAt: Instant,
    val kind: TxKind,
    val subtype: TxSubtype? = null,
    val counterparty: String? = null,
    val merchant: String? = null,
    val note: String? = null,
    val referenceId: String? = null,
    val cardMask: String? = null,
    val rail: String? = null,
    val categoryId: EntityId? = null,
    val dedupeKey: String? = null, // callers may supply a stable key for re-runs
)

/** Persistence interface for [ManualEntryService]. */
interface ManualEntrySink {
    suspend fun findTransaction(id: String): TransactionV6?
    suspend fun restoreFromTombstone(id: String)
    suspend fun appendAudit(
        entityId: String,
        entityType: String,
        action: String,
        actor: String,
        reason: String?,
        atEpochMs: Long,
    )
}
