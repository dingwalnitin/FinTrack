package com.example.fintrack.data.db

import androidx.room.Query

/**
 * Additional query surface for the enrichment scheduler (Stage 4 P08).
 * Kept separate so [LlmDao] stays focused on storage primitives.
 */
interface LlmSchedulerDao {
    /** Due retryable-failed jobs awaiting promotion back to PENDING. */
    @Query(
        """SELECT * FROM llm_jobs WHERE status = 'RETRYABLE_FAILED'
           AND nextRetryAtEpochMs <= :nowEpochMs"""
    )
    suspend fun dueRetryableJobs(nowEpochMs: Long): List<LlmJobEntity>
}
