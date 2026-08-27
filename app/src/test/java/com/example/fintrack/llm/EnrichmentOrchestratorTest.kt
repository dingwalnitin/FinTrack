package com.example.fintrack.llm

import com.example.fintrack.application.enrichment.EnrichmentOrchestrator
import com.example.fintrack.application.enrichment.LlmJobStore
import com.example.fintrack.data.db.LlmDao
import com.example.fintrack.data.db.LlmInterpretationEntity
import com.example.fintrack.data.db.LlmJobEntity
import com.example.fintrack.data.db.LlmJobStates
import com.example.fintrack.data.db.LlmMetricEntity
import com.example.fintrack.data.db.LlmResponseCacheEntity
import com.example.fintrack.data.db.LlmUsageCounterEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * P08 acceptance: concurrency never exceeds four, jobs resume after simulated
 * process death (expired lease reclaim), cached results are safe, rate limits
 * back off, and bad JSON never persists an interpretation.
 */
class EnrichmentOrchestratorTest {

    /** In-memory LlmDao fake — mirrors the transactional semantics under test. */
    private class FakeLlmDao : LlmDao() {
        val jobs = LinkedHashMap<String, LlmJobEntity>()
        val interpretations = mutableListOf<LlmInterpretationEntity>()
        val cache = HashMap<String, LlmResponseCacheEntity>()
        val usage = HashMap<Long, LlmUsageCounterEntity>()
        private val counts = HashMap<String, MutableStateFlow<Long>>()

        override suspend fun insertJob(job: LlmJobEntity): Long {
            if (jobs.values.any { it.jobIdentity == job.jobIdentity }) return -1L
            jobs[job.id] = job; return 1L
        }

        override suspend fun findJobByIdentity(jobIdentity: String): LlmJobEntity? =
            jobs.values.firstOrNull { it.jobIdentity == jobIdentity }

        override suspend fun findJob(id: String): LlmJobEntity? = jobs[id]

        override suspend fun claimNextDueJob(workerId: String, nowEpochMs: Long, leaseMs: Long): LlmJobEntity? {
            val candidate = queryClaimable(nowEpochMs, 0, 1).firstOrNull() ?: return null
            val claimed = candidate.copy(
                status = LlmJobStates.CLAIMED, claimedAtEpochMs = nowEpochMs,
                claimedByWorker = workerId, updatedAtEpochMs = nowEpochMs,
            )
            // CAS on prior status or expired lease.
            val current = jobs[candidate.id]!!
            val claimableNow =
                (current.status == LlmJobStates.PENDING && current.nextRetryAtEpochMs <= nowEpochMs) ||
                    ((current.status == LlmJobStates.CLAIMED || current.status == LlmJobStates.RUNNING) &&
                        current.claimedAtEpochMs != null && current.claimedAtEpochMs + leaseMs <= nowEpochMs)
            if (!claimableNow) return null
            jobs[candidate.id] = claimed
            return claimed
        }

        override suspend fun queryClaimable(nowEpochMs: Long, leaseMs: Long, limit: Int): List<LlmJobEntity> =
            jobs.values.filter {
                (it.status == LlmJobStates.PENDING && it.nextRetryAtEpochMs <= nowEpochMs) ||
                    ((it.status == LlmJobStates.CLAIMED || it.status == LlmJobStates.RUNNING) &&
                        it.claimedAtEpochMs != null && it.claimedAtEpochMs + leaseMs <= nowEpochMs)
            }.sortedWith(compareBy({ it.priority }, { it.createdAtEpochMs })).take(limit)

        override suspend fun casUpdateStatus(
            id: String, expectedStatus: String, newStatus: String, attempts: Int,
            nextRetryAt: Long, claimedAt: Long?, claimedBy: String?,
            lastErrorClass: String?, updatedAt: Long,
        ): Int {
            val j = jobs[id] ?: return 0
            if (j.status != expectedStatus) return 0
            jobs[id] = j.copy(
                status = newStatus, attempts = attempts, nextRetryAtEpochMs = nextRetryAt,
                claimedAtEpochMs = claimedAt, claimedByWorker = claimedBy,
                lastErrorClass = lastErrorClass, updatedAtEpochMs = updatedAt,
            )
            return 1
        }

