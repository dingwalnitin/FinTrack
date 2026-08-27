package com.example.fintrack.sms

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Scheduler for SMS processing workers. Keeps enqueue policy in one place:
 *  - onMessageReceived is unique by tag; only one in-flight processing job.
 *  - backfill runs as a separate chain so the user can re-trigger it.
 */
object SmsIngestionScheduler {

    const val UNIQUE_PROCESSING = "sms-processing"
    const val UNIQUE_BACKFILL = "sms-backfill"

    fun enqueueSmsProcessing(context: Context) {
        val request = OneTimeWorkRequestBuilder<SmsProcessingWorker>()
            .setConstraints(Constraints.Builder().build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_PROCESSING, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun enqueueBackfill(context: Context) {
        val request = OneTimeWorkRequestBuilder<SmsBackfillWorker>()
            .setConstraints(
                Constraints.Builder()
                    // No network constraint; ingestion is fully on-device.
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_BACKFILL, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelBackfill(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_BACKFILL)
    }
}
