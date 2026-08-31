package com.example.fintrack.llm

import com.example.fintrack.application.enrichment.LlmProcessingService
import com.example.fintrack.data.db.LlmDao
import com.example.fintrack.data.db.LlmInterpretationEntity
import com.example.fintrack.data.db.LlmJobEntity
import com.example.fintrack.data.db.LlmJobStates
import com.example.fintrack.data.db.LlmMetricEntity
import com.example.fintrack.data.db.LlmResponseCacheEntity
import com.example.fintrack.data.db.LlmUsageCounterEntity
import com.example.fintrack.data.db.RawSmsEntity
import com.example.fintrack.data.db.SmsBackfillCursorEntity
import com.example.fintrack.data.db.SmsDao
import com.example.fintrack.data.db.SmsIngestionProgressEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the triage phase of [LlmProcessingService]:
 * threshold boundary, batch chunking, fail-open behavior on triage errors,
 * the missing-index fail-open fix, and the scanJob start/stop/start fix
 * that previously left the service frozen at 0/0.
 */
class LlmProcessingServiceTriageTest {

    /** Minimal in-memory [LlmDao] fake — only the members the service touches matter. */
    private class FakeLlmDao : LlmDao() {
        val interpretations = mutableListOf<LlmInterpretationEntity>()
        val jobs = LinkedHashMap<String, LlmJobEntity>()
        val metrics = HashMap<String, Long>()

        override suspend fun insertInterpretation(i: LlmInterpretationEntity): Long {
            interpretations.add(i); return 1L
        }

        override suspend fun interpretationsForMessage(messageId: String): List<LlmInterpretationEntity> =
            interpretations.filter { it.sourceMessageId == messageId }

        override suspend fun interpretedMessageIds(): List<String> =
            interpretations.map { it.sourceMessageId }.distinct()

        override suspend fun settledJobMessageIds(): List<String> =
            jobs.values
                .filter { it.status == LlmJobStates.SUCCEEDED || it.status == LlmJobStates.TERMINAL_FAILED }
                .map { it.sourceMessageId }
                .distinct()

        override suspend fun updateJobOutcome(
            jobIdentity: String, status: String, errorClass: String?, now: Long,
        ): Int {
            val job = jobs[jobIdentity] ?: return 0
            jobs[jobIdentity] = job.copy(status = status, lastErrorClass = errorClass, updatedAtEpochMs = now)
            return 1
        }

        override suspend fun interpretationExists(hash: String): Boolean =
            interpretations.any { it.responseHash == hash }

        override suspend fun insertJob(job: LlmJobEntity): Long {
            if (jobs.containsKey(job.jobIdentity)) return -1L
            jobs[job.jobIdentity] = job
            return 1L
        }

        override suspend fun findJobByIdentity(jobIdentity: String): LlmJobEntity? = jobs[jobIdentity]
        override suspend fun findJob(id: String): LlmJobEntity? = jobs.values.find { it.id == id }
        override suspend fun queryClaimable(nowEpochMs: Long, leaseMs: Long, limit: Int) = emptyList<LlmJobEntity>()
        override suspend fun casUpdateStatus(
            id: String, expectedStatus: String, newStatus: String, attempts: Int,
            nextRetryAt: Long, claimedAt: Long?, claimedBy: String?,
            lastErrorClass: String?, updatedAt: Long,
        ): Int = 0
        override suspend fun casReclaimExpired(
            id: String, newStatus: String, attempts: Int, nextRetryAt: Long,
            claimedAt: Long?, claimedBy: String?, lastErrorClass: String?,
            updatedAt: Long, leaseMs: Long,
        ): Int = 0
        override suspend fun reportFailure(id: String, status: String, errorClass: String?, nextRetryAt: Long, updatedAt: Long) {}
        override suspend fun countInStatus(status: String): Long = 0
        override fun observeCountInStatus(status: String): Flow<Long> = MutableStateFlow(0L)
        override suspend fun stalledJobs(now: Long, leaseMs: Long) = emptyList<LlmJobEntity>()
        override suspend fun dueRetryableJobs(nowEpochMs: Long) = emptyList<LlmJobEntity>()
        override suspend fun releaseExpiredLeases(now: Long, leaseMs: Long): Int = 0
        override suspend fun insertCacheEntry(e: LlmResponseCacheEntity): Long = 1L
        override suspend fun cacheEntry(key: String): LlmResponseCacheEntity? = null
        override suspend fun insertUsageCounter(c: LlmUsageCounterEntity) {}
        override suspend fun usageForDay(day: Long): LlmUsageCounterEntity? = null
        override suspend fun setMetric(name: String, value: Long, now: Long) { metrics[name] = value }
        override suspend fun allMetrics(): List<LlmMetricEntity> = metrics.map { (name, value) ->
            LlmMetricEntity(id = name, metricName = name, value = value, updatedAtEpochMs = 0L)
        }
        override suspend fun totalJobs(): Long = 0
        override suspend fun expiredLeases(now: Long, leaseMs: Long): Long = 0
        override suspend fun cacheEntryCount(): Long = 0
        override suspend fun recentFailureSamples(limit: Int) = emptyList<LlmJobEntity>()
    }

