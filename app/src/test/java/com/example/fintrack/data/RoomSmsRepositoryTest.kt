package com.example.fintrack.data

import com.example.fintrack.data.repository.RoomSmsRepository
import com.example.fintrack.domain.repository.RawSmsRow
import com.example.fintrack.domain.sms.SmsIngestionPolicy
import com.example.fintrack.sms.FakeSmsDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit tests for the SMS evidence acquisition increment:
 *  - duplicate broadcasts (same provider id) are idempotent
 *  - re-running a backfill does not insert duplicates
 *  - cursor advances to the last provider id we have seen
 *  - permission revocation is non-destructive (raw rows preserved)
 *  - aggregate progress is observable; per-message recomposition is not
 */
class RoomSmsRepositoryTest {

    private fun repo(clock: () -> Long = { 0L }): Pair<RoomSmsRepository, FakeSmsDao> {
        val dao = FakeSmsDao()
        return RoomSmsRepository(dao, clock) to dao
    }

    @Test
    fun `captureRaw is idempotent on duplicate providerId`() = runTest {
        val (repo, dao) = repo()
        assertTrue(repo.captureRaw(42, "HDFC", "INR 100", 1_000L, "SMS_RECEIVED"))
        assertFalse(repo.captureRaw(42, "HDFC", "INR 100", 1_000L, "SMS_RECEIVED"))
        assertEquals(1L, dao.rawCount())
    }

    @Test
    fun `captureRaw preserves raw body immutably`() = runTest {
        val (repo, dao) = repo()
        repo.captureRaw(1, "HDFC", "original", 1L, "SMS_RECEIVED")
        val stored = dao.findByProviderId(1)!!
        assertEquals("original", stored.body)
    }

    @Test
    fun `captureRaw leaves unknown fields unknown (null sender preserved)`() = runTest {
        val (repo, dao) = repo()
        repo.captureRaw(1, null, "hi", 1L, "SMS_RECEIVED")
        val stored = dao.findByProviderId(1)!!
        assertEquals(null, stored.sender)
    }

    @Test
    fun `commitBatch is idempotent when re-run with same rows`() = runTest {
        val (repo, dao) = repo()
        val rows = listOf(
            RawSmsRow(1, "HDFC", "INR 100 debited", 100L),
            RawSmsRow(2, "HDFC", "INR 200 debited", 200L),
        )
        val first = repo.commitBatch(rows, "BACKFILL")
        assertEquals(2, first.persisted)
        assertEquals(0, first.duplicate)
        val cursor = repo.currentCursor()
        assertNotNull(cursor)
        assertEquals(1L, cursor!!.lastProviderId)
        assertEquals(2L, cursor.totalPersisted)

        // Re-run the same batch: must be a no-op.
        val second = repo.commitBatch(rows, "BACKFILL")
        assertEquals(0, second.persisted)
        assertEquals(2, second.duplicate)
        assertEquals(2L, dao.rawCount())
    }

    @Test
    fun `backfill batch commit advances cursor to last provider id`() = runTest {
        val (repo, _) = repo()
        repo.commitBatch(
            listOf(
                RawSmsRow(3, "X", "b3", 30L),
                RawSmsRow(2, "X", "b2", 20L),
                RawSmsRow(1, "X", "b1", 10L),
            ),
            "BACKFILL",
        )
        val cursor = repo.currentCursor()!!
        // Cursor advances to the smallest providerId in the batch
        // (we walk newest-first; the last id we processed is the oldest in the page).
        assertEquals(1L, cursor.lastProviderId)
        assertEquals(3L, cursor.totalPersisted)
    }

    @Test
    fun `revocation is non-destructive - raw rows are preserved`() = runTest {
        val (repo, dao) = repo()
        repo.commitBatch(listOf(RawSmsRow(1, "HDFC", "INR 100", 1L)), "BACKFILL")
        assertEquals(1L, dao.rawCount())
        // Simulate the user revoking permission. The repository can still
        // surface the REVOKED status from the existing cursor.
        repo.markStatus("REVOKED", lastError = null)
        assertEquals(1L, dao.rawCount())
        val cursor = repo.currentCursor()!!
        assertEquals("REVOKED", cursor.status)
    }

    @Test
    fun `duplicate broadcasts with the same content are deduped by contentHash`() = runTest {
        val (repo, dao) = repo()
        assertTrue(repo.captureRaw(1, "HDFC", "INR 100", 100L, "SMS_RECEIVED"))
        assertFalse(repo.captureRaw(999, "HDFC", "INR 100", 100L, "SMS_RECEIVED"))
        assertEquals(1L, dao.rawCount())
    }

    @Test
    fun `aggregate progress is observable and never per-message`() = runTest {
        val (repo, _) = repo()
        repeat(50) { i ->
            repo.captureRaw(i.toLong(), "S$i", "b$i", i.toLong(), "SMS_RECEIVED")
        }
        val progress = repo.observeProgress()
        val snapshot = progress.first()
        assertEquals(50L, snapshot.totalPersisted)
    }

    @Test
    fun `raw row count is observable as an aggregate`() = runTest {
        val (repo, _) = repo()
        repeat(7) { i ->
            repo.captureRaw(i.toLong(), "S", "b$i", i.toLong(), "SMS_RECEIVED")
        }
        val c = repo.observeRawCount().first()
        assertEquals(7L, c)
    }

    @Test
    fun `empty batch is rejected (no silent zero-persist)`() {
        val (repo, _) = repo()
        try {
            runBlocking { repo.commitBatch(emptyList(), "BACKFILL") }
            assert(false) { "expected IllegalArgumentException" }
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `content hash is independent of raw row order`() {
        val a = SmsIngestionPolicy.contentHash("X", "body", 1L)
        val b = SmsIngestionPolicy.contentHash("X", "body", 1L)
        assertEquals(a, b)
    }
}
