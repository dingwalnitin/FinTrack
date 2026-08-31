package com.example.fintrack.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.fintrack.data.db.FinTrackDatabaseV2
import com.example.fintrack.data.db.LlmInterpretationEntity
import com.example.fintrack.data.db.LlmJobEntity
import com.example.fintrack.data.db.LlmJobStates
import com.example.fintrack.data.db.RawSmsEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID

/**
 * On-device integration test for the LLM enrichment schema (v5+).
 *
 * Verifies the [LlmDao] job-processing pipeline against a real Room database:
 * job insertion, fair claim with compare-and-set, expired-lease reclaim,
 * interpretation idempotency, and the response cache.
 */
@RunWith(AndroidJUnit4::class)
class LlmDaoIntegrationTest {

    private lateinit var db: FinTrackDatabaseV2
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FinTrackDatabaseV2::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun job(
        id: String = UUID.randomUUID().toString(),
        jobIdentity: String = "identity-$id",
        sourceMessageId: String = "sms-$id",
        status: String = LlmJobStates.PENDING,
        priority: Int = 0,
        createdAt: Long = now,
        nextRetryAtEpochMs: Long = now,
    ) = LlmJobEntity(
        id = id,
        jobIdentity = jobIdentity,
        sourceMessageId = sourceMessageId,
        senderHash = "hash-abc",
        priority = priority,
        status = status,
        attempts = 0,
        maxAttempts = 4,
        nextRetryAtEpochMs = nextRetryAtEpochMs,
        claimedAtEpochMs = null,
        claimedByWorker = null,
        promptVersion = "enrich-prompt-v2",
        schemaVersion = "enrich-schema-v1",
        providerId = "chat-completions",
        lastErrorClass = null,
        createdAtEpochMs = createdAt,
        updatedAtEpochMs = createdAt,
    )

    private fun interpret(
        id: String = UUID.randomUUID().toString(),
        sourceMessageId: String = "sms-$id",
        responseHash: String = "response-hash-$id",
        amountMinor: Long? = 25000L,
    ) = LlmInterpretationEntity(
        id = id,
        sourceMessageId = sourceMessageId,
        responseHash = responseHash,
        promptVersion = "enrich-prompt-v2",
        schemaVersion = "enrich-schema-v1",
        providerId = "chat-completions",
        modelId = "gpt-4o-mini",
        amountMinor = amountMinor,
        currencyCode = "INR",
        direction = "DEBIT",
        accountToken = null,
        rail = "UPI",
        counterpartyRaw = null,
        counterpartyNormalized = null,
        categorySuggestion = null,
        transferTargetToken = null,
        recurring = null,
        emiDetail = null,
        occurredAtEpochMs = null,
        confidenceAmount = 0.99,
        confidenceDirection = 0.99,
        confidenceAccount = null,
        confidenceRail = 0.8,
        confidenceCounterparty = null,
        confidenceCategory = null,
        confidenceTransferTarget = null,
        confidenceRecurring = null,
        confidenceEmi = null,
        evidenceExplanationsJson = "{}",
        overallConfidence = 0.95,
        latencyMs = 500,
        tokensPrompt = 100,
        tokensCompletion = 50,
        fromCache = false,
        createdAtEpochMs = now,
    )

    @Test
    fun `insert and claim a job`() = runTest {
        val dao = db.llmDao()
        val j = job()
        dao.insertJob(j)

        val claimed = dao.claimNextDueJob("worker-1", now, 60_000)
        assertNotNull(claimed)
        assertEquals(j.id, claimed!!.id)
        assertEquals(LlmJobStates.CLAIMED, claimed.status)
        assertEquals("worker-1", claimed.claimedByWorker)
    }

    @Test
    fun `claiming a job a second time returns null (CAS guards)`() = runTest {
        val dao = db.llmDao()
        val j = job()
        dao.insertJob(j)

        val first = dao.claimNextDueJob("worker-1", now, 60_000)
        assertNotNull(first)

        val second = dao.claimNextDueJob("worker-2", now, 60_000)
        assertNull("second worker must not claim the same job", second)
    }

