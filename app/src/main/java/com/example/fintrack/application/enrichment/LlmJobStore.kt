package com.example.fintrack.application.enrichment

import com.example.fintrack.data.db.LlmDao
import com.example.fintrack.data.db.LlmInterpretationEntity
import com.example.fintrack.data.db.LlmJobEntity
import com.example.fintrack.data.db.LlmJobStates
import com.example.fintrack.data.db.LlmResponseCacheEntity
import com.example.fintrack.llm.LlmErrorClass
import com.example.fintrack.llm.PROMPT_VERSION
import com.example.fintrack.llm.SCHEMA_VERSION
import com.example.fintrack.llm.RetryPolicy
import java.security.MessageDigest

/**
 * P08: durable job store over [LlmDao]. Owns job identity, claiming, retry
 * bookkeeping, cache access and usage counters. This is the ONLY component
 * that writes LLM results to Room, and it writes only validated output.
 */
class LlmJobStore(private val dao: LlmDao) {

    fun jobIdentity(sourceMessageId: String, promptVersion: String, schemaVersion: String, providerId: String): String =
        listOf(sourceMessageId, promptVersion, schemaVersion, providerId)
            .joinToString("|").sha256()

    suspend fun enqueue(
        sourceMessageId: String,
        senderHash: String?,
        priority: Int,
        providerId: String,
        nowEpochMs: Long,
        maxAttempts: Int = 4,
        promptVersion: String = PROMPT_VERSION,
        schemaVersion: String = SCHEMA_VERSION,
    ): Boolean {
        val identity = jobIdentity(sourceMessageId, promptVersion, schemaVersion, providerId)
        if (dao.findJobByIdentity(identity) != null) return false // idempotent
        return dao.insertJob(
            LlmJobEntity(
                id = java.util.UUID.randomUUID().toString(),
                jobIdentity = identity,
                sourceMessageId = sourceMessageId,
                senderHash = senderHash,
                priority = priority,
                status = LlmJobStates.PENDING,
                attempts = 0,
                maxAttempts = maxAttempts,
                nextRetryAtEpochMs = nowEpochMs,
                claimedAtEpochMs = null,
                claimedByWorker = null,
                promptVersion = promptVersion,
                schemaVersion = schemaVersion,
                providerId = providerId,
                lastErrorClass = null,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            )
        ) != -1L
    }

    /** Fair claim with expired-lease recovery. Returns the claimed job or null. */
    suspend fun claim(workerId: String, nowEpochMs: Long, leaseMs: Long): LlmJobEntity? =
        dao.claimNextDueJob(workerId, nowEpochMs, leaseMs)

    suspend fun markSucceeded(job: LlmJobEntity, nowEpochMs: Long) {
        dao.casUpdateStatus(
            id = job.id, expectedStatus = job.status, newStatus = LlmJobStates.SUCCEEDED,
            attempts = job.attempts + 1, nextRetryAt = Long.MAX_VALUE,
            claimedAt = null, claimedBy = null, lastErrorClass = null, updatedAt = nowEpochMs,
        )
    }

    /** Classify a failure and schedule retry or terminal-fail. Returns next state. */
    suspend fun markFailed(
        job: LlmJobEntity,
        errorClass: LlmErrorClass,
        retryAfterMs: Long?,
        nowEpochMs: Long,
    ): String {
        val nextAttempt = job.attempts + 1
        val delay = RetryPolicy.nextDelayMs(errorClass, nextAttempt - 1, retryAfterMs)
        val canRetry = errorClass.isRetryable && nextAttempt < job.maxAttempts && delay != null
        val newState = if (canRetry) LlmJobStates.RETRYABLE_FAILED else LlmJobStates.TERMINAL_FAILED
        val nextRetryAt = if (canRetry) nowEpochMs + delay else Long.MAX_VALUE
        dao.reportFailure(job.id, newState, errorClass.name, nextRetryAt, nowEpochMs)
        return newState
    }

