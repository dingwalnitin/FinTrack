package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.PostingDirection
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.SplitLineDraft
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.model.SplitValidation
import com.example.fintrack.domain.model.TransactionSplit
import com.example.fintrack.domain.model.TransactionV6
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.model.TxStatus
import com.example.fintrack.domain.policy.SinglePosting
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Stage 7 P15 — split transactions.
 *
 * A split takes one parent expense and divides it into N children.
 * The parent stays in the ledger but is tombstoned (status=DELETED,
 * reason="split") so its postings are preserved for audit while it is
 * excluded from balances. The children are real [TransactionV6] rows
 * with their own posting groups; they participate in balances normally.
 *
 * Amount conservation is enforced BEFORE any write: the sum of child
 * amounts must equal the parent amount exactly. No rounding drift is
 * allowed — the caller must supply a draft that sums correctly
 * ([SplitLineDraft] validation catches non-positive lines; this service
 * validates the total).
 *
 * All writes go through [TransactionWriteService] (which replaces the
 * prior posting group inside one Room @Transaction) and then through
 * [SplitSink.applySplits] which writes the parent/child link rows in a
 * second Room @Transaction. If the process dies between the two, the
 * next run is idempotent: re-running the same split produces the same
 * child dedupe keys and the unique (parent, child) index absorbs the
 * duplicate link rows.
 */
class SplitService(
    private val writeService: TransactionWriteService,
    private val sink: SplitSink,
    private val clock: () -> Instant = Instant::now,
) {

    /**
     * Validate a split draft against the parent amount. Returns
     * [SplitValidation.Invalid] when the sum does not match exactly.
     */
    fun validate(parentAmountMinor: Long, draft: List<SplitLineDraft>): SplitValidation {
        if (draft.isEmpty()) {
            return SplitValidation.Invalid(0L, "split must have at least one line")
        }
        val sum = draft.sumOf { it.amountMinor }
        return if (sum == parentAmountMinor) {
            SplitValidation.Valid
        } else {
            SplitValidation.Invalid(
                differenceMinor = sum - parentAmountMinor,
                message = "child sum $sum does not equal parent $parentAmountMinor",
            )
        }
    }

    /**
     * Commit a validated split. Returns the persisted children and the
     * durable link rows. Idempotent on the (parent, child) pair.
     */
    suspend fun commit(
        parent: TransactionV6,
        draft: List<SplitLineDraft>,
        provenance: Provenance,
    ): Result<SplitResult> {
        when (val v = validate(parent.amountMinor, draft)) {
            is SplitValidation.Invalid -> return Result.failure(IllegalArgumentException(v.message))
            SplitValidation.Valid -> Unit
        }

        val now = clock()
        val children = mutableListOf<TransactionV6>()
        val links = mutableListOf<TransactionSplit>()

        for ((index, line) in draft.withIndex()) {
            val childId = EntityId.generate()
            val childDedupeKey = sha256("split|${parent.id.value}|$index|${line.amountMinor}|${line.categoryId?.value.orEmpty()}")
            val child = TransactionV6(
                id = childId,
                messageId = null, // splits are derived events; no direct SMS evidence
                accountId = parent.accountId,
                categoryId = line.categoryId,
                amountMinor = line.amountMinor,
                currencyCode = parent.currencyCode,
                occurredAt = parent.occurredAt,
                localDate = parent.localDate,
                counterparty = parent.counterparty,
                counterpartyNormalized = parent.counterpartyNormalized,
                merchant = parent.merchant,
                description = line.memo ?: "Split ${index + 1} of ${draft.size}",
                referenceId = parent.referenceId,
                cardMask = parent.cardMask,
                rail = parent.rail,
                kind = parent.kind,
                subtype = parent.subtype,
                direction = parent.direction,
                status = TxStatus.POSTED,
                provenance = provenance,
                correctionOrigin = null,
                dedupeKey = childDedupeKey,
                postingGroupId = null, // write service assigns a fresh group
                transferGroupId = null,
            )
            val write = writeService.upsert(child)
            children += write.transaction

            links += TransactionSplit(
                id = UUID.randomUUID().toString(),
                parentTransactionId = parent.id.value,
                childTransactionId = write.transaction.id.value,
                splitIdentity = sha256("${parent.id.value}|${write.transaction.id.value}"),
                sourceKind = provenance.sourceKind,
                sourceVersion = provenance.sourceVersion,
                createdAtEpochMs = now.toEpochMilli(),
            )
        }

        // Tombstone the parent AFTER the children exist so a crash between
        // the two steps leaves the ledger in a recoverable state.
        writeService.softDelete(
            txnId = parent.id.value,
            reason = "split into ${children.size} parts",
        )

        sink.applySplits(links)

        return Result.success(SplitResult(children = children, links = links))
    }

    private fun sha256(raw: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

data class SplitResult(
    val children: List<TransactionV6>,
    val links: List<TransactionSplit>,
)

/** Persistence interface for [SplitService]. */
interface SplitSink {
    suspend fun applySplits(links: List<TransactionSplit>)
}