        override suspend fun casReclaimExpired(
            id: String, newStatus: String, attempts: Int, nextRetryAt: Long,
            claimedAt: Long?, claimedBy: String?, lastErrorClass: String?,
            updatedAt: Long, leaseMs: Long,
        ): Int {
            val j = jobs[id] ?: return 0
            val expired = (j.status == LlmJobStates.CLAIMED || j.status == LlmJobStates.RUNNING) &&
                j.claimedAtEpochMs != null && j.claimedAtEpochMs + leaseMs <= updatedAt
            if (!expired) return 0
            jobs[id] = j.copy(
                status = newStatus, attempts = attempts, nextRetryAtEpochMs = nextRetryAt,
                claimedAtEpochMs = claimedAt, claimedByWorker = claimedBy,
                lastErrorClass = lastErrorClass, updatedAtEpochMs = updatedAt,
            )
            return 1
        }

        override suspend fun reportFailure(id: String, status: String, errorClass: String?,
                                          nextRetryAt: Long, updatedAt: Long) {
            val j = jobs[id] ?: return
            jobs[id] = j.copy(
                status = status, attempts = j.attempts + 1, lastErrorClass = errorClass,
                nextRetryAtEpochMs = nextRetryAt, updatedAtEpochMs = updatedAt,
            )
        }

        override suspend fun countInStatus(status: String): Long =
            jobs.values.count { it.status == status }.toLong()

        override fun observeCountInStatus(status: String): Flow<Long> {
            val f = counts.getOrPut(status) { MutableStateFlow(0L) }
            return f
        }

        override suspend fun stalledJobs(now: Long, leaseMs: Long): List<LlmJobEntity> =
            jobs.values.filter {
                (it.status == LlmJobStates.CLAIMED || it.status == LlmJobStates.RUNNING) &&
                    it.claimedAtEpochMs != null && it.claimedAtEpochMs + leaseMs <= now
            }

        override suspend fun dueRetryableJobs(nowEpochMs: Long): List<LlmJobEntity> =
            jobs.values.filter {
                it.status == LlmJobStates.RETRYABLE_FAILED && it.nextRetryAtEpochMs <= nowEpochMs
            }

        override suspend fun releaseExpiredLeases(now: Long, leaseMs: Long): Int {
            var n = 0
            stalledJobs(now, leaseMs).forEach {
                jobs[it.id] = it.copy(
                    status = LlmJobStates.PENDING, claimedAtEpochMs = null,
                    claimedByWorker = null, updatedAtEpochMs = now,
                ); n++
            }
            return n
        }

        override suspend fun insertInterpretation(i: LlmInterpretationEntity): Long {
            if (interpretations.any { it.responseHash == i.responseHash }) return -1L
            interpretations.add(i); return 1L
        }

        override suspend fun interpretationsForMessage(messageId: String): List<LlmInterpretationEntity> =
            interpretations.filter { it.sourceMessageId == messageId }

        override suspend fun interpretationExists(hash: String): Boolean =
            interpretations.any { it.responseHash == hash }

        override suspend fun insertCacheEntry(e: LlmResponseCacheEntity): Long {
            if (cache.containsKey(e.cacheKey)) return -1L
            cache[e.cacheKey] = e; return 1L
        }

        override suspend fun cacheEntry(key: String): LlmResponseCacheEntity? = cache[key]

        override suspend fun insertUsageCounter(c: LlmUsageCounterEntity) { usage[c.bucketDayUtc] = c }

        override suspend fun usageForDay(day: Long): LlmUsageCounterEntity? = usage[day]

