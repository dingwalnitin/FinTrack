package com.example.fintrack.sms

import com.example.fintrack.data.db.RawSmsEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

/**
 * Tests for the [SmsDao] 90-day batch lookback filter (`rawRowsSince`).
 *
 * Verifies that only rows with `receivedAtEpochMs >= cutoff` are returned,
 * that ordering is oldest-first, and that the lookback excludes stale
 * backlog rows from re-processing.
 */
class SmsDaoLookbackTest {

    private lateinit var dao: FakeSmsDao

    private val now = 1_700_000_000_000L
    private val dayMs = 86_400_000L

    @Before
    fun setUp() {
        dao = FakeSmsDao()
    }

    private fun rawSms(
        id: String = UUID.randomUUID().toString(),
        body: String = "Test SMS $id",
        receivedAtEpochMs: Long = now,
    ): RawSmsEntity {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return RawSmsEntity(
            id = id,
            providerId = id.hashCode().toLong().and(0x7fffffff),
            sender = "SENDER",
            receivedAtEpochMs = receivedAtEpochMs,
            body = body,
            contentHash = hash,
            sourceKind = "BACKFILL",
            sourceVersion = "v1",
            capturedAtEpochMs = now,
        )
    }

    @Test
    fun `rawRowsSince returns only rows at or after cutoff`() = runTest {
        val old = rawSms("old-1", body = "old SMS", receivedAtEpochMs = now - 100 * dayMs)
        val recent = rawSms("recent-1", body = "recent SMS", receivedAtEpochMs = now - 1 * dayMs)
        val today = rawSms("today-1", body = "today SMS", receivedAtEpochMs = now)

        dao.insertRawBatch(listOf(old, recent, today))

        val cutoff = now - 90 * dayMs
        val result = dao.rawRowsSince(cutoff)

        assertEquals(2, result.size)
        assertTrue(result.all { it.id != "old-1" })
        assertEquals("recent-1", result[0].id)
        assertEquals("today-1", result[1].id)
    }

    @Test
    fun `rawRowsSince returns empty when no rows after cutoff`() = runTest {
        dao.insertRawBatch(
            listOf(
                rawSms("old-1", receivedAtEpochMs = now - 200 * dayMs),
                rawSms("old-2", receivedAtEpochMs = now - 150 * dayMs),
            ),
        )

        val result = dao.rawRowsSince(now - 90 * dayMs)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `rawRowsSince returns all rows when cutoff is zero`() = runTest {
        dao.insertRawBatch(
            listOf(
                rawSms("a", receivedAtEpochMs = 1000L),
                rawSms("b", receivedAtEpochMs = 2000L),
                rawSms("c", receivedAtEpochMs = 3000L),
            ),
        )

        val result = dao.rawRowsSince(0L)
        assertEquals(3, result.size)
    }

    @Test
    fun `rawRowsSince orders results oldest-first`() = runTest {
        val rows = listOf(
            rawSms("mid", receivedAtEpochMs = now - 50 * dayMs),
            rawSms("oldest", receivedAtEpochMs = now - 80 * dayMs),
            rawSms("newest", receivedAtEpochMs = now - 10 * dayMs),
        )
        dao.insertRawBatch(rows)

        val result = dao.rawRowsSince(now - 90 * dayMs)
        assertEquals(3, result.size)
        assertEquals("oldest", result[0].id)
        assertEquals("mid", result[1].id)
        assertEquals("newest", result[2].id)
    }

    @Test
    fun `boundary condition exact cutoff is included`() = runTest {
        val cutoff = now - 90 * dayMs
        val exact = rawSms("exact", receivedAtEpochMs = cutoff)
        val before = rawSms("before", receivedAtEpochMs = cutoff - 1)
        dao.insertRawBatch(listOf(before, exact))

        val result = dao.rawRowsSince(cutoff)
        assertEquals(1, result.size)
        assertEquals("exact", result[0].id)
    }

    @Test
    fun `findByProviderId returns the matching row`() = runTest {
        val row = rawSms("a", receivedAtEpochMs = now - 10 * dayMs)
        dao.insertRawBatch(listOf(row))

        val found = dao.findByProviderId(row.providerId)
        assertEquals(row.id, found?.id)
    }

    @Test
    fun `findByProviderId returns null for unknown id`() = runTest {
        dao.insertRawBatch(listOf(rawSms("a", receivedAtEpochMs = now - 10 * dayMs)))
        assertTrue(dao.findByProviderId(999_999L) == null)
    }

    @Test
    fun `findByContentHash returns the matching row`() = runTest {
        val row = rawSms("a", body = "unique body", receivedAtEpochMs = now - 10 * dayMs)
        dao.insertRawBatch(listOf(row))

        val found = dao.findByContentHash(row.contentHash)
        assertEquals(row.id, found?.id)
    }

    @Test
    fun `insertRawBatch returns -1 for duplicate provider id`() = runTest {
        val row = rawSms("a", receivedAtEpochMs = now - 10 * dayMs)
        val first = dao.insertRawBatch(listOf(row))
        val second = dao.insertRawBatch(listOf(row.copy(id = "different-id", body = "different body")))
        // First insert returns a positive id, the duplicate returns -1.
        assertTrue(first[0] != -1L)
        assertEquals(-1L, second[0])
    }

    @Test
    fun `allRawRows returns all rows ordered by receivedAt then providerId`() = runTest {
        val rows = listOf(
            rawSms("mid", receivedAtEpochMs = now - 50 * dayMs),
            rawSms("oldest", receivedAtEpochMs = now - 80 * dayMs),
            rawSms("newest", receivedAtEpochMs = now - 10 * dayMs),
        )
        dao.insertRawBatch(rows)

        val result = dao.allRawRows()
        assertEquals(3, result.size)
        assertEquals("oldest", result[0].id)
        assertEquals("mid", result[1].id)
        assertEquals("newest", result[2].id)
    }

    @Test
    fun `rawRowsSince is a subset of allRawRows after cutoff`() = runTest {
        dao.insertRawBatch(
            listOf(
                rawSms("old-1", receivedAtEpochMs = now - 100 * dayMs),
                rawSms("recent-1", receivedAtEpochMs = now - 20 * dayMs),
                rawSms("today-1", receivedAtEpochMs = now),
            ),
        )

        val cutoff = now - 90 * dayMs
        val since = dao.rawRowsSince(cutoff)
        val all = dao.allRawRows()

        // Since is strictly the subset of all whose timestamp >= cutoff.
        assertEquals(2, since.size)
        assertEquals(all.size - 1, since.size)
        assertTrue(since.all { it.receivedAtEpochMs >= cutoff })
    }
}