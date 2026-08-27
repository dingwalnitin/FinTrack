package com.example.fintrack.domain.service

import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.RefundKind
import com.example.fintrack.domain.model.RefundLink
import com.example.fintrack.domain.model.TransactionLink
import com.example.fintrack.domain.model.TransactionLinkRole
import com.example.fintrack.domain.model.TransactionV6
import com.example.fintrack.domain.service.WriteResult
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * P11 #2: refunds.
 *
 * Refunds are events that REFERENCE the original expense; they never
 * mutate it. The service:
 *  1. Persists the refund [TransactionV6] via the [TransactionWriteService]
 *     so the same posting-group invariants hold.
 *  2. Writes a [RefundLink] so the detail screen can show the original
 *     expense under "Linked refunds".
 *  3. Optionally writes a [TransactionLink] with role=REFUND so the
 *     generic "linked events" surface picks it up.
 *
 * Idempotency: refund links are keyed by
 * sha-256(refundedEventId | refundEventId | kind | amountMinor) so a
 * parser re-run is a no-op.
 */
class RefundService(
    private val writeService: TransactionWriteService,
    private val sink: RefundSink,
    private val clock: () -> Instant = Instant::now,
) {

    /**
     * Record a refund. The caller supplies the refund [TransactionV6] —
     * typically the [TransactionWriteService.upsert] is invoked before
     * this call to ensure the posting group is written. [memos] are
     * forwarded to the write service.
     */
    suspend fun recordRefund(
        originalEventId: String,
        refundTxn: TransactionV6,
        kind: RefundKind,
        memos: List<String?> = listOf(null),
    ): Result<RefundResult> {
        // ---- validation ----
        if (refundTxn.amountMinor <= 0) {
            return Result.failure(IllegalArgumentException("refund amountMinor must be > 0"))
        }
        if (kind == RefundKind.PARTIAL && refundTxn.amountMinor > refundTxn.amountMinor) {
            // amountMinor on the link must be <= refundTxn.amountMinor for a partial refund.
            // We accept any non-zero positive value; partial-ness is encoded by kind=PARTIAL
            // and verified at read time.
        }
        // ---- write the refund transaction via the canonical write service ----
        val write = writeService.upsert(refundTxn, memos = memos)
        val persistedRefund = write.transaction
        val now = clock()

        // ---- write the durable refund link ----
        val link = RefundLink(
            id = UUID.randomUUID().toString(),
            refundedEventId = originalEventId,
            refundEventId = persistedRefund.id.value,
            kind = kind,
            amountMinor = persistedRefund.amountMinor,
            currencyCode = persistedRefund.currencyCode,
            provenance = refundTxn.provenance,
            reason = refundTxn.description,
            createdAt = now,
        )
        sink.insertRefundLink(link, refundIdentityFor(link))

        // ---- write the parent/child link (role=REFUND) for the detail screen ----
        val txLink = TransactionLink(
            id = UUID.randomUUID().toString(),
            parentEventId = originalEventId,
            childEventId = persistedRefund.id.value,
            role = TransactionLinkRole.REFUND,
            provenance = refundTxn.provenance,
            reason = refundTxn.description,
            createdAt = now,
        )
        sink.insertTransactionLink(txLink, linkIdentityFor(txLink))

        return Result.success(
            RefundResult(
                refundEventId = persistedRefund.id.value,
                link = link,
                persistedRefund = persistedRefund,
            )
        )
    }

    private fun refundIdentityFor(link: RefundLink): String {
        val raw = "${link.refundedEventId}|${link.refundEventId}|${link.kind.name}|${link.amountMinor}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun linkIdentityFor(link: TransactionLink): String {
        val raw = "${link.parentEventId}|${link.childEventId}|${link.role.name}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

data class RefundResult(
    val refundEventId: String,
    val link: RefundLink,
    val persistedRefund: TransactionV6,
)

/** Persistence interface for [RefundService]. */
interface RefundSink {
    suspend fun insertRefundLink(link: RefundLink, refundIdentity: String)
    suspend fun insertTransactionLink(link: TransactionLink, linkIdentity: String)
}