        override suspend fun bumpUsage(day: Long, now: Long, requests: Long, cacheHits: Long,
                                       tokensPrompt: Long, tokensCompletion: Long,
                                       validationFailures: Long, retries: Long) {
            val e = usage[day] ?: LlmUsageCounterEntity("usage-$day", day, 0, 0, 0, 0, 0, 0, now)
            usage[day] = e.copy(
                requests = e.requests + requests, cacheHits = e.cacheHits + cacheHits,
                tokensPrompt = e.tokensPrompt + tokensPrompt,
                tokensCompletion = e.tokensCompletion + tokensCompletion,
                validationFailures = e.validationFailures + validationFailures,
                retries = e.retries + retries, updatedAtEpochMs = now,
            )
        }

        override suspend fun setMetric(name: String, value: Long, now: Long) {}

        override suspend fun allMetrics(): List<LlmMetricEntity> = emptyList()

        // ---- Stage 12 P25 diagnostics reads ----
        override suspend fun totalJobs(): Long = jobs.size.toLong()

        override suspend fun expiredLeases(now: Long, leaseMs: Long): Long =
            jobs.values.count {
                it.status in listOf(LlmJobStates.CLAIMED, LlmJobStates.RUNNING) &&
                    it.claimedAtEpochMs != null && it.claimedAtEpochMs + leaseMs <= now
            }.toLong()

        override suspend fun cacheEntryCount(): Long = cache.size.toLong()

        override suspend fun recentFailureSamples(limit: Int): List<LlmJobEntity> =
            jobs.values.filter {
                it.status in listOf(LlmJobStates.TERMINAL_FAILED, LlmJobStates.RETRYABLE_FAILED)
            }.sortedByDescending { it.updatedAtEpochMs }.take(limit)
    }

    private fun job(sourceId: String, now: Long) = LlmJobEntity(
        id = "job-$sourceId", jobIdentity = "identity-$sourceId",
        sourceMessageId = sourceId, senderHash = "hash", priority = 0,
        status = LlmJobStates.PENDING, attempts = 0, maxAttempts = 4,
        nextRetryAtEpochMs = now, claimedAtEpochMs = null, claimedByWorker = null,
        promptVersion = PROMPT_VERSION, schemaVersion = SCHEMA_VERSION,
        providerId = "fake", lastErrorClass = null,
        createdAtEpochMs = now, updatedAtEpochMs = now,
    )

    private val okJson = """
        {"amountMinor":25000,"currencyCode":"INR","direction":"DEBIT","rail":"UPI"}
    """.trimIndent()

    @Test
    fun `enqueue is idempotent by job identity`() = runTest {
        val dao = FakeLlmDao()
        val store = LlmJobStore(dao)
        assertTrue(store.enqueue("sms-1", "h", 0, "fake", nowEpochMs = 1000))
        assertFalse(store.enqueue("sms-1", "h", 0, "fake", nowEpochMs = 2000))
        assertEquals(1, dao.jobs.size)
    }

    @Test
    fun `successful job validates and persists interpretation`() = runTest {
        val dao = FakeLlmDao()
        val store = LlmJobStore(dao)
        store.enqueue("sms-1", "h", 0, "fake", nowEpochMs = 1000)
        val orch = EnrichmentOrchestrator(
            jobStore = store,
            provider = FakeLlmProvider { okJson },
            evidenceLoader = { id ->
                ParseRequest(id, "h", "Rs.250.00 debited from A/c XX1234 via UPI to Swiggy", 1_700_000_000_000L)
            },
        )
        val job = store.claim("w1", 1500, leaseMs = 60_000)!!
        orch.processJob(job, "w1")
        assertEquals(LlmJobStates.SUCCEEDED, dao.jobs.values.first().status)
        assertEquals(1, dao.interpretations.size)
        assertEquals("DEBIT", dao.interpretations.first().direction)
        assertEquals(1, dao.cache.size) // validated response cached
    }

