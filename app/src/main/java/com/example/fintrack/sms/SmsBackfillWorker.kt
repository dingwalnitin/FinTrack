package com.example.fintrack.sms

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.fintrack.FinTrackApplication
import com.example.fintrack.domain.sms.SmsIngestionPolicy

/**
 * WorkManager-backed backfill worker. Delegates the actual paging / commit to
 * [BackfillOrchestrator] which is unit-tested directly; this class only
 * maps the outcome to a [Result] for the WorkManager scheduler.
 */
class SmsBackfillWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as FinTrackApplication
        val orchestrator = BackfillOrchestrator(app.smsSource, app.smsRepository)
        return when (orchestrator.run()) {
            BackfillOrchestrator.Outcome.Complete,
            BackfillOrchestrator.Outcome.Paused,
            BackfillOrchestrator.Outcome.Revoked -> Result.success()
            is BackfillOrchestrator.Outcome.Failed ->
                if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    @Suppress("UNUSED") // kept to keep the policy import live in this file
    private val policy = SmsIngestionPolicy.BACKFILL_PAGE_SIZE
}
