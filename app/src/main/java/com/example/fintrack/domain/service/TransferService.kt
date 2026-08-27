package com.example.fintrack.domain.service

import com.example.fintrack.domain.dedupe.Candidate
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.PostingDirection
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.model.TransactionV6
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.model.TxStatus
import com.example.fintrack.domain.model.TxSubtype
import com.example.fintrack.domain.policy.TransferEngine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * P11 #1 / #4: two-sided owned-account transfers.
 *
 * A transfer is two simultaneous balance movements that are excluded from
 * income/expense metrics and have an explicit Transfer status. The service
 * writes:
 *  - two [TransactionV6] rows with kind=TRANSFER, one DEBIT on the source
 *    account and one CREDIT on the destination, both sharing the same
 *    [TransferEntity.transferGroupId].
 *  - two [LedgerEntryEntity] rows (one per side) sharing the same postingGroupId.
 *  - one [TransferEntity] linking the two entries.
 *
 * All writes happen inside one Room @Transaction via the [TransferSink].
 * The two sides can be edited independently after creation; the link
 * survives because it is keyed on the entry ids (which do not change on
 * re-write because the same postingGroupId is reused).
 *
 * Cash movements (ATM withdrawal / deposit) reuse this service with
 * [cashMovementSubtype] = CASH_OUT / CASH_IN so the bank account and the
 * cash wallet both reflect the movement and the pair is excluded from
 * income/expense metrics.
 */