    @Test
    fun `expired lease can be reclaimed by another worker`() = runTest {
        val dao = db.llmDao()
        val j = job()
        dao.insertJob(j)

        val first = dao.claimNextDueJob("worker-1", now, 60_000)
        assertNotNull(first)

        // Lease expired (now + 120s > 60s lease)
        val reclaimed = dao.claimNextDueJob("worker-2", now + 120_000, 60_000)
        assertNotNull(reclaimed)
        assertEquals(j.id, reclaimed!!.id)
        assertEquals("worker-2", reclaimed.claimedByWorker)
    }

    @Test
    fun `multiple jobs are claimed in priority order`() = runTest {
        val dao = db.llmDao()
        val high = job(id = "high", jobIdentity = "hi", priority = 0, createdAt = now)
        val low = job(id = "low", jobIdentity = "lo", priority = 10, createdAt = now)
        dao.insertJob(high)
        dao.insertJob(low)

        val first = dao.claimNextDueJob("worker-1", now, 60_000)
        assertEquals("high", first!!.id)

        val second = dao.claimNextDueJob("worker-1", now, 60_000)
        assertEquals("low", second!!.id)
    }

    @Test
    fun `job identity unique index prevents duplicates`() = runTest {
        val dao = db.llmDao()
        val j = job(id = "a", jobIdentity = "same-identity")
        val dup = job(id = "b", jobIdentity = "same-identity")
        val first = dao.insertJob(j)
        val second = dao.insertJob(dup)
        assertTrue(first > 0L)
        assertEquals(-1L, second)
    }

    @Test
    fun `insert and retrieve interpretation`() = runTest {
        val dao = db.llmDao()
        val i = interpret(id = "i1", sourceMessageId = "sms-1")
        dao.insertInterpretation(i)

        val results = dao.interpretationsForMessage("sms-1")
        assertEquals(1, results.size)
        assertEquals(25000L, results[0].amountMinor)
    }

    @Test
    fun `interpretation response hash unique index prevents duplicates`() = runTest {
        val dao = db.llmDao()
        val i1 = interpret(id = "a", sourceMessageId = "sms-1", responseHash = "same-hash")
        val i2 = interpret(id = "b", sourceMessageId = "sms-2", responseHash = "same-hash")
        val first = dao.insertInterpretation(i1)
        val second = dao.insertInterpretation(i2)
        assertTrue(first > 0L)
        assertEquals(-1L, second)
    }

    @Test
    fun `update job outcome to succeeded`() = runTest {
        val dao = db.llmDao()
        val j = job()
        dao.insertJob(j)
        val claimed = dao.claimNextDueJob("worker-1", now, 60_000)!!

        val updated = dao.casUpdateStatus(
            id = claimed.id,
            expectedStatus = LlmJobStates.CLAIMED,
            newStatus = LlmJobStates.SUCCEEDED,
            attempts = 1,
            nextRetryAt = now,
            claimedAt = null,
            claimedBy = null,
            lastErrorClass = null,
            updatedAt = now + 1000,
        )
        assertEquals(1, updated)

        val stored = dao.findJob(claimed.id)
        assertEquals(LlmJobStates.SUCCEEDED, stored!!.status)
    }

    @Test
    fun `report failure does not advance beyond retryable failed`() = runTest {
        val dao = db.llmDao()
        val j = job()
        dao.insertJob(j)
        val claimed = dao.claimNextDueJob("worker-1", now, 60_000)!!

        dao.reportFailure(
            id = claimed.id,
            status = LlmJobStates.RETRYABLE_FAILED,
            errorClass = "RATE_LIMITED",
            nextRetryAt = now + 30_000,
            updatedAt = now + 1000,
        )
        val stored = dao.findJob(claimed.id)
        assertEquals(LlmJobStates.RETRYABLE_FAILED, stored!!.status)
        assertEquals("RATE_LIMITED", stored.lastErrorClass)
    }

    @Test
    fun `claimNextDueJob respects nextRetryAtEpochMs`() = runTest {
        val dao = db.llmDao()
        // Not due yet — nextRetryAt is in the future.
        val j = job(id = "future", jobIdentity = "future", nextRetryAtEpochMs = now + 60_000)
        dao.insertJob(j)

        val claimed = dao.claimNextDueJob("worker-1", now, 60_000)
        assertNull("job with future retry must not be claimable", claimed)
    }
}