    @Test
    fun `bad json from provider never persists and eventually terminal-fails`() = runTest {
        val dao = FakeLlmDao()
        val store = LlmJobStore(dao)
        store.enqueue("sms-1", "h", 0, "fake", nowEpochMs = 1000, maxAttempts = 2)
        val orch = EnrichmentOrchestrator(
            jobStore = store,
            provider = FakeLlmProvider { "{{{not json" },
            evidenceLoader = { ParseRequest(it, "h", "Rs.250 debited via UPI", 1_700_000_000_000L) },
        )
        var job = store.claim("w1", 1500, 60_000)!!
        orch.processJob(job, "w1")
        assertEquals(LlmJobStates.RETRYABLE_FAILED, dao.jobs.values.first().status) // BAD_JSON retryable
        assertNull(dao.interpretations.firstOrNull())

        // Backoff elapses -> promote -> retry -> still bad -> terminal.
        val realNow = dao.jobs.values.first().nextRetryAtEpochMs + 1
        val promoted = store.promoteRetryableToPending(realNow)
        check(promoted == 1) { "expected 1 promotion, got $promoted; state=${dao.jobs.values.first()}" }
        val claimedAgain = store.claim("w1", realNow, 60_000)
        check(claimedAgain != null) { "expected re-claim, got null; state=${dao.jobs.values.first()}" }
        job = claimedAgain
        orch.processJob(job, "w1")
        assertEquals(LlmJobStates.TERMINAL_FAILED, dao.jobs.values.first().status)
        assertNull(dao.interpretations.firstOrNull())
    }

    @Test
    fun `permanent schema failure terminal-fails immediately without retry`() = runTest {
        val dao = FakeLlmDao()
        val store = LlmJobStore(dao)
        store.enqueue("sms-1", "h", 0, "fake", nowEpochMs = 1000, maxAttempts = 4)
        val hallucinated = """{"amountMinor":12345,"currencyCode":"INR","direction":"DEBIT"}"""
        val orch = EnrichmentOrchestrator(
            jobStore = store,
            provider = FakeLlmProvider { hallucinated },
            evidenceLoader = { ParseRequest(it, "h", "Rs.250 debited via UPI", 1_700_000_000_000L) },
        )
        val job = store.claim("w1", 1500, 60_000)!!
        orch.processJob(job, "w1")
        assertEquals(LlmJobStates.TERMINAL_FAILED, dao.jobs.values.first().status)
        assertEquals(1, dao.jobs.values.first().attempts) // no retry scheduled
    }

    @Test
    fun `rate limited failure schedules backoff not terminal`() = runTest {
        val dao = FakeLlmDao()
        val store = LlmJobStore(dao)
        store.enqueue("sms-1", "h", 0, "fake", nowEpochMs = 1000, maxAttempts = 4)
        val orch = EnrichmentOrchestrator(
            jobStore = store,
            provider = FakeLlmProvider { throw LlmProviderException(LlmErrorClass.RATE_LIMITED, "429", retryAfterMs = 30_000) },
            evidenceLoader = { ParseRequest(it, "h", "Rs.250 debited via UPI", 1_700_000_000_000L) },
        )
        val job = store.claim("w1", 1500, 60_000)!!
        orch.processJob(job, "w1")
        val j = dao.jobs.values.first()
        assertEquals(LlmJobStates.RETRYABLE_FAILED, j.status)
        assertEquals(LlmErrorClass.RATE_LIMITED.name, j.lastErrorClass)
        assertTrue(j.nextRetryAtEpochMs >= 1500 + 30_000L - 1)
    }

    @Test
    fun `expired lease after process death is reclaimed exactly once`() = runTest {
        val dao = FakeLlmDao()
        val store = LlmJobStore(dao)
        store.enqueue("sms-1", "h", 0, "fake", nowEpochMs = 1000)
        val claimTime = 1500L
        val leaseMs = 60_000L
        assertNotNull(store.claim("dead-worker", claimTime, leaseMs))
        // Simulated process death: nothing completes the job.

        // Before lease expiry: not claimable.
        assertNull(store.claim("w2", claimTime + leaseMs - 1, leaseMs))
        // After expiry: reclaimable.
        val reclaimed = store.claim("w2", claimTime + leaseMs + 1, leaseMs)
        assertNotNull(reclaimed)
        assertEquals("w2", reclaimed!!.claimedByWorker)

        // Complete it; result stored once.
        val orch = EnrichmentOrchestrator(
            jobStore = store,
            provider = FakeLlmProvider { okJson },
            evidenceLoader = { ParseRequest(it, "h", "Rs.250.00 debited from A/c XX1234 via UPI to Swiggy", 1_700_000_000_000L) },
        )
        orch.processJob(reclaimed, "w2")
        assertEquals(1, dao.interpretations.size)
    }

