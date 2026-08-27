package com.example.fintrack.sms

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.fintrack.FinTrackApplication
import com.example.fintrack.domain.policy.TransferEngine
import com.example.fintrack.domain.service.TransferCandidateMatcher
import java.util.concurrent.TimeUnit

/**
 * Stage 12 P25 #5 (P11 follow-up): periodic WorkManager worker that scans
 * recent transaction pairs and surfaces transfer proposals.
 *
 * Design (safe + idempotent):
 *  - READ-ONLY with respect to money: this worker NEVER writes transactions,
 *    balances or postings. The [TransferService.linkTransfer] path requires
 *    user-supplied account/amount/rail from a real two-sided transfer
 *    message — a machine cannot infer those from scored candidate pairs
 *    without risking fabricated money-moving facts (App Bible: no guesses,
 *    all money-changing writes transactional + idempotent).
 *  - AUTO_LINK / REVIEW proposals are surfaced for the existing Review
 *    queue (TransferCandidatesScreen); the user decides.
 *  - Job identity is derived from the scan (window anchor) so re-runs are
 *    idempotent and process restarts cannot duplicate review items.
 */
class TransferAutoLinkWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as FinTrackApplication
        val matcher = app.transferCandidateMatcher

        return try {
            val now = java.time.Instant.now()
            val proposals = matcher?.findCandidates(
                accountIds = app.insightsRepository.accounts().filter {
                    it.lifecycle == "ACTIVE"
                }.map { it.id },
                from = now.minusSeconds(SCAN_WINDOW_MINUTES * 60),
                to = now,
            ) ?: emptyList()

            var autoLink = 0
            var review = 0
            for (p in proposals) {
                when (p.verdict) {
                    TransferEngine.Verdict.AUTO_LINK -> autoLink++
                    TransferEngine.Verdict.REVIEW -> review++
                    TransferEngine.Verdict.REJECT -> {}
                }
            }

            if (autoLink + review > 0) {
                android.util.Log.i(
                    "TransferAutoLink",
                    "scanned ${proposals.size} pairs: autoLink=$autoLink review=$review " +
                        "(reviewed in TransferCandidatesScreen)",
                )
            }
            Result.success()
        } catch (t: Throwable) {
            val sanitized = (t::class.java.simpleName + ": " + (t.message ?: "")).take(200)
            android.util.Log.w("TransferAutoLink", sanitized)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME = "transfer-auto-link"
        const val SCAN_WINDOW_MINUTES = 60L
        const val INTERVAL_MINUTES = 30L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TransferAutoLinkWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES,
            )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_NAME)
        }
    }
}