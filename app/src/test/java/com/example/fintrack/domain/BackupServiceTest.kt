package com.example.fintrack.domain

import com.example.fintrack.domain.model.BackupDataset
import com.example.fintrack.domain.model.ImportCommitResult
import com.example.fintrack.domain.model.ImportValidation
import com.example.fintrack.domain.model.MergePolicy
import com.example.fintrack.domain.service.BackupCodec
import com.example.fintrack.domain.service.BackupSink
import com.example.fintrack.domain.service.BackupService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 11 P23 — backup/restore core: deterministic export, validation,
 * staging, preview, idempotent re-import, explicit conflict resolution.
 */
class BackupServiceTest {

    private class FakeSink : BackupSink {
        val live = mutableMapOf<BackupDataset, MutableMap<String, String>>()
        val staged = mutableMapOf<BackupDataset, MutableMap<String, String>>()

        override suspend fun exportRows(dataset: BackupDataset): List<String> =
            live[dataset]?.values?.toList() ?: emptyList()

        override suspend fun beginBatch(formatVersion: Int, schemaVersion: Int, totalRows: Int) {
            // no-op for the in-memory fake
        }

        override suspend fun stageRows(dataset: BackupDataset, rows: List<String>) {
            val m = staged.getOrPut(dataset) { mutableMapOf() }
            rows.forEach { m[stableId(it)] = it }
        }

        override suspend fun stagedDatasets(): List<BackupDataset> =
            staged.filterValues { it.isNotEmpty() }.keys.toList()

        override suspend fun stagedRowCount(dataset: BackupDataset): Int =
            staged[dataset]?.size ?: 0

        override suspend fun stagedIds(dataset: BackupDataset): List<String> =
            staged[dataset]?.keys?.toList() ?: emptyList()

        override suspend fun stagedRowById(dataset: BackupDataset, stableId: String): String? =
            staged[dataset]?.get(stableId)

        override suspend fun clearStaging() = staged.clear()

        override suspend fun commitStaged(
            policy: MergePolicy,
            replaceIds: Map<BackupDataset, Set<String>>,
        ): Pair<Map<BackupDataset, Int>, Map<BackupDataset, Int>> {
            var inserted = 0
            var replaced = 0
            for ((ds, rows) in staged) {
                for ((id, row) in rows) {
                    val target = live.getOrPut(ds) { mutableMapOf() }
                    if (target.containsKey(id)) {
                        if (id in (replaceIds[ds] ?: emptySet())) {
                            target[id] = row
                            replaced++
                        } else {
                            // KEEP_LIVE: skip
                        }
                    } else {
                        target[id] = row
                        inserted++
                    }
                }
            }
            staged.clear()
            return Pair(
                BackupDataset.entries.associateWith { inserted },
                BackupDataset.entries.associateWith { replaced },
            )
        }

        override suspend fun liveRowById(dataset: BackupDataset, stableId: String): String? =
            live[dataset]?.get(stableId)

        override suspend fun liveIds(dataset: BackupDataset): List<String> =
            live[dataset]?.keys?.toList() ?: emptyList()

        private fun stableId(row: String) =
            row.split(';').firstOrNull { it.startsWith("id=") }?.removePrefix("id=") ?: ""
    }

    private fun service(sink: FakeSink) = BackupService(
        sink = sink,
        codec = BackupCodec(),
        clock = { 1_700_000_000_000L },
    )

    private val txnA = "id=txn-a;accountId=acc-1;amountMinor=25000;currencyCode=INR;kind=EXPENSE;status=POSTED"
    private val txnAChanged = "id=txn-a;accountId=acc-1;amountMinor=25500;currencyCode=INR;kind=EXPENSE;status=POSTED"
    private val txnB = "id=txn-b;accountId=acc-1;amountMinor=9900;currencyCode=INR;kind=INCOME;status=POSTED"

    @Test
    fun `export is deterministic - same state produces identical payload`() = runTest {
        val sink = FakeSink()
        sink.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["txn-b"] = txnB
        sink.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["txn-a"] = txnA

        val e1 = service(sink).buildExport("test-1")
        val e2 = service(sink).buildExport("test-1")
        assertEquals(e1.body, e2.body)
    }

    @Test
    fun `export manifest carries per-dataset checksums and no secrets dataset exists`() = runTest {
        val sink = FakeSink()
        sink.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["txn-a"] = txnA
        val payload = service(sink).buildExport("test-1")

        assertTrue(payload.manifest.datasets.isNotEmpty())
        assertTrue(payload.manifest.datasets.all { it.sha256.length == 64 })
        // Structural exclusion of secrets: the enum has no LLM/provider entry.
        assertTrue(BackupDataset.entries.none { it.name.contains("LLM") || it.name.contains("SECRET") })
    }

    @Test
    fun `round trip export then validate succeeds with matching counts`() = runTest {
        val sink = FakeSink()
        sink.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["txn-a"] = txnA
        sink.live.getOrPut(BackupDataset.ACCOUNTS) { mutableMapOf() }["acc-1"] =
            "id=acc-1;name=HDFC;normalizedName=hdfc;currencyCode=INR"

        val payload = service(sink).buildExport("test-1")
        when (val v = service(FakeSink()).validate(payload.body)) {
            is ImportValidation.Valid -> {
                assertEquals(1, v.counts[BackupDataset.TRANSACTIONS])
                assertEquals(1, v.counts[BackupDataset.ACCOUNTS])
            }
            is ImportValidation.Invalid -> throw AssertionError("expected valid: ${v.reasons}")
        }
    }

