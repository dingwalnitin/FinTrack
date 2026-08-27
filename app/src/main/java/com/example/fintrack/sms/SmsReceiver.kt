package com.example.fintrack.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.fintrack.FinTrackApplication
import com.example.fintrack.domain.sms.SmsIngestionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SMS_RECEIVED receiver.
 *
 * Mandate (per App Bible / prompt):
 *  - Keep the receiver work minimal. We persist raw evidence immediately and
 *    enqueue durable WorkManager processing. We do NOT do parsing, posting,
 *    network or any money-changing work here.
 *  - Receiver must be resilient to:
 *      * duplicate broadcasts (same provider id) — handled by the unique index
 *        on raw_sms.providerId and on the contentHash; captureRaw is a no-op
 *      * app-killed state — we ignore the broadcast; on next launch the
 *        backfill worker reconciles via cursor + last providerId
 *
 * We do NOT call goAsync() because we want a hard, bounded receipt of the
 * broadcast to prevent ANRs. The actual row write is small and bounded.
 */
class SmsReceiver : BroadcastReceiver() {

    private val dedupeGate = AtomicBoolean(false)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // De-dup at receiver layer when the system fires the broadcast twice
        // within the same process lifetime (some OEMs retry).
        if (!dedupeGate.compareAndSet(false, true)) return
        try {
            val app = context.applicationContext as FinTrackApplication
            val repo = app.smsRepository

            // Bounded synchronous persist; raw writes are short and idempotent.
            val captured = runBlocking(Dispatchers.IO) {
                var any = false
                for (m in messages) {
                    val providerId = inferProviderId(m)
                    val persisted = repo.captureRaw(
                        providerId = providerId,
                        sender = m.displayOriginatingAddress,
                        body = m.displayMessageBody.orEmpty(),
                        timestampEpochMs = if (m.timestampMillis > 0) m.timestampMillis else System.currentTimeMillis(),
                        sourceKind = SmsIngestionPolicy.SOURCE_KIND_SMS_RECEIVED,
                    )
                    any = any or persisted
                }
                any
            }

            // Kick durable processing for any newly captured message.
            if (captured) {
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        SmsIngestionScheduler.enqueueSmsProcessing(context.applicationContext)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        } finally {
            dedupeGate.set(false)
        }
    }

    /**
     * SmsMessage has no public provider id; we synthesize a stable id from
     * (timestamp | sender | body) so it matches our content hash boundary. The
     * raw_sms table unique index on contentHash still de-dupes duplicates.
     */
    private fun inferProviderId(m: android.telephony.SmsMessage): Long {
        val ts = if (m.timestampMillis > 0) m.timestampMillis else System.currentTimeMillis()
        val key = (m.displayOriginatingAddress.orEmpty()) + '|' + (m.displayMessageBody.orEmpty())
        return (ts xor key.hashCode().toLong()) and 0x7fffffffffffffffL
    }
}
