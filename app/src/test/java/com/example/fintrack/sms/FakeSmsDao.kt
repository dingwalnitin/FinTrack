package com.example.fintrack.sms

import com.example.fintrack.data.db.RawSmsEntity
import com.example.fintrack.data.db.SmsBackfillCursorEntity
import com.example.fintrack.data.db.SmsDao
import com.example.fintrack.data.db.SmsIngestionProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory test double for [SmsDao]. Mirrors the unique-index / IGNORE
 * behavior on raw_sms so idempotency can be unit-tested without an Android
 * runtime.
 */
class FakeSmsDao : SmsDao {

    private val rows = mutableListOf<RawSmsEntity>()
    private val seenProviderIds = mutableSetOf<Long>()
    private val seenContentHashes = mutableSetOf<String>()

    private val cursorFlow = MutableStateFlow<SmsBackfillCursorEntity?>(null)
    private val progressFlow = MutableStateFlow<SmsIngestionProgressEntity?>(null)

    override suspend fun insertRawBatch(rows: List<RawSmsEntity>): List<Long> =
        rows.map { e ->
            val dup = seenProviderIds.contains(e.providerId) ||
                seenContentHashes.contains(e.contentHash)
            if (dup) -1L
            else {
                this.rows.add(e)
                seenProviderIds.add(e.providerId)
                seenContentHashes.add(e.contentHash)
                e.id.hashCode().toLong()
            }
        }

    override fun observeRawCount(): Flow<Long> =
        MutableStateFlow(rows.size.toLong())

    override suspend fun rawCount(): Long = rows.size.toLong()

    override suspend fun findByProviderId(providerId: Long): RawSmsEntity? =
        rows.firstOrNull { it.providerId == providerId }

    override suspend fun findByContentHash(hash: String): RawSmsEntity? =
        rows.firstOrNull { it.contentHash == hash }

    override suspend fun allRawRows(): List<RawSmsEntity> = rows.sortedWith(
        compareBy<RawSmsEntity> { it.receivedAtEpochMs }.thenBy { it.providerId },
    )

    override suspend fun rawSmsById(id: String): RawSmsEntity? =
        rows.firstOrNull { it.id == id }

    override suspend fun rawRowsSince(receivedAfterEpochMs: Long): List<RawSmsEntity> =
        rows.filter { it.receivedAtEpochMs >= receivedAfterEpochMs }
            .sortedWith(compareBy<RawSmsEntity> { it.receivedAtEpochMs }.thenBy { it.providerId })

    override suspend fun upsertCursor(cursor: SmsBackfillCursorEntity) {
        cursorFlow.value = cursor
    }

    override suspend fun getCursor(): SmsBackfillCursorEntity? = cursorFlow.value

    override fun observeCursor(): Flow<SmsBackfillCursorEntity?> = cursorFlow

    override suspend fun upsertProgress(progress: SmsIngestionProgressEntity) {
        progressFlow.value = progress
    }

    override fun observeProgress(): Flow<SmsIngestionProgressEntity?> = progressFlow

    override suspend fun getProgress(): SmsIngestionProgressEntity? = progressFlow.value

    fun allRows(): List<RawSmsEntity> = rows.toList()
}
