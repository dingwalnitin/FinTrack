package com.example.fintrack.domain

import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.PostingDirection
import com.example.fintrack.domain.model.Provenance
import com.example.fintrack.domain.model.SourceKind
import com.example.fintrack.domain.model.TransactionV6
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.model.TxStatus
import com.example.fintrack.domain.model.TxSubtype
import com.example.fintrack.domain.policy.SinglePosting
import com.example.fintrack.domain.service.ManualEntryInput
import com.example.fintrack.domain.service.ManualEntryService
import com.example.fintrack.domain.service.ManualEntrySink
import com.example.fintrack.domain.service.TransactionWriteService
import com.example.fintrack.domain.service.TransactionWriteSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * P11 #3 / #4 manual entry tests.
 *  - Validation rejects bad input via Result.failure.
 *  - createManual forces MANUAL_ENTRY provenance and null messageId.
 *  - editManual sets USER_CORRECTION when a substantive field changes.
 *  - editManual leaves correctionOrigin null when only display fields change.
 *  - restoreTransaction resurrects a soft-deleted event (audit recorded).
 *  - Offline: no network involved.
 */
class ManualEntryServiceTest {

    private class FakeWriteSink : TransactionWriteSink {
        val stored = linkedMapOf<String, TransactionV6>()
        val postingsByGroup = linkedMapOf<String, MutableList<SinglePosting>>()
        override suspend fun findTransaction(id: String): TransactionV6? = stored[id]
        override suspend fun findPostingGroupId(id: String): String? = stored[id]?.postingGroupId
        override suspend fun replacePostingGroupAndUpsertTxn(
            txn: TransactionV6,
            previousPostingGroupId: String?,
            newPostings: List<SinglePosting>,
        ): Pair<TransactionV6, List<SinglePosting>> {
            previousPostingGroupId?.let { postingsByGroup.remove(it) }
            stored[txn.id.value] = txn
            val g = txn.postingGroupId!!
            postingsByGroup.getOrPut(g) { mutableListOf() }.clear()
            postingsByGroup[g]!!.addAll(newPostings)
            return txn to newPostings
        }
        override suspend fun updateStatusAndTombstone(
            txnId: String, status: String, deletedAtEpochMs: Long, deletedReason: String?,
        ) {
            stored[txnId] = stored[txnId]!!.copy(
                status = TxStatus.valueOf(status),
                deletedAt = Instant.ofEpochMilli(deletedAtEpochMs),
                deletedReason = deletedReason,
            )
        }
    }

    private class FakeManualEntrySink : ManualEntrySink {
        val stored = linkedMapOf<String, TransactionV6>()
        val audit = mutableListOf<Triple<String, String, String>>()
        var restoreCalls = 0
        override suspend fun findTransaction(id: String): TransactionV6? = stored[id]
        override suspend fun restoreFromTombstone(id: String) {
            restoreCalls++
            stored[id] = stored[id]!!.copy(
                status = TxStatus.POSTED,
                deletedAt = null,
                deletedReason = null,
            )
        }
        override suspend fun appendAudit(
            entityId: String, entityType: String, action: String,
            actor: String, reason: String?, atEpochMs: Long,
        ) {
            audit += Triple(entityId, action, reason ?: "")
        }
    }

    private fun manualInput(
        amountMinor: Long = 25_000L,
        currency: String = "INR",
        kind: TxKind = TxKind.EXPENSE,
        occurredAt: Instant = Instant.parse("2024-01-15T10:00:00Z"),
        merchant: String? = "Swiggy",
        counterparty: String? = "swiggy",
        note: String? = null,
    ) = ManualEntryInput(
        accountId = EntityId("acc1"),
        amountMinor = amountMinor,
        currencyCode = currency,
        occurredAt = occurredAt,
        kind = kind,
        subtype = if (kind == TxKind.EXPENSE) TxSubtype.UPI else null,
        counterparty = counterparty,
        merchant = merchant,
        note = note,
        referenceId = null,
    )

    @Test
    fun `createManual forces MANUAL_ENTRY provenance and null messageId`() = runTest {
        val writeSink = FakeWriteSink()
        val manualSink = FakeManualEntrySink()
        val writeService = TransactionWriteService(writeSink)
        val svc = ManualEntryService(writeService, manualSink)
        val result = svc.createManual(manualInput())
        assertTrue(result.isSuccess)
        val txn = result.getOrThrow()
        assertNull(txn.messageId)
        assertEquals(SourceKind.MANUAL_ENTRY, txn.provenance.sourceKind)
        assertEquals(ManualEntryService.MANUAL_VERSION, txn.provenance.sourceVersion)
        assertNull(txn.correctionOrigin)
    }

