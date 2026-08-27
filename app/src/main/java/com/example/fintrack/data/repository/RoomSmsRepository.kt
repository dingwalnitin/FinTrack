package com.example.fintrack.data.repository

import com.example.fintrack.data.db.RawSmsEntity
import com.example.fintrack.data.db.SmsBackfillCursorEntity
import com.example.fintrack.data.db.SmsDao
import com.example.fintrack.data.db.SmsIngestionProgressEntity
import com.example.fintrack.domain.repository.BackfillCursor
import com.example.fintrack.domain.repository.BatchResult
import com.example.fintrack.domain.repository.IngestionProgress
import com.example.fintrack.domain.repository.RawSmsRow
import com.example.fintrack.domain.repository.SmsRepository
import com.example.fintrack.domain.sms.SmsIngestionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Room-backed [SmsRepository]. All writes are idempotent:
 *  - insert ignores duplicate providerId / contentHash
 *  - batch commit is transactional so a process death mid-batch leaves
 *    either the whole batch persisted or nothing
 *
 * The class never deletes a raw row, never mutates stored evidence, and never
 * exposes the user SMS database.
 */
class RoomSmsRepository(
    private val dao: SmsDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : SmsRepository {

    override suspend fun captureRaw(
        providerId: Long,
        sender: String?,
        body: String,
        timestampEpochMs: Long,
        sourceKind: String,
    ): Boolean {
        val id = UUID.randomUUID().toString()
        val hash = SmsIngestionPolicy.contentHash(sender, body, timestampEpochMs)
        // De-dup by contentHash to handle providerId churn across rebuilds.
        val existing = dao.findByProviderId(providerId) ?: dao.findByContentHash(hash)
        if (existing != null) return false

        val inserted = dao.insertRawBatch(
            listOf(
                RawSmsEntity(
                    id = id,
                    providerId = providerId,
                    sender = sender,
                    receivedAtEpochMs = timestampEpochMs,
                    body = body,
                    contentHash = hash,
                    sourceKind = sourceKind,
                    sourceVersion = SmsIngestionPolicy.SOURCE_VERSION,
                    capturedAtEpochMs = clock(),
                )
            )
        )
        val wasInserted = inserted.isNotEmpty() && inserted.first() != -1L
        if (wasInserted) {
            // Bump aggregate progress without advancing the backfill cursor —
            // captures are not backfill pages and may arrive in any order.
            val prior = dao.getProgress()
            dao.upsertProgress(
                SmsIngestionProgressEntity(
                    id = 1,
                    totalPersisted = (prior?.totalPersisted ?: 0L) + 1L,
                    lastUpdatedAtEpochMs = clock(),
                    status = prior?.status ?: "RUNNING",
                    lastError = prior?.lastError,
                )
            )
        }
        return wasInserted
    }

    override suspend fun commitBatch(
        rows: List<RawSmsRow>,
        sourceKind: String,
    ): BatchResult {
        require(rows.isNotEmpty()) { "rows must be non-empty" }
        val now = clock()

        val ordered = rows.sortedByDescending { it.providerId }
        val entities = ordered.map { row ->
            val hash = SmsIngestionPolicy.contentHash(row.sender, row.body, row.timestampEpochMs)
            RawSmsEntity(
                id = UUID.randomUUID().toString(),
                providerId = row.providerId,
                sender = row.sender,
                receivedAtEpochMs = row.timestampEpochMs,
                body = row.body,
                contentHash = hash,
                sourceKind = sourceKind,
                sourceVersion = SmsIngestionPolicy.SOURCE_VERSION,
                capturedAtEpochMs = now,
            )
        }
        // Pre-compute dedupe-inside-batch counts so we can report them while
        // keeping the @Transaction commit atomic.
        val byProvider = entities.distinctBy { it.providerId }
        val byContent = byProvider.distinctBy { it.contentHash }

        // Compute which rows would be duplicates against already-stored rows.
        var duplicatesAgainstExisting = 0
        val filtered = ArrayList<RawSmsEntity>(entities.size)
        for (e in byContent) {
            val storedByProvider = dao.findByProviderId(e.providerId)
            val storedByHash = dao.findByContentHash(e.contentHash)
            if (storedByProvider != null || storedByHash != null) {
                duplicatesAgainstExisting++
            } else {
                filtered.add(e)
            }
        }
        val persistedInBatch = filtered.count { e ->
            dao.insertRawBatch(listOf(e)).firstOrNull()?.let { it != -1L } == true
        }
        // In-batch duplicates = distinct rows that were not new, excluding
        // those already counted as duplicates against stored rows.
        val duplicateInBatch = byContent.size - persistedInBatch - duplicatesAgainstExisting

        // Advance cursor + aggregate counts atomically.
        val lastProviderId = entities.last().providerId
        // If zero rows were actually persisted we still mustn't roll back the
        // already-seen aggregates: pass the input size for "seen".
        dao.commitBatch(
            rows = filtered,
            lastProviderId = lastProviderId,
            nowEpochMs = now,
            seenInBatch = entities.size,
            persistedInBatch = persistedInBatch,
            duplicateInBatch = duplicateInBatch + duplicatesAgainstExisting,
        )

        return BatchResult(
            persisted = persistedInBatch,
            duplicate = duplicateInBatch + duplicatesAgainstExisting,
            lastProviderId = lastProviderId,
        )
    }

    override suspend fun currentCursor(): BackfillCursor? {
        val c = dao.getCursor() ?: return null
        return BackfillCursor(
            lastProviderId = c.lastProviderId,
            status = c.status,
            totalSeen = c.totalSeen,
            totalPersisted = c.totalPersisted,
            totalDuplicate = c.totalDuplicate,
        )
    }

    override suspend fun markStatus(status: String, lastError: String?) {
        val now = clock()
        dao.upsertProgress(
            (dao.getProgress() ?: SmsIngestionProgressEntity(
                id = 1, totalPersisted = 0L, lastUpdatedAtEpochMs = now,
                status = status, lastError = lastError,
            )).copy(status = status, lastError = lastError, lastUpdatedAtEpochMs = now)
        )
        dao.upsertCursor(
            dao.getCursor()?.copy(status = status, lastUpdatedAtEpochMs = now)
                ?: SmsBackfillCursorEntity(
                    id = 1, lastProviderId = null,
                    startedAtEpochMs = now, lastUpdatedAtEpochMs = now,
                    status = status, totalSeen = 0L, totalPersisted = 0L, totalDuplicate = 0L,
                )
        )
    }

    override fun observeProgress(): Flow<IngestionProgress> =
        dao.observeProgress().map { p ->
            IngestionProgress(
                totalPersisted = p?.totalPersisted ?: 0L,
                status = p?.status ?: "IDLE",
                lastUpdatedAtEpochMs = p?.lastUpdatedAtEpochMs ?: 0L,
                lastError = p?.lastError,
            )
        }

    override fun observeRawCount(): Flow<Long> = dao.observeRawCount()
}
