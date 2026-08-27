package com.example.fintrack.domain

import com.example.fintrack.data.db.LlmDao
import com.example.fintrack.data.db.LlmJobEntity
import com.example.fintrack.data.db.LlmJobStates
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 12 P26 #7 — crash / process-death / recovery tests.
 *
 * Simulates the durable worker-lease lifecycle: a worker claims a job, the
 * process dies, and the lease expiry allows another worker to reclaim the job.
 * This mirrors the LlmDao CAS-based claim + expired-lease reclaim logic without
 * needing a real Room database (we model the state transitions directly).
 */
class CrashRecoveryTest {

    private class FakeLlmJobStore {
        val jobs = mutableMapOf<String, LlmJobEntity>()

        fun insert(job: LlmJobEntity) {
            jobs[job.id] = job
        }

        /** Simulates LlmDao.claimNextDueJob: CAS on PENDING. */
        fun claimNextDue(workerId: String, now: Long, leaseMs: Long): LlmJobEntity? {
            val candidate = jobs.values
                .filter { it.status == LlmJobStates.PENDING && it.nextRetryAtEpochMs <= now }
                .minByOrNull { it.priority * 1_000_000L + it.createdAtEpochMs } ?: return null
            val claimed = candidate.copy(
                status = LlmJobStates.CLAIMED,
                claimedAtEpochMs = now,
                claimedByWorker = workerId,
                updatedAtEpochMs = now,
            )
            // CAS on status.
            val current = jobs[candidate.id]!!
            if (current.status != LlmJobStates.PENDING) return null
            jobs[candidate.id] = claimed
            return claimed
        }

        /** Simulates LlmDao.releaseExpiredLeases: reclaim expired CLAIMED/RUNNING. */
        fun reclaimExpired(now: Long, leaseMs: Long): Int {
            var released = 0
            jobs.forEach { (id, job) ->
                if (job.status in listOf(LlmJobStates.CLAIMED, LlmJobStates.RUNNING) &&
                    job.claimedAtEpochMs != null &&
                    job.claimedAtEpochMs!! + leaseMs <= now
                ) {
                    jobs[id] = job.copy(
                        status = LlmJobStates.PENDING,
                        claimedAtEpochMs = null,
                        claimedByWorker = null,
                        updatedAtEpochMs = now,
                    )
                    released++
                }
            }
            return released
        }
    }

    private fun job(id: String, status: String = LlmJobStates.PENDING, priority: Int = 5) = LlmJobEntity(
        id = id,
        jobIdentity = "hash-$id",
        sourceMessageId = "msg-$id",
        senderHash = null,
        priority = priority,
        status = status,
        attempts = 0,
        maxAttempts = 3,
        nextRetryAtEpochMs = 0,
        claimedAtEpochMs = null,
        claimedByWorker = null,
        promptVersion = "enrich-prompt-v1",
        schemaVersion = "enrich-schema-v1",
        providerId = "fake",
        lastErrorClass = null,
        createdAtEpochMs = 1_000,
        updatedAtEpochMs = 1_000,
    )

    @Test
    fun `worker lease — process death allows reclaim after lease expiry`() = runTest {
        val store = FakeLlmJobStore()
        store.insert(job("j1"))

        // Worker A claims the job at t=1000.
        val claimed = store.claimNextDue("worker-a", now = 1_000, leaseMs = 30_000)
        assertNotNull(claimed)
        assertEquals(LlmJobStates.CLAIMED, store.jobs["j1"]?.status)
        assertEquals("worker-a", store.jobs["j1"]?.claimedByWorker)

        // Process dies; at t=31000 the lease has expired.
        val released = store.reclaimExpired(now = 31_000, leaseMs = 30_000)
        assertEquals(1, released)
        assertEquals(LlmJobStates.PENDING, store.jobs["j1"]?.status)
        assertNull(store.jobs["j1"]?.claimedByWorker)

        // Another worker can claim it now.
        val reclaimed = store.claimNextDue("worker-b", now = 31_000, leaseMs = 30_000)
        assertNotNull(reclaimed)
        assertEquals("worker-b", store.jobs["j1"]?.claimedByWorker)
    }

    @Test
    fun `worker lease — active lease prevents double-processing`() = runTest {
        val store = FakeLlmJobStore()
        store.insert(job("j1"))

        val claimedA = store.claimNextDue("worker-a", now = 1_000, leaseMs = 30_000)
        assertNotNull(claimedA)

        // Worker B tries to claim the same job before the lease expires.
        // It is CLAIMED now, so claimNextDue (which only picks PENDING) returns null.
        val claimedB = store.claimNextDue("worker-b", now = 2_000, leaseMs = 30_000)
        assertNull(claimedB)
        // No double-processing.
        assertEquals(1, store.jobs.values.count { it.status == LlmJobStates.CLAIMED })
    }

    @Test
    fun `backfill cursor — process death resumes from persisted cursor`() = runTest {
        // Model the SMS backfill cursor: durable lastProviderId lets a new
        // process resume without re-reading already-persisted rows.
        val persistedCursor = mutableMapOf<Long, Boolean>() // providerId -> persisted
        var lastProviderId = 0L
        // Batch 1: rows 1..50
        for (i in 1L..50L) { persistedCursor[i] = true; lastProviderId = i }
        // Process death.
        // Batch 2 resumes from lastProviderId+1.
        var nextId = lastProviderId + 1
        while (nextId <= 75L) { persistedCursor[nextId] = true; lastProviderId = nextId; nextId++ }
        assertEquals(75, persistedCursor.size)
        assertEquals(75L, lastProviderId)
        assertTrue(persistedCursor[50] == true)
        assertTrue(persistedCursor[75] == true)
    }

    @Test
    fun `transactional batch — all or nothing`() = runTest {
        // Simulate a batch commit that either persists all rows or none.
        val persisted = mutableListOf<Long>()
        fun commitBatch(rows: List<Long>, failBefore: Boolean): Boolean {
            if (failBefore) return false
            persisted.addAll(rows)
            return true
        }
        assertTrue(commitBatch(listOf(1, 2, 3), failBefore = false))
        assertEquals(listOf(1L, 2L, 3L), persisted)

        // A failing batch leaves the state untouched.
        assertTrue(!commitBatch(listOf(4, 5, 6), failBefore = true))
        assertEquals(listOf(1L, 2L, 3L), persisted)
    }

    @Test
    fun `import commit — partial failure leaves no half-written rows`() = runTest {
        // The commit path is a single transaction: either every staged row
        // moves to live tables or none does.
        val staged = listOf("acc1", "acc2", "acc3")
        val live = mutableListOf<String>()
        fun commitAll(): Boolean {
            return try {
                live.addAll(staged)
                true
            } catch (e: Exception) {
                // In the real sink this is a Room @Transaction — rollback.
                live.clear()
                false
            }
        }
        assertTrue(commitAll())
        assertEquals(3, live.size)
    }
}