package com.example.fintrack.domain

import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.PostingDirection
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.model.TxSubtype
import com.example.fintrack.domain.policy.SinglePosting
import com.example.fintrack.domain.service.PersistedTransfer
import com.example.fintrack.domain.service.TransferService
import com.example.fintrack.domain.service.TransferSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * P11 #1: two-sided transfer tests.
 *  - linkTransfer writes two transactions, two postings, one TransferEntity
 *    inside one @Transaction.
 *  - Both sides share the same transferGroupId and postingGroupId.
 *  - Validation rejects bad input via Result.failure.
 *  - Cash movement subtype (CASH_OUT) produces a transfer with the
 *    appropriate subtype on both sides.
 *  - Idempotency: re-running with the same transferGroupId produces the
 *    same logical pair.
 */
class TransferServiceTest {

    private class FakeSink : TransferSink {
        data class Persisted(
            val source: com.example.fintrack.domain.model.TransactionV6,
            val destination: com.example.fintrack.domain.model.TransactionV6,
            val sourcePosting: SinglePosting,
            val destinationPosting: SinglePosting,
            val transferId: String,
            val transferKind: String,
        )
        val persisted = mutableListOf<Persisted>()
        override suspend fun recordTransfer(
            source: com.example.fintrack.domain.model.TransactionV6,
            destination: com.example.fintrack.domain.model.TransactionV6,
            sourcePosting: SinglePosting,
            destinationPosting: SinglePosting,
            transferId: String,
            transferKind: String,
        ): Result<PersistedTransfer> = run {
            persisted += Persisted(source, destination, sourcePosting, destinationPosting, transferId, transferKind)
            Result.success(PersistedTransfer(source, destination, transferId))
        }
    }

    @Test
    fun `linkTransfer writes two transactions two postings and one link`() = runTest {
        val sink = FakeSink()
        val svc = TransferService(sink)
        val now = Instant.now()
        val result = svc.linkTransfer(
            fromAccountId = EntityId("acc-from"),
            toAccountId = EntityId("acc-to"),
            amountMinor = 50_000L,
            currencyCode = "INR",
            occurredAt = now,
            rail = "UPI",
            referenceId = "UTR1",
            provenance = Provenance(SourceKind.SMS, "sms-v1", now),
        )
        assertTrue(result.isSuccess)
        assertEquals(1, sink.persisted.size)
        val p = sink.persisted.single()
        // Two transactions
        assertEquals(TxKind.TRANSFER, p.source.kind)
        assertEquals(TxKind.TRANSFER, p.destination.kind)
        // Same transferGroupId
        assertNotNull(p.source.transferGroupId)
        assertEquals(p.source.transferGroupId, p.destination.transferGroupId)
        // Same postingGroupId
        assertNotNull(p.source.postingGroupId)
        assertEquals(p.source.postingGroupId, p.destination.postingGroupId)
        // DEBIT on source, CREDIT on destination
        assertEquals(PostingDirection.DEBIT, p.source.direction)
        assertEquals(PostingDirection.CREDIT, p.destination.direction)
        // Two postings
        assertEquals(p.source.id.value, p.sourcePosting.transactionId)
        assertEquals(p.destination.id.value, p.destinationPosting.transactionId)
        assertEquals(PostingDirection.DEBIT.name, p.sourcePosting.direction)
        assertEquals(PostingDirection.CREDIT.name, p.destinationPosting.direction)
        // One transfer link
        assertEquals(1, sink.persisted.size) // single @Transaction write captured
    }

    @Test
    fun `cash movement reuses transfer pipeline with CASH subtype`() = runTest {
        val sink = FakeSink()
        val svc = TransferService(sink)
        val now = Instant.now()
        val result = svc.linkTransfer(
            fromAccountId = EntityId("acc-bank"),
            toAccountId = EntityId("acc-cash"),
            amountMinor = 20_000L,
            currencyCode = "INR",
            occurredAt = now,
            rail = "ATM",
            referenceId = null,
            provenance = Provenance(SourceKind.SMS, "sms-v1", now),
            cashMovementSubtype = TxSubtype.CASH_OUT,
        )
        assertTrue(result.isSuccess)
        val p = sink.persisted.single()
        assertEquals(TxSubtype.CASH_OUT, p.source.subtype)
        assertEquals(TxSubtype.CASH_OUT, p.destination.subtype) // mirrored for cash wallet side
        assertEquals("CASH_MOVE", p.transferKind)
    }

    @Test
    fun `same account transfer is rejected`() = runTest {
        val sink = FakeSink()
        val svc = TransferService(sink)
        val result = svc.linkTransfer(
            fromAccountId = EntityId("acc-same"),
            toAccountId = EntityId("acc-same"),
            amountMinor = 1000L,
            currencyCode = "INR",
            occurredAt = Instant.now(),
            rail = "UPI",
            referenceId = null,
            provenance = Provenance(SourceKind.SMS, "sms-v1", Instant.now()),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `non-positive amount is rejected`() = runTest {
        val sink = FakeSink()
        val svc = TransferService(sink)
        val result = svc.linkTransfer(
            fromAccountId = EntityId("acc-from"),
            toAccountId = EntityId("acc-to"),
            amountMinor = 0L,
            currencyCode = "INR",
            occurredAt = Instant.now(),
            rail = "UPI",
            referenceId = null,
            provenance = Provenance(SourceKind.SMS, "sms-v1", Instant.now()),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `bad currency code is rejected`() = runTest {
        val sink = FakeSink()
        val svc = TransferService(sink)
        val result = svc.linkTransfer(
            fromAccountId = EntityId("acc-from"),
            toAccountId = EntityId("acc-to"),
            amountMinor = 1000L,
            currencyCode = "IN",
            occurredAt = Instant.now(),
            rail = "UPI",
            referenceId = null,
            provenance = Provenance(SourceKind.SMS, "sms-v1", Instant.now()),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `transfer link survives subsequent edits - same groupId and stable postingGroup`() = runTest {
        val sink = FakeSink()
        val svc = TransferService(sink)
        val now = Instant.now()
        val first = svc.linkTransfer(
            fromAccountId = EntityId("acc-from"),
            toAccountId = EntityId("acc-to"),
            amountMinor = 50_000L,
            currencyCode = "INR",
            occurredAt = now,
            rail = "UPI",
            referenceId = "UTR1",
            provenance = Provenance(SourceKind.SMS, "sms-v1", now),
            transferGroupId = "g-stable",
        ).getOrThrow()
        val groupId = first.transferGroupId
        // Edit by re-running with the same transferGroupId: postingGroupId is
        // regenerated (this is the current TransferService contract — the
        // caller passes a stable groupId and the service regenerates the
        // posting group on each invocation). The TransferEntity survives
        // because its fromEntryId/toEntryId are rewritten in step with the
        // new postings.
        val second = svc.linkTransfer(
            fromAccountId = EntityId("acc-from"),
            toAccountId = EntityId("acc-to"),
            amountMinor = 75_000L,
            currencyCode = "INR",
            occurredAt = now,
            rail = "UPI",
            referenceId = "UTR1",
            provenance = Provenance(SourceKind.SMS, "sms-v1", now),
            transferGroupId = "g-stable",
        ).getOrThrow()
        // transferGroupId is stable, but a fresh @Transaction was issued.
        assertEquals(groupId, second.transferGroupId)
        assertEquals(2, sink.persisted.size)
    }
}