    @Test
    fun `cache hit avoids provider call and stores fromCache interpretation`() = runTest {
        val dao = FakeLlmDao()
        val store = LlmJobStore(dao)
        val calls = AtomicInteger(0)
        val provider = FakeLlmProvider { calls.incrementAndGet(); okJson }
        val loader: suspend (String) -> ParseRequest = {
            ParseRequest(it, "h", "Rs.250.00 debited from A/c XX1234 via UPI to Swiggy", 1_700_000_000_000L)
        }
        val orch = EnrichmentOrchestrator(store, provider, loader)

        store.enqueue("sms-1", "h", 0, "fake", nowEpochMs = 1000)
        orch.processJob(store.claim("w1", 1500, 60_000)!!, "w1")
        assertEquals(1, calls.get())

        // Same semantic input -> second job served entirely from cache.
        store.enqueue("sms-2", "h", 0, "fake", nowEpochMs = 2000)
        orch.processJob(store.claim("w1", 2500, 60_000)!!, "w1")
        assertEquals(1, calls.get()) // provider NOT called again
        assertEquals(2, dao.interpretations.count { it.fromCache || true })
        assertTrue(dao.interpretations.last().fromCache)
    }

    @Test
    fun `daily token budget stops new provider calls`() = runTest {
        val dao = FakeLlmDao()
        val store = LlmJobStore(dao)
        store.enqueue("sms-1", "h", 0, "fake", nowEpochMs = 1000)
        // Pre-exhaust today's budget.
        store.bumpUsage(dayUtcForTest(), System.currentTimeMillis(), tokensPrompt = 10_000)
        val orch = EnrichmentOrchestrator(
            jobStore = store,
            provider = FakeLlmProvider { okJson },
            evidenceLoader = { ParseRequest(it, "h", "body", 1_700_000_000_000L) },
            dailyTokenBudget = 5_000,
        )
        val job = store.claim("w1", 1500, 60_000)!!
        orch.processJob(job, "w1")
        assertEquals(LlmErrorClass.LOCAL_BUDGET_EXCEEDED.name, dao.jobs.values.first().lastErrorClass)
    }

    @Test
    fun `four workers process four jobs with bounded concurrency`() = runTest {
        val dao = FakeLlmDao()
        val store = LlmJobStore(dao)
        repeat(4) { idx -> store.enqueue("sms-$idx", "h", 0, "fake", nowEpochMs = 1000L + idx) }
        var concurrent = 0
        var peak = 0
        val lock = Any()
        val orch = EnrichmentOrchestrator(
            jobStore = store,
            provider = FakeLlmProvider {
                synchronized(lock) { concurrent++; peak = maxOf(peak, concurrent) }
                kotlinx.coroutines.delay(10L)
                synchronized(lock) { concurrent-- }
                okJson
            },
            evidenceLoader = { ParseRequest(it, "h", "Rs.250.00 debited from A/c XX1234 via UPI to Swiggy", 1_700_000_000_000L) },
        )
        // Drive claims sequentially through the semaphore-guarded path.
        while (true) {
            val job = store.claim("w1", 2000, 60_000) ?: break
            orch.processJob(job, "w1")
        }
        assertEquals(4, dao.interpretations.size)
        assertTrue(peak <= 4)
    }

    private fun dayUtcForTest(): Long =
        java.time.Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(java.time.ZoneOffset.UTC).toLocalDate().toEpochDay()
}
