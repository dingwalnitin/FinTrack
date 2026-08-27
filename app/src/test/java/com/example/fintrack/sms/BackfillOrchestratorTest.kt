package com.example.fintrack.sms

import com.example.fintrack.data.repository.RoomSmsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end backfill tests using the in-memory fakes. Verifies:
 *  - complete run over multiple pages
 *  - resume after pause (page cap hit)
 *  - revoke path leaves raw rows intact
 *  - duplicate source rows are deduped, no new rows are created on re-run
 */
class BackfillOrchestratorTest {

    private data class TestEnv(
        val repo: RoomSmsRepository,
        val dao: FakeSmsDao,
        val source: InMemorySmsSource,
    )

    private fun newRepo(
        rows: List<RawSms>,
        revoked: Boolean = false,
    ): TestEnv {
        val dao = FakeSmsDao()
        val source = InMemorySmsSource(rows, revoked = revoked)
        val repo = RoomSmsRepository(dao)
        return TestEnv(repo, dao, source)
    }

    @Test
    fun `complete run over multiple pages sets status COMPLETE`() = runTest {
        val rows = (1L..150L).map { RawSms(it, "S", it * 1000L, "body$it") }
        val (repo, dao, source) = newRepo(rows)
        val orchestrator = BackfillOrchestrator(source, repo, maxPages = 10, pageSize = 25)
        val outcome = orchestrator.run()
        assertEquals(BackfillOrchestrator.Outcome.Complete, outcome)
        assertEquals(150L, dao.rawCount())
    }

    @Test
    fun `resume after pause picks up at the cursor without duplicates`() = runTest {
        val rows = (1L..300L).map { RawSms(it, "S", it * 1000L, "body$it") }
        val (repo, dao, source) = newRepo(rows)
        val first = BackfillOrchestrator(source, repo, maxPages = 2, pageSize = 50)
        val o1 = first.run()
        assertEquals(BackfillOrchestrator.Outcome.Paused, o1)
        val firstCount = dao.rawCount()
        assertEquals(100L, firstCount)

        // Second run resumes from the cursor.
        val second = BackfillOrchestrator(source, repo, maxPages = 2, pageSize = 50)
        val o2 = second.run()
        assertTrue(
            o2 is BackfillOrchestrator.Outcome.Paused || o2 is BackfillOrchestrator.Outcome.Complete
        )
        val afterSecond = dao.rawCount()
        assertTrue("expected progress, got $afterSecond", afterSecond > firstCount)
        // No duplicates: every raw row's providerId appears exactly once.
        val ids = dao.allRows().map { it.providerId }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `revocation is non-destructive and surfaces as Outcome Revoked`() = runTest {
        val rows = (1L..5L).map { RawSms(it, "S", it * 1000L, "b$it") }
        val (repo, dao, source) = newRepo(rows)
        // First run captures all rows.
        BackfillOrchestrator(source, repo, maxPages = 5, pageSize = 10).run()
        assertEquals(5L, dao.rawCount())
        // Revoke: next run is a clean no-op with REVOKED status.
        source.setRevoked(true)
        val outcome = BackfillOrchestrator(source, repo).run()
        assertEquals(BackfillOrchestrator.Outcome.Revoked, outcome)
        // Raw rows are preserved.
        assertEquals(5L, dao.rawCount())
        val cursor = repo.currentCursor()!!
        assertEquals("REVOKED", cursor.status)
    }

    @Test
    fun `re-run on a complete cursor is a no-op success`() = runTest {
        val rows = (1L..3L).map { RawSms(it, "S", it * 1000L, "b$it") }
        val (repo, dao, source) = newRepo(rows)
        BackfillOrchestrator(source, repo, maxPages = 5, pageSize = 10).run()
        val before = dao.rawCount()
        val second = BackfillOrchestrator(source, repo, maxPages = 5, pageSize = 10).run()
        assertEquals(BackfillOrchestrator.Outcome.Complete, second)
        assertEquals(before, dao.rawCount())
    }

    @Test
    fun `empty source results in COMPLETE without any writes`() = runTest {
        val (repo, dao, source) = newRepo(emptyList())
        val outcome = BackfillOrchestrator(source, repo).run()
        assertEquals(BackfillOrchestrator.Outcome.Complete, outcome)
        assertEquals(0L, dao.rawCount())
    }
}