class TransferService(
    private val sink: TransferSink,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * Link a two-sided transfer. Returns the persisted (source, destination)
     * pair and the durable [transferEntityId] used in [com.example.fintrack.data.db.TransferEntity].
     *
     * Validation (Result.failure, never throws):
     *  - amountMinor > 0
     *  - currencyCode is ISO-4217
     *  - fromAccountId != toAccountId
     *  - rail is non-null and in the whitelist
     *  - occurredAt <= now() + 1h
     *
     * Idempotency: callers MAY supply a stable [transferGroupId]; otherwise
     * a new UUID is generated. The service is safe to re-run with the same
     * inputs because the unique `toEntryId` index on `transfers` makes the
     * second insert a no-op.
     */
    suspend fun linkTransfer(
        fromAccountId: EntityId,
        toAccountId: EntityId,
        amountMinor: Long,
        currencyCode: String,
        occurredAt: Instant,
        rail: String,
        referenceId: String?,
        provenance: Provenance,
        memo: String? = null,
        transferGroupId: String? = null,
        cashMovementSubtype: TxSubtype? = null,
    ): Result<TransferResult> {
        // ---- validation ----
        if (amountMinor <= 0) return Result.failure(IllegalArgumentException("amountMinor must be > 0"))
        if (currencyCode.length != 3) return Result.failure(IllegalArgumentException("currencyCode must be ISO-4217"))
        if (fromAccountId == toAccountId) return Result.failure(IllegalArgumentException("fromAccountId == toAccountId"))
        if (rail.uppercase() !in TransactionV6.RAIL_NAMES) {
            return Result.failure(IllegalArgumentException("unknown rail '$rail'"))
        }
        val now = clock()
        if (occurredAt.isAfter(now.plusSeconds(3600))) {
            return Result.failure(IllegalArgumentException("occurredAt is more than 1h in the future"))
        }
        if (fromAccountId.value == toAccountId.value) {
            return Result.failure(IllegalArgumentException("fromAccountId == toAccountId"))
        }

        // ---- ids ----
        val groupId = transferGroupId ?: UUID.randomUUID().toString()
        val postingGroupId = UUID.randomUUID().toString()
        val sourceTxnId = EntityId(UUID.randomUUID().toString())
        val destTxnId = EntityId(UUID.randomUUID().toString())
        val sourceEntryId = UUID.randomUUID().toString()
        val destEntryId = UUID.randomUUID().toString()
        val transferId = UUID.randomUUID().toString()
        val localDate = occurredAt.atZone(zone).toLocalDate()

        // Build the two sides. The destination side is CREDIT (flow in);
        // the source side is DEBIT (flow out). The PostingPolicy
        // direction rules already enforce that for the user's account.
        val sourceTxn = TransactionV6(
            id = sourceTxnId,
            messageId = null,
            accountId = fromAccountId,
            categoryId = null,
            amountMinor = amountMinor,
            currencyCode = currencyCode,
            occurredAt = occurredAt,
            localDate = localDate,
            counterparty = null,
            counterpartyNormalized = null,
            merchant = null,
            description = memo,
            referenceId = referenceId,
            cardMask = null,
            rail = rail,
            kind = TxKind.TRANSFER,
            subtype = cashMovementSubtype, // null = pure transfer; set to CASH_OUT for ATM
            direction = PostingDirection.DEBIT,
            status = TxStatus.POSTED,
            provenance = provenance,
            correctionOrigin = null,
            dedupeKey = dedupeKeyForTransfer(groupId, "SOURCE"),
            postingGroupId = postingGroupId,
            transferGroupId = groupId,
        )
        val destTxn = TransactionV6(
            id = destTxnId,
            messageId = null,
            accountId = toAccountId,
            categoryId = null,
            amountMinor = amountMinor,
            currencyCode = currencyCode,
            occurredAt = occurredAt,
            localDate = localDate,
            counterparty = null,
            counterpartyNormalized = null,
            merchant = null,
            description = memo,
            referenceId = referenceId,
            cardMask = null,
            rail = rail,
            kind = TxKind.TRANSFER,
            subtype = cashMovementSubtype, // CASH_IN counterpart on the cash wallet
            direction = PostingDirection.CREDIT,
            status = TxStatus.POSTED,
            provenance = provenance,
            correctionOrigin = null,
            dedupeKey = dedupeKeyForTransfer(groupId, "DESTINATION"),
            postingGroupId = postingGroupId,
            transferGroupId = groupId,
        )
        return sink.recordTransfer(
            source = sourceTxn,
            destination = destTxn,
            sourcePosting = com.example.fintrack.domain.policy.SinglePosting(
                id = sourceEntryId,
                transactionId = sourceTxnId.value,
                accountId = fromAccountId.value,
                direction = PostingDirection.DEBIT.name,
                amountMinor = amountMinor,
                currencyCode = currencyCode,
                postingGroupId = postingGroupId,
                memo = memo,
            ),
            destinationPosting = com.example.fintrack.domain.policy.SinglePosting(
                id = destEntryId,
                transactionId = destTxnId.value,
                accountId = toAccountId.value,
                direction = PostingDirection.CREDIT.name,
                amountMinor = amountMinor,
                currencyCode = currencyCode,
                postingGroupId = postingGroupId,
                memo = memo,
            ),
            transferId = transferId,
            transferKind = if (cashMovementSubtype != null) "CASH_MOVE" else "TRANSFER",
        ).map { res ->
            TransferResult(
                source = res.source,
                destination = res.destination,
                transferEntityId = res.transferEntityId,
                transferGroupId = groupId,
                postingGroupId = postingGroupId,
            )
        }
    }

    /**
     * Helper: dedupe key for a transfer side. Stable for a given
     * (groupId, side) pair so re-runs are idempotent on the
     * transactions.dedupeKey unique index.
     */
    private fun dedupeKeyForTransfer(groupId: String, side: String): String {
        val raw = "$groupId|$side"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** Result of a successful transfer link. */
data class TransferResult(
    val source: TransactionV6,
    val destination: TransactionV6,
    val transferEntityId: String,
    val transferGroupId: String,
    val postingGroupId: String,
)

/** Persistence interface for [TransferService]. */
interface TransferSink {
    suspend fun recordTransfer(
        source: TransactionV6,
        destination: TransactionV6,
        sourcePosting: com.example.fintrack.domain.policy.SinglePosting,
        destinationPosting: com.example.fintrack.domain.policy.SinglePosting,
        transferId: String,
        transferKind: String,
    ): Result<PersistedTransfer>
}

data class PersistedTransfer(
    val source: TransactionV6,
    val destination: TransactionV6,
    val transferEntityId: String,
)