    /**
     * RETRYABLE_FAILED -> PENDING when its backoff has elapsed. Called by the
     * scheduler loop; claim() only picks PENDING/expired-lease rows.
     */
    suspend fun promoteRetryableToPending(nowEpochMs: Long): Int {
        val due = dao.dueRetryableJobs(nowEpochMs)
        var promoted = 0
        due.forEach {
                val n = dao.casUpdateStatus(
                    id = it.id, expectedStatus = LlmJobStates.RETRYABLE_FAILED,
                    newStatus = LlmJobStates.PENDING, attempts = it.attempts,
                    nextRetryAt = nowEpochMs, claimedAt = null, claimedBy = null,
                    lastErrorClass = it.lastErrorClass, updatedAt = nowEpochMs,
                )
                promoted += n
            }
        return promoted
    }

    /** Process-death / stall diagnostics. */
    suspend fun stalledJobs(nowEpochMs: Long, leaseMs: Long): List<LlmJobEntity> =
        dao.stalledJobs(nowEpochMs, leaseMs)

    suspend fun releaseExpiredLeases(nowEpochMs: Long, leaseMs: Long): Int =
        dao.releaseExpiredLeases(nowEpochMs, leaseMs)

    suspend fun queueDepth(): Long = dao.countInStatus(LlmJobStates.PENDING)

    // ---- interpretations ----

    suspend fun storeInterpretation(entity: LlmInterpretationEntity): Boolean =
        dao.insertInterpretation(entity) != -1L

    suspend fun interpretationsForMessage(messageId: String): List<LlmInterpretationEntity> =
        dao.interpretationsForMessage(messageId)

    // ---- scan outcomes ----

    /** Stable job identity for the on-demand scan pipeline. */
    fun scanIdentity(sourceMessageId: String): String = "scan:$sourceMessageId"

    /**
     * Every message the scan must not look at again: already interpreted, or
     * carrying a terminal scan outcome. One query per set instead of one per
     * row. RETRYABLE_FAILED is deliberately absent so transient provider
     * failures are retried on the next pass.
     */
    suspend fun settledMessageIds(): Set<String> =
        (dao.interpretedMessageIds() + dao.settledJobMessageIds()).toSet()

    /**
     * Record the terminal (or retryable) outcome of scanning one message so a
     * later pass does not redo the LLM work. Insert-then-update because the
     * unique jobIdentity makes the insert a no-op once a row already exists.
     */
    suspend fun recordScanOutcome(
        sourceMessageId: String,
        senderHash: String?,
        providerId: String,
        status: String,
        errorClass: String?,
        nowEpochMs: Long,
    ) {
        val identity = scanIdentity(sourceMessageId)
        dao.insertJob(
            LlmJobEntity(
                id = java.util.UUID.randomUUID().toString(),
                jobIdentity = identity,
                sourceMessageId = sourceMessageId,
                senderHash = senderHash,
                priority = 0,
                status = status,
                attempts = 1,
                maxAttempts = 1,
                nextRetryAtEpochMs = nowEpochMs,
                claimedAtEpochMs = null,
                claimedByWorker = null,
                promptVersion = PROMPT_VERSION,
                schemaVersion = SCHEMA_VERSION,
                providerId = providerId,
                lastErrorClass = errorClass,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            )
        )
        dao.updateJobOutcome(identity, status, errorClass, nowEpochMs)
    }

    // ---- metrics ----

    suspend fun metric(name: String): Long =
        dao.allMetrics().firstOrNull { it.metricName == name }?.value ?: 0L

    suspend fun setMetric(name: String, value: Long, nowEpochMs: Long) =
        dao.setMetric(name, value, nowEpochMs)

    // ---- cache ----

    suspend fun cachedResponse(cacheKey: String): LlmResponseCacheEntity? = dao.cacheEntry(cacheKey)

    suspend fun putCacheEntry(entity: LlmResponseCacheEntity): Boolean =
        dao.insertCacheEntry(entity) != -1L

    // ---- usage ----

    suspend fun bumpUsage(dayUtc: Long, now: Long, requests: Long = 0, cacheHits: Long = 0,
                          tokensPrompt: Long = 0, tokensCompletion: Long = 0,
                          validationFailures: Long = 0, retries: Long = 0) =
        dao.bumpUsage(dayUtc, now, requests, cacheHits, tokensPrompt, tokensCompletion,
            validationFailures, retries)

    suspend fun usageForDay(dayUtc: Long) = dao.usageForDay(dayUtc)

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

