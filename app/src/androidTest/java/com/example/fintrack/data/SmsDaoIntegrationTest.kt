package com.example.fintrack.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.fintrack.data.db.FinTrackDatabaseV2
import com.example.fintrack.data.db.RawSmsEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device integration test for the v4 SMS evidence schema.
 * Verifies the unique indexes on providerId and contentHash are enforced
 * and that the aggregate progress flow tracks writes correctly.
 */
@RunWith(AndroidJUnit4::class)
class SmsDaoIntegrationTest {

    private lateinit var db: FinTrackDatabaseV2

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

    private fun row(providerId: Long, body: String, ts: Long) = RawSmsEntity(
        id = "id-$providerId",
        providerId = providerId,
        sender = "HDFC",
        receivedAtEpochMs = ts,
        body = body,
        contentHash = "h$providerId",
        sourceKind = "BACKFILL",
        sourceVersion = "sms-v1",
        capturedAtEpochMs = 0L,
    )

    @Test
    fun uniqueIndexOnProviderId_isEnforced() = runTest {
        val dao = db.smsDao()
        dao.insertRawBatch(listOf(row(1, "a", 1L)))
        val second = dao.insertRawBatch(listOf(row(1, "a", 1L)))
        assertEquals(-1L, second.first())
        assertEquals(1L, dao.rawCount())
    }

    @Test
    fun uniqueIndexOnContentHash_isEnforced() = runTest {
        val dao = db.smsDao()
        val r1 = row(1, "a", 1L).copy(contentHash = "same")
        val r2 = row(2, "a", 1L).copy(contentHash = "same")
        dao.insertRawBatch(listOf(r1))
        val second = dao.insertRawBatch(listOf(r2))
        assertEquals(-1L, second.first())
        assertEquals(1L, dao.rawCount())
    }

    @Test
    fun commitBatch_advancesCursor_andUpdatesProgress() = runTest {
        val dao = db.smsDao()
        dao.commitBatch(
            rows = listOf(
                row(10, "a", 10L),
                row(5, "b", 5L),
                row(1, "c", 1L),
            ),
            lastProviderId = 1L,
            nowEpochMs = 1_000L,
            seenInBatch = 3,
            persistedInBatch = 3,
            duplicateInBatch = 0,
        )
        val c = dao.getCursor()
        assertNotNull(c)
        assertEquals(1L, c!!.lastProviderId)
        assertEquals(3L, c.totalPersisted)
        assertEquals(3L, c.totalSeen)
        val p = dao.getProgress()
        assertNotNull(p)
        assertEquals(3L, p!!.totalPersisted)
    }

    @Test
    fun cursor_isSingleton() = runTest {
        val dao = db.smsDao()
        assertNull(dao.getCursor())
        dao.commitBatch(
            rows = listOf(row(1, "a", 1L)),
            lastProviderId = 1L,
            nowEpochMs = 1L,
            seenInBatch = 1, persistedInBatch = 1, duplicateInBatch = 0,
        )
        // Second commit should still produce a single cursor row.
        dao.commitBatch(
            rows = listOf(row(2, "b", 2L)),
            lastProviderId = 2L,
            nowEpochMs = 2L,
            seenInBatch = 1, persistedInBatch = 1, duplicateInBatch = 0,
        )
        val c = dao.getCursor()!!
        assertEquals(2L, c.lastProviderId)
        assertEquals(2L, c.totalPersisted)
    }
}
