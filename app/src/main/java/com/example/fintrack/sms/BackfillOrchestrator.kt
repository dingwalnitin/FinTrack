package com.example.fintrack.sms

import com.example.fintrack.domain.repository.RawSmsRow
import com.example.fintrack.domain.repository.SmsRepository
import com.example.fintrack.domain.sms.SmsIngestionPolicy

/**
 * Testable backfill orchestrator. Walks [SmsSource] in pages and commits each
 * page to [SmsRepository] durably. This is the work the [SmsBackfillWorker]
 * does in its [doWork]; extracting it lets us unit-test the resume, dedupe,
 * revocation, and bounded run behavior without spinning up a
 * CoroutineWorker context.
 */
class BackfillOrchestrator(
    private val source: SmsSource,
    private val repository: SmsRepository,
    private val maxPages: Int = SmsIngestionPolicy.BACKFILL_MAX_PAGES_PER_RUN,
    private val pageSize: Int = SmsIngestionPolicy.BACKFILL_PAGE_SIZE,
) {

    sealed interface Outcome {
        data object Complete : Outcome
        data object Paused : Outcome // hit per-run page cap; another run resumes
        data object Revoked : Outcome
        data class Failed(val reason: String) : Outcome
    }

    suspend fun run(): Outcome {
        if (!source.hasPermission()) {
            repository.markStatus("REVOKED", lastError = null)
            return Outcome.Revoked
        }
        val cursor = repository.currentCursor()
        if (cursor?.status == "COMPLETE" && cursor.lastProviderId != null) {
            return Outcome.Complete
        }
        repository.markStatus("RUNNING")

        var afterProviderId: Long? = cursor?.lastProviderId
        var pagesProcessed = 0
        return try {
            while (pagesProcessed < maxPages) {
                val page = source.readPage(afterProviderId, pageSize)
                if (page.isEmpty()) {
                    repository.markStatus("COMPLETE")
                    return Outcome.Complete
                }
                val rows = page.map {
                    RawSmsRow(
                        providerId = it.providerId,
                        sender = it.sender,
                        body = it.body,
                        timestampEpochMs = it.timestampEpochMs,
                    )
                }
                repository.commitBatch(rows, sourceKind = SmsIngestionPolicy.SOURCE_KIND_BACKFILL)
                afterProviderId = page.last().providerId
                pagesProcessed++
            }
            repository.markStatus("PAUSED")
            Outcome.Paused
        } catch (t: Throwable) {
            val sanitized = (t::class.java.simpleName + ": " + (t.message ?: "")).take(200)
            repository.markStatus("FAILED", lastError = sanitized)
            Outcome.Failed(sanitized)
        }
    }
}
