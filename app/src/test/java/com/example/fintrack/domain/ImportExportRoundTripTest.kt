package com.example.fintrack.domain

import com.example.fintrack.domain.model.BackupDataset
import com.example.fintrack.domain.model.MergePolicy
import com.example.fintrack.domain.service.BackupCodec
import com.example.fintrack.domain.service.BackupSink
import com.example.fintrack.domain.service.BackupService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 12 P26 #5 — Import/export round-trip and migration tests.
 *
 * Uses the in-memory fake sink so round-trips run on JVM. Covers clean-db
 * restore, conflict cases (KEEP_LIVE default), explicit REPLACE, and
 * idempotent re-import.
 */
class ImportExportRoundTripTest {

    private class FakeSink : BackupSink {
        val live = mutableMapOf<BackupDataset, MutableMap<String, String>>()
        val staged = mutableMapOf<BackupDataset, MutableMap<String, String>>()

        private fun stableId(row: String): String =
            row.split(';').firstOrNull { it.startsWith("id=") }?.removePrefix("id=")?.trim() ?: ""

        override suspend fun exportRows(dataset: BackupDataset): List<String> =
            live[dataset]?.values?.toList() ?: emptyList()

        override suspend fun beginBatch(formatVersion: Int, schemaVersion: Int, totalRows: Int) {
            staged.clear()
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
            val inserted = mutableMapOf<BackupDataset, Int>()
            val replaced = mutableMapOf<BackupDataset, Int>()
            for ((ds, rows) in staged) {
                var ins = 0
                var rep = 0
                for ((id, row) in rows) {
                    val target = live.getOrPut(ds) { mutableMapOf() }
                    if (target.containsKey(id)) {
                        if (policy == MergePolicy.REPLACE_WITH_IMPORTED && (replaceIds[ds]?.contains(id) == true)) {
                            target[id] = row
                            rep++
                        }
                        // else KEEP_LIVE: skip
                    } else {
                        target[id] = row
                        ins++
                    }
                }
                inserted[ds] = ins
                replaced[ds] = rep
            }
            return Pair(inserted, replaced)
        }

        override suspend fun liveRowById(dataset: BackupDataset, stableId: String): String? =
            live[dataset]?.get(stableId)

        override suspend fun liveIds(dataset: BackupDataset): List<String> =
            live[dataset]?.keys?.toList() ?: emptyList()
    }

    private fun service(sink: FakeSink) = BackupService(
        sink = sink,
        codec = BackupCodec(),
        clock = { 1_700_000_000_000L },
    )

    private fun row(id: String, dataset: BackupDataset): String = "id=$id;dataset=${dataset.name};value=1"

    @Test
    fun `round trip — export then restore on clean database`() = runTest {
        val source = FakeSink()
        source.live[BackupDataset.ACCOUNTS] = mutableMapOf(
            "acc1" to row("acc1", BackupDataset.ACCOUNTS),
            "acc2" to row("acc2", BackupDataset.ACCOUNTS),
        )
        source.live[BackupDataset.CATEGORIES] = mutableMapOf(
            "cat1" to row("cat1", BackupDataset.CATEGORIES),
        )
        val svc = service(source)
        val payload = svc.buildExport("0.1.0")

        // Restore into an empty sink.
        val target = FakeSink()
        val targetSvc = service(target)
        val validation = targetSvc.validate(payload.body)
        assertTrue("validation must pass", validation is com.example.fintrack.domain.model.ImportValidation.Valid)
        val staged = targetSvc.stageValidated(payload.body)
        assertTrue(staged is com.example.fintrack.domain.model.ImportValidation.Valid)
        val commit = targetSvc.commit(policy = MergePolicy.KEEP_LIVE)
        assertTrue(commit is com.example.fintrack.domain.model.ImportCommitResult.Committed)
        val result = commit as com.example.fintrack.domain.model.ImportCommitResult.Committed
        assertEquals(3, result.insertedByDataset.values.sum())
        assertEquals(row("acc1", BackupDataset.ACCOUNTS), target.live[BackupDataset.ACCOUNTS]?.get("acc1"))
        assertEquals(row("cat1", BackupDataset.CATEGORIES), target.live[BackupDataset.CATEGORIES]?.get("cat1"))
    }

