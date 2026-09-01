package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.TransactionV6
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.model.TxStatus
import com.example.fintrack.domain.model.PostingDirection
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.SourceKind
import java.time.Instant
import java.util.UUID

/**
 * Stage 13 (F) — SMS review: list ingestion results and apply manual
 * overrides on the correct write path.
 *
 * Overrides must go through the existing [TransactionWriteService] (NOT direct
 * DAO writes) so postings, audit and idempotency are respected. A manual
 * override persists with `correctionOrigin = USER_CORRECTION` and a non-null
 * `correctionSourceKind`, so reprocessing never clobbers it.
 */
class SmsReviewService(
    private val writeService: TransactionWriteService,
    private val clock: () -> Instant = Instant::now,
) {

    data class OverrideInput(
        val amountMinor: Long,
        val currencyCode: String,
        val kind: TxKind,
        val occurredAtEpochMs: Long,
        val counterparty: String?,
        val categoryId: String?,
        val accountId: String,
        val reason: String?,
    )

    /**
     * Apply a user override to an existing transaction. Rebuilds the
     * transaction with USER_CORRECTION provenance and rewrites the posting
     * group via [TransactionWriteService.upsert]. Idempotent.
     */
    suspend fun applyOverride(transaction: TransactionV6, input: OverrideInput): Result<TransactionV6> {
        if (input.amountMinor <= 0) {
            return Result.failure(IllegalArgumentException("amount must be positive"))
        }
        val now = clock()
        val direction = when (input.kind) {
            TxKind.EXPENSE, TxKind.FEE, TxKind.CASH_MOVE -> PostingDirection.DEBIT
            TxKind.INCOME, TxKind.REFUND -> PostingDirection.CREDIT
            TxKind.TRANSFER, TxKind.UNKNOWN -> PostingDirection.DEBIT
        }
        val overridden = transaction.copy(
            accountId = com.example.fintrack.domain.model.EntityId(input.accountId),
            categoryId = input.categoryId?.let { com.example.fintrack.domain.model.EntityId(it) },
            amountMinor = input.amountMinor,
            currencyCode = input.currencyCode,
            kind = input.kind,
            subtype = transaction.subtype,
            status = if (transaction.status == TxStatus.DELETED) TxStatus.POSTED else transaction.status,
            occurredAt = Instant.ofEpochMilli(input.occurredAtEpochMs),
            direction = direction,
            merchant = input.counterparty,
            correctionOrigin = Provenance(
                sourceKind = SourceKind.USER_CORRECTION,
                sourceVersion = CORRECTION_VERSION,
                capturedAt = now,
            ),
        )
        val result = writeService.upsert(overridden, listOf(input.reason))
        return Result.success(result.transaction)
    }

    companion object {
        const val CORRECTION_VERSION = "sms-review-v1"
    }
}
