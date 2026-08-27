package com.example.fintrack.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v4 SMS evidence blueprint.
 *
 * Two distinct tables:
 *  - [RawSmsEntity] is immutable raw evidence (provider id, sender, timestamp,
 *    body, content hash). We do NOT delete the user SMS database; this is our
 *    local copy keyed by the system provider id for idempotency.
 *  - [SmsBackfillCursorEntity] persists the durable backfill cursor (last
 *    provider id processed) so a process-death or reboot resumes from the
 *    same point with no duplicate writes.
 *  - [SmsIngestionProgressEntity] is a single-row aggregate count of raw
 *    evidence rows + last-cursor state. The UI observes aggregate counts only;
 *    we never re-emit per-message.
 */

@Entity(
    tableName = "raw_sms",
    indices = [
        Index(value = ["providerId"], unique = true), // system id, idempotency key
        Index("receivedAtEpochMs"),
        Index("contentHash", unique = true),          // dedupe across provider-id churn
    ],
)
data class RawSmsEntity(
    @PrimaryKey val id: String,                    // stable UUID we own
    val providerId: Long,                          // Telephony.Sms._ID
    val sender: String?,                           // may be unknown (null) when sender is missing
    val receivedAtEpochMs: Long,
    val body: String,                              // immutable
    val contentHash: String,                       // sha-256(body|sender|timestamp)
    val sourceKind: String,                        // SMS_RECEIVED | BACKFILL
    val sourceVersion: String,                     // schema version
    val capturedAtEpochMs: Long,
)

@Entity(
    tableName = "sms_backfill_cursor",
)
data class SmsBackfillCursorEntity(
    @PrimaryKey val id: Int,                       // always 1 — singleton
    val lastProviderId: Long?,                     // last successfully persisted id
    val startedAtEpochMs: Long,
    val lastUpdatedAtEpochMs: Long,
    val status: String,                            // IDLE | RUNNING | PAUSED | COMPLETE | REVOKED | FAILED
    val totalSeen: Long,                           // aggregate read count
    val totalPersisted: Long,                      // aggregate persisted count (new rows)
    val totalDuplicate: Long,                      // aggregate deduped count
)

@Entity(
    tableName = "sms_ingestion_progress",
)
data class SmsIngestionProgressEntity(
    @PrimaryKey val id: Int,                       // always 1 — singleton
    val totalPersisted: Long,
    val lastUpdatedAtEpochMs: Long,
    val status: String,                            // IDLE | RUNNING | PAUSED | COMPLETE | REVOKED | FAILED
    val lastError: String?,                        // last error, sanitized
)