    @Test
    fun `tampered payload fails checksum validation`() = runTest {
        val sink = FakeSink()
        sink.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["txn-a"] = txnA
        val body = service(sink).buildExport("test-1").body
        val tampered = body.replace("amountMinor=25000", "amountMinor=99999")

        when (val v = service(FakeSink()).validate(tampered)) {
            is ImportValidation.Invalid -> assertTrue(
                v.reasons.any { it.contains("checksum", ignoreCase = true) },
            )
            is ImportValidation.Valid -> throw AssertionError("tampered payload must not validate")
        }
    }

    @Test
    fun `newer format or schema version is rejected before any write`() = runTest {
        val sink = FakeSink()
        sink.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["txn-a"] = txnA
        val body = service(sink).buildExport("test-1").body
            .replace("formatVersion=1", "formatVersion=99")

        when (val v = service(FakeSink()).validate(body)) {
            is ImportValidation.Invalid -> assertTrue(v.reasons.any { it.contains("newer") })
            is ImportValidation.Valid -> throw AssertionError("future format must be rejected")
        }
    }

    @Test
    fun `re-importing same export twice is idempotent`() = runTest {
        val source = FakeSink()
        source.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["txn-a"] = txnA
        source.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["txn-b"] = txnB
        val payload = service(source).buildExport("test-1")

        val target = FakeSink()
        val svc = service(target)
        svc.stageValidated(payload.body)
        val first = svc.commit(MergePolicy.KEEP_LIVE)
        assertTrue(first is ImportCommitResult.Committed)

        // Second import of the SAME file: everything is identical → no new rows.
        svc.stageValidated(payload.body)
        val preview = svc.preview()
        assertEquals(0, preview.totalNew())
        assertEquals(2, preview.totalIdentical())
        val second = svc.commit(MergePolicy.KEEP_LIVE)
        assertTrue(second is ImportCommitResult.Committed)
        assertEquals(0, (second as ImportCommitResult.Committed)
            .insertedByDataset.values.sum())
        assertEquals(2, target.live[BackupDataset.TRANSACTIONS]?.size)
    }

    @Test
    fun `conflicting row requires explicit user choice - keep live default`() = runTest {
        val source = FakeSink()
        source.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["txn-a"] = txnAChanged
        val payload = service(source).buildExport("test-1")

        val target = FakeSink()
        target.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["txn-a"] = txnA
        val svc = service(target)
        svc.stageValidated(payload.body)
        val preview = svc.preview()
        assertEquals(1, preview.realConflicts().size)
        assertTrue(preview.realConflicts()[0].differenceSummary.contains("amountMinor"))

        val result = svc.commit(MergePolicy.KEEP_LIVE)
        assertTrue(result is ImportCommitResult.Committed)
        assertEquals(1, (result as ImportCommitResult.Committed).skippedConflicts)
        // Live value survived.
        assertTrue(target.live[BackupDataset.TRANSACTIONS]!!["txn-a"]!!.contains("amountMinor=25000"))
    }

    @Test
    fun `replace-with-imported overwrites only selected conflicts`() = runTest {
        val source = FakeSink()
        source.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["txn-a"] = txnAChanged
        val payload = service(source).buildExport("test-1")

        val target = FakeSink()
        target.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["txn-a"] = txnA
        val svc = service(target)
        svc.stageValidated(payload.body)
        // REPLACE now requires an explicit per-row selection; without it
        // nothing is overwritten (fail-safe default).
        val noSelection = svc.commit(MergePolicy.REPLACE_WITH_IMPORTED, emptySet())
        assertTrue(noSelection is ImportCommitResult.Committed)
        assertTrue(
            target.live[BackupDataset.TRANSACTIONS]!!["txn-a"]!!.contains("amountMinor=25000"),
        )

        // Re-stage and commit again WITH the explicit selection.
        svc.stageValidated(payload.body)
        val result = svc.commit(MergePolicy.REPLACE_WITH_IMPORTED, setOf("txn-a"))
        assertTrue(result is ImportCommitResult.Committed)
        assertTrue(
            target.live[BackupDataset.TRANSACTIONS]!!["txn-a"]!!.contains("amountMinor=25500"),
        )
    }

    @Test
    fun `abort-on-conflict policy writes nothing when a real conflict exists`() = runTest {
        val source = FakeSink()
        source.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["txn-a"] = txnAChanged
        val payload = service(source).buildExport("test-1")

        val target = FakeSink()
        target.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["txn-a"] = txnA
        val svc = service(target)
        svc.stageValidated(payload.body)
        val result = svc.commit(MergePolicy.ABORT_ON_CONFLICT)
        assertTrue(result is ImportCommitResult.Aborted)
        // Nothing written.
        assertEquals(1, target.live[BackupDataset.TRANSACTIONS]?.size)
    }

    @Test
    fun `clean install restore brings in all rows`() = runTest {
        val source = FakeSink()
        source.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["txn-a"] = txnA
        source.live.getOrPut(BackupDataset.TRANSACTIONS) { mutableMapOf() }["txn-b"] = txnB
        val payload = service(source).buildExport("test-1")

        val fresh = FakeSink()
        val svc = service(fresh)
        svc.stageValidated(payload.body)
        val preview = svc.preview()
        assertEquals(2, preview.totalNew())
        assertTrue(preview.realConflicts().isEmpty())
        val result = svc.commit(MergePolicy.KEEP_LIVE)
        assertTrue(result is ImportCommitResult.Committed)
        assertEquals(2, fresh.live[BackupDataset.TRANSACTIONS]?.size)
    }
}
