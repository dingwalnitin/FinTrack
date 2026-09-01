package com.example.fintrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the LLM enrichment pipeline (Stage 4).
 *
 * All writes are idempotent (IGNORE on unique jobIdentity / responseHash /
 * cacheKey). Job claiming is a transactional compare-and-set so four workers
 * can claim concurrently without double-processing. No raw SMS bodies or
 * prompts are ever stored here — only ids, hashes and validated output.
 */
@Dao
abstract class LlmDao : LlmSchedulerDao {

    // ---- jobs ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertJob(job: LlmJobEntity): Long

    @Query("SELECT * FROM llm_jobs WHERE jobIdentity = :jobIdentity LIMIT 1")
    abstract suspend fun findJobByIdentity(jobIdentity: String): LlmJobEntity?

    @Query("SELECT * FROM llm_jobs WHERE id = :id LIMIT 1")
    abstract suspend fun findJob(id: String): LlmJobEntity?

    /**
     * Transactional fair claim: picks the oldest due PENDING job (priority,
     * then FIFO) or an expired-lease CLAIMED/RUNNING job, and flips it to
     * CLAIMED under this worker. Returns null when nothing is claimable.
     */
    @Transaction
    open suspend fun claimNextDueJob(workerId: String, nowEpochMs: Long, leaseMs: Long): LlmJobEntity? {
        val candidate = queryClaimable(nowEpochMs, leaseMs, limit = 1).firstOrNull() ?: return null
        val claimed = candidate.copy(
            status = LlmJobStates.CLAIMED,
            claimedAtEpochMs = nowEpochMs,
            claimedByWorker = workerId,
            updatedAtEpochMs = nowEpochMs,
        )
        // Compare-and-set on status+id; 0 rows means another worker won the race.
        val updated = casUpdate(claimed)
        return if (updated) claimed else null
    }

    @Query(
        """SELECT * FROM llm_jobs WHERE
           (status = 'PENDING' AND nextRetryAtEpochMs <= :nowEpochMs)
           OR (status IN ('CLAIMED','RUNNING') AND claimedAtEpochMs IS NOT NULL
               AND claimedAtEpochMs + :leaseMs <= :nowEpochMs)
           ORDER BY priority ASC, createdAtEpochMs ASC LIMIT :limit"""
    )
    abstract suspend fun queryClaimable(nowEpochMs: Long, leaseMs: Long = 0, limit: Int): List<LlmJobEntity>

    /** Full-row CAS update guarded by prior status — prevents lost updates between workers. */
    @Query(
        """UPDATE llm_jobs SET status = :newStatus, attempts = :attempts,
           nextRetryAtEpochMs = :nextRetryAt, claimedAtEpochMs = :claimedAt,
           claimedByWorker = :claimedBy, lastErrorClass = :lastErrorClass,
           updatedAtEpochMs = :updatedAt
           WHERE id = :id AND status = :expectedStatus"""
    )
    abstract suspend fun casUpdateStatus(
        id: String, expectedStatus: String, newStatus: String, attempts: Int,
        nextRetryAt: Long, claimedAt: Long?, claimedBy: String?,
        lastErrorClass: String?, updatedAt: Long,
    ): Int

    internal suspend fun casUpdate(job: LlmJobEntity): Boolean =
        casUpdateStatus(
            id = job.id, expectedStatus = LlmJobStates.PENDING, newStatus = job.status,
            attempts = job.attempts, nextRetryAt = job.nextRetryAtEpochMs,
            claimedAt = job.claimedAtEpochMs, claimedBy = job.claimedByWorker,
            lastErrorClass = job.lastErrorClass, updatedAt = job.updatedAtEpochMs,
        ) > 0 || run {
            // Expired-lease reclaim path: expected status was CLAIMED or RUNNING.
            casUpdateFromExpired(job)
        }

