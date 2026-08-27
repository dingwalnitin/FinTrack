package com.example.fintrack.sms

/**
 * Provider-agnostic raw SMS record. The system SMS database assigns an integer
 * id (Telephony.Sms._ID) which is the only stable identity we can rely on for
 * idempotent ingestion. Body and sender come from the system; we never write
 * to or delete the user SMS database.
 */
data class RawSms(
    /** Provider id (Telephony.Sms._ID). Unique per inbox; used as a stable cursor. */
    val providerId: Long,
    val sender: String?,
    val timestampEpochMs: Long,
    val body: String,
)
