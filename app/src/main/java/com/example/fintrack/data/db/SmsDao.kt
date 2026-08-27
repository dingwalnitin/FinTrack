package com.example.fintrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DAO for SMS evidence tables.
 *
 * All writes are idempotent (IGNORE on providerId / contentHash unique indexes)
 * and transactional at the batch boundary so a process death in the middle of a
 * batch leaves either the whole batch or nothing persisted.
 */
@Dao
interface SmsDao {

    // ---- raw_sms ----

    /**
     * Bulk insert with conflict-ignore. Returns the count of rows that were
     * actually inserted (excluding duplicates), to update aggregate progress.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRawBatch(rows: List<RawSmsEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM raw_sms")
    fun observeRawCount(): Flow<Long>

    @Query("SELECT COUNT(*) FROM raw_sms")
    suspend fun rawCount(): Long

    @Query("SELECT * FROM raw_sms WHERE providerId = :providerId LIMIT 1")
    suspend fun findByProviderId(providerId: Long): RawSmsEntity?

    @Query("SELECT * FROM raw_sms WHERE contentHash = :hash LIMIT 1")
    suspend fun findByContentHash(hash: String): RawSmsEntity?

    /**
     * All raw evidence rows ordered oldest-first, for LLM batch processing.
     * Bodies are returned because they are the input to the LLM; this is the
     * explicit "scan all SMS through the LLM" path requested from Settings.
     */
    @Query("SELECT * FROM raw_sms ORDER BY receivedAtEpochMs ASC, providerId ASC")
    suspend fun allRawRows(): List<RawSmsEntity>

    @Query("SELECT * FROM raw_sms WHERE id = :id LIMIT 1")
    suspend fun rawSmsById(id: String): RawSmsEntity?

    // ---- sms_backfill_cursor ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: SmsBackfillCursorEntity)

    @Query("SELECT * FROM sms_backfill_cursor WHERE id = 1")
    suspend fun getCursor(): SmsBackfillCursorEntity?

    @Query("SELECT * FROM sms_backfill_cursor WHERE id = 1")
    fun observeCursor(): Flow<SmsBackfillCursorEntity?>

    /**
     * Transactional batch commit: persists all new raw rows, advances the
     * cursor to the last provider id we have seen, and updates aggregate
     * counts. Either every row in the batch is written or none is.
     */
    @Transaction
    suspend fun commitBatch(
        rows: List<RawSmsEntity>,
        lastProviderId: Long,
        nowEpochMs: Long,
        seenInBatch: Int,
        persistedInBatch: Int,
        duplicateInBatch: Int,
    ) {
        if (rows.isNotEmpty()) {
            insertRawBatch(rows)
        }
        val prior = getCursor()
        val seen = (prior?.totalSeen ?: 0L) + seenInBatch.toLong()
        val persisted = (prior?.totalPersisted ?: 0L) + persistedInBatch.toLong()
        val dupes = (prior?.totalDuplicate ?: 0L) + duplicateInBatch.toLong()
        upsertCursor(
            SmsBackfillCursorEntity(
                id = 1,
                lastProviderId = lastProviderId,
                startedAtEpochMs = prior?.startedAtEpochMs ?: nowEpochMs,
                lastUpdatedAtEpochMs = nowEpochMs,
                status = prior?.status ?: "RUNNING",
                totalSeen = seen,
                totalPersisted = persisted,
                totalDuplicate = dupes,
            )
        )
        upsertProgress(
            SmsIngestionProgressEntity(
                id = 1,
                totalPersisted = persisted,
                lastUpdatedAtEpochMs = nowEpochMs,
                status = prior?.status ?: "RUNNING",
                lastError = null,
            )
        )
    }

    // ---- sms_ingestion_progress (aggregate, single row) ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: SmsIngestionProgressEntity)

    @Query("SELECT * FROM sms_ingestion_progress WHERE id = 1")
    fun observeProgress(): Flow<SmsIngestionProgressEntity?>

    @Query("SELECT * FROM sms_ingestion_progress WHERE id = 1")
    suspend fun getProgress(): SmsIngestionProgressEntity?
}