    /** In-memory [SmsDao] fake — only [allRawRows] is exercised by the service. */
    private class FakeSmsDao(var rows: List<RawSmsEntity>) : SmsDao {
        override suspend fun insertRawBatch(rows: List<RawSmsEntity>): List<Long> = emptyList()
        override fun observeRawCount(): Flow<Long> = MutableStateFlow(rows.size.toLong())
        override suspend fun rawCount(): Long = rows.size.toLong()
        override suspend fun findByProviderId(providerId: Long): RawSmsEntity? = null
        override suspend fun findByContentHash(hash: String): RawSmsEntity? = null
        override suspend fun allRawRows(): List<RawSmsEntity> = rows
        override suspend fun rawSmsById(id: String): RawSmsEntity? = rows.find { it.id == id }
        override suspend fun upsertCursor(cursor: SmsBackfillCursorEntity) {}
        override suspend fun getCursor(): SmsBackfillCursorEntity? = null
        override fun observeCursor(): Flow<SmsBackfillCursorEntity?> = MutableStateFlow(null)
        override suspend fun upsertProgress(progress: SmsIngestionProgressEntity) {}
        override fun observeProgress(): Flow<SmsIngestionProgressEntity?> = MutableStateFlow(null)
        override suspend fun getProgress(): SmsIngestionProgressEntity? = null
    }

    private fun row(
        id: String,
        body: String = "some SMS body $id",
        receivedAt: Long = 1_000L,
        sourceKind: String = "BACKFILL",
    ) = RawSmsEntity(
        id = id, providerId = id.hashCode().toLong(), sender = "BANK", receivedAtEpochMs = receivedAt,
        body = body, contentHash = "hash-$id", sourceKind = sourceKind, sourceVersion = "v1",
        capturedAtEpochMs = receivedAt,
    )

    private fun triageJson(scores: List<Pair<Int, Double>>) =
        scores.joinToString(prefix = "[", postfix = "]") { (i, s) -> "{\"index\":$i,\"confidence\":$s}" }