    @Test
    fun `non-positive amount fails`() = runTest {
        val writeSink = FakeWriteSink()
        val manualSink = FakeManualEntrySink()
        val svc = ManualEntryService(TransactionWriteService(writeSink), manualSink)
        val result = svc.createManual(manualInput(amountMinor = 0L))
        assertTrue(result.isFailure)
    }

    @Test
    fun `bad currency fails`() = runTest {
        val writeSink = FakeWriteSink()
        val manualSink = FakeManualEntrySink()
        val svc = ManualEntryService(TransactionWriteService(writeSink), manualSink)
        val result = svc.createManual(manualInput(currency = "RUPEES"))
        assertTrue(result.isFailure)
    }

    @Test
    fun `occurredAt more than 1h in the future fails`() = runTest {
        val writeSink = FakeWriteSink()
        val manualSink = FakeManualEntrySink()
        val svc = ManualEntryService(TransactionWriteService(writeSink), manualSink)
        val future = Instant.now().plusSeconds(3600 * 3)
        val result = svc.createManual(manualInput(occurredAt = future))
        assertTrue(result.isFailure)
    }

    @Test
    fun `editManual sets USER_CORRECTION when amount changes`() = runTest {
        val writeSink = FakeWriteSink()
        val manualSink = FakeManualEntrySink()
        val writeService = TransactionWriteService(writeSink)
        val svc = ManualEntryService(writeService, manualSink)
        val txn = svc.createManual(manualInput(amountMinor = 25_000L)).getOrThrow()
        manualSink.stored[txn.id.value] = txn
        writeSink.stored[txn.id.value] = txn

        val editResult = svc.editManual(txn.id.value, manualInput(amountMinor = 30_000L))
        assertTrue(editResult.isSuccess)
        val edited = editResult.getOrThrow()
        assertNotNull(edited.correctionOrigin)
        assertEquals(SourceKind.USER_CORRECTION, edited.correctionOrigin!!.sourceKind)
        assertEquals(30_000L, edited.amountMinor)
    }

    @Test
    fun `editManual leaves correctionOrigin null when only display fields change`() = runTest {
        val writeSink = FakeWriteSink()
        val manualSink = FakeManualEntrySink()
        val writeService = TransactionWriteService(writeSink)
        val svc = ManualEntryService(writeService, manualSink)
        val txn = svc.createManual(manualInput(merchant = "Old")).getOrThrow()
        manualSink.stored[txn.id.value] = txn
        writeSink.stored[txn.id.value] = txn

        val editResult = svc.editManual(txn.id.value, manualInput(merchant = "New", note = "updated note"))
        assertTrue(editResult.isSuccess)
        val edited = editResult.getOrThrow()
        assertNull(edited.correctionOrigin)
        assertEquals("New", edited.merchant)
    }

    @Test
    fun `restoreTransaction resurrects a soft-deleted event and writes audit`() = runTest {
        val writeSink = FakeWriteSink()
        val manualSink = FakeManualEntrySink()
        val writeService = TransactionWriteService(writeSink)
        val svc = ManualEntryService(writeService, manualSink)
        val txn = svc.createManual(manualInput()).getOrThrow()
        manualSink.stored[txn.id.value] = txn
        writeSink.stored[txn.id.value] = txn
        // Soft-delete
        writeService.softDelete(txn.id.value, reason = "test")
        // Mirror the tombstone into the manualSink (which is what the real
        // RoomManualEntryRepository would observe after the write).
        val deleted = writeSink.stored[txn.id.value]!!.copy(
            status = TxStatus.DELETED,
            deletedAt = Instant.now(),
            deletedReason = "test",
        )
        manualSink.stored[txn.id.value] = deleted

        val restored = svc.restoreTransaction(txn.id.value)
        assertTrue(restored.isSuccess)
        assertEquals(TxStatus.POSTED, manualSink.stored[txn.id.value]!!.status)
        assertEquals(1, manualSink.restoreCalls)
        assertTrue(manualSink.audit.any { it.second == "RESTORED" })
    }

    @Test
    fun `createManual and editManual are offline - no network calls touched`() = runTest {
        // The service is constructed with pure sinks (no remote IO). The
        // assertion is that no method on ManualEntryService touches any
        // network surface. This is verified by the architectural test
        // (DependencyDirectionTest) at the package level.
        val writeSink = FakeWriteSink()
        val manualSink = FakeManualEntrySink()
        val svc = ManualEntryService(TransactionWriteService(writeSink), manualSink)
        val a = svc.createManual(manualInput())
        val txn = a.getOrThrow()
        // Mirror the created transaction into the manual sink so the edit
        // can find it.
        manualSink.stored[txn.id.value] = txn
        val b = svc.editManual(txn.id.value, manualInput(merchant = "Another"))
        assertTrue(a.isSuccess)
        assertTrue(b.isSuccess)
    }
}