    @Query(
        """UPDATE llm_jobs SET status = :newStatus, attempts = :attempts,
           nextRetryAtEpochMs = :nextRetryAt, claimedAtEpochMs = :claimedAt,
           claimedByWorker = :claimedBy, lastErrorClass = :lastErrorClass,
           updatedAtEpochMs = :updatedAt
           WHERE id = :id AND status IN ('CLAIMED','RUNNING')
           AND claimedAtEpochMs + :leaseMs <= :updatedAt"""
    )
    abstract suspend fun casReclaimExpired(
        id: String, newStatus: String, attempts: Int, nextRetryAt: Long,
        claimedAt: Long?, claimedBy: String?, lastErrorClass: String?,
        updatedAt: Long, leaseMs: Long,
    ): Int

    internal suspend fun casUpdateFromExpired(job: LlmJobEntity): Boolean =
        casReclaimExpired(
            id = job.id, newStatus = job.status, attempts = job.attempts,
            nextRetryAt = job.nextRetryAtEpochMs, claimedAt = job.claimedAtEpochMs,
            claimedBy = job.claimedByWorker, lastErrorClass = job.lastErrorClass,
            updatedAt = job.updatedAtEpochMs, leaseMs = 0,
        ) > 0

    @Query(
        """UPDATE llm_jobs SET status = :status, attempts = attempts + 1,
           lastErrorClass = :errorClass, nextRetryAtEpochMs = :nextRetryAt,
           updatedAtEpochMs = :updatedAt WHERE id = :id"""
    )
    abstract suspend fun reportFailure(id: String, status: String, errorClass: String?, nextRetryAt: Long, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM llm_jobs WHERE status = :status")
    abstract suspend fun countInStatus(status: String): Long

    @Query("SELECT COUNT(*) FROM llm_jobs WHERE status = :status")
    abstract fun observeCountInStatus(status: String): Flow<Long>

    @Query("SELECT * FROM llm_jobs WHERE status IN ('CLAIMED','RUNNING') AND claimedAtEpochMs + :leaseMs <= :now")
    abstract suspend fun stalledJobs(now: Long, leaseMs: Long): List<LlmJobEntity>

    /** Process-death recovery: release leases held by a dead process back to PENDING. */
    @Query(
        """UPDATE llm_jobs SET status = 'PENDING', claimedAtEpochMs = NULL,
           claimedByWorker = NULL, updatedAtEpochMs = :now
           WHERE status IN ('CLAIMED','RUNNING') AND claimedAtEpochMs + :leaseMs <= :now"""
    )
    abstract suspend fun releaseExpiredLeases(now: Long, leaseMs: Long): Int

    // ---- interpretations ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertInterpretation(i: LlmInterpretationEntity): Long

    @Query("SELECT * FROM llm_interpretations WHERE sourceMessageId = :messageId ORDER BY createdAtEpochMs DESC")
    abstract suspend fun interpretationsForMessage(messageId: String): List<LlmInterpretationEntity>

    /** Bulk form of [interpretationsForMessage] — avoids an N+1 scan over raw_sms. */
    @Query("SELECT DISTINCT sourceMessageId FROM llm_interpretations")
    abstract suspend fun interpretedMessageIds(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM llm_interpretations WHERE responseHash = :hash)")
    abstract suspend fun interpretationExists(hash: String): Boolean

    /**
     * Messages whose scan outcome is terminal (either interpreted, or
     * deliberately rejected/abandoned). Retryable failures are excluded so a
     * transient provider outage is picked up again on the next pass.
     */
    @Query("SELECT DISTINCT sourceMessageId FROM llm_jobs WHERE status IN ('SUCCEEDED','TERMINAL_FAILED')")
    abstract suspend fun settledJobMessageIds(): List<String>

    @Query(
        """UPDATE llm_jobs SET status = :status, lastErrorClass = :errorClass,
           updatedAtEpochMs = :now WHERE jobIdentity = :jobIdentity"""
    )
    abstract suspend fun updateJobOutcome(jobIdentity: String, status: String, errorClass: String?, now: Long): Int

    // ---- cache ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertCacheEntry(e: LlmResponseCacheEntity): Long

    @Query("SELECT * FROM llm_response_cache WHERE cacheKey = :key LIMIT 1")
    abstract suspend fun cacheEntry(key: String): LlmResponseCacheEntity?

    // ---- usage counters ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertUsageCounter(c: LlmUsageCounterEntity)

    @Query("SELECT * FROM llm_usage_counters WHERE bucketDayUtc = :day LIMIT 1")
    abstract suspend fun usageForDay(day: Long): LlmUsageCounterEntity?

    @Transaction
    open suspend fun bumpUsage(day: Long, now: Long, requests: Long = 0, cacheHits: Long = 0,
                          tokensPrompt: Long = 0, tokensCompletion: Long = 0,
                          validationFailures: Long = 0, retries: Long = 0) {
        val existing = usageForDay(day)
        val updated = LlmUsageCounterEntity(
            id = "usage-$day",
            bucketDayUtc = day,
            requests = (existing?.requests ?: 0) + requests,
            cacheHits = (existing?.cacheHits ?: 0) + cacheHits,
            tokensPrompt = (existing?.tokensPrompt ?: 0) + tokensPrompt,
            tokensCompletion = (existing?.tokensCompletion ?: 0) + tokensCompletion,
            validationFailures = (existing?.validationFailures ?: 0) + validationFailures,
            retries = (existing?.retries ?: 0) + retries,
            updatedAtEpochMs = now,
        )
        insertUsageCounter(updated)
    }

    // ---- metrics ----

    @Query("INSERT OR REPLACE INTO llm_metrics (id, metricName, value, updatedAtEpochMs) VALUES (:name, :name, :value, :now)")
    abstract suspend fun setMetric(name: String, value: Long, now: Long)

    @Query("SELECT * FROM llm_metrics ORDER BY metricName")
    abstract suspend fun allMetrics(): List<LlmMetricEntity>

    // ---- Stage 12 P25: diagnostics reads ----

    @Query("SELECT COUNT(*) FROM llm_jobs")
    abstract suspend fun totalJobs(): Long

    @Query("SELECT COUNT(*) FROM llm_jobs WHERE status IN ('CLAIMED','RUNNING') AND claimedAtEpochMs + :leaseMs <= :now")
    abstract suspend fun expiredLeases(now: Long, leaseMs: Long = 30_000): Long

    @Query("SELECT COUNT(*) FROM llm_response_cache")
    abstract suspend fun cacheEntryCount(): Long

    /** Recent terminal/retryable failure samples for diagnostics (identities only). */
    @Query(
        """SELECT * FROM llm_jobs WHERE status IN ('TERMINAL_FAILED','RETRYABLE_FAILED')
           ORDER BY updatedAtEpochMs DESC LIMIT :limit"""
    )
    abstract suspend fun recentFailureSamples(limit: Int): List<LlmJobEntity>

    // ---- Stage 13 (F): SMS review reads + re-run ----

    /** Latest job for a source message (SMS review status). */
    @Query("SELECT * FROM llm_jobs WHERE sourceMessageId = :sourceMessageId ORDER BY createdAtEpochMs DESC LIMIT 1")
    abstract suspend fun jobForMessage(sourceMessageId: String): LlmJobEntity?

    /** Reset a message's terminal/retryable job back to PENDING so it can be re-run (single-job re-run). */
    @Query(
        """UPDATE llm_jobs SET status = 'PENDING', attempts = 0, nextRetryAtEpochMs = :nowEpochMs,
           claimedAtEpochMs = NULL, claimedByWorker = NULL, lastErrorClass = NULL,
           updatedAtEpochMs = :nowEpochMs
           WHERE sourceMessageId = :sourceMessageId AND status IN ('TERMINAL_FAILED','RETRYABLE_FAILED')"""
    )
    abstract suspend fun resetJobToPending(sourceMessageId: String, nowEpochMs: Long): Int

    /** Aggregate counts of jobs per status (review-list summary). */
    @Query(
        """SELECT status, COUNT(*) AS count FROM llm_jobs GROUP BY status"""
    )
    abstract suspend fun jobStatusCounts(): List<JobStatusCountRow>

    @Query("SELECT * FROM llm_jobs WHERE status = :status ORDER BY createdAtEpochMs DESC LIMIT :limit")
    abstract suspend fun jobsInStatus(status: String, limit: Int): List<LlmJobEntity>
}

/** Aggregate count of llm_jobs per status (SMS review summary). */
data class JobStatusCountRow(val status: String, val count: Long)