    @Test
    fun `triage threshold is inclusive at 0point7`() = runTest {
        val rows = listOf(row("a"), row("b"))
        val smsDao = FakeSmsDao(rows)
        val llmDao = FakeLlmDao()
        val provider = FakeLlmProvider { prompt ->
            if (prompt.contains("financial SMS classifier")) {
                triageJson(listOf(0 to 0.7, 1 to 0.6999))
            } else {
                "not json" // forces an immediate, non-retryable Invalid decode
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = LlmProcessingService(smsDao, llmDao, provider, scope)

        service.startScan()
        advanceUntilIdle()

        // Both rows attempted decode == both reached processOne (only score>=0.7
        // should truly need the extraction call, but score 1 fails triage and must
        // be skipped without ever calling the provider again for it).
        assertEquals("COMPLETE", service.progress.value.status)
        assertEquals(1L, service.progress.value.failed) // row "a" reaches decode, fails validation
        assertEquals(2L, service.progress.value.processed)
    }

    @Test
    fun `batch of 45 rows splits into 20 20 5 triage calls`() = runTest {
        val rows = (1..45).map { row("id$it") }
        val smsDao = FakeSmsDao(rows)
        val llmDao = FakeLlmDao()
        var triageCalls = 0
        val provider = FakeLlmProvider { prompt ->
            if (prompt.contains("financial SMS classifier")) {
                triageCalls++
                triageJson(emptyList()) // no scores -> every row fails-open at score 0.0? no: missing entirely -> default now 1.0 (fail open)
            } else {
                "not json"
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = LlmProcessingService(smsDao, llmDao, provider, scope)

        service.startScan()
        advanceUntilIdle()

        assertEquals(3, triageCalls)
        // Every row's index was omitted from the triage response, so under the
        // fail-open fix every one of the 45 rows still proceeds to interpretation.
        assertEquals(45L, service.progress.value.failed)
    }

    @Test
    fun `triage HTTP failure fails open and records lastError`() = runTest {
        val rows = listOf(row("a"), row("b"))
        val smsDao = FakeSmsDao(rows)
        val llmDao = FakeLlmDao()
        val provider = FakeLlmProvider { prompt ->
            if (prompt.contains("financial SMS classifier")) {
                throw IllegalStateException("triage endpoint down")
            } else {
                """{"amountMinor":100,"currencyCode":"INR","direction":"DEBIT"}"""
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = LlmProcessingService(smsDao, llmDao, provider, scope)

        service.startScan()
        advanceUntilIdle()

        // Fail-open: both rows still reach (and pass) interpretation despite the
        // triage outage, and the triage error is surfaced (nothing later overwrites
        // lastError here since interpretation succeeds for both rows).
        assertEquals(2L, service.progress.value.succeeded)
        assertNotNull(service.progress.value.lastError)
        assertTrue(service.progress.value.lastError!!.contains("Triage failed"))
    }

    @Test
    fun `malformed triage response fails open and now records lastError`() = runTest {
        val rows = listOf(row("a"))
        val smsDao = FakeSmsDao(rows)
        val llmDao = FakeLlmDao()
        var triageCalls = 0
        val provider = FakeLlmProvider { prompt ->
            if (prompt.contains("financial SMS classifier")) {
                triageCalls++
                "<<not an array>>"
            } else {
                """{"amountMinor":100,"currencyCode":"INR","direction":"DEBIT"}"""
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = LlmProcessingService(smsDao, llmDao, provider, scope)

        service.startScan()
        advanceUntilIdle()

        assertEquals(1L, service.progress.value.succeeded)
        assertEquals(2, triageCalls)
        assertNotNull(service.progress.value.lastError)
        assertTrue(service.progress.value.lastError!!.contains("Triage decode failed"))
    }

    @Test
    fun `malformed triage response retries original input with strict directive`() = runTest {
        val rows = listOf(row("a"))
        val smsDao = FakeSmsDao(rows)
        val llmDao = FakeLlmDao()
        var triageCalls = 0
        val provider = FakeLlmProvider { prompt ->
            if (prompt.contains("financial SMS classifier")) {
                triageCalls++
                if (triageCalls == 1) {
                    "not json"
                } else {
                    assertTrue(prompt.contains("RETRY THE SAME TRIAGE TASK FROM THE ORIGINAL INPUT"))
                    assertTrue(prompt.contains("some SMS body a"))
                    """[{"i":0,"c":0.1}]"""
                }
            } else {
                """{"amountMinor":100,"currencyCode":"INR","direction":"DEBIT"}"""
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = LlmProcessingService(smsDao, llmDao, provider, scope)

        service.startScan()
        advanceUntilIdle()

        assertEquals(2, triageCalls)
        assertEquals(0L, service.progress.value.succeeded)
        assertEquals(1L, service.progress.value.processed)
    }

    @Test
    fun `single triage object is accepted`() = runTest {
        val rows = listOf(row("a"))
        val smsDao = FakeSmsDao(rows)
        val llmDao = FakeLlmDao()
        var triageCalls = 0
        val provider = FakeLlmProvider { prompt ->
            if (prompt.contains("financial SMS classifier")) {
                triageCalls++
                """{"i":0,"c":0.1}"""
            } else {
                """{"amountMinor":100,"currencyCode":"INR","direction":"DEBIT"}"""
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = LlmProcessingService(smsDao, llmDao, provider, scope)

        service.startScan()
        advanceUntilIdle()

        assertEquals(1, triageCalls)
        assertEquals(0L, service.progress.value.succeeded)
        assertEquals(1L, service.progress.value.processed)
    }

    @Test
    fun `stop then start again actually runs a second scan`() = runTest {
        val rows = listOf(row("a"))
        val smsDao = FakeSmsDao(rows)
        val llmDao = FakeLlmDao()
        val provider = FakeLlmProvider { prompt ->
            if (prompt.contains("financial SMS classifier")) triageJson(listOf(0 to 1.0)) else "not json"
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = LlmProcessingService(smsDao, llmDao, provider, scope)

        service.startScan()
        service.stopScan() // cancel almost immediately
        advanceUntilIdle()
        assertEquals("IDLE", service.progress.value.status)

        // Regression: a prior bug cancelled the whole shared scope on stopScan(),
        // permanently poisoning it so this second startScan() would be a silent no-op.
        service.startScan()
        advanceUntilIdle()
        assertEquals("COMPLETE", service.progress.value.status)
        assertEquals(1L, service.progress.value.processed)
    }

    @Test
    fun `rows with an existing interpretation are excluded from triage and interpretation`() = runTest {
        val rows = listOf(row("a"), row("b"))
        val smsDao = FakeSmsDao(rows)
        val llmDao = FakeLlmDao().apply {
            interpretations.add(
                LlmInterpretationEntity(
                    id = "existing", sourceMessageId = "a", responseHash = "h",
                    promptVersion = "1", schemaVersion = "1", providerId = "fake", modelId = "fake-model-1",
                    amountMinor = 100, currencyCode = "INR", direction = "DEBIT", accountToken = null,
                    rail = "UPI", counterpartyRaw = null, counterpartyNormalized = null,
                    categorySuggestion = null, transferTargetToken = null, recurring = false,
                    emiDetail = null, occurredAtEpochMs = null,
                    confidenceAmount = 1.0, confidenceDirection = 1.0, confidenceAccount = null,
                    confidenceRail = 1.0, confidenceCounterparty = null, confidenceCategory = null,
                    confidenceTransferTarget = null, confidenceRecurring = null, confidenceEmi = null,
                    evidenceExplanationsJson = "{}", overallConfidence = 1.0,
                    latencyMs = 0, tokensPrompt = 0, tokensCompletion = 0, fromCache = false,
                    createdAtEpochMs = 0,
                ),
            )
        }
        var triagedRowCount = 0
        val provider = FakeLlmProvider { prompt ->
            if (prompt.contains("financial SMS classifier")) {
                triagedRowCount += Regex("--- Message \\d+ ---").findAll(prompt).count()
                triageJson(listOf(0 to 1.0))
            } else {
                "not json"
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = LlmProcessingService(smsDao, llmDao, provider, scope)

        service.startScan()
        advanceUntilIdle()

        // Only row "b" should ever be sent to triage; "a" already has an interpretation.
        assertEquals(1, triagedRowCount)
        assertEquals(2L, service.progress.value.total)
        assertEquals(2L, service.progress.value.processed)
    }

    @Test
    fun `triage decodes compact token-optimized keys`() = runTest {
        val rows = listOf(row("a"), row("b"))
        val smsDao = FakeSmsDao(rows)
        val llmDao = FakeLlmDao()
        val provider = FakeLlmProvider { prompt ->
            if (prompt.contains("financial SMS classifier")) {
                // Return compact token-saving keys {"i": 0, "c": 0.9}
                """[{"i": 0, "c": 0.95}, {"i": 1, "c": 0.2}]"""
            } else {
                """{"amountMinor":100,"currencyCode":"INR","direction":"DEBIT"}"""
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = LlmProcessingService(smsDao, llmDao, provider, scope)

        service.startScan()
        advanceUntilIdle()

        assertEquals("COMPLETE", service.progress.value.status)
        assertEquals(1L, service.progress.value.succeeded) // only row "a" passed triage (0.95 >= 0.7)
        assertEquals(2L, service.progress.value.processed)
    }

    @Test
    fun `explicitly triggered messages use direct triage while the rest stay batched`() = runTest {
        val rows = (1..25).map { row("batch-$it") } + listOf(
            row("live-1", sourceKind = "SMS_RECEIVED"),
            row("live-2", sourceKind = "SMS_RECEIVED"),
        )
        val smsDao = FakeSmsDao(rows)
        val llmDao = FakeLlmDao()
        var triageCalls = 0
        val provider = FakeLlmProvider { prompt ->
            if (prompt.contains("financial SMS classifier")) {
                triageCalls++
                val count = Regex("--- Message \\d+ ---").findAll(prompt).count()
                triageJson((0 until count).map { it to 0.1 })
            } else {
                "not json"
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = LlmProcessingService(smsDao, llmDao, provider, scope)

        // The worker forwards the ids captured by SmsReceiver; only those go direct.
        launch { service.startScanAndWait(directMessageIds = setOf("live-1", "live-2")) }
        advanceUntilIdle()

        assertEquals(4, triageCalls) // 2 direct singles + 20 + 5
        assertEquals(25L, service.progress.value.batchTriaged)
        assertEquals(2L, service.progress.value.directTriaged)
        assertEquals(2, service.progress.value.triageBatchesCompleted)
        assertEquals(27L, service.progress.value.triageProcessed)
        assertEquals(27L, service.progress.value.processed)
    }

    @Test
    fun `without trigger ids every row is batched regardless of sourceKind`() = runTest {
        val rows = (1..25).map { row("batch-$it") } + listOf(
            row("live-1", sourceKind = "SMS_RECEIVED"),
            row("live-2", sourceKind = "SMS_RECEIVED"),
        )
        val smsDao = FakeSmsDao(rows)
        val llmDao = FakeLlmDao()
        var triageCalls = 0
        val provider = FakeLlmProvider { prompt ->
            if (prompt.contains("financial SMS classifier")) {
                triageCalls++
                val count = Regex("--- Message \\d+ ---").findAll(prompt).count()
                triageJson((0 until count).map { it to 0.1 })
            } else {
                "not json"
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = LlmProcessingService(smsDao, llmDao, provider, scope)

        service.startScan()
        advanceUntilIdle()

        assertEquals(2, triageCalls) // 20 + 7, no per-row calls
        assertEquals(27L, service.progress.value.batchTriaged)
        assertEquals(0L, service.progress.value.directTriaged)
        assertEquals(2, service.progress.value.triageBatchesCompleted)
    }

    @Test
    fun `triage-rejected rows are not re-sent to the model on a later scan`() = runTest {
        val rows = listOf(row("a"), row("b"))
        val smsDao = FakeSmsDao(rows)
        val llmDao = FakeLlmDao()
        var triagedRowCount = 0
        val provider = FakeLlmProvider { prompt ->
            if (prompt.contains("financial SMS classifier")) {
                val count = Regex("--- Message \\d+ ---").findAll(prompt).count()
                triagedRowCount += count
                triageJson((0 until count).map { it to 0.1 }) // all rejected
            } else {
                "not json"
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = LlmProcessingService(smsDao, llmDao, provider, scope)

        service.startScan()
        advanceUntilIdle()
        assertEquals(2, triagedRowCount)

        // Rejections are durable in llm_jobs, so a second scan finds nothing pending.
        service.startScan()
        advanceUntilIdle()

        assertEquals(2, triagedRowCount)
        assertEquals(2L, service.progress.value.total)
        assertEquals(0L, service.progress.value.triageTotal)
        assertEquals(2L, service.progress.value.processed)
        assertEquals("COMPLETE", service.progress.value.status)
    }

    @Test
    fun `a scan requested mid-flight forces a second pass over rows captured later`() = runTest {
        val smsDao = FakeSmsDao(listOf(row("a")))
        val llmDao = FakeLlmDao()
        val triaged = mutableListOf<String>()
        val testScope = this
        var waiter: kotlinx.coroutines.Job? = null
        lateinit var service: LlmProcessingService
        val provider = FakeLlmProvider { prompt ->
            if (prompt.contains("financial SMS classifier")) {
                listOf("a", "b").filter { prompt.contains("body $it") }.forEach(triaged::add)
                if (waiter == null) {
                    // A live SMS lands after the running scan already took its snapshot.
                    smsDao.rows = smsDao.rows + row("b", sourceKind = "SMS_RECEIVED")
                    waiter = testScope.launch { service.startScanAndWait(setOf("b")) }
                }
                triageJson(listOf(0 to 0.1))
            } else {
                "not json"
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        service = LlmProcessingService(smsDao, llmDao, provider, scope)

        service.startScan()
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), triaged)
        assertTrue(waiter!!.isCompleted)
        assertEquals(1L, service.progress.value.directTriaged)
        assertEquals("COMPLETE", service.progress.value.status)
    }

    @Test
    fun `malformed extraction retries once with strict object prompt`() = runTest {
        val rows = listOf(row("a", body = "INR 1 debited"))
        val smsDao = FakeSmsDao(rows)
        val llmDao = FakeLlmDao()
        var extractionCalls = 0
        val provider = FakeLlmProvider { prompt ->
            if (prompt.contains("financial SMS classifier")) {
                triageJson(listOf(0 to 1.0))
            } else {
                extractionCalls++
                if (extractionCalls == 1) {
                    "not json"
                } else {
                    assertTrue(prompt.contains("RETRY THE SAME EXTRACTION TASK FROM THE ORIGINAL INPUT"))
                    """{"amountMinor":100,"currencyCode":"INR","direction":"DEBIT"}"""
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = LlmProcessingService(smsDao, llmDao, provider, scope)

        service.startScan()
        advanceUntilIdle()

        assertEquals(2, extractionCalls)
        assertEquals(1L, service.progress.value.succeeded)
    }
}
