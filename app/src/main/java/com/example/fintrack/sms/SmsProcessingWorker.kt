package com.example.fintrack.sms

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.fintrack.FinTrackApplication

/**
 * SmsProcessingWorker: durable downstream processing kicked by the receiver.
 *
 * The receiver persists immutable raw evidence; this worker then runs the
 * captured SMS through the LLM (rate-limited, with exponential backoff) via
 * [LlmProcessingService] so every newly arrived SMS is interpreted as soon
 * as it lands — not only when the user manually triggers a scan in Settings.
 */
class SmsProcessingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as FinTrackApplication
        val repo = app.smsRepository
        val triggerIds = inputData
            .getStringArray(SmsIngestionScheduler.KEY_TRIGGER_MESSAGE_IDS)
            ?.toSet()
            .orEmpty()
        return try {
            repo.markStatus("RUNNING")
            // The service is idempotent — messages with a durable outcome are
            // skipped — and guarantees a pass that observes everything captured
            // before this call, so a scan already in flight cannot swallow the
            // message that triggered us. Trigger ids are triaged one-shot so a
            // live SMS is not queued behind a historical backfill.
            app.llmProcessingService.startScanAndWait(directMessageIds = triggerIds)
            repo.markStatus("IDLE")
            Result.success()
        } catch (t: Throwable) {
            val sanitized = (t::class.java.simpleName + ": " + (t.message ?: ""))
                .take(200)
            repo.markStatus("FAILED", lastError = sanitized)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