    @Test
    fun `conflict — KEEP_LIVE default preserves existing rows`() = runTest {
        val source = FakeSink()
        source.live[BackupDataset.ACCOUNTS] = mutableMapOf(
            "acc1" to row("acc1", BackupDataset.ACCOUNTS),
        )
        val svc = service(source)
        val payload = svc.buildExport("0.1.0")

        val target = FakeSink()
        target.live[BackupDataset.ACCOUNTS] = mutableMapOf(
            "acc1" to "id=acc1;dataset=ACCOUNTS;value=LOCAL_CHANGE",
        )
        val targetSvc = service(target)
        val validation = targetSvc.validate(payload.body)
        assertTrue(validation is com.example.fintrack.domain.model.ImportValidation.Valid)
        val staged = targetSvc.stageValidated(payload.body)
        assertTrue(staged is com.example.fintrack.domain.model.ImportValidation.Valid)
        val commit = targetSvc.commit(policy = MergePolicy.KEEP_LIVE)
        // Existing row preserved
        assertEquals("id=acc1;dataset=ACCOUNTS;value=LOCAL_CHANGE", target.live[BackupDataset.ACCOUNTS]?.get("acc1"))
    }

    @Test
    fun `conflict — explicit REPLACE honors the selected ids`() = runTest {
        val source = FakeSink()
        source.live[BackupDataset.ACCOUNTS] = mutableMapOf(
            "acc1" to row("acc1", BackupDataset.ACCOUNTS),
        )
        val svc = service(source)
        val payload = svc.buildExport("0.1.0")

        val target = FakeSink()
        target.live[BackupDataset.ACCOUNTS] = mutableMapOf(
            "acc1" to "id=acc1;dataset=ACCOUNTS;value=LOCAL_CHANGE",
        )
        val targetSvc = service(target)
        val validation = targetSvc.validate(payload.body)
        assertTrue(validation is com.example.fintrack.domain.model.ImportValidation.Valid)
        val staged = targetSvc.stageValidated(payload.body)
        assertTrue(staged is com.example.fintrack.domain.model.ImportValidation.Valid)
        val commit = targetSvc.commit(
            policy = MergePolicy.REPLACE_WITH_IMPORTED,
            userSelectedReplaceIds = setOf("acc1"),
        )
        // Row now matches the imported value.
        assertEquals(row("acc1", BackupDataset.ACCOUNTS), target.live[BackupDataset.ACCOUNTS]?.get("acc1"))
    }

    @Test
    fun `idempotent re-import — no duplicates on second restore`() = runTest {
        val source = FakeSink()
        source.live[BackupDataset.ACCOUNTS] = mutableMapOf(
            "acc1" to row("acc1", BackupDataset.ACCOUNTS),
        )
        val svc = service(source)
        val payload = svc.buildExport("0.1.0")

        val target = FakeSink()
        val targetSvc = service(target)
        val v1 = targetSvc.validate(payload.body)
        assertTrue(v1 is com.example.fintrack.domain.model.ImportValidation.Valid)
        targetSvc.stageValidated(payload.body)
        targetSvc.commit(policy = MergePolicy.KEEP_LIVE)
        val firstCount = target.live[BackupDataset.ACCOUNTS]?.size ?: 0
        assertEquals(1, firstCount)

        // Re-import same payload — should be a no-op.
        val v2 = targetSvc.validate(payload.body)
        assertTrue(v2 is com.example.fintrack.domain.model.ImportValidation.Valid)
        targetSvc.stageValidated(payload.body)
        targetSvc.commit(policy = MergePolicy.KEEP_LIVE)
        val secondCount = target.live[BackupDataset.ACCOUNTS]?.size ?: 0
        assertEquals("no duplicates on re-import", 1, secondCount)
    }
}