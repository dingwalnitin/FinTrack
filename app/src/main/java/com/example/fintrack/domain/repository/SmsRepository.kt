package com.example.fintrack.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * SMS evidence acquisition contract. The UI and the WorkManager worker go
 * through this interface only; no layer above domain touches the data layer
 * directly.
 *
 * All writes are idempotent — duplicate providerId or contentHash is silently
 * ignored. Raw evidence is immutable; the only mutable row is the cursor /
 * progress aggregate.
 */
interface SmsRepository {

    /**
     * Persist a single raw SMS (e.g., from the receiver). Returns the new
     * `raw_sms` id when the row was newly inserted, or null when it was a
     * duplicate. The id lets the caller ask for that message to be processed
     * ahead of any historical backlog.
     */
    suspend fun captureRaw(
        providerId: Long,
        sender: String?,
        body: String,
        timestampEpochMs: Long,
        sourceKind: String,
    ): String?

    /**
     * Persist a batch durably and atomically advance the cursor. Processed rows
     * become one batch boundary (transactional). Duplicate rows in the batch
     * are counted but never re-inserted. Returns a [BatchResult] summarising
     * the batch.
     */
    suspend fun commitBatch(
        rows: List<RawSmsRow>,
        sourceKind: String,
    ): BatchResult

    suspend fun currentCursor(): BackfillCursor?
    suspend fun markStatus(status: String, lastError: String? = null)

    /** Aggregate counts only — UI never recomposes per-message. */
    fun observeProgress(): Flow<IngestionProgress>

    fun observeRawCount(): Flow<Long>
}

/** Single immutable raw record passed across the domain/data boundary. */
data class RawSmsRow(
    val providerId: Long,
    val sender: String?,
    val body: String,
    val timestampEpochMs: Long,
)

data class BatchResult(
    val persisted: Int,
    val duplicate: Int,
    val lastProviderId: Long,
)

data class BackfillCursor(
    val lastProviderId: Long?,
    val status: String,
    val totalSeen: Long,
    val totalPersisted: Long,
    val totalDuplicate: Long,
)

data class IngestionProgress(
    val totalPersisted: Long,
    val status: String,
    val lastUpdatedAtEpochMs: Long,
    val lastError: String?,
)
